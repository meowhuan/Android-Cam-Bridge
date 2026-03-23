#include "receiver_server.h"

#define WIN32_LEAN_AND_MEAN
#include <winsock2.h>
#include <ws2tcpip.h>
#include <windows.h>

#include "usb_aoa_transport.h"

#include <setupapi.h>
#include <initguid.h>
#include <usbiodef.h>
#include <bcrypt.h>
#include <wincrypt.h>
#include <mfapi.h>
#include <mferror.h>
#include <mfidl.h>
#include <mftransform.h>
#include <wmcodecdsp.h>

#include <algorithm>
#include <chrono>
#include <cctype>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <iostream>
#include <sstream>
#include <string>
#include <thread>
#include <unordered_map>
#include <vector>

#pragma comment(lib, "setupapi.lib")
#pragma comment(lib, "bcrypt.lib")
#pragma comment(lib, "crypt32.lib")
#pragma comment(lib, "mfplat.lib")
#pragma comment(lib, "mfuuid.lib")
#pragma comment(lib, "ole32.lib")

namespace acb::receiver {
namespace {

constexpr auto kInvalidSocket = static_cast<SOCKET>(~0ULL);

struct ParsedRequest {
  std::string method;
  std::string path;
  std::unordered_map<std::string, std::string> headers;
  std::string body;
};

struct UsbNativeDevice {
  std::string path;
  std::string description;
  std::string hardwareId;
  bool androidCandidate = false;
};

std::string StatusText(int status) {
  switch (status) {
    case 200:
      return "OK";
    case 400:
      return "Bad Request";
    case 404:
      return "Not Found";
    case 500:
      return "Internal Server Error";
    case 503:
      return "Service Unavailable";
    default:
      return "OK";
  }
}

std::string HttpResponse(int status,
                         const std::string& contentType,
                         const std::string& body,
                         const std::vector<std::pair<std::string, std::string>>& extra = {}) {
  std::ostringstream oss;
  oss << "HTTP/1.1 " << status << " " << StatusText(status) << "\r\n"
      << "Content-Type: " << contentType << "\r\n"
      << "Access-Control-Allow-Origin: *\r\n"
      << "Connection: close\r\n"
      << "Content-Length: " << body.size() << "\r\n";
  for (const auto& [k, v] : extra) {
    oss << k << ": " << v << "\r\n";
  }
  oss << "\r\n" << body;
  return oss.str();
}

std::string HttpResponseBinary(int status,
                               const std::string& contentType,
                               const std::vector<uint8_t>& body,
                               const std::vector<std::pair<std::string, std::string>>& extra = {}) {
  std::ostringstream header;
  header << "HTTP/1.1 " << status << " " << StatusText(status) << "\r\n"
         << "Content-Type: " << contentType << "\r\n"
         << "Access-Control-Allow-Origin: *\r\n"
         << "Connection: close\r\n"
         << "Content-Length: " << body.size() << "\r\n";
  for (const auto& [k, v] : extra) {
    header << k << ": " << v << "\r\n";
  }
  header << "\r\n";

  std::string out = header.str();
  out.append(reinterpret_cast<const char*>(body.data()), body.size());
  return out;
}

std::string Json(int status, const std::string& body) {
  return HttpResponse(status, "application/json", body);
}

std::string ToLower(std::string s) {
  std::transform(s.begin(), s.end(), s.begin(), [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
  return s;
}

std::string Trim(const std::string& s) {
  size_t start = 0;
  while (start < s.size() && std::isspace(static_cast<unsigned char>(s[start]))) {
    ++start;
  }
  size_t end = s.size();
  while (end > start && std::isspace(static_cast<unsigned char>(s[end - 1]))) {
    --end;
  }
  return s.substr(start, end - start);
}

bool ParseRequest(const std::string& raw, ParsedRequest* out) {
  const auto headerEnd = raw.find("\r\n\r\n");
  if (headerEnd == std::string::npos) {
    return false;
  }

  const std::string headerPart = raw.substr(0, headerEnd);
  out->body = raw.substr(headerEnd + 4);

  std::istringstream stream(headerPart);
  std::string requestLine;
  if (!std::getline(stream, requestLine)) {
    return false;
  }
  if (!requestLine.empty() && requestLine.back() == '\r') {
    requestLine.pop_back();
  }

  std::istringstream rl(requestLine);
  if (!(rl >> out->method >> out->path)) {
    return false;
  }

  std::string line;
  while (std::getline(stream, line)) {
    if (!line.empty() && line.back() == '\r') {
      line.pop_back();
    }
    if (line.empty()) {
      continue;
    }
    const auto pos = line.find(':');
    if (pos == std::string::npos) {
      continue;
    }
    const auto key = ToLower(Trim(line.substr(0, pos)));
    const auto value = Trim(line.substr(pos + 1));
    out->headers[key] = value;
  }

  return true;
}

std::string JsonEscape(const std::string& s) {
  std::string out;
  out.reserve(s.size());
  for (char c : s) {
    switch (c) {
      case '\\':
        out += "\\\\";
        break;
      case '"':
        out += "\\\"";
        break;
      case '\n':
        out += "\\n";
        break;
      case '\r':
        out += "\\r";
        break;
      case '\t':
        out += "\\t";
        break;
      default:
        out += c;
        break;
    }
  }
  return out;
}

std::string JsonUnescape(const std::string& s) {
  std::string out;
  out.reserve(s.size());
  bool escaped = false;
  for (size_t i = 0; i < s.size(); ++i) {
    const char c = s[i];
    if (!escaped) {
      if (c == '\\') {
        escaped = true;
      } else {
        out.push_back(c);
      }
      continue;
    }

    switch (c) {
      case '\\':
        out.push_back('\\');
        break;
      case '"':
        out.push_back('"');
        break;
      case '/':
        out.push_back('/');
        break;
      case 'b':
        out.push_back('\b');
        break;
      case 'f':
        out.push_back('\f');
        break;
      case 'n':
        out.push_back('\n');
        break;
      case 'r':
        out.push_back('\r');
        break;
      case 't':
        out.push_back('\t');
        break;
      case 'u':
        // Keep as-is for unsupported unicode escape.
        out.push_back('\\');
        out.push_back('u');
        break;
      default:
        out.push_back(c);
        break;
    }
    escaped = false;
  }
  if (escaped) {
    out.push_back('\\');
  }
  return out;
}

std::string JsonField(const std::string& body, const std::string& key, const std::string& fallback) {
  const std::string token = "\"" + key + "\"";
  const auto keyPos = body.find(token);
  if (keyPos == std::string::npos) {
    return fallback;
  }
  const auto colon = body.find(':', keyPos + token.size());
  if (colon == std::string::npos) {
    return fallback;
  }
  const auto q1 = body.find('"', colon + 1);
  if (q1 == std::string::npos) {
    return fallback;
  }
  const auto q2 = body.find('"', q1 + 1);
  if (q2 == std::string::npos) {
    return fallback;
  }
  return JsonUnescape(body.substr(q1 + 1, q2 - q1 - 1));
}

std::vector<uint8_t> Base64Decode(const std::string& input) {
  if (input.empty()) return {};
  DWORD outLen = 0;
  if (!CryptStringToBinaryA(input.c_str(),
                            static_cast<DWORD>(input.size()),
                            CRYPT_STRING_BASE64,
                            nullptr,
                            &outLen,
                            nullptr,
                            nullptr)) {
    return {};
  }
  std::vector<uint8_t> out(outLen, 0);
  if (!CryptStringToBinaryA(input.c_str(),
                            static_cast<DWORD>(input.size()),
                            CRYPT_STRING_BASE64,
                            out.data(),
                            &outLen,
                            nullptr,
                            nullptr)) {
    return {};
  }
  out.resize(outLen);
  return out;
}

std::string ResolveWsHostPort(const ParsedRequest& req, uint16_t defaultPort) {
  auto it = req.headers.find("host");
  if (it == req.headers.end() || Trim(it->second).empty()) {
    return "127.0.0.1:" + std::to_string(defaultPort);
  }

  std::string host = Trim(it->second);
  // Strip potential scheme/prefix by defensive handling.
  const auto schemePos = host.find("://");
  if (schemePos != std::string::npos) {
    host = host.substr(schemePos + 3);
  }
  const auto slashPos = host.find('/');
  if (slashPos != std::string::npos) {
    host = host.substr(0, slashPos);
  }
  if (host.empty()) {
    return "127.0.0.1:" + std::to_string(defaultPort);
  }
  if (host.find(':') == std::string::npos) {
    host += ":" + std::to_string(defaultPort);
  }
  return host;
}

uint32_t JsonFieldUInt(const std::string& body, const std::string& key, uint32_t fallback) {
  const std::string token = "\"" + key + "\"";
  const auto keyPos = body.find(token);
  if (keyPos == std::string::npos) {
    return fallback;
  }
  const auto colon = body.find(':', keyPos + token.size());
  if (colon == std::string::npos) {
    return fallback;
  }
  size_t start = colon + 1;
  while (start < body.size() && std::isspace(static_cast<unsigned char>(body[start]))) {
    ++start;
  }
  size_t end = start;
  while (end < body.size() && std::isdigit(static_cast<unsigned char>(body[end]))) {
    ++end;
  }
  if (end <= start) {
    return fallback;
  }
  return static_cast<uint32_t>(std::strtoul(body.substr(start, end - start).c_str(), nullptr, 10));
}

std::string Utf16ToUtf8(const std::wstring& w) {
  if (w.empty()) return "";
  const int size = WideCharToMultiByte(CP_UTF8, 0, w.c_str(), -1, nullptr, 0, nullptr, nullptr);
  if (size <= 1) return "";
  std::string out(static_cast<size_t>(size - 1), '\0');
  WideCharToMultiByte(CP_UTF8, 0, w.c_str(), -1, out.data(), size, nullptr, nullptr);
  return out;
}

std::string Utf16ToUtf8(const wchar_t* w) {
  if (w == nullptr || *w == L'\0') return "";
  const int size = WideCharToMultiByte(CP_UTF8, 0, w, -1, nullptr, 0, nullptr, nullptr);
  if (size <= 1) return "";
  std::string out(static_cast<size_t>(size - 1), '\0');
  WideCharToMultiByte(CP_UTF8, 0, w, -1, out.data(), size, nullptr, nullptr);
  return out;
}

std::string GetDevRegPropertyString(HDEVINFO devInfo, SP_DEVINFO_DATA* devData, DWORD property) {
  DWORD dataType = 0;
  DWORD bytes = 0;
  SetupDiGetDeviceRegistryPropertyW(devInfo, devData, property, &dataType, nullptr, 0, &bytes);
  if (bytes == 0) return "";

  std::vector<wchar_t> buf((bytes / sizeof(wchar_t)) + 2, L'\0');
  if (!SetupDiGetDeviceRegistryPropertyW(devInfo,
                                         devData,
                                         property,
                                         &dataType,
                                         reinterpret_cast<PBYTE>(buf.data()),
                                         static_cast<DWORD>(buf.size() * sizeof(wchar_t)),
                                         &bytes)) {
    return "";
  }

  return Utf16ToUtf8(buf.data());
}

bool LooksLikeAndroid(const std::string& text) {
  const std::string lower = ToLower(text);
  static const char* kTokens[] = {
      "android", "adb", "mtp", "rndis", "pixel", "samsung", "xiaomi", "huawei", "oppo", "vivo", "oneplus"};
  for (const char* token : kTokens) {
    if (lower.find(token) != std::string::npos) {
      return true;
    }
  }

  static const char* kAndroidVidTokens[] = {"vid_18d1", "vid_04e8", "vid_2717", "vid_2a70", "vid_22d9", "vid_2d95"};
  for (const char* token : kAndroidVidTokens) {
    if (lower.find(token) != std::string::npos) {
      return true;
    }
  }
  return false;
}

std::vector<UsbNativeDevice> EnumerateUsbNativeDevices() {
  std::vector<UsbNativeDevice> out;
  HDEVINFO devInfo = SetupDiGetClassDevsW(&GUID_DEVINTERFACE_USB_DEVICE, nullptr, nullptr,
                                          DIGCF_DEVICEINTERFACE | DIGCF_PRESENT);
  if (devInfo == INVALID_HANDLE_VALUE) {
    return out;
  }

  for (DWORD i = 0;; ++i) {
    SP_DEVICE_INTERFACE_DATA ifData{};
    ifData.cbSize = sizeof(ifData);
    if (!SetupDiEnumDeviceInterfaces(devInfo, nullptr, &GUID_DEVINTERFACE_USB_DEVICE, i, &ifData)) {
      break;
    }

    DWORD required = 0;
    SetupDiGetDeviceInterfaceDetailW(devInfo, &ifData, nullptr, 0, &required, nullptr);
    if (required == 0) continue;
    std::vector<uint8_t> detailBuf(required, 0);
    auto* detail = reinterpret_cast<SP_DEVICE_INTERFACE_DETAIL_DATA_W*>(detailBuf.data());
    detail->cbSize = sizeof(SP_DEVICE_INTERFACE_DETAIL_DATA_W);

    SP_DEVINFO_DATA devData{};
    devData.cbSize = sizeof(devData);
    if (!SetupDiGetDeviceInterfaceDetailW(devInfo, &ifData, detail, required, nullptr, &devData)) {
      continue;
    }

    UsbNativeDevice dev;
    dev.path = Utf16ToUtf8(detail->DevicePath);
    dev.description = GetDevRegPropertyString(devInfo, &devData, SPDRP_FRIENDLYNAME);
    if (dev.description.empty()) {
      dev.description = GetDevRegPropertyString(devInfo, &devData, SPDRP_DEVICEDESC);
    }
    dev.hardwareId = GetDevRegPropertyString(devInfo, &devData, SPDRP_HARDWAREID);
    dev.androidCandidate = LooksLikeAndroid(dev.description) || LooksLikeAndroid(dev.hardwareId);
    out.push_back(std::move(dev));
  }

  SetupDiDestroyDeviceInfoList(devInfo);
  return out;
}

std::string UsbNativeDevicesJson(const std::vector<UsbNativeDevice>& devices) {
  std::ostringstream body;
  body << "{\"devices\":[";
  for (size_t i = 0; i < devices.size(); ++i) {
    const auto& d = devices[i];
    if (i > 0) body << ",";
    body << "{\"path\":\"" << JsonEscape(d.path)
         << "\",\"description\":\"" << JsonEscape(d.description)
         << "\",\"hardwareId\":\"" << JsonEscape(d.hardwareId)
         << "\",\"androidCandidate\":" << (d.androidCandidate ? "true" : "false")
         << "}";
  }
  body << "]}";
  return body.str();
}

bool RunCommandHidden(const std::string& commandLine) {
  STARTUPINFOA si{};
  si.cb = sizeof(si);
  si.dwFlags = STARTF_USESHOWWINDOW;
  si.wShowWindow = SW_HIDE;

  PROCESS_INFORMATION pi{};
  std::vector<char> cmd(commandLine.begin(), commandLine.end());
  cmd.push_back('\0');

  if (!CreateProcessA(nullptr, cmd.data(), nullptr, nullptr, FALSE, CREATE_NO_WINDOW, nullptr, nullptr, &si, &pi)) {
    return false;
  }

  WaitForSingleObject(pi.hProcess, 8000);
  DWORD exitCode = 1;
  GetExitCodeProcess(pi.hProcess, &exitCode);
  CloseHandle(pi.hThread);
  CloseHandle(pi.hProcess);
  return exitCode == 0;
}

bool SendAll(SOCKET s, const char* data, size_t len) {
  size_t sentTotal = 0;
  while (sentTotal < len) {
    const int sent = send(s, data + sentTotal, static_cast<int>(len - sentTotal), 0);
    if (sent <= 0) {
      return false;
    }
    sentTotal += static_cast<size_t>(sent);
  }
  return true;
}

bool RecvAll(SOCKET s, uint8_t* data, size_t len) {
  size_t readTotal = 0;
  while (readTotal < len) {
    const int n = recv(s, reinterpret_cast<char*>(data + readTotal), static_cast<int>(len - readTotal), 0);
    if (n <= 0) {
      return false;
    }
    readTotal += static_cast<size_t>(n);
  }
  return true;
}

uint16_t ReadBE16(const uint8_t* p) {
  return static_cast<uint16_t>((static_cast<uint16_t>(p[0]) << 8) | p[1]);
}

uint64_t ReadBE64(const uint8_t* p) {
  uint64_t v = 0;
  for (int i = 0; i < 8; ++i) {
    v = (v << 8) | p[i];
  }
  return v;
}

uint32_t ReadLE32(const uint8_t* p) {
  return static_cast<uint32_t>(p[0]) | (static_cast<uint32_t>(p[1]) << 8) |
         (static_cast<uint32_t>(p[2]) << 16) | (static_cast<uint32_t>(p[3]) << 24);
}

bool HasAnnexBStartCode(const uint8_t* data, size_t size) {
  if (data == nullptr || size < 4) return false;
  const size_t scan = std::min<size_t>(size, 64);
  for (size_t i = 0; i + 3 < scan; ++i) {
    if (data[i] == 0x00 && data[i + 1] == 0x00 &&
        ((data[i + 2] == 0x01) || (data[i + 2] == 0x00 && data[i + 3] == 0x01))) {
      return true;
    }
  }
  return false;
}

bool ConvertAvccToAnnexB(const uint8_t* data, size_t size, std::vector<uint8_t>* out) {
  if (data == nullptr || out == nullptr || size < 5) return false;
  out->clear();
  out->reserve(size + 128);

  size_t off = 0;
  while (off + 4 <= size) {
    const uint32_t naluLen = (static_cast<uint32_t>(data[off]) << 24) |
                             (static_cast<uint32_t>(data[off + 1]) << 16) |
                             (static_cast<uint32_t>(data[off + 2]) << 8) |
                             static_cast<uint32_t>(data[off + 3]);
    off += 4;
    if (naluLen == 0) {
      continue;
    }
    if (off + naluLen > size) {
      out->clear();
      return false;
    }
    out->push_back(0x00);
    out->push_back(0x00);
    out->push_back(0x00);
    out->push_back(0x01);
    out->insert(out->end(), data + off, data + off + naluLen);
    off += naluLen;
  }
  return !out->empty();
}

bool LooksLikeAvcDecoderConfigurationRecord(const uint8_t* data, size_t size) {
  if (data == nullptr || size < 7) return false;
  // AVCDecoderConfigurationRecord starts with configurationVersion=1.
  if (data[0] != 0x01) return false;
  if (HasAnnexBStartCode(data, size)) return false;
  // If first 4 bytes look like a valid NAL length, this is more likely AVCC NAL stream, not config record.
  const uint32_t naluLen = (static_cast<uint32_t>(data[0]) << 24) |
                           (static_cast<uint32_t>(data[1]) << 16) |
                           (static_cast<uint32_t>(data[2]) << 8) |
                           static_cast<uint32_t>(data[3]);
  if (naluLen > 0 && naluLen + 4 <= size) return false;
  return true;
}

template <typename T>
void SafeRelease(T** p) {
  if (p && *p) {
    (*p)->Release();
    *p = nullptr;
  }
}

inline uint8_t ClampU8(int v) {
  if (v < 0) return 0;
  if (v > 255) return 255;
  return static_cast<uint8_t>(v);
}

void NV12ToBGRA(const uint8_t* nv12,
                uint32_t width,
                uint32_t height,
                int yStride,
                int uvStride,
                std::vector<uint8_t>* outBgra) {
  if (yStride <= 0) yStride = static_cast<int>(width);
  if (uvStride <= 0) uvStride = yStride;
  const uint8_t* yPlane = nv12;
  const uint8_t* uvPlane = nv12 + static_cast<size_t>(yStride) * static_cast<size_t>(height);
  const size_t yPlaneSize = static_cast<size_t>(width) * static_cast<size_t>(height);
  outBgra->resize(yPlaneSize * 4);

  for (uint32_t y = 0; y < height; ++y) {
    for (uint32_t x = 0; x < width; ++x) {
      const int yv = static_cast<int>(yPlane[static_cast<size_t>(y) * yStride + x]);
      const size_t uvIndex = static_cast<size_t>(y / 2) * static_cast<size_t>(uvStride) + (x & ~1U);
      const int u = static_cast<int>(uvPlane[uvIndex + 0]) - 128;
      const int v = static_cast<int>(uvPlane[uvIndex + 1]) - 128;

      const int c = yv - 16;
      const int d = u;
      const int e = v;
      const int r = (298 * c + 409 * e + 128) >> 8;
      const int g = (298 * c - 100 * d - 208 * e + 128) >> 8;
      const int b = (298 * c + 516 * d + 128) >> 8;

      const size_t out = (static_cast<size_t>(y) * width + x) * 4;
      (*outBgra)[out + 0] = ClampU8(b);
      (*outBgra)[out + 1] = ClampU8(g);
      (*outBgra)[out + 2] = ClampU8(r);
      (*outBgra)[out + 3] = 255;
    }
  }
}

class H264MftDecoder {
 public:
  ~H264MftDecoder() { Reset(); }

  void Reset() {
    SafeRelease(&decoder_);
    inputStreamId_ = 0;
    outputStreamId_ = 0;
    width_ = 0;
    height_ = 0;
    outputTypeSet_ = false;
    streamStarted_ = false;
  }

  bool DecodeAnnexB(const uint8_t* data, size_t size, std::vector<uint8_t>* outBgra, uint32_t* outW, uint32_t* outH) {
    if (!EnsureDecoder()) {
      return false;
    }

    IMFSample* inSample = nullptr;
    IMFMediaBuffer* inBuffer = nullptr;
    if (FAILED(MFCreateSample(&inSample))) {
      return false;
    }
    if (FAILED(MFCreateMemoryBuffer(static_cast<DWORD>(size), &inBuffer))) {
      SafeRelease(&inSample);
      return false;
    }

    BYTE* dst = nullptr;
    DWORD maxLen = 0;
    DWORD curLen = 0;
    if (FAILED(inBuffer->Lock(&dst, &maxLen, &curLen))) {
      SafeRelease(&inBuffer);
      SafeRelease(&inSample);
      return false;
    }
    memcpy(dst, data, size);
    inBuffer->Unlock();
    inBuffer->SetCurrentLength(static_cast<DWORD>(size));
    inSample->AddBuffer(inBuffer);
    SafeRelease(&inBuffer);

    const HRESULT inHr = decoder_->ProcessInput(inputStreamId_, inSample, 0);
    SafeRelease(&inSample);
    if (FAILED(inHr) && inHr != MF_E_NOTACCEPTING) {
      return false;
    }

    bool gotFrame = false;
    while (true) {
      MFT_OUTPUT_STREAM_INFO outInfo{};
      if (FAILED(decoder_->GetOutputStreamInfo(outputStreamId_, &outInfo))) {
        break;
      }

      IMFSample* outSample = nullptr;
      IMFMediaBuffer* outBuffer = nullptr;
      if (!(outInfo.dwFlags & MFT_OUTPUT_STREAM_PROVIDES_SAMPLES)) {
        if (FAILED(MFCreateSample(&outSample))) break;
        if (FAILED(MFCreateMemoryBuffer(outInfo.cbSize, &outBuffer))) {
          SafeRelease(&outSample);
          break;
        }
        outSample->AddBuffer(outBuffer);
        SafeRelease(&outBuffer);
      }

      MFT_OUTPUT_DATA_BUFFER outData{};
      outData.dwStreamID = outputStreamId_;
      outData.pSample = outSample;
      DWORD status = 0;
      HRESULT hr = decoder_->ProcessOutput(0, 1, &outData, &status);

      if (hr == MF_E_TRANSFORM_NEED_MORE_INPUT) {
        SafeRelease(&outSample);
        break;
      }
      if (hr == MF_E_TRANSFORM_STREAM_CHANGE) {
        SafeRelease(&outSample);
        if (!SetOutputTypeNV12()) {
          break;
        }
        continue;
      }
      if (FAILED(hr)) {
        SafeRelease(&outSample);
        break;
      }

      IMFSample* produced = outData.pSample;
      IMFMediaBuffer* contiguous = nullptr;
      if (produced && SUCCEEDED(produced->ConvertToContiguousBuffer(&contiguous))) {
        IMF2DBuffer* buffer2d = nullptr;
        BYTE* scan0 = nullptr;
        LONG stride = 0;
        bool converted = false;

        if (SUCCEEDED(contiguous->QueryInterface(IID_PPV_ARGS(&buffer2d))) &&
            SUCCEEDED(buffer2d->Lock2D(&scan0, &stride))) {
          if (width_ > 0 && height_ > 0) {
            NV12ToBGRA(scan0, width_, height_, static_cast<int>(stride), static_cast<int>(stride), outBgra);
            *outW = width_;
            *outH = height_;
            gotFrame = true;
            converted = true;
          }
          buffer2d->Unlock2D();
        }

        if (!converted) {
          BYTE* src = nullptr;
          DWORD srcMax = 0;
          DWORD srcLen = 0;
          if (SUCCEEDED(contiguous->Lock(&src, &srcMax, &srcLen))) {
            if (width_ > 0 && height_ > 0 && srcLen >= width_ * height_ * 3 / 2) {
              NV12ToBGRA(src, width_, height_, static_cast<int>(width_), static_cast<int>(width_), outBgra);
              *outW = width_;
              *outH = height_;
              gotFrame = true;
            }
            contiguous->Unlock();
          }
        }

        SafeRelease(&buffer2d);
      }
      SafeRelease(&contiguous);
      if (outData.pEvents) outData.pEvents->Release();
      SafeRelease(&produced);

      if (gotFrame) break;
    }

    return gotFrame;
  }

 private:
  bool EnsureDecoder() {
    if (decoder_) return true;

    if (FAILED(CoCreateInstance(CLSID_CMSH264DecoderMFT, nullptr, CLSCTX_INPROC_SERVER, IID_PPV_ARGS(&decoder_)))) {
      return false;
    }

    MFT_INPUT_STREAM_INFO inInfo{};
    if (FAILED(decoder_->GetInputStreamInfo(0, &inInfo))) {
      return false;
    }
    inputStreamId_ = 0;
    outputStreamId_ = 0;

    IMFMediaType* inType = nullptr;
    if (FAILED(MFCreateMediaType(&inType))) {
      return false;
    }
    inType->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Video);
    inType->SetGUID(MF_MT_SUBTYPE, MFVideoFormat_H264);
    inType->SetUINT32(MF_MT_INTERLACE_MODE, MFVideoInterlace_Progressive);
    const HRESULT setIn = decoder_->SetInputType(inputStreamId_, inType, 0);
    SafeRelease(&inType);
    if (FAILED(setIn)) {
      return false;
    }

    if (!SetOutputTypeNV12()) {
      return false;
    }

    decoder_->ProcessMessage(MFT_MESSAGE_COMMAND_FLUSH, 0);
    decoder_->ProcessMessage(MFT_MESSAGE_NOTIFY_BEGIN_STREAMING, 0);
    decoder_->ProcessMessage(MFT_MESSAGE_NOTIFY_START_OF_STREAM, 0);
    streamStarted_ = true;
    return true;
  }

  bool SetOutputTypeNV12() {
    if (!decoder_) return false;
    IMFMediaType* outType = nullptr;
    DWORD i = 0;
    while (SUCCEEDED(decoder_->GetOutputAvailableType(outputStreamId_, i, &outType))) {
      GUID subtype{};
      if (SUCCEEDED(outType->GetGUID(MF_MT_SUBTYPE, &subtype)) && subtype == MFVideoFormat_NV12) {
        if (SUCCEEDED(decoder_->SetOutputType(outputStreamId_, outType, 0))) {
          UINT32 w = 0;
          UINT32 h = 0;
          MFGetAttributeSize(outType, MF_MT_FRAME_SIZE, &w, &h);
          width_ = w;
          height_ = h;
          outputTypeSet_ = true;
          SafeRelease(&outType);
          return true;
        }
      }
      SafeRelease(&outType);
      ++i;
    }
    return false;
  }

  IMFTransform* decoder_ = nullptr;
  DWORD inputStreamId_ = 0;
  DWORD outputStreamId_ = 0;
  uint32_t width_ = 0;
  uint32_t height_ = 0;
  bool outputTypeSet_ = false;
  bool streamStarted_ = false;
};

std::mutex gDecoderMutex;
H264MftDecoder gH264Decoder;

std::string WebSocketAcceptKey(const std::string& clientKey) {
  const std::string source = clientKey + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

  BCRYPT_ALG_HANDLE alg = nullptr;
  BCRYPT_HASH_HANDLE hash = nullptr;
  DWORD objLen = 0;
  DWORD cbData = 0;

  if (BCryptOpenAlgorithmProvider(&alg, BCRYPT_SHA1_ALGORITHM, nullptr, 0) < 0) {
    return "";
  }
  if (BCryptGetProperty(alg, BCRYPT_OBJECT_LENGTH, reinterpret_cast<PUCHAR>(&objLen), sizeof(objLen), &cbData, 0) < 0) {
    BCryptCloseAlgorithmProvider(alg, 0);
    return "";
  }
  std::vector<uint8_t> hashObj(objLen);
  if (BCryptCreateHash(alg, &hash, hashObj.data(), objLen, nullptr, 0, 0) < 0) {
    BCryptCloseAlgorithmProvider(alg, 0);
    return "";
  }
  if (BCryptHashData(hash, reinterpret_cast<PUCHAR>(const_cast<char*>(source.data())), static_cast<ULONG>(source.size()), 0) < 0) {
    BCryptDestroyHash(hash);
    BCryptCloseAlgorithmProvider(alg, 0);
    return "";
  }
  std::vector<uint8_t> digest(20);
  if (BCryptFinishHash(hash, digest.data(), static_cast<ULONG>(digest.size()), 0) < 0) {
    BCryptDestroyHash(hash);
    BCryptCloseAlgorithmProvider(alg, 0);
    return "";
  }
  BCryptDestroyHash(hash);
  BCryptCloseAlgorithmProvider(alg, 0);

  DWORD base64Len = 0;
  if (!CryptBinaryToStringA(digest.data(),
                            static_cast<DWORD>(digest.size()),
                            CRYPT_STRING_BASE64 | CRYPT_STRING_NOCRLF,
                            nullptr,
                            &base64Len)) {
    return "";
  }

  std::string out(base64Len, '\0');
  if (!CryptBinaryToStringA(digest.data(),
                            static_cast<DWORD>(digest.size()),
                            CRYPT_STRING_BASE64 | CRYPT_STRING_NOCRLF,
                            out.data(),
                            &base64Len)) {
    return "";
  }
  if (!out.empty() && out.back() == '\0') {
    out.pop_back();
  }
  return out;
}

}  // namespace

ReceiverServer::ReceiverServer(uint16_t port) : port_(port) {}

ReceiverServer::~ReceiverServer() { Stop(); }

bool ReceiverServer::Start() {
  if (running_) {
    return true;
  }

  if (!mfStarted_) {
    if (FAILED(MFStartup(MF_VERSION, MFSTARTUP_NOSOCKET))) {
      return false;
    }
    mfStarted_ = true;
  }

  WSADATA wsaData{};
  if (WSAStartup(MAKEWORD(2, 2), &wsaData) != 0) {
    return false;
  }

  SOCKET s = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
  if (s == INVALID_SOCKET) {
    WSACleanup();
    return false;
  }

  sockaddr_in addr{};
  addr.sin_family = AF_INET;
  addr.sin_addr.s_addr = htonl(INADDR_ANY);
  addr.sin_port = htons(port_);

  int opt = 1;
  setsockopt(s, SOL_SOCKET, SO_REUSEADDR, reinterpret_cast<const char*>(&opt), sizeof(opt));

  if (bind(s, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) == SOCKET_ERROR) {
    closesocket(s);
    WSACleanup();
    return false;
  }

  if (listen(s, SOMAXCONN) == SOCKET_ERROR) {
    closesocket(s);
    WSACleanup();
    return false;
  }

  listenSocket_ = static_cast<uintptr_t>(s);
  running_ = true;
  std::thread([this] { AcceptLoop(); }).detach();
  return true;
}

void ReceiverServer::Stop() {
  if (!running_) {
    if (mfStarted_) {
      MFShutdown();
      mfStarted_ = false;
    }
    return;
  }
  running_ = false;

  SOCKET s = static_cast<SOCKET>(listenSocket_);
  if (s != kInvalidSocket) {
    closesocket(s);
  }
  listenSocket_ = static_cast<uintptr_t>(kInvalidSocket);
  WSACleanup();

  if (mfStarted_) {
    std::lock_guard<std::mutex> lock(gDecoderMutex);
    gH264Decoder.Reset();
    MFShutdown();
    mfStarted_ = false;
  }
}

uint64_t ReceiverServer::NowMs() {
  using namespace std::chrono;
  return duration_cast<milliseconds>(steady_clock::now().time_since_epoch()).count();
}

bool ReceiverServer::HandleWebSocketV2(uintptr_t clientSocket, const std::string& rawRequest) {
  SOCKET client = static_cast<SOCKET>(clientSocket);

  ParsedRequest req;
  if (!ParseRequest(rawRequest, &req)) {
    return false;
  }

  const auto keyIt = req.headers.find("sec-websocket-key");
  if (keyIt == req.headers.end()) {
    return false;
  }

  const std::string accept = WebSocketAcceptKey(keyIt->second);
  if (accept.empty()) {
    return false;
  }

  std::ostringstream hs;
  hs << "HTTP/1.1 101 Switching Protocols\r\n"
     << "Upgrade: websocket\r\n"
     << "Connection: Upgrade\r\n"
     << "Sec-WebSocket-Accept: " << accept << "\r\n\r\n";
  const std::string hsResp = hs.str();
  if (!SendAll(client, hsResp.data(), hsResp.size())) {
    return false;
  }

  while (running_) {
    uint8_t hdr[2];
    if (!RecvAll(client, hdr, sizeof(hdr))) {
      return true;
    }

    const uint8_t opcode = hdr[0] & 0x0F;
    const bool masked = (hdr[1] & 0x80) != 0;
    uint64_t payloadLen = (hdr[1] & 0x7F);

    if (opcode == 0x8) {
      return true;
    }
    if (!masked) {
      return false;
    }

    if (payloadLen == 126) {
      uint8_t ext[2];
      if (!RecvAll(client, ext, sizeof(ext))) {
        return false;
      }
      payloadLen = ReadBE16(ext);
    } else if (payloadLen == 127) {
      uint8_t ext[8];
      if (!RecvAll(client, ext, sizeof(ext))) {
        return false;
      }
      payloadLen = ReadBE64(ext);
    }

    uint8_t mask[4];
    if (!RecvAll(client, mask, sizeof(mask))) {
      return false;
    }

    if (payloadLen > (8ULL * 1024ULL * 1024ULL)) {
      return false;
    }

    std::vector<uint8_t> payload(static_cast<size_t>(payloadLen));
    if (payloadLen > 0 && !RecvAll(client, payload.data(), payload.size())) {
      return false;
    }

    for (size_t i = 0; i < payload.size(); ++i) {
      payload[i] ^= mask[i % 4];
    }

    if (opcode != 0x2) {
      continue;
    }

    if (payload.size() < 24) {
      continue;
    }

    ProcessV2MediaPacket(payload.data(), payload.size());
  }

  return true;
}

std::string ReceiverServer::HandleRequest(const std::string& request) {
  ParsedRequest req;
  if (!ParseRequest(request, &req)) {
    return Json(400, R"({"error":"bad_request"})");
  }

  if (req.method == "OPTIONS") {
    return HttpResponse(200, "text/plain", "",
                        {{"Access-Control-Allow-Methods", "GET, POST, OPTIONS"},
                         {"Access-Control-Allow-Headers", "Content-Type, X-Width, X-Height, X-Session-Id"}});
  }

  if (req.method == "GET" && req.path == "/api/v1/devices") {
    return Json(200, R"({"devices":[{"id":"android-camera","name":"Android Camera"}]})");
  }

  if (req.method == "GET" && req.path == "/api/v2/usb-native/devices") {
    const auto devices = EnumerateUsbNativeDevices();
    {
      std::lock_guard<std::mutex> lock(usbNativeMutex_);
      usbNativeLastScanMs_ = NowMs();
      if (devices.empty()) {
        usbNativeConnected_ = false;
        usbNativeDevicePath_.clear();
        usbNativeDescription_.clear();
        usbNativeLastError_ = "no_usb_devices";
      } else {
        const auto it = std::find_if(devices.begin(), devices.end(), [](const UsbNativeDevice& d) { return d.androidCandidate; });
        if (it != devices.end()) {
          usbNativeConnected_ = true;
          usbNativeDevicePath_ = it->path;
          usbNativeDescription_ = it->description;
          usbNativeLastError_.clear();
        } else {
          usbNativeConnected_ = false;
          usbNativeDevicePath_.clear();
          usbNativeDescription_.clear();
          usbNativeLastError_ = "no_android_candidate";
        }
      }
    }
    return Json(200, UsbNativeDevicesJson(devices));
  }

  if (req.method == "GET" && req.path == "/api/v2/usb-native/status") {
    bool connected = false;
    std::string path;
    std::string description;
    std::string error;
    uint64_t lastScan = 0;
    {
      std::lock_guard<std::mutex> lock(usbNativeMutex_);
      connected = usbNativeConnected_;
      path = usbNativeDevicePath_;
      description = usbNativeDescription_;
      error = usbNativeLastError_;
      lastScan = usbNativeLastScanMs_;
    }
    std::ostringstream body;
    body << "{\"connected\":" << (connected ? "true" : "false")
         << ",\"devicePath\":\"" << JsonEscape(path)
         << "\",\"description\":\"" << JsonEscape(description)
         << "\",\"lastError\":\"" << JsonEscape(error)
         << "\",\"lastScanMs\":" << lastScan
         << "}";
    return Json(200, body.str());
  }

  if (req.method == "GET" && req.path == "/api/v2/usb-native/link") {
    bool active = false;
    std::string linkId;
    std::string sessionId;
    std::string devicePath;
    uint64_t lastPacketMs = 0;
    uint64_t rxPackets = 0;
    uint64_t rxBytes = 0;
    uint32_t expectedSeq = 0;
    uint32_t mtu = 0;
    std::string lastError;
    {
      std::lock_guard<std::mutex> lock(usbLinkMutex_);
      active = usbLinkActive_;
      linkId = usbLinkId_;
      sessionId = usbLinkSessionId_;
      devicePath = usbLinkDevicePath_;
      lastPacketMs = usbLinkLastPacketMs_;
      rxPackets = usbLinkRxPackets_;
      rxBytes = usbLinkRxBytes_;
      expectedSeq = usbLinkExpectedSeq_;
      mtu = usbLinkMtu_;
      lastError = usbLinkLastError_;
    }
    std::ostringstream body;
    body << "{\"active\":" << (active ? "true" : "false")
         << ",\"linkId\":\"" << JsonEscape(linkId)
         << "\",\"sessionId\":\"" << JsonEscape(sessionId)
         << "\",\"devicePath\":\"" << JsonEscape(devicePath)
         << "\",\"lastPacketMs\":" << lastPacketMs
         << ",\"rxPackets\":" << rxPackets
         << ",\"rxBytes\":" << rxBytes
         << ",\"expectedSeq\":" << expectedSeq
         << ",\"mtu\":" << mtu
         << ",\"lastError\":\"" << JsonEscape(lastError) << "\""
         << "}";
    return Json(200, body.str());
  }

  if (req.method == "POST" && req.path == "/api/v2/usb-aoa/connect") {
    const std::string devicePath = JsonField(req.body, "devicePath", "");
    // Stop any existing AOA connection
    if (usbAoa_) {
      usbAoa_->Stop();
      usbAoa_.reset();
    }
    // Create new AOA transport with callback to ProcessV2MediaPacket
    usbAoa_ = std::make_unique<UsbAoaTransport>(
        [this](const uint8_t* data, size_t size) {
          ProcessV2MediaPacket(data, size);
          std::lock_guard<std::mutex> lock(usbAoaMutex_);
          usbAoaRxPackets_ += 1;
          usbAoaRxBytes_ += size;
          usbAoaLastPacketMs_ = NowMs();
        });
    std::wstring wDevicePath;
    if (!devicePath.empty()) {
      int wlen = MultiByteToWideChar(CP_UTF8, 0, devicePath.c_str(), -1, nullptr, 0);
      if (wlen > 0) {
        wDevicePath.resize(wlen - 1);
        MultiByteToWideChar(CP_UTF8, 0, devicePath.c_str(), -1, &wDevicePath[0], wlen);
      }
    }
    const bool ok = usbAoa_->StartHandshake(wDevicePath);
    if (ok) {
      {
        std::lock_guard<std::mutex> lock(usbAoaMutex_);
        usbAoaConnected_ = true;
        usbAoaLastError_.clear();
        usbAoaRxPackets_ = 0;
        usbAoaRxBytes_ = 0;
        usbAoaLastPacketMs_ = 0;
      }
      usbAoa_->StartBulkRead();
    } else {
      std::lock_guard<std::mutex> lock(usbAoaMutex_);
      usbAoaConnected_ = false;
      usbAoaLastError_ = usbAoa_ ? usbAoa_->GetLastError() : "unknown_error";
    }
    std::ostringstream body;
    body << "{\"ok\":" << (ok ? "true" : "false");
    if (usbAoa_) {
      body << ",\"state\":\"" << (ok ? "connected" : "error") << "\"";
      body << ",\"devicePath\":\"" << JsonEscape(usbAoa_->GetDevicePath()) << "\"";
      if (!ok) {
        body << ",\"error\":\"" << JsonEscape(usbAoa_->GetLastError()) << "\"";
      }
    }
    body << "}";
    return Json(ok ? 200 : 503, body.str());
  }

  if (req.method == "POST" && req.path == "/api/v2/usb-aoa/disconnect") {
    if (usbAoa_) {
      usbAoa_->Stop();
      usbAoa_.reset();
    }
    {
      std::lock_guard<std::mutex> lock(usbAoaMutex_);
      usbAoaConnected_ = false;
      usbAoaLastError_.clear();
    }
    return Json(200, R"({"ok":true})");
  }

  if (req.method == "GET" && req.path == "/api/v2/usb-aoa/status") {
    bool connected = false;
    std::string lastError;
    uint64_t rxPackets = 0;
    uint64_t rxBytes = 0;
    uint64_t lastPacketMs = 0;
    std::string state = "disconnected";
    std::string devicePath;
    {
      std::lock_guard<std::mutex> lock(usbAoaMutex_);
      connected = usbAoaConnected_;
      lastError = usbAoaLastError_;
      rxPackets = usbAoaRxPackets_;
      rxBytes = usbAoaRxBytes_;
      lastPacketMs = usbAoaLastPacketMs_;
      if (usbAoa_) {
        auto aoaState = usbAoa_->GetState();
        switch (aoaState) {
          case AoaState::Disconnected: state = "disconnected"; break;
          case AoaState::Handshaking: state = "handshaking"; break;
          case AoaState::Connected: state = "connected"; break;
          case AoaState::Error: state = "error"; break;
        }
        devicePath = usbAoa_->GetDevicePath();
        if (lastError.empty()) {
          lastError = usbAoa_->GetLastError();
        }
      }
    }
    std::ostringstream body;
    body << "{\"connected\":" << (connected ? "true" : "false")
         << ",\"state\":\"" << state << "\""
         << ",\"devicePath\":\"" << JsonEscape(devicePath) << "\""
         << ",\"rxPackets\":" << rxPackets
         << ",\"rxBytes\":" << rxBytes
         << ",\"lastPacketMs\":" << lastPacketMs
         << ",\"lastError\":\"" << JsonEscape(lastError) << "\""
         << "}";
    return Json(200, body.str());
  }

  if (req.method == "POST" && req.path == "/api/v2/usb-native/handshake") {
    const std::string sessionId = JsonField(req.body, "sessionId", "");
    const std::string devicePath = JsonField(req.body, "devicePath", "");
    const uint32_t mtu = JsonFieldUInt(req.body, "mtu", 16384);
    if (sessionId.empty()) {
      return Json(400, R"({"error":"missing_session_id"})");
    }
    if (sessionId != activeSessionId_) {
      return Json(400, R"({"error":"session_mismatch"})");
    }

    const std::string linkId = "usb_" + std::to_string(NowMs());
    {
      std::lock_guard<std::mutex> lock(usbLinkMutex_);
      usbLinkActive_ = true;
      usbLinkId_ = linkId;
      usbLinkSessionId_ = sessionId;
      usbLinkDevicePath_ = devicePath;
      usbLinkLastPacketMs_ = 0;
      usbLinkRxPackets_ = 0;
      usbLinkRxBytes_ = 0;
      usbLinkExpectedSeq_ = 0;
      usbLinkMtu_ = std::clamp<uint32_t>(mtu, 1024, 1U << 20);
      usbLinkLastError_.clear();
    }
    std::ostringstream body;
    body << "{\"ok\":true,\"linkId\":\"" << JsonEscape(linkId)
         << "\",\"ackWindow\":64,\"mtu\":" << std::clamp<uint32_t>(mtu, 1024, 1U << 20) << "}";
    return Json(200, body.str());
  }

  if (req.method == "POST" && req.path == "/api/v2/usb-native/packet") {
    const std::string linkId = JsonField(req.body, "linkId", "");
    const uint32_t seq = JsonFieldUInt(req.body, "seq", 0);
    const uint32_t size = JsonFieldUInt(req.body, "size", 0);
    const std::string payloadBase64 = JsonField(req.body, "payload", "");
    if (linkId.empty()) {
      return Json(400, R"({"error":"missing_link_id"})");
    }

    uint32_t expectedSeq = 0;
    uint64_t rxPackets = 0;
    uint64_t rxBytes = 0;
    bool seqMismatch = false;
    {
      std::lock_guard<std::mutex> lock(usbLinkMutex_);
      if (!usbLinkActive_ || linkId != usbLinkId_) {
        return Json(400, R"({"error":"link_not_active"})");
      }
      expectedSeq = usbLinkExpectedSeq_;
      if (seq != usbLinkExpectedSeq_) {
        seqMismatch = true;
        usbLinkLastError_ = "seq_mismatch";
        usbLinkExpectedSeq_ = seq + 1;
      } else {
        usbLinkExpectedSeq_ += 1;
        usbLinkLastError_.clear();
      }
      usbLinkLastPacketMs_ = NowMs();
      usbLinkRxPackets_ += 1;
      usbLinkRxBytes_ += size;
      rxPackets = usbLinkRxPackets_;
      rxBytes = usbLinkRxBytes_;
      expectedSeq = usbLinkExpectedSeq_;
    }

    if (!payloadBase64.empty()) {
      const auto packet = Base64Decode(payloadBase64);
      if (!packet.empty()) {
        ProcessUsbV2MediaPacket(packet.data(), packet.size());
      } else {
        std::lock_guard<std::mutex> lock(usbLinkMutex_);
        usbLinkLastError_ = "invalid_payload_base64";
      }
    }

    std::ostringstream body;
    body << "{\"ok\":" << (seqMismatch ? "false" : "true")
         << ",\"expectedSeq\":" << expectedSeq
         << ",\"rxPackets\":" << rxPackets
         << ",\"rxBytes\":" << rxBytes
         << ",\"seqMismatch\":" << (seqMismatch ? "true" : "false")
         << "}";
    return Json(200, body.str());
  }

  if (req.method == "GET" && req.path.rfind("/ws/v2/media", 0) == 0) {
    return Json(400, R"({"error":"websocket_upgrade_required","hint":"Use WebSocket client for /ws/v2/media"})");
  }

  if (req.method == "POST" && req.path == "/api/v1/adb/setup") {
    const bool okReverse = RunCommandHidden("adb reverse tcp:39393 tcp:39393");
    const bool okForward = RunCommandHidden("adb forward tcp:39394 tcp:39394");
    std::ostringstream body;
    body << "{\"ok\":" << (okReverse ? "true" : "false")
         << ",\"reverse\":" << (okReverse ? "true" : "false")
         << ",\"forward\":" << (okForward ? "true" : "false") << "}";
    return Json(okReverse ? 200 : 500, body.str());
  }

  if (req.method == "POST" && req.path == "/api/v2/adb/setup") {
    const bool okReverse = RunCommandHidden("adb reverse tcp:39393 tcp:39393");
    const bool okForward = RunCommandHidden("adb forward tcp:39394 tcp:39394");
    std::ostringstream body;
    body << "{\"ok\":" << (okReverse ? "true" : "false")
         << ",\"reverse\":" << (okReverse ? "true" : "false")
         << ",\"forward\":" << (okForward ? "true" : "false") << "}";
    return Json(okReverse ? 200 : 500, body.str());
  }

  if (req.method == "POST" && req.path == "/api/v1/session/start") {
    const std::string transport = JsonField(req.body, "transport", "lan");
    if (transport == "usb-adb") {
      RunCommandHidden("adb reverse tcp:39393 tcp:39393");
      RunCommandHidden("adb forward tcp:39394 tcp:39394");
    }

    activeSessionId_ = "sess_" + std::to_string(NowMs());
    std::ostringstream body;
    body << "{\"sessionId\":\"" << JsonEscape(activeSessionId_)
         << "\",\"offerSdp\":\"\",\"qrPayload\":\"acb://pair/" << JsonEscape(activeSessionId_) << "\"}";
    return Json(200, body.str());
  }

  if (req.method == "POST" && req.path == "/api/v2/session/start") {
    const std::string transport = JsonField(req.body, "transport", "lan");
    if (transport == "usb-adb") {
      RunCommandHidden("adb reverse tcp:39393 tcp:39393");
      RunCommandHidden("adb forward tcp:39394 tcp:39394");
    } else if (transport == "usb-native") {
      const auto devices = EnumerateUsbNativeDevices();
      const auto it = std::find_if(devices.begin(), devices.end(), [](const UsbNativeDevice& d) { return d.androidCandidate; });
      {
        std::lock_guard<std::mutex> lock(usbNativeMutex_);
        usbNativeLastScanMs_ = NowMs();
        if (it != devices.end()) {
          usbNativeConnected_ = true;
          usbNativeDevicePath_ = it->path;
          usbNativeDescription_ = it->description;
          usbNativeLastError_.clear();
        } else {
          usbNativeConnected_ = false;
          usbNativeDevicePath_.clear();
          usbNativeDescription_.clear();
          usbNativeLastError_ = devices.empty() ? "no_usb_devices" : "no_android_candidate";
        }
      }
      if (it == devices.end()) {
        std::ostringstream err;
        err << "{\"error\":\"usb_native_device_not_found\",\"hint\":\"check USB cable/mode or call /api/v2/usb-native/devices\","
            << "\"deviceCount\":" << devices.size() << "}";
        return Json(503, err.str());
      }
      {
        std::lock_guard<std::mutex> lock(usbLinkMutex_);
        usbLinkActive_ = false;
        usbLinkId_.clear();
        usbLinkSessionId_.clear();
        usbLinkDevicePath_ = usbNativeDevicePath_;
        usbLinkLastPacketMs_ = 0;
        usbLinkRxPackets_ = 0;
        usbLinkRxBytes_ = 0;
        usbLinkExpectedSeq_ = 0;
        usbLinkMtu_ = 16384;
        usbLinkLastError_.clear();
      }
    } else if (transport == "usb-aoa") {
      // USB AOA uses bulk transfer, no RNDIS/ADB needed
      // The AOA connection should already be established via /api/v2/usb-aoa/connect
      std::lock_guard<std::mutex> lock(usbAoaMutex_);
      if (!usbAoaConnected_ || !usbAoa_ || !usbAoa_->IsConnected()) {
        return Json(503, R"({"error":"usb_aoa_not_connected","hint":"call POST /api/v2/usb-aoa/connect first"})");
      }
    }
    activeTransport_ = transport;
    activeSessionId_ = "sess_v2_" + std::to_string(NowMs());
    activeAuthToken_ = "tok_" + std::to_string(NowMs() + 17);
    const std::string wsHostPort = ResolveWsHostPort(req, port_);

    std::ostringstream body;
    body << "{\"sessionId\":\"" << JsonEscape(activeSessionId_)
         << "\",\"wsUrl\":\"ws://" << JsonEscape(wsHostPort) << "/ws/v2/media?sessionId=" << JsonEscape(activeSessionId_)
         << "\",\"authToken\":\"" << JsonEscape(activeAuthToken_)
         << "\",\"recommendedAspect\":\"16:9\"";
    if (transport == "usb-native") {
      std::lock_guard<std::mutex> lock(usbNativeMutex_);
      body << ",\"usbNative\":{\"connected\":" << (usbNativeConnected_ ? "true" : "false")
           << ",\"devicePath\":\"" << JsonEscape(usbNativeDevicePath_)
           << "\",\"description\":\"" << JsonEscape(usbNativeDescription_)
           << "\"}";
    }
    if (transport == "usb-native") {
      std::lock_guard<std::mutex> lock(usbLinkMutex_);
      body << ",\"usbLink\":{\"active\":" << (usbLinkActive_ ? "true" : "false")
           << ",\"hint\":\"call /api/v2/usb-native/handshake\"}";
    }
    if (transport == "usb-aoa") {
      std::lock_guard<std::mutex> lock(usbAoaMutex_);
      body << ",\"usbAoa\":{\"connected\":" << (usbAoaConnected_ ? "true" : "false")
           << ",\"hint\":\"media frames are received via USB bulk transfer\"}";
    }
    body << "}";
    return Json(200, body.str());
  }

  if (req.method == "POST" && req.path == "/api/v1/session/answer") {
    return Json(200, R"({"ok":true})");
  }

  if (req.method == "POST" && req.path == "/api/v1/session/stop") {
    std::lock_guard<std::mutex> lock(frameMutex_);
    frameState_.jpeg.clear();
    frameState_.width = 0;
    frameState_.height = 0;
    frameState_.lastV1FrameTickMs = 0;
    return Json(200, R"({"ok":true})");
  }

  if (req.method == "POST" && req.path == "/api/v2/session/stop") {
    {
      std::lock_guard<std::mutex> lock(frameMutex_);
      frameState_.jpeg.clear();
      frameState_.width = 0;
      frameState_.height = 0;
      frameState_.v2Bgra.clear();
      frameState_.v2Width = 0;
      frameState_.v2Height = 0;
      frameState_.lastV2DecodedTickMs = 0;
    }
    {
      std::lock_guard<std::mutex> lock(usbLinkMutex_);
      usbLinkActive_ = false;
      usbLinkId_.clear();
      usbLinkSessionId_.clear();
      usbLinkDevicePath_.clear();
      usbLinkLastPacketMs_ = 0;
      usbLinkRxPackets_ = 0;
      usbLinkRxBytes_ = 0;
      usbLinkExpectedSeq_ = 0;
      usbLinkLastError_.clear();
    }
    {
      std::lock_guard<std::mutex> lock(usbAoaMutex_);
      if (activeTransport_ == "usb-aoa" && usbAoa_) {
        usbAoa_->Stop();
        usbAoa_.reset();
        usbAoaConnected_ = false;
      }
    }
    return Json(200, R"({"ok":true})");
  }

  if (req.method == "POST" && req.path == "/api/v1/frame") {
    if (req.body.empty()) {
      return Json(400, R"({"error":"empty_body"})");
    }

    uint32_t width = 0;
    uint32_t height = 0;
    auto wIt = req.headers.find("x-width");
    auto hIt = req.headers.find("x-height");
    if (wIt != req.headers.end()) {
      width = static_cast<uint32_t>(std::strtoul(wIt->second.c_str(), nullptr, 10));
    }
    if (hIt != req.headers.end()) {
      height = static_cast<uint32_t>(std::strtoul(hIt->second.c_str(), nullptr, 10));
    }

    {
      std::lock_guard<std::mutex> lock(frameMutex_);
      const uint64_t nowMs = NowMs();
      frameState_.jpeg.assign(req.body.begin(), req.body.end());
      frameState_.width = width;
      frameState_.height = height;
      frameState_.frameCount += 1;
      frameState_.lastV1FrameTickMs = nowMs;
      frameState_.lastFrameTickMs = nowMs;
    }

    return Json(200, R"({"ok":true})");
  }

  if (req.method == "POST" && req.path == "/api/v1/audio") {
    if (req.body.empty()) {
      return Json(400, R"({"error":"empty_body"})");
    }
    uint32_t sampleRate = 0;
    uint32_t channels = 0;
    auto srIt = req.headers.find("x-sample-rate");
    auto chIt = req.headers.find("x-channels");
    if (srIt != req.headers.end()) {
      sampleRate = static_cast<uint32_t>(std::strtoul(srIt->second.c_str(), nullptr, 10));
    }
    if (chIt != req.headers.end()) {
      channels = static_cast<uint32_t>(std::strtoul(chIt->second.c_str(), nullptr, 10));
    }

    {
      std::lock_guard<std::mutex> lock(frameMutex_);
      frameState_.audioBytes += static_cast<uint64_t>(req.body.size());
      frameState_.audioPackets += 1;
      frameState_.audioSampleRate = sampleRate;
      frameState_.audioChannels = channels;
    }
    return Json(200, R"({"ok":true})");
  }

  if (req.method == "GET" && req.path == "/api/v2/frame.bgra") {
    std::vector<uint8_t> bgra;
    uint32_t width = 0;
    uint32_t height = 0;
    uint64_t lastDecodedMs = 0;
    {
      std::lock_guard<std::mutex> lock(frameMutex_);
      bgra = frameState_.v2Bgra;
      width = frameState_.v2Width;
      height = frameState_.v2Height;
      lastDecodedMs = frameState_.lastV2DecodedTickMs;
    }

    if (bgra.empty() || width == 0 || height == 0) {
      return Json(404, R"({"error":"no_frame"})");
    }
    const uint64_t nowMs = NowMs();
    if (lastDecodedMs == 0 || (nowMs - lastDecodedMs) > 1000) {
      return Json(404, R"({"error":"stale_frame"})");
    }

    std::vector<uint8_t> body;
    body.resize(8 + bgra.size());
    body[0] = static_cast<uint8_t>(width & 0xFF);
    body[1] = static_cast<uint8_t>((width >> 8) & 0xFF);
    body[2] = static_cast<uint8_t>((width >> 16) & 0xFF);
    body[3] = static_cast<uint8_t>((width >> 24) & 0xFF);
    body[4] = static_cast<uint8_t>(height & 0xFF);
    body[5] = static_cast<uint8_t>((height >> 8) & 0xFF);
    body[6] = static_cast<uint8_t>((height >> 16) & 0xFF);
    body[7] = static_cast<uint8_t>((height >> 24) & 0xFF);
    memcpy(body.data() + 8, bgra.data(), bgra.size());
    return HttpResponseBinary(200, "application/octet-stream", body, {{"X-Format", "BGRA"}});
  }

  if (req.method == "GET" && req.path == "/api/v1/frame.jpg") {
    std::vector<uint8_t> jpeg;
    uint32_t width = 0;
    uint32_t height = 0;
    {
      std::lock_guard<std::mutex> lock(frameMutex_);
      jpeg = frameState_.jpeg;
      width = frameState_.width;
      height = frameState_.height;
    }

    if (jpeg.empty()) {
      return Json(404, R"({"error":"no_frame"})");
    }

    return HttpResponseBinary(200,
                              "image/jpeg",
                              jpeg,
                              {{"X-Width", std::to_string(width)}, {"X-Height", std::to_string(height)}});
  }

  if (req.method == "GET" && req.path.rfind("/api/v1/stats", 0) == 0) {
    uint64_t frameCount = 0;
    uint64_t lastMs = 0;
    uint64_t audioBytes = 0;
    uint64_t audioPackets = 0;
    uint32_t audioSampleRate = 0;
    uint32_t audioChannels = 0;
    {
      std::lock_guard<std::mutex> lock(frameMutex_);
      frameCount = frameState_.frameCount;
      lastMs = frameState_.lastFrameTickMs;
      audioBytes = frameState_.audioBytes;
      audioPackets = frameState_.audioPackets;
      audioSampleRate = frameState_.audioSampleRate;
      audioChannels = frameState_.audioChannels;
    }

    const uint64_t now = NowMs();
    const bool alive = (lastMs != 0 && (now - lastMs) < 2000);
    const int fps = alive ? 30 : 0;

    std::ostringstream body;
    body << "{\"rtt\":15,\"fps\":" << fps
         << ",\"bitrate\":4000000,\"droppedFrames\":0,\"reconnectCount\":0,\"frameCount\":" << frameCount
         << ",\"audioBytes\":" << audioBytes
         << ",\"audioPackets\":" << audioPackets
         << ",\"audioSampleRate\":" << audioSampleRate
         << ",\"audioChannels\":" << audioChannels
         << "}";
    return Json(200, body.str());
  }

  if (req.method == "GET" && req.path.rfind("/api/v2/session/", 0) == 0 && req.path.find("/stats") != std::string::npos) {
    uint64_t frameCount = 0;
    uint64_t lastMs = 0;
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
    {
      std::lock_guard<std::mutex> lock(frameMutex_);
      frameCount = frameState_.frameCount;
      lastMs = frameState_.lastFrameTickMs;
      audioBytes = frameState_.audioBytes;
      audioPackets = frameState_.audioPackets;
      audioSampleRate = frameState_.audioSampleRate;
      audioChannels = frameState_.audioChannels;
      v2VideoBytes = frameState_.v2VideoBytes;
      v2AudioBytes = frameState_.v2AudioBytes;
      v2VideoFrames = frameState_.v2VideoFrames;
      v2AudioFrames = frameState_.v2AudioFrames;
      v2Keyframes = frameState_.v2Keyframes;
      v2DecodedFrames = frameState_.v2DecodedFrames;
    }

    const uint64_t now = NowMs();
    const bool alive = (lastMs != 0 && (now - lastMs) < 2000);
    const int fps = alive ? 30 : 0;
    bool usbNativeConnected = false;
    std::string usbNativeError;
    bool usbLinkActive = false;
    uint64_t usbLinkRxPackets = 0;
    uint64_t usbLinkRxBytes = 0;
    std::string usbLinkError;
    {
      std::lock_guard<std::mutex> lock(usbNativeMutex_);
      usbNativeConnected = usbNativeConnected_;
      usbNativeError = usbNativeLastError_;
    }
    {
      std::lock_guard<std::mutex> lock(usbLinkMutex_);
      usbLinkActive = usbLinkActive_;
      usbLinkRxPackets = usbLinkRxPackets_;
      usbLinkRxBytes = usbLinkRxBytes_;
      usbLinkError = usbLinkLastError_;
    }

    std::ostringstream body;
    body << "{\"sessionId\":\"" << JsonEscape(activeSessionId_)
         << "\",\"transport\":\"" << JsonEscape(activeTransport_)
         << "\",\"rtt\":12,\"fps\":" << fps
         << ",\"bitrate\":4000000,\"droppedFrames\":0,\"reconnectCount\":0,\"frameCount\":" << frameCount
         << ",\"audioBytes\":" << audioBytes
         << ",\"audioPackets\":" << audioPackets
         << ",\"audioSampleRate\":" << audioSampleRate
         << ",\"audioChannels\":" << audioChannels
         << ",\"v2VideoBytes\":" << v2VideoBytes
         << ",\"v2AudioBytes\":" << v2AudioBytes
         << ",\"v2VideoFrames\":" << v2VideoFrames
         << ",\"v2AudioFrames\":" << v2AudioFrames
         << ",\"v2Keyframes\":" << v2Keyframes
         << ",\"v2DecodedFrames\":" << v2DecodedFrames
         << ",\"usbNativeConnected\":" << (usbNativeConnected ? "true" : "false")
         << ",\"usbNativeLastError\":\"" << JsonEscape(usbNativeError) << "\""
         << ",\"usbLinkActive\":" << (usbLinkActive ? "true" : "false")
         << ",\"usbLinkRxPackets\":" << usbLinkRxPackets
         << ",\"usbLinkRxBytes\":" << usbLinkRxBytes
         << ",\"usbLinkLastError\":\"" << JsonEscape(usbLinkError) << "\""
         << "}";
    return Json(200, body.str());
  }

  return Json(404, R"({"error":"not_found"})");
}

void ReceiverServer::ProcessV2MediaPacket(const uint8_t* packet, size_t packetSize) {
  if (packet == nullptr || packetSize < 24) {
    return;
  }

  const uint8_t version = packet[0];
  const uint8_t streamType = packet[1];
  const uint8_t codec = packet[2];
  const uint8_t flags = packet[3];
  (void)version;
  (void)codec;

  const uint32_t payloadSize = ReadLE32(packet + 20);
  const size_t actualPayload = packetSize - 24;
  if (payloadSize > actualPayload) {
    return;
  }

  if (streamType == 1) {
    const uint8_t* bitstream = packet + 24;
    size_t bitstreamSize = payloadSize;
    if (LooksLikeAvcDecoderConfigurationRecord(bitstream, bitstreamSize)) {
      return;
    }
    std::vector<uint8_t> annexB;
    if (!HasAnnexBStartCode(bitstream, bitstreamSize)) {
      if (ConvertAvccToAnnexB(bitstream, bitstreamSize, &annexB)) {
        bitstream = annexB.data();
        bitstreamSize = annexB.size();
      }
    }

    std::vector<uint8_t> decodedBgra;
    uint32_t decodedW = 0;
    uint32_t decodedH = 0;
    {
      std::lock_guard<std::mutex> decLock(gDecoderMutex);
      gH264Decoder.DecodeAnnexB(bitstream, bitstreamSize, &decodedBgra, &decodedW, &decodedH);
    }

    std::lock_guard<std::mutex> lock(frameMutex_);
    frameState_.v2VideoFrames += 1;
    frameState_.v2VideoBytes += payloadSize;
    if ((flags & 0x01) != 0) {
      frameState_.v2Keyframes += 1;
    }
    if (!decodedBgra.empty() && decodedW > 0 && decodedH > 0) {
      const uint64_t nowMs = NowMs();
      frameState_.v2Bgra = std::move(decodedBgra);
      frameState_.v2Width = decodedW;
      frameState_.v2Height = decodedH;
      frameState_.v2DecodedFrames += 1;
      frameState_.lastV2DecodedTickMs = nowMs;
      frameState_.lastFrameTickMs = nowMs;
    }
  } else if (streamType == 2) {
    std::lock_guard<std::mutex> lock(frameMutex_);
    frameState_.v2AudioFrames += 1;
    frameState_.v2AudioBytes += payloadSize;
  }
}

void ReceiverServer::ProcessUsbV2MediaPacket(const uint8_t* packet, size_t packetSize) {
  if (packet == nullptr || packetSize < 24) {
    return;
  }

  const uint8_t version = packet[0];
  const uint8_t streamType = packet[1];
  const uint8_t codec = packet[2];
  const uint8_t flags = packet[3];
  (void)version;
  (void)codec;

  const uint32_t payloadSize = ReadLE32(packet + 20);
  const size_t actualPayload = packetSize - 24;
  if (payloadSize > actualPayload) {
    return;
  }

  if (streamType == 1) {
    const uint8_t* bitstream = packet + 24;
    size_t bitstreamSize = payloadSize;
    if (LooksLikeAvcDecoderConfigurationRecord(bitstream, bitstreamSize)) {
      return;
    }
    std::vector<uint8_t> annexB;
    if (!HasAnnexBStartCode(bitstream, bitstreamSize)) {
      if (ConvertAvccToAnnexB(bitstream, bitstreamSize, &annexB)) {
        bitstream = annexB.data();
        bitstreamSize = annexB.size();
      }
    }

    std::vector<uint8_t> decodedBgra;
    uint32_t decodedW = 0;
    uint32_t decodedH = 0;
    {
      std::lock_guard<std::mutex> decLock(gDecoderMutex);
      gH264Decoder.DecodeAnnexB(bitstream, bitstreamSize, &decodedBgra, &decodedW, &decodedH);
    }

    std::lock_guard<std::mutex> lock(frameMutex_);
    frameState_.v2VideoFrames += 1;
    frameState_.v2VideoBytes += payloadSize;
    if ((flags & 0x01) != 0) {
      frameState_.v2Keyframes += 1;
    }
    if (!decodedBgra.empty() && decodedW > 0 && decodedH > 0) {
      const uint64_t nowMs = NowMs();
      frameState_.v2Bgra = std::move(decodedBgra);
      frameState_.v2Width = decodedW;
      frameState_.v2Height = decodedH;
      frameState_.v2DecodedFrames += 1;
      frameState_.lastV2DecodedTickMs = nowMs;
      frameState_.lastFrameTickMs = nowMs;
    }
  } else if (streamType == 2) {
    std::lock_guard<std::mutex> lock(frameMutex_);
    frameState_.v2AudioFrames += 1;
    frameState_.v2AudioBytes += payloadSize;
  }
}

void ReceiverServer::AcceptLoop() {
  SOCKET s = static_cast<SOCKET>(listenSocket_);
  while (running_) {
    SOCKET client = accept(s, nullptr, nullptr);
    if (client == INVALID_SOCKET) {
      if (running_) {
        std::cerr << "accept failed\n";
      }
      continue;
    }

    std::string request;
    request.reserve(1024 * 1024);

    char buffer[8192];
    int received = 0;
    size_t expectedTotal = std::string::npos;

    while ((received = recv(client, buffer, sizeof(buffer), 0)) > 0) {
      request.append(buffer, buffer + received);

      if (expectedTotal == std::string::npos) {
        const auto headerEnd = request.find("\r\n\r\n");
        if (headerEnd != std::string::npos) {
          size_t contentLength = 0;
          const auto clPos = ToLower(request.substr(0, headerEnd)).find("content-length:");
          if (clPos != std::string::npos) {
            const auto lineEnd = request.find("\r\n", clPos);
            const auto line = request.substr(clPos + 15, lineEnd - (clPos + 15));
            contentLength = static_cast<size_t>(std::strtoull(Trim(line).c_str(), nullptr, 10));
          }
          expectedTotal = headerEnd + 4 + contentLength;
        }
      }

      if (expectedTotal != std::string::npos && request.size() >= expectedTotal) {
        break;
      }

      if (request.size() > 8 * 1024 * 1024) {
        break;
      }
    }

    // Dispatch each connection to its own thread so the accept loop is never blocked.
    // This is critical: HandleWebSocketV2 runs an infinite recv loop, so if called
    // inline it would prevent all subsequent HTTP requests (including OBS frame polls).
    std::thread([this, client, request]() {
      CoInitializeEx(nullptr, COINIT_MULTITHREADED);

      ParsedRequest parsed;
      if (ParseRequest(request, &parsed)) {
        const auto upIt = parsed.headers.find("upgrade");
        if (parsed.path.rfind("/ws/v2/media", 0) == 0 && upIt != parsed.headers.end() &&
            ToLower(upIt->second).find("websocket") != std::string::npos) {
          HandleWebSocketV2(static_cast<uintptr_t>(client), request);
          closesocket(client);
          CoUninitialize();
          return;
        }
      }

      const std::string response = HandleRequest(request);
      send(client, response.data(), static_cast<int>(response.size()), 0);
      closesocket(client);
      CoUninitialize();
    }).detach();
  }
}

}  // namespace acb::receiver
