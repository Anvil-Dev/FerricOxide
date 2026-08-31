package dev.anvilcraft.oxide.ferric.network;

import dev.anvilcraft.oxide.ferric.FerricOxide;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 编辑网页显示器地址的请求（客户端 → 服务端）。携带被点击的方块位置，
 * 由服务端自行重算组合与锚点，避免客户端伪造锚点。
 */
public record SetWebDisplayUrlPayload(BlockPos clicked, String url) implements CustomPacketPayload {
    public static final Type<SetWebDisplayUrlPayload> TYPE =
        new Type<>(Identifier.fromNamespaceAndPath(FerricOxide.MODID, "set_web_display_url"));

    public static final StreamCodec<FriendlyByteBuf, SetWebDisplayUrlPayload> STREAM_CODEC = CustomPacketPayload.codec(
        (payload, buf) -> {
            buf.writeBlockPos(payload.clicked());
            buf.writeUtf(payload.url());
        },
        buf -> new SetWebDisplayUrlPayload(buf.readBlockPos(), buf.readUtf())
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
