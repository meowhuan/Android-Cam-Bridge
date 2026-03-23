#include "virtualcam_pin.h"
#include "virtualcam_filter.h"
#include "guids.h"

#include <cstring>
#include <cstdint>
#include <algorithm>
#include <new>

// DirectShow / KsProxy error codes that may not be in all SDK versions
#ifndef E_PROP_SET_UNSUPPORTED
#define E_PROP_SET_UNSUPPORTED  _HRESULT_TYPEDEF_(0x80070492L)
#endif
#ifndef E_PROP_ID_UNSUPPORTED
#define E_PROP_ID_UNSUPPORTED   _HRESULT_TYPEDEF_(0x80070493L)
#endif

// We need AMPROPERTY_PIN_CATEGORY from vidcap.h / ksproxy
#ifndef AMPROPERTY_PIN_CATEGORY
#define AMPROPERTY_PIN_CATEGORY 0
#endif

constexpr int VirtualCamPin::kResolutions[kNumCaps][2];

// ===========================================================================
// SimpleMediaSample -- minimal IMediaSample implementation
// ===========================================================================
class SimpleMediaSample final : public IMediaSample {
public:
    SimpleMediaSample(BYTE* buffer, LONG size, VirtualCamPin* allocator)
        : buffer_(buffer), maxSize_(size), actualSize_(size), allocator_(allocator) {
        allocator_->AddRef();
    }
    ~SimpleMediaSample() {
        delete[] buffer_;
        allocator_->Release();
    }

    // IUnknown
    STDMETHODIMP QueryInterface(REFIID riid, void** ppv) override {
        if (!ppv) return E_POINTER;
        if (riid == IID_IUnknown || riid == IID_IMediaSample) {
            *ppv = static_cast<IMediaSample*>(this);
            AddRef();
            return S_OK;
        }
        *ppv = nullptr;
        return E_NOINTERFACE;
    }
    STDMETHODIMP_(ULONG) AddRef() override {
        return InterlockedIncrement(&refCount_);
    }
    STDMETHODIMP_(ULONG) Release() override {
        ULONG ref = InterlockedDecrement(&refCount_);
        if (ref == 0) delete this;
        return ref;
    }

    // IMediaSample
    STDMETHODIMP GetPointer(BYTE** ppBuffer) override {
        if (!ppBuffer) return E_POINTER;
        *ppBuffer = buffer_;
        return S_OK;
    }
    STDMETHODIMP_(LONG) GetSize() override { return maxSize_; }
    STDMETHODIMP GetTime(REFERENCE_TIME* pTimeStart, REFERENCE_TIME* pTimeEnd) override {
        if (!pTimeStart || !pTimeEnd) return E_POINTER;
        *pTimeStart = timeStart_;
        *pTimeEnd = timeEnd_;
        return hasTime_ ? S_OK : VFW_E_SAMPLE_TIME_NOT_SET;
    }
    STDMETHODIMP SetTime(REFERENCE_TIME* pTimeStart, REFERENCE_TIME* pTimeEnd) override {
        if (pTimeStart && pTimeEnd) {
            timeStart_ = *pTimeStart;
            timeEnd_ = *pTimeEnd;
            hasTime_ = true;
        } else {
            hasTime_ = false;
        }
        return S_OK;
    }
    STDMETHODIMP IsSyncPoint() override { return isSyncPoint_ ? S_OK : S_FALSE; }
    STDMETHODIMP SetSyncPoint(BOOL bIsSyncPoint) override {
        isSyncPoint_ = !!bIsSyncPoint;
        return S_OK;
    }
    STDMETHODIMP IsPreroll() override { return S_FALSE; }
    STDMETHODIMP SetPreroll(BOOL) override { return S_OK; }
    STDMETHODIMP_(LONG) GetActualDataLength() override { return actualSize_; }
    STDMETHODIMP SetActualDataLength(LONG length) override {
        if (length > maxSize_) return E_INVALIDARG;
        actualSize_ = length;
        return S_OK;
    }
    STDMETHODIMP GetMediaType(AM_MEDIA_TYPE** ppMediaType) override {
        if (!ppMediaType) return E_POINTER;
        *ppMediaType = nullptr;
        return S_FALSE; // No type change
    }
    STDMETHODIMP SetMediaType(AM_MEDIA_TYPE*) override { return S_OK; }
    STDMETHODIMP IsDiscontinuity() override { return isDiscontinuity_ ? S_OK : S_FALSE; }
    STDMETHODIMP SetDiscontinuity(BOOL bDiscontinuity) override {
        isDiscontinuity_ = !!bDiscontinuity;
        return S_OK;
    }
    STDMETHODIMP GetMediaTime(LONGLONG* pTimeStart, LONGLONG* pTimeEnd) override {
        (void)pTimeStart; (void)pTimeEnd;
        return VFW_E_MEDIA_TIME_NOT_SET;
    }
    STDMETHODIMP SetMediaTime(LONGLONG*, LONGLONG*) override { return S_OK; }

private:
    volatile LONG refCount_ = 1;
    BYTE* buffer_;
    LONG maxSize_;
    LONG actualSize_;
    VirtualCamPin* allocator_;
    REFERENCE_TIME timeStart_ = 0;
    REFERENCE_TIME timeEnd_ = 0;
    bool hasTime_ = false;
    bool isSyncPoint_ = true;
    bool isDiscontinuity_ = false;
};

