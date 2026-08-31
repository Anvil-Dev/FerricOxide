package dev.anvilcraft.oxide.ferric.client.display;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * 方块交互的客户端入口（被 common 侧的 {@code WebDisplayBlock} 以客户端分支调用）。
 */
public final class WebDisplayScreens {
    private WebDisplayScreens() {
    }

    /** Shift+右键：打开地址编辑屏。 */
    public static void openEditScreen(BlockPos pos) {
        Minecraft.getInstance().setScreen(new WebDisplayEditScreen(pos));
    }

    /** 右键：打开捕获屏（键鼠转发给大屏的离屏 WebView）。 */
    public static void openCaptureScreen(BlockPos pos) {
        Minecraft.getInstance().setScreen(new WebDisplayCaptureScreen(pos));
    }
}
