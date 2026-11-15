package com.mcpanel.web;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class BizExecutor {
    private static final int CORE = 8;
    private static final int MAX = 64;
    private static final BlockingQueue<Runnable> QUEUE = new LinkedBlockingQueue<>(2000);

    private static final ThreadPoolExecutor POOL = new ThreadPoolExecutor(
            CORE, MAX, 60L, TimeUnit.SECONDS, QUEUE,
            new ThreadFactory() {
                private final AtomicInteger seq = new AtomicInteger(1);
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "biz-worker-" + seq.getAndIncrement());
                    t.setDaemon(true);
                    return t;
                }
            },
            new ThreadPoolExecutor.CallerRunsPolicy()
    );

    public static void execute(Runnable r) {
        POOL.execute(r);
    }
}