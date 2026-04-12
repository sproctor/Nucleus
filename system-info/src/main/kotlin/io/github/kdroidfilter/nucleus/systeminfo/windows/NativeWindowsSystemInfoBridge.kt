package io.github.kdroidfilter.nucleus.systeminfo.windows

import io.github.kdroidfilter.nucleus.core.runtime.NativeLibraryLoader

private const val LIBRARY_NAME = "nucleus_system_info"

internal object NativeWindowsSystemInfoBridge {
    private val loaded = NativeLibraryLoader.load(LIBRARY_NAME, NativeWindowsSystemInfoBridge::class.java)

    val isLoaded: Boolean get() = loaded
}
