package io.github.kdroidfilter.nucleus.systeminfo.windows

import io.github.kdroidfilter.nucleus.systeminfo.PlatformSystemInfo
import io.github.kdroidfilter.nucleus.systeminfo.model.ComponentInfo
import io.github.kdroidfilter.nucleus.systeminfo.model.CpuGlobalInfo
import io.github.kdroidfilter.nucleus.systeminfo.model.DiskInfo
import io.github.kdroidfilter.nucleus.systeminfo.model.MemoryInfo
import io.github.kdroidfilter.nucleus.systeminfo.model.MotherboardInfo
import io.github.kdroidfilter.nucleus.systeminfo.model.NetworkInterfaceInfo
import io.github.kdroidfilter.nucleus.systeminfo.model.OsInfo
import io.github.kdroidfilter.nucleus.systeminfo.model.ProcessInfo
import io.github.kdroidfilter.nucleus.systeminfo.model.ProductInfo
import io.github.kdroidfilter.nucleus.systeminfo.model.UserInfo

@Suppress("TooManyFunctions")
internal object WindowsSystemInfo : PlatformSystemInfo {
    override fun isAvailable(): Boolean = false

    override fun osInfo(): OsInfo? = null

    override fun memoryInfo(): MemoryInfo? = null

    override fun cpuInfo(): CpuGlobalInfo? = null

    override fun disks(): List<DiskInfo> = emptyList()

    override fun components(): List<ComponentInfo> = emptyList()

    override fun networks(): List<NetworkInterfaceInfo> = emptyList()

    override fun users(): List<UserInfo> = emptyList()

    override fun motherboard(): MotherboardInfo? = null

    override fun product(): ProductInfo? = null

    override fun processes(): List<ProcessInfo> = emptyList()

    override fun process(pid: Long): ProcessInfo? = null
}
