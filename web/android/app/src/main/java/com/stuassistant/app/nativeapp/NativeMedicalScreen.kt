package com.stuassistant.app.nativeapp

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.stuassistant.app.nativeapp.local.MedCaptcha
import kotlinx.coroutines.*
import org.json.JSONObject

@Composable fun NativeDataImage(data:String,label:String,onClick:(()->Unit)?=null) {
    val bitmap=remember(data) {runCatching {val raw=data.substringAfter(',');val bytes=Base64.decode(raw,Base64.DEFAULT);BitmapFactory.decodeByteArray(bytes,0,bytes.size)?.asImageBitmap()}.getOrNull()}
    Box(Modifier.heightIn(min=48.dp).then(if(onClick!=null)Modifier.clickable(onClick=onClick).semantics{contentDescription=label}else Modifier)) {
        if(bitmap!=null)Image(bitmap,label,modifier=Modifier.heightIn(min=48.dp,max=180.dp))
        else NText("图像没有读出来，点一下重新读取",14,muted=true)
    }
}

private fun medError(error:Throwable):String=formatLocalError(error,scope="med")

@Composable fun NativeMedicalScreen(store:NativeStore,s:NativeState,onNavigate:(String)->Unit) {
    val semester=remember { (AcademicCalendar.position()?.term?:AcademicCalendar.currentOrNext(AcademicClock.moment().date)).id }
    val scope=rememberCoroutineScope()
    var status by remember {mutableStateOf<JSONObject?>(null)}
    var checking by remember {mutableStateOf(true)}
    var mode by remember {mutableStateOf("sso")}
    var username by remember {mutableStateOf("")}
    var password by remember {mutableStateOf("")}
    var code by remember {mutableStateOf("")}
    var captcha by remember {mutableStateOf<MedCaptcha?>(null)}
    var loadingImage by remember {mutableStateOf(false)}
    var captchaRequest by remember {mutableIntStateOf(0)}
    var busy by remember {mutableStateOf<String?>(null)}
    var problem by remember {mutableStateOf<String?>(null)}
    var done by remember {mutableStateOf<String?>(null)}

    suspend fun refreshCaptcha() {
        if(loadingImage)return
        val request=++captchaRequest
        loadingImage=true;captcha=null;code=""
        try {
            val result=store.fetchMedCaptchaLocal()
            if(request==captchaRequest) captcha=result
        } catch(e:CancellationException){throw e}
        catch(e:Exception){if(request==captchaRequest) problem=medError(e)}
        finally{if(request==captchaRequest) loadingImage=false}
    }

    suspend fun sync() {
        busy="sync";problem=null;done=null
        try {
            val res=store.syncMedLocal(semester)
            done="已本机同步 ${res.optString("semester", semester)}：${res.optInt("courses")} 门课、${res.optInt("meetings")} 个上课时间。"
        } catch(e:CancellationException){throw e}
        catch(e:Exception){problem=medError(e)}
        finally{busy=null}
    }

    LaunchedEffect(store,mode) {
        try {
            status=store.medStatusLocal()
            if(status?.optBoolean("linked")!=true && mode=="local") refreshCaptcha()
        } catch(e:CancellationException){throw e}
        catch(_:Exception){status=JSONObject().put("linked",false)}
        finally{checking=false}
    }

    BackHandler {onNavigate("me")}
    NPage("连接医学院教务","医学院 · 本机直连",actions={NButton("‹ 我的",{onNavigate("me")},haptic=NativeButtonHaptic.Navigation)}) {
        NText("本机直连医学院教务（jwgl.med.stu.edu.cn / sso.med.stu.edu.cn），账号密码不经过开发者服务器；同步后的课表、成绩与考试仅加密保存在本机。",14,muted=true)
        if(checking) NCard {NText("正在检查本机医学院连接状态…",14,muted=true)}
        else if(status?.optBoolean("linked")==true) NCard {
            NText("✓ 已经在本机连上了",18,true)
            NText(listOf(status?.optString("name").orEmpty(),status?.optString("username").orEmpty()).filter{it.isNotBlank()}.joinToString(" · "),14,muted=true)
            NText("本机 Android Keystore 仅加密保留医学院会话凭证，未保存密码。凭证过期时会提示你重新连一次。",14,muted=true)
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                NButton(if(busy=="sync")"正在同步…"else"立即同步",{scope.launch {sync()}},busy==null,true,haptic=NativeButtonHaptic.Submit)
                NButton(
                    if(busy=="unlink")"正在解除…"else"解除绑定",
                    {
                        scope.launch {
                            busy="unlink";problem=null;done=null
                            try {
                                store.unlinkMedLocal()
                                status=JSONObject().put("linked",false)
                                done="已经解除绑定，本机保存的医学院登录凭证已删除。"
                                if(mode=="local") refreshCaptcha()
                            } catch(e:CancellationException){throw e}
                            catch(e:Exception){problem=medError(e)}
                            finally{busy=null}
                        }
                    },
                    busy==null,
                    haptic=NativeButtonHaptic.Destructive,
                )
            }
        } else NCard {
            NText("医学院教务系统（jwgl.med.stu.edu.cn）与主校区相互独立；支持统一认证（免验证码）和教务本地口令（需图形验证码）两种本机直连方式。",14,muted=true)
            NField("学号 / 账号",username,{username=it},enabled=busy==null)
            NField("密码",password,{password=it},password=true,placeholder=if(mode=="sso")"学校统一认证密码"else"医学院教务本地密码",enabled=busy==null)
            NText("密码仅从本机直连医学院官网换取会话凭证，用后即丢，不保存且不上传开发者服务器。",14,muted=true)
            NButton(
                if(mode=="sso")"统一认证进不去？换教务本地口令"else"改用学校统一认证",
                {
                    mode=if(mode=="sso")"local"else"sso"
                    problem=null
                },
                busy==null,
                haptic=NativeButtonHaptic.Utility,
            )
            if(mode=="local") {
                NText("图形验证码",14,true)
                Row(Modifier.fillMaxWidth(),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.weight(1f)){NField("",code,{code=it},placeholder="照着右边图片敲",enabled=busy==null)}
                    Box(Modifier.widthIn(max=140.dp)) {
                        if(captcha!=null&&!loadingImage) NativeDataImage(captcha!!.imageDataUrl,"图形验证码，点一下换一张") {scope.launch {refreshCaptcha()}}
                        else NButton(if(loadingImage)"读取中…"else"点这里取图",{scope.launch {refreshCaptcha()}},!loadingImage,haptic=NativeButtonHaptic.Utility)
                    }
                }
                NText("验证码一两分钟就过期，停留太久就点图片换一张。",14,muted=true)
            }
            NButton(
                if(busy=="login")"正在本机直连…"else"连接并同步",
                {
                    when {
                        mode=="local"&&(captcha==null||loadingImage)->{problem="验证码还没取到，点一下图片换一张。"}
                        username.trim().isEmpty()||password.isEmpty()->{problem="账号和密码都要填。"}
                        mode=="local"&&code.trim().length<3->{problem="验证码看着不像对的，照着图片再敲一遍。"}
                        else->scope.launch {
                            busy="login";problem=null;done=null
                            try {
                                val res=store.bindMedLocal(
                                    mode=mode,
                                    username=username.trim(),
                                    password=password,
                                    verifyCode=if(mode=="local")code.trim()else"",
                                    yzmCode=if(mode=="local")captcha?.yzmCode.orEmpty()else"",
                                    semester=semester,
                                )
                                password=""
                                status=JSONObject()
                                    .put("linked",true)
                                    .put("username",res.optString("username",username.trim()))
                                    .put("name",res.optString("name"))
                                val summary=res.optJSONObject("summary")
                                done="已本机同步 ${summary?.optString("semester",semester) ?: semester}：${summary?.optInt("courses") ?: 0} 门课、${summary?.optInt("meetings") ?: 0} 个上课时间。"
                            } catch(e:CancellationException){throw e}
                            catch(e:Exception){
                                problem=medError(e)
                                if(mode=="local") refreshCaptcha()
                            } finally{busy=null}
                        }
                    }
                },
                busy==null,
                true,
                Modifier.fillMaxWidth(),
                haptic=NativeButtonHaptic.Submit,
            )
        }
        NError(problem)
        if(done!=null) NText("✓ $done",14)
        NText("医学院教务系统和主校区不是一家厂商。如果校园网受限或密码改过，也可以先用「手动导入课表」把课录进来，完全离线可用。",14,muted=true)
    }
}
