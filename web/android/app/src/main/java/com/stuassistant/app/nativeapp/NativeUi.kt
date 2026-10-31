package com.stuassistant.app.nativeapp

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.shapes.RoundedRectangle
import com.stuassistant.app.NativeFeedback
import com.stuassistant.app.R
import com.stuassistant.app.glass.*

data class NativeColors(val bg:Color,val surface:Color,val ink:Color,val muted:Color,val brand:Color,val danger:Color,val border:Color,val onBrand:Color=Color.White)
val LocalNativeColors = staticCompositionLocalOf { NativeColors(Color(0xFFFFF0F1),Color(0xFFFFFCFC),Color(0xFF35212D),Color(0xFF76636D),Color(0xFF6756B9),Color(0xFFAB3A47),Color(0xFFE8DCE0)) }
val LocalNativeBackdrop = staticCompositionLocalOf<Backdrop> { error("Native backdrop owner missing") }
val LocalNativeFeedback = staticCompositionLocalOf<NativeFeedback?> { null }
val LocalNativeStrength = staticCompositionLocalOf { 100 }
val LocalNativeConnect = staticCompositionLocalOf<()->Unit> { {} }
val LocalNativePalette = staticCompositionLocalOf { "sweet" }
val LocalNativeForegroundEpoch = staticCompositionLocalOf { 0 }
val NativeFontFamily=FontFamily(Font(R.font.stu_regular,FontWeight.Normal),Font(R.font.stu_semibold,FontWeight.SemiBold),Font(R.font.stu_bold,FontWeight.Bold))

@Composable fun NTitle(text:String,size:Int=30,lineHeight:Float=size*1.2f) {
    BasicText(text,style=TextStyle(color=LocalNativeColors.current.ink,fontSize=size.sp,lineHeight=lineHeight.sp,fontWeight=FontWeight.Bold,fontFamily=NativeFontFamily))
}

/** Opt-in semantic feedback; visual press interactions do not imply vibration. */
@Composable fun nHapticFeedback(kind:String):()->Unit {
    val engine = LocalNativeFeedback.current
    val strength by rememberUpdatedState(LocalNativeStrength.current)
    val active = remember(engine,kind) { booleanArrayOf(false) }
    DisposableEffect(active) { active[0]=true; onDispose { active[0]=false } }
    return remember(engine,kind,active) { { if(active[0]&&strength>0)engine?.play(kind,strength) } }
}
@Composable fun nSelectionFeedback():()->Unit = nHapticFeedback("selection")

@Composable fun NText(text:String,size:Int=16,bold:Boolean=false,muted:Boolean=false) {
    val c=LocalNativeColors.current
    BasicText(text,style=TextStyle(color=if(muted)c.muted else c.ink,fontSize=size.sp,lineHeight=(size*1.6).sp,fontWeight=if(bold)FontWeight.SemiBold else FontWeight.Normal,fontFamily=NativeFontFamily))
}

@Composable fun NPage(title:String,eyebrow:String="",actions:(@Composable RowScope.()->Unit)?=null,content:@Composable ColumnScope.()->Unit) {
    Box(Modifier.fillMaxSize().imePadding(),contentAlignment=Alignment.TopCenter) {
        Column(Modifier.widthIn(max=600.dp).fillMaxWidth().verticalScroll(rememberScrollState()).statusBarsPadding().padding(start=20.dp,end=20.dp,top=24.dp,bottom=120.dp).navigationBarsPadding(),verticalArrangement=Arrangement.spacedBy(20.dp)) {
            if(title.isNotBlank()||eyebrow.isNotBlank()||actions!=null)Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                if(eyebrow.isNotEmpty()) NText(eyebrow,14,muted=true)
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
                    Box(Modifier.weight(1f)) { NTitle(title) }
                    if(actions!=null) Row(verticalAlignment=Alignment.CenterVertically,content=actions)
                }
            }
            content()
        }
    }
}

/** Source path and colour recipes from the existing Logo.tsx. */
@Composable fun NativeLogo(size:Int=64) {
    val classic=LocalNativePalette.current=="classic"
    val path=remember {androidx.compose.ui.graphics.vector.PathParser().parsePathString("M662 264H406A124 124 0 0 0 406 512H618A124 124 0 0 1 618 760H362").toPath()}
    Canvas(Modifier.size(size.dp).clip(RoundedRectangle((size*232f/1024f).dp)).clearAndSetSemantics{}) {
        val scale=this.size.width/1024f
        scale(scale,scale,pivot=androidx.compose.ui.geometry.Offset.Zero) {
            drawRect(androidx.compose.ui.graphics.Brush.linearGradient(listOf(if(classic)Color(0xFF2A7AE0)else Color(0xFF9D8BF2),if(classic)Color(0xFF0B3586)else Color(0xFF6A55B0)),end=androidx.compose.ui.geometry.Offset(1024f,1024f)),size=androidx.compose.ui.geometry.Size(1024f,1024f))
            drawRect(androidx.compose.ui.graphics.Brush.radialGradient(listOf(Color.White.copy(alpha=.18f),Color.Transparent),center=androidx.compose.ui.geometry.Offset(205f,102f),radius=819f),size=androidx.compose.ui.geometry.Size(1024f,1024f))
            val cut=androidx.compose.ui.graphics.Path().apply {addRect(androidx.compose.ui.geometry.Rect(0f,0f,1024f,1024f));addOval(androidx.compose.ui.geometry.Rect(433f,433f,591f,591f));fillType=androidx.compose.ui.graphics.PathFillType.EvenOdd}
            clipPath(cut) {drawPath(path,if(classic)Color(0xFFF9F6F1)else Color(0xFFFFF7F4),style=androidx.compose.ui.graphics.drawscope.Stroke(100f,cap=androidx.compose.ui.graphics.StrokeCap.Round,join=androidx.compose.ui.graphics.StrokeJoin.Round))}
            drawCircle(if(classic)Color(0xFFF5714E)else Color(0xFFFF9D7E),62f,androidx.compose.ui.geometry.Offset(512f,512f))
        }
    }
}

