package com.zerobox.runtime.delegate;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Process;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.zerobox.runtime.config.PassthroughConfig;
import com.zerobox.runtime.daemon.StealthSelfChecker;
import com.zerobox.runtime.daemon.ZeroRuntimeSession;
import java.io.File;

/**
 * Isolated container slot Activity hosted in process {@code :p0} ~ {@code :p3}.
 * Initializes the guest environment (Seccomp-BPF VFS, /proc cloak, sibling module loader,
 * and cross-process control agent) before delegating to the target APK Activity or
 * rendering the interactive container runtime diagnostics view.
 */
public class SlotActivity extends Activity {

    public static class P0 extends SlotActivity {}
    public static class P1 extends SlotActivity {}
    public static class P2 extends SlotActivity {}
    public static class P3 extends SlotActivity {}

    public static Class<?> getSlotActivityClass(int slotIndex) {
        switch (Math.abs(slotIndex) % 4) {
            case 0:
                return P0.class;
            case 1:
                return P1.class;
            case 2:
                return P2.class;
            default:
                return P3.class;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent intent = getIntent();
        String guestPkg = intent != null
                ? intent.getStringExtra(ZeroComponentFactory.EXTRA_TARGET_PACKAGE)
                : null;
        int userId = intent != null
                ? intent.getIntExtra(ZeroComponentFactory.EXTRA_TARGET_USER_ID, 0)
                : 0;
        String apkPath = intent != null
                ? intent.getStringExtra(ZeroComponentFactory.EXTRA_TARGET_APK_PATH)
                : null;
        String dataDir = intent != null
                ? intent.getStringExtra(ZeroComponentFactory.EXTRA_TARGET_DATA_DIR)
                : null;

        if (guestPkg == null || guestPkg.isEmpty()) {
            guestPkg = "com.zerobox.guest.sandbox";
        }
        if (dataDir == null || dataDir.isEmpty()) {
            dataDir = new File(getFilesDir(), "users/" + userId + "/packages/" + guestPkg + "/data")
                    .getAbsolutePath();
        }
        if (apkPath == null || apkPath.isEmpty()) {
            apkPath = getApplicationInfo().sourceDir;
        }

        PassthroughConfig config = PassthroughConfig.createPureStealthPreset();
        ZeroInstrumentation.ensureInstalledOnCurrentActivityThread(getApplicationContext());
        ZeroRuntimeSession.initializeSlotProcess(
                getApplicationContext(),
                guestPkg,
                userId,
                new File(apkPath),
                new File(dataDir),
                config);

        // Fallback: if an OEM ROM bypassed AppComponentFactory on cold slot process start,
        // re-dispatch now that ZeroInstrumentation is installed on ActivityThread.mInstrumentation.
        String targetClass = intent != null
                ? intent.getStringExtra(ZeroComponentFactory.EXTRA_TARGET_CLASS)
                : null;
        if (targetClass != null
                && !targetClass.isEmpty()
                && !targetClass.equals(getClass().getName())
                && !intent.getBooleanExtra("zerobox.extra.REDISPATCHED", false)) {
            Intent relaunch = new Intent(intent);
            relaunch.putExtra("zerobox.extra.REDISPATCHED", true);
            startActivity(relaunch);
            finish();
            return;
        }

        // Render live slot status & stealth verification report if launched directly as sandbox inspector
        ScrollView scrollView = new ScrollView(this);
        scrollView.setBackgroundColor(Color.parseColor("#0F172A"));

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 18, getResources().getDisplayMetrics());
        container.setPadding(pad, pad, pad, pad);

        TextView titleView = new TextView(this);
        titleView.setText("ZeroBox Container Slot Active (PID " + Process.myPid() + ")");
        titleView.setTextColor(Color.parseColor("#38BDF8"));
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        container.addView(titleView);

        TextView subtitleView = new TextView(this);
        subtitleView.setText(
                "Guest Package: " + guestPkg + "  |  User ID: " + userId + "\n"
                        + "Isolated Data Dir: " + dataDir + "\n"
                        + "Original APK (Zero-Mod): " + apkPath + "\n");
        subtitleView.setTextColor(Color.parseColor("#94A3B8"));
        subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        container.addView(subtitleView);

        TextView reportView = new TextView(this);
        reportView.setText(StealthSelfChecker.generateFullReport(
                this,
                guestPkg,
                new File(apkPath),
                ZeroInstrumentation.getActiveGuestClassLoader(getClassLoader())));
        reportView.setTextColor(Color.parseColor("#E2E8F0"));
        reportView.setTypeface(Typeface.MONOSPACE);
        reportView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        reportView.setBackgroundColor(Color.parseColor("#1E293B"));
        reportView.setPadding(pad, pad, pad, pad);
        reportView.setGravity(Gravity.START);
        container.addView(reportView);

        scrollView.addView(container);
        setContentView(scrollView);
    }
}
