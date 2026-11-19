package com.mcpanel.web;

import com.mcpanel.config.ConfigManager;
import com.mcpanel.mc.ServerProcess;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class DashboardHandler {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern XMX_PATTERN = Pattern.compile("-Xmx(\\d+)([gGmMkK])");

    public static class StatPoint {
        public long time;
        public double cpu;
        public double mem;
        public long memTotal;
        public long netIn;
        public long netOut;
    }

    public static class InstanceState {
        public LinkedList<StatPoint> live = new LinkedList<>();
        public List<StatPoint> history = new ArrayList<>();
        public long lastProcCpuTime = -1;
        public long lastProcCpuCalcTime = 0;
        public long lastProcInBytes = -1;
        public long lastProcOutBytes = -1;
        public long lastNetTime = 0;
        public long lastRecordMinute = 0;
        public long lastMemTotal = 0;
        
        public long cachedDiskUsed = 0;
        public long cachedDiskAt = 0;
        public boolean diskCalculating = false;
    }

    private static final Map<String, InstanceState> stateMap = new ConcurrentHashMap<>();
    
    static {
        ScheduledExecutorService ses = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "global-dashboard-sampler");
            t.setDaemon(true);
            return t;
        });
        
        ses.scheduleAtFixedRate(() -> {
            try {
                com.mcpanel.instance.InstanceRuntimeManager mgr = com.mcpanel.instance.InstanceRuntimeManager.getInstance();
                for (com.mcpanel.instance.InstanceRuntime rt : mgr.all()) {
                    String instanceId = rt.inst.id;
                    InstanceState st = stateMap.computeIfAbsent(instanceId, k -> {
                        InstanceState newSt = new InstanceState();
                        loadHistory(instanceId, rt.config, newSt);
                        return newSt;
                    });
                    sampleAndSave(instanceId, rt.mcServer, rt.config, st);
                }
            } catch (Throwable ignored) {}
        }, 1, 1, TimeUnit.SECONDS);
    }

    private static void sampleAndSave(String instanceId, ServerProcess mcServer, ConfigManager config, InstanceState st) {
        StatPoint pt = new StatPoint();
        pt.time = System.currentTimeMillis();

        boolean running = false;
        long pid = -1;
        try {
            running = mcServer != null && mcServer.isRunning();
            pid = running ? mcServer.getPid() : -1;
        } catch (Throwable ignored) {}

        if (st.lastMemTotal == 0) {
            String type = config.get("_instance.type", "mc");
            String startCmd = "velocity".equals(type) ? config.get("velocity.start.command", "") : config.get("mc.start.command", "");
            st.lastMemTotal = parseMaxHeap(startCmd, config);
            if (st.lastMemTotal == 0) st.lastMemTotal = 2L * 1024 * 1024 * 1024;
        }

        if (!running || pid <= 0) {
            // ★ 核心修复：只有确认停止（!running）才重置基准。只是没抓到 PID 的话，保留上一次的 CPU 基准
            if (!running) {
                st.lastProcCpuTime = -1;
                st.lastProcInBytes = -1;
            }
            pt.cpu = 0.0;
            pt.mem = 0.0;
            pt.memTotal = st.lastMemTotal;
            pt.netIn = 0L;
            pt.netOut = 0L;
        } else {
            pt.mem = readProcessMemory(pid);
            if (pt.mem < 0) pt.mem = 0;

            pt.memTotal = st.lastMemTotal;
            if (pt.mem > pt.memTotal) pt.mem = pt.memTotal;

            long[] netBytes = readProcessNetBytes(pid);
            long now = System.currentTimeMillis();
            
            if (st.lastNetTime > 0 && now > st.lastNetTime && st.lastProcInBytes != -1 && st.lastProcOutBytes != -1) {
                double secs = (now - st.lastNetTime) / 1000.0;
                pt.netIn = (long) (Math.max(0, netBytes[0] - st.lastProcInBytes) / secs);
                pt.netOut = (long) (Math.max(0, netBytes[1] - st.lastProcOutBytes) / secs);
            } else {
                pt.netIn = 0L;
                pt.netOut = 0L;
            }
            st.lastProcInBytes = netBytes[0];
            st.lastProcOutBytes = netBytes[1];
            st.lastNetTime = now;
            
            pt.cpu = readProcessCpu(pid, st);
            if (pt.cpu < 0) pt.cpu = 0;
            if (pt.cpu > 100) pt.cpu = 100;
        }

        synchronized (st.live) {
            st.live.add(pt);
            if (st.live.size() > 60) st.live.removeFirst();
            saveToFile(config, instanceId, "live_history.json", st.live);
        }

        long minute = System.currentTimeMillis() / 60000;
        if (minute > st.lastRecordMinute) {
            st.lastRecordMinute = minute;
            synchronized (st.history) {
                st.history.add(pt);
                long cutoff = System.currentTimeMillis() - 7 * 24 * 3600 * 1000L;
                st.history.removeIf(p -> p.time < cutoff);
                saveToFile(config, instanceId, "history.json", st.history);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void loadHistory(String instanceId, ConfigManager config, InstanceState st) {
        try {
            File f1 = new File(config.getRootDir(), "data/" + instanceId + "/live_history.json");
            if (f1.exists()) {
                List<StatPoint> loaded = MAPPER.readValue(f1, new com.fasterxml.jackson.core.type.TypeReference<List<StatPoint>>(){});
                st.live.clear();
                for (StatPoint pt : loaded) {
                    fixOldData(pt);
                    st.live.add(pt);
                }
            }
            File f2 = new File(config.getRootDir(), "data/" + instanceId + "/history.json");
            if (f2.exists()) {
                List<StatPoint> loaded = MAPPER.readValue(f2, new com.fasterxml.jackson.core.type.TypeReference<List<StatPoint>>(){});
                st.history.clear();
                for (StatPoint pt : loaded) {
                    fixOldData(pt);
                    st.history.add(pt);
                }
            }
        } catch (Exception ignored) {}
    }

    private static void fixOldData(StatPoint pt) {
        if (pt.mem > 0 && pt.mem < 100000) pt.mem = pt.mem * 1024 * 1024;
        if (pt.memTotal <= 0) {
            if (pt.mem > 0) pt.memTotal = (long) (Math.ceil(pt.mem / 1024.0 / 1024.0 / 1024.0) * 1024L * 1024L * 1024L);
            else pt.memTotal = 2L * 1024 * 1024 * 1024;
        }
        if (pt.cpu > 100) pt.cpu = 100;
        if (pt.cpu < 0) pt.cpu = 0;
        if (pt.netIn < 0) pt.netIn = 0;
        if (pt.netOut < 0) pt.netOut = 0;
    }

    private static synchronized void saveToFile(ConfigManager config, String instanceId, String fileName, List<StatPoint> list) {
        try {
            File f = new File(config.getRootDir(), "data/" + instanceId + "/" + fileName);
            if (f.getParentFile() != null) f.getParentFile().mkdirs();
            MAPPER.writeValue(f, list);
        } catch (Exception ignored) {}
    }

    private final ServerProcess mcServer;
    private final ConfigManager config;
    private final String instanceId;

    public DashboardHandler(ServerProcess mcServer, ConfigManager config) {
        this.mcServer = mcServer;
        this.config = config;
        this.instanceId = config.get("_instance.id", "default");
    }

    public void handle(HttpServerExchange exchange) {
        String path = exchange.getRequestPath();
        if (path.equals("/api/dashboard/history")) {
            handleHistory(exchange);
            return;
        }

        Map<String, Object> data = new HashMap<>();
        boolean running = false;
        long pid = -1;
        
        try {
            running = mcServer.isRunning();
            data.put("running", running);
            data.put("starting", mcServer.isStarting());
            data.put("installing", mcServer.isInstalling());
            data.put("needsEula", mcServer.needsEula);
            data.put("state", mcServer.getStateString());
            pid = mcServer.getPid();
            data.put("pid", pid);
            data.put("uptime", mcServer.getUptime());
        } catch (Throwable e) {
            data.put("running", false);
            data.put("starting", false);
            data.put("installing", false);
            data.put("needsEula", false);
            data.put("state", "stopped");
            data.put("pid", -1L);
            data.put("uptime", 0L);
        }

        InstanceState st = stateMap.computeIfAbsent(instanceId, k -> {
            InstanceState newSt = new InstanceState();
            loadHistory(instanceId, config, newSt);
            return newSt;
        });
        
        StatPoint latest = st.live.isEmpty() ? new StatPoint() : st.live.getLast();
        data.put("cpuLoad", latest.cpu);
        data.put("memUsed", (long)latest.mem);
        data.put("memTotal", latest.memTotal);
        data.put("netIn", latest.netIn);
        data.put("netOut", latest.netOut);

        String type = config.get("_instance.type", "mc");
        data.put("instanceType", type);
        
        data.put("diskUsed", computeDiskUsed(st));

        try {
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
            exchange.getResponseSender().send(MAPPER.writeValueAsString(data));
        } catch (Throwable e) {
            exchange.setStatusCode(500);
            exchange.getResponseSender().send("{\"error\":\"internal\"}");
        }
    }
    
    private long computeDiskUsed(InstanceState st) {
        long now = System.currentTimeMillis();
        if (st.diskCalculating) return st.cachedDiskUsed;
        if (now - st.cachedDiskAt < 60_000) return st.cachedDiskUsed;
        
        st.diskCalculating = true;
        st.cachedDiskAt = now;
        
        new Thread(() -> {
            long used = 0;
            try {
                String dirKey = "velocity".equals(config.get("_instance.type", "mc")) ? "velocity.server.dir" : "mc.server.dir";
                Path serverDir = config.resolve(config.get(dirKey, "server")).toPath().toAbsolutePath().normalize();
                if (Files.exists(serverDir)) {
                    try (Stream<Path> s = Files.walk(serverDir)) {
                        used = s.filter(Files::isRegularFile).mapToLong(p -> {
                            try { return Files.size(p); } catch (Throwable e) { return 0; }
                        }).sum();
                    }
                }
            } catch (Throwable ignored) {}
            st.cachedDiskUsed = used;
            st.diskCalculating = false;
        }).start();

        return st.cachedDiskUsed;
    }

    private void handleHistory(HttpServerExchange exchange) {
        try {
            String range = exchange.getQueryParameters().getOrDefault("range", new ArrayDeque<>(Collections.singletonList("day"))).peek();
            InstanceState st = stateMap.computeIfAbsent(instanceId, k -> {
                InstanceState newSt = new InstanceState();
                loadHistory(instanceId, config, newSt);
                return newSt;
            });
            
            if ("live".equals(range)) {
                List<StatPoint> result;
                synchronized (st.live) { result = new ArrayList<>(st.live); }
                exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
                exchange.getResponseSender().send(MAPPER.writeValueAsString(result));
                return;
            }
            
            long now = System.currentTimeMillis();
            long cutoff = now - ("week".equals(range) ? 7 * 24 * 3600 * 1000L : 24 * 3600 * 1000L);
            List<StatPoint> filtered = new ArrayList<>();
            synchronized (st.history) {
                for (StatPoint pt : st.history) {
                    if (pt.time >= cutoff) filtered.add(pt);
                }
            }
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
            exchange.getResponseSender().send(MAPPER.writeValueAsString(filtered));
        } catch (Exception e) {
            exchange.setStatusCode(500);
            exchange.getResponseSender().send("[]");
        }
    }

    private static long[] readProcessNetBytes(long pid) {
        long totalIn = 0, totalOut = 0;
        try {
            Process p = new ProcessBuilder("ss", "-tinp").redirectErrorStream(true).start();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                boolean matchPid = false;
                while ((line = br.readLine()) != null) {
                    if (line.contains("pid=" + pid + ",") || line.contains("pid=" + pid + ")")) matchPid = true;
                    if (matchPid) {
                        Matcher mIn = Pattern.compile("bytes_received:(\\d+)").matcher(line);
                        if (mIn.find()) totalIn += Long.parseLong(mIn.group(1));
                        Matcher mOut = Pattern.compile("bytes_sent:(\\d+)").matcher(line);
                        if (mOut.find()) totalOut += Long.parseLong(mOut.group(1));
                    }
                    if (line.startsWith("ESTAB") || line.startsWith("LISTEN") || line.startsWith("TIME-WAIT") || line.trim().isEmpty()) matchPid = false;
                }
            }
            p.waitFor();
        } catch (Throwable ignored) {}
        return new long[]{totalIn, totalOut};
    }

    private static long parseMaxHeap(String cmd, ConfigManager config) {
        if (cmd != null) {
            Matcher m = XMX_PATTERN.matcher(cmd);
            if (m.find()) return matchToBytes(m);
        }
        try {
            File serverDir = config.resolve(config.get("mc.server.dir", "server"));
            File argsFile = new File(serverDir, "user_jvm_args.txt");
            if (argsFile.exists()) {
                String content = new String(Files.readAllBytes(argsFile.toPath()), StandardCharsets.UTF_8);
                Matcher m2 = XMX_PATTERN.matcher(content);
                if (m2.find()) return matchToBytes(m2);
            }
        } catch (Throwable ignored) {}
        return 0;
    }

    private static long matchToBytes(Matcher m) {
        try {
            long v = Long.parseLong(m.group(1));
            char u = m.group(2).toLowerCase().charAt(0);
            if (u == 'g') return v * 1024L * 1024L * 1024L;
            if (u == 'm') return v * 1024L * 1024L;
            if (u == 'k') return v * 1024L;
        } catch (Throwable ignored) {}
        return 0;
    }

    private static long readProcessMemory(long pid) {
        try {
            Path p = Paths.get("/proc/" + pid + "/status");
            if (!Files.exists(p)) return 0;
            for (String line : Files.readAllLines(p)) {
                if (line.startsWith("VmRSS:")) {
                    String[] parts = line.trim().split("\\s+");
                    return Long.parseLong(parts[1]) * 1024L;
                }
            }
        } catch (Throwable ignored) {}
        return 0;
    }

    private static double readProcessCpu(long pid, InstanceState st) {
        if (pid <= 0) return 0;
        try {
            Path statPath = Paths.get("/proc/" + pid + "/stat");
            if (!Files.exists(statPath)) return 0;
            String stat = new String(Files.readAllBytes(statPath), StandardCharsets.UTF_8);
            int rp = stat.lastIndexOf(')');
            if (rp < 0 || rp + 2 >= stat.length()) return 0;
            String[] parts = stat.substring(rp + 2).trim().split("\\s+");
            if (parts.length < 13) return 0;
            long utime = Long.parseLong(parts[11]);
            long stime = Long.parseLong(parts[12]);
            long totalTime = utime + stime;
            long now = System.currentTimeMillis();
            if (st.lastProcCpuTime == -1) { 
                st.lastProcCpuTime = totalTime; 
                st.lastProcCpuCalcTime = now; 
                return 0; 
            }
            long diffTime = totalTime - st.lastProcCpuTime;
            long diffMs = now - st.lastProcCpuCalcTime;
            st.lastProcCpuTime = totalTime; 
            st.lastProcCpuCalcTime = now;
            if (diffMs <= 0) return 0;
            // Linux jiffies 1 tick 通常为 10ms，所以 diffTime * 10 是实际使用的毫秒数
            return Math.min(100.0, ((diffTime * 10.0) / diffMs) * 100.0);
        } catch (Throwable e) {
            return 0;
        }
    }
}