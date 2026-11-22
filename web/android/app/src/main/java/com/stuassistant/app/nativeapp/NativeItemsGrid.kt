package com.stuassistant.app.nativeapp

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import com.stuassistant.app.glass.LocalLiquidGlassDarkTheme
import com.stuassistant.app.glass.LocalLiquidGlassReducedMotion
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt

/** The recent slope also covers flicks with too few delivered samples for a curved fit. */
internal class ItemsDatePanVelocity(down: PointerInputChange) {
    private data class Sample(val timeMillis: Long, val x: Float)

    private val tracker = VelocityTracker().apply { addPointerInputChange(down) }
    private val motion = ArrayDeque<Sample>().apply { addLast(Sample(down.uptimeMillis, down.position.x)) }
    private var timeMillis = down.uptimeMillis

    fun add(change: PointerInputChange) {
        tracker.addPointerInputChange(change)
        timeMillis = change.uptimeMillis
        if (abs(change.position.x - motion.last().x) < 0.5f) return
        motion.addLast(Sample(timeMillis, change.position.x))
        while (motion.size > 2 && timeMillis - motion.first().timeMillis > 100L) motion.removeFirst()
    }

    fun velocity(): Float {
        if (motion.size < 2 || timeMillis - motion.last().timeMillis > 70L) return 0f
        val start = motion.first()
        val slope = (motion.last().x - start.x) * 1000f / (timeMillis - start.timeMillis).coerceAtLeast(12L)
        val estimate = tracker.calculateVelocity().x
        return if (estimate * slope > 0f && abs(estimate) >= 0.7f * abs(slope)) estimate else slope
    }
}

internal enum class ItemsDragKind { MOVE, RESIZE_TOP, RESIZE_BOTTOM }

private class GridDragSession(
    val item: ItemsRecordModel,
    val kind: ItemsDragKind,
    val origDayIndex: Int,
    val origStartMin: Int,
    val origEndMin: Int,
    val origWorldLeftPx: Float, // relative to day 0 start inside cols-track
    val origTopPx: Float,
    val origWidthPx: Float,
    val origCalOffset: Float,
    val origScrollY: Float,
    val startPointerRoot: Offset,
) {
    var curPointerRoot by mutableStateOf(startPointerRoot)
    var targetDayIndex by mutableIntStateOf(origDayIndex)
    var targetStartMin by mutableIntStateOf(origStartMin)
    var targetEndMin by mutableIntStateOf(origEndMin)
    var renderOffsetPx by mutableStateOf(Offset.Zero)
}

internal data class BlockLaneGeometry(
    val leftPx: Float,
    val widthPx: Float,
)

internal fun computeBlockGeometry(
    colWidthPx: Float,
    laneInfo: ItemsPlacedLane?,
    dens: Int,
    density: Float,
): BlockLaneGeometry {
    val lanes = laneInfo?.lanes ?: 1
    val lane = laneInfo?.lane ?: 0
    val edgePadPx = minOf(density, colWidthPx / 4f)
    val usableWidth = colWidthPx - 2f * edgePadPx
    val gap = minOf((if (dens >= 7) 1.5f else 3f) * density, usableWidth / (2f * lanes))
    val netWidth = usableWidth - (lanes - 1) * gap

    if (laneInfo?.hasFixedRail == true) {
        val preferredRailWidth = when {
            dens >= 7 -> 5f * density
            dens >= 5 -> 16f * density
            dens <= 1 -> (colWidthPx * 0.30f).coerceAtLeast(36f * density)
            else -> 22f * density
        }
        val railWidth = preferredRailWidth.coerceAtMost(netWidth * 0.45f)
        if (lane == 0) return BlockLaneGeometry(edgePadPx, railWidth)
        val laneWidth = (netWidth - railWidth) / (lanes - 1)
        return BlockLaneGeometry(edgePadPx + railWidth + gap + (lane - 1) * (laneWidth + gap), laneWidth)
    }
    val laneWidth = netWidth / lanes
    return BlockLaneGeometry(edgePadPx + lane * (laneWidth + gap), laneWidth)
}

private fun recomputeDragTargets(
    drag: GridDragSession,
    calOffset: Float,
    scrollY: Float,
    stridePx: Float,
    colWidthPx: Float,
    pxPerMin: Float,
    totalGridHeightPx: Float,
    totalDays: Int,
    dateWindow: ItemsDateWindow,
    onLivePillText: (String?) -> Unit,
) {
    val worldDx = (drag.curPointerRoot.x - drag.startPointerRoot.x) + (calOffset - drag.origCalOffset) * stridePx
    val worldDy = (drag.curPointerRoot.y - drag.startPointerRoot.y) + (scrollY - drag.origScrollY)
    val dur = drag.origEndMin - drag.origStartMin
    val durPx = dur * pxPerMin

    when (drag.kind) {
        ItemsDragKind.RESIZE_BOTTOM -> {
            val deltaMin = (((worldDy / pxPerMin) / 15f).roundToInt() * 15)
            drag.targetStartMin = drag.origStartMin
            drag.targetEndMin = (drag.origEndMin + deltaMin).coerceIn(drag.origStartMin + ITEMS_MIN_DURATION, ITEMS_GRID_END_MIN)
            drag.targetDayIndex = drag.origDayIndex
            drag.renderOffsetPx = Offset.Zero
        }
        ItemsDragKind.RESIZE_TOP -> {
            val deltaMin = (((worldDy / pxPerMin) / 15f).roundToInt() * 15)
            drag.targetEndMin = drag.origEndMin
            drag.targetStartMin = (drag.origStartMin + deltaMin).coerceIn(ITEMS_GRID_START_MIN, drag.origEndMin - ITEMS_MIN_DURATION)
            drag.targetDayIndex = drag.origDayIndex
            drag.renderOffsetPx = Offset(0f, (drag.targetStartMin - ITEMS_GRID_START_MIN) * pxPerMin - drag.origTopPx)
        }
        ItemsDragKind.MOVE -> {
            val minDy = -drag.origTopPx
            val bottomClearance = if (drag.item.isDeadlinePoint) ITEMS_MIN_DURATION * pxPerMin else durPx
            val maxDy = (totalGridHeightPx - bottomClearance - drag.origTopPx).coerceAtLeast(minDy)
            val clampedDy = worldDy.coerceIn(minDy, maxDy)

            val minWorldDx = -(drag.origWorldLeftPx) + 1f
            val maxWorldDx = ((totalDays - 1) * stridePx + colWidthPx - drag.origWidthPx - 1f) - drag.origWorldLeftPx
            val clampedDx = worldDx.coerceIn(minOf(minWorldDx, maxWorldDx), maxOf(minWorldDx, maxWorldDx))

            drag.renderOffsetPx = Offset(clampedDx, clampedDy)

            val centerWorldX = drag.origWorldLeftPx + drag.origWidthPx * 0.5f + clampedDx
            val targetIdx = floor((centerWorldX / stridePx).toDouble()).toInt().coerceIn(0, totalDays - 1)
            drag.targetDayIndex = targetIdx

            val deltaMin = (((clampedDy / pxPerMin) / 15f).roundToInt() * 15)
            val latestStart = ITEMS_GRID_END_MIN - if (drag.item.isDeadlinePoint) ITEMS_MIN_DURATION else dur
            val ns = (drag.origStartMin + deltaMin).coerceIn(ITEMS_GRID_START_MIN, latestStart)
            drag.targetStartMin = ns
            drag.targetEndMin = (ns + dur).coerceAtMost(ITEMS_GRID_END_MIN)
        }
    }

    val dStr = dateWindow.dateAt(drag.targetDayIndex)
    val stStr = AcademicClock.clock(drag.targetStartMin)
    val enStr = AcademicClock.clock(drag.targetEndMin)
    if (drag.item.isDeadlinePoint) {
        onLivePillText("${AcademicClock.monthDay(dStr)} $stStr 截止")
    } else if (drag.kind == ItemsDragKind.MOVE) {
        val wd = AcademicClock.weekdayText(AcademicClock.weekday(dStr))
        onLivePillText("${AcademicClock.monthDay(dStr)} $wd $stStr–$enStr")
    } else {
        onLivePillText("$stStr–$enStr · ${ItemsCalendarMath.formatDuration(drag.targetEndMin - drag.targetStartMin)}")
    }
}

