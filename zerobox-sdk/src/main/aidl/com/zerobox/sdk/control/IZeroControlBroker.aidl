package com.zerobox.sdk.control;

import com.zerobox.sdk.control.IZeroControlSession;

/**
 * Central control broker hosted in the ZeroBox :daemon process.
 * Dispatches Shizuku-style direct IZeroControlSession Binder handles to authorized callers.
 */
interface IZeroControlBroker {
    void registerSession(String packageName, int userId, String processName, IZeroControlSession session);
    void unregisterSession(String packageName, int userId, String processName);

    IZeroControlSession acquireSession(String packageName, int userId);
    List<String> listActiveSessions();
}
