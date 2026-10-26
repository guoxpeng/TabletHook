package com.roro.tablethook.hook

import android.content.Context
import android.content.Intent
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

private const val LOGIN_SELECT_CLASS =
    "com.tencent.mm.plugin.account.ui.LoginSelectUI"
private const val EXTRA_PURPOSE = "mobile_input_purpose"
private const val FLAG_ACTIVITY_SINGLE_TOP = 0x20000000

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
        hookFoldWhitelist(classLoader)
        hookJumpRedirect(classLoader)
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
            hookFoldWhitelist(classLoader)
            hookJumpRedirect(classLoader)
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

    /**
     * 折叠屏白名单钩子：欢迎屏登录点击分流看 ok.B() 与 ok.R()——B() 本体
     * 就是调 ok.C（模块已钩成 true），B() 为 true 后 R() 为 false 会走
     * MobileInputUI（手机号屏，无平板选项）；R() 为 true 才走 LoginSelectUI
     * （账号列表屏，elig 回调显示 phone_and_pad）。R() 方法体是
     * V/f0/d0/b0/T/k0/h0 的纯或，没有可用于 DexKit 定位的特征字符串，
     * 故按类名+方法名直接反射挂钩，并校验公开静态无参 boolean 签名。
     * 解析失败只记日志，绝不影响已有的 ok.C 与 checkloginaspad 钩子。
     * 缓存快速通道与 DexKit 路径都调它，缓存格式不动（每次启动现解析，
     * 一次 Class.forName + declaredMethods 扫描，开销可忽略）。
     */
    private fun hookFoldWhitelist(classLoader: ClassLoader): Boolean {
        try {
            val clazz = Class.forName(FOLD_WHITELIST_CLASS, false, classLoader)
            val method = clazz.declaredMethods.firstOrNull { candidate ->
                candidate.name == FOLD_WHITELIST_METHOD &&
                    candidate.parameterTypes.isEmpty()
            } ?: error("折叠屏白名单方法未找到：$FOLD_WHITELIST_CLASS.$FOLD_WHITELIST_METHOD")
            check(Modifier.isPublic(method.modifiers) && Modifier.isStatic(method.modifiers)) {
                "折叠屏白名单方法不是公开静态方法"
            }
            check(method.returnType.name == HookUtils.BOOLEAN_TYPE) {
                "折叠屏白名单返回类型已变化"
            }
            HookUtils.hookBooleanMethod(xposed, method, FOLD_WHITELIST_HOOK_ID)
            HookUtils.logi("wechat R hook installed")
            return true
        } catch (throwable: Throwable) {
            HookUtils.logi("wechat R hook FAILED: ${throwable.message}")
            xposed.log(
                Log.WARN,
                HookUtils.TAG,
                "微信折叠屏白名单钩子失败（不影响已有钩子）",
                throwable
            )
            return false
        }
    }

    /**
     * 冷启动改道钩子：实机冷启动根本不走欢迎屏 WelcomeActivity，而是
     * LauncherUI.jumpToLogin(Context) 在方法内建 intent 直达密码屏
     * LoginPasswordUI，所以光钩 R() 不够——R 只修欢迎屏的分流，冷启动
     * 这条路碰不到它。改道做法：按类名+方法名直接反射解析
     * LauncherUI.jumpToLogin(Context)V（单 Context 参数、void 返回，
     * 静态与否不限，解析时不校验 static），用 libxposed 102 的
     * HookBuilder.intercept(Hooker)：Hooker 只有一个方法
     * intercept(Chain)，Chain.getArg(0) 取传入的 Context，
     * 不调 proceed() 即跳过原方法（void 方法返回 null 即可）。
     * 前两版都编不过已作废：第一版 intercept lambda 参数无法推断
     * 类型；第二版用的 BeforeHookCallback 在 102.0.0 里根本不存在
     * （Hooker 的抽象方法是 intercept(Chain)，不是静态 before）。
     * intercept 体内自己建一个到 LoginSelectUI（账号列表屏）的
     * intent，带 mobile_input_purpose=1 extra（与 resumeLogoutJump
     * 直达分支一致），用传入的 Context startActivity；体内任何异常
     * 都记日志并调 chain.proceed() 跑原方法（密码屏），登录流程不断。
     * 为避开 lambda 参数推断问题，Hooker 用显式匿名对象实现。
     * 标志位说明：FLAG_ACTIVITY_NEW_TASK（常量，0x10000000）保证
     * 非 Activity Context 也能 startActivity；0x20000000 是
     * FLAG_ACTIVITY_SINGLE_TOP，与微信自己发 LoginSelectUI intent 时
     * 用的标志一致（resumeLogoutJump 里就是这个值），两者都加。
     * 缓存快速通道与 DexKit 路径都调它，缓存格式不动（每次启动现解析）。
     * 解析失败只记日志，绝不影响已有的 ok.C、checkloginaspad、R 钩子。
     */
    private fun hookJumpRedirect(classLoader: ClassLoader): Boolean {
        try {
            val clazz = Class.forName(JUMP_CLASS, false, classLoader)
            val method = clazz.declaredMethods.firstOrNull { candidate ->
                candidate.name == JUMP_METHOD &&
                    candidate.parameterTypes.map { it.name } ==
                    listOf(Context::class.java.name)
            } ?: error("冷启动改道方法未找到：$JUMP_CLASS.$JUMP_METHOD")
            check(method.returnType.name == HookUtils.VOID_TYPE) {
                "冷启动改道方法返回类型已变化"
            }
            method.isAccessible = true
            xposed.hook(method)
                .setId(JUMP_HOOK_ID)
                .intercept(object : XposedInterface.Hooker {
                    override fun intercept(
                        chain: XposedInterface.Chain
                    ): Any? {
                        try {
                            val context = chain.getArg(0) as Context
                            val intent = Intent().apply {
                                setClassName(context, LOGIN_SELECT_CLASS)
                                putExtra(EXTRA_PURPOSE, 1)
                                addFlags(
                                    Intent.FLAG_ACTIVITY_NEW_TASK or
                                        FLAG_ACTIVITY_SINGLE_TOP
                                )
                            }
                            context.startActivity(intent)
                            HookUtils.logi(
                                "wechat jump redirected to LoginSelectUI"
                            )
                            return null
                        } catch (throwable: Throwable) {
                            HookUtils.logi(
                                "wechat jump redirect FAILED: " +
                                    "${throwable.message}"
                            )
                            return chain.proceed()
                        }
                    }
                })
            HookUtils.logi("wechat jump hook installed")
            return true
        } catch (throwable: Throwable) {
            HookUtils.logi("wechat jump hook FAILED: ${throwable.message}")
            xposed.log(
                Log.WARN,
                HookUtils.TAG,
                "微信冷启动改道钩子失败（不影响已有钩子）",
                throwable
            )
            return false
        }
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
        private const val FOLD_WHITELIST_CLASS = "com.tencent.mm.ui.ok"
        private const val FOLD_WHITELIST_METHOD = "R"
        private const val FOLD_WHITELIST_HOOK_ID = "wechat_fold_whitelist"
        private const val JUMP_CLASS = "com.tencent.mm.ui.LauncherUI"
        private const val JUMP_METHOD = "jumpToLogin"
        private const val JUMP_HOOK_ID = "wechat_jump_to_login"

        private const val CACHE_FILE_NAME = "tablethook_wechat_cache.json"
        private const val KEY_ENV = "env"
        private const val KEY_ELIG = "elig"
        private const val KEY_CLASS = "class"
        private const val KEY_METHOD = "method"
        private const val KEY_PARAMS = "params"
        private const val KEY_RETURN_TYPE = "returnType"
    }
}
