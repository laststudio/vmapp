#include "zerobox_core.h"

#include <jni.h>
#include <pthread.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

namespace zerobox {
namespace arthook {

namespace {

constexpr uint32_t kAccNative            = 0x0100;
constexpr uint32_t kAccFastNative        = 0x00080000;
constexpr uint32_t kAccCriticalNative    = 0x00200000;
constexpr uint32_t kAccCompileDontBother = 0x02000000;

constexpr int kMaxHookSlots = 64;
constexpr size_t kMaxArtMethodBytes = 64;

struct ArtRuntimeLayout {
    bool calibrated;
    size_t art_method_size;
    size_t access_flags_offset;
    size_t jni_data_offset;
    size_t quick_code_offset;
    void* generic_jni_trampoline;
};

struct HookSlot {
    bool in_use;
    int slot_index;
    uintptr_t target_art_method;
    bool is_static;
    char shorty[16];
    uint8_t original_bytes[kMaxArtMethodBytes];
};

pthread_mutex_t g_art_hook_lock = PTHREAD_MUTEX_INITIALIZER;
ArtRuntimeLayout g_layout = {false, 0, 4, 0, 0, nullptr};
HookSlot g_slots[kMaxHookSlots];
JavaVM* g_vm = nullptr;
jclass g_bridge_class = nullptr;
jmethodID g_dispatch_mid = nullptr;

// Known dummy target used during RegisterNatives runtime layout calibration
void JNICALL CalibrationProbeA(JNIEnv* /*env*/, jclass /*clazz*/) {}
void JNICALL CalibrationProbeB(JNIEnv* /*env*/, jclass /*clazz*/) {}

uint64_t InvokeJavaBridgeDispatcher(
    JNIEnv* caller_env,
    int slot_index,
    jobject receiver_or_class,
    uintptr_t a0,
    uintptr_t a1,
    uintptr_t a2,
    uintptr_t a3,
    uintptr_t a4,
    uintptr_t a5,
    uintptr_t a6,
    uintptr_t a7
) {
    if (g_bridge_class == nullptr || g_dispatch_mid == nullptr) {
        return 0;
    }
    JNIEnv* env = caller_env;
    bool attached = false;
    if (env == nullptr && g_vm != nullptr) {
        jint status = g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
        if (status == JNI_EDETACHED) {
            if (g_vm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
                attached = true;
            } else {
                return 0;
            }
        } else if (status != JNI_OK || env == nullptr) {
            return 0;
        }
    }
    if (env == nullptr) {
        return 0;
    }

    char shorty_buf[16] = {0};
    pthread_mutex_lock(&g_art_hook_lock);
    if (slot_index >= 0 && slot_index < kMaxHookSlots && g_slots[slot_index].in_use) {
        strncpy(shorty_buf, g_slots[slot_index].shorty, sizeof(shorty_buf) - 1);
    }
    pthread_mutex_unlock(&g_art_hook_lock);

    const uintptr_t raw_slots[8] = {a0, a1, a2, a3, a4, a5, a6, a7};
    size_t shorty_len = strlen(shorty_buf);
    size_t param_count = shorty_len > 1 ? (shorty_len - 1) : 0;
    if (param_count > 8) {
        param_count = 8;
    }

    jclass object_class = env->FindClass("java/lang/Object");
    jlongArray raw_args = env->NewLongArray(8);
    jobjectArray obj_args = env->NewObjectArray(8, object_class, nullptr);
    jobjectArray out_obj_ret = env->NewObjectArray(1, object_class, nullptr);

    jlong vals[8] = {0};
    for (size_t i = 0; i < 8; ++i) {
        if (i < param_count && shorty_buf[i + 1] == 'L') {
            if (raw_slots[i] != 0 && obj_args != nullptr) {
                env->SetObjectArrayElement(
                    obj_args,
                    static_cast<jsize>(i),
                    reinterpret_cast<jobject>(raw_slots[i])
                );
            }
        } else {
            vals[i] = static_cast<jlong>(raw_slots[i]);
        }
    }
    if (raw_args != nullptr) {
        env->SetLongArrayRegion(raw_args, 0, 8, vals);
    }

    jlong prim_result = env->CallStaticLongMethod(
        g_bridge_class,
        g_dispatch_mid,
        static_cast<jint>(slot_index),
        receiver_or_class,
        raw_args,
        obj_args,
        out_obj_ret
    );

    uint64_t final_ret = static_cast<uint64_t>(prim_result);
    if (shorty_len > 0 && shorty_buf[0] == 'L' && out_obj_ret != nullptr && !env->ExceptionCheck()) {
        jobject ret_obj = env->GetObjectArrayElement(out_obj_ret, 0);
        final_ret = static_cast<uint64_t>(reinterpret_cast<uintptr_t>(ret_obj));
    }

    if (raw_args != nullptr) {
        env->DeleteLocalRef(raw_args);
    }
    if (obj_args != nullptr) {
        env->DeleteLocalRef(obj_args);
    }
    if (out_obj_ret != nullptr) {
        env->DeleteLocalRef(out_obj_ret);
    }
    if (object_class != nullptr) {
        env->DeleteLocalRef(object_class);
    }
    if (env->ExceptionCheck()) {
        // Leave pending exception for ART to unwind to Java caller
    }
    if (attached && g_vm != nullptr) {
        g_vm->DetachCurrentThread();
    }
    return final_ret;
}

template <int N>
uint64_t JNICALL NativeHookTrampolineStub(
    JNIEnv* env,
    jobject receiver_or_class,
    uintptr_t a0,
    uintptr_t a1,
    uintptr_t a2,
    uintptr_t a3,
    uintptr_t a4,
    uintptr_t a5,
    uintptr_t a6,
    uintptr_t a7
) {
    return InvokeJavaBridgeDispatcher(env, N, receiver_or_class, a0, a1, a2, a3, a4, a5, a6, a7);
}

using StubFnPtr = uint64_t (JNICALL *)(
    JNIEnv*,
    jobject,
    uintptr_t,
    uintptr_t,
    uintptr_t,
    uintptr_t,
    uintptr_t,
    uintptr_t,
    uintptr_t,
    uintptr_t
);

#define ZB_STUB_ENTRY(n) &NativeHookTrampolineStub<n>

const StubFnPtr kStubTable[kMaxHookSlots] = {
    ZB_STUB_ENTRY(0),  ZB_STUB_ENTRY(1),  ZB_STUB_ENTRY(2),  ZB_STUB_ENTRY(3),
    ZB_STUB_ENTRY(4),  ZB_STUB_ENTRY(5),  ZB_STUB_ENTRY(6),  ZB_STUB_ENTRY(7),
    ZB_STUB_ENTRY(8),  ZB_STUB_ENTRY(9),  ZB_STUB_ENTRY(10), ZB_STUB_ENTRY(11),
    ZB_STUB_ENTRY(12), ZB_STUB_ENTRY(13), ZB_STUB_ENTRY(14), ZB_STUB_ENTRY(15),
    ZB_STUB_ENTRY(16), ZB_STUB_ENTRY(17), ZB_STUB_ENTRY(18), ZB_STUB_ENTRY(19),
    ZB_STUB_ENTRY(20), ZB_STUB_ENTRY(21), ZB_STUB_ENTRY(22), ZB_STUB_ENTRY(23),
    ZB_STUB_ENTRY(24), ZB_STUB_ENTRY(25), ZB_STUB_ENTRY(26), ZB_STUB_ENTRY(27),
    ZB_STUB_ENTRY(28), ZB_STUB_ENTRY(29), ZB_STUB_ENTRY(30), ZB_STUB_ENTRY(31),
    ZB_STUB_ENTRY(32), ZB_STUB_ENTRY(33), ZB_STUB_ENTRY(34), ZB_STUB_ENTRY(35),
    ZB_STUB_ENTRY(36), ZB_STUB_ENTRY(37), ZB_STUB_ENTRY(38), ZB_STUB_ENTRY(39),
    ZB_STUB_ENTRY(40), ZB_STUB_ENTRY(41), ZB_STUB_ENTRY(42), ZB_STUB_ENTRY(43),
    ZB_STUB_ENTRY(44), ZB_STUB_ENTRY(45), ZB_STUB_ENTRY(46), ZB_STUB_ENTRY(47),
    ZB_STUB_ENTRY(48), ZB_STUB_ENTRY(49), ZB_STUB_ENTRY(50), ZB_STUB_ENTRY(51),
    ZB_STUB_ENTRY(52), ZB_STUB_ENTRY(53), ZB_STUB_ENTRY(54), ZB_STUB_ENTRY(55),
    ZB_STUB_ENTRY(56), ZB_STUB_ENTRY(57), ZB_STUB_ENTRY(58), ZB_STUB_ENTRY(59),
    ZB_STUB_ENTRY(60), ZB_STUB_ENTRY(61), ZB_STUB_ENTRY(62), ZB_STUB_ENTRY(63)
};

#undef ZB_STUB_ENTRY

} // namespace

bool EnsureArtLayoutCalibrated(JNIEnv* env, jclass bridgeClass) {
    pthread_mutex_lock(&g_art_hook_lock);
    if (g_layout.calibrated) {
        pthread_mutex_unlock(&g_art_hook_lock);
        return true;
    }

    env->GetJavaVM(&g_vm);
    if (g_bridge_class == nullptr) {
        g_bridge_class = reinterpret_cast<jclass>(env->NewGlobalRef(bridgeClass));
    }
    g_dispatch_mid = env->GetStaticMethodID(
        g_bridge_class,
        "onArtHookDispatched",
        "(ILjava/lang/Object;[J[Ljava/lang/Object;[Ljava/lang/Object;)J"
    );
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
    }

