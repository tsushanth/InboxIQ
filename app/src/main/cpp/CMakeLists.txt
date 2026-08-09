cmake_minimum_required(VERSION 3.22.1)
project(inboxiq_whisper)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Optimization flags (avoid -ffast-math as ggml needs non-finite math)
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O3 -DNDEBUG")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -DNDEBUG")

# 16 KB page size support (required by Google Play)
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384")

# ARM NEON support for ARM architectures
if(${ANDROID_ABI} STREQUAL "arm64-v8a")
    set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -march=armv8-a+fp+simd")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -march=armv8-a+fp+simd")
elseif(${ANDROID_ABI} STREQUAL "armeabi-v7a")
    set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -mfpu=neon -mfloat-abi=softfp")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -mfpu=neon -mfloat-abi=softfp")
endif()

# whisper.cpp source directory
set(WHISPER_CPP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp)

# Check if whisper.cpp exists
if(NOT EXISTS ${WHISPER_CPP_DIR}/src/whisper.cpp)
    message(FATAL_ERROR "whisper.cpp not found! Run ./setup_whisper.sh first.")
endif()

# ggml sources - adapted for newer whisper.cpp structure
set(GGML_SOURCES
    ${WHISPER_CPP_DIR}/ggml/src/ggml.c
    ${WHISPER_CPP_DIR}/ggml/src/ggml-alloc.c
    ${WHISPER_CPP_DIR}/ggml/src/ggml-backend.cpp
    ${WHISPER_CPP_DIR}/ggml/src/ggml-quants.c
    ${WHISPER_CPP_DIR}/ggml/src/ggml-aarch64.c
    ${WHISPER_CPP_DIR}/ggml/src/ggml-cpu.c
)

# Add ggml library
add_library(ggml STATIC ${GGML_SOURCES})

target_include_directories(ggml PUBLIC
    ${WHISPER_CPP_DIR}/ggml/include
    ${WHISPER_CPP_DIR}/ggml/src
)

target_compile_definitions(ggml PUBLIC
    GGML_USE_CPU
    _GNU_SOURCE
)

# Add whisper library
add_library(whisper STATIC
    ${WHISPER_CPP_DIR}/src/whisper.cpp
)

target_include_directories(whisper PUBLIC
    ${WHISPER_CPP_DIR}/include
    ${WHISPER_CPP_DIR}/src
    ${WHISPER_CPP_DIR}/ggml/include
    ${WHISPER_CPP_DIR}/ggml/src
)

target_link_libraries(whisper ggml)

# JNI bridge library
add_library(whisper_jni SHARED
    whisper_jni.cpp
)

target_include_directories(whisper_jni PRIVATE
    ${WHISPER_CPP_DIR}/include
    ${WHISPER_CPP_DIR}/ggml/include
)

# 16 KB page size support (required by Google Play)
target_link_options(whisper_jni PRIVATE "-Wl,-z,max-page-size=16384")

target_link_libraries(whisper_jni
    whisper
    ggml
    android
    log
)
