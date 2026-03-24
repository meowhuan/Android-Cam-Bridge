#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <winhttp.h>

#include <atomic>
#include <cstdint>
#include <cstring>
#include <iostream>
#include <mutex>
#include <sstream>
#include <string>
#include <thread>
#include <vector>

#pragma comment(lib, "winhttp.lib")

namespace {

#pragma pack(push, 1)
struct SharedFrameHeader {
  uint32_t magic;
  uint32_t version;
  uint32_t width;
  uint32_t height;
  uint32_t format;
  uint64_t pts;
  uint32_t slotCount;
  uint32_t slotSize;
  uint32_t frameSize;
  uint32_t frameIndex;
};
#pragma pack(pop)

constexpr uint32_t kMagic = 0x42434141;
constexpr uint32_t kVersion = 2;
constexpr uint32_t kFormatBgra = 1;
constexpr uint32_t kSlotCount = 3;
constexpr const wchar_t* kPipeName = L"\\\\.\\pipe\\acb-virtualcam-control";
constexpr const wchar_t* kMapName = L"Local\\acb_virtualcam_frame";
constexpr uint32_t kMaxFrameBytes = 3840 * 2160 * 4;

struct BridgeState {
  std::mutex mu;
  std::string receiverHostPort = "127.0.0.1:39393";
  uint32_t pollIntervalMs = 16;
  bool streaming = false;
  bool exitRequested = false;
  uint64_t frames = 0;
  uint64_t bytes = 0;
  std::string lastError;
  uint32_t lastWidth = 0;
  uint32_t lastHeight = 0;
  HANDLE mapHandle = nullptr;
  uint8_t* mapView = nullptr;
  uint32_t frameIndex = 0;
};

std::wstring ToWide(const std::string& s) {
  if (s.empty()) return L"";
  const int size = MultiByteToWideChar(CP_UTF8, 0, s.c_str(), -1, nullptr, 0);
  if (size <= 1) return L"";
  std::wstring out(static_cast<size_t>(size - 1), L'\0');
  MultiByteToWideChar(CP_UTF8, 0, s.c_str(), -1, out.data(), size);
  return out;
}

std::string ToNarrow(const std::wstring& s) {
  if (s.empty()) return "";
  const int size = WideCharToMultiByte(CP_UTF8, 0, s.c_str(), -1, nullptr, 0, nullptr, nullptr);
  if (size <= 1) return "";
  std::string out(static_cast<size_t>(size - 1), '\0');
  WideCharToMultiByte(CP_UTF8, 0, s.c_str(), -1, out.data(), size, nullptr, nullptr);
  return out;
}

void ParseHostPort(const std::string& input, std::string* host, int* port) {
  std::string value = input;
  const auto scheme = value.find("://");
  if (scheme != std::string::npos) value = value.substr(scheme + 3);
  const auto slash = value.find('/');
  if (slash != std::string::npos) value = value.substr(0, slash);
  const auto colon = value.rfind(':');
  if (colon == std::string::npos) {
    *host = value.empty() ? "127.0.0.1" : value;
    *port = 39393;
    return;
  }
  *host = value.substr(0, colon);
  *port = std::atoi(value.substr(colon + 1).c_str());
  if (host->empty()) *host = "127.0.0.1";
  if (*port <= 0) *port = 39393;
}

bool EnsureSharedMemory(BridgeState* state) {
  if (state->mapView != nullptr) return true;
  const uint64_t totalSize = sizeof(SharedFrameHeader) + static_cast<uint64_t>(kSlotCount) * kMaxFrameBytes;
  state->mapHandle = CreateFileMappingW(INVALID_HANDLE_VALUE, nullptr, PAGE_READWRITE,
                                        static_cast<DWORD>(totalSize >> 32),
                                        static_cast<DWORD>(totalSize & 0xFFFFFFFFULL),
                                        kMapName);
  if (!state->mapHandle) return false;
  state->mapView = static_cast<uint8_t*>(MapViewOfFile(state->mapHandle, FILE_MAP_ALL_ACCESS, 0, 0, 0));
  if (!state->mapView) {
    CloseHandle(state->mapHandle);
    state->mapHandle = nullptr;
    return false;
  }
  std::memset(state->mapView, 0, static_cast<size_t>(totalSize));
  auto* hdr = reinterpret_cast<SharedFrameHeader*>(state->mapView);
  hdr->magic = kMagic;
  hdr->version = kVersion;
  hdr->format = kFormatBgra;
  hdr->slotCount = kSlotCount;
  hdr->slotSize = kMaxFrameBytes;
  return true;
}

bool FetchFrameBgra(const std::string& receiverHostPort, std::vector<uint8_t>* outBody, int* statusCode) {
  std::string host;
  int port = 39393;
  ParseHostPort(receiverHostPort, &host, &port);

  HINTERNET session = WinHttpOpen(L"ACB-VirtualCamBridge/0.3", WINHTTP_ACCESS_TYPE_DEFAULT_PROXY,
                                  WINHTTP_NO_PROXY_NAME, WINHTTP_NO_PROXY_BYPASS, 0);
  if (!session) return false;
  const std::wstring whost = ToWide(host);
  HINTERNET conn = WinHttpConnect(session, whost.c_str(), static_cast<INTERNET_PORT>(port), 0);
  if (!conn) {
    WinHttpCloseHandle(session);
    return false;
  }
  HINTERNET req = WinHttpOpenRequest(conn, L"GET", L"/api/v2/frame.bgra", nullptr, WINHTTP_NO_REFERER,
                                     WINHTTP_DEFAULT_ACCEPT_TYPES, 0);
  if (!req) {
    WinHttpCloseHandle(conn);
    WinHttpCloseHandle(session);
    return false;
  }
  BOOL ok = WinHttpSendRequest(req, WINHTTP_NO_ADDITIONAL_HEADERS, 0, WINHTTP_NO_REQUEST_DATA, 0, 0, 0);
  if (!ok || !WinHttpReceiveResponse(req, nullptr)) {
    WinHttpCloseHandle(req);
    WinHttpCloseHandle(conn);
    WinHttpCloseHandle(session);
    return false;
  }

  DWORD code = 0;
  DWORD codeSize = sizeof(code);
  WinHttpQueryHeaders(req, WINHTTP_QUERY_STATUS_CODE | WINHTTP_QUERY_FLAG_NUMBER, WINHTTP_HEADER_NAME_BY_INDEX,
                      &code, &codeSize, WINHTTP_NO_HEADER_INDEX);
  *statusCode = static_cast<int>(code);

  outBody->clear();
  while (true) {
    DWORD available = 0;
    if (!WinHttpQueryDataAvailable(req, &available) || available == 0) break;
    const size_t offset = outBody->size();
    outBody->resize(offset + available);
    DWORD read = 0;
    if (!WinHttpReadData(req, outBody->data() + offset, available, &read)) break;
    outBody->resize(offset + read);
  }

  WinHttpCloseHandle(req);
  WinHttpCloseHandle(conn);
  WinHttpCloseHandle(session);
  return true;
}

void WriteFrameToSharedMemory(BridgeState* state, const std::vector<uint8_t>& body) {
  if (body.size() < 8) return;
  const uint32_t width = static_cast<uint32_t>(body[0]) |
                         (static_cast<uint32_t>(body[1]) << 8) |
                         (static_cast<uint32_t>(body[2]) << 16) |
                         (static_cast<uint32_t>(body[3]) << 24);
  const uint32_t height = static_cast<uint32_t>(body[4]) |
                          (static_cast<uint32_t>(body[5]) << 8) |
                          (static_cast<uint32_t>(body[6]) << 16) |
                          (static_cast<uint32_t>(body[7]) << 24);
  if (width == 0 || height == 0) return;
  const size_t expected = static_cast<size_t>(width) * static_cast<size_t>(height) * 4;
  if (body.size() < 8 + expected || expected > kMaxFrameBytes) return;

  auto* hdr = reinterpret_cast<SharedFrameHeader*>(state->mapView);
  uint8_t* payloadBase = state->mapView + sizeof(SharedFrameHeader);
  const uint32_t slot = (state->frameIndex + 1) % kSlotCount;
  uint8_t* slotPtr = payloadBase + static_cast<size_t>(slot) * kMaxFrameBytes;
  std::memcpy(slotPtr, body.data() + 8, expected);

  hdr->width = width;
  hdr->height = height;
  hdr->frameSize = static_cast<uint32_t>(expected);
  hdr->frameIndex = slot;
  hdr->pts = GetTickCount64();

  state->frameIndex = slot;
  state->lastWidth = width;
  state->lastHeight = height;
  state->frames += 1;
  state->bytes += expected;
}

void PullWorker(BridgeState* state) {
  while (true) {
    std::string receiver;
    uint32_t pollMs = 20;
    bool streaming = false;
    bool exitRequested = false;
    {
      std::lock_guard<std::mutex> lock(state->mu);
      receiver = state->receiverHostPort;
      pollMs = state->pollIntervalMs;
      streaming = state->streaming;
      exitRequested = state->exitRequested;
    }
    if (exitRequested) return;
    if (!streaming) {
      Sleep(50);
      continue;
    }

    int status = 0;
    std::vector<uint8_t> body;
    const bool ok = FetchFrameBgra(receiver, &body, &status);
    {
      std::lock_guard<std::mutex> lock(state->mu);
      if (!ok) {
        state->lastError = "http_request_failed";
      } else if (status != 200) {
        std::ostringstream ss;
        ss << "http_" << status;
        state->lastError = ss.str();
      } else {
        if (EnsureSharedMemory(state)) {
          WriteFrameToSharedMemory(state, body);
          state->lastError.clear();
        } else {
          state->lastError = "shared_memory_failed";
        }
      }
    }
    Sleep(pollMs);
  }
}

std::string BuildStatus(const BridgeState& state) {
  std::ostringstream ss;
  ss << "streaming=" << (state.streaming ? "1" : "0")
     << " receiver=" << state.receiverHostPort
     << " frames=" << state.frames
     << " bytes=" << state.bytes
     << " size=" << state.lastWidth << "x" << state.lastHeight
     << " error=" << (state.lastError.empty() ? "none" : state.lastError);
  return ss.str();
}

std::string HandleCommand(BridgeState* state, const std::string& cmdLine) {
  std::istringstream iss(cmdLine);
  std::string cmd;
  iss >> cmd;
  if (cmd.rfind("\xEF\xBB\xBF", 0) == 0) {
    cmd.erase(0, 3);
  }
  for (char& c : cmd) c = static_cast<char>(std::toupper(static_cast<unsigned char>(c)));
  if (cmd == "START") {
    std::lock_guard<std::mutex> lock(state->mu);
    state->streaming = true;
    return "OK START";
  }
  if (cmd == "STOP") {
    std::lock_guard<std::mutex> lock(state->mu);
    state->streaming = false;
    return "OK STOP";
  }
  if (cmd == "SET_RECEIVER") {
    std::string value;
    iss >> value;
    if (value.empty()) return "ERR missing receiver";
    std::lock_guard<std::mutex> lock(state->mu);
    state->receiverHostPort = value;
    return "OK SET_RECEIVER";
  }
  if (cmd == "SET_INTERVAL") {
    uint32_t interval = 0;
    iss >> interval;
    if (interval < 5 || interval > 1000) return "ERR interval(5..1000)";
    std::lock_guard<std::mutex> lock(state->mu);
    state->pollIntervalMs = interval;
    return "OK SET_INTERVAL";
  }
  if (cmd == "STATUS") {
    std::lock_guard<std::mutex> lock(state->mu);
    return BuildStatus(*state);
  }
  if (cmd == "EXIT") {
    std::lock_guard<std::mutex> lock(state->mu);
    state->exitRequested = true;
    state->streaming = false;
    return "OK EXIT";
  }
  return "ERR unknown command";
}

}  // namespace

