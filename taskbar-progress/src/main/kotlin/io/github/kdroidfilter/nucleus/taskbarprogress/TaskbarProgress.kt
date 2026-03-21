package io.github.kdroidfilter.nucleus.taskbarprogress

import io.github.kdroidfilter.nucleus.core.runtime.Platform
import io.github.kdroidfilter.nucleus.taskbarprogress.windows.NativeWindowsTaskbarBridge
import java.awt.Window

/**
 * Cross-platform taskbar/dock progress indicator and attention request.
 *
 * Windows: uses ITaskbarList3 COM interface and FlashWindowEx via JNI.
 * macOS / Linux: not yet implemented (calls are no-ops).
 */
object TaskbarProgress {
    private const val PROGRESS_MAX = 100L

    enum class AttentionType(
        internal val nativeValue: Int,
    ) {
        /** Flash taskbar button briefly (4 times). */
        INFORMATIONAL(1),

        /** Flash window and taskbar button until the app gets focus. */
        CRITICAL(2),
    }

    @Suppress("MagicNumber")
    enum class State(
        internal val flag: Int,
    ) {
        /** No progress displayed. */
        NO_PROGRESS(0x00),

        /** Indeterminate (pulsing) progress. */
        INDETERMINATE(0x01),

        /** Normal progress bar (green). */
        NORMAL(0x02),

        /** Error state (red). */
        ERROR(0x04),

        /** Paused state (yellow). */
        PAUSED(0x08),
    }

    /**
     * Returns true if taskbar progress is available on this platform.
     */
    fun isAvailable(): Boolean = Platform.Current == Platform.Windows && NativeWindowsTaskbarBridge.isLoaded

    /**
     * Sets the progress value for the given window.
     *
     * @param window the AWT window whose taskbar button shows the progress
     * @param value  progress fraction in 0.0..1.0
     * @return true if the operation succeeded
     */
    fun setProgress(
        window: Window,
        value: Double,
    ): Boolean {
        if (Platform.Current != Platform.Windows || !NativeWindowsTaskbarBridge.isLoaded) return false
        val clamped = value.coerceIn(0.0, 1.0)
        return NativeWindowsTaskbarBridge.nativeSetProgress(
            window,
            (clamped * PROGRESS_MAX).toLong(),
            PROGRESS_MAX,
        ) == 0
    }

    /**
     * Sets the progress state for the given window.
     *
     * @param window the AWT window whose taskbar button shows the progress
     * @param state  the desired progress state
     * @return true if the operation succeeded
     */
    fun setState(
        window: Window,
        state: State,
    ): Boolean {
        if (Platform.Current != Platform.Windows || !NativeWindowsTaskbarBridge.isLoaded) return false
        return NativeWindowsTaskbarBridge.nativeSetProgressState(window, state.flag) == 0
    }

    /**
     * Convenience: shows normal progress at the given value.
     */
    fun showProgress(
        window: Window,
        value: Double,
    ): Boolean = setState(window, State.NORMAL) && setProgress(window, value)

    /**
     * Convenience: shows error progress at the given value.
     */
    fun showError(
        window: Window,
        value: Double = 1.0,
    ): Boolean = setState(window, State.ERROR) && setProgress(window, value)

    /**
     * Convenience: shows indeterminate (pulsing) progress.
     */
    fun showIndeterminate(window: Window): Boolean = setState(window, State.INDETERMINATE)

    /**
     * Convenience: shows paused progress at the given value.
     */
    fun showPaused(
        window: Window,
        value: Double = 1.0,
    ): Boolean = setState(window, State.PAUSED) && setProgress(window, value)

    /**
     * Hides progress from the taskbar button.
     */
    fun hideProgress(window: Window): Boolean = setState(window, State.NO_PROGRESS)

    /**
     * Requests user attention by flashing the window in the taskbar.
     *
     * - [AttentionType.INFORMATIONAL]: flashes the taskbar button briefly (4 times).
     * - [AttentionType.CRITICAL]: flashes window and taskbar button until the app gets focus.
     *
     * @param window the AWT window to flash
     * @param type   the attention urgency level
     * @return true if the operation succeeded
     */
    fun requestAttention(
        window: Window,
        type: AttentionType = AttentionType.INFORMATIONAL,
    ): Boolean {
        if (Platform.Current != Platform.Windows || !NativeWindowsTaskbarBridge.isLoaded) return false
        return NativeWindowsTaskbarBridge.nativeRequestAttention(window, type.nativeValue) == 0
    }

    /**
     * Stops any active window flashing.
     *
     * @param window the AWT window to stop flashing
     * @return true if the operation succeeded
     */
    fun stopAttention(window: Window): Boolean {
        if (Platform.Current != Platform.Windows || !NativeWindowsTaskbarBridge.isLoaded) return false
        return NativeWindowsTaskbarBridge.nativeRequestAttention(window, 0) == 0
    }
}
