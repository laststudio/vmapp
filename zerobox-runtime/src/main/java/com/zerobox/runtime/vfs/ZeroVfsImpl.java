package com.zerobox.runtime.vfs;

import com.zerobox.runtime.nativebridge.ZeroNativeBridge;
import com.zerobox.sdk.vfs.VfsRule;
import com.zerobox.sdk.vfs.ZeroVfs;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Runtime implementation of {@link ZeroVfs}, synchronizing Java-side {@link VfsRuleMatcher}
 * with the native Seccomp-BPF kernel syscall boundary table.
 */
public final class ZeroVfsImpl implements ZeroVfs {

    private final File isolatedDataDir;
    private final VfsRuleMatcher matcher;

    public ZeroVfsImpl(File isolatedDataDir, String guestPackage, int userId, File originalApkFile) {
        this.isolatedDataDir = isolatedDataDir;
        this.matcher = new VfsRuleMatcher();

        if (!isolatedDataDir.exists()) {
            isolatedDataDir.mkdirs();
        }
        new File(isolatedDataDir, "files").mkdirs();
        new File(isolatedDataDir, "cache").mkdirs();
        new File(isolatedDataDir, "shared_prefs").mkdirs();
        new File(isolatedDataDir, "databases").mkdirs();

        String realDataPath = isolatedDataDir.getAbsolutePath();
        matcher.addRule(VfsRule.passthrough("/system"));
        matcher.addRule(VfsRule.passthrough("/apex"));
        matcher.addRule(VfsRule.passthrough("/vendor"));
        matcher.addRule(VfsRule.passthrough(realDataPath));

        if (guestPackage != null && !guestPackage.isEmpty()) {
            matcher.addRule(VfsRule.redirect("/data/data/" + guestPackage, realDataPath));
            matcher.addRule(VfsRule.redirect("/data/user/0/" + guestPackage, realDataPath));
            matcher.addRule(VfsRule.redirect("/data/user/" + userId + "/" + guestPackage, realDataPath));
        }

        if (originalApkFile != null && guestPackage != null && !guestPackage.isEmpty()) {
            String origApkPath = originalApkFile.getAbsolutePath();
            matcher.addRule(VfsRule.redirect("/data/app/" + guestPackage + "/base.apk", origApkPath));
            matcher.addRule(VfsRule.redirect("/data/app/" + guestPackage + "-1/base.apk", origApkPath));
        }
    }

    @Override
    public File getIsolatedDataDir() {
        return isolatedDataDir;
    }

    @Override
    public String resolvePath(String virtualPath) {
        if (ZeroNativeBridge.isNativeAvailable()) {
            String nativeResolved = ZeroNativeBridge.nativeResolvePath(virtualPath);
            if (nativeResolved != null && !nativeResolved.isEmpty()) {
                return nativeResolved;
            }
        }
        return matcher.resolve(virtualPath);
    }

    @Override
    public void addPassthroughPath(String pathPrefix) {
        matcher.addRule(VfsRule.passthrough(pathPrefix));
        if (ZeroNativeBridge.isNativeAvailable()) {
            ZeroNativeBridge.nativeAddPassthroughPath(pathPrefix);
        }
    }

    @Override
    public void addRedirectRule(String virtualPrefix, String realPrefix) {
        matcher.addRule(VfsRule.redirect(virtualPrefix, realPrefix));
        if (ZeroNativeBridge.isNativeAvailable()) {
            ZeroNativeBridge.nativeAddRedirectRule(virtualPrefix, realPrefix);
        }
    }

    @Override
    public void addHiddenPath(String pathPrefix) {
        matcher.addRule(VfsRule.hide(pathPrefix));
        if (ZeroNativeBridge.isNativeAvailable()) {
            ZeroNativeBridge.nativeAddHiddenPath(pathPrefix);
        }
    }

    @Override
    public List<String> listPrivateFiles(String relativeDir) {
        File targetDir = resolveSafePrivateSubpath(relativeDir);
        if (!targetDir.exists() || !targetDir.isDirectory()) {
            return Collections.emptyList();
        }
        File[] children = targetDir.listFiles();
        if (children == null) {
            return Collections.emptyList();
        }
        List<String> names = new ArrayList<>(children.length);
        for (File child : children) {
            names.add(child.isDirectory() ? child.getName() + "/" : child.getName());
        }
        Collections.sort(names);
        return names;
    }

    @Override
    public byte[] readPrivateFile(String relativePath) throws IOException {
        File targetFile = resolveSafePrivateSubpath(relativePath);
        if (!targetFile.exists() || !targetFile.isFile()) {
            throw new IOException("Private file not found: " + relativePath);
        }
        try (FileInputStream fis = new FileInputStream(targetFile);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = fis.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        }
    }

    @Override
    public void writePrivateFile(String relativePath, byte[] content, boolean append) throws IOException {
        File targetFile = resolveSafePrivateSubpath(relativePath);
        File parent = targetFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        try (FileOutputStream fos = new FileOutputStream(targetFile, append)) {
            if (content != null && content.length > 0) {
                fos.write(content);
            }
        }
    }

    public VfsRuleMatcher getRuleMatcher() {
        return matcher;
    }

    private File resolveSafePrivateSubpath(String relativePath) {
        if (relativePath == null || relativePath.trim().isEmpty() || ".".equals(relativePath.trim())) {
            return isolatedDataDir;
        }
        File candidate = new File(isolatedDataDir, relativePath);
        try {
            String baseCanonical = isolatedDataDir.getCanonicalPath();
            String targetCanonical = candidate.getCanonicalPath();
            if (!targetCanonical.startsWith(baseCanonical)) {
                throw new SecurityException("Path traversal outside isolated data dir rejected: " + relativePath);
            }
            return candidate;
        } catch (IOException e) {
            return candidate;
        }
    }
}
