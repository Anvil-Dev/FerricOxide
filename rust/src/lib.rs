//! FerricOxide native WebView bridge.
//!
//! Exposes JNI functions consumed by `dev.anvilcraft.oxide.ferric.webui.NativeWebView`.
//! Windows and Linux use a dedicated Tao event-loop thread. macOS instead dispatches
//! every command to the OS main queue, where AppKit and WKWebView must be accessed.

#[cfg(target_os = "macos")]
use std::any::Any;
#[cfg(target_os = "macos")]
use std::cell::RefCell;
use std::collections::HashMap;
use std::sync::atomic::{AtomicU64, Ordering};
#[cfg(not(target_os = "macos"))]
use std::sync::mpsc;
use std::sync::{Arc, Mutex, OnceLock};

use jni::errors::ThrowRuntimeExAndDefault;
use jni::objects::{Global, JByteArray, JClass, JObject, JString};
use jni::signature::RuntimeMethodSignature;
use jni::strings::JNIString;
use jni::sys::{jboolean, jint, jlong};
use jni::{jni_str, Env, EnvUnowned, JavaVM, JValue};
#[cfg(not(target_os = "macos"))]
use tao::dpi::{LogicalPosition, LogicalSize};
#[cfg(not(target_os = "macos"))]
use tao::event::Event;
#[cfg(not(target_os = "macos"))]
use tao::event_loop::{
    ControlFlow, EventLoop, EventLoopBuilder, EventLoopProxy, EventLoopWindowTarget,
};
#[cfg(not(target_os = "macos"))]
use tao::window::{Window, WindowBuilder};
use wry::http::Response;
use wry::Rect;
use wry::{RequestAsyncResponder, WebView, WebViewBuilder};

mod offscreen;

#[cfg(windows)]
mod platform {
    use std::num::NonZeroIsize;
    use wry::raw_window_handle::{
        HandleError, HasWindowHandle, RawWindowHandle, WindowHandle, Win32WindowHandle,
    };

    /// Wraps a foreign HWND (e.g. Minecraft's GLFW window) so it can act as the WebView parent.
    pub struct ParentHwnd(pub isize);

    impl HasWindowHandle for ParentHwnd {
        fn window_handle(&self) -> Result<WindowHandle<'_>, HandleError> {
            // SAFETY: the HWND is owned by the caller (Minecraft) and stays valid for the
            // lifetime of the embedded webview, which keeps its own copy of the handle.
            unsafe {
                Ok(WindowHandle::borrow_raw(RawWindowHandle::Win32(
                    Win32WindowHandle::new(
                        NonZeroIsize::new(self.0).ok_or(HandleError::NotSupported)?,
                    ),
                )))
            }
        }
    }

    pub fn is_valid_parent(hwnd: isize) -> bool {
        hwnd != 0
    }

    #[link(name = "user32")]
    unsafe extern "system" {
        fn GetForegroundWindow() -> isize;
        fn GetWindowThreadProcessId(hwnd: isize, pid: *mut u32) -> u32;
        fn AttachThreadInput(id_attach: u32, id_attach_to: u32, attach: i32) -> i32;
        fn BringWindowToTop(hwnd: isize) -> i32;
        fn SetForegroundWindow(hwnd: isize) -> i32;
        fn SetFocus(hwnd: isize) -> isize;
    }

    #[link(name = "kernel32")]
    unsafe extern "system" {
        fn GetCurrentThreadId() -> u32;
    }

    /// Returns OS focus to the Minecraft window after its focused WebView2 child is destroyed.
    ///
    /// The webview thread owns neither the foreground window nor the target window, so it
    /// temporarily attaches its input queue to both; otherwise `SetForegroundWindow`/`SetFocus`
    /// would be silently rejected by the foreground-lock rules.
    pub fn restore_focus(hwnd: isize) {
        unsafe {
            let current = GetCurrentThreadId();
            let foreground_thread = GetWindowThreadProcessId(GetForegroundWindow(), std::ptr::null_mut());
            let target_thread = GetWindowThreadProcessId(hwnd, std::ptr::null_mut());
            for thread in [foreground_thread, target_thread] {
                if thread != 0 && thread != current {
                    AttachThreadInput(current, thread, 1);
                }
            }
            BringWindowToTop(hwnd);
            SetForegroundWindow(hwnd);
            SetFocus(hwnd);
            for thread in [foreground_thread, target_thread] {
                if thread != 0 && thread != current {
                    AttachThreadInput(current, thread, 0);
                }
            }
        }
    }
}

#[cfg(target_os = "macos")]
mod platform {
    use std::ffi::c_void;
    use std::ptr::NonNull;
    use wry::raw_window_handle::{
        AppKitWindowHandle, HandleError, HasWindowHandle, RawWindowHandle, WindowHandle,
    };

    /// Wraps Minecraft's GLFW NSView so WKWebView can be created as its child on the main thread.
    pub struct ParentNsView(pub isize);

    impl HasWindowHandle for ParentNsView {
        fn window_handle(&self) -> Result<WindowHandle<'_>, HandleError> {
            let ns_view = NonNull::new(self.0 as *mut c_void).ok_or(HandleError::NotSupported)?;
            // SAFETY: GLFW owns the NSView and keeps it alive for the Minecraft window's lifetime.
            // This method is only called while handling a command on the main dispatch queue.
            unsafe {
                Ok(WindowHandle::borrow_raw(RawWindowHandle::AppKit(
                    AppKitWindowHandle::new(ns_view),
                )))
            }
        }
    }
}

