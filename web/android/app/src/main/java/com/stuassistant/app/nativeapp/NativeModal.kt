package com.stuassistant.app.nativeapp

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.dialog
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.shapes.RoundedRectangle
import com.stuassistant.app.glass.LiquidDialogSurface
import com.stuassistant.app.glass.LocalLiquidGlassReducedMotion
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** The interaction contract follows web/src/components/Sheet.tsx, in the same Compose window. */
private class NativeSheetEntry(
    val title: MutableState<String>,
    val dismiss: MutableState<() -> Unit>,
    val content: MutableState<@Composable ColumnScope.() -> Unit>,
) {
    val focus = FocusRequester()
    var containsFocus = false
    var hasBeenPresented = false
}

class NativeSheetController {
    // Activity observes this list to suppress page and navigation accessibility/focus.
    internal val entries = mutableStateListOf<Any>()
    internal val backgroundFocus = mutableListOf<FocusRequester>()
    private var focusToRestore: FocusRequester? = null

    internal fun add(entry: Any) {
        if (entries.isEmpty()) {
            focusToRestore = backgroundFocus.firstOrNull { it.saveFocusedChild() }
        } else {
            (entries.lastOrNull() as? NativeSheetEntry)?.focus?.saveFocusedChild()
        }
        entries.add(entry)
    }

    internal fun restoreBackgroundFocus() {
        val target = focusToRestore
        focusToRestore = null
        if (target != null && target in backgroundFocus) target.restoreFocusedChild()
    }
}

val LocalNativeSheets = staticCompositionLocalOf { NativeSheetController() }

/**
 * Apply to the page and navigation sibling containers, never the modal host or backdrop source.
 * Each group saves the actual Compose descendant, rather than the AndroidComposeView itself.
 */
@Composable
fun Modifier.nativeSheetBackground(controller: NativeSheetController): Modifier {
    val requester = remember(controller) { FocusRequester() }
    val modal = controller.entries.isNotEmpty()
    DisposableEffect(controller, requester) {
        controller.backgroundFocus.add(requester)
        onDispose { controller.backgroundFocus.remove(requester) }
    }
    return this
        .focusProperties { onEnter = { if (modal) cancelFocusChange() } }
        .focusRequester(requester)
        .focusRestorer()
        .focusGroup()
        .then(if (modal) Modifier.clearAndSetSemantics {} else Modifier)
}

/** Register outside the page's capture layer; no Android Dialog/Popup creates another window. */
@Composable
fun NSheet(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val controller = LocalNativeSheets.current
    val entry = remember {
        NativeSheetEntry(mutableStateOf(title), mutableStateOf(onDismiss), mutableStateOf(content))
    }
    SideEffect {
        entry.title.value = title
        entry.dismiss.value = onDismiss
        entry.content.value = content
    }
    DisposableEffect(controller, entry) {
        controller.add(entry)
        onDispose { controller.entries.remove(entry) }
    }
}

@Composable
fun NativeSheetHost(controller: NativeSheetController, backdrop: Backdrop) {
    val entries = controller.entries.filterIsInstance<NativeSheetEntry>()
    val top = entries.lastOrNull()
    val latestTop by rememberUpdatedState(top)
    BackHandler(enabled = top != null) { latestTop?.dismiss?.value?.invoke() }

    LaunchedEffect(top) {
        // Wait until requesters and the background onEnter guards reflect the new stack.
        withFrameNanos { }
        if (latestTop !== top) return@LaunchedEffect
        if (top == null) {
            controller.restoreBackgroundFocus()
        } else {
            if (!top.containsFocus) {
                val restored = top.hasBeenPresented && top.focus.restoreFocusedChild()
                if (!restored) top.focus.requestFocus(FocusDirection.Next)
            }
            top.hasBeenPresented = true
        }
    }

    // Keep underlying content composed: it may itself register the next NSheet in the stack.
    entries.forEach { entry ->
        key(entry) { NativeSheetLayer(entry, entry === top, backdrop) }
    }
}

