# 贡献指南 (Contributing Guide)

感谢您关注并愿意为 FuckClip 项目贡献代码与文档。为了保证项目的长期可维护性、架构整洁性以及多语言体验的一致性，请在提交 Issue 或 Pull Request 前仔细阅读本指南。

[English Version Below](#english-version)

---

## 目录
- [行为准则](#行为准则)
- [开发环境与准备](#开发环境与准备)
- [项目架构分层](#项目架构分层)
- [代码编写规范](#代码编写规范)
- [多语言与国际化规范](#多语言与国际化规范)
- [本地验证与测试](#本地验证与测试)
- [Git 提交与 Pull Request 流程](#git-提交与-pull-request-流程)

---

## 行为准则
参与本项目的所有贡献者均应保持友好、包容与尊重的态度。我们在代码审查与讨论中就事论事，共同维护健康的开源协作氛围。

---

## 开发环境与准备

在开始贡献前，请确保您的本地开发环境满足以下要求：
- **JDK**：OpenJDK 17（推荐 Eclipse Temurin 17 或 Android Studio 内置 JDK 17）。
- **Android SDK**：Compile SDK 35（Android 15），Min SDK 29（Android 10）。
- **Android Studio**：推荐使用最新稳定版（Ladybug 或更高版本）。
- **Python**：Python 3.8+（用于运行多语言自动化校验脚本）。
- **Git**：建议配置标准行尾换行符（Linux/macOS 默认 LF）。

拉取项目后，可在项目根目录下运行 Gradle 包装器检查编译状态：
```bash
./gradlew testDebugUnitTest
```

---

## 项目架构分层

FuckClip 采用严格的高内聚、低耦合多模块组件化设计，代码需归入正确的模块边界：

1. **`:core` 模块（通用领域与核心模型）**：
   - 包含跨进程共享的数据类（如 `HookLog`、`WhitelistRule`、`WhitelistBackup`）。
   - 包含纯业务逻辑状态机（如毒瘤应用频繁读取限流器 `RogueAppLimiter`）。
   - 包含跨模块通用的常量键（如 `PrefKeys`）。
   - **约束**：保持纯 Kotlin/Android 基础模型，严禁依赖 Compose UI 或 Xposed 框架。

2. **`:hook` 模块（系统层挂钩实现）**：
   - 基于 LibXposed API 102 规范实现。
   - 负责在 `system_server` 进程中拦截原生剪贴板服务与厂商定制剪贴板服务（如三星专有服务）。
   - 负责授权队列和系统级弹窗。放行读取时，原生剪贴板服务仍会执行 Android 的访问检查。
   - **约束**：严禁调用可能在系统服务内触发死锁的阻塞方法；系统级 UI 必须轻量高效。

3. **`:app` 模块（用户界面与管理中心）**：
   - 基于 Jetpack Compose 与 Material 3 构建现代响应式界面。
   - 采用 Koin 进行依赖注入，采用 StateFlow 与协程进行状态驱动。
   - 涵盖首页监控、应用规则列表、使用记录审计、全局偏好设置及动态主题配置。

---

## 代码编写规范

为保持代码质量与阅读体验，请遵循以下规范：

1. **单一职责与单文件行数控制**：
   - 每个类或 Composable 组件应当保持单一职责。
   - **严格控制单个 Kotlin 文件行数在 500 行以内**。当文件体积增大时，应及时按功能拆分（例如将大界面的弹窗、卡片、操作行提取为独立的子文件）。

2. **双语 KDoc 注释要求**：
   - 所有新增的 `public` 与 `internal` 顶层成员、类、函数、常量均需配备规范的中英双语 KDoc 注释。
   - 格式规范示例：
     ```kotlin
     /**
      * Dispatches clipboard read events to the asynchronous prompt queue.
      *
      * 将剪贴板读取事件分发至异步弹窗调度队列。
      *
      * @param packageName Target package requesting clipboard data.
      * @return Immediate decision if cached or throttled, or null if awaiting user prompt.
      */
     fun dispatchReadRequest(packageName: String): Boolean?
     ```

3. **Kotlin 编程风格**：
   - 遵循官方 Kotlin 编码规范（Kotlin Coding Conventions）。
   - 优先使用不可变数据结构（`val`、`List`、不可变 Data Class）。
   - 状态使用 Flow / StateFlow 驱动，避免在 UI 层持有易变全局变量。
   - 严禁使用未捕获的隐藏崩溃隐患，涉及反射与跨进程 IPC 调用必须有完善的异常捕获与日志记录。

4. **无 Emoji 原则**：
   - 代码标识符、字符串资源、日志及注释中均不使用 Emoji 符号。

---

## 多语言与国际化规范

本项目面向全球开源用户，支持多语言动态切换：

1. **资源完整性**：
   - 所有展示给用户的界面文本、提示语、对话框内容，**必须**统一定义在 `res/values/` 下的 XML 资源文件中，严禁在 Kotlin 代码中硬编码中英文字符串。
   - 每新增或修改一处字符串资源，必须同步在以下目录补全翻译：
     - `app/src/main/res/values/`（英语，主基准）
     - `app/src/main/res/values-zh-rCN/`（简体中文）
     - `app/src/main/res/values-zh-rTW/`（繁体中文）
     - `hook/src/main/res/`（若涉及系统服务层弹窗文本，需同步维护 Hook 模块独立资源）

2. **多语言自动化校验**：
   - 每次提交前，必须运行多语言一致性校验脚本：
     ```bash
     python3 tools/check_translations.py
     ```
   - 脚本会自动核对三种语言下的全部词条，输出 `Translation check passed` 方为合规。此项在 CI 门禁中为强制检查项。

---

## 本地验证与测试

在发起 Pull Request 之前，请在本地完整运行以下验证命令，确保无任何报错或警告：

```bash
# 1. 运行多语言一致性校验
python3 tools/check_translations.py

# 2. 运行所有模块的单元测试
./gradlew test

# 3. 验证 Release 打包
./gradlew assembleRelease
```

发布签名的本地验证步骤见 [README 构建与编译](../README.md#构建与编译)。

---

## Git 提交与 Pull Request 流程

1. **分支管理**：
   - 从 `master` 主分支拉出您的特性或修复分支，命名格式推荐：
     - `feat/feature-name`
     - `fix/issue-description`
     - `refactor/modularization-target`

2. **Commit 提交信息格式**：
   - 遵循标准语义化提交规范（Conventional Commits），格式如下：
     ```text
     <type>(<scope>): <清晰的中文或英文简述>
     ```
   - 常用 `type` 类型：
     - `feat`: 新增业务功能或特性
     - `fix`: 修复程序 Bug
     - `refactor`: 代码重构（不改变外部功能的架构拆分或整理）
     - `perf`: 性能优化
     - `i18n`: 多语言词条补充与修订
     - `docs`: 文档修改
     - `chore`: 构建配置、依赖版本或工具维护
   - 示例：
     - `refactor(ui): 模块化拆分 SettingsScreen 组件`
     - `fix(hook): 修复三星剪贴板服务在特定 ROM 下的反射空指针异常`

3. **创建 Pull Request**：
   - 确保 PR 标题精炼明确，PR 描述中列出变更内容、测试情况以及关联的 Issue 编号（如 `Closes #12`）。
   - PR 合并时会自动触发 GitHub Actions 发布流水线，并自动将您的 PR 标题归入版本 Release 说明中，因此请保持 PR 标题的规范与专业。

---

<span id="english-version"></span>

## English Version

Thank you for contributing to FuckClip. To keep our architecture robust, maintainable, and well-localized, please adhere to the following guidelines.

### Architecture Guidelines
- **`:core`**: Pure domain models, limiter state machines, and cross-module preference keys. No UI or Xposed dependencies.
- **`:hook`**: LibXposed API 102 implementation operating in `system_server`. Reflection proxies, deadlock-free dispatching, and system dialogs.
- **`:app`**: Jetpack Compose, Material 3, Koin DI, StateFlow-driven user interface.

### Code Quality Standards
- **File Size**: Every single Kotlin file must remain strictly under 500 lines. Decompose large files into cohesive, single-responsibility components.
- **KDoc Documentation**: All `public` and `internal` classes, interfaces, and functions must have bilingual KDoc comments (English and Chinese).
- **Clean Text**: Do not use emojis in code, resource keys, or documentation.

### Localization (i18n)
- Every user-facing string must be declared in XML resources across `values/` (en), `values-zh-rCN/` (zh-CN), and `values-zh-rTW/` (zh-TW).
- Always verify translations locally before submitting:
  ```bash
  python3 tools/check_translations.py
  ```

### Verification Checklist
Before submitting a Pull Request, confirm that:
1. `python3 tools/check_translations.py` passes 100%.
2. `./gradlew test` passes 100%.
3. `./gradlew assembleRelease` finishes with code 0.

Thank you for helping make FuckClip better!
