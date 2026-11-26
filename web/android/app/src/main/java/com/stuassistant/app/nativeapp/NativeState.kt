package com.stuassistant.app.nativeapp

import com.stuassistant.app.nativeapp.local.LocalSchoolError
import org.json.JSONObject

/** Local-first state; personal academic/schedule data lives only in Android Keystore storage. */
data class NativeState(
    val server: String = "",
    val token: String? = null,
    val user: JSONObject? = null,
    val preview: Boolean = false,
    val sample: Boolean = false,
    val mode: String = "cached",
    val timetable: JSONObject? = null,
    val tasks: List<JSONObject> = emptyList(),
    val exams: List<JSONObject> = emptyList(),
    val grades: List<JSONObject> = emptyList(),
    val notices: List<JSONObject> = emptyList(),
    val plans: List<JSONObject> = emptyList(),
    val sources: List<JSONObject> = emptyList(),
    val planSummary: JSONObject = JSONObject(),
    val loading: Boolean = false,
    val syncing: Boolean = false,
    val error: String? = null,
    val done: Set<String> = emptySet(),
    val theme: String = "system",
    val palette: String = NATIVE_DEFAULT_PALETTE,
    val style: String = NATIVE_DEFAULT_STYLE,
    val hapticStrength: Int = 100,
    val schoolId: String = "",
    val rememberSchool: Boolean = false,
    val syncedAt: Long? = null,
    val calendarRevision: Long = 0,
    val calendarLastBatch: String? = null,
    val calendarFocusDate: String? = null,
    val onboarded: Boolean = false,
    val boundSources: Set<String> = emptySet(),
    val availableTimetableSources: List<String> = emptyList(),
    val activeTimetableSource: String? = null,
    val medLinked: Boolean = false,
    val medUsername: String = "",
    val medName: String = "",
    val manualSchool: String? = null,
    val manualSemester: String? = null,
)

class NativeApiError(
    val code: String,
    val status: Int = 0,
    val detail: String = code,
) : Exception(detail)

/**
 * Formats direct-to-school login, sync, and local storage errors into clear, actionable
 * Chinese guidance, clearly distinguishing credential errors, verification challenges,
 * off-campus/maintenance/network issues, and expired sessions without leaking raw exceptions.
 */
