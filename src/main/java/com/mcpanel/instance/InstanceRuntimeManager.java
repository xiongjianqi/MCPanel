package com.mcpanel.instance;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class InstanceRuntimeManager {
    private static InstanceRuntimeManager instance;

    private final Map<String, InstanceRuntime> runtimes = new ConcurrentHashMap<>();

    public static InstanceRuntimeManager getInstance() {
        if (instance == null) {
            synchronized (InstanceRuntimeManager.class) {
                if (instance == null) instance = new InstanceRuntimeManager();
            }
        }
        return instance;
    }

    public void initAll() {
        List<Instance> list = InstanceManager.getInstance().listInstances();
        for (Instance inst : list) {
            try {
                InstanceRuntime rt = new InstanceRuntime(inst);
                runtimes.put(inst.id, rt);
            } catch (Exception e) {
                System.err.println("[Panel] 实例 " + inst.id + " 初始化失败: " + e.getMessage());
            }
        }

        if (list.isEmpty()) {
            System.out.println("[Panel] 没有实例");
            return;
        }

        // ★ 核心修复：异步并发启动实例，不再阻塞主线程
        for (InstanceRuntime rt : runtimes.values()) {
            com.mcpanel.util.Async.run(() -> {
                try {
                    rt.start();
                } catch (Exception e) {
                    System.err.println("[Panel] 启动实例 " + rt.inst.id + " 失败: " + e.getMessage());
                }
            });
        }

        System.out.println("[Panel] 共 " + runtimes.size() + " 个实例运行时");
    }

    public InstanceRuntime get(String id) {
        if (id == null) return null;
        return runtimes.get(id);
    }

    public Collection<InstanceRuntime> all() {
        return runtimes.values();
    }

    public InstanceRuntime addRuntime(Instance inst) {
        if (runtimes.containsKey(inst.id)) return runtimes.get(inst.id);
        try {
            InstanceRuntime rt = new InstanceRuntime(inst);
            runtimes.put(inst.id, rt);
            return rt;
        } catch (Exception e) {
            System.err.println("[Panel] 创建实例运行时失败: " + e.getMessage());
            return null;
        }
    }

    public void removeRuntime(String id) {
        InstanceRuntime rt = runtimes.remove(id);
        if (rt == null) return;
        try {
            rt.close();
        } catch (Exception ignored) {}
    }

    public void startInstance(String id) {
        InstanceRuntime rt = runtimes.get(id);
        if (rt == null) return;
        com.mcpanel.util.Async.run(() -> {
            try { rt.start(); } catch (Exception e) {}
        });
    }

    public void clearAllLogs() {
        for (InstanceRuntime rt : runtimes.values()) {
            try { rt.terminalHandler.clearHistory(); } catch (Exception e) {}
        }
    }

    /** 等待每个实例的终端日志队列写盘完成（最长 totalTimeoutMs） */
    public void flushTerminalLogs(long totalTimeoutMs) {
        long deadline = System.currentTimeMillis() + Math.max(0L, totalTimeoutMs);
        for (InstanceRuntime rt : runtimes.values()) {
            long remain = deadline - System.currentTimeMillis();
            if (remain <= 0) break;
            try {
                rt.terminalHandler.awaitFlushed(remain);
            } catch (Throwable ignored) {}
        }
    }

    /** 关闭所有实例并把日志刷盘：面板退出（本地 stop / 网页关闭面板）统一走这里 */
    public void shutdownAllAndFlush() {
        shutdownAll();
        flushTerminalLogs(5000L);
        try {
            // 留一点时间给会话/分享等异步落盘任务
            Thread.sleep(600L);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    public void shutdownAll() {
        List<Thread> workers = new ArrayList<>();
        for (final InstanceRuntime rt : runtimes.values()) {
            Runnable task = () -> {
                try {
                    String displayName = (rt.inst.name != null && !rt.inst.name.isEmpty()) ? rt.inst.name : rt.inst.id;
                    if (rt.mcServer.isRunning()) {
                        rt.mcServer.shutdown();
                        if (rt.mcServer.isRunning()) rt.mcServer.kill();
                        String msg = "[Panel] 实例 [" + displayName + "] 已安全退出";
                        System.out.println(msg);
                        if (rt.terminalHandler != null) rt.terminalHandler.log(msg);
                    }
                    // ★ 修复：如果本来就停止，不输出任何终端日志
                } catch (Throwable ignored) {}
            };
            Thread t = com.mcpanel.util.Async.worker("shutdown-all-" + rt.inst.id, task);
            if (t != null) workers.add(t);
            else task.run(); // 线程配额已满时退化为串行执行，不能被"拒绝"卡住退出流程
        }
        for (Thread t : workers) {
            try { t.join(70000); } catch (InterruptedException ignored) {}
        }
    }
}