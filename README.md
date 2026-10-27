# 微Q平板模块（TabletHook 精简分支）

这是 [roro2239/TabletHook](https://github.com/roro2239/TabletHook) 的个人维护分支，在原作者停更后继续跟进。

**本分支只做两件事：微信平板登录 + QQ 平板登录。**

## 与上游的差异

| 项目 | 上游 TabletHook | 本分支 2.0-merged |
| --- | --- | --- |
| 拼多多平板登录 | 支持 | **已删除**（`PddHook` 整体移除，含作用域） |
| 微信平板登录 | 支持 | 保留，逻辑未改动 |
| QQ 平板登录 | 仅 MSF 客户端类型（DexKit 动态定位） | 重写为固定类名实现，覆盖 AppSetting / PadUtil / 多设备判定 / NT 会话 |
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

## 2.0-merged 变更

- 微信与 QQ 合并进同一个模块包，一次安装、一个作用域配置搞定两端。
- QQ 侧改为固定类名 hook，QQ 进程**不再加载 DexKit**（`libdexkit.so` 不出现在目标进程的 `/proc/self/maps` 里）。
- QQ 侧新增：`AppSetting.e()/i()/o()` 接管、`PadUtil.a()` 返回 `TABLET`、`isPadForMultiDeviceLogin` 恒真、NT `InitSessionConfig` 强制 `deviceType=KPAD`，并处理 `DelegateLastClassLoader` 插件类加载器。
- 移除屏幕画像伪装（`PadSurfaceHook`）：它只改应用读到的大小、不改真实 Surface，会带来排版错位，而风控收益不确定，属于纯负担，已删除。
- 移除拼多多支持（见上）。

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

## 技术实现

- 使用 `libxposed API 102` 接入 Xposed 生命周期和 Hook API。
- **微信**：用 `DexKit 2.2.0` 按字符串（`inTabletEnv`、`/cgi-bin/micromsg-bin/checkloginaspad`）动态定位平板环境判定与副设备登录资格方法，不依赖固定混淆类名。
- **QQ**：直接用 QQ 9.3 系列稳定的固定类名（`com.tencent.common.config.AppSetting`、`com.tencent.common.config.pad.PadUtil`、`com.tencent.qqnt.kernel.nativeinterface.InitSessionConfig` 等），不引入 DexKit，减少目标进程内的模块痕迹。
- QQ 平板 appid / key 取自 QQ 自身清单里的 `AppSetting_params_pad`（`537397816` / `0FFD632C4B7E08AF`），不是外部硬编的猜测值。
- 对关键候选执行数量和签名校验，匹配异常时记录错误，不使用不确定结果。

动态定位只能降低版本变化带来的影响，不能保证所有后续版本自动兼容。目标应用更新后如出现匹配失败，需要结合日志重新分析。

## 项目结构

```text
app/src/main/java/com/roro/tablethook/
├── QqPadEntry.kt          模块入口、进程识别和应用分发
├── config/
│   └── ModuleConfig.kt    行为开关（诊断日志、NT 会话 dry-run 等）
├── hook/
│   ├── WeChatHook.kt      微信平板登录逻辑（DexKit）
│   └── QqHook.kt          QQ 平板登录逻辑（固定类名）
└── util/
    └── HookUtils.kt       DexKit 加载和公共 Hook 工具
```

## 日志

模块日志统一使用 `TabletHook` 标签，可在 Xposed 管理器的模块日志中筛选，主要包括：

- 微信 DexKit 候选数量与命中结果
- QQ 各 Hook 点的安装结果（`AppSetting` / `PadUtil` / `isPadForMultiDeviceLogin` / NT 会话）
- `InitSessionConfig` 改写前后的 `deviceType` / `platform` / `appid`
- 服务端下发的在线设备列表（`client_type` 可区分手机与平板）
- 初始化或匹配失败原因

日志不应包含登录凭证、令牌或二维码票据的实际值。反馈兼容性问题时，请同时提供目标应用版本、模块版本和相关错误日志。

## 已知限制

QQ 的多端在线按**平台大类**判定互斥：安卓手机和伪装成平板的安卓设备仍属同一大类，两台安卓端登录同一账号会被服务端互踢。本模块解决的是「客户端被识别为平板」，不改变服务端的平台互斥策略。跨平台组合（安卓 + PC、安卓 + iOS）不受此限制。
