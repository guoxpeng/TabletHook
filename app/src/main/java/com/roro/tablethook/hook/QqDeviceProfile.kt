package com.roro.tablethook.hook

import android.content.Context
import android.os.Build
import com.roro.tablethook.config.ModuleConfig
import com.roro.tablethook.util.HookUtils
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Field

/**
 * QQ 设备画像对齐（2.8-profile）。
 *
 * 思路借自 I'm Pad（`com.houvven.impad` / Houvven/I-Am-Pad）的 QQ 分支：它完全不碰
 * `AppSetting` / `PadUtil` / appid，而是把进程内的机型字段改成一台真实平板，让 QQ
 * 从源头采集到平板设备信息。旁证是同一份源码里对小红书的注释——设备类型由服务端
 * 按机型分类决定。
 *
 * 相比 I'm Pad 的做法，这里为「稳妥」做了四处收敛：
 *
 * 1. **只改内存里的 Build 字段**，不写系统属性、不装 Magisk 模块。改动只活在 QQ 进程内，
 *    微信、支付类应用完全不受影响，QQ 进程一退就自动还原。
 * 2. **不删 `files/mmkv/Pandora`，不主动 kill 进程**。I'm Pad 靠「删缓存 + 自杀」强制
 *    QQ 重新采集，一旦判断条件写错就是无限重启。这里改成把 `BUGLY_COMMON_VALUES`
 *    里的 `deviceModel` 就地改写，下次冷启动自然读到新值，没有任何自毁路径。
 * 3. **每步独立兜异常**。任何一步失败只记日志，绝不影响已经验证过的 6 条 AppSetting 规则。
 * 4. **幂等**：缓存已经是目标值就不再写，重复启动零副作用。
 *
 * 已知边界（写在这里避免以后误判）：Build 字段与 `SystemProperties.get` 都在 Java 层，
 * QQ 的 native 库（libfekit / libturingxq）与 `/proc` 读取器仍会看到真机值；屏幕仍是
 * 1080x2400 @480dpi，与目标机型并不自洽。这一版只解决「Java 层上报的机型」。
 */
internal object QqDeviceProfile {

    /**
     * 目标画像：Xiaomi Pad 6 Pro。
     *
     * 这组值是 I'm Pad 在 QQ 上实际验证过能生效的一组，不要随意换——换成其他机型
     * 等于重新做一次未知验证。
     */
    private const val TARGET_BRAND = "Xiaomi"
    private const val TARGET_MANUFACTURER = "Xiaomi"
    private const val TARGET_MODEL = "23046RP50C"

    /** Xiaomi Pad 6 Pro 的设备代号 / 市场名，QQ 的 Bugly 会一并采集。 */
    private const val TARGET_DEVICE = "liuqin"
    private const val TARGET_MARKET_NAME = "Xiaomi Pad 6 Pro"

    /** QQ 的 Bugly SDK 把机型缓存写在这里，不清掉就会一直上报旧机型。 */
    private const val BUGLY_PREFS = "BUGLY_COMMON_VALUES"
    private const val BUGLY_MODEL_KEY = "deviceModel"

    /** `ro.build.characteristics`：真平板是 `tablet`。 */
    private const val CHAR_KEY = "ro.build.characteristics"
    private const val CHAR_TABLET = "tablet"

    /**
     * 需要改写的系统属性。
     *
     * 只改 `Build.MODEL` 不够——实测第二轮启动 QQ 会把 `BUGLY_COMMON_VALUES.deviceModel`
     * 重新写回真机 `RMX2202`，说明 Bugly 采集走的是 `SystemProperties.get`，不是 Build 字段。
     * 这里把整组 `ro.product.*` 一起盖掉，让「Build 字段」和「属性读取」两条路径口径一致。
     */
    private val PROFILE_PROPERTIES: Map<String, String> = linkedMapOf(
        CHAR_KEY to CHAR_TABLET,
        "ro.product.model" to TARGET_MODEL,
        "ro.product.brand" to TARGET_BRAND,
        "ro.product.manufacturer" to TARGET_MANUFACTURER,
        "ro.product.device" to TARGET_DEVICE,
        "ro.product.name" to TARGET_DEVICE,
        "ro.product.marketname" to TARGET_MARKET_NAME
    )

    /** Build 里需要改写的静态字段。 */
    private val PROFILE_BUILD_FIELDS: Map<String, String> = linkedMapOf(
        "MANUFACTURER" to TARGET_MANUFACTURER,
        "BRAND" to TARGET_BRAND,
        "MODEL" to TARGET_MODEL,
        "DEVICE" to TARGET_DEVICE,
        "PRODUCT" to TARGET_DEVICE
    )

