package dev.anvilcraft.oxide.ferric;

import com.mojang.logging.LogUtils;
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
    }
}
