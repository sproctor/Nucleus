// CPU information: count, vendor, brand, frequency, usage.
// Sources: CPUID, GetNativeSystemInfo, GetSystemTimes, CallNtPowerInformation,
//          GetLogicalProcessorInformationEx

#include "nucleus_system_info_common.h"
#include <powerbase.h>
#include <intrin.h>

#define MAX_CPUS 1024

// Previous idle/kernel/user times for per-CPU usage calculation
static ULONGLONG g_prev_idle = 0, g_prev_kernel = 0, g_prev_user = 0;
static int g_cpu_init = 0;

static int get_logical_cpu_count(void) {
    SYSTEM_INFO si;
    GetNativeSystemInfo(&si);
    return (int)si.dwNumberOfProcessors;
}

// Get CPU vendor ID via CPUID (leaf 0)
static void get_cpu_vendor(char *vendor, size_t size) {
    int info[4];
    __cpuid(info, 0);
    // EBX, EDX, ECX order
    if (size >= 13) {
        memcpy(vendor, &info[1], 4);
        memcpy(vendor + 4, &info[3], 4);
        memcpy(vendor + 8, &info[2], 4);
        vendor[12] = '\0';
    }
}

// Get CPU brand string via CPUID (leaves 0x80000002-0x80000004)
static void get_cpu_brand(char *brand, size_t size) {
    int info[4];
    __cpuid(info, 0x80000000);
    if ((unsigned int)info[0] < 0x80000004) {
        brand[0] = '\0';
        return;
    }
    char buf[49];
    __cpuid((int *)(buf + 0), 0x80000002);
    __cpuid((int *)(buf + 16), 0x80000003);
    __cpuid((int *)(buf + 32), 0x80000004);
    buf[48] = '\0';
    // Trim leading spaces
    char *p = buf;
    while (*p == ' ') p++;
    strncpy(brand, p, size - 1);
    brand[size - 1] = '\0';
}

JNIEXPORT jfloat JNICALL
Java_io_github_kdroidfilter_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeGlobalCpuUsage(
    JNIEnv *env, jclass clazz) {
    FILETIME idle_ft, kernel_ft, user_ft;
    if (!GetSystemTimes(&idle_ft, &kernel_ft, &user_ft)) return 0.0f;

    ULARGE_INTEGER idle_u, kernel_u, user_u;
    idle_u.LowPart = idle_ft.dwLowDateTime;    idle_u.HighPart = idle_ft.dwHighDateTime;
    kernel_u.LowPart = kernel_ft.dwLowDateTime; kernel_u.HighPart = kernel_ft.dwHighDateTime;
    user_u.LowPart = user_ft.dwLowDateTime;    user_u.HighPart = user_ft.dwHighDateTime;

    if (!g_cpu_init) {
        g_prev_idle = idle_u.QuadPart;
        g_prev_kernel = kernel_u.QuadPart;
        g_prev_user = user_u.QuadPart;
        g_cpu_init = 1;
        return 0.0f;
    }

    ULONGLONG d_idle = idle_u.QuadPart - g_prev_idle;
    ULONGLONG d_kernel = kernel_u.QuadPart - g_prev_kernel;
    ULONGLONG d_user = user_u.QuadPart - g_prev_user;

    g_prev_idle = idle_u.QuadPart;
    g_prev_kernel = kernel_u.QuadPart;
    g_prev_user = user_u.QuadPart;

    // kernel time includes idle time
    ULONGLONG total = d_kernel + d_user;
    if (total == 0) return 0.0f;
    ULONGLONG active = total - d_idle;
    return (float)((double)active / (double)total * 100.0);
}

JNIEXPORT jint JNICALL
Java_io_github_kdroidfilter_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativePhysicalCoreCount(
    JNIEnv *env, jclass clazz) {
    DWORD len = 0;
    GetLogicalProcessorInformationEx(RelationProcessorCore, NULL, &len);
    if (GetLastError() != ERROR_INSUFFICIENT_BUFFER) return 0;

    BYTE *buf = (BYTE *)malloc(len);
    if (!buf) return 0;
    if (!GetLogicalProcessorInformationEx(RelationProcessorCore,
            (PSYSTEM_LOGICAL_PROCESSOR_INFORMATION_EX)buf, &len)) {
        free(buf);
        return 0;
    }

    int count = 0;
    DWORD offset = 0;
    while (offset < len) {
        PSYSTEM_LOGICAL_PROCESSOR_INFORMATION_EX info =
            (PSYSTEM_LOGICAL_PROCESSOR_INFORMATION_EX)(buf + offset);
        if (info->Relationship == RelationProcessorCore) count++;
        offset += info->Size;
    }
    free(buf);
    return count;
}

JNIEXPORT jint JNICALL
Java_io_github_kdroidfilter_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeCpuCount(
    JNIEnv *env, jclass clazz) {
    return get_logical_cpu_count();
}