    private const val HOOK_ID_CHAR = "qq_device_char_001"
    private const val HOOK_ID_BUILD = "qq_device_build_001"

    /** 缓存对齐的重试参数：Application 可能比本模块晚 attach，拿不到 Context 时再等一会。 */
    private const val ALIGN_RETRY_TIMES = 4
    private const val ALIGN_RETRY_INTERVAL_MS = 1_500L

    /**
     * 安装设备画像对齐。由 [QqHook.install] 在既有规则装完之后调用，与它们互不依赖。
     *
     * @param xposed 模块入口，用于挂 `SystemProperties.get`
     * @param contextProvider 延迟取 Context：`:MSF` 进程拿不到 Application 时返回 null
     */
    fun install(
        xposed: XposedInterface,
        processName: String,
        contextProvider: () -> Context?,
        log: (String) -> Unit
    ) {
        if (!ModuleConfig.ENABLE_QQ_DEVICE_PROFILE) {
            log("[profile] 设备画像对齐已关闭（ModuleConfig.ENABLE_QQ_DEVICE_PROFILE=false）")
            return
        }

        val buildResult = applyBuildFields(log)
        val propResult = hookCharacteristics(xposed, log)
        log("[profile] $processName Build{$buildResult} characteristics{$propResult}")

        // 实测：QQ 每次启动都会用 native 采集把 deviceModel 写回真机机型，
        // 改文件抢不过它（Application 已被 QQ 重写，抢不到更早的 Context）。
        // 这里保留改写只是保证「进程退出后文件里是平板值」，供其他读取方兜底，
        // 不作为主路径。真正的机型来源是上面两个 Java 层改写。
        alignCacheDeferred(contextProvider, log)
    }

    // ---------------- Build 字段 ----------------

    private fun applyBuildFields(log: (String) -> Unit): String {
        val changed = mutableListOf<String>()
        val failed = mutableListOf<String>()

        PROFILE_BUILD_FIELDS.forEach { (name, target) ->
            val ok = runCatching {
                val field = Build::class.java.getField(name)
                val before = field.get(null) as? String
                if (before == target) {
                    return@runCatching true
                }
                if (!setStaticString(field, target)) {
                    return@runCatching false
                }
                changed += "$name:$before->$target"
                true
            }.getOrElse {
                log("[profile] Build.$name 改写异常：${it.javaClass.simpleName} ${it.message}")
                HookUtils.logi("profile event=build_field_failed field=$name")
                false
            }
            if (!ok) {
                failed += name
            }
        }

        if (failed.isNotEmpty()) {
            // 明确打出来：失败时 QQ 上报的仍是真机机型，等于这一版没生效，不要误判。
            log("[profile] Build 字段改写失败：$failed（当前 ${Build.MANUFACTURER}/${Build.MODEL}）")
            HookUtils.ruleFailure(HOOK_ID_BUILD, "QQ 机型伪装", "FIELD_SET_FAILED", "qq")
        } else {
            HookUtils.ruleInstalled(HOOK_ID_BUILD, PROFILE_BUILD_FIELDS.size, "qq")
        }
        return if (changed.isEmpty()) "已是目标值" else changed.joinToString(",")
    }

    /**
     * 改写 `Build` 的静态 String 字段。
     *
     * 先走标准反射；ART 上 `static final` 引用字段偶尔会被拒绝写入，此时用 Unsafe
     * 直接按静态字段偏移量覆盖，避免「装了模块但机型没变」这种最难排查的静默失效。
     */
    private fun setStaticString(field: Field, value: String): Boolean {
        runCatching {
            field.isAccessible = true
            field.set(null, value)
        }
        if (readString(field) == value) {
            return true
        }
        return runCatching {
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val unsafe = unsafeClass.getDeclaredField("theUnsafe")
                .apply { isAccessible = true }
                .get(null)
            val base = unsafeClass
                .getMethod("staticFieldBase", Field::class.java)
                .invoke(unsafe, field)
            val offset = unsafeClass
                .getMethod("staticFieldOffset", Field::class.java)
                .invoke(unsafe, field) as Long
            unsafeClass
                .getMethod(
                    "putObject",
                    Any::class.java,
                    Long::class.javaPrimitiveType,
                    Any::class.java
                )
                .invoke(unsafe, base, offset, value)
            readString(field) == value
        }.getOrElse { false }
    }

    private fun readString(field: Field): String? = runCatching {
        field.get(null) as? String
    }.getOrNull()

    // ---------------- SystemProperties ----------------

