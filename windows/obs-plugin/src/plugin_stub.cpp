#ifdef _WIN32
#include <windows.h>
#endif

extern "C" __declspec(dllexport) const char* acb_obs_build_mode() {
  return "stub-no-obs-sdk";
}

extern "C" __declspec(dllexport) const char* acb_obs_plugin_note() {
  return "OBS SDK is missing. Configure OBS_INCLUDE_DIR and OBS_LIB_DIR to build a loadable OBS module.";
}
