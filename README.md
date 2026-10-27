# 微Q平板模块（TabletHook 精简分支）

这是 [roro2239/TabletHook](https://github.com/roro2239/TabletHook) 的个人维护分支，在原作者停更后继续跟进。

**本分支只做两件事：微信平板登录 + QQ 平板登录。**

## 与上游的差异

| 项目 | 上游 TabletHook | 本分支 2.1-cached |
| --- | --- | --- |
| 拼多多平板登录 | 支持 | **已删除**（`PddHook` 整体移除，含作用域） |
| 微信平板登录 | 支持 | 保留，并补充折叠屏白名单、密码页改道 |
| 微信 DexKit 定位 | 每次启动全量扫描 | **结果缓存**，命中即跳过 DexKit |
| QQ 平板登录 | 仅 MSF 客户端类型（DexKit 动态定位） | 重写为固定类名实现，覆盖 AppSetting / PadUtil / 多设备判定 / NT 会话，完全不依赖 DexKit |
| 屏幕画像伪装 | 无 | 无（曾实现后删除，理由见下） |
| 模块入口 | `TabletHook.kt` | `QqPadEntry.kt` |
| 应用名 | TabletHook | 微Q平板模块 |
| 作用域 | 微信 / QQ / 拼多多 | 微信 / QQ |

删除拼多多的原因：上游的拼多多实现只能改客户端平板判定，而拼多多本身不支持同账号多端共存，登录态还会被服务端主动失效，实际收益为零，反而多一份注入面。相关代码已全部移除，不再维护。

## 支持范围

下表中的版本是当前完成静态分析和真机验证的最低版本。低于最低版本的应用未进行兼容性验证。

| 应用 | 包名 | 最低版本 | versionCode | 真机验证版本 |
| --- | --- | --- | ---: | --- |
| 微信 | `com.tencent.mm` | `8.0.77` | `3141` | 8.0.77 |
| QQ | `com.tencent.mobileqq` | `9.3.35` | `15560` | 9.3.75（`42350`） |

## 变更记录

### 2.1-cached

- **微信定位结果缓存**：DexKit 算出的两个关键方法签名写入 `files/tablethook_wechat_cache.json`，下次启动反射校验后直接挂钩。命中缓存时**完全不加载 DexKit**——进程内 `libdexkit.so` 的映射数为 0，也不再花 200~400ms 做 dex 全量扫描。
- 缓存带完整校验（public/static 修饰符、返回类型、参数列表），微信升级导致签名变化时自动失效并回落 DexKit 重搜、覆盖缓存，不会拿错方法乱 hook。
- 微信新增**折叠屏白名单**钩子（`com.tencent.mm.ui.ok.R()` 恒真）。
- 微信新增**密码页改道**：首次进入 `LoginPasswordUI` 时引导到「选择登录方式」，避免直接落到密码框，方便走扫码 / 平板登录。每进程只改道一次。
- 新增独立日志通道：`TabletHookTrace`（logcat）+ `files/tablethook_trace.log`（落盘），框架日志抓不到时仍可回溯。

### 2.0-merged

- 微信与 QQ 合并进同一个模块包，一次安装、一个作用域配置搞定两端。
- QQ 侧改为固定类名 hook，QQ 进程不再加载 DexKit。
- QQ 侧接管 `AppSetting.e()/i()/o()`、`PadUtil.a()` 返回 `TABLET`、`isPadForMultiDeviceLogin` 恒真、NT `InitSessionConfig` 强制 `deviceType=KPAD`，并处理 `DelegateLastClassLoader` 插件类加载器。
- 移除屏幕画像伪装（`PadSurfaceHook`）：它只改应用读到的大小、不改真实 Surface，会带来排版错位，而风控收益不确定，属于纯负担。
- 移除拼多多支持。

## 运行要求

- Android 11 或更高版本（`minSdk 30`）
- 支持 libxposed API 102 的 Xposed 框架（LSPosed / Vector 等）
- 已安装受支持的目标应用

## 安装使用

1. 在 Releases 页面下载并安装模块 APK。
2. 在 Xposed 管理器中启用「微Q平板模块」。
3. 确认作用域包含微信与 QQ。
4. 完全结束目标应用进程后重新打开应用。
5. 按目标应用的正常登录流程完成登录。

首次启动微信会走 DexKit 动态定位（约 0.3s）；之后再启动直接读缓存。升级微信后第一次启动会自动重搜一次并刷新缓存。

## 技术实现

- 使用 `libxposed API 102` 接入 Xposed 生命周期和 Hook API。
- **微信**：用 `DexKit 2.2.0` 按字符串（`inTabletEnv`、`/cgi-bin/micromsg-bin/checkloginaspad`）动态定位平板环境判定与副设备登录资格方法，不依赖固定混淆类名；定位结果带签名缓存。
- **QQ**：直接用 QQ 9.3 系列稳定的固定类名（`com.tencent.common.config.AppSetting`、`com.tencent.common.config.pad.PadUtil`、`com.tencent.qqnt.kernel.nativeinterface.InitSessionConfig` 等），不引入 DexKit。
- QQ 平板 appid / key 取自 QQ 自身清单里的 `AppSetting_params_pad`（`537397816` / `0FFD632C4B7E08AF`），不是外部硬编的猜测值。
- 对关键候选执行数量和签名校验，匹配异常时记录错误，不使用不确定结果。

动态定位只能降低版本变化带来的影响，不能保证所有后续版本自动兼容。目标应用更新后如出现匹配失败，需要结合日志重新分析。

## 项目结构

```text
app/src/main/java/com/roro/tablethook/
├── QqPadEntry.kt          模块入口、进程识别、缓存优先分发
├── config/
│   └── ModuleConfig.kt    行为开关（诊断日志、NT 会话 dry-run 等）
├── hook/
│   ├── WeChatHook.kt      微信平板登录（DexKit + 签名缓存 + 折叠屏/密码页钩子）
│   └── QqHook.kt          QQ 平板登录（固定类名）
└── util/
    └── HookUtils.kt       DexKit 加载、公共 Hook 工具、trace 日志通道
```

## 日志

两条通道同时输出，任选其一排查：

| 通道 | 位置 | 用途 |
| --- | --- | --- |
| 框架日志 | Xposed 管理器模块日志，标签 `TabletHook` | 候选数量、Hook 安装结果、初始化失败原因 |
| 独立日志 | logcat 标签 `TabletHookTrace` | 同上，框架日志抓不到时的备份通道 |
| 落盘日志 | 目标应用私有目录 `files/tablethook_trace.log` | 进程已退出、logcat 已被冲掉时回溯 |

微信定位缓存位于目标应用私有目录 `files/tablethook_wechat_cache.json`；升级微信后若怀疑缓存过期，删掉该文件即可强制重搜。

日志不应包含登录凭证、令牌或二维码票据的实际值。反馈兼容性问题时，请同时提供目标应用版本、模块版本和相关错误日志。

## 已知限制

QQ 的多端在线按**平台大类**判定互斥：安卓手机和伪装成平板的安卓设备仍属同一大类，两台安卓端登录同一账号会被服务端互踢。本模块解决的是「客户端被识别为平板」，不改变服务端的平台互斥策略。跨平台组合（安卓 + PC、安卓 + iOS）不受此限制。
