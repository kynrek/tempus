package com.eddyizm.tempus.repository;

import com.eddyizm.tempus.util.AppExecutors;

import androidx.lifecycle.LiveData;

import com.eddyizm.tempus.database.AppDatabase;
import com.eddyizm.tempus.database.dao.LyricsDao;
import com.eddyizm.tempus.model.LyricsCache;

public class LyricsRepository {
    private final LyricsDao lyricsDao = AppDatabase.getInstance().lyricsDao();

    public LyricsCache getLyrics(String songId) {
        return DbReads.read(() -> lyricsDao.getOne(songId), null);
    }

    public LiveData<LyricsCache> observeLyrics(String songId) {
        return lyricsDao.observeOne(songId);
    }

    public void insert(LyricsCache lyricsCache) {
        InsertThreadSafe insert = new InsertThreadSafe(lyricsDao, lyricsCache);
        AppExecutors.io().execute(insert);
    }

    public void delete(String songId) {
        DeleteThreadSafe delete = new DeleteThreadSafe(lyricsDao, songId);
        AppExecutors.io().execute(delete);
    }

    private static class GetLyricsThreadSafe implements Runnable {
        private final LyricsDao lyricsDao;
        private final String songId;
        private LyricsCache lyricsCache;

        public GetLyricsThreadSafe(LyricsDao lyricsDao, String songId) {
            this.lyricsDao = lyricsDao;
            this.songId = songId;
        }

        @Override
        public void run() {
            lyricsCache = lyricsDao.getOne(songId);
        }

        public LyricsCache getLyrics() {
            return lyricsCache;
        }
    }

    private static class InsertThreadSafe implements Runnable {
        private final LyricsDao lyricsDao;
        private final LyricsCache lyricsCache;

        public InsertThreadSafe(LyricsDao lyricsDao, LyricsCache lyricsCache) {
            this.lyricsDao = lyricsDao;
            this.lyricsCache = lyricsCache;
        }

        @Override
        public void run() {
            lyricsDao.insert(lyricsCache);
        }
    }

    private static class DeleteThreadSafe implements Runnable {
        private final LyricsDao lyricsDao;
        private final String songId;

        public DeleteThreadSafe(LyricsDao lyricsDao, String songId) {
            this.lyricsDao = lyricsDao;
            this.songId = songId;
        }

        @Override
        public void run() {
            lyricsDao.delete(songId);
        }
    }
}