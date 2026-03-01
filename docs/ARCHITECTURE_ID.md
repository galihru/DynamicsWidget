# WidgetDynamics 系统架构（中文版）

## 1. 系统目标

左下角桌面小组件，具备以下行为：

*   鼠标悬停图标即可打开应用，无需点击。
*   鼠标离开交互区域后自动关闭。
*   打开时立即获得焦点，随时可交互。
*   内置浏览器标签页 + 天气 / 待办 / 消息模块。
*   标签页可固定（Pinned），在特定自动关闭事件中保持不被关闭。
*   动态通知图标 + 悬停触发的深度链接（deep-link）。
*   后台运行 + Windows 开机自启动。
*   支持多运行时扩展（Scala + Node + 插件进程）。

***

## 2. 组件架构

```text
+-------------------------------------------------------------+
| Widget Host (Scala)                                         |
|-------------------------------------------------------------|
| AppStateStore | EventBus | HoverStateMachine | TabManager  |
| FocusController | NotificationRouter | DeepLinkRouter       |
| ModuleHost | StartupManager                                 |
+-------------------------+-----------------------------------+
                          |
                          v
+-------------------------------------------------------------+
| UI Layer (Swing/JCEF)                                       |
| - 锚点图标窗口（左下角）                                     |
| - 主 Widget 窗口                                            |
| - 标签栏 + 搜索框                                           |
| - 浏览器模块（生产环境用 Chromium）                        |
| - 天气 / 待办 / 消息 面板                                  |
+-------------------------------------------------------------+
                          |
                          v
+-------------------------------------------------------------+
| Workers / 插件                                              |
| - Node worker（推送 / 消息引擎）                            |
| - 可选语言插件（Hack 等，通过 JSON-RPC IPC）               |
+-------------------------------------------------------------+
```

***

## 3. 悬停 / 打开 / 关闭 状态机

### 状态

*   `Collapsed`（收起）
*   `Expanding`（展开中）
*   `Expanded`（已展开）
*   `Collapsing`（收起中）
*   `PinnedHold`（固定保持）

### 事件

*   `TrayHoverEnter`（托盘图标进入悬停）
*   `TrayHoverExit`
*   `PointerMoved`
*   `OpenByDeepLink`
*   `PinChanged`
*   `CloseTimerElapsed`
*   `WindowFocusLost`

### 核心规则

1.  鼠标悬停图标 → 进入 `Expanded`。
2.  鼠标离开交互范围 → 进入 `Collapsing`，启动 debounce 计时器。
3.  计时完成且鼠标仍在外 → 切换到 `Collapsed`。
4.  若在超时前重新进入 → 取消关闭，返回 `Expanded`。
5.  若当前标签页被固定 → 进入 `PinnedHold`。
6.  悬停动态通知图标 → `OpenByDeepLink` 跳转特定标签/线程/URL。

***

## 4. 交互区域（防闪烁机制）

有效指针区域为以下区域的并集：

*   `anchorRect`（图标窗口）
*   `mainWindowRect`
*   `safeCorridorRect`（图标到主窗口之间的安全走廊）

目的：鼠标从图标移动到窗口时不触发自动关闭。

***

## 5. 标签页与浏览器

标签页模型：

*   `id`, `title`, `kind`, `url`,
*   `pinned`, `unreadCount`, `lastAccessMs`.

操作包括：

*   添加 / 删除 / 选择 标签页
*   固定 / 取消固定
*   URL 导航 或 搜索查询

浏览器（生产环境）：

*   使用嵌入式 Chromium（JCEF/WebView2）
*   仍提供 Stub 实现，便于无 heavy dependency 的测试

***

## 6. Widget 模块

### 天气（Weather）

*   使用 Open-Meteo 免费 API。
*   缓存 10–15 分钟。

### 代办（Todo）

*   Offline-first。
*   保存至 SQLite 或本地文件。

### 消息（Messaging）

*   从 worker 接收通知。
*   Deep-link 到具体消息线程。

***

## 7. 动态图标 & 智能悬停

处理流程：

1.  通知进入 → 优先级评分。
2.  渲染动态图标（徽标 / 平台标识 / 头像）。
3.  悬停在动态图标上 → 自动打开指定 deep-link。

Deep-link 格式：

*   `widget://messaging/thread/{id}`
*   `widget://browser/open?url=...`
*   `widget://weather/location/{lat},{lon}`

***

## 8. 多语言运行时策略

采用 **独立进程边界（boundary process）**，不在主进程混合多语言：

*   Scala：UI + 状态引擎（主进程）
*   Node：推送 + 消息连接器
*   可选 Hack：插件进程（JSON-RPC / stdin-stdout / websocket）

优点：

*   崩溃隔离
*   独立升级，热插拔
*   安装程序可选择需要的运行时

***

## 9. 安装器 & 后台自启动

安装脚本 `Install-WidgetDockPro.ps1`：

1.  检查/安装运行时（JRE、WebView2、Node）。
2.  拷贝 bundle 至 `%LOCALAPPDATA%\WidgetDockPro`。
3.  注册开机启动快捷方式。
4.  使用 `javaw`（无 console）启动应用。
5.  自动打开安装目录。

***

## 10. 验收目标

*   悬停打开 < 120ms
*   自动关闭稳定、无闪烁
*   标签添加 / 删除 / 固定 全部可用
*   搜索栏 + 浏览标签可导航
*   通知可动态更新图标
*   图标悬停可自动 deep-link 打开对应内容
*   开机自启 + 后台稳定运行
