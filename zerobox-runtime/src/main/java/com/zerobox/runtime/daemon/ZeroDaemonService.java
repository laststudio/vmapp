package com.zerobox.runtime.daemon;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import com.zerobox.sdk.control.IZeroControlBroker;
import com.zerobox.sdk.control.IZeroControlSession;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lightweight coordinator service hosted in {@code :daemon}.
 * Routes Shizuku-style {@link IZeroControlSession} Binder handles between container slot processes
 * and the Host Manager UI or external control scripts.
 */
public final class ZeroDaemonService extends Service {

    private final Map<String, IZeroControlSession> activeSessions = new ConcurrentHashMap<>();

    private final IZeroControlBroker.Stub brokerBinder = new IZeroControlBroker.Stub() {
        @Override
        public void registerSession(
                String packageName,
                int userId,
                String processName,
                IZeroControlSession session) {
            if (packageName == null || session == null) return;
            String key = buildSessionKey(packageName, userId);
            activeSessions.put(key, session);
        }

        @Override
        public void unregisterSession(String packageName, int userId, String processName) {
            if (packageName == null) return;
            activeSessions.remove(buildSessionKey(packageName, userId));
        }

        @Override
        public IZeroControlSession acquireSession(String packageName, int userId) {
            if (packageName == null) return null;
            IZeroControlSession session = activeSessions.get(buildSessionKey(packageName, userId));
            if (session != null && session.asBinder().isBinderAlive()) {
                return session;
            }
            activeSessions.remove(buildSessionKey(packageName, userId));
            return null;
        }

        @Override
        public List<String> listActiveSessions() {
            List<String> keys = new ArrayList<>();
            for (Map.Entry<String, IZeroControlSession> entry : activeSessions.entrySet()) {
                if (entry.getValue().asBinder().isBinderAlive()) {
                    keys.add(entry.getKey());
                }
            }
            return keys;
        }
    };

    private static String buildSessionKey(String packageName, int userId) {
        return packageName + "@" + userId;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return brokerBinder;
    }
}
