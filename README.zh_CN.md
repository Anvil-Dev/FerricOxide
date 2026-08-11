<div align="center">

<img src="src/main/resources/icon.png" width="256" height="256" alt="FerricOxide 图标">

# FerricOxide

**由 Rust 驱动的 Minecraft 原生 WebView UI。**

[English](README.md) | 简体中文

</div>

FerricOxide 通过一个轻薄的 Rust JNI 桥接层调用操作系统原生的 WebView，在 Minecraft
游戏内渲染现代化的 Web UI（HTML/CSS/JS）——不捆绑任何浏览器引擎，不内嵌 Chromium。
它为 Minecraft 模组开发者提供了一个轻量、高性能、跨平台的图形界面基础设施。

## 工作原理

```
┌─────────────────────────── Minecraft (Java) ───────────────────────────┐
│  WebUi (公共 API) ──► NativeWebView (JNI 存根)                          │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │ JNI 调用（即发即弃的命令）
┌─────────────────────────── Rust (cdylib) ─────────────────────────────┐
│  ferric_oxide_native: jni + wry + tao                                  │
│  专用事件循环线程持有所有 WebView/窗口                                    │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │ 操作系统原生 WebView
        Windows: WebView2 (Chromium) │ macOS: WKWebView    Linux: WebKitGTK
```

- **Java** 层暴露了一个对模组开发者友好的小型 API（`WebUi`），底层由轻薄的 JNI 存根
  （`NativeWebView`）支撑。
