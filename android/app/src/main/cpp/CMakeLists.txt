cmake_minimum_required(VERSION 3.22.1)
project(apm_crash_demo C)

# 演示入口独立于 SDK Native 工具，不修改既有库的 ABI。
add_library(apm_crash_demo SHARED native_crash_demo.c)
target_link_options(apm_crash_demo PRIVATE -Wl,-z,max-page-size=16384)
