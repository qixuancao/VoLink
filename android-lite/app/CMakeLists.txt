cmake_minimum_required(VERSION 3.22)
project(CodexVoice LANGUAGES C CXX)
# Reuse the upstream Projucer output without editing generated files.
add_compile_definitions(SONOBUS_VOICE_LITE=1)
set_property(GLOBAL PROPERTY JOB_POOLS voice_compile=4)
set(CMAKE_JOB_POOL_COMPILE voice_compile)
# Build Opus from the included source instead of an opaque prebuilt archive.
set(OPUS_BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_TESTING OFF CACHE BOOL "" FORCE)
add_subdirectory("../../deps/opus" opus-build)
set(opus opus)
add_subdirectory("../../mobile/Builds/Android/app" upstream)
target_link_options(juce_jni PRIVATE "-Wl,-z,max-page-size=16384")
