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
 * 1. AppSetting.e()  -> 平板 appid（537397816）
 * 2. AppSetting.i()  -> 平板 appid 对应的 key
 * 3. AppSetting.o(Context) -> true（平板判定开关）
 * 4. PadUtil.a(Context) -> DeviceType.TABLET
 * 5. injector.d#isPadForMultiDeviceLogin -> true
 * 6. IQQNTWrapperSession$CppProxy#init -> 会话配置强制 KPAD / 平板 appid / KANDROID
 * 7. RegisterProxySvcPackHandler#e3 -> 只读诊断，转储服务端在线设备列表
 *
 * 与独立模块的差别：不再使用 DexKit。QQ 侧全部走固定类名，既省一次 dexkit .so 加载
 * （少一份 /proc/self/maps 里的模块痕迹），也让 QQ 进程完全不碰 DexKit。
 */
internal class QqHook(
    private val xposed: XposedInterface
) {
    private val installedLoaders: MutableSet<ClassLoader> =
        Collections.newSetFromMap(WeakHashMap<ClassLoader, Boolean>())
    private val delegateLoaderHooked = AtomicBoolean(false)
    private val appSettingLogged = AtomicBoolean(false)
    private val sessionLogged = AtomicBoolean(false)

    fun install(classLoader: ClassLoader, processName: String) {
        if (ModuleConfig.LOG_DEVICE_PROFILE) {
            logDeviceProfile(processName)
        }
        installOn(classLoader, "app")
        hookDelegateLoader()
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
        }.getOrNull() ?: return
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
        runCatching {
            for (method in appSetting.declaredMethods) {
                if (!Modifier.isStatic(method.modifiers) || method.parameterTypes.size > 1) {
                    continue
                }
                when {
                    method.name == "e" && method.returnType == Int::class.javaPrimitiveType ->
                        forceInt(method, PAD_APP_ID, tag)

                    method.name == "i" && method.returnType == String::class.java ->
                        forceString(method, PAD_KEY, tag)

                    method.name == "o" &&
                        method.parameterTypes.size == 1 &&
                        method.parameterTypes[0] == Context::class.java &&
                        method.returnType == Boolean::class.javaPrimitiveType -> {
                        method.isAccessible = true
                        xposed.hook(method)
                            .setId(APP_SETTING_PAD_FLAG_HOOK_ID)
                            .intercept { true }
                    }
                }
            }
            if (appSettingLogged.compareAndSet(false, true)) {
                log("[$tag] AppSetting 已接管：e()=${PAD_APP_ID}，i()=*，o()=true")
            }
        }.onFailure { log("[$tag] AppSetting hook 失败：$it") }
    }

    private fun forceInt(method: Method, value: Int, tag: String) {
        runCatching {
            method.isAccessible = true
            xposed.hook(method)
                .setId("qq_app_setting_int")
                .intercept { value }
        }.onFailure { log("[$tag] ${method.name} hook 失败：$it") }
    }

    private fun forceString(method: Method, value: String, tag: String) {
        runCatching {
            method.isAccessible = true
            xposed.hook(method)
                .setId("qq_app_setting_string")
                .intercept { value }
        }.onFailure { log("[$tag] ${method.name} hook 失败：$it") }
    }

    // ---------------- PadUtil ----------------

    private fun hookPadUtil(loader: ClassLoader, tag: String) {
        runCatching {
            val padUtil = Class.forName(CLS_PAD_UTIL, false, loader)
            val deviceType = Class.forName(CLS_PAD_DEVICE_TYPE, false, loader)
            val tablet = enumConstant(deviceType, "TABLET") ?: error("未找到 TABLET 枚举值")
            var count = 0
            for (method in padUtil.declaredMethods) {
                val params = method.parameterTypes
                if (method.name == "a" &&
                    params.size == 1 &&
                    params[0] == Context::class.java &&
                    method.returnType == deviceType
                ) {
                    method.isAccessible = true
                    xposed.hook(method)
                        .setId(PAD_UTIL_HOOK_ID)
                        .intercept { tablet }
                    count++
                }
            }
            log("[$tag] PadUtil.a -> TABLET x$count")
        }.onFailure { log("[$tag] PadUtil hook 失败：$it") }
    }

    private fun hookIsPadForMultiDeviceLogin(loader: ClassLoader, tag: String) {
        runCatching {
            val cls = Class.forName(CLS_INJECTOR, false, loader)
            var count = 0
            for (method in cls.declaredMethods) {
                if (method.name != "isPadForMultiDeviceLogin") {
                    continue
                }
                method.isAccessible = true
                xposed.hook(method)
                    .setId(MULTI_DEVICE_PAD_HOOK_ID)
                    .intercept { true }
                count++
            }
            log("[$tag] isPadForMultiDeviceLogin -> true x$count")
        }.onFailure { log("[$tag] isPadForMultiDeviceLogin hook 失败：$it") }
    }

    // ---------------- NT 内核会话 ----------------

    private fun hookSessionInit(loader: ClassLoader, tag: String) {
        val cfgClass = runCatching {
            Class.forName(CLS_INIT_SESSION_CONFIG, false, loader)
        }.getOrNull() ?: return
        val pad = runCatching {
            enumConstant(Class.forName(CLS_NT_DEVICE_TYPE, false, loader), "KPAD")
        }.getOrNull()
        val androidPlatform = runCatching {
            enumConstant(Class.forName(CLS_NT_PLATFORM_TYPE, false, loader), "KANDROID")
        }.getOrNull()

        runCatching {
            val proxy = Class.forName(CLS_SESSION_PROXY, false, loader)
            var count = 0
            for (method in proxy.declaredMethods) {
                if (method.name != "init") {
                    continue
                }
                val params = method.parameterTypes
                if (params.size != 4 || params[0] != cfgClass) {
                    continue
                }
                method.isAccessible = true
                xposed.hook(method)
                    .setId(SESSION_INIT_HOOK_ID)
                    .intercept { chain ->
                        val config = chain.getArg(0)
                        if (config != null && !ModuleConfig.QQ_DRY_RUN) {
                            if (ModuleConfig.LOG_QQ_SESSION) {
                                dumpSessionConfig(tag, config, "before")
                            }
                            pad?.let { setField(config, "deviceType", it, tag) }
                            setField(config, "appid", PAD_APP_ID.toString(), tag)
                            androidPlatform?.let { setField(config, "platform", it, tag) }
                            if (ModuleConfig.LOG_QQ_SESSION) {
                                dumpSessionConfig(tag, config, "after")
                            }
                        }
                        chain.proceed()
                    }
                count++
            }
            log(
                "[$tag] IQQNTWrapperSession.init 已接管 x$count" +
                    "（forcePad=${!ModuleConfig.QQ_DRY_RUN}）"
            )
        }.onFailure { log("[$tag] NT 会话 hook 跳过：$it") }
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
        runCatching {
            val handler = Class.forName(CLS_REGISTER_PROXY, false, loader)
            val pushParams = Class.forName(CLS_PUSH_PARAMS, false, loader)
            for (method in handler.declaredMethods) {
                if (method.name != "e3") {
                    continue
                }
                val params = method.parameterTypes
                if (params.size != 1 || params[0] != pushParams) {
                    continue
                }
                method.isAccessible = true
                xposed.hook(method)
                    .setId(ONLINE_DEVICES_HOOK_ID)
                    .intercept { chain ->
                        val result = chain.proceed()
                        runCatching { dumpOnlineDevices(chain.getArg(0)) }
                        result
                    }
            }
        }.onFailure { log("[$tag] 在线设备诊断 hook 跳过：$it") }
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

        /** QQ 9.3.75 实测值。appid/key 与 QQ 自身清单里的 AppSetting_params_pad 一致。 */
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

        private const val DELEGATE_LOADER_HOOK_ID = "qq_delegate_loader"
        private const val APP_SETTING_PAD_FLAG_HOOK_ID = "qq_app_setting_pad_flag"
        private const val PAD_UTIL_HOOK_ID = "qq_pad_util"
        private const val MULTI_DEVICE_PAD_HOOK_ID = "qq_multi_device_pad"
        private const val SESSION_INIT_HOOK_ID = "qq_session_init"
        private const val ONLINE_DEVICES_HOOK_ID = "qq_online_devices"

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
