package com.roro.tablethook

import android.content.Context
import android.util.Log
import com.roro.tablethook.hook.QqHook
import com.roro.tablethook.hook.WeChatHook
import com.roro.tablethook.util.HookUtils
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import org.luckypray.dexkit.DexKitBridge
import java.util.concurrent.atomic.AtomicBoolean

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
 *
 * 微信侧额外等 Tinker 就绪再挂钩（借鉴 ImPad 的 `tinker_ready` 时序）：
 * onPackageReady 拿到的 loader 未必是最终执行业务代码的那个，微信用 Tinker 热补丁，
 * 真正的类由补丁 loader 定义。如果在旧 loader 上 hook，类对象和运行时用的不是同一个，
 * 表现就是"钩子装成功了但完全不生效"。所以先挂 `TinkerApplication.onBaseContextAttached`，
 * 等补丁加载完成拿到真正 loader 再装业务 hook。
 */
class QqPadEntry : XposedModule() {
    private var targetProcessName: String? = null
    private var wechatSourceDir: String? = null

    private val wechatInstalled = AtomicBoolean(false)

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
                QqHook(this).install(param.classLoader, processName, appDataDir)
                return
            }

            wechatSourceDir = param.applicationInfo.sourceDir
            installWeChatWhenTinkerReady(param.classLoader, appDataDir)
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

    // ---------------- 微信：等 Tinker 就绪 ----------------

    private fun installWeChatWhenTinkerReady(classLoader: ClassLoader, appDataDir: String?) {
        val tinkerApplication = runCatching {
            Class.forName(TINKER_APPLICATION_CLASS, false, classLoader)
        }.getOrNull()

        if (tinkerApplication == null) {
            HookUtils.logi("wechat tinker_ready skip: $TINKER_APPLICATION_CLASS 不存在")
            installWeChat(classLoader, appDataDir)
            return
        }

        // onPackageReady 可能晚于 attachBaseContext：Application 已经有了就直接用它。
        val attached = currentApplicationOrNull()
        if (attached != null) {
            val readyLoader = attached.classLoader ?: classLoader
            HookUtils.logi("wechat tinker_ready: application attached loader=${readyLoader.javaClass.name}")
            installWeChat(readyLoader, appDataDir)
            return
        }

        val bootstrapOk = runCatching {
            val method = tinkerApplication.getDeclaredMethod(
                TINKER_ATTACH_METHOD,
                Context::class.java,
                Long::class.javaPrimitiveType,
                Long::class.javaPrimitiveType
            )
            method.isAccessible = true
            hook(method)
                .setId(TINKER_BOOTSTRAP_HOOK_ID)
                .intercept { chain ->
                    val result = chain.proceed()
                    runCatching {
                        val application = chain.thisObject as? Context
                        val readyLoader = application?.classLoader
                        if (readyLoader != null) {
                            HookUtils.logi(
                                "wechat tinker_ready fired loader=${readyLoader.javaClass.name}"
                            )
                            installWeChat(readyLoader, appDataDir)
                        }
                    }
                    result
                }
            HookUtils.logi("wechat tinker bootstrap installed: $TINKER_ATTACH_METHOD")
            true
        }.getOrElse {
            HookUtils.logi("wechat tinker bootstrap FAILED: ${it.message}")
            false
        }

        if (!bootstrapOk) {
            installWeChat(classLoader, appDataDir)
            return
        }

        // 兜底：Tinker 没启用或子类没走 super 时，onBaseContextAttached 不会回调，
        // 那样微信侧会一条 hook 都装不上。等一小会儿还没就绪就用启动时的 loader 补装。
        Thread {
            runCatching {
                Thread.sleep(TINKER_FALLBACK_DELAY_MS)
                if (!wechatInstalled.get()) {
                    HookUtils.logi("wechat tinker_ready TIMEOUT, fallback to initial loader")
                    installWeChat(classLoader, appDataDir)
                }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun installWeChat(classLoader: ClassLoader, appDataDir: String?) {
        if (!wechatInstalled.compareAndSet(false, true)) {
            return
        }
        try {
            if (appDataDir != null && WeChatHook(this).tryCachedHook(classLoader, appDataDir)) {
                HookUtils.logi("wechat cache HIT hook installed (no bridge)")
                HookUtils.trace(appDataDir, "wechat cache HIT loader=${classLoader.javaClass.name}")
                return
            }

            val sourceDir = wechatSourceDir
                ?: error("微信 sourceDir 未记录，无法创建 DexKit 索引")
            HookUtils.loadDexKitLibrary(getModuleApplicationInfo().nativeLibraryDir)
            DexKitBridge.create(sourceDir).use { bridge ->
                WeChatHook(this).install(bridge, classLoader, appDataDir)
            }
        } catch (throwable: Throwable) {
            // 允许兜底线程再试一次：这里挂不上就没有任何微信侧 hook 了。
            wechatInstalled.set(false)
            HookUtils.trace(appDataDir, "wechat install FAILED: ${throwable.message}")
            HookUtils.logi("wechat install FAILED: ${throwable.message}")
            log(Log.ERROR, HookUtils.TAG, "微信平板模式 Hook 初始化失败", throwable)
        }
    }

    private fun currentApplicationOrNull(): Context? = runCatching {
        Class.forName("android.app.ActivityThread")
            .getMethod("currentApplication")
            .invoke(null) as? Context
    }.getOrNull()

    private companion object {
        const val TINKER_APPLICATION_CLASS = "com.tencent.tinker.loader.app.TinkerApplication"
        const val TINKER_ATTACH_METHOD = "onBaseContextAttached"
        const val TINKER_BOOTSTRAP_HOOK_ID = "wechat_tinker_bootstrap"
        const val TINKER_FALLBACK_DELAY_MS = 3000L
    }
}