// ===========================================================================
// EnumMediaTypes implementation
// ===========================================================================

EnumMediaTypes::EnumMediaTypes(const AM_MEDIA_TYPE* types, int count, int pos)
    : count_(count), pos_(pos) {
    types_ = new AM_MEDIA_TYPE[count];
    for (int i = 0; i < count; ++i) {
        types_[i] = types[i];
        if (types[i].cbFormat > 0 && types[i].pbFormat) {
            types_[i].pbFormat = static_cast<BYTE*>(CoTaskMemAlloc(types[i].cbFormat));
            if (types_[i].pbFormat)
                memcpy(types_[i].pbFormat, types[i].pbFormat, types[i].cbFormat);
        }
    }
}

EnumMediaTypes::~EnumMediaTypes() {
    for (int i = 0; i < count_; ++i) {
        if (types_[i].pbFormat) CoTaskMemFree(types_[i].pbFormat);
    }
    delete[] types_;
}

STDMETHODIMP EnumMediaTypes::QueryInterface(REFIID riid, void** ppv) {
    if (!ppv) return E_POINTER;
    if (riid == IID_IUnknown || riid == IID_IEnumMediaTypes) {
        *ppv = static_cast<IEnumMediaTypes*>(this);
        AddRef();
        return S_OK;
    }
    *ppv = nullptr;
    return E_NOINTERFACE;
}

STDMETHODIMP_(ULONG) EnumMediaTypes::AddRef() {
    return InterlockedIncrement(&refCount_);
}

STDMETHODIMP_(ULONG) EnumMediaTypes::Release() {
    ULONG ref = InterlockedDecrement(&refCount_);
    if (ref == 0) delete this;
    return ref;
}

STDMETHODIMP EnumMediaTypes::Next(ULONG cMediaTypes, AM_MEDIA_TYPE** ppMediaTypes, ULONG* pcFetched) {
    if (!ppMediaTypes) return E_POINTER;
    ULONG fetched = 0;
    while (fetched < cMediaTypes && pos_ < count_) {
        ppMediaTypes[fetched] = CopyMediaType(&types_[pos_]);
        if (!ppMediaTypes[fetched]) return E_OUTOFMEMORY;
        ++fetched;
        ++pos_;
    }
    if (pcFetched) *pcFetched = fetched;
    return (fetched == cMediaTypes) ? S_OK : S_FALSE;
}

STDMETHODIMP EnumMediaTypes::Skip(ULONG cMediaTypes) {
    pos_ += static_cast<int>(cMediaTypes);
    return (pos_ <= count_) ? S_OK : S_FALSE;
}

STDMETHODIMP EnumMediaTypes::Reset() {
    pos_ = 0;
    return S_OK;
}

STDMETHODIMP EnumMediaTypes::Clone(IEnumMediaTypes** ppEnum) {
    if (!ppEnum) return E_POINTER;
    *ppEnum = new (std::nothrow) ::EnumMediaTypes(types_, count_, pos_);
    return (*ppEnum) ? S_OK : E_OUTOFMEMORY;
}

AM_MEDIA_TYPE* EnumMediaTypes::CopyMediaType(const AM_MEDIA_TYPE* src) {
    auto* dst = static_cast<AM_MEDIA_TYPE*>(CoTaskMemAlloc(sizeof(AM_MEDIA_TYPE)));
    if (!dst) return nullptr;
    *dst = *src;
    if (src->cbFormat > 0 && src->pbFormat) {
        dst->pbFormat = static_cast<BYTE*>(CoTaskMemAlloc(src->cbFormat));
        if (!dst->pbFormat) {
            CoTaskMemFree(dst);
            return nullptr;
        }
        memcpy(dst->pbFormat, src->pbFormat, src->cbFormat);
    }
    return dst;
}

