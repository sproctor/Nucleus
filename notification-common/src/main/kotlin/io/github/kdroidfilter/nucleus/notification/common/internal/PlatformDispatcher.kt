package io.github.kdroidfilter.nucleus.notification.common.internal

import io.github.kdroidfilter.nucleus.notification.common.Notification
import io.github.kdroidfilter.nucleus.notification.common.NotificationResult

internal interface PlatformDispatcher {
    fun isAvailable(): Boolean

    fun initialize()

    fun send(notification: Notification): NotificationResult

    fun dismiss(platformId: String)
}
