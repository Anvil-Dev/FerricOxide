//! FerricOxide native WebView bridge.
//!
//! Exposes JNI functions consumed by `dev.anvilcraft.oxide.webui.NativeWebView`.
//! All WebView/window work happens on a single dedicated event-loop thread;
//! JNI calls are translated into messages sent through an `EventLoopProxy`.

use std::collections::HashMap;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{mpsc, Arc, OnceLock};

use jni::errors::ThrowRuntimeExAndDefault;
use jni::objects::{Global, JClass, JObject, JString};
use jni::signature::RuntimeMethodSignature;
use jni::strings::JNIString;
use jni::sys::{jboolean, jint, jlong};
use jni::{jni_str, Env, EnvUnowned, JavaVM, JValue};
use tao::dpi::{LogicalPosition, LogicalSize};
use tao::event::Event;
use tao::event_loop::{ControlFlow, EventLoop, EventLoopBuilder, EventLoopProxy, EventLoopWindowTarget};
use tao::window::{Window, WindowBuilder};
use wry::Rect;
use wry::{WebView, WebViewBuilder};

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
}

#[cfg(not(windows))]
mod platform {
    pub fn is_valid_parent(_hwnd: isize) -> bool {
        false
    }
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
/// from the event-loop thread after the WebView has been created (or failed to).
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

/// Parameters for creating a new WebView.
struct WebViewSpec {
    title: String,
    width: u32,
    height: u32,
    url: Option<String>,
    html: Option<String>,
    transparent: bool,
    visible: bool,
    /// Foreign window handle to embed into (HWND on Windows, 0 = standalone window).
    parent: isize,
    callback: Option<IpcCallback>,
    creation: Option<CreationCallback>,
}

/// Commands sent from arbitrary JNI threads to the event-loop thread.
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
}

/// A live WebView. Field order matters: the WebView borrows the parent window
/// handle, so it must be dropped first. `window` is only present for standalone
/// (top-level) webviews; embedded ones have no window of their own.
struct Entry {
    webview: WebView,
    window: Option<Window>,
}

static PROXY: OnceLock<EventLoopProxy<Cmd>> = OnceLock::new();
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

/// Lazily spawns the event-loop thread and returns a proxy to it.
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

fn run(event_loop: EventLoop<Cmd>) {
    let mut entries: HashMap<u64, Entry> = HashMap::new();
    let _ = event_loop.run(move |event, elwt, control_flow| {
        *control_flow = ControlFlow::Wait;
        if let Event::UserEvent(cmd) = event {
            handle(cmd, elwt, &mut entries);
        }
    });
}

fn handle(cmd: Cmd, elwt: &EventLoopWindowTarget<Cmd>, entries: &mut HashMap<u64, Entry>) {
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
            entries.remove(&id);
        }
    }
}

fn create_entry(
    id: u64,
    spec: WebViewSpec,
    elwt: &EventLoopWindowTarget<Cmd>,
    entries: &mut HashMap<u64, Entry>,
) -> Result<(), String> {
    let mut builder = WebViewBuilder::new().with_transparent(spec.transparent);
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

    if spec.parent != 0 {
        if !platform::is_valid_parent(spec.parent) {
            return Err("parent window is only supported on Windows".to_string());
        }
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
        });
        return Ok(());
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
                };
                let creation = spec.creation.take();

                let id = NEXT_ID.fetch_add(1, Ordering::Relaxed);
                // Fire-and-forget: creation happens on the event-loop thread and the result is
                // reported through the CreationCallback. We must not block here - a blocking
                // nativeCreate while the parent window's thread is the caller would deadlock
                // when the embedded webview's creation sends synchronous messages to it.
                if proxy()
                    .send_event(Cmd::Create {
                        id,
                        spec,
                        creation,
                    })
                    .is_err()
                {
                    return fail(env, "webview event loop is not running");
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
                    let _ = proxy().send_event(Cmd::Eval {
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
                    let _ = proxy().send_event(Cmd::LoadUrl {
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
                    let _ = proxy().send_event(Cmd::LoadHtml {
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
    let _ = proxy().send_event(Cmd::SetVisible {
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
    let _ = proxy().send_event(Cmd::Focus { id: id as u64 });
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
    let _ = proxy().send_event(Cmd::SetBounds {
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
    let _ = proxy().send_event(Cmd::Close { id: id as u64 });
}
