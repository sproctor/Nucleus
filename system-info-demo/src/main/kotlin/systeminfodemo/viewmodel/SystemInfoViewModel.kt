@file:Suppress("TooManyFunctions", "MagicNumber")

package systeminfodemo.viewmodel

import io.github.kdroidfilter.nucleus.systeminfo.SystemInfo
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

data class SystemInfoState(
    val osInfo: OsInfo? = null,
    val memoryInfo: MemoryInfo? = null,
    val cpuInfo: CpuGlobalInfo? = null,
    val disks: List<DiskInfo> = emptyList(),
    val components: List<ComponentInfo> = emptyList(),
    val networks: List<NetworkInterfaceInfo> = emptyList(),
    val users: List<UserInfo> = emptyList(),
    val motherboard: MotherboardInfo? = null,
    val product: ProductInfo? = null,
    val processes: List<ProcessInfo> = emptyList(),
    val cpuHistory: List<Float> = emptyList(),
    val memoryHistory: List<Float> = emptyList(),
)

private const val REFRESH_INTERVAL_MS = 2000L
private const val HISTORY_MAX_SIZE = 60

object SystemInfoViewModel {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(SystemInfoState())
    val state: StateFlow<SystemInfoState> = _state.asStateFlow()

    init {
        scope.launch { collectLoop() }
    }

    private suspend fun collectLoop() {
        while (true) {
            refresh()
            delay(REFRESH_INTERVAL_MS.milliseconds)
        }
    }

    private fun refresh() {
        val os = SystemInfo.osInfo()
        val mem = SystemInfo.memoryInfo()
        val cpu = SystemInfo.cpuInfo()
        val disks = SystemInfo.disks()
        val components = SystemInfo.components()
        val networks = SystemInfo.networks()
        val users = SystemInfo.users()
        val mb = SystemInfo.motherboard()
        val product = SystemInfo.product()
        val procs = SystemInfo.processes()

        val current = _state.value
        val cpuHist = (current.cpuHistory + (cpu?.globalCpuUsage ?: 0f)).takeLast(HISTORY_MAX_SIZE)
        val memPercent =
            if (mem != null && mem.totalMemory > 0) {
                mem.usedMemory.toFloat() / mem.totalMemory.toFloat() * 100f
            } else {
                0f
            }
        val memHist = (current.memoryHistory + memPercent).takeLast(HISTORY_MAX_SIZE)

        _state.value =
            SystemInfoState(
                osInfo = os,
                memoryInfo = mem,
                cpuInfo = cpu,
                disks = disks,
                components = components,
                networks = networks,
                users = users,
                motherboard = mb,
                product = product,
                processes = procs,
                cpuHistory = cpuHist,
                memoryHistory = memHist,
            )
    }
}
