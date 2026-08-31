package dev.anvilcraft.oxide.ferric.display;

import dev.anvilcraft.oxide.ferric.FerricOxide;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 网页显示器相关注册项：方块、方块物品、方块实体。
 */
public final class ModDisplay {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(FerricOxide.MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(FerricOxide.MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
        DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, FerricOxide.MODID);

    /** 网页显示器方块。 */
    public static final DeferredBlock<WebDisplayBlock> WEB_DISPLAY_BLOCK = BLOCKS.registerBlock(
        "web_display",
        WebDisplayBlock::new,
        () -> BlockBehaviour.Properties.ofFullCopy(Blocks.WHITE_CONCRETE)
    );

    /** 网页显示器物品。 */
    public static final DeferredItem<BlockItem> WEB_DISPLAY_ITEM =
        ITEMS.registerSimpleBlockItem(WEB_DISPLAY_BLOCK);

    /** 网页显示器方块实体类型。 */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<WebDisplayBlockEntity>> WEB_DISPLAY_BLOCK_ENTITY =
        BLOCK_ENTITIES.register(
            "web_display",
            () -> new BlockEntityType<>(WebDisplayBlockEntity::new, WEB_DISPLAY_BLOCK.get())
        );

    private ModDisplay() {
    }

    /**
     * 在模组构造时调用：注册全部注册表，并把显示器加入原版“功能方块”创造模式页。
     */
    public static void register(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        BLOCK_ENTITIES.register(modEventBus);
        // BuildCreativeModeTabContentsEvent 是 mod-bus 事件
        modEventBus.addListener(
            BuildCreativeModeTabContentsEvent.class,
            event -> {
                if (event.getTabKey() == net.minecraft.world.item.CreativeModeTabs.FUNCTIONAL_BLOCKS) {
                    event.accept(WEB_DISPLAY_ITEM);
                }
            }
        );
    }
}
