#pragma once

#include <guiddef.h>

// Filter CLSID -- unique to Android Cam Bridge virtual camera
// {A1B2C3D4-E5F6-7890-ABCD-EF1234567890}
//
// When INITGUID is defined (via initguid.h), DEFINE_GUID creates storage.
// Otherwise it produces an extern declaration.
DEFINE_GUID(CLSID_AcbVirtualCam,
    0xa1b2c3d4, 0xe5f6, 0x7890, 0xab, 0xcd, 0xef, 0x12, 0x34, 0x56, 0x78, 0x90);
