package com.stuassistant.app

import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.kyant.backdrop.backdrops.*
import com.stuassistant.app.glass.*
import com.stuassistant.app.nativeapp.*
import kotlinx.coroutines.*

/** Native entry. The previous BridgeActivity remains in the rollback commit only. */
class NativeActivity:ComponentActivity() {
    private lateinit var store:NativeStore
    private lateinit var feedback:NativeFeedback
    private val work=MainScope()
    private var ready by mutableStateOf(false)
    private var resumeEpoch by mutableIntStateOf(0)
    override fun onCreate(savedInstanceState:Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        store=NativeStore(applicationContext);feedback=NativeFeedback(this)
        if(applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE !=0) {
            AcademicClock.setPreviewTime(intent.getStringExtra("now"))
            if(intent.getBooleanExtra("preview",false))store.enterPreview()
        }
        setContent {NativeApplication(store,feedback,ready,resumeEpoch)}
        migrateLegacyStorage(this,store) {ready=true;refresh("read")}
    }
    private fun refresh(how:String) {
        if(store.state.value.token==null||store.state.value.preview)return
        work.launch {try{store.refresh(how)}catch(e:CancellationException){throw e}catch(_:Exception){}}
    }
    override fun onResume() {super.onResume();resumeEpoch++;if(ready)refresh("auto")}
    override fun onPause() {feedback.cancel();super.onPause()}
    override fun onDestroy() {work.cancel();feedback.close();store.close();super.onDestroy()}
}

@Composable private fun NativeApplication(store:NativeStore,feedback:NativeFeedback,ready:Boolean,resumeEpoch:Int) {
    val s by store.state.collectAsState()
    val systemDark=isSystemInDarkTheme()
    val dark=s.theme=="dark" || (s.theme=="system"&&systemDark)
    val classic=s.palette=="classic"
    val colors=remember(dark,classic) {nativeColors(dark,classic)}
    val context=androidx.compose.ui.platform.LocalContext.current
    val activity=context as ComponentActivity
    SideEffect {WindowCompat.getInsetsController(activity.window,activity.window.decorView).apply {isAppearanceLightStatusBars=!dark;isAppearanceLightNavigationBars=!dark}}
    val reduced=remember(resumeEpoch) {Settings.Global.getFloat(context.contentResolver,Settings.Global.ANIMATOR_DURATION_SCALE,1f)==0f}
    var route by rememberSaveable {mutableStateOf("today")}
    var campusView by rememberSaveable {mutableStateOf("notices")}
    var connect by rememberSaveable {mutableStateOf(false)}
    var gateStart by rememberSaveable {mutableStateOf("hello")}
    var gateCancelable by rememberSaveable {mutableStateOf(false)}
    val needsWelcome=s.token==null&&!s.preview
    // The onboarding flow owns its lifetime across account login and school binding.
    // Receiving the app token must not unmount the gate before its Bind step.
    LaunchedEffect(ready,needsWelcome) {
        if(ready&&needsWelcome&&!connect){gateStart=if(s.error?.contains("登录已过期")==true)"account"else"hello";gateCancelable=false;connect=true}
    }
    val onConnect:()->Unit={gateStart=if(s.token!=null&&!s.preview)"bind"else"account";gateCancelable=true;connect=true}
    val navigate:(String)->Unit={if(it.startsWith("campus:"))campusView=it.substringAfter(':');route=it}
    val base=rememberLayerBackdrop()
    val page=rememberLayerBackdrop()
    val combined=rememberCombinedBackdrop(base,page)
    val sheets=remember {NativeSheetController()}
    val density=LocalDensity.current
    val bottom=with(density){WindowInsets.navigationBars.getBottom(this).toDp().value}
    val ime=WindowInsets.ime.getBottom(density)>0
    val scope=rememberCoroutineScope()
    LaunchedEffect(s.hapticStrength) {if(s.hapticStrength==0)feedback.cancel()}
    BackHandler {activity.moveTaskToBack(true)}
    BackHandler(enabled=!needsWelcome&&!connect&&route!="today"&&sheets.entries.isEmpty()) {route=if(route=="import"||route=="medical")"week"else"today"}
    CompositionLocalProvider(LocalNativeColors provides colors,LocalNativePalette provides s.palette,LocalNativeForegroundEpoch provides resumeEpoch,LocalNativeBackdrop provides base,LocalNativeFeedback provides feedback,LocalNativeStrength provides s.hapticStrength,LocalNativeConnect provides onConnect,LocalNativeSheets provides sheets,LocalLiquidGlassDarkTheme provides dark,LocalLiquidGlassAccentColor provides colors.brand,LocalLiquidGlassReducedMotion provides reduced) {
        Box(Modifier.fillMaxSize().background(colors.bg)) {
            Box(Modifier.fillMaxSize().layerBackdrop(base).background(colors.bg))
            Box(Modifier.fillMaxSize().layerBackdrop(page).nativeSheetBackground(sheets)) {
                if(!ready) NPage("STU 事项助手"){NLoading()}
                else if(needsWelcome||connect) NativeWelcomeScreen(store,s,start=gateStart,onDone={connect=false;store.reloadAfterMutation()},onCancel=if(gateCancelable){{connect=false}}else null)
                else NativePageTransition(NativePageRoute(route,campusView),reduced) { snapshot ->
                    when {
                        snapshot.route=="today"->NativeTodayScreen(store,s,navigate,onConnect)
                        snapshot.route=="week"->NativeWeekScreen(store,s,navigate,onConnect)
                        snapshot.route.startsWith("items")->NativeItemsScreen(store,s,navigate,onConnect,snapshot.route.substringAfter(':',"all"))
                        snapshot.route.startsWith("campus")->NativeCampusScreen(store,s,snapshot.campusView,onConnect,{campusView=it})
                        snapshot.route=="me"->NativeMeScreen(store,s,onConnect,{connect=false;route="today"})
                        snapshot.route=="medical"->NativeMedicalScreen(store,s,navigate)
                        snapshot.route=="import"->NativeImportScreen(store,s,navigate)
                        else->NativeTodayScreen(store,s,navigate,onConnect)
                    }
                }
            }
            if(ready&&!needsWelcome&&!connect&&!ime&&sheets.entries.isEmpty()) {
                val current=if(route=="import"||route=="medical")"week"else route.substringBefore(':')
                val urgent=s.tasks.map(AcademicRecords::task).count {it.id !in s.done&&AcademicRecords.bucket(it) in listOf("today","overdue")}
                val reconnect=s.sources.count {it.optString("state")=="need_login"&&it.optString("source") in academicStrings(s.user?.optJSONArray("bound"))}
                val tabs=listOf(GlassNavigationTab("today","今天"),GlassNavigationTab("week","课表"),GlassNavigationTab("items","事项",urgent),GlassNavigationTab("campus","校园"),GlassNavigationTab("me","我的",reconnect))
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height((104+bottom).dp).nativeSheetBackground(sheets)) {
                    LiquidGlassBottomBar(GlassNavigationState(current,tabs,dark,s.palette,s.hapticStrength,bottom,reduced),combined) {next->if(next!=current){feedback.play("navigation",s.hapticStrength);route=next}else if(route=="import"||route=="medical"){route="week"}}
                }
            }
            if(ready) NativeUpdatePrompt(store,combined,Modifier.align(Alignment.BottomCenter).padding(start=12.dp,end=12.dp,bottom=(104+bottom).dp))
            NativeSheetHost(sheets,combined)
        }
    }
}
