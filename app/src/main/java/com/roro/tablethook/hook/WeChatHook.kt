package com.roro.tablethook.hook

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

internal class WeChatHook(
    private val xposed: XposedInterface
) {
    fun install(bridge: DexKitBridge, classLoader: ClassLoader, appDataDir: String?) {
        // 快速通道：与 QQ 同一个毛病——微信主进程重启时 DexKit 全量搜索慢，
        // 平板判定读到之前就按手机显示。首次解析成功后把两个目标方法缓存到
        // 微信自身 files 目录，后续启动直接反射挂钩（毫秒级），微信更新导致
        // 缓存失效时自动回落 DexKit 重搜。
        HookUtils.logi("wechat install start dataDir=$appDataDir")
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

        HookUtils.hookBooleanMethod(
            xposed,
            tabletEnvironmentMethod,
            TABLET_ENVIRONMENT_HOOK_ID
        )
        HookUtils.hookBooleanMethod(
            xposed,
            loginAsPadEligibilityMethod,
            LOGIN_AS_PAD_ELIGIBILITY_HOOK_ID
        )
        xposed.log(Log.INFO, HookUtils.TAG, "微信平板登录模式已启用")
        HookUtils.logi("wechat dexkit hook installed")

        if (appDataDir != null) {
            try {
                saveCache(appDataDir, tabletEnvironmentMethod, loginAsPadEligibilityMethod)
                HookUtils.logi("wechat cache saved")
            } catch (throwable: Throwable) {
                HookUtils.logi(
                    "wechat cache save FAILED: " + throwable.stackTraceToString().take(1000)
                )
                xposed.log(
                    Log.WARN,
                    HookUtils.TAG,
                    "微信平板缓存写入失败（不影响本次挂钩）",
                    throwable
                )
            }
        }
    }

    private fun cacheFile(appDataDir: String): File {
        return File(File(appDataDir, "files"), CACHE_FILE_NAME)
    }

    /**
     * 缓存快速通道（不依赖 DexKit 桥）：入口在建桥之前先调它，
     * 建桥要数秒，微信启动读平板判定可能抢在这之前，缓存命中就直接挂钩返回。
     */
    internal fun tryCachedHook(classLoader: ClassLoader, appDataDir: String): Boolean {
        val file = cacheFile(appDataDir)
        if (!file.isFile) {
            return false
        }
        try {
            val json = JSONObject(file.readText())
            val env = resolveCachedMethod(classLoader, json.getJSONObject(KEY_ENV))
            check(Modifier.isPublic(env.modifiers) && Modifier.isStatic(env.modifiers)) {
                "缓存平板判定方法不是公开静态方法"
            }
            check(env.returnType.name == HookUtils.BOOLEAN_TYPE) {
                "缓存平板判定返回类型已变化"
            }
            val elig = resolveCachedMethod(classLoader, json.getJSONObject(KEY_ELIG))
            check(Modifier.isPublic(elig.modifiers) && !Modifier.isStatic(elig.modifiers)) {
                "缓存登录资格方法不是公开实例方法"
            }
            check(elig.returnType.name == Any::class.java.name) {
                "缓存登录资格返回类型已变化"
            }
            check(
                elig.parameterTypes.map { it.name } == listOf(
                    String::class.java.name,
                    String::class.java.name,
                    Continuation::class.java.name
                )
            ) {
                "缓存登录资格参数已变化"
            }
            env.isAccessible = true
            elig.isAccessible = true
            HookUtils.hookBooleanMethod(xposed, env, TABLET_ENVIRONMENT_HOOK_ID)
            HookUtils.hookBooleanMethod(xposed, elig, LOGIN_AS_PAD_ELIGIBILITY_HOOK_ID)
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

    private fun resolveCachedMethod(classLoader: ClassLoader, json: JSONObject): Method {
        val className = json.getString(KEY_CLASS)
        val methodName = json.getString(KEY_METHOD)
        val paramTypeNames = json.getJSONArray(KEY_PARAMS).let { array ->
            List(array.length()) { index -> array.getString(index) }
        }
        val returnTypeName = json.getString(KEY_RETURN_TYPE)
        val clazz = Class.forName(className, false, classLoader)
        val method = clazz.declaredMethods.firstOrNull { candidate ->
            candidate.name == methodName &&
                candidate.parameterTypes.map { it.name } == paramTypeNames
        } ?: error("缓存方法未找到：$className.$methodName")
        check(method.returnType.name == returnTypeName) {
            "缓存返回类型已变化：$className.$methodName"
        }
        return method
    }

    private fun saveCache(appDataDir: String, env: Method, elig: Method) {
        val dir = File(appDataDir, "files")
        if (!dir.isDirectory) {
            dir.mkdirs()
        }
        val json = JSONObject()
            .put(KEY_ENV, methodJson(env))
            .put(KEY_ELIG, methodJson(elig))
        val file = File(dir, CACHE_FILE_NAME)
        val temp = File(dir, "$CACHE_FILE_NAME.tmp")
        temp.writeText(json.toString())
        if (!temp.renameTo(file)) {
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
    }

    private fun methodJson(method: Method): JSONObject {
        return JSONObject()
            .put(KEY_CLASS, method.declaringClass.name)
            .put(KEY_METHOD, method.name)
            .put(
                KEY_PARAMS,
                JSONArray(method.parameterTypes.map { it.name })
            )
            .put(KEY_RETURN_TYPE, method.returnType.name)
    }

    companion object {
        const val PACKAGE_NAME = "com.tencent.mm"
        const val PROCESS_NAME = PACKAGE_NAME

        private const val TABLET_ENV_KEYWORD = "inTabletEnv"
        private const val LOGIN_AS_PAD_CGI_PATH = "/cgi-bin/micromsg-bin/checkloginaspad"
        private const val TABLET_ENVIRONMENT_HOOK_ID = "wechat_tablet_environment"
        private const val LOGIN_AS_PAD_ELIGIBILITY_HOOK_ID =
            "wechat_login_as_pad_eligibility"

        private const val CACHE_FILE_NAME = "tablethook_wechat_cache.json"
        private const val KEY_ENV = "env"
        private const val KEY_ELIG = "elig"
        private const val KEY_CLASS = "class"
        private const val KEY_METHOD = "method"
        private const val KEY_PARAMS = "params"
        private const val KEY_RETURN_TYPE = "returnType"
    }
}
