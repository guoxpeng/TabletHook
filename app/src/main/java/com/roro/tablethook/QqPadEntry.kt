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
 * 加载顺序刻意做成「缓存优先」：
 * - QQ 全走固定类名，本来就不需要 DexKit；
 * - 微信先用上次定位结果的缓存，命中就完全不加载 DexKit。
 * 两者叠加后，绝大多数启动路径上目标进程都不会加载 libdexkit.so，
 * `/proc/self/maps` 里也就没有模块 native 库的痕迹。
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
        HookUtils.logi("onModuleLoaded process=$processName matched=$targetProcessName")
    }

    override fun onPackageReady(param: PackageReadyParam) {
        val processName = targetProcessName ?: return
        val expectedPackage = when {
            processName == WeChatHook.PROCESS_NAME -> WeChatHook.PACKAGE_NAME
            QqHook.isTargetProcess(processName) -> QqHook.PACKAGE_NAME
            else -> return
        }
        val appDataDir = param.applicationInfo.dataDir
        HookUtils.trace(
            appDataDir,
            "entry process=$processName package=${param.packageName} " +
                "expected=$expectedPackage isFirst=${param.isFirstPackage}"
        )
        HookUtils.logi(
            "entry process=$processName package=${param.packageName} " +
                "expected=$expectedPackage isFirst=${param.isFirstPackage} dataDir=$appDataDir"
        )
        if (param.packageName != expectedPackage) {
            return
        }
        // QQ 的 DelegateLastClassLoader 会让 isFirstPackage 不稳定，交给 QqHook 自己去重。
        if (!param.isFirstPackage && !QqHook.isTargetProcess(processName)) {
            return
        }

        try {
            // QQ 侧全部使用固定类名，不需要 DexKit：
            // 这样 QQ 进程里不会加载模块目录下的 libdexkit.so，
            // /proc/self/maps 也就少一条模块路径痕迹。
            if (QqHook.isTargetProcess(processName)) {
                QqHook(this).install(param.classLoader, processName)
                return
            }

            // 微信：缓存命中就直接挂钩，连 DexKit 都不加载。
            if (appDataDir != null && WeChatHook(this).tryCachedHook(param.classLoader, appDataDir)) {
                HookUtils.logi("wechat cache HIT hook installed (no bridge)")
                return
            }

            HookUtils.loadDexKitLibrary(getModuleApplicationInfo().nativeLibraryDir)
            DexKitBridge.create(param.applicationInfo.sourceDir).use { bridge ->
                WeChatHook(this).install(bridge, param.classLoader, appDataDir)
            }
        } catch (throwable: Throwable) {
            HookUtils.trace(appDataDir, "entry FAILED: ${throwable.message}")
            HookUtils.logi("entry FAILED: ${throwable.message}")
            log(
                Log.ERROR,
                HookUtils.TAG,
                "$processName 平板模式 Hook 初始化失败",
                throwable
            )
        }
    }
}
