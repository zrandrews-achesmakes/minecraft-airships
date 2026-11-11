package io.github.zrandrews.airships.ship;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import io.github.zrandrews.airships.AirshipsConfig;
import io.github.zrandrews.airships.block.HelmBlockEntity;
import io.github.zrandrews.airships.registry.ModBlocks;
import io.github.zrandrews.airships.registry.ModEntities;

/**
 * Turns blocks in the world into a ship, and back again.
 */
public final class ShipAssembler {
	/** Removing blocks: tell clients, but no drops, no neighbour updates, no chest spilling. */
	static final int REMOVE_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_SKIP_ALL_SIDEEFFECTS;
	/** Placing blocks back: tell clients, don't let neighbours pop off while we rebuild. */
	static final int PLACE_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;

	private ShipAssembler() {
	}

	public enum Status { LAUNCHED, UNMOVABLE, TOO_BIG, NOT_ENOUGH_LIFT }

	/** What happened when we tried to launch. {@code ship} is only set when launched. */
	public record Result(Status status, Component message, @Nullable ShipEntity ship) {
		public boolean launched() {
			return status == Status.LAUNCHED;
		}
	}

	/** Launch from a Helm right-click: build the ship, tell the player, put them at the wheel. */
	public static void tryAssemble(ServerLevel level, BlockPos helmPos, Player player) {
		Result result = assemble(level, helmPos);
		player.sendOverlayMessage(result.message());

		if (result.launched() && result.ship() != null) {
			player.startRiding(result.ship(), true, true);
		}
	}

	/** Turns the blocks connected to the Helm at {@code helmPos} into a ship. Used by the Helm and by tests. */
	public static Result assemble(ServerLevel level, BlockPos helmPos) {
		AirshipsConfig cfg = AirshipsConfig.get();

		// 1. Find every block connected to the helm (face to face).
		Set<BlockPos> found = new HashSet<>();
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		found.add(helmPos.immutable());
		queue.add(helmPos.immutable());

		while (!queue.isEmpty()) {
			BlockPos current = queue.poll();

			for (Direction dir : Direction.values()) {
				BlockPos next = current.relative(dir);
				if (found.contains(next)) continue;

				BlockState state = level.getBlockState(next);
				if (!isShipBlock(level, next, state, cfg)) continue;

				if (state.getDestroySpeed(level, next) < 0) {
					return new Result(Status.UNMOVABLE, Component.translatable("message.airships.unmovable", state.getBlock().getName()), null);
				}

				found.add(next);
				if (found.size() > cfg.maxShipBlocks) {
					return new Result(Status.TOO_BIG, Component.translatable("message.airships.too_big", cfg.maxShipBlocks), null);
				}

				queue.add(next);
			}
		}

		// 2. Check lift.
		int lifters = 0;
		for (BlockPos pos : found) {
			if (level.getBlockState(pos).is(ModBlocks.LIFT_BLOCK)) lifters++;
		}

		int mass = found.size();
		int lift = lifters * cfg.liftPerLiftBlock;

		if (lift < mass) {
			int needed = (int) Math.ceil((mass - lift) / (double) cfg.liftPerLiftBlock);
			return new Result(Status.NOT_ENOUGH_LIFT, Component.translatable("message.airships.not_enough_lift", mass, lift, needed), null);
		}

		// 3. Copy blocks (and chest contents etc.) into the ship.
		List<ShipBlock> blocks = new ArrayList<>(found.size());
		int fuel = 0;

		for (BlockPos pos : found) {
			BlockState state = level.getBlockState(pos);
			Optional<CompoundTag> beData = Optional.empty();
			BlockEntity be = level.getBlockEntity(pos);

			if (be instanceof HelmBlockEntity helm && pos.equals(helmPos)) {
				fuel = helm.getFuel();
				helm.setFuel(0);
			}

			if (be != null) {
				beData = Optional.of(be.saveWithFullMetadata(level.registryAccess()));
			}

			blocks.add(new ShipBlock(pos.subtract(helmPos), state, beData));
		}

		// 4. Spawn the ship entity on top of the helm block position.
		ShipEntity ship = new ShipEntity(ModEntities.SHIP, level);
		ship.setPos(helmPos.getX() + 0.5, helmPos.getY(), helmPos.getZ() + 0.5);
		ship.initShip(blocks, fuel);

		// 5. Remove the blocks from the world. Fragile blocks (torches, carpets, doors...)
		//    first, so nothing pops off as an item.
		List<BlockPos> order = new ArrayList<>(found);
		order.sort((a, b) -> Boolean.compare(isSturdy(level, a), isSturdy(level, b)));

		for (BlockPos pos : order) {
			level.removeBlockEntity(pos);
			level.setBlock(pos, Blocks.AIR.defaultBlockState(), REMOVE_FLAGS);
		}

		level.addFreshEntity(ship);
		return new Result(Status.LAUNCHED, Component.translatable("message.airships.launched", mass, lift), ship);
	}

	private static boolean isSturdy(ServerLevel level, BlockPos pos) {
		return level.getBlockState(pos).isCollisionShapeFullBlock(level, pos);
	}

	static boolean isShipBlock(ServerLevel level, BlockPos pos, BlockState state, AirshipsConfig cfg) {
		if (state.isAir()) return false;
		if (state.getBlock() instanceof LiquidBlock) return false;

		if (cfg.ignoreTerrainBlocks && isTerrain(state)) return false;

		return true;
	}

	/** Natural ground the assembler won't pick up, so ships can be built on the ground. */
	static boolean isTerrain(BlockState state) {
		return state.is(BlockTags.SUBSTRATE_OVERWORLD) // dirt, grass, podzol, mycelium, mud, moss
				|| state.is(BlockTags.SAND)
				|| state.is(BlockTags.BASE_STONE_OVERWORLD)
				|| state.is(BlockTags.BASE_STONE_NETHER)
				|| state.is(Blocks.GRAVEL)
				|| state.is(Blocks.BEDROCK)
				|| state.is(Blocks.SNOW)
				|| state.is(Blocks.SHORT_GRASS)
				|| state.is(Blocks.TALL_GRASS)
				|| state.is(Blocks.CLAY)
				|| state.is(Blocks.DIRT_PATH)
				|| state.is(Blocks.FARMLAND)
				|| state.is(Blocks.END_STONE);
	}
}
