package com.zerobox.runtime;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.zerobox.runtime.config.PassthroughConfig;
import com.zerobox.runtime.hook.ZeroHookImpl;
import com.zerobox.runtime.module.SiblingModuleLoader;
import com.zerobox.runtime.vfs.ProcMapsSanitizer;
import com.zerobox.runtime.vfs.VfsRuleMatcher;
import com.zerobox.runtime.vfs.ZeroVfsImpl;
import com.zerobox.sdk.hook.ZeroHook;
import com.zerobox.sdk.vfs.VfsRule;
import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import org.junit.Test;

public class ZeroBoxRuntimeTest {

    public static class SampleTarget {
        public String greet(String name) {
            return "Hello, " + name;
        }

        public String formatMixed(String prefix, int version, boolean flag, long timestamp) {
            return prefix + ":" + version + ":" + flag + ":" + timestamp;
        }
    }

    @Test
    public void testPassthroughConfigSerializationAndPresets() {
        PassthroughConfig stealth = PassthroughConfig.createPureStealthPreset();
        assertTrue(stealth.isEnableSeccompVfs());
        assertTrue(stealth.isEnableProcCloak());
        assertFalse(stealth.isEnableJavaHookEngine()); // Lazy-loaded: off by default
        stealth.addCustomPassthroughPath("/sdcard/Download");

        String serialized = stealth.serialize();
        PassthroughConfig restored = PassthroughConfig.deserialize(serialized);
        assertEquals(stealth.isEnableSeccompVfs(), restored.isEnableSeccompVfs());
        assertEquals(stealth.isEnableProcCloak(), restored.isEnableProcCloak());
        assertEquals(stealth.isEnableJavaHookEngine(), restored.isEnableJavaHookEngine());
        assertTrue(restored.getCustomPassthroughPaths().contains("/sdcard/Download"));
    }

    @Test
    public void testVfsRuleMatcherPriorityOrderPassthroughOverRedirect() {
        VfsRuleMatcher matcher = new VfsRuleMatcher();
        matcher.addRule(VfsRule.redirect("/data/user/0/com.target", "/host/users/0/com.target"));
        matcher.addRule(VfsRule.passthrough("/data/user/0/com.target/shared_passthrough"));
        matcher.addRule(VfsRule.hide("/data/user/0/com.target/secret_probe"));

        // Redirected path
        assertEquals(
                "/host/users/0/com.target/databases/app.db",
                matcher.resolve("/data/user/0/com.target/databases/app.db"));

        // Passthrough path wins over redirect prefix ("Hiding > Confrontation")
        assertEquals(
                "/data/user/0/com.target/shared_passthrough/config.json",
                matcher.resolve("/data/user/0/com.target/shared_passthrough/config.json"));

        // Hidden path returns null (signaling -ENOENT at kernel boundary)
        assertNull(matcher.resolve("/data/user/0/com.target/secret_probe/check.txt"));
    }

    @Test
    public void testProcMapsSanitizerHidesContainerAndModuleTraces() {
        ProcMapsSanitizer sanitizer = new ProcMapsSanitizer();
        sanitizer.configure(
                "com.game.target",
                "/data/user/0/com.zerobox.app/files/zerobox_env/apps/com.game.target/base.apk");
        sanitizer.addCloakToken("libcustom_plugin.so");

        String rawMaps =
                "70000000-70010000 r-xp 00000000 fd:00 101 /apex/com.android.runtime/lib64/bionic/libc.so\n"
                + "71000000-71020000 r-xp 00000000 fd:00 102 /data/app/com.zerobox.app/lib/arm64/libzerobox_core.so\n"
                + "72000000-72010000 r--p 00000000 fd:00 103 /data/user/0/com.zerobox.app/files/zerobox_env/apps/com.game.target/base.apk\n"
                + "73000000-73005000 r-xp 00000000 fd:00 104 /data/local/tmp/libcustom_plugin.so\n";

        String cleanMaps = sanitizer.sanitizeMapsContent(rawMaps);
        assertTrue(cleanMaps.contains("libc.so"));
        assertFalse(cleanMaps.contains("libzerobox_core.so"));
        assertFalse(cleanMaps.contains("libcustom_plugin.so"));
        assertFalse(cleanMaps.contains("com.zerobox.app"));
        assertTrue(cleanMaps.contains("/data/app/com.game.target-1/base.apk"));
    }

