# WebUI 双端桥接设计（Bridge）

本文定义 FerricOxide 中 Java 与页面 JS 之间的通信协议与 API。取代原有的
`WebUiMessage` + `WebUi.eval(String)` 方案，两个方向对称，均支持事件（单向）与请求-响应（带返回值）。

## 1. 为什么重做

原方案的问题：

| 方向 | 问题 |
| --- | --- |
| JS → Java | 页面手写 `window.ipc.postMessage(JSON.stringify({type: ...}))`，每处都要防御性判断 `window.ipc` 是否存在 |
| JS → Java | Java 侧收到 `WebUiMessage`，取值一律带 fallback（`integer(k, -1)`），字段拼错不会报错，只会静默拿到默认值 |
| JS → Java | 没有返回值。页面想向 Java 要一份数据，只能绕道 `ferric://` 资源协议伪装成图片请求 |
| Java → JS | 只有 `eval(String js)`，靠字符串拼接 JS 源码，无转义、无类型 |
| Java → JS | 页面必须把回调挂成 `window.ferricOxide.onXxx` 全局函数，Java 侧还要写 `window.ferricOxide && window.ferricOxide.onXxx && ...` 防御 |
| Java → JS | 同样没有返回值，Java 无法向页面取值 |
| 线程 | `MessageHandler` 在原生 UI 线程回调，是否切回渲染线程由调用方各自决定，实际代码出现了重复切换 |

## 2. 线路协议

两个方向共用同一个信封结构，一套解析逻辑。全部为 JSON 对象：

```jsonc
{"k": "e", "n": "game.time", "p": {"ticks": 1200}}   // event：单向事件
{"k": "q", "i": 17, "n": "item.icon", "p": {...}}    // query：请求，i 为调用 id
{"k": "r", "i": 17, "p": {...}}                      // reply：成功响应
{"k": "r", "i": 17, "e": "no handler: item.icon"}    // reply：失败响应
```

- `k`：信封种类，`e` / `q` / `r` 三选一。
- `n`：通道名，事件与请求共用命名空间，建议 `<模组域>.<动作>` 形式。
- `i`：调用 id，仅 `q` / `r` 使用。发起方各自维护自增序列，两个方向互不干扰。
- `p`：业务负载，任意 JSON 值，可省略（视为 `null`）。
- `e`：错误描述，仅失败响应携带；与 `p` 互斥。

传输通道：

- JS → Java 走既有的 `window.ipc.postMessage(json)`。
- Java → JS 不再拼接任意 JS 源码，固定执行 `window.__ferric.accept(<json>)`。
  Gson 默认转义 `<`、`>`、`&`、`'`、`=` 与 U+2028/U+2029，其输出可安全作为 JS 表达式字面量嵌入。

## 3. JS 侧 API

运行时由 Rust 在建立 WebView 时通过初始化脚本注入（`with_initialization_script`），
先于页面任何脚本执行，因此页面无需判空、无需等待、`loadUrl` 加载的远程页面同样可用。

```js
// —— JS 发起 ——
ferric.emit(name, payload)              // 单向事件，无返回
await ferric.call(name, payload)        // 请求，返回 Promise；Java 抛异常时 reject

// —— JS 接收 ——
const off = ferric.on(name, handler)    // 订阅 Java 事件，可注册多个，返回取消函数
ferric.handle(name, handler)            // 应答 Java 请求，handler 可返回值或 Promise

// —— 资源 ——
ferric.resource(path, query)            // 拼出资源 URL，屏蔽平台协议差异
```

`ferric.resource` 取代原先由 Java 注入的 `window.ferricOxideResourceBase` 字符串拼接：

```js
ferric.resource('minecraft/textures/item/apple.png')  // 资源包文件
ferric.resource('item/minecraft:dragon_head', {size: 48})
ferric.resource('entity/minecraft:zombie', {size: 128})
```

约束：

- `ferric.handle` 同名重复注册直接抛错，避免静默覆盖。
- 请求发到没有 handler 的通道时，回复 `no handler: <name>` 错误响应，调用方 Promise reject。
- handler 内部抛出的异常转成错误响应回传，同时在页面控制台打印。

## 4. Java 侧 API

面向接口：`WebBridge` 为接口，`WebBridgeImpl` 为实现，`WebUi` 只负责窗口生命周期并暴露 `bridge()`。