void EnumMediaTypes::FreeMediaType(AM_MEDIA_TYPE* mt) {
    if (!mt) return;
    if (mt->pbFormat) CoTaskMemFree(mt->pbFormat);
    CoTaskMemFree(mt);
}

// ===========================================================================
// VirtualCamPin implementation
// ===========================================================================

VirtualCamPin::VirtualCamPin(VirtualCamFilter* filter) : filter_(filter) {
    InitializeCriticalSection(&cs_);
    stopEvent_ = CreateEvent(nullptr, TRUE, FALSE, nullptr);
    // Pre-allocate frame buffer for max resolution
    frameBuffer_.resize(3840ULL * 2160 * 4);
}

VirtualCamPin::~VirtualCamPin() {
    StopThread();
    if (stopEvent_) CloseHandle(stopEvent_);
    if (connectedPin_) connectedPin_->Release();
    if (connectedInput_) connectedInput_->Release();
    if (connectedMt_.pbFormat) CoTaskMemFree(connectedMt_.pbFormat);
    DeleteCriticalSection(&cs_);
}

// ---------------------------------------------------------------------------
// IUnknown
// ---------------------------------------------------------------------------

STDMETHODIMP VirtualCamPin::QueryInterface(REFIID riid, void** ppv) {
    if (!ppv) return E_POINTER;

    if (riid == IID_IUnknown) {
        *ppv = static_cast<IPin*>(this);
    } else if (riid == IID_IPin) {
        *ppv = static_cast<IPin*>(this);
    } else if (riid == IID_IQualityControl) {
        *ppv = static_cast<IQualityControl*>(this);
    } else if (riid == IID_IAMStreamConfig) {
        *ppv = static_cast<IAMStreamConfig*>(this);
    } else if (riid == IID_IKsPropertySet) {
        *ppv = static_cast<IKsPropertySet*>(this);
    } else if (riid == IID_IMemAllocator) {
        *ppv = static_cast<IMemAllocator*>(this);
    } else {
        *ppv = nullptr;
        return E_NOINTERFACE;
    }
    AddRef();
    return S_OK;
}

STDMETHODIMP_(ULONG) VirtualCamPin::AddRef() {
    return InterlockedIncrement(&refCount_);
}

STDMETHODIMP_(ULONG) VirtualCamPin::Release() {
    ULONG ref = InterlockedDecrement(&refCount_);
    if (ref == 0) delete this;
    return ref;
}

// ---------------------------------------------------------------------------
// Helper: build AM_MEDIA_TYPE for RGB32 at given resolution
// ---------------------------------------------------------------------------

void VirtualCamPin::BuildMediaType(AM_MEDIA_TYPE* pmt, int width, int height) {
    ZeroMemory(pmt, sizeof(AM_MEDIA_TYPE));
    pmt->majortype = MEDIATYPE_Video;
    pmt->subtype = MEDIASUBTYPE_RGB32;
    pmt->formattype = FORMAT_VideoInfo;
    pmt->bFixedSizeSamples = TRUE;
    pmt->bTemporalCompression = FALSE;
    pmt->lSampleSize = width * height * 4;
    pmt->cbFormat = sizeof(VIDEOINFOHEADER);
    pmt->pbFormat = static_cast<BYTE*>(CoTaskMemAlloc(sizeof(VIDEOINFOHEADER)));
    if (!pmt->pbFormat) return;

    auto* vih = reinterpret_cast<VIDEOINFOHEADER*>(pmt->pbFormat);
    ZeroMemory(vih, sizeof(VIDEOINFOHEADER));
    vih->bmiHeader.biSize = sizeof(BITMAPINFOHEADER);
    vih->bmiHeader.biWidth = width;
    vih->bmiHeader.biHeight = height; // positive = bottom-up (DirectShow standard)
    vih->bmiHeader.biPlanes = 1;
    vih->bmiHeader.biBitCount = 32;
    vih->bmiHeader.biCompression = BI_RGB;
    vih->bmiHeader.biSizeImage = width * height * 4;
    vih->AvgTimePerFrame = 333333; // 30fps in 100ns units
    // rcSource and rcTarget left as zero (entire frame)
}

void VirtualCamPin::GetPreferredResolution(int& width, int& height) {
    uint32_t w = 0, h = 0;
    if (reader_.GetResolution(w, h) && w > 0 && h > 0) {
        width = static_cast<int>(w);
        height = static_cast<int>(h);
    } else {
        width = curWidth_;
        height = curHeight_;
    }
}

// ---------------------------------------------------------------------------
// IPin
// ---------------------------------------------------------------------------

