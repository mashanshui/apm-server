cmake_minimum_required(VERSION 3.22.1)

project(apm_anr LANGUAGES CXX)

find_package(bytehook REQUIRED CONFIG)

# ByteHook SO 由依赖 AAR 提供；只取 Prefab 的头文件和链接目录，避免把同一 SO 再打进本模块。
get_target_property(BYTEHOOK_INCLUDES bytehook::bytehook INTERFACE_INCLUDE_DIRECTORIES)
get_target_property(BYTEHOOK_LIBRARY bytehook::bytehook IMPORTED_LOCATION)
get_filename_component(BYTEHOOK_LIBRARY_DIR "${BYTEHOOK_LIBRARY}" DIRECTORY)

add_library(apm_anr SHARED
    AnrDumper.cc
    MatrixTracer.cc
    SignalHandler.cc
    Support.cc
    nativehelper/managed_jnienv.cc
)
target_compile_features(apm_anr PRIVATE cxx_std_17)
target_include_directories(apm_anr PRIVATE ${BYTEHOOK_INCLUDES})
target_link_directories(apm_anr PRIVATE "${BYTEHOOK_LIBRARY_DIR}")
target_link_libraries(apm_anr "-lbytehook" log)
