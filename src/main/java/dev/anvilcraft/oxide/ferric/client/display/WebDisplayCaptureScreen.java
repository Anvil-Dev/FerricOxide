package dev.anvilcraft.oxide.ferric.client.display;

import dev.anvilcraft.oxide.ferric.display.WebDisplayGroup;
import dev.anvilcraft.oxide.ferric.webui.OffscreenWebView;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

/**
 * 网页显示器捕获屏：打开时不绘制任何内容，把键鼠输入全部转发给离屏 WebView。
 *
 * <p>鼠标位置经相机反投影成世界射线，与大屏正面平面求交得到 UV 再换算为网页像素坐标；
 * 键盘事件映射为 CDP {@code Input.dispatchKeyEvent}，字符输入走 {@code Input.insertText}。
 * ESC 退出捕获；Shift+ESC 作为普通 ESC 转发给网页。
 */
public class WebDisplayCaptureScreen extends Screen {
    private final net.minecraft.core.BlockPos clicked;
    private WebDisplayManager.@Nullable ActiveDisplay display;
    private boolean mouseInside;

    public WebDisplayCaptureScreen(net.minecraft.core.BlockPos clicked) {
        super(Component.translatable("screen.ferric_oxide.web_display.capture"));
        this.clicked = clicked;
    }

    @Override
    protected void init() {
        // 打开时解析一次；此后每帧惰性跟随
        if (this.minecraft != null && this.minecraft.level != null) {
            this.display = WebDisplayManager.resolve(this.minecraft.level, this.clicked);
        }
    }

    @Override
    public boolean isInGameUi() {
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractBackground(
        net.minecraft.client.gui.GuiGraphicsExtractor graphics,
        int mouseX,
        int mouseY,
        float a
    ) {
        // 不可见：不绘制背景
    }

    // ------------------------------------------------------------------
    // 鼠标投影
    // ------------------------------------------------------------------

    private WebDisplayManager.@Nullable ActiveDisplay display() {
        if (this.display == null && this.minecraft != null && this.minecraft.level != null) {
            this.display = WebDisplayManager.resolve(this.minecraft.level, this.clicked);
        }
        return this.display;
    }

    /**
     * 把当前鼠标位置投影到大屏正面，返回 [u, v]（各 0~1）；不在大屏上时返回 null。
     */
    private double @Nullable [] computeUv() {
        WebDisplayManager.ActiveDisplay display = display();
        Minecraft mc = this.minecraft;
        if (display == null || mc == null || display.webView == null) {
            return null;
        }
        Camera camera = mc.gameRenderer.getMainCamera();
        double mx = mc.mouseHandler.xpos();
        double my = mc.mouseHandler.ypos();
        int w = mc.getWindow().getWidth();
        int h = mc.getWindow().getHeight();
        if (w <= 0 || h <= 0) {
            return null;
        }
        float ndcX = (float) (mx * 2.0 / w - 1.0);
        float ndcY = (float) (1.0 - my * 2.0 / h);
        // 视图-旋转-投影矩阵不含平移：NDC 远点反投影得到相机系世界方向
        Matrix4f inverse = camera.getViewRotationProjectionMatrix(new Matrix4f()).invert();
        Vector4f far = inverse.transform(new Vector4f(ndcX, ndcY, 1.0f, 1.0f));
        if (far.w() == 0.0f) {
            return null;
        }
        Vec3 direction = new Vec3(far.x() / far.w(), far.y() / far.w(), far.z() / far.w());

        WebDisplayGroup.Group group = display.group;
        Direction facing = group.facing();
        Vec3[] corners =
            WebDisplayGeometry.worldCorners(group.anchor(), facing, group.width(), group.height());
        Vec3 normal = facing.getUnitVec3();
        Vec3 cameraPos = camera.position();
        double denom = normal.dot(direction);
        if (Math.abs(denom) < 1.0E-6) {
            return null;
        }
        double t = normal.dot(corners[1].subtract(cameraPos)) / denom;
        if (t <= 0) {
            return null;
        }
        Vec3 hit = cameraPos.add(direction.scale(t));
        Vec3 right = WebDisplayGeometry.rightOf(facing).getUnitVec3();
        Vec3 topLeft = corners[2];
        double u = hit.subtract(topLeft).dot(right) / group.width();
        double v = (topLeft.y - hit.y) / group.height();
        if (u < 0 || u > 1 || v < 0 || v > 1) {
            return null;
        }
        return new double[]{u, v};
    }

    private int toPixelX(double u) {
        WebDisplayManager.ActiveDisplay display = display();
        return display == null ? 0 : (int) Math.round(u * display.textureWidth());
    }

    private int toPixelY(double v) {
        WebDisplayManager.ActiveDisplay display = display();
        return display == null ? 0 : (int) Math.round(v * display.textureHeight());
    }

    @Override
    public void mouseMoved(double x, double y) {
        forwardMouseMove();
    }

    private void forwardMouseMove() {
        WebDisplayManager.ActiveDisplay display = display();
        if (display == null || display.webView == null) {
            return;
        }
        double[] uv = computeUv();
        if (uv == null) {
            if (this.mouseInside) {
                this.mouseInside = false;
                display.webView.mouseLeave(0, 0);
            }
            return;
        }
        this.mouseInside = true;
        display.webView.mouseMove(toPixelX(uv[0]), toPixelY(uv[1]));
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        WebDisplayManager.ActiveDisplay display = display();
        double[] uv = computeUv();
        if (display != null && display.webView != null && uv != null) {
            display.webView.mouseButton(toPixelX(uv[0]), toPixelY(uv[1]), event.button(), true);
        }
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        WebDisplayManager.ActiveDisplay display = display();
        double[] uv = computeUv();
        if (display != null && display.webView != null && uv != null) {
            display.webView.mouseButton(toPixelX(uv[0]), toPixelY(uv[1]), event.button(), false);
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        WebDisplayManager.ActiveDisplay display = display();
        double[] uv = computeUv();
        if (display != null && display.webView != null && uv != null) {
            // CDP deltaY 向下为正；MC 滚轮向上为正。每格约 120 像素
            display.webView.mouseWheel(
                toPixelX(uv[0]), toPixelY(uv[1]), -scrollX * 120.0, -scrollY * 120.0);
        }
        return true;
    }

    // ------------------------------------------------------------------
    // 键盘转发
    // ------------------------------------------------------------------

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.isEscape()) {
            if (event.hasShiftDown()) {
                // Shift+ESC 映射为普通 ESC 转发给网页
                forwardKey("rawKeyDown", GLFW.GLFW_KEY_ESCAPE, 0);
                forwardKey("keyUp", GLFW.GLFW_KEY_ESCAPE, 0);
            } else {
                // ESC 退出捕获
                this.onClose();
            }
            return true;
        }
        forwardKey(event.hasShiftDown() || isCharacterKey(event.key()) ? "keyDown" : "rawKeyDown",
            event.key(), cdpModifiers(event));
        return true;
    }

