package com.roro.tablethook.config

/**
 * 模块全局开关。
 *
 * 默认值偏保守：只做「客户端类型判定」这一件事，不改变设备机型或屏幕画像，
 * 避免引入新的可观测特征。
 */
internal object ModuleConfig {

    /**
     * 是否在 QQ 主进程（com.tencent.mobileqq）一并 hook 设备类型。
     *
     * 只 hook :MSF 时，登录包自称平板，主进程仍按手机判定，
     * 同一会话内出现两种设备类型属于可被观测的不一致。
     */
    const val HOOK_QQ_MAIN_PROCESS = true

    /**
     * QQ NT 会话只诊断不改写。
     *
     * 排查期打开，可对比「QQ 本来报什么」与「改写后报什么」。
     * 正常运行必须为 false，否则 NT 会话不会被强制成平板。
     */
    const val QQ_DRY_RUN = false

    /** 是否输出设备画像诊断日志（机型 + 屏幕参数）。 */
    const val LOG_DEVICE_PROFILE = true

    /**
     * 是否输出 QQ 会话配置与在线设备列表的详细日志。
     *
     * 含 appid / deviceType / 服务端在线设备条目，排查价值高，但可观测面也大。
     */
    const val LOG_QQ_SESSION = true
}
