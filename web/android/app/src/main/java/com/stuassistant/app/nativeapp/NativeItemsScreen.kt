package com.stuassistant.app.nativeapp

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp as lerpFloat
import androidx.compose.ui.zIndex
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import com.stuassistant.app.glass.LocalLiquidGlassDarkTheme
import com.stuassistant.app.glass.LocalLiquidGlassReducedMotion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun NativeCalendarItemsScreen(
    store: NativeStore,
    s: NativeState,
    onConnect: () -> Unit,
    route: String = "items",
) {
    var records by remember(s.server, s.user?.optString("id"), s.preview) {
        mutableStateOf(
            if (s.preview) {
                ItemsDrafts.samplePreviewRecords(s.calendarFocusDate ?: AcademicClock.moment().date)
            } else {
                emptyList()
            },
        )
    }
    var error by remember(s.server, s.user?.optString("id")) { mutableStateOf<String?>(null) }
    var pendingWrites by remember(s.server, s.user?.optString("id")) { mutableIntStateOf(0) }
    var busyPlans by remember(s.server, s.user?.optString("id")) { mutableStateOf<Set<String>>(emptySet()) }
    val scope = rememberCoroutineScope()
    val openAiComposer = LocalCalendarComposer.current

    LaunchedEffect(s.server, s.user?.optString("id"), s.calendarRevision, s.preview, s.token, pendingWrites == 0) {
        if (!s.preview && s.token != null && pendingWrites == 0) {
            try {
                val loaded = store.calendarItems()
                if (pendingWrites == 0) records = loaded
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                error = e.message
            }
        }
    }
    LaunchedEffect(s.token) {
        if (!s.preview && s.token != null) runCatching { store.loadPlans() }
    }

    fun persistCalendar(write: suspend (String) -> Unit) {
        if (s.preview || s.token == null) return
        if (pendingWrites == 0) error = null
        pendingWrites++
        try {
            store.queueCalendarWrite(
                onFinished = { failure ->
                    if (failure != null) error = failure.message ?: "事项未能保存"
                    pendingWrites--
                },
                write = write,
            )
        } catch (e: Exception) {
            pendingWrites--
            error = e.message ?: "事项未能保存"
        }
    }

    key(s.server, s.user?.optString("id"), s.preview) {
        NativeItemsPage(
            s = s,
            records = records,
            initialRoute = route,
            error = error,
            onOpenAiComposer = { if (s.preview || s.token == null) onConnect() else openAiComposer() },
            onSetRecordDone = { id, done ->
                records = records.map { r ->
                    if (r.optString("id") == id) JSONObject(r.toString()).put("done", done) else r
                }
                persistCalendar { account -> store.setCalendarDone(id, done, account) }
            },
            onTogglePlan = { plan ->
                val id = plan.optString("id")
                if (id !in busyPlans) {
                    busyPlans = busyPlans + id
                    scope.launch {
                        try {
                            store.togglePlan(id, !plan.optBoolean("done"))
                        } catch (e: Exception) {
                            error = e.message
                        } finally {
                            busyPlans = busyPlans - id
                        }
                    }
                }
            },
            onSetCourseDone = store::setTaskDone,
            busyPlanIds = busyPlans,
            onDeleteRecord = { id ->
                records = records.filterNot { it.optString("id") == id }
                persistCalendar { account -> store.removeCalendar(id, account) }
            },
            onDeletePlan = { plan ->
                val id = plan.optString("id")
                if (id.isNotBlank() && id !in busyPlans) {
                    busyPlans = busyPlans + id
                    scope.launch {
                        try {
                            store.removePlan(id)
                        } catch (e: Exception) {
                            error = e.message
                        } finally {
                            busyPlans = busyPlans - id
                        }
                    }
                }
            },
            onUpdateRecordDraft = { id, draft ->
                records = records.map { r ->
                    if (r.optString("id") == id) JSONObject(r.toString()).put("draft", JSONObject(draft.toString())) else r
                }
                persistCalendar { account -> store.updateCalendarDraft(id, draft, account) }
            },
            onUpsertRecord = { record, index ->
                val id = record.optString("id")
                val list = records.toMutableList()
                val idx = list.indexOfFirst { it.optString("id") == id }
                if (idx >= 0) list[idx] = record
                else if (index != null) list.add(index.coerceIn(0, list.size), record)
                else list.add(record)
                records = list
                persistCalendar { account -> store.upsertCalendarRecord(record, index, account) }
            },
        )
    }
}

