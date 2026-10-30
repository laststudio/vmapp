cmake_minimum_required(VERSION 3.22.1)
project("zerobox_core" LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include_directories(
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/../../../../zerobox-sdk/src/main/cpp/include
)

add_library(
    zerobox_core
    SHARED
    vfs/raw_syscall.cpp
    vfs/seccomp_vfs.cpp
    cloak/proc_cloak.cpp
    binder/binder_filter.cpp
    memory/memory_engine.cpp
    hook/art_hook.cpp
    zerobox_jni.cpp
)

target_compile_options(zerobox_core PRIVATE -Wall -Wextra -Wno-unused-parameter -fvisibility=hidden -O2)
target_link_libraries(zerobox_core android log dl)
