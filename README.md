<div align="center">

<img src="src/main/resources/icon.png" width="256" height="256" alt="FerricOxide icon">

# FerricOxide

**Native WebView UI for Minecraft, driven by Rust.**

English | [简体中文](README.zh_CN.md)

</div>

FerricOxide renders modern web UI (HTML/CSS/JS) inside Minecraft by calling the operating
system's native WebView through a thin Rust JNI bridge — no bundled browser engines, no
embedded Chromium. It is a lightweight, high-performance, cross-platform GUI foundation for
Minecraft mod developers.

## How it works

```
┌─────────────────────────── Minecraft (Java) ───────────────────────────┐
│  WebUi (public API) ──► NativeWebView (JNI stubs)                     │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │ JNI calls (fire-and-forget commands)
┌─────────────────────────── Rust (cdylib) ─────────────────────────────┐
│  ferric_oxide_native: jni + wry + tao                                  │
│  dedicated event-loop thread owns every WebView/window                 │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │ OS native WebView
        Windows: WebView2 (Chromium) │ macOS: WKWebView    Linux: WebKitGTK
```

- **Java** exposes a small, mod-developer-friendly API (`WebUi`), backed by thin JNI stubs
  (`NativeWebView`).
- **Rust** (`rust/`, crate `ferric_oxide_native`) uses [`wry`](https://crates.io/crates/wry)
  to drive the platform's native WebView. All window/WebView work happens on one dedicated
  event-loop thread; JNI calls become messages sent through an `EventLoopProxy`, so no game
  thread is ever blocked.
- **Embedding**: on Windows the WebView is created as an HWND child of the Minecraft window
  (`WebViewBuilder::build_as_child`), so the web UI is pinned to the game window and follows
  its size. Other platforms currently fall back to a standalone window.
- **Two-way messaging**: pages call `window.ipc.postMessage(JSON.stringify(...))`; Java
  receives typed messages via `WebUi.on("my.type", handler)`. Java pushes to the page with
  `WebUi.eval(...)`.

## Requirements

- Minecraft / NeoForge dev environment (see `gradle.properties` for the pinned versions)
- JDK 25
- Rust toolchain (`cargo`, stable) — needed to build the native library locally
- Windows: WebView2 Runtime (preinstalled on Windows 11 / modern Edge)
- Linux: WebKitGTK 4.1 and D-Bus runtime libraries (for example,
  `libwebkit2gtk-4.1-0` and `libdbus-1-3` on Ubuntu)

## Building

```bash
./gradlew build
```

A normal local build invokes `cargo build --release` via the `buildRustNative` task, copies the
host library into the jar at `natives/<platform>/<arch>/...`, and configures dev runs with the
`ferricoxide.native.path` system property. The `NativeLoader` first honours that property and
otherwise extracts the matching library from the mod jar at runtime.

The reusable GitHub Actions build compiles these eight native targets in parallel and packages
them into one mod JAR:

| Platform        | Rust target                 | JAR resource                                         |
|-----------------|-----------------------------|------------------------------------------------------|
| Windows x86     | `i686-pc-windows-msvc`      | `natives/windows/x86/ferric_oxide_native.dll`        |
| Windows x86_64  | `x86_64-pc-windows-msvc`    | `natives/windows/x86_64/ferric_oxide_native.dll`     |
| Windows aarch64 | `aarch64-pc-windows-msvc`   | `natives/windows/aarch64/ferric_oxide_native.dll`    |
| Linux x86       | `i686-unknown-linux-gnu`    | `natives/linux/x86/libferric_oxide_native.so`        |
| Linux x86_64    | `x86_64-unknown-linux-gnu`  | `natives/linux/x86_64/libferric_oxide_native.so`     |
| Linux aarch64   | `aarch64-unknown-linux-gnu` | `natives/linux/aarch64/libferric_oxide_native.so`    |
| macOS x86_64    | `x86_64-apple-darwin`       | `natives/macos/x86_64/libferric_oxide_native.dylib`  |
| macOS aarch64   | `aarch64-apple-darwin`      | `natives/macos/aarch64/libferric_oxide_native.dylib` |

The packaging job supplies the merged resource directory with
`-PprebuiltNativesDir=/path/to/natives`. In this mode Gradle copies every prebuilt native and
does not run Cargo; without the property, local and development-run behavior remains unchanged.

The x86 libraries are built and packaged for completeness, but current Minecraft, JDK 25, and
LWJGL distributions generally do not provide a complete 32-bit runtime. Loading an x86 native
still requires an x86 JVM and an otherwise architecture-compatible game environment. Packaging
the Linux libraries also does not bundle their WebKitGTK, GTK, or D-Bus system dependencies.

## Using the API

```java
import dev.anvilcraft.oxide.ferric.webui.WebUi;
import dev.anvilcraft.oxide.ferric.webui.WebUiMessage;

// Load your page from the mod's assets, embed it into the Minecraft window:
WebUi ui = WebUi.embedded(
    "My Mod UI",
    WebUi.readModAsset("my_mod", "webui/index.html"),
    minecraft.getWindow().getWidth(),
    minecraft.getWindow().getHeight()
);

// Handle JS -> Java messages:
ui.on("my_mod.button_clicked", msg -> {
    int value = msg.integer("value", 0);
    // ... touch the game (this handler already runs on the render thread)
});

// Push Java -> JS (page exposes e.g. window.myMod.onData(message)):
ui.eval("window.myMod && window.myMod.onData && window.myMod.onData("
    + WebUiMessage.create("my_mod.data").put("foo", 42).toJson() + ");");

// WebUi is AutoCloseable; close() destroys the native window (also reclaimed by GC).
ui.close();
```

Open a standalone (non-embedded) window with `WebUi.window(...)` instead. The raw lower-level
API (`NativeWebView.Builder`, `MessageHandler`, `CreationCallback`) is available under
`dev.anvilcraft.oxide.ferric.webui` for advanced use.

## Demo

Run `/ferric ui demo` in-game to open the bundled demo UI: a page that pings the game chat and
receives the current world time pushed from Java once per second. Press **Esc** in the WebView
to close it and return mouse control to Minecraft.

Set `FERRICOXIDE_AUTO_OPEN=1` to open the demo UI automatically after launch (used for
smoke-testing).

## Project layout

```
rust/                          Native JNI bridge (wry + tao + jni)
  src/lib.rs                   JNI entry points, event-loop thread, embedded/standalone WebViews
src/main/java/dev/anvilcraft/oxide/ferric/
  FerricOxide.java            Mod entry point
  client/FerricOxideClient.java  Client command + demo UI wiring
  webui/WebUi.java            Public high-level API
  webui/NativeWebView.java    Low-level JNI wrapper
  webui/NativeLoader.java     Library loading (system property / jar extraction)
  webui/WebUiMessage.java     Typed message helpers for the IPC channel
src/main/resources/assets/ferric_oxide/webui/demo.html
```

## License

GNU LGPL 3.0