@Composable
internal fun NativeItemsPage(
    s: NativeState,
    records: List<JSONObject>,
    initialRoute: String = "items",
    initialGrid: Boolean = initialRoute.substringAfter(':', "") == "grid",
    initialEditingId: String? = null,
    initialAcadOpen: Boolean = initialRoute.substringAfter(':', "") == "academic",
    error: String? = null,
    onAdd: (() -> Unit)? = null,
    onOpenAiComposer: (() -> Unit)? = null,
    onSetRecordDone: (String, Boolean) -> Unit,
    onTogglePlan: (JSONObject) -> Unit = {},
    onSetCourseDone: (String, Boolean) -> Unit,
    busyPlanIds: Set<String> = emptySet(),
    onDeleteRecord: (String) -> Unit = {},
    onDeletePlan: (JSONObject) -> Unit = {},
    onUpdateRecordDraft: (id: String, draft: JSONObject) -> Unit = { _, _ -> },
    onUpsertRecord: (record: JSONObject, index: Int?) -> Unit = { _, _ -> },
) {
    val c = LocalNativeColors.current
    val dark = LocalLiquidGlassDarkTheme.current == true
    val palette = LocalNativePalette.current
    val reduced = LocalLiquidGlassReducedMotion.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val tick = nSelectionFeedback()
    val submitHaptic = nHapticFeedback(NativeButtonHaptic.Submit.release)

    var now by remember { mutableStateOf(AcademicClock.moment()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = AcademicClock.moment()
            delay(30_000L)
        }
    }

    val initialScope = when (initialRoute.substringAfter(':', "")) {
        "school" -> "school"
        "inbox" -> "inbox"
        else -> "schedule"
    }

    val initialDate = s.calendarFocusDate ?: now.date
    var earliestDate by rememberSaveable { mutableStateOf(initialDate) }
    var latestDate by rememberSaveable { mutableStateOf(initialDate) }
    val dateWindow = remember(now.date, earliestDate, latestDate) {
        ItemsDateWindow(now.date, earliestDate, latestDate)
    }
    var currentScope by rememberSaveable { mutableStateOf(initialScope) }
    var isGrid by rememberSaveable { mutableStateOf(initialGrid) }
    var calCols by rememberSaveable { mutableIntStateOf(3) }
    var focusedDate by rememberSaveable { mutableStateOf(s.calendarFocusDate ?: now.date) }
    var pendingFocus by remember { mutableStateOf<String?>(null) }
    var monthOpen by remember { mutableStateOf(false) }
    var scaleMenuOpen by remember { mutableStateOf(false) }
    var acadOpen by rememberSaveable { mutableStateOf(initialAcadOpen) }
    var handledCalendarBatch by rememberSaveable { mutableStateOf<String?>(null) }

    // Update scope when navigated via e.g. Today screen's "全部作业"
    LaunchedEffect(initialRoute) {
        when (initialRoute.substringAfter(':', "")) {
            "school" -> currentScope = "school"
            "inbox" -> currentScope = "inbox"
        }
    }

    // Working reactive copy synced from external `records`
    var workingRecords by remember { mutableStateOf(records) }
    LaunchedEffect(records) { workingRecords = records }

    val models = remember(workingRecords) {
        workingRecords.mapNotNull { ItemsDrafts.parseRecord(it) }
    }
    val latestModels by rememberUpdatedState(models)
    val latestDoneIds by rememberUpdatedState(s.done)
    val doc = remember(s.timetable) { AcademicTimetable.normalize(s.timetable) }
    val tasks = remember(s.tasks) { s.tasks.map(AcademicRecords::task).distinctBy(::itemsTaskKey) }
    val exams = remember(s.exams) { s.exams.map(AcademicRecords::exam).distinctBy(AcademicExam::id) }
    val grades = remember(s.grades) { s.grades.map(AcademicRecords::grade) }

    val initialDayIdx = remember(focusedDate, dateWindow) {
        dateWindow.indexOf(focusedDate).let { if (it >= 0) it else dateWindow.todayIndex }
    }
    val pagerState = key(dateWindow.dates.first()) {
        rememberPagerState(initialPage = initialDayIdx, pageCount = { dateWindow.totalDays })
    }
    var listAlignment by remember(pagerState) { mutableStateOf<Int?>(null) }
    var listScrubOffset by remember(pagerState) { mutableStateOf<Float?>(null) }
    var listScrubbing by remember(pagerState) { mutableStateOf(false) }
    var listNavigation by remember(pagerState) { mutableStateOf<Job?>(null) }
    val listOffset = listAlignment?.toFloat() ?: listScrubOffset
        ?: (pagerState.currentPage + pagerState.currentPageOffsetFraction)
    val listDateMoving = listScrubbing || pagerState.isScrollInProgress

    LaunchedEffect(pagerState.settledPage, listDateMoving, listAlignment, currentScope, isGrid, pendingFocus) {
        val alignment = listAlignment
        if (alignment != null) {
            if (!listDateMoving && pagerState.settledPage == alignment) listAlignment = null
        } else if (currentScope == "schedule" && !isGrid && pendingFocus == null && !listDateMoving) {
            focusedDate = dateWindow.dateAt(pagerState.settledPage)
        }
    }

    var calColsF by remember { mutableFloatStateOf(calCols.toFloat()) }
    var calOffset by remember(dateWindow.dates.first()) {
        mutableFloatStateOf(ItemsCalendarMath.offsetForDayIndex(initialDayIdx, calCols, dateWindow.totalDays))
    }
    var gridDateMoving by remember { mutableStateOf(false) }
    var gridNavigation by remember { mutableStateOf<Job?>(null) }

    val gridScrollState = rememberScrollState(initial = 0)
    val inboxScrollState = rememberLazyListState()
    val schoolScrollState = rememberLazyListState()
    val dayListStateHolder = rememberSaveableStateHolder()

    var editingId by remember { mutableStateOf(initialEditingId) }
    var editSheetHeightPx by remember { mutableIntStateOf(0) }
    var contextModal by remember { mutableStateOf<ItemsContextModal?>(null) }
    var isGridDragging by remember { mutableStateOf(false) }
    var livePillText by remember { mutableStateOf<String?>(null) }
    var livePillTimeout by remember { mutableStateOf<Job?>(null) }

    fun updateLivePill(text: String?) {
        livePillTimeout?.cancel()
        livePillTimeout = null
        livePillText = text
    }

    // Quick-add composer state (persists draft if closed without saving)
    var quickAddOpen by remember { mutableStateOf(false) }
    var quickTitle by rememberSaveable { mutableStateOf("") }
    var quickNote by rememberSaveable { mutableStateOf("") }
    var quickPriorityCode by rememberSaveable { mutableIntStateOf(0) }
    var quickDefaultDate by rememberSaveable { mutableStateOf<String?>(null) }
    var quickManualDate by rememberSaveable { mutableStateOf<String?>(null) }
    var quickManualStart by rememberSaveable { mutableStateOf<Int?>(null) }
    var quickManualEnd by rememberSaveable { mutableStateOf<Int?>(null) }
    var quickManualAllDay by rememberSaveable { mutableStateOf(false) }
    var quickTokenOff by rememberSaveable { mutableStateOf(false) }
    var quickSubmitted by rememberSaveable { mutableStateOf(false) }
    val hasQuickDraft = !quickSubmitted && (quickTitle.isNotBlank() || quickNote.isNotBlank())

    fun prepareQuickDraft() {
        if (!hasQuickDraft) {
            quickTitle = ""
            quickNote = ""
            quickPriorityCode = 0
            quickDefaultDate = if (currentScope == "schedule") focusedDate else null
            quickManualDate = null
            quickManualStart = null
            quickManualEnd = null
            quickManualAllDay = false
            quickTokenOff = false
        }
        quickSubmitted = false
    }

    // 5-second countdown Undo Toast state
    var toastSeq by remember { mutableLongStateOf(0L) }
    var activeToast by remember { mutableStateOf<ItemsToastData?>(null) }

    fun showToast(message: String, onUndo: (() -> Unit)? = null) {
        toastSeq += 1L
        activeToast = ItemsToastData(
            id = toastSeq,
            message = message,
            hasUndo = onUndo != null,
            onUndo = onUndo,
        )
    }

    // Mutations (optimistic local + store persistence + Undo support)
    fun setRecordDone(id: String, done: Boolean) {
        val old = workingRecords.firstOrNull { it.optString("id") == id } ?: return
        if (old.optBoolean("done") == done) return
        workingRecords = workingRecords.map { r ->
            if (r.optString("id") == id) JSONObject(r.toString()).put("done", done) else r
        }
        onSetRecordDone(id, done)
    }

    fun commitToggleRecord(item: ItemsRecordModel) {
        val current = workingRecords.firstOrNull { it.optString("id") == item.id } ?: return
        val wasDone = current.optBoolean("done")
        val nextDone = !wasDone
        val linkedChange = item.schoolTaskId?.takeIf { (it in latestDoneIds) != nextDone }
        setRecordDone(item.id, nextDone)
        linkedChange?.let { onSetCourseDone(it, nextDone) }
        if (nextDone && editingId == null) {
            showToast("已完成「${item.title}」") {
                setRecordDone(item.id, wasDone)
                linkedChange?.let { onSetCourseDone(it, wasDone) }
            }
        }
    }

    fun commitToggleHomework(task: AcademicTask) {
        val nextDone = task.id !in latestDoneIds
        val linkedChange = latestModels.firstOrNull { it.schoolTaskId == task.id && it.done != nextDone }
        onSetCourseDone(task.id, nextDone)
        linkedChange?.let { setRecordDone(it.id, nextDone) }
        if (nextDone) {
            showToast("已完成作业「${task.title}」") {
                onSetCourseDone(task.id, false)
                linkedChange?.let { setRecordDone(it.id, it.done) }
            }
        }
    }

    fun commitDraftEdit(item: ItemsRecordModel, nextDraft: JSONObject) {
        workingRecords = workingRecords.map { r ->
            if (r.optString("id") == item.id) JSONObject(r.toString()).put("draft", JSONObject(nextDraft.toString())) else r
        }
        onUpdateRecordDraft(item.id, nextDraft)
    }

    fun cancelGridNavigation() {
        gridNavigation?.cancel()
        gridDateMoving = false
    }

    suspend fun animateGridViewport(
        targetCols: Int,
        targetOff: Float,
        moveOffset: (Float) -> Unit = { calOffset = it },
    ) {
        val oldCols = calColsF
        val oldOff = calOffset
        if (!reduced && (abs(oldCols - targetCols) > 0.005f || abs(oldOff - targetOff) > 0.005f)) {
            animate(0f, 1f, animationSpec = spring(dampingRatio = 1f, stiffness = 420f)) { value, _ ->
                calColsF = lerpFloat(oldCols, targetCols.toFloat(), value)
                moveOffset(lerpFloat(oldOff, targetOff, value))
            }
        }
        calColsF = targetCols.toFloat()
        moveOffset(targetOff)
    }

    fun animateCalViewTo(targetCols: Int, targetOff: Float) {
        cancelGridNavigation()
        val safeOff = targetOff.coerceIn(0f, (dateWindow.totalDays - targetCols).coerceAtLeast(0).toFloat())
        calCols = targetCols
        gridDateMoving = true
        gridNavigation = scope.launch {
            animateGridViewport(targetCols, safeOff)
            val first = safeOff.roundToInt()
            val current = dateWindow.indexOf(focusedDate)
            if (current !in first until first + targetCols) focusedDate = dateWindow.centerDate(safeOff, targetCols.toFloat())
            gridDateMoving = false
        }
    }

    fun beginGridNavigation(): Float {
        cancelGridNavigation()
        scaleMenuOpen = false
        updateLivePill(null)
        gridDateMoving = true
        return calOffset
    }

    fun panGridTo(offset: Float) {
        val maxOffset = (dateWindow.totalDays - calColsF).coerceAtLeast(0f)
        val crossed = ItemsCalendarMath.crossedGridDates(
            calOffset.coerceIn(0f, maxOffset),
            offset.coerceIn(0f, maxOffset),
        )
        calOffset = offset
        repeat(crossed) { tick() }
    }

    fun releaseGridPan(velocity: Float) {
        if (!isGrid || currentScope != "schedule") return
        cancelGridNavigation()
        gridDateMoving = true
        val targetCols = calCols
        gridNavigation = scope.launch {
            val maxOffset = (dateWindow.totalDays - calColsF).coerceAtLeast(0f)
            val maxVelocity = ItemsCalendarMath.gridFlingMaxVelocity
            var remainingVelocity = velocity.coerceIn(-maxVelocity, maxVelocity)
            if (!reduced && abs(remainingVelocity) >= ItemsCalendarMath.gridFlingStopVelocity && calOffset in 0f..maxOffset) {
                var previousFrame = withFrameNanos { it }
                while (abs(remainingVelocity) >= ItemsCalendarMath.gridFlingStopVelocity) {
                    val frame = withFrameNanos { it }
                    val dt = ((frame - previousFrame) / 1_000_000_000f).coerceIn(0f, 0.04f)
                    previousFrame = frame
                    val next = ItemsCalendarMath.stepGridFling(calOffset, remainingVelocity, dt, maxOffset)
                    panGridTo(next.offset)
                    remainingVelocity = next.velocity
                }
            }

            val stop = ItemsCalendarMath.nearestGridStop(calOffset, targetCols, dateWindow.totalDays)
            animateGridViewport(targetCols, stop, ::panGridTo)
            focusedDate = dateWindow.centerDate(stop, targetCols.toFloat())
            gridDateMoving = false
        }
    }

    fun alignListToDay(index: Int) {
        listNavigation?.cancel()
        listScrubbing = false
        listScrubOffset = null
        listAlignment = index
        pagerState.requestScrollToPage(index)
    }

    fun jumpToDate(date: String) {
        val idx = dateWindow.indexOf(date)
        currentScope = "schedule"
        monthOpen = false
        scaleMenuOpen = false
        if (idx < 0) {
            if (runCatching { java.time.LocalDate.parse(date) }.isFailure) return
            pendingFocus = date
            focusedDate = date
            earliestDate = minOf(earliestDate, date)
            latestDate = maxOf(latestDate, date)
            return
        }
        if (isGrid) {
            focusedDate = date
            animateCalViewTo(calCols, ItemsCalendarMath.offsetForDayIndex(idx, calCols, dateWindow.totalDays))
        } else {
            listNavigation?.cancel()
            listAlignment = null
            listScrubbing = false
            listScrubOffset = null
            listNavigation = scope.launch {
                if (reduced) pagerState.scrollToPage(idx)
                else pagerState.animateScrollToPage(idx, animationSpec = spring(0.88f, 340f))
                focusedDate = date
            }
        }
    }

    LaunchedEffect(pendingFocus, dateWindow) {
        val date = pendingFocus ?: return@LaunchedEffect
        val idx = dateWindow.indexOf(date)
        if (idx < 0) return@LaunchedEffect
        alignListToDay(idx)
        cancelGridNavigation()
        calColsF = calCols.toFloat()
        calOffset = ItemsCalendarMath.offsetForDayIndex(idx, calCols, dateWindow.totalDays)
        pendingFocus = null
    }
    LaunchedEffect(s.calendarLastBatch) {
        val batch = s.calendarLastBatch ?: return@LaunchedEffect
        if (batch != handledCalendarBatch) {
            handledCalendarBatch = batch
            s.calendarFocusDate?.let { jumpToDate(it) }
        }
    }

    fun commitMoveToInbox(item: ItemsRecordModel) {
        val prevDraft = JSONObject(item.rawDraft.toString())
        val prevDate = item.date
        val nextDraft = ItemsDrafts.moveToInbox(prevDraft)
        editingId = null
        commitDraftEdit(item, nextDraft)
        showToast(if (item.hasIndependentDates) "已取消排期" else "已移至待安排") {
            commitDraftEdit(item, prevDraft)
            if (prevDate != null) jumpToDate(prevDate)
        }
    }

    fun commitDeleteItem(item: ItemsRecordModel) {
        val removedIdx = workingRecords.indexOfFirst { it.optString("id") == item.id }
        val removedRecord = JSONObject(item.rawRecord.toString())
        editingId = null
        workingRecords = workingRecords.filterNot { it.optString("id") == item.id }
        onDeleteRecord(item.id)
        showToast("已删除「${item.title}」") {
            val mut = workingRecords.toMutableList()
            if (removedIdx >= 0) mut.add(removedIdx.coerceIn(0, mut.size), removedRecord)
            else mut.add(removedRecord)
            workingRecords = mut
            onUpsertRecord(removedRecord, if (removedIdx >= 0) removedIdx else null)
        }
    }

    fun scheduleInboxItem(item: ItemsRecordModel) {
        val prevDraft = JSONObject(item.rawDraft.toString())
        val suggestion = listOf(now.date, AcademicClock.addDays(now.date, 1)).firstNotNullOfOrNull { day ->
            val busy = ItemsSmartScheduler.busyIntervalsOn(day, doc, exams, models, item.id)
            ItemsSmartScheduler.findFreeSlot(60, 12 * 60 + 30, busy, dayStartMin = ItemsSmartScheduler.upcomingStart(day, now))
                ?.let { day to it }
        }
        if (suggestion == null) {
            showToast("今天和明天暂无 1 小时空档")
            return
        }
        val (targetDate, slot) = suggestion
        val nextDraft = ItemsDrafts.setTimed(prevDraft, targetDate, slot.first, slot.second)
        commitDraftEdit(item, nextDraft)
        submitHaptic()
        showToast("已排入 ${AcademicClock.slash(targetDate)} ${AcademicClock.weekdayText(AcademicClock.weekday(targetDate))} ${AcademicClock.clock(slot.first)}") {
            commitDraftEdit(item, prevDraft)
        }
    }

    fun triageHomeworkTask(task: AcademicTask) {
        val existing = models.firstOrNull { it.schoolTaskId == task.id }
        if (existing != null && existing.isScheduled) {
            jumpToDate(existing.date!!)
            editingId = existing.id
            return
        }
        val deadline = task.dueMs?.takeIf { it > now.ms }?.let(AcademicClock::moment)
        val startSearch = deadline?.let { maxOf(now.date, AcademicClock.addDays(it.date, -1)) } ?: now.date
        var targetDate = startSearch
        var chosen: Pair<Int, Int>? = null
        for (offset in 0..3) {
            val d = AcademicClock.addDays(startSearch, offset)
            if (deadline != null && d > deadline.date) break
            val start = ItemsSmartScheduler.upcomingStart(d, now)
            val end = if (deadline != null && d == deadline.date) minOf(deadline.minutes, ITEMS_GRID_END_MIN) else ITEMS_GRID_END_MIN
            val busy = ItemsSmartScheduler.busyIntervalsOn(d, doc, exams, models, existing?.id)
            val slot = ItemsSmartScheduler.findFreeSlot(90, 19 * 60, busy, start, end)
                ?: ItemsSmartScheduler.findFreeSlot(60, 19 * 60, busy, start, end)
            if (slot != null) {
                targetDate = d
                chosen = slot
                break
            }
        }
        val slot = chosen ?: run {
            showToast(if (deadline != null) "截止前暂无 1 小时空档" else "近几天暂无 1 小时空档")
            return
        }
        submitHaptic()
        if (existing != null) {
            val prevDraft = JSONObject(existing.rawDraft.toString())
            val nextDraft = ItemsDrafts.setTimed(prevDraft, targetDate, slot.first, slot.second)
            commitDraftEdit(existing, nextDraft)
            showToast("已排入 ${AcademicClock.slash(targetDate)} ${AcademicClock.clock(slot.first)}") {
                commitDraftEdit(existing, prevDraft)
            }
        } else {
            val newRec = ItemsDrafts.newRecord(
                title = task.title,
                note = "${task.course} · ${itemsTaskDeadline(task)}",
                priority = if (AcademicRecords.bucket(task, now.ms) == "overdue") ItemsPriority.URGENT else ItemsPriority.IMPORTANT,
                date = targetDate,
                startMin = slot.first,
                endMin = slot.second,
                schoolTaskId = task.id,
                done = task.id in s.done,
            )
            val createdId = newRec.getString("id")
            workingRecords = workingRecords + newRec
            onUpsertRecord(newRec, null)
            showToast("已排入 ${AcademicClock.slash(targetDate)} ${AcademicClock.clock(slot.first)}") {
                workingRecords = workingRecords.filterNot { it.optString("id") == createdId }
                onDeleteRecord(createdId)
            }
        }
    }

    fun scheduleExamReview(ex: AcademicExam) {
        val revTitle = "${ex.title.substringBefore('·').trim()} 考前复习"
        val existing = models.firstOrNull { it.title == revTitle }
        if (existing != null && existing.isScheduled) {
            acadOpen = false
            jumpToDate(existing.date!!)
            editingId = existing.id
            return
        }
        val exDate = ex.date ?: AcademicClock.addDays(now.date, 3)
        val targetDate = AcademicClock.addDays(exDate, -1).let { if (it < now.date) now.date else it }
        val busy = ItemsSmartScheduler.busyIntervalsOn(targetDate, doc, exams, models)
        val start = ItemsSmartScheduler.upcomingStart(targetDate, now)
        val end = if (targetDate == exDate && ex.startMs != null) minOf(AcademicClock.moment(ex.startMs).minutes, ITEMS_GRID_END_MIN)
        else ITEMS_GRID_END_MIN
        val slot = ItemsSmartScheduler.findFreeSlot(90, 14 * 60, busy, start, end)
            ?: ItemsSmartScheduler.findFreeSlot(60, 19 * 60, busy, start, end)
            ?: run {
                acadOpen = false
                showToast("考前暂无可自动安排的空档")
                return
            }
        val rec = ItemsDrafts.newRecord(
            title = revTitle,
            note = listOf(ex.timeRaw, ex.location).filter { it.isNotBlank() }.joinToString(" · "),
            priority = ItemsPriority.URGENT,
            date = targetDate,
            startMin = slot.first,
            endMin = slot.second,
        )
        val createdId = rec.getString("id")
        workingRecords = workingRecords + rec
        onUpsertRecord(rec, null)
        submitHaptic()
        acadOpen = false
        jumpToDate(targetDate)
        showToast("已排入复习：${AcademicClock.slash(targetDate)} ${AcademicClock.clock(slot.first)}") {
            workingRecords = workingRecords.filterNot { it.optString("id") == createdId }
            onDeleteRecord(createdId)
        }
    }

    fun openQuickAddForSlot(date: String, startMin: Int?, endMin: Int?, allDay: Boolean, presetTitle: String = "") {
        editingId = null
        contextModal = null
        prepareQuickDraft()
        if (quickTitle.isBlank()) quickTitle = presetTitle
        quickManualDate = date
        quickManualStart = startMin
        quickManualEnd = endMin
        quickManualAllDay = allDay
        quickTokenOff = false
        quickAddOpen = true
    }

    // Content top = ItemsTopHeader's fixed rows (status + 4 + 44 titlebar + 12 + 38 scope), plus the animated week strip
    // and grid column labels. The month drawer overlays content and never shifts it, so open/close cannot jump.
    val statusTopDp = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val stripTargetDp = if (currentScope == "schedule") 60.dp else 0.dp
    val subTargetDp = when {
        currentScope != "schedule" || !isGrid -> 0.dp
        calColsF in 1.4f..6.4f -> 28.75.dp
        else -> 6.75.dp
    }
    val stripHeightDp by animateDpAsState(
        stripTargetDp,
        if (reduced) snap() else if (stripTargetDp > 0.dp) spring(0.86f, 480f) else tween(160),
        label = "items-strip-h",
    )
    val subHeightDp by animateDpAsState(
        subTargetDp,
        if (reduced) snap() else if (subTargetDp > 7.dp) spring(0.86f, 480f) else tween(150),
        label = "items-sub-h",
    )
    val headerHeightDp = statusTopDp + 98.dp + stripHeightDp + subHeightDp
    val navBottomInsetDp = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    // Per-day urgency & course helpers for Week Strip / Month Drawer
    val dayHasCourse: (String) -> Boolean = remember(doc) {
        { d -> AcademicTimetable.planFor(doc, d).blocks.isNotEmpty() }
    }
    val dayUrgency: (String) -> ItemsDayUrgency = remember(models, tasks, exams, s.done) {
        { d ->
            val hasExam = exams.any { it.date == d }
            val hasDueTask = tasks.any { it.id !in s.done && it.dueMs != null && AcademicClock.moment(it.dueMs).date == d }
            val hasUrgentItem = models.any { it.date == d && !it.done && it.priority == ItemsPriority.URGENT }
            val hasNormalItem = models.any { it.date == d && !it.done }
            when {
                hasExam || hasDueTask || hasUrgentItem -> ItemsDayUrgency.URGENT
                hasNormalItem -> ItemsDayUrgency.NORMAL
                else -> ItemsDayUrgency.NONE
            }
        }
    }

    // Counts for scope bar
    val inboxCount = remember(models) { models.count { !it.isScheduled && !it.done } }
    val undoneSchoolCount = remember(tasks, s.done) { tasks.count { it.id !in s.done } }

    // Academic push layer animation (0f = main Items, 1f = Academic layer pushed on top)
    val acadProgress = remember { Animatable(if (acadOpen) 1f else 0f) }
    var acadDragProgress by remember { mutableStateOf<Float?>(null) }
    val academicProgress = acadDragProgress ?: acadProgress.value
    LaunchedEffect(acadOpen, reduced) {
        if (reduced) acadProgress.snapTo(if (acadOpen) 1f else 0f)
        else acadProgress.animateTo(if (acadOpen) 1f else 0f, spring(dampingRatio = 0.86f, stiffness = 460f))
    }

    // Clean Backdrop graph: baseBackdrop -> itemsContentBackdrop -> itemsChromeBackdrop (overlays ONLY)
    val baseBackdrop = LocalNativeBackdrop.current
    val itemsContentBackdrop = rememberLayerBackdrop()
    val itemsChromeBackdrop = rememberCombinedBackdrop(baseBackdrop, itemsContentBackdrop)

    val editingItem = editingId?.let { eid -> models.firstOrNull { it.id == eid } }
    NativeSuppressBottomBar(editingItem != null || quickAddOpen || acadOpen)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val screenWidthPx = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)

        // MAIN LAYER (`日程 / 待安排 / 作业`)
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val p = academicProgress
                    translationX = -28.dp.toPx() * p
                    val sc = 1f - 0.035f * p
                    scaleX = sc
                    scaleY = sc
                    alpha = 1f - 0.48f * p
                }
                .then(if (acadOpen) Modifier.clearAndSetSemantics {} else Modifier),
        ) {
            // 1) Scrolling / panning content viewport captured into `itemsContentBackdrop`
            Box(
                Modifier
                    .fillMaxSize()
                    .layerBackdrop(itemsContentBackdrop),
            ) {
                if (!error.isNullOrBlank()) {
                    Box(Modifier.padding(top = headerHeightDp + 8.dp, start = 16.dp, end = 16.dp).zIndex(2f)) {
                        NError(error)
                    }
                }
                val paneKey = when {
                    currentScope == "inbox" -> 1
                    currentScope == "school" -> 2
                    isGrid -> 3
                    else -> 0
                }
                AnimatedContent(
                    targetState = paneKey,
                    transitionSpec = { itemsPaneTransition(initialState, targetState, reduced, density.density) },
                    modifier = Modifier.fillMaxSize(),
                    label = "items-pane",
                ) { pane ->
                    when (pane) {
                        1 -> {
                            val inboxItems = remember(models) { models.filter { !it.isScheduled } }
                            ItemsInboxListView(
                                items = inboxItems,
                                topPaddingDp = headerHeightDp,
                                bottomNavInsetDp = navBottomInsetDp,
                                scrollState = inboxScrollState,
                                editingId = editingId,
                                onToggleDone = ::commitToggleRecord,
                                onTapItem = { editingId = it.id },
                                onQuickSchedule = ::scheduleInboxItem,
                            )
                        }
                        2 -> {
                            ItemsHomeworkListView(
                                tasks = tasks,
                                doneIds = s.done,
                                linkedItems = models,
                                now = now,
                                topPaddingDp = headerHeightDp,
                                bottomNavInsetDp = navBottomInsetDp,
                                scrollState = schoolScrollState,
                                onToggleTask = ::commitToggleHomework,
                                onTapTask = { contextModal = ItemsContextModal.Homework(it) },
                                onTriage = ::triageHomeworkTask,
                                onJumpToScheduled = { linked ->
                                    jumpToDate(linked.date!!)
                                    editingId = linked.id
                                },
                            )
                        }
                        else -> {
                            if (pane == 3) {
                                ItemsCalendarGridView(
                                    dateWindow = dateWindow,
                                    calOffset = calOffset,
                                    calColsF = calColsF,
                                    calCols = calCols,
                                    topContentPaddingDp = headerHeightDp,
                                    bottomNavInsetDp = navBottomInsetDp,
                                    editingId = editingId,
                                    editSheetHeightPx = if (editingItem?.isTimed == true) editSheetHeightPx else 0,
                                    doc = doc,
                                    tasks = tasks,
                                    doneTaskIds = s.done,
                                    exams = exams,
                                    items = models.filter { it.isScheduled },
                                    now = now,
                                    scrollState = gridScrollState,
                                    onBeginNavigation = ::beginGridNavigation,
                                    onCalOffsetChange = { calOffset = it },
                                    onPanOffsetChange = ::panGridTo,
                                    onCalViewChange = { nextColsF, nextOff ->
                                        calColsF = nextColsF
                                        calOffset = nextOff
                                    },
                                    onReleasePan = ::releaseGridPan,
                                    onSettleOffset = { animateCalViewTo(calCols, it) },
                                    onReleasePinch = { colsVel, anchorDay, anchorFrac ->
                                        val proj = calColsF + (colsVel * 0.11f).coerceIn(-1.4f, 1.4f)
                                        val targetCols = ItemsCalendarMath.nearestScale(proj)
                                        val targetOff = ItemsCalendarMath.snapOffsetForScale(targetCols, anchorDay, anchorFrac, dateWindow.totalDays)
                                        animateCalViewTo(targetCols, targetOff)
                                        updateLivePill("${ItemsCalendarMath.scaleShortName(targetCols)}视图")
                                        livePillTimeout = scope.launch {
                                            delay(620L)
                                            livePillText = null
                                        }
                                    },
                                    onGridDraggingChanged = { isGridDragging = it },
                                    onLivePillText = ::updateLivePill,
                                    onTapItem = { editingId = it.id },
                                    onDeselectEditing = { editingId = null },
                                    onTapCourse = { block, d -> contextModal = ItemsContextModal.Course(block, d) },
                                    onTapHomework = { t -> contextModal = ItemsContextModal.Homework(t) },
                                    onTapDayAllDay = { d -> contextModal = ItemsContextModal.AllDay(d) },
                                    onTapExam = { acadOpen = true },
                                    onTapEmptySlot = { d, startMin -> openQuickAddForSlot(d, startMin, (startMin + 60).coerceAtMost(ITEMS_GRID_END_MIN), false) },
                                    onCommitItemMove = { item, newDate, startMin, endMin, isResize, snapOff ->
                                        val nextDraft = ItemsDrafts.setTimed(item.rawDraft, newDate, startMin, endMin)
                                        commitDraftEdit(item, nextDraft)
                                        focusedDate = newDate
                                        if (snapOff != null && abs(calOffset - snapOff) > 0.005f) {
                                            animateCalViewTo(calCols, snapOff)
                                        }
                                        if (editingId != item.id) {
                                            val durMin = endMin - startMin
                                            val wd = AcademicClock.weekdayText(AcademicClock.weekday(newDate))
                                            showToast(
                                                if (isResize) "时长已调为 ${AcademicClock.clock(startMin)}–${AcademicClock.clock(endMin)}（${ItemsCalendarMath.formatDuration(durMin)}）"
                                                else if (item.isDeadlinePoint) "截止改为 ${AcademicClock.monthDay(newDate)} ${AcademicClock.clock(startMin)}"
                                                else "已改期至 ${AcademicClock.monthDay(newDate)} $wd ${AcademicClock.clock(startMin)}",
                                            ) {
                                                commitDraftEdit(item, item.rawDraft)
                                                item.date?.let { jumpToDate(it) }
                                            }
                                        }
                                    },
                                )
                            } else {
                                HorizontalPager(
                                    state = pagerState,
                                    key = { dateWindow.dates[it] },
                                    beyondViewportPageCount = 1,
                                    modifier = Modifier.fillMaxSize().testTag("items-list-pager"),
                                ) { pageIdx ->
                                    val d = dateWindow.dates[pageIdx]
                                    dayListStateHolder.SaveableStateProvider(d) {
                                        ItemsDayListPane(
                                            date = d,
                                            todayDate = now.date,
                                            dateWindow = dateWindow,
                                            topPaddingDp = headerHeightDp,
                                            bottomNavInsetDp = navBottomInsetDp,
                                            scrollState = rememberLazyListState(),
                                            doc = doc,
                                            tasks = tasks,
                                            doneIds = s.done,
                                            exams = exams,
                                            items = models,
                                            plans = s.plans,
                                            busyPlanIds = busyPlanIds,
                                            editingId = editingId,
                                            onToggleTask = ::commitToggleHomework,
                                            onToggleItem = ::commitToggleRecord,
                                            onTogglePlan = onTogglePlan,
                                            onDeletePlan = onDeletePlan,
                                            onTapTask = { contextModal = ItemsContextModal.Homework(it) },
                                            onTapCourse = { block, cd -> contextModal = ItemsContextModal.Course(block, cd) },
                                            onTapItem = { editingId = it.id },
                                            onTapExam = { acadOpen = true },
                                            onTapGapAdd = { start, end -> openQuickAddForSlot(d, start, end, false) },
                                            onJumpDay = { nextD -> jumpToDate(nextD) },
                                            nowMs = now.ms,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 2) Scrim behind collapsible Month Drawer
            AnimatedVisibility(
                visible = currentScope == "schedule" && monthOpen,
                enter = if (reduced) EnterTransition.None else fadeIn(tween(160)),
                exit = if (reduced) ExitTransition.None else fadeOut(tween(130)),
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.22f))
                        .then(if (monthOpen) Modifier.pointerInput(Unit) { detectTapGestures { monthOpen = false } } else Modifier),
                )
            }

            // 3) Sticky Refractive Top Header
            ItemsTopHeader(
                backdrop = itemsChromeBackdrop,
                dateWindow = dateWindow,
                scope = currentScope,
                isGrid = isGrid,
                focusedDate = focusedDate,
                listOffset = listOffset,
                dateMoving = if (isGrid) gridDateMoving || isGridDragging else listDateMoving,
                calOffset = calOffset,
                calColsF = calColsF,
                calCols = calCols,
                inboxCount = inboxCount,
                schoolCount = undoneSchoolCount,
                monthOpen = monthOpen,
                scaleMenuOpen = scaleMenuOpen,
                dayHasCourse = dayHasCourse,
                dayUrgency = dayUrgency,
                onSelectScope = { nextScope ->
                    cancelGridNavigation()
                    currentScope = nextScope
                    monthOpen = false
                    scaleMenuOpen = false
                    editingId = null
                    contextModal = null
                    updateLivePill(null)
                },
                onToggleView = {
                    if (currentScope != "schedule") currentScope = "schedule"
                    monthOpen = false
                    scaleMenuOpen = false
                    editingId = null
                    updateLivePill(null)
                    val date = when {
                        isGrid && gridDateMoving -> dateWindow.centerDate(calOffset, calColsF)
                        !isGrid && listDateMoving -> dateWindow.dateAt(listOffset.roundToInt())
                        else -> focusedDate
                    }
                    val idx = dateWindow.indexOf(date).takeIf { it >= 0 } ?: dateWindow.todayIndex
                    val nextGrid = !isGrid
                    focusedDate = dateWindow.dateAt(idx)
                    cancelGridNavigation()
                    alignListToDay(idx)
                    if (nextGrid) {
                        calColsF = calCols.toFloat()
                        calOffset = ItemsCalendarMath.offsetForDayIndex(idx, calCols, dateWindow.totalDays)
                    }
                    isGrid = nextGrid
                },
                onToggleMonthDrawer = {
                    scaleMenuOpen = false
                    monthOpen = !monthOpen
                },
                onGoToday = { jumpToDate(now.date) },
                onOpenAcademic = {
                    editingId = null
                    contextModal = null
                    monthOpen = false
                    scaleMenuOpen = false
                    acadOpen = true
                },
                onSelectDate = { d -> jumpToDate(d) },
                onToggleScaleMenu = { scaleMenuOpen = !scaleMenuOpen },
                onBeginCalScrub = ::beginGridNavigation,
                onScrubCalOffset = ::panGridTo,
                onReleaseCalScrub = ::releaseGridPan,
                onBeginListScrub = {
                    val start = listOffset
                    listNavigation?.cancel()
                    listAlignment = null
                    listScrubbing = true
                    listScrubOffset = start
                    start
                },
                onScrubListOffset = { next ->
                    listScrubOffset = next
                    val (page, fraction) = ItemsCalendarMath.listPagePosition(next, dateWindow.totalDays)
                    pagerState.requestScrollToPage(page, fraction)
                },
                onReleaseListScrub = { velPages, startIdx ->
                    val offset = listScrubOffset
                    if (offset != null && !isGrid && currentScope == "schedule") {
                        val (page, fraction) = ItemsCalendarMath.listPagePosition(offset, dateWindow.totalDays)
                        val target = ItemsCalendarMath.snapListTarget(offset, velPages, startIdx, dateWindow.totalDays)
                        listNavigation = scope.launch {
                            pagerState.scrollToPage(page, fraction)
                            listScrubOffset = null
                            if (reduced) pagerState.scrollToPage(target)
                            else pagerState.animateScrollToPage(target, animationSpec = spring(0.88f, 340f))
                            focusedDate = dateWindow.dateAt(target)
                            listScrubbing = false
                        }
                    } else {
                        listScrubOffset = null
                        listScrubbing = false
                    }
                },
            )

            // 4) Floating Drag/Pinch Pill
            AnimatedContent(
                targetState = livePillText,
                contentKey = { it != null },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 6.dp),
                transitionSpec = {
                    if (reduced) EnterTransition.None togetherWith ExitTransition.None
                    else fadeIn(tween(120)) togetherWith fadeOut(tween(150))
                },
                label = "items-live-time",
            ) { timeText ->
                timeText?.let { text ->
                    Box(
                        Modifier
                            .shadow(10.dp, Capsule())
                            .clip(Capsule())
                            .background(c.ink)
                            .padding(horizontal = 13.dp, vertical = 5.dp),
                    ) {
                        BasicText(text, style = nativeTextStyle(c.bg, 12f, 15f, FontWeight.Bold, tabularNums = true))
                    }
                }
            }

            // 5) Floating Scale Menu Popover (`1日 / 3日 / 5日 / 周`)
            ItemsScalePopover(
                open = scaleMenuOpen && isGrid && currentScope == "schedule",
                backdrop = itemsChromeBackdrop,
                selectedCols = calCols,
                onSelectCols = { cols ->
                    scaleMenuOpen = false
                    val idx = dateWindow.indexOf(focusedDate).coerceAtLeast(0)
                    animateCalViewTo(cols, ItemsCalendarMath.offsetForDayIndex(idx, cols, dateWindow.totalDays))
                },
                onDismiss = { scaleMenuOpen = false },
                topPaddingDp = statusTopDp + 162.dp,
            )

            // 6) Floating `+` FAB (tucks away during drag or sheet open)
            ItemsFloatingAddButton(
                visible = !isGridDragging && editingItem == null && contextModal == null && !quickAddOpen && !acadOpen,
                hasDraft = hasQuickDraft,
                backdrop = itemsChromeBackdrop,
                onClick = {
                    if (onAdd != null) onAdd()
                    else {
                        prepareQuickDraft()
                        quickAddOpen = true
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 86.dp)
                    .navigationBarsPadding(),
            )

            // 7) Floating 5-second circular countdown Undo Toast
            ItemsUndoToast(
                toast = activeToast,
                backdrop = itemsChromeBackdrop,
                onExpired = { expiredId ->
                    if (activeToast?.id == expiredId) activeToast = null
                },
                onUndoClicked = { data ->
                    activeToast = null
                    data.onUndo?.invoke()
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 16.dp, end = 82.dp, bottom = 89.dp)
                    .navigationBarsPadding(),
            )

            // 8) Content-First Edit Sheet + Real Android IME Choreography
            ItemsEventEditSheet(
                editing = editingItem,
                backdrop = itemsChromeBackdrop,
                isGridMode = isGrid && currentScope == "schedule",
                onSheetHeightChanged = { editSheetHeightPx = it },
                dateWindow = dateWindow,
                doc = doc,
                exams = exams,
                allItems = models,
                onToggleDone = ::commitToggleRecord,
                onUpdateTitle = { item, newTitle ->
                    val nextDraft = ItemsDrafts.setTitle(item.rawDraft, newTitle)
                    commitDraftEdit(item, nextDraft)
                },
                onUpdateNote = { item, newNote ->
                    val nextDraft = ItemsDrafts.setNote(item.rawDraft, newNote)
                    commitDraftEdit(item, nextDraft)
                },
                onCyclePriority = { item ->
                    val nextDraft = ItemsDrafts.setPriority(item.rawDraft, item.priority.next())
                    commitDraftEdit(item, nextDraft)
                },
                onMoveToDateTime = { item, date, startMin, endMin, allDay ->
                    val nextDraft = if (allDay || startMin == null) {
                        ItemsDrafts.setAllDay(item.rawDraft, date)
                    } else {
                        ItemsDrafts.setTimed(item.rawDraft, date, startMin, endMin ?: (startMin + 60))
                    }
                    commitDraftEdit(item, nextDraft)
                    if (currentScope == "schedule") jumpToDate(date)
                },
                onMoveToInbox = ::commitMoveToInbox,
                onDelete = ::commitDeleteItem,
                onClose = { editingId = null },
            )

            // 9) Contextual Sheets via native `NSheet` (`Course / Homework / All-Day`)
            ItemsContextualSheetHost(
                modal = contextModal,
                dateWindow = dateWindow,
                doc = doc,
                tasks = tasks,
                doneTaskIds = s.done,
                items = models,
                now = now,
                onDismiss = { contextModal = null },
                onToggleCourseTask = { tid ->
                    tasks.firstOrNull { it.id == tid }?.let(::commitToggleHomework) ?: onSetCourseDone(tid, tid !in latestDoneIds)
                },
                onToggleItemDone = ::commitToggleRecord,
                onOpenHomework = { t -> contextModal = ItemsContextModal.Homework(t) },
                onOpenItemEdit = { item ->
                    contextModal = null
                    editingId = item.id
                },
                onTriageHomework = { t ->
                    contextModal = null
                    triageHomeworkTask(t)
                },
                onJumpToScheduledHomework = { linked ->
                    contextModal = null
                    jumpToDate(linked.date!!)
                    editingId = linked.id
                },
                onQuickAddForCourse = { block, cd ->
                    contextModal = null
                    val pref = (block.endMin + 15).coerceAtLeast(ITEMS_GRID_START_MIN)
                    val busy = ItemsSmartScheduler.busyIntervalsOn(cd, doc, exams, models)
                    val slot = ItemsSmartScheduler.findFreeSlot(60, pref, busy, dayStartMin = pref)
                    openQuickAddForSlot(cd, slot?.first, slot?.second, slot == null, presetTitle = "${itemsShortTitle(block.course.title, 4)} ")
                },
                onQuickAddAllDay = { d ->
                    contextModal = null
                    openQuickAddForSlot(d, null, null, true)
                },
            )

            // 10) Local NLP Quick-Add Sheet
            ItemsQuickAddSheet(
                visible = quickAddOpen,
                backdrop = itemsChromeBackdrop,
                dateWindow = dateWindow,
                focusDate = focusedDate,
                defaultDate = quickDefaultDate,
                draftTitle = quickTitle,
                draftNote = quickNote,
                draftPriority = ItemsPriority.fromCode(quickPriorityCode),
                manualDate = quickManualDate,
                manualStartMin = quickManualStart,
                manualEndMin = quickManualEnd,
                manualAllDay = quickManualAllDay,
                tokenOff = quickTokenOff,
                canUseOnlineAi = !s.preview && s.token != null && onOpenAiComposer != null,
                doc = doc,
                exams = exams,
                allItems = models,
                onDraftTitleChange = { quickTitle = it },
                onDraftNoteChange = { quickNote = it },
                onCyclePriority = { quickPriorityCode = ItemsPriority.fromCode(quickPriorityCode).next().code },
                onSetManual = { md, ms, me, ma, to ->
                    quickManualDate = md
                    quickManualStart = ms
                    quickManualEnd = me
                    quickManualAllDay = ma
                    quickTokenOff = to
                },
                onOpenAiComposer = { onOpenAiComposer?.invoke() },
                onSubmit = { title, note, prio, date, startMin, endMin, allDay ->
                    val rec = ItemsDrafts.newRecord(
                        title = title,
                        note = note,
                        priority = prio,
                        date = date,
                        startMin = startMin,
                        endMin = endMin,
                        isAllDay = allDay,
                    )
                    workingRecords = workingRecords + rec
                    onUpsertRecord(rec, null)
                    quickSubmitted = true
                    quickAddOpen = false
                    if (date != null) {
                        jumpToDate(date)
                        showToast("已保存到 ${AcademicClock.monthDay(date)}${startMin?.let { " ${AcademicClock.clock(it)}" } ?: " 全天"}")
                    } else {
                        currentScope = "inbox"
                        showToast("已存入待安排")
                    }
                },
                onDismiss = {
                    quickAddOpen = false
                    if (hasQuickDraft) showToast("草稿已保留，点 + 继续")
                },
            )
        }

        // SECONDARY PUSH LAYER (`学业`: Exams + Grades + Interactive Left-Edge Swipe-Back)
        if (acadOpen || academicProgress > 0.005f) {
            ItemsAcademicPushOverlay(
                progress = academicProgress,
                screenWidthPx = screenWidthPx,
                backdrop = itemsChromeBackdrop,
                exams = exams,
                grades = grades,
                now = now,
                onClose = {
                    acadDragProgress = null
                    acadOpen = false
                },
                onDragFraction = { acadDragProgress = it },
                onReleaseSwipe = { shouldClose ->
                    scope.launch {
                        acadDragProgress?.let { acadProgress.snapTo(it) }
                        acadDragProgress = null
                        if (shouldClose) acadOpen = false
                        else if (reduced) acadProgress.snapTo(1f)
                        else acadProgress.animateTo(1f, spring(dampingRatio = 0.86f, stiffness = 460f))
                    }
                },
                onJumpToExamDate = { date ->
                    acadOpen = false
                    jumpToDate(date)
                },
                onScheduleExamReview = ::scheduleExamReview,
            )
        }
    }
}

private sealed class ItemsDayTimelineRow(val key: String, val startMin: Int, val endMin: Int) {
    class CourseRow(val block: AcademicBlock) : ItemsDayTimelineRow("course-${block.key}", block.startMin, block.endMin)
    class ExamRow(val exam: AcademicExam, s: Int, e: Int) : ItemsDayTimelineRow("exam-${exam.id}", s, e)
    class ItemRow(val item: ItemsRecordModel) : ItemsDayTimelineRow("record-${item.id}", item.startMin!!, item.endMin!!)
}

private class ItemsPreviewLine(val lead: String, val leadColor: Color, val dot: Color?, val title: String, val tail: String)

private fun itemsTaskKey(task: AcademicTask): String =
    if (task.id.isNotBlank()) "${task.source}:${task.id}" else "${task.source}:${task.course}:${task.title}:${task.dueRaw}"

private val ItemsRowShape = RoundedRectangle(18.dp)

private fun itemsPaneTransition(from: Int, to: Int, reduced: Boolean, pxPerDp: Float): ContentTransform {
    if (reduced) return EnterTransition.None togetherWith ExitTransition.None
    fun ord(p: Int) = if (p == 3) 0 else p
    val dir = ord(to).compareTo(ord(from))
    val exitEase = CubicBezierEasing(0.32f, 0f, 0.67f, 1f)
    return if (dir == 0) {
        (fadeIn(tween(200)) + scaleIn(spring(0.86f, 460f), initialScale = 1.02f)) togetherWith
            (fadeOut(tween(120, easing = exitEase)) + scaleOut(tween(120, easing = exitEase), targetScale = 0.975f))
    } else {
        (fadeIn(tween(180)) + slideInHorizontally(spring(0.86f, 460f)) { (dir * 22f * pxPerDp).roundToInt() }) togetherWith
            (fadeOut(tween(120, easing = exitEase)) + slideOutHorizontally(tween(120, easing = exitEase)) { (-dir * 16f * pxPerDp).roundToInt() })
    }
}

/** The single chip geometry of the Items lists: room, priority and inline actions share it. */
@Composable
private fun ItemsPill(
    text: String,
    tone: Color,
    modifier: Modifier = Modifier,
    icon: String? = null,
    ink: Color = tone,
    fill: Color? = null,
    onClick: (() -> Unit)? = null,
) {
    val dark = LocalLiquidGlassDarkTheme.current == true
    val shape = remember { RoundedRectangle(8.dp) }
    Row(
        modifier
            .height(24.dp)
            .widthIn(min = 60.dp)
            .clip(shape)
            .background(fill ?: tone.copy(alpha = if (dark) 0.20f else 0.12f))
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) NativeIcon(icon, ink, size = 12.dp)
        BasicText(
            text,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            style = nativeTextStyle(ink, 12f, 15f, FontWeight.SemiBold),
        )
    }
}

/** Fixed 42dp lead column: `start / end`, or a two-line label such as `18:00 / 截止`. */
@Composable
private fun ItemsLead(top: String, bottom: String? = null, topColor: Color? = null) {
    val c = LocalNativeColors.current
    Column {
        BasicText(top, maxLines = 1, softWrap = false, style = nativeTextStyle(topColor ?: c.ink, 13f, 17f, FontWeight.SemiBold, tabularNums = true))
        if (bottom != null) BasicText(bottom, maxLines = 1, softWrap = false, style = nativeTextStyle(c.muted, 12f, 15f, tabularNums = true))
    }
}

@Composable
private fun itemsLeadWidth() = (42f * LocalDensity.current.fontScale.coerceIn(1f, 1.8f)).dp

/** A 22dp ring inside the 24dp node column; the 48dp touch target overflows the column without widening the row. */
@Composable
private fun ItemsRowCheck(done: Boolean, accent: Color, title: String, enabled: Boolean = true, onToggle: () -> Unit) {
    Box(Modifier.requiredSize(48.dp)) {
        ItemsCheckbox(done = done, accent = accent, title = title, onToggle = onToggle, enabled = enabled)
    }
}

@Composable
private fun ItemsRowDot(color: Color) {
    Box(Modifier.size(9.dp).clip(CircleShape).background(color))
}

/**
 * The one row of every Items list: `lead 42 | node 24 | title + sub | pill`, on the app's glass card.
 * Hue only lives in the node and the pill, so courses, exams, homework and personal items read as one family.
 */
@Composable
private fun ItemsRowCard(
    title: String,
    onClick: () -> Unit,
    node: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    lead: (@Composable () -> Unit)? = null,
    sub: String = "",
    subIcon: String? = null,
    subAccent: String = "",
    subAccentColor: Color? = null,
    done: Boolean = false,
    highlighted: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = LocalNativeColors.current
    val reduced = LocalLiquidGlassReducedMotion.current
    val titleColor by animateColorAsState(if (done) c.muted else c.ink, if (reduced) snap() else tween(200), label = "row-title")
    val ring by animateFloatAsState(if (highlighted) 1f else 0f, if (reduced) snap() else tween(180), label = "row-ring")
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .nativeCardAction(onClick, shape = ItemsRowShape)
            .nativeGlassCard(18)
            .border(1.25.dp, c.brand.copy(alpha = 0.72f * ring), ItemsRowShape)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (lead != null) Box(Modifier.width(itemsLeadWidth())) { lead() }
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { node() }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            BasicText(
                title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = nativeTextStyle(
                    color = titleColor,
                    sizeSp = 15f,
                    lineHeightSp = 20f,
                    weight = FontWeight.SemiBold,
                    decoration = if (done) TextDecoration.LineThrough else TextDecoration.None,
                ),
            )
            if (sub.isNotBlank() || subAccent.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (subIcon != null) NativeIcon(subIcon, c.muted, size = 11.dp)
                    if (sub.isNotBlank()) {
                        BasicText(
                            sub,
                            modifier = Modifier.weight(1f, fill = false),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = nativeTextStyle(c.muted, 12.5f, 16f),
                        )
                    }
                    if (subAccent.isNotBlank()) {
                        BasicText(
                            if (sub.isNotBlank()) "· $subAccent" else subAccent,
                            maxLines = 1,
                            softWrap = false,
                            style = nativeTextStyle(subAccentColor ?: c.muted, 12.5f, 16f),
                        )
                    }
                }
            }
        }
        trailing?.invoke()
    }
}

