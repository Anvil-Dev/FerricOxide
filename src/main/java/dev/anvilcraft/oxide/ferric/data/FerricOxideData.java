package dev.anvilcraft.oxide.ferric.data;

import dev.anvilcraft.oxide.ferric.FerricOxide;
import dev.anvilcraft.oxide.ferric.display.ModDisplay;
import net.minecraft.client.data.models.BlockModelGenerators;
import net.minecraft.client.data.models.ItemModelGenerators;
import net.minecraft.client.data.models.ModelProvider;
import net.minecraft.client.data.models.model.ModelLocationUtils;
import net.minecraft.client.data.models.model.TextureMapping;
import net.minecraft.client.data.models.model.TextureSlot;
import net.minecraft.client.data.models.model.TexturedModel;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.DataGenerator;
import net.minecraft.data.loot.BlockLootSubProvider;
import net.minecraft.data.loot.LootTableProvider;
import net.minecraft.resources.Identifier;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.data.LanguageProvider;
import net.neoforged.neoforge.data.event.GatherDataEvent;

import java.util.List;
import java.util.Set;

/**
 * 数据生成入口（{@code runData}）：方块模型/方块状态、语言文件、掉落表。
 */
public final class FerricOxideData {
    private FerricOxideData() {
    }

    /** 在模组构造时调用。 */
    public static void register(IEventBus modEventBus) {
        modEventBus.addListener(GatherDataEvent.Client.class, FerricOxideData::gatherClient);
    }

    private static void gatherClient(GatherDataEvent.Client event) {
        DataGenerator generator = event.getGenerator();
        generator.addProvider(true, new FerricModelProvider(generator.getPackOutput()));
        generator.addProvider(true, new FerricLanguageProvider(generator.getPackOutput()));
        // 掉落表等服务端数据也在这里注册：clientData/serverData 两个 run 共用同一输出目录时，
        // HashCache 的过期清理会互相删除对方的产物，因此只保留 clientData 一个入口
        generator.addProvider(true, new LootTableProvider(
            generator.getPackOutput(),
            Set.of(),
            List.of(new LootTableProvider.SubProviderEntry(FerricBlockLoot::new, LootContextParamSets.BLOCK)),
            event.getLookupProvider()
        ));
    }

    /**
     * 方块模型：全部六个面（含正面底层）使用白色混凝土纹理，方块状态带水平朝向变体。
     */
    private static final class FerricModelProvider extends ModelProvider {
        private FerricModelProvider(net.minecraft.data.PackOutput output) {
            super(output, FerricOxide.MODID);
        }

        @Override
        protected void registerModels(BlockModelGenerators blockModels, ItemModelGenerators itemModels) {
            Block block = ModDisplay.WEB_DISPLAY_BLOCK.get();
            TexturedModel.Provider whiteConcrete = TexturedModel.CUBE.updateTexture(
                mapping -> mapping.put(
                    TextureSlot.ALL,
                    new Material(Identifier.withDefaultNamespace("block/white_concrete"))
                )
            );
            blockModels.createHorizontallyRotatedBlock(block, whiteConcrete);
            blockModels.registerSimpleItemModel(block, ModelLocationUtils.getModelLocation(block));
        }
    }

    /** 掉落表：掉落自身。 */
    private static final class FerricBlockLoot extends BlockLootSubProvider {
        private FerricBlockLoot(HolderLookup.Provider registries) {
            super(Set.of(), FeatureFlags.REGISTRY.allFlags(), registries);
        }

        @Override
        protected void generate() {
            this.dropSelf(ModDisplay.WEB_DISPLAY_BLOCK.get());
        }

        @Override
        protected Iterable<Block> getKnownBlocks() {
            return ModDisplay.BLOCKS.getEntries().stream().map(holder -> (Block) holder.value()).toList();
        }
    }

    /** 语言文件（en_us)。 */
    private static final class FerricLanguageProvider extends LanguageProvider {
        private FerricLanguageProvider(net.minecraft.data.PackOutput output) {
            super(output, FerricOxide.MODID, "en_us");
        }

        @Override
        protected void addTranslations() {
            this.add(ModDisplay.WEB_DISPLAY_BLOCK.get(), "Web Display");
            this.add("screen.ferric_oxide.web_display.edit", "Edit Web Display URL");
            this.add("screen.ferric_oxide.web_display.url", "Web page URL");
            this.add("screen.ferric_oxide.web_display.capture", "Web Display");
            // 既有演示键
            this.add("itemGroup.ferric_oxide", "Example Mod Tab");
            this.add("block.ferric_oxide.example_block", "Example Block");
            this.add("item.ferric_oxide.example_item", "Example Item");
            this.add("key.ferric_oxide.open_webui", "Open FerricOxide Web UI");
        }
    }
}
