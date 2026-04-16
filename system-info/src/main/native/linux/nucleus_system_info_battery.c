// Battery information stub for Linux (not yet implemented).

#include "nucleus_system_info_common.h"

JNIEXPORT jboolean JNICALL
Java_io_github_kdroidfilter_nucleus_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeBatteryPresent(
    JNIEnv *env, jclass clazz) {
    return JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_io_github_kdroidfilter_nucleus_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeBatteryExternalConnected(
    JNIEnv *env, jclass clazz) {
    return JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_io_github_kdroidfilter_nucleus_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeBatteryIsCharging(
    JNIEnv *env, jclass clazz) {
    return JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_io_github_kdroidfilter_nucleus_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeBatteryFullyCharged(
    JNIEnv *env, jclass clazz) {
    return JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_io_github_kdroidfilter_nucleus_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeBatteryCurrentCapacity(
    JNIEnv *env, jclass clazz) {
    return 0;
}

JNIEXPORT jint JNICALL
Java_io_github_kdroidfilter_nucleus_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeBatteryMaxCapacity(
    JNIEnv *env, jclass clazz) {
    return 0;
}

JNIEXPORT jint JNICALL
Java_io_github_kdroidfilter_nucleus_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeBatteryDesignCapacity(
    JNIEnv *env, jclass clazz) {
    return 0;
}

JNIEXPORT jint JNICALL
Java_io_github_kdroidfilter_nucleus_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeBatteryCycleCount(
    JNIEnv *env, jclass clazz) {
    return 0;
}

JNIEXPORT jint JNICALL
Java_io_github_kdroidfilter_nucleus_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeBatteryVoltage(
    JNIEnv *env, jclass clazz) {
    return 0;
}

JNIEXPORT jint JNICALL
Java_io_github_kdroidfilter_nucleus_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeBatteryAmperage(
    JNIEnv *env, jclass clazz) {
    return 0;
}

JNIEXPORT jfloat JNICALL
Java_io_github_kdroidfilter_nucleus_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeBatteryTemperature(
    JNIEnv *env, jclass clazz) {
    return 0.0f;
}

JNIEXPORT jint JNICALL
Java_io_github_kdroidfilter_nucleus_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeBatteryTimeRemaining(
    JNIEnv *env, jclass clazz) {
    return -1;
}

JNIEXPORT jstring JNICALL
Java_io_github_kdroidfilter_nucleus_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeBatteryManufacturer(
    JNIEnv *env, jclass clazz) {
    return NULL;
}

JNIEXPORT jstring JNICALL
Java_io_github_kdroidfilter_nucleus_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeBatteryModelName(
    JNIEnv *env, jclass clazz) {
    return NULL;
}

JNIEXPORT jstring JNICALL
Java_io_github_kdroidfilter_nucleus_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeBatterySerialNumber(
    JNIEnv *env, jclass clazz) {
    return NULL;
}
