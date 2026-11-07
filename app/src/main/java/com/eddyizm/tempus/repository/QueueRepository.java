package com.eddyizm.tempus.repository;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MediatorLiveData;
import androidx.lifecycle.MutableLiveData;

import com.eddyizm.tempus.App;
import com.eddyizm.tempus.database.AppDatabase;
import com.eddyizm.tempus.database.dao.QueueDao;
import com.eddyizm.tempus.model.Queue;
import com.eddyizm.tempus.subsonic.base.ApiResponse;
import com.eddyizm.tempus.subsonic.models.Child;
import com.eddyizm.tempus.subsonic.models.Bookmark;
import com.eddyizm.tempus.subsonic.models.PlayQueue;
import com.eddyizm.tempus.util.AppExecutors;
import com.eddyizm.tempus.util.Preferences;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class QueueRepository {
    private static final String TAG = "QueueRepository";
    private static final ExecutorService dbExecutor = Executors.newSingleThreadExecutor();

    private final QueueDao queueDao = AppDatabase.getInstance().queueDao();

    public LiveData<List<Queue>> getLiveQueue() {
        // The resume point (every 15 s while playing) and the last-played time (every track
        // change) are written to the queue rows. Each write makes Room read the whole queue again
        // (~3,000 rows on the car box) and every observer re-map it on the main thread, although
        // the songs and their order did not change. Pass on only lists whose songs or order differ.
        MediatorLiveData<List<Queue>> distinct = new MediatorLiveData<>();
        distinct.addSource(queueDao.getAll(), rows -> {
            List<Queue> shown = distinct.getValue();
            if (shown != null && sameSongs(shown, rows)) return;
            distinct.setValue(rows);
        });
        return distinct;
    }

    static boolean sameSongs(List<Queue> a, List<Queue> b) {
        if (a == b) return true;
        if (a == null || b == null || a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!java.util.Objects.equals(a.get(i).getId(), b.get(i).getId())) return false;
        }
        return true;
    }

    public List<Child> getMedia() {
        return DbReads.read(() -> queueDao.getAllSimple().stream()
                .map(Child.class::cast)
                .collect(Collectors.toList()), new ArrayList<>());
    }

    public MutableLiveData<PlayQueue> getPlayQueue() {
        MutableLiveData<PlayQueue> playQueue = new MutableLiveData<>();

        Log.d(TAG, "Getting play queue from server...");

        App.getSubsonicClientInstance(false)
                .getBookmarksClient()
                .getPlayQueue()
                .enqueue(new Callback<ApiResponse>() {
                    @Override
                    public void onResponse(@NonNull Call<ApiResponse> call, @NonNull Response<ApiResponse> response) {
                        if (response.isSuccessful() && response.body() != null
                                && response.body().getSubsonicResponse() != null
                                && response.body().getSubsonicResponse().getPlayQueue() != null) {
                            PlayQueue serverQueue = response.body().getSubsonicResponse().getPlayQueue();
                            Log.d(TAG, "Server returned play queue with " +
                                    (serverQueue.getEntries() != null ? serverQueue.getEntries().size() : 0) + " items");
                            playQueue.setValue(serverQueue);
                        } else {
                            Log.d(TAG, "Server returned no play queue");
                            playQueue.setValue(null);
                        }
                    }

                    @Override
                    public void onFailure(@NonNull Call<ApiResponse> call, @NonNull Throwable t) {
                        Log.e(TAG, "Failed to get play queue", t);
                        playQueue.setValue(null);
                    }
                });

        return playQueue;
    }

    public void savePlayQueue(List<String> ids, String current, long position) {
        Log.d(TAG, "Saving play queue to server - Items: " + ids.size() + ", Current: " + current);

        App.getSubsonicClientInstance(false)
                .getBookmarksClient()
                .savePlayQueue(ids, current, position)
                .enqueue(new Callback<ApiResponse>() {
                    @Override
                    public void onResponse(@NonNull Call<ApiResponse> call, @NonNull Response<ApiResponse> response) {
                        if (response.isSuccessful()) {
                            Log.d(TAG, "Play queue saved successfully");
                        } else {
                            Log.d(TAG, "Play queue save failed with code: " + response.code());
                        }
                    }

                    @Override
                    public void onFailure(@NonNull Call<ApiResponse> call, @NonNull Throwable t) {
                        Log.e(TAG, "Play queue save failed", t);
                    }
                });
    }

    /**
     * Reads the current queue on the DB executor and pushes it (with the given
     * current song + position) to the server. Used for the automatic saves (pause, track
     * change, seek and the playback timer) so the resume point is always current without a UI
     * button press.
     * <p>
     * Nothing is sent when the current item is not part of the local queue. Radio and podcasts
     * played from the Podcast tab or a channel page do not update the local queue, so saving
     * would pair the old song list with the station or episode as the current item.
     */
    public void savePlayQueueToServer(String current, long position) {
        if (!Preferences.isSyncronizationEnabled()) return;
        // A read of the whole queue: on the shared pool, not the single queue-write thread, where it
        // held up the small writes queued behind it on every pause, seek and track change.
        // Queued behind the writes already waiting (so it sees them), but read on the pool.
        dbExecutor.execute(() -> AppExecutors.io().execute(() -> {
            List<String> ids = queueDao.getAllSimple().stream()
                    .map(Queue::getId)
                    .collect(Collectors.toList());
            if (!ids.contains(current)) {
                Log.d(TAG, "Skipping queue save, current item is not in the queue: " + current);
                return;
            }
            savePlayQueue(ids, current, position);
        }));
    }

    /**
     * Creates/updates a server-side bookmark for a song (Navidrome/Subsonic). This is the
     * cross-device "continue listening" source; the local Preferences store is the fast cache.
     */
    public void createServerBookmark(String id, long positionMs) {
        // Skip trivial positions (a barely-started track isn't "in progress").
        if (id == null || positionMs <= 5000L) return;
        App.getSubsonicClientInstance(false)
                .getBookmarksClient()
                .createBookmark(id, positionMs)
                .enqueue(new Callback<ApiResponse>() {
                    @Override
                    public void onResponse(@NonNull Call<ApiResponse> call, @NonNull Response<ApiResponse> response) {
                        String status = "unknown";
                        String error = "";
                        if (response.body() != null && response.body().getSubsonicResponse() != null) {
                            status = String.valueOf(response.body().getSubsonicResponse().getStatus());
                            if (response.body().getSubsonicResponse().getError() != null) {
                                error = response.body().getSubsonicResponse().getError().getMessage();
                            }
                        }
                        if (!response.isSuccessful() || !"ok".equals(status)) {
                            Log.w(TAG, "createBookmark id=" + id + " pos=" + positionMs
                                    + " -> HTTP " + response.code() + " subsonicStatus=" + status + " " + error);
                        }
                    }

                    @Override
                    public void onFailure(@NonNull Call<ApiResponse> call, @NonNull Throwable t) {
                        Log.e(TAG, "createBookmark id=" + id + " pos=" + positionMs + " failed", t);
                    }
                });
    }

    /**
     * Removes the server bookmark for a song (used when a track plays to completion, so a fully
     * finished song no longer appears in "Continue listening"). Clears the local resume point too.
     */
    public void deleteServerBookmark(String id) {
        if (id == null) return;
        Preferences.clearResumePoint(id);
        App.getSubsonicClientInstance(false)
                .getBookmarksClient()
                .deleteBookmark(id)
                .enqueue(new Callback<ApiResponse>() {
                    @Override
                    public void onResponse(@NonNull Call<ApiResponse> call, @NonNull Response<ApiResponse> response) {
                        if (!response.isSuccessful()) {
                            Log.w(TAG, "deleteBookmark failed with code: " + response.code());
                        }
                    }

                    @Override
                    public void onFailure(@NonNull Call<ApiResponse> call, @NonNull Throwable t) {
                        Log.e(TAG, "deleteBookmark failed", t);
                    }
                });
    }

    /**
     * Reads all server bookmarks and merges them into the durable local store (source for the
     * Continue listening overview). Cross-device bookmarks appear because the server is authoritative.
     * Runs [onSynced] (if given) on the main thread after the merge.
     */
    public void syncBookmarksFromServer(@Nullable Runnable onSynced) {
        App.getSubsonicClientInstance(false)
                .getBookmarksClient()
                .getBookmarks()
                .enqueue(new Callback<ApiResponse>() {
                    @Override
                    public void onResponse(@NonNull Call<ApiResponse> call, @NonNull Response<ApiResponse> response) {
                        if (response.isSuccessful() && response.body() != null
                                && response.body().getSubsonicResponse() != null) {
                            List<Bookmark> bookmarks = response.body().getSubsonicResponse().getBookmarks() != null
                                    ? response.body().getSubsonicResponse().getBookmarks().getBookmarks()
                                    : null;
                            if (bookmarks != null) {
                                Map<String, Preferences.ResumePoint> merged = new HashMap<>();
                                for (Bookmark bm : bookmarks) {
                                    Child entry = bm.getEntry();
                                    if (entry == null || entry.getId() == null || bm.getPosition() <= 0L) continue;
                                    long ts = bm.getChanged() != null ? bm.getChanged().getTime() : System.currentTimeMillis();
                                    merged.put(entry.getId(), new Preferences.ResumePoint(
                                            entry.getId(),
                                            bm.getPosition(),
                                            entry.getTitle(),
                                            entry.getAlbum(),
                                            entry.getArtist(),
                                            entry.getAlbumId(),
                                            entry.getCoverArtId(),
                                            entry.getType(),
                                            ts
                                    ));
                                }
                                Preferences.saveResumePoints(merged);
                            }
                        }
                        if (onSynced != null) {
                            new Handler(Looper.getMainLooper()).post(onSynced);
                        }
                    }

                    @Override
                    public void onFailure(@NonNull Call<ApiResponse> call, @NonNull Throwable t) {
                        Log.e(TAG, "syncBookmarks failed", t);
                        if (onSynced != null) {
                            new Handler(Looper.getMainLooper()).post(onSynced);
                        }
                    }
                });
    }

    public void insert(Child media, boolean reset, int afterIndex) {
        dbExecutor.execute(() -> {
            List<Queue> mediaList = new ArrayList<>();
            int insertionIndex = afterIndex;

            if (!reset) {
                mediaList = queueDao.getAllSimple();
            } else {
                insertionIndex = 0;
            }

            if (insertionIndex < 0) {
                insertionIndex = 0;
            } else if (insertionIndex > mediaList.size()) {
                insertionIndex = mediaList.size();
            }

            Queue queueItem = new Queue(media);
            mediaList.add(insertionIndex, queueItem);

            for (int i = 0; i < mediaList.size(); i++) {
                mediaList.get(i).setTrackOrder(i);
            }

            queueDao.replaceQueue(mediaList);
        });
    }

    public void insertAll(List<Child> toAdd, boolean reset, int afterIndex) {
        dbExecutor.execute(() -> {
            List<Queue> media = new ArrayList<>();
            int insertionIndex = afterIndex;

            if (!reset) {
                media = queueDao.getAllSimple();
            } else {
                insertionIndex = 0;
            }

            if (insertionIndex < 0) {
                insertionIndex = 0;
            } else if (insertionIndex > media.size()) {
                insertionIndex = media.size();
            }

            // Keep songs that are already queued: the player holds them twice, so the saved queue
            // has to as well, or a restore drops them. Rows are keyed by position, not song id.
            List<Queue> toInsert = new ArrayList<>(toAdd.size());
            for (Child child : toAdd) toInsert.add(new Queue(child));
            media.addAll(insertionIndex, toInsert);

            for (int i = 0; i < media.size(); i++) {
                media.get(i).setTrackOrder(i);
            }

            queueDao.replaceQueue(media);
        });
    }

    public void delete(int position) {
        dbExecutor.execute(() -> queueDao.delete(position));
    }

    /**
     * Saves {@code songs} as the whole queue, in that order, keeping each song's last-played time and
     * stored position. Rebuilding the rows from the songs alone (insertAll with reset) cleared both, so
     * after a move, removal or shuffle the restore no longer knew which song to come back to.
     */
    public void replaceKeepingResume(List<Child> songs) {
        final List<Child> copy = new ArrayList<>(songs);
        dbExecutor.execute(() -> {
            java.util.Map<String, Queue> previous = new java.util.HashMap<>();
            for (Queue row : queueDao.getAllSimple()) {
                Queue kept = previous.get(row.getId());
                // A song queued twice: keep whichever copy was played last.
                if (kept == null || row.getLastPlay() > kept.getLastPlay()) previous.put(row.getId(), row);
            }
            List<Queue> rows = new ArrayList<>(copy.size());
            for (int i = 0; i < copy.size(); i++) {
                Queue row = new Queue(copy.get(i));
                Queue old = previous.get(row.getId());
                if (old != null) {
                    row.setLastPlay(old.getLastPlay());
                    row.setPlayingChanged(old.getPlayingChanged());
                }
                row.setTrackOrder(i);
                rows.add(row);
            }
            queueDao.replaceQueue(rows);
        });
    }

    public void deleteAll() {
        dbExecutor.execute(queueDao::deleteAll);
    }

    public int count() {
        return DbReads.read(queueDao::count, 0);
    }

    public void setLastPlayedTimestamp(String id) {
        dbExecutor.execute(() -> queueDao.setLastPlay(id, System.currentTimeMillis()));
    }

    /**
     * Marks a song as the one to come back to, and stores how far into it playback had got.
     * This write moves both columns together, so a pause cannot leave the pointer on one row
     * and the position on another. setLastPlayedTimestamp moves last_play on its own, so a
     * song reached by a track change can still carry a position from an earlier pause.
     */
    public void setResumePoint(String id, long positionMs) {
        // Update the queue row for the launch-restore path which reads it back from the DB.
        // (MediaManager.setResumePoint also writes the durable per-song store with metadata.)
        dbExecutor.execute(() -> queueDao.setResumePoint(id, System.currentTimeMillis(), positionMs));
    }

    public Queue getLastPlayedMedia() {
        return DbReads.read(queueDao::getLastPlayed, null);
    }

    private static class GetMediaThreadSafe implements Runnable {
        private final QueueDao queueDao;
        private List<Queue> media;

        public GetMediaThreadSafe(QueueDao queueDao) {
            this.queueDao = queueDao;
        }

        @Override
        public void run() {
            media = queueDao.getAllSimple();
        }

        public List<Queue> getMedia() {
            return media;
        }
    }

    private static class CountThreadSafe implements Runnable {
        private final QueueDao queueDao;
        private int count = 0;

        public CountThreadSafe(QueueDao queueDao) {
            this.queueDao = queueDao;
        }

        @Override
        public void run() {
            count = queueDao.count();
        }

        public int getCount() {
            return count;
        }
    }

    private static class GetLastPlayedMediaThreadSafe implements Runnable {
        private final QueueDao queueDao;
        private Queue lastMediaPlayed;

        public GetLastPlayedMediaThreadSafe(QueueDao queueDao) {
            this.queueDao = queueDao;
        }

        @Override
        public void run() {
            lastMediaPlayed = queueDao.getLastPlayed();
        }

        public Queue getQueueItem() {
            return lastMediaPlayed;
        }
    }

    public void deleteRange(int fromIndex, int toIndex) {
        dbExecutor.execute(() -> {
            List<Queue> media = queueDao.getAllSimple();
            if (fromIndex < 0 || toIndex > media.size() || fromIndex >= toIndex) return;
            media.subList(fromIndex, toIndex).clear();
            for (int i = 0; i < media.size(); i++) {
                media.get(i).setTrackOrder(i);
            }
            queueDao.replaceQueue(media);
        });
    }
}