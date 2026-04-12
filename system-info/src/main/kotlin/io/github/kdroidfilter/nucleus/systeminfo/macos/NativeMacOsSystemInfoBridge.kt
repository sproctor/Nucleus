package io.github.kdroidfilter.nucleus.systeminfo.macos

import io.github.kdroidfilter.nucleus.core.runtime.NativeLibraryLoader

private const val LIBRARY_NAME = "nucleus_system_info"

internal object NativeMacOsSystemInfoBridge {
    private val loaded = NativeLibraryLoader.load(LIBRARY_NAME, NativeMacOsSystemInfoBridge::class.java)

    val isLoaded: Boolean get() = loaded
}
