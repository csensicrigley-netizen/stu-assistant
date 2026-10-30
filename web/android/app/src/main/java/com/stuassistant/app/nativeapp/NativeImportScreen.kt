package com.stuassistant.app.nativeapp

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.kyant.shapes.RoundedRectangle
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

private val importSchools=listOf(Triple("main","主校区","教务系统可自动同步"),Triple("med","医学院","教务系统不同，需手动录入"),Triple("other","其他","自行录入"))
private val importMethods=listOf("paste" to "粘贴文字","csv" to "CSV 表格","manual" to "一条一条加")
private data class ImportDraft(val id:String=UUID.randomUUID().toString(),val title:String,val days:List<Int>,val periods:List<Int>,val from:Int,val to:Int,val teacher:String,val location:String)
private fun importRuns(values:List<Int>):String {
    val runs=mutableListOf<MutableList<Int>>()
    values.distinct().sorted().forEach {v->if(runs.lastOrNull()?.lastOrNull()==v-1)runs.last().add(v)else runs.add(mutableListOf(v))}
    return runs.joinToString(","){if(it.size==1)it.first().toString()else"${it.first()}-${it.last()}"}
}
private fun importLine(d:ImportDraft)=listOf(d.title,d.days.joinToString(","){"${AcademicClock.weekdayText(it)} ${importRuns(d.periods)}节"},if(d.from==d.to)"${d.from}周"else"${d.from}-${d.to}周",d.teacher,d.location).joinToString(" | ")

/** Explicit snake_case serialization is shared with the JS import contract. */
internal fun nativeImportDocument(doc:AcademicTimetableDoc):JSONObject = JSONObject().put("semester",doc.semester).put("checked_at",JSONObject.NULL).put("status",if(doc.unarranged.isEmpty())"ok"else"partial").put("pending",JSONArray()).put("coverage",JSONObject().put("kind","theory").put("weeks","all").put("complete",true).put("source","manual"))
    .put("unarranged",JSONArray(doc.unarranged.map {JSONObject().put("id",it.id).put("title",it.title).put("class_name",it.className).put("teachers",JSONArray(it.teachers))}))
    .put("courses",JSONArray(doc.courses.map {course-> JSONObject().put("id",course.id).put("title",course.title).put("course_code",course.code).put("class_name",course.className).put("teacher",course.teacher).put("teachers",JSONArray(if(course.teacher.isBlank())emptyList<String>()else listOf(course.teacher))).put("meetings",JSONArray(course.meetings.map {m->JSONObject().put("id",m.id).put("course_id",course.id).put("title",m.title.ifEmpty { course.title }).put("class_name",m.className.ifEmpty { course.className }).put("weekday",m.weekday).put("weeks",JSONArray(m.weeks)).put("periods",JSONArray(m.periods)).put("teacher",m.teacher).put("location",m.location).put("raw_time",m.rawTime).put("notes",m.notes).put("online_info",m.onlineInfo)}))}))