@Composable
private fun ItemsTaskRow(
    item: ItemsRecordModel,
    isEditing: Boolean,
    onToggleDone: () -> Unit,
    onTap: () -> Unit,
    lead: (@Composable () -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
) {
    val c = LocalNativeColors.current
    val dark = LocalLiquidGlassDarkTheme.current == true
    val palette = LocalNativePalette.current
    val reduced = LocalLiquidGlassReducedMotion.current
    val tones = remember(dark, palette, item.priority, item.done, c) {
        itemsEventTones(dark, palette, item.priority, item.done, c)
    }
    ItemsRowCard(
        title = item.title,
        onClick = onTap,
        modifier = Modifier.testTag("items-card-${item.id}"),
        done = item.done,
        highlighted = isEditing,
        sub = item.note,
        subIcon = if (item.note.isNotBlank()) "note" else null,
        lead = lead,
        node = { ItemsRowCheck(item.done, tones.accent, item.title, onToggle = onToggleDone) },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AnimatedVisibility(
                    visible = item.priority != ItemsPriority.NORMAL && !item.done,
                    enter = if (reduced) EnterTransition.None else expandHorizontally(tween(160)) + fadeIn(tween(160)),
                    exit = if (reduced) ExitTransition.None else shrinkHorizontally(tween(150)) + fadeOut(tween(120)),
                ) {
                    ItemsPill(
                        item.priority.label,
                        if (item.priority == ItemsPriority.URGENT) c.danger else nativeSemanticTones(dark, palette).amber,
                    )
                }
                action?.invoke()
            }
        },
    )
}

