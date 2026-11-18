cmake_minimum_required(VERSION 3.22.1)
project(telemusic_bitperfect CXX)
set(CMAKE_CXX_STANDARD 17)
add_library(telemusic_bitperfect SHARED bitperfect.cpp)
target_link_libraries(telemusic_bitperfect aaudio log)
# Newer AAudio calls are guarded with __builtin_available at runtime.
target_compile_definitions(telemusic_bitperfect PRIVATE __ANDROID_UNAVAILABLE_SYMBOLS_ARE_WEAK__)
target_compile_options(telemusic_bitperfect PRIVATE -Werror=unguarded-availability)
