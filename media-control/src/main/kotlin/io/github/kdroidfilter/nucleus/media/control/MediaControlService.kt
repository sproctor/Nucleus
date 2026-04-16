package io.github.kdroidfilter.nucleus.media.control

import io.github.kdroidfilter.nucleus.core.runtime.NucleusApp
import io.github.kdroidfilter.nucleus.media.control.linux.NativeLinuxBridge
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.swing.SwingUtilities

/**
 * Entry point for OS-level media controls on Linux via MPRIS D-Bus.
 *
 * On Windows and macOS, all methods are no-ops and [isAvailable] returns `false`.
 *
 * Linux: communicates with the MPRIS D-Bus interface at `org.mpris.MediaPlayer2.<name>`.
 * The D-Bus name defaults to `org.mpris.MediaPlayer2.${NucleusApp.appId}`.
 *
 * Events dispatched to the callback are delivered on the Swing EDT.
 */
object MediaControlService {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Returns `true` if the native library loaded successfully on Linux.
     * Always returns `false` on Windows and macOS.
     */
    fun isAvailable(): Boolean = NativeLinuxBridge.isLoaded

    /**
     * Configure the MPRIS player identity on D-Bus.
     * Must be called once before any other method on Linux.
     *
     * @param dbusName The D-Bus bus name following the MPRIS spec format.
     *                 Defaults to `org.mpris.MediaPlayer2.${NucleusApp.appId}`.
     * @param displayName The human-readable name shown in the system media center.
     *                    Defaults to [NucleusApp.appName] or `"Nucleus App"`.
     */
    fun configure(
        dbusName: String = "org.mpris.MediaPlayer2.${NucleusApp.appId}",
        displayName: String = NucleusApp.appName ?: "Nucleus App",
    ) {
        if (!isAvailable()) return
        NativeLinuxBridge.nativeConfigure(dbusName, displayName)
    }

    /**
     * Update the metadata shown in the system media center.
     * Call whenever the track changes.
     */
    fun setMetadata(metadata: MediaMetadata) {
        if (!isAvailable()) return
        NativeLinuxBridge.nativeSetMetadata(
            title = metadata.title,
            artist = metadata.artist,
            album = metadata.album,
            coverUrl = metadata.coverUrl,
            durationMs = metadata.duration ?: -1L,
        )
    }

    /**
     * Update the playback state shown in the system media center.
     * Call on play/pause/stop and periodically during playback to update position.
     */
    fun setPlaybackState(state: MediaPlaybackState) {
        if (!isAvailable()) return
        NativeLinuxBridge.nativeSetPlaybackState(
            status = state.status.ordinal,
            positionMs = state.positionMs ?: -1L,
        )
    }

    /**
     * Update the player volume shown in the system media center.
     * @param volume Volume level in the range 0.0–1.0.
     */
    fun setVolume(volume: Double) {
        if (!isAvailable()) return
        NativeLinuxBridge.nativeSetVolume(volume.coerceIn(0.0, 1.0))
    }

    /**
     * Listen for control events from the OS (play, pause, seek, next, previous...).
     *
     * The callback is dispatched on the Swing EDT — safe to mutate Compose/Swing state directly.
     * Only one listener is active at a time; calling attach replaces any previous listener.
     */
    fun attach(callback: (MediaControlEvent) -> Unit) {
        if (!isAvailable()) return
        NativeLinuxBridge.attach { raw ->
            val event = parseEvent(raw) ?: return@attach
            SwingUtilities.invokeLater { callback(event) }
        }
    }

    /**
     * Detach the listener and unregister from D-Bus.
     * No further events will be dispatched.
     */
    fun detach() {
        if (!isAvailable()) return
        NativeLinuxBridge.detach()
    }

    @Serializable
    private data class EventDto(
        val type: String,
        val offsetUs: Long? = null,
        val positionUs: Long? = null,
        val volume: Double? = null,
        val uri: String? = null,
    )

    private fun parseEvent(raw: String): MediaControlEvent? {
        val dto = runCatching { json.decodeFromString<EventDto>(raw) }.getOrNull() ?: return null
        return when (dto.type) {
            "play" -> MediaControlEvent.Play
            "pause" -> MediaControlEvent.Pause
            "toggle" -> MediaControlEvent.Toggle
            "next" -> MediaControlEvent.Next
            "previous" -> MediaControlEvent.Previous
            "stop" -> MediaControlEvent.Stop
            "seek" -> dto.offsetUs?.let { MediaControlEvent.SeekBy(it / MICROS_PER_MS) }
            "set_position" -> dto.positionUs?.let { MediaControlEvent.SetPosition(it / MICROS_PER_MS) }
            "set_volume" -> dto.volume?.let { MediaControlEvent.SetVolume(it) }
            "open_uri" -> dto.uri?.let { MediaControlEvent.OpenUri(it) }
            "raise" -> MediaControlEvent.Raise
            "quit" -> MediaControlEvent.Quit
            else -> null
        }
    }

    private const val MICROS_PER_MS = 1000L
}
