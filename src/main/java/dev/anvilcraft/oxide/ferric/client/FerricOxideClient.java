package dev.anvilcraft.oxide.ferric.client;

import dev.anvilcraft.oxide.ferric.FerricOxide;
import dev.anvilcraft.oxide.ferric.webui.NativeLoader;
import dev.anvilcraft.oxide.ferric.webui.NativeWebView;
import dev.anvilcraft.oxide.ferric.webui.WebUi;
import dev.anvilcraft.oxide.ferric.webui.WebUiMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.commands.Commands;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.common.NeoForge;

import org.jspecify.annotations.Nullable;

/**
 * Client-side entry point: loads the native library and registers the demo WebView command.
 */
@Mod(value = FerricOxide.MODID, dist = Dist.CLIENT)
public final class FerricOxideClient {
    /**
     * Set FERRICOXIDE_AUTO_OPEN=1 to open the demo UI right after game start (smoke testing).
     */
    private static final boolean AUTO_OPEN = System.getenv("FERRICOXIDE_AUTO_OPEN") != null;
    private static boolean autoOpened;

    public FerricOxideClient(IEventBus modEventBus) {
        NativeLoader.load();
        NeoForge.EVENT_BUS.addListener(RegisterClientCommandsEvent.class, FerricOxideClient::registerClientCommands);
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, FerricOxideClient::onClientTick);
    }

    private static void registerClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher()
            .register(Commands.literal("ferric").then(Commands.literal("ui").then(Commands.literal("demo").executes(context -> {
                DemoWebUi.open();
                return 1;
            }))));
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        if (AUTO_OPEN && !autoOpened && Minecraft.getInstance().getWindow() != null && Minecraft.getInstance().getWindow().getWidth() > 0) {
            autoOpened = true;
            DemoWebUi.open();
        }
        DemoWebUi.tickMouseGrab();
        DemoWebUi.syncBounds();
        DemoWebUi.pushGameTime();
    }

    /**
     * Shared demo state: a single WebView window showing the bundled demo page.
     */
    static final class DemoWebUi {
        private static @Nullable WebUi webUi;
        private static int lastWidth = -1;
        private static int lastHeight = -1;
        private static int gameTimeCounter;
        /**
         * Ticks remaining during which we retry re-grabbing the mouse after closing the UI.
         * The OS does not hand focus back to the Minecraft window synchronously when the
         * webview child window is destroyed, and {@code grabMouse()} silently does nothing
         * while the window is unfocused — so we retry for up to a second.
         */
        private static int grabRetryTicks;

        private DemoWebUi() {
        }

        static void open() {
            Minecraft mc = Minecraft.getInstance();
            if (!NativeWebView.isAvailable()) {
                FerricOxide.LOGGER.warn("Native webview unavailable; cannot open demo UI");
                return;
            }
            if (webUi != null) {
                webUi.focus();
                return;
            }
            try {
                webUi = WebUi.embedded(
                    "FerricOxide Demo",
                    FerricOxide.MODID,
                    "webui/demo.html",
                    mc.getWindow().getWidth(),
                    mc.getWindow().getHeight()
                ).on("ferric_oxide.ping", DemoWebUi::onPing).on("ferric_oxide.close", message -> close());
            } catch (Throwable t) {
                FerricOxide.LOGGER.error("Failed to create demo webview", t);
            }
        }

        /**
         * Closes the demo UI and restores Minecraft's mouse capture.
         *
         * <p>The embedded webview is destroyed asynchronously on the native thread, and the OS
         * only hands focus back to the Minecraft window afterwards. {@code grabMouse()} is a
         * no-op while the window is unfocused, so the actual grab is retried from
         * {@link #tickMouseGrab()} once focus has returned.
         *
         * <p>Whether the grab is attempted at all is decided from the screen state at close
         * time, not from the moment the UI was opened: the {@code /ferric ui demo} command
         * executes while the chat screen is still open, so a grab flag captured in
         * {@link #open()} would be stale. Only in-game (no screen) or the focus-loss
         * {@link PauseScreen} should the cursor return to grab; any other screen (e.g. the
         * title screen with {@code FERRICOXIDE_AUTO_OPEN}) keeps it free.
         */
        static void close() {
            if (webUi == null) {
                return;
            }
            webUi.close();
            webUi = null;
            lastWidth = -1;
            lastHeight = -1;
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen != null && !(mc.screen instanceof PauseScreen)) {
                return;
            }
            // Native teardown restores OS focus after destroying the WebView child. Retry the
            // Minecraft cursor grab until that asynchronous focus transfer has completed.
            grabRetryTicks = 20;
        }

        /**
         * Retries the deferred mouse grab until the window regains focus (called every tick).
         */
        static void tickMouseGrab() {
            if (grabRetryTicks <= 0) {
                return;
            }
            grabRetryTicks--;
            Minecraft mc = Minecraft.getInstance();
            if (mc.mouseHandler.isMouseGrabbed()) {
                grabRetryTicks = 0;
                return;
            }
            if (mc.screen != null) {
                // While the embedded webview holds OS focus, vanilla pause-on-lost-focus opens
                // a PauseScreen behind it. That screen is a side effect of the UI itself, not
                // user intent — dismiss it so the game returns to the pre-UI state. Any other
                // screen is left alone and stops the retry.
                if (mc.screen instanceof PauseScreen) {
                    mc.setScreen(null);
                } else {
                    grabRetryTicks = 0;
                    return;
                }
            }
            if (mc.isWindowActive()) {
                mc.mouseHandler.grabMouse();
                grabRetryTicks = 0;
            }
        }

        /**
         * Handles a JS->Java ping: shows the payload in the game chat. Runs on the render thread.
         */
        private static void onPing(WebUiMessage message) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                mc.gui.getChat()
                    .addClientSystemMessage(net.minecraft.network.chat.Component.literal("WebView ping #" + message.integer("count", -1)));
            }
        }

        /**
         * Keeps the embedded webview sized to the Minecraft window (called every client tick).
         */
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

        /**
         * Pushes the current world time to the page once per second while the demo UI is open.
         */
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
            String json = WebUiMessage.create("ferric_oxide.game_time").put("ticks", mc.level.getGameTime()).toJson();
            webUi.eval("window.ferricOxide && window.ferricOxide.onGameTime && " + "window.ferricOxide.onGameTime(" + json + ");");
        }
    }
}
