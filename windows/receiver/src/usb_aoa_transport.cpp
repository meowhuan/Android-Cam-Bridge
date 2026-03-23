#include "usb_aoa_transport.h"

#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <winusb.h>
#include <setupapi.h>
#include <usbiodef.h>

EXTERN_C const GUID GUID_DEVINTERFACE_USB_DEVICE;

#include <algorithm>
#include <chrono>
#include <cstring>
#include <iostream>
#include <sstream>
#include <string>
#include <thread>
#include <vector>

#pragma comment(lib, "winusb.lib")
#pragma comment(lib, "setupapi.lib")

namespace acb::receiver {
namespace {

// ---------------------------------------------------------------------------
// AOA protocol constants
// ---------------------------------------------------------------------------
constexpr uint16_t AOA_VID         = 0x18D1;
constexpr uint16_t AOA_PID_ACC     = 0x2D00;
constexpr uint16_t AOA_PID_ACC_ADB = 0x2D01;

constexpr uint8_t AOA_GET_PROTOCOL    = 51;
constexpr uint8_t AOA_SEND_STRING     = 52;
constexpr uint8_t AOA_START_ACCESSORY = 53;

// Accessory identification strings (indices 0-5)
static const char* kAoaStrings[] = {
    "Android-Cam-Bridge",          // 0 - manufacturer
    "ACB Receiver",                // 1 - model
    "Camera bridge USB transport", // 2 - description
    "1.0",                         // 3 - version
    "",                            // 4 - uri
    "acb-usb-001",                 // 5 - serial
};

// ---------------------------------------------------------------------------
// USB frame protocol constants
// ---------------------------------------------------------------------------
constexpr uint8_t  kFrameMagic[4] = {0x41, 0x43, 0x42, 0x01};
constexpr size_t   kFrameHeaderSize = 4 + 4;          // magic(4) + length(4)
constexpr size_t   kV2MediaHeaderSize = 24;
constexpr size_t   kMaxFrameSize = 2u * 1024u * 1024u; // 2 MiB
constexpr size_t   kBulkReadBufSize = 64u * 1024u;     // 64 KB

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

std::string ToLower(const std::string& s) {
  std::string out = s;
  std::transform(out.begin(), out.end(), out.begin(),
                 [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
  return out;
}

std::string WideToUtf8(const std::wstring& ws) {
  if (ws.empty()) return {};
  int len = WideCharToMultiByte(CP_UTF8, 0, ws.data(),
                                static_cast<int>(ws.size()),
                                nullptr, 0, nullptr, nullptr);
  if (len <= 0) return {};
  std::string out(static_cast<size_t>(len), '\0');
  WideCharToMultiByte(CP_UTF8, 0, ws.data(),
                      static_cast<int>(ws.size()),
                      out.data(), len, nullptr, nullptr);
  return out;
}

std::wstring Utf8ToWide(const std::string& s) {
  if (s.empty()) return {};
  int len = MultiByteToWideChar(CP_UTF8, 0, s.data(),
                                static_cast<int>(s.size()),
                                nullptr, 0);
  if (len <= 0) return {};
  std::wstring out(static_cast<size_t>(len), L'\0');
  MultiByteToWideChar(CP_UTF8, 0, s.data(),
                      static_cast<int>(s.size()),
                      out.data(), len);
  return out;
}

bool PathContainsVidPid(const std::string& path, uint16_t vid, uint16_t pid) {
  char vidBuf[16], pidBuf[16];
  snprintf(vidBuf, sizeof(vidBuf), "vid_%04x", vid);
  snprintf(pidBuf, sizeof(pidBuf), "pid_%04x", pid);
  const std::string lower = ToLower(path);
  return lower.find(vidBuf) != std::string::npos &&
         lower.find(pidBuf) != std::string::npos;
}

bool IsAoaDevice(const std::string& path) {
  return PathContainsVidPid(path, AOA_VID, AOA_PID_ACC) ||
         PathContainsVidPid(path, AOA_VID, AOA_PID_ACC_ADB);
}

bool LooksLikeAndroid(const std::string& text) {
  const std::string lower = ToLower(text);
  static const char* kTokens[] = {
      "android", "adb", "mtp", "rndis", "pixel",
      "samsung", "xiaomi", "huawei", "oppo", "vivo", "oneplus"};
  for (const char* tok : kTokens) {
    if (lower.find(tok) != std::string::npos) return true;
  }
  static const char* kVidTokens[] = {
      "vid_18d1", "vid_04e8", "vid_2717",
      "vid_2a70", "vid_22d9", "vid_2d95"};
  for (const char* tok : kVidTokens) {
    if (lower.find(tok) != std::string::npos) return true;
  }
  return false;
}

// Retrieve a device registry property as a UTF-8 string.
std::string GetDevRegPropertyString(HDEVINFO devInfo,
                                    SP_DEVINFO_DATA* devData,
                                    DWORD property) {
  DWORD dataType = 0;
  DWORD bytes = 0;
  SetupDiGetDeviceRegistryPropertyW(devInfo, devData, property,
                                    &dataType, nullptr, 0, &bytes);
  if (bytes == 0) return {};
  std::vector<wchar_t> buf((bytes / sizeof(wchar_t)) + 2, L'\0');
  if (!SetupDiGetDeviceRegistryPropertyW(devInfo, devData, property,
                                         &dataType,
                                         reinterpret_cast<BYTE*>(buf.data()),
                                         bytes, nullptr)) {
    return {};
  }
  return WideToUtf8(buf.data());
}

struct EnumDevice {
  std::wstring path;
  std::string  pathUtf8;
  std::string  description;
  std::string  hardwareId;
};

std::vector<EnumDevice> EnumerateUsbDevices() {
  std::vector<EnumDevice> out;
  HDEVINFO devInfo = SetupDiGetClassDevsW(
      &GUID_DEVINTERFACE_USB_DEVICE, nullptr, nullptr,
      DIGCF_DEVICEINTERFACE | DIGCF_PRESENT);
  if (devInfo == INVALID_HANDLE_VALUE) return out;

  for (DWORD i = 0; ; ++i) {
    SP_DEVICE_INTERFACE_DATA ifData{};
    ifData.cbSize = sizeof(ifData);
    if (!SetupDiEnumDeviceInterfaces(devInfo, nullptr,
                                     &GUID_DEVINTERFACE_USB_DEVICE,
                                     i, &ifData)) {
      break;
    }

    DWORD required = 0;
    SetupDiGetDeviceInterfaceDetailW(devInfo, &ifData, nullptr, 0,
                                     &required, nullptr);
    if (required == 0) continue;

    std::vector<uint8_t> detailBuf(required, 0);
    auto* detail = reinterpret_cast<SP_DEVICE_INTERFACE_DETAIL_DATA_W*>(
        detailBuf.data());
    detail->cbSize = sizeof(SP_DEVICE_INTERFACE_DETAIL_DATA_W);

    SP_DEVINFO_DATA devData{};
    devData.cbSize = sizeof(devData);
    if (!SetupDiGetDeviceInterfaceDetailW(devInfo, &ifData, detail,
                                          required, nullptr, &devData)) {
      continue;
    }

    EnumDevice dev;
    dev.path = detail->DevicePath;
    dev.pathUtf8 = WideToUtf8(dev.path);
    dev.description = GetDevRegPropertyString(devInfo, &devData,
                                              SPDRP_DEVICEDESC);
    dev.hardwareId = GetDevRegPropertyString(devInfo, &devData,
                                             SPDRP_HARDWAREID);
    out.push_back(std::move(dev));
  }

  SetupDiDestroyDeviceInfoList(devInfo);
  return out;
}

bool SendAoaControlTransfer(WINUSB_INTERFACE_HANDLE hWinUsb,
                            uint8_t request,
                            uint16_t index,
                            const void* data,
                            uint16_t length) {
  WINUSB_SETUP_PACKET pkt{};
  pkt.RequestType = 0x40;  // vendor, host-to-device
  pkt.Request = request;
  pkt.Value = 0;
  pkt.Index = index;
  pkt.Length = length;

  ULONG transferred = 0;
  return WinUsb_ControlTransfer(
             hWinUsb, pkt,
             const_cast<UCHAR*>(static_cast<const UCHAR*>(data)),
             length, &transferred, nullptr) == TRUE;
}

int GetAoaProtocolVersion(WINUSB_INTERFACE_HANDLE hWinUsb) {
  WINUSB_SETUP_PACKET pkt{};
  pkt.RequestType = 0xC0;  // vendor, device-to-host
  pkt.Request = AOA_GET_PROTOCOL;
  pkt.Value = 0;
  pkt.Index = 0;
  pkt.Length = 2;

  uint16_t version = 0;
  ULONG transferred = 0;
  if (!WinUsb_ControlTransfer(hWinUsb, pkt,
                              reinterpret_cast<UCHAR*>(&version),
                              2, &transferred, nullptr) || transferred < 2) {
    return -1;
  }
  return static_cast<int>(version);
}

}  // namespace

// ---------------------------------------------------------------------------
// Construction / destruction
// ---------------------------------------------------------------------------

UsbAoaTransport::UsbAoaTransport(PacketCallback callback)
    : callback_(std::move(callback)) {}

UsbAoaTransport::~UsbAoaTransport() { Stop(); }

// ---------------------------------------------------------------------------
// Public API
// ---------------------------------------------------------------------------

bool UsbAoaTransport::StartHandshake(const std::wstring& targetDevicePath) {
  Stop();  // clean up any previous session

  SetState(AoaState::Handshaking);
  std::cerr << "[AOA] Starting AOA handshake\n";

  // Step 1 - enumerate USB devices
  auto devices = EnumerateUsbDevices();
  if (devices.empty()) {
    SetError("no USB devices found");
    return false;
  }

  // Step 2 - check if a device is already in AOA mode
  const EnumDevice* aoaDev = nullptr;
  for (const auto& d : devices) {
    if (IsAoaDevice(d.pathUtf8)) {
      aoaDev = &d;
      std::cerr << "[AOA] Found device already in AOA mode: "
                << d.pathUtf8 << "\n";
      break;
    }
  }

  if (aoaDev) {
    // Already in AOA mode — skip straight to opening it
    goto open_aoa_device;
  }

  {
    // Steps 3-8: find an Android device and switch it to AOA mode
    const EnumDevice* candidate = nullptr;

    if (!targetDevicePath.empty()) {
      // Use the caller-specified device path
      std::string targetUtf8 = WideToUtf8(targetDevicePath);
      std::string targetLower = ToLower(targetUtf8);
      for (const auto& d : devices) {
        if (ToLower(d.pathUtf8) == targetLower) {
          candidate = &d;
          break;
        }
      }
      if (!candidate) {
        SetError("target device path not found: " + targetUtf8);
        return false;
      }
    } else {
      // Find first Android candidate
      for (const auto& d : devices) {
        if (LooksLikeAndroid(d.description) ||
            LooksLikeAndroid(d.hardwareId) ||
            LooksLikeAndroid(d.pathUtf8)) {
          candidate = &d;
          break;
        }
      }
      if (!candidate) {
        SetError("no Android device found among "
                 + std::to_string(devices.size()) + " USB devices");
        return false;
      }
    }

    std::cerr << "[AOA] Android candidate: " << candidate->pathUtf8 << "\n";

    // Step 4 - open device with WinUSB
    HANDLE hDev = CreateFileW(
        candidate->path.c_str(),
        GENERIC_READ | GENERIC_WRITE,
        FILE_SHARE_READ | FILE_SHARE_WRITE,
        nullptr, OPEN_EXISTING,
        FILE_ATTRIBUTE_NORMAL | FILE_FLAG_OVERLAPPED,
        nullptr);
    if (hDev == INVALID_HANDLE_VALUE) {
      DWORD err = ::GetLastError();
      SetError("CreateFileW failed on candidate, error=" + std::to_string(err));
      return false;
    }

    WINUSB_INTERFACE_HANDLE hWinUsbTemp = nullptr;
    if (!WinUsb_Initialize(hDev, &hWinUsbTemp)) {
      DWORD err = ::GetLastError();
      CloseHandle(hDev);
      SetError("WinUsb_Initialize failed on candidate, error="
               + std::to_string(err));
      return false;
    }

    // Step 5 - GET_PROTOCOL
    int aoaVersion = GetAoaProtocolVersion(hWinUsbTemp);
    if (aoaVersion < 1) {
      WinUsb_Free(hWinUsbTemp);
      CloseHandle(hDev);
      SetError("device does not support AOA (version="
               + std::to_string(aoaVersion) + ")");
      return false;
    }
    std::cerr << "[AOA] AOA protocol version: " << aoaVersion << "\n";

    // Step 6 - SEND_STRING (indices 0-5)
    for (uint16_t idx = 0; idx < 6; ++idx) {
      const char* str = kAoaStrings[idx];
      uint16_t len = static_cast<uint16_t>(std::strlen(str)); // exclude NUL
      if (!SendAoaControlTransfer(hWinUsbTemp, AOA_SEND_STRING,
                                  idx, str, len)) {
        DWORD err = ::GetLastError();
        WinUsb_Free(hWinUsbTemp);
        CloseHandle(hDev);
        SetError("SEND_STRING failed for index " + std::to_string(idx)
                 + ", error=" + std::to_string(err));
        return false;
      }
    }
    std::cerr << "[AOA] Sent all accessory strings\n";

    // Step 7 - START_ACCESSORY
    if (!SendAoaControlTransfer(hWinUsbTemp, AOA_START_ACCESSORY,
                                0, nullptr, 0)) {
      DWORD err = ::GetLastError();
      WinUsb_Free(hWinUsbTemp);
      CloseHandle(hDev);
      SetError("START_ACCESSORY failed, error=" + std::to_string(err));
      return false;
    }
    std::cerr << "[AOA] START_ACCESSORY sent, device will re-enumerate\n";

    // Step 8 - close old handles
    WinUsb_Free(hWinUsbTemp);
    CloseHandle(hDev);

    // Step 9 - poll for AOA device re-enumeration (up to 8 seconds)
    constexpr int kPollIntervalMs = 250;
    constexpr int kPollTimeoutMs = 8000;
    int elapsed = 0;

    while (elapsed < kPollTimeoutMs) {
      std::this_thread::sleep_for(
          std::chrono::milliseconds(kPollIntervalMs));
      elapsed += kPollIntervalMs;

      auto freshDevices = EnumerateUsbDevices();
      for (const auto& d : freshDevices) {
        if (IsAoaDevice(d.pathUtf8)) {
          aoaDev = nullptr;  // pointer was into old vector
          // Copy into devices so pointer stays valid for open_aoa_device
          devices = std::move(freshDevices);
          for (const auto& dd : devices) {
            if (IsAoaDevice(dd.pathUtf8)) {
              aoaDev = &dd;
              break;
            }
          }
          std::cerr << "[AOA] AOA device appeared after "
                    << elapsed << "ms\n";
          goto open_aoa_device;
        }
      }
    }

    SetError("AOA device did not re-enumerate within "
             + std::to_string(kPollTimeoutMs) + "ms");
    return false;
  }

open_aoa_device:
  {
    // Step 10 - open the AOA device
    std::cerr << "[AOA] Opening AOA device: " << aoaDev->pathUtf8 << "\n";

    hDevice_ = CreateFileW(
        aoaDev->path.c_str(),
        GENERIC_READ | GENERIC_WRITE,
        FILE_SHARE_READ | FILE_SHARE_WRITE,
        nullptr, OPEN_EXISTING,
        FILE_ATTRIBUTE_NORMAL | FILE_FLAG_OVERLAPPED,
        nullptr);
    if (hDevice_ == INVALID_HANDLE_VALUE) {
      DWORD err = ::GetLastError();
      SetError("CreateFileW failed on AOA device, error="
               + std::to_string(err));
      return false;
    }

    if (!WinUsb_Initialize(hDevice_, &hWinUsb_)) {
      DWORD err = ::GetLastError();
      CloseHandle(hDevice_);
      hDevice_ = INVALID_HANDLE_VALUE;
      SetError("WinUsb_Initialize failed on AOA device, error="
               + std::to_string(err));
      return false;
    }

    // Step 11 - enumerate endpoints to find bulk IN and OUT
    USB_INTERFACE_DESCRIPTOR ifDesc{};
    if (!WinUsb_QueryInterfaceSettings(hWinUsb_, 0, &ifDesc)) {
      DWORD err = ::GetLastError();
      CloseHandles();
      SetError("WinUsb_QueryInterfaceSettings failed, error="
               + std::to_string(err));
      return false;
    }

    bulkInPipe_ = 0;
    bulkOutPipe_ = 0;

    for (UCHAR p = 0; p < ifDesc.bNumEndpoints; ++p) {
      WINUSB_PIPE_INFORMATION pipeInfo{};
      if (!WinUsb_QueryPipe(hWinUsb_, 0, p, &pipeInfo)) continue;

      if (pipeInfo.PipeType == UsbdPipeTypeBulk) {
        if (pipeInfo.PipeId & 0x80) {
          bulkInPipe_ = pipeInfo.PipeId;
          std::cerr << "[AOA] Bulk IN  pipe: 0x"
                    << std::hex << static_cast<int>(bulkInPipe_)
                    << std::dec << "\n";
        } else {
          bulkOutPipe_ = pipeInfo.PipeId;
          std::cerr << "[AOA] Bulk OUT pipe: 0x"
                    << std::hex << static_cast<int>(bulkOutPipe_)
                    << std::dec << "\n";
        }
      }
    }

    if (bulkInPipe_ == 0) {
      CloseHandles();
      SetError("no bulk IN endpoint found on AOA device");
      return false;
    }

    if (bulkOutPipe_ == 0) {
      CloseHandles();
      SetError("no bulk OUT endpoint found on AOA device");
      return false;
    }

    // Step 12 - set pipe policies
    ULONG timeout = 2000;
    WinUsb_SetPipePolicy(hWinUsb_, bulkInPipe_,
                         PIPE_TRANSFER_TIMEOUT,
                         sizeof(timeout), &timeout);
    WinUsb_SetPipePolicy(hWinUsb_, bulkOutPipe_,
                         PIPE_TRANSFER_TIMEOUT,
                         sizeof(timeout), &timeout);

    ULONG rawIo = TRUE;
    WinUsb_SetPipePolicy(hWinUsb_, bulkInPipe_,
                         RAW_IO,
                         sizeof(rawIo), &rawIo);

    // Step 13 - update state
    {
      std::lock_guard<std::mutex> lock(mutex_);
      devicePath_ = aoaDev->pathUtf8;
      state_ = AoaState::Connected;
      lastError_.clear();
    }

    std::cerr << "[AOA] Connected successfully\n";
    return true;
  }
}

void UsbAoaTransport::StartBulkRead() {
  if (running_.load()) return;
  running_ = true;
  readThread_ = std::thread(&UsbAoaTransport::BulkReadLoop, this);
}

void UsbAoaTransport::Stop() {
  running_ = false;
  if (hWinUsb_) {
    WinUsb_AbortPipe(hWinUsb_, bulkInPipe_);
  }
  if (readThread_.joinable()) {
    readThread_.join();
  }
  CloseHandles();
  SetState(AoaState::Disconnected);
}

bool UsbAoaTransport::IsConnected() const {
  std::lock_guard<std::mutex> lock(mutex_);
  return state_ == AoaState::Connected;
}

AoaState UsbAoaTransport::GetState() const {
  std::lock_guard<std::mutex> lock(mutex_);
  return state_;
}

std::string UsbAoaTransport::GetLastError() const {
  std::lock_guard<std::mutex> lock(mutex_);
  return lastError_;
}

AoaStats UsbAoaTransport::GetStats() const {
  std::lock_guard<std::mutex> lock(mutex_);
  return stats_;
}

std::string UsbAoaTransport::GetDevicePath() const {
  std::lock_guard<std::mutex> lock(mutex_);
  return devicePath_;
}

// ---------------------------------------------------------------------------
// Private implementation
// ---------------------------------------------------------------------------

void UsbAoaTransport::BulkReadLoop() {
  std::cerr << "[AOA] Bulk read loop started\n";

  enum class ParseState { Sync, Header, Body };

  std::vector<uint8_t> readBuf(kBulkReadBufSize);
  std::vector<uint8_t> accumBuf;
  accumBuf.reserve(kMaxFrameSize + kFrameHeaderSize);

  ParseState parseState = ParseState::Sync;
  uint32_t frameLength = 0;       // from the length field
  size_t syncMatchCount = 0;      // how many magic bytes matched so far

  while (running_.load()) {
    ULONG bytesRead = 0;
    BOOL ok = WinUsb_ReadPipe(
        hWinUsb_, bulkInPipe_,
        readBuf.data(),
        static_cast<ULONG>(readBuf.size()),
        &bytesRead, nullptr);

    if (!ok) {
      DWORD err = ::GetLastError();

      if (err == ERROR_SEM_TIMEOUT) {
        // Timeout is normal under no-data conditions; keep trying
        continue;
      }

      if (err == ERROR_DEVICE_NOT_CONNECTED ||
          err == ERROR_GEN_FAILURE) {
        std::cerr << "[AOA] Device disconnected (error="
                  << err << ")\n";
        SetState(AoaState::Disconnected);
        running_ = false;
        break;
      }

      // Other errors: attempt a pipe reset and retry once
      std::cerr << "[AOA] Read error " << err
                << ", resetting pipe\n";
      WinUsb_ResetPipe(hWinUsb_, bulkInPipe_);
      continue;
    }

    if (bytesRead == 0) continue;

    // Update stats
    {
      std::lock_guard<std::mutex> lock(mutex_);
      stats_.rxPackets++;
      stats_.rxBytes += bytesRead;
      stats_.lastPacketMs = NowMs();
    }

    // Feed data into the frame parser
    size_t offset = 0;
    while (offset < bytesRead) {
      switch (parseState) {
        case ParseState::Sync: {
          // Scan for magic bytes one at a time
          while (offset < bytesRead) {
            if (readBuf[offset] == kFrameMagic[syncMatchCount]) {
              ++syncMatchCount;
              ++offset;
              if (syncMatchCount == 4) {
                // Magic found; move to header
                parseState = ParseState::Header;
                accumBuf.clear();
                syncMatchCount = 0;
                break;
              }
            } else {
              // Mismatch — restart matching.  If the current byte
              // happens to be the first magic byte, count it.
              if (syncMatchCount > 0) {
                syncMatchCount = 0;
                // Do not advance offset; re-check this byte against
                // the start of magic.
              } else {
                ++offset;
              }
            }
          }
          break;
        }

        case ParseState::Header: {
          // Accumulate 4 bytes of frame_length (LE uint32)
          while (offset < bytesRead && accumBuf.size() < 4) {
            accumBuf.push_back(readBuf[offset++]);
          }
          if (accumBuf.size() == 4) {
            std::memcpy(&frameLength, accumBuf.data(), 4);
            // frameLength includes 24-byte v2 header + payload
            if (frameLength < 24) {
              parseState = ParseState::Sync;
              accumBuf.clear();
              syncMatchCount = 0;
              continue;
            }
            if (frameLength > kMaxFrameSize) {
              std::cerr << "[AOA] Frame too large ("
                        << frameLength
                        << " bytes), resyncing\n";
              parseState = ParseState::Sync;
              accumBuf.clear();
              syncMatchCount = 0;
            } else {
              accumBuf.clear();
              accumBuf.reserve(frameLength);
              parseState = ParseState::Body;
            }
          }
          break;
        }

        case ParseState::Body: {
          size_t need = frameLength - accumBuf.size();
          size_t avail = bytesRead - offset;
          size_t take = (std::min)(need, avail);
          accumBuf.insert(accumBuf.end(),
                          readBuf.data() + offset,
                          readBuf.data() + offset + take);
          offset += take;

          if (accumBuf.size() == frameLength) {
            // Complete frame — deliver via callback.
            // accumBuf contains [24-byte v2 header | payload].
            if (callback_) {
              callback_(accumBuf.data(), accumBuf.size());
            }
            parseState = ParseState::Sync;
            accumBuf.clear();
            syncMatchCount = 0;
          }
          break;
        }
      }  // switch
    }  // while offset < bytesRead
  }  // while running_

  std::cerr << "[AOA] Bulk read loop stopped\n";
}

void UsbAoaTransport::SetState(AoaState state) {
  std::lock_guard<std::mutex> lock(mutex_);
  state_ = state;
}

void UsbAoaTransport::SetError(const std::string& msg) {
  std::cerr << "[AOA] Error: " << msg << "\n";
  std::lock_guard<std::mutex> lock(mutex_);
  state_ = AoaState::Error;
  lastError_ = msg;
}

void UsbAoaTransport::CloseHandles() {
  if (hWinUsb_) {
    WinUsb_Free(hWinUsb_);
    hWinUsb_ = nullptr;
  }
  if (hDevice_ != INVALID_HANDLE_VALUE) {
    CloseHandle(hDevice_);
    hDevice_ = INVALID_HANDLE_VALUE;
  }
  bulkInPipe_ = 0;
  bulkOutPipe_ = 0;
}

uint64_t UsbAoaTransport::NowMs() {
  return static_cast<uint64_t>(
      std::chrono::duration_cast<std::chrono::milliseconds>(
          std::chrono::steady_clock::now().time_since_epoch())
          .count());
}

}  // namespace acb::receiver
