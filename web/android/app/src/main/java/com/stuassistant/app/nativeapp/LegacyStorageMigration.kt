package com.stuassistant.app.nativeapp

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File

/** A one-time, local-only reader for the previous Capacitor origin. No page UI uses it. */
@SuppressLint("SetJavaScriptEnabled")
fun migrateLegacyStorage(context:Context,store:NativeStore,finished:()->Unit) {
    if(store.legacyMigrationDone()) {finished();return}
    if(!File(context.applicationInfo.dataDir,"app_webview").exists()) {store.importLegacy(JSONObject());finished();return}
    val handler=Handler(Looper.getMainLooper())
    var closed=false
    val web=WebView(context)
    fun close() {if(closed)return;closed=true;web.stopLoading();web.destroy();finished()}
    web.settings.apply {javaScriptEnabled=true;domStorageEnabled=true;blockNetworkLoads=true;allowFileAccess=false;allowContentAccess=false}
    web.webViewClient=object:WebViewClient() {
        override fun onPageFinished(view:WebView,url:String) {
            if(closed)return
            view.evaluateJavascript("""(function(){var out={};for(var i=0;i<localStorage.length;i++){var k=localStorage.key(i);if(k==='stu.api'||k==='stu.token'||k==='stu.account'||k==='stu.theme'||k==='stu.palette'||k==='stu.haptics.strength'||k==='stu.haptics'||k==='stu.update.skip'||k.indexOf('stu.school:')===0||k.indexOf('stu.done.v2:')===0){out[k]=localStorage.getItem(k);}}return JSON.stringify(out);})()""") {raw->
                if(!closed) {runCatching { val text=JSONTokener(raw).nextValue();require(text is String);store.importLegacy(JSONObject(text)) };close()}
            }
        }
    }
    handler.postDelayed({close()},6000)
    web.loadDataWithBaseURL("https://localhost/","<html><body></body></html>","text/html","UTF-8",null)
}