internal fun formatLocalError(error: Throwable, scope: String = "general"): String {
    val code = when (error) {
        is LocalSchoolError -> error.code
        is NativeApiError -> error.code
        else -> ""
    }
    val detail = when (error) {
        is LocalSchoolError -> error.detail.trim()
        is NativeApiError -> error.detail.trim()
        else -> ""
    }
    return when (code) {
        "invalid_credentials", "bad_credentials" ->
            if (scope == "med") {
                "学号或密码不正确。统一认证请使用学校门户密码；如已切换「教务本地口令」，请填写医学院教务独立密码。"
            } else {
                "学号或统一认证密码不正确。请核对输入后重试；若近期改过密码，请使用最新密码。"
            }
        "invalid_authentication_input", "invalid_input" ->
            "学号或密码格式不正确，请检查是否有多余空格后重试。"
        "authentication_identity_mismatch" ->
            "学校返回的账号身份与填写的学号不一致，请核对学号后重新连接。"
        "weak_pass" ->
            "医学院教务提示当前为初始密码或弱密码，请先在浏览器登录医学院教务修改密码后再回来连接。"
        "too_many_attempts", "rate_limited", "authentication_budget" ->
            "短时间内尝试次数过多，学校已暂时限制请求。请稍等几分钟后再试，避免账号被临时锁定。"

        "captcha_required" ->
            "学校统一认证要求输入图形验证码，无法后台自动重连。请前往「我的 → 课表与账号」手动重新连接。"
        "captcha_rejected", "bad_captcha" ->
            if (scope == "med") {
                "图形验证码不正确或已过期，请点击图片换一张后重新输入。"
            } else {
                "图形验证码不正确或已过期，请重新输入新验证码后再试。"
            }
        "cas_captcha" ->
            "医学院统一认证触发了验证码验证，请点击下方切换为「教务本地口令」登录，或稍后再试。"
        "totp_required" ->
            "学校统一认证要求输入数盾 6 位动态口令，无法后台自动重连。请前往「我的 → 课表与账号」手动重新连接。"
        "invalid_totp", "totp_rejected" ->
            "动态口令不正确或已过期，请等待数盾刷新下一组 6 位数字后再试。"
        "interaction_required" ->
            "学校认证要求额外人机验证（如滑块验证）。请先在手机浏览器打开学校统一认证完成一次登录，再回来重试。"

        "source_unavailable" ->
            "学校系统暂时不可用，可能处于夜间维护或繁忙状态。请稍后再试，本机已同步的课表仍可离线查看。"
        "med_unreachable" ->
            "本机暂时连不上医学院教务系统（jwgl.med.stu.edu.cn），可能受校外网络限制或校方维护影响。请切换校园网/流量重试，或先使用「手动导入课表」。"
        "network", "network_error" ->
            if (scope == "med") {
                "本机无法连接医学院教务系统，请检查网络或切换校园网后重试；也可先使用「手动导入课表」。"
            } else {
                "本机无法连接到学校系统，请检查网络连接；校外网络或校方夜间维护时部分页面可能暂不可达，可稍后或切换网络重试。"
            }
        "timeout", "request_timeout", "request_deadline" ->
            "学校系统响应超时，可能是网络较慢或校方繁忙。请稍后重试，已缓存的课表仍可离线查看。"
        "authentication_outcome_unknown", "authentication_unconfirmed" ->
            "未能收到学校完整的登录确认（可能因网络抖动中断）。本次未重发密码，请检查网络后重试一次。"

        "login_expired", "needs_login", "session_invalid" ->
            "教务或 MySTU 的本机登录会话已过期，当前展示的是上次同步的缓存。请点击「重新连接」重新登录。"
        "med_expired" ->
            "医学院教务的本机登录凭证已过期，请在本页重新输入账号密码连接一次。"
        "not_linked" ->
            "尚未连接医学院教务账号，请先在下方填写账号和密码完成连接。"
        "login_rejected", "login_failed" ->
            "学校拒绝了本次登录请求。建议先在手机浏览器登录一次学校系统确认账号状态正常，再回来重试。"
        "access_denied" ->
            "学校系统暂未放行本次页面访问，可能是登录会话已失效，请重新连接后再试。"

        "authentication_schema_changed", "schema_changed" ->
            "学校页面结构与当前版本预期不符（可能校方改版）。请检查应用更新，或先使用「手动导入课表」。"
        "insecure_transport", "unsafe_cookie_scope", "insecure_authentication_route",
        "unsafe_authentication_request", "insecure_password_form", "unsafe_url", "unsafe_redirect" ->
            "当前连接未通过本机安全校验（如跳转到非学校域名或异常传输），已自动中止。请确认未使用异常代理或公共 Wi-Fi 认证页后重试。"
        "cancelled" ->
            "已取消本次连接，你可以随时重新发起。"
        "no_timetable" ->
            "当前还没有可导出的课表，请先同步教务、连接医学院或手动导入课表。"
        "plan_not_found" ->
            "这条计划已经不在了，请返回刷新查看。"
        "preview", "preview_read_only" ->
            "当前为示例模式，退出示例后即可使用本机直连与保存功能。"
        "storage_error", "secure_storage" ->
            "本机加密存储读写失败，请重启应用后再试。"
        "blocked_non_public_route" ->
            "已拦截非公开服务端请求，本机个人数据不会被上传。"
        "response_too_large", "authentication_response_limit" ->
            "学校返回的数据超出安全限制，已停止读取，请稍后重试。"
        else -> {
            val readableDetail = detail.takeIf { text ->
                text.isNotBlank() &&
                    text != code &&
                    text.any { ch -> ch in '\u4E00'..'\u9FFF' } &&
                    !text.contains("Exception", ignoreCase = true) &&
                    !text.contains("java.") &&
                    !text.contains("javax.") &&
                    !text.contains("okhttp", ignoreCase = true)
            }
            readableDetail ?: if (scope == "med") {
                "医学院教务暂时未能完成同步，请检查网络或稍后重试；也可先使用「手动导入课表」。"
            } else {
                "本次操作未能完成，请检查网络或学校系统状态后重试；已缓存的课表仍可离线查看。"
            }
        }
    }
}
