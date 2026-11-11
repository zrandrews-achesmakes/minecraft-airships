package io.github.zrandrews.airships.registry;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

import io.github.zrandrews.airships.AirshipsMod;
import io.github.zrandrews.airships.ship.ShipEntity;

public class ModEntities {
	private static final ResourceKey<EntityType<?>> SHIP_KEY = ResourceKey.create(Registries.ENTITY_TYPE, AirshipsMod.id("ship"));

	/**
	 * The ship entity sits on the Helm block. Its own hitbox is just that one block
	 * (so you click the helm to use it); the rest of the ship is handled by ShipEntity itself.
	 */
	public static final EntityType<ShipEntity> SHIP = Registry.register(
			BuiltInRegistries.ENTITY_TYPE,
			SHIP_KEY,
			EntityType.Builder.<ShipEntity>of(ShipEntity::new, MobCategory.MISC)
					.sized(1.0f, 1.0f)
					.clientTrackingRange(10)
					.updateInterval(1)
					.build(SHIP_KEY)
	);

	public static void initialize() {
	}
}
