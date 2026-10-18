package com.naivety.zhicolor.ui.components.motion

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import com.naivety.zhicolor.ui.components.LocalGlassOverlayActive
import kotlinx.coroutines.flow.collect
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** The real openness is shared by normal show/hide and system-back seeking. */
@Stable
internal class PredictiveBackDismissState(initialOpenFraction: Float = 0f) {
    val openFraction = Animatable(PredictiveBackMotionPolicy.fraction(initialOpenFraction))
    var gestureInProgress by mutableStateOf(false)
        private set
    var settlementEpoch by mutableIntStateOf(0)
        private set
    val commitGate = OverlayBackCommitGate()
    private val sessions = BackGestureSession()

    suspend fun animateOpenTo(target: Float) {
        val bounded = PredictiveBackMotionPolicy.fraction(target)
        val duration = PredictiveBackMotionPolicy.durationMillis(openFraction.value, bounded)
        if (duration == 0) openFraction.snapTo(bounded)
        else openFraction.animateTo(bounded, tween(duration, easing = LinearEasing))
    }

    fun beginGesture(): Int {
        gestureInProgress = true
        return sessions.begin()
    }

    fun isCurrentGesture(session: Int): Boolean = sessions.isCurrent(session)

    fun invalidateGesture() {
        sessions.invalidate()
        gestureInProgress = false
        settlementEpoch++
    }

    fun finishGesture(session: Int) {
        if (!sessions.finish(session)) return
        gestureInProgress = false
        // A first-frame start/cancel can be coalesced into false -> false by composition.
        // This token always changes, so the owner still settles to its current open target.
        settlementEpoch++
    }
}

/** Consume only the top portal's back; all geometry is still frame(openFraction). */
@Composable
internal fun PredictiveBackDismissHandler(
    state: PredictiveBackDismissState,
    enabled: Boolean,
    shown: Boolean,
    dismissible: Boolean,
    onDismiss: () -> Unit,
) {
    val canDismiss by rememberUpdatedState(
        PredictiveBackMotionPolicy.shouldDismissOverlay(enabled, shown, dismissible),
    )
    val currentDismiss by rememberUpdatedState(onDismiss)
    // A closing or non-dismissible top portal consumes the event without touching a parent.
    PredictiveBackHandler(enabled = enabled) { events ->
        if (!canDismiss || state.commitGate.committed) {
            events.collect { }
        } else {
            val session = state.beginGesture()
            try {
                state.openFraction.stop()
                val startOpen = state.openFraction.value
                events.collect { event ->
                    if (state.isCurrentGesture(session)) {
                        state.openFraction.snapTo(PredictiveBackMotionPolicy.gestureOpenFraction(startOpen, event.progress))
                    }
                }
                if (state.isCurrentGesture(session) && canDismiss) {
                    state.animateOpenTo(0f)
                    if (state.isCurrentGesture(session) && state.commitGate.tryCommit(canDismiss)) currentDismiss()
                }
            } finally {
                // Cancellation leaves openness at the last rendered frame. The host settles
                // that same value back to its current target, along precisely the same path.
                state.finishGesture(session)
            }
        }
    }
}

/**
 * A child has one fixed entrance path. Click, back button, either system edge, and cancel
 * change only its open fraction; they never choose a different transition or direction.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun <T> PredictiveBackPage(
    targetState: T,
    backTarget: T,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = "PredictiveBackPage",
    onChildActiveChanged: (Boolean) -> Unit = {},
    content: @Composable (T) -> Unit,
) {
    val motion = remember { PredictiveBackDismissState(if (targetState == backTarget) 0f else 1f) }
    var retainedChild by remember { mutableStateOf(targetState) }
    val currentBack by rememberUpdatedState(onBack)
    val hasOverlay = LocalGlassOverlayActive.current
    val mainTabActive = LocalMainTabActive.current
    val hasRetainedChild = retainedChild != backTarget
    val canHandleBack = mainTabActive && PredictiveBackMotionPolicy.shouldHandlePageBack(targetState == backTarget, hasOverlay, hasRetainedChild)
    val canCommitBack by rememberUpdatedState(mainTabActive && targetState != backTarget && !hasOverlay)
    SideEffect { onChildActiveChanged(targetState != backTarget || hasRetainedChild) }

    LaunchedEffect(targetState, hasOverlay) {
        motion.invalidateGesture()
        if (targetState != backTarget) motion.commitGate.reset()
    }
    LaunchedEffect(targetState, motion.gestureInProgress, motion.settlementEpoch) {
        if (!motion.gestureInProgress) {
            if (targetState != backTarget) {
                if (retainedChild != targetState) {
                    if (retainedChild != backTarget) motion.animateOpenTo(0f)
                    retainedChild = targetState
                }
                motion.animateOpenTo(1f)
            } else {
                motion.animateOpenTo(0f)
                retainedChild = backTarget
            }
        }
    }
    // Retain consumption until a closing child reaches its origin. At the actual root
    // no callback is registered, so ColorOS keeps the native back-to-home animation.
    PredictiveBackHandler(enabled = canHandleBack) { events ->
        if (!canCommitBack || motion.commitGate.committed) {
            events.collect { }
        } else {
            val session = motion.beginGesture()
            try {
                motion.openFraction.stop()
                val startOpen = motion.openFraction.value
                events.collect { event ->
                    if (motion.isCurrentGesture(session)) {
                        motion.openFraction.snapTo(PredictiveBackMotionPolicy.gestureOpenFraction(startOpen, event.progress))
                    }
                }
                if (motion.isCurrentGesture(session) && canCommitBack) {
                    motion.animateOpenTo(0f)
                    if (motion.isCurrentGesture(session) && motion.commitGate.tryCommit(canCommitBack)) currentBack()
                }
            } finally {
                motion.finishGesture(session)
            }
        }
    }
    val pageColor = MiuixTheme.colorScheme.surface
    Box(modifier.fillMaxSize().clipToBounds().background(pageColor)) {
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer {
                    val frame = PredictiveBackMotionPolicy.pageParentFrame(motion.openFraction.value, size.width)
                    alpha = frame.alpha
                    scaleX = frame.scale
                    scaleY = frame.scale
                    translationX = frame.translationX
                }
                .focusProperties { canFocus = !hasRetainedChild }
                .pointerInput(hasRetainedChild) {
                    if (hasRetainedChild) awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                        }
                    }
                }
                .semantics { if (hasRetainedChild) hideFromAccessibility() }
                .background(pageColor),
        ) { key(backTarget) { content(backTarget) } }
        if (hasRetainedChild) {
            Box(Modifier.fillMaxSize().graphicsLayer {
                val frame = PredictiveBackMotionPolicy.pageChildFrame(motion.openFraction.value, size.width)
                alpha = frame.alpha
                scaleX = frame.scale
                scaleY = frame.scale
                translationX = frame.translationX
            }.background(pageColor)) { key(retainedChild) { content(retainedChild) } }
        }
    }
}
