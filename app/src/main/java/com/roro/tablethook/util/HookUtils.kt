package com.roro.tablethook.util

import io.github.libxposed.api.XposedInterface
import java.io.File
import java.lang.reflect.Method

internal object HookUtils {
    const val TAG = "TabletHook"
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
