# FerricOxide

Native WebView UI for Minecraft, driven by Rust.

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
- Rust toolchain (`cargo`, stable) — needed to build the native library
- Windows: WebView2 Runtime (preinstalled on Windows 11 / modern Edge)

## Building

```bash
./gradlew build
```

Gradle invokes `cargo build --release` via the `buildRustNative` task, copies the library
into the jar (`natives/<platform>/<arch>/...`), and configures dev runs with the
`ferricoxide.native.path` system property. The `NativeLoader` first honours that property and
otherwise extracts the library from the mod jar at runtime.

## Using the API

```java
import dev.anvilcraft.oxide.ferric.webui.WebUi;
import dev.anvilcraft.oxide.ferric.webui.WebUiMessage;

// Load your page from the mod's assets, embed it into the Minecraft window:
WebUi ui = WebUi.embedded(
    "My Mod UI",
    WebUi.readModAsset("my_mod", "webui/index.html"),
    minecraft.getWindow().getWidth(),
    minecraft.getWindow().getHeight());

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

Press **F6** in-game to toggle the bundled demo UI: a page that pings the game chat and
receives the current world time pushed from Java once per second.

Set `FERRICOXIDE_AUTO_OPEN=1` to open the demo UI automatically after launch (used for
smoke-testing).

## Project layout

```
rust/                          Native JNI bridge (wry + tao + jni)
  src/lib.rs                   JNI entry points, event-loop thread, embedded/standalone WebViews
src/main/java/dev/anvilcraft/oxide/ferric/
  FerricOxide.java            Mod entry point
  client/FerricOxideClient.java  Keybind + demo UI wiring
  webui/WebUi.java            Public high-level API
  webui/NativeWebView.java    Low-level JNI wrapper
  webui/NativeLoader.java     Library loading (system property / jar extraction)
  webui/WebUiMessage.java     Typed message helpers for the IPC channel
src/main/resources/assets/ferric_oxide/webui/demo.html
```

## License

GNU LGPL 3.0