@Composable fun NativeImportScreen(store:NativeStore,s:NativeState,onNavigate:(String)->Unit) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val semester=remember {(AcademicCalendar.position()?.term?:AcademicCalendar.currentOrNext(AcademicClock.moment().date)).id}
    var step by remember {mutableStateOf(0)}
    var school by remember {mutableStateOf<String?>(null)}
    var method by remember {mutableStateOf("paste")}
    var text by remember {mutableStateOf("")}
    var note by remember {mutableStateOf("")}
    var raw by remember {mutableStateOf<JSONObject?>(null)}
    var doc by remember {mutableStateOf<AcademicTimetableDoc?>(null)}
    var busy by remember {mutableStateOf<String?>(null)}
    var problem by remember {mutableStateOf<String?>(null)}
    var existing by remember {mutableStateOf<String?>(null)}
    var template by remember {mutableStateOf("")}
    var savedAt by remember {mutableStateOf(semester)}
    fun go(next:Int){problem=null;step=next}
    suspend fun parse(format:String,body:String) {
        busy="parse";problem=null
        try {
            val result=store.request("/v1/timetable/import/parse","POST",JSONObject().put("text",body).put("format",format).put("semester",semester))
            raw=result
            doc=AcademicTimetable.normalize(JSONObject().put("semester",result.optString("semester").ifBlank{semester}).put("status","ok").put("courses",result.optJSONArray("courses")?:JSONArray()).put("unarranged",result.optJSONArray("unarranged")?:JSONArray()).put("pending",JSONArray()).put("coverage",JSONObject().put("complete",true)))
            go(2)
        }catch(e:CancellationException){throw e}catch(_:Exception){problem="服务器没有回应，检查一下网络再试一次。"}finally{busy=null}
    }
    val file=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {uri->if(uri!=null)scope.launch {
        try {val body=withContext(Dispatchers.IO){context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use{it.readText()}?:error("file")};text=body;method="csv";parse("csv",body)}
        catch(e:CancellationException){throw e}catch(_:Exception){problem="文件没有读出来，请选择 CSV UTF-8 文本再试一次。"}
    }}
    val saveTemplate=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) {uri->if(uri!=null)scope.launch {
        try {withContext(Dispatchers.IO){context.contentResolver.openOutputStream(uri)?.use{it.write(template.toByteArray(Charsets.UTF_8))}?:error("file")}}
        catch(e:CancellationException){throw e}catch(_:Exception){problem="模板没下载下来，检查一下网络再试一次。"}
    }}
    LaunchedEffect(store,semester) {try {val result=store.request("/v1/timetable/manual?semester=$semester");if(result.optJSONObject("doc")!=null)existing=result.optString("school")}catch(e:CancellationException){throw e}catch(_:Exception){}}
    BackHandler {onNavigate("week")}
    NPage("导入课表","手动录入 · $semester",actions={NButton("返回课表",{onNavigate("week")})}) {
        NText("医学院的教务系统和主校区不是一套，没法自动同步，只能自己录。录完和自动同步的课表一样显示。",14,muted=true)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {listOf("选校区","录入","核对","完成").forEachIndexed {i,label->NText("${if(i<step)"✓"else i+1} $label",14,bold=i==step,muted=i!=step)}}
        when(step) {
            0->{
                importSchools.forEach {(id,name,hint)->NCard {NRow(name,hint,if(existing==id)"这个学期已录过"else"",onClick={school=id;go(1)});if(id=="med")NText("医学院教务系统和主校区不同，需要你自己录入；录完之后和自动同步的课表一样显示。",14,muted=true)}}
                if(existing!=null)NText("这个学期已经有一份手动课表（${importSchools.firstOrNull{it.first==existing}?.second?:existing}）。再导入会整份覆盖，不会留两份。",14,muted=true)
            }
            1->{
                NButton("‹ 换校区",{go(0)})
                NChips(importMethods,method,{method=it})
                when(method) {
                    "paste"->{NField("把课表粘在这里",text,{text=it},multiline=true,placeholder="细胞生物学实验 | 周四 6-9节 | 1-16周 | 何昭明 | 桑浦山-细胞生物学实验室-理433\n高等数学 | 周一 1-2节,周三 3-4节 | 1-16周 | 张三 | 桑浦山-E305",minHeight=190);NText("一行一门课，竖线或制表符分隔。从 Excel 直接复制也行。",14,muted=true);ImportHowTo()}
                    "csv"->NCard {
                        NText("Excel 里按「课程名称 / 星期 / 节次 / 周次 / 教师 / 地点」六列填，存成 CSV UTF-8 再选进来。先下模板最省事。",14,muted=true)
                        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            NButton("下载模板",{scope.launch {try {template=store.requestText("/v1/timetable/import/template");saveTemplate.launch("课表模板-$semester.csv")}catch(e:CancellationException){throw e}catch(_:Exception){problem="模板没下载下来，检查一下网络再试一次。"}}},busy==null)
                            NButton("选择文件",{file.launch(arrayOf("text/csv","text/comma-separated-values","text/x-csv","text/plain","application/csv","application/vnd.ms-excel"))},busy==null)
                        }
                        if(text.isNotEmpty())NText("已读到 ${text.lines().count{it.isNotBlank()}} 行，点下面「解析」看看对不对。",14,muted=true)
                    }
                    "manual"->ImportManual(busy!=null) {drafts->scope.launch {parse("paste",drafts.joinToString("\n",transform=::importLine))}}
                }
                NError(problem)
                if(method!="manual")NButton(if(busy=="parse")"正在解析…"else"解析",{scope.launch{parse(if(method=="csv")"csv"else"paste",text)}},busy==null&&text.isNotBlank(),true,Modifier.fillMaxWidth())
            }
            2->{
                NButton("‹ 返回修改",{go(1)},busy==null)
                val courses=doc?.courses.orEmpty()
                val meetings=courses.sumOf {it.meetings.size}
                val weeks=courses.flatMap {it.meetings}.flatMap {it.weeks}
                val stats=raw?.optJSONObject("stats")
                val pair=stats?.optJSONArray("weeks")
                val from=pair?.optInt(0)?.takeIf{it>0}?:weeks.minOrNull()?:0
                val to=pair?.optInt(1)?.takeIf{it>0}?:weeks.maxOrNull()?:0
                NCard {Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {Column{NText("课程",14,muted=true);NText("${courses.size} 门",24,true)};Column{NText("上课",14,muted=true);NText("$meetings 段",24,true)};Column{NText("周次",14,muted=true);NText(if(to==0)"周次未标注"else if(from==to)"第 $from 周"else"$from–$to 周",18,true)}}}
                val errors=academicStrings(raw?.optJSONArray("errors"))
                if(errors.isNotEmpty())NCard {NText("有 ${errors.size} 处没读懂",16,true);errors.forEach{NError(it)};NText("这几行先跳过了，改好再解析一次；其余课可以照常导入。",14,muted=true)}
                val warnings=academicStrings(raw?.optJSONArray("warnings"))
                if(warnings.isNotEmpty())NCard {NText("顺手提一下",16,true);warnings.forEach{NText(it,14,muted=true)}}
                if(courses.isNotEmpty())ImportPreviewGrid(courses)
                val unarranged=doc?.unarranged.orEmpty()
                if(unarranged.isNotEmpty())NCard {NText("没排时间的课（${unarranged.size} 门）",16,true);unarranged.forEach{NText(it.title,14)};NText("这些会挂在课表页「需要留意」里，不会画进格子。",14,muted=true)}
                NField("备注（自己看，可留空）",note,{note=it.take(200)})
                NError(problem)
                NButton(if(busy=="save")"正在导入…"else if(errors.isNotEmpty())"先导入读懂的 ${courses.size} 门"else"确认导入",{scope.launch {
                    busy="save";problem=null
                    try {val res=store.request("/v1/timetable/import/save","POST",JSONObject().put("doc",nativeImportDocument(doc!!)).put("school",school).put("note",note));if(res.optBoolean("ok")){savedAt=res.optString("semester",semester);go(3);store.reloadAfterMutation()}else{raw=JSONObject(raw.toString()).put("errors",res.optJSONArray("errors")?:JSONArray().put("导入没有成功，再试一次"));problem="这份课表没通过服务端校验，看看下面红字。"}}
                    catch(e:CancellationException){throw e}catch(_:Exception){problem="导入没有送出去，检查一下网络再试一次。"}finally{busy=null}
                }},courses.isNotEmpty()&&busy==null,true,Modifier.fillMaxWidth())
                if(courses.isEmpty())NText("一门课都没解析出来，先回去改一下。每一行至少要有课程名和「周几 第几节」。",14,muted=true)
                else if(errors.isNotEmpty())NText("也可以先导进去，回头把那几行改好再导入一次 —— 同一学期是整份覆盖，不会留两份。",14,muted=true)
            }
            3->{NCard {NText("✓ 课表已经存好了",18,true);NText("$savedAt · ${importSchools.firstOrNull{it.first==school}?.second.orEmpty()} · ${doc?.courses?.size?:0} 门课。从这一秒起它和自动同步的课表走同一套页面、同一套作息时间，不用再做什么。",14,muted=true)};NButton("去看课表 →",{onNavigate("week")},primary=true,modifier=Modifier.fillMaxWidth());NText("录错了随时回来重录：同一学期再导入一次就是整份覆盖，不会留两份。",14,muted=true)}
        }
        NText("节次时刻按教务处作息表算：13 节、5 个大节，第二、四、五大节各 155 分钟，晚课 19:20 开始。录进去的课只存在你自己的账号里。",14,muted=true)
    }
}

