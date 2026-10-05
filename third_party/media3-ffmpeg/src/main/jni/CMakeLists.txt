# Copyright (C) 2026 abn3li
# SPDX-License-Identifier: GPL-3.0-only
cmake_minimum_required(VERSION 3.21)
project(telemusic_ffmpeg_jni C CXX)
set(CMAKE_CXX_STANDARD 11)

if(NOT FFMPEG_SOURCE_DIR OR NOT FFMPEG_STATIC_DIR)
    message(FATAL_ERROR "Use tools/licensing/build_ffmpeg.py to supply the pinned FFmpeg build.")
endif()

foreach(name avutil swresample avcodec)
    add_library(${name} STATIC IMPORTED)
    set_target_properties(${name} PROPERTIES IMPORTED_LOCATION "${FFMPEG_STATIC_DIR}/lib${name}/lib${name}.a")
endforeach()

add_library(ffmpegJNI SHARED ffmpeg_jni.cc)
# FFmpeg's VERSION file otherwise shadows libc++'s <version> on Windows.
target_compile_options(ffmpegJNI PRIVATE "-idirafter" "${FFMPEG_SOURCE_DIR}")
target_link_libraries(ffmpegJNI PRIVATE swresample avcodec avutil android log)
# Keep the decoder usable on phones with either 4 KiB or 16 KiB pages.
target_link_options(ffmpegJNI PRIVATE "-Wl,-z,max-page-size=16384")
if(ANDROID_ABI STREQUAL "arm64-v8a")
    target_link_options(ffmpegJNI PRIVATE "-Wl,-Bsymbolic")
endif()
