package com.roro.tablethook.hook

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import com.roro.tablethook.config.ModuleConfig
import com.roro.tablethook.util.HookUtils
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * QQ 平板模式。
 *
 * 合并自独立模块 com.buffy.qqpad（legacy Xposed），改写为 libxposed 实现，行为保持一致：
 *
 * 1. AppSetting.e()  -> 平板 appid（跟随原生，常量兜底）
 * 2. AppSetting.i()  -> 平板 appid 对应的 key（跟随原生，常量兜底）
 * 3. AppSetting.o(Context) -> true（平板判定开关，原生值会被记录用于判断能否跟随）
 * 4. PadUtil.a(Context) -> DeviceType.TABLET
 * 5. injector.d#isPadForMultiDeviceLogin -> true
 * 6. IQQNTWrapperSession$CppProxy#init -> 会话配置强制 KPAD / 平板 appid / KANDROID
 * 7. RegisterProxySvcPackHandler#e3 -> 只读诊断，转储服务端在线设备列表
 *
 * 与独立模块的差别：不再使用 DexKit。QQ 侧全部走固定类名，既省一次 dexkit .so 加载
 * （少一份 /proc/self/maps 里的模块痕迹），也让 QQ 进程完全不碰 DexKit。
 *
 * 每个 hook 点都带一个不可变规则 ID，并且要求「刚好命中一个」才挂：
 * 匹配不到、或者匹配到多个，都会记一条 `NO_COMPATIBLE_RULE` 并跳过这条规则，
 * 不会再出现 QQ 升级后某条 hook 静默失效、日志里只留一个 x0 的情况。
 */
