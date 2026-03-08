package io.github.kdroidfilter.nucleus.window

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.dp
import io.github.kdroidfilter.nucleus.window.styling.LocalTitleBarStyle
import io.github.kdroidfilter.nucleus.window.styling.TitleBarStyle
import io.github.kdroidfilter.nucleus.window.utils.macos.JniMacTitleBarBridge
import io.github.kdroidfilter.nucleus.window.utils.macos.JniMacWindowUtil
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext

private const val MENU_BAR_POLL_INTERVAL_MS = 16L
private const val MENU_BAR_ANIMATION_MS = 200

@OptIn(ExperimentalComposeUiApi::class)
@Suppress("FunctionNaming")
@Composable
internal fun DecoratedWindowScope.MacOSTitleBar(
    modifier: Modifier = Modifier,
    gradientStartColor: Color = Color.Unspecified,
    style: TitleBarStyle = LocalTitleBarStyle.current,
    content: @Composable TitleBarScope.(DecoratedWindowState) -> Unit = {},
) {
    val useNewFullscreenControls = modifier.hasNewFullscreenControls()

    // Notify native side about the newFullscreenControls preference
    DisposableEffect(window, useNewFullscreenControls) {
        if (useNewFullscreenControls) {
            val ptr = JniMacWindowUtil.getWindowPtr(window)
            if (ptr != 0L && JniMacTitleBarBridge.isLoaded) {
                JniMacTitleBarBridge.nativeSetNewFullscreenControls(ptr, true)
            }
        }
        onDispose {
            if (useNewFullscreenControls) {
                val ptr = JniMacWindowUtil.getWindowPtr(window)
                if (ptr != 0L && JniMacTitleBarBridge.isLoaded) {
                    JniMacTitleBarBridge.nativeSetNewFullscreenControls(ptr, false)
                }
            }
        }
    }

    DisposableEffect(window) {
        onDispose {
            val ptr = JniMacWindowUtil.getWindowPtr(window)
            if (ptr != 0L) JniMacTitleBarBridge.nativeResetTitleBar(ptr)
        }
    }

    // Poll the menu bar offset during fullscreen with newFullscreenControls.
    // When the user moves the cursor to the top of the screen, macOS slides
    // the auto-hidden menu bar down. We track its height and push the title
    // bar (+ its traffic-light buttons) down by the same amount — mirroring
    // Safari's fullscreen title bar behaviour.
    var menuBarOffsetPt by remember { mutableStateOf(0f) }

    if (state.isFullscreen && useNewFullscreenControls) {
        LaunchedEffect(window) {
            while (coroutineContext.isActive) {
                val ptr = JniMacWindowUtil.getWindowPtr(window)
                if (ptr != 0L && JniMacTitleBarBridge.isLoaded) {
                    val offset = JniMacTitleBarBridge.nativeGetMenuBarOffset(ptr)
                    if (offset != menuBarOffsetPt) {
                        menuBarOffsetPt = offset
                    }
                } else {
                    menuBarOffsetPt = 0f
                }
                delay(MENU_BAR_POLL_INTERVAL_MS)
            }
        }
    } else {
        menuBarOffsetPt = 0f
    }

    val animatedOffset by animateDpAsState(
        targetValue = menuBarOffsetPt.dp,
        animationSpec = tween(durationMillis = MENU_BAR_ANIMATION_MS),
    )

    // Push the animated offset to native so traffic-light buttons follow
    // the same smooth animation as the Compose title bar.
    LaunchedEffect(animatedOffset) {
        val ptr = JniMacWindowUtil.getWindowPtr(window)
        if (ptr != 0L && JniMacTitleBarBridge.isLoaded) {
            JniMacTitleBarBridge.nativeSetMenuBarOffset(ptr, animatedOffset.value)
        }
    }

    val background by style.colors.backgroundFor(state)

    // ── Fullscreen with newFullscreenControls: render as overlay ──
    // The title bar is stored in FullscreenTitleBarHolder and rendered by
    // DecoratedWindow as an overlay on top of the content — outside the
    // normal DecoratedWindowMeasurePolicy layout. This avoids the need
    // for zIndex (which can hide Compose popups) and keeps popup/tooltip
    // positioning correct because the overlay naturally draws last.
    if (state.isFullscreen && useNewFullscreenControls) {
        val holder = LocalFullscreenTitleBarHolder.current
        if (holder != null) {
            val viewConfig = LocalViewConfiguration.current
            holder.titleBarHeight = style.metrics.height
            holder.content = {
                var lastPress by remember { mutableStateOf(0L) }
                TitleBarImpl(
                    modifier =
                        Modifier
                            .background(background)
                            .padding(top = animatedOffset)
                            .then(modifier)
                            .titleBarHitTestHandler(window)
                            .onPointerEvent(PointerEventType.Press, PointerEventPass.Final) {
                                if (
                                    this.currentEvent.button == PointerButton.Primary &&
                                    this.currentEvent.changes.any { !it.isConsumed }
                                ) {
                                    val now = System.currentTimeMillis()
                                    if (now - lastPress in viewConfig.doubleTapMinTimeMillis..viewConfig.doubleTapTimeoutMillis) {
                                        val ptr = JniMacWindowUtil.getWindowPtr(window)
                                        if (ptr != 0L && JniMacTitleBarBridge.isLoaded) {
                                            JniMacTitleBarBridge.nativePerformTitleBarDoubleClickAction(ptr)
                                        }
                                    }
                                    lastPress = now
                                }
                            },
                    gradientStartColor = gradientStartColor,
                    style = style,
                    applyTitleBar = { _, _ ->
                        JniMacWindowUtil.applyWindowProperties(window)
                        PaddingValues(start = 80.dp)
                    },
                    onPlace = {
                        val ptr = JniMacWindowUtil.getWindowPtr(window)
                        if (ptr != 0L && JniMacTitleBarBridge.isLoaded) {
                            JniMacTitleBarBridge.nativeUpdateFullScreenButtons(ptr)
                        }
                    },
                    backgroundContent = {
                        Spacer(modifier = Modifier.fillMaxSize())
                    },
                    content = content,
                )
            }
            return
        }
    }

    // ── Normal title bar (not fullscreen, or without newFullscreenControls) ──
    val viewConfig = LocalViewConfiguration.current
    var lastPress = 0L

    TitleBarImpl(
        modifier =
            modifier
                .titleBarHitTestHandler(window)
                .onPointerEvent(PointerEventType.Press, PointerEventPass.Final) {
                    if (
                        this.currentEvent.button == PointerButton.Primary &&
                        this.currentEvent.changes.any { !it.isConsumed }
                    ) {
                        val now = System.currentTimeMillis()
                        if (now - lastPress in viewConfig.doubleTapMinTimeMillis..viewConfig.doubleTapTimeoutMillis) {
                            val ptr = JniMacWindowUtil.getWindowPtr(window)
                            if (ptr != 0L && JniMacTitleBarBridge.isLoaded) {
                                JniMacTitleBarBridge.nativePerformTitleBarDoubleClickAction(ptr)
                            }
                        }
                        lastPress = now
                    }
                },
        gradientStartColor = gradientStartColor,
        style = style,
        applyTitleBar = { height, titleBarState ->
            JniMacWindowUtil.applyWindowProperties(window)

            val ptr = JniMacWindowUtil.getWindowPtr(window)

            if (titleBarState.isFullscreen) {
                PaddingValues(start = 80.dp)
            } else {
                val leftInset =
                    if (ptr != 0L && JniMacTitleBarBridge.isLoaded) {
                        JniMacTitleBarBridge.nativeApplyTitleBar(ptr, height.value)
                    } else {
                        @Suppress("MagicNumber")
                        val shrink = minOf(height.value / 28f, 1f)
                        @Suppress("MagicNumber")
                        height.value + 2f * shrink * 20f
                    }
                PaddingValues(start = leftInset.dp)
            }
        },
        onPlace = {
            if (state.isFullscreen) {
                val ptr = JniMacWindowUtil.getWindowPtr(window)
                if (ptr != 0L && JniMacTitleBarBridge.isLoaded) {
                    JniMacTitleBarBridge.nativeUpdateFullScreenButtons(ptr)
                }
            }
        },
        backgroundContent = {
            Spacer(modifier = Modifier.fillMaxSize())
        },
        content = content,
    )
}

