package dev.anvilcraft.oxide.ferric;

import com.mojang.logging.LogUtils;
import dev.anvilcraft.oxide.ferric.display.ModDisplay;
import dev.anvilcraft.oxide.ferric.network.FerricNetworking;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

@Mod(FerricOxide.MODID)
public class FerricOxide {
    public static final String MODID = "ferric_oxide";
    public static final Logger LOGGER = LogUtils.getLogger();

    public FerricOxide(IEventBus modEventBus, ModContainer modContainer) {
        LOGGER.info("FerricOxide initializing");
        ModDisplay.register(modEventBus);
        FerricNetworking.register(modEventBus);
        dev.anvilcraft.oxide.ferric.data.FerricOxideData.register(modEventBus);
    }
}
