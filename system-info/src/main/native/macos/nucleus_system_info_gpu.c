// GPU information: name, vendor/device IDs, memory, driver version.
// Sources: IOKit IOAccelerator (AGXAccelerator on Apple Silicon, various on Intel)

#include "nucleus_system_info_common.h"
#include <IOKit/IOKitLib.h>
#include <CoreFoundation/CoreFoundation.h>

#define MAX_GPUS 8

typedef struct {
    char name[128];
    uint32_t vendor_id;
    uint32_t device_id;
    long long dedicated_video_memory;
    long long dedicated_system_memory;
    long long shared_system_memory;
    char driver_version[64];
} gpu_entry_t;

static int g_gpu_count = 0;
static gpu_entry_t g_gpus[MAX_GPUS];

// Read a uint32 from a little-endian CFData property (vendor-id, device-id)
static uint32_t read_le32_property(io_service_t service, CFStringRef key) {
    CFDataRef data = (CFDataRef)IORegistryEntryCreateCFProperty(
        service, key, kCFAllocatorDefault, 0);
    if (!data) return 0;
    uint32_t val = 0;
    if (CFDataGetLength(data) >= 4) {
        const uint8_t *bytes = CFDataGetBytePtr(data);
        val = (uint32_t)bytes[0] | ((uint32_t)bytes[1] << 8) |
              ((uint32_t)bytes[2] << 16) | ((uint32_t)bytes[3] << 24);
    }
    CFRelease(data);
    return val;
}

// Read a CFString property into a C buffer
static int read_string_property(io_service_t service, CFStringRef key, char *buf, size_t bufsize) {
    CFStringRef str = (CFStringRef)IORegistryEntryCreateCFProperty(
        service, key, kCFAllocatorDefault, 0);
    if (!str) return -1;
    if (CFGetTypeID(str) != CFStringGetTypeID()) { CFRelease(str); return -1; }
    Boolean ok = CFStringGetCString(str, buf, (CFIndex)bufsize, kCFStringEncodingUTF8);
    CFRelease(str);
    return ok ? 0 : -1;
}

// Read a CFNumber property as int64
static long long read_number_property(io_service_t service, CFStringRef key) {
    CFNumberRef num = (CFNumberRef)IORegistryEntryCreateCFProperty(
        service, key, kCFAllocatorDefault, 0);
    if (!num) return 0;
    if (CFGetTypeID(num) != CFNumberGetTypeID()) { CFRelease(num); return 0; }
    long long val = 0;
    CFNumberGetValue(num, kCFNumberSInt64Type, &val);
    CFRelease(num);
    return val;
}

// Read a nested number from PerformanceStatistics dictionary
static long long read_perf_stat(io_service_t service, const char *stat_key) {
    CFDictionaryRef stats = (CFDictionaryRef)IORegistryEntryCreateCFProperty(
        service, CFSTR("PerformanceStatistics"), kCFAllocatorDefault, 0);
    if (!stats) return 0;
    if (CFGetTypeID(stats) != CFDictionaryGetTypeID()) { CFRelease(stats); return 0; }
    CFStringRef key = CFStringCreateWithCString(kCFAllocatorDefault, stat_key,
                                                 kCFStringEncodingUTF8);
    long long val = 0;
    CFNumberRef num = (CFNumberRef)CFDictionaryGetValue(stats, key);
    if (num && CFGetTypeID(num) == CFNumberGetTypeID()) {
        CFNumberGetValue(num, kCFNumberSInt64Type, &val);
    }
    CFRelease(key);
    CFRelease(stats);
    return val;
}

