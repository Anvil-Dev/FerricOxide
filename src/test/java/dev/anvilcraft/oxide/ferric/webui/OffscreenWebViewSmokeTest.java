package dev.anvilcraft.oxide.ferric.webui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 离屏渲染冒烟测试（仅 Windows + 已安装 WebView2 运行时，且显式开启时运行）：
 *
 * <pre>./gradlew test --tests "*OffscreenWebViewSmokeTest" -Dferricoxide.offscreen.smoke=true</pre>
 *
 * <p>创建一个 320x180 的离屏 WebView 加载纯红页面，等待 WGC 捕获到帧后断言中心像素
 * 为红色，并把整帧保存为 {@code build/offscreen-smoke.png} 供人工检查。
 */
@EnabledIfSystemProperty(named = "ferricoxide.offscreen.smoke", matches = "true")
class OffscreenWebViewSmokeTest {
    private static final int WIDTH = 320;
    private static final int HEIGHT = 180;

    @Test
    void rendersAPageOffscreen() throws Exception {
        NativeLoader.load();
        assertTrue(NativeLoader.isLoaded(), "native library failed to load");

        String html = "<html><body style=\"margin:0;background:#e02040\"></body></html>";
        String url = "data:text/html," + URLEncoder.encode(html, StandardCharsets.UTF_8).replace("+", "%20");

        java.util.concurrent.atomic.AtomicReference<String> creationError =
            new java.util.concurrent.atomic.AtomicReference<>();
        try (OffscreenWebView view = new OffscreenWebView.Builder()
            .size(WIDTH, HEIGHT)
            .url(url)
            .onCreated((id, error) -> {
                if (error != null && !error.isEmpty()) {
                    creationError.set(error);
                }
            })
            .build()) {
            OffscreenWebView.Frame frame = null;
            long deadline = System.currentTimeMillis() + 15_000;
            while (System.currentTimeMillis() < deadline) {
                if (creationError.get() != null) {
                    break;
                }
                OffscreenWebView.Frame candidate = view.getFrame();
                // 等到真正画出页面内容（中心像素变红），而不是首帧的空白
                if (candidate != null && isPageRed(candidate.rgba())) {
                    frame = candidate;
                    break;
                }
                Thread.sleep(200);
            }
            if (creationError.get() != null) {
                throw new AssertionError("offscreen webview creation failed: " + creationError.get());
            }
            assertNotNull(frame, "page content was not captured within 15s");

            Path out = projectRoot().resolve("build/offscreen-smoke.png");
            dumpPng(frame.rgba(), out);
            assertTrue(Files.exists(out));
        }
    }

    /** 测试任务的工作目录可能被构建插件改写；向上找到项目根（含 settings.gradle）。 */
    private static Path projectRoot() {
        Path dir = Path.of(System.getProperty("user.dir"));
        while (dir != null && !Files.exists(dir.resolve("settings.gradle"))) {
            dir = dir.getParent();
        }
        if (dir == null) {
            throw new IllegalStateException("cannot locate project root from " + System.getProperty("user.dir"));
        }
        return dir;
    }

    private static boolean isPageRed(byte[] rgba) {
        int center = ((HEIGHT / 2) * WIDTH + WIDTH / 2) * 4;
        int r = rgba[center] & 0xFF;
        int g = rgba[center + 1] & 0xFF;
        int b = rgba[center + 2] & 0xFF;
        return r > 180 && g < 80 && b < 110;
    }

    private static void dumpPng(byte[] rgba, Path out) throws Exception {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                int idx = (y * WIDTH + x) * 4;
                int argb = ((rgba[idx + 3] & 0xFF) << 24)
                    | ((rgba[idx] & 0xFF) << 16)
                    | ((rgba[idx + 1] & 0xFF) << 8)
                    | (rgba[idx + 2] & 0xFF);
                image.setRGB(x, y, argb);
            }
        }
        ImageIO.write(image, "png", out.toFile());
    }
}
