package io.github.achesmakes.airships.registry;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.entity.BlockEntityType;

import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;

import io.github.achesmakes.airships.AirshipsMod;
import io.github.achesmakes.airships.block.HelmBlockEntity;

public class ModBlockEntities {
    public static final BlockEntityType<HelmBlockEntity> HELM =
            Registry.register(
                    BuiltInRegistries.BLOCK_ENTITY_TYPE,
                    AirshipsMod.id("helm"),
                    FabricBlockEntityTypeBuilder.<HelmBlockEntity>create(
                                    HelmBlockEntity::new, ModBlocks.HELM)
                            .build());

    public static void initialize() {}
}
