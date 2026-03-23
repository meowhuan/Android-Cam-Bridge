#include "virtualcam_filter.h"
#include "virtualcam_pin.h"
#include "guids.h"

#include <cstring>
#include <new>

// ===========================================================================
// EnumPins implementation
// ===========================================================================

EnumPins::EnumPins(IPin* pin, ULONG pos) : pin_(pin), pos_(pos) {
    if (pin_) pin_->AddRef();
}

EnumPins::~EnumPins() {
    if (pin_) pin_->Release();
}

STDMETHODIMP EnumPins::QueryInterface(REFIID riid, void** ppv) {
    if (!ppv) return E_POINTER;
    if (riid == IID_IUnknown || riid == IID_IEnumPins) {
        *ppv = static_cast<IEnumPins*>(this);
        AddRef();
        return S_OK;
    }
    *ppv = nullptr;
    return E_NOINTERFACE;
}

STDMETHODIMP_(ULONG) EnumPins::AddRef() {
    return InterlockedIncrement(&refCount_);
}

STDMETHODIMP_(ULONG) EnumPins::Release() {
    ULONG ref = InterlockedDecrement(&refCount_);
    if (ref == 0) delete this;
    return ref;
}

STDMETHODIMP EnumPins::Next(ULONG cPins, IPin** ppPins, ULONG* pcFetched) {
    if (!ppPins) return E_POINTER;
    ULONG fetched = 0;
    while (fetched < cPins && pos_ == 0) {
        ppPins[fetched] = pin_;
        pin_->AddRef();
        ++fetched;
        ++pos_;
    }
    if (pcFetched) *pcFetched = fetched;
    return (fetched == cPins) ? S_OK : S_FALSE;
}

STDMETHODIMP EnumPins::Skip(ULONG cPins) {
    pos_ += cPins;
    return (pos_ <= 1) ? S_OK : S_FALSE;
}

STDMETHODIMP EnumPins::Reset() {
    pos_ = 0;
    return S_OK;
}

STDMETHODIMP EnumPins::Clone(IEnumPins** ppEnum) {
    if (!ppEnum) return E_POINTER;
    *ppEnum = new (std::nothrow) ::EnumPins(pin_, pos_);
    return (*ppEnum) ? S_OK : E_OUTOFMEMORY;
}

// ===========================================================================
// VirtualCamFilter implementation
// ===========================================================================

VirtualCamFilter::VirtualCamFilter() {
    InitializeCriticalSection(&cs_);
    pin_ = new (std::nothrow) VirtualCamPin(this);
}

VirtualCamFilter::~VirtualCamFilter() {
    if (pin_) {
        pin_->Release();
        pin_ = nullptr;
    }
    if (clock_) {
        clock_->Release();
        clock_ = nullptr;
    }
    DeleteCriticalSection(&cs_);
}

// ---------------------------------------------------------------------------
// IUnknown
// ---------------------------------------------------------------------------

STDMETHODIMP VirtualCamFilter::QueryInterface(REFIID riid, void** ppv) {
    if (!ppv) return E_POINTER;

    if (riid == IID_IUnknown) {
        *ppv = static_cast<IBaseFilter*>(this);
    } else if (riid == IID_IPersist) {
        *ppv = static_cast<IPersist*>(this);
    } else if (riid == IID_IMediaFilter) {
        *ppv = static_cast<IMediaFilter*>(this);
    } else if (riid == IID_IBaseFilter) {
        *ppv = static_cast<IBaseFilter*>(this);
    } else if (riid == IID_IAMFilterMiscFlags) {
        *ppv = static_cast<IAMFilterMiscFlags*>(this);
    } else {
        *ppv = nullptr;
        return E_NOINTERFACE;
    }
    AddRef();
    return S_OK;
}

STDMETHODIMP_(ULONG) VirtualCamFilter::AddRef() {
    return InterlockedIncrement(&refCount_);
}

STDMETHODIMP_(ULONG) VirtualCamFilter::Release() {
    ULONG ref = InterlockedDecrement(&refCount_);
    if (ref == 0) delete this;
    return ref;
}

// ---------------------------------------------------------------------------
// IPersist
// ---------------------------------------------------------------------------

STDMETHODIMP VirtualCamFilter::GetClassID(CLSID* pClassID) {
    if (!pClassID) return E_POINTER;
    *pClassID = CLSID_AcbVirtualCam;
    return S_OK;
}