@Composable
private fun ItemsListScroller(
    topPaddingDp: Dp,
    bottomNavInsetDp: Dp,
    scrollState: LazyListState,
    content: LazyListScope.() -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = topPaddingDp)
            .nativeTopSoftEdge(scrollState, depth = 26.dp),
        state = scrollState,
        contentPadding = PaddingValues(top = 12.dp, start = 16.dp, end = 16.dp, bottom = bottomNavInsetDp + 128.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

private fun LazyListScope.animatedItem(key: String, reduced: Boolean, content: @Composable () -> Unit) {
    item(key) {
        val motion = if (reduced) Modifier else Modifier.animateItem(
            fadeInSpec = tween(160),
            placementSpec = spring(dampingRatio = 0.88f, stiffness = 420f),
            fadeOutSpec = tween(140),
        )
        Box(motion) { content() }
    }
}

@Composable
private fun ItemsEmptyLine(text: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
        NText(text, 13, muted = true)
    }
}

@Composable
private fun ItemsDayListPane(
    date: String,
    todayDate: String,
    dateWindow: ItemsDateWindow,
    topPaddingDp: Dp,
    bottomNavInsetDp: Dp,
    scrollState: LazyListState,
    doc: AcademicTimetableDoc?,
    tasks: List<AcademicTask>,
    doneIds: Set<String>,
    exams: List<AcademicExam>,
    items: List<ItemsRecordModel>,
    plans: List<JSONObject>,
    busyPlanIds: Set<String>,
    editingId: String?,
    onToggleTask: (AcademicTask) -> Unit,
    onToggleItem: (ItemsRecordModel) -> Unit,
    onTogglePlan: (JSONObject) -> Unit,
    onDeletePlan: (JSONObject) -> Unit,
    onTapTask: (AcademicTask) -> Unit,
    onTapCourse: (AcademicBlock, String) -> Unit,
    onTapItem: (ItemsRecordModel) -> Unit,
    onTapExam: (AcademicExam) -> Unit,
    onTapGapAdd: (Int, Int) -> Unit,
    onJumpDay: (String) -> Unit,
    nowMs: Long,
) {
    val c = LocalNativeColors.current
    val dark = LocalLiquidGlassDarkTheme.current == true
    val palette = LocalNativePalette.current
    val amber = nativeSemanticTones(dark, palette).amber
    val reduced = LocalLiquidGlassReducedMotion.current

    val dueTasks = remember(date, todayDate, tasks, doneIds) {
        tasks.filter { t ->
            val dueMs = t.dueMs ?: return@filter false
            val dueDate = AcademicClock.moment(dueMs).date
            when {
                dueDate == date -> true
                date == todayDate && dueDate < todayDate -> t.id !in doneIds
                else -> false
            }
        }.sortedWith(compareBy<AcademicTask> { it.id in doneIds }.thenBy { it.dueMs ?: Long.MAX_VALUE })
    }
    val allDayItems = remember(date, items) { items.filter { it.date == date && it.isAllDay } }
    val dayCourses = remember(date, doc) { AcademicTimetable.planFor(doc, date).blocks }
    val dayExams = remember(date, exams) { exams.filter { it.date == date && it.startMs != null } }
    val timedItems = remember(date, items) { items.filter { it.date == date && it.isTimed } }
    val legacyPlans = remember(date, plans) { plans.filter { it.optString("date") == date } }

    val timeline = remember(dayCourses, dayExams, timedItems) {
        buildList {
            dayExams.forEach { ex ->
                val sm = AcademicClock.moment(ex.startMs!!).minutes
                add(ItemsDayTimelineRow.ExamRow(ex, sm, sm + 120))
            }
            dayCourses.forEach { add(ItemsDayTimelineRow.CourseRow(it)) }
            timedItems.forEach { add(ItemsDayTimelineRow.ItemRow(it)) }
        }.sortedBy { it.startMin }
    }

    val tomorrowDate = AcademicClock.addDays(date, 1)
    val preview = remember(tomorrowDate, dateWindow, exams, tasks, doneIds, doc, items, dark, palette, c) {
        if (dateWindow.indexOf(tomorrowDate) < 0) emptyList() else buildList {
            exams.filter { it.date == tomorrowDate }.forEach { ex ->
                add(ItemsPreviewLine(ex.startMs?.let(AcademicClock::clockAt) ?: "全天", c.danger, c.danger, ex.title, "考试"))
            }
            tasks.filter { it.id !in doneIds && it.dueMs != null && AcademicClock.moment(it.dueMs).date == tomorrowDate }.take(2).forEach { t ->
                add(ItemsPreviewLine(AcademicClock.clockAt(t.dueMs!!), c.muted, null, t.title, "截止"))
            }
            AcademicTimetable.planFor(doc, tomorrowDate).blocks.take(3).forEach { b ->
                add(ItemsPreviewLine(b.start, c.muted, nativeCourseSwatch(b.course.hue, dark, palette).mark, b.course.title, itemsRoomLabel(b.location)))
            }
            items.filter { it.date == tomorrowDate && !it.done }.take(2).forEach { p ->
                add(ItemsPreviewLine(p.startClock ?: p.untimedLabel, c.muted, null, p.title, if (p.isDeadlinePoint) "截止" else ""))
            }
        }.sortedWith(compareBy<ItemsPreviewLine> { it.lead.firstOrNull()?.isDigit() == true }.thenBy { it.lead })
    }

    ItemsListScroller(topPaddingDp, bottomNavInsetDp, scrollState) {
        // Deadlines: the lead column carries the due time, so no extra tag is needed.
        dueTasks.forEach { task ->
            val dueMs = task.dueMs ?: return@forEach
            animatedItem("deadline-${itemsTaskKey(task)}", reduced) {
                val isDone = task.id in doneIds
                val dueDate = AcademicClock.moment(dueMs).date
                val overdue = !isDone && dueDate < todayDate
                ItemsRowCard(
                    title = task.title,
                    onClick = { onTapTask(task) },
                    done = isDone,
                    sub = task.course,
                    lead = {
                        if (overdue) ItemsLead("逾期", "${-AcademicClock.daysUntil(dueDate, nowMs)} 天", c.danger)
                        else ItemsLead(AcademicClock.clockAt(dueMs), "截止", if (isDone) c.muted else if (date == todayDate) amber else null)
                    },
                    node = { ItemsRowCheck(isDone, if (overdue) c.danger else c.brand, task.title) { onToggleTask(task) } },
                )
            }
        }

        allDayItems.forEach { item ->
            animatedItem("record-${item.id}", reduced) {
                ItemsTaskRow(
                    item = item,
                    isEditing = item.id == editingId,
                    onToggleDone = { onToggleItem(item) },
                    onTap = { onTapItem(item) },
                    lead = { ItemsLead(item.untimedLabel, topColor = if (item.isDeadlineOnly) amber else c.muted) },
                )
            }
        }

        var lastEndMin: Int? = null
        timeline.forEach { row ->
            val prevEnd = lastEndMin
            val blocksTime = row !is ItemsDayTimelineRow.ItemRow || !row.item.isDeadlinePoint
            val gapStart = prevEnd?.coerceAtLeast(ITEMS_GRID_START_MIN)
            val gapEnd = row.startMin.coerceAtMost(ITEMS_GRID_END_MIN)
            if (blocksTime && gapStart != null && gapEnd - gapStart >= 75) {
                animatedItem("gap-${row.key}", reduced) {
                    Row(
                        Modifier.fillMaxWidth().height(24.dp).padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        BasicText("空闲 ${AcademicClock.span(gapEnd - gapStart)}", style = nativeTextStyle(c.muted, 12.5f, 16f))
                        ItemsPill("+ 安排", c.brand) { onTapGapAdd(gapStart + 15, gapStart + 75) }
                    }
                }
            }

            animatedItem(row.key, reduced) {
                when (row) {
                    is ItemsDayTimelineRow.ExamRow -> ItemsRowCard(
                        title = row.exam.title,
                        onClick = { onTapExam(row.exam) },
                        sub = listOf(row.exam.location, row.exam.seat).filter { it.isNotBlank() }.joinToString(" · "),
                        lead = { ItemsLead(AcademicClock.clock(row.startMin), AcademicClock.clock(row.endMin), c.danger) },
                        node = { ItemsRowDot(c.danger) },
                        trailing = { ItemsPill("考试", c.danger) },
                    )
                    is ItemsDayTimelineRow.CourseRow -> {
                        val block = row.block
                        val swatch = nativeCourseSwatch(block.course.hue, dark, palette)
                        ItemsRowCard(
                            title = block.course.title,
                            onClick = { onTapCourse(block, date) },
                            sub = block.teacher,
                            lead = { ItemsLead(block.start, block.end) },
                            node = { ItemsRowDot(swatch.mark) },
                            trailing = {
                                if (block.location.isNotBlank()) {
                                    ItemsPill(
                                        itemsRoomLabel(block.location),
                                        swatch.mark,
                                        modifier = Modifier.widthIn(max = 124.dp),
                                        ink = swatch.ink,
                                        fill = swatch.mid.copy(alpha = if (dark) 0.55f else 0.62f),
                                    )
                                }
                            },
                        )
                    }
                    is ItemsDayTimelineRow.ItemRow -> ItemsTaskRow(
                        item = row.item,
                        isEditing = row.item.id == editingId,
                        onToggleDone = { onToggleItem(row.item) },
                        onTap = { onTapItem(row.item) },
                        lead = {
                            ItemsLead(
                                row.item.startClock.orEmpty(),
                                if (row.item.isDeadlinePoint) "截止" else row.item.endClock,
                                if (row.item.done) c.muted else null,
                            )
                        },
                    )
                }
            }
            if (blocksTime) lastEndMin = maxOf(lastEndMin ?: 0, row.endMin)
        }

        // Legacy server /v1/plan records if any
        legacyPlans.forEach { plan ->
            val pid = plan.optString("id")
            animatedItem("plan-$pid", reduced) {
                ItemsDisclosureRow(
                    id = "plan-$pid",
                    title = plan.optString("title"),
                    subtitle = listOf(plan.optString("start"), plan.optString("end")).filter { it.isNotBlank() }.joinToString(" – "),
                    done = plan.optBoolean("done"),
                    onToggle = { onTogglePlan(plan) },
                    busy = pid in busyPlanIds,
                    onDelete = { onDeletePlan(plan) },
                ) {
                    if (plan.optString("note").isNotBlank()) NText(plan.optString("note"), 14)
                }
            }
        }

        if (dueTasks.isEmpty() && allDayItems.isEmpty() && timeline.isEmpty() && legacyPlans.isEmpty()) {
            animatedItem("empty", reduced) { ItemsEmptyLine(if (date == todayDate) "今天无安排" else "这天无安排") }
        }

        if (preview.isNotEmpty()) {
            animatedItem("preview-$tomorrowDate", reduced) {
                ItemsNextDayPreview(tomorrowDate, preview) { onJumpDay(tomorrowDate) }
            }
        }
    }
}

