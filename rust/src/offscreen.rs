//! Offscreen WebView2 rendering (Windows only).
//!
//! Creates a WebView2 composition controller rooted at a WinRT composition visual that is
//! never shown on screen, and captures that visual subtree with Windows.Graphics.Capture
//! (WGC) into a CPU-readable RGBA frame buffer. Input is forwarded through the Chrome
//! DevTools Protocol (`ICoreWebView2::CallDevToolsProtocolMethod`), so no window message
//! plumbing is needed at all.
//!
//! Reference implementation: flutter-webview-windows (MIT),
//! <https://github.com/nu-book/flutter-webview-windows> — same composition-controller +
//! `GraphicsCaptureItem.CreateFromVisual` + free-threaded frame pool pipeline.

/// One captured frame: 8-byte little-endian generation followed by tightly packed RGBA.
pub struct Frame {
    pub generation: u64,
    pub rgba: Vec<u8>,
}

/// Parameters for creating an offscreen WebView.
pub struct OffscreenSpec {
    pub width: u32,
    pub height: u32,
    pub url: Option<String>,
    pub init_script: Option<String>,
}

#[cfg(windows)]
pub use imp::OffscreenWebView;

#[cfg(not(windows))]
pub use stub::OffscreenWebView;

#[cfg(not(windows))]
mod stub {
    use super::{Frame, OffscreenSpec};

    /// Non-Windows placeholder: creation always fails, every operation is a no-op.
    pub struct OffscreenWebView;

    impl OffscreenWebView {
        pub fn create(_spec: OffscreenSpec) -> Result<Self, String> {
            Err("offscreen webview is only supported on Windows".to_string())
        }

        pub fn frame(&self, _last_generation: u64) -> Option<Frame> {
            None
        }

        pub fn resize(&mut self, _width: u32, _height: u32) {}

        pub fn cdp(&self, _method: &str, _params: &str) {}

        pub fn load_url(&self, _url: &str) {}

        pub fn eval(&self, _js: &str) {}
    }
}

#[cfg(windows)]
mod imp {
    use super::{Frame, OffscreenSpec};
    use std::sync::atomic::{AtomicBool, Ordering};
    use std::sync::mpsc;
    use std::sync::{Arc, Mutex};

    use webview2_com::Microsoft::Web::WebView2::Win32::*;
    use webview2_com::{
        CoreWebView2EnvironmentOptions, CreateCoreWebView2CompositionControllerCompletedHandler,
        CreateCoreWebView2EnvironmentCompletedHandler, NewWindowRequestedEventHandler,
    };
    use windows::core::{w, Interface, HSTRING, PCWSTR};
    use windows::Foundation::TypedEventHandler;
    use windows::Graphics::Capture::{
        Direct3D11CaptureFramePool, GraphicsCaptureItem, GraphicsCaptureSession,
    };
    use windows::Graphics::DirectX::Direct3D11::IDirect3DDevice;
    use windows::Graphics::DirectX::DirectXPixelFormat;
    use windows::Graphics::SizeInt32;
    use windows::UI::Composition::{Compositor, ContainerVisual};
    use windows::Win32::Foundation::{HMODULE, HWND, LPARAM, LRESULT, RECT, WPARAM};
    use windows::Win32::Graphics::Direct3D::D3D_DRIVER_TYPE_HARDWARE;
    use windows::Win32::Graphics::Direct3D11::{
        D3D11CreateDevice, ID3D11Device, ID3D11DeviceContext, ID3D11Texture2D,
        D3D11_CPU_ACCESS_READ, D3D11_CREATE_DEVICE_BGRA_SUPPORT, D3D11_MAP_READ,
        D3D11_MAPPED_SUBRESOURCE, D3D11_SDK_VERSION, D3D11_TEXTURE2D_DESC, D3D11_USAGE_STAGING,
    };
    use windows::Win32::Graphics::Dxgi::Common::{
        DXGI_FORMAT_B8G8R8A8_UNORM, DXGI_SAMPLE_DESC,
    };
    use windows::Win32::System::WinRT::Direct3D11::{
        CreateDirect3D11DeviceFromDXGIDevice, IDirect3DDxgiInterfaceAccess,
    };
    use windows::Win32::System::WinRT::{
        CreateDispatcherQueueController, DispatcherQueueOptions, RoInitialize,
        DQTAT_COM_STA, DQTYPE_THREAD_CURRENT, RO_INIT_SINGLETHREADED,
    };
    use windows::Win32::UI::WindowsAndMessaging::{
        CreateWindowExW, DefWindowProcW, DestroyWindow, RegisterClassW, WINDOW_EX_STYLE,
        WNDCLASSW, WS_OVERLAPPED,
    };
    use windows::System::DispatcherQueueController;
    use windows_numerics::Vector2;

