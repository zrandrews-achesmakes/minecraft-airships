package io.github.zrandrews.airships.mixin;

import java.util.List;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import io.github.zrandrews.airships.ship.ShipCollision;

/**
 * When any entity moves, vanilla collects the solid shapes of nearby entities
 * (boats, shulkers...). We add the blocks of nearby ships to that list, so
 * players and mobs can walk on decks and bump into hulls.
 */
@Mixin(Entity.class)
public abstract class EntityCollisionMixin {
	@ModifyExpressionValue(
			method = "collide",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/world/level/Level;getEntityCollisions(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;)Ljava/util/List;"
			)
	)
	private List<VoxelShape> airships$addShipCollisions(List<VoxelShape> original, @Local(argsOnly = true) Vec3 movement) {
		Entity self = (Entity) (Object) this;
		// Same area vanilla searches: the move plus room to step up.
		AABB area = self.getBoundingBox().expandTowards(movement).expandTowards(0.0, self.maxUpStep(), 0.0).inflate(1.0E-7);
		return ShipCollision.addShipShapes(self.level(), self, area, original);
	}
}