@Composable
private fun ItemsNextDayPreview(date: String, preview: List<ItemsPreviewLine>, onClick: () -> Unit) {
    val c = LocalNativeColors.current
    val leadWidth = itemsLeadWidth()
    Column(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.padding(top = 8.dp, start = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            BasicText(
                "${AcademicClock.monthDay(date)} ${AcademicClock.weekdayText(AcademicClock.weekday(date))}预告",
                style = nativeTextStyle(c.muted, 12f, 16f, FontWeight.SemiBold),
            )
            NativeIcon("chevron-right", c.muted, size = 12.dp, strokeWidth = 2.2f)
        }
        Column(
            Modifier.fillMaxWidth().nativeGlassCard(18, elevated = false).padding(horizontal = 14.dp, vertical = 4.dp),
        ) {
            preview.forEachIndexed { index, line ->
                if (index > 0) {
                    Box(Modifier.fillMaxWidth().padding(start = leadWidth + 48.dp).height(0.75.dp).background(c.border.copy(alpha = 0.55f)))
                }
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 38.dp).padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    BasicText(
                        line.lead,
                        modifier = Modifier.width(leadWidth),
                        maxLines = 1,
                        softWrap = false,
                        style = nativeTextStyle(line.leadColor, 12.5f, 16f, FontWeight.SemiBold, tabularNums = true),
                    )
                    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                        if (line.dot != null) Box(Modifier.size(7.dp).clip(CircleShape).background(line.dot))
                        else Box(Modifier.size(13.dp).border(1.5.dp, c.muted.copy(alpha = 0.7f), CircleShape))
                    }
                    BasicText(
                        line.title,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = nativeTextStyle(c.ink.copy(alpha = 0.86f), 13.5f, 18f, FontWeight.Medium),
                    )
                    if (line.tail.isNotBlank()) {
                        BasicText(line.tail, maxLines = 1, softWrap = false, style = nativeTextStyle(c.muted, 12f, 15f))
                    }
                }
            }
        }
    }
}

