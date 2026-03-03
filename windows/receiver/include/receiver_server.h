#pragma once

#include <atomic>
#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

namespace acb::receiver {

struct VideoConfig {
  uint32_t width = 1920;
  uint32_t height = 1080;
  uint32_t fps = 30;
  uint32_t bitrate = 5000000;
};

struct AudioConfig {
  bool enabled = true;
};

struct SessionStartRequest {
  std::string transport = "lan";
  std::string deviceId;
  VideoConfig video;
  AudioConfig audio;
};

class ReceiverServer {
 public:
  explicit ReceiverServer(uint16_t port);
  ~ReceiverServer();

  bool Start();
  void Stop();

 private:
  struct FrameState {
    std::vector<uint8_t> jpeg;
    std::vector<uint8_t> v2Bgra;
    uint64_t frameCount = 0;
    uint64_t lastFrameTickMs = 0;
    uint32_t width = 0;
    uint32_t height = 0;
    uint32_t v2Width = 0;
    uint32_t v2Height = 0;
    uint64_t audioBytes = 0;
    uint64_t audioPackets = 0;
    uint32_t audioSampleRate = 0;
    uint32_t audioChannels = 0;
    uint64_t v2VideoBytes = 0;
    uint64_t v2AudioBytes = 0;
    uint64_t v2VideoFrames = 0;
    uint64_t v2AudioFrames = 0;
    uint64_t v2Keyframes = 0;
    uint64_t v2DecodedFrames = 0;
  };

  uint16_t port_;
  std::atomic<bool> running_{false};
  uintptr_t listenSocket_ = static_cast<uintptr_t>(~0ULL);
  std::mutex frameMutex_;
  FrameState frameState_;
  std::string activeSessionId_ = "sess_demo_001";
  std::string activeAuthToken_ = "token_demo";
  std::string activeTransport_ = "lan";
  bool mfStarted_ = false;

  void AcceptLoop();
  std::string HandleRequest(const std::string& request);
  static uint64_t NowMs();
  bool HandleWebSocketV2(uintptr_t clientSocket, const std::string& rawRequest);
};

}  // namespace acb::receiver
