package com.roro.tablethook.util

import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.io.File
import java.lang.reflect.Method

internal object HookUtils {
    const val TAG = "TabletHook"

    /** 独立于 Xposed 日志通道的备份通道，方便直接从 logcat 抓。 */
    private const val TRACE_TAG = "TabletHookTrace"

    /** 落盘日志文件名，位于目标应用私有目录 files/ 下。 */
    private const val TRACE_FILE = "tablethook_trace.log"

    val BOOLEAN_TYPE: String = Boolean::class.javaPrimitiveType!!.name
    val VOID_TYPE: String = Void.TYPE.name

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

    /**
     * 打一条 `TabletHookTrace` 日志。
     *
     * Xposed 的模块日志在部分环境下抓不到（框架未落文件、进程被冻结等），
     * 这条通道用于关键路径的双保险。
     */
    fun logi(line: String) {
        runCatching { Log.i(TRACE_TAG, line) }
    }

    /**
     * 把一行诊断信息追加到目标应用私有目录的 `files/tablethook_trace.log`。
     *
     * 拿不到 dataDir 时静默跳过；写失败不影响任何 Hook 逻辑。
     */
    fun trace(appDataDir: String?, line: String) {
        if (appDataDir.isNullOrEmpty()) {
            return
        }
        runCatching {
            val dir = File(appDataDir, "files")
            if (!dir.isDirectory) {
                dir.mkdirs()
            }
            File(dir, TRACE_FILE).appendText(line + "\n")
        }
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

    /**
     * 规则唯一性断言：刚好命中一个才返回，否则返回 null 并记一条失败事件。
     *
     * 借鉴 ImPad 的 fail-closed 思路——宁可把这条规则整条跳过、明确报
     * `NO_COMPATIBLE_RULE`，也不要在多个候选里随便挑一个挂上去。挂错方法比
     * 不挂更难排查：应用不会报错，只是行为莫名其妙不对。
     */
    fun uniqueMatchOrNull(
        candidates: List<Method>,
        ruleId: String,
        label: String,
        processName: String
    ): Method? {
        if (candidates.size == 1) {
            return candidates.single()
        }
        val reason = if (candidates.isEmpty()) {
            "TARGET_NOT_FOUND"
        } else {
            "AMBIGUOUS_TARGET n=${candidates.size}"
        }
        ruleFailure(ruleId, label, reason, processName)
        return null
    }

    /** 一条规则安装成功。 */
    fun ruleInstalled(ruleId: String, hookCount: Int, processName: String) {
        logi("rule-runtime event=hook_installed ruleId=$ruleId hookCount=$hookCount processName=$processName")
    }

    /** 一条规则在当前应用构建上不可用，明确失败而不是静默跳过。 */
    fun ruleFailure(ruleId: String, label: String, reason: String, processName: String) {
        logi("rule-runtime event=NO_COMPATIBLE_RULE ruleId=$ruleId label=$label reason=$reason processName=$processName")
    }

    fun methodSignatures(method: Method): List<String> =
        method.parameterTypes.map { it.name }

    private const val DEXKIT_LIBRARY = "dexkit"
}
