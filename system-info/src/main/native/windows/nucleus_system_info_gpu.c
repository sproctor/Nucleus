// GPU information via DXGI (DirectX Graphics Infrastructure).
// Sources: IDXGIFactory1, IDXGIAdapter1
// Driver version from registry (via DXGI LUID -> SetupAPI).

#include "nucleus_system_info_common.h"

// DXGI headers
#include <dxgi.h>
#include <initguid.h>
// IDXGIFactory1 GUID
DEFINE_GUID(IID_IDXGIFactory1_local,
    0x770aae78, 0xf26f, 0x4dba, 0xa8, 0x29, 0x25, 0x3c, 0x83, 0xd1, 0xb3, 0x87);

#define MAX_GPUS 16

typedef struct {
    char name[256];
    UINT vendor_id;
    UINT device_id;
    SIZE_T dedicated_video_memory;
    SIZE_T dedicated_system_memory;
    SIZE_T shared_system_memory;
    char driver_version[64];
} gpu_entry_t;

static gpu_entry_t g_gpus[MAX_GPUS];
static int g_gpu_count = 0;

// Try to read driver version from registry for a given adapter
static void read_driver_version(UINT vendor_id, UINT device_id, char *out, size_t out_size) {
    out[0] = '\0';

    // Search in HKLM\SYSTEM\CurrentControlSet\Control\Class\{4d36e968-e325-11ce-bfc1-08002be10318}\XXXX
    // This is the display adapter class GUID
    const wchar_t *class_key = L"SYSTEM\\CurrentControlSet\\Control\\Class\\{4d36e968-e325-11ce-bfc1-08002be10318}";
    HKEY hk;
    if (RegOpenKeyExW(HKEY_LOCAL_MACHINE, class_key, 0, KEY_READ, &hk) != ERROR_SUCCESS) return;

    wchar_t subkey_name[32];
    for (DWORD i = 0; i < 64; i++) {
        DWORD name_len = sizeof(subkey_name) / sizeof(subkey_name[0]);
        if (RegEnumKeyExW(hk, i, subkey_name, &name_len, NULL, NULL, NULL, NULL) != ERROR_SUCCESS) break;

        HKEY hk_sub;
        if (RegOpenKeyExW(hk, subkey_name, 0, KEY_READ, &hk_sub) != ERROR_SUCCESS) continue;

        // Check MatchingDeviceId to match vendor and device
        wchar_t match_buf[256];
        DWORD match_size = sizeof(match_buf);
        DWORD type;
        if (RegQueryValueExW(hk_sub, L"MatchingDeviceId", NULL, &type, (LPBYTE)match_buf, &match_size) == ERROR_SUCCESS
            && type == REG_SZ) {
            // Format: pci\ven_XXXX&dev_XXXX...
            char match_utf8[256];
            WideCharToMultiByte(CP_UTF8, 0, match_buf, -1, match_utf8, sizeof(match_utf8), NULL, NULL);
            _strlwr(match_utf8);

            char ven_str[16], dev_str[16];
            snprintf(ven_str, sizeof(ven_str), "ven_%04x", vendor_id);
            snprintf(dev_str, sizeof(dev_str), "dev_%04x", device_id);

            if (strstr(match_utf8, ven_str) && strstr(match_utf8, dev_str)) {
                // Found matching adapter — read DriverVersion
                wchar_t ver_buf[128];
                DWORD ver_size = sizeof(ver_buf);
                if (RegQueryValueExW(hk_sub, L"DriverVersion", NULL, &type, (LPBYTE)ver_buf, &ver_size) == ERROR_SUCCESS
                    && type == REG_SZ) {
                    WideCharToMultiByte(CP_UTF8, 0, ver_buf, -1, out, (int)out_size, NULL, NULL);
                }
                RegCloseKey(hk_sub);
                break;
            }
        }
        RegCloseKey(hk_sub);
    }
    RegCloseKey(hk);
}

static void refresh_gpus(void) {
    g_gpu_count = 0;

    // Create DXGI factory
    IDXGIFactory1 *factory = NULL;
    HRESULT hr = CreateDXGIFactory1(&IID_IDXGIFactory1_local, (void **)&factory);
    if (FAILED(hr) || !factory) return;

    IDXGIAdapter1 *adapter = NULL;
    for (UINT i = 0; i < MAX_GPUS; i++) {
        hr = factory->lpVtbl->EnumAdapters1(factory, i, &adapter);
        if (hr == DXGI_ERROR_NOT_FOUND || FAILED(hr)) break;

        DXGI_ADAPTER_DESC1 desc;
        hr = adapter->lpVtbl->GetDesc1(adapter, &desc);
        adapter->lpVtbl->Release(adapter);
        if (FAILED(hr)) continue;

        // Skip software adapters (Microsoft Basic Render Driver)
        if (desc.Flags & DXGI_ADAPTER_FLAG_SOFTWARE) continue;

        gpu_entry_t *g = &g_gpus[g_gpu_count];
        memset(g, 0, sizeof(*g));

        // Name
        char *name = wchar_to_utf8(desc.Description);
        if (name) {
            strncpy(g->name, name, sizeof(g->name) - 1);
            free(name);
        }

        g->vendor_id = desc.VendorId;
        g->device_id = desc.DeviceId;
        g->dedicated_video_memory = desc.DedicatedVideoMemory;
        g->dedicated_system_memory = desc.DedicatedSystemMemory;
        g->shared_system_memory = desc.SharedSystemMemory;

        // Driver version from registry
        read_driver_version(desc.VendorId, desc.DeviceId, g->driver_version, sizeof(g->driver_version));

        g_gpu_count++;
    }

    factory->lpVtbl->Release(factory);
}

