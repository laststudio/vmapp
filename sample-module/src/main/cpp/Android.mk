LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := zerobox_sample_plugin
LOCAL_C_INCLUDES := $(LOCAL_PATH)/../../../../zerobox-sdk/src/main/cpp/include
LOCAL_SRC_FILES := sample_native_plugin.cpp
LOCAL_CFLAGS := -Wall -Wextra -fvisibility=hidden -O2
LOCAL_CPPFLAGS := -std=c++17 -fno-exceptions -fno-rtti
LOCAL_LDLIBS := -llog
include $(BUILD_SHARED_LIBRARY)