@Composable fun NButton(label:String,onClick:()->Unit,enabled:Boolean=true,primary:Boolean=false,modifier:Modifier=Modifier,mechanical:Boolean=false) {
    val source=remember { MutableInteractionSource() }
    val commitMechanical = if(mechanical)NativePressFeedback(source,enabled)else null
    val c=LocalNativeColors.current
    LiquidButton({commitMechanical?.invoke();onClick()},LocalNativeBackdrop.current,modifier=modifier,enabled=enabled,interactionSource=source,
        surfaceColor=if(primary)c.brand.copy(alpha=if(enabled).93f else .35f)else c.surface.copy(alpha=if(enabled).5f else .2f)) {
        BasicText(label,style=TextStyle(color=if(primary)c.onBrand else c.ink.copy(alpha=if(enabled)1f else .45f),fontSize=16.sp,lineHeight=22.sp,fontWeight=FontWeight.SemiBold,fontFamily=NativeFontFamily))
    }
}
/** Only explicit commit buttons opt into the existing two-phase preset. */
@Composable internal fun NativePressFeedback(source:MutableInteractionSource,enabled:Boolean=true):()->Unit {
    val engine=LocalNativeFeedback.current
    val strength by rememberUpdatedState(LocalNativeStrength.current)
    val latestEnabled by rememberUpdatedState(enabled)
    val pressed=remember(source,engine) {arrayOfNulls<PressInteraction.Press>(1)}
    val release=remember(source,engine) {{
        if(pressed[0]!=null) {
            pressed[0]=null
            if(strength>0)engine?.play("release",strength)
        }
    }}
    DisposableEffect(pressed) {onDispose {pressed[0]=null}}
    LaunchedEffect(source,engine) {
        source.interactions.collect { interaction ->
            when(interaction) {
                is PressInteraction.Press -> if(latestEnabled&&strength>0) {pressed[0]=interaction;engine?.play("press",strength)}
                is PressInteraction.Release -> if(pressed[0]===interaction.press)release()
                // Cancel only this gesture's pairing; do not interrupt other feedback.
                is PressInteraction.Cancel -> if(pressed[0]===interaction.press)pressed[0]=null
                else -> Unit
            }
        }
    }
    return release
}
@Composable fun NCard(content:@Composable ColumnScope.()->Unit) {
    val c=LocalNativeColors.current
    val shape=RoundedRectangle(20.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(c.surface).border(1.dp,c.border,shape).padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp),content=content)
}

@Composable fun NRow(title:String,subtitle:String="",trailing:String="",onClick:(()->Unit)?=null) {
    val source=remember {MutableInteractionSource()}
    Row(Modifier.fillMaxWidth().heightIn(min=48.dp).then(if(onClick!=null)Modifier.clickable(role=Role.Button,interactionSource=source,indication=null,onClick=onClick)else Modifier).padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)) {NText(title,16,true);if(subtitle.isNotBlank())NText(subtitle,14,muted=true)}
        if(trailing.isNotEmpty())NText(trailing,14,muted=true)
        if(onClick!=null)NText("›",24,muted=true)
    }
}

@Composable fun NIconButton(label:String,onClick:()->Unit,enabled:Boolean=true) {
    val source=remember {MutableInteractionSource()}
    val ink=LocalNativeColors.current.ink
    Box(Modifier.size(48.dp).semantics {contentDescription=label}.clickable(enabled=enabled,role=Role.Button,interactionSource=source,indication=null,onClick=onClick),contentAlignment=Alignment.Center) {
        Canvas(Modifier.size(18.dp)) {
            scale(size.width/24,size.height/24,pivot=androidx.compose.ui.geometry.Offset.Zero) {
                val stroke=androidx.compose.ui.graphics.drawscope.Stroke(1.8f,cap=androidx.compose.ui.graphics.StrokeCap.Round,join=androidx.compose.ui.graphics.StrokeJoin.Round)
                val path=androidx.compose.ui.graphics.Path().apply {moveTo(3f,6f);lineTo(21f,6f);moveTo(9f,6f);lineTo(9f,3f);lineTo(15f,3f);lineTo(15f,6f);moveTo(5f,6f);lineTo(6f,21f);lineTo(18f,21f);lineTo(19f,6f);moveTo(10f,10f);lineTo(10f,17f);moveTo(14f,10f);lineTo(14f,17f)}
                drawPath(path,ink.copy(alpha=if(enabled)1f else .4f),style=stroke)
            }
        }
    }
}

