#pragma once

#include <atomic>
#include <cstdint>
#include <functional>
#include <mutex>
#include <string>
#include <thread>

#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <windows.h>
#include <winusb.h>

namespace acb::receiver {

enum class AoaState { Disconnected, Handshaking, Connected, Error };

struct AoaStats {
  uint64_t rxPackets = 0;
  uint64_t rxBytes = 0;
  uint64_t txPackets = 0;
  uint64_t txBytes = 0;
  uint64_t lastPacketMs = 0;
};

class UsbAoaTransport {
 public:
  using PacketCallback = std::function<void(const uint8_t*, size_t)>;

  explicit UsbAoaTransport(PacketCallback callback);
  ~UsbAoaTransport();

  UsbAoaTransport(const UsbAoaTransport&) = delete;
  UsbAoaTransport& operator=(const UsbAoaTransport&) = delete;

  bool StartHandshake(const std::wstring& targetDevicePath = L"");
  void StartBulkRead();
  void Stop();

  bool IsConnected() const;
  AoaState GetState() const;
  std::string GetLastError() const;
  AoaStats GetStats() const;
  std::string GetDevicePath() const;

 private:
  void BulkReadLoop();
  void SetState(AoaState state);
  void SetError(const std::string& msg);
  void CloseHandles();
  static uint64_t NowMs();

  HANDLE hDevice_ = INVALID_HANDLE_VALUE;
  WINUSB_INTERFACE_HANDLE hWinUsb_ = nullptr;
  UCHAR bulkInPipe_ = 0;
  UCHAR bulkOutPipe_ = 0;

  std::thread readThread_;
  std::atomic<bool> running_{false};

  mutable std::mutex mutex_;
  AoaState state_ = AoaState::Disconnected;
  std::string lastError_;
  AoaStats stats_{};
  std::string devicePath_;

  PacketCallback callback_;
};

}  // namespace acb::receiver
