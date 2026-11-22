package com.stuassistant.app.nativeapp

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import com.stuassistant.app.glass.LiquidDialogSurface
import com.stuassistant.app.glass.LocalLiquidGlassDarkTheme
import com.stuassistant.app.glass.LocalLiquidGlassReducedMotion
import kotlinx.coroutines.launch
import java.time.LocalDate

@Composable
internal fun ItemsCheckbox(
    done: Boolean,
    accent: Color,
    title: String,
    onToggle: () -> Unit,
    enabled: Boolean = true,
    sizeDp: Int = 48,
    square: Boolean = false,
) {
    val c = LocalNativeColors.current
    val reduced = LocalLiquidGlassReducedMotion.current
    val tick = nSelectionFeedback()
    val dark = LocalLiquidGlassDarkTheme.current == true
    val okColor = nativeSemanticTones(dark, LocalNativePalette.current).leaf
    val checkPath = remember { NativeIcons.path("check") }

    val progress by animateFloatAsState(
        if (done) 1f else 0f,
        if (reduced) snap() else spring(dampingRatio = 0.62f, stiffness = 540f),
        label = "chk-fill",
    )
    val bumpScale by animateFloatAsState(
        if (done) 1f else 0.94f,
        if (reduced) snap() else spring(dampingRatio = 0.54f, stiffness = 560f),
        label = "chk-scale",
    )

    val interactionSource = remember { MutableInteractionSource() }
    Box(
        Modifier
            .size(sizeDp.dp)
            .toggleable(
                value = done,
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Checkbox,
                onValueChange = {
                    tick()
                    onToggle()
                },
            )
            .semantics {
                contentDescription = "${if (done) "取消完成" else "标记完成"}：$title"
                stateDescription = if (done) "已完成" else "未完成"
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            Modifier
                .size(22.dp)
                .graphicsLayer {
                    scaleX = bumpScale
                    scaleY = bumpScale
                },
        ) {
            val fillA = progress.coerceIn(0f, 1f)
            val outline = if (done) okColor else accent
            if (square) {
                val inset = 1.dp.toPx()
                val corner = CornerRadius(4.dp.toPx())
                val fillSize = Size(size.width - 2 * inset, size.height - 2 * inset)
                val topLeft = Offset(inset, inset)
                drawRoundRect(okColor.copy(alpha = fillA), topLeft, fillSize, corner)
                drawRoundRect(outline, topLeft, fillSize, corner, style = Stroke(1.75.dp.toPx()))
            } else {
                if (fillA > 0.01f) {
                    drawCircle(okColor.copy(alpha = fillA), radius = size.width / 2f)
                }
                drawCircle(outline, radius = size.width / 2f - 1.dp.toPx(), style = Stroke(1.9.dp.toPx()))
            }
            if (fillA > 0.02f) {
                scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
                    drawPath(
                        path = checkPath,
                        color = Color.White.copy(alpha = fillA),
                        style = Stroke(2.4f, cap = StrokeCap.Round, join = StrokeJoin.Round),
                    )
                }
            }
        }
    }
}

@Composable
private fun ItemsSheetNoteField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalNativeColors.current
    val dark = LocalLiquidGlassDarkTheme.current == true
    val reduced = LocalLiquidGlassReducedMotion.current
    var focused by remember { mutableStateOf(false) }
    val fill by animateColorAsState(
        if (focused) c.brand.copy(alpha = if (dark) 0.12f else 0.06f) else c.ink.copy(alpha = 0.045f),
        if (reduced) snap() else tween(140),
        label = "items-note-fill",
    )
    val border by animateColorAsState(
        if (focused) c.brand.copy(alpha = 0.45f) else c.border.copy(alpha = 0.55f),
        if (reduced) snap() else tween(140),
        label = "items-note-border",
    )
    val shape = RoundedRectangle(12.dp)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(fill)
            .border(1.dp, border, shape)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        maxLines = 4,
        textStyle = nativeTextStyle(c.ink, 13.5f, 19f),
        cursorBrush = SolidColor(c.brand),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) NText("备注", size = 13, muted = true)
                inner()
            }
        },
    )
}

@Composable
private fun ItemsSheetScheduleButton(
    label: String,
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalNativeColors.current
    val reduced = LocalLiquidGlassReducedMotion.current
    val follow = nSelectionFeedback()
    val angle by animateFloatAsState(if (expanded) 180f else 0f, if (reduced) snap() else tween(160), label = "items-date-chevron")
    val fill by animateColorAsState(
        if (expanded) c.brand.copy(alpha = 0.13f) else c.ink.copy(alpha = 0.055f),
        if (reduced) snap() else tween(150),
        label = "items-date-fill",
    )
    Row(
        modifier.heightIn(min = 40.dp)
            .clip(RoundedRectangle(10.dp))
            .background(fill)
            .clickable(role = Role.Button) { follow(); onClick() }
            .semantics { stateDescription = if (expanded) "已展开" else "已收起" }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        NativeIcon("calendar", c.muted, size = 14.dp)
        BasicText(
            label,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = nativeTextStyle(c.ink, 12.5f, 16f, FontWeight.Bold, tabularNums = true),
        )
        NativeIcon("chevron-down", c.muted, size = 12.dp, modifier = Modifier.rotate(angle))
    }
}

@Composable
private fun ItemsSheetPriorityButton(priority: ItemsPriority, accent: Color, onClick: () -> Unit) {
    val c = LocalNativeColors.current
    val tick = nSelectionFeedback()
    Row(
        Modifier.heightIn(min = 40.dp)
            .clip(RoundedRectangle(10.dp))
            .background(c.ink.copy(alpha = 0.055f))
            .clickable(role = Role.Button) { tick(); onClick() }
            .semantics { contentDescription = "优先级：${priority.label}" }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        NativeIcon("flag", if (priority == ItemsPriority.NORMAL) c.muted else accent, size = 14.dp)
        if (priority != ItemsPriority.NORMAL) {
            BasicText(priority.label, maxLines = 1, style = nativeTextStyle(c.ink, 12f, 15f, FontWeight.Bold))
        }
    }
}

