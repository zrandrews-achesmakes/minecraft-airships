package io.github.zrandrews.airships;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Server-side settings, stored in config/airships.json.
 * Edit the file and restart the game/server to apply.
 */
public class AirshipsConfig {
	/** Max blocks in one ship. 2048 matches Create's default maxBlocksMoved. */
	public int maxShipBlocks = 2048;

	/** How many blocks of ship one Lift Block can carry. */
	public int liftPerLiftBlock = 48;

	/** Top horizontal speed in blocks per tick (20 ticks = 1 second). */
	public double horizontalSpeed = 0.35;

	/** Top vertical speed in blocks per tick. */
	public double verticalSpeed = 0.2;

	/** Fuel burned per tick while the ship is moving (coal = 1600 fuel). */
	public double fuelPerMovingTick = 1.0;

	/** Extra fuel burned per tick for every block in the ship, while moving. */
	public double fuelPerBlockPerMovingTick = 1.0 / 256.0;

	/** Max fuel a ship can hold. */
	public int maxFuel = 64 * 1600;

	/** If true, assembly won't grab natural ground (dirt, stone, sand...) so ships can be built on the ground. */
	public boolean ignoreTerrainBlocks = true;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static AirshipsConfig instance = new AirshipsConfig();

	public static AirshipsConfig get() {
		return instance;
	}

	public static void load() {
		Path path = FabricLoader.getInstance().getConfigDir().resolve("airships.json");

		try {
			if (Files.exists(path)) {
				try (Reader reader = Files.newBufferedReader(path)) {
					AirshipsConfig loaded = GSON.fromJson(reader, AirshipsConfig.class);
					if (loaded != null) instance = loaded;
				}
			}

			// Always write back so new options appear in the file.
			try (Writer writer = Files.newBufferedWriter(path)) {
				GSON.toJson(instance, writer);
			}
		} catch (IOException | RuntimeException e) {
			AirshipsMod.LOGGER.error("Could not read config/airships.json, using defaults", e);
		}
	}
}
