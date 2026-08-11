package com.roro.tablethook

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import org.luckypray.dexkit.DexKitBridge
import java.io.File
import java.lang.reflect.Modifier
import kotlin.coroutines.Continuation

class WeChatTabletHook : XposedModule() {
    private var isWeChatMainProcess = false

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        isWeChatMainProcess = param.processName == WECHAT_PACKAGE
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (!isWeChatMainProcess ||
            param.packageName != WECHAT_PACKAGE ||
            !param.isFirstPackage
        ) {
            return
        }

        try {
            val nativeLibraryDir = getModuleApplicationInfo().nativeLibraryDir
            check(!nativeLibraryDir.isNullOrEmpty()) {
                "无法获取模块 native library 目录"
            }
            val dexKitLibrary = File(
                nativeLibraryDir,
                System.mapLibraryName(DEXKIT_LIBRARY)
            )
            check(dexKitLibrary.isFile) {
                "DexKit native library 不存在：${dexKitLibrary.absolutePath}"
            }
            System.load(dexKitLibrary.absolutePath)
            val targetMethods = DexKitBridge.create(param.applicationInfo.sourceDir).use { bridge ->
                val keywordMatches = bridge.findMethod {
                    matcher {
                        usingStrings(TABLET_ENV_KEYWORD)
                    }
                }
                val matches = keywordMatches.filter { method ->
                    Modifier.isPublic(method.modifiers) &&
                        Modifier.isStatic(method.modifiers) &&
                        method.returnTypeName == Boolean::class.javaPrimitiveType!!.name
                }
                log(
                    Log.INFO,
                    TAG,
                    "DexKit 平板判定候选：关键词=${keywordMatches.size}，签名=${matches.size}"
                )
                check(matches.size == 1) {
                    "平板判定方法匹配数量异常：关键词=${keywordMatches.size}，签名=${matches.size}"
                }
                val tabletEnvironmentMethod = matches.single()
                    .getMethodInstance(param.classLoader)

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
                log(
                    Log.INFO,
                    TAG,
                    "DexKit 副设备登录资格候选：" +
                        "关键词=${eligibilityKeywordMatches.size}，签名=${eligibilityMatches.size}"
                )
                check(eligibilityMatches.size == 1) {
                    "副设备登录资格方法匹配数量异常：" +
                        "关键词=${eligibilityKeywordMatches.size}，签名=${eligibilityMatches.size}"
                }
                val loginAsPadEligibilityMethod = eligibilityMatches.single()
                    .getMethodInstance(param.classLoader)
                tabletEnvironmentMethod to loginAsPadEligibilityMethod
            }

            hook(targetMethods.first)
                .setId(TABLET_ENVIRONMENT_HOOK_ID)
                .intercept { true }
            hook(targetMethods.second)
                .setId(LOGIN_AS_PAD_ELIGIBILITY_HOOK_ID)
                .intercept { true }
            log(Log.INFO, TAG, "微信平板登录模式已启用")
        } catch (throwable: Throwable) {
            log(Log.ERROR, TAG, "微信平板登录 Hook 初始化失败", throwable)
        }
    }

    private companion object {
        const val TAG = "TabletHook"
        const val WECHAT_PACKAGE = "com.tencent.mm"
        const val DEXKIT_LIBRARY = "dexkit"
        const val TABLET_ENV_KEYWORD = "inTabletEnv"
        const val LOGIN_AS_PAD_CGI_PATH = "/cgi-bin/micromsg-bin/checkloginaspad"
        const val TABLET_ENVIRONMENT_HOOK_ID = "wechat_tablet_environment"
        const val LOGIN_AS_PAD_ELIGIBILITY_HOOK_ID = "wechat_login_as_pad_eligibility"
    }
}
