/**
 * nucleus_launcher_windows.cpp
 *
 * JNI bridge for Windows Launcher APIs:
 * - Badge Notifications via WinRT/WRL (BadgeUpdateManager)
 * - Jump Lists via COM (ICustomDestinationList)
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
#include <propkey.h>
#include <objectarray.h>
#include <strsafe.h>

#include <wrl/implements.h>
#include <wrl/event.h>
#include <windows.ui.notifications.h>
#include <windows.data.xml.dom.h>

#include <jni.h>

#include <string>
#include <vector>
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
#pragma comment(lib, "uuid.lib")

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

// Jump List state
static std::mutex g_jl_mutex;
static ComPtr<ICustomDestinationList> g_jumpList;


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

static std::vector<std::wstring> toWStringArray(JNIEnv *env, jobjectArray jArray) {
    std::vector<std::wstring> result;
    if (!jArray) return result;
    jsize len = env->GetArrayLength(jArray);
    result.reserve(len);
    for (jsize i = 0; i < len; i++) {
        jstring jstr = (jstring)env->GetObjectArrayElement(jArray, i);
        result.push_back(toWString(env, jstr));
        if (jstr) env->DeleteLocalRef(jstr);
    }
    return result;
}

static std::vector<int> toIntVector(JNIEnv *env, jintArray jArray) {
    std::vector<int> result;
    if (!jArray) return result;
    jsize len = env->GetArrayLength(jArray);
    result.resize(len);
    env->GetIntArrayRegion(jArray, 0, len, (jint*)result.data());
    return result;
}

static std::vector<bool> toBoolVector(JNIEnv *env, jbooleanArray jArray) {
    std::vector<bool> result;
    if (!jArray) return result;
    jsize len = env->GetArrayLength(jArray);
    jboolean *raw = env->GetBooleanArrayElements(jArray, nullptr);
    result.assign(raw, raw + len);
    env->ReleaseBooleanArrayElements(jArray, raw, JNI_ABORT);
    return result;
}

// ============================================================================
// Jump List helpers
// ============================================================================

static HRESULT createShellLinkItem(
    const std::wstring &title,
    const std::wstring &arguments,
    const std::wstring &description,
    const std::wstring &iconPath,
    int iconIndex,
    IShellLinkW **ppLink
) {
    WCHAR exePath[MAX_PATH]{};
    GetModuleFileNameW(nullptr, exePath, MAX_PATH);

    ComPtr<IShellLinkW> link;
    HRESULT hr = CoCreateInstance(CLSID_ShellLink, nullptr, CLSCTX_INPROC_SERVER,
        IID_PPV_ARGS(&link));
    if (FAILED(hr)) return hr;

    hr = link->SetPath(exePath);
    if (FAILED(hr)) return hr;

    hr = link->SetArguments(arguments.c_str());
    if (FAILED(hr)) return hr;

    if (!description.empty()) {
        hr = link->SetDescription(description.c_str());
        if (FAILED(hr)) return hr;
    }

    if (!iconPath.empty()) {
        hr = link->SetIconLocation(iconPath.c_str(), iconIndex);
    } else {
        hr = link->SetIconLocation(exePath, iconIndex);
    }
    if (FAILED(hr)) return hr;

    ComPtr<IPropertyStore> propStore;
    hr = link.As(&propStore);
    if (FAILED(hr)) return hr;

    PROPVARIANT pv;
    hr = InitPropVariantFromString(title.c_str(), &pv);
    if (FAILED(hr)) return hr;
    hr = propStore->SetValue(PKEY_Title, pv);
    PropVariantClear(&pv);
    if (FAILED(hr)) return hr;

    hr = propStore->Commit();
    if (FAILED(hr)) return hr;

    *ppLink = link.Detach();
    return S_OK;
}

static HRESULT createSeparatorLink(IShellLinkW **ppLink) {
    ComPtr<IShellLinkW> link;
    HRESULT hr = CoCreateInstance(CLSID_ShellLink, nullptr, CLSCTX_INPROC_SERVER,
        IID_PPV_ARGS(&link));
    if (FAILED(hr)) return hr;

    ComPtr<IPropertyStore> propStore;
    hr = link.As(&propStore);
    if (FAILED(hr)) return hr;

    PROPVARIANT pv;
    hr = InitPropVariantFromBoolean(TRUE, &pv);
    if (FAILED(hr)) return hr;
    hr = propStore->SetValue(PKEY_AppUserModel_IsDestListSeparator, pv);
    PropVariantClear(&pv);
    if (FAILED(hr)) return hr;

    hr = propStore->Commit();
    if (FAILED(hr)) return hr;

    *ppLink = link.Detach();
    return S_OK;
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
Java_io_github_kdroidfilter_nucleus_launcher_windows_NativeWindowsBadgeBridge_nativeInitialize(
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
Java_io_github_kdroidfilter_nucleus_launcher_windows_NativeWindowsBadgeBridge_nativeSetBadgeNumber(
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
Java_io_github_kdroidfilter_nucleus_launcher_windows_NativeWindowsBadgeBridge_nativeSetBadgeGlyph(
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
Java_io_github_kdroidfilter_nucleus_launcher_windows_NativeWindowsBadgeBridge_nativeClearBadge(
    JNIEnv *env, jclass clazz
) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_initialized || !g_badgeUpdater) return env->NewStringUTF("Not initialized");

    g_badgeUpdater->Clear();
    return nullptr;
}

JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_nucleus_launcher_windows_NativeWindowsBadgeBridge_nativeUninitialize(
    JNIEnv *env, jclass clazz
) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_badgeUpdater.Reset();
    g_badgeManager.Reset();
    g_initialized = false;
}

// ============================================================================
// Jump List JNI exports
// ============================================================================

JNIEXPORT jstring JNICALL
Java_io_github_kdroidfilter_nucleus_launcher_windows_NativeWindowsJumpListBridge_nativeSetProcessAppId(
    JNIEnv *env, jclass clazz, jstring jAumid
) {
    std::wstring aumid = toWString(env, jAumid);
    if (aumid.empty()) return env->NewStringUTF("AUMID is empty");

    HRESULT hr = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    if (FAILED(hr) && hr != RPC_E_CHANGED_MODE && hr != S_FALSE) {
        return errorString(env, "CoInitializeEx failed", hr);
    }

    hr = SetCurrentProcessExplicitAppUserModelID(aumid.c_str());
    if (FAILED(hr)) return errorString(env, "SetCurrentProcessExplicitAppUserModelID failed", hr);

    // Create Start Menu shortcut with AUMID (required for unpackaged apps)
    std::wstring appName = aumid;
    size_t lastDot = appName.find_last_of(L'.');
    if (lastDot != std::wstring::npos && lastDot + 1 < appName.length()) {
        appName = appName.substr(lastDot + 1);
    }
    createShortcut(aumid, appName);

    return nullptr;
}

JNIEXPORT jstring JNICALL
Java_io_github_kdroidfilter_nucleus_launcher_windows_NativeWindowsJumpListBridge_nativeBeginList(
    JNIEnv *env, jclass clazz, jstring jAumid, jboolean jIsAppx
) {
    std::lock_guard<std::mutex> lock(g_jl_mutex);

    HRESULT hr = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    if (FAILED(hr) && hr != RPC_E_CHANGED_MODE && hr != S_FALSE) {
        return errorString(env, "CoInitializeEx failed", hr);
    }

    g_jumpList.Reset();
    hr = CoCreateInstance(CLSID_DestinationList, nullptr, CLSCTX_INPROC_SERVER,
        IID_PPV_ARGS(&g_jumpList));
    if (FAILED(hr) || !g_jumpList) {
        return errorString(env, "Failed to create ICustomDestinationList", hr);
    }

    std::wstring aumid = toWString(env, jAumid);
    bool isAppx = (bool)jIsAppx;

    if (!isAppx && !aumid.empty()) {
        SetCurrentProcessExplicitAppUserModelID(aumid.c_str());

        // Create Start Menu shortcut with AUMID (required for unpackaged apps)
        std::wstring appName = aumid;
        size_t lastDot = appName.find_last_of(L'.');
        if (lastDot != std::wstring::npos && lastDot + 1 < appName.length()) {
            appName = appName.substr(lastDot + 1);
        }
        createShortcut(aumid, appName);

        hr = g_jumpList->SetAppID(aumid.c_str());
        if (FAILED(hr)) {
            g_jumpList.Reset();
            return errorString(env, "SetAppID failed", hr);
        }
    }

    UINT minSlots = 0;
    ComPtr<IObjectArray> removedItems;
    hr = g_jumpList->BeginList(&minSlots, IID_PPV_ARGS(&removedItems));
    if (FAILED(hr)) {
        g_jumpList.Reset();
        return errorString(env, "BeginList failed", hr);
    }

    return nullptr;
}

JNIEXPORT jstring JNICALL
Java_io_github_kdroidfilter_nucleus_launcher_windows_NativeWindowsJumpListBridge_nativeAppendCategory(
    JNIEnv *env, jclass clazz, jstring jName,
    jobjectArray jTitles, jobjectArray jArguments, jobjectArray jDescriptions,
    jobjectArray jIconPaths, jintArray jIconIndices
) {
    std::lock_guard<std::mutex> lock(g_jl_mutex);
    if (!g_jumpList) return env->NewStringUTF("No active jump list session");

    std::wstring name = toWString(env, jName);
    auto titles = toWStringArray(env, jTitles);
    auto arguments = toWStringArray(env, jArguments);
    auto descriptions = toWStringArray(env, jDescriptions);
    auto iconPaths = toWStringArray(env, jIconPaths);
    auto iconIndices = toIntVector(env, jIconIndices);

    size_t count = titles.size();

    ComPtr<IObjectCollection> collection;
    HRESULT hr = CoCreateInstance(CLSID_EnumerableObjectCollection, nullptr,
        CLSCTX_INPROC_SERVER, IID_PPV_ARGS(&collection));
    if (FAILED(hr)) return errorString(env, "Failed to create IObjectCollection", hr);

    for (size_t i = 0; i < count; i++) {
        IShellLinkW *link = nullptr;
        hr = createShellLinkItem(
            titles[i],
            i < arguments.size() ? arguments[i] : L"",
            i < descriptions.size() ? descriptions[i] : L"",
            i < iconPaths.size() ? iconPaths[i] : L"",
            i < iconIndices.size() ? iconIndices[i] : 0,
            &link);
        if (FAILED(hr)) return errorString(env, "Failed to create shell link item", hr);
        collection->AddObject(link);
        link->Release();
    }

    ComPtr<IObjectArray> array;
    hr = collection.As(&array);
    if (FAILED(hr)) return errorString(env, "Failed to get IObjectArray", hr);

    hr = g_jumpList->AppendCategory(name.c_str(), array.Get());
    if (FAILED(hr)) return errorString(env, "AppendCategory failed", hr);

    return nullptr;
}

JNIEXPORT jstring JNICALL
Java_io_github_kdroidfilter_nucleus_launcher_windows_NativeWindowsJumpListBridge_nativeAppendKnownCategory(
    JNIEnv *env, jclass clazz, jint categoryId
) {
    std::lock_guard<std::mutex> lock(g_jl_mutex);
    if (!g_jumpList) return env->NewStringUTF("No active jump list session");

    HRESULT hr = g_jumpList->AppendKnownCategory((KNOWNDESTCATEGORY)categoryId);
    if (FAILED(hr)) return errorString(env, "AppendKnownCategory failed", hr);

    return nullptr;
}

JNIEXPORT jstring JNICALL
Java_io_github_kdroidfilter_nucleus_launcher_windows_NativeWindowsJumpListBridge_nativeAddUserTasks(
    JNIEnv *env, jclass clazz,
    jobjectArray jTitles, jobjectArray jArguments, jobjectArray jDescriptions,
    jobjectArray jIconPaths, jintArray jIconIndices, jbooleanArray jIsSeparator
) {
    std::lock_guard<std::mutex> lock(g_jl_mutex);
    if (!g_jumpList) return env->NewStringUTF("No active jump list session");

    auto titles = toWStringArray(env, jTitles);
    auto arguments = toWStringArray(env, jArguments);
    auto descriptions = toWStringArray(env, jDescriptions);
    auto iconPaths = toWStringArray(env, jIconPaths);
    auto iconIndices = toIntVector(env, jIconIndices);
    auto isSeparator = toBoolVector(env, jIsSeparator);

    size_t count = titles.size();

    ComPtr<IObjectCollection> collection;
    HRESULT hr = CoCreateInstance(CLSID_EnumerableObjectCollection, nullptr,
        CLSCTX_INPROC_SERVER, IID_PPV_ARGS(&collection));
    if (FAILED(hr)) return errorString(env, "Failed to create IObjectCollection", hr);

    for (size_t i = 0; i < count; i++) {
        IShellLinkW *link = nullptr;
        if (i < isSeparator.size() && isSeparator[i]) {
            hr = createSeparatorLink(&link);
        } else {
            hr = createShellLinkItem(
                titles[i],
                i < arguments.size() ? arguments[i] : L"",
                i < descriptions.size() ? descriptions[i] : L"",
                i < iconPaths.size() ? iconPaths[i] : L"",
                i < iconIndices.size() ? iconIndices[i] : 0,
                &link);
        }
        if (FAILED(hr)) return errorString(env, "Failed to create task item", hr);
        collection->AddObject(link);
        link->Release();
    }

    ComPtr<IObjectArray> array;
    hr = collection.As(&array);
    if (FAILED(hr)) return errorString(env, "Failed to get IObjectArray", hr);

    hr = g_jumpList->AddUserTasks(array.Get());
    if (FAILED(hr)) return errorString(env, "AddUserTasks failed", hr);

    return nullptr;
}

JNIEXPORT jstring JNICALL
Java_io_github_kdroidfilter_nucleus_launcher_windows_NativeWindowsJumpListBridge_nativeCommitList(
    JNIEnv *env, jclass clazz
) {
    std::lock_guard<std::mutex> lock(g_jl_mutex);
    if (!g_jumpList) return env->NewStringUTF("No active jump list session");

    HRESULT hr = g_jumpList->CommitList();
    g_jumpList.Reset();
    if (FAILED(hr)) return errorString(env, "CommitList failed", hr);

    return nullptr;
}

JNIEXPORT jstring JNICALL
Java_io_github_kdroidfilter_nucleus_launcher_windows_NativeWindowsJumpListBridge_nativeDeleteList(
    JNIEnv *env, jclass clazz, jstring jAumid, jboolean jIsAppx
) {
    std::lock_guard<std::mutex> lock(g_jl_mutex);

    HRESULT hr = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    if (FAILED(hr) && hr != RPC_E_CHANGED_MODE && hr != S_FALSE) {
        return errorString(env, "CoInitializeEx failed", hr);
    }

    ComPtr<ICustomDestinationList> destList;
    hr = CoCreateInstance(CLSID_DestinationList, nullptr, CLSCTX_INPROC_SERVER,
        IID_PPV_ARGS(&destList));
    if (FAILED(hr)) return errorString(env, "Failed to create ICustomDestinationList", hr);

    std::wstring aumid = toWString(env, jAumid);
    bool isAppx = (bool)jIsAppx;

    if (!isAppx && !aumid.empty()) {
        hr = destList->SetAppID(aumid.c_str());
        if (FAILED(hr)) return errorString(env, "SetAppID failed", hr);
    }

    hr = destList->DeleteList(isAppx ? nullptr : aumid.c_str());
    if (FAILED(hr)) return errorString(env, "DeleteList failed", hr);

    return nullptr;
}

} // extern "C"

BOOL APIENTRY DllMain(HMODULE hModule, DWORD reason, LPVOID lpReserved) {
    if (reason == DLL_PROCESS_ATTACH) DisableThreadLibraryCalls(hModule);
    return TRUE;
}
