package io.github.kdroidfilter.nucleus.notification.common.internal

import io.github.kdroidfilter.nucleus.notification.common.DismissReason

internal data class NotificationCallbacks(
    val onActivated: (() -> Unit)?,
    val onDismissed: ((DismissReason) -> Unit)?,
    val onFailed: (() -> Unit)?,
    val buttonCallbacks: Map<String, () -> Unit>,
)
