cmake_minimum_required(VERSION 3.22.1)
project("zerobox_sample_plugin" LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include_directories(${CMAKE_CURRENT_SOURCE_DIR}/../../../../zerobox-sdk/src/main/cpp/include)

add_library(zerobox_sample_plugin SHARED sample_native_plugin.cpp)
target_link_libraries(zerobox_sample_plugin log)
