package com.stuassistant.app.nativeapp

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import org.json.JSONObject

@Composable fun NativeDataImage(data:String,label:String,onClick:(()->Unit)?=null) {
    val bitmap=remember(data) {runCatching {val raw=data.substringAfter(',');val bytes=Base64.decode(raw,Base64.DEFAULT);BitmapFactory.decodeByteArray(bytes,0,bytes.size)?.asImageBitmap()}.getOrNull()}
    Box(Modifier.heightIn(min=48.dp).then(if(onClick!=null)Modifier.clickable(onClick=onClick).semantics{contentDescription=label}else Modifier)) {
        if(bitmap!=null)Image(bitmap,label,modifier=Modifier.heightIn(min=48.dp,max=180.dp))
        else NText("图像没有读出来，点一下重新读取",14,muted=true)
    }
}

private fun medError(error:Throwable):String=when((error as? NativeApiError)?.code) {
    "bad_captcha"->"验证码不对或已经过期了，换一张再试一次。"
    "cas_captcha"->"这次统一认证要验证码，切到「教务本地口令」那条路再试。"
    "bad_credentials"->"学校统一认证说学号或密码不对，就是你平时在浏览器里登录用的那组。"
    "med_unreachable"->"连不上医学院教务系统，可能是校园网限制，换个网络再试。"
    "med_expired"->"医学院的登录过期了，需要重新输一次账号密码。"
    "timeout"->"医学院那边太久没回话，稍等一下再试。"
    "network"->"网络断了，检查一下再试。"
    "not_linked"->"还没连过医学院账号，先把账号密码填上。"
    else->if(error is NativeApiError) {if(error.code.startsWith("http_"))"服务器出了点问题，稍后再试。"else error.detail}else"出了点意外，再试一次。"
}

