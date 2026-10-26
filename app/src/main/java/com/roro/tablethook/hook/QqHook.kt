package com.roro.tablethook.hook

import android.content.Context
import android.util.Log
import com.roro.tablethook.util.HookUtils
import io.github.libxposed.api.XposedInterface
import org.json.JSONObject
import org.luckypray.dexkit.DexKitBridge
import java.io.File
import java.lang.reflect.Modifier

internal class QqHook(
    private val xposed: XposedInterface
) {
    fun install(bridge: DexKitBridge, classLoader: ClassLoader, appDataDir: String?) {
        // 快速通道：MSF 进程重启时会自动登录，若等 DexKit 全量搜索完成再挂钩，
        // 登录包可能已经按手机身份发出，导致另一台手机被挤下线。
        // 首次解析成功后把目标方法缓存到 QQ 自身 files 目录，后续 MSF 启动
        // 直接反射挂钩（毫秒级），QQ 更新导致缓存失效时自动回落 DexKit 重搜。
        HookUtils.trace(appDataDir, "qq install start")
        if (appDataDir != null && tryCachedHook(classLoader, appDataDir)) {
            HookUtils.trace(appDataDir, "qq cache HIT hook installed")
            return
        }
        HookUtils.trace(appDataDir, "qq cache MISS dexkit start")

        val configClasses = bridge.findClass {
            matcher {
                usingEqStrings(
                    PHONE_CONFIG_KEY,
                    TABLET_CONFIG_KEY,
                    CONFIG_ERROR_LOG
                )
            }
        }
        xposed.log(
            Log.INFO,
            HookUtils.TAG,
            "DexKit QQ 客户端配置类候选：${configClasses.size}"
        )
        val configClass = HookUtils.requireSingle(
            configClasses,
            "QQ 客户端配置类"
        )
        val configMethods = bridge.findMethod {
            matcher {
                declaredClass(configClass.name)
            }
        }
        val contextBooleanMethods = configMethods.filter { method ->
            Modifier.isStatic(method.modifiers) &&
                method.returnTypeName == HookUtils.BOOLEAN_TYPE &&
                method.paramTypeNames == listOf(Context::class.java.name)
        }
        val deviceTypeGetterCounts = contextBooleanMethods
            .flatMap { method -> method.invokes }
            .filter { invoked ->
                Modifier.isStatic(invoked.modifiers) &&
                    invoked.returnTypeName != HookUtils.BOOLEAN_TYPE &&
                    invoked.paramTypeNames == listOf(Context::class.java.name)
            }
            .groupingBy { method -> method }
            .eachCount()
        val highestReferenceCount = deviceTypeGetterCounts.values.maxOrNull()
            ?: error("未找到 QQ 设备类型 Getter")
        val deviceTypeGetters = deviceTypeGetterCounts.filterValues { count ->
            count == highestReferenceCount
        }.keys
        val deviceTypeGetterData = HookUtils.requireSingle(
            deviceTypeGetters,
            "QQ 设备类型 Getter"
        )
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
        xposed.log(
            Log.INFO,
            HookUtils.TAG,
            "DexKit QQ MSF 设备类型候选：" +
                "Context布尔=${contextBooleanMethods.size}，" +
                "Getter=${deviceTypeGetters.size}，" +
                "最高引用=$highestReferenceCount，" +
                "TABLET=${tabletValues.size}"
        )
        val tabletValue = HookUtils.requireSingle(tabletValues, "QQ TABLET 枚举值")
        val deviceTypeGetter = deviceTypeGetterData.getMethodInstance(classLoader)

        deviceTypeGetter.isAccessible = true
        xposed.hook(deviceTypeGetter)
            .setId(TABLET_CLIENT_HOOK_ID)
            .intercept { tabletValue }
        xposed.log(Log.INFO, HookUtils.TAG, "QQ MSF 平板设备类型已启用")
        HookUtils.trace(appDataDir, "qq dexkit hook installed")

        if (appDataDir != null) {
            try {
                saveCache(
                    appDataDir,
                    deviceTypeGetter.declaringClass.name,
                    deviceTypeGetter.name,
                    deviceTypeGetterData.returnTypeName
                )
                HookUtils.trace(appDataDir, "qq cache saved")
            } catch (throwable: Throwable) {
                HookUtils.trace(
                    appDataDir,
                    "qq cache save FAILED: " + throwable.stackTraceToString().take(1000)
                )
                xposed.log(
                    Log.WARN,
                    HookUtils.TAG,
                    "QQ 平板缓存写入失败（不影响本次挂钩）",
                    throwable
                )
            }
        }
    }

    private fun cacheFile(appDataDir: String): File {
        return File(File(appDataDir, "files"), CACHE_FILE_NAME)
    }

    private fun tryCachedHook(classLoader: ClassLoader, appDataDir: String): Boolean {
        val file = cacheFile(appDataDir)
        if (!file.isFile) {
            return false
        }
        try {
            val json = JSONObject(file.readText())
            val className = json.getString(KEY_CLASS)
            val methodName = json.getString(KEY_METHOD)
            val returnTypeName = json.getString(KEY_RETURN_TYPE)
            val clazz = Class.forName(className, false, classLoader)
            val method = clazz.getDeclaredMethod(methodName, Context::class.java)
            check(Modifier.isStatic(method.modifiers)) {
                "缓存方法不是静态方法"
            }
            check(method.returnType.name == returnTypeName) {
                "缓存返回类型已变化"
            }
            check(method.returnType.isEnum) {
                "缓存返回类型不是枚举"
            }
            val tabletValue = checkNotNull(method.returnType.enumConstants)
                .firstOrNull { constant ->
                    constant is Enum<*> && constant.name == TABLET_ENUM_NAME
                } ?: error("缓存枚举中无 TABLET")
            method.isAccessible = true
            xposed.hook(method)
                .setId(TABLET_CLIENT_HOOK_ID)
                .intercept { tabletValue }
            xposed.log(Log.INFO, HookUtils.TAG, "QQ MSF 平板设备类型已启用（缓存）")
            return true
        } catch (throwable: Throwable) {
            HookUtils.trace(
                appDataDir,
                "qq cache invalid: ${throwable.message}"
            )
            xposed.log(
                Log.WARN,
                HookUtils.TAG,
                "QQ 平板缓存失效，回落 DexKit 重搜",
                throwable
            )
            return false
        }
    }

    private fun saveCache(
        appDataDir: String,
        className: String,
        methodName: String,
        returnTypeName: String
    ) {
        val dir = File(appDataDir, "files")
        if (!dir.isDirectory) {
            dir.mkdirs()
        }
        val json = JSONObject()
            .put(KEY_CLASS, className)
            .put(KEY_METHOD, methodName)
            .put(KEY_RETURN_TYPE, returnTypeName)
        val file = File(dir, CACHE_FILE_NAME)
        val temp = File(dir, "$CACHE_FILE_NAME.tmp")
        temp.writeText(json.toString())
        if (!temp.renameTo(file)) {
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
    }

    companion object {
        const val PACKAGE_NAME = "com.tencent.mobileqq"
        const val PROCESS_NAME = "$PACKAGE_NAME:MSF"

        private const val PHONE_CONFIG_KEY = "AppSetting_params"
        private const val TABLET_CONFIG_KEY = "AppSetting_params_pad"
        private const val CONFIG_ERROR_LOG = "setArgsFromManifest fail, "
        private const val TABLET_ENUM_NAME = "TABLET"
        private const val TABLET_CLIENT_HOOK_ID = "qq_tablet_client"

        private const val CACHE_FILE_NAME = "tablethook_qq_cache.json"
        private const val KEY_CLASS = "class"
        private const val KEY_METHOD = "method"
        private const val KEY_RETURN_TYPE = "returnType"
    }
}
