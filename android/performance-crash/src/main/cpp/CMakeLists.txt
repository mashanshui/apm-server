cmake_minimum_required(VERSION 3.22.1)
project(apm_crash C)

# 回溯源码固定在本模块内；调用方不需要检出 xCrash 或配置其原生源码。
set(XCRASH_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/third_party/xcrash)
file(GLOB XCRASH_UNWIND_SOURCES CONFIGURE_DEPENDS
        ${XCRASH_ROOT}/dumper/*.c
        ${XCRASH_ROOT}/common/*.c
        ${XCRASH_ROOT}/lzma/*.c)

add_library(apm_crash_unwind STATIC ${XCRASH_UNWIND_SOURCES})
set_target_properties(apm_crash_unwind PROPERTIES POSITION_INDEPENDENT_CODE ON)
target_include_directories(apm_crash_unwind PUBLIC
        ${XCRASH_ROOT}/common ${XCRASH_ROOT}/dumper ${XCRASH_ROOT}/lzma)
target_compile_definitions(apm_crash_unwind PRIVATE _7ZIP_ST)
target_compile_options(apm_crash_unwind PRIVATE -ffunction-sections -fdata-sections -fvisibility=hidden)

# JNI 入口和独立执行程序采用各自名称，避免与其他 SDK 发生 SO 名称冲突。
add_library(apm_crash SHARED native_bridge.c)
if(CMAKE_BUILD_TYPE STREQUAL "Debug")
    # 受控信号触发入口只存在于 Debug 测试构建。
    target_compile_definitions(apm_crash PRIVATE APM_NATIVE_TEST=1)
endif()
target_link_libraries(apm_crash PRIVATE apm_crash_unwind log dl)
target_link_options(apm_crash PRIVATE -Wl,--gc-sections -Wl,-Bsymbolic-functions -Wl,-z,max-page-size=16384)

add_executable(apm_crash_dumper native_dumper.c native_diagnostics.c)
target_link_libraries(apm_crash_dumper PRIVATE apm_crash_unwind log dl)
target_link_options(apm_crash_dumper PRIVATE -Wl,--gc-sections -Wl,-z,max-page-size=16384)
set_target_properties(apm_crash_dumper PROPERTIES PREFIX "lib" SUFFIX ".so")
