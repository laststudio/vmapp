package com.zerobox.sdk.vfs;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Virtual File System & Private Directory Bridge API.
 * Allows modules and controllers to directly inspect/modify the guest application's isolated
 * private directory ({@code /data/user/0/<pkg>}) as well as configure runtime passthrough
 * and redirection rules on the Seccomp-BPF kernel boundary.
 */
public interface ZeroVfs {

    /**
     * Returns the host directory backing the guest application's private data folder.
     */
    File getIsolatedDataDir();

    /**
     * Resolves a virtual path (as seen by the guest app) to its real host filesystem path
     * according to the active passthrough and redirection policy.
     */
    String resolvePath(String virtualPath);

    /**
     * Registers a high-priority passthrough path prefix so matching file operations
     * go straight to the real Android OS without virtual redirection.
     */
    void addPassthroughPath(String pathPrefix);

    /**
     * Registers a path prefix redirection rule ({@code virtualPrefix -> realPrefix}).
     */
    void addRedirectRule(String virtualPrefix, String realPrefix);

    /**
     * Hides a specific file or directory prefix from the guest app (syscall returns -ENOENT).
     */
    void addHiddenPath(String pathPrefix);

    /**
     * Lists files inside a relative path of the guest application's private directory.
     */
    List<String> listPrivateFiles(String relativeDir);

    /**
     * Reads all bytes from a file inside the guest application's private directory.
     */
    byte[] readPrivateFile(String relativePath) throws IOException;

    /**
     * Writes bytes to a file inside the guest application's private directory.
     */
    void writePrivateFile(String relativePath, byte[] content, boolean append) throws IOException;
}