/**
 * Mirrors JBR's `customTitleBarMouseEventHandler` / `forceHitTest` approach.
 * Runs on the parent modifier (Main pass, after children have processed events).
 *
 * - Unconsumed Press → marks a pending drag (button down on empty title bar area).
 * - Unconsumed Move while pending → initiates native window drag via JNI.
 * - Consumed Press → enters `inUserControl` (interactive child handles it).
 * - Release → resets state.
 *
 * The native NucleusDragView is a pure pass-through; all drag decisions live here.
 */
internal fun Modifier.titleBarHitTestHandler(window: java.awt.Window): Modifier =
    pointerInput(window) {
        val ctx = coroutineContext
        awaitPointerEventScope {
            var inUserControl = false
            var pendingDrag = false
            while (ctx.isActive) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                event.changes.forEach {
                    if (!it.isConsumed && !inUserControl) {
                        when (event.type) {
                            PointerEventType.Press -> pendingDrag = true
                            PointerEventType.Move ->
                                if (pendingDrag) {
                                    startWindowDrag(window)
                                    pendingDrag = false
                                }
                            PointerEventType.Release -> pendingDrag = false
                        }
                    } else {
                        if (event.type == PointerEventType.Press) {
                            inUserControl = true
                            pendingDrag = false
                        }
                        if (event.type == PointerEventType.Release) {
                            inUserControl = false
                        }
                    }
                }
            }
        }
    }

private fun startWindowDrag(window: java.awt.Window) {
    val ptr = JniMacWindowUtil.getWindowPtr(window)
    if (ptr != 0L && JniMacTitleBarBridge.isLoaded) {
        JniMacTitleBarBridge.nativeStartWindowDrag(ptr)
    }
}
