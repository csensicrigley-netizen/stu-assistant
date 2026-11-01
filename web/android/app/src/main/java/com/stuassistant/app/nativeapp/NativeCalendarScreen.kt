package com.stuassistant.app.nativeapp

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import com.kyant.shapes.RoundedRectangle
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.RectangleShape
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.drawPlainBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.roundToInt

val LocalCalendarComposer = staticCompositionLocalOf<() -> Unit> { {} }

@Composable
fun NativeCalendarItemsScreen(store: NativeStore, s: NativeState, onConnect: () -> Unit) {
    var section by rememberSaveable { mutableStateOf("items") }
    var day by rememberSaveable { mutableStateOf(AcademicClock.moment().date) }
    var records by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val open = LocalCalendarComposer.current
    LaunchedEffect(s.calendarLastBatch) { s.calendarFocusDate?.let { day = it } }
    LaunchedEffect(s.server, s.user?.optString("id"), s.calendarRevision) {
        records = emptyList()
        if (!s.preview && s.token != null) try { records = store.calendarItems() }
        catch (e: Exception) { error = e.message }
    }
    LaunchedEffect(s.token) { if (!s.preview && s.token != null) runCatching { store.loadPlans() } }
    NPage("事项", actions = {
        NButton(if (section == "exams") "返回" else "考试", { section = if (section == "exams") "items" else "exams" })
        NButton(if (section == "grades") "返回" else "成绩", { section = if (section == "grades") "items" else "grades" })
    }) {
        AnimatedContent(section, transitionSpec = { (fadeIn(tween(220)) + slideInHorizontally { it / 8 }) togetherWith fadeOut(tween(150)) }, label = "items-section") { target ->
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                when (target) {
                    "exams" -> AcademicExams(s.exams.map(AcademicRecords::exam), AcademicClock.nowMillis())
                    "grades" -> AcademicGrades(s.grades.map(AcademicRecords::grade))
                    else -> {
                        NButton("＋ 记一件事", { if (s.preview || s.token == null) onConnect() else open() }, primary = true, modifier = Modifier.fillMaxWidth())
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            NButton("‹", { day = AcademicClock.addDays(day, -1) })
                            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                CalendarCounter("${AcademicClock.monthDay(day)} · ${AcademicClock.weekdayText(AcademicClock.weekday(day))}", AcademicClock.dayStart(day),
                                    TextStyle(color = LocalNativeColors.current.ink, fontSize = 18.sp, fontFamily = NativeFontFamily, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), duration = 280)
                            }
                            NButton("›", { day = AcademicClock.addDays(day, 1) })
                        }
                        NError(error)
                        AnimatedContent(day, transitionSpec = {
                            val direction = if (targetState > initialState) 1 else -1
                            (slideInHorizontally(tween(280, easing = FastOutSlowInEasing)) { it * direction / 3 } + fadeIn()) togetherWith
                                (slideOutHorizontally(tween(220)) { -it * direction / 3 } + fadeOut())
                        }, label = "agenda-day") { date ->
                            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                val dated = records.filter { CalendarDrafts.date(it.getJSONObject("draft")) == date }
                                val old = s.plans.filter { it.optString("date") == date }
                                if (dated.isEmpty() && old.isEmpty()) NText("这一天还空着", 16, muted = true)
                                dated.forEach { record ->
                                    val draft = record.getJSONObject("draft")
                                    NRow((if (record.optBoolean("done")) "✓  " else "○  ") + draft.optString("title"), CalendarDrafts.label(draft), onClick = {
                                        val account = store.calendarAccount()
                                        scope.launch { try { store.toggleCalendar(record.optString("id"), account) } catch (e: Exception) { error = e.message } }
                                    })
                                }
                                old.forEach { plan -> NRow(plan.optString("title"), plan.optString("start"), if (plan.optBoolean("done")) "✓" else "○", onClick = {
                                    scope.launch { try { store.togglePlan(plan.optString("id"), !plan.optBoolean("done")) } catch (e: Exception) { error = e.message } }
                                }) }
                            }
                        }
                        val undated = records.filter { CalendarDrafts.date(it.getJSONObject("draft")) == null }
                        if (undated.isNotEmpty()) {
                            NTitle("待安排", 21)
                            undated.forEach { record -> NRow(record.getJSONObject("draft").optString("title"), trailing = if (record.optBoolean("done")) "✓" else "○", onClick = {
                                val account = store.calendarAccount()
                                scope.launch { try { store.toggleCalendar(record.optString("id"), account) } catch (e: Exception) { error = e.message } }
                            }) }
                        }
                        if (s.tasks.isNotEmpty()) {
                            NDivider(); NTitle("课程动态", 21)
                            s.tasks.map(AcademicRecords::task).filter { it.id !in s.done }.take(12).forEach { task ->
                                NRow(task.title, task.course, "○", onClick = { store.toggleTask(task.id) })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun CalendarTimeline(day: String, onDay: (String) -> Unit, drafts: List<JSONObject>, selected: Int,
    existing: List<CalendarMark>, doc: AcademicTimetableDoc?, onEdit: (Int, String, Long, Long?) -> Unit, onSelect: (Int) -> Unit, onPrecise: (CalendarMark) -> Unit) {
    val colors = LocalNativeColors.current
    val density = LocalDensity.current
    val reduced = com.stuassistant.app.glass.LocalLiquidGlassReducedMotion.current
    val blockShape = remember { RoundedRectangle(12.dp) }
    val scrollTick = nSelectionFeedback()
    var windowStart by remember { mutableFloatStateOf(8f*60) }
    var windowMinutes by remember { mutableFloatStateOf(360f) }
    var gesturing by remember { mutableStateOf(false) }
    val displayedStart by animateFloatAsState(windowStart, if (gesturing) snap() else tween(220, easing = FastOutSlowInEasing), label = "calendar-hour-window")
    var keepWindowForSelection by remember { mutableStateOf<Int?>(null) }
    val currentSelected by rememberUpdatedState(selected)
    val currentMarks = calendarMarks(drafts)
    LaunchedEffect(selected) {
        if (keepWindowForSelection == selected) { keepWindowForSelection = null; return@LaunchedEffect }
        currentMarks.firstOrNull { it.item == selected }?.let { mark ->
            val duration = ((mark.end ?: mark.start)-mark.start)/60000f
            windowMinutes = (duration+240).coerceIn(360f,1440f)
            val center = AcademicClock.moment(mark.start).minutes + duration/2
            windowStart = (center-windowMinutes/2).coerceIn(0f,1440f-windowMinutes)
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            NButton("‹", { onDay(AcademicClock.addDays(day,-1)) }, modifier = Modifier.size(48.dp).semantics { contentDescription = "前一天" })
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                CalendarCounter(day.take(7).replace("-", "年")+"月", AcademicClock.dayStart(day),
                    TextStyle(color = colors.ink, fontSize = 17.sp, fontFamily = NativeFontFamily, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold))
            }
            NButton("›", { onDay(AcademicClock.addDays(day,1)) }, modifier = Modifier.size(48.dp).semantics { contentDescription = "后一天" })
        }
        AnimatedContent(day, Modifier.weight(1f).fillMaxWidth().clipToBounds(), transitionSpec = {
            val sign = if (targetState > initialState) 1 else -1
            (slideInHorizontally(tween(320, easing = FastOutSlowInEasing)) { sign*it/3 }+fadeIn(tween(220))) togetherWith
                (slideOutHorizontally(tween(280, easing = FastOutSlowInEasing)) { -sign*it/3 }+fadeOut(tween(220)))
        }, label = "calendar-three-days") { date ->
            val centerDay = AcademicClock.dayStart(date)
            val dates = (-1..1).map { AcademicClock.addDays(date,it) }
            val all = existing + currentMarks + dates.flatMap { d ->
                val midnight = AcademicClock.dayStart(d)
                AcademicTimetable.planFor(doc,d).blocks.map { CalendarMark("course-$d-${it.key}",it.course.title,
                    midnight+it.startMin*60000L,midnight+it.endMin*60000L,source="course") }
            }
            val naturalSlots = dates.flatMap { calendarDaySlots(all, AcademicClock.dayStart(it)) }
            var heldColumns by remember(date) { mutableStateOf<Map<String,Pair<Int,Int>>>(emptyMap()) }
            val slots = naturalSlots.map { slot -> heldColumns[slot.mark.key]?.let { slot.copy(column=it.first,columns=it.second) } ?: slot }
                .sortedBy { if(it.mark.item == selected) 2 else if(it.mark.item >= 0) 1 else 0 }
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().height(42.dp).padding(start=52.dp,end=12.dp), verticalAlignment=Alignment.CenterVertically) {
                    dates.forEach { d ->
                        val chosen = d == date
                        Row(Modifier.weight(1f).fillMaxHeight().clickable { if(!chosen) { scrollTick(); onDay(d) } },
                            horizontalArrangement=Arrangement.Center, verticalAlignment=Alignment.CenterVertically) {
                            NText(AcademicClock.weekdayText(AcademicClock.weekday(d)),11,muted=true)
                            Spacer(Modifier.width(5.dp))
                            Box(Modifier.size(26.dp).clip(CircleShape).background(if(chosen)colors.brand else Color.Transparent), contentAlignment=Alignment.Center) {
                                CalendarCounter(d.takeLast(2).toInt().toString(),AcademicClock.dayStart(d),
                                    TextStyle(color=if(chosen)colors.onBrand else colors.ink,fontSize=14.sp,fontFamily=NativeFontFamily,fontWeight=androidx.compose.ui.text.font.FontWeight.SemiBold))
                            }
                        }
                    }
                }
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().clipToBounds().semantics {
                    contentDescription="日历时间线"; stateDescription="可见分钟:${windowMinutes.roundToInt()}"
                }) {
                    val track = rememberLayerBackdrop()
                    val width = constraints.maxWidth.toFloat()
                    val height = constraints.maxHeight.toFloat()
                    val gutter = with(density) { 52.dp.toPx() }
                    val dayWidth = (width-gutter-with(density){12.dp.toPx()})/3
                    val boxes = calendarBoxes(slots,centerDay,displayedStart,windowMinutes,width,height,density.density)
                    val hourPixels = height*60/windowMinutes
                    val major = when { hourPixels >= with(density){40.dp.toPx()} -> 60; hourPixels >= with(density){20.dp.toPx()} -> 120; else -> 240 }
                    Box(Modifier.fillMaxSize().calendarGestures(date,slots,
                        viewport={windowStart to windowMinutes}, updateViewport={start,minutes->windowStart=start;windowMinutes=minutes},
                        active={isActive->
                            if(isActive)heldColumns=naturalSlots.associate{it.mark.key to (it.column to it.columns)}
                            else heldColumns=emptyMap()
                            gesturing=isActive
                        },onEdit=onEdit,
                        selectByDrag={index->keepWindowForSelection=index.takeIf{it!=currentSelected};onSelect(index)},
                        onTap={onSelect(it.item);onPrecise(it)},onCreate={onEdit(selected,"schedule",it,null)},onScrollTick=scrollTick)
                        .layerBackdrop(track)) {
                        Canvas(Modifier.fillMaxSize()) {
                            for(column in 0..3) {
                                val x=gutter+column*dayWidth
                                drawLine(colors.ink.copy(alpha=.07f),androidx.compose.ui.geometry.Offset(x,0f),androidx.compose.ui.geometry.Offset(x,height),1.dp.toPx())
                            }
                            for(minute in 0..1440 step (major/2)) {
                                val y=(minute-displayedStart)/windowMinutes*height
                                if(y !in 0f..height)continue
                                drawLine(colors.ink.copy(alpha=if(minute%major==0).13f else .055f),
                                    androidx.compose.ui.geometry.Offset(gutter,y),androidx.compose.ui.geometry.Offset(width-12.dp.toPx(),y),1.dp.toPx())
                            }
                        }
                        for(minute in 0..1440 step major) {
                            val y=(minute-displayedStart)/windowMinutes*height
                            if(y in -20f..(height+20)) Box(Modifier.offset{IntOffset(0,(y-7.dp.toPx()).roundToInt())}.width(44.dp),contentAlignment=Alignment.CenterEnd) {
                                BasicText(AcademicClock.clock(minute),style=TextStyle(color=colors.muted,fontSize=10.sp,fontFamily=NativeFontFamily,fontFeatureSettings="tnum"))
                            }
                        }
                        boxes.forEach { rect ->
                            val mark=rect.slot.mark
                            val segment=((rect.slot.midnight-AcademicClock.dayStart(AcademicClock.moment(mark.start).date))/86400000L).toInt()
                            key(mark.key,segment) {
                                val left by animateFloatAsState(rect.left,if(reduced)snap()else tween(160),label="calendar-event-column")
                                val eventWidth by animateFloatAsState(rect.width,if(reduced||gesturing)snap()else tween(180),label="calendar-event-width")
                                if(rect.bottom>=-48 && rect.top<=height+48) {
                                    val editable=mark.item>=0
                                    val focused=editable&&mark.item==selected
                                    val point=mark.end==null
                                    val tint=when {mark.field=="deadline"->if(colors.bg.luminance()>.5f)Color(0xFFD63760)else Color(0xFFFF809D)
                                        editable->colors.brand;mark.source=="course"->colors.muted;else->Color(0xFF398A79)}
                                    val position=Modifier.offset{IntOffset(left.roundToInt(),rect.top.roundToInt())}
                                        .width(with(density){eventWidth.toDp()}).requiredHeight(with(density){rect.height.toDp()})
                                        .semantics(mergeDescendants=true) {
                                            contentDescription="${mark.title} ${if(mark.field=="deadline")"截止" else "安排"} ${CalendarDrafts.iso(mark.start)}${if(editable)" 可拖动" else " 只读"}"
                                            if(editable)onClick(label="修改时间"){onSelect(mark.item);onPrecise(mark);true}
                                        }
                                    if(point) Box(position) {
                                        Box(Modifier.align(Alignment.Center).fillMaxWidth().height(2.dp).background(tint))
                                        Box(Modifier.align(Alignment.CenterStart).size(22.dp).clip(CircleShape).background(tint.copy(alpha=.18f)),contentAlignment=Alignment.Center) {
                                            Box(Modifier.size(12.dp).clip(CircleShape).background(tint).border(1.5.dp,colors.bg,CircleShape))
                                        }
                                        BasicText(mark.title,Modifier.align(Alignment.TopStart).padding(start=3.dp,end=3.dp),
                                            style=TextStyle(color=tint,fontSize=10.sp,fontFamily=NativeFontFamily),maxLines=1,overflow=TextOverflow.Ellipsis)
                                        if(editable&&mark.field=="schedule")Box(Modifier.align(Alignment.BottomEnd).size(20.dp).semantics{contentDescription="拖出时长"},contentAlignment=Alignment.Center){
                                            Box(Modifier.size(8.dp).border(1.5.dp,tint,CircleShape))
                                        }
                                    } else Box(position.shadow(if(focused)6.dp else 0.dp,blockShape,ambientColor=tint.copy(alpha=.2f),spotColor=tint.copy(alpha=.25f))
                                        .clip(blockShape).background(Brush.verticalGradient(if(editable)listOf(tint.copy(alpha=if(focused).89f else .65f),tint.copy(alpha=if(focused)1f else .80f))
                                            else listOf(tint.copy(alpha=.15f),tint.copy(alpha=.23f))))
                                        .border(1.dp,if(editable)colors.onBrand.copy(alpha=.2f)else tint.copy(alpha=.18f),blockShape)) {
                                        Column(Modifier.fillMaxSize().padding(horizontal=7.dp,vertical=8.dp),verticalArrangement=Arrangement.Center) {
                                            BasicText(mark.title,style=TextStyle(color=if(editable)colors.onBrand else colors.ink,fontSize=11.sp,lineHeight=15.sp,fontFamily=NativeFontFamily,
                                                fontWeight=if(focused)androidx.compose.ui.text.font.FontWeight.SemiBold else androidx.compose.ui.text.font.FontWeight.Normal),
                                                maxLines=if(rect.height>=with(density){68.dp.toPx()})2 else 1,overflow=TextOverflow.Ellipsis)
                                            if(rect.height>=with(density){68.dp.toPx()}) {
                                                Spacer(Modifier.height(3.dp))
                                                CalendarCounter(AcademicClock.clockAt(mark.start),mark.start,TextStyle(color=if(editable)colors.onBrand.copy(alpha=.8f)else colors.muted,fontSize=10.sp,fontFamily=NativeFontFamily))
                                            }
                                        }
                                        if(editable) {
                                            Box(Modifier.align(Alignment.TopCenter).padding(top=4.dp).width(20.dp).height(2.dp).clip(CircleShape).background(colors.onBrand.copy(alpha=.65f)))
                                            Box(Modifier.align(Alignment.BottomCenter).padding(bottom=4.dp).width(20.dp).height(2.dp).clip(CircleShape).background(colors.onBrand.copy(alpha=.65f)))
                                        }
                                    }
                                }
                            }
                        }
                    }
                    // The edge material samples only this calendar, never the page behind it.
                    listOf(true,false).forEach { top ->
                        val mask=Brush.verticalGradient(if(top)listOf(Color.Black,Color.Black.copy(alpha=.65f),Color.Transparent)
                            else listOf(Color.Transparent,Color.Black.copy(alpha=.65f),Color.Black))
                        Box(Modifier.align(if(top)Alignment.TopCenter else Alignment.BottomCenter).fillMaxWidth().height((maxHeight*.16f).coerceIn(36.dp,64.dp))
                            .graphicsLayer{compositingStrategy=CompositingStrategy.Offscreen}
                            .drawWithContent{drawContent();drawRect(mask,blendMode=BlendMode.DstIn)}
                            .drawPlainBackdrop(backdrop=track,shape={RectangleShape},effects={
                                blur(7.dp.toPx())
                                if(!reduced)lens(6.dp.toPx(),12.dp.toPx(),chromaticAberration=true)
                            }).background(colors.bg.copy(alpha=.72f)))
                    }
                }
            }
        }
        if(currentMarks.none{it.item==selected})Box(Modifier.padding(horizontal=24.dp)) {
            NRow("时间",trailing="未安排  ＋",onClick={onPrecise(CalendarMark("new-$selected",drafts[selected].optString("title"),AcademicClock.dayStart(day)+14*3600000L,null,item=selected))})
        }
    }
}

