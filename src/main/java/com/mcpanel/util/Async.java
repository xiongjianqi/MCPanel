package com.mcpanel.util;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 统一的线程调度入口。
 *
 * 以前项目里到处 new Thread()，长时间运行 / 内存吃紧时很容易出现
 * "java.lang.OutOfMemoryError: unable to create native thread"。
 * 这里把所有线程创建收敛到几个固定大小的池：
 *  - run()    ：短任务，固定并发 4，队列满时丢弃并记日志，绝不阻塞调用方
 *  - serial() ：需要顺序执行的动作（安装、回档、面板退出）
 *  - later()  ：延迟任务（启动超时、PID 等待、验证码过期），共享一个定时线程
 *  - worker() ：必须长期阻塞的单线程任务（读服务端日志流等），每个任务一条线程，
 *               用 256KB 的小栈，显著降低线程栈占用的内存
 *
 * ★ 线程治理（第二次）：
 *  1. worker() 以前是完全无上限的，只要调用方反复触发（重连、反复启停、插件每 tick 调用）
 *     就会稳定地"1 次调用 = 1 条常驻线程"。现在加了硬上限 panel.max.blocking.threads
 *     （默认 256），超过上限直接拒绝并打印提示，宁可少一条日志线程，也不让原生线程被吃光。
 *  2. worker() 线程名带上全局序号（mc-log-reader-12），线程 dump / 面板诊断里能直接看出
 *     是哪一类任务在堆积；DashboardHandler 的线程统计仍按名称前缀聚合。
 *  3. 所有线程都是 daemon + 小栈，任何一条线程卡住都不会阻止面板退出。
 */
public final class Async {
    private static final int POOL_SIZE = Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors()));
    private static final BlockingQueue<Runnable> QUEUE = new ArrayBlockingQueue<>(2000);
    private static final AtomicInteger SEQ = new AtomicInteger(1);

    /**
     * 常驻（阻塞型）线程上限：每个实例正常只需要 1~3 条，256 条足够几十个实例。
     * 这个数字是"硬天花板"：旧版本没有上限，反复启停/插件高频调用能把线程堆到 900+，
     * 最终触发 unable to create native thread。
     */
    private static final int MAX_WORKERS = Math.max(8,
            Integer.getInteger("panel.max.blocking.threads", 256));
    private static final AtomicInteger LIVE_WORKERS = new AtomicInteger();
    private static final AtomicLong LAST_WORKER_REJECT_LOG = new AtomicLong();

    private static final RejectedExecutionHandler DISCARD = (r, e) ->
            System.err.println("[Async] 任务队列已满，丢弃一个后台任务");

    private static final ExecutorService POOL = new ThreadPoolExecutor(
            POOL_SIZE, POOL_SIZE, 0L, TimeUnit.MILLISECONDS, QUEUE,
            r -> daemonThread(r, "async-" + SEQ.getAndIncrement()),
            DISCARD);

    private static final ExecutorService SERIAL = Executors.newSingleThreadExecutor(
            r -> daemonThread(r, "async-serial"));

    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(
            r -> daemonThread(r, "async-timer"));

    private Async() {
    }

    /** 短任务：提交到固定大小线程池 */
    public static void run(Runnable task) {
        submit(POOL, task);
    }

    /** 需要按顺序执行的动作 */
    public static void serial(Runnable task) {
        submit(SERIAL, task);
    }

    /** 延迟执行（毫秒） */
    public static void later(long delayMs, Runnable task) {
        try {
            TIMER.schedule(() -> safeRun(task), Math.max(0L, delayMs), TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            System.err.println("[Async] 提交定时任务失败: " + t);
        }
    }

    /** 共享的定时器（需要周期任务时用），只有一条线程 */
    public static ScheduledExecutorService timer() {
        return TIMER;
    }

    /**
     * 长期阻塞的单线程任务：读日志流、等待进程等，每个任务一条小栈线程。
     *
     * 返回值可能为 null（达到上限时拒绝执行），调用方如果要 join 必须先判空。
     */
    public static Thread worker(String name, Runnable task) {
        int live = LIVE_WORKERS.incrementAndGet();
        if (live > MAX_WORKERS) {
            LIVE_WORKERS.decrementAndGet();
            logWorkerReject(name, live - 1);
            return null;
        }
        String threadName = name + "-" + SEQ.getAndIncrement();
        Thread t = new Thread(null, () -> {
            try {
                safeRun(task);
            } finally {
                LIVE_WORKERS.decrementAndGet();
            }
        }, threadName, 256L * 1024);
        t.setDaemon(true);
        try {
            t.start();
        } catch (Throwable e) {
            LIVE_WORKERS.decrementAndGet();
            System.err.println("[Async] 创建阻塞型线程失败（" + name + "）: " + e.getClass().getSimpleName());
            return null;
        }
        return t;
    }

    /** 当前存活的阻塞型线程数，诊断接口用 */
    public static int workerCount() {
        return LIVE_WORKERS.get();
    }

    public static int maxWorkers() {
        return MAX_WORKERS;
    }

    private static void logWorkerReject(String name, int live) {
        long now = System.currentTimeMillis();
        long last = LAST_WORKER_REJECT_LOG.get();
        if (now - last < 30_000L) return;
        if (!LAST_WORKER_REJECT_LOG.compareAndSet(last, now)) return;
        System.err.println("[Async] ⚠ 阻塞型线程已达上限 " + MAX_WORKERS + "（当前 " + live
                + "），跳过任务: " + name + "；请检查是否有实例反复启停或插件高频调用"
                + "（可通过 -Dpanel.max.blocking.threads=N 调整上限）");
    }

    private static void submit(ExecutorService pool, Runnable task) {
        try {
            pool.execute(() -> safeRun(task));
        } catch (Throwable t) {
            System.err.println("[Async] 提交后台任务失败: " + t);
        }
    }

    private static void safeRun(Runnable task) {
        try {
            task.run();
        } catch (Throwable t) {
            System.err.println("[Async] 后台任务异常: " + t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : " - " + t.getMessage()));
        }
    }

    private static Thread daemonThread(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }
}