@Composable private fun ImportHowTo() {
    var open by remember {mutableStateOf(false)}
    NRow("怎么填（三种格式，抄一种就行）",trailing=if(open)"⌃"else"⌄",onClick={open=!open})
    if(open)NCard {
        NText("① 一行一课（推荐）",14,true)
        NText("课程名 | 时间 | 周次 | 教师 | 地点\n细胞生物学实验 | 周四 6-9节 | 1-16周 | 何昭明 | 桑浦山-理433\n高等数学 | 周一 1-2节,周三 3-4节 | 1-16周 | 张三 | 桑浦山-E305",14)
        NText("竖线换成制表符（Tab）也行。时间和周次能省，但课程名必须有。",14,muted=true)
        NText("② 从 Excel 直接复制",14,true)
        NText("课程名称\t星期\t节次\t周次\t教师\t地点\n细胞生物学实验\t四\t6-9\t1-16\t何昭明\t理433",14)
        NText("表头认这些写法：课程名称/课程名/课程、星期/周几、节次/节/时间、周次/周、教师/老师、地点/教室。",14,muted=true)
        NText("③ 简写也认",14,true)
        NText("周四 6-9节   星期四 第6-9节   四 6-9   Thu 6-9\n1-16周  第1-16周  1,3,5周  1-8,10-16周  1-16单周\n节次：6-9   第6节-第9节   6,7,8,9",14)
        NText("节次 1–13（对应学校的 1–9、0、A、B、C），周次 1–30，星期 一…日 或 1–7。",14,muted=true)
    }
}

