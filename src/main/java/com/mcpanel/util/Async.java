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

/**
 * 统一的线程调度入口。
 *
 * 以前项目里到处 new Thread()，长时间运行 / 内存吃紧时很容易出现
 * "java.lang.OutOfMemoryError: unable to create native thread"。
 * 这里把所有线程创建收敛到几个固定大小的池：
 *  - run()    ：短任务，固定并发 4，队列满时丢弃并记日志，绝不阻塞调用方
 *  - serial() ：需要顺序执行的动作（安装、回档、面板退出）
 *  - later()  ：延迟任务（启动超时、PID 等待），共享一个定时线程
 *  - worker() ：必须长期阻塞的单线程任务（读服务端日志流等），每个任务一条线程，
 *               用 256KB 的小栈，显著降低线程栈占用的内存
 */
public final class Async {
    private static final int POOL_SIZE = Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors()));
    private static final BlockingQueue<Runnable> QUEUE = new ArrayBlockingQueue<>(2000);
    private static final AtomicInteger SEQ = new AtomicInteger(1);

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

    /** 长期阻塞的单线程任务：读日志流、等待进程等，每个任务一条小栈线程 */
    public static Thread worker(String name, Runnable task) {
        Thread t = new Thread(null, () -> safeRun(task), name, 256L * 1024);
        t.setDaemon(true);
        t.start();
        return t;
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