    /// Hidden host window procedure: everything goes to the default handler.
    unsafe extern "system" fn host_wndproc(
        hwnd: HWND,
        msg: u32,
        wparam: WPARAM,
        lparam: LPARAM,
    ) -> LRESULT {
        DefWindowProcW(hwnd, msg, wparam, lparam)
    }

    /// Shared between the webview event-loop thread (resize/close) and the WGC
    /// frame-arrival threads (readback). Guards the D3D11 immediate context too.
    struct Shared {
        device: ID3D11Device,
        context: ID3D11DeviceContext,
        staging: Option<ID3D11Texture2D>,
        /// Latest frame in tightly packed RGBA (swizzled from premultiplied BGRA).
        latest: Vec<u8>,
        generation: u64,
        /// 上次回读时刻：约 30Hz 节流，避免高频页面把 GPU->CPU 带宽打满。
        last_copy: std::time::Instant,
        width: u32,
        height: u32,
    }

    /// A live offscreen WebView. All public methods run on the webview event-loop thread
    /// except [`OffscreenWebView::frame`], which only locks the shared buffer.
    pub struct OffscreenWebView {
        controller: ICoreWebView2CompositionController,
        webview: ICoreWebView2,
        root_visual: ContainerVisual,
        /// 保持合成器存活（可视化树的所有者）。
        _compositor: Compositor,
        frame_pool: Direct3D11CaptureFramePool,
        session: GraphicsCaptureSession,
        winrt_device: IDirect3DDevice,
        hwnd: HWND,
        shared: Arc<Mutex<Shared>>,
        width: u32,
        height: u32,
    }

    /// Ensures WinRT is initialized on the calling (event-loop) thread. WebView2
    /// environment creation requires an STA thread, so we request the single-threaded
    /// apartment; `RPC_E_CHANGED_MODE` (already initialized differently, e.g. by wry)
    /// is tolerated.
    fn ensure_ro_initialized() {
        static DONE: AtomicBool = AtomicBool::new(false);
        if DONE.swap(true, Ordering::Relaxed) {
            return;
        }
        let _ = unsafe { RoInitialize(RO_INIT_SINGLETHREADED) };
    }

    /// 当前线程的 DispatcherQueue 控制器（每个线程只创建一个，线程存续期内保持存活）。
    /// WebView2/wry 可能已经在事件循环线程上建过队列，此时直接复用。
    fn ensure_dispatcher_queue() -> Result<(), String> {
        const RPC_S_ALREADY_CREATED: i32 = 0x8001010E_u32 as i32;
        thread_local! {
            static CONTROLLER: std::cell::RefCell<Option<DispatcherQueueController>> =
                const { std::cell::RefCell::new(None) };
        }
        CONTROLLER.with(|slot| {
            let mut guard = slot.borrow_mut();
            if guard.is_some() {
                return Ok(());
            }
            let options = DispatcherQueueOptions {
                dwSize: std::mem::size_of::<DispatcherQueueOptions>() as u32,
                threadType: DQTYPE_THREAD_CURRENT,
                apartmentType: DQTAT_COM_STA,
            };
            match unsafe { CreateDispatcherQueueController(options) } {
                Ok(controller) => {
                    *guard = Some(controller);
                    Ok(())
                }
                Err(e) if e.code().0 == RPC_S_ALREADY_CREATED => Ok(()),
                Err(e) => Err(format!("CreateDispatcherQueueController: {e}")),
            }
        })
    }