    jmethodID midA = env->GetStaticMethodID(bridgeClass, "nativeCalibrationProbeA", "()V");
    jmethodID midB = env->GetStaticMethodID(bridgeClass, "nativeCalibrationProbeB", "()V");
    if (midA == nullptr || midB == nullptr) {
        pthread_mutex_unlock(&g_art_hook_lock);
        return false;
    }

    JNINativeMethod probes[] = {
        {"nativeCalibrationProbeA", "()V", reinterpret_cast<void*>(&CalibrationProbeA)},
        {"nativeCalibrationProbeB", "()V", reinterpret_cast<void*>(&CalibrationProbeB)}
    };
    env->RegisterNatives(bridgeClass, probes, 2);

    // On Android 8.0 ~ 15, jmethodID is either the ArtMethod* directly or an index/pointer;
    // ToReflectedMethod -> FromReflectedMethod guarantees getting the underlying ArtMethod* when non-opaque.
    uintptr_t addrA = reinterpret_cast<uintptr_t>(midA);
    uintptr_t addrB = reinterpret_cast<uintptr_t>(midB);
    if ((addrA & 1U) != 0) {
        // Android 11+ opaque jmethodID bit; extract ArtMethod* from java.lang.reflect.Executable.artMethod field
        jobject reflA = env->ToReflectedMethod(bridgeClass, midA, JNI_TRUE);
        jobject reflB = env->ToReflectedMethod(bridgeClass, midB, JNI_TRUE);
        jclass execClass = env->FindClass("java/lang/reflect/Executable");
        jfieldID artMethodField = env->GetFieldID(execClass, "artMethod", "J");
        if (artMethodField != nullptr && reflA != nullptr && reflB != nullptr) {
            addrA = static_cast<uintptr_t>(env->GetLongField(reflA, artMethodField));
            addrB = static_cast<uintptr_t>(env->GetLongField(reflB, artMethodField));
        }
    }