    @Test
    public void testSiblingClassLoaderOneWayIsolation() throws Exception {
        // Create an isolated guest ClassLoader whose parent is Bootstrap ClassLoader only
        ClassLoader isolatedGuestLoader = new URLClassLoader(new URL[0], Object.class.getClassLoader());

        // Guest ClassLoader must NOT be able to load ZeroBox runtime or SDK classes
        try {
            isolatedGuestLoader.loadClass("com.zerobox.sdk.api.ZeroModule");
            fail("Guest ClassLoader should not see ZeroBox SDK classes");
        } catch (ClassNotFoundException expected) {
            // Expected!
        }

        // SiblingBridgeClassLoader can load ZeroBox SDK classes while delegating to guest ClassLoader
        SiblingModuleLoader.SiblingBridgeClassLoader siblingLoader =
                new SiblingModuleLoader.SiblingBridgeClassLoader(
                        ZeroBoxRuntimeTest.class.getClassLoader(),
                        isolatedGuestLoader);

        Class<?> loadedFromSibling = siblingLoader.loadClass("com.zerobox.sdk.api.ZeroModule");
        assertNotNull(loadedFromSibling);
    }

    @Test
    public void testZeroHookLifecycleAndShortCircuit() throws Throwable {
        ZeroHookImpl hookEngine = new ZeroHookImpl();
        Method targetMethod = SampleTarget.class.getDeclaredMethod("greet", String.class);
        SampleTarget instance = new SampleTarget();

        ZeroHook.Unhook unhook = hookEngine.hookMethod(targetMethod, new ZeroHook.MethodCallback() {
            @Override
            public void beforeCall(ZeroHook.HookFrame frame) {
                frame.args[0] = frame.args[0] + " (Hooked)";
            }

            @Override
            public void afterCall(ZeroHook.HookFrame frame) {
                frame.setResult(frame.getResult() + "!");
            }
        });

        Object hookedResult = hookEngine.dispatchHookedCall(
                targetMethod,
                instance,
                new Object[] {"World"});
        assertEquals("Hello, World (Hooked)!", hookedResult);

        unhook.unhook();
        Object unhookedResult = hookEngine.dispatchHookedCall(
                targetMethod,
                instance,
                new Object[] {"World"});
        assertEquals("Hello, World", unhookedResult);
    }

    @Test
    public void testZeroVfsImplPrivateDirectoryReadWrite() throws Exception {
        File tempDir = Files.createTempDirectory("zerobox_vfs_test").toFile();
        try {
            File fakeApk = new File(tempDir, "orig_base.apk");
            fakeApk.createNewFile();
            File dataDir = new File(tempDir, "isolated_data");

            ZeroVfsImpl vfs = new ZeroVfsImpl(dataDir, "com.example.guest", 0, fakeApk);
            byte[] payload = "secret_token=12345".getBytes(StandardCharsets.UTF_8);
            vfs.writePrivateFile("files/token.txt", payload, false);

            List<String> files = vfs.listPrivateFiles("files");
            assertTrue(files.contains("token.txt"));

            byte[] readBack = vfs.readPrivateFile("files/token.txt");
            assertArrayEquals(payload, readBack);
        } finally {
            deleteRecursively(tempDir);
        }
    }

    @Test
    public void testProcStatusSeccompAndTracerPidSanitization() {
        ProcMapsSanitizer sanitizer = new ProcMapsSanitizer();
        String rawStatus =
                "Name:\tcom.game.target\n"
                + "State:\tR (running)\n"
                + "TracerPid:\t4321\n"
                + "Seccomp:\t2\n"
                + "Seccomp_filters:\t1\n"
                + "Threads:\t18\n";

        String cleanStatus = sanitizer.sanitizeStatusContent(rawStatus);
        assertTrue(cleanStatus.contains("Seccomp:\t0\n"));
        assertTrue(cleanStatus.contains("Seccomp_filters:\t0\n"));
        assertTrue(cleanStatus.contains("TracerPid:\t0\n"));
        assertFalse(cleanStatus.contains("Seccomp:\t2"));
        assertFalse(cleanStatus.contains("TracerPid:\t4321"));
    }

    @Test
    public void testDexShortyDescriptorComputation() throws Exception {
        Method greetMethod = SampleTarget.class.getDeclaredMethod("greet", String.class);
        assertEquals("LL", ZeroHookImpl.computeShorty(greetMethod));

        Method mixedMethod = SampleTarget.class.getDeclaredMethod(
                "formatMixed",
                String.class,
                int.class,
                boolean.class,
                long.class);
        assertEquals("LLIZJ", ZeroHookImpl.computeShorty(mixedMethod));
    }

    private static void deleteRecursively(File f) {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) {
                    deleteRecursively(c);
                }
            }
        }
        f.delete();
    }
}
