cmake_minimum_required(VERSION 3.22.1)
project(zipdoctor_times LANGUAGES C)

add_library(zipdoctor_times SHARED file_times.c)
target_compile_definitions(zipdoctor_times PRIVATE _FILE_OFFSET_BITS=64)
target_compile_options(zipdoctor_times PRIVATE -Wall -Wextra -Werror)