STDMETHODIMP VirtualCamPin::Connect(IPin* pReceivePin, const AM_MEDIA_TYPE* pmt) {
    if (!pReceivePin) return E_POINTER;

    EnterCriticalSection(&cs_);

    if (connected_) {
        LeaveCriticalSection(&cs_);
        return VFW_E_ALREADY_CONNECTED;
    }

    // Build the media type to propose
    AM_MEDIA_TYPE proposedMt;
    if (pmt && pmt->majortype != GUID_NULL) {
        // Use the caller's media type if provided
        proposedMt = *pmt;
        if (pmt->cbFormat > 0 && pmt->pbFormat) {
            proposedMt.pbFormat = static_cast<BYTE*>(CoTaskMemAlloc(pmt->cbFormat));
            if (proposedMt.pbFormat)
                memcpy(proposedMt.pbFormat, pmt->pbFormat, pmt->cbFormat);
        } else {
            proposedMt.pbFormat = nullptr;
            proposedMt.cbFormat = 0;
        }
    } else {
        int w, h;
        GetPreferredResolution(w, h);
        BuildMediaType(&proposedMt, w, h);
    }

    // Try to connect to the downstream pin
    HRESULT hr = pReceivePin->ReceiveConnection(this, &proposedMt);

    if (FAILED(hr)) {
        // If the proposed type was rejected and caller didn't force one,
        // try other resolutions
        if (!pmt || pmt->majortype == GUID_NULL) {
            if (proposedMt.pbFormat) CoTaskMemFree(proposedMt.pbFormat);

            bool found = false;
            for (int i = 0; i < kNumCaps && !found; ++i) {
                BuildMediaType(&proposedMt, kResolutions[i][0], kResolutions[i][1]);
                hr = pReceivePin->ReceiveConnection(this, &proposedMt);
                if (SUCCEEDED(hr)) {
                    found = true;
                } else {
                    if (proposedMt.pbFormat) CoTaskMemFree(proposedMt.pbFormat);
                    ZeroMemory(&proposedMt, sizeof(proposedMt));
                }
            }
        }
    }

    if (SUCCEEDED(hr)) {
        // Get IMemInputPin from the connected pin
        IMemInputPin* inputPin = nullptr;
        hr = pReceivePin->QueryInterface(IID_IMemInputPin, reinterpret_cast<void**>(&inputPin));
        if (SUCCEEDED(hr)) {
            // Propose our internal allocator
            ALLOCATOR_PROPERTIES props;
            props.cBuffers = 1;
            props.cbBuffer = proposedMt.lSampleSize;
            props.cbAlign = 1;
            props.cbPrefix = 0;

            IMemAllocator* allocator = static_cast<IMemAllocator*>(this);
            ALLOCATOR_PROPERTIES actual;
            allocator->SetProperties(&props, &actual);

            hr = inputPin->NotifyAllocator(allocator, FALSE);
            if (SUCCEEDED(hr)) {
                connected_ = true;
                connectedPin_ = pReceivePin;
                connectedPin_->AddRef();
                connectedInput_ = inputPin;
                // inputPin already AddRef'd by QI

                // Store the connected media type
                connectedMt_ = proposedMt;

                // Extract resolution from connected type
                if (connectedMt_.formattype == FORMAT_VideoInfo && connectedMt_.pbFormat) {
                    auto* vih = reinterpret_cast<VIDEOINFOHEADER*>(connectedMt_.pbFormat);
                    curWidth_ = vih->bmiHeader.biWidth;
                    curHeight_ = abs(vih->bmiHeader.biHeight);
                }
            } else {
                inputPin->Release();
                pReceivePin->Disconnect();
                if (proposedMt.pbFormat) CoTaskMemFree(proposedMt.pbFormat);
            }
        } else {
            pReceivePin->Disconnect();
            if (proposedMt.pbFormat) CoTaskMemFree(proposedMt.pbFormat);
        }
    }

    LeaveCriticalSection(&cs_);
    return hr;
}

STDMETHODIMP VirtualCamPin::ReceiveConnection(IPin*, const AM_MEDIA_TYPE*) {
    return E_UNEXPECTED; // Output pin should not receive connections
}

STDMETHODIMP VirtualCamPin::Disconnect() {
    EnterCriticalSection(&cs_);
    if (!connected_) {
        LeaveCriticalSection(&cs_);
        return S_FALSE;
    }
    connected_ = false;
    LeaveCriticalSection(&cs_);

    StopThread();

    EnterCriticalSection(&cs_);
    if (connectedInput_) {
        connectedInput_->Release();
        connectedInput_ = nullptr;
    }
    if (connectedPin_) {
        connectedPin_->Release();
        connectedPin_ = nullptr;
    }
    if (connectedMt_.pbFormat) {
        CoTaskMemFree(connectedMt_.pbFormat);
        connectedMt_.pbFormat = nullptr;
    }
    ZeroMemory(&connectedMt_, sizeof(connectedMt_));
    LeaveCriticalSection(&cs_);
    return S_OK;
}