@Composable fun NativeMedicalScreen(store:NativeStore,s:NativeState,onNavigate:(String)->Unit) {
    val semester=remember { (AcademicCalendar.position()?.term?:AcademicCalendar.currentOrNext(AcademicClock.moment().date)).id }
    val scope=rememberCoroutineScope()
    var status by remember {mutableStateOf<JSONObject?>(null)}
    var checking by remember {mutableStateOf(true)}
    var mode by remember {mutableStateOf("sso")}
    var username by remember {mutableStateOf("")}
    var password by remember {mutableStateOf("")}
    var code by remember {mutableStateOf("")}
    var captcha by remember {mutableStateOf<JSONObject?>(null)}
    var loadingImage by remember {mutableStateOf(false)}
    var captchaRequest by remember {mutableIntStateOf(0)}
    var busy by remember {mutableStateOf<String?>(null)}
    var problem by remember {mutableStateOf<String?>(null)}
    var done by remember {mutableStateOf<String?>(null)}
    suspend fun refreshCaptcha() {
        if(loadingImage)return
        val request=++captchaRequest
        loadingImage=true;captcha=null;code=""
        try {val result=store.request("/v1/med/captcha");if(request==captchaRequest)captcha=result}
        catch(e:CancellationException){throw e}catch(e:Exception){if(request==captchaRequest)problem=medError(e)}finally{if(request==captchaRequest)loadingImage=false}
    }
    suspend fun sync() {
        busy="sync";problem=null;done=null
        try { val res=store.request("/v1/med/sync","POST",JSONObject().put("semester",semester));store.reloadAfterMutation();done="已同步 ${res.optString("semester")}：${res.optInt("courses")} 门课、${res.optInt("meetings")} 个上课时间。" }
        catch(e:CancellationException){throw e}catch(e:Exception){problem=medError(e)}finally{busy=null}
    }
    LaunchedEffect(store,mode) {
        try {status=store.request("/v1/med/status");if(status?.optBoolean("linked")!=true&&mode=="local")refreshCaptcha()}
        catch(e:CancellationException){throw e}catch(_:Exception){status=JSONObject().put("linked",false);refreshCaptcha()}
        finally{checking=false}
    }
    NPage("连接医学院教务","医学院 · 自动同步",actions={NButton("返回课表",{onNavigate("week")},haptic=NativeButtonHaptic.Navigation)}) {
        NText("连上以后，课表、成绩、考试都会自己同步过来，不用再一门一门录。",14,muted=true)
        if(checking)NCard {NText("正在看这个账号连过没有…",14,muted=true)}
        else if(status?.optBoolean("linked")==true)NCard {
            NText("✓ 已经连上了",18,true)
            NText(listOf(status?.optString("name").orEmpty(),status?.optString("username").orEmpty()).filter{it.isNotBlank()}.joinToString(" · "),14,muted=true)
            NText("存下来的只是一个会话凭证，密码没有留下。凭证过期时会提示你重新连一次。",14,muted=true)
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                NButton(if(busy=="sync")"正在同步…"else"立即同步",{scope.launch {sync()}},busy==null,true,haptic=NativeButtonHaptic.Submit)
                NButton(if(busy=="unlink")"正在解除…"else"解除绑定",{scope.launch {busy="unlink";problem=null;done=null;try {store.request("/v1/med/session","DELETE");status=JSONObject().put("linked",false);done="已经解除绑定，服务端那份登录凭证删掉了。";refreshCaptcha()}catch(e:CancellationException){throw e}catch(e:Exception){problem=medError(e)}finally{busy=null}}},busy==null,haptic=NativeButtonHaptic.Destructive)
            }
        } else NCard {
            NText("医学院教务系统（jwgl.med.stu.edu.cn）和主校区不是一套，登录要填图形验证码。",14,muted=true)
            NField("学号 / 账号",username,{username=it})
            NField("密码",password,{password=it},password=true)
            NText("只用这一次换会话凭证，用完就丢，不保存。",14,muted=true)
            NButton(if(mode=="sso")"统一认证进不去？换教务本地口令"else"改用学校统一认证",{mode=if(mode=="sso")"local"else"sso";problem=null},busy==null,haptic=NativeButtonHaptic.Utility)
            if(mode=="local") {
                NText("图形验证码",14,true)
                Row(Modifier.fillMaxWidth(),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.weight(1f)){NField("",code,{code=it},placeholder="照着右边图片敲")}
                    Box(Modifier.widthIn(max=140.dp)) {if(captcha!=null&&!loadingImage)NativeDataImage(captcha!!.optString("image"),"图形验证码，点一下换一张") {scope.launch {refreshCaptcha()}}
                    else NButton(if(loadingImage)"读取中…"else"点这里取图",{scope.launch {refreshCaptcha()}},!loadingImage,haptic=NativeButtonHaptic.Utility)}
                }
                NText("验证码一两分钟就过期，停留太久就点图片换一张。",14,muted=true)
            }
            NButton(if(busy=="login")"正在连接…"else"连接并同步",{
                when {mode=="local"&&(captcha==null||loadingImage)->{problem="验证码还没取到，点一下图片换一张。"};username.trim().isEmpty()||password.isEmpty()->{problem="账号和密码都要填。"};mode=="local"&&code.trim().length<3->{problem="验证码看着不像对的，照着图片再敲一遍。"};else->scope.launch {
                    busy="login";problem=null;done=null
                    try {val res=store.request("/v1/med/login","POST",JSONObject().put("mode",mode).put("username",username.trim()).put("password",password).put("verify_code",if(mode=="local")code.trim()else"").put("yzm_code",if(mode=="local")captcha?.optString("yzm_code").orEmpty()else""));password="";status=JSONObject().put("linked",true).put("username",username.trim()).put("name",res.optString("name"));sync()}
                    catch(e:CancellationException){throw e}catch(e:Exception){problem=medError(e);refreshCaptcha()}finally{busy=null}
                }}
            },busy==null,true,Modifier.fillMaxWidth(),haptic=NativeButtonHaptic.Submit)
        }
        NError(problem)
        if(done!=null)NText("✓ $done",14)
        NText("医学院教务系统和主校区不是一家厂商，接口也是另外一套。连不上、或者密码在那边改过，就先用「手动导入课表」把课录进来，功能一样。",14,muted=true)
    }
}
