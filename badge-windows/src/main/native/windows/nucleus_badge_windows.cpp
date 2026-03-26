/**
 * nucleus_badge_windows.cpp
 *
 * JNI bridge for Windows Badge Notifications via WinRT/WRL.
 *
 * Badges display a numeric count (1-99+) or a status glyph icon on the app's
 * taskbar button and Start tile. Uses the BadgeUpdateManager WinRT API.
 *
 * Prerequisites: Windows 10 SDK, MSVC (C++17).
 */

#ifndef UNICODE
#define UNICODE
#endif
#ifndef _UNICODE
#define _UNICODE
#endif
#define WIN32_LEAN_AND_MEAN

#include <Windows.h>
#include <sdkddkver.h>
#include <ShObjIdl.h>
#include <ShlObj.h>
#include <Psapi.h>
#include <roapi.h>
#include <winstring.h>
#include <propvarutil.h>
#include <functiondiscoverykeys.h>
#include <strsafe.h>

#include <wrl/implements.h>
#include <wrl/event.h>
#include <windows.ui.notifications.h>
#include <windows.data.xml.dom.h>

#include <jni.h>

#include <string>
#include <mutex>
#include <cstdio>

#pragma comment(lib, "ole32.lib")
#pragma comment(lib, "runtimeobject.lib")
#pragma comment(lib, "kernel32.lib")
#pragma comment(lib, "shell32.lib")
#pragma comment(lib, "shlwapi.lib")
#pragma comment(lib, "user32.lib")
#pragma comment(lib, "advapi32.lib")
#pragma comment(lib, "propsys.lib")

using namespace Microsoft::WRL;
using namespace ABI::Windows::UI::Notifications;
using namespace ABI::Windows::Data::Xml::Dom;

// ============================================================================
// Globals
// ============================================================================

static JavaVM *g_jvm = nullptr;
static bool g_initialized = false;
static std::mutex g_mutex;
static std::wstring g_aumid;
static bool g_isAppx = false;

static ComPtr<IBadgeUpdateManagerStatics> g_badgeManager;
static ComPtr<IBadgeUpdater> g_badgeUpdater;

// ============================================================================
// HSTRING wrapper
// ============================================================================

class HStringWrapper {
    HSTRING _hstr = nullptr;
public:
    HStringWrapper(const wchar_t *str) {
        if (str) WindowsCreateString(str, (UINT32)wcslen(str), &_hstr);
    }
    HStringWrapper(const std::wstring &str) {
        WindowsCreateString(str.c_str(), (UINT32)str.length(), &_hstr);
    }
    ~HStringWrapper() { if (_hstr) WindowsDeleteString(_hstr); }
    HSTRING Get() const { return _hstr; }
    HStringWrapper(const HStringWrapper&) = delete;
    HStringWrapper& operator=(const HStringWrapper&) = delete;
};

// ============================================================================
// JNI helpers
// ============================================================================

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    g_jvm = vm;
    return JNI_VERSION_1_8;
}

static std::wstring toWString(JNIEnv *env, jstring jstr) {
    if (!jstr) return L"";
    const jchar *chars = env->GetStringChars(jstr, nullptr);
    jsize len = env->GetStringLength(jstr);
    std::wstring result((const wchar_t *)chars, len);
    env->ReleaseStringChars(jstr, chars);
    return result;
}

static jstring errorString(JNIEnv *env, const char *context, HRESULT hr) {
    char buf[256];
    snprintf(buf, sizeof(buf), "%s: HRESULT 0x%08lX", context, (unsigned long)hr);
    return env->NewStringUTF(buf);
}

// ============================================================================
// Shortcut creation for unpackaged apps
// ============================================================================

