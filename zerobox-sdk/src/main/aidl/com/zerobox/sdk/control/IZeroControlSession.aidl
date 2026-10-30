package com.zerobox.sdk.control;

/**
 * Direct Binder control session attached to a running ZeroBox guest container process.
 * Inspired by Shizuku's direct Binder bridge architecture, allowing authorized controllers
 * to perform real-time memory R/W, pattern scanning, private directory inspection, and
 * dynamic passthrough/plugin injection without modifying the guest APK.
 */
interface IZeroControlSession {
    int getPid();
    String getPackageName();
    int getUserId();
    String getProcessName();

    List<String> getMemoryRegions(String filterSubstring);
    byte[] readMemory(long address, int length);
    int writeMemory(long address, in byte[] data);
    long[] scanPattern(String moduleFilter, String hexPattern, int maxResults);

    List<String> listPrivateFiles(String relativeDir);
    byte[] readPrivateFile(String relativePath);
    boolean writePrivateFile(String relativePath, in byte[] data, boolean append);

    boolean addPassthroughPath(String pathPrefix);
    boolean addRedirectRule(String virtualPrefix, String realPrefix);
    boolean loadNativePlugin(String soFilePath);

    String runStealthSelfCheckReport();
}