// Walk up to find a property on a parent (e.g., device-id on IOPCIDevice parent)
static uint32_t find_parent_le32_property(io_service_t start, CFStringRef key) {
    io_service_t current = start;
    IOObjectRetain(current);
    int depth = 0;
    while (current && depth < 10) {
        CFDataRef data = (CFDataRef)IORegistryEntryCreateCFProperty(
            current, key, kCFAllocatorDefault, 0);
        if (data && CFGetTypeID(data) == CFDataGetTypeID() && CFDataGetLength(data) >= 4) {
            const uint8_t *bytes = CFDataGetBytePtr(data);
            uint32_t val = (uint32_t)bytes[0] | ((uint32_t)bytes[1] << 8) |
                           ((uint32_t)bytes[2] << 16) | ((uint32_t)bytes[3] << 24);
            CFRelease(data);
            IOObjectRelease(current);
            return val;
        }
        if (data) CFRelease(data);
        io_service_t parent = 0;
        kern_return_t kr = IORegistryEntryGetParentEntry(current, kIOServicePlane, &parent);
        IOObjectRelease(current);
        if (kr != KERN_SUCCESS) break;
        current = parent;
        depth++;
    }
    if (current) IOObjectRelease(current);
    return 0;
}

static void refresh_gpus(void) {
    g_gpu_count = 0;

    // Match IOAccelerator — this covers both AGXAccelerator (Apple Silicon)
    // and IntelAccelerator / AMD accelerators on Intel Macs
    CFMutableDictionaryRef matching = IOServiceMatching("IOAccelerator");
    if (!matching) return;

    io_iterator_t iter;
    if (IOServiceGetMatchingServices(kIOMainPortDefault, matching, &iter) != KERN_SUCCESS) {
        return;
    }

    io_service_t service;
    while ((service = IOIteratorNext(iter)) != 0 && g_gpu_count < MAX_GPUS) {
        gpu_entry_t *g = &g_gpus[g_gpu_count];
        memset(g, 0, sizeof(gpu_entry_t));

        // Name from "model" property
        if (read_string_property(service, CFSTR("model"), g->name, sizeof(g->name)) != 0) {
            // Fallback: use IOClass name
            read_string_property(service, CFSTR("IOClass"), g->name, sizeof(g->name));
        }
        if (g->name[0] == '\0') {
            IOObjectRelease(service);
            continue;
        }

        // Vendor ID (on the accelerator or parent PCI device)
        g->vendor_id = read_le32_property(service, CFSTR("vendor-id"));
        if (g->vendor_id == 0) {
            g->vendor_id = find_parent_le32_property(service, CFSTR("vendor-id"));
        }

        // Device ID (usually on parent IOPCIDevice for discrete GPUs)
        g->device_id = read_le32_property(service, CFSTR("device-id"));
        if (g->device_id == 0) {
            g->device_id = find_parent_le32_property(service, CFSTR("device-id"));
        }

        // Memory info from PerformanceStatistics
        // "Alloc system memory" = total allocated by the GPU driver
        // "In use system memory" = currently in use
        g->shared_system_memory = read_perf_stat(service, "Alloc system memory");

        // VRAM for discrete GPUs (Intel Macs with AMD/Nvidia)
        // Apple Silicon has no dedicated VRAM
        g->dedicated_video_memory = read_number_property(service, CFSTR("VRAM,totalMB"));
        if (g->dedicated_video_memory > 0) {
            g->dedicated_video_memory *= 1024 * 1024; // Convert MB to bytes
        }

        // Driver version from IOSourceVersion
        read_string_property(service, CFSTR("IOSourceVersion"), g->driver_version,
                             sizeof(g->driver_version));

        g_gpu_count++;
        IOObjectRelease(service);
    }
    IOObjectRelease(iter);
}

JNIEXPORT jint JNICALL
Java_io_github_kdroidfilter_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeGpuCount(
    JNIEnv *env, jclass clazz) {
    refresh_gpus();
    return (jint)g_gpu_count;
}

JNIEXPORT jobjectArray JNICALL
Java_io_github_kdroidfilter_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeGpuNames(
    JNIEnv *env, jclass clazz) {
    if (g_gpu_count <= 0) return NULL;
    const char **arr = (const char **)malloc(g_gpu_count * sizeof(char *));
    for (int i = 0; i < g_gpu_count; i++) arr[i] = g_gpus[i].name;
    jobjectArray result = to_string_array(env, arr, g_gpu_count);
    free(arr);
    return result;
}