    /// Dedicated user data folder so the offscreen environments never clash with the
    /// windowed webviews wry creates on this process.
    fn user_data_folder() -> HSTRING {
        let base = std::env::var("LOCALAPPDATA").unwrap_or_else(|_| std::env::temp_dir().to_string_lossy().into_owned());
        HSTRING::from(format!("{base}\\ferric_oxide\\webview2-offscreen"))
    }

    /// 事件循环线程上共享的 WebView2 环境：每个离屏 WebView 各自创建环境会拉起一套
    /// 全新的浏览器进程（放置/拆分时明显卡顿），同一环境可承载任意多个合成控制器。
    fn shared_environment() -> Result<ICoreWebView2Environment3, String> {
        thread_local! {
            static ENVIRONMENT: std::cell::RefCell<Option<ICoreWebView2Environment3>> =
                const { std::cell::RefCell::new(None) };
        }
        if let Some(env) = ENVIRONMENT.with(|slot| slot.borrow().clone()) {
            return Ok(env);
        }
        let env = create_environment()?;
        ENVIRONMENT.with(|slot| *slot.borrow_mut() = Some(env.clone()));
        Ok(env)
    }

    /// Creates the WebView2 environment, pumping COM messages while the async call
    /// completes (same pattern wry uses for its windowed webviews).
    fn create_environment() -> Result<ICoreWebView2Environment3, String> {
        let (tx, rx) = mpsc::channel();
        let data_dir = user_data_folder();
        let options = CoreWebView2EnvironmentOptions::default();
        unsafe {
            CreateCoreWebView2EnvironmentWithOptions(
                PCWSTR::null(),
                PCWSTR(data_dir.as_ptr()),
                &ICoreWebView2EnvironmentOptions::from(options),
                &CreateCoreWebView2EnvironmentCompletedHandler::create(Box::new(
                    move |error_code, environment| {
                        // WebView2 内部以 STA 初始化 COM；若线程已是其他单元模式，
                        // 回调会收到 RPC_E_CHANGED_MODE 但环境本身其实创建成功
                        // （flutter-webview-windows 亦如此处理），此时照常接受环境。
                        let result = (|| {
                            match (error_code, environment) {
                                (Ok(()), Some(env)) => Ok(env),
                                (Err(e), Some(env))
                                    if e.code() == windows::Win32::Foundation::RPC_E_CHANGED_MODE =>
                                {
                                    Ok(env)
                                }
                                (Err(e), _) => Err(e),
                                (Ok(()), None) => Err(windows::core::Error::from(
                                    windows::Win32::Foundation::E_POINTER,
                                )),
                            }
                        })();
                        let _ = tx.send(result.map_err(|e| format!("{e:?}")));
                        Ok(())
                    },
                )),
            )
            .map_err(|e| format!("CreateCoreWebView2EnvironmentWithOptions: {e}"))?;
        }
        let env = webview2_com::wait_with_pump(rx)
            .map_err(|e| format!("webview2 environment: {e:?}"))?
            .map_err(|e| format!("webview2 environment: {e}"))?;
        env.cast::<ICoreWebView2Environment3>()
            .map_err(|e| format!("ICoreWebView2Environment3 unavailable: {e}"))
    }

    /// Creates the hidden top-level window that parents the composition controller.
    fn create_host_window() -> Result<HWND, String> {
        let instance = unsafe {
            windows::Win32::System::LibraryLoader::GetModuleHandleW(None)
                .map_err(|e| format!("GetModuleHandleW: {e}"))?
        };
        let class = w!("FerricOxideOffscreenHost");
        let wc = WNDCLASSW {
            lpfnWndProc: Some(host_wndproc),
            lpszClassName: class,
            hInstance: instance.into(),
            ..Default::default()
        };
        // Ignored on purpose: ERROR_CLASS_ALREADY_EXISTS just means a previous entry
        // registered it.
        let _ = unsafe { RegisterClassW(&wc) };
        unsafe {
            CreateWindowExW(
                WINDOW_EX_STYLE::default(),
                class,
                w!("ferric_oxide offscreen host"),
                WS_OVERLAPPED,
                0,
                0,
                64,
                64,
                None,
                None,
                Some(instance.into()),
                None,
            )
            .map_err(|e| format!("CreateWindowExW: {e}"))
        }
    }

