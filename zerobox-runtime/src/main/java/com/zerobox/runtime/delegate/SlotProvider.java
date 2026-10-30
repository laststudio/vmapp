package com.zerobox.runtime.delegate;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import com.zerobox.runtime.daemon.ZeroRuntimeSession;

/**
 * Early-initialization ContentProvider inside each container slot process ({@code :p0}, {@code :p1}).
 * Android invokes {@link ContentProvider#onCreate()} before {@code Application.onCreate()},
 * allowing ZeroBox to install {@link ZeroInstrumentation} and Seccomp-BPF VFS before guest code runs.
 */
public class SlotProvider extends ContentProvider {

    public static class P0 extends SlotProvider {}
    public static class P1 extends SlotProvider {}

    public static final String METHOD_GET_CONTROL_SESSION = "zerobox.method.GET_CONTROL_SESSION";
    public static final String KEY_CONTROL_BINDER = "zerobox.key.CONTROL_BINDER";

    @Override
    public boolean onCreate() {
        if (getContext() != null) {
            ZeroInstrumentation.ensureInstalledOnCurrentActivityThread(getContext());
        }
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (METHOD_GET_CONTROL_SESSION.equals(method) && getContext() != null) {
            ZeroRuntimeSession session = ZeroRuntimeSession.getCurrentOrCreateDefault(getContext());
            Bundle reply = new Bundle();
            reply.putBinder(KEY_CONTROL_BINDER, session.getControlAgentBinder());
            return reply;
        }
        return super.call(method, arg, extras);
    }

    @Override
    public Cursor query(
            Uri uri,
            String[] projection,
            String selection,
            String[] selectionArgs,
            String sortOrder) {
        ContentProvider delegate = resolveGuestDelegate(uri);
        if (delegate != null) {
            return delegate.query(uri, projection, selection, selectionArgs, sortOrder);
        }
        return null;
    }

    @Override
    public String getType(Uri uri) {
        ContentProvider delegate = resolveGuestDelegate(uri);
        if (delegate != null) {
            return delegate.getType(uri);
        }
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        ContentProvider delegate = resolveGuestDelegate(uri);
        if (delegate != null) {
            return delegate.insert(uri, values);
        }
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        ContentProvider delegate = resolveGuestDelegate(uri);
        if (delegate != null) {
            return delegate.delete(uri, selection, selectionArgs);
        }
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        ContentProvider delegate = resolveGuestDelegate(uri);
        if (delegate != null) {
            return delegate.update(uri, values, selection, selectionArgs);
        }
        return 0;
    }

    private static ContentProvider resolveGuestDelegate(Uri uri) {
        if (uri == null) return null;
        String targetAuth = uri.getQueryParameter("zb_authority");
        if (targetAuth == null || targetAuth.isEmpty()) {
            targetAuth = uri.getAuthority();
        }
        return GuestLoadedApkEngine.getGuestProviderByAuthority(targetAuth);
    }
}