```java
public interface WebBridge {
    // —— Java 发起 ——
    void emit(String name, @Nullable Object payload);
    <R> CompletableFuture<R> call(String name, @Nullable Object payload, Class<R> resultType);

    // —— Java 接收 ——
    <T> WebBridge on(String name, Class<T> payloadType, Consumer<T> handler);
    WebBridge on(String name, Runnable handler);
    <T, R> WebBridge handle(String name, Class<T> payloadType, Function<T, R> handler);
    <T, R> WebBridge handleAsync(String name, Class<T> payloadType, Function<T, CompletableFuture<R>> handler);
}
```

负载编解码统一走 Gson：出站对象直接序列化，入站按 `payloadType` 反序列化。业务侧用
record 描述负载，字段拼错在反序列化阶段就是 `null`／类型错误，不再是静默 fallback：

```java
record Rotate(float yaw, float pitch) {}
record GameTime(long ticks) {}

bridge.on("demo.rotate", Rotate.class, r -> EntityPreviewRenderer.updateRotation(r.yaw(), r.pitch()))
      .on("demo.close", DemoWebUi::close)
      .handle("demo.icon", IconRequest.class, DemoWebUi::renderIcon);

bridge.emit("demo.game_time", new GameTime(level.getGameTime()));
bridge.call("demo.form_values", null, FormValues.class).thenAccept(...);
```

## 5. 线程约定

**所有 Java 侧 handler 一律在渲染线程执行**，由桥接内部统一切换，业务代码不再自行
`WebUi.onRenderThread(...)`。`emit` / `call` 可从任意线程调用。

`handleAsync` 的 handler 在渲染线程被调用，其返回的 `CompletableFuture` 可在任意线程完成，
响应回传由桥接负责。

## 6. 生命周期与错误处理

- `WebUi.close()` 时，所有未完成的 Java → JS 调用以 `IllegalStateException` 异常完成，不留悬挂 Future。
- 收到无法解析的信封、未知 `k`、未知通道名、handler 抛出的异常：一律 `LOGGER.warn` / `LOGGER.error`
  输出，请求类的额外回传错误响应。不存在静默吞掉的路径。
- 页面在桥接就绪前调用 `ferric.*` 不会失败：初始化脚本先于页面脚本注入，`ferric` 必然存在。

## 7. 影响范围

| 文件 | 变更 |
| --- | --- |
| `rust/src/lib.rs` | `WebViewSpec` 增加 `init_script`；`nativeCreate` 增加对应参数；两处 `create_entry` 注入 |
| `webui/NativeWebView.java` | `Builder.initScript(String)`，JNI 签名同步 |
| `webui/bridge/WebBridge.java` | 新增接口 |
| `webui/bridge/WebBridgeImpl.java` | 新增实现：信封编解码、通道注册表、pending 调用表 |
| `webui/WebUi.java` | 去掉 `on(String, Consumer)` 与 `eval`，改为暴露 `bridge()`；注入桥接初始化脚本 |
| `webui/WebUiMessage.java` | 删除 |
| `resources/.../webui/bridge.js` | 新增 JS 运行时，打包进 jar，由 Java 读出后作为初始化脚本传入 |
| `resources/.../webui/demo.html` | 改用 `ferric.*` |
| `client/FerricOxideClient.java` | 改用 `bridge()`，删除重复的渲染线程切换 |
| `src/test/js/bridge.test.js` | 页面侧运行时测试（Node test runner） |
| `build.gradle` | 新增 `testJs` 任务并挂到 `check` 上 |

## 8. 测试

两侧各自独立可测，均不需要启动游戏或原生 WebView：

- **Java 侧**：`WebBridgeImpl` 的出站求值器（`Consumer<String>`）与调度器（`Executor`）都是注入的，
  测试用列表捕获出站 JS、用 `Runnable::run` 同步执行调度，从而驱动完整协议。
- **页面侧**：把 `bridge.js` 载入独立的 VM context，伪造 `window.ipc` 捕获出站 JSON，
  通过 `window.__ferric.accept` 灌入入站信封。

`./gradlew check` 会同时跑两侧（JS 侧需要 Node.js 22+）。

其中 `__RESOURCE_BASE__` 占位符要求**在 `bridge.js` 中恰好出现一次**：若出现两次，
按首次匹配替换的实现会替换错位置。`initScript` 对此直接报错，两侧测试各有用例锁定。