@Composable
private fun ItemsSheetPrimaryButton(
    label: String,
    modifier: Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val c = LocalNativeColors.current
    Box(
        modifier.heightIn(min = 42.dp)
            .clip(Capsule())
            .background(if (enabled) c.brand else c.ink.copy(alpha = 0.06f))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 17.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(label, maxLines = 1, style = nativeTextStyle(if (enabled) c.onBrand else c.muted, 13f, 17f, FontWeight.Bold))
    }
}

@Composable
private fun ItemsSheetTextAction(
    label: String,
    icon: String,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier.heightIn(min = 42.dp)
            .clip(RoundedRectangle(9.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        NativeIcon(icon, color, size = 14.dp)
        BasicText(label, maxLines = 1, style = nativeTextStyle(color, 12.5f, 16f, FontWeight.SemiBold))
    }
}

@Composable
private fun ItemsTimingPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val c = LocalNativeColors.current
    Column(
        modifier.fillMaxWidth()
            .clip(RoundedRectangle(12.dp))
            .background(c.ink.copy(alpha = 0.045f))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/**
 * Content-first bottom edit sheet with real Android `WindowInsets.ime` choreography.
 * In Calendar Grid mode for timed events (when keyboard is closed), the upper grid remains
 * 100% touch-live for direct card dragging, resizing, and switching events.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ItemsEventEditSheet(
    editing: ItemsRecordModel?,
    backdrop: Backdrop,
    isGridMode: Boolean,
    onSheetHeightChanged: (Int) -> Unit,
    dateWindow: ItemsDateWindow,
    doc: AcademicTimetableDoc?,
    exams: List<AcademicExam>,
    allItems: List<ItemsRecordModel>,
    onToggleDone: (ItemsRecordModel) -> Unit,
    onUpdateTitle: (ItemsRecordModel, String) -> Unit,
    onUpdateNote: (ItemsRecordModel, String) -> Unit,
    onCyclePriority: (ItemsRecordModel) -> Unit,
    onMoveToDateTime: (ItemsRecordModel, date: String, startMin: Int?, endMin: Int?, allDay: Boolean) -> Unit,
    onMoveToInbox: (ItemsRecordModel) -> Unit,
    onDelete: (ItemsRecordModel) -> Unit,
    onClose: () -> Unit,
) {
    val open = editing != null
    val retained = remember { arrayOfNulls<ItemsRecordModel>(1) }
    if (editing != null) retained[0] = editing
    val item = editing ?: retained[0] ?: return
    val c = LocalNativeColors.current
    val dark = LocalLiquidGlassDarkTheme.current == true
    val palette = LocalNativePalette.current
    val reduced = LocalLiquidGlassReducedMotion.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val tick = nSelectionFeedback()
    val destructiveHaptic = nHapticFeedback(NativeButtonHaptic.Destructive.release)
    val submitHaptic = nHapticFeedback(NativeButtonHaptic.Submit.release)

    val tones = remember(dark, palette, item.priority, item.done, c) {
        itemsEventTones(dark, palette, item.priority, item.done, c)
    }

    var titleText by remember(item.id) { mutableStateOf(item.title) }
    var noteText by remember(item.id) { mutableStateOf(item.note) }
    var titleFocused by remember(item.id) { mutableStateOf(false) }
    var noteFocused by remember(item.id) { mutableStateOf(false) }
    LaunchedEffect(item.id, item.title, titleFocused) {
        if (!titleFocused && titleText != item.title) titleText = item.title
    }
    LaunchedEffect(item.id, item.note, noteFocused) {
        if (!noteFocused && noteText != item.note) noteText = item.note
    }

    val imeOpen = WindowInsets.isImeVisible
    val typingMode = imeOpen || titleFocused || noteFocused
    var drawerOpen by remember(item.id) { mutableStateOf(!item.isScheduled) }
    var pickInboxDay by remember(item.id) { mutableStateOf(item.date ?: dateWindow.today) }
    val noteFocus = remember { FocusRequester() }
    var dragY by remember(item.id) { mutableFloatStateOf(0f) }
    LaunchedEffect(open) { if (open) dragY = 0f }

    val showDimOverlay = !isGridMode || !item.isTimed || typingMode

    BackHandler(enabled = open) {
        if (typingMode) {
            focusManager.clearFocus(force = true)
            keyboard?.hide()
        } else {
            onClose()
        }
    }

    AnimatedVisibility(visible = open, enter = EnterTransition.None, exit = ExitTransition.None) {
        val dimAlpha by animateFloatAsState(
            when {
                !showDimOverlay -> 0f
                typingMode -> 0.42f
                else -> 0.34f
            },
            if (reduced) snap() else tween(220),
            label = "edit-dim",
        )

        Box(Modifier.fillMaxSize()) {
            if (showDimOverlay || dimAlpha > 0.01f) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .animateEnterExit(enter = itemsScrimEnter(reduced), exit = itemsScrimExit(reduced))
                        .background(Color.Black.copy(alpha = dimAlpha))
                        .then(
                            if (showDimOverlay) {
                                Modifier.pointerInput(typingMode) {
                                    detectTapGestures {
                                        if (typingMode) {
                                            focusManager.clearFocus(force = true)
                                            keyboard?.hide()
                                        } else {
                                            onClose()
                                        }
                                    }
                                }
                            } else Modifier,
                        ),
                )
            }

            val sheetShape = remember { RoundedRectangle(26.dp) }
            LiquidDialogSurface(
                backdrop = backdrop,
                shape = sheetShape,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .animateEnterExit(enter = itemsSheetEnter(reduced), exit = itemsSheetExit(reduced))
                    .widthIn(max = 600.dp)
                    .fillMaxWidth()
                    .onSizeChanged { size ->
                        if (open && isGridMode && item.isTimed && !typingMode) onSheetHeightChanged(size.height)
                    }
                    .padding(start = 10.dp, end = 10.dp, bottom = 10.dp)
                    .navigationBarsPadding()
                    .imePadding()
                    .graphicsLayer { translationY = (dragY * 0.65f).coerceAtLeast(0f) }
                    .pointerInput(item.id) { detectTapGestures(onTap = {}) }
                    .testTag("items-edit-sheet"),
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
                ) {

                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(22.dp)
                            .pointerInput(item.id) {
                                detectVerticalDragGestures(
                                    onDragEnd = {
                                        if (dragY > 56.dp.toPx()) onClose()
                                        else scope.launch { animate(dragY, 0f, animationSpec = spring(0.8f, 420f)) { v, _ -> dragY = v } }
                                    },
                                    onDragCancel = { scope.launch { animate(dragY, 0f, animationSpec = spring(0.8f, 420f)) { v, _ -> dragY = v } } },
                                ) { ch, dragAmount ->
                                    ch.consume()
                                    dragY = (dragY + dragAmount).coerceAtLeast(0f)
                                }
                            }
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { onClose() }
                            .semantics { contentDescription = "收起编辑" },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(Modifier.size(36.dp, 4.dp).clip(Capsule()).background(c.ink.copy(alpha = 0.20f)))
                    }

                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            BasicTextField(
                                value = titleText,
                                onValueChange = { next ->
                                    val capped = next.take(200)
                                    titleText = capped
                                    val trimmed = capped.trim()
                                    if (trimmed.isNotEmpty() && trimmed != item.title) onUpdateTitle(item, trimmed)
                                },
                                modifier = Modifier.weight(1f)
                                    .onFocusChanged { titleFocused = it.isFocused }
                                    .padding(start = 10.dp)
                                    .testTag("items-edit-title"),
                                maxLines = 3,
                                textStyle = nativeTextStyle(
                                    if (item.done) c.muted else c.ink, 18f, 24f, FontWeight.Bold,
                                    decoration = if (item.done) TextDecoration.LineThrough else TextDecoration.None,
                                ),
                                cursorBrush = SolidColor(c.brand),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                                keyboardActions = KeyboardActions(onNext = { noteFocus.requestFocus() }),
                                decorationBox = { inner ->
                                    Box {
                                        if (titleText.isEmpty()) NText("事项标题", size = 18, muted = true, bold = true)
                                        inner()
                                    }
                                },
                            )
                            ItemsCheckbox(
                                done = item.done,
                                accent = c.muted,
                                title = item.title,
                                onToggle = { onToggleDone(item) },
                                sizeDp = 44,
                                square = true,
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        ItemsSheetNoteField(
                            value = noteText,
                            onValueChange = { next ->
                                val capped = next.take(4000)
                                noteText = capped
                                onUpdateNote(item, capped)
                            },
                            modifier = Modifier.focusRequester(noteFocus)
                                .onFocusChanged { noteFocused = it.isFocused }
                                .testTag("items-edit-note"),
                        )

                        // Read-first metadata strip (collapses smoothly while typing)
                        AnimatedVisibility(
                            visible = !typingMode,
                            enter = if (reduced) EnterTransition.None else expandVertically(spring(0.86f, 480f)) + fadeIn(tween(160)),
                            exit = if (reduced) ExitTransition.None else shrinkVertically(tween(150)) + fadeOut(tween(120)),
                        ) {
                            Column(
                                Modifier.fillMaxWidth().padding(top = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                val dtText = when {
                                    item.isDeadlinePoint -> "${AcademicClock.slash(item.date!!)} ${AcademicClock.weekdayText(AcademicClock.weekday(item.date))} · ${item.startClock} 截止"
                                    item.isTimed -> "${AcademicClock.slash(item.date!!)} ${AcademicClock.weekdayText(AcademicClock.weekday(item.date))} · ${item.startClock}–${item.endClock}"
                                    item.isScheduled -> "${AcademicClock.slash(item.date!!)} ${AcademicClock.weekdayText(AcademicClock.weekday(item.date))} · ${item.untimedLabel}"
                                    else -> "待安排"
                                }
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    ItemsSheetScheduleButton(
                                        dtText,
                                        drawerOpen,
                                        { drawerOpen = !drawerOpen },
                                        Modifier.weight(1f).testTag("items-edit-schedule"),
                                    )
                                    ItemsSheetPriorityButton(item.priority, tones.accent) { onCyclePriority(item) }
                                }

                                // Course clash hint + 1-tap free slot relocation
                                if (item.isTimed && !item.isDeadlinePoint) {
                                    val d = item.date!!
                                    val sMin = item.startMin!!
                                    val eMin = item.endMin!!
                                    val dur = eMin - sMin
                                    val clashName = remember(d, sMin, eMin, doc, exams) {
                                        AcademicTimetable.planFor(doc, d).blocks
                                            .firstOrNull { it.startMin < eMin && sMin < it.endMin }?.course?.title
                                            ?: exams.firstOrNull { ex ->
                                                ex.date == d && ex.startMs != null && run {
                                                    val xs = AcademicClock.moment(ex.startMs).minutes
                                                    val xe = (xs + 120).coerceAtMost(ITEMS_GRID_END_MIN)
                                                    xs < eMin && sMin < xe
                                                }
                                            }?.title
                                    }
                                    if (clashName != null) {
                                        val busy = remember(d, doc, exams, allItems, item.id) {
                                            ItemsSmartScheduler.busyIntervalsOn(d, doc, exams, allItems, ignoreItemId = item.id)
                                        }
                                        val availableFrom = ItemsSmartScheduler.upcomingStart(d, AcademicClock.moment())
                                        val freeSlot = remember(busy, dur, sMin, availableFrom) {
                                            ItemsSmartScheduler.findFreeSlot(dur, sMin, busy, dayStartMin = availableFrom)
                                        }
                                        val warnTone = nativeSemanticTones(dark, palette).amber
                                        Row(
                                            Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedRectangle(11.dp))
                                                .background(tones.warnAccent.copy(alpha = 0.14f))
                                                .padding(horizontal = 10.dp, vertical = 7.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                        ) {
                                            Row(
                                                Modifier.weight(1f),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            ) {
                                                Box(Modifier.size(6.dp).clip(CircleShape).background(tones.warnAccent))
                                                BasicText(
                                                    "与「${itemsCleanTitle(clashName)}」撞课",
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    style = nativeTextStyle(if (dark) warnTone else tones.warnInk, 12f, 15f, FontWeight.SemiBold),
                                                )
                                            }
                                            if (freeSlot != null) {
                                                Box(
                                                    Modifier
                                                        .clip(RoundedRectangle(7.dp))
                                                        .background(tones.warnAccent.copy(alpha = 0.22f))
                                                        .clickable(role = Role.Button) {
                                                            submitHaptic()
                                                            onMoveToDateTime(item, d, freeSlot.first, freeSlot.second, false)
                                                        }
                                                        .padding(horizontal = 8.dp, vertical = 3.dp)
                                                        .testTag("items-clash-fix"),
                                                ) {
                                                    BasicText(
                                                        "改到 ${AcademicClock.clock(freeSlot.first)}",
                                                        maxLines = 1,
                                                        softWrap = false,
                                                        style = nativeTextStyle(if (dark) warnTone else tones.warnInk, 12f, 15f, FontWeight.Bold, tabularNums = true),
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                AnimatedVisibility(
                                    visible = drawerOpen,
                                    enter = if (reduced) EnterTransition.None else expandVertically(spring(0.86f, 480f)) + fadeIn(tween(150)),
                                    exit = if (reduced) ExitTransition.None else shrinkVertically(tween(140)) + fadeOut(tween(110)),
                                ) {
                                    val shownDate = item.date ?: pickInboxDay
                                    ItemsTimingPanel {
                                        ItemsDayStepper(shownDate) { date ->
                                            if (item.isScheduled) onMoveToDateTime(item, date, item.startMin, item.endMin, item.isAllDay)
                                            else pickInboxDay = date
                                        }
                                        FlowRow(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp),
                                            itemVerticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            when {
                                                item.isDeadlineOnly -> ItemsTimeChip(
                                                    minutes = item.startMin ?: 18 * 60,
                                                    label = "截止时间",
                                                    displayText = if (item.isDeadlinePoint) null else "设置截止时间",
                                                ) { onMoveToDateTime(item, shownDate, it, null, false) }

                                                item.isTimed -> {
                                                    ItemsTimeRangePicker(item.startMin!!, item.endMin!!) { start, end ->
                                                        onMoveToDateTime(item, shownDate, start, end, false)
                                                    }
                                                    if (!item.hasIndependentDates) {
                                                        DrawerMiniPill("全天", muted = true) {
                                                            tick()
                                                            onMoveToDateTime(item, shownDate, null, null, true)
                                                        }
                                                    }
                                                }

                                                else -> {
                                                    if (!item.hasPlanWindow) {
                                                        DrawerMiniPill("全天", muted = !item.isScheduled) {
                                                            tick()
                                                            if (!item.isScheduled) onMoveToDateTime(item, shownDate, null, null, true)
                                                        }
                                                    }
                                                    ItemsTimeChip(14 * 60, "开始时间", displayText = "选时间") { picked ->
                                                        val start = picked.coerceIn(ITEMS_GRID_START_MIN, ITEMS_GRID_END_MIN - ITEMS_MIN_DURATION)
                                                        onMoveToDateTime(item, shownDate, start, (start + 60).coerceAtMost(ITEMS_GRID_END_MIN), false)
                                                    }
                                                    if (!item.isScheduled) {
                                                        val slot = remember(shownDate, doc, exams, allItems, item.id) {
                                                            val busy = ItemsSmartScheduler.busyIntervalsOn(shownDate, doc, exams, allItems, item.id)
                                                            ItemsSmartScheduler.findFreeSlot(
                                                                60, 12 * 60 + 30, busy,
                                                                dayStartMin = ItemsSmartScheduler.upcomingStart(shownDate, AcademicClock.moment()),
                                                            )
                                                        }
                                                        DrawerMiniPill(
                                                            if (slot == null) "暂无空档" else "排入空档",
                                                            muted = slot == null,
                                                            enabled = slot != null,
                                                        ) {
                                                            submitHaptic()
                                                            slot?.let { onMoveToDateTime(item, shownDate, it.first, it.second, false) }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    NDivider()
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(Modifier.weight(1f)) {
                            androidx.compose.animation.AnimatedVisibility(
                                visible = !typingMode,
                                enter = if (reduced) EnterTransition.None else fadeIn(tween(140)),
                                exit = if (reduced) ExitTransition.None else fadeOut(tween(100)),
                            ) {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    itemVerticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (item.isScheduled && (!item.hasIndependentDates || item.isTimed && !item.isDeadlinePoint)) {
                                        ItemsSheetTextAction(
                                            label = if (item.hasIndependentDates) "取消排期" else "移到待安排",
                                            icon = "inbox",
                                            color = c.muted,
                                            modifier = Modifier.testTag("items-edit-unsched"),
                                        ) {
                                            tick()
                                            onMoveToInbox(item)
                                        }
                                    }
                                    ItemsSheetTextAction("删除", "trash", c.danger, Modifier.testTag("items-edit-delete")) {
                                        destructiveHaptic()
                                        onDelete(item)
                                    }
                                }
                            }
                        }
                        ItemsSheetPrimaryButton("完成编辑", Modifier.testTag("items-edit-done")) {
                            submitHaptic()
                            focusManager.clearFocus(force = true)
                            keyboard?.hide()
                            onClose()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DrawerMiniPill(label: String, muted: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val c = LocalNativeColors.current
    Box(
        Modifier
            .heightIn(min = 36.dp)
            .clip(RoundedRectangle(8.dp))
            .background(if (muted) c.ink.copy(alpha = 0.06f) else c.brand.copy(alpha = 0.14f))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            label,
            maxLines = 1,
            softWrap = false,
            style = nativeTextStyle(if (muted) c.muted else c.brand, 11.5f, 14f, FontWeight.Bold),
        )
    }
}

/**
 * Contextual detail modals built with the app's native single-window `NSheet` host:
 * Course details (+ linked homework & quick-add), Homework details (+ smart triage), and Day All-Day summary.
 */
internal sealed interface ItemsContextModal {
    data class Course(val block: AcademicBlock, val date: String) : ItemsContextModal
    data class Homework(val task: AcademicTask) : ItemsContextModal
    data class AllDay(val date: String) : ItemsContextModal
}

@Composable
internal fun ItemsContextualSheetHost(
    modal: ItemsContextModal?,
    tasks: List<AcademicTask>,
    doneTaskIds: Set<String>,
    items: List<ItemsRecordModel>,
    now: AcademicMoment,
    onDismiss: () -> Unit,
    onToggleCourseTask: (String) -> Unit,
    onToggleItemDone: (ItemsRecordModel) -> Unit,
    onOpenItemEdit: (ItemsRecordModel) -> Unit,
    onTriageHomework: (AcademicTask) -> Unit,
    onJumpToScheduledHomework: (ItemsRecordModel) -> Unit,
    onQuickAddForCourse: (AcademicBlock, String) -> Unit,
    onQuickAddAllDay: (String) -> Unit,
) {
    val dark = LocalLiquidGlassDarkTheme.current == true
    val palette = LocalNativePalette.current
    var childTask by remember(modal) { mutableStateOf<AcademicTask?>(null) }
    val homeworkSheet: @Composable (AcademicTask, () -> Unit) -> Unit = { task, close ->
        ItemsHomeworkSheet(
            task = task,
            done = task.id in doneTaskIds,
            linked = items.firstOrNull { it.schoolTaskId == task.id },
            onDismiss = close,
            onToggleCourseTask = onToggleCourseTask,
            onTriageHomework = onTriageHomework,
            onJumpToScheduledHomework = onJumpToScheduledHomework,
        )
    }
    val nestedHomeworkSheet: @Composable () -> Unit = {
        childTask?.let { task -> homeworkSheet(task) { childTask = null } }
    }

    when (modal) {
        is ItemsContextModal.Course -> {
            val block = modal.block
            val pos = AcademicCalendar.position(modal.date)
            val relatedHomework = tasks.filter { it.course == block.course.title }
            val (doneCount, totalCount) = AcademicTimetable.progress(block.course, pos?.week ?: 0, now.weekday, now.minutes)
            val swatch = nativeCourseSwatch(block.course.hue, dark, palette)

            NSheet(block.course.title, onDismiss) {
                NText(
                    "${AcademicClock.monthDay(modal.date)} ${AcademicClock.weekdayText(block.weekday)} ${block.start}–${block.end}",
                    13,
                    muted = true,
                    tabularNums = true,
                )
                NRow("地点", block.location.ifEmpty { "教务未标注地点" }, leadingIcon = "pin", leadingTint = swatch.mark)
                NRow("教师", block.teacher.ifEmpty { block.course.teacher }.ifEmpty { "未标注" }, leadingIcon = "me", leadingTint = swatch.mark)
                if (totalCount > 0) {
                    NRow("本学期进度", trailing = "$doneCount / $totalCount 次")
                }
                if (relatedHomework.isNotEmpty()) {
                    NText("本课程作业", 14, bold = true, muted = true)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        relatedHomework.forEach { task ->
                            ItemsSheetTaskRow(
                                title = task.title,
                                subtitle = itemsTaskDeadline(task),
                                done = task.id in doneTaskIds,
                                onToggle = { onToggleCourseTask(task.id) },
                                onOpen = { childTask = task },
                            )
                        }
                    }
                }
                NButton(
                    "+ 记本课事项",
                    { onQuickAddForCourse(block, modal.date) },
                    primary = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                nestedHomeworkSheet()
            }
        }
        is ItemsContextModal.Homework -> homeworkSheet(modal.task, onDismiss)
        is ItemsContextModal.AllDay -> {
            val dues = tasks.filter { it.dueMs != null && AcademicClock.moment(it.dueMs).date == modal.date }
            val allDay = items.filter { it.date == modal.date && it.isAllDay }
            val header = "${AcademicClock.monthDay(modal.date)} ${AcademicClock.weekdayText(AcademicClock.weekday(modal.date))} · 全天与截止"
            NSheet(header, onDismiss) {
                if (dues.isNotEmpty()) {
                    NText("当日截止作业", 13, bold = true, muted = true)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        dues.forEach { task ->
                            ItemsSheetTaskRow(
                                title = task.title,
                                subtitle = listOf(task.course, itemsTaskDeadline(task)).filter { it.isNotBlank() }.joinToString(" · "),
                                done = task.id in doneTaskIds,
                                onToggle = { onToggleCourseTask(task.id) },
                                onOpen = { childTask = task },
                            )
                        }
                    }
                }
                if (allDay.isNotEmpty()) {
                    NText("全天事项", 13, bold = true, muted = true)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        allDay.forEach { item ->
                            ItemsSheetTaskRow(
                                title = item.title,
                                subtitle = item.note,
                                done = item.done,
                                onToggle = { onToggleItemDone(item) },
                                onOpen = { onOpenItemEdit(item) },
                            )
                        }
                    }
                }
                if (dues.isEmpty() && allDay.isEmpty()) {
                    NText("暂无全天事项或截止", 14, muted = true)
                }
                NButton(
                    "+ 新增全天事项",
                    { onQuickAddAllDay(modal.date) },
                    primary = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                nestedHomeworkSheet()
            }
        }
        null -> Unit
    }
}

@Composable
private fun ItemsHomeworkSheet(
    task: AcademicTask,
    done: Boolean,
    linked: ItemsRecordModel?,
    onDismiss: () -> Unit,
    onToggleCourseTask: (String) -> Unit,
    onTriageHomework: (AcademicTask) -> Unit,
    onJumpToScheduledHomework: (ItemsRecordModel) -> Unit,
) {
    val c = LocalNativeColors.current
    val context = LocalContext.current
    NSheet(task.title, onDismiss) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.weight(1f)) {
                NRow("截止", itemsTaskDeadline(task), leadingIcon = "clock")
            }
            ItemsCheckbox(done, c.muted, task.title, { onToggleCourseTask(task.id) }, square = true)
        }
        if (task.course.isNotBlank()) NRow("课程", task.course, leadingIcon = "study")
        NRow("来源", AcademicRecords.source(task.source), leadingIcon = "study")
        if (task.schoolStatus.isNotBlank()) NRow("学校状态", task.schoolStatus)
        task.url?.let { url ->
            NButton("打开 ${AcademicRecords.source(task.source)}", { nativeOpenLink(context, url) }, modifier = Modifier.fillMaxWidth(), haptic = NativeButtonHaptic.Navigation)
        }
        if (linked != null && linked.isScheduled) {
            NButton(
                "查看已排日程（${AcademicClock.slash(linked.date!!)}${linked.startClock?.let { " $it" }.orEmpty()}）",
                { onJumpToScheduledHomework(linked) },
                primary = true,
                modifier = Modifier.fillMaxWidth(),
            )
        } else if (!done) {
            NButton(
                "智能排入空闲时段",
                { onTriageHomework(task) },
                primary = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ItemsSheetTaskRow(
    title: String,
    subtitle: String,
    done: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
) {
    val c = LocalNativeColors.current
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedRectangle(12.dp))
            .background(c.ink.copy(alpha = 0.045f))
            .clickable(role = Role.Button, onClick = onOpen)
            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BasicText(
                title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = nativeTextStyle(
                    if (done) c.muted else c.ink, 14f, 19f, FontWeight.SemiBold,
                    decoration = if (done) TextDecoration.LineThrough else TextDecoration.None,
                ),
            )
            if (subtitle.isNotBlank()) NText(subtitle, 12, muted = true, maxLines = 1)
        }
        ItemsCheckbox(done, c.muted, title, onToggle, sizeDp = 44, square = true)
    }
}