STDMETHODIMP VirtualCamPin::ConnectedTo(IPin** pPin) {
    if (!pPin) return E_POINTER;
    EnterCriticalSection(&cs_);
    if (!connected_) {
        *pPin = nullptr;
        LeaveCriticalSection(&cs_);
        return VFW_E_NOT_CONNECTED;
    }
    *pPin = connectedPin_;
    connectedPin_->AddRef();
    LeaveCriticalSection(&cs_);
    return S_OK;
}

STDMETHODIMP VirtualCamPin::ConnectionMediaType(AM_MEDIA_TYPE* pmt) {
    if (!pmt) return E_POINTER;
    EnterCriticalSection(&cs_);
    if (!connected_) {
        ZeroMemory(pmt, sizeof(AM_MEDIA_TYPE));
        LeaveCriticalSection(&cs_);
        return VFW_E_NOT_CONNECTED;
    }
    *pmt = connectedMt_;
    if (connectedMt_.cbFormat > 0 && connectedMt_.pbFormat) {
        pmt->pbFormat = static_cast<BYTE*>(CoTaskMemAlloc(connectedMt_.cbFormat));
        if (pmt->pbFormat)
            memcpy(pmt->pbFormat, connectedMt_.pbFormat, connectedMt_.cbFormat);
    }
    LeaveCriticalSection(&cs_);
    return S_OK;
}

STDMETHODIMP VirtualCamPin::QueryPinInfo(PIN_INFO* pInfo) {
    if (!pInfo) return E_POINTER;
    pInfo->pFilter = static_cast<IBaseFilter*>(filter_);
    if (filter_) filter_->AddRef();
    pInfo->dir = PINDIR_OUTPUT;
    wcscpy_s(pInfo->achName, MAX_PIN_NAME, L"Capture");
    return S_OK;
}

STDMETHODIMP VirtualCamPin::QueryDirection(PIN_DIRECTION* pPinDir) {
    if (!pPinDir) return E_POINTER;
    *pPinDir = PINDIR_OUTPUT;
    return S_OK;
}

STDMETHODIMP VirtualCamPin::QueryId(LPWSTR* Id) {
    if (!Id) return E_POINTER;
    const wchar_t* name = L"Capture";
    size_t len = wcslen(name) + 1;
    *Id = static_cast<LPWSTR>(CoTaskMemAlloc(len * sizeof(wchar_t)));
    if (!*Id) return E_OUTOFMEMORY;
    wcscpy_s(*Id, len, name);
    return S_OK;
}

STDMETHODIMP VirtualCamPin::QueryAccept(const AM_MEDIA_TYPE* pmt) {
    if (!pmt) return E_POINTER;
    if (pmt->majortype != MEDIATYPE_Video) return S_FALSE;
    if (pmt->subtype != MEDIASUBTYPE_RGB32) return S_FALSE;
    if (pmt->formattype != FORMAT_VideoInfo) return S_FALSE;
    return S_OK;
}

STDMETHODIMP VirtualCamPin::EnumMediaTypes(IEnumMediaTypes** ppEnum) {
    if (!ppEnum) return E_POINTER;

    // Determine current preferred resolution from shared memory
    int prefW, prefH;
    GetPreferredResolution(prefW, prefH);

    // Build media types for all supported resolutions
    // Put the preferred/current resolution first
    AM_MEDIA_TYPE types[kNumCaps + 1]; // +1 for the preferred if it's not in the list
    int count = 0;

    // Add preferred resolution first
    BuildMediaType(&types[count++], prefW, prefH);

    // Add standard resolutions (skip if same as preferred)
    for (int i = 0; i < kNumCaps; ++i) {
        if (kResolutions[i][0] == prefW && kResolutions[i][1] == prefH)
            continue;
        BuildMediaType(&types[count++], kResolutions[i][0], kResolutions[i][1]);
    }

    *ppEnum = new (std::nothrow) ::EnumMediaTypes(types, count);

    // Free our temporary format blocks
    for (int i = 0; i < count; ++i) {
        if (types[i].pbFormat) CoTaskMemFree(types[i].pbFormat);
    }

    return (*ppEnum) ? S_OK : E_OUTOFMEMORY;
}

