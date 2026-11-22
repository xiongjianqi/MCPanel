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
        public double netInRate = 0;
        public double netOutRate = 0;
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
        
        final long[] lastThreadCheck = {0L};
        ses.scheduleAtFixedRate(() -> {
            try {
                long nowTs = System.currentTimeMillis();
                if (nowTs - lastThreadCheck[0] > 60_000) {
                    lastThreadCheck[0] = nowTs;
                    checkThreadPressure();
                }
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

    // 线程数偏高时输出一条诊断日志（按线程名前缀聚合），方便定位是谁在漏线程
    // 线程接近系统/容器上限时，跳过可选的扫描，避免把最后一根线程也用掉
    private static boolean threadPressureHigh() {
        try {
            return java.lang.management.ManagementFactory.getThreadMXBean().getThreadCount() > 200;
        } catch (Throwable t) {
            return false;
        }
    }

    private static volatile int lastThreadCount = 0;
    private static volatile long lastThreadAt = 0L;

    private static void checkThreadPressure() {
        try {
            java.lang.management.ThreadMXBean mx = java.lang.management.ManagementFactory.getThreadMXBean();
            int n = mx.getThreadCount();
            long now = System.currentTimeMillis();
            int prev = lastThreadCount;
            long prevAt = lastThreadAt;
            lastThreadCount = n;
            lastThreadAt = now;
            double perMinute = (prevAt > 0 && now > prevAt) ? (n - prev) * 60000.0 / (now - prevAt) : 0.0;

            // 绝对数量偏高，或者虽然还没到 80 但增长很快（慢性泄漏的典型特征）都记一笔
            if (n < 80 && perMinute < 5.0) return;

            java.lang.management.ThreadInfo[] infos = mx.dumpAllThreads(false, false);
            Map<String, Integer> counts = new HashMap<>();
            for (java.lang.management.ThreadInfo ti : infos) {
                String name = ti.getThreadName();
                int idx = Math.max(name.lastIndexOf('-'), name.lastIndexOf('#'));
                if (idx > 0) name = name.substring(0, idx);
                counts.merge(name, 1, Integer::sum);
            }
            StringBuilder sb = new StringBuilder();
            counts.entrySet().stream()
                    .sorted((a, b) -> b.getValue() - a.getValue())
                    .limit(8)
                    .forEach(e -> sb.append(e.getKey()).append('×').append(e.getValue()).append("  "));
            System.err.println("[Panel] ⚠ 线程数偏高: " + n + "（较上次 "
                    + (prevAt > 0 ? String.valueOf(n - prev) : "?") + "，约 "
                    + String.format(java.util.Locale.ROOT, "%.1f", perMinute) + "/分钟；阻塞型任务 "
                    + com.mcpanel.util.Async.workerCount() + "/"
                    + com.mcpanel.util.Async.maxWorkers() + "） | " + sb
                    + " | " + com.mcpanel.util.ProcStats.summary());

            // 把线程数超过 100 的实例单独列出来：多出来的线程到底挂在哪个服务端 JVM 上一眼可见
            StringBuilder inst = new StringBuilder();
            try {
                for (com.mcpanel.instance.InstanceRuntime rt
                        : com.mcpanel.instance.InstanceRuntimeManager.getInstance().all()) {
                    long pid = rt.mcServer == null ? -1 : rt.mcServer.getPid();
                    if (pid <= 0) continue;
                    int threads = com.mcpanel.util.ProcStats.threadsOf(pid);
                    if (threads < 100) continue;
                    if (inst.length() > 0) inst.append(", ");
                    inst.append(rt.inst.id).append('=').append(threads);
                }
            } catch (Throwable ignored) {}
            if (inst.length() > 0) System.err.println("[Panel] 实例线程: " + inst);

            // 增长很快时把具体线程名打出来（最多 40 条），足够定位到是哪个组件在漏线程
            if (perMinute >= 20.0) {
                StringBuilder detail = new StringBuilder();
                int shown = 0;
                for (java.lang.management.ThreadInfo ti : infos) {
                    if (shown++ >= 40) break;
                    detail.append("\n  - ").append(ti.getThreadName())
                          .append(" [").append(ti.getThreadState()).append(']');
                }
                System.err.println("[Panel] 线程明细:" + detail);
            }
        } catch (Throwable ignored) {}
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
                st.lastProcOutBytes = -1;
                st.lastNetTime = 0;
                st.netInRate = 0;
                st.netOutRate = 0;
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

            // 网络采样走全进程共享快照：最多 5 秒采集一次，不再每个实例每秒拉一个 ss 子进程
            if (!threadPressureHigh()) {
                long[] netBytes = processNetSnapshot().get(pid);
                long netAt = netSnapshotAt;
                if (netBytes != null && netAt > st.lastNetTime) {
                    if (st.lastNetTime > 0 && st.lastProcInBytes >= 0 && st.lastProcOutBytes >= 0) {
                        double secs = (netAt - st.lastNetTime) / 1000.0;
                        if (secs > 0) {
                            st.netInRate = Math.max(0, netBytes[0] - st.lastProcInBytes) / secs;
                            st.netOutRate = Math.max(0, netBytes[1] - st.lastProcOutBytes) / secs;
                        }
                    }
                    st.lastProcInBytes = netBytes[0];
                    st.lastProcOutBytes = netBytes[1];
                    st.lastNetTime = netAt;
                }
            }
            pt.netIn = (long) st.netInRate;
            pt.netOut = (long) st.netOutRate;
            
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
            data.put("stopping", mcServer.isStopping());
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
    
    // 磁盘扫描统一交给一条常驻线程：以前每次请求都可能 new Thread，
    // 在低配/小内存环境下会逐步耗尽原生线程（unable to create native thread）。
    private static final java.util.concurrent.ExecutorService DISK_POOL =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "disk-scan");
                t.setDaemon(true);
                return t;
            });

    private long computeDiskUsed(InstanceState st) {
        long now = System.currentTimeMillis();
        if (st.diskCalculating) return st.cachedDiskUsed;
        if (now - st.cachedDiskAt < 60_000) return st.cachedDiskUsed;
        
        if (threadPressureHigh()) return st.cachedDiskUsed;
        st.diskCalculating = true;
        st.cachedDiskAt = now;
        
        try {
            DISK_POOL.execute(() -> {
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
            });
        } catch (Throwable t) {
            st.diskCalculating = false; // 线程池不可用时不要卡住后续刷新
        }

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

    // ==================== 网络采样（共享快照） ====================
    // ss 每次都会创建一个子进程，而 JVM 每个子进程都会占一条 process reaper 线程。
    // 旧实现是"每个运行中的实例每秒启动一次 ss"：实例越多，线程/进程越涨。
    // 现在全进程共享，最多每 5 秒采集一次，所有实例读同一份快照。
    private static final long NET_SAMPLE_INTERVAL_MS = 5000L;
    private static final Object NET_LOCK = new Object();
    private static volatile long netSnapshotAt = 0L;
    private static volatile Map<Long, long[]> netSnapshot = Collections.emptyMap();
    private static final Pattern SS_PID = Pattern.compile("pid=(\\d+)");
    private static final Pattern SS_IN = Pattern.compile("bytes_received:(\\d+)");
    private static final Pattern SS_OUT = Pattern.compile("bytes_sent:(\\d+)");

    private static Map<Long, long[]> processNetSnapshot() {
        long now = System.currentTimeMillis();
        if (now - netSnapshotAt < NET_SAMPLE_INTERVAL_MS) return netSnapshot;
        synchronized (NET_LOCK) {
            long startedAt = System.currentTimeMillis();
            if (startedAt - netSnapshotAt < NET_SAMPLE_INTERVAL_MS) return netSnapshot;
            Map<Long, long[]> map = new HashMap<>();
            Process p = null;
            try {
                p = new ProcessBuilder("ss", "-tinp").redirectErrorStream(true).start();
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    long curPid = -1;
                    while ((line = br.readLine()) != null) {
                        if (line.startsWith("ESTAB") || line.startsWith("LISTEN")
                                || line.startsWith("TIME-WAIT") || line.startsWith("CLOSE-WAIT")
                                || line.startsWith("FIN-WAIT") || line.trim().isEmpty()) {
                            curPid = -1;
                        }
                        if (curPid <= 0) {
                            Matcher pm = SS_PID.matcher(line);
                            if (pm.find()) {
                                try { curPid = Long.parseLong(pm.group(1)); } catch (Exception ignored) {}
                            }
                        }
                        if (curPid > 0) {
                            long[] acc = map.get(curPid);
                            if (acc == null) {
                                acc = new long[2];
                                map.put(curPid, acc);
                            }
                            Matcher mi = SS_IN.matcher(line);
                            if (mi.find()) {
                                try { acc[0] += Long.parseLong(mi.group(1)); } catch (Exception ignored) {}
                            }
                            Matcher mo = SS_OUT.matcher(line);
                            if (mo.find()) {
                                try { acc[1] += Long.parseLong(mo.group(1)); } catch (Exception ignored) {}
                            }
                        }
                    }
                }
                netSnapshot = map;
            } catch (Throwable ignored) {
                // ss 不存在或读取失败：保留旧快照，5 秒后重试
            } finally {
                if (p != null && p.isAlive()) {
                    try { p.destroyForcibly(); } catch (Exception ignored) {}
                }
            }
            netSnapshotAt = System.currentTimeMillis();
            return netSnapshot;
        }
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