JNIEXPORT jlongArray JNICALL
Java_io_github_kdroidfilter_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeGpuVendorIds(
    JNIEnv *env, jclass clazz) {
    if (g_gpu_count <= 0) return NULL;
    jlong *vals = (jlong *)malloc(g_gpu_count * sizeof(jlong));
    for (int i = 0; i < g_gpu_count; i++) vals[i] = (jlong)g_gpus[i].vendor_id;
    jlongArray result = (*env)->NewLongArray(env, g_gpu_count);
    (*env)->SetLongArrayRegion(env, result, 0, g_gpu_count, vals);
    free(vals);
    return result;
}

JNIEXPORT jlongArray JNICALL
Java_io_github_kdroidfilter_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeGpuDeviceIds(
    JNIEnv *env, jclass clazz) {
    if (g_gpu_count <= 0) return NULL;
    jlong *vals = (jlong *)malloc(g_gpu_count * sizeof(jlong));
    for (int i = 0; i < g_gpu_count; i++) vals[i] = (jlong)g_gpus[i].device_id;
    jlongArray result = (*env)->NewLongArray(env, g_gpu_count);
    (*env)->SetLongArrayRegion(env, result, 0, g_gpu_count, vals);
    free(vals);
    return result;
}

JNIEXPORT jlongArray JNICALL
Java_io_github_kdroidfilter_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeGpuDedicatedVideoMemories(
    JNIEnv *env, jclass clazz) {
    if (g_gpu_count <= 0) return NULL;
    jlong *vals = (jlong *)malloc(g_gpu_count * sizeof(jlong));
    for (int i = 0; i < g_gpu_count; i++) vals[i] = (jlong)g_gpus[i].dedicated_video_memory;
    jlongArray result = (*env)->NewLongArray(env, g_gpu_count);
    (*env)->SetLongArrayRegion(env, result, 0, g_gpu_count, vals);
    free(vals);
    return result;
}

JNIEXPORT jlongArray JNICALL
Java_io_github_kdroidfilter_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeGpuDedicatedSystemMemories(
    JNIEnv *env, jclass clazz) {
    if (g_gpu_count <= 0) return NULL;
    jlong *vals = (jlong *)malloc(g_gpu_count * sizeof(jlong));
    for (int i = 0; i < g_gpu_count; i++) vals[i] = (jlong)g_gpus[i].dedicated_system_memory;
    jlongArray result = (*env)->NewLongArray(env, g_gpu_count);
    (*env)->SetLongArrayRegion(env, result, 0, g_gpu_count, vals);
    free(vals);
    return result;
}

JNIEXPORT jlongArray JNICALL
Java_io_github_kdroidfilter_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeGpuSharedSystemMemories(
    JNIEnv *env, jclass clazz) {
    if (g_gpu_count <= 0) return NULL;
    jlong *vals = (jlong *)malloc(g_gpu_count * sizeof(jlong));
    for (int i = 0; i < g_gpu_count; i++) vals[i] = (jlong)g_gpus[i].shared_system_memory;
    jlongArray result = (*env)->NewLongArray(env, g_gpu_count);
    (*env)->SetLongArrayRegion(env, result, 0, g_gpu_count, vals);
    free(vals);
    return result;
}

JNIEXPORT jobjectArray JNICALL
Java_io_github_kdroidfilter_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeGpuDriverVersions(
    JNIEnv *env, jclass clazz) {
    if (g_gpu_count <= 0) return NULL;
    const char **arr = (const char **)malloc(g_gpu_count * sizeof(char *));
    for (int i = 0; i < g_gpu_count; i++) arr[i] = g_gpus[i].driver_version;
    jobjectArray result = to_string_array(env, arr, g_gpu_count);
    free(arr);
    return result;
}
