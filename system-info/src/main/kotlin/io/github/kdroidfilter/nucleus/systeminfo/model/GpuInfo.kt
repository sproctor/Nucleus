package io.github.kdroidfilter.nucleus.systeminfo.model

data class GpuInfo(
    val name: String,
    val vendorId: Long,
    val deviceId: Long,
    val dedicatedVideoMemory: Long,
    val dedicatedSystemMemory: Long,
    val sharedSystemMemory: Long,
    val driverVersion: String?,
)
