# TabletHook

TabletHook 是一个基于 libxposed API 102 和 DexKit 的 Xposed 模块，用于让支持的应用开启平板模式登录。

## 支持范围

下表中的版本是当前完成静态分析和真机验证的最低版本。低于最低版本的应用未进行兼容性验证。

| 应用 | 包名 | 最低版本 | versionCode |
| --- | --- | --- | ---: |
| 微信 | `com.tencent.mm` | `8.0.77` | `3141` |
| QQ | `com.tencent.mobileqq` | `9.3.35` | `15560` |
| 拼多多 | `com.xunmeng.pinduoduo` | `8.19.0` | `81900` |

## 运行要求

- Android 11 或更高版本（`minSdk 30`）
- 支持 libxposed API 102 的 Xposed 框架
- 已安装受支持的目标应用

## 安装使用

1. 从 [Releases](https://github.com/roro2239/TabletHook/releases) 下载并安装模块 APK。
2. 在 Xposed 管理器中启用 TabletHook。
3. 确认模块作用域包含需要启用平板模式的应用。
4. 完全结束目标应用进程后重新打开应用。
5. 按目标应用的正常登录流程完成登录。

## 技术实现

- 使用 `libxposed API 102` 接入 Xposed 生命周期和 Hook API。
- 使用 `DexKit 2.2.0` 按字符串、方法签名和调用关系动态定位目标逻辑。
- 不依赖目标应用的固定混淆类名，降低应用更新导致类名变化的影响。
- 对关键候选执行数量和签名校验，匹配异常时记录错误，不静默使用不确定结果。
- 每个目标应用使用独立 Hook 类，公共 DexKit 加载、候选检查和基础 Hook 能力由工具类统一提供。

动态定位只能降低版本变化带来的影响，不能保证所有后续版本自动兼容。目标应用更新后如出现匹配失败，需要结合日志重新分析。

## 项目结构

```text
app/src/main/java/com/roro/tablethook/
├── TabletHook.kt          模块入口、进程识别和应用分发
├── hook/
│   ├── WeChatHook.kt      微信平板登录逻辑
│   ├── QqHook.kt          QQ 平板登录逻辑
│   └── PddHook.kt         拼多多平板登录逻辑
└── util/
    └── HookUtils.kt       DexKit 加载和公共 Hook 工具
```

## 日志

模块日志统一使用 `TabletHook` 标签。可在 Xposed 管理器的模块日志中按该标签筛选，主要包括：

- DexKit 候选数量和匹配结果
- 平板模式 Hook 初始化结果
- 目标登录链路的脱敏诊断信息
- 初始化或匹配失败原因

日志不应包含登录凭证、令牌或二维码票据的实际值。反馈兼容性问题时，请同时提供目标应用版本、模块版本和相关错误日志。