@Composable
private fun ItemsInboxListView(
    items: List<ItemsRecordModel>,
    topPaddingDp: Dp,
    bottomNavInsetDp: Dp,
    scrollState: LazyListState,
    editingId: String?,
    onToggleDone: (ItemsRecordModel) -> Unit,
    onTapItem: (ItemsRecordModel) -> Unit,
    onQuickSchedule: (ItemsRecordModel) -> Unit,
) {
    val c = LocalNativeColors.current
    val reduced = LocalLiquidGlassReducedMotion.current
    ItemsListScroller(topPaddingDp, bottomNavInsetDp, scrollState) {
        if (items.isEmpty()) animatedItem("empty", reduced) { ItemsEmptyLine("待安排已清空") }
        items.forEach { item ->
            animatedItem("record-${item.id}", reduced) {
                ItemsTaskRow(
                    item = item,
                    isEditing = item.id == editingId,
                    onToggleDone = { onToggleDone(item) },
                    onTap = { onTapItem(item) },
                    action = if (item.done) null else { { ItemsPill("排期", c.brand) { onQuickSchedule(item) } } },
                )
            }
        }
    }
}

@Composable
private fun ItemsHomeworkListView(
    tasks: List<AcademicTask>,
    doneIds: Set<String>,
    linkedItems: List<ItemsRecordModel>,
    now: AcademicMoment,
    topPaddingDp: Dp,
    bottomNavInsetDp: Dp,
    scrollState: LazyListState,
    onToggleTask: (AcademicTask) -> Unit,
    onTapTask: (AcademicTask) -> Unit,
    onTriage: (AcademicTask) -> Unit,
    onJumpToScheduled: (ItemsRecordModel) -> Unit,
) {
    val c = LocalNativeColors.current
    val dark = LocalLiquidGlassDarkTheme.current == true
    val palette = LocalNativePalette.current
    val okColor = nativeSemanticTones(dark, palette).leaf
    val warnColor = nativeSemanticTones(dark, palette).amber
    val reduced = LocalLiquidGlassReducedMotion.current

    val overdue = remember(tasks, doneIds, now.ms) {
        tasks.filter { it.id !in doneIds && AcademicRecords.bucket(it, now.ms) in listOf("overdue", "expired") }
    }
    val upcoming = remember(tasks, doneIds, now.ms) {
        tasks.filter { it.id !in doneIds && AcademicRecords.bucket(it, now.ms) !in listOf("overdue", "expired") }
    }
    val done = remember(tasks, doneIds) { tasks.filter { it.id in doneIds } }

    ItemsListScroller(topPaddingDp, bottomNavInsetDp, scrollState) {
        if (tasks.isEmpty()) animatedItem("empty", reduced) { ItemsEmptyLine("暂无作业") }

        fun HomeworkSection(title: String, titleColor: Color, list: List<AcademicTask>) {
            if (list.isEmpty()) return
            animatedItem("section-$title", reduced) {
                BasicText(
                    title,
                    modifier = Modifier.padding(top = 8.dp, start = 14.dp),
                    style = nativeTextStyle(titleColor, 12f, 16f, FontWeight.SemiBold),
                )
            }
            list.forEach { t ->
                animatedItem("homework-${itemsTaskKey(t)}", reduced) {
                    val isDone = t.id in doneIds
                    val linked = linkedItems.firstOrNull { it.schoolTaskId == t.id }
                    val bucket = AcademicRecords.bucket(t, now.ms)
                    val late = bucket in listOf("overdue", "expired")
                    ItemsRowCard(
                        title = t.title,
                        onClick = { onTapTask(t) },
                        done = isDone,
                        sub = t.course,
                        subAccent = if (isDone) "已完成" else AcademicRecords.todayDue(t, now.ms),
                        subAccentColor = when {
                            isDone -> null
                            late -> c.danger
                            bucket == "today" -> warnColor
                            else -> null
                        },
                        node = { ItemsRowCheck(isDone, if (late) c.danger else c.brand, t.title) { onToggleTask(t) } },
                        trailing = {
                            if (linked != null && linked.isScheduled) {
                                ItemsPill(
                                    "${AcademicClock.weekdayText(AcademicClock.weekday(linked.date!!))}${linked.startClock?.let { " $it" }.orEmpty()} ›",
                                    okColor,
                                ) { onJumpToScheduled(linked) }
                            } else if (!isDone) {
                                ItemsPill(if (linked != null) "重排" else "排期", c.brand) { onTriage(t) }
                            }
                        },
                    )
                }
            }
        }

        HomeworkSection("已逾期", c.danger, overdue)
        HomeworkSection("待完成", c.muted, upcoming)
        HomeworkSection("已完成", c.muted, done)
    }
}

