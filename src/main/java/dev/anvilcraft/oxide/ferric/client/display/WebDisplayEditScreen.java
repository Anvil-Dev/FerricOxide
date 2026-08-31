package dev.anvilcraft.oxide.ferric.client.display;

import dev.anvilcraft.oxide.ferric.display.WebDisplayBlockEntity;
import dev.anvilcraft.oxide.ferric.display.WebDisplayGroup;
import dev.anvilcraft.oxide.ferric.display.WebDisplayGroups;
import dev.anvilcraft.oxide.ferric.network.SetWebDisplayUrlPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/**
 * 网页显示器地址编辑屏：Shift+右键打开。编辑任意成员等价于编辑整个大屏
 * （服务端会以锚点为准写入并广播）。
 */
public class WebDisplayEditScreen extends Screen {
    private final BlockPos clicked;
    private EditBox urlBox;

    public WebDisplayEditScreen(BlockPos clicked) {
        super(Component.translatable("screen.ferric_oxide.web_display.edit"));
        this.clicked = clicked;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int centerY = this.height / 2;
        this.urlBox = new EditBox(
            this.font, centerX - 150, centerY - 12, 300, 20,
            Component.translatable("screen.ferric_oxide.web_display.url")
        );
        this.urlBox.setMaxLength(512);
        this.urlBox.setValue(currentUrl());
        this.urlBox.setFocused(true);
        this.addRenderableWidget(this.urlBox);
        this.addRenderableWidget(Button.builder(
                Component.translatable("gui.done"), button -> saveAndClose())
            .bounds(centerX - 152, centerY + 16, 150, 20)
            .build());
        this.addRenderableWidget(Button.builder(
                Component.translatable("gui.cancel"), button -> this.onClose())
            .bounds(centerX + 2, centerY + 16, 150, 20)
            .build());
    }

    private String currentUrl() {
        Minecraft mc = this.minecraft;
        if (mc != null && mc.level != null) {
            WebDisplayGroup.Group group = WebDisplayGroups.computeAt(mc.level, this.clicked);
            BlockPos anchor = group != null ? group.anchor() : this.clicked;
            if (mc.level.getBlockEntity(anchor) instanceof WebDisplayBlockEntity entity) {
                return entity.getUrl();
            }
        }
        return WebDisplayBlockEntity.DEFAULT_URL;
    }

    private void saveAndClose() {
        String url = this.urlBox.getValue().trim();
        if (!url.isEmpty()) {
            ClientPacketDistributor.sendToServer(new SetWebDisplayUrlPayload(this.clicked, url));
        }
        this.onClose();
    }
}
