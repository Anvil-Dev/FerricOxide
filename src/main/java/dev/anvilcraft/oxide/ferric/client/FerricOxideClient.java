package dev.anvilcraft.oxide.ferric.client;

import dev.anvilcraft.oxide.ferric.FerricOxide;
import dev.anvilcraft.oxide.ferric.webui.NativeLoader;
import dev.anvilcraft.oxide.ferric.webui.NativeWebView;
import dev.anvilcraft.oxide.ferric.webui.WebUi;
import dev.anvilcraft.oxide.ferric.webui.WebUiMessage;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWNativeWin32;

/** Client-side entry point: loads the native library and wires the demo WebView hotkey. */
@Mod(value = FerricOxide.MODID, dist = Dist.CLIENT)
public final class FerricOxideClient {
    private static final KeyMapping OPEN_WEB_UI = new KeyMapping(
        "key.ferric_oxide.open_webui",
        GLFW.GLFW_KEY_F6,
        KeyMapping.Category.MISC
    );

    /** Set FERRICOXIDE_AUTO_OPEN=1 to open the demo UI right after game start (smoke testing). */
    private static final boolean AUTO_OPEN = System.getenv("FERRICOXIDE_AUTO_OPEN") != null;
    private static boolean autoOpened;

    public FerricOxideClient(IEventBus modEventBus) {
        NativeLoader.load();
        modEventBus.addListener(RegisterKeyMappingsEvent.class, event -> event.register(OPEN_WEB_UI));
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, FerricOxideClient::onClientTick);
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        while (OPEN_WEB_UI.consumeClick()) {
            DemoWebUi.toggle();
        }
        if (AUTO_OPEN && !autoOpened && Minecraft.getInstance().getWindow() != null
            && Minecraft.getInstance().getWindow().getWidth() > 0) {
            autoOpened = true;
            DemoWebUi.toggle();
        }
        DemoWebUi.syncBounds();
        DemoWebUi.pushGameTime();
    }

    /** Shared demo state: a single toggled WebView window showing the bundled demo page. */
    static final class DemoWebUi {
        private static WebUi webUi;
        private static int lastWidth = -1;
        private static int lastHeight = -1;
        private static int gameTimeCounter;
        /** Whether the mouse cursor was grabbed by Minecraft before the UI opened. */
        private static boolean mouseWasGrabbed;

        private DemoWebUi() {}

        static void toggle() {
            Minecraft mc = Minecraft.getInstance();
            if (!NativeWebView.isAvailable()) {
                FerricOxide.LOGGER.warn("Native webview unavailable; cannot open demo UI");
                return;
            }
            if (webUi != null) {
                close();
                return;
            }
            mouseWasGrabbed = mc.mouseHandler.isMouseGrabbed();
            try {
                webUi = WebUi.embedded(
                    "FerricOxide Demo",
                    WebUi.readModAsset(FerricOxide.MODID, "webui/demo.html"),
                    mc.getWindow().getWidth(),
                    mc.getWindow().getHeight())
                    .on("ferric_oxide.ping", DemoWebUi::onPing)
                    .on("ferric_oxide.close", message -> close());
            } catch (Throwable t) {
                FerricOxide.LOGGER.error("Failed to create demo webview", t);
            }
        }

        /**
         * Closes the demo UI and restores Minecraft's mouse capture.
         *
         * <p>The embedded webview is destroyed asynchronously on the native thread, so focus is
         * handed back to the Minecraft window and the grab is deferred to the next render tick.
         */
        static void close() {
            if (webUi == null) {
                return;
            }
            webUi.close();
            webUi = null;
            lastWidth = -1;
            lastHeight = -1;
            if (!mouseWasGrabbed) {
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            // Ensure the OS focus is back on the Minecraft window before grabbing the cursor.
            GLFW.glfwFocusWindow(mc.getWindow().handle());
            mc.execute(() -> Minecraft.getInstance().mouseHandler.grabMouse());
        }

        /** Handles a JS->Java ping: shows the payload in the game chat. Runs on the render thread. */
        private static void onPing(WebUiMessage message) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                mc.gui.getChat().addClientSystemMessage(
                    net.minecraft.network.chat.Component.literal(
                        "WebView ping #" + message.integer("count", -1)));
            }
        }

        /** Keeps the embedded webview sized to the Minecraft window (called every client tick). */
        static void syncBounds() {
            if (webUi == null) {
                lastWidth = -1;
                lastHeight = -1;
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            int width = mc.getWindow().getWidth();
            int height = mc.getWindow().getHeight();
            if (width != lastWidth || height != lastHeight) {
                lastWidth = width;
                lastHeight = height;
                webUi.setBounds(0, 0, width, height);
            }
        }

        /** Pushes the current world time to the page once per second while the demo UI is open. */
        private static void pushGameTime() {
            if (webUi == null) {
                return;
            }
            if (++gameTimeCounter % 20 != 0) {
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null) {
                return;
            }
            String json = WebUiMessage.create("ferric_oxide.game_time")
                .put("ticks", mc.level.getGameTime())
                .toJson();
            webUi.eval("window.ferricOxide && window.ferricOxide.onGameTime && "
                + "window.ferricOxide.onGameTime(" + json + ");");
        }
    }
}