STDMETHODIMP VirtualCamPin::QueryInternalConnections(IPin**, ULONG* nPin) {
    if (nPin) *nPin = 0;
    return E_NOTIMPL;
}

STDMETHODIMP VirtualCamPin::EndOfStream() { return S_OK; }
STDMETHODIMP VirtualCamPin::BeginFlush() { return S_OK; }
STDMETHODIMP VirtualCamPin::EndFlush() { return S_OK; }
STDMETHODIMP VirtualCamPin::NewSegment(REFERENCE_TIME, REFERENCE_TIME, double) { return S_OK; }

// ---------------------------------------------------------------------------
// IQualityControl
// ---------------------------------------------------------------------------

STDMETHODIMP VirtualCamPin::Notify(IBaseFilter*, Quality) { return S_OK; }
STDMETHODIMP VirtualCamPin::SetSink(IQualityControl*) { return S_OK; }

// ---------------------------------------------------------------------------
// IAMStreamConfig
// ---------------------------------------------------------------------------

STDMETHODIMP VirtualCamPin::SetFormat(AM_MEDIA_TYPE* pmt) {
    if (!pmt) return E_POINTER;
    if (pmt->majortype != MEDIATYPE_Video) return VFW_E_INVALIDMEDIATYPE;
    if (pmt->subtype != MEDIASUBTYPE_RGB32) return VFW_E_INVALIDMEDIATYPE;
    if (pmt->formattype != FORMAT_VideoInfo) return VFW_E_INVALIDMEDIATYPE;

    auto* vih = reinterpret_cast<VIDEOINFOHEADER*>(pmt->pbFormat);
    if (!vih) return VFW_E_INVALIDMEDIATYPE;

    EnterCriticalSection(&cs_);
    curWidth_ = vih->bmiHeader.biWidth;
    curHeight_ = abs(vih->bmiHeader.biHeight);
    LeaveCriticalSection(&cs_);
    return S_OK;
}

STDMETHODIMP VirtualCamPin::GetFormat(AM_MEDIA_TYPE** ppmt) {
    if (!ppmt) return E_POINTER;
    int w, h;
    GetPreferredResolution(w, h);
    *ppmt = static_cast<AM_MEDIA_TYPE*>(CoTaskMemAlloc(sizeof(AM_MEDIA_TYPE)));
    if (!*ppmt) return E_OUTOFMEMORY;
    BuildMediaType(*ppmt, w, h);
    return S_OK;
}

STDMETHODIMP VirtualCamPin::GetNumberOfCapabilities(int* piCount, int* piSize) {
    if (!piCount || !piSize) return E_POINTER;
    *piCount = kNumCaps;
    *piSize = sizeof(VIDEO_STREAM_CONFIG_CAPS);
    return S_OK;
}

STDMETHODIMP VirtualCamPin::GetStreamCaps(int iIndex, AM_MEDIA_TYPE** ppmt, BYTE* pSCC) {
    if (!ppmt || !pSCC) return E_POINTER;
    if (iIndex < 0 || iIndex >= kNumCaps) return E_INVALIDARG;

    int w = kResolutions[iIndex][0];
    int h = kResolutions[iIndex][1];

    *ppmt = static_cast<AM_MEDIA_TYPE*>(CoTaskMemAlloc(sizeof(AM_MEDIA_TYPE)));
    if (!*ppmt) return E_OUTOFMEMORY;
    BuildMediaType(*ppmt, w, h);

    auto* caps = reinterpret_cast<VIDEO_STREAM_CONFIG_CAPS*>(pSCC);
    ZeroMemory(caps, sizeof(VIDEO_STREAM_CONFIG_CAPS));
    caps->guid = FORMAT_VideoInfo;
    caps->VideoStandard = 0;
    caps->InputSize.cx = w;
    caps->InputSize.cy = h;
    caps->MinCroppingSize.cx = w;
    caps->MinCroppingSize.cy = h;
    caps->MaxCroppingSize.cx = w;
    caps->MaxCroppingSize.cy = h;
    caps->CropGranularityX = 1;
    caps->CropGranularityY = 1;
    caps->CropAlignX = 1;
    caps->CropAlignY = 1;
    caps->MinOutputSize.cx = w;
    caps->MinOutputSize.cy = h;
    caps->MaxOutputSize.cx = w;
    caps->MaxOutputSize.cy = h;
    caps->OutputGranularityX = 1;
    caps->OutputGranularityY = 1;
    caps->StretchTapsX = 0;
    caps->StretchTapsY = 0;
    caps->ShrinkTapsX = 0;
    caps->ShrinkTapsY = 0;
    caps->MinFrameInterval = 333333;   // 30fps
    caps->MaxFrameInterval = 10000000; // 1fps
    caps->MinBitsPerSecond = static_cast<LONG>((std::min)(static_cast<int64_t>(w) * h * 4 * 8 * 1, static_cast<int64_t>(LONG_MAX)));
    caps->MaxBitsPerSecond = static_cast<LONG>((std::min)(static_cast<int64_t>(w) * h * 4 * 8 * 30, static_cast<int64_t>(LONG_MAX)));

    return S_OK;
}

