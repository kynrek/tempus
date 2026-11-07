package com.eddyizm.tempus.util;

import android.os.Process;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The app's shared worker threads, instead of a new Thread (or a new executor) per call.
 * <ul>
 *   <li>{@link #io()}: short database / file / network work someone may be waiting for.</li>
 *   <li>{@link #idle()}: work nobody is waiting for (cover fetching, repairs, prefetches). Runs at
 *       background priority so it doesn't take the fast cores from the screen and the audio.</li>
 *   <li>{@link #compute()}: CPU work split across cores, such as mapping a long queue.</li>
 * </ul>
 * Threads stop after a short idle time, so an idle app keeps none of them.
 */
public final class AppExecutors {
    private static final int CORES = Runtime.getRuntime().availableProcessors();

    // The car box has 4 fast and 4 slow cores: more than 4 CPU threads only adds contention.
    private static final ExecutorService IO = pool("tempus-io", 4, Process.THREAD_PRIORITY_DEFAULT);
    private static final ExecutorService IDLE = pool("tempus-idle", 2, Process.THREAD_PRIORITY_BACKGROUND);
    private static final ExecutorService COMPUTE = pool("tempus-compute", Math.max(1, Math.min(4, CORES - 1)), Process.THREAD_PRIORITY_DEFAULT);

    private AppExecutors() {
    }

    public static ExecutorService io() {
        return IO;
    }

    public static ExecutorService idle() {
        return IDLE;
    }

    public static ExecutorService compute() {
        return COMPUTE;
    }

    /** Threads {@link #compute()} may use at once. */
    public static int computeThreads() {
        return Math.max(1, Math.min(4, CORES - 1));
    }

    private static ExecutorService pool(String name, int threads, int priority) {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                threads, threads, 10, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), factory(name, priority));
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    private static ThreadFactory factory(String name, int priority) {
        AtomicInteger count = new AtomicInteger();
        return runnable -> new Thread(() -> {
            Process.setThreadPriority(priority);
            runnable.run();
        }, name + "-" + count.incrementAndGet());
    }
}
