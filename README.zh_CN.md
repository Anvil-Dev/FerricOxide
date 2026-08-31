<div align="center">

<img src="src/main/resources/icon.png" width="256" height="256" alt="FerricOxide 图标">

# FerricOxide

**由 Rust 驱动的 Minecraft 原生 WebView UI。**

[English](README.md) | 简体中文

</div>

FerricOxide 通过一个轻薄的 Rust JNI 桥接层调用操作系统原生的 WebView，在 Minecraft
游戏内渲染现代化的 Web UI（HTML/CSS/JS）——不捆绑任何浏览器引擎，不内嵌 Chromium。
它为 Minecraft 模组开发者提供了一个轻量、高性能、跨平台的图形界面基础设施。

## 预览

![预览](docs/img/img.png)

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
- **双向桥接**：事件与请求-响应在两个方向上走同一套 JSON 协议。Java 侧使用
  `WebUi.bridge()`，页面侧使用 `ferric.emit / on / call / handle`——运行时在页面任何脚本
  之前注入，无需判空。详见 [`docs/webui-bridge.md`](docs/webui-bridge.md)。
- **世界内显示**：`web_display` 方块通过离屏 WebView 抓帧把网页渲染到方块表面上，
  支持多方块拼接大屏与准星交互。详见
  [`docs/web-in-world-display.md`](docs/web-in-world-display.md)。

### 技术文档

- [`docs/web-gui-architecture.md`](docs/web-gui-architecture.md)——网页作为游戏内 GUI 的实现思路（分层、线程、嵌入、资源体系）
- [`docs/webui-bridge.md`](docs/webui-bridge.md)——Java ⇄ JS 双向桥接协议与 API
- [`docs/web-in-world-display.md`](docs/web-in-world-display.md)——离屏渲染与世界内网页显示器的实现思路

## 环境要求

- Minecraft / NeoForge 开发环境（具体锁定版本见 `gradle.properties`）
- JDK 25
- Rust 工具链（`cargo`，stable）——本地构建 native 库时需要
- Node.js 22+——`./gradlew check` 运行页面侧桥接测试时需要
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

可复用的 GitHub Actions 构建会并行编译以下七个 native 目标，并打包进同一个模组 JAR：

| 平台            | Rust target                 | JAR 内资源路径                                       |
|-----------------|-----------------------------|------------------------------------------------------|
| Windows x86     | `i686-pc-windows-msvc`      | `natives/windows/x86/ferric_oxide_native.dll`        |
| Windows x86_64  | `x86_64-pc-windows-msvc`    | `natives/windows/x86_64/ferric_oxide_native.dll`     |
| Windows aarch64 | `aarch64-pc-windows-msvc`   | `natives/windows/aarch64/ferric_oxide_native.dll`    |
| Linux x86_64    | `x86_64-unknown-linux-gnu`  | `natives/linux/x86_64/libferric_oxide_native.so`     |
| Linux aarch64   | `aarch64-unknown-linux-gnu` | `natives/linux/aarch64/libferric_oxide_native.so`    |
| macOS x86_64    | `x86_64-apple-darwin`       | `natives/macos/x86_64/libferric_oxide_native.dylib`  |
| macOS aarch64   | `aarch64-apple-darwin`      | `natives/macos/aarch64/libferric_oxide_native.dylib` |

打包任务通过 `-PprebuiltNativesDir=/path/to/natives` 传入合并后的资源目录。此模式下
Gradle 只复制预编译的 native 文件，不会运行 Cargo；不传该属性时，本地构建与开发运行的
行为保持不变。

## CI 版本与发布

基准版本来自 `gradle.properties` 的 `mod_version`。CI 将版本作为 Gradle 覆盖参数传入，
因此 JAR 文件名和其中的 NeoForge 元数据始终使用同一个版本：

- `releases/**` 上改变构建输入的 push 会发布 **alpha** 版本到 Modrinth 和 CurseForge，版本号为
  `<mod_version>+build.<GitHub run number>`，例如 `0.0.1+build.123`。
- 推送 `v<mod_version>` 标签（例如 `v0.0.1`）时，工作流会先确认它与 `gradle.properties` 完全一致，
  再发布稳定版到 Modrinth、CurseForge 和 GitHub。
- Pull Request 仅运行七目标构建和测试，不会取得发布权限。

发布需要仓库 Secrets `MODRINTH_TOKEN` 与 `CURSEFORGE_TOKEN`。任何平台拒绝上传都会让工作流
明确失败，不会静默跳过发布。

