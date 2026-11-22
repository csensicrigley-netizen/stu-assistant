package com.stuassistant.app

import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
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
    private var playLaunchReveal by mutableStateOf(false)
    private var resumeEpoch by mutableIntStateOf(0)
    override fun onCreate(savedInstanceState:Bundle?) {
        val splash=installSplashScreen()
        playLaunchReveal=savedInstanceState==null
        splash.setOnExitAnimationListener {provider->provider.remove()}
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        store=NativeStore(applicationContext);feedback=NativeFeedback(this)
        LauncherThemeSync.syncSplashThemeImmediate(this,store.state.value.theme)
        if(applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE !=0) {
            AcademicClock.setPreviewTime(intent.getStringExtra("now"))
            if(intent.getBooleanExtra("preview",false))store.enterPreview()
        }
        setContent {NativeApplication(store,feedback,ready,playLaunchReveal,{playLaunchReveal=false},{mode->LauncherThemeSync.syncSplashThemeImmediate(this,mode)},resumeEpoch)}
        migrateLegacyStorage(this,store) {ready=true;refresh("read")}
    }
    private fun refresh(how:String) {
        if(store.state.value.token==null||store.state.value.preview)return
        work.launch {try{store.refresh(how)}catch(e:CancellationException){throw e}catch(_:Exception){}}
    }
    override fun onResume() {super.onResume();resumeEpoch++;if(ready)refresh("auto")}
    override fun onPause() {feedback.cancel();super.onPause()}
    override fun onStop() {
        if(!isChangingConfigurations&&::store.isInitialized) {
            LauncherThemeSync.syncLauncherAliasOnStop(applicationContext,store.state.value.theme)
        }
        super.onStop()
    }
    override fun onDestroy() {work.cancel();feedback.close();store.close();super.onDestroy()}
}

/**
 * Issue #35: interpolate the theme colour table instead of swapping it in one frame.
 * Every field crosses over the same 220 ms tween, so background, cards, text and the glass
 * controls move together. When the system asks for reduced motion (ANIMATOR_DURATION_SCALE
 * is 0) the tween duration collapses to 0 and the table changes immediately, which keeps
 * the existing accessibility behaviour and avoids double-looking transitions.
 */
@Composable private fun animatedNativeColors(target:NativeColors,reduced:Boolean):NativeColors {
    val spec=tween<Color>(durationMillis=if(reduced) 0 else AppThemeTransitionDurationMillis)
    return NativeColors(
        bg=animateColorAsState(target.bg,spec,label="nativeColorsBg").value,
        surface=animateColorAsState(target.surface,spec,label="nativeColorsSurface").value,
        ink=animateColorAsState(target.ink,spec,label="nativeColorsInk").value,
        muted=animateColorAsState(target.muted,spec,label="nativeColorsMuted").value,
        brand=animateColorAsState(target.brand,spec,label="nativeColorsBrand").value,
        danger=animateColorAsState(target.danger,spec,label="nativeColorsDanger").value,
        border=animateColorAsState(target.border,spec,label="nativeColorsBorder").value,
        onBrand=animateColorAsState(target.onBrand,spec,label="nativeColorsOnBrand").value,
    )
}