#[cfg(all(not(windows), not(target_os = "macos")))]
mod platform {
    pub fn is_valid_parent(_hwnd: isize) -> bool {
        false
    }

    pub fn restore_focus(_hwnd: isize) {}
}

/// Java-side callback (`MessageHandler.onMessage(String)`) invoked from the IPC handler.
struct IpcCallback {
    vm: Arc<JavaVM>,
    handler: Global<JObject<'static>>,
}

impl IpcCallback {
    fn dispatch(&self, body: &str) {
        let _ = self
            .vm
            .attach_current_thread(|env| -> jni::errors::Result<()> {
                let message = env.new_string(body)?;
                let result = env.call_method(
                    self.handler.as_ref(),
                    jni_str!("onMessage"),
                    on_message_sig().method_signature(),
                    &[(&message).into()],
                );
                if result.is_err() || env.exception_check() {
                    env.exception_describe();
                    let _ = env.exception_clear();
                }
                Ok(())
            });
    }
}

/// Lazily parsed `(Ljava/lang/String;)V` signature for the IPC callback method.
fn on_message_sig() -> &'static RuntimeMethodSignature {
    static SIG: OnceLock<RuntimeMethodSignature> = OnceLock::new();
    SIG.get_or_init(|| "(Ljava/lang/String;)V".parse().expect("valid method signature"))
}

/// Java-side creation-result callback (`CreationCallback.onResult(long, String)`) invoked
/// from the platform's native UI thread after the WebView has been created (or failed to).
struct CreationCallback {
    vm: Arc<JavaVM>,
    handler: Global<JObject<'static>>,
}

impl CreationCallback {
    fn notify(&self, id: u64, error: Option<&str>) {
        let _ = self
            .vm
            .attach_current_thread(|env| -> jni::errors::Result<()> {
                let message = env.new_string(error.unwrap_or(""))?;
                let result = env.call_method(
                    self.handler.as_ref(),
                    jni_str!("onResult"),
                    on_result_sig().method_signature(),
                    &[JValue::Long(id as jlong), (&message).into()],
                );
                if result.is_err() || env.exception_check() {
                    env.exception_describe();
                    let _ = env.exception_clear();
                }
                Ok(())
            });
    }
}

/// Lazily parsed `(JLjava/lang/String;)V` signature for the creation-result callback.
fn on_result_sig() -> &'static RuntimeMethodSignature {
    static SIG: OnceLock<RuntimeMethodSignature> = OnceLock::new();
    SIG.get_or_init(|| "(JLjava/lang/String;)V".parse().expect("valid method signature"))
}

/// Java-side resource resolver (`ResourceHandler.resolve(String, long)`) invoked from the
/// custom protocol handler when the page requests a {@code ferric://<namespace>/<path>} URL.
struct ResourceCallback {
    vm: Arc<JavaVM>,
    handler: Global<JObject<'static>>,
}

impl ResourceCallback {
    fn resolve(&self, location: &str, request_id: u64) {
        let _ = self
            .vm
            .attach_current_thread(|env| -> jni::errors::Result<()> {
                let location = env.new_string(location)?;
                let result = env.call_method(
                    self.handler.as_ref(),
                    jni_str!("resolve"),
                    resource_resolve_sig().method_signature(),
                    &[(&location).into(), JValue::Long(request_id as jlong)],
                );
                if result.is_err() || env.exception_check() {
                    env.exception_describe();
                    let _ = env.exception_clear();
                }
                Ok(())
            });
    }
}

/// Lazily parsed `(Ljava/lang/String;J)V` signature for the resource-resolver callback.
fn resource_resolve_sig() -> &'static RuntimeMethodSignature {
    static SIG: OnceLock<RuntimeMethodSignature> = OnceLock::new();
    SIG.get_or_init(|| "(Ljava/lang/String;J)V".parse().expect("valid method signature"))
}

/// Responders for in-flight resource requests, keyed by request id; the Java side resolves
/// the resource on the render thread and hands the bytes back via `nativeResourceResponse`.
static PENDING_RESOURCES: OnceLock<Mutex<HashMap<u64, RequestAsyncResponder>>> = OnceLock::new();
static NEXT_RESOURCE_ID: AtomicU64 = AtomicU64::new(1);

/// Registers the custom protocol that maps `{protocol}://<namespace>/<path>` URLs to
/// `namespace:path` resource locations. Every matching request is forwarded to the Java
/// resource resolver; the response is completed asynchronously once the render thread
/// returns the resource bytes.
fn register_resource_protocol<'a>(
    builder: WebViewBuilder<'a>,
    protocol: String,
    resource: ResourceCallback,
) -> WebViewBuilder<'a> {
    builder.with_asynchronous_custom_protocol(protocol.clone(), move |_id, request, responder| {
        let uri = request.uri().to_string();
        let location = parse_resource_location(&uri, &protocol);
        let Some(location) = location else {
            responder.respond(
                Response::builder()
                    .status(404)
                    .body(Vec::<u8>::new())
                    .expect("valid 404 response"),
            );
            return;
        };
        let request_id = NEXT_RESOURCE_ID.fetch_add(1, Ordering::Relaxed);
        PENDING_RESOURCES
            .get_or_init(|| Mutex::new(HashMap::new()))
            .lock()
            .unwrap()
            .insert(request_id, responder);
        resource.resolve(&location, request_id);
    })
}

