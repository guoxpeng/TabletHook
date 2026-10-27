package com.roro.tablethook

import android.util.Log
import com.roro.tablethook.hook.QqHook
import com.roro.tablethook.hook.WeChatHook
import com.roro.tablethook.util.HookUtils
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import org.luckypray.dexkit.DexKitBridge

/**
 * 统一入口：微信 / QQ 两套平板模式共用一个模块。
 *
 * 两个目标互相独立：任何一个目标的 hook 安装失败，只影响它自己那个进程，
 * 不会连带另一个（异常在 onPackageReady 内被捕获，日志可查）。
 *
 * 微信走 DexKit 动态定位，QQ 走固定类名，因此 QQ 进程不会加载 libdexkit.so，
 * 目标进程的模块痕迹更少。
 */
class QqPadEntry : XposedModule() {
    private var targetProcessName: String? = null

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        val processName = param.processName
        targetProcessName = when {
            processName == WeChatHook.PROCESS_NAME -> processName
            QqHook.isTargetProcess(processName) -> processName
            else -> null
        }
        log(
            Log.INFO,
            HookUtils.TAG,
            "onModuleLoaded process=$processName target=$targetProcessName"
        )
    }

    override fun onPackageReady(param: PackageReadyParam) {
        log(
            Log.INFO,
            HookUtils.TAG,
            "onPackageReady pkg=${param.packageName} first=${param.isFirstPackage} " +
                "target=$targetProcessName"
        )
        val processName = targetProcessName ?: return
        val expectedPackage = when {
            processName == WeChatHook.PROCESS_NAME -> WeChatHook.PACKAGE_NAME
            QqHook.isTargetProcess(processName) -> QqHook.PACKAGE_NAME
            else -> return
        }
        if (param.packageName != expectedPackage) {
            return
        }
        // QQ 的 DelegateLastClassLoader 会让 isFirstPackage 不稳定，交给 QqHook 自己去重。
        if (!param.isFirstPackage && !QqHook.isTargetProcess(processName)) {
            return
        }

        try {
            if (QqHook.isTargetProcess(processName)) {
                QqHook(this).install(param.classLoader, processName)
                return
            }

            HookUtils.loadDexKitLibrary(getModuleApplicationInfo().nativeLibraryDir)
            DexKitBridge.create(param.applicationInfo.sourceDir).use { bridge ->
                WeChatHook(this).install(bridge, param.classLoader)
            }
        } catch (throwable: Throwable) {
            log(Log.ERROR, HookUtils.TAG, "$processName 平板模式 Hook 初始化失败", throwable)
        }
    }
}