static HRESULT createShortcut(const std::wstring &aumid, const std::wstring &appName) {
    WCHAR exePath[MAX_PATH]{};
    GetModuleFileNameW(nullptr, exePath, MAX_PATH);

    WCHAR appDataPath[MAX_PATH]{};
    DWORD written = GetEnvironmentVariableW(L"APPDATA", appDataPath, MAX_PATH);
    if (written == 0) return E_FAIL;

    std::wstring lnkPath = std::wstring(appDataPath) +
        L"\\Microsoft\\Windows\\Start Menu\\Programs\\" + appName + L".lnk";

    ComPtr<IShellLinkW> shellLink;
    HRESULT hr = CoCreateInstance(CLSID_ShellLink, nullptr, CLSCTX_INPROC_SERVER,
        IID_PPV_ARGS(&shellLink));
    if (FAILED(hr)) return hr;

    ComPtr<IPersistFile> persistFile;
    hr = shellLink.As(&persistFile);
    if (FAILED(hr)) return hr;

    bool needsCreate = true;
    if (SUCCEEDED(persistFile->Load(lnkPath.c_str(), STGM_READWRITE))) {
        ComPtr<IPropertyStore> propStore;
        if (SUCCEEDED(shellLink.As(&propStore))) {
            PROPVARIANT pv;
            PropVariantInit(&pv);
            if (SUCCEEDED(propStore->GetValue(PKEY_AppUserModel_ID, &pv))) {
                if (pv.vt == VT_LPWSTR && pv.pwszVal && aumid == pv.pwszVal) {
                    needsCreate = false;
                }
                PropVariantClear(&pv);
            }
        }
    }

    if (!needsCreate) return S_OK;

    hr = shellLink->SetPath(exePath);
    if (FAILED(hr)) return hr;
    hr = shellLink->SetArguments(L"");
    if (FAILED(hr)) return hr;

    std::wstring exeDir(exePath);
    size_t lastSlash = exeDir.find_last_of(L'\\');
    if (lastSlash != std::wstring::npos) exeDir = exeDir.substr(0, lastSlash);
    hr = shellLink->SetWorkingDirectory(exeDir.c_str());
    if (FAILED(hr)) return hr;

    ComPtr<IPropertyStore> propStore;
    hr = shellLink.As(&propStore);
    if (FAILED(hr)) return hr;

    PROPVARIANT appIdPropVar;
    hr = InitPropVariantFromString(aumid.c_str(), &appIdPropVar);
    if (FAILED(hr)) return hr;

    hr = propStore->SetValue(PKEY_AppUserModel_ID, appIdPropVar);
    PropVariantClear(&appIdPropVar);
    if (FAILED(hr)) return hr;

    hr = propStore->Commit();
    if (FAILED(hr)) return hr;

    hr = persistFile->Save(lnkPath.c_str(), TRUE);
    return hr;
}

// ============================================================================
// Badge XML helper
// ============================================================================

static HRESULT setBadgeXml(const std::wstring &value) {
    if (!g_badgeUpdater) return E_NOT_VALID_STATE;

    std::wstring xml = L"<badge value=\"" + value + L"\"/>";

    // Create XmlDocument and load XML
    ComPtr<IXmlDocument> xmlDoc;
    HRESULT hr = RoActivateInstance(
        HStringWrapper(RuntimeClass_Windows_Data_Xml_Dom_XmlDocument).Get(),
        reinterpret_cast<IInspectable**>(xmlDoc.GetAddressOf()));
    if (FAILED(hr)) return hr;

    ComPtr<IXmlDocumentIO> xmlDocIO;
    hr = xmlDoc.As(&xmlDocIO);
    if (FAILED(hr)) return hr;

    hr = xmlDocIO->LoadXml(HStringWrapper(xml).Get());
    if (FAILED(hr)) return hr;

    // Create BadgeNotification
    ComPtr<IBadgeNotificationFactory> factory;
    hr = RoGetActivationFactory(
        HStringWrapper(RuntimeClass_Windows_UI_Notifications_BadgeNotification).Get(),
        IID_PPV_ARGS(&factory));
    if (FAILED(hr)) return hr;

    ComPtr<IBadgeNotification> badge;
    hr = factory->CreateBadgeNotification(xmlDoc.Get(), &badge);
    if (FAILED(hr)) return hr;

    // Update badge
    hr = g_badgeUpdater->Update(badge.Get());
    return hr;
}

// ============================================================================
// JNI exports
// ============================================================================

