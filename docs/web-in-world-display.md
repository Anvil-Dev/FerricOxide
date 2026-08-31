# 网页世界内显示：实现思路

本文描述"网页显示器"方块（`web_display`）的实现方式：把离屏渲染的网页作为动态纹理
贴合到世界内的方块表面上——有深度遮挡、可多方块拼接成大屏幕、可用准星交互。
与覆盖层 GUI（见 [web-gui-architecture.md](web-gui-architecture.md)）是两条独立的管线。

## 1. 方块模型与多方块组合

- `WebDisplayBlock` 是 `HorizontalDirectionalBlock`，放置时正面朝向玩家；非正面各面
  使用白色混凝土纹理。方块实体 `WebDisplayBlockEntity` 持久化页面地址（默认
  `https://space.bilibili.com/430207683`），并通过 `getUpdateTag` / `getUpdatePacket`
  同步到客户端。
- **组合规则**：相邻且朝向相同的显示器泛洪成连通组件后，做**确定性贪心最大矩形
  划分**（`WebDisplayGroup.compute`）：反复取出剩余区域中面积最大的矩形
  （宽 ≤ 16、高 ≤ 9），直到耗尽。划分只取决于组件形状，与触发方块无关，因此所有成员
  得到一致结果——L 形区域会连出其中的最大矩形，突出的格子归入各自的组，而不是像
  早期版本那样整体失效。
- **锚点权威**：每组的锚点取观察者视角的最右下（右轴 = `facing.getCounterClockWise()`）。
  锚点方块实体是地址的唯一权威；服务端在放置/拆除/编辑后把锚点地址广播到全部成员
  （`WebDisplayGroups`），客户端以锚点为准。
- **编辑地址**：Shift+右键打开编辑屏（`WebDisplayEditScreen`），提交
  `SetWebDisplayUrlPayload` 网络包；服务端校验方块类型、64 格距离与长度上限后重算
  锚点并写回。

## 2. 离屏渲染管线（三平台）

Java 侧统一是 `OffscreenWebView`（JNI 封装：创建/取帧/缩放/CDP 输入/关闭，Cleaner 兜底
回收）；Rust 侧按平台分三种实现，帧统一为 8 字节小端世代号 + 紧凑 RGBA：

### Windows：WebView2 + Windows.Graphics.Capture

隐藏宿主窗口 → WebView2 **合成控制器**（`CreateCoreWebView2CompositionController`）
→ WinRT `Compositor` 可视化树 → `GraphicsCaptureItem.CreateFromVisual` +
`Direct3D11CaptureFramePool.CreateFreeThreaded`（B8G8R8A8 单缓冲）→ D3D11 staging
纹理 CPU 回读 → BGRA→RGBA 交换。参考实现：flutter-webview-windows（MIT）。

沿途的关键运行时陷阱（都是实测踩出来的）：

- 非 STA 线程创建环境会返回 `RPC_E_CHANGED_MODE`，但只要环境句柄非空即有效；
- 现代 Windows 上 `Compositor::new()` 要求线程先创建 `DispatcherQueue`
  （thread_local 建一次，容忍"已存在"错误码 `0x8001010E`）；
- WebView2 默认背景透明，WGC 预乘 alpha 回读出全零（黑屏）——必须
  `SetDefaultBackgroundColor` 不透明白底；
- 每个 WebView 各建环境会拉起一整套浏览器进程（放置/拼屏时明显卡顿）——环境改为
  线程级共享，单环境承载任意多控制器。

### macOS：WKWebView 快照

隐藏无边框 `NSWindow` 承载 wry 的 WKWebView（`build_as_child` + `ParentNsView`），
帧用 `WKWebView.takeSnapshot` 轮询（~30Hz），回调里把 `NSImage` 重绘进自建
`NSBitmapImageRep`（RGBA、8bit×4）提取像素。所有操作在主调度队列执行
（与窗口化 WebView 同一形态），条目存 thread_local。

### Linux：WebKitGTK 快照

隐藏 tao 窗口承载 wry 的 WebKitGTK（`WebViewBuilder::build`），帧用
`webkit_web_view_get_snapshot`（`SnapshotRegion::Visible`）轮询，cairo 图面是
ARGB32 预乘（小端内存序 B,G,R,A），逐像素交换成 RGBA。运行在 tao 事件循环线程上
（它持有 glib 主上下文，快照回调在此完成）。