@Composable
private fun NativeSheetLayer(entry: NativeSheetEntry, active: Boolean, backdrop: Backdrop) {
    val colors = LocalNativeColors.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val reducedMotion = LocalLiquidGlassReducedMotion.current
    val onDismiss by rememberUpdatedState(entry.dismiss.value)
    val latestActive by rememberUpdatedState(active)
    val latestReducedMotion by rememberUpdatedState(reducedMotion)
    var dragDistance by remember { mutableFloatStateOf(0f) }
    var settling by remember { mutableStateOf<Job?>(null) }
    val distanceThreshold = with(density) { 110.dp.toPx() }
    val velocityThreshold = with(density) { 600.dp.toPx() }

    fun settle() {
        settling?.cancel()
        if (latestReducedMotion) {
            dragDistance = 0f
        } else {
            settling = scope.launch {
                // SHEET: stiffness 360, damping 30, mass .9 -> unit-mass Compose spring.
                animate(
                    initialValue = dragDistance,
                    targetValue = 0f,
                    animationSpec = spring(dampingRatio = .8333333f, stiffness = 400f),
                ) { value, _ -> dragDistance = value }
            }
        }
    }

    LaunchedEffect(active) { if (!active) { settling?.cancel(); dragDistance = 0f } }

    BoxWithConstraints(
        Modifier.fillMaxSize().then(if (active) Modifier else Modifier.clearAndSetSemantics {}),
    ) {
        Box(
            Modifier.fillMaxSize()
                .background(Color.Black.copy(alpha = .32f))
                .focusProperties { canFocus = false }
                .clearAndSetSemantics {}
                .clickable(
                    enabled = active,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        )
        CompositionLocalProvider(LocalNativeBackdrop provides backdrop) {
            LiquidDialogSurface(
                backdrop,
                modifier = Modifier.align(Alignment.BottomCenter)
                    .navigationBarsPadding().imePadding()
                    .widthIn(max = 600.dp).fillMaxWidth().heightIn(max = maxHeight * .88f)
                    // Motion's lower elastic bound is .6; thresholds use raw pointer distance.
                    .offset { IntOffset(0, (dragDistance * .6f).roundToInt()) }
                    .focusProperties {
                        onEnter = { if (!active) cancelFocusChange() }
                        onExit = { if (active) cancelFocusChange() }
                    }
                    .focusRequester(entry.focus)
                    .onFocusChanged { entry.containsFocus = it.hasFocus }
                    .focusRestorer()
                    .onPreviewKeyEvent { event ->
                        if (!active) false else when (event.key) {
                            Key.Escape -> {
                                if (event.type == KeyEventType.KeyDown) onDismiss()
                                true
                            }
                            Key.Tab -> {
                                if (event.type == KeyEventType.KeyDown) {
                                    val direction = if (event.isShiftPressed) FocusDirection.Previous else FocusDirection.Next
                                    if (!focusManager.moveFocus(direction)) {
                                        // onExit cancels traversal beyond the modal. Restart at its
                                        // first/last descendant, without visiting a background node.
                                        focusManager.clearFocus(force = true)
                                        entry.focus.requestFocus(direction)
                                    }
                                }
                                true
                            }
                            else -> false
                        }
                    }
                    .focusGroup()
                    .semantics {
                        dialog()
                        paneTitle = entry.title.value
                        isTraversalGroup = true
                        dismiss { onDismiss(); true }
                    }
                    // Consume taps on otherwise empty panel space instead of hitting its scrim.
                    .pointerInput(Unit) { detectTapGestures(onTap = {}) },
            ) {
                Box(
                    Modifier.fillMaxWidth().height(24.dp).clearAndSetSemantics {}
                        .pointerInput(active, density) {
                            if (!active) return@pointerInput
                            val tracker = VelocityTracker()
                            detectDragGestures(
                                orientationLock = Orientation.Vertical,
                                onDragStart = { down, slopTrigger, overSlop ->
                                    settling?.cancel()
                                    tracker.resetTracking()
                                    tracker.addPointerInputChange(down)
                                    // Include the distance used for recognition in the 110dp test.
                                    dragDistance = (dragDistance + slopTrigger.position.y - down.position.y - overSlop.y).coerceAtLeast(0f)
                                },
                                onDrag = { change, amount ->
                                    // This helper reconstructs absolute deltas even as the panel
                                    // moves, and includes historical input samples.
                                    tracker.addPointerInputChange(change)
                                    dragDistance = (dragDistance + amount.y).coerceAtLeast(0f)
                                    change.consume()
                                },
                                onDragEnd = { up ->
                                    tracker.addPointerInputChange(up)
                                    val velocity = tracker.calculateVelocity().y
                                    if (latestActive && (dragDistance > distanceThreshold || velocity > velocityThreshold)) {
                                        onDismiss()
                                    } else settle()
                                },
                                onDragCancel = { settle() },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(40.dp, 5.dp).background(colors.border, RoundedRectangle(3.dp)))
                }
                Row(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f).semantics { heading() }) { NText(entry.title.value, 24, true) }
                    NButton("关闭", onDismiss)
                }
                Column(
                    Modifier.fillMaxWidth().weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    entry.content.value(this)
                }
            }
        }
    }
}
