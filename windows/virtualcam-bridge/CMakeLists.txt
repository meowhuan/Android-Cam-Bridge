cmake_minimum_required(VERSION 3.22)
project(acb_virtualcam_bridge LANGUAGES CXX)

add_executable(acb-virtualcam-bridge src/main.cpp)

target_link_libraries(acb-virtualcam-bridge PRIVATE ws2_32)