    /**
     * 让 `SystemProperties.get("ro.build.characteristics")` 在 QQ 进程内返回 `tablet`。
     *
     * 只挑返回 String、且第一个参数是 String 的 `get` 重载（`get(String)` 与
     * `get(String, String)` 两个都在里面）；匹配不到就整条跳过并明确记一条失败事件，
     * 不在候选里随便挑一个挂——挂错方法比不挂更难查。
     */
    private fun hookCharacteristics(xposed: XposedInterface, log: (String) -> Unit): String {
        val clazz = runCatching { Class.forName("android.os.SystemProperties") }.getOrNull()
        if (clazz == null) {
            log("[profile] SystemProperties 类不可用，跳过 characteristics 改写")
            HookUtils.ruleFailure(HOOK_ID_CHAR, "characteristics", "CLASS_NOT_FOUND", "qq")
            return "SKIP(类不可用)"
        }

        val methods = runCatching {
            clazz.declaredMethods.filter { method ->
                method.name == "get" &&
                    method.returnType == String::class.java &&
                    method.parameterTypes.isNotEmpty() &&
                    method.parameterTypes[0] == String::class.java
            }
        }.getOrDefault(emptyList())

        if (methods.isEmpty()) {
            log("[profile] SystemProperties.get 无匹配重载，跳过 characteristics 改写")
            HookUtils.ruleFailure(HOOK_ID_CHAR, "characteristics", "TARGET_NOT_FOUND", "qq")
            return "SKIP(无匹配重载)"
        }

        var hooked = 0
        methods.forEach { method ->
            runCatching {
                method.isAccessible = true
                HookUtils.safeHook(xposed, method, HOOK_ID_CHAR).intercept { chain ->
                    val key = chain.args.firstOrNull() as? String
                    val override = if (key == null) null else PROFILE_PROPERTIES[key]
                    override ?: chain.proceed()
                }
                hooked++
            }.onFailure {
                log("[profile] SystemProperties.get(${method.parameterTypes.size}) hook 失败：${it.javaClass.simpleName}")
            }
        }

        if (hooked > 0) {
            HookUtils.ruleInstalled(HOOK_ID_CHAR, hooked, "qq")
        }
        return "$hooked/${methods.size}"
    }

    // ---------------- BUGLY 缓存 ----------------

    /**
     * 延迟对齐 `BUGLY_COMMON_VALUES.deviceModel`。
     *
     * 不在 `install` 里同步做，是因为这个时机 Application 可能还没 attach，`context` 为 null；
     * 而 `:MSF` 进程本来就长期拿不到 Application，需要等主进程把文件写好。
     * 值是幂等的，两个进程同时写同一个值也无害。
     */
    private fun alignCacheDeferred(contextProvider: () -> Context?, log: (String) -> Unit) {
        Thread {
            repeat(ALIGN_RETRY_TIMES) { attempt ->
                val context = runCatching { contextProvider() }.getOrNull()
                if (context != null) {
                    val result = alignCache(context, log)
                    if (result != null) {
                        return@Thread
                    }
                }
                if (attempt < ALIGN_RETRY_TIMES - 1) {
                    runCatching { Thread.sleep(ALIGN_RETRY_INTERVAL_MS) }
                }
            }
            log("[profile] BUGLY 缓存对齐放弃：始终拿不到 Context（下次启动再试）")
        }.apply {
            name = "tablethook-profile-align"
            isDaemon = true
            start()
        }
    }

    /** 返回 null 表示这一轮没做成、值得重试；返回字符串表示已有结论。 */
    private fun alignCache(context: Context, log: (String) -> Unit): String? = runCatching {
        val prefs = context.getSharedPreferences(BUGLY_PREFS, Context.MODE_PRIVATE)
        val current = prefs.getString(BUGLY_MODEL_KEY, null)
        when {
            current == TARGET_MODEL -> "已是目标值".also {
                log("[profile] BUGLY 缓存已是 $TARGET_MODEL，跳过改写")
                HookUtils.logi("profile event=bugly_cache_aligned model=$TARGET_MODEL state=noop")
            }

            prefs.edit().putString(BUGLY_MODEL_KEY, TARGET_MODEL).commit() -> {
                log("[profile] BUGLY 缓存机型 ${current ?: "null"} -> $TARGET_MODEL（下次冷启动生效）")
                HookUtils.logi("profile event=bugly_cache_aligned model=$TARGET_MODEL from=${current ?: "null"}")
                "已改写 ${current ?: "null"} -> $TARGET_MODEL"
            }

            else -> {
                log("[profile] BUGLY 缓存写入失败（commit=false）")
                HookUtils.logi("profile event=bugly_cache_write_failed")
                null
            }
        }
    }.getOrElse {
        log("[profile] BUGLY 缓存对齐异常：${it.javaClass.simpleName} ${it.message}")
        null
    }
}
