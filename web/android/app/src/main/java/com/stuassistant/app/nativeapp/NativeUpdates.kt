package com.stuassistant.app.nativeapp

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.kyant.backdrop.Backdrop
import com.kyant.shapes.RoundedRectangle
import com.stuassistant.app.glass.LiquidDialogSurface
import kotlinx.coroutines.*
import org.json.JSONObject

private fun packageVersion(context:Context):Pair<String,Long> {
    val info=context.packageManager.getPackageInfo(context.packageName,0)
    return (info.versionName?:"") to if(android.os.Build.VERSION.SDK_INT>=28)info.longVersionCode else info.versionCode.toLong()
}
private suspend fun checkNativeUpdate(store:NativeStore,version:Pair<String,Long>):JSONObject? = try {
    val result=store.request("/v1/meta/version?name=${java.net.URLEncoder.encode(version.first,"UTF-8")}&code=${version.second}",auth=false)
    if(result.optBoolean("outdated"))result.optJSONObject("update") else null
}catch(e:CancellationException){throw e}catch(_:Exception){null}
private fun updateUrl(server:String,info:JSONObject)= info.optString("apk","/download/stu-assistant.apk").let {if(it.startsWith("https://")||it.startsWith("http://"))it else server+"/"+it.trimStart('/')}

@Composable fun NativeUpdateSection(store:NativeStore) {
    val context=LocalContext.current
    val version=remember {packageVersion(context)}
    val prefs=remember {context.getSharedPreferences("native-updates",Context.MODE_PRIVATE)}
    var checking by remember {mutableStateOf(false)}
    var checked by remember {mutableStateOf(false)}
    var info by remember {mutableStateOf<JSONObject?>(null)}
    val scope=rememberCoroutineScope()
    NText("版本与更新",18,true)
    NCard {
        NRow("当前版本 ${version.first}",when {checking->"正在问服务器…";info!=null->"有新版 ${info!!.optString("versionName")}（装新安装包）";checked->"已经是最新版本。";else->"发了新版会在这里和底部提示条上告诉你。"})
        if(info!=null)NButton("下载 ${info!!.optString("versionName")}",{nativeOpenLink(context,updateUrl(store.state.value.server,info!!))},primary=true,haptic=NativeButtonHaptic.Navigation)
        NButton(if(checking)"检查中…"else"检查更新",{scope.launch {checking=true;info=null;prefs.edit().remove("skip").apply();info=checkNativeUpdate(store,version);checked=true;checking=false}},!checking,haptic=NativeButtonHaptic.Submit)
        info?.optJSONArray("notes")?.let {notes->(0 until notes.length()).forEach {NText("· ${notes.optString(it)}",14,muted=true)}}
    }
}

@Composable fun NativeUpdatePrompt(store:NativeStore,backdrop:Backdrop,modifier:Modifier=Modifier) {
    val context=LocalContext.current
    val version=remember {packageVersion(context)}
    val prefs=remember {context.getSharedPreferences("native-updates",Context.MODE_PRIVATE)}
    var info by remember {mutableStateOf<JSONObject?>(null)}
    val s by store.state.collectAsState()
    val foreground=LocalNativeForegroundEpoch.current
    LaunchedEffect(s.server,s.token,foreground) {
        while(isActive) {
            if(s.server.isNotEmpty()) {
                val next=checkNativeUpdate(store,version)
                if(next==null)info=null
                else if(next.optLong("versionCode")>prefs.getLong("skip",0)) {
                    info=next
                    prefs.edit().putLong("skip",next.optLong("versionCode")).apply()
                }
            }
            delay(300000)
        }
    }
    val shown=info?:return
    CompositionLocalProvider(LocalNativeBackdrop provides backdrop) {
        Box(modifier.fillMaxWidth(),contentAlignment=Alignment.BottomCenter) {
            LiquidDialogSurface(backdrop,Modifier.widthIn(max=600.dp).fillMaxWidth().semantics {liveRegion=LiveRegionMode.Polite},RoundedRectangle(16.dp)) {
                Row(Modifier.padding(horizontal=14.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        NText("新版本 ${shown.optString("versionName")} 可以更新${if(shown.optBoolean("mandatory"))"（必须更新）"else""}",14,true)
                        shown.optJSONArray("notes")?.let{notes->(0 until minOf(3,notes.length())).forEach{NText("· ${notes.optString(it)}",12,muted=true)}}
                    }
                    if(!shown.optBoolean("mandatory"))NButton("稍后",{info=null},haptic=NativeButtonHaptic.Utility)
                    NButton("立即更新",{nativeOpenLink(context,updateUrl(s.server,shown))},primary=true,haptic=NativeButtonHaptic.Navigation)
                }
            }
        }
    }
}
