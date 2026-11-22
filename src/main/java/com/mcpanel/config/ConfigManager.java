package com.mcpanel.config;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

public class ConfigManager {
    private static ConfigManager globalInstance;
    private final File rootDir;
    private final File configFile;
    private final boolean isGlobal;
    private final Properties props = new Properties();
    private final Map<String, String> runtime = new ConcurrentHashMap<>();

    public static synchronized ConfigManager getInstance() {
        if (globalInstance == null) {
            globalInstance = new ConfigManager(new File("."), true);
        }
        return globalInstance;
    }

    public ConfigManager(File rootDir) { 
        this(rootDir, false); 
    }

    public ConfigManager(File rootDir, boolean isGlobal) {
        File resolvedRoot;
        try { 
            resolvedRoot = rootDir.getCanonicalFile(); 
        } catch (IOException e) { 
            resolvedRoot = rootDir.getAbsoluteFile(); 
        }
        this.rootDir = resolvedRoot;
        this.isGlobal = isGlobal;
        this.configFile = new File(this.rootDir, "config.properties");
    }

    public File getRootDir() { 
        return rootDir; 
    }

    public File resolve(String rel) {
        if (rel == null || rel.isEmpty()) return rootDir;
        File f = new File(rel);
        if (f.isAbsolute()) {
            throw new SecurityException("绝对路径不被允许: " + rel);
        }
        try {
            Path rootPath = rootDir.toPath().normalize().toAbsolutePath();
            Path targetPath = rootPath.resolve(rel).normalize().toAbsolutePath();
            if (!targetPath.startsWith(rootPath)) {
                throw new SecurityException("检测到路径逃逸 (Path traversal): " + rel);
            }
            return targetPath.toFile();
        } catch (Exception e) {
            throw new SecurityException("路径解析失败: " + rel, e);
        }
    }

    public void init() {
        if (!configFile.exists()) save();
        try (InputStream in = new FileInputStream(configFile)) {
            props.load(in);
        } catch (IOException e) {
            throw new RuntimeException("无法读取配置文件: " + configFile.getPath(), e);
        }
        ensureDefaults();
    }

    private void ensureDefaults() {
        if (!isGlobal) return;

        Map<String, String> defaults = new LinkedHashMap<>();
        defaults.put("web.port", "8080");
        defaults.put("share.port", "33333");
        defaults.put("panel.host", "127.0.0.1");
        defaults.put("panel.domain", "");
        // 补全 SSH/SFTP 的端口配置
        defaults.put("remote.ssh.port", "22");
        defaults.put("remote.sftp.port", "22");
        defaults.put("web.session.timeout.hours", "360");
        defaults.put("security.no_sandbox.lock", "true");

        boolean changed = false;
        for (Map.Entry<String, String> e : defaults.entrySet()) {
            if (!props.containsKey(e.getKey())) {
                props.setProperty(e.getKey(), e.getValue());
                changed = true;
            }
        }
        // 一次性迁移：老版本默认 720 小时（30 天），新版本默认 360 小时（15 天）。
        // 用 "_" 前缀的隐藏键做标记（不会出现在配置页），避免每次启动把用户改过的值重置。
        if (!props.containsKey("_session.timeout.migrated")) {
            if ("720".equals(props.getProperty("web.session.timeout.hours"))) {
                props.setProperty("web.session.timeout.hours", "360");
            }
            props.setProperty("_session.timeout.migrated", "1");
            changed = true;
        }
        if (changed) save();
    }

    public String get(String key) { 
        return get(key, ""); 
    }

    public String get(String key, String def) {
        String r = runtime.get(key);
        if (r != null) return r;
        return props.getProperty(key, def);
    }

    public boolean getBool(String key, boolean def) {
        return "true".equalsIgnoreCase(get(key, String.valueOf(def)));
    }

    public int getInt(String key, int def) {
        try { 
            return Integer.parseInt(get(key, String.valueOf(def))); 
        } catch (Exception e) { 
            return def; 
        }
    }

    public boolean has(String key) {
        return runtime.containsKey(key) || props.containsKey(key);
    }

    public Map<String, String> getAll() {
        Map<String, String> map = new java.util.HashMap<>();
        for (String key : props.stringPropertyNames()) {
            if (key.startsWith("_")) continue;
            map.put(key, props.getProperty(key));
        }
        for (Map.Entry<String, String> e : runtime.entrySet()) {
            if (e.getKey().startsWith("_")) continue;
            map.put(e.getKey(), e.getValue());
        }
        return map;
    }

    // ★ 修复：Properties 继承 Hashtable，单次 get/put 是线程安全的，但 save() 里 store()
    // 会边遍历边写，其它线程同时 set/remove 会抛 ConcurrentModificationException
    //（配置文件写失败，设置丢失）。写操作统一与 save() 用同一把对象锁。
    public synchronized void set(String key, String value) {
        runtime.remove(key);
        props.setProperty(key, value);
        save();
    }

    public synchronized void remove(String key) {
        runtime.remove(key);
        if (props.containsKey(key)) {
            props.remove(key);
            save();
        }
    }

    public void setRuntime(String key, String value) {
        if (value == null) runtime.remove(key);
        else runtime.put(key, value);
    }

    public void clearRuntime(String key) { 
        runtime.remove(key); 
    }

    public synchronized void save() {
        File tmp = new File(configFile.getParentFile(), configFile.getName() + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            props.store(out, "MC Panel Configuration");
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }
        try {
            Files.move(tmp.toPath(), configFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            try {
                configFile.setReadable(false, false);
                configFile.setReadable(true, true);
                configFile.setWritable(false, false);
                configFile.setWritable(true, true);
            } catch (Exception ignored) {}
        } catch (IOException e) {
            try {
                Files.move(tmp.toPath(), configFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                configFile.setReadable(false, false);
                configFile.setReadable(true, true);
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        }
    }
}