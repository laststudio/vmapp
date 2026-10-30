#include "zerobox_core.h"

#include <jni.h>
#include <string>
#include <vector>

namespace {

JavaVM* g_java_vm = nullptr;

std::string JStringToStdString(JNIEnv* env, jstring jstr) {
    if (jstr == nullptr) return {};
    const char* chars = env->GetStringUTFChars(jstr, nullptr);
    if (chars == nullptr) return {};
    std::string out(chars);
    env->ReleaseStringUTFChars(jstr, chars);
    return out;
}

} // namespace

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    g_java_vm = vm;
    return JNI_VERSION_1_6;
}

extern "C" {

JNIEXPORT void JNICALL
Java_com_zerobox_runtime_nativebridge_ZeroNativeBridge_nativeCalibrationProbeA(
    JNIEnv* /*env*/,
    jclass /*clazz*/
) {}

JNIEXPORT void JNICALL
Java_com_zerobox_runtime_nativebridge_ZeroNativeBridge_nativeCalibrationProbeB(
    JNIEnv* /*env*/,
    jclass /*clazz*/
) {}

JNIEXPORT jint JNICALL
Java_com_zerobox_runtime_nativebridge_ZeroNativeBridge_nativeInstallArtHook(
    JNIEnv* env,
    jclass clazz,
    jobject reflectedMethod,
    jboolean isStatic,
    jstring jShorty
) {
    std::string shorty = JStringToStdString(env, jShorty);
    return static_cast<jint>(
        zerobox::arthook::InstallArtMethodHook(env, clazz, reflectedMethod, isStatic, shorty.c_str())
    );
}

JNIEXPORT jboolean JNICALL
Java_com_zerobox_runtime_nativebridge_ZeroNativeBridge_nativeRestoreArtHook(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jint slotIndex
) {
    return zerobox::arthook::RestoreArtMethodHook(static_cast<int>(slotIndex))
        ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jbyteArray JNICALL
Java_com_zerobox_runtime_nativebridge_ZeroNativeBridge_nativeSwapOriginalArtMethod(
    JNIEnv* env,
    jclass /*clazz*/,
    jint slotIndex
) {
    uint8_t saved[64] = {0};
    if (!zerobox::arthook::TemporarilySwapOriginalBytes(static_cast<int>(slotIndex), saved)) {
        return nullptr;
    }
    jbyteArray arr = env->NewByteArray(64);
    if (arr != nullptr) {
        env->SetByteArrayRegion(arr, 0, 64, reinterpret_cast<const jbyte*>(saved));
    }
    return arr;
}

JNIEXPORT void JNICALL
Java_com_zerobox_runtime_nativebridge_ZeroNativeBridge_nativeReapplyArtHook(
    JNIEnv* env,
    jclass /*clazz*/,
    jint slotIndex,
    jbyteArray jSavedBytes
) {
    if (jSavedBytes == nullptr || env->GetArrayLength(jSavedBytes) < 64) {
        return;
    }
    uint8_t saved[64] = {0};
    env->GetByteArrayRegion(jSavedBytes, 0, 64, reinterpret_cast<jbyte*>(saved));
    zerobox::arthook::ReapplyHookedBytes(static_cast<int>(slotIndex), saved);
}

JNIEXPORT jboolean JNICALL
Java_com_zerobox_runtime_nativebridge_ZeroNativeBridge_nativeInitContainer(
    JNIEnv* env,
    jclass /*clazz*/,
    jstring jHostPackage,
    jstring jGuestPackage,
    jint userId,
    jstring jIsolatedDataDir,
    jstring jOriginalApkPath,
    jboolean enableSeccompVfs,
    jboolean enableProcCloak,
    jboolean enableBinderFilter,
    jboolean passthroughHostPms
) {
    std::string host_pkg = JStringToStdString(env, jHostPackage);
    std::string guest_pkg = JStringToStdString(env, jGuestPackage);
    std::string data_dir = JStringToStdString(env, jIsolatedDataDir);
    std::string orig_apk = JStringToStdString(env, jOriginalApkPath);

    if (enableProcCloak) {
        zerobox::cloak::InitializeProcCloak(guest_pkg, data_dir, orig_apk);
    }
    if (enableBinderFilter) {
        zerobox::binder::InitializeBinderFilter(
            host_pkg,
            guest_pkg,
            0,
            userId,
            passthroughHostPms == JNI_TRUE
        );
    }
    if (enableSeccompVfs) {
        bool ok = zerobox::vfs::InitializeSeccompVfs(
            guest_pkg,
            static_cast<int>(userId),
            data_dir,
            orig_apk,
            enableProcCloak == JNI_TRUE,
            enableBinderFilter == JNI_TRUE
        );
        return ok ? JNI_TRUE : JNI_FALSE;
    }
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_zerobox_runtime_nativebridge_ZeroNativeBridge_nativeAddPassthroughPath(
    JNIEnv* env,
    jclass /*clazz*/,
    jstring jPathPrefix
) {
    zerobox::vfs::AddPassthroughPrefix(JStringToStdString(env, jPathPrefix));
}

JNIEXPORT void JNICALL
Java_com_zerobox_runtime_nativebridge_ZeroNativeBridge_nativeAddRedirectRule(
    JNIEnv* env,
    jclass /*clazz*/,
    jstring jVirtualPrefix,
    jstring jRealPrefix
) {
    zerobox::vfs::AddRedirectRule(
        JStringToStdString(env, jVirtualPrefix),
        JStringToStdString(env, jRealPrefix)
    );
}

JNIEXPORT void JNICALL
Java_com_zerobox_runtime_nativebridge_ZeroNativeBridge_nativeAddHiddenPath(
    JNIEnv* env,
    jclass /*clazz*/,
    jstring jPathPrefix
) {
    zerobox::vfs::AddHiddenPrefix(JStringToStdString(env, jPathPrefix));
}

JNIEXPORT jstring JNICALL
Java_com_zerobox_runtime_nativebridge_ZeroNativeBridge_nativeResolvePath(
    JNIEnv* env,
    jclass /*clazz*/,
    jstring jVirtualPath
) {
    std::string vpath = JStringToStdString(env, jVirtualPath);
    int blocked_errno = 0;
    std::string resolved = zerobox::vfs::ResolveVirtualPath(vpath.c_str(), &blocked_errno);
    if (blocked_errno != 0) {
        return env->NewStringUTF("");
    }
    return env->NewStringUTF(resolved.c_str());
}

JNIEXPORT jobjectArray JNICALL
Java_com_zerobox_runtime_nativebridge_ZeroNativeBridge_nativeGetRealMemoryRegions(
    JNIEnv* env,
    jclass /*clazz*/,
    jstring jFilterSubstr
) {
    std::string filter = JStringToStdString(env, jFilterSubstr);
    std::vector<std::string> lines = zerobox::memory::ReadRealProcMapsLines(filter.c_str());
    jclass strClass = env->FindClass("java/lang/String");
    jobjectArray arr = env->NewObjectArray(static_cast<jsize>(lines.size()), strClass, nullptr);
    for (size_t i = 0; i < lines.size(); ++i) {
        jstring s = env->NewStringUTF(lines[i].c_str());
        env->SetObjectArrayElement(arr, static_cast<jsize>(i), s);
        env->DeleteLocalRef(s);
    }
    return arr;
}

JNIEXPORT jint JNICALL
Java_com_zerobox_runtime_nativebridge_ZeroNativeBridge_nativeReadMemory(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong address,
    jbyteArray jBuffer,
    jint offset,
    jint length
) {
    if (jBuffer == nullptr || length <= 0 || offset < 0) {
        return -22;
    }
    jsize arr_len = env->GetArrayLength(jBuffer);
    if (offset + length > arr_len) {
        return -22;
    }
    std::vector<uint8_t> tmp(static_cast<size_t>(length));
    int n = zerobox::memory::SafeReadMemory(
        static_cast<uintptr_t>(address),
        tmp.data(),
        static_cast<size_t>(length)
    );
    if (n > 0) {
        env->SetByteArrayRegion(jBuffer, offset, n, reinterpret_cast<const jbyte*>(tmp.data()));
    }
    return n;
}

JNIEXPORT jint JNICALL
Java_com_zerobox_runtime_nativebridge_ZeroNativeBridge_nativeWriteMemory(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong address,
    jbyteArray jBuffer,
    jint offset,
    jint length
) {
    if (jBuffer == nullptr || length <= 0 || offset < 0) {
        return -22;
    }
    jsize arr_len = env->GetArrayLength(jBuffer);
    if (offset + length > arr_len) {
        return -22;
    }
    std::vector<uint8_t> tmp(static_cast<size_t>(length));
    env->GetByteArrayRegion(jBuffer, offset, length, reinterpret_cast<jbyte*>(tmp.data()));
    return zerobox::memory::SafeWriteMemory(
        static_cast<uintptr_t>(address),
        tmp.data(),
        static_cast<size_t>(length)
    );
}

JNIEXPORT jlongArray JNICALL
Java_com_zerobox_runtime_nativebridge_ZeroNativeBridge_nativeScanPattern(
    JNIEnv* env,
    jclass /*clazz*/,
    jstring jModuleFilter,
    jstring jHexPattern,
    jint maxResults
) {
    std::string mod = JStringToStdString(env, jModuleFilter);
    std::string pat = JStringToStdString(env, jHexPattern);
    std::vector<uintptr_t> hits = zerobox::memory::ScanPatternAll(
        mod.c_str(),
        pat.c_str(),
        static_cast<int>(maxResults)
    );
    jlongArray out = env->NewLongArray(static_cast<jsize>(hits.size()));
    if (!hits.empty()) {
        std::vector<jlong> jhits(hits.size());
        for (size_t i = 0; i < hits.size(); ++i) {
            jhits[i] = static_cast<jlong>(hits[i]);
        }
        env->SetLongArrayRegion(out, 0, static_cast<jsize>(jhits.size()), jhits.data());
    }
    return out;
}

JNIEXPORT jboolean JNICALL
Java_com_zerobox_runtime_nativebridge_ZeroNativeBridge_nativeLoadPlugin(
    JNIEnv* env,
    jclass /*clazz*/,
    jstring jSoPath,
    jstring jGuestPackage,
    jint userId,
    jstring jDataDir
) {
    std::string so_path = JStringToStdString(env, jSoPath);
    std::string guest_pkg = JStringToStdString(env, jGuestPackage);
    std::string data_dir = JStringToStdString(env, jDataDir);
    bool ok = zerobox::memory::LoadNativePlugin(
        g_java_vm,
        so_path.c_str(),
        guest_pkg.c_str(),
        static_cast<int>(userId),
        data_dir.c_str()
    );
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_zerobox_runtime_nativebridge_ZeroNativeBridge_nativeHookPlt(
    JNIEnv* env,
    jclass /*clazz*/,
    jstring jLibName,
    jstring jSymbolName,
    jlong replacementPtr,
    jlongArray jOriginalPtrOut
) {
    std::string lib = JStringToStdString(env, jLibName);
    std::string sym = JStringToStdString(env, jSymbolName);
    void* orig = nullptr;
    int rc = zerobox::memory::HookPltSymbol(
        lib.c_str(),
        sym.c_str(),
        reinterpret_cast<void*>(replacementPtr),
        &orig
    );
    if (rc == 0 && jOriginalPtrOut != nullptr && env->GetArrayLength(jOriginalPtrOut) > 0) {
        jlong val = reinterpret_cast<jlong>(orig);
        env->SetLongArrayRegion(jOriginalPtrOut, 0, 1, &val);
    }
    return rc == 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_com_zerobox_runtime_nativebridge_ZeroNativeBridge_nativeGetLibcTextHash(
    JNIEnv* /*env*/,
    jclass /*clazz*/
) {
    return static_cast<jlong>(zerobox::memory::ComputeExecutableTextHash("libc.so"));
}

JNIEXPORT jboolean JNICALL
Java_com_zerobox_runtime_nativebridge_ZeroNativeBridge_nativeIsSeccompActive(
    JNIEnv* /*env*/,
    jclass /*clazz*/
) {
    return zerobox::vfs::IsSeccompActive() ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
