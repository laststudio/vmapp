package com.zerobox.runtime.delegate;

import android.app.Application;
import android.app.Service;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.os.IBinder;
import com.zerobox.runtime.config.PassthroughConfig;
import com.zerobox.runtime.daemon.ZeroPackageEngine;
import com.zerobox.runtime.daemon.ZeroRuntimeSession;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Isolated Service slot hosted in {@code :p0} ~ {@code :p1}.
 * Supports both:
 * <ul>
 *   <li>Exposing the in-process {@code IZeroControlSession} Binder when bound with {@link #ACTION_ACQUIRE_CONTROL_SESSION}</li>
 *   <li>Instantiating and delegating guest APK {@link Service} components ({@code onCreate}, {@code onStartCommand},
 *       {@code onBind}, {@code onDestroy}) inside the isolated slot process.</li>
 * </ul>
 */
public class SlotService extends Service {

    public static class P0 extends SlotService {}
    public static class P1 extends SlotService {}

    public static final String ACTION_ACQUIRE_CONTROL_SESSION =
            "com.zerobox.runtime.action.ACQUIRE_CONTROL_SESSION";

    private final Map<String, Service> activeGuestServices = new ConcurrentHashMap<>();

    public static Class<?> getSlotServiceClass(int slotIndex) {
        return (Math.abs(slotIndex) % 2 == 0) ? P0.class : P1.class;
    }

    @Override
    public IBinder onBind(Intent intent) {
        if (intent != null && ACTION_ACQUIRE_CONTROL_SESSION.equals(intent.getAction())) {
            ZeroRuntimeSession session = ZeroRuntimeSession.getCurrentOrCreateDefault(this);
            return session.getControlAgentBinder();
        }
        Service guestService = resolveOrCreateGuestService(intent);
        if (guestService != null) {
            Intent orig = unwrapOriginalIntent(intent);
            return guestService.onBind(orig);
        }
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Service guestService = resolveOrCreateGuestService(intent);
        if (guestService != null) {
            Intent orig = unwrapOriginalIntent(intent);
            return guestService.onStartCommand(orig, flags, startId);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        for (Service guestService : activeGuestServices.values()) {
            try {
                guestService.onDestroy();
            } catch (Throwable ignored) {
            }
        }
        activeGuestServices.clear();
        super.onDestroy();
    }

    private Service resolveOrCreateGuestService(Intent intent) {
        if (intent == null || !intent.hasExtra(ZeroComponentFactory.EXTRA_TARGET_CLASS)) {
            return null;
        }
        String targetClass = intent.getStringExtra(ZeroComponentFactory.EXTRA_TARGET_CLASS);
        String guestPkg = intent.getStringExtra(ZeroComponentFactory.EXTRA_TARGET_PACKAGE);
        int userId = intent.getIntExtra(ZeroComponentFactory.EXTRA_TARGET_USER_ID, 0);
        String apkPath = intent.getStringExtra(ZeroComponentFactory.EXTRA_TARGET_APK_PATH);
        String dataDir = intent.getStringExtra(ZeroComponentFactory.EXTRA_TARGET_DATA_DIR);
        if (targetClass == null || targetClass.isEmpty() || guestPkg == null) {
            return null;
        }

        String serviceKey = guestPkg + "@" + userId + "/" + targetClass;
        Service existing = activeGuestServices.get(serviceKey);
        if (existing != null) {
            return existing;
        }

        try {
            Context hostAppCtx = getApplicationContext();
            ZeroPackageEngine pkgEngine = new ZeroPackageEngine(hostAppCtx);
            PassthroughConfig config = pkgEngine.loadPassthroughConfig(guestPkg, userId);
            GuestLoadedApkEngine.LoadedGuestApk loaded = GuestLoadedApkEngine.ensureGuestApkLoaded(
                    hostAppCtx,
                    guestPkg,
                    userId,
                    new File(apkPath != null ? apkPath : hostAppCtx.getApplicationInfo().sourceDir),
                    new File(dataDir != null ? dataDir : hostAppCtx.getFilesDir().getAbsolutePath()),
                    config,
                    pkgEngine.getEnabledModuleApks(),
                    pkgEngine.getEnabledNativePlugins());

            Class<?> svcClass = loaded.classLoader.loadClass(targetClass);
            Service guestSvc = (Service) svcClass.getDeclaredConstructor().newInstance();

            Method attachBase = ContextWrapper.class.getDeclaredMethod("attachBaseContext", Context.class);
            attachBase.setAccessible(true);
            attachBase.invoke(guestSvc, loaded.baseContext);

            try {
                Field appField = Service.class.getDeclaredField("mApplication");
                appField.setAccessible(true);
                Application app = loaded.application != null ? loaded.application : getApplication();
                appField.set(guestSvc, app);
            } catch (Throwable ignored) {
            }

            guestSvc.onCreate();
            activeGuestServices.put(serviceKey, guestSvc);
            return guestSvc;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Intent unwrapOriginalIntent(Intent wrapper) {
        if (wrapper != null && wrapper.hasExtra(ZeroComponentFactory.EXTRA_ORIGINAL_INTENT)) {
            Intent orig = wrapper.getParcelableExtra(ZeroComponentFactory.EXTRA_ORIGINAL_INTENT);
            if (orig != null) {
                return orig;
            }
        }
        return wrapper;
    }
}