    @Override
    public boolean keyReleased(KeyEvent event) {
        forwardKey("keyUp", event.key(), cdpModifiers(event));
        return true;
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        WebDisplayManager.ActiveDisplay display = display();
        if (display != null && display.webView != null) {
            display.webView.insertText(event.codepointAsString());
        }
        return true;
    }

    private void forwardKey(String type, int glfwKey, int modifiers) {
        WebDisplayManager.ActiveDisplay display = display();
        if (display == null || display.webView == null) {
            return;
        }
        KeyMapping mapping = KeyMapping.of(glfwKey);
        if (mapping == null) {
            return;
        }
        display.webView.keyEvent(type, mapping.vk, mapping.key, mapping.code, null, modifiers);
    }

    private static boolean isCharacterKey(int glfwKey) {
        return glfwKey >= GLFW.GLFW_KEY_SPACE && glfwKey <= GLFW.GLFW_KEY_Z;
    }

    /** CDP 修饰键位掩码：Alt=1 Ctrl=2 Meta=4 Shift=8。 */
    private static int cdpModifiers(KeyEvent event) {
        int modifiers = 0;
        if (event.hasAltDown()) {
            modifiers |= 1;
        }
        if (event.hasControlDown()) {
            modifiers |= 2;
        }
        if (event.hasShiftDown()) {
            modifiers |= 8;
        }
        return modifiers;
    }

    // ------------------------------------------------------------------
    // GLFW -> Windows VK / CDP 映射
    // ------------------------------------------------------------------

    private record KeyMapping(int vk, String key, String code) {
        static @Nullable KeyMapping of(int glfwKey) {
            // 字母/数字：GLFW 键码与 ASCII、Windows VK 一致
            if (glfwKey >= GLFW.GLFW_KEY_A && glfwKey <= GLFW.GLFW_KEY_Z) {
                char c = (char) glfwKey;
                return new KeyMapping(glfwKey, String.valueOf(Character.toLowerCase(c)), "Key" + c);
            }
            if (glfwKey >= GLFW.GLFW_KEY_0 && glfwKey <= GLFW.GLFW_KEY_9) {
                return new KeyMapping(glfwKey, String.valueOf((char) glfwKey), "Digit" + (char) glfwKey);
            }
            return switch (glfwKey) {
                case GLFW.GLFW_KEY_SPACE -> new KeyMapping(0x20, " ", "Space");
                case GLFW.GLFW_KEY_ENTER -> new KeyMapping(0x0D, "Enter", "Enter");
                case GLFW.GLFW_KEY_TAB -> new KeyMapping(0x09, "Tab", "Tab");
                case GLFW.GLFW_KEY_BACKSPACE -> new KeyMapping(0x08, "Backspace", "Backspace");
                case GLFW.GLFW_KEY_DELETE -> new KeyMapping(0x2E, "Delete", "Delete");
                case GLFW.GLFW_KEY_ESCAPE -> new KeyMapping(0x1B, "Escape", "Escape");
                case GLFW.GLFW_KEY_LEFT -> new KeyMapping(0x25, "ArrowLeft", "ArrowLeft");
                case GLFW.GLFW_KEY_UP -> new KeyMapping(0x26, "ArrowUp", "ArrowUp");
                case GLFW.GLFW_KEY_RIGHT -> new KeyMapping(0x27, "ArrowRight", "ArrowRight");
                case GLFW.GLFW_KEY_DOWN -> new KeyMapping(0x28, "ArrowDown", "ArrowDown");
                case GLFW.GLFW_KEY_HOME -> new KeyMapping(0x24, "Home", "Home");
                case GLFW.GLFW_KEY_END -> new KeyMapping(0x23, "End", "End");
                case GLFW.GLFW_KEY_PAGE_UP -> new KeyMapping(0x21, "PageUp", "PageUp");
                case GLFW.GLFW_KEY_PAGE_DOWN -> new KeyMapping(0x22, "PageDown", "PageDown");
                case GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_KEY_RIGHT_SHIFT ->
                    new KeyMapping(0x10, "Shift", "ShiftLeft");
                case GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_RIGHT_CONTROL ->
                    new KeyMapping(0x11, "Control", "ControlLeft");
                case GLFW.GLFW_KEY_LEFT_ALT, GLFW.GLFW_KEY_RIGHT_ALT ->
                    new KeyMapping(0x12, "Alt", "AltLeft");
                default -> null;
            };
        }
    }
}
