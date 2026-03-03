cmake_minimum_required(VERSION 3.22)
project(acb_obs_plugin LANGUAGES CXX)

set(OBS_INCLUDE_DIR "" CACHE PATH "Path to OBS SDK include directory (must contain obs-module.h)")
set(OBS_LIB_DIR "" CACHE PATH "Path to OBS SDK library directory (must contain obs.lib)")
set(OBS_GENERATED_INCLUDE_DIR "" CACHE PATH "Path to OBS generated headers (must contain obsconfig.h)")
set(OBS_PLUGIN_NAME "acb-obs-plugin")

if (OBS_INCLUDE_DIR
    AND EXISTS "${OBS_INCLUDE_DIR}/obs-module.h"
    AND OBS_LIB_DIR
    AND OBS_GENERATED_INCLUDE_DIR
    AND EXISTS "${OBS_GENERATED_INCLUDE_DIR}/obsconfig.h")
  message(STATUS "Building OBS module with SDK: ${OBS_INCLUDE_DIR}")

  add_library(acb-obs-plugin MODULE src/plugin.cpp)
  target_include_directories(acb-obs-plugin PRIVATE ${OBS_INCLUDE_DIR} ${OBS_GENERATED_INCLUDE_DIR})
  target_link_directories(acb-obs-plugin PRIVATE ${OBS_LIB_DIR})
  target_link_libraries(acb-obs-plugin PRIVATE obs winhttp windowscodecs)
  target_compile_definitions(acb-obs-plugin PRIVATE NOMINMAX)
  set_target_properties(acb-obs-plugin PROPERTIES OUTPUT_NAME "${OBS_PLUGIN_NAME}" PREFIX "")

  file(WRITE "${CMAKE_CURRENT_BINARY_DIR}/acb_obs_build_mode.txt" "real\n")

  install(TARGETS acb-obs-plugin
          RUNTIME DESTINATION "obs-studio/plugins/${OBS_PLUGIN_NAME}/bin/64bit"
          LIBRARY DESTINATION "obs-studio/plugins/${OBS_PLUGIN_NAME}/bin/64bit")
  install(FILES data/locale/en-US.ini
          DESTINATION "obs-studio/plugins/${OBS_PLUGIN_NAME}/data/locale")
else()
  message(WARNING
    "OBS SDK not configured. Building fallback stub that will NOT load in OBS.\n"
    "Set OBS_INCLUDE_DIR, OBS_GENERATED_INCLUDE_DIR and OBS_LIB_DIR to build a real OBS module.")

  add_library(acb-obs-plugin MODULE src/plugin_stub.cpp)
  target_compile_definitions(acb-obs-plugin PRIVATE NOMINMAX)
  set_target_properties(acb-obs-plugin PROPERTIES OUTPUT_NAME "${OBS_PLUGIN_NAME}" PREFIX "")

  file(WRITE "${CMAKE_CURRENT_BINARY_DIR}/acb_obs_build_mode.txt" "stub-no-obs-sdk\n")
endif()
