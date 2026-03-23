#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <initguid.h>
#include <objbase.h>
#include <strmif.h>
#include <uuids.h>
#include <ks.h>
#include <ksmedia.h>
#include <ksproxy.h>
#include <new>

#include "guids.h"
#include "virtualcam_filter.h"

// ---------------------------------------------------------------------------
// Global state
// ---------------------------------------------------------------------------
static HMODULE g_hModule = nullptr;
static volatile LONG g_refCount = 0;

// String form of our CLSID
static const wchar_t kClsidStr[] = L"{A1B2C3D4-E5F6-7890-ABCD-EF1234567890}";
static const wchar_t kFilterName[] = L"Android Cam Bridge";

void DllAddRef() { InterlockedIncrement(&g_refCount); }
void DllRelease() { InterlockedDecrement(&g_refCount); }

// ---------------------------------------------------------------------------
// Class factory
// ---------------------------------------------------------------------------
class ClassFactory final : public IClassFactory {
public:
    ClassFactory() { DllAddRef(); }
    ~ClassFactory() { DllRelease(); }

    // IUnknown
    STDMETHODIMP QueryInterface(REFIID riid, void** ppv) override {
        if (!ppv) return E_POINTER;
        if (riid == IID_IUnknown || riid == IID_IClassFactory) {
            *ppv = static_cast<IClassFactory*>(this);
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

    // IClassFactory
    STDMETHODIMP CreateInstance(IUnknown* pUnkOuter, REFIID riid, void** ppv) override {
        if (!ppv) return E_POINTER;
        *ppv = nullptr;
        if (pUnkOuter) return CLASS_E_NOAGGREGATION;

        auto* filter = new (std::nothrow) VirtualCamFilter();
        if (!filter) return E_OUTOFMEMORY;

        HRESULT hr = filter->QueryInterface(riid, ppv);
        filter->Release(); // QI added a ref; release our initial one
        return hr;
    }
    STDMETHODIMP LockServer(BOOL fLock) override {
        if (fLock) DllAddRef();
        else DllRelease();
        return S_OK;
    }

private:
    volatile LONG refCount_ = 1;
};

// ---------------------------------------------------------------------------
// DLL entry points
// ---------------------------------------------------------------------------

BOOL WINAPI DllMain(HINSTANCE hinstDLL, DWORD fdwReason, LPVOID) {
    if (fdwReason == DLL_PROCESS_ATTACH) {
        g_hModule = hinstDLL;
        DisableThreadLibraryCalls(hinstDLL);
    }
    return TRUE;
}

extern "C" {

HRESULT WINAPI DllGetClassObject(REFCLSID rclsid, REFIID riid, LPVOID* ppv) {
    if (!ppv) return E_POINTER;
    *ppv = nullptr;
    if (rclsid != CLSID_AcbVirtualCam)
        return CLASS_E_CLASSNOTAVAILABLE;

    auto* factory = new (std::nothrow) ClassFactory();
    if (!factory) return E_OUTOFMEMORY;

    HRESULT hr = factory->QueryInterface(riid, ppv);
    factory->Release();
    return hr;
}

HRESULT WINAPI DllCanUnloadNow() {
    return (g_refCount == 0) ? S_OK : S_FALSE;
}

// ---------------------------------------------------------------------------
// Registry helpers
// ---------------------------------------------------------------------------

static HRESULT SetRegistryValue(HKEY hKey, const wchar_t* subKey,
                                const wchar_t* valueName, const wchar_t* value) {
    HKEY key;
    LONG result = RegCreateKeyExW(hKey, subKey, 0, nullptr, 0, KEY_WRITE, nullptr, &key, nullptr);
    if (result != ERROR_SUCCESS) return HRESULT_FROM_WIN32(result);
    result = RegSetValueExW(key, valueName, 0, REG_SZ,
                            reinterpret_cast<const BYTE*>(value),
                            static_cast<DWORD>((wcslen(value) + 1) * sizeof(wchar_t)));
    RegCloseKey(key);
    return HRESULT_FROM_WIN32(result);
}

static void DeleteRegistryTree(HKEY hKey, const wchar_t* subKey) {
    RegDeleteTreeW(hKey, subKey);
    RegDeleteKeyW(hKey, subKey);
}

HRESULT WINAPI DllRegisterServer() {
    // Get the path to this DLL
    wchar_t dllPath[MAX_PATH];
    DWORD len = GetModuleFileNameW(g_hModule, dllPath, MAX_PATH);
    if (len == 0 || len >= MAX_PATH) return HRESULT_FROM_WIN32(GetLastError());

    // 1. Register COM server: HKCR\CLSID\{...}
    wchar_t clsidKey[128];
    swprintf_s(clsidKey, L"CLSID\\%s", kClsidStr);

    HRESULT hr = SetRegistryValue(HKEY_CLASSES_ROOT, clsidKey, nullptr, kFilterName);
    if (FAILED(hr)) return hr;

    wchar_t inprocKey[160];
    swprintf_s(inprocKey, L"%s\\InprocServer32", clsidKey);

    hr = SetRegistryValue(HKEY_CLASSES_ROOT, inprocKey, nullptr, dllPath);
    if (FAILED(hr)) return hr;

    hr = SetRegistryValue(HKEY_CLASSES_ROOT, inprocKey, L"ThreadingModel", L"Both");
    if (FAILED(hr)) return hr;

    // 2. Register in DirectShow VideoInputDeviceCategory using IFilterMapper2
    CoInitializeEx(nullptr, COINIT_MULTITHREADED);

    IFilterMapper2* mapper = nullptr;
    hr = CoCreateInstance(CLSID_FilterMapper2, nullptr, CLSCTX_INPROC_SERVER,
                          IID_IFilterMapper2, reinterpret_cast<void**>(&mapper));
    if (SUCCEEDED(hr)) {
        // Define the pin's media type for registration
        const REGPINTYPES pinTypes = {
            &MEDIATYPE_Video,
            &MEDIASUBTYPE_RGB32
        };

        // Define the output pin
        const REGFILTERPINS2 pins = {
            REG_PINFLAG_B_OUTPUT,  // dwFlags
            1,                      // cInstances
            1,                      // nMediaTypes
            &pinTypes,             // lpMediaType
            0,                      // nMediums
            nullptr,               // lpMedium
            &PIN_CATEGORY_CAPTURE  // clsPinCategory
        };

        // Define the filter registration info
        REGFILTER2 rf2;
        rf2.dwVersion = 2;
        rf2.dwMerit = MERIT_DO_NOT_USE; // Low merit -- user selects explicitly
        rf2.cPins2 = 1;
        rf2.rgPins2 = &pins;

        hr = mapper->RegisterFilter(
            CLSID_AcbVirtualCam,             // Filter CLSID
            kFilterName,                      // Filter name
            nullptr,                          // Device moniker (not needed)
            &CLSID_VideoInputDeviceCategory,  // Category
            kFilterName,                      // Instance name
            &rf2                              // Filter info
        );

        mapper->Release();
    }

    CoUninitialize();

    return hr;
}

HRESULT WINAPI DllUnregisterServer() {
    // 1. Unregister from DirectShow category using IFilterMapper2
    IFilterMapper2* mapper = nullptr;
    HRESULT hr = CoCreateInstance(CLSID_FilterMapper2, nullptr, CLSCTX_INPROC_SERVER,
                                  IID_IFilterMapper2, reinterpret_cast<void**>(&mapper));
    if (SUCCEEDED(hr)) {
        mapper->UnregisterFilter(&CLSID_VideoInputDeviceCategory, kFilterName,
                                  CLSID_AcbVirtualCam);
        mapper->Release();
    }

    // 2. Remove COM server registration
    wchar_t clsidKey[128];
    swprintf_s(clsidKey, L"CLSID\\%s", kClsidStr);
    DeleteRegistryTree(HKEY_CLASSES_ROOT, clsidKey);

    return S_OK;
}

} // extern "C"
