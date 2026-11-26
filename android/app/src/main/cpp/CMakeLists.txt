cmake_minimum_required(VERSION 3.22.1)
project(vgo_jni LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(VGO_CORE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../src")

add_library(vgo_jni SHARED
    "${VGO_CORE_DIR}/viet.cpp"
    "${VGO_CORE_DIR}/engine.cpp"
    "${VGO_CORE_DIR}/vni.cpp"
    "${VGO_CORE_DIR}/learn.cpp"
    vgo_bridge.cpp
    vgo_jni.cpp
)

target_include_directories(vgo_jni PRIVATE
    "${VGO_CORE_DIR}"
    "${CMAKE_CURRENT_SOURCE_DIR}"
)

target_compile_options(vgo_jni PRIVATE -O2 -Wall -Wextra)
find_library(log-lib log)
target_link_libraries(vgo_jni ${log-lib})
