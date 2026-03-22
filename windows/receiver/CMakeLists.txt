cmake_minimum_required(VERSION 3.22)
project(acb_receiver LANGUAGES CXX)

add_executable(acb-receiver
  src/main.cpp
  src/receiver_server.cpp
)

target_include_directories(acb-receiver PRIVATE include)

target_link_libraries(acb-receiver PRIVATE ws2_32 setupapi bcrypt crypt32 mfplat mfuuid ole32 wmcodecdspuuid)
