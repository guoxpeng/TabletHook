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
     * 是否在 NT 会话初始化时 dump `InitSessionConfig`。
     *
     * 每个进程只打一次（`sessionLogged` 保证），成本极低，是确认
     * 「上报给内核的 appid / deviceType 到底是什么」最直接的证据，保留。
     */
    const val LOG_QQ_SESSION = true

    /**
     * 是否 dump 服务端下发的在线设备列表。
     *
     * **默认关**。开启后会额外挂 `RegisterProxySvcPackHandler.e3`，
     * 每次服务端推送都做反射 + 拼字符串 + 写 trace 文件，属于持续性 IO，
     * 长时间开启会加重卡顿、也扩大可观测面。只在排查多设备互踢时临时打开。
     */
    const val LOG_QQ_ONLINE_DEVICES = false

    /**
     * QQ 平板凭证来源探针（只读）。
     *
     * 打开后 `e()` / `i()` / `o()` 会各记一行「凭证来源 / 实际取值」，每个进程只记一次。
     * 正常留开也无所谓：只在首次调用时写一行。
     */
    const val PROBE_QQ_APP_ID = true

    /**
     * dump `AppSetting` 全部静态 int / String 字段。
     *
     * 用于排查字段含义，一次输出几十个字段，只在需要时打开。
     *
     * 注意：9.3.75 的 dex 静态初始值是 f=537397776 / g=537397237 /
     * i=B0FAC20A724CCF50 / j=C5EF37EE8FAB3E09，但运行时 `AppSetting.u()` 会用
     * AndroidManifest 的 `AppSetting_params` / `AppSetting_params_pad` 覆盖它们，
     * 覆盖后的真值是：手机 537397777 / FB28D5C4ADEAFA38，平板 537397816 / 0FFD632C4B7E08AF。
     * 判断平板凭证请以 manifest 为准，不要看 dex 初始值。
     */
    const val PROBE_QQ_APP_SETTING_FIELDS = false
}
