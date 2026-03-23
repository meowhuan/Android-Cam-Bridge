cmake_minimum_required(VERSION 3.22)
project(acb_virtualcam LANGUAGES CXX)

add_library(acb-virtualcam SHARED
    src/dllmain.cpp
    src/virtualcam_filter.cpp
    src/virtualcam_pin.cpp
    src/virtualcam.def
)

target_include_directories(acb-virtualcam PRIVATE src)
target_compile_features(acb-virtualcam PRIVATE cxx_std_17)
target_link_libraries(acb-virtualcam PRIVATE strmiids uuid ole32 oleaut32 advapi32)