@Composable private fun NativeApplication(store:NativeStore,feedback:NativeFeedback,ready:Boolean,playLaunchReveal:Boolean,onLaunchRevealFinished:()->Unit,onThemeModeObserved:(String)->Unit,resumeEpoch:Int) {
    val s by store.state.collectAsState()
    val systemDark=isSystemInDarkTheme()
    val dark=s.theme=="dark" || (s.theme=="system"&&systemDark)
    // Issue #35: keep the target table separate from the animated one so the window,
    // status bar and system insets keep using the final colour instead of every frame.
    val targetColors=remember(dark,s.palette) {nativeColors(dark,s.palette)}
    val launchState=remember {SundialLaunchState(playLaunchReveal)}
    val context=androidx.compose.ui.platform.LocalContext.current
    val activity=context as ComponentActivity
    SideEffect {
        WindowCompat.getInsetsController(activity.window,activity.window.decorView).apply {
            isAppearanceLightStatusBars=!dark
            isAppearanceLightNavigationBars=!dark
        }
    }
    LaunchedEffect(s.theme) { onThemeModeObserved(s.theme) }
    LaunchedEffect(targetColors.bg) {
        activity.window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(targetColors.bg.toArgb()))
    }
    val reduced=remember(resumeEpoch) {Settings.Global.getFloat(context.contentResolver,Settings.Global.ANIMATOR_DURATION_SCALE,1f)==0f}
    // Issue #35: the native shell used to swap the colour table in a single frame, while the
    // web build animated the same change through CSS transitions. Interpolate the table so the
    // two layers behave alike; reduced motion still switches immediately.
    val colors=animatedNativeColors(targetColors,reduced)
    val themeProgress by animateFloatAsState(
        targetValue=if(dark) 1f else 0f,
        animationSpec=tween(durationMillis=if(reduced) 0 else AppThemeTransitionDurationMillis),
        label="nativeGlassTheme",
    )
    var route by rememberSaveable {mutableStateOf("today")}
    var calendarOpen by rememberSaveable {mutableStateOf(false)}
    var calendarDraft by key(s.server, s.user?.optString("id"), s.preview) {
        rememberSaveable { mutableStateOf<CalendarComposerDraft?>(null) }
    }
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
    val openCalendarComposer: (CalendarComposerDraft) -> Unit = { draft ->
        calendarDraft = draft
        calendarOpen = true
    }
    val base=rememberLayerBackdrop()
    val page=rememberLayerBackdrop()
    val combined=rememberCombinedBackdrop(base,page)
    val sheets=remember {NativeSheetController()}
    val barSuppressor = remember { NativeBottomBarSuppressor() }
    val itemsViewState = key(s.server, s.user?.optString("id"), s.preview) {
        rememberSaveableStateHolder()
    }
    val density=LocalDensity.current
    val bottom=with(density){WindowInsets.navigationBars.getBottom(this).toDp().value}
    val ime=WindowInsets.ime.getBottom(density)>0
    val showBottomNavigation = ready && !needsWelcome && !connect
    val retractBottomNavigation = barSuppressor.suppressed || (ime && !calendarOpen)
    LaunchedEffect(s.hapticStrength) {if(s.hapticStrength==0)feedback.cancel()}
    BackHandler {activity.moveTaskToBack(true)}
    BackHandler(enabled=!needsWelcome&&!connect&&route!="today"&&sheets.entries.isEmpty()) {route=if(route=="import"||route=="medical")"me"else"today"}
    CompositionLocalProvider(LocalCalendarComposer provides openCalendarComposer,LocalNativeColors provides colors,LocalNativePalette provides s.palette,LocalNativeForegroundEpoch provides resumeEpoch,LocalNativeBackdrop provides base,LocalNativeFeedback provides feedback,LocalNativeStrength provides s.hapticStrength,LocalNativeConnect provides onConnect,LocalNativeSheets provides sheets,LocalNativeBottomBarSuppressor provides barSuppressor,LocalLiquidGlassDarkTheme provides dark,LocalLiquidGlassAccentColor provides colors.brand,LocalLiquidGlassReducedMotion provides reduced,LocalLiquidGlassThemeProgress provides themeProgress) {
        Box(Modifier.fillMaxSize().background(if(launchState.active) launchStageColor(dark) else colors.bg)) {
            Box(Modifier.fillMaxSize().background(colors.bg).launchIrisRevealEffect(launchState,dark=dark)) {
                val orbPrimary = colors.brand.copy(alpha = 0.15f + 0.07f * themeProgress)
                val orbWarm = lerp(colors.brand, colors.danger, 0.42f).copy(alpha = 0.10f + 0.04f * themeProgress)
                val orbBreeze = lerp(colors.brand, Color(0xFF38A89D), 0.38f).copy(alpha = 0.085f + 0.035f * themeProgress)
                Box(
                    Modifier
                        .fillMaxSize()
                        .layerBackdrop(base)
                        .background(colors.bg)
                        .drawWithCache {
                            val w = size.width
                            val h = size.height
                            val valid = w > 1f && h > 1f
                            val c1 = Offset(w * 0.86f, h * 0.10f)
                            val r1 = (w * 0.80f).coerceAtLeast(1f)
                            val c2 = Offset(w * 0.12f, h * 0.34f)
                            val r2 = (w * 0.70f).coerceAtLeast(1f)
                            val c3 = Offset(w * 0.84f, h * 0.72f)
                            val r3 = (w * 0.74f).coerceAtLeast(1f)
                            val b1 = if (valid) Brush.radialGradient(listOf(orbPrimary, Color.Transparent), c1, r1) else null
                            val b2 = if (valid) Brush.radialGradient(listOf(orbWarm, Color.Transparent), c2, r2) else null
                            val b3 = if (valid) Brush.radialGradient(listOf(orbBreeze, Color.Transparent), c3, r3) else null
                            onDrawBehind {
                                if (b1 != null) drawCircle(b1, r1, c1)
                                if (b2 != null) drawCircle(b2, r2, c2)
                                if (b3 != null) drawCircle(b3, r3, c3)
                            }
                        },
                )
                Box(Modifier.fillMaxSize().layerBackdrop(page).nativeSheetBackground(sheets)
                    .then(if(calendarOpen) Modifier.clearAndSetSemantics {} else Modifier)) {
                    if(!ready) Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){NativeLogo(80)}
                    else if(needsWelcome||connect) NativeWelcomeScreen(store,s,start=gateStart,onDone={connect=false;store.reloadAfterMutation()},onCancel=if(gateCancelable){{connect=false}}else null)
                    else NativePageTransition(NativePageRoute(route,campusView),reduced) { snapshot ->
                        when {
                            snapshot.route=="today"->NativeTodayScreen(store,s,navigate,onConnect)
                            snapshot.route=="week"->NativeWeekScreen(store,s,navigate,onConnect)
                            snapshot.route.startsWith("items")->itemsViewState.SaveableStateProvider("items") {
                                NativeCalendarItemsScreen(store, s, onConnect, route = snapshot.route)
                            }
                            snapshot.route.startsWith("campus")->NativeCampusScreen(store,s,snapshot.campusView,onConnect,{campusView=it})
                            snapshot.route=="me"->NativeMeScreen(store,s,onConnect,{connect=false;route="today"},navigate)
                            snapshot.route=="medical"->NativeMedicalScreen(store,s,navigate)
                            snapshot.route=="import"->NativeImportScreen(store,s,navigate)
                            else->NativeTodayScreen(store,s,navigate,onConnect)
                        }
                    }
                }
                // Keep one dock through sheet and IME transitions so it always returns
                // from its previous animated position.
                if(showBottomNavigation) {
                    val current=if(route=="import"||route=="medical")"me"else route.substringBefore(':')
                    val urgent=s.tasks.map(AcademicRecords::task).count {it.id !in s.done&&AcademicRecords.bucket(it) in listOf("today","overdue")}
                    val reconnect=s.sources.count {it.optString("state")=="need_login"&&it.optString("source") in academicStrings(s.user?.optJSONArray("bound"))}
                    val tabs=listOf(GlassNavigationTab("today","今天"),GlassNavigationTab("week","课表"),GlassNavigationTab("items","事项",urgent),GlassNavigationTab("campus","校园"),GlassNavigationTab("me","我的",reconnect))
                    val navRetract by animateFloatAsState(
                        targetValue = if (retractBottomNavigation) 1f else 0f,
                        animationSpec = when {
                            reduced -> snap()
                            retractBottomNavigation -> tween(200, easing = CubicBezierEasing(.32f, 0f, .67f, 1f))
                            else -> spring(dampingRatio = .86f, stiffness = 460f)
                        },
                        label = "itemsNavRetract",
                    )
                    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height((104+bottom).dp)
                        .graphicsLayer {
                            val progress = navRetract.coerceIn(0f, 1f)
                            translationY = progress * size.height
                            alpha = 1f - progress
                        }
                        .nativeSheetBackground(sheets)
                        .then(if(calendarOpen||retractBottomNavigation) Modifier.clearAndSetSemantics {} else Modifier)) {
                        LiquidGlassBottomBar(GlassNavigationState(current,tabs,dark,s.palette,s.hapticStrength,bottom,reduced),combined) {next->
                            // Also reject a selection queued before the sheet opened.
                            if(sheets.entries.isEmpty()&&!calendarOpen&&!retractBottomNavigation) {
                                if(next!=current){feedback.play("navigation",s.hapticStrength);route=next}
                                else if(route=="import"||route=="medical"){route="me"}
                            }
                        }
                    }
                }
                if(ready) NativeUpdatePrompt(store,combined,Modifier.align(Alignment.BottomCenter).padding(start=12.dp,end=12.dp,bottom=(104+bottom).dp).nativeSheetBackground(sheets))
                NativeHomeAnnouncement(store,ready&&!needsWelcome&&!connect&&route=="today"&&!ime&&!calendarOpen,sheets.entries.isEmpty())
                NativeSheetHost(sheets,combined)
                AnimatedVisibility(calendarOpen&&s.token!=null&&!s.preview, enter=slideInVertically(tween(420)){ -it/12 }+fadeIn(tween(220)), exit=slideOutVertically(tween(260)){ -it/12 }+fadeOut(tween(220))) {
                    CompositionLocalProvider(LocalNativeBackdrop provides base) {
                        key(calendarDraft) {
                            NativeCalendarComposer(store, s, inputBackdrop = combined, initialDraft = calendarDraft) {
                                calendarOpen = false
                            }
                        }
                    }
                }
            }
            SundialLaunchOverlay(
                state=launchState,
                ready=ready,
                reducedMotion=reduced,
                dark=dark,
                onFinished=onLaunchRevealFinished,
            )
        }
    }
}
