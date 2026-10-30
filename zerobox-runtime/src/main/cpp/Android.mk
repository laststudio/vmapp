LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := zerobox_core
LOCAL_C_INCLUDES := \
    $(LOCAL_PATH) \
    $(LOCAL_PATH)/../../../../zerobox-sdk/src/main/cpp/include

LOCAL_SRC_FILES := \
    vfs/raw_syscall.cpp \
    vfs/seccomp_vfs.cpp \
    cloak/proc_cloak.cpp \
    binder/binder_filter.cpp \
    memory/memory_engine.cpp \
    hook/art_hook.cpp \
    zerobox_jni.cpp

LOCAL_CFLAGS := -Wall -Wextra -Wno-unused-parameter -fvisibility=hidden -O2
LOCAL_CPPFLAGS := -std=c++17 -fno-exceptions -fno-rtti
LOCAL_LDLIBS := -llog -ldl
include $(BUILD_SHARED_LIBRARY)