/// Maps a custom-protocol URL back to a `namespace:path` resource location, preserving any
/// query string (e.g. `?size=48`) so the Java side can read parameters.
///
/// On Windows the WebView2 workaround reverts `http://{protocol}.localhost/...` to
/// `{protocol}://localhost/<namespace>/<path>`, so the host is the placeholder `localhost`
/// and the namespace is the first path segment. On Linux/macOS the native scheme carries the
/// namespace in the host: `{protocol}://<namespace>/<path>`.
fn parse_resource_location(uri: &str, protocol: &str) -> Option<String> {
    let rest = uri.strip_prefix(&format!("{protocol}://"))?;
    let (host, path_and_query) = rest.split_once('/')?;
    let (namespace, path_and_query) = if host == "localhost" {
        path_and_query.split_once('/')?
    } else {
        (host, path_and_query)
    };
    let (path, query) = match path_and_query.split_once('?') {
        Some((path, query)) => (path, Some(query)),
        None => (path_and_query, None),
    };
    let path = path.split('#').next().unwrap_or(path);
    let query = query.map(|query| query.split('#').next().unwrap_or(query));
    if namespace.is_empty() || path.is_empty() {
        return None;
    }
    match query {
        Some(query) if !query.is_empty() => Some(format!("{namespace}:{path}?{query}")),
        _ => Some(format!("{namespace}:{path}")),
    }
}

/// Parameters for creating a new WebView.
struct WebViewSpec {
    #[cfg_attr(target_os = "macos", allow(dead_code))]
    title: String,
    width: u32,
    height: u32,
    url: Option<String>,
    html: Option<String>,
    transparent: bool,
    visible: bool,
    /// Foreign parent handle (HWND on Windows, NSView pointer on macOS, 0 = standalone).
    parent: isize,
    callback: Option<IpcCallback>,
    creation: Option<CreationCallback>,
    /// Custom protocol name (e.g. "ferric") mapping `{protocol}://<namespace>/<path>` to
    /// game resources; `None` disables the protocol.
    protocol: Option<String>,
    /// Java-side resolver used by the custom protocol handler.
    resource: Option<ResourceCallback>,
    /// Script evaluated before any page script on every navigation. Used to install the
    /// `ferric` bridge runtime so pages never have to feature-detect it.
    init_script: Option<String>,
}

/// Commands sent from arbitrary JNI threads to the platform's native UI thread.
enum Cmd {
    Create {
        id: u64,
        spec: WebViewSpec,
        creation: Option<CreationCallback>,
    },
    Eval {
        id: u64,
        js: String,
    },
    LoadUrl {
        id: u64,
        url: String,
    },
    LoadHtml {
        id: u64,
        html: String,
    },
    SetVisible {
        id: u64,
        visible: bool,
    },
    Focus {
        id: u64,
    },
    SetBounds {
        id: u64,
        x: i32,
        y: i32,
        w: u32,
        h: u32,
    },
    Close {
        id: u64,
    },
    OffscreenCreate {
        id: u64,
        spec: offscreen::OffscreenSpec,
        creation: Option<CreationCallback>,
    },
    OffscreenGetFrame {
        id: u64,
        last_generation: u64,
        respond: std::sync::mpsc::Sender<Option<(u64, Vec<u8>)>>,
    },
    OffscreenResize {
        id: u64,
        w: u32,
        h: u32,
    },
    OffscreenCdp {
        id: u64,
        method: String,
        params: String,
    },
    OffscreenLoadUrl {
        id: u64,
        url: String,
    },
    OffscreenEval {
        id: u64,
        js: String,
    },
    OffscreenClose {
        id: u64,
    },
}

/// A live WebView. Field order matters: the WebView must be dropped before its optional
/// standalone Tao window. Embedded Windows entries retain their parent HWND so focus can
/// be restored after WebView2 is destroyed; macOS entries live only in the main-thread map.
struct Entry {
    webview: WebView,
    #[cfg(not(target_os = "macos"))]
    window: Option<Window>,
    #[cfg(not(target_os = "macos"))]
    parent: isize,
}

#[cfg(not(target_os = "macos"))]
static PROXY: OnceLock<EventLoopProxy<Cmd>> = OnceLock::new();
#[cfg(target_os = "macos")]
thread_local! {
    static MAC_ENTRIES: RefCell<HashMap<u64, Entry>> = RefCell::new(HashMap::new());
    /// Offscreen WKWebViews live on the main dispatch queue, same as windowed ones.
    static MAC_OFFSCREEN_ENTRIES: RefCell<HashMap<u64, offscreen::OffscreenWebView>> =
        RefCell::new(HashMap::new());
}
static NEXT_ID: AtomicU64 = AtomicU64::new(1);

/// Debug aid: print panic payloads with location to stdout so they show up in run logs.
pub fn init_panic_hook() {
    std::panic::set_hook(Box::new(|info| {
        let payload = if let Some(s) = info.payload().downcast_ref::<&str>() {
            (*s).to_string()
        } else if let Some(s) = info.payload().downcast_ref::<String>() {
            s.clone()
        } else {
            "<non-string>".to_string()
        };
        eprintln!("[ferric-oxide] PANIC at {:?}: {}", info.location(), payload);
    }));
}

