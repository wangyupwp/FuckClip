# FuckClip

FuckClip 是一个 Android 剪贴板权限管理模块。它通过 [LibXposed API 102](https://github.com/libxposed/api) 在系统服务中拦截标准剪贴板操作，让你按应用决定谁可以读取、写入或清空剪贴板。

[简体中文](#安装与激活) · [English](#english) · [Releases](https://github.com/wangyupwp/FuckClip/releases) · [MIT License](LICENSE)

当前源码的默认构建版本为 **v1.0.6（10006）**。最低安装版本是 **Android 10（API 29）**，编译和目标 SDK 均为 **API 35**。剪贴板拦截和预览的实际兼容性取决于系统实现及所用的 Xposed 框架。

## 安装与激活

1. 准备已 Root 的 Android 设备，安装支持 LibXposed API 102 和系统进程作用域的 LSPosed 或兼容框架。
2. 安装已签名的 Release APK。可在 [Releases](https://github.com/wangyupwp/FuckClip/releases) 查看已发布的版本，或使用本地构建的安装包。不确定设备架构时，选文件名含 `universal` 的版本。
3. 在框架管理器中启用 **FuckClip**，作用域选择 **系统框架（`system` / `system_server`）**。
4. 重启设备加载模块。覆盖升级后也建议重启，让系统进程加载新版拦截逻辑。

标准剪贴板接口之外的厂商扩展需要单独验证。部分 ROM 可以通过 `IClipboard.Stub` 找到服务，但预览依赖已识别的系统剪贴板实现。

## 权限规则与读取预览

在 **应用程序** 页面，为每个应用分别设置读取和写入规则。**清空剪贴板沿用写入规则**，在询问时会明确提示应用请求清空。

| 规则 | 行为 |
| --- | --- |
| 询问 | 请求通过限流检查且无可复用授权时显示系统弹窗。你可以允许或拒绝本次操作，也可以勾选「记住此选择」。 |
| 允许 | 将请求交给 Android 原生服务。Android 仍会执行自身的访问检查。 |
| 拒绝 | 读取返回空结果，写入或清空请求被拦截。 |

读取询问弹窗可以预览主用户在解锁状态下的剪贴板文本。敏感标记、其他用户的剪贴板、锁屏状态以及无法确认的剪贴板实现会隐藏读取预览。如果剪贴板在预览后发生变化，本次读取会返回空结果。

只有 Android 成功返回与预览一致的内容后，单次允许才可能开启临时放行。该窗口只允许同一应用再次读取同一份内容。拒绝、关闭弹窗或超时会撤销旧的临时放行；超时不会保存永久规则。**设置 → 防护与限制** 可调整拒绝冷却、快速连读时长、频次上限和并发容忍数。

## 使用记录、归档与后台接收

- **使用记录**显示最近 100 条日志，可按读取、写入及处理结果筛选，也可搜索应用名或已记录的内容。短时间内的重复操作可能合并显示。
- **归档**保留更早的记录。在使用记录页点右上角「归档」即可分页查看。最近记录和归档都存在应用私有的 SQLite 数据库中，应用重启后会重新加载。「清除历史记录」会同时删除两处记录。
- **日志保活**位于 **设置 → 数据 → 日志保活**。开启后，前台服务通过通知维持后台日志接收。Android 13 及以上会在开启时按需请求通知权限。通知可以停止服务，设备重启或应用被强制停止后，需要重新开启。

读取日志的正文来自 Android 实际返回给调用方的内容。带敏感标记的剪贴板和其他 Android 用户的剪贴板只记录操作信息，通知也不包含剪贴板原文。日志数据库排除在 Android 自动备份之外，归档目前没有自动清理周期。

## 其他功能

- 在 **设置 → 数据** 导入或导出 JSON 格式的应用规则。规则备份不包含使用记录。
- 支持简体中文、繁體中文和英语。可切换浅色、深色、纯黑和跟随系统主题，也可在支持的设备上使用动态配色。
- 应用内更新目前检查上游的 [GitHub Releases](https://github.com/Cail-Gainey/FuckClip/releases)，可下载匹配架构的 APK 并交由 Android 安装器处理。

## 本地构建

需要 JDK 17、Android SDK Platform 35、Android Build Tools 35.0.0 和 Python 3。Gradle Wrapper 使用 8.14.3。

```bash
python3 tools/check_translations.py
./gradlew testDebugUnitTest assembleDebug
./gradlew :app:assembleRelease
```

Windows PowerShell 使用 `python` 和 `.\gradlew.bat`。APK 输出位于 `app/build/outputs/apk/`。构建会生成 `arm64-v8a`、`armeabi-v7a`、`x86_64` 和 `universal` 四种 APK。

### Release 签名

`assembleRelease` 生成待签名 APK。维护者在环境中设置 `KEYSTORE_FILE`、`KEYSTORE_PASSWORD`、`KEY_ALIAS` 和 `KEY_PASSWORD` 后，可以使用项目脚本签名并验证证书链。

```bash
python3 tools/sign_release.py --apksigner "$ANDROID_HOME/build-tools/35.0.0/apksigner" \
  app/build/outputs/apk/release/app-universal-release-unsigned.apk FuckClip-universal-release.apk
```

发布脚本使用 AppMarket 的发布证书，并把公开轮换证明 [`keystore/release.lineage`](keystore/release.lineage) 写入 APK。轮换链连接旧版 FuckClip 的内置发布证书和当前证书。用该旧证书签署的版本可使用更高版本号的轮换包覆盖升级；使用其他证书签署的安装包需要单独迁移。GitHub Actions 使用 `KEYSTORE_BASE64`、`KEYSTORE_PASSWORD`、`KEY_ALIAS` 和 `KEY_PASSWORD` 四个仓库 Secrets。

## 项目结构

| 模块 | 职责 |
| --- | --- |
| `:app` | Compose 管理界面、应用规则、SQLite 日志与归档、日志保活和应用更新 |
| `:hook` | `system_server` 中的剪贴板拦截、授权弹窗、读取预览和审计事件 |
| `:core` | 共享配置键、规则和日志模型、读取限流与临时授权状态 |

更多资料见 [架构设计](docs/ARCHITECTURE.md)、[常见问题](docs/FAQ.md)、[更新日志](docs/CHANGELOG.md)、[贡献指南](docs/CONTRIBUTING.md)、[安全策略](docs/SECURITY.md)和[行为准则](docs/CODE_OF_CONDUCT.md)。

## English

FuckClip adds per-app **Ask**, **Allow**, and **Deny** policies to Android clipboard access. Read and write policies are independent, and clearing the clipboard follows the write policy. Requests that pass the module still go through Android's own checks.

- **Install** a signed Release APK on a rooted device with a LibXposed API 102 framework. Enable FuckClip for **System Framework** (`system` / `system_server`) and reboot. Android 10 (API 29) is the minimum install version.
- **Read preview** shows eligible clipboard text in the authorization dialog on supported system implementations. Sensitive clips, other users' clips, and locked sessions are excluded. If the clip changes after preview, the request returns no data. A successful one-time grant can be reused briefly for the same clip only.
- **Audit log** keeps the latest 100 entries in the main view and older entries in a paged archive. Both are stored in a private SQLite database excluded from automatic backup. Enable **Log keep-alive** under **Settings → Data** for background log reception through a foreground notification.
- **Build** with JDK 17, Android SDK 35, and the Gradle wrapper. Run `./gradlew assembleDebug` for a debug APK or follow [Release signing](#release-签名) for signed packages.

## 许可与致谢

本项目采用 [MIT 许可证](LICENSE)。感谢原作者 [nlifew](https://github.com/nlifew)、重构维护者 [Cail-Gainey](https://github.com/Cail-Gainey)，以及 [LibXposed](https://github.com/libxposed)、[MaterialKolor](https://github.com/jordond/MaterialKolor) 和 [Koin](https://insert-koin.io/) 项目。
