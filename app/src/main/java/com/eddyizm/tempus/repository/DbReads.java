package com.eddyizm.tempus.repository;

import android.os.Looper;
import android.util.Log;

import com.eddyizm.tempus.util.AppExecutors;

import java.util.concurrent.Callable;

/**
 * Blocking Room reads for the repositories' synchronous getters. Off the main thread the query just
 * runs on the caller's thread. These getters used to start a new Thread and join() it on every call,
 * even from threads that could have queried directly. On the main thread (Room refuses queries there)
 * the query runs on a shared worker and the caller waits, as before but without a new thread each time.
 */
final class DbReads {
    private static final String TAG = "DbReads";

    private DbReads() {
    }

    static <T> T read(Callable<T> query, T fallback) {
        try {
            if (Looper.myLooper() != Looper.getMainLooper()) return query.call();
            return AppExecutors.io().submit(query).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return fallback;
        } catch (Exception e) {
            Log.w(TAG, "Query failed", e);
            return fallback;
        }
    }
}
