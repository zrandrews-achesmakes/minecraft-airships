package io.github.zrandrews.airships.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;

/** Lets a brewing stand on a flying ship feed its brew/fuel progress to the brewing screen (#31). */
@Mixin(BrewingStandBlockEntity.class)
public interface BrewingStandBlockEntityAccessor {
	@Accessor("dataAccess")
	ContainerData airships$getDataAccess();
}