/**
 * Instant local NLP Quick-Add sheet with draft preservation, manual schedule picker,
 * conflict-free slot finder, and optional AI online parsing shortcut.
 */
@Composable
internal fun ItemsQuickAddSheet(
    visible: Boolean,
    backdrop: Backdrop,
    dateWindow: ItemsDateWindow,
    focusDate: String,
    defaultDate: String?,
    draftTitle: String,
    draftNote: String,
    draftPriority: ItemsPriority,
    manualDate: String?,
    manualStartMin: Int?,
    manualEndMin: Int?,
    manualAllDay: Boolean,
    tokenOff: Boolean,
    canUseOnlineAi: Boolean,
    doc: AcademicTimetableDoc?,
    exams: List<AcademicExam>,
    allItems: List<ItemsRecordModel>,
    onDraftTitleChange: (String) -> Unit,
    onDraftNoteChange: (String) -> Unit,
    onCyclePriority: () -> Unit,
    onSetManual: (date: String?, startMin: Int?, endMin: Int?, allDay: Boolean, tokenOff: Boolean) -> Unit,
    onOpenAiComposer: () -> Unit,
    onSubmit: (title: String, note: String, priority: ItemsPriority, date: String?, startMin: Int?, endMin: Int?, allDay: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = LocalNativeColors.current
    val dark = LocalLiquidGlassDarkTheme.current == true
    val reduced = LocalLiquidGlassReducedMotion.current
    val tick = nSelectionFeedback()
    val submitHaptic = nHapticFeedback(NativeButtonHaptic.Submit.release)
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val titleFocus = remember { FocusRequester() }
    val noteFocus = remember { FocusRequester() }
    val onDismissLatest by rememberUpdatedState(onDismiss)
    var pickerOpen by remember { mutableStateOf(false) }
    var dragY by remember { mutableFloatStateOf(0f) }

    fun dismiss() {
        focusManager.clearFocus(force = true)
        keyboard?.hide()
        onDismissLatest()
    }

    BackHandler(enabled = visible) {
        if (pickerOpen) pickerOpen = false else dismiss()
    }

    LaunchedEffect(visible) {
        if (visible) {
            dragY = 0f
            pickerOpen = false
            withFrameNanos { }
            runCatching { titleFocus.requestFocus() }
            keyboard?.show()
        }
    }

    val parsed = remember(draftTitle, manualDate, manualStartMin, manualEndMin, manualAllDay, tokenOff, dateWindow.today, focusDate, defaultDate) {
        when {
            manualDate != null && !tokenOff -> ItemsQuickParseResult(
                cleanTitle = draftTitle.trim().ifEmpty { "未命名事项" },
                hitDate = manualDate,
                startMin = if (manualAllDay) null else manualStartMin,
                endMin = if (manualAllDay) null else manualEndMin,
                isAllDay = manualAllDay || manualStartMin == null,
            )
            tokenOff -> ItemsQuickParseResult(draftTitle.trim(), null, null, null, false)
            else -> ItemsQuickParser.parse(draftTitle, dateWindow.today, defaultDate ?: focusDate).let { result ->
                if (result.hitDate == null && defaultDate != null) result.copy(hitDate = defaultDate, isAllDay = true)
                else result
            }
        }
    }

    fun triggerSubmit() {
        if (draftTitle.isBlank()) return
        submitHaptic()
        focusManager.clearFocus(force = true)
        keyboard?.hide()
        onSubmit(
            parsed.cleanTitle,
            draftNote.trim(),
            draftPriority,
            parsed.hitDate,
            parsed.startMin,
            parsed.endMin,
            parsed.isAllDay,
        )
    }

    AnimatedVisibility(
        visible = visible,
        enter = EnterTransition.None,
        exit = ExitTransition.None,
    ) {
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .animateEnterExit(enter = itemsScrimEnter(reduced), exit = itemsScrimExit(reduced))
                    .background(Color.Black.copy(alpha = 0.28f))
                    .pointerInput(Unit) { detectTapGestures { dismiss() } },
            )
            LiquidDialogSurface(
                backdrop = backdrop,
                shape = RoundedRectangle(26.dp),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .animateEnterExit(enter = itemsSheetEnter(reduced), exit = itemsSheetExit(reduced))
                    .widthIn(max = 600.dp)
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 8.dp, bottom = 8.dp)
                    .navigationBarsPadding()
                    .imePadding()
                    .graphicsLayer {
                        translationY = (dragY * 0.65f).coerceAtLeast(0f)
                    }
                    .pointerInput(Unit) { detectTapGestures(onTap = {}) }
                    .testTag("items-quick-add"),
            ) {
                Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(22.dp)
                            .pointerInput(Unit) {
                                detectVerticalDragGestures(
                                    onDragEnd = {
                                        if (dragY > 56.dp.toPx()) dismiss()
                                        else scope.launch { animate(dragY, 0f, animationSpec = spring(0.8f, 420f)) { v, _ -> dragY = v } }
                                    },
                                    onDragCancel = { scope.launch { animate(dragY, 0f, animationSpec = spring(0.8f, 420f)) { v, _ -> dragY = v } } },
                                ) { ch, dragAmount ->
                                    ch.consume()
                                    dragY = (dragY + dragAmount).coerceAtLeast(0f)
                                }
                            }
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                role = Role.Button,
                            ) { dismiss() }
                            .semantics { contentDescription = "收起新建" },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(Modifier.size(36.dp, 4.dp).clip(Capsule()).background(c.ink.copy(alpha = 0.20f)))
                    }

                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                        BasicTextField(
                            value = draftTitle,
                            onValueChange = { next ->
                                if (tokenOff && next.isEmpty()) onSetManual(null, null, null, false, false)
                                onDraftTitleChange(next.take(200))
                            },
                            modifier = Modifier.fillMaxWidth()
                                .focusRequester(titleFocus)
                                .onFocusChanged { if (it.isFocused) pickerOpen = false }
                                .heightIn(min = 44.dp)
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                                .testTag("items-quick-title"),
                            maxLines = 3,
                            textStyle = nativeTextStyle(c.ink, 18f, 24f, FontWeight.Bold),
                            cursorBrush = SolidColor(c.brand),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                            keyboardActions = KeyboardActions(onNext = { noteFocus.requestFocus() }),
                            decorationBox = { inner ->
                                Box {
                                    if (draftTitle.isEmpty()) NText("记一件事…", size = 18, muted = true, bold = true)
                                    inner()
                                }
                            },
                        )
                        Spacer(Modifier.height(6.dp))
                        ItemsSheetNoteField(
                            value = draftNote,
                            onValueChange = { onDraftNoteChange(it.take(2000)) },
                            modifier = Modifier.focusRequester(noteFocus)
                                .onFocusChanged { if (it.isFocused) pickerOpen = false }
                                .testTag("items-quick-note"),
                        )

                        Spacer(Modifier.height(10.dp))
                        val date = parsed.hitDate
                        val dateLabel = if (date == null) "待安排" else {
                            val day = "${AcademicClock.slash(date)} ${AcademicClock.weekdayText(AcademicClock.weekday(date))}"
                            val time = parsed.startMin?.let(AcademicClock::clock)
                            val end = parsed.endMin?.let(AcademicClock::clock)
                            day + when {
                                time == null -> " · 全天"
                                end == null -> " · $time"
                                else -> " · $time–$end"
                            }
                        }
                        val prioTone = itemsEventTones(dark, LocalNativePalette.current, draftPriority, false, c)
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ItemsSheetScheduleButton(
                                label = dateLabel,
                                expanded = pickerOpen,
                                onClick = {
                                    if (!pickerOpen) {
                                        focusManager.clearFocus(force = true)
                                        keyboard?.hide()
                                    }
                                    pickerOpen = !pickerOpen
                                },
                                modifier = Modifier.weight(1f).testTag("items-quick-schedule"),
                            )
                            ItemsSheetPriorityButton(draftPriority, prioTone.accent, onCyclePriority)
                        }

                        AnimatedVisibility(
                            visible = pickerOpen,
                            enter = if (reduced) EnterTransition.None else expandVertically() + fadeIn(tween(150)),
                            exit = if (reduced) ExitTransition.None else shrinkVertically() + fadeOut(tween(110)),
                        ) {
                            val pickedDate = parsed.hitDate ?: defaultDate ?: focusDate
                            val start = parsed.startMin
                            val end = parsed.endMin
                            val today = dateWindow.today
                            val preferred = 14 * 60
                            val slot = remember(pickedDate, doc, exams, allItems) {
                                val busy = ItemsSmartScheduler.busyIntervalsOn(pickedDate, doc, exams, allItems)
                                ItemsSmartScheduler.findFreeSlot(
                                    60, preferred, busy,
                                    dayStartMin = ItemsSmartScheduler.upcomingStart(pickedDate, AcademicClock.moment()),
                                )
                            }

                            fun pickDate(next: String) {
                                onSetManual(next, start, end, start == null, false)
                            }

                            ItemsTimingPanel(Modifier.padding(top = 8.dp)) {
                                FlowRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                    itemVerticalAlignment = Alignment.CenterVertically,
                                ) {
                                    ItemsDayStepper(pickedDate, ::pickDate)
                                    val shortcuts = listOf(today to "今天", AcademicClock.addDays(today, 1) to "明天", AcademicClock.addDays(today, 2) to "后天")
                                    shortcuts.forEach { (day, name) ->
                                        DrawerMiniPill(name, muted = date != day) { tick(); pickDate(day) }
                                    }
                                }
                                FlowRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                    itemVerticalAlignment = Alignment.CenterVertically,
                                ) {
                                    DrawerMiniPill("待安排", muted = date != null) {
                                        tick()
                                        onSetManual(null, null, null, false, true)
                                        pickerOpen = false
                                    }
                                    DrawerMiniPill("全天", muted = !parsed.isAllDay) {
                                        tick()
                                        onSetManual(pickedDate, null, null, true, false)
                                    }
                                    if (start == null) {
                                        ItemsTimeChip(preferred, "开始时间", displayText = "选时间") { minutes ->
                                            val next = minutes.coerceIn(ITEMS_GRID_START_MIN, ITEMS_GRID_END_MIN - ITEMS_MIN_DURATION)
                                            onSetManual(pickedDate, next, (next + 60).coerceAtMost(ITEMS_GRID_END_MIN), false, false)
                                        }
                                    } else {
                                        ItemsTimeRangePicker(start, end ?: (start + 60).coerceAtMost(ITEMS_GRID_END_MIN)) { next, finish ->
                                            onSetManual(pickedDate, next, finish, false, false)
                                        }
                                    }
                                    DrawerMiniPill(if (slot == null) "暂无空档" else "智能空档", muted = true, enabled = slot != null) {
                                        tick()
                                        slot?.let { onSetManual(pickedDate, it.first, it.second, false, false) }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    NDivider()
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (canUseOnlineAi) {
                            ItemsSheetTextAction("AI", "spark", c.brand) {
                                tick()
                                dismiss()
                                onOpenAiComposer()
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        ItemsSheetPrimaryButton(
                            label = "保存",
                            modifier = Modifier.testTag("items-quick-submit").semantics { contentDescription = "保存事项" },
                            enabled = draftTitle.isNotBlank(),
                            onClick = ::triggerSubmit,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ItemsTimeRangePicker(start: Int, end: Int, onPick: (Int, Int) -> Unit) {
    val c = LocalNativeColors.current
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ItemsTimeChip(start, "开始时间") { picked ->
            val duration = (end - start).coerceAtLeast(ITEMS_MIN_DURATION)
            val next = picked.coerceIn(ITEMS_GRID_START_MIN, ITEMS_GRID_END_MIN - ITEMS_MIN_DURATION)
            onPick(next, (next + duration).coerceAtMost(ITEMS_GRID_END_MIN))
        }
        BasicText("–", style = nativeTextStyle(c.muted, 12f, 15f))
        ItemsTimeChip(end, "结束时间") { picked ->
            onPick(start, picked.coerceIn(start + ITEMS_MIN_DURATION, ITEMS_GRID_END_MIN))
        }
    }
}

private fun itemsSheetEnter(reduced: Boolean): EnterTransition =
    if (reduced) EnterTransition.None else slideInVertically(spring(dampingRatio = 0.9f, stiffness = 380f)) { it }

private fun itemsSheetExit(reduced: Boolean): ExitTransition =
    if (reduced) ExitTransition.None else slideOutVertically(tween(210, easing = CubicBezierEasing(0.32f, 0f, 0.67f, 1f))) { it }

private fun itemsScrimEnter(reduced: Boolean): EnterTransition =
    if (reduced) EnterTransition.None else fadeIn(tween(200))

private fun itemsScrimExit(reduced: Boolean): ExitTransition =
    if (reduced) ExitTransition.None else fadeOut(tween(180))

/** Tappable `HH:mm` chip backed by the platform 24-hour time picker. */
@Composable
internal fun ItemsTimeChip(
    minutes: Int,
    label: String,
    displayText: String? = null,
    onPick: (Int) -> Unit,
) {
    val c = LocalNativeColors.current
    val dark = LocalLiquidGlassDarkTheme.current == true
    val context = LocalContext.current
    val tick = nSelectionFeedback()
    val shape = remember { RoundedRectangle(8.dp) }
    Box(
        Modifier
            .heightIn(min = 36.dp)
            .widthIn(min = 54.dp)
            .clip(shape)
            .background(c.surface.copy(alpha = if (dark) 0.42f else 0.92f))
            .border(0.75.dp, c.border.copy(alpha = if (dark) 0.7f else 1f), shape)
            .clickable(role = Role.Button) {
                tick()
                android.app.TimePickerDialog(
                    context,
                    if (dark) android.R.style.Theme_DeviceDefault_Dialog_Alert else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert,
                    { _, h, m -> onPick(h * 60 + m) },
                    minutes / 60,
                    minutes % 60,
                    true,
                ).show()
            }
            .semantics { contentDescription = displayText ?: "$label ${AcademicClock.clock(minutes)}" }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            displayText ?: AcademicClock.clock(minutes),
            maxLines = 1,
            softWrap = false,
            style = nativeTextStyle(c.ink, 12.5f, 15f, FontWeight.Bold),
        )
    }
}

/** `‹ 11/24 周二 ›` day stepper shared by the edit drawer and the quick-add picker. */
@Composable
internal fun ItemsDayStepper(date: String, onPick: (String) -> Unit) {
    val c = LocalNativeColors.current
    val dark = LocalLiquidGlassDarkTheme.current == true
    val context = LocalContext.current
    val tick = nSelectionFeedback()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        ItemsStepButton("chevron-left", "前一天") { tick(); onPick(AcademicClock.addDays(date, -1)) }
        BasicText(
            "${AcademicClock.slash(date)} ${AcademicClock.weekdayText(AcademicClock.weekday(date))}",
            modifier = Modifier.clip(RoundedRectangle(8.dp))
                .background(c.surface.copy(alpha = if (dark) 0.42f else 0.92f))
                .clickable(role = Role.Button) {
                    tick()
                    val current = LocalDate.parse(date)
                    android.app.DatePickerDialog(
                        context,
                        if (dark) android.R.style.Theme_DeviceDefault_Dialog_Alert else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert,
                        { _, year, month, day -> onPick(LocalDate.of(year, month + 1, day).toString()) },
                        current.year, current.monthValue - 1, current.dayOfMonth,
                    ).show()
                }
                .semantics { contentDescription = "选择日期 ${AcademicClock.monthDay(date)}" }
                .padding(horizontal = 10.dp, vertical = 10.dp),
            maxLines = 1,
            softWrap = false,
            style = nativeTextStyle(c.ink, 12f, 15f, FontWeight.Bold),
        )
        ItemsStepButton("chevron-right", "后一天") { tick(); onPick(AcademicClock.addDays(date, 1)) }
    }
}

@Composable
private fun ItemsStepButton(icon: String, label: String, onClick: () -> Unit) {
    val c = LocalNativeColors.current
    val dark = LocalLiquidGlassDarkTheme.current == true
    Box(
        Modifier
            .size(36.dp)
            .clip(RoundedRectangle(8.dp))
            .background(c.surface.copy(alpha = if (dark) 0.42f else 0.88f))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        NativeIcon(icon, c.muted, size = 12.dp, strokeWidth = 2.4f)
    }
}
