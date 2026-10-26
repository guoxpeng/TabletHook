package com.roro.tablethook.util

import io.github.libxposed.api.XposedInterface
import java.io.File
import java.lang.reflect.Method

internal object HookUtils {
    const val TAG = "TabletHook"
    val BOOLEAN_TYPE: String = Boolean::class.javaPrimitiveType!!.name
    val VOID_TYPE: String = Void.TYPE.name

    /**
     * 模块自带的 xposed.log 在这台机器上任何地方都找不到（06:33 成功时同样无日志），
     * 所以诊断信息直接写文件：模块以目标应用 UID 运行，可写目标应用自己的 files 目录，
     * root 可读。所有写操作失败都静默忽略，绝不能影响挂钩本身。
     */
    fun trace(appDataDir: String?, line: String) {
        try {
            if (appDataDir == null) {
                return
            }
            val dir = File(appDataDir, "files")
            if (!dir.isDirectory) {
                dir.mkdirs()
            }
            File(dir, "tablethook_trace.log").appendText(line + "\n")
        } catch (_: Throwable) {
        }
    }

    /**
     * 文件追踪在实机上一个字都没写出来（原因未明），改走 android.util.Log，
     * 直接进 logcat，root 一定能读到。同样失败静默。
     */
    fun logi(line: String) {
        try {
            android.util.Log.i("TabletHookTrace", line)
        } catch (_: Throwable) {
        }
    }

    fun loadDexKitLibrary(nativeLibraryDir: String?) {
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

    fun hookBooleanMethod(xposed: XposedInterface, method: Method, hookId: String) {
        method.isAccessible = true
        xposed.hook(method)
            .setId(hookId)
            .intercept { true }
    }

    fun <T> requireSingle(values: Collection<T>, label: String): T {
        check(values.size == 1) {
            "$label 匹配数量异常：${values.size}"
        }
        return values.first()
    }

    private const val DEXKIT_LIBRARY = "dexkit"
}