// --- JNI exports ---

JNIEXPORT jint JNICALL
Java_io_github_kdroidfilter_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeGpuCount(
    JNIEnv *env, jclass clazz) {
    refresh_gpus();
    return g_gpu_count;
}

JNIEXPORT jobjectArray JNICALL
Java_io_github_kdroidfilter_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeGpuNames(
    JNIEnv *env, jclass clazz) {
    const char *names[MAX_GPUS];
    for (int i = 0; i < g_gpu_count; i++) names[i] = g_gpus[i].name;
    return to_string_array(env, names, g_gpu_count);
}

JNIEXPORT jlongArray JNICALL
Java_io_github_kdroidfilter_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeGpuVendorIds(
    JNIEnv *env, jclass clazz) {
    jlongArray arr = (*env)->NewLongArray(env, g_gpu_count);
    jlong *vals = (jlong *)malloc(g_gpu_count * sizeof(jlong));
    if (!vals) return arr;
    for (int i = 0; i < g_gpu_count; i++) vals[i] = (jlong)g_gpus[i].vendor_id;
    (*env)->SetLongArrayRegion(env, arr, 0, g_gpu_count, vals);
    free(vals);
    return arr;
}

JNIEXPORT jlongArray JNICALL
Java_io_github_kdroidfilter_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeGpuDeviceIds(
    JNIEnv *env, jclass clazz) {
    jlongArray arr = (*env)->NewLongArray(env, g_gpu_count);
    jlong *vals = (jlong *)malloc(g_gpu_count * sizeof(jlong));
    if (!vals) return arr;
    for (int i = 0; i < g_gpu_count; i++) vals[i] = (jlong)g_gpus[i].device_id;
    (*env)->SetLongArrayRegion(env, arr, 0, g_gpu_count, vals);
    free(vals);
    return arr;
}

JNIEXPORT jlongArray JNICALL
Java_io_github_kdroidfilter_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeGpuDedicatedVideoMemories(
    JNIEnv *env, jclass clazz) {
    jlongArray arr = (*env)->NewLongArray(env, g_gpu_count);
    jlong *vals = (jlong *)malloc(g_gpu_count * sizeof(jlong));
    if (!vals) return arr;
    for (int i = 0; i < g_gpu_count; i++) vals[i] = (jlong)g_gpus[i].dedicated_video_memory;
    (*env)->SetLongArrayRegion(env, arr, 0, g_gpu_count, vals);
    free(vals);
    return arr;
}

JNIEXPORT jlongArray JNICALL
Java_io_github_kdroidfilter_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeGpuDedicatedSystemMemories(
    JNIEnv *env, jclass clazz) {
    jlongArray arr = (*env)->NewLongArray(env, g_gpu_count);
    jlong *vals = (jlong *)malloc(g_gpu_count * sizeof(jlong));
    if (!vals) return arr;
    for (int i = 0; i < g_gpu_count; i++) vals[i] = (jlong)g_gpus[i].dedicated_system_memory;
    (*env)->SetLongArrayRegion(env, arr, 0, g_gpu_count, vals);
    free(vals);
    return arr;
}

JNIEXPORT jlongArray JNICALL
Java_io_github_kdroidfilter_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeGpuSharedSystemMemories(
    JNIEnv *env, jclass clazz) {
    jlongArray arr = (*env)->NewLongArray(env, g_gpu_count);
    jlong *vals = (jlong *)malloc(g_gpu_count * sizeof(jlong));
    if (!vals) return arr;
    for (int i = 0; i < g_gpu_count; i++) vals[i] = (jlong)g_gpus[i].shared_system_memory;
    (*env)->SetLongArrayRegion(env, arr, 0, g_gpu_count, vals);
    free(vals);
    return arr;
}

JNIEXPORT jobjectArray JNICALL
Java_io_github_kdroidfilter_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeGpuDriverVersions(
    JNIEnv *env, jclass clazz) {
    const char *versions[MAX_GPUS];
    for (int i = 0; i < g_gpu_count; i++) versions[i] = g_gpus[i].driver_version;
    return to_string_array(env, versions, g_gpu_count);
}