/// Lazily spawns the Windows/Linux event-loop thread and returns a proxy to it.
#[cfg(not(target_os = "macos"))]
fn proxy() -> &'static EventLoopProxy<Cmd> {
    PROXY.get_or_init(|| {
        let (tx, rx) = mpsc::channel::<EventLoopProxy<Cmd>>();
        std::thread::Builder::new()
            .name("ferric-oxide-webview".to_string())
            .spawn(move || {
                let mut builder = EventLoopBuilder::<Cmd>::with_user_event();
                #[cfg(target_os = "windows")]
                {
                    use tao::platform::windows::EventLoopBuilderExtWindows;
                    // Allow creating the event loop on a non-main thread; on Windows tao
                    // panics by default to discourage this.
                    builder.with_any_thread(true);
                }
                let event_loop = builder.build();
                let _ = tx.send(event_loop.create_proxy());
                run(event_loop);
            })
            .expect("failed to spawn webview thread");
        rx.recv().expect("webview event loop thread died on startup")
    })
}

#[cfg(not(target_os = "macos"))]
fn send_cmd(cmd: Cmd) -> Result<(), ()> {
    proxy().send_event(cmd).map_err(|_| ())
}

#[cfg(target_os = "macos")]
fn send_cmd(cmd: Cmd) -> Result<(), ()> {
    use dispatch2::DispatchQueue;

    DispatchQueue::main().exec_async(move || {
        if let Err(payload) = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            handle_macos(cmd);
        })) {
            eprintln!(
                "[ferric-oxide] macOS main-queue handler panicked: {}",
                panic_payload(payload.as_ref())
            );
        }
    });
    Ok(())
}

#[cfg(target_os = "macos")]
fn panic_payload(payload: &(dyn Any + Send)) -> String {
    if let Some(message) = payload.downcast_ref::<&str>() {
        (*message).to_string()
    } else if let Some(message) = payload.downcast_ref::<String>() {
        message.clone()
    } else {
        "<non-string>".to_string()
    }
}

#[cfg(not(target_os = "macos"))]
fn run(event_loop: EventLoop<Cmd>) {
    let mut entries: HashMap<u64, Entry> = HashMap::new();
    let mut offscreen_entries: HashMap<u64, offscreen::OffscreenWebView> = HashMap::new();
    let _ = event_loop.run(move |event, elwt, control_flow| {
        *control_flow = ControlFlow::Wait;
        if let Event::UserEvent(cmd) = event {
            handle(cmd, elwt, &mut entries, &mut offscreen_entries);
        }
    });
}

#[cfg(not(target_os = "macos"))]
fn handle(
    cmd: Cmd,
    elwt: &EventLoopWindowTarget<Cmd>,
    entries: &mut HashMap<u64, Entry>,
    offscreen_entries: &mut HashMap<u64, offscreen::OffscreenWebView>,
) {
    match cmd {
        Cmd::Create { id, spec, creation } => {
            let result = create_entry(id, spec, elwt, entries);
            if let Some(callback) = creation {
                callback.notify(id, result.as_ref().err().map(String::as_str));
            }
        }
        Cmd::Eval { id, js } => {
            if let Some(entry) = entries.get(&id) {
                let _ = entry.webview.evaluate_script(&js);
            }
        }
        Cmd::LoadUrl { id, url } => {
            if let Some(entry) = entries.get(&id) {
                let _ = entry.webview.load_url(&url);
            }
        }
        Cmd::LoadHtml { id, html } => {
            if let Some(entry) = entries.get(&id) {
                let _ = entry.webview.load_html(&html);
            }
        }
        Cmd::SetVisible { id, visible } => {
            if let Some(entry) = entries.get(&id) {
                let _ = entry.webview.set_visible(visible);
                if let Some(window) = &entry.window {
                    window.set_visible(visible);
                }
            }
        }
        Cmd::Focus { id } => {
            if let Some(entry) = entries.get(&id) {
                let _ = entry.webview.focus();
            }
        }
        Cmd::SetBounds { id, x, y, w, h } => {
            if let Some(entry) = entries.get(&id) {
                match &entry.window {
                    // Embedded bounds come from GLFW's native client area. Keep them in physical
                    // pixels so wry does not apply the Windows display scale a second time.
                    None => {
                        let _ = entry.webview.set_bounds(Rect {
                            position: tao::dpi::PhysicalPosition::new(x, y).into(),
                            size: tao::dpi::PhysicalSize::new(w, h).into(),
                        });
                    }
                    Some(window) => {
                        window
                            .set_outer_position(LogicalPosition::new(x as f64, y as f64));
                        window.set_inner_size(LogicalSize::new(w as f64, h as f64));
                    }
                }
            }
        }
        Cmd::Close { id } => {
            if let Some(entry) = entries.remove(&id) {
                let parent = entry.parent;
                // Drop the WebView first. Restoring focus before its child HWND is destroyed
                // lets WebView2 immediately steal focus back during teardown.
                drop(entry);
                if parent != 0 {
                    platform::restore_focus(parent);
                }
            }
        }
        Cmd::OffscreenCreate { id, spec, creation } => {
            let result = offscreen::OffscreenWebView::create(
                spec,
                // Linux/BSD offscreen views are built on a hidden tao window and need
                // the event loop target; Windows creates its own host window.
                #[cfg(any(
                    target_os = "linux",
                    target_os = "dragonfly",
                    target_os = "freebsd",
                    target_os = "openbsd",
                    target_os = "netbsd"
                ))]
                elwt,
            );
            if let Ok(view) = result {
                offscreen_entries.insert(id, view);
                if let Some(callback) = creation {
                    callback.notify(id, None);
                }
            } else if let Some(callback) = creation {
                callback.notify(id, result.as_ref().err().map(String::as_str));
            }
        }
        Cmd::OffscreenGetFrame {
            id,
            last_generation,
            respond,
        } => {
            let frame = offscreen_entries
                .get(&id)
                .and_then(|view| view.frame(last_generation))
                .map(|frame| (frame.generation, frame.rgba));
            let _ = respond.send(frame);
        }
        Cmd::OffscreenResize { id, w, h } => {
            if let Some(view) = offscreen_entries.get_mut(&id) {
                view.resize(w, h);
            }
        }
        Cmd::OffscreenCdp { id, method, params } => {
            if let Some(view) = offscreen_entries.get(&id) {
                view.cdp(&method, &params);
            }
        }
        Cmd::OffscreenLoadUrl { id, url } => {
            if let Some(view) = offscreen_entries.get(&id) {
                view.load_url(&url);
            }
        }
        Cmd::OffscreenEval { id, js } => {
            if let Some(view) = offscreen_entries.get(&id) {
                view.eval(&js);
            }
        }
        Cmd::OffscreenClose { id } => {
            offscreen_entries.remove(&id);
        }
    }
}