// ---------------------------------------------------------------------------
// IMediaFilter
// ---------------------------------------------------------------------------

STDMETHODIMP VirtualCamFilter::Stop() {
    EnterCriticalSection(&cs_);
    if (state_ != State_Stopped) {
        state_ = State_Stopped;
        LeaveCriticalSection(&cs_);
        pin_->StopThread();
    } else {
        LeaveCriticalSection(&cs_);
    }
    return S_OK;
}

STDMETHODIMP VirtualCamFilter::Pause() {
    EnterCriticalSection(&cs_);
    if (state_ == State_Stopped) {
        pin_->StartThread();
    }
    state_ = State_Paused;
    LeaveCriticalSection(&cs_);
    return S_OK;
}

STDMETHODIMP VirtualCamFilter::Run(REFERENCE_TIME tStart) {
    EnterCriticalSection(&cs_);
    startTime_ = tStart;
    if (state_ == State_Stopped) {
        pin_->StartThread();
    }
    state_ = State_Running;
    LeaveCriticalSection(&cs_);
    return S_OK;
}

STDMETHODIMP VirtualCamFilter::GetState(DWORD /*dwMilliSecsTimeout*/, FILTER_STATE* State) {
    if (!State) return E_POINTER;
    *State = state_;
    return S_OK;
}

STDMETHODIMP VirtualCamFilter::SetSyncSource(IReferenceClock* pClock) {
    EnterCriticalSection(&cs_);
    if (clock_) clock_->Release();
    clock_ = pClock;
    if (clock_) clock_->AddRef();
    LeaveCriticalSection(&cs_);
    return S_OK;
}

STDMETHODIMP VirtualCamFilter::GetSyncSource(IReferenceClock** pClock) {
    if (!pClock) return E_POINTER;
    EnterCriticalSection(&cs_);
    *pClock = clock_;
    if (clock_) clock_->AddRef();
    LeaveCriticalSection(&cs_);
    return S_OK;
}

// ---------------------------------------------------------------------------
// IBaseFilter
// ---------------------------------------------------------------------------

STDMETHODIMP VirtualCamFilter::EnumPins(IEnumPins** ppEnum) {
    if (!ppEnum) return E_POINTER;
    *ppEnum = new (std::nothrow) ::EnumPins(static_cast<IPin*>(pin_));
    return (*ppEnum) ? S_OK : E_OUTOFMEMORY;
}

STDMETHODIMP VirtualCamFilter::FindPin(LPCWSTR Id, IPin** ppPin) {
    if (!ppPin) return E_POINTER;
    if (!Id) return E_POINTER;
    if (wcscmp(Id, L"Output") == 0 || wcscmp(Id, L"Capture") == 0) {
        *ppPin = static_cast<IPin*>(pin_);
        pin_->AddRef();
        return S_OK;
    }
    *ppPin = nullptr;
    return VFW_E_NOT_FOUND;
}

STDMETHODIMP VirtualCamFilter::QueryFilterInfo(FILTER_INFO* pInfo) {
    if (!pInfo) return E_POINTER;
    wcscpy_s(pInfo->achName, MAX_FILTER_CHARS, L"Android Cam Bridge");
    pInfo->pGraph = graph_;
    if (graph_) graph_->AddRef();
    return S_OK;
}

STDMETHODIMP VirtualCamFilter::JoinFilterGraph(IFilterGraph* pGraph, LPCWSTR /*pName*/) {
    EnterCriticalSection(&cs_);
    // Do NOT AddRef -- prevent circular reference
    graph_ = pGraph;
    LeaveCriticalSection(&cs_);
    return S_OK;
}

STDMETHODIMP VirtualCamFilter::QueryVendorInfo(LPWSTR* pVendorInfo) {
    if (!pVendorInfo) return E_POINTER;
    const wchar_t* vendor = L"Android Cam Bridge";
    size_t len = wcslen(vendor) + 1;
    *pVendorInfo = static_cast<LPWSTR>(CoTaskMemAlloc(len * sizeof(wchar_t)));
    if (!*pVendorInfo) return E_OUTOFMEMORY;
    wcscpy_s(*pVendorInfo, len, vendor);
    return S_OK;
}

// ---------------------------------------------------------------------------
// IAMFilterMiscFlags
// ---------------------------------------------------------------------------

STDMETHODIMP_(ULONG) VirtualCamFilter::GetMiscFlags() {
    return AM_FILTER_MISC_FLAGS_IS_SOURCE;
}
