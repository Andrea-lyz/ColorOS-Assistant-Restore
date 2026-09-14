# ColorOS 唤语 · AssistRestore

[![简体中文](https://img.shields.io/badge/README-简体中文-dc8a3c?style=for-the-badge)](README.md)
[![English](https://img.shields.io/badge/README-English-5b6b8c?style=for-the-badge)](README_EN.md)

[![libxposed API](https://img.shields.io/badge/libxposed-API%20102-brightgreen)](https://github.com/libxposed/api)
[![Platform](https://img.shields.io/badge/ColorOS-16%20CN-1a73e8)](docs/technical-notes.zh.md)
[![Root](https://img.shields.io/badge/Root-KernelSU%20%7C%20Magisk-orange)](#环境要求)

在 ColorOS 国内版上还原 AOSP 的数字助理行为。

国内固件把「唤醒数字助理」的三个入口改写成了自家小布助手，并让这些路径绕开 AOSP 的助理分派：长按电源键固定启动小布、长按手势条固定触发小布识屏、屏幕底部角落内滑不派发任何助理。本模块不替换系统的助理栈，只在被改写的那几个分派点上把请求交还给系统当前设置的默认助理应用（`Settings.Secure.assistant` / `RoleManager.ROLE_ASSISTANT` 指向的应用），对上层保持与 AOSP 一致的行为。

## 功能

三个入口在国内外固件上都是同一套代码，区别只在两处区域闸门和若干写死的组件名，因此三条路径都可以逐个接管：

| 入口 | 国内固件的原始行为 | 接管后的行为 |
| --- | --- | --- |
| 长按电源键 | 显式启动 `com.heytap.speechassist` | 按 AOSP 分支走 `launchAssistAction`，唤醒默认助理（伴随一次长按震动） |
| 长按手势条 | `start_type=91` 交给小布识屏 | 唤醒默认助理；可选改为「一圈即搜」或「小布识屏」 |
| 底角内滑 | 助理可用性恒为 `false`，手势不生效 | 恢复助理可用性判定，设置等页面也能用 |

每个入口都可以单独选择唤醒目标，也可以单独关闭。除此以外：

- **一圈即搜**：为长按手势条补齐了国内固件缺失的系统 CTS 服务链路，并可在 Google 应用进程内做机型伪装。
- **页面级手势**：桌面默认会屏蔽设置这类页面请求的底角手势，模块把这两个页面级屏蔽位放开。
- **抑制无用唤醒**：长按手势条时不再预先白唤醒一次小布识屏服务。
- **进程保活**：Google 应用被系统冻结是「派发成功但屏幕没反应」的常见原因，模块在 OEM 冻结决策处对该应用做了豁免。

## 环境要求

- ColorOS 国内版固件（已在 ColorOS 16 / `V16.1.0` / PJZ110 / `regionmark=CN` 上验证）。
- KernelSU 或 Magisk 等 Root 方案。
- LSPosed 等支持 libxposed API 102 的框架。

## 安装

1. 安装 `app-debug.apk`。
2. 在 LSPosed 中启用模块，作用域勾选以下四项：

   ```
   system
   com.android.systemui
   com.android.launcher
   com.google.android.googlequicksearchbox
   ```

3. 重启设备。
4. 打开「ColorOS 唤语」，在「入口」页确认模块状态为已生效，并按需调整三个入口的唤醒目标。

四个作用域分别对应电源键派发、SystemUI 手势与助理派发、桌面底角手势、以及一圈即搜与进程保活。缺少任意一项，对应功能不会生效。

## 使用

应用底部有两个页面，功能页从「入口」页进入。

**入口页**：顶部两张卡片分别显示模块状态和当前系统默认助理（点击可直接跳转系统助理设置），下方是三个唤醒入口和「模块接管全部入口」总开关。

**唤醒目标页**：顶部按「长按电源键 / 长按手势条 / 底角内滑」分页，每个入口的目标互不影响。可选目标如下：

| 目标 | 说明 |
| --- | --- |
| 跟随系统默认助理 | 唤醒 `Settings.Secure.assistant` 指向的应用，即 AOSP 语义 |
| 一圈即搜 | 走系统 CTS 服务；仅默认助理为 Google 应用时有意义 |
| 小布识屏 | 不接管，长按手势条仍由 ColorOS 自己处理（仅长按手势条） |
| 小布助手 | 直接唤起小布助手本体（仅长按电源键与底角内滑） |
| 其他应用… | 进入自定义目标页，手动填写包名或服务组件 |
| 全部关闭 | 该入口不唤醒任何助理，同时压掉 OEM 自己的调用 |

**自定义目标页**：可以粘贴应用信息里的 Intent JSON 自动识别，也可以手动填写包名、服务组件、调用方式和 Intent 参数。

**高级页**：

| 选项 | 默认 | 说明 |
| --- | --- | --- |
| 跳过识屏服务预绑定 | 开 | 长按手势条时不再白唤醒一次小布识屏服务 |
| 解除页面级手势限制 | 开 | 放开应用请求的页面级屏蔽位，设置等页面也能用底角内滑 |
| Google 应用机型伪装 | 开 | 在 Google 应用进程内伪装为 SM-S928B，解锁一圈即搜 |
| 隐藏手势条时保持长按 | 开 | ColorOS 在隐藏手势条后会连手势条区域的长按一起停用；打开后底部原位置的长按照常唤醒该入口配置的目标。手势条本身的显示不变 |
| 隐藏桌面图标 | 关 | 关掉桌面上的图标（Launcher 入口是一个 activity-alias）。入口 Activity 仍保留 MAIN + INFO 过滤器，所以隐藏后依然能从 LSPosed 模块页或系统设置的应用详情打开本应用 |

长按电源键的震动反馈固定开启，不提供开关。

## 已知限制

系统的语音交互会话同一时刻只认一个助理应用，这是平台本身的约束，因此：

- 「其他应用」只对该应用自己声明了助理活动的情况有效；部分应用（例如 ChatGPT 的 `ACTION_ASSIST` 代理活动）在没有活动会话时不会有任何反应。
- 自定义目标以显式组件加参数的方式启动，能否弹出界面取决于目标应用本身。
- 页面级放开只覆盖应用可请求的那两个屏蔽位，锁屏、通知栏、QS 展开、导航栏隐藏和屏幕固定仍然保持屏蔽。
- 同一手势不要和其它接管类模块（例如 Oplus-Assistant-Hook）同时启用，先接管的一方会直接返回。

## 排错

模块日志的 TAG 为 `AssistRestore`，在 LSPosed 日志里按进程筛选即可。常见记录：

| 记录 | 含义 |
| --- | --- |
| `power_key_target mode=... package=...` | 电源键命中，已按配置解析出目标 |
| `assist_dispatch component=... invocationType=...` | 已派发到助理（1 底角、5 手势条、6 电源键） |
| `target_started entry=... method=...` | 自定义目标已启动 |
| `power_key_skip reason=disabled` / `assist_skip reason=...` | 按开关或页面状态主动跳过 |
| `gesture_handle_ocr_preload_skipped` | 正常：本次长按由模块接管，已跳过识屏服务预绑定 |
| `circle_to_search_triggered` | 一圈即搜已触发 |
| `google_hans_scene_exempt` | 已在系统冻结决策处豁免 Google 应用 |

出现 `assist_dispatch` 但屏幕没有任何反应，说明派发链路正常，问题在助理进程被冻结或回收：把默认助理应用设为允许后台运行、关闭权限自动回收，再试一次。

更完整的逆向依据、方法签名与真机日志见 [技术说明](docs/technical-notes.zh.md)。

## 从源码构建

需要 JDK 21 与 Android SDK（`compileSdk 37`）。工程使用 Gradle 8.13 wrapper 与 AGP 8.13.2。

```bash
./gradlew assembleDebug
```

产物位于 `app/build/outputs/apk/debug/app-debug.apk`。离线环境可在依赖缓存完整时加 `--offline`。

## 版本

**1.0.0** — 三个入口全部可用，支持逐入口选择唤醒目标、一圈即搜、页面级手势放开与 Google 应用进程保活。

## 免责声明

本项目仅供学习与研究。修改系统助理分派行为存在风险，请自行评估并做好备份。
