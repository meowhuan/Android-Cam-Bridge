#pragma once
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <strmif.h>
#include <uuids.h>

// Forward declarations
class VirtualCamPin;

// ---------------------------------------------------------------------------
// EnumPins -- enumerator for a single-pin filter
// ---------------------------------------------------------------------------
class EnumPins final : public IEnumPins {
public:
    EnumPins(IPin* pin, ULONG pos = 0);
    ~EnumPins();

    // IUnknown
    STDMETHODIMP QueryInterface(REFIID riid, void** ppv) override;
    STDMETHODIMP_(ULONG) AddRef() override;
    STDMETHODIMP_(ULONG) Release() override;

    // IEnumPins
    STDMETHODIMP Next(ULONG cPins, IPin** ppPins, ULONG* pcFetched) override;
    STDMETHODIMP Skip(ULONG cPins) override;
    STDMETHODIMP Reset() override;
    STDMETHODIMP Clone(IEnumPins** ppEnum) override;

private:
    volatile LONG refCount_ = 1;
    IPin* pin_;
    ULONG pos_;
};

// ---------------------------------------------------------------------------
// VirtualCamFilter -- DirectShow source filter
// ---------------------------------------------------------------------------
class VirtualCamFilter final : public IBaseFilter, public IAMFilterMiscFlags {
public:
    VirtualCamFilter();
    ~VirtualCamFilter();

    // IUnknown
    STDMETHODIMP QueryInterface(REFIID riid, void** ppv) override;
    STDMETHODIMP_(ULONG) AddRef() override;
    STDMETHODIMP_(ULONG) Release() override;

    // IPersist
    STDMETHODIMP GetClassID(CLSID* pClassID) override;

    // IMediaFilter
    STDMETHODIMP Stop() override;
    STDMETHODIMP Pause() override;
    STDMETHODIMP Run(REFERENCE_TIME tStart) override;
    STDMETHODIMP GetState(DWORD dwMilliSecsTimeout, FILTER_STATE* State) override;
    STDMETHODIMP SetSyncSource(IReferenceClock* pClock) override;
    STDMETHODIMP GetSyncSource(IReferenceClock** pClock) override;

    // IBaseFilter
    STDMETHODIMP EnumPins(IEnumPins** ppEnum) override;
    STDMETHODIMP FindPin(LPCWSTR Id, IPin** ppPin) override;
    STDMETHODIMP QueryFilterInfo(FILTER_INFO* pInfo) override;
    STDMETHODIMP JoinFilterGraph(IFilterGraph* pGraph, LPCWSTR pName) override;
    STDMETHODIMP QueryVendorInfo(LPWSTR* pVendorInfo) override;

    // IAMFilterMiscFlags
    STDMETHODIMP_(ULONG) GetMiscFlags() override;

    // Accessors for the pin
    FILTER_STATE GetFilterState() const { return state_; }
    IFilterGraph* GetGraph() {
        EnterCriticalSection(&cs_);
        IFilterGraph* g = graph_;
        LeaveCriticalSection(&cs_);
        return g;
    }
    IReferenceClock* GetClock() const { return clock_; }
    REFERENCE_TIME GetStartTime() const { return startTime_; }

private:
    volatile LONG refCount_ = 1;
    FILTER_STATE state_ = State_Stopped;
    IFilterGraph* graph_ = nullptr;
    IReferenceClock* clock_ = nullptr;
    REFERENCE_TIME startTime_ = 0;
    VirtualCamPin* pin_ = nullptr;
    CRITICAL_SECTION cs_;
};