@Composable internal fun CalendarPrecise(mark: CalendarMark, save: (Long, Long?) -> Unit) {
    var date by remember(mark.key) { mutableStateOf(AcademicClock.moment(mark.start).date) }
    var start by remember(mark.key) { mutableStateOf(AcademicClock.clockAt(mark.start)) }
    var end by remember(mark.key) { mutableStateOf(mark.end?.let(AcademicClock::clockAt).orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.semantics(mergeDescendants = true) { contentDescription = "精确日期" }) {
            NField("日期", date, { date = it; error = null }, placeholder = "2026-11-02")
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = "精确开始时间" }) {
                NField(if (mark.field == "deadline") "截止" else "开始", start, { start = it; error = null }, placeholder = "14:00")
            }
            if (mark.field == "schedule") Box(Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = "精确结束时间" }) {
                NField("结束", end, { end = it; error = null }, placeholder = "可空")
            }
        }
        NError(error)
        Spacer(Modifier.weight(1f))
            NButton("完成", {
                val a = AcademicClock.parseTime("${date}T$start:00")
                val b = if (end.isBlank()) null else AcademicClock.parseTime("${date}T$end:00")
                if (a == null || (end.isNotBlank() && (b == null || b <= a))) error = "日期或时间无效，结束需晚于开始"
                else save(a, b)
            }, primary = true, modifier = Modifier.fillMaxWidth())
    }
}
