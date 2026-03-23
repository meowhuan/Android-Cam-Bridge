#pragma once
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <strmif.h>
#include <amvideo.h>
#include <uuids.h>
#include <ks.h>
#include <ksmedia.h>
#include <ksproxy.h>

#include <vector>

#include "shared_frame.h"

class VirtualCamFilter;

// ---------------------------------------------------------------------------
// EnumMediaTypes -- enumerator for media types offered by the pin
// ---------------------------------------------------------------------------
class EnumMediaTypes final : public IEnumMediaTypes {
public:
    EnumMediaTypes(const AM_MEDIA_TYPE* types, int count, int pos = 0);
    ~EnumMediaTypes();

    STDMETHODIMP QueryInterface(REFIID riid, void** ppv) override;
    STDMETHODIMP_(ULONG) AddRef() override;
    STDMETHODIMP_(ULONG) Release() override;

    STDMETHODIMP Next(ULONG cMediaTypes, AM_MEDIA_TYPE** ppMediaTypes, ULONG* pcFetched) override;
    STDMETHODIMP Skip(ULONG cMediaTypes) override;
    STDMETHODIMP Reset() override;
    STDMETHODIMP Clone(IEnumMediaTypes** ppEnum) override;

    static AM_MEDIA_TYPE* CopyMediaType(const AM_MEDIA_TYPE* src);
    static void FreeMediaType(AM_MEDIA_TYPE* mt);

private:
    volatile LONG refCount_ = 1;
    AM_MEDIA_TYPE* types_;
    int count_;
    int pos_;
};

// ---------------------------------------------------------------------------
// VirtualCamPin -- output pin that delivers video frames
// ---------------------------------------------------------------------------
class VirtualCamPin final : public IPin, public IQualityControl,
                            public IAMStreamConfig, public IKsPropertySet,
                            public IMemAllocator {
public:
    explicit VirtualCamPin(VirtualCamFilter* filter);
    ~VirtualCamPin();

    // IUnknown
    STDMETHODIMP QueryInterface(REFIID riid, void** ppv) override;
    STDMETHODIMP_(ULONG) AddRef() override;
    STDMETHODIMP_(ULONG) Release() override;

    // IPin
    STDMETHODIMP Connect(IPin* pReceivePin, const AM_MEDIA_TYPE* pmt) override;
    STDMETHODIMP ReceiveConnection(IPin* pConnector, const AM_MEDIA_TYPE* pmt) override;
    STDMETHODIMP Disconnect() override;
    STDMETHODIMP ConnectedTo(IPin** pPin) override;
    STDMETHODIMP ConnectionMediaType(AM_MEDIA_TYPE* pmt) override;
    STDMETHODIMP QueryPinInfo(PIN_INFO* pInfo) override;
    STDMETHODIMP QueryDirection(PIN_DIRECTION* pPinDir) override;
    STDMETHODIMP QueryId(LPWSTR* Id) override;
    STDMETHODIMP QueryAccept(const AM_MEDIA_TYPE* pmt) override;
    STDMETHODIMP EnumMediaTypes(IEnumMediaTypes** ppEnum) override;
    STDMETHODIMP QueryInternalConnections(IPin** apPin, ULONG* nPin) override;
    STDMETHODIMP EndOfStream() override;
    STDMETHODIMP BeginFlush() override;
    STDMETHODIMP EndFlush() override;
    STDMETHODIMP NewSegment(REFERENCE_TIME tStart, REFERENCE_TIME tStop, double dRate) override;

    // IQualityControl
    STDMETHODIMP Notify(IBaseFilter* pSelf, Quality q) override;
    STDMETHODIMP SetSink(IQualityControl* piqc) override;

    // IAMStreamConfig
    STDMETHODIMP SetFormat(AM_MEDIA_TYPE* pmt) override;
    STDMETHODIMP GetFormat(AM_MEDIA_TYPE** ppmt) override;
    STDMETHODIMP GetNumberOfCapabilities(int* piCount, int* piSize) override;
    STDMETHODIMP GetStreamCaps(int iIndex, AM_MEDIA_TYPE** ppmt, BYTE* pSCC) override;

    // IKsPropertySet
    STDMETHODIMP Set(REFGUID guidPropSet, DWORD dwPropID, LPVOID pInstanceData,
                     DWORD cbInstanceData, LPVOID pPropData, DWORD cbPropData) override;
    STDMETHODIMP Get(REFGUID guidPropSet, DWORD dwPropID, LPVOID pInstanceData,
                     DWORD cbInstanceData, LPVOID pPropData, DWORD cbPropData,
                     DWORD* pcbReturned) override;
    STDMETHODIMP QuerySupported(REFGUID guidPropSet, DWORD dwPropID,
                                DWORD* pTypeSupport) override;

    // IMemAllocator (internal allocator)
    STDMETHODIMP SetProperties(ALLOCATOR_PROPERTIES* pRequest,
                               ALLOCATOR_PROPERTIES* pActual) override;
    STDMETHODIMP GetProperties(ALLOCATOR_PROPERTIES* pProps) override;
    STDMETHODIMP Commit() override;
    STDMETHODIMP Decommit() override;
    STDMETHODIMP GetBuffer(IMediaSample** ppBuffer, REFERENCE_TIME* pStartTime,
                           REFERENCE_TIME* pEndTime, DWORD dwFlags) override;
    STDMETHODIMP ReleaseBuffer(IMediaSample* pBuffer) override;

    // Thread control (called by filter)
    void StartThread();
    void StopThread();

private:
    void BuildMediaType(AM_MEDIA_TYPE* pmt, int width, int height);
    void GetPreferredResolution(int& width, int& height);
    static DWORD WINAPI ThreadEntry(LPVOID param);
    void ThreadProc();

    volatile LONG refCount_ = 1;
    VirtualCamFilter* filter_;
    IPin* connectedPin_ = nullptr;
    IMemInputPin* connectedInput_ = nullptr;
    AM_MEDIA_TYPE connectedMt_ = {};
    bool connected_ = false;

    // Frame delivery thread
    HANDLE thread_ = nullptr;
    volatile bool threadRunning_ = false;
    HANDLE stopEvent_ = nullptr;

    SharedFrameReader reader_;
    CRITICAL_SECTION cs_;

    // Internal allocator state
    ALLOCATOR_PROPERTIES allocProps_ = {};
    bool allocCommitted_ = false;

    // Current negotiated resolution
    int curWidth_ = 1280;
    int curHeight_ = 720;

    // Frame buffer for reading from shared memory
    std::vector<uint8_t> frameBuffer_;

    // Supported resolutions (width, height)
    static constexpr int kNumCaps = 4;
    static constexpr int kResolutions[kNumCaps][2] = {
        {640, 480},
        {1280, 720},
        {1920, 1080},
        {3840, 2160}
    };
};