// ---------------------------------------------------------------------------
// IKsPropertySet -- CRITICAL for camera detection
// ---------------------------------------------------------------------------

STDMETHODIMP VirtualCamPin::Set(REFGUID, DWORD, LPVOID, DWORD, LPVOID, DWORD) {
    return E_NOTIMPL;
}

STDMETHODIMP VirtualCamPin::Get(REFGUID guidPropSet, DWORD dwPropID,
                                 LPVOID /*pInstanceData*/, DWORD /*cbInstanceData*/,
                                 LPVOID pPropData, DWORD cbPropData,
                                 DWORD* pcbReturned) {
    if (guidPropSet != AMPROPSETID_Pin) return E_PROP_SET_UNSUPPORTED;
    if (dwPropID != AMPROPERTY_PIN_CATEGORY) return E_PROP_ID_UNSUPPORTED;
    if (cbPropData < sizeof(GUID)) return E_UNEXPECTED;

    // Return PIN_CATEGORY_CAPTURE -- this is what makes apps recognize us as a camera
    *reinterpret_cast<GUID*>(pPropData) = PIN_CATEGORY_CAPTURE;
    if (pcbReturned) *pcbReturned = sizeof(GUID);
    return S_OK;
}

STDMETHODIMP VirtualCamPin::QuerySupported(REFGUID guidPropSet, DWORD dwPropID,
                                            DWORD* pTypeSupport) {
    if (guidPropSet != AMPROPSETID_Pin) return E_PROP_SET_UNSUPPORTED;
    if (dwPropID != AMPROPERTY_PIN_CATEGORY) return E_PROP_ID_UNSUPPORTED;
    if (pTypeSupport) *pTypeSupport = KSPROPERTY_SUPPORT_GET;
    return S_OK;
}

// ---------------------------------------------------------------------------
// IMemAllocator (internal allocator)
// ---------------------------------------------------------------------------

STDMETHODIMP VirtualCamPin::SetProperties(ALLOCATOR_PROPERTIES* pRequest,
                                           ALLOCATOR_PROPERTIES* pActual) {
    if (!pRequest || !pActual) return E_POINTER;
    EnterCriticalSection(&cs_);
    allocProps_ = *pRequest;
    if (allocProps_.cBuffers < 1) allocProps_.cBuffers = 1;
    if (allocProps_.cbAlign < 1) allocProps_.cbAlign = 1;
    *pActual = allocProps_;
    LeaveCriticalSection(&cs_);
    return S_OK;
}

STDMETHODIMP VirtualCamPin::GetProperties(ALLOCATOR_PROPERTIES* pProps) {
    if (!pProps) return E_POINTER;
    EnterCriticalSection(&cs_);
    *pProps = allocProps_;
    LeaveCriticalSection(&cs_);
    return S_OK;
}

STDMETHODIMP VirtualCamPin::Commit() {
    EnterCriticalSection(&cs_);
    allocCommitted_ = true;
    LeaveCriticalSection(&cs_);
    return S_OK;
}

STDMETHODIMP VirtualCamPin::Decommit() {
    EnterCriticalSection(&cs_);
    allocCommitted_ = false;
    LeaveCriticalSection(&cs_);
    return S_OK;
}

STDMETHODIMP VirtualCamPin::GetBuffer(IMediaSample** ppBuffer,
                                       REFERENCE_TIME* /*pStartTime*/,
                                       REFERENCE_TIME* /*pEndTime*/,
                                       DWORD /*dwFlags*/) {
    if (!ppBuffer) return E_POINTER;
    EnterCriticalSection(&cs_);
    if (!allocCommitted_) {
        LeaveCriticalSection(&cs_);
        return VFW_E_NOT_COMMITTED;
    }
    LONG size = allocProps_.cbBuffer;
    LeaveCriticalSection(&cs_);

    BYTE* buffer = new (std::nothrow) BYTE[size];
    if (!buffer) return E_OUTOFMEMORY;

    auto* sample = new (std::nothrow) SimpleMediaSample(buffer, size, this);
    if (!sample) {
        delete[] buffer;
        return E_OUTOFMEMORY;
    }
    *ppBuffer = sample;
    return S_OK;
}

