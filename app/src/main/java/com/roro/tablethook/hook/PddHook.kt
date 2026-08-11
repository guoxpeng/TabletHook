package com.roro.tablethook.hook

import android.content.Context
import android.util.Log
import com.roro.tablethook.util.HookUtils
import io.github.libxposed.api.XposedInterface
import org.json.JSONObject
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Modifier

internal class PddHook(
    private val xposed: XposedInterface
) {
    @Volatile
    private var padDecisionLogged = false

    fun install(bridge: DexKitBridge, classLoader: ClassLoader) {
        val padKeywordMatches = bridge.findMethod {
            matcher {
                usingEqStrings(
                    BUILD_CHARACTERISTICS,
                    TABLET_VALUE
                )
            }
        }
        val padDecisionMatches = padKeywordMatches.filter { method ->
            Modifier.isPublic(method.modifiers) &&
                Modifier.isStatic(method.modifiers) &&
                method.returnTypeName == HookUtils.BOOLEAN_TYPE &&
                method.paramTypeNames == listOf(Context::class.java.name)
        }
        val padDecisionMethod = HookUtils.requireSingle(
            padDecisionMatches,
            "拼多多平板判定方法"
        ).getMethodInstance(classLoader)

        val requestKeywordMatches = bridge.findMethod {
            matcher {
                usingEqStrings(
                    LOGIN_SCENE_FIELD,
                    REFER_PAGE_FIELD,
                    APP_ID_FIELD
                )
            }
        }
        val requestSubmitMatches = requestKeywordMatches.filter { method ->
            !Modifier.isStatic(method.modifiers) &&
                method.returnTypeName == HookUtils.VOID_TYPE &&
                method.paramTypeNames == listOf(
                    JSONObject::class.java.name,
                    String::class.java.name
                )
        }
        val requestSubmitMethod = HookUtils.requireSingle(
            requestSubmitMatches,
            "拼多多登录请求提交方法"
        ).getMethodInstance(classLoader)

        val ticketKeywordMatches = bridge.findMethod {
            matcher {
                usingEqStrings(
                    TICKET_FIELD,
                    SECRET_KEY_FIELD
                )
            }
        }
        val ticketExchangeMatches = ticketKeywordMatches.filter { method ->
            Modifier.isPublic(method.modifiers) &&
                !Modifier.isStatic(method.modifiers) &&
                method.returnTypeName == HookUtils.VOID_TYPE &&
                method.paramTypeNames == listOf(
                    String::class.java.name,
                    String::class.java.name
                ) &&
                method.invokes.any { invoked ->
                    invoked.usingStrings.contains(AUTH_TICKET_PATH)
                }
        }
        val ticketExchangeMethod = HookUtils.requireSingle(
            ticketExchangeMatches,
            "拼多多二维码换票方法"
        ).getMethodInstance(classLoader)

        val credentialKeywordMatches = bridge.findMethod {
            matcher {
                usingEqStrings(
                    ACCESS_TOKEN_FIELD,
                    UID_FIELD,
                    UIN_FIELD,
                    LOGIN_TIME_FIELD
                )
            }
        }
        val credentialWriteMatches = credentialKeywordMatches.filter { method ->
            Modifier.isStatic(method.modifiers) &&
                method.returnTypeName == HookUtils.VOID_TYPE &&
                method.paramTypeNames == listOf(
                    String::class.java.name,
                    String::class.java.name,
                    String::class.java.name,
                    String::class.java.name,
                    HookUtils.BOOLEAN_TYPE
                )
        }
        val credentialWriteMethod = HookUtils.requireSingle(
            credentialWriteMatches,
            "拼多多登录凭证写入方法"
        ).getMethodInstance(classLoader)

        val tokenEventKeywordMatches = bridge.findMethod {
            matcher {
                usingEqStrings(TOKEN_EXPIRED_EVENT)
            }
        }
        val tokenEventMatches = tokenEventKeywordMatches.filter { method ->
            !Modifier.isStatic(method.modifiers) &&
                method.returnTypeName == HookUtils.VOID_TYPE &&
                method.paramTypeNames.size == 1
        }.sortedBy { method -> method.descriptor }
        check(tokenEventMatches.isNotEmpty()) {
            "未找到拼多多 token 失效事件接收方法"
        }
        val tokenEventMethods = tokenEventMatches.map { method ->
            method.getMethodInstance(classLoader)
        }

        xposed.log(
            Log.INFO,
            HookUtils.TAG,
            "DexKit 拼多多诊断候选：" +
                "平板关键词=${padKeywordMatches.size}，平板签名=${padDecisionMatches.size}，" +
                "请求关键词=${requestKeywordMatches.size}，请求签名=${requestSubmitMatches.size}，" +
                "换票关键词=${ticketKeywordMatches.size}，换票签名=${ticketExchangeMatches.size}，" +
                "凭证关键词=${credentialKeywordMatches.size}，" +
                "凭证签名=${credentialWriteMatches.size}，" +
                "失效事件=${tokenEventMatches.size}"
        )

        padDecisionMethod.isAccessible = true
        xposed.hook(padDecisionMethod)
            .setId(PAD_DECISION_HOOK_ID)
            .intercept {
                if (!padDecisionLogged) {
                    padDecisionLogged = true
                    xposed.log(Log.INFO, HookUtils.TAG, "拼多多平板判定已命中，返回 true")
                }
                true
            }

        ticketExchangeMethod.isAccessible = true
        xposed.hook(ticketExchangeMethod)
            .setId(TICKET_EXCHANGE_HOOK_ID)
            .intercept { chain ->
                xposed.log(
                    Log.INFO,
                    HookUtils.TAG,
                    "拼多多二维码换票开始：字段=[ticket, secret_key]"
                )
                chain.proceed()
            }

        requestSubmitMethod.isAccessible = true
        xposed.hook(requestSubmitMethod)
            .setId(LOGIN_REQUEST_HOOK_ID)
            .intercept { chain ->
                logLoginRequest(
                    chain.getArg(0) as? JSONObject,
                    chain.getArg(1) as? String
                )
                chain.proceed()
            }

        credentialWriteMethod.isAccessible = true
        xposed.hook(credentialWriteMethod)
            .setId(CREDENTIAL_WRITE_HOOK_ID)
            .intercept { chain ->
                val result = chain.proceed()
                val coreFieldsComplete = (0..2).all { index ->
                    !(chain.args.getOrNull(index) as? String).isNullOrEmpty()
                }
                xposed.log(
                    Log.INFO,
                    HookUtils.TAG,
                    "拼多多标准登录凭证已写入：" +
                        "字段=[access_token, uid, uin, login_time]，" +
                        "核心字段完整=$coreFieldsComplete"
                )
                result
            }

        tokenEventMethods.forEachIndexed { index, method ->
            method.isAccessible = true
            xposed.hook(method)
                .setId("${TOKEN_EXPIRED_HOOK_ID}_$index")
                .intercept { chain ->
                    val event = chain.args.getOrNull(0)
                    val eventName = runCatching {
                        event?.javaClass?.getField(EVENT_NAME_FIELD)?.get(event) as? String
                    }.getOrNull()
                    if (eventName == TOKEN_EXPIRED_EVENT) {
                        xposed.log(Log.WARN, HookUtils.TAG, "拼多多收到 login_token_expired 事件")
                    }
                    chain.proceed()
                }
        }
        xposed.log(Log.INFO, HookUtils.TAG, "拼多多平板登录动态诊断已启用")
    }

    private fun logLoginRequest(payload: JSONObject?, url: String?) {
        val endpoint = LOGIN_ENDPOINTS.firstOrNull { path ->
            url?.endsWith(path) == true
        } ?: return
        val fields = buildList {
            val keys = payload?.keys() ?: return@buildList
            while (keys.hasNext()) {
                add(keys.next())
            }
        }.sorted()
        xposed.log(Log.INFO, HookUtils.TAG, "拼多多登录请求：接口=$endpoint，字段=$fields")
    }

    companion object {
        const val PACKAGE_NAME = "com.xunmeng.pinduoduo"
        const val PROCESS_NAME = PACKAGE_NAME

        private const val BUILD_CHARACTERISTICS = "ro.build.characteristics"
        private const val TABLET_VALUE = "tablet"
        private const val LOGIN_SCENE_FIELD = "login_scene"
        private const val REFER_PAGE_FIELD = "refer_page_sn"
        private const val APP_ID_FIELD = "app_id"
        private const val TICKET_FIELD = "ticket"
        private const val SECRET_KEY_FIELD = "secret_key"
        private const val ACCESS_TOKEN_FIELD = "access_token"
        private const val UID_FIELD = "uid"
        private const val UIN_FIELD = "uin"
        private const val LOGIN_TIME_FIELD = "login_time"
        private const val AUTH_TICKET_PATH = "/api/sigerus/auth/ticket"
        private const val STANDARD_LOGIN_PATH = "/login"
        private const val TOKEN_EXPIRED_EVENT = "login_token_expired"
        private const val EVENT_NAME_FIELD = "name"
        private val LOGIN_ENDPOINTS = listOf(
            AUTH_TICKET_PATH,
            STANDARD_LOGIN_PATH
        )

        private const val PAD_DECISION_HOOK_ID = "pinduoduo_pad_decision"
        private const val TICKET_EXCHANGE_HOOK_ID = "pinduoduo_ticket_exchange"
        private const val LOGIN_REQUEST_HOOK_ID = "pinduoduo_login_request"
        private const val CREDENTIAL_WRITE_HOOK_ID = "pinduoduo_credential_write"
        private const val TOKEN_EXPIRED_HOOK_ID = "pinduoduo_token_expired"
    }
}
