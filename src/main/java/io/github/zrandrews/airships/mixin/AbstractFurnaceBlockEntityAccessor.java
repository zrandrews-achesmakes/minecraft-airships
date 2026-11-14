package io.github.zrandrews.airships.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;

/** Lets a furnace on a flying ship feed its burn/cook progress to the furnace screen (#31). */
@Mixin(AbstractFurnaceBlockEntity.class)
public interface AbstractFurnaceBlockEntityAccessor {
	@Accessor("dataAccess")
	ContainerData airships$getDataAccess();
}
