cmake_minimum_required(VERSION 3.18)
project(cowdoom C)

# doomgeneric é clonado pelo GitHub Actions; sources.cmake vem do Makefile dele (ferramentas/doom_sources.py).
set(DG ${CMAKE_CURRENT_SOURCE_DIR}/doomgeneric/doomgeneric)
include(${CMAKE_CURRENT_SOURCE_DIR}/sources.cmake)
list(TRANSFORM DG_SOURCES PREPEND ${DG}/)

add_library(cowdoom SHARED ${DG_SOURCES} cowdoom_jni.c)
target_include_directories(cowdoom PRIVATE ${DG})
target_compile_definitions(cowdoom PRIVATE NORMALUNIX LINUX _DEFAULT_SOURCE)
target_compile_options(cowdoom PRIVATE -w -fcommon -O2)
target_link_libraries(cowdoom log m)