@Composable private fun ImportManual(busy:Boolean,onSubmit:(List<ImportDraft>)->Unit) {
    var drafts by remember {mutableStateOf(emptyList<ImportDraft>())}
    var title by remember {mutableStateOf("")};var days by remember {mutableStateOf(emptyList<Int>())};var periods by remember {mutableStateOf(emptyList<Int>())}
    var from by remember {mutableStateOf("1")};var to by remember {mutableStateOf("16")};var teacher by remember {mutableStateOf("")};var location by remember {mutableStateOf("")};var warn by remember {mutableStateOf<String?>(null)}
    NCard {
        NField("课程名",title,{title=it})
        NText("星期（可多选）",14,true)
        FlowRow(horizontalArrangement=Arrangement.spacedBy(4.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {(1..7).forEach {day->ImportChoice(AcademicClock.weekdayText(day),day in days){days=if(day in days)days-day else(days+day).sorted()}}}
        NText("节次（可多选，1–13）",14,true)
        FlowRow(horizontalArrangement=Arrangement.spacedBy(4.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {AcademicTimetable.periods.forEach {p->ImportChoice(p.code,p.index in periods,"第 ${p.code} 节 ${p.start}–${p.end}"){periods=if(p.index in periods)periods-p.index else(periods+p.index).sorted()}}}
        NText("选中的节次：${if(periods.isEmpty())"还没选"else"第 ${periods.joinToString("、"){AcademicTimetable.periodByIndex[it]?.code?:it.toString()}} 节"}",14,muted=true)
        Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {Box(Modifier.weight(1f)){NField("从第几周",from,{from=it})};Box(Modifier.weight(1f)){NField("到第几周",to,{to=it})}}
        NField("教师",teacher,{teacher=it});NField("地点",location,{location=it})
        NError(warn)
        NButton("再加一门",{
            val a=from.toIntOrNull()?:0;val b=to.toIntOrNull()?:0
            warn=when {title.trim().isEmpty()->"先写课程名。";days.isEmpty()->"至少选一个星期。";periods.isEmpty()->"至少选一节。";a<1||b>30||a>b->"周次要在 1–30 之间，而且开始不能晚于结束。";else->null}
            if(warn==null){drafts=drafts+ImportDraft(title=title.trim(),days=days,periods=periods,from=a,to=b,teacher=teacher.trim(),location=location.trim());title="";teacher="";location="";days=emptyList();periods=emptyList()}
        },!busy,modifier=Modifier.fillMaxWidth())
    }
    if(drafts.isNotEmpty())NCard {
        NText("已经加了 ${drafts.size} 门",14,true)
        drafts.forEach {d->Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                NText(d.title,16,true)
                NText("${d.days.joinToString("、"){AcademicClock.weekdayText(it)}} · 第 ${d.periods.joinToString("、"){AcademicTimetable.periodByIndex[it]?.code?:it.toString()}} 节 · ${if(d.from==d.to)d.from.toString()else"${d.from}–${d.to}"} 周${if(d.teacher.isBlank())""else" · ${d.teacher}"}",14,muted=true)
            }
            NIconButton("删掉 ${d.title}",{drafts=drafts.filter{it.id!=d.id}},!busy)
        }}
    }
    NButton("用这 ${drafts.size} 门课生成预览",{onSubmit(drafts)},drafts.isNotEmpty()&&!busy,true,Modifier.fillMaxWidth())
}

@Composable private fun ImportChoice(label:String,selected:Boolean,description:String=label,onClick:()->Unit) {
    val c=LocalNativeColors.current;val tick=nSelectionFeedback()
    Box(Modifier.heightIn(min=48.dp).clip(RoundedRectangle(12.dp)).background(if(selected)c.brand.copy(alpha=.15f)else c.surface).toggleable(value=selected,role=Role.Checkbox){tick();onClick()}.semantics{contentDescription=description}.padding(horizontal=12.dp,vertical=10.dp),contentAlignment=Alignment.Center){NText(label,14,bold=selected)}
}

@Composable private fun ImportPreviewGrid(courses:List<AcademicCourse>) {
    NCard {
        NText("周视图预览",16,true)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(4.dp)){Box(Modifier.width(20.dp));(1..7).forEach{Box(Modifier.weight(1f),contentAlignment=Alignment.Center){NText(listOf("一","二","三","四","五","六","日")[it-1],14,muted=true)}}}
        Column(verticalArrangement=Arrangement.spacedBy(3.dp)) {AcademicTimetable.periods.forEach {p->Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(4.dp),verticalAlignment=Alignment.CenterVertically) {
            Box(Modifier.width(20.dp)){NText(p.code,14,muted=true)}
            (1..7).forEach {day->
                val course=courses.firstOrNull{it.meetings.any{m->m.weekday==day&&p.index in m.periods}}
                val dark=LocalNativeColors.current.bg.luminance()<.2f
                val classic=LocalNativePalette.current=="classic"
                val mark=if(course!=null)academicCourseColor(course.hue)else LocalNativeColors.current.border.copy(alpha=.4f)
                val mid=if(course==null)mark else nativeOklch(if(dark){if(classic).42 else .44}else{if(classic).87 else .885},if(dark){if(classic).08 else .065}else{if(classic).07 else .06},course.hue.toDouble())
                val shape=RoundedRectangle(4.dp)
                Box(Modifier.weight(1f).height(18.dp).clip(shape).background(mid).then(if(course!=null)Modifier.border(1.dp,mark,shape)else Modifier).semantics{contentDescription=if(course==null)"${AcademicClock.weekdayText(day)} 第${p.code}节 空"else"${course.title} · ${AcademicClock.weekdayText(day)} 第${p.code}节"})
            }
        }}}
        NText("颜色和导入之后的课表一致，看到空的那格就是没录上。",14,muted=true)
    }
}