int main() {
  BridgeState state;
  if (!EnsureSharedMemory(&state)) {
    std::cerr << "failed to initialize shared memory map\n";
    return 1;
  }

  std::thread worker(PullWorker, &state);

  std::wcout << L"acb-virtualcam-bridge (v0.3)\n";
  std::wcout << L"control pipe: " << kPipeName << L"\n";
  std::wcout << L"shared map: " << kMapName << L"\n";
  std::cout << "commands: START | STOP | SET_RECEIVER <host:port> | SET_INTERVAL <ms> | STATUS | EXIT\n";

  while (true) {
    HANDLE pipe = CreateNamedPipeW(kPipeName,
                                   PIPE_ACCESS_DUPLEX,
                                   PIPE_TYPE_MESSAGE | PIPE_READMODE_MESSAGE | PIPE_WAIT,
                                   1,
                                   2048,
                                   2048,
                                   0,
                                   nullptr);
    if (pipe == INVALID_HANDLE_VALUE) {
      std::cerr << "failed to create control pipe\n";
      break;
    }
    if (!ConnectNamedPipe(pipe, nullptr) && GetLastError() != ERROR_PIPE_CONNECTED) {
      CloseHandle(pipe);
      continue;
    }

    char buffer[1024]{};
    DWORD read = 0;
    while (ReadFile(pipe, buffer, sizeof(buffer) - 1, &read, nullptr) && read > 0) {
      buffer[read] = '\0';
      std::string line(buffer);
      while (!line.empty() && (line.back() == '\r' || line.back() == '\n')) line.pop_back();
      const std::string resp = HandleCommand(&state, line) + "\n";
      DWORD written = 0;
      WriteFile(pipe, resp.data(), static_cast<DWORD>(resp.size()), &written, nullptr);
      {
        std::lock_guard<std::mutex> lock(state.mu);
        if (state.exitRequested) break;
      }
    }
    DisconnectNamedPipe(pipe);
    CloseHandle(pipe);

    bool done = false;
    {
      std::lock_guard<std::mutex> lock(state.mu);
      done = state.exitRequested;
    }
    if (done) break;
  }

  {
    std::lock_guard<std::mutex> lock(state.mu);
    state.exitRequested = true;
    state.streaming = false;
  }
  worker.join();

  if (state.mapView) UnmapViewOfFile(state.mapView);
  if (state.mapHandle) CloseHandle(state.mapHandle);
  return 0;
}