@Composable
private fun ItemsAcademicPushOverlay(
    progress: Float,
    screenWidthPx: Float,
    backdrop: Backdrop,
    exams: List<AcademicExam>,
    grades: List<AcademicGrade>,
    now: AcademicMoment,
    onClose: () -> Unit,
    onDragFraction: (Float) -> Unit,
    onReleaseSwipe: (shouldClose: Boolean) -> Unit,
    onJumpToExamDate: (String) -> Unit,
    onScheduleExamReview: (AcademicExam) -> Unit,
) {
    val c = LocalNativeColors.current
    val dark = LocalLiquidGlassDarkTheme.current == true
    val palette = LocalNativePalette.current
    val density = LocalDensity.current
    val tick = nSelectionFeedback()
    val scroll = rememberScrollState()
    var dedupRetake by rememberSaveable { mutableStateOf(true) }
    var semester by rememberSaveable { mutableStateOf("all") }

    BackHandler(enabled = progress > 0.3f) { onClose() }

    val edgePx = with(density) { 44.dp.toPx() }
    val orbPrimary = c.brand.copy(alpha = if (dark) 0.22f else 0.15f)
    val orbWarm = lerp(c.brand, c.danger, 0.42f).copy(alpha = if (dark) 0.14f else 0.10f)

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { translationX = (1f - progress) * screenWidthPx }
            .shadow(16.dp, ambientColor = Color.Black.copy(0.24f))
            .background(c.bg)
            .drawWithCache {
                val w = size.width
                val h = size.height
                val b1 = Brush.radialGradient(listOf(orbPrimary, Color.Transparent), Offset(w * 0.85f, h * 0.1f), (w * 0.8f).coerceAtLeast(1f))
                val b2 = Brush.radialGradient(listOf(orbWarm, Color.Transparent), Offset(w * 0.12f, h * 0.35f), (w * 0.7f).coerceAtLeast(1f))
                onDrawBehind {
                    drawCircle(b1, w * 0.8f, Offset(w * 0.85f, h * 0.1f))
                    drawCircle(b2, w * 0.7f, Offset(w * 0.12f, h * 0.35f))
                }
            }
            .pointerInput(screenWidthPx) {
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (down.position.x > edgePx) return@awaitEachGesture
                    val pid = down.id
                    var dragging = false
                    var totalDx = 0f
                    var totalDy = 0f
                    val tracker = VelocityTracker().apply { addPosition(down.uptimeMillis, down.position) }
                    while (true) {
                        val ev = awaitPointerEvent(PointerEventPass.Main)
                        val ch = ev.changes.firstOrNull { it.id == pid } ?: break
                        if (!ch.pressed) {
                            tracker.addPosition(ch.uptimeMillis, ch.position)
                            break
                        }
                        val d = ch.positionChange()
                        totalDx += d.x
                        totalDy += d.y
                        tracker.addPosition(ch.uptimeMillis, ch.position)
                        if (!dragging) {
                            if (abs(totalDy) > slop && abs(totalDy) >= totalDx) break
                            if (totalDx > slop && totalDx > abs(totalDy) * 1.2f) {
                                dragging = true
                                ch.consume()
                            }
                        } else {
                            ch.consume()
                            val nextP = (1f - (totalDx / screenWidthPx).coerceIn(0f, 1f))
                            onDragFraction(nextP)
                        }
                    }
                    if (dragging) {
                        val vx = tracker.calculateVelocity().x
                        onReleaseSwipe(totalDx > 84.dp.toPx() || vx > 420.dp.toPx())
                    }
                }
            }
            .testTag("items-academic-layer"),
    ) {
        val sortedExams = remember(exams) { exams.sortedBy { it.startMs ?: Long.MAX_VALUE } }
        val filteredGrades = remember(grades, semester, dedupRetake) {
            val semFiltered = if (semester == "all") grades else grades.filter { it.semester == semester }
            if (!dedupRetake) semFiltered
            else semFiltered.groupBy { it.title }.values.map { list ->
                list.maxByOrNull { it.score.toDoubleOrNull() ?: Double.MIN_VALUE } ?: list.first()
            }
        }
        val summary = remember(filteredGrades) { AcademicRecords.summarize(filteredGrades) }
        val semesters = remember(grades) { grades.map { it.semester }.filter { it.isNotEmpty() }.distinct().sortedDescending() }

        Column(
            Modifier
                .fillMaxSize()
                .nativeTopSoftEdge(scroll, depth = 36.dp)
                .verticalScroll(scroll)
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(start = 14.dp, end = 14.dp, top = 58.dp, bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            BasicText("考试与考核", style = nativeTextStyle(c.muted, 12f, 16f, FontWeight.Bold))
            if (sortedExams.isEmpty()) {
                NCard {
                    NText("还没有考试安排", 15, bold = true)
                    NText("教务公布考试时间后，同步一次就会出现在这里。", 13, muted = true)
                }
            } else {
                sortedExams.forEach { ex ->
                    val leftDays = ex.date?.let { AcademicClock.daysUntil(it, now.ms).coerceAtLeast(0) }
                    val warnColor = nativeSemanticTones(dark, palette).amber
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .then(
                                ex.date?.let { d ->
                                    Modifier.nativeCardAction({ onJumpToExamDate(d) }, shape = RoundedRectangle(18.dp))
                                } ?: Modifier,
                            )
                            .nativeGlassCard(18)
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            BasicText(ex.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = nativeTextStyle(c.ink, 14.5f, 20f, FontWeight.Bold))
                            NText(ex.timeRaw.ifBlank { "时间未公布" }, 12, muted = true, tabularNums = true)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                NativeIcon("pin", c.brand, size = 12.dp)
                                BasicText(
                                    (ex.location.ifBlank { "考场未公布" }) + if (ex.seat.isNotBlank()) " · 座位 ${ex.seat}" else "",
                                    style = nativeTextStyle(c.brand, 12f, 16f, FontWeight.Bold),
                                )
                            }
                        }
                        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (leftDays != null) {
                                Box(
                                    Modifier
                                        .clip(RoundedRectangle(11.dp))
                                        .background(warnColor.copy(alpha = 0.14f))
                                        .padding(horizontal = 10.dp, vertical = 5.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    BasicText("$leftDays 天", style = nativeTextStyle(warnColor, 15f, 18f, FontWeight.Bold, tabularNums = true))
                                }
                            }
                            Box(
                                Modifier
                                    .height(28.dp)
                                    .clip(RoundedRectangle(9.dp))
                                    .background(c.brand.copy(alpha = 0.13f))
                                    .clickable(role = Role.Button) { onScheduleExamReview(ex) }
                                    .padding(horizontal = 10.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                BasicText("+ 排复习", style = nativeTextStyle(c.brand, 12f, 15f, FontWeight.Bold))
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(
                    "课程成绩 · GPA ${summary.weightedGpa?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "—"}",
                    style = nativeTextStyle(c.muted, 12f, 16f, FontWeight.Bold, tabularNums = true),
                )
                Box(
                    Modifier
                        .clip(Capsule())
                        .clickable {
                            tick()
                            dedupRetake = !dedupRetake
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    BasicText(
                        if (dedupRetake) "重修已去重" else "含首考",
                        style = nativeTextStyle(c.brand, 12f, 16f, FontWeight.Bold),
                    )
                }
            }

            if (semesters.size > 1) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (listOf("all" to "全部学期") + semesters.map { it to AcademicRecords.semesterLabel(it) }).forEach { (key, label) ->
                        val on = semester == key
                        DrawerMiniButton(label, muted = !on) {
                            tick()
                            semester = key
                        }
                    }
                }
            }

            if (filteredGrades.isEmpty()) {
                NCard {
                    NText("还没有成绩记录", 15, bold = true)
                    NText("同步教务成绩后会在这里统计加权绩点与分数。", 13, muted = true)
                }
            } else {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min)
                        .nativeGlassCard(20),
                ) {
                    listOf(
                        "加权均分" to (summary.weightedScore?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "—"),
                        "加权绩点" to (summary.weightedGpa?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "—"),
                        "总学分" to AcademicRecords.creditText(summary.credits),
                    ).forEachIndexed { idx, (lbl, valStr) ->
                        if (idx > 0) Box(Modifier.width(0.75.dp).fillMaxHeight().padding(vertical = 12.dp).background(c.border))
                        Column(
                            Modifier.weight(1f).padding(vertical = 12.dp, horizontal = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            NText(lbl, 11, muted = true)
                            BasicText(valStr, style = nativeTextStyle(c.ink, 19f, 24f, FontWeight.Bold, tabularNums = true))
                        }
                    }
                }

                Column(
                    Modifier
                        .fillMaxWidth()
                        .nativeGlassCard(18),
                ) {
                    val leafColor = nativeSemanticTones(dark, palette).leaf
                    filteredGrades.forEachIndexed { idx, g ->
                        if (idx > 0) Box(Modifier.fillMaxWidth().height(0.75.dp).background(c.border.copy(alpha = 0.65f)))
                        val numScore = g.score.toDoubleOrNull()
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 54.dp)
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                BasicText(g.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = nativeTextStyle(c.ink, 13.5f, 18f, FontWeight.SemiBold))
                                NText(
                                    AcademicRecords.semesterLabel(g.semester) +
                                        (g.credits?.let { " · ${AcademicRecords.creditText(it)} 学分" } ?: ""),
                                    11,
                                    muted = true,
                                    tabularNums = true,
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                BasicText(
                                    g.score.ifBlank { "—" },
                                    style = nativeTextStyle(
                                        when {
                                            numScore == null -> c.muted
                                            numScore < 60 -> c.danger
                                            else -> leafColor
                                        },
                                        15f,
                                        18f,
                                        FontWeight.Bold,
                                        tabularNums = true,
                                    ),
                                )
                                g.gpa?.let {
                                    BasicText(it.toString(), style = nativeTextStyle(c.muted, 11f, 14f, tabularNums = true))
                                }
                            }
                        }
                    }
                }
            }
        }

        // Top Liquid Glass Academic Navigation Bar
        Row(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(0f to c.bg.copy(0.94f), 0.78f to c.bg.copy(0.80f), 1f to Color.Transparent))
                .statusBarsPadding()
                .padding(horizontal = 14.dp, vertical = 6.dp)
                .height(44.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            ItemsLiquidPillButton(
                backdrop = backdrop,
                contentDescriptionText = "返回事项",
                onClick = onClose,
                horizontalPadding = 11.dp,
                height = 36.dp,
            ) {
                NativeIcon("chevron-left", c.ink, size = 16.dp, strokeWidth = 2.2f)
                BasicText("事项", style = nativeTextStyle(c.ink, 13f, 16f, FontWeight.Bold))
            }
            BasicText("学业", style = nativeTextStyle(c.ink, 15.5f, 20f, FontWeight.Bold))
            Spacer(Modifier.width(66.dp))
        }
    }
}