macOS/Linux 都关闭了 wry 的 background throttling，否则隐藏视图约 5 分钟后会被挂起。

## 3. 纹理上传与世界内渲染

- `WebDisplayManager` 按锚点管理 `ActiveDisplay`：拉取新帧（世代号去重）→
  直接 `ByteBuffer` 上传 `WebDisplayTexture`（`GL_RGBA8`）。纹理**必须注册进
  `TextureManager`**，否则 `entitySolid` 渲染时拿到缺失纹理（紫黑方块）。
- **视口策略**：无论屏幕多大，WebView 布局宽度恒定 1920 CSS px，高度按组的宽高比
  伸缩（上限 8192，超限等比缩小），保证字体/排版不随屏幕尺寸变化。
- 渲染器只在锚点提交**一个**覆盖整组的四边形（`RenderTypes.entitySolid`），
  法线取朝向——深度写入让 3D 世界自然地遮挡屏幕（躲在墙后看不到），屏幕内容以
  全亮坐标自发光显示。
- **视锥剔除修复**：BE 渲染器的默认渲染包围盒只有锚点自身 1×1×1，锚点一离开视锥
  整块大屏消失；`getRenderBoundingBox` 覆盖为整组包围盒（走 10 tick 节流缓存，
  避免每帧泛洪）。

## 4. 输入捕获

右键进入捕获屏（`WebDisplayCaptureScreen`）：隐形 Screen 接管键鼠。

- **鼠标投影**：相机视图旋转投影矩阵求逆，把鼠标 NDC 远点反投影成世界射线，与组
  正面所在平面求交得到 UV，再乘以视口分辨率得到页面坐标。
- **事件转发**：Windows 走 CDP `Input.dispatchMouseEvent / dispatchKeyEvent /
  insertText`（可信事件，原生行为完整）；macOS/Linux 没有等价注入 API，走
  **CDP→JS 翻译层**（`cdp_input_js`）：`elementFromPoint` 派发合成 DOM 事件，
  mousedown 时手动聚焦可编辑元素，滚轮换算成 `scrollBy`，文本用
  `execCommand('insertText')`。
- Esc 退出捕获；Shift+Esc 把纯 Esc 转发给页面。

## 5. 性能决策

- 拼屏/拆屏导致组结构变化时：**锚点不变 → 原地 resize**（只重建纹理）；**锚点移动 →
  迁移**旧条目的 WebView 到新锚点（页面不重载、浏览器进程不重建）。这是消除放置
  卡顿的主要手段。
- 组计算本身做了 `MAX_SCAN = 256` 的失控保护，客户端每位置 10 tick 最多重算一次。

## 6. 已知限制与平台状态

| 平台 | 状态 | 注意事项 |
| --- | --- | --- |
| Windows | 已实机验证 | 需要 WebView2 Runtime（Windows 11 / 新版 Edge 预装） |
| macOS | 已通过 CI 编译，**未经实机验证** | 位图行序可能上下翻转（代码中已标注翻转点）；快照依赖隐藏 NSWindow 仍可渲染 |
| Linux | 已通过 CI 编译，**未经实机验证** | 隐藏窗口下快照可能为空（若如此需改为屏幕外窗口）；需 WebKitGTK 4.1 运行时 |
| 其他 | 不支持 | 创建失败时退化为白色混凝土 + 单次告警 |

- macOS/Linux 的合成事件 `isTrusted=false`：对严格校验事件可信性的站点，点击/输入
  交互会受限；滚动与文本输入由翻译层兜底。
- 32 位 Linux 原生目标已从 CI 移除（Ubuntu 24.04 的 i386 WebKitGTK 开发链不可安装，
  且 32 位环境无 JDK 25）。
- 页面加载依赖系统的网络栈直连，不走 JVM/Gradle 代理设置。

## 7. 相关代码索引

```
rust/src/offscreen.rs                 三平台离屏实现 + CDP→JS 翻译层
src/main/java/dev/anvilcraft/oxide/ferric/
  display/                            方块、方块实体、组合算法、注册、服务端同步
  client/display/                     管理器、渲染器、动态纹理、几何、捕获/编辑屏
  network/                            SetWebDisplayUrlPayload
  webui/OffscreenWebView.java         离屏 JNI 封装
src/test/java/.../WebDisplayGroupTest.java            组合算法单元测试
src/test/java/.../OffscreenWebViewSmokeTest.java      离屏渲染冒烟测试（默认禁用）
```
