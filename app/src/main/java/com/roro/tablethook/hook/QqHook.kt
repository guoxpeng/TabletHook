package com.roro.tablethook.hook

import android.content.Context
import android.util.Log
import com.roro.tablethook.util.HookUtils
import io.github.libxposed.api.XposedInterface
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Modifier

internal class QqHook(
    private val xposed: XposedInterface
) {
    fun install(bridge: DexKitBridge, classLoader: ClassLoader) {
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
    }

    companion object {
        const val PACKAGE_NAME = "com.tencent.mobileqq"
        const val PROCESS_NAME = "$PACKAGE_NAME:MSF"

        private const val PHONE_CONFIG_KEY = "AppSetting_params"
        private const val TABLET_CONFIG_KEY = "AppSetting_params_pad"
        private const val CONFIG_ERROR_LOG = "setArgsFromManifest fail, "
        private const val TABLET_ENUM_NAME = "TABLET"
        private const val TABLET_CLIENT_HOOK_ID = "qq_tablet_client"
    }
}