@Composable
private fun DrawerMiniButton(label: String, muted: Boolean = false, onClick: () -> Unit) {
    val c = LocalNativeColors.current
    Box(
        Modifier
            .height(28.dp)
            .clip(RoundedRectangle(9.dp))
            .background(if (muted) c.ink.copy(alpha = 0.06f) else c.brand.copy(alpha = 0.14f))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            label,
            maxLines = 1,
            softWrap = false,
            style = nativeTextStyle(if (muted) c.muted else c.brand, 12f, 15f, FontWeight.Bold),
        )
    }
}

@Composable
internal fun ItemsDisclosureRow(
    id: String,
    title: String,
    subtitle: String,
    done: Boolean,
    onToggle: () -> Unit,
    busy: Boolean = false,
    onDelete: (() -> Unit)? = null,
    detail: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    var expanded by rememberSaveable(id) { mutableStateOf(false) }
    val c = LocalNativeColors.current
    val tick = nSelectionFeedback()
    Column(
        Modifier
            .fillMaxWidth()
            .nativeGlassCard(18)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ItemsCheckbox(done = done, accent = c.brand, title = title, onToggle = onToggle, enabled = !busy)
            Column(
                Modifier
                    .weight(1f)
                    .clickable(role = Role.Button) { tick(); expanded = !expanded },
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                BasicText(
                    title,
                    style = nativeTextStyle(
                        if (done) c.muted else c.ink,
                        14.5f,
                        20f,
                        FontWeight.Bold,
                        decoration = if (done) TextDecoration.LineThrough else TextDecoration.None,
                    ),
                )
                if (subtitle.isNotBlank()) NText(subtitle, 12, muted = true, tabularNums = true)
            }
            if (onDelete != null) {
                Box(
                    Modifier
                        .size(36.dp)
                        .clickable(enabled = !busy, role = Role.Button, onClick = onDelete),
                    contentAlignment = Alignment.Center,
                ) {
                    NativeIcon("trash", c.muted, size = 16.dp)
                }
            }
        }
        AnimatedVisibility(expanded) {
            Column(Modifier.padding(start = 48.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                NDivider()
                detail()
            }
        }
    }
}