@Composable fun NChips(options:List<Pair<String,String>>,value:String,onSelect:(String)->Unit) = NChips(options,value,onSelect,haptic=null)
@Composable fun NChips(options:List<Pair<String,String>>,value:String,onSelect:(String)->Unit,haptic:String?) {
    if(options.isEmpty())return
    val current by rememberUpdatedState(value)
    val callback by rememberUpdatedState(onSelect)
    val tick=nHapticFeedback(haptic?:"selection")
    val c=LocalNativeColors.current
    LiquidBottomTabs(selectedTabIndex={options.indexOfFirst{it.first==current}.coerceAtLeast(0)},onTabSelected={i->options.getOrNull(i)?.first?.let{if(it!=current){if(haptic!=null)tick();callback(it)}}},backdrop=LocalNativeBackdrop.current,tabsCount=options.size,modifier=Modifier.fillMaxWidth().semantics { isTraversalGroup=true }) {
        options.forEach { (key,label)->
            LiquidBottomTab(onClick={if(key!=current){if(haptic!=null)tick();callback(key)}},modifier=Modifier.semantics { selected=key==current; contentDescription=label }) {
                BasicText(label,style=TextStyle(color=if(key==current)c.brand else c.ink,fontSize=15.sp,lineHeight=20.sp,fontWeight=if(key==current)FontWeight.SemiBold else FontWeight.Normal,fontFamily=NativeFontFamily),maxLines=1)
            }
        }
    }
}

@Composable fun NField(label:String,value:String,onValueChange:(String)->Unit,password:Boolean=false,multiline:Boolean=false,enabled:Boolean=true,placeholder:String="",minHeight:Int=48,autoFocus:Boolean=false) {
    val c=LocalNativeColors.current
    val focus=remember {FocusRequester()}
    val keyboard=LocalSoftwareKeyboardController.current
    LaunchedEffect(autoFocus) {if(autoFocus&&enabled){focus.requestFocus();keyboard?.show()}}
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        if(label.isNotEmpty())NText(label,14,true)
        BasicTextField(value,onValueChange,enabled=enabled,modifier=Modifier.fillMaxWidth().heightIn(min=minHeight.dp).focusRequester(focus).clip(RoundedRectangle(12.dp)).background(c.surface).border(1.dp,c.border,RoundedRectangle(12.dp)).padding(12.dp).semantics { contentDescription=label },
            textStyle=TextStyle(color=c.ink,fontSize=16.sp,lineHeight=25.sp,fontFamily=NativeFontFamily),singleLine=!multiline,
            keyboardOptions=KeyboardOptions(keyboardType=if(password)androidx.compose.ui.text.input.KeyboardType.Password else if(label.contains("邮箱"))androidx.compose.ui.text.input.KeyboardType.Email else if(label.contains("服务器"))androidx.compose.ui.text.input.KeyboardType.Uri else androidx.compose.ui.text.input.KeyboardType.Text,autoCorrectEnabled=false),
            visualTransformation=if(password)PasswordVisualTransformation()else VisualTransformation.None,
            cursorBrush=androidx.compose.ui.graphics.SolidColor(c.brand),decorationBox={input->Box {if(value.isEmpty()&&placeholder.isNotEmpty())BasicText(placeholder,style=TextStyle(color=c.muted,fontSize=16.sp,lineHeight=25.sp,fontFamily=NativeFontFamily));input()}})
    }
}

@Composable fun NError(text:String?,retry:(()->Unit)?=null) {
    if(text.isNullOrEmpty())return
    Column(verticalArrangement=Arrangement.spacedBy(8.dp),modifier=Modifier.semantics{liveRegion=LiveRegionMode.Polite}) { BasicText(text,style=TextStyle(color=LocalNativeColors.current.danger,fontSize=14.sp,lineHeight=22.sp));if(retry!=null)NButton("重试",retry) }
}
@Composable fun NLoading() { NText("正在读取…",14,muted=true) }
@Composable fun NDivider() { Box(Modifier.fillMaxWidth().height(1.dp).background(LocalNativeColors.current.border)) }
fun nativeOpenLink(context:android.content.Context,url:String) { runCatching { val uri=Uri.parse(url);if(uri.scheme in listOf("https","http")) context.startActivity(Intent(Intent.ACTION_VIEW,uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
@Composable fun NOpenLink(label:String,url:String) { val context=LocalContext.current; NRow(label,trailing="↗",onClick={nativeOpenLink(context,url)}) }
