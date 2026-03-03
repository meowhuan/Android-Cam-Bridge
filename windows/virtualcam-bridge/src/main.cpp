#define WIN32_LEAN_AND_MEAN
#include <windows.h>

#include <cstdint>
#include <iostream>
#include <string>

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
};
#pragma pack(pop)

constexpr uint32_t kMagic = 0x42434141;  // "AACB" little-endian marker for ACB.
constexpr const wchar_t* kPipeName = L"\\\\.\\pipe\\acb-virtualcam-control";

}  // namespace

int main() {
  std::wcout << L"acb-virtualcam-bridge (v0.2 scaffold)\n";
  std::wcout << L"control pipe: " << kPipeName << L"\n";

  HANDLE pipe = CreateNamedPipeW(kPipeName,
                                 PIPE_ACCESS_DUPLEX,
                                 PIPE_TYPE_MESSAGE | PIPE_READMODE_MESSAGE | PIPE_WAIT,
                                 1,
                                 1024,
                                 1024,
                                 0,
                                 nullptr);

  if (pipe == INVALID_HANDLE_VALUE) {
    std::cerr << "failed to create control pipe\n";
    return 1;
  }

  std::cout << "waiting for START/STOP/SET_FORMAT commands...\n";
  ConnectNamedPipe(pipe, nullptr);

  char buffer[256]{};
  DWORD read = 0;
  while (ReadFile(pipe, buffer, sizeof(buffer) - 1, &read, nullptr) && read > 0) {
    buffer[read] = '\0';
    std::string cmd(buffer);
    std::cout << "recv: " << cmd << "\n";
  }

  DisconnectNamedPipe(pipe);
  CloseHandle(pipe);
  return 0;
}
