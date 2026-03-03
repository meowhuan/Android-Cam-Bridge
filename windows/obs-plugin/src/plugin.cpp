#include <obs-module.h>
#include <util/platform.h>

#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <wincodec.h>
#include <winhttp.h>

#include <atomic>
#include <cstdlib>
#include <cstdint>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#pragma comment(lib, "windowscodecs.lib")
#pragma comment(lib, "winhttp.lib")

OBS_DECLARE_MODULE()
OBS_MODULE_USE_DEFAULT_LOCALE("acb-obs-plugin", "en-US")

namespace {

constexpr const char* kSourceId = "android_cam_source";

struct AndroidCamSourceContext {
  obs_source_t* source = nullptr;
  std::string connectionMode = "managed";
  std::string sessionId;
  std::string transportMode = "usb-adb";
  std::string hostAddress = "127.0.0.1:39393";
  std::string fitMode = "letterbox";
  std::string qualityPreset = "balanced";
  bool manualSession = false;
  bool syncAudio = true;
  std::string latencyProfile = "balanced";
  std::string outputResolution = "source";
  uint32_t targetWidth = 0;
  uint32_t targetHeight = 0;

  std::atomic<bool> running{false};
  std::mutex stateMutex;
  std::thread worker;
  uint32_t width = 1280;
  uint32_t height = 720;
  bool adbSetupDone = false;
};

std::wstring ToWide(const std::string& s) {
  if (s.empty()) {
    return L"";
  }
  const int required = MultiByteToWideChar(CP_UTF8, 0, s.c_str(), -1, nullptr, 0);
  std::wstring out(static_cast<size_t>(required), L'\0');
  MultiByteToWideChar(CP_UTF8, 0, s.c_str(), -1, out.data(), required);
  if (!out.empty() && out.back() == L'\0') {
    out.pop_back();
  }
  return out;
}

void ParseHostPort(const std::string& input, std::string* host, int* port) {
  std::string value = input;
  const auto scheme = value.find("://");
  if (scheme != std::string::npos) {
    value = value.substr(scheme + 3);
  }

  const auto slash = value.find('/');
  if (slash != std::string::npos) {
    value = value.substr(0, slash);
  }

  const auto colon = value.rfind(':');
  if (colon != std::string::npos) {
    *host = value.substr(0, colon);
    *port = std::atoi(value.substr(colon + 1).c_str());
  } else {
    *host = value;
    *port = 39393;
  }

  if (*host == "") {
    *host = "127.0.0.1";
  }
  if (*port <= 0) {
    *port = 39393;
  }
}

void ParseResolutionPreset(const std::string& preset, uint32_t* width, uint32_t* height) {
  if (preset == "640x480") {
    *width = 640;
    *height = 480;
    return;
  }
  if (preset == "1280x720") {
    *width = 1280;
    *height = 720;
    return;
  }
  if (preset == "1920x1080") {
    *width = 1920;
    *height = 1080;
    return;
  }
  *width = 0;
  *height = 0;
}

void ResizeNearest(const std::vector<uint8_t>& src,
                   uint32_t srcW,
                   uint32_t srcH,
                   uint32_t dstW,
                   uint32_t dstH,
                   std::vector<uint8_t>* dst) {
  dst->resize(static_cast<size_t>(dstW) * static_cast<size_t>(dstH) * 4);
  for (uint32_t y = 0; y < dstH; ++y) {
    const uint32_t sy = static_cast<uint32_t>((static_cast<uint64_t>(y) * srcH) / dstH);
    for (uint32_t x = 0; x < dstW; ++x) {
      const uint32_t sx = static_cast<uint32_t>((static_cast<uint64_t>(x) * srcW) / dstW);
      const size_t srcIdx = (static_cast<size_t>(sy) * srcW + sx) * 4;
      const size_t dstIdx = (static_cast<size_t>(y) * dstW + x) * 4;
      (*dst)[dstIdx + 0] = src[srcIdx + 0];
      (*dst)[dstIdx + 1] = src[srcIdx + 1];
      (*dst)[dstIdx + 2] = src[srcIdx + 2];
      (*dst)[dstIdx + 3] = src[srcIdx + 3];
    }
  }
}

void ComposeWithFitMode(const std::vector<uint8_t>& src,
                        uint32_t srcW,
                        uint32_t srcH,
                        uint32_t dstW,
                        uint32_t dstH,
                        const std::string& fitMode,
                        std::vector<uint8_t>* out) {
  if (fitMode == "stretch") {
    ResizeNearest(src, srcW, srcH, dstW, dstH, out);
    return;
  }

  out->assign(static_cast<size_t>(dstW) * static_cast<size_t>(dstH) * 4, 0);

  const double srcAspect = static_cast<double>(srcW) / static_cast<double>(srcH);
  const double dstAspect = static_cast<double>(dstW) / static_cast<double>(dstH);

  uint32_t scaledW = 0;
  uint32_t scaledH = 0;
  if (fitMode == "crop") {
    if (srcAspect > dstAspect) {
      scaledH = dstH;
      scaledW = static_cast<uint32_t>(dstH * srcAspect);
    } else {
      scaledW = dstW;
      scaledH = static_cast<uint32_t>(dstW / srcAspect);
    }
  } else {  // letterbox default
    if (srcAspect > dstAspect) {
      scaledW = dstW;
      scaledH = static_cast<uint32_t>(dstW / srcAspect);
    } else {
      scaledH = dstH;
      scaledW = static_cast<uint32_t>(dstH * srcAspect);
    }
  }

  scaledW = (scaledW == 0) ? 1 : scaledW;
  scaledH = (scaledH == 0) ? 1 : scaledH;

  std::vector<uint8_t> scaled;
  ResizeNearest(src, srcW, srcH, scaledW, scaledH, &scaled);

  if (fitMode == "crop") {
    const uint32_t offX = (scaledW > dstW) ? (scaledW - dstW) / 2 : 0;
    const uint32_t offY = (scaledH > dstH) ? (scaledH - dstH) / 2 : 0;
    for (uint32_t y = 0; y < dstH; ++y) {
      const uint32_t sy = y + offY;
      for (uint32_t x = 0; x < dstW; ++x) {
        const uint32_t sx = x + offX;
        const size_t srcIdx = (static_cast<size_t>(sy) * scaledW + sx) * 4;
        const size_t dstIdx = (static_cast<size_t>(y) * dstW + x) * 4;
        (*out)[dstIdx + 0] = scaled[srcIdx + 0];
        (*out)[dstIdx + 1] = scaled[srcIdx + 1];
        (*out)[dstIdx + 2] = scaled[srcIdx + 2];
        (*out)[dstIdx + 3] = scaled[srcIdx + 3];
      }
    }
    return;
  }

  const uint32_t offX = (dstW - scaledW) / 2;
  const uint32_t offY = (dstH - scaledH) / 2;
  for (uint32_t y = 0; y < scaledH; ++y) {
    for (uint32_t x = 0; x < scaledW; ++x) {
      const size_t srcIdx = (static_cast<size_t>(y) * scaledW + x) * 4;
      const size_t dstIdx = (static_cast<size_t>(y + offY) * dstW + (x + offX)) * 4;
      (*out)[dstIdx + 0] = scaled[srcIdx + 0];
      (*out)[dstIdx + 1] = scaled[srcIdx + 1];
      (*out)[dstIdx + 2] = scaled[srcIdx + 2];
      (*out)[dstIdx + 3] = scaled[srcIdx + 3];
    }
  }
}

bool HttpRequestBinary(const std::string& host,
                       int port,
                       const std::wstring& method,
                       const std::wstring& path,
                       const std::vector<uint8_t>& requestBody,
                       const wchar_t* contentType,
                       std::vector<uint8_t>* out,
                       int* statusCode) {
  HINTERNET session = WinHttpOpen(L"ACB-OBS/1.0", WINHTTP_ACCESS_TYPE_DEFAULT_PROXY,
                                  WINHTTP_NO_PROXY_NAME, WINHTTP_NO_PROXY_BYPASS, 0);
  if (!session) {
    return false;
  }

  const std::wstring whost = ToWide(host);
  HINTERNET connect = WinHttpConnect(session, whost.c_str(), static_cast<INTERNET_PORT>(port), 0);
  if (!connect) {
    WinHttpCloseHandle(session);
    return false;
  }

  HINTERNET request = WinHttpOpenRequest(connect,
                                         method.c_str(),
                                         path.c_str(),
                                         nullptr,
                                         WINHTTP_NO_REFERER,
                                         WINHTTP_DEFAULT_ACCEPT_TYPES,
                                         0);

  if (!request) {
    WinHttpCloseHandle(connect);
    WinHttpCloseHandle(session);
    return false;
  }

  std::wstring headers;
  if (contentType) {
    headers = L"Content-Type: ";
    headers += contentType;
    headers += L"\r\n";
  }

  BOOL ok = WinHttpSendRequest(request,
                               headers.empty() ? WINHTTP_NO_ADDITIONAL_HEADERS : headers.c_str(),
                               headers.empty() ? 0 : static_cast<DWORD>(-1),
                               requestBody.empty() ? WINHTTP_NO_REQUEST_DATA : const_cast<uint8_t*>(requestBody.data()),
                               static_cast<DWORD>(requestBody.size()),
                               static_cast<DWORD>(requestBody.size()),
                               0);

  if (!ok || !WinHttpReceiveResponse(request, nullptr)) {
    WinHttpCloseHandle(request);
    WinHttpCloseHandle(connect);
    WinHttpCloseHandle(session);
    return false;
  }

  DWORD code = 0;
  DWORD codeSize = sizeof(code);
  WinHttpQueryHeaders(request,
                      WINHTTP_QUERY_STATUS_CODE | WINHTTP_QUERY_FLAG_NUMBER,
                      WINHTTP_HEADER_NAME_BY_INDEX,
                      &code,
                      &codeSize,
                      WINHTTP_NO_HEADER_INDEX);
  *statusCode = static_cast<int>(code);

  out->clear();
  while (true) {
    DWORD available = 0;
    if (!WinHttpQueryDataAvailable(request, &available) || available == 0) {
      break;
    }
    const size_t offset = out->size();
    out->resize(offset + available);
    DWORD read = 0;
    if (!WinHttpReadData(request, out->data() + offset, available, &read)) {
      break;
    }
    out->resize(offset + read);
  }

  WinHttpCloseHandle(request);
  WinHttpCloseHandle(connect);
  WinHttpCloseHandle(session);
  return true;
}

bool HttpPostJson(const std::string& host, int port, const std::wstring& path, const std::string& json) {
  std::vector<uint8_t> body(json.begin(), json.end());
  std::vector<uint8_t> response;
  int code = 0;
  return HttpRequestBinary(host, port, L"POST", path, body, L"application/json", &response, &code) && code >= 200 && code < 300;
}

bool HttpGetJpeg(const std::string& host, int port, std::vector<uint8_t>* jpeg) {
  std::vector<uint8_t> response;
  int code = 0;
  const bool ok = HttpRequestBinary(host, port, L"GET", L"/api/v1/frame.jpg", {}, nullptr, &response, &code);
  if (!ok || code != 200) {
    return false;
  }
  *jpeg = std::move(response);
  return true;
}

bool HttpGetBgraV2(const std::string& host,
                   int port,
                   std::vector<uint8_t>* bgra,
                   uint32_t* width,
                   uint32_t* height) {
  std::vector<uint8_t> response;
  int code = 0;
  const bool ok = HttpRequestBinary(host, port, L"GET", L"/api/v2/frame.bgra", {}, nullptr, &response, &code);
  if (!ok || code != 200 || response.size() < 8) {
    return false;
  }

  const uint32_t w = static_cast<uint32_t>(response[0]) |
                     (static_cast<uint32_t>(response[1]) << 8) |
                     (static_cast<uint32_t>(response[2]) << 16) |
                     (static_cast<uint32_t>(response[3]) << 24);
  const uint32_t h = static_cast<uint32_t>(response[4]) |
                     (static_cast<uint32_t>(response[5]) << 8) |
                     (static_cast<uint32_t>(response[6]) << 16) |
                     (static_cast<uint32_t>(response[7]) << 24);
  if (w == 0 || h == 0) {
    return false;
  }
  const size_t expected = static_cast<size_t>(w) * static_cast<size_t>(h) * 4;
  if (response.size() < 8 + expected) {
    return false;
  }

  bgra->assign(response.begin() + 8, response.begin() + 8 + expected);
  *width = w;
  *height = h;
  return true;
}

bool DecodeJpegToBgra(const std::vector<uint8_t>& jpeg, std::vector<uint8_t>* bgra, uint32_t* width, uint32_t* height) {
  IWICImagingFactory* factory = nullptr;
  HRESULT hr = CoCreateInstance(CLSID_WICImagingFactory, nullptr, CLSCTX_INPROC_SERVER, IID_PPV_ARGS(&factory));
  if (FAILED(hr) || !factory) {
    return false;
  }

  IWICStream* stream = nullptr;
  hr = factory->CreateStream(&stream);
  if (FAILED(hr) || !stream) {
    factory->Release();
    return false;
  }

  hr = stream->InitializeFromMemory(const_cast<BYTE*>(jpeg.data()), static_cast<DWORD>(jpeg.size()));
  if (FAILED(hr)) {
    stream->Release();
    factory->Release();
    return false;
  }

  IWICBitmapDecoder* decoder = nullptr;
  hr = factory->CreateDecoderFromStream(stream, nullptr, WICDecodeMetadataCacheOnLoad, &decoder);
  if (FAILED(hr) || !decoder) {
    stream->Release();
    factory->Release();
    return false;
  }

  IWICBitmapFrameDecode* frame = nullptr;
  hr = decoder->GetFrame(0, &frame);
  if (FAILED(hr) || !frame) {
    decoder->Release();
    stream->Release();
    factory->Release();
    return false;
  }

  UINT w = 0;
  UINT h = 0;
  frame->GetSize(&w, &h);

  IWICFormatConverter* converter = nullptr;
  hr = factory->CreateFormatConverter(&converter);
  if (FAILED(hr) || !converter) {
    frame->Release();
    decoder->Release();
    stream->Release();
    factory->Release();
    return false;
  }

  hr = converter->Initialize(frame,
                             GUID_WICPixelFormat32bppBGRA,
                             WICBitmapDitherTypeNone,
                             nullptr,
                             0.f,
                             WICBitmapPaletteTypeCustom);
  if (FAILED(hr)) {
    converter->Release();
    frame->Release();
    decoder->Release();
    stream->Release();
    factory->Release();
    return false;
  }

  const UINT stride = w * 4;
  bgra->resize(static_cast<size_t>(stride) * static_cast<size_t>(h));
  hr = converter->CopyPixels(nullptr, stride, static_cast<UINT>(bgra->size()), bgra->data());

  converter->Release();
  frame->Release();
  decoder->Release();
  stream->Release();
  factory->Release();

  if (FAILED(hr)) {
    return false;
  }

  *width = static_cast<uint32_t>(w);
  *height = static_cast<uint32_t>(h);
  return true;
}

void StreamWorker(AndroidCamSourceContext* ctx) {
  CoInitializeEx(nullptr, COINIT_MULTITHREADED);

  while (ctx->running.load()) {
    std::string hostAddress;
    std::string transport;
    std::string fitMode;
    std::string latencyProfile;
    uint32_t targetWidth = 0;
    uint32_t targetHeight = 0;
    {
      std::lock_guard<std::mutex> lock(ctx->stateMutex);
      hostAddress = ctx->hostAddress;
      transport = ctx->transportMode;
      fitMode = ctx->fitMode;
      latencyProfile = ctx->latencyProfile;
      targetWidth = ctx->targetWidth;
      targetHeight = ctx->targetHeight;
    }

    std::string host;
    int port = 39393;
    ParseHostPort(hostAddress, &host, &port);

    std::vector<uint8_t> bgra;
    uint32_t width = 0;
    uint32_t height = 0;
    if (!HttpGetBgraV2(host, port, &bgra, &width, &height)) {
      std::vector<uint8_t> jpeg;
      if (!HttpGetJpeg(host, port, &jpeg)) {
        os_sleep_ms(10);
        continue;
      }
      if (!DecodeJpegToBgra(jpeg, &bgra, &width, &height)) {
        os_sleep_ms(20);
        continue;
      }
    }

    std::vector<uint8_t> scaled;
    const uint8_t* outputData = bgra.data();
    uint32_t outputWidth = width;
    uint32_t outputHeight = height;

    if (targetWidth > 0 && targetHeight > 0 && (targetWidth != width || targetHeight != height)) {
      ComposeWithFitMode(bgra, width, height, targetWidth, targetHeight, fitMode, &scaled);
      outputData = scaled.data();
      outputWidth = targetWidth;
      outputHeight = targetHeight;
    }

    {
      std::lock_guard<std::mutex> lock(ctx->stateMutex);
      ctx->width = outputWidth;
      ctx->height = outputHeight;
    }

    obs_source_frame frame{};
    frame.data[0] = const_cast<uint8_t*>(outputData);
    frame.linesize[0] = static_cast<uint32_t>(outputWidth * 4);
    frame.width = outputWidth;
    frame.height = outputHeight;
    frame.format = VIDEO_FORMAT_BGRA;
    frame.timestamp = os_gettime_ns();
    obs_source_output_video(ctx->source, &frame);

    if (latencyProfile == "low") {
      os_sleep_ms(0);
    } else {
      os_sleep_ms(1);
    }
  }

  CoUninitialize();
}

const char* AndroidCamGetName(void*) {
  return obs_module_text("AndroidCamSource.Name");
}

void* AndroidCamCreate(obs_data_t* settings, obs_source_t* source) {
  auto* ctx = new AndroidCamSourceContext();
  ctx->source = source;
  ctx->connectionMode = obs_data_get_string(settings, "connection_mode");
  ctx->sessionId = obs_data_get_string(settings, "session_id");
  ctx->transportMode = obs_data_get_string(settings, "transport_mode");
  ctx->hostAddress = obs_data_get_string(settings, "host_address");
  ctx->fitMode = obs_data_get_string(settings, "fit_mode");
  ctx->qualityPreset = obs_data_get_string(settings, "quality_preset");
  ctx->manualSession = obs_data_get_bool(settings, "manual_session");
  ctx->syncAudio = obs_data_get_bool(settings, "sync_audio");
  ctx->latencyProfile = obs_data_get_string(settings, "latency_profile");
  ctx->outputResolution = obs_data_get_string(settings, "output_resolution");

  if (ctx->connectionMode.empty()) {
    ctx->connectionMode = "managed";
  }
  if (ctx->transportMode.empty()) {
    ctx->transportMode = "usb-adb";
  }
  if (ctx->hostAddress.empty()) {
    ctx->hostAddress = "127.0.0.1:39393";
  }
  if (ctx->fitMode.empty()) {
    ctx->fitMode = "letterbox";
  }
  if (ctx->qualityPreset.empty()) {
    ctx->qualityPreset = "balanced";
  }
  if (ctx->outputResolution.empty()) {
    ctx->outputResolution = "source";
  }
  ParseResolutionPreset(ctx->outputResolution, &ctx->targetWidth, &ctx->targetHeight);

  // OBS plugin runs in receiver-attach mode only. Session lifecycle is managed by receiver/gui.

  ctx->running.store(true);
  ctx->worker = std::thread(StreamWorker, ctx);
  return ctx;
}

void AndroidCamDestroy(void* data) {
  auto* ctx = static_cast<AndroidCamSourceContext*>(data);
  if (!ctx) {
    return;
  }

  ctx->running.store(false);
  if (ctx->worker.joinable()) {
    ctx->worker.join();
  }
  delete ctx;
}

void AndroidCamUpdate(void* data, obs_data_t* settings) {
  auto* ctx = static_cast<AndroidCamSourceContext*>(data);
  if (!ctx) {
    return;
  }

  std::lock_guard<std::mutex> lock(ctx->stateMutex);
  ctx->connectionMode = obs_data_get_string(settings, "connection_mode");
  ctx->sessionId = obs_data_get_string(settings, "session_id");
  ctx->transportMode = obs_data_get_string(settings, "transport_mode");
  ctx->hostAddress = obs_data_get_string(settings, "host_address");
  ctx->fitMode = obs_data_get_string(settings, "fit_mode");
  ctx->qualityPreset = obs_data_get_string(settings, "quality_preset");
  ctx->manualSession = obs_data_get_bool(settings, "manual_session");
  ctx->syncAudio = obs_data_get_bool(settings, "sync_audio");
  ctx->latencyProfile = obs_data_get_string(settings, "latency_profile");
  ctx->outputResolution = obs_data_get_string(settings, "output_resolution");
  ParseResolutionPreset(ctx->outputResolution, &ctx->targetWidth, &ctx->targetHeight);
  if (ctx->transportMode.find("usb") == std::string::npos) {
    ctx->adbSetupDone = false;
  }
}

obs_properties_t* AndroidCamProperties(void*) {
  obs_properties_t* props = obs_properties_create();

  obs_property_t* conn = obs_properties_add_list(props,
                                                 "connection_mode",
                                                 obs_module_text("AndroidCamSource.Prop.ConnectionMode"),
                                                 OBS_COMBO_TYPE_LIST,
                                                 OBS_COMBO_FORMAT_STRING);
  obs_property_list_add_string(conn, obs_module_text("AndroidCamSource.Option.Managed"), "managed");
  obs_property_list_add_string(conn, obs_module_text("AndroidCamSource.Option.Attach"), "attach");

  obs_property_t* transport = obs_properties_add_list(props,
                                                      "transport_mode",
                                                      obs_module_text("AndroidCamSource.Prop.Transport"),
                                                      OBS_COMBO_TYPE_LIST,
                                                      OBS_COMBO_FORMAT_STRING);
  obs_property_list_add_string(transport, obs_module_text("AndroidCamSource.Option.USB"), "usb-adb");
  obs_property_list_add_string(transport, obs_module_text("AndroidCamSource.Option.WiFi"), "lan");
  obs_property_list_add_string(transport, obs_module_text("AndroidCamSource.Option.USBNative"), "usb-native");

  obs_property_t* res = obs_properties_add_list(props,
                                                "output_resolution",
                                                obs_module_text("AndroidCamSource.Prop.OutputResolution"),
                                                OBS_COMBO_TYPE_LIST,
                                                OBS_COMBO_FORMAT_STRING);
  obs_property_list_add_string(res, obs_module_text("AndroidCamSource.Option.ResSource"), "source");
  obs_property_list_add_string(res, "640x480", "640x480");
  obs_property_list_add_string(res, "1280x720", "1280x720");
  obs_property_list_add_string(res, "1920x1080", "1920x1080");

  obs_property_t* fit = obs_properties_add_list(props,
                                                "fit_mode",
                                                obs_module_text("AndroidCamSource.Prop.FitMode"),
                                                OBS_COMBO_TYPE_LIST,
                                                OBS_COMBO_FORMAT_STRING);
  obs_property_list_add_string(fit, obs_module_text("AndroidCamSource.Option.FitLetterbox"), "letterbox");
  obs_property_list_add_string(fit, obs_module_text("AndroidCamSource.Option.FitCrop"), "crop");
  obs_property_list_add_string(fit, obs_module_text("AndroidCamSource.Option.FitStretch"), "stretch");

  obs_property_t* qp = obs_properties_add_list(props,
                                               "quality_preset",
                                               obs_module_text("AndroidCamSource.Prop.QualityPreset"),
                                               OBS_COMBO_TYPE_LIST,
                                               OBS_COMBO_FORMAT_STRING);
  obs_property_list_add_string(qp, obs_module_text("AndroidCamSource.Option.QualityBalanced"), "balanced");
  obs_property_list_add_string(qp, obs_module_text("AndroidCamSource.Option.QualityHigh"), "high");
  obs_property_list_add_string(qp, obs_module_text("AndroidCamSource.Option.QualityUltra"), "ultra");

  obs_properties_add_text(props,
                          "host_address",
                          obs_module_text("AndroidCamSource.Prop.HostAddress"),
                          OBS_TEXT_DEFAULT);
  obs_properties_add_bool(props,
                          "manual_session",
                          obs_module_text("AndroidCamSource.Prop.ManualSession"));
  obs_properties_add_text(props,
                          "session_id",
                          obs_module_text("AndroidCamSource.Prop.SessionId"),
                          OBS_TEXT_DEFAULT);
  obs_properties_add_bool(props,
                          "sync_audio",
                          obs_module_text("AndroidCamSource.Prop.SyncAudio"));

  obs_property_t* latency = obs_properties_add_list(props,
                                                    "latency_profile",
                                                    obs_module_text("AndroidCamSource.Prop.LatencyProfile"),
                                                    OBS_COMBO_TYPE_LIST,
                                                    OBS_COMBO_FORMAT_STRING);
  obs_property_list_add_string(latency, obs_module_text("AndroidCamSource.Option.LatencyLow"), "low");
  obs_property_list_add_string(latency, obs_module_text("AndroidCamSource.Option.LatencyBalanced"), "balanced");
  obs_property_list_add_string(latency, obs_module_text("AndroidCamSource.Option.LatencyStable"), "stable");
  return props;
}

void AndroidCamDefaults(obs_data_t* settings) {
  obs_data_set_default_string(settings, "connection_mode", "attach");
  obs_data_set_default_string(settings, "session_id", "");
  obs_data_set_default_string(settings, "transport_mode", "usb-adb");
  obs_data_set_default_string(settings, "host_address", "127.0.0.1:39393");
  obs_data_set_default_string(settings, "fit_mode", "letterbox");
  obs_data_set_default_string(settings, "quality_preset", "balanced");
  obs_data_set_default_bool(settings, "manual_session", false);
  obs_data_set_default_bool(settings, "sync_audio", true);
  obs_data_set_default_string(settings, "latency_profile", "balanced");
  obs_data_set_default_string(settings, "output_resolution", "source");
}

uint32_t AndroidCamWidth(void* data) {
  auto* ctx = static_cast<AndroidCamSourceContext*>(data);
  if (!ctx) {
    return 1280;
  }
  std::lock_guard<std::mutex> lock(ctx->stateMutex);
  return ctx->width;
}

uint32_t AndroidCamHeight(void* data) {
  auto* ctx = static_cast<AndroidCamSourceContext*>(data);
  if (!ctx) {
    return 720;
  }
  std::lock_guard<std::mutex> lock(ctx->stateMutex);
  return ctx->height;
}

obs_source_info BuildSourceInfo() {
  obs_source_info info{};
  info.id = kSourceId;
  info.type = OBS_SOURCE_TYPE_INPUT;
  info.output_flags = OBS_SOURCE_ASYNC_VIDEO;
  info.get_name = AndroidCamGetName;
  info.create = AndroidCamCreate;
  info.destroy = AndroidCamDestroy;
  info.update = AndroidCamUpdate;
  info.get_defaults = AndroidCamDefaults;
  info.get_properties = AndroidCamProperties;
  info.get_width = AndroidCamWidth;
  info.get_height = AndroidCamHeight;
  return info;
}

const obs_source_info kAndroidCamSourceInfo = BuildSourceInfo();

}  // namespace

extern "C" MODULE_EXPORT const char* obs_module_name(void) {
  return "Android Cam Bridge OBS Plugin";
}

extern "C" MODULE_EXPORT const char* obs_module_description(void) {
  return "Android camera source for OBS via LAN/USB receiver.";
}

extern "C" MODULE_EXPORT bool obs_module_load(void) {
  obs_register_source(&kAndroidCamSourceInfo);
  blog(LOG_INFO, "[acb-obs-plugin] loaded and source registered: %s", kSourceId);
  return true;
}