extern "C" {

JNIEXPORT jstring JNICALL
Java_io_github_kdroidfilter_nucleus_badge_windows_NativeWindowsBadgeBridge_nativeInitialize(
    JNIEnv *env, jclass clazz, jstring jAumid, jboolean jIsAppx
) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_initialized) return nullptr; // already initialized = success

    HRESULT hr = RoInitialize(RO_INIT_MULTITHREADED);
    if (FAILED(hr) && hr != RPC_E_CHANGED_MODE && hr != S_FALSE) {
        return errorString(env, "RoInitialize failed", hr);
    }

    g_aumid = toWString(env, jAumid);
    g_isAppx = (bool)jIsAppx;

    if (!g_isAppx && !g_aumid.empty()) {
        SetCurrentProcessExplicitAppUserModelID(g_aumid.c_str());

        std::wstring appName = g_aumid;
        size_t lastDot = appName.find_last_of(L'.');
        if (lastDot != std::wstring::npos && lastDot + 1 < appName.length()) {
            appName = appName.substr(lastDot + 1);
        }
        createShortcut(g_aumid, appName);
    }

    // Get BadgeUpdateManager
    hr = RoGetActivationFactory(
        HStringWrapper(RuntimeClass_Windows_UI_Notifications_BadgeUpdateManager).Get(),
        IID_PPV_ARGS(&g_badgeManager));
    if (FAILED(hr) || !g_badgeManager) {
        return errorString(env, "Failed to get BadgeUpdateManager", hr);
    }

    // Create badge updater
    if (g_isAppx || g_aumid.empty()) {
        hr = g_badgeManager->CreateBadgeUpdaterForApplication(&g_badgeUpdater);
    } else {
        hr = g_badgeManager->CreateBadgeUpdaterForApplicationWithId(
            HStringWrapper(g_aumid).Get(), &g_badgeUpdater);
    }

    if (FAILED(hr) || !g_badgeUpdater) {
        g_badgeManager.Reset();
        return errorString(env, "Failed to create BadgeUpdater", hr);
    }

    g_initialized = true;
    return nullptr; // success
}

JNIEXPORT jstring JNICALL
Java_io_github_kdroidfilter_nucleus_badge_windows_NativeWindowsBadgeBridge_nativeSetBadgeNumber(
    JNIEnv *env, jclass clazz, jint value
) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_initialized) return env->NewStringUTF("Not initialized");

    std::wstring valStr = std::to_wstring(value);
    HRESULT hr = setBadgeXml(valStr);
    if (FAILED(hr)) return errorString(env, "setBadgeNumber failed", hr);
    return nullptr;
}

JNIEXPORT jstring JNICALL
Java_io_github_kdroidfilter_nucleus_badge_windows_NativeWindowsBadgeBridge_nativeSetBadgeGlyph(
    JNIEnv *env, jclass clazz, jstring jGlyph
) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_initialized) return env->NewStringUTF("Not initialized");

    std::wstring glyph = toWString(env, jGlyph);
    HRESULT hr = setBadgeXml(glyph);
    if (FAILED(hr)) return errorString(env, "setBadgeGlyph failed", hr);
    return nullptr;
}

JNIEXPORT jstring JNICALL
Java_io_github_kdroidfilter_nucleus_badge_windows_NativeWindowsBadgeBridge_nativeClearBadge(
    JNIEnv *env, jclass clazz
) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_initialized || !g_badgeUpdater) return env->NewStringUTF("Not initialized");

    g_badgeUpdater->Clear();
    return nullptr;
}

JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_nucleus_badge_windows_NativeWindowsBadgeBridge_nativeUninitialize(
    JNIEnv *env, jclass clazz
) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_badgeUpdater.Reset();
    g_badgeManager.Reset();
    g_initialized = false;
}

} // extern "C"

BOOL APIENTRY DllMain(HMODULE hModule, DWORD reason, LPVOID lpReserved) {
    if (reason == DLL_PROCESS_ATTACH) DisableThreadLibraryCalls(hModule);
    return TRUE;
}
