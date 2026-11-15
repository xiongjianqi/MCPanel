package com.mcpanel.web;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ★ 修复（问题三）：登录限流器重写。
 *
 * 旧实现把「每次 /api/login 请求」都当成一次可疑样本：5 次请求间隔 < 1 秒立即封禁 15 分钟。
 * 正常用户刷新/前端重试/多标签页打开即可触发，且 WebServer 与 AuthHandler.handleForgot 都会
 * 调用 checkAndRecord，同一次请求被计两次，于是正常用户被 403/429 锁死，表现为「页面一直加载
 * 不出来」。此外 records 超过 10000 时直接 clear()，会把仍在封禁中的记录一并误清。
 *
 * 新语义（只惩罚失败，不惩罚访问频率）：
 *   - recordFail(ip)：记录一次「登录/找回密码凭证校验失败」的时间戳；窗口 FAIL_WINDOW_MS
 *     内失败次数达到 MAX_FAILS 即封禁 BAN_DURATION_MS。
 *   - resetFail(ip)：登录/重置密码成功后清零失败计数（封禁标记不动，解封只由时间决定）。
 *   - checkAndRecord(ip)：只检查当前 IP 是否处于封禁期，不产生任何计数、不创建记录；
 *     正常请求永远不会因为调用它而被封。
 *   - 清理：共享 daemon 单线程调度器每 60 秒按「封禁已过期且窗口内无失败记录」逐条 removeIf，
 *     不再整体 clear；封禁中的记录绝不会被清理。
 */
public class RateLimiter {

    /** 失败计数窗口：5 分钟 */
    private static final long FAIL_WINDOW_MS = 5 * 60 * 1000L;
    /** 窗口内达到该失败次数即封禁 */
    private static final int MAX_FAILS = 10;
    /** 封禁时长：10 分钟 */
    private static final long BAN_DURATION_MS = 10 * 60 * 1000L;
    /** 定期清理间隔：60 秒 */
    private static final long CLEAN_INTERVAL_MS = 60 * 1000L;
    /** 记录表硬上限：超过后仅清理过期项（绝不整体 clear） */
    private static final int MAX_RECORDS = 10000;

    private static class Record {
        /** 窗口内的失败时间戳（升序） */
        final Deque<Long> failTimes = new ArrayDeque<>();
        long bannedUntil = 0L;
    }

    private final Map<String, Record> records = new ConcurrentHashMap<>();

