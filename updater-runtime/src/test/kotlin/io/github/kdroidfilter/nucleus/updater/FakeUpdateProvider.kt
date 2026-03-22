package io.github.kdroidfilter.nucleus.updater

import io.github.kdroidfilter.nucleus.core.runtime.Platform
import io.github.kdroidfilter.nucleus.updater.provider.UpdateProvider

class FakeUpdateProvider : UpdateProvider {
    override fun getUpdateMetadataUrl(
        channel: String,
        platform: Platform,
    ): String = "https://example.com/updates/$channel"

    override fun getDownloadUrl(
        fileName: String,
        version: String,
    ): String = "https://example.com/downloads/$version/$fileName"
}