    fn create_composition_controller(
        env: &ICoreWebView2Environment3,
        hwnd: HWND,
    ) -> Result<ICoreWebView2CompositionController, String> {
        let (tx, rx) = mpsc::channel();
        unsafe {
            env.CreateCoreWebView2CompositionController(
                hwnd,
                &CreateCoreWebView2CompositionControllerCompletedHandler::create(Box::new(
                    move |error_code, controller| {
                        let result = (|| {
                            error_code?;
                            controller
                                .ok_or_else(|| windows::core::Error::from(windows::Win32::Foundation::E_POINTER))
                        })();
                        let _ = tx.send(result.map_err(|e| format!("{e:?}")));
                        Ok(())
                    },
                )),
            )
            .map_err(|e| format!("CreateCoreWebView2CompositionController: {e}"))?;
        }
        webview2_com::wait_with_pump(rx)
            .map_err(|e| format!("composition controller: {e:?}"))?
            .map_err(|e| format!("composition controller: {e}"))
    }

    /// Creates the D3D11 device + immediate context used for readback.
    fn create_d3d11() -> Result<(ID3D11Device, ID3D11DeviceContext), String> {
        let mut device = None;
        let mut context = None;
        unsafe {
            D3D11CreateDevice(
                None,
                D3D_DRIVER_TYPE_HARDWARE,
                HMODULE::default(),
                // BGRA support is required by every DXGI/DComp consumer.
                D3D11_CREATE_DEVICE_BGRA_SUPPORT,
                None,
                D3D11_SDK_VERSION,
                Some(&mut device),
                None,
                Some(&mut context),
            )
            .map_err(|e| format!("D3D11CreateDevice: {e}"))?;
        }
        match (device, context) {
            (Some(device), Some(context)) => Ok((device, context)),
            _ => Err("D3D11CreateDevice returned null outputs".to_string()),
        }
    }

    fn create_staging(
        device: &ID3D11Device,
        width: u32,
        height: u32,
    ) -> Result<ID3D11Texture2D, String> {
        let desc = D3D11_TEXTURE2D_DESC {
            Width: width,
            Height: height,
            MipLevels: 1,
            ArraySize: 1,
            Format: DXGI_FORMAT_B8G8R8A8_UNORM,
            SampleDesc: DXGI_SAMPLE_DESC { Count: 1, Quality: 0 },
            Usage: D3D11_USAGE_STAGING,
            BindFlags: 0,
            CPUAccessFlags: D3D11_CPU_ACCESS_READ.0 as u32,
            MiscFlags: 0,
        };
        let mut texture = None;
        unsafe {
            device
                .CreateTexture2D(&desc, None, Some(&mut texture))
                .map_err(|e| format!("CreateTexture2D(staging): {e}"))?;
        }
        texture.ok_or_else(|| "staging texture is null".to_string())
    }

    /// Copies the captured frame into the shared RGBA buffer. Runs on a WGC callback
    /// thread; the shared mutex serializes against resize/close on the event-loop thread.
    fn on_frame(shared: &Arc<Mutex<Shared>>, texture: &ID3D11Texture2D) {
        let mut guard = shared.lock().unwrap();
        let Shared { context, staging, latest, generation, last_copy, width, height, .. } = &mut *guard;
        if last_copy.elapsed() < std::time::Duration::from_millis(33) {
            return;
        }
        let Some(staging) = staging else {
            return;
        };
        unsafe {
            context.CopyResource(&*staging, texture);
            let mut mapped = D3D11_MAPPED_SUBRESOURCE::default();
            if context
                .Map(&*staging, 0, D3D11_MAP_READ, 0, Some(&mut mapped))
                .is_err()
            {
                return;
            }
            let w = *width as usize;
            let h = *height as usize;
            let need = w * h * 4;
            if latest.len() != need {
                latest.resize(need, 0);
            }
            let src = mapped.pData as *const u8;
            let row_pitch = mapped.RowPitch as usize;
            for row in 0..h {
                let src_row = src.add(row * row_pitch);
                let dst_row = latest.as_mut_ptr().add(row * w * 4);
                for col in 0..w {
                    let s = src_row.add(col * 4);
                    let d = dst_row.add(col * 4);
                    // BGRA (premultiplied) -> RGBA, alpha kept as-is.
                    *d = *s.add(2);
                    *d.add(1) = *s.add(1);
                    *d.add(2) = *s;
                    *d.add(3) = *s.add(3);
                }
            }
            context.Unmap(&*staging, 0);
        }
        *last_copy = std::time::Instant::now();
        *generation += 1;
    }