JNIEXPORT jobjectArray JNICALL
Java_io_github_kdroidfilter_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeCpuNames(
    JNIEnv *env, jclass clazz) {
    int count = get_logical_cpu_count();
    const char **names = (const char **)malloc(count * sizeof(char *));
    if (!names) return NULL;
    char name[32];
    for (int i = 0; i < count; i++) {
        snprintf(name, sizeof(name), "cpu%d", i);
        names[i] = _strdup(name);
    }
    jobjectArray result = to_string_array(env, names, count);
    for (int i = 0; i < count; i++) free((void *)names[i]);
    free(names);
    return result;
}

JNIEXPORT jobjectArray JNICALL
Java_io_github_kdroidfilter_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeCpuVendorIds(
    JNIEnv *env, jclass clazz) {
    int count = get_logical_cpu_count();
    char vendor[16];
    get_cpu_vendor(vendor, sizeof(vendor));
    const char **vendors = (const char **)malloc(count * sizeof(char *));
    if (!vendors) return NULL;
    for (int i = 0; i < count; i++) vendors[i] = vendor;
    jobjectArray result = to_string_array(env, vendors, count);
    free(vendors);
    return result;
}

JNIEXPORT jobjectArray JNICALL
Java_io_github_kdroidfilter_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeCpuBrands(
    JNIEnv *env, jclass clazz) {
    int count = get_logical_cpu_count();
    char brand[64];
    get_cpu_brand(brand, sizeof(brand));
    const char **brands = (const char **)malloc(count * sizeof(char *));
    if (!brands) return NULL;
    for (int i = 0; i < count; i++) brands[i] = brand;
    jobjectArray result = to_string_array(env, brands, count);
    free(brands);
    return result;
}

// PROCESSOR_POWER_INFORMATION is not always in SDK headers
typedef struct {
    ULONG Number;
    ULONG MaxMhz;
    ULONG CurrentMhz;
    ULONG MhzLimit;
    ULONG MaxIdleState;
    ULONG CurrentIdleState;
} MY_PROCESSOR_POWER_INFORMATION;

JNIEXPORT jlongArray JNICALL
Java_io_github_kdroidfilter_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeCpuFrequencies(
    JNIEnv *env, jclass clazz) {
    int count = get_logical_cpu_count();
    DWORD buf_size = count * sizeof(MY_PROCESSOR_POWER_INFORMATION);
    MY_PROCESSOR_POWER_INFORMATION *ppi = (MY_PROCESSOR_POWER_INFORMATION *)malloc(buf_size);
    if (!ppi) return NULL;

    NTSTATUS status = CallNtPowerInformation(ProcessorInformation, NULL, 0, ppi, buf_size);
    jlongArray arr = (*env)->NewLongArray(env, count);
    if (status == 0) { // STATUS_SUCCESS
        jlong *freqs = (jlong *)malloc(count * sizeof(jlong));
        if (freqs) {
            for (int i = 0; i < count; i++) {
                freqs[i] = (jlong)ppi[i].CurrentMhz;
            }
            (*env)->SetLongArrayRegion(env, arr, 0, count, freqs);
            free(freqs);
        }
    }
    free(ppi);
    return arr;
}

JNIEXPORT jfloatArray JNICALL
Java_io_github_kdroidfilter_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeCpuUsages(
    JNIEnv *env, jclass clazz) {
    // Per-CPU usage via NtQuerySystemInformation is complex; return global for all for now.
    // This matches the global usage for each logical CPU as a reasonable approximation.
    int count = get_logical_cpu_count();
    float global = 0.0f;

    FILETIME idle_ft, kernel_ft, user_ft;
    if (GetSystemTimes(&idle_ft, &kernel_ft, &user_ft)) {
        ULARGE_INTEGER idle_u, kernel_u, user_u;
        idle_u.LowPart = idle_ft.dwLowDateTime;    idle_u.HighPart = idle_ft.dwHighDateTime;
        kernel_u.LowPart = kernel_ft.dwLowDateTime; kernel_u.HighPart = kernel_ft.dwHighDateTime;
        user_u.LowPart = user_ft.dwLowDateTime;    user_u.HighPart = user_ft.dwHighDateTime;

        if (g_cpu_init) {
            ULONGLONG d_idle = idle_u.QuadPart - g_prev_idle;
            ULONGLONG d_kernel = kernel_u.QuadPart - g_prev_kernel;
            ULONGLONG d_user = user_u.QuadPart - g_prev_user;
            ULONGLONG total = d_kernel + d_user;
            if (total > 0) {
                global = (float)((double)(total - d_idle) / (double)total * 100.0);
            }
        }
    }

    jfloatArray arr = (*env)->NewFloatArray(env, count);
    jfloat *usages = (jfloat *)malloc(count * sizeof(jfloat));
    if (usages) {
        for (int i = 0; i < count; i++) usages[i] = global;
        (*env)->SetFloatArrayRegion(env, arr, 0, count, usages);
        free(usages);
    }
    return arr;
}