STDMETHODIMP VirtualCamPin::ReleaseBuffer(IMediaSample* pBuffer) {
    if (pBuffer) pBuffer->Release();
    return S_OK;
}

// ---------------------------------------------------------------------------
// Thread management
// ---------------------------------------------------------------------------

void VirtualCamPin::StartThread() {
    if (threadRunning_) return;
    ResetEvent(stopEvent_);
    threadRunning_ = true;
    thread_ = CreateThread(nullptr, 0, ThreadEntry, this, 0, nullptr);
    if (!thread_) {
        threadRunning_ = false;
    }
}

void VirtualCamPin::StopThread() {
    if (!threadRunning_) return;
    threadRunning_ = false;
    SetEvent(stopEvent_);
    if (thread_) {
        WaitForSingleObject(thread_, 5000);
        CloseHandle(thread_);
        thread_ = nullptr;
    }
}

DWORD WINAPI VirtualCamPin::ThreadEntry(LPVOID param) {
    auto* pin = static_cast<VirtualCamPin*>(param);
    pin->ThreadProc();
    return 0;
}

void VirtualCamPin::ThreadProc() {
    // Commit the allocator
    Commit();

    REFERENCE_TIME frameNumber = 0;
    const REFERENCE_TIME frameDuration = 333333; // 30fps in 100ns units
    bool firstFrame = true;
    bool hasValidFrame = false;
    uint32_t lastValidW = 0, lastValidH = 0;

    while (threadRunning_) {
        // Check if we're connected
        EnterCriticalSection(&cs_);
        IMemInputPin* inputPin = connectedInput_;
        if (inputPin) inputPin->AddRef();
        int width = curWidth_;
        int height = curHeight_;
        LeaveCriticalSection(&cs_);

        if (!inputPin) {
            WaitForSingleObject(stopEvent_, 33);
            continue;
        }

        // Try to read a frame from shared memory
        uint32_t frameW = 0, frameH = 0;
        bool gotNewFrame = reader_.ReadFrame(frameBuffer_.data(),
                                             static_cast<uint32_t>(frameBuffer_.size()),
                                             frameW, frameH);

        if (gotNewFrame && frameW > 0 && frameH > 0) {
            hasValidFrame = true;
            lastValidW = frameW;
            lastValidH = frameH;
        }

        // Get a buffer from the allocator
        IMediaSample* sample = nullptr;
        HRESULT hr = GetBuffer(&sample, nullptr, nullptr, 0);
        if (FAILED(hr) || !sample) {
            inputPin->Release();
            WaitForSingleObject(stopEvent_, 33);
            continue;
        }

        BYTE* sampleBuf = nullptr;
        sample->GetPointer(&sampleBuf);
        LONG sampleSize = sample->GetSize();
        LONG expectedSize = static_cast<LONG>(width) * height * 4;

        if (hasValidFrame && static_cast<int>(lastValidW) == width
            && static_cast<int>(lastValidH) == height
            && expectedSize <= sampleSize) {
            // Flip vertically: shared memory is top-down BGRA,
            // DirectShow RGB32 with positive biHeight is bottom-up
            int stride = width * 4;
            for (int y = 0; y < height; ++y) {
                const uint8_t* srcRow = frameBuffer_.data() + y * stride;
                uint8_t* dstRow = sampleBuf + (height - 1 - y) * stride;
                memcpy(dstRow, srcRow, stride);
            }
            sample->SetActualDataLength(expectedSize);
        } else {
            // No valid frame or resolution mismatch -- deliver black frame
            if (expectedSize <= sampleSize) {
                ZeroMemory(sampleBuf, expectedSize);
                sample->SetActualDataLength(expectedSize);
            }
        }

        // Set timestamps
        REFERENCE_TIME startTime = frameNumber * frameDuration;
        REFERENCE_TIME endTime = startTime + frameDuration;
        sample->SetTime(&startTime, &endTime);
        sample->SetSyncPoint(TRUE);

        if (firstFrame) {
            sample->SetDiscontinuity(TRUE);
            firstFrame = false;
        }

        // Deliver to the downstream filter
        hr = inputPin->Receive(sample);
        sample->Release();
        inputPin->Release();
        if (FAILED(hr) || hr == S_FALSE) break;

        ++frameNumber;

        // Poll at ~30fps matching AvgTimePerFrame=333333 (30fps)
        if (WaitForSingleObject(stopEvent_, 33) == WAIT_OBJECT_0) {
            break;
        }
    }

    Decommit();
    reader_.Close();
}