x86 动态库仅为完整性而构建和打包，但目前的 Minecraft、JDK 25 和 LWJGL 发行版通常不提供
完整的 32 位运行时。加载 x86 native 仍需要 x86 JVM 以及架构兼容的游戏环境。Linux x86
目标已整体移除：Ubuntu 24.04 不再提供可安装的 i386 WebKitGTK 开发链，且不存在能运行
游戏的 32 位 JDK 25。打包 Linux 动态库也不会捆绑其 WebKitGTK、GTK 或 D-Bus 系统依赖。

## 使用 API

Java 侧——负载就是普通 record，由 Gson 转换：

```java
import dev.anvilcraft.oxide.ferric.webui.WebUi;

record Clicked(int value) {}
record Data(int foo) {}
record PlayerInfo(String name, float health) {}

// 从模组资源加载页面，并嵌入到 Minecraft 窗口中：
WebUi ui = WebUi.embedded(
    "My Mod UI", "my_mod", "webui/index.html",
    minecraft.getWindow().getWidth(),
    minecraft.getWindow().getHeight()
);

ui.bridge()
    // JS -> Java 事件（处理器一律在渲染线程上运行）：
    .on("my_mod.clicked", Clicked.class, clicked -> doSomething(clicked.value()))
    // JS -> Java 请求——返回值会作为响应回传给页面：
    .handle("my_mod.player", Void.class, ignored -> new PlayerInfo("Steve", 20.0F));

// Java -> JS 事件：
ui.bridge().emit("my_mod.data", new Data(42));

// Java -> JS 请求：
ui.bridge().call("my_mod.form", null, FormValues.class).thenAccept(this::save);

// WebUi 实现了 AutoCloseable；close() 会销毁原生窗口（GC 也会回收）。
ui.close();
```

页面侧——完全对称，无需任何准备工作：

```js
ferric.emit('my_mod.clicked', {value: 42});
const player = await ferric.call('my_mod.player');

ferric.on('my_mod.data', (data) => render(data.foo));
ferric.handle('my_mod.form', () => collectFormValues());

// 引用游戏资源，无需关心平台的 URL 协议差异：
img.src = ferric.resource('item/minecraft:apple', {size: 48});
```

如需打开独立的（非嵌入）窗口，改用 `WebUi.window(...)`。更底层的原始 API
（`NativeWebView.Builder`、`MessageHandler`、`CreationCallback`）位于
`dev.anvilcraft.oxide.ferric.webui` 包下，供高级场景使用。

## 演示

在游戏内运行 `/ferric ui demo` 即可打开内置的演示 UI，它覆盖了桥接的每个方向：向游戏
聊天栏发送 ping（JS 事件）、点击 **Who am I?** 查询玩家名与血量（JS 请求）、每秒接收一次
世界时间以及实体预览帧（Java 事件）。在 WebView 中按 **Esc** 可关闭它并把鼠标控制权交还
给 Minecraft。

设置 `FERRICOXIDE_AUTO_OPEN=1` 可在启动后自动打开演示 UI（用于冒烟测试）。

## 项目结构

```
rust/                          Native JNI 桥接层（wry + tao + jni）
  src/lib.rs                   JNI 入口、事件循环线程、嵌入/独立 WebView
  src/offscreen.rs             三平台离屏渲染 + CDP 输入翻译层
src/main/java/dev/anvilcraft/oxide/ferric/
  FerricOxide.java            模组入口
  client/FerricOxideClient.java  客户端命令 + 演示 UI 接线
  webui/WebUi.java            公共高层 API
  webui/NativeWebView.java    底层 JNI 封装
  webui/NativeLoader.java     动态库加载（系统属性 / JAR 解压）
  webui/OffscreenWebView.java 离屏 WebView JNI 封装（取帧 + CDP 输入）
  webui/bridge/               双向事件/请求桥接（WebBridge + 协议实现）
  display/                    网页显示器方块、方块实体、组合算法、注册
  client/display/             显示管理器、渲染器、动态纹理、捕获/编辑屏
  network/                    SetWebDisplayUrlPayload 网络包
  data/                       数据生成（模型、语言、掉落表）
src/main/resources/assets/ferric_oxide/webui/
  bridge.js                    页面侧桥接运行时（先于页面脚本注入）
  demo.html                    演示页面
src/test/java/                 Java 侧桥接与组合算法的 JUnit 测试
src/test/js/bridge.test.js     页面侧桥接的 Node 测试
docs/web-gui-architecture.md   网页作为 GUI 的实现思路
docs/webui-bridge.md           桥接协议与 API 设计
docs/web-in-world-display.md   世界内网页显示的实现思路
```

## 许可证

GNU LGPL 3.0
