package com.roro.tablethook

import android.content.Context
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import org.luckypray.dexkit.DexKitBridge
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import kotlin.coroutines.Continuation

class TabletHook : XposedModule() {
    private var targetProcessName: String? = null

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        targetProcessName = param.processName.takeIf { processName ->
            processName == WECHAT_PACKAGE || processName == QQ_MSF_PROCESS
        }
    }

    override fun onPackageReady(param: PackageReadyParam) {
        val processName = targetProcessName ?: return
        val expectedPackage = when (processName) {
            WECHAT_PACKAGE -> WECHAT_PACKAGE
            QQ_MSF_PROCESS -> QQ_PACKAGE
            else -> return
        }
        if (param.packageName != expectedPackage || !param.isFirstPackage) {
            return
        }

        try {
            loadDexKitLibrary()
            DexKitBridge.create(param.applicationInfo.sourceDir).use { bridge ->
                when (processName) {
                    WECHAT_PACKAGE -> installWeChatHooks(bridge, param.classLoader)
                    QQ_MSF_PROCESS -> installQqHooks(bridge, param.classLoader)
                }
            }
        } catch (throwable: Throwable) {
            log(Log.ERROR, TAG, "$processName 平板登录 Hook 初始化失败", throwable)
        }
    }

    private fun loadDexKitLibrary() {
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
    }

    private fun installWeChatHooks(bridge: DexKitBridge, classLoader: ClassLoader) {
        val keywordMatches = bridge.findMethod {
            matcher {
                usingStrings(WECHAT_TABLET_ENV_KEYWORD)
            }
        }
        val environmentMatches = keywordMatches.filter { method ->
            Modifier.isPublic(method.modifiers) &&
                Modifier.isStatic(method.modifiers) &&
                method.returnTypeName == BOOLEAN_TYPE
        }
        log(
            Log.INFO,
            TAG,
            "DexKit 微信平板判定候选：关键词=${keywordMatches.size}，签名=${environmentMatches.size}"
        )
        val tabletEnvironmentMethod = environmentMatches
            .requireSingle("微信平板判定方法")
            .getMethodInstance(classLoader)

        val eligibilityKeywordMatches = bridge.findMethod {
            matcher {
                usingEqStrings(WECHAT_LOGIN_AS_PAD_CGI_PATH)
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
            "DexKit 微信副设备登录资格候选：" +
                "关键词=${eligibilityKeywordMatches.size}，签名=${eligibilityMatches.size}"
        )
        val loginAsPadEligibilityMethod = eligibilityMatches
            .requireSingle("微信副设备登录资格方法")
            .getMethodInstance(classLoader)

        hookBooleanMethod(tabletEnvironmentMethod, WECHAT_TABLET_ENVIRONMENT_HOOK_ID)
        hookBooleanMethod(loginAsPadEligibilityMethod, WECHAT_LOGIN_AS_PAD_ELIGIBILITY_HOOK_ID)
        log(Log.INFO, TAG, "微信平板登录模式已启用")
    }

    private fun installQqHooks(bridge: DexKitBridge, classLoader: ClassLoader) {
        val configClasses = bridge.findClass {
            matcher {
                usingEqStrings(
                    QQ_PHONE_CONFIG_KEY,
                    QQ_TABLET_CONFIG_KEY,
                    QQ_CONFIG_ERROR_LOG
                )
            }
        }
        log(Log.INFO, TAG, "DexKit QQ 客户端配置类候选：${configClasses.size}")
        val configClass = configClasses.requireSingle("QQ 客户端配置类")
        val configMethods = bridge.findMethod {
            matcher {
                declaredClass(configClass.name)
            }
        }
        val contextBooleanMethods = configMethods.filter { method ->
            Modifier.isStatic(method.modifiers) &&
                method.returnTypeName == BOOLEAN_TYPE &&
                method.paramTypeNames == listOf(Context::class.java.name)
        }
        val deviceTypeGetterCounts = contextBooleanMethods
            .flatMap { method -> method.invokes }
            .filter { invoked ->
                Modifier.isStatic(invoked.modifiers) &&
                    invoked.returnTypeName != BOOLEAN_TYPE &&
                    invoked.paramTypeNames == listOf(Context::class.java.name)
            }
            .groupingBy { method -> method }
            .eachCount()
        val highestReferenceCount = deviceTypeGetterCounts.values.maxOrNull()
            ?: error("未找到 QQ 设备类型 Getter")
        val deviceTypeGetters = deviceTypeGetterCounts.filterValues { count ->
            count == highestReferenceCount
        }.keys
        val deviceTypeGetterData = deviceTypeGetters.requireSingle("QQ 设备类型 Getter")
        val deviceTypeClass = Class.forName(
            deviceTypeGetterData.returnTypeName,
            false,
            classLoader
        )
        check(deviceTypeClass.isEnum) {
            "QQ 设备类型 Getter 返回值不是枚举"
        }
        val enumConstants = checkNotNull(deviceTypeClass.enumConstants) {
            "无法读取 QQ 设备类型枚举值"
        }
        val tabletValues = enumConstants.filter { constant ->
            constant is Enum<*> && constant.name == TABLET_ENUM_NAME
        }
        log(
            Log.INFO,
            TAG,
            "DexKit QQ MSF 设备类型候选：" +
                "Context布尔=${contextBooleanMethods.size}，" +
                "Getter=${deviceTypeGetters.size}，" +
                "最高引用=$highestReferenceCount，" +
                "TABLET=${tabletValues.size}"
        )
        val tabletValue = tabletValues.requireSingle("QQ TABLET 枚举值")
        val deviceTypeGetter = deviceTypeGetterData.getMethodInstance(classLoader)

        deviceTypeGetter.isAccessible = true
        hook(deviceTypeGetter)
            .setId(QQ_TABLET_CLIENT_HOOK_ID)
            .intercept { tabletValue }
        log(Log.INFO, TAG, "QQ MSF 平板设备类型已启用")
    }

    private fun hookBooleanMethod(method: Method, hookId: String) {
        method.isAccessible = true
        hook(method)
            .setId(hookId)
            .intercept { true }
    }

    private fun <T> Collection<T>.requireSingle(label: String): T {
        check(size == 1) {
            "$label 匹配数量异常：$size"
        }
        return first()
    }

    private companion object {
        const val TAG = "TabletHook"
        const val WECHAT_PACKAGE = "com.tencent.mm"
        const val QQ_PACKAGE = "com.tencent.mobileqq"
        const val QQ_MSF_PROCESS = "com.tencent.mobileqq:MSF"
        const val DEXKIT_LIBRARY = "dexkit"
        val BOOLEAN_TYPE: String = Boolean::class.javaPrimitiveType!!.name

        const val WECHAT_TABLET_ENV_KEYWORD = "inTabletEnv"
        const val WECHAT_LOGIN_AS_PAD_CGI_PATH = "/cgi-bin/micromsg-bin/checkloginaspad"
        const val QQ_PHONE_CONFIG_KEY = "AppSetting_params"
        const val QQ_TABLET_CONFIG_KEY = "AppSetting_params_pad"
        const val QQ_CONFIG_ERROR_LOG = "setArgsFromManifest fail, "
        const val TABLET_ENUM_NAME = "TABLET"

        const val WECHAT_TABLET_ENVIRONMENT_HOOK_ID = "wechat_tablet_environment"
        const val WECHAT_LOGIN_AS_PAD_ELIGIBILITY_HOOK_ID = "wechat_login_as_pad_eligibility"
        const val QQ_TABLET_CLIENT_HOOK_ID = "qq_tablet_client"
    }
}
