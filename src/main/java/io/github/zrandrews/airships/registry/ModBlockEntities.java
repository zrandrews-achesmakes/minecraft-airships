package io.github.zrandrews.airships.registry;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.entity.BlockEntityType;

import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;

import io.github.zrandrews.airships.AirshipsMod;
import io.github.zrandrews.airships.block.HelmBlockEntity;

public class ModBlockEntities {
	public static final BlockEntityType<HelmBlockEntity> HELM = Registry.register(
			BuiltInRegistries.BLOCK_ENTITY_TYPE,
			AirshipsMod.id("helm"),
			FabricBlockEntityTypeBuilder.<HelmBlockEntity>create(HelmBlockEntity::new, ModBlocks.HELM).build()
	);

	public static void initialize() {
	}
}
