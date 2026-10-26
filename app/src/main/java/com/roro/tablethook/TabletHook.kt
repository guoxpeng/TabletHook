package com.roro.tablethook

import android.util.Log
import com.roro.tablethook.hook.PddHook
import com.roro.tablethook.hook.QqHook
import com.roro.tablethook.hook.WeChatHook
import com.roro.tablethook.util.HookUtils
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import org.luckypray.dexkit.DexKitBridge

class TabletHook : XposedModule() {
    private var targetProcessName: String? = null

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        targetProcessName = param.processName.takeIf { processName ->
            processName == WeChatHook.PROCESS_NAME ||
                processName == QqHook.PROCESS_NAME ||
                processName == PddHook.PROCESS_NAME
        }
        HookUtils.logi("onModuleLoaded process=${param.processName} matched=$targetProcessName")
    }

    override fun onPackageReady(param: PackageReadyParam) {
        val processName = targetProcessName ?: return
        val expectedPackage = when (processName) {
            WeChatHook.PROCESS_NAME -> WeChatHook.PACKAGE_NAME
            QqHook.PROCESS_NAME -> QqHook.PACKAGE_NAME
            PddHook.PROCESS_NAME -> PddHook.PACKAGE_NAME
            else -> return
        }
        HookUtils.trace(
            param.applicationInfo.dataDir,
            "entry process=$processName package=${param.packageName} " +
                "expected=$expectedPackage isFirst=${param.isFirstPackage}"
        )
        HookUtils.logi(
            "entry process=$processName package=${param.packageName} " +
                "expected=$expectedPackage isFirst=${param.isFirstPackage} " +
                "dataDir=${param.applicationInfo.dataDir}"
        )
        if (param.packageName != expectedPackage || !param.isFirstPackage) {
            return
        }

        try {
            HookUtils.loadDexKitLibrary(getModuleApplicationInfo().nativeLibraryDir)
            DexKitBridge.create(param.applicationInfo.sourceDir).use { bridge ->
                when (processName) {
                    WeChatHook.PROCESS_NAME -> WeChatHook(this).install(
                        bridge,
                        param.classLoader
                    )
                    QqHook.PROCESS_NAME -> QqHook(this).install(
                        bridge,
                        param.classLoader,
                        param.applicationInfo.dataDir
                    )
                    PddHook.PROCESS_NAME -> PddHook(this).install(
                        bridge,
                        param.classLoader
                    )
                }
            }
        } catch (throwable: Throwable) {
            HookUtils.trace(
                param.applicationInfo.dataDir,
                "entry FAILED: " + throwable.stackTraceToString().take(2000)
            )
            HookUtils.logi(
                "entry FAILED: " + throwable.stackTraceToString().take(2000)
            )
            log(Log.ERROR, HookUtils.TAG, "$processName 平板登录 Hook 初始化失败", throwable)
        }
    }
}