/**
 * 1:1 Continuous Multi-Scale Calendar Grid (`1.0 .. 7.0` optical zoom snapping to `1/3/5/7`),
 * sticky synchronized `全天` rail, `ov-rail` course/item separation, touch-first outer-border resize handles,
 * and true 1:1 follow-finger cross-day drag with nonlinear edge auto-scroll & FLIP landing.
 */
@Composable
internal fun ItemsCalendarGridView(
    dateWindow: ItemsDateWindow,
    calOffset: Float,
    calColsF: Float,
    calCols: Int,
    topContentPaddingDp: Dp,
    bottomNavInsetDp: Dp,
    editingId: String?,
    editSheetHeightPx: Int,
    doc: AcademicTimetableDoc?,
    tasks: List<AcademicTask>,
    doneTaskIds: Set<String>,
    exams: List<AcademicExam>,
    items: List<ItemsRecordModel>,
    now: AcademicMoment,
    scrollState: ScrollState = rememberScrollState(initial = 40),
    onBeginNavigation: () -> Float,
    onCalOffsetChange: (Float) -> Unit,
    onPanOffsetChange: (Float) -> Unit,
    onCalViewChange: (colsF: Float, offset: Float) -> Unit,
    onReleasePan: (velColsPerSec: Float) -> Unit,
    onSettleOffset: (Float) -> Unit,
    onReleasePinch: (velColsPerSec: Float, anchorDay: Float, anchorFrac: Float) -> Unit,
    onGridDraggingChanged: (Boolean) -> Unit,
    onLivePillText: (String?) -> Unit,
    onTapItem: (ItemsRecordModel) -> Unit,
    onDeselectEditing: () -> Unit,
    onTapCourse: (AcademicBlock, String) -> Unit,
    onTapHomework: (AcademicTask) -> Unit,
    onTapDayAllDay: (String) -> Unit,
    onTapExam: (AcademicExam) -> Unit,
    onTapEmptySlot: (String, Int) -> Unit,
    onCommitItemMove: (ItemsRecordModel, newDate: String, startMin: Int, endMin: Int, isResize: Boolean, snapOffsetPx: Float?) -> Unit,
) {
    val c = LocalNativeColors.current
    val dark = LocalLiquidGlassDarkTheme.current == true
    val palette = LocalNativePalette.current
    val reduced = LocalLiquidGlassReducedMotion.current
    val density = LocalDensity.current
    val tick = nSelectionFeedback()
    val hapticPress = nHapticFeedback(NativeButtonHaptic.Utility.press)
    val scope = rememberCoroutineScope()

    val hourHeightDp = 52f
    val hourHeightPx = with(density) { hourHeightDp.dp.toPx() }
    val pxPerMin = hourHeightPx / 60f
    val totalGridHeightDp = (ITEMS_GRID_TOTAL_MIN / 60f * hourHeightDp).dp
    val totalGridHeightPx = ITEMS_GRID_TOTAL_MIN * pxPerMin
    val gutterDp = 36.dp
    val gutterPx = with(density) { gutterDp.toPx() }
    val dens = ItemsCalendarMath.nearestScale(calColsF)

    var activeDrag by remember { mutableStateOf<GridDragSession?>(null) }
    var pinchActive by remember { mutableStateOf(false) }
    var pinchGuardUntil by remember { mutableLongStateOf(0L) }
    var leftEdgeGlow by remember { mutableFloatStateOf(0f) }
    var rightEdgeGlow by remember { mutableFloatStateOf(0f) }

    // FLIP settle animation after dropping an event
    var flipSettlingId by remember { mutableStateOf<String?>(null) }
    val flipAnim = remember { Animatable(Offset.Zero, Offset.VectorConverter) }

    var vpBoundsRoot by remember { mutableStateOf(Pair(Offset.Zero, Offset.Zero)) }
    var scrollerTopRootY by remember { mutableFloatStateOf(0f) }
    var scrollerBottomRootY by remember { mutableFloatStateOf(0f) }
    var lastVpWidthPx by remember { mutableFloatStateOf(900f) }

    val gapDp = ItemsCalendarMath.columnGapDp(calColsF)
    val gapPx = with(density) { gapDp.dp.toPx() }
    val stridePx = ((lastVpWidthPx + gapPx) / calColsF.coerceIn(1f, 7f)).coerceAtLeast(1f)
    val colWidthPx = (stridePx - gapPx).coerceAtLeast(with(density) { 18.dp.toPx() })
    val colWidthDp = with(density) { colWidthPx.toDp() }

    val latestCalOffset by rememberUpdatedState(calOffset)
    val latestCalColsF by rememberUpdatedState(calColsF)
    val latestCalCols by rememberUpdatedState(calCols)
    val latestEditSheetHeightPx by rememberUpdatedState(editSheetHeightPx)
    val beginNavigation by rememberUpdatedState(onBeginNavigation)
    val panOffsetChange by rememberUpdatedState(onPanOffsetChange)
    val calViewChange by rememberUpdatedState(onCalViewChange)
    val releasePan by rememberUpdatedState(onReleasePan)
    val releasePinch by rememberUpdatedState(onReleasePinch)
    val setLivePillText by rememberUpdatedState(onLivePillText)
    val latestStridePx by rememberUpdatedState(stridePx)
    val latestColWidthPx by rememberUpdatedState(colWidthPx)
    val latestDateWindow by rememberUpdatedState(dateWindow)

    fun setDrag(drag: GridDragSession?) {
        activeDrag = drag
        onGridDraggingChanged(drag != null)
        if (drag == null) onLivePillText(null)
    }

    val baseBottomPadding = bottomNavInsetDp + 110.dp
    val editBottomPadding = with(density) { editSheetHeightPx.toDp() } + 28.dp
    val gridBottomPadding by animateDpAsState(
        targetValue = maxOf(baseBottomPadding, editBottomPadding),
        animationSpec = if (reduced || editSheetHeightPx > 0) snap() else tween(200),
        label = "items-grid-bottom-space",
    )
    val selectedTimedItem = items.firstOrNull { it.id == editingId && it.isTimed }
    val calendarHeightPx = scrollerBottomRootY - scrollerTopRootY
    LaunchedEffect(
        selectedTimedItem?.id, selectedTimedItem?.startMin, selectedTimedItem?.endMin,
        editSheetHeightPx, calendarHeightPx, activeDrag,
    ) {
        val target = selectedTimedItem ?: return@LaunchedEffect
        if (editSheetHeightPx <= 0 || calendarHeightPx <= 0f || activeDrag != null) return@LaunchedEffect
        // The new bottom padding must be measured before scrolling into the space above the sheet.
        withFrameNanos { }
        withFrameNanos { }
        val marginPx = with(density) { 18.dp.toPx() }
        val top = (target.startMin!! - ITEMS_GRID_START_MIN) * pxPerMin
        val bottom = (target.endMin!! - ITEMS_GRID_START_MIN) * pxPerMin
        val next = ItemsCalendarMath.scrollForVisibleGridCard(
            scroll = scrollState.value.toFloat(),
            cardTop = top,
            cardBottom = bottom,
            visibleHeight = calendarHeightPx - editSheetHeightPx,
            margin = marginPx,
        ).roundToInt().coerceIn(0, scrollState.maxValue)
        if (next != scrollState.value) {
            if (reduced) scrollState.scrollTo(next)
            else scrollState.animateScrollTo(next, tween(210))
        }
    }

    // Nonlinear edge auto-scroll frame loop during active drag
    LaunchedEffect(activeDrag) {
        val drag = activeDrag ?: run {
            leftEdgeGlow = 0f
            rightEdgeGlow = 0f
            return@LaunchedEffect
        }
        var lastNs = 0L
        while (activeDrag === drag) {
            var scrolled = false
            var pendingVScrollPx = 0f
            withFrameNanos { ns ->
                if (activeDrag !== drag) return@withFrameNanos
                if (lastNs == 0L) {
                    lastNs = ns
                    return@withFrameNanos
                }
                val dt = ((ns - lastNs) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
                lastNs = ns

                if (drag.kind == ItemsDragKind.MOVE) {
                    val vpLeft = vpBoundsRoot.first.x
                    val vpRight = vpBoundsRoot.second.x
                    val vpW = (vpRight - vpLeft).coerceAtLeast(1f)
                    val zoneX = minOf(with(density) { 62.dp.toPx() }, vpW * 0.24f)
                    // Edges only arm after the finger has travelled toward them, so a drag that starts inside a zone stays still.
                    val armPx = with(density) { 14.dp.toPx() }
                    val towardRight = drag.curPointerRoot.x > drag.startPointerRoot.x + armPx
                    val towardLeft = drag.curPointerRoot.x < drag.startPointerRoot.x - armPx
                    val pr = if (towardRight && drag.curPointerRoot.x > vpRight - zoneX) {
                        ((drag.curPointerRoot.x - (vpRight - zoneX)) / zoneX).coerceIn(0f, 1f)
                    } else 0f
                    val pl = if (towardLeft && drag.curPointerRoot.x < vpLeft + zoneX) {
                        (((vpLeft + zoneX) - drag.curPointerRoot.x) / zoneX).coerceIn(0f, 1f)
                    } else 0f

                    val maxOff = (latestDateWindow.totalDays - latestCalColsF).coerceAtLeast(0f)
                    val maxColsPerSec = ItemsCalendarMath.maxAutoScrollColsPerSec(latestCalCols)
                    val speedCols = when {
                        pr > 0f -> ItemsCalendarMath.edgeAutoScrollFactor(pr) * maxColsPerSec
                        pl > 0f -> -ItemsCalendarMath.edgeAutoScrollFactor(pl) * maxColsPerSec
                        else -> 0f
                    }
                    if (speedCols != 0f) {
                        val nextOff = (latestCalOffset + speedCols * dt).coerceIn(0f, maxOff)
                        if (abs(nextOff - latestCalOffset) > 1e-5f) {
                            onCalOffsetChange(nextOff)
                            scrolled = true
                        }
                    }
                    rightEdgeGlow = if (pr > 0f && latestCalOffset < maxOff - 0.01f) {
                        0.28f + ItemsCalendarMath.edgeAutoScrollFactor(pr) * 0.72f
                    } else 0f
                    leftEdgeGlow = if (pl > 0f && latestCalOffset > 0.01f) {
                        0.28f + ItemsCalendarMath.edgeAutoScrollFactor(pl) * 0.72f
                    } else 0f
                }

                val topBound = scrollerTopRootY + with(density) { 6.dp.toPx() }
                val botMargin = if (latestEditSheetHeightPx > 0) {
                    latestEditSheetHeightPx + with(density) { 24.dp.toPx() }
                } else with(density) { (88.dp + bottomNavInsetDp).toPx() }
                val botBound = (scrollerBottomRootY - botMargin).coerceAtLeast(topBound + with(density) { 80.dp.toPx() })
                val zoneY = with(density) { 48.dp.toPx() }
                val armY = with(density) { 14.dp.toPx() }
                val pd = if (drag.curPointerRoot.y > drag.startPointerRoot.y + armY && drag.curPointerRoot.y > botBound - zoneY) {
                    ((drag.curPointerRoot.y - (botBound - zoneY)) / zoneY).coerceIn(0f, 1f)
                } else 0f
                val pu = if (drag.curPointerRoot.y < drag.startPointerRoot.y - armY && drag.curPointerRoot.y < topBound + zoneY) {
                    (((topBound + zoneY) - drag.curPointerRoot.y) / zoneY).coerceIn(0f, 1f)
                } else 0f
                val vSpeedPx = when {
                    pd > 0f -> ItemsCalendarMath.edgeAutoScrollFactor(pd) * with(density) { 270.dp.toPx() }
                    pu > 0f -> -ItemsCalendarMath.edgeAutoScrollFactor(pu) * with(density) { 270.dp.toPx() }
                    else -> 0f
                }
                pendingVScrollPx = vSpeedPx * dt
            }
            if (activeDrag !== drag) break
            if (pendingVScrollPx != 0f) {
                val consumed = scrollState.scrollBy(pendingVScrollPx)
                if (abs(consumed) > 0.1f) scrolled = true
            }
            if (scrolled && activeDrag === drag) {
                recomputeDragTargets(
                    drag = drag,
                    calOffset = latestCalOffset,
                    scrollY = scrollState.value.toFloat(),
                    stridePx = latestStridePx,
                    colWidthPx = latestColWidthPx,
                    pxPerMin = pxPerMin,
                    totalGridHeightPx = totalGridHeightPx,
                    totalDays = latestDateWindow.totalDays,
                    dateWindow = latestDateWindow,
                    onLivePillText = onLivePillText,
                )
            }
        }
    }

    fun finishDrag(drag: GridDragSession) {
        if (activeDrag !== drag) return
        val landedDayIndex = drag.targetDayIndex
        val landedDate = latestDateWindow.dateAt(landedDayIndex)
        val landedStart = drag.targetStartMin
        val landedEnd = drag.targetEndMin
        val isResize = drag.kind != ItemsDragKind.MOVE

        var snapOff: Float? = null
        if (!isResize) {
            val baseSnap = ItemsCalendarMath.nearestGridStop(latestCalOffset, latestCalCols, latestDateWindow.totalDays)
            var nextSnap = baseSnap
            if (landedDayIndex < nextSnap) nextSnap = landedDayIndex.toFloat()
            else if (landedDayIndex > nextSnap + latestCalCols - 1) nextSnap = (landedDayIndex - latestCalCols + 1).toFloat()
            snapOff = nextSnap.coerceIn(0f, (latestDateWindow.totalDays - latestCalCols).coerceAtLeast(0).toFloat())

            val dropWorldLeft = drag.origWorldLeftPx + drag.renderOffsetPx.x
            val dropWorldTop = drag.origTopPx + drag.renderOffsetPx.y
            val landColLeft = landedDayIndex * latestStridePx
            val landTop = (landedStart - ITEMS_GRID_START_MIN) * pxPerMin
            val fdx = dropWorldLeft - landColLeft
            val fdy = dropWorldTop - landTop
            if (!reduced && hypot(fdx, fdy) > 2f) {
                flipSettlingId = drag.item.id
                scope.launch {
                    flipAnim.snapTo(Offset(fdx, fdy))
                    flipAnim.animateTo(Offset.Zero, spring(dampingRatio = 0.86f, stiffness = 460f))
                    if (flipSettlingId == drag.item.id) flipSettlingId = null
                }
            }
        }

        setDrag(null)
        if (landedDayIndex != drag.origDayIndex || landedStart != drag.origStartMin || landedEnd != drag.origEndMin) {
            onCommitItemMove(drag.item, landedDate, landedStart, landedEnd, isResize, snapOff)
        } else if (snapOff != null) {
            onSettleOffset(snapOff)
        }
    }

    val firstVis = floor(calOffset.toDouble()).toInt().coerceIn(0, dateWindow.totalDays - 1)
    val lastVis = ceil((calOffset + calColsF + 0.5f).toDouble()).toInt().coerceIn(firstVis, dateWindow.totalDays - 1)
    val visRange = firstVis..lastVis

    // Stable all-day rail: sized by the busiest day in the whole window, so panning never makes the grid jump.
    val allDayRowsNeeded = remember(tasks, doneTaskIds, items) {
        val counts = HashMap<String, Int>()
        tasks.forEach { t ->
            val due = t.dueMs
            if (t.id !in doneTaskIds && due != null) {
                val day = AcademicClock.moment(due).date
                counts[day] = (counts[day] ?: 0) + 1
            }
        }
        items.forEach { p ->
            val day = p.date
            if (p.isAllDay && !p.done && day != null) counts[day] = (counts[day] ?: 0) + 1
        }
        counts.values.maxOfOrNull { it.coerceAtMost(2) } ?: 0
    }
    val railBadgeDp = if (dens == 5) 18 else 20
    val allDayRailHeight by animateDpAsState(
        when {
            allDayRowsNeeded == 0 -> 18.dp
            dens >= 7 -> 21.dp
            else -> (allDayRowsNeeded * railBadgeDp + (allDayRowsNeeded - 1) * 3).dp
        },
        if (reduced) snap() else spring(0.86f, 480f),
        label = "allday-rail",
    )

    val multiTouchZoomAndPanModifier = Modifier.pointerInput(dateWindow.dates.first()) {
        val slop = viewConfiguration.touchSlop
        awaitEachGesture {
            val firstDown = awaitFirstDown(requireUnconsumed = false)
            val velocity = ItemsDatePanVelocity(firstDown)
            var isPinching = false
            var pinchCols = latestCalColsF
            var pinchAnchorFrac = 0.5f
            var pinchAnchorDay = 0f
            var pinchHint = 0
            var lastColsVel = 0f
            var lastColsSample = pinchCols
            var lastSampleTime = firstDown.uptimeMillis

            var isPanningX = false
            var singleFingerClaimed = false
            var panStartOff = latestCalOffset
            var panStartX = firstDown.position.x
            var totalDx = 0f
            var totalDy = 0f

            fun panTo(pointerX: Float) {
                val maxOff = (dateWindow.totalDays - latestCalColsF).coerceAtLeast(0f)
                val offset = panStartOff - (pointerX - panStartX) / latestStridePx
                panOffsetChange(ItemsCalendarMath.rubberBandClamp(offset, 0f, maxOff))
            }

            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                val pressed = event.changes.count { it.pressed }
                if (pressed >= 2) {
                    if (!isPinching) {
                        isPinching = true
                        isPanningX = false
                        pinchActive = true
                        if (activeDrag != null) setDrag(null)
                        val offset = beginNavigation()
                        val vpW = (vpBoundsRoot.second.x - vpBoundsRoot.first.x).coerceAtLeast(1f)
                        val cent = event.calculateCentroid(useCurrent = false)
                        val centLocalVpX = if (cent != Offset.Unspecified) cent.x - gutterPx else vpW * 0.5f
                        pinchCols = latestCalColsF
                        pinchAnchorFrac = (centLocalVpX / vpW).coerceIn(0.05f, 0.95f)
                        pinchAnchorDay = offset + pinchAnchorFrac * pinchCols
                        lastColsSample = pinchCols
                        lastSampleTime = event.changes.first().uptimeMillis
                    }
                    val zoom = event.calculateZoom()
                    if (zoom.isFinite() && zoom > 0f) {
                        pinchCols = (pinchCols / zoom).coerceIn(0.5f, 8.5f)
                        val cols = ItemsCalendarMath.rubberBandClamp(pinchCols, 1f, 7f)
                        val maxOff = (dateWindow.totalDays - cols).coerceAtLeast(0f)
                        val off = ItemsCalendarMath.rubberBandClamp(pinchAnchorDay - pinchAnchorFrac * cols, 0f, maxOff)
                        val now = event.changes.first().uptimeMillis
                        val dt = ((now - lastSampleTime) / 1000f).coerceAtLeast(0.004f)
                        lastColsVel = lastColsVel * 0.45f + ((cols - lastColsSample) / dt) * 0.55f
                        lastColsSample = cols
                        lastSampleTime = now
                        calViewChange(cols, off)
                        val hint = ItemsCalendarMath.nearestScale(cols)
                        if (hint != pinchHint) {
                            pinchHint = hint
                            setLivePillText("${ItemsCalendarMath.scaleShortName(hint)}视图")
                        }
                    }
                    event.changes.forEach { it.consume() }
                } else if (isPinching) {
                    if (pressed == 0) break
                    event.changes.forEach { it.consume() }
                } else {
                    val change = event.changes.firstOrNull { it.id == firstDown.id } ?: break
                    velocity.add(change)
                    if (!change.pressed) {
                        if (isPanningX) panTo(change.position.x)
                        break
                    }
                    if (!isPanningX && change.isConsumed) singleFingerClaimed = true
                    if (singleFingerClaimed) continue
                    val delta = change.positionChange()
                    totalDx += delta.x
                    totalDy += delta.y

                    if (!isPanningX) {
                        if (abs(totalDy) > slop && abs(totalDy) >= abs(totalDx)) {
                            singleFingerClaimed = true
                            continue
                        }
                        if (abs(totalDx) <= slop || abs(totalDx) <= abs(totalDy) * 1.1f) continue
                        isPanningX = true
                        panStartOff = beginNavigation()
                        val overSlop = if (totalDx > 0f) totalDx - slop else totalDx + slop
                        panStartX = change.position.x - overSlop
                    }
                    change.consume()
                    panTo(change.position.x)
                }
            }

            if (isPinching) {
                pinchActive = false
                pinchGuardUntil = android.os.SystemClock.uptimeMillis() + 280L
                tick()
                releasePinch(lastColsVel * 0.3f, pinchAnchorDay, pinchAnchorFrac)
            } else if (isPanningX) {
                releasePan(-velocity.velocity() / latestStridePx)
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(top = topContentPaddingDp)
            .then(multiTouchZoomAndPanModifier)
            .testTag("items-calendar-grid"),
    ) {
        // Synchronized All-Day / Homework Due Rail
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 24.dp)
                .padding(horizontal = 10.dp, vertical = 4.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { if (editingId != null) onDeselectEditing() },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(gutterDp)) {
                BasicText(
                    "全天",
                    style = nativeTextStyle(c.muted.copy(alpha = 0.78f), 9.5f, 12f, FontWeight.Bold),
                )
            }
            Box(
                Modifier
                    .weight(1f)
                    .clipToBounds()
                    .onGloballyPositioned { coords ->
                        lastVpWidthPx = coords.size.width.toFloat().coerceAtLeast(1f)
                        val p = coords.positionInRoot()
                        vpBoundsRoot = p to Offset(p.x + coords.size.width, p.y)
                    },
            ) {
                Box(Modifier.fillMaxWidth().height(allDayRailHeight)) {
                    for (idx in visRange) {
                        val d = dateWindow.dates[idx]
                        val xPx = (idx - calOffset) * stridePx
                        val dues = remember(d, tasks, doneTaskIds) {
                            tasks.filter { t ->
                                t.id !in doneTaskIds && t.dueMs != null && AcademicClock.moment(t.dueMs).date == d
                            }
                        }
                        val allDayItems = remember(d, items) {
                            items.filter { it.date == d && it.isAllDay && !it.done }
                        }
                        val count = dues.size + allDayItems.size
                        Column(
                            Modifier
                                .graphicsLayer { translationX = xPx }
                                .width(colWidthDp)
                                .then(
                                    if (dens == 7 && count > 0) {
                                        Modifier.clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                        ) {
                                            if (editingId != null) onDeselectEditing() else onTapDayAllDay(d)
                                        }
                                    } else Modifier,
                                ),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            if (dens == 7) {
                                dues.take(1).forEach { _ ->
                                    Box(Modifier.fillMaxWidth().height(5.dp).clip(Capsule()).background(c.danger.copy(alpha = 0.88f)))
                                }
                                allDayItems.take(if (dues.isEmpty()) 2 else 1).forEach { _ ->
                                    Box(Modifier.fillMaxWidth().height(5.dp).clip(Capsule()).background(c.brand.copy(alpha = 0.88f)))
                                }
                                if (count > 2) {
                                    Box(Modifier.fillMaxWidth().height(4.dp).clip(Capsule()).background(c.muted.copy(alpha = 0.65f)))
                                }
                            } else {
                                val firstAll = buildList<Pair<Boolean, Any>> {
                                    dues.forEach { add(true to it) }
                                    allDayItems.forEach { add(false to it) }
                                }
                                val overflow = count > 2
                                val shown = if (overflow) firstAll.take(1) else firstAll
                                shown.forEach { (isHomework, payload) ->
                                    val title = if (isHomework) (payload as AcademicTask).title else (payload as ItemsRecordModel).title
                                    val tint = if (isHomework) c.danger else c.brand
                                    Box(
                                        Modifier
                                            .fillMaxWidth()
                                            .height(if (dens == 5) 18.dp else 20.dp)
                                            .clip(RoundedRectangle(if (dens == 5) 6.dp else 7.dp))
                                            .background(tint.copy(alpha = if (dark) 0.24f else 0.14f))
                                            .clickable {
                                                if (editingId != null) onDeselectEditing()
                                                else if (isHomework) onTapHomework(payload as AcademicTask)
                                                else onTapItem(payload as ItemsRecordModel)
                                            }
                                            .padding(horizontal = if (dens == 5) 4.dp else 6.dp),
                                        contentAlignment = Alignment.CenterStart,
                                    ) {
                                        BasicText(
                                            title,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            style = nativeTextStyle(tint, if (dens == 5) 9.5f else 11f, 13f, FontWeight.Bold),
                                        )
                                    }
                                }
                                if (overflow) {
                                    Box(
                                        Modifier
                                            .fillMaxWidth()
                                            .height(if (dens == 5) 18.dp else 20.dp)
                                            .clip(RoundedRectangle(if (dens == 5) 6.dp else 7.dp))
                                            .background(c.ink.copy(alpha = 0.08f))
                                            .clickable {
                                                if (editingId != null) onDeselectEditing() else onTapDayAllDay(d)
                                            },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        BasicText(
                                            "还有 ${count - 1} 项",
                                            maxLines = 1,
                                            softWrap = false,
                                            style = nativeTextStyle(c.muted, if (dens == 5) 9.5f else 11f, 13f, FontWeight.Bold),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Scrollable Hourly Time Grid with Progressive Top Soft Edge
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .onGloballyPositioned { coords ->
                    val y = coords.positionInRoot().y
                    scrollerTopRootY = y
                    scrollerBottomRootY = y + coords.size.height
                }
                .clipToBounds()
                .verticalScroll(scrollState)
                .padding(start = 10.dp, end = 10.dp, bottom = gridBottomPadding),
        ) {
            Row(Modifier.fillMaxWidth().height(totalGridHeightDp)) {
                // Left Hour Gutter (`08 .. 22`)
                Box(
                    Modifier
                        .width(gutterDp)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { if (editingId != null) onDeselectEditing() },
                ) {
                    for (h in 8..22) {
                        val yDp = ((h - 8) * hourHeightDp).dp
                        BasicText(
                            String.format(java.util.Locale.ROOT, "%02d", h),
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.offset(y = (yDp - 6.dp).coerceAtLeast(0.dp)),
                            style = nativeTextStyle(c.muted.copy(alpha = 0.85f), 10f, 12f, FontWeight.Medium, tabularNums = true),
                        )
                    }
                }

                // Multi-day viewport track + Edge Auto-Scroll glows
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clipToBounds(),
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        for (i in 0 until 14) {
                            val y = i * hourHeightPx
                            drawLine(
                                color = c.border.copy(alpha = 0.62f),
                                start = Offset(0f, y),
                                end = Offset(size.width, y),
                                strokeWidth = 0.75.dp.toPx(),
                            )
                        }
                    }

                    val renderIndices = buildSet {
                        for (i in visRange) add(i)
                        activeDrag?.let {
                            add(it.origDayIndex)
                            add(it.targetDayIndex)
                        }
                    }.sorted()

                    for (dayIdx in renderIndices) key(dayIdx) {
                        val d = dateWindow.dates[dayIdx]
                        val colLeftPx = (dayIdx - calOffset) * stridePx
                        val dayCourses = remember(d, doc) { AcademicTimetable.planFor(doc, d).blocks }
                        val dayExams = remember(d, exams) { exams.filter { it.date == d && it.startMs != null } }
                        val dayItems = remember(d, items) { items.filter { it.date == d && it.isTimed } }

                        val fixedIntervals = remember(dayCourses, dayExams, d) {
                            dayCourses.map { b -> ItemsGridInterval("c:${b.key}", b.startMin, b.endMin, 0) } +
                                dayExams.map { ex ->
                                    val sm = AcademicClock.moment(ex.startMs!!).minutes.coerceIn(ITEMS_GRID_START_MIN, ITEMS_GRID_END_MIN - 30)
                                    ItemsGridInterval("x:${ex.id}", sm, (sm + 120).coerceAtMost(ITEMS_GRID_END_MIN), 0)
                                }
                        }
                        val personalIntervals = remember(dayItems) {
                            dayItems.map { p -> ItemsGridInterval("p:${p.id}", p.startMin!!, p.endMin!!, 1) }
                        }
                        val lanes = remember(fixedIntervals, personalIntervals) {
                            itemsPackDayLanes(fixedIntervals, personalIntervals)
                        }

                        Box(
                            Modifier
                                .graphicsLayer { translationX = colLeftPx }
                                .width(colWidthDp)
                                .height(totalGridHeightDp),
                        ) {
                            Canvas(Modifier.height(totalGridHeightDp).width(1.dp)) {
                                drawLine(
                                    c.border.copy(alpha = 0.78f),
                                    Offset.Zero,
                                    Offset(0f, size.height),
                                    strokeWidth = 0.75.dp.toPx(),
                                )
                            }
                            for (hr in 8 until 22) {
                                Box(
                                    Modifier
                                        .offset(y = ((hr - 8) * hourHeightDp).dp)
                                        .fillMaxWidth()
                                        .height(hourHeightDp.dp)
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                        ) {
                                            if (editingId != null) onDeselectEditing()
                                            else onTapEmptySlot(d, hr * 60)
                                        },
                                )
                            }

                            // 1) Courses (`ov-rail`)
                            dayCourses.forEach { block ->
                                val laneInfo = lanes["c:${block.key}"]
                                val isUnder = laneInfo?.hasFixedRail == true && laneInfo.lane == 0
                                val swatch = nativeCourseSwatch(block.course.hue, dark, palette)
                                val topPx = ((block.startMin - ITEMS_GRID_START_MIN) * pxPerMin).coerceAtLeast(0f)
                                val hPx = ((block.endMin - block.startMin) * pxPerMin).coerceAtLeast(with(density) { 24.dp.toPx() })
                                val laneGeom = computeBlockGeometry(colWidthPx, laneInfo, dens, density.density)
                                val cShape = if (isUnder && dens > 1) Capsule() else RoundedRectangle(if (dens >= 7) 6.dp else if (dens >= 5) 8.dp else 10.dp)

                                Box(
                                    Modifier
                                        .offset { IntOffset(laneGeom.leftPx.roundToInt(), topPx.roundToInt()) }
                                        .width(with(density) { laneGeom.widthPx.toDp() })
                                        .height(with(density) { hPx.toDp() })
                                        .clip(cShape)
                                        .background(swatch.soft.copy(alpha = if (dark) 0.92f else 0.95f))
                                        .border(0.75.dp, swatch.mid.copy(alpha = if (dark) 0.34f else 0.30f), cShape)
                                        .clickable { if (editingId != null) onDeselectEditing() else onTapCourse(block, d) }
                                    .semantics { contentDescription = "${block.course.title} ${block.start}–${block.end}" }
                                        .padding(
                                            horizontal = if (isUnder && dens > 1) 0.dp else if (dens >= 7) 2.dp else 5.dp,
                                            vertical = if (isUnder && dens > 1) 5.dp else 3.dp,
                                        ),
                                ) {
                                    if (isUnder && dens > 1) {
                                        if (dens < 7) {
                                            val railLinePx = with(density) { (if (dens == 5) 10f else 11.5f).sp.toPx() }
                                            val fitChars = ((hPx - with(density) { 10.dp.toPx() }) / railLinePx).toInt().coerceAtLeast(1)
                                            val short = itemsCleanTitle(block.course.title).take(fitChars)
                                            Column(
                                                Modifier.fillMaxSize(),
                                                horizontalAlignment = Alignment.CenterHorizontally,
                                                verticalArrangement = Arrangement.Center,
                                            ) {
                                                short.forEach { ch ->
                                                    BasicText(
                                                        ch.toString(),
                                                        maxLines = 1,
                                                        softWrap = false,
                                                        style = nativeTextStyle(
                                                            swatch.ink,
                                                            if (dens == 5) 8.5f else 10f,
                                                            if (dens == 5) 10f else 11.5f,
                                                            FontWeight.Bold,
                                                        ),
                                                    )
                                                }
                                            }
                                        }
                                    } else {
                                        val isShared = (laneInfo?.lanes ?: 1) > 1 && dens > 1
                                        val titleText = if (dens >= 5 || isShared) itemsCleanTitle(block.course.title) else block.course.title
                                        Column(Modifier.fillMaxSize()) {
                                            BasicText(
                                                titleText,
                                                maxLines = if (dens >= 7) 3 else 2,
                                                overflow = TextOverflow.Ellipsis,
                                                style = nativeTextStyle(
                                                    swatch.ink,
                                                    when {
                                                        dens >= 7 -> 8.5f
                                                        dens == 5 -> 10f
                                                        else -> 11f
                                                    },
                                                    when {
                                                        dens >= 7 -> 10.5f
                                                        dens == 5 -> 12.5f
                                                        else -> 14f
                                                    },
                                                    FontWeight.Bold,
                                                ),
                                            )
                                            if (!isShared && dens < 7 && block.location.isNotBlank()) {
                                                BasicText(
                                                    block.location,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    style = nativeTextStyle(swatch.ink.copy(alpha = 0.82f), if (dens == 5) 9.5f else 10f, 12f),
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            // 2) Exam blocks
                            dayExams.forEach { ex ->
                                val laneInfo = lanes["x:${ex.id}"]
                                val isUnder = laneInfo?.hasFixedRail == true && laneInfo.lane == 0
                                val sm = AcademicClock.moment(ex.startMs!!).minutes.coerceIn(ITEMS_GRID_START_MIN, ITEMS_GRID_END_MIN - 30)
                                val em = (sm + 120).coerceAtMost(ITEMS_GRID_END_MIN)
                                val topPx = (sm - ITEMS_GRID_START_MIN) * pxPerMin
                                val hPx = ((em - sm) * pxPerMin).coerceAtLeast(with(density) { 28.dp.toPx() })
                                val laneGeom = computeBlockGeometry(colWidthPx, laneInfo, dens, density.density)
                                val exShape = if (isUnder && dens > 1) Capsule() else RoundedRectangle(if (dens >= 7) 6.dp else 10.dp)

                                Box(
                                    Modifier
                                        .offset { IntOffset(laneGeom.leftPx.roundToInt(), topPx.roundToInt()) }
                                        .width(with(density) { laneGeom.widthPx.toDp() })
                                        .height(with(density) { hPx.toDp() })
                                        .clip(exShape)
                                        .background(c.danger.copy(alpha = if (dark) 0.24f else 0.16f))
                                        .border(1.dp, c.danger.copy(alpha = 0.45f), exShape)
                                        .clickable { if (editingId != null) onDeselectEditing() else onTapExam(ex) }
                                        .semantics { contentDescription = "${ex.title} ${AcademicClock.clock(sm)} 考试" }
                                        .padding(horizontal = if (isUnder && dens > 1) 0.dp else 5.dp, vertical = 3.dp),
                                ) {
                                    if (!isUnder || dens == 1) {
                                        Column {
                                            BasicText(
                                                if (dens >= 5) itemsCleanTitle(ex.title) else ex.title,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                                style = nativeTextStyle(c.danger, if (dens >= 7) 8.5f else 11f, 13.5f, FontWeight.Bold),
                                            )
                                            if (dens < 7 && ex.location.isNotBlank()) {
                                                BasicText(
                                                    ex.location,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    style = nativeTextStyle(c.danger.copy(alpha = 0.85f), 10f, 12f),
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            // 3) Personal scheduled items (kept mounted with alpha=0 during active drag so pointerInput never cancels!)
                            for (item in dayItems) key(item.id) {
                                val laneInfo = lanes["p:${item.id}"]
                                val isOver = laneInfo?.overlapsFixed == true
                                val laneGeom = computeBlockGeometry(colWidthPx, laneInfo, dens, density.density)
                                val topPx = (item.startMin!! - ITEMS_GRID_START_MIN) * pxPerMin
                                val hPx = (item.endMin!! - item.startMin) * pxPerMin
                                val isEditing = item.id == editingId
                                val isDraggingThis = activeDrag?.item?.id == item.id

                                val flipOffset = if (flipSettlingId == item.id) flipAnim.value else Offset.Zero
                                var cardCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
                                val latestDayIdx by rememberUpdatedState(dayIdx)
                                val latestItem by rememberUpdatedState(item)
                                val latestGeometry by rememberUpdatedState(laneGeom)
                                val latestTopPx by rememberUpdatedState(topPx)

                                fun dragInput(kind: ItemsDragKind, coordinates: () -> LayoutCoordinates?): Modifier =
                                    Modifier.pointerInput(item.id, kind) {
                                        val slop = if (kind == ItemsDragKind.MOVE) {
                                            maxOf(5.dp.toPx(), viewConfiguration.touchSlop * 0.65f)
                                        } else 3.dp.toPx()
                                        awaitEachGesture {
                                            val down = awaitFirstDown(requireUnconsumed = false)
                                            if (pinchActive || android.os.SystemClock.uptimeMillis() < pinchGuardUntil) return@awaitEachGesture
                                            val startRoot = coordinates()?.localToRoot(down.position) ?: down.position
                                            var session: GridDragSession? = null
                                            try {
                                                while (true) {
                                                    val event = awaitPointerEvent(PointerEventPass.Main)
                                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                                    if (pinchActive || change.isConsumed || event.changes.count { it.pressed } >= 2) break
                                                    if (!change.pressed) {
                                                        val drag = session
                                                        if (drag != null) finishDrag(drag)
                                                        else if (kind == ItemsDragKind.MOVE) onTapItem(latestItem)
                                                        break
                                                    }
                                                    val root = coordinates()?.localToRoot(change.position) ?: change.position
                                                    val delta = root - startRoot
                                                    val traveled = if (kind == ItemsDragKind.MOVE) hypot(delta.x, delta.y) else abs(delta.y)
                                                    if (session == null && traveled > slop) {
                                                        hapticPress()
                                                        val activeItem = latestItem
                                                        session = GridDragSession(
                                                            item = activeItem,
                                                            kind = kind,
                                                            origDayIndex = latestDayIdx,
                                                            origStartMin = checkNotNull(activeItem.startMin),
                                                            origEndMin = checkNotNull(activeItem.endMin),
                                                            origWorldLeftPx = latestDayIdx * latestStridePx + latestGeometry.leftPx,
                                                            origTopPx = latestTopPx,
                                                            origWidthPx = latestGeometry.widthPx,
                                                            origCalOffset = latestCalOffset,
                                                            origScrollY = scrollState.value.toFloat(),
                                                            startPointerRoot = startRoot,
                                                        )
                                                        setDrag(session)
                                                    }
                                                    session?.let { drag ->
                                                        change.consume()
                                                        drag.curPointerRoot = root
                                                        recomputeDragTargets(
                                                            drag, latestCalOffset, scrollState.value.toFloat(),
                                                            latestStridePx, latestColWidthPx, pxPerMin,
                                                            totalGridHeightPx, latestDateWindow.totalDays, latestDateWindow, onLivePillText,
                                                        )
                                                    }
                                                }
                                            } finally {
                                                if (session != null && activeDrag === session) setDrag(null)
                                            }
                                        }
                                    }

                                ItemsGridEventCard(
                                    item = item,
                                    leftPx = laneGeom.leftPx + flipOffset.x,
                                    topPx = topPx + flipOffset.y,
                                    widthPx = laneGeom.widthPx,
                                    heightPx = hPx,
                                    dens = dens,
                                    isSharedLane = (laneInfo?.lanes ?: 1) > 1,
                                    isClash = isOver,
                                    isEditing = isEditing,
                                    isDragging = false,
                                    dark = dark,
                                    palette = palette,
                                    colors = c,
                                    startDisplayMin = item.startMin,
                                    endDisplayMin = item.endMin,
                                    modifier = Modifier
                                        .zIndex(if (isEditing) 12f else 5f)
                                        .alpha(if (isDraggingThis) 0f else 1f)
                                        .onGloballyPositioned { cardCoords = it }
                                        .semantics(mergeDescendants = true) {
                                            contentDescription = if (item.isDeadlinePoint) "${item.title} ${item.startClock} 截止"
                                            else "${item.title} ${item.startClock}–${item.endClock}"
                                            onClick(label = "编辑事项") { onTapItem(item); true }
                                        }
                                        .then(dragInput(ItemsDragKind.MOVE) { cardCoords }),
                                )

                                // Touch-first generous outer-border resize knobs (`44×24dp`) placed in column space so parent card bounds NEVER clip hit area!
                                // Stay mounted (invisible) while this item is dragged so the knob gesture is never cancelled.
                                if (isEditing && !item.isDeadlinePoint) {
                                    val tones = itemsEventTones(dark, palette, item.priority, item.done, c)
                                    val knobW = minOf(with(density) { (if (dens >= 7) 24.dp else 44.dp).toPx() }, laneGeom.widthPx)
                                    val knobH = minOf(with(density) { 24.dp.toPx() }, hPx)
                                    val knobLeft = laneGeom.leftPx + (laneGeom.widthPx - knobW) * 0.5f

                                    for (kind in arrayOf(ItemsDragKind.RESIZE_TOP, ItemsDragKind.RESIZE_BOTTOM)) key(kind) {
                                        val edge = if (kind == ItemsDragKind.RESIZE_TOP) topPx else topPx + hPx
                                        val knobY = edge - knobH * 0.5f
                                        var knobCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
                                        Box(
                                            Modifier
                                                .offset { IntOffset(knobLeft.roundToInt(), knobY.roundToInt()) }
                                                .width(with(density) { knobW.toDp() })
                                                .height(with(density) { knobH.toDp() })
                                                .zIndex(22f)
                                                .alpha(if (isDraggingThis) 0f else 1f)
                                                .onGloballyPositioned { knobCoords = it }
                                                .semantics { contentDescription = if (kind == ItemsDragKind.RESIZE_TOP) "上边调整开始时间" else "下边调整结束时间" }
                                                .then(dragInput(kind) { knobCoords }),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Box(
                                                Modifier
                                                    .size(width = if (dens >= 7) 10.dp else 16.dp, height = if (dens >= 7) 5.dp else 6.dp)
                                                    .shadow(3.dp, Capsule())
                                                    .clip(Capsule())
                                                    .background(Color.White)
                                                    .border(1.75.dp, tones.accent, Capsule()),
                                            )
                                        }
                                    }
                                }
                            }

                            // Today live red indicator line
                            if (d == dateWindow.today && now.minutes in ITEMS_GRID_START_MIN..ITEMS_GRID_END_MIN) {
                                val nowY = (now.minutes - ITEMS_GRID_START_MIN) * pxPerMin
                                Box(
                                    Modifier
                                        .offset { IntOffset((-3.dp.toPx()).roundToInt(), (nowY - 3.5.dp.toPx()).roundToInt()) }
                                        .fillMaxWidth()
                                        .height(7.dp)
                                        .zIndex(9f),
                                    contentAlignment = Alignment.CenterStart,
                                ) {
                                    Box(Modifier.size(7.dp).clip(CircleShape).background(c.danger))
                                    Box(Modifier.fillMaxWidth().height(1.5.dp).background(c.danger))
                                }
                            }
                        }
                    }

                    // Drop Ghost overlay inside track while cross-day moving
                    activeDrag?.takeIf { it.kind == ItemsDragKind.MOVE }?.let { drag ->
                        val tones = itemsEventTones(dark, palette, drag.item.priority, drag.item.done, c)
                        val ghostLeft = (drag.targetDayIndex - calOffset) * stridePx + with(density) { 1.dp.toPx() }
                        val ghostTop = (drag.targetStartMin - ITEMS_GRID_START_MIN) * pxPerMin
                        val ghostH = (drag.targetEndMin - drag.targetStartMin) * pxPerMin
                        val ghostW = (colWidthPx - with(density) { 2.dp.toPx() }).coerceAtLeast(with(density) { 16.dp.toPx() })
                        val gShape = RoundedRectangle(if (dens >= 7) 6.dp else if (dens >= 5) 8.dp else 10.dp)

                        Box(
                            Modifier
                                .graphicsLayer { translationX = ghostLeft; translationY = ghostTop }
                                .width(with(density) { ghostW.toDp() })
                                .height(with(density) { ghostH.toDp() })
                                .zIndex(15f)
                                .clip(gShape)
                                .background(tones.accent.copy(alpha = 0.16f))
                                .border(1.5.dp, tones.accent.copy(alpha = 0.60f), gShape),
                        )
                    }

                    // Active floating dragged / resized personal event card
                    activeDrag?.let { drag ->
                        val leftInVp = (drag.origWorldLeftPx - calOffset * stridePx) + drag.renderOffsetPx.x
                        val topInGrid = if (drag.kind == ItemsDragKind.RESIZE_TOP) {
                            (drag.targetStartMin - ITEMS_GRID_START_MIN) * pxPerMin
                        } else {
                            drag.origTopPx + drag.renderOffsetPx.y
                        }
                        val curH = (drag.targetEndMin - drag.targetStartMin) * pxPerMin
                        ItemsGridEventCard(
                            item = drag.item,
                            leftPx = leftInVp,
                            topPx = topInGrid,
                            widthPx = drag.origWidthPx,
                            heightPx = curH,
                            dens = dens,
                            isSharedLane = false,
                            isClash = false,
                            isEditing = drag.item.id == editingId,
                            isDragging = true,
                            dark = dark,
                            palette = palette,
                            colors = c,
                            startDisplayMin = drag.targetStartMin,
                            endDisplayMin = drag.targetEndMin,
                            modifier = Modifier.zIndex(30f),
                        )
                    }

                    // Edge auto-scroll glow bars
                    if (leftEdgeGlow > 0.01f) {
                        Box(
                            Modifier
                                .align(Alignment.CenterStart)
                                .width(34.dp)
                                .fillMaxHeight()
                                .zIndex(28f)
                                .background(Brush.horizontalGradient(listOf(c.brand.copy(alpha = 0.28f * leftEdgeGlow), Color.Transparent))),
                        )
                    }
                    if (rightEdgeGlow > 0.01f) {
                        Box(
                            Modifier
                                .align(Alignment.CenterEnd)
                                .width(34.dp)
                                .fillMaxHeight()
                                .zIndex(28f)
                                .background(Brush.horizontalGradient(listOf(Color.Transparent, c.brand.copy(alpha = 0.28f * rightEdgeGlow)))),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ItemsGridEventCard(
    item: ItemsRecordModel,
    leftPx: Float,
    topPx: Float,
    widthPx: Float,
    heightPx: Float,
    dens: Int,
    isSharedLane: Boolean,
    isClash: Boolean,
    isEditing: Boolean,
    isDragging: Boolean,
    dark: Boolean,
    palette: String,
    colors: NativeColors,
    startDisplayMin: Int,
    endDisplayMin: Int,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val tones = remember(dark, palette, item.priority, item.done, colors) {
        itemsEventTones(dark, palette, item.priority, item.done, colors)
    }
    val radius = when {
        dens >= 7 -> 6.dp
        dens >= 5 -> 8.dp
        else -> 10.dp
    }
    val shape = remember(radius) { RoundedRectangle(radius) }
    val fillBrush = remember(tones.glassTop, tones.glassBottom) {
        Brush.linearGradient(
            listOf(tones.glassTop.copy(alpha = 0.94f), tones.glassBottom.copy(alpha = 0.90f)),
            start = Offset.Zero,
            end = Offset(260f, 320f),
        )
    }
    val borderWidth = if (isDragging || isEditing) 2.dp else 1.dp
    val borderColor = if (isDragging || isEditing) tones.accent else tones.border
    val compact = heightPx < with(density) { 26.dp.toPx() }
    val showDot = dens < 5 && !compact
    val titleText = item.title
    val showTime = dens < 5 && heightPx >= with(density) { (if (item.isDeadlinePoint) 34.dp else 40.dp).toPx() }
    val startClock = AcademicClock.clock(startDisplayMin)
    val endClock = AcademicClock.clock(endDisplayMin)
    val timeLabel = buildString {
        append(startClock)
        if (item.isDeadlinePoint) append(" 截止")
        else {
            if (dens == 1 || (!isSharedLane && !isClash)) append("–$endClock")
            if (isClash && dens == 1) append(" · 撞课")
        }
    }

    Box(
        Modifier
            .graphicsLayer {
                translationX = leftPx
                translationY = topPx
                if (isDragging) {
                    scaleX = 1.04f
                    scaleY = 1.04f
                }
            }
            .width(with(density) { widthPx.toDp() })
            .height(with(density) { heightPx.toDp() })
            // Caller gestures and semantics go after the translation, so the hit area follows the card.
            .then(modifier)
            .shadow(
                elevation = if (isDragging) 14.dp else if (isEditing) 8.dp else 2.dp,
                shape = shape,
                ambientColor = if (isDragging) Color.Black.copy(0.42f) else tones.accent.copy(0.28f),
                spotColor = if (isDragging) Color.Black.copy(0.42f) else tones.accent.copy(0.28f),
            )
            .clip(shape)
            .background(fillBrush)
            .border(borderWidth, borderColor, shape)
            .alpha(if (item.done) 0.58f else 1f)
            .padding(
                horizontal = when {
                    dens >= 7 -> 2.dp
                    dens >= 5 -> 4.dp
                    isSharedLane -> 4.dp
                    else -> 6.dp
                },
                vertical = if (compact) 0.dp else if (dens >= 7) 2.5.dp else 3.5.dp,
            ),
        contentAlignment = if (compact) Alignment.CenterStart else Alignment.TopStart,
    ) {
        Column(if (compact) Modifier.fillMaxWidth() else Modifier.fillMaxSize()) {
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (showDot) {
                    Box(
                        Modifier
                            .padding(top = 3.5.dp)
                            .size(6.5.dp)
                            .clip(CircleShape)
                            .background(tones.dotFill)
                            .border(1.5.dp, if (item.done) tones.okAccent else tones.accent, CircleShape),
                    )
                }
                val compactSharedOneLine = isSharedLane && dens == 3 && heightPx < with(density) { 68.dp.toPx() }
                BasicText(
                    titleText,
                    maxLines = when {
                        compact -> 1
                        dens >= 7 -> 3
                        compactSharedOneLine -> 1
                        else -> 2
                    },
                    overflow = TextOverflow.Ellipsis,
                    style = nativeTextStyle(
                        color = tones.titleInk,
                        sizeSp = when {
                            compact -> 9f
                            dens >= 7 -> 8.5f
                            dens == 5 -> 10f
                            else -> 11f
                        },
                        lineHeightSp = when {
                            compact -> 11f
                            dens >= 7 -> 10.5f
                            dens == 5 -> 12f
                            else -> 13.5f
                        },
                        weight = FontWeight.Bold,
                        decoration = if (item.done) TextDecoration.LineThrough else TextDecoration.None,
                    ),
                )
            }
            if (showTime) {
                Spacer(Modifier.height(1.dp))
                BasicText(
                    timeLabel,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = nativeTextStyle(
                        color = tones.timeInk,
                        sizeSp = 10f,
                        lineHeightSp = 12f,
                        weight = FontWeight.Medium,
                    ),
                )
            }
        }
    }
}