#[cfg(target_os = "macos")]
fn handle_macos(cmd: Cmd) {
    match cmd {
        Cmd::Create { id, spec, creation } => {
            let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                MAC_ENTRIES.with(|entries| {
                    let mut entries = entries.borrow_mut();
                    create_entry_macos(id, spec, &mut entries)
                })
            }));
            let result = match result {
                Ok(result) => result,
                Err(payload) => Err(format!(
                    "create embedded webview panicked: {}",
                    panic_payload(payload.as_ref())
                )),
            };
            if let Some(callback) = creation {
                callback.notify(id, result.as_ref().err().map(String::as_str));
            }
        }
        Cmd::Eval { id, js } => MAC_ENTRIES.with(|entries| {
            if let Some(entry) = entries.borrow().get(&id) {
                let _ = entry.webview.evaluate_script(&js);
            }
        }),
        Cmd::LoadUrl { id, url } => MAC_ENTRIES.with(|entries| {
            if let Some(entry) = entries.borrow().get(&id) {
                let _ = entry.webview.load_url(&url);
            }
        }),
        Cmd::LoadHtml { id, html } => MAC_ENTRIES.with(|entries| {
            if let Some(entry) = entries.borrow().get(&id) {
                let _ = entry.webview.load_html(&html);
            }
        }),
        Cmd::SetVisible { id, visible } => MAC_ENTRIES.with(|entries| {
            if let Some(entry) = entries.borrow().get(&id) {
                let _ = entry.webview.set_visible(visible);
            }
        }),
        Cmd::Focus { id } => MAC_ENTRIES.with(|entries| {
            if let Some(entry) = entries.borrow().get(&id) {
                let _ = entry.webview.focus();
            }
        }),
        Cmd::SetBounds { id, x, y, w, h } => MAC_ENTRIES.with(|entries| {
            if let Some(entry) = entries.borrow().get(&id) {
                let _ = entry.webview.set_bounds(Rect {
                    position: tao::dpi::PhysicalPosition::new(x, y).into(),
                    size: tao::dpi::PhysicalSize::new(w, h).into(),
                });
            }
        }),
        Cmd::Close { id } => MAC_ENTRIES.with(|entries| {
            entries.borrow_mut().remove(&id);
        }),
        Cmd::OffscreenCreate { id, spec, creation } => {
            let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                MAC_OFFSCREEN_ENTRIES.with(|entries| {
                    offscreen::OffscreenWebView::create(spec)
                        .map(|view| entries.borrow_mut().insert(id, view))
                })
            }));
            let error = match result {
                Ok(Ok(_)) => None,
                Ok(Err(message)) => Some(message),
                Err(payload) => Some(format!(
                    "create offscreen webview panicked: {}",
                    panic_payload(payload.as_ref())
                )),
            };
            if let Some(callback) = creation {
                callback.notify(id, error.as_deref());
            }
        }
        Cmd::OffscreenGetFrame {
            id,
            last_generation,
            respond,
        } => {
            let frame = MAC_OFFSCREEN_ENTRIES.with(|entries| {
                entries
                    .borrow()
                    .get(&id)
                    .and_then(|view| view.frame(last_generation))
                    .map(|frame| (frame.generation, frame.rgba))
            });
            let _ = respond.send(frame);
        }
        Cmd::OffscreenResize { id, w, h } => MAC_OFFSCREEN_ENTRIES.with(|entries| {
            if let Some(view) = entries.borrow_mut().get_mut(&id) {
                view.resize(w, h);
            }
        }),
        Cmd::OffscreenCdp { id, method, params } => MAC_OFFSCREEN_ENTRIES.with(|entries| {
            if let Some(view) = entries.borrow().get(&id) {
                view.cdp(&method, &params);
            }
        }),
        Cmd::OffscreenLoadUrl { id, url } => MAC_OFFSCREEN_ENTRIES.with(|entries| {
            if let Some(view) = entries.borrow().get(&id) {
                view.load_url(&url);
            }
        }),
        Cmd::OffscreenEval { id, js } => MAC_OFFSCREEN_ENTRIES.with(|entries| {
            if let Some(view) = entries.borrow().get(&id) {
                view.eval(&js);
            }
        }),
        Cmd::OffscreenClose { id } => MAC_OFFSCREEN_ENTRIES.with(|entries| {
            entries.borrow_mut().remove(&id);
        }),
    }
}

