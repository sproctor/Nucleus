package io.github.kdroidfilter.nucleus.notification.common.internal

import io.github.kdroidfilter.nucleus.freedesktop.icons.FreedesktopIcon
import io.github.kdroidfilter.nucleus.notification.common.DismissReason
import io.github.kdroidfilter.nucleus.notification.common.Notification
import io.github.kdroidfilter.nucleus.notification.common.NotificationHandle
import io.github.kdroidfilter.nucleus.notification.common.NotificationResult
import io.github.kdroidfilter.nucleus.notification.linux.CloseReason
import io.github.kdroidfilter.nucleus.notification.linux.LinuxNotificationCenter
import io.github.kdroidfilter.nucleus.notification.linux.LinuxNotificationListener
import io.github.kdroidfilter.nucleus.notification.linux.NotificationAction
import io.github.kdroidfilter.nucleus.notification.linux.NotificationHints
import java.util.logging.Level
import java.util.logging.Logger
import io.github.kdroidfilter.nucleus.notification.linux.Notification as LinuxNotification

internal class LinuxDispatcher private constructor() : PlatformDispatcher {
    private val logger = Logger.getLogger(LinuxDispatcher::class.java.simpleName)

    @Volatile
    private var listenerRegistered = false

    private val listener =
        object : LinuxNotificationListener {
            override fun onActionInvoked(
                notificationId: Int,
                actionKey: String,
            ) {
                val id = notificationId.toString()
                val callbacks = CallbackRegistry.get(id) ?: return
                try {
                    if (actionKey == NotificationAction.DEFAULT_KEY) {
                        callbacks.onActivated?.invoke()
                    } else {
                        callbacks.buttonCallbacks[actionKey]?.invoke()
                    }
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: RuntimeException,
                ) {
                    logger.log(Level.WARNING, "Error in notification callback", e)
                }
            }

            override fun onClosed(
                notificationId: Int,
                reason: CloseReason,
            ) {
                val id = notificationId.toString()
                val callbacks = CallbackRegistry.remove(id) ?: return
                try {
                    callbacks.onDismissed?.invoke(reason.toCommon())
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: RuntimeException,
                ) {
                    logger.log(Level.WARNING, "Error in notification dismiss callback", e)
                }
            }
        }

    companion object {
        fun createIfAvailable(): LinuxDispatcher? =
            try {
                Class.forName("io.github.kdroidfilter.nucleus.notification.linux.LinuxNotificationCenter")
                LinuxDispatcher()
            } catch (_: ClassNotFoundException) {
                null
            }
    }

    override fun isAvailable(): Boolean = LinuxNotificationCenter.isAvailable

    override fun initialize() {
        // Linux does not require explicit initialization
    }

    override fun send(notification: Notification): NotificationResult {
        ensureListenerRegistered()

        val actions = mutableListOf<NotificationAction>()
        // Add default action so body clicks trigger onActivated
        if (notification.onActivated != null) {
            actions.add(NotificationAction(key = NotificationAction.DEFAULT_KEY, label = ""))
        }
        // Add button actions
        val buttonCallbacks = mutableMapOf<String, () -> Unit>()
        notification.buttons.forEachIndexed { index, button ->
            val key = "btn_$index"
            actions.add(NotificationAction(key = key, label = button.title))
            buttonCallbacks[key] = button.onClick
        }

        val hints =
            NotificationHints(
                imagePath = notification.largeImage?.let { FreedesktopIcon.Custom(it) },
            )

        val linuxNotification =
            LinuxNotification(
                summary = notification.title,
                body = notification.message,
                appIcon = notification.smallIcon?.let { FreedesktopIcon.Custom(it) },
                actions = actions,
                hints = hints,
            )

        val id = LinuxNotificationCenter.notify(linuxNotification)
        if (id == 0) {
            notification.onFailed?.invoke()
            return NotificationResult.Failure("Linux notification server returned 0")
        }

        val platformId = id.toString()
        CallbackRegistry.register(
            platformId,
            NotificationCallbacks(
                onActivated = notification.onActivated,
                onDismissed = notification.onDismissed,
                onFailed = notification.onFailed,
                buttonCallbacks = buttonCallbacks,
            ),
        )

        return NotificationResult.Success(NotificationHandle(platformId, this))
    }

    override fun dismiss(platformId: String) {
        val id = platformId.toIntOrNull() ?: return
        LinuxNotificationCenter.closeNotification(id)
    }

    private fun ensureListenerRegistered() {
        if (!listenerRegistered) {
            synchronized(this) {
                if (!listenerRegistered) {
                    LinuxNotificationCenter.addListener(listener)
                    listenerRegistered = true
                }
            }
        }
    }
}

private fun CloseReason.toCommon(): DismissReason =
    when (this) {
        CloseReason.DISMISSED -> DismissReason.USER_DISMISSED
        CloseReason.EXPIRED -> DismissReason.TIMED_OUT
        CloseReason.CLOSED -> DismissReason.APPLICATION
        CloseReason.UNDEFINED -> DismissReason.UNKNOWN
    }
