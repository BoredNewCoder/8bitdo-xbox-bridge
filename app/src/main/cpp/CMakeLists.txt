cmake_minimum_required(VERSION 3.22.1)
project("gipuinput")

add_library(gipuinput SHARED
    uinput_gamepad.c
)

target_link_libraries(gipuinput
    log
)