    size_t diff = (addrB > addrA) ? (addrB - addrA) : (addrA - addrB);
    if (diff < 16 || diff > kMaxArtMethodBytes) {
        diff = sizeof(void*) == 8 ? 32 : 24;
    }
    g_layout.art_method_size = diff;
    g_layout.access_flags_offset = 4;

    // Locate the pointer field containing &CalibrationProbeA
    uintptr_t target_fn = reinterpret_cast<uintptr_t>(&CalibrationProbeA);
    bool found_jni_ptr = false;
    for (size_t off = 8; off + sizeof(void*) <= g_layout.art_method_size; off += sizeof(uint32_t)) {
        uintptr_t val = *reinterpret_cast<uintptr_t*>(addrA + off);
        if (val == target_fn) {
            g_layout.jni_data_offset = off;
            g_layout.quick_code_offset = off + sizeof(void*);
            if (g_layout.quick_code_offset + sizeof(void*) <= kMaxArtMethodBytes) {
                g_layout.generic_jni_trampoline =
                    *reinterpret_cast<void**>(addrA + g_layout.quick_code_offset);
            }
            found_jni_ptr = true;
            break;
        }
    }

    if (!found_jni_ptr) {
        // Fallback standard layout (Android 8 ~ 14)
        g_layout.jni_data_offset = (sizeof(void*) == 8) ? 16 : 16;
        g_layout.quick_code_offset = g_layout.jni_data_offset + sizeof(void*);
        g_layout.generic_jni_trampoline =
            *reinterpret_cast<void**>(addrA + g_layout.quick_code_offset);
    }

