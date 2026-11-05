package com.eddyizm.tempus.util;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.List;
import java.util.Map;

/**
 * Caps how fast downloads use mobile data, so navigation and streaming still get through while
 * the car downloads music over the cell connection. One budget is shared by every download
 * running at once; on Wi-Fi (an unmetered network) nothing is held back.
 */
@UnstableApi
public final class ThrottledDataSource implements DataSource {
    // Small reads keep the pace smooth instead of bursting a whole buffer and then pausing.
    private static final int MAX_READ_WHILE_THROTTLED = 16 * 1024;
    private static final long METERED_RECHECK_MS = 2000;

    private static final Object BUDGET = new Object();
    // When the shared budget is next free to send another byte, in elapsedRealtime nanoseconds.
    private static long nextFreeNanos;

    private static volatile boolean metered;
    private static volatile long meteredCheckedAt;

    private final DataSource upstream;
    private final Context context;

    private ThrottledDataSource(Context context, DataSource upstream) {
        this.context = context;
        this.upstream = upstream;
    }

    public static final class Factory implements DataSource.Factory {
        private final Context context;
        private final DataSource.Factory upstream;

        public Factory(Context context, DataSource.Factory upstream) {
            this.context = context.getApplicationContext();
            this.upstream = upstream;
        }

        @NonNull
        @Override
        public DataSource createDataSource() {
            return new ThrottledDataSource(context, upstream.createDataSource());
        }
    }

    @Override
    public int read(@NonNull byte[] buffer, int offset, int length) throws IOException {
        long bitsPerSecond = activeLimit();
        if (bitsPerSecond <= 0) return upstream.read(buffer, offset, length);

        int read = upstream.read(buffer, offset, Math.min(length, MAX_READ_WHILE_THROTTLED));
        if (read > 0) pace(read, bitsPerSecond);
        return read;
    }

    /** The cap in bits per second if one applies right now, else 0. */
    private long activeLimit() {
        int kbps = Preferences.getDownloadCellularLimitKbps();
        if (kbps <= 0) return 0;
        long now = SystemClock.elapsedRealtime();
        if (now - meteredCheckedAt > METERED_RECHECK_MS) {
            ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
            metered = cm != null && cm.isActiveNetworkMetered();
            meteredCheckedAt = now;
        }
        return metered ? kbps * 1000L : 0;
    }

    private static void pace(int bytes, long bitsPerSecond) throws InterruptedIOException {
        long cost = bytes * 8L * 1_000_000_000L / bitsPerSecond;
        long waitUntil;
        synchronized (BUDGET) {
            long now = SystemClock.elapsedRealtimeNanos();
            // Idle time doesn't bank up into a later burst beyond a quarter second.
            long start = Math.max(nextFreeNanos, now - 250_000_000L);
            nextFreeNanos = start + cost;
            waitUntil = nextFreeNanos;
        }
        long sleepNanos = waitUntil - SystemClock.elapsedRealtimeNanos();
        if (sleepNanos <= 0) return;
        try {
            Thread.sleep(sleepNanos / 1_000_000L, (int) (sleepNanos % 1_000_000L));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException();
        }
    }

    @Override
    public void addTransferListener(@NonNull TransferListener transferListener) {
        upstream.addTransferListener(transferListener);
    }

    @Override
    public long open(@NonNull DataSpec dataSpec) throws IOException {
        return upstream.open(dataSpec);
    }

    @Nullable
    @Override
    public Uri getUri() {
        return upstream.getUri();
    }

    @NonNull
    @Override
    public Map<String, List<String>> getResponseHeaders() {
        return upstream.getResponseHeaders();
    }

    @Override
    public void close() throws IOException {
        upstream.close();
    }
}