    impl OffscreenWebView {
        /// Creates an offscreen WebView. Must be called on the webview event-loop thread:
        /// environment/controller creation pumps that thread's message queue.
        pub fn create(spec: OffscreenSpec) -> Result<Self, String> {
            ensure_ro_initialized();
            if !GraphicsCaptureSession::IsSupported()
                .map_err(|e| format!("GraphicsCaptureSession.IsSupported: {e}"))?
            {
                return Err("Windows.Graphics.Capture is not supported on this machine".to_string());
            }

            let hwnd = create_host_window()?;
            let result = (|| {
                let env = shared_environment()?;
                let controller = create_composition_controller(&env, hwnd)?;

                // Pixel-exact offscreen sizing: raw pixels, fixed scale 1.0.
                let controller3 = controller
                    .cast::<ICoreWebView2Controller3>()
                    .map_err(|e| format!("ICoreWebView2Controller3: {e}"))?;
                unsafe {
                    controller3
                        .SetBoundsMode(COREWEBVIEW2_BOUNDS_MODE_USE_RAW_PIXELS)
                        .map_err(|e| format!("SetBoundsMode: {e}"))?;
                    controller3
                        .SetShouldDetectMonitorScaleChanges(false)
                        .map_err(|e| format!("SetShouldDetectMonitorScaleChanges: {e}"))?;
                    controller3
                        .SetRasterizationScale(1.0)
                        .map_err(|e| format!("SetRasterizationScale: {e}"))?;
                    // 默认背景设成不透明白：透明背景在 WGC 下读出的是 premultiplied
                    // 全零像素（渲染成纯黑），页面未加载完时应显示白底。
                    if let Ok(controller2) = controller.cast::<ICoreWebView2Controller2>() {
                        let _ = controller2.SetDefaultBackgroundColor(COREWEBVIEW2_COLOR {
                            A: 255,
                            R: 255,
                            G: 255,
                            B: 255,
                        });
                    }
                }

                let base = controller
                    .cast::<ICoreWebView2Controller>()
                    .map_err(|e| format!("ICoreWebView2Controller: {e}"))?;
                let webview = unsafe {
                    base.SetBounds(RECT {
                        left: 0,
                        top: 0,
                        right: spec.width as i32,
                        bottom: spec.height as i32,
                    })
                    .map_err(|e| format!("SetBounds: {e}"))?;
                    base.SetIsVisible(true).map_err(|e| format!("SetIsVisible: {e}"))?;
                    base.CoreWebView2()
                        .map_err(|e| format!("CoreWebView2: {e}"))?
                };

                // Same-window popups: target=_blank links navigate this webview instead of
                // spawning a foreign Edge window.
                unsafe {
                    let mut token = 0i64;
                    let webview_for_popup = webview.clone();
                    webview
                        .add_NewWindowRequested(
                            &NewWindowRequestedEventHandler::create(Box::new(
                                move |_sender, args| {
                                    if let Some(args) = args {
                                        args.SetNewWindow(&webview_for_popup)?;
                                        args.SetHandled(true)?;
                                    }
                                    Ok(())
                                },
                            )),
                            &mut token,
                        )
                        .map_err(|e| format!("add_NewWindowRequested: {e}"))?;
                }

                if let Some(script) = &spec.init_script {
                    unsafe {
                        webview
                            .AddScriptToExecuteOnDocumentCreated(&HSTRING::from(script), None)
                            .map_err(|e| format!("AddScriptToExecuteOnDocumentCreated: {e}"))?;
                    }
                }

                // WinRT compositor tree: root sized explicitly, webview child follows it.
                // 新版 Windows 的 Compositor::new 强制要求当前线程存在 DispatcherQueue；
                // 队列每线程只建一次并在其中保持存活。不创建 CompositionTarget：
                // CreateTargetForCurrentView 在桌面进程要求 CoreWindow 不可用，而
                // WGC 捕获会话自身会驱动可视化树合成。
                ensure_dispatcher_queue()?;
                let compositor = Compositor::new()
                    .map_err(|e| format!("Compositor::new: {e}"))?;
                let root = compositor
                    .CreateContainerVisual()
                    .map_err(|e| format!("CreateContainerVisual: {e}"))?;
                root.SetSize(Vector2 {
                    X: spec.width as f32,
                    Y: spec.height as f32,
                })
                .map_err(|e| format!("visual SetSize: {e}"))?;
                root.SetIsVisible(true)
                    .map_err(|e| format!("visual SetIsVisible: {e}"))?;
                let child = compositor
                    .CreateContainerVisual()
                    .map_err(|e| format!("CreateContainerVisual(child): {e}"))?;
                let child_visual = child
                    .cast::<windows::UI::Composition::Visual>()
                    .map_err(|e| format!("child cast to Visual: {e}"))?;
                child_visual
                    .SetRelativeSizeAdjustment(Vector2 { X: 1.0, Y: 1.0 })
                    .map_err(|e| format!("SetRelativeSizeAdjustment: {e}"))?;
                root.Children()
                    .map_err(|e| format!("Children: {e}"))?
                    .InsertAtTop(&child)
                    .map_err(|e| format!("InsertAtTop: {e}"))?;
                unsafe {
                    controller
                        .SetRootVisualTarget(&child_visual)
                        .map_err(|e| format!("SetRootVisualTarget: {e}"))?;
                }

                // Capture pipeline: WGC on the root visual, free-threaded frame pool.
                let (device, context) = create_d3d11()?;
                let dxgi_device = device
                    .cast::<windows::Win32::Graphics::Dxgi::IDXGIDevice>()
                    .map_err(|e| format!("IDXGIDevice: {e}"))?;
                let winrt_device: IDirect3DDevice = unsafe {
                    CreateDirect3D11DeviceFromDXGIDevice(&dxgi_device)
                        .map_err(|e| format!("CreateDirect3D11DeviceFromDXGIDevice: {e}"))?
                        .cast()
                        .map_err(|e| format!("IDirect3DDevice cast: {e}"))?
                };
                let capture_item = GraphicsCaptureItem::CreateFromVisual(&root)
                    .map_err(|e| format!("GraphicsCaptureItem.CreateFromVisual: {e}"))?;

                let staging = create_staging(&device, spec.width, spec.height)?;
                let shared = Arc::new(Mutex::new(Shared {
                    device,
                    context,
                    staging: Some(staging),
                    latest: vec![0; spec.width as usize * spec.height as usize * 4],
                    generation: 0,
                    last_copy: std::time::Instant::now()
                        .checked_sub(std::time::Duration::from_millis(100))
                        .unwrap_or_else(std::time::Instant::now),
                    width: spec.width,
                    height: spec.height,
                }));

                let frame_pool = Direct3D11CaptureFramePool::CreateFreeThreaded(
                    &winrt_device,
                    DirectXPixelFormat::B8G8R8A8UIntNormalized,
                    1,
                    SizeInt32 {
                        Width: spec.width as i32,
                        Height: spec.height as i32,
                    },
                )
                .map_err(|e| format!("frame pool: {e}"))?;

                let handler_shared = Arc::clone(&shared);
                frame_pool
                    .FrameArrived(&TypedEventHandler::new(
                        move |pool: windows::core::Ref<'_, Direct3D11CaptureFramePool>,
                              _args: windows::core::Ref<'_, windows::core::IInspectable>| {
                            let Some(pool) = pool.cloned() else {
                                return Ok(());
                            };
                            if let Ok(frame) = pool.TryGetNextFrame() {
                                if let Ok(surface) = frame.Surface() {
                                    if let Ok(access) =
                                        surface.cast::<IDirect3DDxgiInterfaceAccess>()
                                    {
                                        if let Ok(texture) =
                                            unsafe { access.GetInterface::<ID3D11Texture2D>() }
                                        {
                                            on_frame(&handler_shared, &texture);
                                        }
                                    }
                                }
                                let _ = frame.Close();
                            }
                            Ok(())
                        },
                    ))
                    .map_err(|e| format!("FrameArrived: {e}"))?;

                let session = frame_pool
                    .CreateCaptureSession(&capture_item)
                    .map_err(|e| format!("CreateCaptureSession: {e}"))?;
                session
                    .StartCapture()
                    .map_err(|e| format!("StartCapture: {e}"))?;

                if let Some(url) = &spec.url {
                    unsafe {
                        webview
                            .Navigate(&HSTRING::from(url))
                            .map_err(|e| format!("Navigate: {e}"))?;
                    }
                }

                Ok(OffscreenWebView {
                    controller,
                    webview,
                    root_visual: root,
                    _compositor: compositor,
                    frame_pool,
                    session,
                    winrt_device,
                    hwnd,
                    shared,
                    width: spec.width,
                    height: spec.height,
                })
            })();
            if result.is_err() {
                unsafe {
                    let _ = DestroyWindow(hwnd);
                }
            }
            result
        }

