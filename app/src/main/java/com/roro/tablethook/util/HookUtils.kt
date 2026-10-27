package com.roro.tablethook.util

import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.io.File
import java.lang.reflect.Executable
import java.lang.reflect.Method

internal object HookUtils {
    const val TAG = "TabletHook"

    /** 独立于 Xposed 日志通道的备份通道，方便直接从 logcat 抓。 */
    private const val TRACE_TAG = "TabletHookTrace"

    /** 落盘日志文件名，位于目标应用私有目录 files/ 下。 */
    private const val TRACE_FILE = "tablethook_trace.log"

    /** 落盘日志大小上限，超过后截断重建。 */
    private const val TRACE_MAX_BYTES = 1L * 1024 * 1024

    val BOOLEAN_TYPE: String = Boolean::class.javaPrimitiveType!!.name
    val VOID_TYPE: String = Void.TYPE.name

    /** DexKit native 库是否已经加载过：DexKit 可能被重试调用，`System.load` 同一库重复加载会抛异常。 */
    @Volatile
    private var dexKitLoaded = false

    /**
     * 加载 DexKit 的 native 库。
     *
     * 优先用模块目录下的绝对路径（依赖 `extractNativeLibs=true` / `useLegacyPackaging=true`）；
     * 拿不到文件时退回 `System.loadLibrary`，走系统从 APK 内映射的路径。
     *
     * 这样 `extractNativeLibs` 哪天被改掉也不会让微信侧静默全崩——QQ 侧本来就不用 DexKit，
     * 只有微信受影响，而这种崩溃表现是「模块启用了但微信完全没反应」，很难归因。
     */
    fun loadDexKitLibrary(nativeLibraryDir: String?) {
        if (dexKitLoaded) {
            return
        }
        synchronized(this) {
            if (dexKitLoaded) {
                return
            }
            val libName = System.mapLibraryName(DEXKIT_LIBRARY)
            if (!nativeLibraryDir.isNullOrEmpty()) {
                val file = File(nativeLibraryDir, libName)
                if (file.isFile) {
                    System.load(file.absolutePath)
                    dexKitLoaded = true
                    return
                }
                logi("dexkit 绝对路径不存在（$nativeLibraryDir），尝试 loadLibrary")
            }
            // 兜底：extractNativeLibs=false 时 nativeLibraryDir 里没有实体文件，
            // 但系统仍能从 APK 直接映射。
            System.loadLibrary(DEXKIT_LIBRARY)
            dexKitLoaded = true
        }
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
     * 超过 [TRACE_MAX_BYTES] 会截断重写：主进程和 `:MSF` 会同时往这个文件追加，
     * 长期不封顶会一直涨，且多进程并发 append 越大越容易出现半行交错。
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
            val file = File(dir, TRACE_FILE)
            if (file.length() > TRACE_MAX_BYTES) {
                file.writeText("=== trace 超过上限，已截断重建 ===\n")
            }
            file.appendText(line + "\n")
        }
    }

    /**
     * 统一的安全 hook 入口。
     *
     * 所有 hook 一律带 `ExceptionMode.PROTECTIVE`：模块是注入到宿主进程里的，
     * 我们自己抛出的异常绝不能让宿主崩溃（否则表现是「装了模块，微信/QQ 偶尔闪退」，
     * 极难归因）。
     *
     * 注意它只能保证「不崩」，不能保证语义正确 —— 拦截体内部仍必须各自给出
     * 合理的兜底返回值。
     */
    fun safeHook(
        xposed: XposedInterface,
        executable: Executable,
        hookId: String
    ): XposedInterface.HookBuilder = xposed.hook(executable)
        .setId(hookId)
        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)

    fun hookBooleanMethod(xposed: XposedInterface, method: Method, hookId: String) {
        method.isAccessible = true
        safeHook(xposed, method, hookId)
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
