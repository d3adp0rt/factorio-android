cmake_minimum_required(VERSION 3.22)
project(zstdjni C)

set(CMAKE_C_STANDARD 11)

# only the decompressor half of zstd, ~1 MB of sources, no external dependencies
file(GLOB ZSTD_SOURCES
        ${CMAKE_CURRENT_SOURCE_DIR}/zstd/common/*.c
        ${CMAKE_CURRENT_SOURCE_DIR}/zstd/decompress/*.c)

add_library(zstdjni SHARED
        ${ZSTD_SOURCES}
        zstd_jni.c)

target_include_directories(zstdjni PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/zstd)
target_compile_definitions(zstdjni PRIVATE ZSTD_DISABLE_ASM ZSTD_LEGACY_SUPPORT=0)
target_compile_options(zstdjni PRIVATE -O2 -fvisibility=hidden)
target_link_libraries(zstdjni PRIVATE log)
