package com.mcpanel.instance;

import com.mcpanel.util.LinuxUserManager;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class InstanceManager {
    private static InstanceManager instance;
    private static final String ROOT = "instances";
    private static final int MC_PORT_START = 25565;
    private static final int VELOCITY_PORT_START = 25577;

    private final Map<String, Instance> instances = new ConcurrentHashMap<>();

    public static synchronized InstanceManager getInstance() {
        if (instance == null) instance = new InstanceManager();
        return instance;
    }

    public static String detectType(java.io.File serverDir) {
        if (serverDir == null || !serverDir.isDirectory()) return "mc";
        java.io.File[] jars = serverDir.listFiles(
                (d, n) -> n.toLowerCase().endsWith(".jar"));
        if (jars == null || jars.length == 0) return "mc";
        for (java.io.File jar : jars) {
            if (jar.getName().toLowerCase().startsWith("velocity")) return "velocity";
        }
        return "mc";
    }

    public synchronized void init() {
        try {
            Files.createDirectories(Paths.get(ROOT));
        } catch (IOException e) {
            System.err.println("[Instance] 无法创建 instances 目录: " + e.getMessage());
            return;
        }
        scanInstances();
    }

    private void scanInstances() {
        Path root = Paths.get(ROOT);
        if (!Files.isDirectory(root)) return;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(root)) {
            for (Path p : ds) {
                if (!Files.isDirectory(p)) continue;
                String id = p.getFileName().toString();
                Instance inst = new Instance();
                inst.id = id;
                inst.loadMeta();
                if (inst.type == null || inst.type.isEmpty()) {
                    inst.type = detectType(inst.getServerDir());
                    inst.saveMeta();
                }
                instances.put(id, inst);
            }
        } catch (IOException e) {
            System.err.println("[Instance] 扫描实例失败: " + e.getMessage());
        }
    }

    public List<Instance> listInstances() {
        List<Instance> list = new ArrayList<>(instances.values());
        list.sort(Comparator.comparingLong((Instance a) -> a.createdAt));
        return list;
    }

    public Instance getInstance(String id) {
        if (id == null) return null;
        return instances.get(id);
    }

    private Set<Integer> collectUsedPorts() {
        Set<Integer> used = new HashSet<>();
        used.add(8080);
        used.add(33333);

        for (Instance inst : instances.values()) {
            try {
                java.util.Properties props = new java.util.Properties();
                Path cfg = Paths.get(ROOT, inst.id, "config.properties");
                if (Files.exists(cfg)) {
                    try (InputStream in = Files.newInputStream(cfg)) {
                        props.load(in);
                    }
                }
                addPort(used, props.getProperty("mc.port", ""));
                addPort(used, props.getProperty("velocity.port", ""));
            } catch (Exception ignored) {}
        }
        return used;
    }

    private void addPort(Set<Integer> used, String s) {
        if (s == null || s.isEmpty()) return;
        try { used.add(Integer.parseInt(s)); } catch (NumberFormatException ignored) {}
    }

    private int pickFreePort(Set<Integer> used, int start) {
        int p = start;
        while (used.contains(p)) {
            p++;
            if (p > 65535) throw new RuntimeException("没有可用端口（起始 " + start + "）");
        }
        return p;
    }

    public synchronized Instance createInstance(String name, String desc) throws Exception {
        return createInstance(name, desc, "mc");
    }

    public synchronized Instance createInstance(String name, String desc, String type) throws Exception {
        String id = generateId();
        Path dir = Paths.get(ROOT, id);
        Files.createDirectories(dir);
        Files.createDirectories(dir.resolve("server"));
        Files.createDirectories(dir.resolve("backup"));
        Files.createDirectories(dir.resolve("plugins"));
        Files.createDirectories(dir.resolve("plugins-data"));
        Files.createDirectories(dir.resolve("logs"));

        Instance inst = new Instance();
        inst.id = id;
        inst.name = (name == null || name.isEmpty()) ? id : name;
        inst.desc = (desc == null) ? "" : desc;
        inst.bg = null;
        inst.type = (type == null || type.isEmpty()) ? "mc" : type;
        inst.createdAt = System.currentTimeMillis();
        inst.saveMeta();

        java.util.Properties props = new java.util.Properties();
        Set<Integer> usedPorts = collectUsedPorts();

        if ("velocity".equalsIgnoreCase(inst.type)) {
            props.setProperty("velocity.start.command", "java -Xms512M -Xmx512M -jar velocity.jar");
            props.setProperty("velocity.server.dir", "server");
            props.setProperty("velocity.auto.start", "true");
            props.setProperty("terminal.shutdown.command", "shutdown");
            props.setProperty("terminal.restart.delay", "5000");
            props.setProperty("terminal.auto.restart", "false");
            int port = pickFreePort(usedPorts, VELOCITY_PORT_START);
            props.setProperty("velocity.port", String.valueOf(port));
        } else {
            Path defaultCfg = Paths.get(ROOT, "default", "config.properties");
            if (Files.exists(defaultCfg)) {
                try (InputStream in = Files.newInputStream(defaultCfg)) {
                    props.load(in);
                }
            } else {
                props.setProperty("mc.start.command", "java -Xmx2G -jar server.jar nogui");
                props.setProperty("mc.server.dir", "server");
                props.setProperty("mc.auto.start", "true");
                props.setProperty("terminal.shutdown.command", "stop");
                props.setProperty("terminal.restart.delay", "5000");
                props.setProperty("terminal.auto.restart", "false");
            }
            props.remove("mc.port.forward.from");
            props.remove("mc.port.forward.to");
            props.remove("mc.port.random.enabled");
            int port = pickFreePort(usedPorts, MC_PORT_START);
            props.setProperty("mc.port", String.valueOf(port));
            if (!props.containsKey("mc.auto.start")) {
                props.setProperty("mc.auto.start", "true");
            }
        }

        try (OutputStream out = Files.newOutputStream(dir.resolve("config.properties"))) {
            props.store(out, "Instance " + id + " (" + inst.type + ")");
        }

        instances.put(id, inst);
        System.out.println("[Instance] 已创建 " + inst.type + " 实例: " + id + " (" + inst.name + ")");
        return inst;
    }

    public synchronized boolean deleteInstance(String id) {
        Instance inst = instances.get(id);
        if (inst == null) return false;
        try {
            // ★ 安全修复：同步清理系统隔离用户
            LinuxUserManager.deleteInstanceUser(inst);

            deleteRecursive(Paths.get(ROOT, id));
            instances.remove(id);
            System.out.println("[Instance] 已删除实例: " + id);
            return true;
        } catch (IOException e) {
            System.err.println("[Instance] 删除实例失败: " + e.getMessage());
            return false;
        }
    }

    private String generateId() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd-HHmmss");
        String base = "inst-" + sdf.format(new Date());
        String id = base;
        int n = 1;
        while (instances.containsKey(id) || Files.exists(Paths.get(ROOT, id))) {
            id = base + "-" + n++;
        }
        return id;
    }

    private void deleteRecursive(Path p) throws IOException {
        if (Files.isDirectory(p)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(p)) {
                for (Path c : ds) deleteRecursive(c);
            }
        }
        Files.deleteIfExists(p);
    }
}