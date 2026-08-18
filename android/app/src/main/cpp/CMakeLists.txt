# llama.cpp bridge for the briefing composer. CPU-only on purpose: llama.cpp's Android GPU
# story (OpenCL/Vulkan) is device-lottery, while a 2B Q4 on 4 big-ish cores comfortably clears
# the one-short-sentence latency budget. KleidiAI kernels give the arm64 i8mm fast path.
cmake_minimum_required(VERSION 3.22)
project(mandopop_llama C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_CPU_KLEIDIAI ON CACHE BOOL "" FORCE)
endif()

add_subdirectory(${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/llama.cpp llama-build)

add_library(mandopop_llama SHARED llama_bridge.cpp)

target_include_directories(mandopop_llama PRIVATE
        ${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/llama.cpp/include
        ${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/llama.cpp/ggml/include)

target_link_libraries(mandopop_llama llama android log)