        /// Returns the newest frame when its generation differs from `last_generation`.
        pub fn frame(&self, last_generation: u64) -> Option<Frame> {
            let guard = self.shared.lock().unwrap();
            if guard.generation == last_generation {
                return None;
            }
            Some(Frame {
                generation: guard.generation,
                rgba: guard.latest.clone(),
            })
        }

        /// Resizes the page viewport and recreates the frame pool and staging texture.
        pub fn resize(&mut self, width: u32, height: u32) {
            if width == 0 || height == 0 || (width == self.width && height == self.height) {
                return;
            }
            self.width = width;
            self.height = height;
            let _ = self.root_visual.SetSize(Vector2 {
                X: width as f32,
                Y: height as f32,
            });
            unsafe {
                let _ = self
                    .controller
                    .cast::<ICoreWebView2Controller>()
                    .and_then(|base| {
                        base.SetBounds(RECT {
                            left: 0,
                            top: 0,
                            right: width as i32,
                            bottom: height as i32,
                        })
                    });
            }
            let mut guard = self.shared.lock().unwrap();
            guard.width = width;
            guard.height = height;
            guard.latest = vec![0; width as usize * height as usize * 4];
            if let Ok(staging) = create_staging(&guard.device, width, height) {
                guard.staging = Some(staging);
            }
            drop(guard);
            let _ = self.frame_pool.Recreate(
                &self.winrt_device,
                DirectXPixelFormat::B8G8R8A8UIntNormalized,
                1,
                SizeInt32 {
                    Width: width as i32,
                    Height: height as i32,
                },
            );
        }

        /// Forwards a Chrome DevTools Protocol command (used for input injection).
        pub fn cdp(&self, method: &str, params: &str) {
            unsafe {
                let _ = self.webview.CallDevToolsProtocolMethod(
                    &HSTRING::from(method),
                    &HSTRING::from(params),
                    None,
                );
            }
        }

        /// Navigates to a URL.
        pub fn load_url(&self, url: &str) {
            unsafe {
                let _ = self.webview.Navigate(&HSTRING::from(url));
            }
        }

        /// Runs a JavaScript snippet in the page context. Fire-and-forget.
        pub fn eval(&self, js: &str) {
            unsafe {
                let _ = self.webview.ExecuteScript(&HSTRING::from(js), None);
            }
        }
    }

    impl Drop for OffscreenWebView {
        fn drop(&mut self) {
            let _ = self.session.Close();
            let _ = self.frame_pool.Close();
            unsafe {
                let base: Result<ICoreWebView2Controller, _> = self.controller.cast();
                if let Ok(base) = base {
                    let _ = base.Close();
                }
                let _ = DestroyWindow(self.hwnd);
            }
        }
    }
}
