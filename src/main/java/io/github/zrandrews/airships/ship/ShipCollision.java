package io.github.zrandrews.airships.ship;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Gives entities something solid to stand on: the blocks of nearby ships. */
public final class ShipCollision {
	/** Safety cap on how many ship cells we scan for one entity move. */
	private static final int MAX_CELLS = 4096;

	private ShipCollision() {
	}

	public static List<VoxelShape> addShipShapes(Level level, @Nullable Entity entity, AABB area, List<VoxelShape> original) {
		List<ShipEntity> ships = ShipTracker.ships(level);
		if (ships.isEmpty()) return original;

		List<VoxelShape> result = null;

		for (ShipEntity ship : ships) {
			if (entity == ship || (entity != null && entity.getVehicle() == ship)) continue;
			if (!ship.hasBlocks()) continue;
			if (!ship.worldBounds().intersects(area)) continue;

			double ox = ship.getX() - 0.5;
			double oy = ship.getY();
			double oz = ship.getZ() - 0.5;

			int x0 = (int) Math.floor(area.minX - ox), x1 = (int) Math.floor(area.maxX - ox);
			int y0 = (int) Math.floor(area.minY - oy), y1 = (int) Math.floor(area.maxY - oy);
			int z0 = (int) Math.floor(area.minZ - oz), z1 = (int) Math.floor(area.maxZ - oz);

			long cells = (long) (x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1);
			if (cells > MAX_CELLS) continue;

			BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

			for (int x = x0; x <= x1; x++) {
				for (int y = y0; y <= y1; y++) {
					for (int z = z0; z <= z1; z++) {
						BlockState state = ship.getLocalState(cursor.set(x, y, z));
						if (state == null) continue;

						VoxelShape shape = state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
						if (shape.isEmpty()) continue;

						if (result == null) result = new ArrayList<>(original);

						for (AABB box : shape.toAabbs()) {
							result.add(Shapes.create(box.move(ox + x, oy + y, oz + z)));
						}
					}
				}
			}
		}

		return result == null ? original : result;
	}
}
