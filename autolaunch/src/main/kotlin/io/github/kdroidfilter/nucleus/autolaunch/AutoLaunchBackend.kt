package io.github.kdroidfilter.nucleus.autolaunch

/** Internal SPI implemented by each platform/packaging backend. */
internal interface AutoLaunchBackend {
    fun state(): AutoLaunchState

    fun enable(): AutoLaunchResult

    fun disable(): AutoLaunchResult

    /** Opens the platform-native UI for managing startup apps, if available. */
    fun openSystemSettings(): Boolean = false
}

internal object NoOpAutoLaunchBackend : AutoLaunchBackend {
    override fun state(): AutoLaunchState = AutoLaunchState.UNSUPPORTED

    override fun enable(): AutoLaunchResult = AutoLaunchResult.UNSUPPORTED

    override fun disable(): AutoLaunchResult = AutoLaunchResult.UNSUPPORTED
}