- **Rust** 层（`rust/`，crate 名 `ferric_oxide_native`）使用
  [`wry`](https://crates.io/crates/wry) 驱动各平台的原生 WebView。所有窗口/WebView
  操作都在一个专用的事件循环线程上完成；JNI 调用会通过 `EventLoopProxy` 转成消息发送，
  因此游戏线程永远不会被阻塞。
- **嵌入方式**：在 Windows 上，WebView 会作为 Minecraft 窗口的 HWND 子窗口创建
  （`WebViewBuilder::build_as_child`），因此 Web UI 会贴合游戏窗口并跟随其尺寸变化。
  其他平台目前回退为独立窗口。
- **双向通信**：页面通过 `window.ipc.postMessage(JSON.stringify(...))` 发送消息；Java
  通过 `WebUi.on("my.type", handler)` 接收带类型的消息。Java 通过 `WebUi.eval(...)`
  向页面推送数据。

## 环境要求

- Minecraft / NeoForge 开发环境（具体锁定版本见 `gradle.properties`）
- JDK 25
- Rust 工具链（`cargo`，stable）——本地构建 native 库时需要
- Windows：WebView2 Runtime（Windows 11 / 新版 Edge 已预装）
- Linux：WebKitGTK 4.1 与 D-Bus 运行时库（例如 Ubuntu 上的
  `libwebkit2gtk-4.1-0` 和 `libdbus-1-3`）

## 构建

```bash
./gradlew build
```

普通的本地构建会通过 `buildRustNative` 任务调用 `cargo build --release`，把宿主机平台的
动态库复制到 JAR 内的 `natives/<platform>/<arch>/...` 路径，并为开发运行配置
`ferricoxide.native.path` 系统属性。`NativeLoader` 会优先使用该属性指定的路径，否则在
运行时从模组 JAR 中解压匹配平台的动态库。

可复用的 GitHub Actions 构建会并行编译以下八个 native 目标，并打包进同一个模组 JAR：

| 平台            | Rust target                 | JAR 内资源路径                                       |
|-----------------|-----------------------------|------------------------------------------------------|
| Windows x86     | `i686-pc-windows-msvc`      | `natives/windows/x86/ferric_oxide_native.dll`        |
| Windows x86_64  | `x86_64-pc-windows-msvc`    | `natives/windows/x86_64/ferric_oxide_native.dll`     |
| Windows aarch64 | `aarch64-pc-windows-msvc`   | `natives/windows/aarch64/ferric_oxide_native.dll`    |
| Linux x86       | `i686-unknown-linux-gnu`    | `natives/linux/x86/libferric_oxide_native.so`        |
| Linux x86_64    | `x86_64-unknown-linux-gnu`  | `natives/linux/x86_64/libferric_oxide_native.so`     |
| Linux aarch64   | `aarch64-unknown-linux-gnu` | `natives/linux/aarch64/libferric_oxide_native.so`    |
| macOS x86_64    | `x86_64-apple-darwin`       | `natives/macos/x86_64/libferric_oxide_native.dylib`  |
| macOS aarch64   | `aarch64-apple-darwin`      | `natives/macos/aarch64/libferric_oxide_native.dylib` |

打包任务通过 `-PprebuiltNativesDir=/path/to/natives` 传入合并后的资源目录。此模式下
Gradle 只复制预编译的 native 文件，不会运行 Cargo；不传该属性时，本地构建与开发运行的
行为保持不变。

x86 动态库仅为完整性而构建和打包，但目前的 Minecraft、JDK 25 和 LWJGL 发行版通常不提供
完整的 32 位运行时。加载 x86 native 仍需要 x86 JVM 以及架构兼容的游戏环境。打包 Linux
动态库也不会捆绑其 WebKitGTK、GTK 或 D-Bus 系统依赖。

## 使用 API

```java
import dev.anvilcraft.oxide.ferric.webui.WebUi;
import dev.anvilcraft.oxide.ferric.webui.WebUiMessage;

// 从模组资源加载页面，并嵌入到 Minecraft 窗口中：
WebUi ui = WebUi.embedded(
    "My Mod UI",
    WebUi.readModAsset("my_mod", "webui/index.html"),
    minecraft.getWindow().getWidth(),
    minecraft.getWindow().getHeight()
);

// 处理 JS -> Java 消息：
ui.on("my_mod.button_clicked", msg -> {
    int value = msg.integer("value", 0);
    // ... 操作游戏内容（该处理器已在渲染线程上运行）
});

// 推送 Java -> JS（页面需暴露例如 window.myMod.onData(message)）：
ui.eval("window.myMod && window.myMod.onData && window.myMod.onData("
    + WebUiMessage.create("my_mod.data").put("foo", 42).toJson() + ");");

// WebUi 实现了 AutoCloseable；close() 会销毁原生窗口（GC 也会回收）。
ui.close();
```

如需打开独立的（非嵌入）窗口，改用 `WebUi.window(...)`。更底层的原始 API
（`NativeWebView.Builder`、`MessageHandler`、`CreationCallback`）位于
`dev.anvilcraft.oxide.ferric.webui` 包下，供高级场景使用。

## 演示

在游戏内运行 `/ferric ui demo` 即可打开内置的演示 UI：一个可以向游戏聊天栏发送 ping、
并每秒接收一次 Java 推送的当前世界时间的页面。在 WebView 中按 **Esc** 可关闭它并把鼠标
控制权交还给 Minecraft。

设置 `FERRICOXIDE_AUTO_OPEN=1` 可在启动后自动打开演示 UI（用于冒烟测试）。

## 项目结构

```
rust/                          Native JNI 桥接层（wry + tao + jni）
  src/lib.rs                   JNI 入口、事件循环线程、嵌入/独立 WebView
src/main/java/dev/anvilcraft/oxide/ferric/
  FerricOxide.java            模组入口
  client/FerricOxideClient.java  客户端命令 + 演示 UI 接线
  webui/WebUi.java            公共高层 API
  webui/NativeWebView.java    底层 JNI 封装
  webui/NativeLoader.java     动态库加载（系统属性 / JAR 解压）
  webui/WebUiMessage.java     IPC 通道的带类型消息辅助类
src/main/resources/assets/ferric_oxide/webui/demo.html
```

## 许可证

GNU LGPL 3.0
