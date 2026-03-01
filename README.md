# WidgetDynamics


Windows 桌面小部件的新项目，具有零点击悬停模式、浏览器标签页、小部件模块、动态通知和自动启动功能。


## 活跃功能


- 鼠标悬停图标即可打开小部件，无需点击，退出区域会自动关闭。
- 浏览器标签页使用 JavaFX WebView（真正的网页内容）。
- 浏览器状态栏显示加载状态（加载中/成功/失败）+ 用于外部浏览器的“打开”按钮。
- 天气数据来自 Open-Meteo API。
- 动画天气面板（晴/多云/雨/暴风），背景会根据天气情况动态变化。
- 持久化的待办事项列表，位于 `%LOCALAPPDATA%\\WidgetDockPro\\todo.json` 中。
- 消息面板自动接收来自浏览器标题/通知 API 桥的通知。
- 动态气泡图标显示发件人姓名首字母和通知预览。
- 单实例锁定 + “退出”菜单，方便干净地关闭程序。


## 结构


- `docs/ARCHITECTURE_ID.md`：架构指南和系统逻辑。
- `src/main/scala/widgetdock`：Scala 代码框架。
- `scripts`：引导依赖项、本地安装程序和启动注册。


## 本地运行（安装前测试）


1. 确保 JDK 17+ 可用。
2. 运行：


```powershell
cd C:\Users\asus\public\WidgetDockPro
..\scala-cli.exe run src\main\scala --scala 3.3.1
```


3. 要停止应用程序：
- 右键单击​​气泡图标 -> `退出 WidgetDockPro`，或
- 关闭命令行包含 `WidgetDockPro` 的 `java` 进程。


## 安装捆绑包（自动目录 + 启动）


```powershell
cd C:\Users\asus\public\WidgetDockPro
powershell -ExecutionPolicy Bypass -File .\scripts\Install-WidgetDockPro.ps1
```


安装脚本将：
- 检查/安装运行时环境（JRE/WebView2/Node），
- 在 `%LOCALAPPDATA%\\WidgetDockPro` 目录下创建安装文件夹，
- 注册启动项，
- 在后台运行应用程序。
