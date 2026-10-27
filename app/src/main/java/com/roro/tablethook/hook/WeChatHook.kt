package com.roro.tablethook.hook

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import com.roro.tablethook.util.HookUtils
import io.github.libxposed.api.XposedInterface
import org.json.JSONArray
import org.json.JSONObject
import org.luckypray.dexkit.DexKitBridge
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import kotlin.coroutines.Continuation

/**
 * 微信平板登录。
 *
 * 三件事：
 * 1. `inTabletEnv` 判定方法恒真 + 副设备登录资格恒真（关键，缺一不可）
 * 2. 折叠屏白名单 `com.tencent.mm.ui.ok.R()` 恒真
 * 3. 首次进入密码登录页时改道到「选择登录方式」，方便走扫码 / 平板登录
 *
 * DexKit 定位结果会缓存到目标应用私有目录 `files/tablethook_wechat_cache.json`，
 * 下次启动直接反射校验后挂钩：命中就完全不用加载 DexKit（少一次 dex 全量扫描，
 * 目标进程的 `/proc/self/maps` 里也不会出现 libdexkit.so）。
 * 缓存校验不通过（微信升级导致类名/签名变化）会自动回落 DexKit 重搜并覆盖缓存。
 */
internal class WeChatHook(
    private val xposed: XposedInterface
) {
    fun install(bridge: DexKitBridge, classLoader: ClassLoader, appDataDir: String?) {
        HookUtils.logi(
            "wechat install start dataDir=$appDataDir loader=${classLoader.javaClass.name}"
        )
        if (appDataDir != null && tryCachedHook(classLoader, appDataDir)) {
            HookUtils.logi("wechat cache HIT hook installed")
            return
        }
        HookUtils.logi("wechat cache MISS dexkit start")

        val keywordMatches = bridge.findMethod {
            matcher {
                usingStrings(TABLET_ENV_KEYWORD)
            }
        }
        val environmentMatches = keywordMatches.filter { method ->
            Modifier.isPublic(method.modifiers) &&
                Modifier.isStatic(method.modifiers) &&
                method.returnTypeName == HookUtils.BOOLEAN_TYPE
        }
        xposed.log(
            Log.INFO,
            HookUtils.TAG,
            "DexKit 微信平板判定候选：关键词=${keywordMatches.size}，" +
                "签名=${environmentMatches.size}"
        )
        HookUtils.logi(
            "wechat dexkit env candidates: keyword=${keywordMatches.size} " +
                "signature=${environmentMatches.size}"
        )
        val tabletEnvironmentMethod = HookUtils.requireSingle(
            environmentMatches,
            "微信平板判定方法"
        ).getMethodInstance(classLoader)

        val eligibilityKeywordMatches = bridge.findMethod {
            matcher {
                usingEqStrings(LOGIN_AS_PAD_CGI_PATH)
            }
        }
        val eligibilityMatches = eligibilityKeywordMatches.filter { method ->
            Modifier.isPublic(method.modifiers) &&
                !Modifier.isStatic(method.modifiers) &&
                method.returnTypeName == Any::class.java.name &&
                method.paramTypeNames == listOf(
                    String::class.java.name,
                    String::class.java.name,
                    Continuation::class.java.name
                )
        }
        xposed.log(
            Log.INFO,
            HookUtils.TAG,
            "DexKit 微信副设备登录资格候选：" +
                "关键词=${eligibilityKeywordMatches.size}，签名=${eligibilityMatches.size}"
        )
        HookUtils.logi(
            "wechat dexkit elig candidates: keyword=${eligibilityKeywordMatches.size} " +
                "signature=${eligibilityMatches.size}"
        )
        val loginAsPadEligibilityMethod = HookUtils.requireSingle(
            eligibilityMatches,
            "微信副设备登录资格方法"
        ).getMethodInstance(classLoader)

        // 打一条类的定义 loader：Tinker 场景下这里应该是补丁 loader，
        // 如果显示的是 base.apk 的 PathClassLoader，说明拿到了旧代码、hook 不会生效。
        HookUtils.logi(
            "wechat env method=${tabletEnvironmentMethod.declaringClass.name}" +
                "#${tabletEnvironmentMethod.name}" +
                " definedBy=${tabletEnvironmentMethod.declaringClass.classLoader?.javaClass?.name}"
        )

        hookBooleanMethod(tabletEnvironmentMethod, TABLET_ENVIRONMENT_HOOK_ID)
        hookBooleanMethod(loginAsPadEligibilityMethod, LOGIN_AS_PAD_ELIGIBILITY_HOOK_ID)
        hookFoldWhitelist(classLoader)
        hookPasswordFirstRedirect(classLoader)

        xposed.log(Log.INFO, HookUtils.TAG, "微信平板登录模式已启用")
        HookUtils.logi("wechat dexkit hook installed")

        if (appDataDir != null) {
            try {
                saveCache(appDataDir, tabletEnvironmentMethod, loginAsPadEligibilityMethod)
                HookUtils.logi("wechat cache saved")
            } catch (throwable: Throwable) {
                HookUtils.logi("wechat cache save FAILED: ${throwable.message}")
                xposed.log(
                    Log.WARN,
                    HookUtils.TAG,
                    "微信平板缓存写入失败（不影响本次挂钩）",
                    throwable
                )
            }
        }
    }

    /**
     * 用上次算好的定位结果直接挂钩。
     *
     * 每一步都做签名校验：微信升级后如果方法被改名/改签名，这里会抛异常，
     * 由外层吞掉并回落 DexKit，不会拿错方法乱 hook。
     */
    internal fun tryCachedHook(classLoader: ClassLoader, appDataDir: String): Boolean {
        val cacheFile = cacheFile(appDataDir)
        if (!cacheFile.isFile) {
            return false
        }
        try {
            val json = JSONObject(cacheFile.readText())

            val env = resolveCachedMethod(classLoader, json.getJSONObject(KEY_ENV))
            check(
                Modifier.isPublic(env.modifiers) && Modifier.isStatic(env.modifiers)
            ) { "缓存平板判定方法不是公开静态方法" }
            check(env.returnType.name == HookUtils.BOOLEAN_TYPE) { "缓存平板判定返回类型已变化" }

            val eligibility = resolveCachedMethod(classLoader, json.getJSONObject(KEY_ELIG))
            check(
                Modifier.isPublic(eligibility.modifiers) && !Modifier.isStatic(eligibility.modifiers)
            ) { "缓存登录资格方法不是公开实例方法" }
            check(eligibility.returnType.name == Any::class.java.name) {
                "缓存登录资格返回类型已变化"
            }
            check(
                HookUtils.methodSignatures(eligibility) == listOf(
                    String::class.java.name,
                    String::class.java.name,
                    Continuation::class.java.name
                )
            ) { "缓存登录资格参数已变化" }

            env.isAccessible = true
            eligibility.isAccessible = true
            HookUtils.logi(
                "wechat cache env=${env.declaringClass.name}#${env.name}" +
                    " definedBy=${env.declaringClass.classLoader?.javaClass?.name}"
            )
            hookBooleanMethod(env, TABLET_ENVIRONMENT_HOOK_ID)
            hookBooleanMethod(eligibility, LOGIN_AS_PAD_ELIGIBILITY_HOOK_ID)
            hookFoldWhitelist(classLoader)
            hookPasswordFirstRedirect(classLoader)
            xposed.log(Log.INFO, HookUtils.TAG, "微信平板登录模式已启用（缓存）")
            return true
        } catch (throwable: Throwable) {
            HookUtils.logi("wechat cache invalid: ${throwable.message}")
            xposed.log(
                Log.WARN,
                HookUtils.TAG,
                "微信平板缓存失效，回落 DexKit 重搜",
                throwable
            )
            return false
        }
    }

    private fun hookBooleanMethod(method: Method, hookId: String) {
        HookUtils.hookBooleanMethod(xposed, method, hookId)
    }

    /**
     * 折叠屏白名单：`com.tencent.mm.ui.ok.R()` 恒真。
     *
     * 失败只影响折叠屏适配，不阻断主链路。
     */
    private fun hookFoldWhitelist(classLoader: ClassLoader): Boolean {
        return try {
            val cls = Class.forName(FOLD_WHITELIST_CLASS, false, classLoader)
            val method = cls.declaredMethods.firstOrNull { candidate ->
                candidate.name == FOLD_WHITELIST_METHOD && candidate.parameterTypes.isEmpty()
            } ?: error("折叠屏白名单方法未找到：$FOLD_WHITELIST_CLASS.$FOLD_WHITELIST_METHOD")
            check(Modifier.isPublic(method.modifiers) && Modifier.isStatic(method.modifiers)) {
                "折叠屏白名单方法不是公开静态方法"
            }
            check(method.returnType.name == HookUtils.BOOLEAN_TYPE) {
                "折叠屏白名单返回类型已变化"
            }
            hookBooleanMethod(method, FOLD_WHITELIST_HOOK_ID)
            HookUtils.logi("wechat R hook installed")
            true
        } catch (throwable: Throwable) {
            HookUtils.logi("wechat R hook FAILED: ${throwable.message}")
            xposed.log(Log.WARN, HookUtils.TAG, "微信折叠屏白名单钩子失败（不影响已有钩子）", throwable)
            false
        }
    }

    /**
     * 密码登录页改道：`LoginPasswordUI.onCreate(Bundle)` 执行后，首次把用户
     * 引导到「选择登录方式」（`LoginSelectUI`），这样才有机会走扫码 / 平板登录，
     * 而不是直接落到密码输入框。
     *
     * 每个进程只改道一次，避免返回时被反复弹走。
     */
    private fun hookPasswordFirstRedirect(classLoader: ClassLoader): Boolean {
        return try {
            val cls = Class.forName(PASSWORD_CLASS, false, classLoader)
            val method = cls.declaredMethods.firstOrNull { candidate ->
                candidate.name == PASSWORD_METHOD &&
                    HookUtils.methodSignatures(candidate) == listOf(Bundle::class.java.name)
            } ?: error("密码屏改道方法未找到：$PASSWORD_CLASS.$PASSWORD_METHOD")
            check(method.returnType.name == HookUtils.VOID_TYPE) { "密码屏改道方法返回类型已变化" }

            method.isAccessible = true
            xposed.hook(method)
                .setId(PASSWORD_HOOK_ID)
                .intercept { chain ->
                    HookUtils.logi("wechat password intercept fired")
                    val result = chain.proceed()
                    runCatching {
                        if (!passwordRedirectDone) {
                            passwordRedirectDone = true
                            val context = currentApplicationContext()
                            val intent = Intent().apply {
                                setClassName(context, LOGIN_SELECT_UI)
                                putExtra(EXTRA_MOBILE_INPUT_PURPOSE, 1)
                                addFlags(
                                    Intent.FLAG_ACTIVITY_NEW_TASK or
                                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                                )
                            }
                            context.startActivity(intent)
                            HookUtils.logi("wechat password redirected to LoginSelectUI")
                        }
                    }.onFailure {
                        HookUtils.logi("wechat password redirect FAILED: ${it.message}")
                    }
                    result
                }
            HookUtils.logi("wechat password hook installed")
            true
        } catch (throwable: Throwable) {
            HookUtils.logi("wechat password hook FAILED: ${throwable.message}")
            xposed.log(Log.WARN, HookUtils.TAG, "微信密码屏改道钩子失败（不影响已有钩子）", throwable)
            false
        }
    }

    private fun currentApplicationContext(): Context {
        val application = Class.forName("android.app.ActivityThread")
            .getMethod("currentApplication")
            .invoke(null)
            ?: error("ActivityThread.currentApplication() 返回 null")
        return application as Context
    }

    // ---------------- 缓存 ----------------

    private fun cacheFile(appDataDir: String): File =
        File(File(appDataDir, "files"), CACHE_FILE_NAME)

    private fun saveCache(appDataDir: String, env: Method, eligibility: Method) {
        val dir = File(appDataDir, "files")
        if (!dir.isDirectory) {
            dir.mkdirs()
        }
        val json = JSONObject()
            .put(KEY_ENV, methodJson(env))
            .put(KEY_ELIG, methodJson(eligibility))
        val target = File(dir, CACHE_FILE_NAME)
        val temp = File(dir, "$CACHE_FILE_NAME.tmp")
        temp.writeText(json.toString())
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
    }

    private fun methodJson(method: Method): JSONObject = JSONObject()
        .put(KEY_CLASS, method.declaringClass.name)
        .put(KEY_METHOD, method.name)
        .put(KEY_PARAMS, JSONArray(HookUtils.methodSignatures(method)))
        .put(KEY_RETURN_TYPE, method.returnType.name)

    private fun resolveCachedMethod(classLoader: ClassLoader, json: JSONObject): Method {
        val className = json.getString(KEY_CLASS)
        val methodName = json.getString(KEY_METHOD)
        val params = json.getJSONArray(KEY_PARAMS)
        val paramTypeNames = List(params.length()) { params.getString(it) }
        val returnTypeName = json.getString(KEY_RETURN_TYPE)

        val cls = Class.forName(className, false, classLoader)
        val method = cls.declaredMethods.firstOrNull { candidate ->
            candidate.name == methodName &&
                HookUtils.methodSignatures(candidate) == paramTypeNames
        } ?: error("缓存方法未找到：$className.$methodName")
        check(method.returnType.name == returnTypeName) {
            "缓存返回类型已变化：$className.$methodName"
        }
        return method
    }

    companion object {
        const val PACKAGE_NAME = "com.tencent.mm"
        const val PROCESS_NAME = PACKAGE_NAME

        private const val TABLET_ENV_KEYWORD = "inTabletEnv"
        private const val LOGIN_AS_PAD_CGI_PATH = "/cgi-bin/micromsg-bin/checkloginaspad"
        private const val TABLET_ENVIRONMENT_HOOK_ID = "wechat_tablet_environment"
        private const val LOGIN_AS_PAD_ELIGIBILITY_HOOK_ID =
            "wechat_login_as_pad_eligibility"

        private const val FOLD_WHITELIST_CLASS = "com.tencent.mm.ui.ok"
        private const val FOLD_WHITELIST_METHOD = "R"
        private const val FOLD_WHITELIST_HOOK_ID = "wechat_fold_whitelist"

        private const val PASSWORD_CLASS = "com.tencent.mm.plugin.account.ui.LoginPasswordUI"
        private const val PASSWORD_METHOD = "onCreate"
        private const val PASSWORD_HOOK_ID = "wechat_password_first_redirect"
        private const val LOGIN_SELECT_UI = "com.tencent.mm.plugin.account.ui.LoginSelectUI"
        private const val EXTRA_MOBILE_INPUT_PURPOSE = "mobile_input_purpose"

        @Volatile
        private var passwordRedirectDone = false

        private const val CACHE_FILE_NAME = "tablethook_wechat_cache.json"
        private const val KEY_ENV = "env"
        private const val KEY_ELIG = "elig"
        private const val KEY_CLASS = "class"
        private const val KEY_METHOD = "method"
        private const val KEY_PARAMS = "params"
        private const val KEY_RETURN_TYPE = "returnType"
    }
}
