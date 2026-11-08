# 技术架构与设计原理 (Technical Architecture)

本文档深入解析 FuckClip 的系统级挂钩机制、分层架构与核心设计决策。

[English Version Below](#english-version)

---

## 整体架构视图

FuckClip 采用高内聚、低耦合的多模块组件化设计：

```mermaid
flowchart TD
    subgraph SystemServer["system_server 进程 (Android Framework)"]
        AOSP["原生 ClipboardService"]
        SAMSUNG["IClipboard.Stub 发现兜底"]
        HOOK[":hook 模块 (LibXposed API 102)"]
        LIMITER["毒瘤应用限流引擎 (RogueAppLimiter)"]
        PROMPT["异步防死锁弹窗 (ClipboardPromptManager)"]
        
        AOSP --> HOOK
        SAMSUNG --> HOOK
        HOOK --> LIMITER
        LIMITER --> PROMPT
    end

    subgraph UserApp["io.github.cail_gainey.fuckclip 进程 (用户管理界面)"]
        COMPOSE["Jetpack Compose UI (Material 3)"]
        VIEWMODEL["ViewModel (StateFlow & 协程)"]
        REPO["SettingsRepository / Store"]
        KOIN["Koin 依赖注入容器"]

        COMPOSE --> VIEWMODEL
        VIEWMODEL --> REPO
        REPO --> KOIN
    end

    subgraph Core["共享核心 (:core 模块)"]
        MODELS["领域实体: WhitelistRule, HookLog"]
        KEYS["全局偏好键: PrefKeys"]
        LIMITER_CORE["限流算法状态机"]
    end

    HOOK -. 依赖 .-> Core
    UserApp -. 依赖 .-> Core
    UserApp == 跨进程规则同步 ==> HOOK
```

---

## 模块分层职责

### 1. `:core` 模块（通用基础与领域模型）
- **定位**：纯 Kotlin/Android 基础模型与纯算法库，不包含任何 UI 与 Xposed 框架依赖。
- **核心组件**：
  - `WhitelistRule`：三态规则实体，定义 `REQUEST`（询问）、`DENY`（拒绝）、`GRANT`（允许）。
  - `HookLog`：审计记录实体，记录调用包名、时间戳、操作类型（READ / WRITE）及处理结果。
  - `RogueAppLimiter`：滑动时间窗口频次限流器，基于环形时间队列维护调用频次。
  - `PrefKeys`：集中管理跨进程共享的键名。

---

### 2. `:hook` 模块（系统层挂钩实现）
- **定位**：工作在 Android 核心系统进程 `system_server` 中，基于 LibXposed API 102 现代标准。
- **挂钩拦截策略**：
  - **原生 AOSP 服务**：挂钩 `com.android.server.clipboard.ClipboardService` 的 `getPrimaryClip`、`setPrimaryClip` 等核心 Binder 接口。
  - **定制 ROM 兜底**：从 `IClipboard.Stub.onTransact` 发现实际服务实例。
- **读取权限**：模块允许后，Android 原生服务继续执行访问检查。读取弹窗不读取缓存，审计日志只可记录原生服务返回的内容。
- **授权调度**：弹窗由主线程展示。最多四个 Binder 读取调用等待授权，每个读取请求从入队起最多等待 12 秒，超时会退出队列。
- **跨进程规则**：`system_server` 向管理端的非导出接收器发送定向广播。管理端只更新用户决定的操作方向，Hook 从远程偏好读取最新规则。

---

### 3. `:app` 模块（现代化用户界面）
- **定位**：用户配置与监控审计中心，采用现代 Android 全技术栈构建。
- **核心特性**：
  - **Jetpack Compose + Material 3**：全面响应式 UI，无遗留 XML 布局依赖。
  - **动态主题引擎**：支持跟随系统的动态调色板（Dynamic Color）及多种调色风格（Tonal Spot、Neutral、Vibrant、Expressive、Monochrome）。
  - **单向数据流 (UDF)**：通过 `StateFlow` 与 `SharedFlow` 驱动 UI 重组，状态清晰无副作用。
  - **组件化轻量单文件**：所有界面拆分为独立的卡片、对话框与组件子文件，严格控制单文件在 500 行以内。

---

<span id="english-version"></span>

## English Version

### Architecture Overview

FuckClip is structured into three distinct modules to ensure separation of concerns and maintainability:

1. **`:core`**: Pure domain models and algorithm components. Contains `WhitelistRule`, `HookLog`, `RogueAppLimiter`, and shared constants. Does not depend on Android UI or Xposed libraries.
2. **`:hook`**: LibXposed API 102 implementation injected into `system_server`. It intercepts clipboard reads and writes, respects Android's access checks, and queues prompts with bounded read concurrency and per-request deadlines.
3. **`:app`**: Modern Jetpack Compose & Material 3 application. Powered by Koin dependency injection, StateFlow state machines, dynamic theming palettes, and robust JSON backup/restore tools.
