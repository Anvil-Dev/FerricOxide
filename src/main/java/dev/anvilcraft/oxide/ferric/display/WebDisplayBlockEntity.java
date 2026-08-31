package dev.anvilcraft.oxide.ferric.display;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/**
 * 网页显示器方块实体：存储网页地址。
 *
 * <p>大屏组合中所有成员的编辑共享同一份地址，数据以锚点（观察者视角最右下）的方块实体
 * 为准；服务端在组变化时会把锚点地址广播到所有成员，保证锚点被破坏后地址不丢失。
 */
public class WebDisplayBlockEntity extends BlockEntity {
    /** 默认网页地址。 */
    public static final String DEFAULT_URL = "https://space.bilibili.com/430207683";

    private String url = DEFAULT_URL;

    public WebDisplayBlockEntity(BlockPos pos, BlockState blockState) {
        super(ModDisplay.WEB_DISPLAY_BLOCK_ENTITY.get(), pos, blockState);
    }

    public String getUrl() {
        return this.url;
    }

    /**
     * 直接写入地址（不做组传播，组传播由 {@link WebDisplayGroups} 负责）。
     */
    public void setUrl(String url) {
        this.url = url;
        this.setChanged();
    }

    /** 写入地址并同步到客户端。 */
    public void setUrlAndSync(String url) {
        setUrl(url);
        if (this.level != null && !this.level.isClientSide()) {
            this.level.sendBlockUpdated(this.worldPosition, this.getBlockState(), this.getBlockState(), 3);
        }
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        this.url = input.getStringOr("url", DEFAULT_URL);
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putString("url", this.url);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putString("url", this.url);
        return tag;
    }

    @Override
    public @Nullable Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
