cmake_minimum_required(VERSION 3.22.1)
project(glia_native LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# llama.cpp (integracao real)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
add_subdirectory(llama.cpp)

include_directories(${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/include)
include_directories(${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/ggml/include)

add_library(glia_native SHARED
    llama_jni.cpp
    audio_processor.cpp
)

find_library(log-lib log)
target_link_libraries(glia_native PRIVATE llama ggml ${log-lib})