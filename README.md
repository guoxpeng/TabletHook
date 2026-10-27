# 微Q平板模块（TabletHook 精简分支）

这是 [roro2239/TabletHook](https://github.com/roro2239/TabletHook) 的个人维护分支，在原作者停更后继续跟进。

**本分支只做两件事：微信平板登录 + QQ 平板登录。**

## 与上游的差异

| 项目 | 上游 TabletHook | 本分支 |
| --- | --- | --- |
| 拼多多平板登录 | 支持 | **已删除**（`PddHook` 整体移除，含作用域） |
| 微信平板登录 | 支持 | 保留，并补充折叠屏白名单、密码页改道 |
| 微信 DexKit 定位 | 每次启动全量扫描 | **结果缓存**，命中即跳过 DexKit |
| 微信热补丁 | 不处理 | 等 Tinker 就绪后再挂钩 |
| QQ 平板登录 | 仅 MSF 客户端类型（DexKit 动态定位） | 重写为固定类名实现，覆盖 AppSetting / PadUtil / 多设备判定 / NT 会话，完全不依赖 DexKit |
| QQ 平板 appid | 无（不实现） | **跟随 QQ 原生返回值**，写死值仅作兜底 |
| 失败处理 | 静默 | 每条规则有不可变 ID，装不上明确打 `NO_COMPATIBLE_RULE` |
| 屏幕画像伪装 | 无 | 无（曾实现后删除，理由见下） |
| 模块入口 | `TabletHook.kt` | `QqPadEntry.kt` |
| 应用名 | TabletHook | 微Q平板模块 |
| 作用域 | 微信 / QQ / 拼多多 | 微信 / QQ |

删除拼多多的原因：上游的拼多多实现只能改客户端平板判定，而拼多多本身不支持同账号多端共存，登录态还会被服务端主动失效，实际收益为零，反而多一份注入面。相关代码已全部移除，不再维护。

## 支持范围

下表中的版本是当前完成静态分析和真机验证的最低版本。低于最低版本的应用未进行兼容性验证。

| 应用 | 包名 | 最低版本 | versionCode | 真机验证版本 |
| --- | --- | --- | ---: | --- |
| 微信 | `com.tencent.mm` | `8.0.77` | `3141` | 8.0.79 |
| QQ | `com.tencent.mobileqq` | `9.3.35` | `15560` | 9.3.75（`42350`） |

## 变更记录

### 2.6-resilient

这一版仍然不改变上报给服务端的数值，修的是「会不会整个装不上 / 装上了不生效」这一类问题。

- **`isFirstPackage` 不再当门槛**（本轮最关键的一条）。它的语义是「这个包是不是本进程加载的第一个包」，不是「这是不是首次回调」。微信走 Tinker 时补丁包同样触发 `onPackageReady`，那时该值可能是 `false` —— 用它做门槛会让微信侧一条 hook 都装不上，而且之后没有任何重试路径，表现是「模块启用了但微信完全没反应」。幂等改由 loader 引用比较承担。
- **修复微信 Tinker 兜底与真 loader 的竞态**。3 秒兜底线程可能抢在 Tinker 之前用启动时的 loader 装一遍；旧逻辑里 `wechatInstalled` 随即置位，等 Tinker 真正就绪也无法再装 —— 而那次安装的类对象和微信实际运行的补丁类不是同一个，表现就是「钩子装成功了但完全不生效」。现在 `fromTinker=true` 的安装允许覆盖兜底安装，兜底安装不允许覆盖真 loader。Tinker 时序日志同时落盘（原来只进 logcat，事后无法追溯）。
- **所有 hook 统一 `ExceptionMode.PROTECTIVE`**。模块注入在宿主进程里，自身抛出的异常绝不能让宿主崩溃（否则表现为「装了模块偶尔闪退」，极难归因）。11 个 hook 点全部改走 `HookUtils.safeHook`。
- **主动补一次原生 `AppSetting` 初始化**。反编译确认 `a()` 只做一件事：确保 `u()` 执行。而 `u()` 不只覆盖平板/手机凭证，还会把 manifest 的版本段拼进静态字段 `o`（客户端版本串）。从 2.4.2 起不再 `proceed()`，`e()` / `i()` 里那句 `a()` 也就没了。实测安全（`c()` / `d()` / `g()` / `h()` 每条都以 `a()` 开头，QQ 启动必然调用其中之一），但这是条隐式依赖，现在由 `primeNativeAppSetting` 主动保证。
  - 特意规避了反向风险：`u()` 内部 catch 掉异常后照常返回，而 `a()` 随后仍会把标记置位 —— 等于永久放弃初始化。所以 Context 没就绪时宁可不碰，交给 QQ 自己触发。
- **DexKit 加载增加 `System.loadLibrary` 兜底**。原来硬依赖 `extractNativeLibs=true`（走绝对路径 `System.load`），该配置一旦变更微信侧就会静默全崩。现在绝对路径不存在时退回 `loadLibrary`，并加了重复加载保护（DexKit 可能被重试调用）。
- **折叠屏白名单方法匹配改用 filter + 唯一性校验**。原来 `firstOrNull` 会按声明顺序随便挑同名重载，挑错就 `check` 失败、白名单永久装不上，且只有 WARN 级别。
- `AppSetting` 类缺失的日志只记首次，避免 QQ 为每个插件 loader 都刷一条。

### 2.5-idempotent

这一版不改变上报给服务端的任何数值（appid / key / deviceType 与 2.4.2 完全一致），只修「同一个正确的值可能因为时序或重复回调而变成错的」这一类结构性问题。

- **重复 install 导致 hook 叠加**：QQ 的 `onPackageReady` 会在同一进程里回调多次（旧日志实测一次运行出现两次，两次 `isFirstPackage` 都是 true）。原来每次都 `QqHook(this)` 新建实例，实例级的 `installedLoaders` / `delegateLoaderHooked` 去重全部失效，`DelegateLastClassLoader` 的 5 个构造器被叠着 hook 多遍，之后每次创建 loader 都触发多轮 `installOn`。现在 `QqPadEntry` 持有进程内唯一实例，重复回调只对**新的** loader 补装。
- **平板凭证不再被永久锁死**：原来 `padCredential` 是 `by lazy`。`e()` 首次被调用的时机可能早于 Application 就绪（`:MSF` 尤其明显），那次拿不到 Context 就退回编译期常量，而 `lazy` 会永久缓存这个结果——之后 Context 就绪了也不会再读 manifest。现在只缓存 manifest 真值，兜底结果 5 秒后自动重试。
- **NT 会话配置改为「要么全改，要么全不改」**：原来 `KPAD` / `KANDROID` 枚举缺失时用 `?.let` 静默跳过，`setField` 失败只 `Log.w`，可能出现 appid 已是平板、`deviceType` 还是手机的内部矛盾状态。现在三个字段必须都能解析才挂规则，写入时任一步失败就把已写字段回滚成原值。
- **`e()` / `i()` 拦截体加异常兜底**：这两个方法是 QQ 取 appid / key 的必经之路，任何异常冒出去都会打断调用方。现在异常时返回常量兜底并限流记一条日志。
- **`appContext()` 补全兜底链路**：`BaseApplication` 是 QQ 的类，原来用模块自己的 classloader 去 `Class.forName` 大概率 ClassNotFound，等于没有兜底。现在改用 app 的 loader，并增加 `currentActivityThread().getApplication()` 这条路径。
- **修复 `InitSessionConfig` 的 after 日志从来没打出来**：原来 before / after 共用一个 `sessionLogged` 标志，before 先置位，after 永远被吞。「对比改前改后」这个功能实际从未生效。
- `QQ_DRY_RUN=true` 时也会 dump 会话配置（诊断模式下正是要看「本来报什么」）。
- `AppSetting.e()` / `i()` 优先匹配**无参**版本，避免将来 QQ 增多一个重载时把 no-arg 版本挤成 `AMBIGUOUS_TARGET` 而整条规则失效。
- `DelegateLastClassLoader` 的构造器改为逐个兜异常：某一个挂不上不再连累其余几个。
- 微信定位缓存增加**版本号**校验：类名和签名都没变、但方法语义变了的情况靠签名查不出来，只能靠版本号让缓存整体作废。
- 落盘日志加 1MB 上限（主进程与 `:MSF` 并发追加，长期不封顶会一直涨）。
- 在线设备诊断规则被配置关闭时明确记一条日志，避免 `installed=6` 被误读成「有一条规则失效」。

### 2.4.2-manifest

- **平板凭证改为从 AndroidManifest 单一来源解析**。`AppSetting.e()` / `i()` 现在共用同一份 `PadCred`（appid + key），取值优先读 QQ 自己的 `metaData["AppSetting_params_pad"]`，读不到才退回常量。
- 依据：反编译 `AppSetting.u()` 可见，原生 `e() = o(ctx) ? g : f`、`i() = o(ctx) ? j : i`，而 `g/j` 正是被 `AppSetting_params_pad` 覆盖出来的。所以 manifest 就是原生平板分支的唯一真源，读它等价于读原生，且 appid 与 key 天然配对。
- 修复 2.4.1 的**判据分裂**隐患：那一版 `e()` 用 `padFlag && native is Int && native > 0` 判断、`i()` 用 `padFlag && !native.isNullOrEmpty()` 判断，两侧是独立分支。旧日志中已实际出现过同一进程内 `e()` 走 `follow-native`、`i()` 走 `fallback` 的情况；只因当时常量恰好等于真值才没有出错。一旦 QQ 更换平板 appid，就会出现 appid 取新值、key 取旧常量的错配签名。
- `e()` / `i()` 不再执行 `chain.proceed()`，`o()` 也直接返回 `true`。高频的 `i()`（被 `AppSetting.k()` 设备指纹调用）路径上零反射、零跨层调用。
- 日志开关拆细：新增 `LOG_QQ_ONLINE_DEVICES`（默认关）。此前在线设备 dump 挂在服务端推送回调 `RegisterProxySvcPackHandler.e3` 上，每次推送都做反射 + 写文件，属于持续性 IO。`LOG_QQ_SESSION` 仅保留一次性的 `InitSessionConfig` 输出。
- 新增启动日志 `PAD_CRED source=manifest|const-fallback appId=... key=脱敏 padUtilHooked=...`，一眼可看出这次用的是 manifest 真值还是常量兜底。
- 修正文档中「dex 静态字段 `g=537397237` / `j=C5EF37EE8FAB3E09` 是平板值」的错误说法：那两个只是编译期占位符，运行时会被 manifest 覆盖成 `537397816` / `0FFD632C4B7E08AF`。

### 2.4.1-adaptive

> 已被 2.4.2 取代：本版的「分别跟随原生」存在 appid / key 判据分裂风险，详见上一节。

- **QQ 平板 appid 改为跟随原生**：`AppSetting.e()` / `i()` 不再无脑返回写死值，而是先取一次原生返回值。只要原生 `o()` 走过平板分支，就采用原生给出的 appid / key；只有原生仍判为手机时才回退到常量。
- 意义：QQ 换代更换平板 appid 时，模块自动跟随，不会再出现「客户端自称平板、服务端不认」的**静默降级**。
- `key` 与 `appid` 强制同源：`i()` 若发现平板判定尚未确认，会先触发一次 `e()` 补齐判定，避免 appid 跟随新版而 key 仍兜底旧值。
- NT `InitSessionConfig` 的 appid 同步改为从当前解析结果读取，不再写死。

### 2.2-rules

- **QQ 每条 hook 加不可变规则 ID**（`qq_app_setting_appid_001`、`qq_pad_util_001`、`qq_session_init_001` 等共 7 条），并要求「刚好命中一个」才挂载。
- 匹配不到或匹配到多个时记录 `NO_COMPATIBLE_RULE`（含 `TARGET_NOT_FOUND` / `AMBIGUOUS_TARGET n=` / `SIGNATURE_CHANGED` / `ENUM_MISSING`），并在启动时输出汇总：`rule-runtime event=summary installed=N failed=M ok=[...] bad=[...]`。失败不再静默。
- **微信等 Tinker 就绪后再挂钩**：先探测 `TinkerApplication`，存在则等 `onBaseContextAttached` 之后用热补丁 classloader 安装，避免热更新后 hook 指向旧代码；另设 3 秒兜底，防止未走 `super` 时永不回调。

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
- QQ 平板 appid / key **从 AndroidManifest 的 `AppSetting_params_pad` 单一来源解析**（`appid#key#...`）。这正是 QQ 原生 `AppSetting.u()` 覆盖静态字段 `g` / `j` 的地方，所以等价于原生平板分支，且 appid 与 key 必然配对；读不到时才退回常量 `537397816` / `0FFD632C4B7E08AF`。
- 对关键候选执行数量和签名校验，匹配异常时记录规则 ID 与原因，不使用不确定结果。

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

`InitSessionConfig.deviceInfo` 里的机型信息（`devName` / `vendorName` / `osVer`）保持真实值，没有跟着 `deviceType=KPAD` 一起伪装成平板型号。刻意如此：改机型只会引入更多与真实硬件矛盾的字段，风险大于收益；但这也意味着「客户端类型是平板、设备名是真机型号」是本模块已知的可观测面。真机实测服务端接受该组合（在线设备列表 `client_type=7`）。

微信侧 `inTabletEnv` 与副设备登录资格两个方法依赖 DexKit 动态定位，缓存按版本号失效；折叠屏白名单 `com.tencent.mm.ui.ok.R()` 与密码页 `LoginPasswordUI` 是固定类名，微信改这两处时只会丢对应辅助功能（折叠屏适配 / 密码页改道），不影响平板登录主链路，日志里会有 `wechat R hook FAILED` / `wechat password hook FAILED`。
