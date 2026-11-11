package io.github.zrandrews.airships;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.api.ModInitializer;
import net.minecraft.resources.Identifier;

import io.github.zrandrews.airships.network.AirshipsNetworking;
import io.github.zrandrews.airships.registry.ModBlockEntities;
import io.github.zrandrews.airships.registry.ModBlocks;
import io.github.zrandrews.airships.registry.ModEntities;

public class AirshipsMod implements ModInitializer {
	public static final String MOD_ID = "airships";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		AirshipsConfig.load();
		ModBlocks.initialize();
		ModBlockEntities.initialize();
		ModEntities.initialize();
		AirshipsNetworking.initialize();
		LOGGER.info("Airships loaded (max ship size {} blocks)", AirshipsConfig.get().maxShipBlocks);
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
