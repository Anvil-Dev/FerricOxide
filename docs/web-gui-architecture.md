# 网页作为 GUI：实现思路

本文描述 FerricOxide 把网页（HTML/CSS/JS）作为 Minecraft 游戏内 GUI 的实现方式：
不捆绑任何浏览器引擎，通过一层轻薄的 Rust JNI 桥调用操作系统原生 WebView。
双向桥接协议本身的定义见 [webui-bridge.md](webui-bridge.md)，本文聚焦架构与工程决策。

## 1. 分层架构

```
┌─────────────────────────── Minecraft (Java) ───────────────────────────┐
│  WebUi（公共 API）──► NativeWebView（JNI 存根）                          │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │ JNI 调用（即发即弃命令 + 创建回调）
┌─────────────────────────── Rust (cdylib: ferric_oxide_native) ─────────┐
│  jni + wry + tao：命令通道（Cmd）驱动 WebView 生命周期                   │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │ 操作系统原生 WebView
        Windows: WebView2 (Chromium) │ macOS: WKWebView    Linux: WebKitGTK
```

- **Java 公共 API（`WebUi`）**：模组开发者只看到 `WebUi.embedded(...)` / `WebUi.window(...)`
  与 `bridge()`。负载是普通 record，Gson 负责序列化。
- **JNI 存根（`NativeWebView`）**：每个原生方法一一对应 Rust 侧导出函数
  （`nativeCreate/nativeEval/nativeLoadUrl/nativeSetVisible/nativeSetBounds/nativeClose/...`），
  不承载任何逻辑。
- **Rust 桥（`rust/src/lib.rs`）**：`wry` 驱动各平台原生 WebView，`tao` 提供窗口与事件循环。
  所有 JNI 调用被翻译成 `Cmd` 枚举消息投递到拥有 WebView 的线程，游戏线程从不等待
  原生侧——唯一的例外是创建，其结果通过 `CreationCallback` 异步通知 Java。

## 2. 线程模型

各平台的窗口系统约束不同，Rust 侧因此有两种调度形态：

- **Windows / Linux**：一个专用的 `ferric-oxide-webview` 线程运行 `tao` 事件循环，
  JNI 线程通过 `EventLoopProxy<Cmd>` 发消息。该线程同时持有 GTK/glib 主上下文（Linux），
  WebKitGTK 的异步回调也在此处执行。
- **macOS**：AppKit/WebKit 只允许主线程操作，而主线程归 Minecraft（GLFW）所有，不能跑
  自己的事件循环。因此 macOS 上 `send_cmd` 改为经 `dispatch2` 向主调度队列
  `DispatchQueue::main().exec_async(...)` 派发，WebView 存放在 thread_local 的
  `MAC_ENTRIES` 中；处理器用 `catch_unwind` 包裹，panic 只记日志、绝不拖垮游戏主线程。

Java 侧的方向相反：JS → Java 的桥接回调从原生 UI 线程进来后，统一切到渲染线程再调
用户处理器（见 webui-bridge.md 第 5 节），因此模组代码里不需要再做线程切换。

## 3. 嵌入方式

`WebUi.embedded(...)` 与 `WebUi.window(...)` 的区别只在原生侧的宿主：

- **Windows 嵌入**：WebView2 以 Minecraft 窗口的 HWND 子窗口创建
  （`WebViewBuilder::build_as_child(&ParentHwnd(hwnd))`），位置尺寸以 GLFW 客户区的
  物理像素为准（`nativeSetBounds`），跟随游戏窗口，避免 wry 重复套用显示器缩放。
- **macOS 嵌入**：wry 的 `build_as_child` 接收 `ParentNsView`（对 GLFW 窗口主 NSView
  指针的 `HasWindowHandle` 包装），WKWebView 作为子视图加入。
- **Linux**：回退为独立窗口（GTK 下没有等价的子窗口嵌入路径）。
- **独立窗口**：`WebUi.window(...)` 创建装饰化 tao 窗口，位置尺寸用逻辑像素。

嵌入式 UI 覆盖在游戏画面之上但不属于 Minecraft 的 Screen 体系，因此需要自己管理
输入边界：WebView 内按 Esc 关闭 UI 并把鼠标控制权交还游戏；Windows 上销毁子窗口前
用 `AttachThreadInput` 做焦点恢复，防止 WebView2 在拆解时抢走焦点。

## 4. 资源体系

页面资源不依赖 `file://`（各平台对本地文件的策略差异巨大），而是注册自定义协议
`ferric://<namespace>/<path>`：

- Rust 在建 WebView 时注册协议处理器（`register_resource_protocol`）；请求到达后通过
  JNI 回调 Java 的 `ResourceHandler`，Java 从模组 assets / 资源包读取字节，再经
  `nativeResourceResponse` 回传。整条链异步，不阻塞任一 UI 线程。
- 模组自己的页面用 `webui/` 相对根路径加载（`WebUi` 自动拼成 `ferric://` URL），
  页面里引用游戏资源用 `ferric.resource('item/minecraft:apple', {size: 48})`。
- **`ferric://item` 协议**：`ItemIconRenderer` / `EntityPreviewRenderer` 把物品图标与
  实体预览用 Minecraft 自身的渲染管线绘制成 PNG，经同一协议回给页面——网页里可以
  直接 `<img>` 显示游戏内容。

## 5. 双向桥接

事件与请求-响应在两个方向上共用一套 JSON 信封（`{"k":"e|q|r", ...}`），JS 侧运行时
（`ferric.emit / on / call / handle`）由 Rust 以初始化脚本注入，先于页面任何脚本执行，
页面无需判空。完整协议与 API 见 [webui-bridge.md](webui-bridge.md)。

## 6. 生命周期与回收

`WebUi` 实现 `AutoCloseable`：`close()` 销毁原生窗口/WebView；Java 对象同时挂了
Cleaner，忘记 close 时由 GC 兜底回收原生句柄。所有销毁命令同样走 `Cmd` 通道，
保证在与创建相同的线程上执行。

## 7. 与世界内显示的关系

本管线渲染的是**覆盖层 GUI**：WebView 直接贴在游戏窗口上，与 3D 世界无遮挡关系。
需要把网页贴到世界内方块表面（有深度遮挡、可多方块拼接、可准星交互）时，走另一条
离屏渲染管线，见 [web-in-world-display.md](web-in-world-display.md)。