    g_layout.calibrated = (g_layout.generic_jni_trampoline != nullptr);
    ZB_LOGI("ART Hook Engine calibrated: size=%zu jni_off=%zu quick_off=%zu generic_tramp=%p",
            g_layout.art_method_size,
            g_layout.jni_data_offset,
            g_layout.quick_code_offset,
            g_layout.generic_jni_trampoline);
    pthread_mutex_unlock(&g_art_hook_lock);
    return g_layout.calibrated;
}

int InstallArtMethodHook(
    JNIEnv* env,
    jclass bridgeClass,
    jobject reflectedMethod,
    jboolean isStatic,
    const char* shorty
) {
    if (reflectedMethod == nullptr) return -1;
    if (!EnsureArtLayoutCalibrated(env, bridgeClass)) {
        return -2;
    }

    jclass execClass = env->FindClass("java/lang/reflect/Executable");
    jfieldID artMethodField = env->GetFieldID(execClass, "artMethod", "J");
    if (artMethodField == nullptr) return -3;

    uintptr_t art_method = static_cast<uintptr_t>(env->GetLongField(reflectedMethod, artMethodField));
    if (art_method == 0) return -4;

    pthread_mutex_lock(&g_art_hook_lock);
    // Check if already hooked
    for (int i = 0; i < kMaxHookSlots; ++i) {
        if (g_slots[i].in_use && g_slots[i].target_art_method == art_method) {
            pthread_mutex_unlock(&g_art_hook_lock);
            return i;
        }
    }

    int slot = -1;
    for (int i = 0; i < kMaxHookSlots; ++i) {
        if (!g_slots[i].in_use) {
            slot = i;
            break;
        }
    }
    if (slot < 0) {
        pthread_mutex_unlock(&g_art_hook_lock);
        return -5;
    }

    HookSlot& s = g_slots[slot];
    s.in_use = true;
    s.slot_index = slot;
    s.target_art_method = art_method;
    s.is_static = (isStatic == JNI_TRUE);
    memset(s.shorty, 0, sizeof(s.shorty));
    if (shorty != nullptr) {
        strncpy(s.shorty, shorty, sizeof(s.shorty) - 1);
    }
    memcpy(s.original_bytes, reinterpret_cast<const void*>(art_method), g_layout.art_method_size);

    // Patch ArtMethod to route through art_quick_generic_jni_trampoline -> kStubTable[slot]
    uint32_t* flags_ptr = reinterpret_cast<uint32_t*>(art_method + g_layout.access_flags_offset);
    uint32_t flags = *flags_ptr;
    flags |= (kAccNative | kAccCompileDontBother);
    flags &= ~(kAccFastNative | kAccCriticalNative);
    *flags_ptr = flags;

    void** jni_ptr = reinterpret_cast<void**>(art_method + g_layout.jni_data_offset);
    void** quick_ptr = reinterpret_cast<void**>(art_method + g_layout.quick_code_offset);
    *jni_ptr = reinterpret_cast<void*>(kStubTable[slot]);
    *quick_ptr = g_layout.generic_jni_trampoline;

    __builtin___clear_cache(
        reinterpret_cast<char*>(art_method),
        reinterpret_cast<char*>(art_method + g_layout.art_method_size)
    );

    pthread_mutex_unlock(&g_art_hook_lock);
    return slot;
}

bool RestoreArtMethodHook(int slotIndex) {
    if (slotIndex < 0 || slotIndex >= kMaxHookSlots) return false;
    pthread_mutex_lock(&g_art_hook_lock);
    HookSlot& s = g_slots[slotIndex];
    if (!s.in_use || s.target_art_method == 0) {
        pthread_mutex_unlock(&g_art_hook_lock);
        return false;
    }
    memcpy(reinterpret_cast<void*>(s.target_art_method), s.original_bytes, g_layout.art_method_size);
    __builtin___clear_cache(
        reinterpret_cast<char*>(s.target_art_method),
        reinterpret_cast<char*>(s.target_art_method + g_layout.art_method_size)
    );
    s.in_use = false;
    pthread_mutex_unlock(&g_art_hook_lock);
    return true;
}

bool TemporarilySwapOriginalBytes(int slotIndex, uint8_t* saved_hooked_bytes) {
    if (slotIndex < 0 || slotIndex >= kMaxHookSlots) return false;
    pthread_mutex_lock(&g_art_hook_lock);
    HookSlot& s = g_slots[slotIndex];
    if (!s.in_use || s.target_art_method == 0) {
        pthread_mutex_unlock(&g_art_hook_lock);
        return false;
    }
    memcpy(saved_hooked_bytes, reinterpret_cast<const void*>(s.target_art_method), g_layout.art_method_size);
    memcpy(reinterpret_cast<void*>(s.target_art_method), s.original_bytes, g_layout.art_method_size);
    pthread_mutex_unlock(&g_art_hook_lock);
    return true;
}

void ReapplyHookedBytes(int slotIndex, const uint8_t* saved_hooked_bytes) {
    if (slotIndex < 0 || slotIndex >= kMaxHookSlots) return;
    pthread_mutex_lock(&g_art_hook_lock);
    HookSlot& s = g_slots[slotIndex];
    if (s.in_use && s.target_art_method != 0) {
        memcpy(reinterpret_cast<void*>(s.target_art_method), saved_hooked_bytes, g_layout.art_method_size);
    }
    pthread_mutex_unlock(&g_art_hook_lock);
}

} // namespace arthook
} // namespace zerobox