#[cfg(target_os = "macos")]
fn create_entry_macos(
    id: u64,
    spec: WebViewSpec,
    entries: &mut HashMap<u64, Entry>,
) -> Result<(), String> {
    if spec.parent == 0 {
        return Err(
            "standalone webviews are not supported on macOS; provide a parent NSView handle"
                .to_string(),
        );
    }

    let mut builder = WebViewBuilder::new().with_transparent(spec.transparent);
    if let Some(script) = spec.init_script {
        builder = builder.with_initialization_script(script);
    }
    if let Some(url) = spec.url {
        builder = builder.with_url(url);
    } else if let Some(html) = spec.html {
        builder = builder.with_html(html);
    }
    if let Some(callback) = spec.callback {
        builder = builder.with_ipc_handler(move |request| {
            callback.dispatch(request.body());
        });
    }
    if let (Some(protocol), Some(resource)) = (spec.protocol, spec.resource) {
        builder = register_resource_protocol(builder, protocol, resource);
    }

    let webview = builder
        .with_bounds(Rect {
            position: tao::dpi::PhysicalPosition::new(0, 0).into(),
            size: tao::dpi::PhysicalSize::new(spec.width, spec.height).into(),
        })
        .build_as_child(&platform::ParentNsView(spec.parent))
        .map_err(|e| format!("create embedded webview: {e}"))?;
    let _ = webview.set_visible(spec.visible);
    entries.insert(id, Entry { webview });
    Ok(())
}

#[cfg(not(target_os = "macos"))]
fn create_entry(
    id: u64,
    spec: WebViewSpec,
    elwt: &EventLoopWindowTarget<Cmd>,
    entries: &mut HashMap<u64, Entry>,
) -> Result<(), String> {
    let mut builder = WebViewBuilder::new().with_transparent(spec.transparent);
    if let Some(script) = spec.init_script {
        builder = builder.with_initialization_script(script);
    }
    if let Some(url) = spec.url {
        builder = builder.with_url(url);
    } else if let Some(html) = spec.html {
        builder = builder.with_html(html);
    }
    if let Some(callback) = spec.callback {
        builder = builder.with_ipc_handler(move |request| {
            callback.dispatch(request.body());
        });
    }
    if let (Some(protocol), Some(resource)) = (spec.protocol, spec.resource) {
        builder = register_resource_protocol(builder, protocol, resource);
    }

    if spec.parent != 0 {
        if !platform::is_valid_parent(spec.parent) {
            return Err("parent window is only supported on Windows".to_string());
        }
        #[cfg(windows)]
        {
            let webview = builder
                .with_bounds(Rect {
                    position: tao::dpi::PhysicalPosition::new(0, 0).into(),
                    size: tao::dpi::PhysicalSize::new(spec.width, spec.height).into(),
                })
                .build_as_child(&platform::ParentHwnd(spec.parent))
                .map_err(|e| format!("create embedded webview: {e}"))?;
            entries.insert(id, Entry {
                webview,
                window: None,
                parent: spec.parent,
            });
            return Ok(());
        }
        #[cfg(not(windows))]
        unreachable!("is_valid_parent rejects non-zero parents on this platform");
    }

    let window = WindowBuilder::new()
        .with_title(spec.title)
        .with_inner_size(LogicalSize::new(spec.width as f64, spec.height as f64))
        .with_visible(spec.visible)
        .with_resizable(true)
        .build(elwt)
        .map_err(|e| format!("create window: {e}"))?;

    let webview = builder
        .build(&window)
        .map_err(|e| format!("create webview: {e}"))?;
    entries.insert(id, Entry {
        webview,
        window: Some(window),
        parent: 0,
    });
    Ok(())
}

// ---------------------------------------------------------------------------
// JNI entry points (dev.anvilcraft.oxide.webui.NativeWebView)
// ---------------------------------------------------------------------------

fn opt_string(env: &Env<'_>, value: &JString<'_>) -> jni::errors::Result<Option<String>> {
    if value.is_null() {
        Ok(None)
    } else {
        value.try_to_string(env).map(Some)
    }
}

/// Throws a RuntimeException with the given message and returns a default value.
fn fail<T: Default>(env: &mut Env<'_>, message: &str) -> jni::errors::Result<T> {
    env.throw_new(jni_str!("java/lang/RuntimeException"), JNIString::new(message))?;
    Ok(T::default())
}

