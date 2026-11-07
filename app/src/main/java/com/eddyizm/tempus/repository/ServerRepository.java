package com.eddyizm.tempus.repository;

import com.eddyizm.tempus.util.AppExecutors;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.Transformations;

import com.eddyizm.tempus.database.AppDatabase;
import com.eddyizm.tempus.database.dao.ServerDao;
import com.eddyizm.tempus.model.Server;
import com.eddyizm.tempus.util.CredentialCipher;

import java.util.ArrayList;

import java.util.List;

public class ServerRepository {
    private static final String TAG = "QueueRepository";

    private final ServerDao serverDao = AppDatabase.getInstance().serverDao();

    /** Saved servers with their passwords decrypted (they're stored encrypted, see CredentialCipher). */
    public LiveData<List<Server>> getLiveServer() {
        return Transformations.map(serverDao.getAll(), servers -> {
            List<Server> out = new ArrayList<>();
            if (servers != null) for (Server s : servers) out.add(CredentialCipher.decrypted(s));
            return out;
        });
    }

    /** Encrypts passwords saved before encryption was added; run once at startup, off the main thread. */
    public static void encryptSavedPasswords() {
        ServerDao dao = AppDatabase.getInstance().serverDao();
        for (Server s : dao.getAllNow()) {
            if (s.getPassword() != null && !s.getPassword().isEmpty() && !CredentialCipher.isEncrypted(s.getPassword())) {
                dao.update(CredentialCipher.encrypted(s));
            }
        }
    }

    public void insert(Server server) {
        InsertThreadSafe insert = new InsertThreadSafe(serverDao, CredentialCipher.encrypted(server));
        AppExecutors.io().execute(insert);
    }

    public void update(Server server) {
        UpdateThreadSafe update = new UpdateThreadSafe(serverDao, CredentialCipher.encrypted(server));
        AppExecutors.io().execute(update);
    }

    public void delete(Server server) {
        DeleteThreadSafe delete = new DeleteThreadSafe(serverDao, server);
        AppExecutors.io().execute(delete);
    }

    private static class InsertThreadSafe implements Runnable {
        private final ServerDao serverDao;
        private final Server server;

        public InsertThreadSafe(ServerDao serverDao, Server server) {
            this.serverDao = serverDao;
            this.server = server;
        }

        @Override
        public void run() {
            serverDao.insert(server);
        }
    }

    public static class UpdateThreadSafe implements Runnable {
        private final ServerDao serverDao;
        private final Server server;

        public UpdateThreadSafe(ServerDao serverDao, Server server) {
            this.serverDao = serverDao;
            this.server = server;
        }

        @Override
        public void run() {
            serverDao.update(server);
        }
    }

    private static class DeleteThreadSafe implements Runnable {
        private final ServerDao serverDao;
        private final Server server;

        public DeleteThreadSafe(ServerDao serverDao, Server server) {
            this.serverDao = serverDao;
            this.server = server;
        }

        @Override
        public void run() {
            serverDao.delete(server);
        }
    }
}
