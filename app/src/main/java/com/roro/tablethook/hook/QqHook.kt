package com.roro.tablethook.hook

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
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
 * 1. AppSetting.e()  -> 平板 appid（来自 manifest 的平板凭证，与 i() 同源）
 * 2. AppSetting.i()  -> 平板 appid 对应的 key（同一份凭证）
 * 3. AppSetting.o(Context) -> true（平板判定开关）
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

    private var processName: String = PACKAGE_NAME
    private var appDataDir: String? = null

    /** 规则汇总用同一把锁，避免 installed / failed 两个集合各自加锁时出现不一致快照。 */
    private val rulesLock = Any()
    private val installedRules = linkedSetOf<String>()
    private val failedRules = linkedSetOf<String>()

    /**
     * 实际承担过安装的 loader 标签（app / dlcl / tinker / reentry）。
     *
     * 必要性来自实测：QQ 启用了 Tinker 全量补丁，`AppSetting` 在进程里有两个类对象 ——
     * base.apk 一个、补丁一个，业务用的是补丁那份。只在 `[app]` 上装成功时日志同样显示
     * 「6 条规则全装上」，但实际毫无作用。把装过哪些 loader 写进汇总，才不会把
     * 「装在了没人用的类上」误读成「一切正常」。
     */
    private val appliedTags: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private val probedKeys: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** 会话配置 dump 的 stage 去重（before / after 各自一次）。 */
    private val dumpedStages: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** `PadUtil.a()` 的 hook 是否装上，仅用于诊断输出，不参与取值决策。 */
    private val padUtilHooked = AtomicBoolean(false)

    /** 一组平板凭证，appid 与 key 必须成对出现。 */
    data class PadCred(val appId: Int, val key: String, val source: String)

    /**
     * 已经成功解析到的 manifest 平板凭证。
     *
     * **只有真值才写进来**。读 manifest 失败时不会把 const 兜底值永久锁死，
     * 下次调用还会重试（5 秒节流）。
     *
     * 历史教训：这一份原本是 `by lazy { resolvePadCredential() }`。`e()` 首次被调用
     * 的时机可能早于 Application 就绪（`:MSF` 进程尤其明显），那次拿不到 Context
     * 就退回编译期常量，而 `lazy` 会把这个结果**永久缓存**——之后 Context 就绪了
     * 也再不会去看 manifest。表现就是「装上当天正常，某天 QQ 一升级就静默降级」。
     */
    @Volatile
    private var cachedManifestCred: PadCred? = null

    /** 下次允许重试解析 manifest 的时间点（`SystemClock.elapsedRealtime`）。 */
    @Volatile
    private var nextCredentialRetryAt = 0L

    private val credentialLock = Any()

    /** 编译期常量兜底凭证，与 QQ 9.3.75 manifest 平板项一致。 */
    private val constCred = PadCred(PAD_APP_ID, PAD_KEY, SRC_CONST)

    /** 启动时拿到的 app loader，用于在没有 Application 的进程里反射读 manifest。 */
    @Volatile
    private var appLoader: ClassLoader? = null

    /** `AppSetting` 类对象，供延迟补初始化时反射调用。 */
    @Volatile
    private var appSettingClass: Class<*>? = null

    /** 是否已经补过一次原生 `AppSetting` 初始化。 */
    private val nativePrimed = AtomicBoolean(false)

    /** 幂等标记：同一进程的重复 `install` 回调只做第一次。 */
    private val bootstrapped = AtomicBoolean(false)

    fun install(classLoader: ClassLoader, processName: String, appDataDir: String?) {
        // 先落这两个字段，后面的日志才有地方可写。
        this.processName = processName
        this.appDataDir = appDataDir
        if (!bootstrapped.compareAndSet(false, true)) {
            // 重复回调：不再重复 hook DLCL 构造器、也不再重打汇总，
            // 但如果给的是另一个 loader，仍要补装（installOn 内部按 loader 去重），
            // 否则第二次回调带来的那个「更正确」的 loader 会漏装。
            if (classLoader !== appLoader) {
                log("[reentry] 重复 install 回调，补装到新 loader：${classLoader.javaClass.name}")
                // 新 loader 往往比首次回调时的更接近真实业务 loader（首次可能只是
                // base.apk 的 PathClassLoader）。appContext() 的 BaseApplication 兜底
                // 依赖 appLoader，一直用旧的话，:MSF 这种拿不到 Application 的进程
                // 会一直读不到 manifest —— 表现为「凭证长期走常量兜底」。
                if (canLoadBaseApplication(classLoader)) {
                    appLoader = classLoader
                    log("[reentry] appLoader 已更新为：${classLoader.javaClass.name}")
                }
                runCatching { installOn(classLoader, "reentry") }.onFailure {
                    log("[reentry] installOn 阶段异常：$it")
                }
            } else {
                log("[skip] 重复 install 回调，同一 loader 已装过，忽略")
            }
            return
        }
        this.appLoader = classLoader
        if (ModuleConfig.LOG_DEVICE_PROFILE) {
            logDeviceProfile(processName)
        }
        // 三个阶段各自独立兜异常，且把最关键的 DelegateLastClassLoader 放到最前面。
        //
        // 原来这三步是裸调用串行执行：`installOn` 里任何一处抛出（比如某个 loader 里的
        // AppSetting 依赖类缺失，`declaredMethods` 触发 NoClassDefFoundError），
        // 后面的 `hookDelegateLoader()` 就再也不会执行 —— 而 DLCL 钩子是「拿到插件真实
        // loader」的唯一途径，它没装上，NT 相关类会全部漏 hook，且日志里只有一句
        // 初始化失败，看不出到底是哪一步断的。
        runCatching { hookDelegateLoader() }.onFailure {
            log("hookDelegateLoader 阶段异常：$it")
        }
        runCatching { installOn(classLoader, "app") }.onFailure {
            log("installOn(app) 阶段异常：$it")
        }
        runCatching { logSummary() }.onFailure {
            log("logSummary 阶段异常：$it")
        }
        scheduleLoaderCoverageCheck()
    }

    /**
     * 启动 5 秒后自检一次「业务真正使用的 loader 有没有被覆盖」。
     *
     * 这是针对本模块最隐蔽失效模式的兜底判断。实测 QQ 加载 Tinker 全量补丁后，
     * 同一进程里 `AppSetting` 有两个类对象：base.apk 一个、补丁一个，业务只用补丁那份。
     * 如果补丁 loader 没被捕获，`[app]` 那次安装照样打出「6 条规则全装上」，
     * summary 也显示 installed=6 failed=0 —— 一切看起来正常，功能却是死的。
     *
     * 只在「补丁目录存在」时才起这个线程，正常场景（无补丁）零开销。
     */
    private fun scheduleLoaderCoverageCheck() {
        if (!tinkerPatchPresent()) {
            return
        }
        Thread {
            runCatching {
                Thread.sleep(LOADER_COVERAGE_DELAY_MS)
                val tags = appliedTags.toList()
                val covered = tags.contains("dlcl") || tags.contains("tinker")
                if (covered) {
                    log("LOADER_COVERAGE 正常：补丁 loader 已覆盖 loaders=[${tags.joinToString(",")}]")
                } else {
                    log(
                        "LOADER_COVERAGE 异常：Tinker 补丁存在，但 ${LOADER_COVERAGE_DELAY_MS}ms 内没有" +
                            "捕获到补丁 loader，hook 只装在 [${tags.joinToString(",")}] 上。" +
                            "若业务类由补丁定义，本次平板模式不会生效（设备上服务端大概率仍显示手机）"
                    )
                }
            }
        }.apply {
            isDaemon = true
            name = "qq-loader-coverage"
        }.start()
    }

    /** 一条规则装完了，记下来用于最后汇总。 */
    private fun markInstalled(ruleId: String) {
        synchronized(rulesLock) {
            installedRules.add(ruleId)
            // 互斥：同一条规则可能先安装成功、随后在运行时改写失败（比如
            // InitSessionConfig 写入失败回滚）。两个集合同时包含它，summary 会打出
            // installed=6 failed=1 这种自相矛盾的行，排查时反而被误导。
            failedRules.remove(ruleId)
        }
    }

    private fun markFailed(ruleId: String) {
        synchronized(rulesLock) {
            failedRules.add(ruleId)
            installedRules.remove(ruleId)
        }
    }

    /**
     * 启动时打一条汇总：哪些规则装上了、哪些因为 QQ 版本变化没装上。
     *
     * 排查时只看这一行就够——不用再猜是模块没生效还是 QQ 改了签名。
     */
    private fun logSummary() {
        val (ok, bad) = synchronized(rulesLock) {
            installedRules.toList() to failedRules.toList()
        }
        val line = "rule-runtime event=summary process=$processName installed=${ok.size}" +
            " failed=${bad.size}" +
            " loaders=[${appliedTags.joinToString(",")}]" +
            (if (ok.isEmpty()) "" else " ok=[${ok.joinToString(",")}]") +
            (if (bad.isEmpty()) "" else " bad=[${bad.joinToString(",")}]")
        log(line)
    }

    // ---------------- 类加载器 ----------------

    /**
     * QQ 用 Tinker 做全量 dex 替换（实测 `tinker/patch-<hash>/dex/tinker_classN.apk`
     * 达 421MB），补丁里的类由 **DelegateLastClassLoader** 定义 —— 已由类来源探针实测：
     *
     * ```
     * [app]  类来源 AppSetting loader=PathClassLoader            dex=[base.apk]
     * [dlcl] 类来源 AppSetting loader=DelegateLastClassLoader    dex=[TINKER:tinker_classN.apk]
     * ```
     *
     * 也就是说同一个进程里存在**两个不同的 AppSetting 类对象**，业务实际使用的是补丁那份。
     * `[app]` 那次安装打在 base.apk 的类上，日志同样打出「6 条规则全装上」，但完全没有作用。
     *
     * 后果：平板模式的正确性完全押在 DLCL 这一条通路上。一旦这个 loader 换实现或不存在
     * （`DelegateLastClassLoader` 是 API 27 才有的类），业务类就再也不会被 hook，
     * 而所有日志看起来都正常 —— 这是本模块最难发现的失效。
     *
     * 因此这里同时挂 Tinker 自己的 `TinkerClassLoader`：两条通路互为备份，
     * `installOn` 内部按 loader 引用去重，不会重复安装。
     */
    private fun hookDelegateLoader() {
        if (!delegateLoaderHooked.compareAndSet(false, true)) {
            return
        }
        // 系统类，用 bootstrap loader 即可解析。
        hookLoaderConstructors(
            className = DLCL_CLASS,
            loader = null,
            tag = "dlcl",
            hookId = DELEGATE_LOADER_HOOK_ID
        )
        // Tinker 的 loader 是应用类，必须用 QQ 的 loader 解析；不存在就说明这条通路不适用。
        hookLoaderConstructors(
            className = TINKER_LOADER_CLASS,
            loader = appLoader,
            tag = "tinker",
            hookId = TINKER_LOADER_HOOK_ID
        )
    }

    private fun hookLoaderConstructors(
        className: String,
        loader: ClassLoader?,
        tag: String,
        hookId: String
    ) {
        val cls = runCatching { Class.forName(className, false, loader) }.getOrNull()
        if (cls == null) {
            log("$className 不存在，跳过（该 loader 类型未被 QQ 使用）")
            return
        }
        var count = 0
        var failed = 0
        // 逐个构造器单独兜异常：某个（比如已废弃的那个）挂不上，
        // 不能让其余几个跟着一起不装。
        for (ctor in cls.declaredConstructors) {
            val ok = runCatching {
                ctor.isAccessible = true
                HookUtils.safeHook(xposed, ctor, hookId)
                    .intercept { chain ->
                        val result = chain.proceed()
                        runCatching {
                            (chain.thisObject as? ClassLoader)?.let { installOn(it, tag) }
                        }
                        result
                    }
            }.isSuccess
            if (ok) count++ else failed++
        }
        log("$className 构造器已 hook x$count（失败 $failed）")
    }

    private fun installOn(loader: ClassLoader, tag: String) {
        val appSetting = runCatching {
            Class.forName(CLS_APP_SETTING, false, loader)
        }.getOrNull()
        if (appSetting == null) {
            // QQ 会为各种插件创建多个 loader，绝大多数里面没有 AppSetting。
            // 只在首次记录失败，避免 `[dlcl]` 每次都刷一条（真正的失败信息已经进 summary）。
            if (probedKeys.add("$RULE_APP_SETTING@missing@$processName")) {
                HookUtils.ruleFailure(
                    RULE_APP_SETTING,
                    CLS_APP_SETTING,
                    "TARGET_CLASS_MISSING（该 loader 不含业务类，属正常）",
                    processName
                )
            }
            markFailed(RULE_APP_SETTING)
            return
        }
        synchronized(installedLoaders) {
            if (!installedLoaders.add(loader)) {
                return
            }
        }
        appliedTags.add(tag)
        log("[$tag] 开始安装 QQ 平板 hook：${loader.javaClass.name}")
        // 每条规则独立兜异常。原来五条裸调用串行执行，`hookAppSetting` 里任何一处抛出
        // （例如插件 loader 缺依赖类时 `declaredMethods` 触发 NoClassDefFoundError）
        // 后面四条就全部不装，而且只有一句「初始化失败」看不出断在哪。
        runCatching { hookAppSetting(appSetting, tag) }
            .onFailure { log("[$tag] hookAppSetting 阶段异常：$it") }
        runCatching { hookPadUtil(loader, tag) }
            .onFailure { log("[$tag] hookPadUtil 阶段异常：$it") }
        runCatching { hookIsPadForMultiDeviceLogin(loader, tag) }
            .onFailure { log("[$tag] hookIsPadForMultiDeviceLogin 阶段异常：$it") }
        runCatching { hookSessionInit(loader, tag) }
            .onFailure { log("[$tag] hookSessionInit 阶段异常：$it") }
        runCatching { hookRegisterProxy(loader, tag) }
            .onFailure { log("[$tag] hookRegisterProxy 阶段异常：$it") }
        // 后到的 loader（dlcl / tinker）才是业务真正在用的那一份，
        // 装完补打一条汇总，否则只看首条 summary 会漏掉它。
        if (tag != "app") {
            runCatching { logSummary() }
        }
    }

    // ---------------- AppSetting ----------------

    private fun hookAppSetting(appSetting: Class<*>, tag: String) {
        appSettingClass = appSetting
        logClassProvenance(appSetting, tag)
        probeAppSettingFields(appSetting, tag)

        val statics = appSetting.declaredMethods.filter { method ->
            Modifier.isStatic(method.modifiers) && method.parameterTypes.size <= 1
        }

        // e() -> 平板 appid：与 i() 同源取值，保证配对
        // 优先认无参版本；只有 QQ 改了签名（比如加了 Context 参数）才退回单参版本，
        // 避免将来多出一个 e(int) 重载时把 no-arg 版本挤成 AMBIGUOUS 而整条规则失效。
        val appIdCandidates = statics.filter {
            it.name == "e" && it.returnType == Int::class.javaPrimitiveType
        }
        val appIdMatches = appIdCandidates.filter { it.parameterTypes.isEmpty() }
            .ifEmpty { appIdCandidates }
        HookUtils.uniqueMatchOrNull(appIdMatches, RULE_APP_SETTING_APPID, "AppSetting.e()", processName)
            ?.let { method ->
                if (hookPadAppId(method, tag)) {
                    log("[$tag] ${RULE_APP_SETTING_APPID} AppSetting.e() 已接管（同源凭证）")
                }
            }
            ?: markFailed(RULE_APP_SETTING_APPID)

        // i() -> 平板 appid 对应的 key：与 e() 同一来源
        val keyCandidates = statics.filter {
            it.name == "i" && it.returnType == String::class.java
        }
        val keyMatches = keyCandidates.filter { it.parameterTypes.isEmpty() }
            .ifEmpty { keyCandidates }
        HookUtils.uniqueMatchOrNull(keyMatches, RULE_APP_SETTING_KEY, "AppSetting.i()", processName)
            ?.let { method ->
                if (hookPadKey(method, tag)) {
                    log("[$tag] ${RULE_APP_SETTING_KEY} AppSetting.i() 已接管（同源凭证）")
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
                HookUtils.safeHook(xposed, method, RULE_APP_SETTING_FLAG)
                    .intercept {
                        // 不再执行原生实现：原生 o() 只是 `PadUtil.a(ctx) == TABLET`，
                        // 而 PadUtil.a() 已被我们改成 TABLET，结论恒为 true；直接给结果
                        // 可以省掉一次跨层调用，也不会有任何副作用。
                        probeNativeValue(RULE_APP_SETTING_FLAG, "o(Context)", true)
                        true
                    }
                HookUtils.ruleInstalled(RULE_APP_SETTING_FLAG, 1, processName)
                markInstalled(RULE_APP_SETTING_FLAG)
                log("[$tag] ${RULE_APP_SETTING_FLAG} AppSetting.o() -> true")
            }.onFailure { log("[$tag] ${RULE_APP_SETTING_FLAG} hook 失败：$it") }
        } ?: markFailed(RULE_APP_SETTING_FLAG)
    }

    /**
     * 记录「我们 hook 的这个类究竟来自哪个 dex」。
     *
     * QQ 同样启用了 Tinker：实测 `/data/data/com.tencent.mobileqq/tinker/` 下有
     * `patch-<hash>/dex/tinker_classN.apk`（**421MB，全量 dex 替换**，不是小补丁）。
     *
     * 这是本模块最不容易发现的一类失效：如果补丁已加载，业务代码用的是补丁里的
     * `AppSetting`，而我们在 `onPackageReady` 阶段 `Class.forName` 拿到并 hook 的
     * 可能是 base.apk 里的同名类 —— 两者不是同一个 Class 对象，hook 装上、日志正常，
     * 但对运行中的代码毫无影响。微信侧早就用 `definedBy` 记了这件事，QQ 侧一直没有。
     *
     * 这里同时记录 dex 里是否出现 tinker 路径，以及「补丁目录存在但没加载」的提示，
     * 让这类失效在日志里一眼可判，而不是只能靠猜。
     */
    private fun logClassProvenance(cls: Class<*>, tag: String) {
        if (!probedKeys.add("provenance@$tag@$processName")) {
            return
        }
        runCatching {
            val loader = cls.classLoader
            val paths = dexPathsOf(loader)
            val tinkerLoaded = paths.any { it.contains("tinker", ignoreCase = true) }
            val shown = paths.joinToString(",") { path ->
                val name = path.substringAfterLast('/')
                if (path.contains("tinker", ignoreCase = true)) "TINKER:$name" else name
            }
            log(
                "[$tag] 类来源 ${cls.name} loader=${loader?.javaClass?.name ?: "bootstrap"} " +
                    "tinkerLoaded=$tinkerLoaded dex=[${shown.take(400)}]"
            )
            if (!tinkerLoaded && tinkerPatchPresent()) {
                log(
                    "[$tag] 提示：Tinker 补丁目录存在但当前未加载，hook 打在 base.apk 的类上。" +
                        "QQ 之后若启用补丁需重启进程重新挂钩，否则可能作用于错误的类对象"
                )
            }
        }.onFailure { log("[$tag] 类来源探针失败：$it") }
    }

    /** `tinker/` 下是否存在 `patch-*` 目录（只判断存在性，不读内容）。 */
    private fun tinkerPatchPresent(): Boolean = runCatching {
        val dir = java.io.File(appDataDir ?: return@runCatching false, "tinker")
        dir.isDirectory && dir.list()?.any { it.startsWith("patch") } == true
    }.getOrDefault(false)

    /**
     * 取一个 ClassLoader 实际加载的全部 dex / apk 路径。
     *
     * 走 `BaseDexClassLoader.pathList.dexElements[].path`（Android 8+ 为 `path`，
     * 更早版本为 `file`），只读不改。拿不到就返回空列表，不影响任何 hook 行为。
     */
    private fun dexPathsOf(loader: ClassLoader?): List<String> {
        if (loader == null) {
            return emptyList()
        }
        return runCatching {
            var cls: Class<*>? = loader.javaClass
            var pathListField: Field? = null
            while (cls != null && pathListField == null) {
                pathListField = runCatching { cls.getDeclaredField("pathList") }.getOrNull()
                cls = cls.superclass
            }
            val pathList = pathListField?.apply { isAccessible = true }?.get(loader)
                ?: return@runCatching emptyList()
            val elementsField = runCatching {
                pathList.javaClass.getDeclaredField("dexElements")
            }.getOrNull() ?: return@runCatching emptyList()
            elementsField.isAccessible = true
            val elements = elementsField.get(pathList) as? Array<*> ?: return@runCatching emptyList()
            elements.mapNotNull { element -> element?.let { elementPath(it) } }
        }.getOrDefault(emptyList())
    }

    private fun elementPath(element: Any): String? = runCatching {
        element.javaClass.declaredFields.firstNotNullOfOrNull { field ->
            if (field.name != "path" && field.name != "file") {
                return@firstNotNullOfOrNull null
            }
            field.isAccessible = true
            when (val value = field.get(element)) {
                is String -> value
                is java.io.File -> value.absolutePath
                else -> null
            }
        }
    }.getOrNull()

    /**
     * 主动触发一次原生 `AppSetting.a()` 链上的初始化。
     *
     * 原生 `a()` 只做一件事：确保 `u()` 执行过一次。而 `u()` 除了把 manifest 的平板
     * 凭证覆盖到 `g/j`、手机凭证覆盖到 `f/i`，还会把 manifest 的版本段拼进静态字段
     * `o`（客户端版本串 `9.3.75.42350.2026-10-13.<hash>`）。
     *
     * 我们从 2.4.2 起不再 `chain.proceed()`，`e()` / `i()` 里原本那句 `a()` 也就没了。
     * 现实中不会出问题——`c()` / `d()` / `g()` / `h()` 每个都以 `a()` 开头，QQ 启动
     * 必然调用其中之一（版本号上报）。但这条依赖是隐式的：万一某个进程只碰 `e()`/`i()`，
     * `u()` 就永不执行，`o` 会缺最后一段。
     *
     * 这里调一次 `c()`（public static String，第一行就是 `a()`），把「依赖 QQ 的调用
     * 顺序」变成「我们主动保证」。同时把返回的版本串记进日志，顺带成为 `o` 是否完整的探针。
     */
    private fun primeNativeAppSetting(appSetting: Class<*>, tag: String) {
        // 关键前置：原生 `u()` 里 `BaseApplication.getContext()` 若为 null 会抛 NPE，
        // 而它内部 catch 掉之后 `a()` 仍会把 D 置为 true —— 等于永久放弃初始化。
        // 所以 Context 没就绪时宁可不碰，交给 QQ 自己在合适的时机调用。
        if (appContext() == null) {
            log("[$tag] 跳过主动初始化：Context 尚未就绪，交给 QQ 自己触发")
            return
        }
        val version = runCatching {
            appSetting.getMethod("c").invoke(null) as? String
        }.getOrNull()
        if (version.isNullOrEmpty()) {
            log("[$tag] 原生 AppSetting.c() 未取到版本串（e()/i() 已被接管，通常无影响）")
        } else if (version.endsWith(".")) {
            log("[$tag] 原生版本串疑似缺末段（可能 u() 未生效）：$version")
        } else {
            log("[$tag] 原生版本串已初始化：$version")
        }
    }

    /**
     * 接管 `AppSetting.e()`，返回平板 appid。
     *
     * 取值来自 [padCredential]（manifest 优先、常量兜底），与 [hookPadKey] 用的是**同一份**
     * 凭证，所以 appid 与 key 永远配对。这里不再执行原生实现，高频调用下没有额外开销。
     *
     * 整个拦截体包在 runCatching 里：这是 QQ 每次取 appid 的必经之路，任何异常冒出去
     * 都会直接打断调用方（可能崩在 QQ 自己的业务代码里）。宁可返回兜底常量并记一条日志。
     */
    private fun hookPadAppId(method: Method, tag: String): Boolean {
        return runCatching {
            method.isAccessible = true
            HookUtils.safeHook(xposed, method, RULE_APP_SETTING_APPID)
                .intercept {
                    runCatching {
                        val cred = padCredential()
                        probeNativeValue(
                            RULE_APP_SETTING_APPID,
                            "e() source=${cred.source}",
                            cred.appId
                        )
                        cred.appId
                    }.getOrElse { error ->
                        logOnce(
                            RULE_APP_SETTING_APPID,
                            "[$tag] $RULE_APP_SETTING_APPID 取值异常，本次返回常量兜底：$error"
                        )
                        PAD_APP_ID
                    }
                }
            HookUtils.ruleInstalled(RULE_APP_SETTING_APPID, 1, processName)
            markInstalled(RULE_APP_SETTING_APPID)
            true
        }.getOrElse {
            log("[$tag] $RULE_APP_SETTING_APPID hook 失败：$it")
            false
        }
    }

    /** 接管 `AppSetting.i()`，与 [hookPadAppId] 共用同一份 [padCredential]。 */
    private fun hookPadKey(method: Method, tag: String): Boolean {
        return runCatching {
            method.isAccessible = true
            HookUtils.safeHook(xposed, method, RULE_APP_SETTING_KEY)
                .intercept {
                    runCatching {
                        val cred = padCredential()
                        probeNativeValue(
                            RULE_APP_SETTING_KEY,
                            "i() source=${cred.source}",
                            maskKey(cred.key)
                        )
                        cred.key
                    }.getOrElse { error ->
                        logOnce(
                            RULE_APP_SETTING_KEY,
                            "[$tag] $RULE_APP_SETTING_KEY 取值异常，本次返回常量兜底：$error"
                        )
                        PAD_KEY
                    }
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
     * 取本次要用的平板凭证。
     *
     * 命中 manifest 真值就永久缓存；只有兜底结果时不缓存，隔一段时间重试——
     * 因为读 manifest 依赖 Context，而 `e()` 可能早于 Application 就绪就被调用。
     * 重试间隔 5 秒，避免高频 `e()` 路径上反复走 PackageManager。
     */
    private fun padCredential(): PadCred {
        cachedManifestCred?.let { return it }
        val now = SystemClock.elapsedRealtime()
        if (now < nextCredentialRetryAt) {
            return constCred
        }
        synchronized(credentialLock) {
            cachedManifestCred?.let { return it }
            if (now < nextCredentialRetryAt) {
                return constCred
            }
            val resolved = resolvePadCredential()
            return if (resolved.source == SRC_MANIFEST) {
                cachedManifestCred = resolved
                resolved
            } else {
                nextCredentialRetryAt = now + CREDENTIAL_RETRY_INTERVAL_MS
                resolved
            }
        }
    }

    /**
     * 解析本次进程的平板凭证。
     *
     * 真源是 QQ 自己的 AndroidManifest `AppSetting_params_pad`（格式 `appid#key#...`）：
     * 原生 `AppSetting.u()` 正是拿它覆盖静态字段 g / j 的，所以读它等价于读原生平板分支，
     * 而且 appid 与 key 天然成对。读不到才退回编译期常量。
     */
    private fun resolvePadCredential(): PadCred {
        val fromManifest = readManifestPadCredential()
        val cred = fromManifest ?: constCred
        log(
            "PAD_CRED source=${cred.source} appId=${cred.appId} key=${maskKey(cred.key)} " +
                "padUtilHooked=${padUtilHooked.get()}"
        )
        if (fromManifest == null) {
            log("PAD_CRED 读取 manifest 失败，本次退回编译期常量（$PAD_APP_ID），稍后自动重试")
        } else if (nativePrimed.compareAndSet(false, true)) {
            // 走到这里说明 Context 一定可用（刚用它读过 PackageManager），
            // 是补原生初始化最安全的时机。放在 onPackageReady 里做不了：
            // 实测那个阶段 ActivityThread.currentApplication() 还是 null。
            appSettingClass?.let { primeNativeAppSetting(it, "cred") }
        }
        return cred
    }

    /** 从 QQ 的 metaData 读 `AppSetting_params_pad`，格式 `appid#key#...`。 */
    private fun readManifestPadCredential(): PadCred? = runCatching {
        val context = appContext() ?: return@runCatching null
        val info = context.packageManager.getApplicationInfo(
            PACKAGE_NAME,
            PackageManager.GET_META_DATA
        )
        val raw = info.metaData?.getString("AppSetting_params_pad")
            ?: return@runCatching null
        val parts = raw.split("#")
        val appId = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: return@runCatching null
        val key = parts.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
            ?: return@runCatching null
        PadCred(appId, key, SRC_MANIFEST)
    }.getOrNull()

    /**
     * QQ 进程里的 Context。
     *
     * `:MSF` 这类没有 Activity 的进程取不到 `ActivityThread.currentApplication()`，
     * 依次退到：`currentActivityThread().getApplication()`、QQ 自己的
     * `BaseApplication.getContext()`。
     *
     * 最后这条必须用 **app 的 loader** 去加载：`BaseApplication` 是 QQ 的类，
     * 用模块自己的 loader 调 `Class.forName` 大概率是 ClassNotFound，那样等于没有兜底。
     */
    private fun appContext(): Context? {
        runCatching {
            Class.forName("android.app.ActivityThread", false, null)
                .getMethod("currentApplication")
                .invoke(null) as? Context
        }.getOrNull()?.let { return it }

        runCatching {
            val threadClass = Class.forName("android.app.ActivityThread", false, null)
            val thread = threadClass.getMethod("currentActivityThread").invoke(null)
            threadClass.getMethod("getApplication").invoke(thread) as? Context
        }.getOrNull()?.let { return it }

        return runCatching {
            val loader = appLoader ?: return@runCatching null
            Class.forName("com.tencent.qphone.base.util.BaseApplication", false, loader)
                .getMethod("getContext")
                .invoke(null) as? Context
        }.getOrNull()
    }

    /**
     * 这个 loader 能不能找到 QQ 自己的 `BaseApplication`。
     *
     * 用来判断「它是否值得替换 appLoader」：能加载到说明它看得见 QQ 的 application 类，
     * 拿 `getContext()` 的兜底路径才成立；加载不到说明是某个插件 loader，换上去反而更差。
     */
    private fun canLoadBaseApplication(loader: ClassLoader): Boolean = runCatching {
        Class.forName("com.tencent.qphone.base.util.BaseApplication", false, loader)
        true
    }.getOrDefault(false)

    /** key 打日志时脱敏，只留头尾确认「是不是同一串」。 */
    private fun maskKey(key: String): String =
        if (key.length <= 6) "****" else "${key.take(4)}…${key.takeLast(2)}"

    /**
     * 当前应当上报给 NT 的平板 appid。
     *
     * 与 `AppSetting.e()` 共用同一份凭证，避免会话配置和 Java 层上报两个值打架。
     */
    private fun currentPadAppId(): Int = padCredential().appId

    // ---------------- 平板凭证探针（只读） ----------------

    /**
     * 同一个 key 在进程内只打一次日志。
     *
     * 用于 `e()` / `i()` 这类高频路径上的异常上报：既要留下证据，
     * 又不能因为同一个异常被调用一千次就写一千行文件。
     */
    private fun logOnce(key: String, message: String) {
        if (probedKeys.add("log:$key@$processName")) {
            log(message)
        }
    }

    /**
     * 记录一次「取证结论」，每个规则 + 进程只记一次，避免高频方法刷屏。
     *
     * `e()` / `i()` 现在都从同一份 [padCredential] 取值，看 `source` 字段就知道
     * 这次用的是 manifest 真值还是常量兜底。
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
            HookUtils.safeHook(xposed, method, RULE_PAD_UTIL)
                .intercept { tablet }
            HookUtils.ruleInstalled(RULE_PAD_UTIL, 1, processName)
            markInstalled(RULE_PAD_UTIL)
            padUtilHooked.set(true)
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
            HookUtils.safeHook(xposed, method, RULE_MULTI_DEVICE_PAD)
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

        // fail-closed：三个字段必须都能解析，否则整条规则不挂。
        // 只改一半（appid 成了平板、deviceType 还是手机）比完全不改更难排查，
        // 而且会让 Java 层与内核层对设备类型的认知互相矛盾。
        if (pad == null || androidPlatform == null) {
            HookUtils.ruleFailure(
                RULE_SESSION_INIT,
                "DeviceType.KPAD / PlatformType.KANDROID",
                "ENUM_MISSING pad=${pad != null} platform=${androidPlatform != null}",
                processName
            )
            markFailed(RULE_SESSION_INIT)
            return
        }

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
            HookUtils.safeHook(xposed, method, RULE_SESSION_INIT)
                .intercept { chain ->
                    // 改写逻辑整体包起来，但 chain.proceed() 必须无条件执行。
                    //
                    // 这是全模块唯一「proceed 在最后」的拦截点，也曾经是唯一可能整条被跳过的：
                    // 一旦 dump / override 里抛出未捕获异常，异常被 PROTECTIVE 模式吞掉，
                    // 后面的 chain.proceed() 也就不会执行 —— 表现是 NT 内核 init 完全没跑，
                    // 而日志里只留下一条 (before)，连异常都看不到（线上实测 after 记录恒为 0）。
                    // init 是内核建立会话的必经调用，跳过它等于把 QQ 的通信层拆掉。
                    runCatching {
                        val config = chain.getArg(0) ?: return@runCatching
                        // dump 放在 dry-run 判断之外：诊断模式下正是要看「本来报什么」。
                        if (ModuleConfig.LOG_QQ_SESSION) {
                            dumpSessionConfig(tag, config, "before")
                        }
                        if (!ModuleConfig.QQ_DRY_RUN) {
                            val applied = overrideSessionConfig(config, pad, androidPlatform, tag)
                            if (ModuleConfig.LOG_QQ_SESSION) {
                                dumpSessionConfig(tag, config, "after")
                            }
                            if (!applied) {
                                markFailed(RULE_SESSION_INIT)
                            }
                        }
                    }.onFailure { throwable ->
                        logOnce(
                            "$RULE_SESSION_INIT@intercept",
                            "[$tag] $RULE_SESSION_INIT 拦截体异常（已吞，proceed 仍会执行）：$throwable"
                        )
                    }
                    val result = chain.proceed()
                    // 留一条「原生 init 确实跑到了」的硬证据。
                    //
                    // 没有这条时，日志里出现 (before) 却没有 (after) 有两种完全不同的解释：
                    // 拦截体中途抛了（proceed 被跳过），或者 init 压根没被调用。
                    // 两者对 QQ 的影响天差地别，却无法从日志区分。
                    logOnce(
                        "$RULE_SESSION_INIT@proceeded",
                        "[$tag] $RULE_SESSION_INIT 已执行原生 init（会话建立未被跳过）"
                    )
                    result
                }
            HookUtils.ruleInstalled(RULE_SESSION_INIT, 1, processName)
            markInstalled(RULE_SESSION_INIT)
            log("[$tag] $RULE_SESSION_INIT NT 会话已接管（forcePad=${!ModuleConfig.QQ_DRY_RUN}）")
        }.onFailure {
            log("[$tag] $RULE_SESSION_INIT hook 失败：$it")
            markFailed(RULE_SESSION_INIT)
        }
    }

    /**
     * 把会话配置一次性改成平板，任一步失败就整体回滚。
     *
     * 三个字段里只要有一个写不进去，就把已经写过的恢复成原值——宁可这次会话按原生配置走，
     * 也不要留下「appid 已是平板、deviceType 还是手机」这种内部矛盾状态。
     */
    private fun overrideSessionConfig(
        config: Any,
        pad: Any,
        platform: Any,
        tag: String
    ): Boolean {
        val snapshot = listOf(
            "deviceType" to getField(config, "deviceType"),
            "appid" to getField(config, "appid"),
            "platform" to getField(config, "platform")
        )
        val ok = setField(config, "deviceType", pad, tag) &&
            setField(config, "appid", currentPadAppId().toString(), tag) &&
            setField(config, "platform", platform, tag)
        if (!ok) {
            for ((name, value) in snapshot) {
                if (value != null) {
                    setField(config, name, value, tag)
                }
            }
            logOnce(
                "$RULE_SESSION_INIT@rollback",
                "[$tag] $RULE_SESSION_INIT 写入失败，已回滚为原生会话配置（不留半改状态）"
            )
        }
        return ok
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
            // 按「安装阶段 + 阶段名」去重：`[app]` 与 `[dlcl]` 是两个独立的 loader，
            // 各自看到的 InitSessionConfig 是不同的对象，合并去重会让后来者永远打不出来。
            // 原来只按 stage 去重，且 before 先把自己置位，after 实际一次都没打出来过
            // （线上日志实测 before=8 / after=0），诊断功能等于不存在。
            if (dumpedStages.add("$tag@$stage")) {
                log(builder.toString())
            }
        }
    }

    // ---------------- 在线设备列表（诊断） ----------------

    private fun hookRegisterProxy(loader: ClassLoader, tag: String) {
        if (!ModuleConfig.LOG_QQ_ONLINE_DEVICES) {
            // 明确写一条：否则 summary 里 installed 少一条，看日志会误判成「这条规则失效了」。
            log("[$tag] $RULE_ONLINE_DEVICES 已按配置关闭（LOG_QQ_ONLINE_DEVICES=false）")
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
            HookUtils.safeHook(xposed, method, RULE_ONLINE_DEVICES)
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
         * 最终兜底值（QQ 9.3.75）。
         *
         * 与 QQ 自己 AndroidManifest 里 `AppSetting_params_pad` 的值一致。
         * 正常运行时不走这里：`e()`/`i()` 优先读 manifest 的平板凭证，
         * 只有 manifest 读不到（取不到 Context 等）才退回这两个常量。
         *
         * 注意 dex 里 `AppSetting` 的静态初始值 g/j（537397237 / C5EF37EE8FAB3E09）
         * 只是编译期占位，运行时会被 manifest 覆盖，**不能拿它当平板值参考**。
         */
        private const val PAD_APP_ID = 537397816
        private const val PAD_KEY = "0FFD632C4B7E08AF"

        /** 凭证来源标记。 */
        private const val SRC_MANIFEST = "manifest"
        private const val SRC_CONST = "const-fallback"

        /** manifest 读取失败后的重试间隔：`e()` 是高频路径，不能每次调用都走 PackageManager。 */
        private const val CREDENTIAL_RETRY_INTERVAL_MS = 5_000L

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

        /** 系统类：Android 8.1+ 才有，QQ 的 Tinker 补丁实际由它定义。 */
        private const val DLCL_CLASS = "dalvik.system.DelegateLastClassLoader"

        /** Tinker 自带的补丁 loader：DLCL 通路不可用时的备份落点。 */
        private const val TINKER_LOADER_CLASS = "com.tencent.tinker.loader.TinkerClassLoader"
        private const val TINKER_LOADER_HOOK_ID = "qq_tinker_loader"

        /** 启动后多久检查「补丁 loader 是否被覆盖」。QQ 的 Tinker 在 Application 创建后很快完成。 */
        private const val LOADER_COVERAGE_DELAY_MS = 5_000L

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

        private fun setField(target: Any, name: String, value: Any?, tag: String): Boolean {
            var cls: Class<*>? = target.javaClass
            while (cls != null) {
                try {
                    val field: Field = cls.getDeclaredField(name)
                    field.isAccessible = true
                    field.set(target, value)
                    return true
                } catch (_: NoSuchFieldException) {
                    cls = cls.superclass
                } catch (throwable: Throwable) {
                    Log.w(HookUtils.TAG, "[$tag] 写入 $name 失败：$throwable")
                    return false
                }
            }
            Log.w(HookUtils.TAG, "[$tag] 字段不存在：$name")
            return false
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
