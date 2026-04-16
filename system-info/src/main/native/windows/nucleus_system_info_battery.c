// Battery information stub for Windows (not yet implemented).

#include "nucleus_system_info_common.h"

JNIEXPORT jboolean JNICALL
Java_io_github_kdroidfilter_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeBatteryPresent(
    JNIEnv *env, jclass clazz) {
    return JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_io_github_kdroidfilter_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeBatteryExternalConnected(
    JNIEnv *env, jclass clazz) {
    return JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_io_github_kdroidfilter_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeBatteryIsCharging(
    JNIEnv *env, jclass clazz) {
    return JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_io_github_kdroidfilter_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeBatteryFullyCharged(
    JNIEnv *env, jclass clazz) {
    return JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_io_github_kdroidfilter_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeBatteryCurrentCapacity(
    JNIEnv *env, jclass clazz) {
    return 0;
}

JNIEXPORT jint JNICALL
Java_io_github_kdroidfilter_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeBatteryMaxCapacity(
    JNIEnv *env, jclass clazz) {
    return 0;
}

JNIEXPORT jint JNICALL
Java_io_github_kdroidfilter_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeBatteryDesignCapacity(
    JNIEnv *env, jclass clazz) {
    return 0;
}

JNIEXPORT jint JNICALL
Java_io_github_kdroidfilter_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeBatteryCycleCount(
    JNIEnv *env, jclass clazz) {
    return 0;
}

JNIEXPORT jint JNICALL
Java_io_github_kdroidfilter_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeBatteryVoltage(
    JNIEnv *env, jclass clazz) {
    return 0;
}

JNIEXPORT jint JNICALL
Java_io_github_kdroidfilter_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeBatteryAmperage(
    JNIEnv *env, jclass clazz) {
    return 0;
}

JNIEXPORT jfloat JNICALL
Java_io_github_kdroidfilter_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeBatteryTemperature(
    JNIEnv *env, jclass clazz) {
    return 0.0f;
}

JNIEXPORT jint JNICALL
Java_io_github_kdroidfilter_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeBatteryTimeRemaining(
    JNIEnv *env, jclass clazz) {
    return -1;
}

JNIEXPORT jstring JNICALL
Java_io_github_kdroidfilter_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeBatteryManufacturer(
    JNIEnv *env, jclass clazz) {
    return NULL;
}

JNIEXPORT jstring JNICALL
Java_io_github_kdroidfilter_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeBatteryModelName(
    JNIEnv *env, jclass clazz) {
    return NULL;
}

JNIEXPORT jstring JNICALL
Java_io_github_kdroidfilter_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeBatterySerialNumber(
    JNIEnv *env, jclass clazz) {
    return NULL;
}