#[expect(clippy::too_many_arguments)]
#[no_mangle]
pub extern "system" fn Java_dev_anvilcraft_oxide_ferric_webui_NativeWebView_nativeCreate<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    title: JString<'caller>,
    width: jint,
    height: jint,
    url: JString<'caller>,
    html: JString<'caller>,
    transparent: jboolean,
    visible: jboolean,
    parent: jlong,
    handler: JObject<'caller>,
    creation: JObject<'caller>,
    protocol: JString<'caller>,
    resource: JObject<'caller>,
    init_script: JString<'caller>,
) -> jlong {
    unowned_env
        .with_env(
            |env| -> jni::errors::Result<jlong> {
                let Some(title) = opt_string(env, &title)? else {
                    return fail(env, "title must not be null");
                };

                let callback = if handler.is_null() {
                    None
                } else {
                    Some(IpcCallback {
                        vm: Arc::new(env.get_java_vm()?),
                        handler: env.new_global_ref(handler)?,
                    })
                };

                let creation = if creation.is_null() {
                    None
                } else {
                    Some(CreationCallback {
                        vm: Arc::new(env.get_java_vm()?),
                        handler: env.new_global_ref(creation)?,
                    })
                };

                let resource = if resource.is_null() {
                    None
                } else {
                    Some(ResourceCallback {
                        vm: Arc::new(env.get_java_vm()?),
                        handler: env.new_global_ref(resource)?,
                    })
                };

                let mut spec = WebViewSpec {
                    title,
                    width: width.max(1) as u32,
                    height: height.max(1) as u32,
                    url: opt_string(env, &url)?,
                    html: opt_string(env, &html)?,
                    transparent,
                    visible,
                    parent: parent as isize,
                    callback,
                    creation,
                    protocol: opt_string(env, &protocol)?,
                    resource,
                    init_script: opt_string(env, &init_script)?,
                };
                let creation = spec.creation.take();

                let id = NEXT_ID.fetch_add(1, Ordering::Relaxed);
                // Fire-and-forget: creation happens on the platform's native UI thread and the
                // result is reported through the CreationCallback. We must not block here - a
                // blocking nativeCreate while the parent window's thread is the caller would
                // deadlock when the embedded webview sends synchronous messages to it.
                if send_cmd(Cmd::Create { id, spec, creation }).is_err() {
                    return fail(env, "webview command dispatcher is not running");
                }
                Ok(id as jlong)
            },
        )
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[no_mangle]
pub extern "system" fn Java_dev_anvilcraft_oxide_ferric_webui_NativeWebView_nativeEval<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    id: jlong,
    js: JString<'caller>,
) {
    unowned_env
        .with_env(
            |env| -> jni::errors::Result<()> {
                if let Some(js) = opt_string(env, &js)? {
                    let _ = send_cmd(Cmd::Eval {
                        id: id as u64,
                        js,
                    });
                }
                Ok(())
            },
        )
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[no_mangle]
pub extern "system" fn Java_dev_anvilcraft_oxide_ferric_webui_NativeWebView_nativeLoadUrl<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    id: jlong,
    url: JString<'caller>,
) {
    unowned_env
        .with_env(
            |env| -> jni::errors::Result<()> {
                if let Some(url) = opt_string(env, &url)? {
                    let _ = send_cmd(Cmd::LoadUrl {
                        id: id as u64,
                        url,
                    });
                }
                Ok(())
            },
        )
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[no_mangle]
pub extern "system" fn Java_dev_anvilcraft_oxide_ferric_webui_NativeWebView_nativeLoadHtml<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    id: jlong,
    html: JString<'caller>,
) {
    unowned_env
        .with_env(
            |env| -> jni::errors::Result<()> {
                if let Some(html) = opt_string(env, &html)? {
                    let _ = send_cmd(Cmd::LoadHtml {
                        id: id as u64,
                        html,
                    });
                }
                Ok(())
            },
        )
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[no_mangle]
pub extern "system" fn Java_dev_anvilcraft_oxide_ferric_webui_NativeWebView_nativeSetVisible(
    _env: EnvUnowned<'_>,
    _class: JClass<'_>,
    id: jlong,
    visible: jboolean,
) {
    let _ = send_cmd(Cmd::SetVisible {
        id: id as u64,
        visible,
    });
}

#[no_mangle]
pub extern "system" fn Java_dev_anvilcraft_oxide_ferric_webui_NativeWebView_nativeFocus(
    _env: EnvUnowned<'_>,
    _class: JClass<'_>,
    id: jlong,
) {
    let _ = send_cmd(Cmd::Focus { id: id as u64 });
}

#[no_mangle]
pub extern "system" fn Java_dev_anvilcraft_oxide_ferric_webui_NativeWebView_nativeSetBounds(
    _env: EnvUnowned<'_>,
    _class: JClass<'_>,
    id: jlong,
    x: jint,
    y: jint,
    w: jint,
    h: jint,
) {
    let _ = send_cmd(Cmd::SetBounds {
        id: id as u64,
        x,
        y,
        w: w.max(1) as u32,
        h: h.max(1) as u32,
    });
}

#[no_mangle]
pub extern "system" fn Java_dev_anvilcraft_oxide_ferric_webui_NativeWebView_nativeClose(
    _env: EnvUnowned<'_>,
    _class: JClass<'_>,
    id: jlong,
) {
    let _ = send_cmd(Cmd::Close { id: id as u64 });
}

/// Completes a pending custom-protocol resource request with the bytes resolved on the Java
/// side. A null byte array resolves as 404; otherwise the payload is served with the given
/// MIME type.
#[no_mangle]
pub extern "system" fn Java_dev_anvilcraft_oxide_ferric_webui_NativeWebView_nativeResourceResponse<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    request_id: jlong,
    bytes: JByteArray<'caller>,
    mime: JString<'caller>,
) {
    unowned_env
        .with_env(|env| -> jni::errors::Result<()> {
            let payload = if bytes.is_null() {
                None
            } else {
                let raw = env.convert_byte_array(&bytes)?;
                let mime = opt_string(env, &mime)?
                    .unwrap_or_else(|| "application/octet-stream".to_string());
                Some((mime, raw.into_iter().map(|b| b as u8).collect::<Vec<u8>>()))
            };

            if let Some(responder) = PENDING_RESOURCES
                .get_or_init(|| Mutex::new(HashMap::new()))
                .lock()
                .unwrap()
                .remove(&(request_id as u64))
            {
                match payload {
                    Some((mime, body)) => {
                        let response = Response::builder()
                            .status(200)
                            .header("Content-Type", mime)
                            .body(body)
                            .expect("valid resource response");
                        responder.respond(response);
                    }
                    None => {
                        let response = Response::builder()
                            .status(404)
                            .body(Vec::<u8>::new())
                            .expect("valid 404 response");
                        responder.respond(response);
                    }
                }
            }
            Ok(())
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

// ---------------------------------------------------------------------------
// Offscreen JNI entry points (dev.anvilcraft.oxide.ferric.webui.OffscreenWebView)
// ---------------------------------------------------------------------------

/// Creates an offscreen WebView asynchronously; returns the id immediately and reports the
/// outcome through the creation callback.
#[no_mangle]
pub extern "system" fn Java_dev_anvilcraft_oxide_ferric_webui_OffscreenWebView_nativeCreate<
    'caller,
>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    width: jint,
    height: jint,
    url: JString<'caller>,
    init_script: JString<'caller>,
    creation: JObject<'caller>,
) -> jlong {
    unowned_env
        .with_env(|env| -> jni::errors::Result<jlong> {
            let creation = if creation.is_null() {
                None
            } else {
                Some(CreationCallback {
                    vm: Arc::new(env.get_java_vm()?),
                    handler: env.new_global_ref(creation)?,
                })
            };
            let spec = offscreen::OffscreenSpec {
                width: width.max(1) as u32,
                height: height.max(1) as u32,
                url: opt_string(env, &url)?,
                init_script: opt_string(env, &init_script)?,
            };
            let id = NEXT_ID.fetch_add(1, Ordering::Relaxed);
            if send_cmd(Cmd::OffscreenCreate { id, spec, creation }).is_err() {
                return fail(env, "webview command dispatcher is not running");
            }
            Ok(id as jlong)
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

/// Returns the newest frame as `8-byte little-endian generation || RGBA pixels`, or null when
/// nothing newer than `last_generation` exists (or the webview is gone).
#[no_mangle]
pub extern "system" fn Java_dev_anvilcraft_oxide_ferric_webui_OffscreenWebView_nativeGetFrame<
    'caller,
>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    id: jlong,
    last_generation: jlong,
) -> JByteArray<'caller> {
    unowned_env
        .with_env(|env| -> jni::errors::Result<JByteArray<'caller>> {
            let (tx, rx) = std::sync::mpsc::channel();
            if send_cmd(Cmd::OffscreenGetFrame {
                id: id as u64,
                last_generation: last_generation as u64,
                respond: tx,
            })
            .is_err()
            {
                return Ok(JByteArray::default());
            }
            // The event loop answers promptly; a timeout keeps a dead loop from hanging the
            // render thread.
            let Ok(Some((generation, rgba))) = rx.recv_timeout(std::time::Duration::from_secs(2))
            else {
                return Ok(JByteArray::default());
            };
            let mut payload = Vec::with_capacity(rgba.len() + 8);
            payload.extend_from_slice(&generation.to_le_bytes());
            payload.extend_from_slice(&rgba);
            let signed: Vec<i8> = payload.into_iter().map(|b| b as i8).collect();
            let array = env.new_byte_array(signed.len())?;
            array.set_region(env, 0, &signed)?;
            Ok(array)
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[no_mangle]
pub extern "system" fn Java_dev_anvilcraft_oxide_ferric_webui_OffscreenWebView_nativeResize(
    _env: EnvUnowned<'_>,
    _class: JClass<'_>,
    id: jlong,
    width: jint,
    height: jint,
) {
    let _ = send_cmd(Cmd::OffscreenResize {
        id: id as u64,
        w: width.max(1) as u32,
        h: height.max(1) as u32,
    });
}

#[no_mangle]
pub extern "system" fn Java_dev_anvilcraft_oxide_ferric_webui_OffscreenWebView_nativeCdp<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    id: jlong,
    method: JString<'caller>,
    params: JString<'caller>,
) {
    unowned_env
        .with_env(|env| -> jni::errors::Result<()> {
            if let (Some(method), Some(params)) =
                (opt_string(env, &method)?, opt_string(env, &params)?)
            {
                let _ = send_cmd(Cmd::OffscreenCdp {
                    id: id as u64,
                    method,
                    params,
                });
            }
            Ok(())
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[no_mangle]
pub extern "system" fn Java_dev_anvilcraft_oxide_ferric_webui_OffscreenWebView_nativeLoadUrl<
    'caller,
>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    id: jlong,
    url: JString<'caller>,
) {
    unowned_env
        .with_env(|env| -> jni::errors::Result<()> {
            if let Some(url) = opt_string(env, &url)? {
                let _ = send_cmd(Cmd::OffscreenLoadUrl {
                    id: id as u64,
                    url,
                });
            }
            Ok(())
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[no_mangle]
pub extern "system" fn Java_dev_anvilcraft_oxide_ferric_webui_OffscreenWebView_nativeEval<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    id: jlong,
    js: JString<'caller>,
) {
    unowned_env
        .with_env(|env| -> jni::errors::Result<()> {
            if let Some(js) = opt_string(env, &js)? {
                let _ = send_cmd(Cmd::OffscreenEval { id: id as u64, js });
            }
            Ok(())
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[no_mangle]
pub extern "system" fn Java_dev_anvilcraft_oxide_ferric_webui_OffscreenWebView_nativeClose(
    _env: EnvUnowned<'_>,
    _class: JClass<'_>,
    id: jlong,
) {
    let _ = send_cmd(Cmd::OffscreenClose { id: id as u64 });
}
