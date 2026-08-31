package dev.anvilcraft.oxide.ferric.network;

import dev.anvilcraft.oxide.ferric.display.WebDisplayGroups;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 网络包注册与服务端处理。
 */
public final class FerricNetworking {
    private FerricNetworking() {
    }

    /** 在模组构造时调用。 */
    public static void register(IEventBus modEventBus) {
        modEventBus.addListener(RegisterPayloadHandlersEvent.class, event -> event.registrar("1")
            .playToServer(
                SetWebDisplayUrlPayload.TYPE,
                SetWebDisplayUrlPayload.STREAM_CODEC,
                FerricNetworking::handleSetUrl
            ));
    }

    private static void handleSetUrl(SetWebDisplayUrlPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Player player = context.player();
            Level level = player.level();
            BlockPos center = payload.clicked();
            // 基本校验：目标必须是网页显示器且在玩家可及范围内
            if (!(level.getBlockState(center).getBlock()
                instanceof dev.anvilcraft.oxide.ferric.display.WebDisplayBlock)) {
                return;
            }
            if (player.distanceToSqr(center.getX() + 0.5, center.getY() + 0.5, center.getZ() + 0.5) > 64 * 64) {
                return;
            }
            String url = payload.url();
            if (url == null || url.isBlank() || url.length() > 512) {
                return;
            }
            WebDisplayGroups.applyUrlEdit(level, center, url.trim());
        });
    }
}
