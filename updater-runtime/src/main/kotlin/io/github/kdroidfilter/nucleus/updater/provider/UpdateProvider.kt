package io.github.kdroidfilter.nucleus.updater.provider

import io.github.kdroidfilter.nucleus.core.runtime.Platform
import java.net.http.HttpClient

interface UpdateProvider {
    fun getUpdateMetadataUrl(
        channel: String,
        platform: Platform,
    ): String

    fun getDownloadUrl(
        fileName: String,
        version: String,
    ): String

    fun authHeaders(): Map<String, String> = emptyMap()

    /**
     * Resolve the metadata (YAML) URL for the given channel, optionally consulting a
     * remote service. The default delegates to [getUpdateMetadataUrl]; providers that
     * need an HTTP round-trip (e.g. GitHub pre-release channels) should override this.
     *
     * The [httpClient] is supplied by [io.github.kdroidfilter.nucleus.updater.NucleusUpdater]
     * so providers can reuse the configured client and credentials.
     */
    fun resolveMetadataUrl(
        channel: String,
        platform: Platform,
        httpClient: HttpClient,
    ): String = getUpdateMetadataUrl(channel, platform)
}