internal class QqHook(
    private val xposed: XposedInterface
) {
    private val installedLoaders: MutableSet<ClassLoader> =
        Collections.newSetFromMap(WeakHashMap<ClassLoader, Boolean>())
    private val delegateLoaderHooked = AtomicBoolean(false)
    private val sessionLogged = AtomicBoolean(false)

    private var processName: String = PACKAGE_NAME
    private var appDataDir: String? = null

    private val installedRules = linkedSetOf<String>()
    private val failedRules = linkedSetOf<String>()
    private val probedKeys: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * 原生 `AppSetting.o(Context)` 是否曾经返回过 true。
     *
     * 这是「能否跟随原生 appid」的判据：`o()` 为真时，`e()` / `i()` 的原生实现本来就会
     * 给出当前版本正确的平板 appid 和 key（9.3.75 实测 537397816 / 0FFD632C4B7E08AF）。
     * 所以只要原生曾经走过平板分支，我们就直接跟随原生值，写死的 [PAD_APP_ID] 只作兜底。
     */
    private val nativePadFlagSeen = AtomicBoolean(false)

    /** 原生 `e()` 最近一次给出的平板 appid，供 NT 会话配置复用。0 表示还没拿到。 */
    private val resolvedPadAppId = java.util.concurrent.atomic.AtomicInteger(0)

    /** 原生 `i()` 最近一次给出的平板 key，仅诊断用。 */
    private val resolvedPadKey = java.util.concurrent.atomic.AtomicReference<String?>(null)

    private var appSettingClass: Class<*>? = null

    fun install(classLoader: ClassLoader, processName: String, appDataDir: String?) {
        this.processName = processName
        this.appDataDir = appDataDir
        if (ModuleConfig.LOG_DEVICE_PROFILE) {
            logDeviceProfile(processName)
        }
        installOn(classLoader, "app")
        hookDelegateLoader()
        logSummary()
    }

    /** 一条规则装完了，记下来用于最后汇总。 */
    private fun markInstalled(ruleId: String) {
        synchronized(installedRules) { installedRules.add(ruleId) }
    }

    private fun markFailed(ruleId: String) {
        synchronized(failedRules) { failedRules.add(ruleId) }
    }

    /**
     * 启动时打一条汇总：哪些规则装上了、哪些因为 QQ 版本变化没装上。
     *
     * 排查时只看这一行就够——不用再猜是模块没生效还是 QQ 改了签名。
     */
    private fun logSummary() {
        val ok = synchronized(installedRules) { installedRules.toList() }
        val bad = synchronized(failedRules) { failedRules.toList() }
        val line = "rule-runtime event=summary process=$processName installed=${ok.size}" +
            " failed=${bad.size}" +
            (if (ok.isEmpty()) "" else " ok=[${ok.joinToString(",")}]") +
            (if (bad.isEmpty()) "" else " bad=[${bad.joinToString(",")}]")
        log(line)
    }

    // ---------------- 类加载器 ----------------

    /**
     * QQ 用 DelegateLastClassLoader 做插件化，真正加载业务类的 loader 不是 onPackageReady 给的那个。
     * 抓构造器拿到真实 loader 再补装一遍，否则 NT 相关类会漏。
     */
    private fun hookDelegateLoader() {
        if (!delegateLoaderHooked.compareAndSet(false, true)) {
            return
        }
        runCatching {
            val cls = Class.forName("dalvik.system.DelegateLastClassLoader", false, null)
            var count = 0
            for (ctor in cls.declaredConstructors) {
                ctor.isAccessible = true
                xposed.hook(ctor)
                    .setId(DELEGATE_LOADER_HOOK_ID)
                    .intercept { chain ->
                        val result = chain.proceed()
                        runCatching {
                            (chain.thisObject as? ClassLoader)?.let { installOn(it, "dlcl") }
                        }
                        result
                    }
                count++
            }
            log("DelegateLastClassLoader 构造器已 hook x$count")
        }.onFailure { log("DelegateLastClassLoader hook 失败：$it") }
    }

    private fun installOn(loader: ClassLoader, tag: String) {
        val appSetting = runCatching {
            Class.forName(CLS_APP_SETTING, false, loader)
        }.getOrNull()
        if (appSetting == null) {
            HookUtils.ruleFailure(RULE_APP_SETTING, CLS_APP_SETTING, "TARGET_CLASS_MISSING", processName)
            markFailed(RULE_APP_SETTING)
            return
        }
        synchronized(installedLoaders) {
            if (!installedLoaders.add(loader)) {
                return
            }
        }
        log("[$tag] 开始安装 QQ 平板 hook：${loader.javaClass.name}")
        hookAppSetting(appSetting, tag)
        hookPadUtil(loader, tag)
        hookIsPadForMultiDeviceLogin(loader, tag)
        hookSessionInit(loader, tag)
        hookRegisterProxy(loader, tag)
    }

    // ---------------- AppSetting ----------------

    private fun hookAppSetting(appSetting: Class<*>, tag: String) {
        appSettingClass = appSetting
        probeAppSettingFields(appSetting, tag)

        val statics = appSetting.declaredMethods.filter { method ->
            Modifier.isStatic(method.modifiers) && method.parameterTypes.size <= 1
        }

        // e() -> 平板 appid：跟随原生，常量兜底
        val appIdMatches = statics.filter {
            it.name == "e" && it.returnType == Int::class.javaPrimitiveType
        }
        HookUtils.uniqueMatchOrNull(appIdMatches, RULE_APP_SETTING_APPID, "AppSetting.e()", processName)
            ?.let { method ->
                if (hookPadAppId(method, tag)) {
                    log("[$tag] ${RULE_APP_SETTING_APPID} AppSetting.e() 已接管（跟随原生 / 兜底 $PAD_APP_ID）")
                }
            }
            ?: markFailed(RULE_APP_SETTING_APPID)

        // i() -> 平板 appid 对应的 key：跟随原生，常量兜底
        val keyMatches = statics.filter {
            it.name == "i" && it.returnType == String::class.java
        }
        HookUtils.uniqueMatchOrNull(keyMatches, RULE_APP_SETTING_KEY, "AppSetting.i()", processName)
            ?.let { method ->
                if (hookPadKey(method, tag)) {
                    log("[$tag] ${RULE_APP_SETTING_KEY} AppSetting.i() 已接管（跟随原生）")
                }
            }
            ?: markFailed(RULE_APP_SETTING_KEY)

        // o(Context) -> 平板判定开关，恒真
        val flagMatches = statics.filter {
            it.name == "o" &&
                it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == Context::class.java &&
                it.returnType == Boolean::class.javaPrimitiveType
        }
        HookUtils.uniqueMatchOrNull(
            flagMatches,
            RULE_APP_SETTING_FLAG,
            "AppSetting.o(Context)",
            processName
        )?.let { method ->
            runCatching {
                method.isAccessible = true
                xposed.hook(method)
                    .setId(RULE_APP_SETTING_FLAG)
                    .intercept { chain ->
                        // 先看原生的答案：为真说明 QQ 自己也认为这是平板环境，
                        // 那么 e()/i() 的原生实现就是可信的，不用我们再写死。
                        val native = runCatching { chain.proceed() }.getOrNull()
                        if (native == true) {
                            nativePadFlagSeen.set(true)
                        }
                        probeNativeValue(RULE_APP_SETTING_FLAG, "o(Context)", native)
                        true
                    }
                HookUtils.ruleInstalled(RULE_APP_SETTING_FLAG, 1, processName)
                markInstalled(RULE_APP_SETTING_FLAG)
                log("[$tag] ${RULE_APP_SETTING_FLAG} AppSetting.o() -> true")
            }.onFailure { log("[$tag] ${RULE_APP_SETTING_FLAG} hook 失败：$it") }
        } ?: markFailed(RULE_APP_SETTING_FLAG)
    }

    /**
     * 接管 `AppSetting.e()`。
     *
     * 不再无脑返回写死的 appid —— 先问一次原生：
     * - 原生 `o()` 曾返回 true → 原生的 `e()` 已经是当前版本正确的平板 appid，直接用它；
     * - 否则（原生仍走手机分支）→ 用 [PAD_APP_ID] 兜底。
     *
     * 这样 QQ 换平板 appid 时我们自动跟随，不会再出现「客户端自称平板但服务端不认」的静默降级。
     */
    private fun hookPadAppId(method: Method, tag: String): Boolean {
        return runCatching {
            method.isAccessible = true
            xposed.hook(method)
                .setId(RULE_APP_SETTING_APPID)
                .intercept { chain ->
                    val native = runCatching { chain.proceed() }.getOrNull()
                    val padFlag = nativePadFlagSeen.get()
                    val useNative = padFlag && native is Int && native > 0
                    val chosen: Any = if (useNative) {
                        native as Int
                    } else {
                        PAD_APP_ID
                    }
                    resolvedPadAppId.set(chosen as Int)
                    probeNativeValue(
                        RULE_APP_SETTING_APPID,
                        "e() native=$native padFlagSeen=$padFlag chosen=${if (useNative) "follow-native" else "fallback"}:$chosen",
                        chosen
                    )
                    chosen
                }
            HookUtils.ruleInstalled(RULE_APP_SETTING_APPID, 1, processName)
            markInstalled(RULE_APP_SETTING_APPID)
            true
        }.getOrElse {
            log("[$tag] $RULE_APP_SETTING_APPID hook 失败：$it")
            false
        }
    }

    /** 接管 `AppSetting.i()`，策略同 [hookPadAppId]。 */
    private fun hookPadKey(method: Method, tag: String): Boolean {
        return runCatching {
            method.isAccessible = true
            xposed.hook(method)
                .setId(RULE_APP_SETTING_KEY)
                .intercept { chain ->
                    // key 和 appid 必须同源。若平板判定还没确认，先问一次 e()：
                    // 它会连锁走到 o()，把原生判定结果补上，避免 appid 跟随新版、
                    // key 却仍兜底到旧常量这种「半跟随」错配。
                    if (!nativePadFlagSeen.get()) {
                        currentPadAppId()
                    }
                    val native = runCatching { chain.proceed() }.getOrNull() as? String
                    // 用显式分支标记，不能靠「返回值 == 兜底常量」判断：
                    // 原生 key 与兜底常量恰好相等时会把 follow 误报成 fallback。
                    val useNative = nativePadFlagSeen.get() && !native.isNullOrEmpty()
                    val chosen: Any = if (useNative) {
                        native!!
                    } else {
                        PAD_KEY
                    }
                    resolvedPadKey.set(chosen as String)
                    probeNativeValue(
                        RULE_APP_SETTING_KEY,
                        "i() native=${if (native.isNullOrEmpty()) "null" else "****"} chosen=${if (useNative) "follow-native" else "fallback"}",
                        chosen
                    )
                    chosen
                }
            HookUtils.ruleInstalled(RULE_APP_SETTING_KEY, 1, processName)
            markInstalled(RULE_APP_SETTING_KEY)
            true
        }.getOrElse {
            log("[$tag] $RULE_APP_SETTING_KEY hook 失败：$it")
            false
        }
    }

    /**
     * 当前应当上报给 NT 的平板 appid。
     *
     * 优先用原生 `e()` 已经给出的值（此时 `e()` 已被接管，调用它就是跟随逻辑）；
     * 拿不到才回退到写死的 [PAD_APP_ID]。
     */
    private fun currentPadAppId(): Int {
        val cached = resolvedPadAppId.get()
        if (cached > 0) {
            return cached
        }
        val viaNative = runCatching {
            appSettingClass?.getMethod("e")?.invoke(null) as? Int
        }.getOrNull()
        return if (viaNative != null && viaNative > 0) viaNative else PAD_APP_ID
    }

    // ---------------- appid 来源探针（只读） ----------------

    /**
     * 记录一次「原生值 / 最终选择」，每个规则 + 进程只记一次，避免高频方法刷屏。
     *
     * `e()` / `i()` 已经不再无脑返回常量，这里的日志就是判断「跟随原生」是否真的生效的直接依据。
     */
    private fun probeNativeValue(ruleId: String, label: String, value: Any?) {
        if (!ModuleConfig.PROBE_QQ_APP_ID) {
            return
        }
        if (!probedKeys.add("$ruleId@$processName")) {
            return
        }
        log("PROBE rule=$ruleId $label => $value")
    }

    /**
     * dump `AppSetting` 的全部静态 int / String 字段。
     *
     * 用来确认 QQ 自带的「手机 appid / 平板 appid / 对应 key」四个值分别落在哪个字段上，
     * 以及当前版本是否仍然沿用 `e() = o(ctx) ? 平板 : 手机` 这一组合方式。
     */
    private fun probeAppSettingFields(appSetting: Class<*>, tag: String) {
        if (!ModuleConfig.PROBE_QQ_APP_SETTING_FIELDS) {
            return
        }
        if (!probedKeys.add("$RULE_PROBE_FIELDS@$processName")) {
            return
        }
        runCatching {
            val parts = mutableListOf<String>()
            for (field in appSetting.declaredFields) {
                if (!Modifier.isStatic(field.modifiers)) {
                    continue
                }
                val type = field.type
                if (type != Int::class.javaPrimitiveType && type != String::class.java) {
                    continue
                }
                field.isAccessible = true
                val value = runCatching { field.get(null) }.getOrNull()
                val shown = when {
                    value == null -> "null"
                    value is String && value.length > 48 -> value.take(48) + "…"
                    else -> value.toString()
                }
                parts.add("${field.name}=$shown")
            }
            log("[$tag] PROBE static-fields ${parts.joinToString(" ")}")
        }.onFailure { log("[$tag] PROBE static-fields 读取失败：$it") }
    }

    // ---------------- PadUtil ----------------

    private fun hookPadUtil(loader: ClassLoader, tag: String) {
        val padUtil = runCatching {
            Class.forName(CLS_PAD_UTIL, false, loader)
        }.getOrNull()
        val deviceType = runCatching {
            Class.forName(CLS_PAD_DEVICE_TYPE, false, loader)
        }.getOrNull()
        if (padUtil == null || deviceType == null) {
            HookUtils.ruleFailure(RULE_PAD_UTIL, "PadUtil/DeviceType", "TARGET_CLASS_MISSING", processName)
            markFailed(RULE_PAD_UTIL)
            return
        }
        val tablet = enumConstant(deviceType, "TABLET")
        if (tablet == null) {
            HookUtils.ruleFailure(RULE_PAD_UTIL, "DeviceType.TABLET", "ENUM_MISSING", processName)
            markFailed(RULE_PAD_UTIL)
            return
        }
        val matches = padUtil.declaredMethods.filter { method ->
            method.name == "a" &&
                method.parameterTypes.size == 1 &&
                method.parameterTypes[0] == Context::class.java &&
                method.returnType == deviceType
        }
        val method = HookUtils.uniqueMatchOrNull(
            matches,
            RULE_PAD_UTIL,
            "PadUtil.a(Context)->DeviceType",
            processName
        )
        if (method == null) {
            markFailed(RULE_PAD_UTIL)
            return
        }
        runCatching {
            method.isAccessible = true
            xposed.hook(method)
                .setId(RULE_PAD_UTIL)
                .intercept { tablet }
            HookUtils.ruleInstalled(RULE_PAD_UTIL, 1, processName)
            markInstalled(RULE_PAD_UTIL)
            log("[$tag] $RULE_PAD_UTIL PadUtil.a -> TABLET")
        }.onFailure {
            log("[$tag] $RULE_PAD_UTIL hook 失败：$it")
            markFailed(RULE_PAD_UTIL)
        }
    }

    private fun hookIsPadForMultiDeviceLogin(loader: ClassLoader, tag: String) {
        val cls = runCatching {
            Class.forName(CLS_INJECTOR, false, loader)
        }.getOrNull()
        if (cls == null) {
            HookUtils.ruleFailure(RULE_MULTI_DEVICE_PAD, CLS_INJECTOR, "TARGET_CLASS_MISSING", processName)
            markFailed(RULE_MULTI_DEVICE_PAD)
            return
        }
        val named = cls.declaredMethods.filter { it.name == "isPadForMultiDeviceLogin" }
        val matches = named.filter { it.returnType == Boolean::class.javaPrimitiveType }
        if (named.isNotEmpty() && matches.isEmpty()) {
            HookUtils.ruleFailure(
                RULE_MULTI_DEVICE_PAD,
                "isPadForMultiDeviceLogin",
                "SIGNATURE_CHANGED (returnType!=boolean)",
                processName
            )
            markFailed(RULE_MULTI_DEVICE_PAD)
            return
        }
        val method = HookUtils.uniqueMatchOrNull(
            matches,
            RULE_MULTI_DEVICE_PAD,
            "isPadForMultiDeviceLogin()",
            processName
        )
        if (method == null) {
            markFailed(RULE_MULTI_DEVICE_PAD)
            return
        }
        runCatching {
            method.isAccessible = true
            xposed.hook(method)
                .setId(RULE_MULTI_DEVICE_PAD)
                .intercept { true }
            HookUtils.ruleInstalled(RULE_MULTI_DEVICE_PAD, 1, processName)
            markInstalled(RULE_MULTI_DEVICE_PAD)
            log("[$tag] $RULE_MULTI_DEVICE_PAD isPadForMultiDeviceLogin -> true")
        }.onFailure {
            log("[$tag] $RULE_MULTI_DEVICE_PAD hook 失败：$it")
            markFailed(RULE_MULTI_DEVICE_PAD)
        }
    }

    // ---------------- NT 内核会话 ----------------

    private fun hookSessionInit(loader: ClassLoader, tag: String) {
        val cfgClass = runCatching {
            Class.forName(CLS_INIT_SESSION_CONFIG, false, loader)
        }.getOrNull()
        if (cfgClass == null) {
            HookUtils.ruleFailure(RULE_SESSION_INIT, CLS_INIT_SESSION_CONFIG, "TARGET_CLASS_MISSING", processName)
            markFailed(RULE_SESSION_INIT)
            return
        }
        val proxy = runCatching {
            Class.forName(CLS_SESSION_PROXY, false, loader)
        }.getOrNull()
        if (proxy == null) {
            HookUtils.ruleFailure(RULE_SESSION_INIT, CLS_SESSION_PROXY, "TARGET_CLASS_MISSING", processName)
            markFailed(RULE_SESSION_INIT)
            return
        }
        val pad = runCatching {
            enumConstant(Class.forName(CLS_NT_DEVICE_TYPE, false, loader), "KPAD")
        }.getOrNull()
        val androidPlatform = runCatching {
            enumConstant(Class.forName(CLS_NT_PLATFORM_TYPE, false, loader), "KANDROID")
        }.getOrNull()

        val matches = proxy.declaredMethods.filter { method ->
            method.name == "init" &&
                method.parameterTypes.size == 4 &&
                method.parameterTypes[0] == cfgClass
        }
        val method = HookUtils.uniqueMatchOrNull(
            matches,
            RULE_SESSION_INIT,
            "IQQNTWrapperSession\$CppProxy.init(InitSessionConfig,...)",
            processName
        )
        if (method == null) {
            markFailed(RULE_SESSION_INIT)
            return
        }
        runCatching {
            method.isAccessible = true
            xposed.hook(method)
                .setId(RULE_SESSION_INIT)
                .intercept { chain ->
                    val config = chain.getArg(0)
                    if (config != null && !ModuleConfig.QQ_DRY_RUN) {
                        if (ModuleConfig.LOG_QQ_SESSION) {
                            dumpSessionConfig(tag, config, "before")
                        }
                        pad?.let { setField(config, "deviceType", it, tag) }
                        setField(config, "appid", currentPadAppId().toString(), tag)
                        androidPlatform?.let { setField(config, "platform", it, tag) }
                        if (ModuleConfig.LOG_QQ_SESSION) {
                            dumpSessionConfig(tag, config, "after")
                        }
                    }
                    chain.proceed()
                }
            HookUtils.ruleInstalled(RULE_SESSION_INIT, 1, processName)
            markInstalled(RULE_SESSION_INIT)
            log("[$tag] $RULE_SESSION_INIT NT 会话已接管（forcePad=${!ModuleConfig.QQ_DRY_RUN}）")
        }.onFailure {
            log("[$tag] $RULE_SESSION_INIT hook 失败：$it")
            markFailed(RULE_SESSION_INIT)
        }
    }

    private fun dumpSessionConfig(tag: String, config: Any, stage: String) {
        runCatching {
            val builder = StringBuilder("[$tag] InitSessionConfig($stage)")
                .append(" deviceType=").append(getField(config, "deviceType"))
                .append(" platform=").append(getField(config, "platform"))
                .append(" appid=").append(getField(config, "appid"))
                .append(" clientVer=").append(getField(config, "clientVer"))
            val deviceInfo = getField(config, "deviceInfo")
            if (deviceInfo != null && deviceInfo !is String) {
                builder.append(" | deviceInfo{")
                    .append("devName=").append(getField(deviceInfo, "devName"))
                    .append(",devType=").append(getField(deviceInfo, "devType"))
                    .append(",vendorName=").append(getField(deviceInfo, "vendorName"))
                    .append(",osVer=").append(getField(deviceInfo, "osVer"))
                    .append('}')
            }
            if (sessionLogged.compareAndSet(false, true)) {
                log(builder.toString())
            }
        }
    }

    // ---------------- 在线设备列表（诊断） ----------------

    private fun hookRegisterProxy(loader: ClassLoader, tag: String) {
        if (!ModuleConfig.LOG_QQ_SESSION) {
            return
        }
        val handler = runCatching {
            Class.forName(CLS_REGISTER_PROXY, false, loader)
        }.getOrNull()
        val pushParams = runCatching {
            Class.forName(CLS_PUSH_PARAMS, false, loader)
        }.getOrNull()
        if (handler == null || pushParams == null) {
            HookUtils.ruleFailure(RULE_ONLINE_DEVICES, "RegisterProxySvcPackHandler", "TARGET_CLASS_MISSING", processName)
            markFailed(RULE_ONLINE_DEVICES)
            return
        }
        val matches = handler.declaredMethods.filter { method ->
            method.name == "e3" &&
                method.parameterTypes.size == 1 &&
                method.parameterTypes[0] == pushParams
        }
        val method = HookUtils.uniqueMatchOrNull(
            matches,
            RULE_ONLINE_DEVICES,
            "RegisterProxySvcPackHandler.e3(PushParams)",
            processName
        )
        if (method == null) {
            markFailed(RULE_ONLINE_DEVICES)
            return
        }
        runCatching {
            method.isAccessible = true
            xposed.hook(method)
                .setId(RULE_ONLINE_DEVICES)
                .intercept { chain ->
                    val result = chain.proceed()
                    runCatching { dumpOnlineDevices(chain.getArg(0)) }
                    result
                }
            HookUtils.ruleInstalled(RULE_ONLINE_DEVICES, 1, processName)
            markInstalled(RULE_ONLINE_DEVICES)
            log("[$tag] $RULE_ONLINE_DEVICES 在线设备诊断已挂载")
        }.onFailure {
            log("[$tag] $RULE_ONLINE_DEVICES hook 失败：$it")
            markFailed(RULE_ONLINE_DEVICES)
        }
    }

    private fun dumpOnlineDevices(params: Any?) {
        if (params == null) {
            return
        }
        val list = callGet(getField(params, "online_infos"))
        if (list !is List<*>) {
            log("SERVER online devices: $list")
            return
        }
        val builder = StringBuilder("SERVER online devices: n=${list.size}")
        list.forEachIndexed { index, entry ->
            if (entry == null) {
                return@forEachIndexed
            }
            builder.append(" |#").append(index + 1)
                .append(" client_type=").append(callGet(getField(entry, "client_type")))
                .append(" platform_id=").append(callGet(getField(entry, "platform_id")))
                .append(" appid=").append(callGet(getField(entry, "appid")))
                .append(" devName=").append(callGet(getField(entry, "dev_name")))
        }
        log(builder.toString())
    }

    // ---------------- 诊断 ----------------

    private fun logDeviceProfile(processName: String) {
        val metrics = runCatching {
            val system = Class.forName("android.content.res.Resources")
                .getMethod("getSystem")
                .invoke(null)
            system.javaClass.getMethod("getDisplayMetrics").invoke(system) as DisplayMetrics
        }.getOrNull()
        val screen = metrics?.let {
            "${it.widthPixels}x${it.heightPixels} @ ${it.densityDpi}dpi"
        } ?: "读取失败"
        log(
            "QQ 设备画像：进程=$processName，" +
                "机型=${Build.MANUFACTURER} ${Build.MODEL}，屏幕=$screen"
        )
    }

    private fun log(message: String) {
        xposed.log(Log.INFO, HookUtils.TAG, message)
        HookUtils.trace(appDataDir, "qq $message")
    }

    companion object {
        const val PACKAGE_NAME = "com.tencent.mobileqq"
        const val MAIN_PROCESS_NAME = PACKAGE_NAME
        const val MSF_PROCESS_NAME = "$PACKAGE_NAME:MSF"

        fun isTargetProcess(name: String): Boolean = when (name) {
            MSF_PROCESS_NAME -> true
            MAIN_PROCESS_NAME -> ModuleConfig.HOOK_QQ_MAIN_PROCESS
            else -> false
        }

        /**
         * 兜底值（QQ 9.3.75 实测）。
         *
         * **不再是唯一来源**：`e()` / `i()` 已改为优先跟随原生返回值
         * （只要原生 `o()` 走过平板分支），这两个常量只在原生仍走手机分支时兜底。
         */
        private const val PAD_APP_ID = 537397816
        private const val PAD_KEY = "0FFD632C4B7E08AF"

        private const val CLS_APP_SETTING = "com.tencent.common.config.AppSetting"
        private const val CLS_PAD_UTIL = "com.tencent.common.config.pad.PadUtil"
        private const val CLS_PAD_DEVICE_TYPE = "com.tencent.common.config.pad.DeviceType"
        private const val CLS_INJECTOR = "com.tencent.mobileqq.injector.d"
        private const val CLS_INIT_SESSION_CONFIG =
            "com.tencent.qqnt.kernel.nativeinterface.InitSessionConfig"
        private const val CLS_NT_DEVICE_TYPE =
            "com.tencent.qqnt.kernel.nativeinterface.DeviceType"
        private const val CLS_NT_PLATFORM_TYPE =
            "com.tencent.qqnt.kernel.nativeinterface.PlatformType"
        private const val CLS_SESSION_PROXY =
            "com.tencent.qqnt.kernel.nativeinterface.IQQNTWrapperSession\$CppProxy"
        private const val CLS_REGISTER_PROXY = "com.tencent.mobileqq.app.RegisterProxySvcPackHandler"
        private const val CLS_PUSH_PARAMS = "tencent.im.msg.nt_register_proxy\$PushParams"

        /**
         * 不可变规则 ID。命名与 ImPad 的 `qq_device_type_001` 同风格，
         * 目的是一眼能对着日志定位是哪条规则失效。
         */
        private const val RULE_APP_SETTING = "qq_app_setting_001"
        private const val RULE_APP_SETTING_APPID = "qq_app_setting_appid_001"
        private const val RULE_APP_SETTING_KEY = "qq_app_setting_key_001"
        private const val RULE_APP_SETTING_FLAG = "qq_app_setting_pad_flag_001"
        private const val RULE_PAD_UTIL = "qq_pad_util_001"
        private const val RULE_MULTI_DEVICE_PAD = "qq_multi_device_pad_001"
        private const val RULE_SESSION_INIT = "qq_session_init_001"
        private const val RULE_ONLINE_DEVICES = "qq_online_devices_001"

        private const val DELEGATE_LOADER_HOOK_ID = "qq_delegate_loader"

        /** 探针内部用的去重键，不是真实 hook 规则。 */
        private const val RULE_PROBE_FIELDS = "qq_probe_appsetting_fields"

        private fun enumConstant(enumClass: Class<*>, name: String): Any? {
            val constants = enumClass.enumConstants ?: return null
            return constants.firstOrNull { it is Enum<*> && it.name == name }
        }

        private fun getField(target: Any, name: String): Any? {
            var cls: Class<*>? = target.javaClass
            while (cls != null) {
                try {
                    val field = cls.getDeclaredField(name)
                    field.isAccessible = true
                    return field.get(target)
                } catch (_: NoSuchFieldException) {
                    cls = cls.superclass
                } catch (_: Throwable) {
                    return null
                }
            }
            return null
        }

        private fun setField(target: Any, name: String, value: Any?, tag: String) {
            var cls: Class<*>? = target.javaClass
            while (cls != null) {
                try {
                    val field: Field = cls.getDeclaredField(name)
                    field.isAccessible = true
                    field.set(target, value)
                    return
                } catch (_: NoSuchFieldException) {
                    cls = cls.superclass
                } catch (throwable: Throwable) {
                    Log.w(HookUtils.TAG, "[$tag] 写入 $name 失败：$throwable")
                    return
                }
            }
            Log.w(HookUtils.TAG, "[$tag] 字段不存在：$name")
        }

        private fun callGet(holder: Any?): Any? {
            if (holder == null) {
                return null
            }
            return runCatching {
                holder.javaClass.getMethod("get").invoke(holder)
            }.getOrElse { holder.toString() }
        }
    }
}
