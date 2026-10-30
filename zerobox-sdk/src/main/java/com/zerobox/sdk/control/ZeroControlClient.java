package com.zerobox.sdk.control;

import android.os.IBinder;
import android.os.RemoteException;
import com.zerobox.sdk.memory.MemoryRegion;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * High-level Java client wrapper for interacting with a remote guest process via
 * {@link IZeroControlBroker} and {@link IZeroControlSession} (Shizuku-style Binder bridge).
 */
public final class ZeroControlClient {
    private final IZeroControlSession session;

    public ZeroControlClient(IZeroControlSession session) {
        if (session == null) {
            throw new IllegalArgumentException("IZeroControlSession cannot be null");
        }
        this.session = session;
    }

    public static ZeroControlClient fromBinder(IBinder sessionBinder) {
        return new ZeroControlClient(IZeroControlSession.Stub.asInterface(sessionBinder));
    }

    public boolean isAlive() {
        return session.asBinder().isBinderAlive();
    }

    public int getPid() throws RemoteException {
        return session.getPid();
    }

    public String getPackageName() throws RemoteException {
        return session.getPackageName();
    }

    public int getUserId() throws RemoteException {
        return session.getUserId();
    }

    public List<MemoryRegion> getMemoryRegions(String filterSubstring) throws RemoteException {
        List<String> lines = session.getMemoryRegions(filterSubstring != null ? filterSubstring : "");
        if (lines == null) {
            return Collections.emptyList();
        }
        List<MemoryRegion> regions = new ArrayList<>(lines.size());
        for (String line : lines) {
            MemoryRegion region = MemoryRegion.parseMapsLine(line);
            if (region != null) {
                regions.add(region);
            }
        }
        return regions;
    }

    public byte[] readMemory(long address, int length) throws RemoteException {
        return session.readMemory(address, length);
    }

    public int writeMemory(long address, byte[] data) throws RemoteException {
        return session.writeMemory(address, data);
    }

    public long[] scanPattern(String moduleFilter, String hexPattern, int maxResults) throws RemoteException {
        return session.scanPattern(
                moduleFilter != null ? moduleFilter : "",
                hexPattern,
                maxResults);
    }

    public List<String> listPrivateFiles(String relativeDir) throws RemoteException {
        return session.listPrivateFiles(relativeDir != null ? relativeDir : "");
    }

    public byte[] readPrivateFile(String relativePath) throws RemoteException {
        return session.readPrivateFile(relativePath);
    }

    public boolean writePrivateFile(String relativePath, byte[] data, boolean append) throws RemoteException {
        return session.writePrivateFile(relativePath, data, append);
    }

    public boolean addPassthroughPath(String pathPrefix) throws RemoteException {
        return session.addPassthroughPath(pathPrefix);
    }

    public boolean addRedirectRule(String virtualPrefix, String realPrefix) throws RemoteException {
        return session.addRedirectRule(virtualPrefix, realPrefix);
    }

    public boolean loadNativePlugin(String soFilePath) throws RemoteException {
        return session.loadNativePlugin(soFilePath);
    }

    public String runStealthSelfCheckReport() throws RemoteException {
        return session.runStealthSelfCheckReport();
    }
}