    /** ★ 修复（问题十二）：全类共享一个 daemon 调度器，不再每次 new Timer 建线程 */
    private static final ScheduledExecutorService CLEANER = Executors.newSingleThreadScheduledExecutor(
            new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "rate-limiter-cleaner");
                    t.setDaemon(true);
                    return t;
                }
            });

    /** 防止多个 RateLimiter 实例重复注册清理任务 */
    private static final AtomicBoolean CLEANER_STARTED = new AtomicBoolean(false);

    public RateLimiter() {
        if (CLEANER_STARTED.compareAndSet(false, true)) {
            CLEANER.scheduleWithFixedDelay(new Runnable() {
                @Override
                public void run() {
                    try {
                        cleanup();
                    } catch (Throwable t) {
                        System.err.println("[RateLimiter] 清理任务异常: " + t);
                    }
                }
            }, CLEAN_INTERVAL_MS, CLEAN_INTERVAL_MS, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * 只检查该 IP 当前是否被封禁（不计数、不创建记录、不修改状态）。
     * 保持旧方法名，供 WebServer /api/login 与 AuthHandler.handleForgot 兼容调用。
     *
     * @return true 表示仍处于封禁期，调用方应返回 429
     */
    public boolean checkAndRecord(String ip) {
        return checkBanned(ip);
    }

    /** 语义明确的封禁检查别名。 */
    public boolean checkBanned(String ip) {
        if (isBlank(ip)) return false;
        Record r = records.get(ip);
        if (r == null) return false;
        long now = System.currentTimeMillis();
        synchronized (r) {
            if (r.bannedUntil > now) return true;
            if (r.bannedUntil != 0L) {
                // 封禁已到期：只清封禁标记，历史失败记录由窗口逻辑处理
                r.bannedUntil = 0L;
            }
            return false;
        }
    }

    /**
     * 记录一次凭证校验失败；窗口内失败次数达到阈值时封禁该 IP。
     * 注意：调用方只应在「登录/找回密码失败」分支调用，成功分支必须调用 {@link #resetFail(String)}。
     */
    public void recordFail(String ip) {
        if (isBlank(ip)) return;
        long now = System.currentTimeMillis();
        Record r = records.computeIfAbsent(ip, k -> new Record());
        if (records.size() > MAX_RECORDS) enforceLimit(now);
        boolean banned = false;
        synchronized (r) {
            if (r.bannedUntil > now) return; // 已在封禁期，无需再累计
            pruneExpired(r.failTimes, now);   // 只移除窗口外的旧失败记录（逐条）
            r.failTimes.addLast(now);
            if (r.failTimes.size() >= MAX_FAILS) {
                r.bannedUntil = now + BAN_DURATION_MS;
                r.failTimes.clear();
                banned = true;
            }
        }
        if (banned) {
            System.out.println("[Panel] IP " + ip + " 在 " + (FAIL_WINDOW_MS / 60000L)
                    + " 分钟内登录失败 " + MAX_FAILS + " 次，封禁 "
                    + (BAN_DURATION_MS / 60000L) + " 分钟");
        }
    }

    /** 登录/重置密码成功：清零失败计数（不清除封禁，封禁到期由 checkBanned 处理）。 */
    public void resetFail(String ip) {
        if (isBlank(ip)) return;
        Record r = records.get(ip);
        if (r == null) return;
        synchronized (r) {
            r.failTimes.clear();
            if (r.bannedUntil <= System.currentTimeMillis()) {
                r.bannedUntil = 0L;
            }
        }
    }

    /** 定期逐条清理：只删除「未封禁且窗口内没有失败记录」的条目。 */
    private void cleanup() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Record>> it = records.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Record> e = it.next();
            Record r = e.getValue();
            boolean remove;
            synchronized (r) {
                if (r.bannedUntil > now) {
                    remove = false;
                } else {
                    r.bannedUntil = 0L;
                    pruneExpired(r.failTimes, now);
                    remove = r.failTimes.isEmpty();
                }
            }
            if (remove) it.remove();
        }
        if (records.size() > MAX_RECORDS) {
            // ★ 安全修复：旧实现只打印告警、从不真正限制，来源 IP 很多时 Map 会无限增长。
            // 这里改为真正逐条淘汰（优先淘汰未封禁且窗口内无失败的记录），保证内存有上限；
            // 仍然不整体 clear，尽量保留封禁中的记录。
            enforceLimit(now);
            System.err.println("[RateLimiter] 记录数超过上限，已淘汰旧记录，当前: " + records.size());
        }
    }

    /**
     * ★ 安全修复：记录表硬上限。先淘汰「未封禁且窗口内无失败」的条目；仍超限时才按迭代顺序
     * 淘汰（内存安全优先，避免被大量伪造 IP 撑爆堆内存）。封禁中的记录通常保留。
     */
    private void enforceLimit(long now) {
        java.util.Iterator<Map.Entry<String, Record>> it = records.entrySet().iterator();
        while (records.size() > MAX_RECORDS && it.hasNext()) {
            Map.Entry<String, Record> e = it.next();
            Record r = e.getValue();
            boolean removable;
            synchronized (r) {
                if (r.bannedUntil > now) {
                    removable = false;
                } else {
                    r.bannedUntil = 0L;
                    pruneExpired(r.failTimes, now);
                    removable = r.failTimes.isEmpty();
                }
            }
            if (removable) it.remove();
        }
        if (records.size() > MAX_RECORDS) {
            java.util.Iterator<String> it2 = records.keySet().iterator();
            while (records.size() > MAX_RECORDS && it2.hasNext()) {
                it2.next();
                it2.remove();
            }
        }
    }

    private static void pruneExpired(Deque<Long> times, long now) {
        Long first;
        while ((first = times.peekFirst()) != null && now - first > FAIL_WINDOW_MS) {
            times.pollFirst();
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
