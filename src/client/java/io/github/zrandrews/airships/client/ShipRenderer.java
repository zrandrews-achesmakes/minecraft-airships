package io.github.zrandrews.airships.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import io.github.zrandrews.airships.AirshipsMod;
import io.github.zrandrews.airships.ship.ShipBlock;
import io.github.zrandrews.airships.ship.ShipEntity;

/**
 * Draws every block of a ship, the same way vanilla draws falling sand, plus the
 * block-entity models (chests, signs, beds, banners...) that have no plain block model.
 */
public class ShipRenderer extends EntityRenderer<ShipEntity, ShipRenderState> {
	/** One drawable block: its offset from the helm plus a reusable render state. */
	public record CachedBlock(BlockPos local, MovingBlockRenderState state) {
	}

	/**
	 * A block with a block entity. The client-side block entity is rebuilt whenever the block
	 * moves into a different world cell, so lighting and distance checks use where it is now.
	 */
	public static final class CachedBlockEntity {
		final BlockPos local;
		final BlockState state;
		final Optional<CompoundTag> data;
		@Nullable BlockEntity blockEntity;
		boolean unsupported;

		CachedBlockEntity(BlockPos local, BlockState state, Optional<CompoundTag> data) {
			this.local = local;
			this.state = state;
			this.data = data;
		}
	}

	/** A block entity ready to submit this frame. */
	public record BlockEntityToDraw(BlockPos local, BlockEntityRenderState state) {
	}

	/** Everything built once per ship shape (stored on the entity, rebuilt when the shape changes). */
	private record RenderCache(List<CachedBlock> blocks, List<CachedBlockEntity> blockEntities) {
	}

	/** How many block-entity models the last drawn ship submitted (for the client test and debugging). */
	public static volatile int lastBlockEntitiesDrawn;

	public ShipRenderer(EntityRendererProvider.Context context) {
		super(context);
	}

	/** The entity's own hitbox is just the helm, so cull using the whole ship instead. */
	@Override
	protected net.minecraft.world.phys.AABB getBoundingBoxForCulling(ShipEntity ship, float partialTick) {
		return ship.worldBounds();
	}

	@Override
	public ShipRenderState createRenderState() {
		return new ShipRenderState();
	}

	@Override
	public void extractRenderState(ShipEntity ship, ShipRenderState state, float partialTick) {
		super.extractRenderState(ship, state, partialTick);

		if (!(ship.level() instanceof ClientLevel level)) {
			state.blocks = List.of();
			state.blockEntities = List.of();
			return;
		}

		if (!(ship.renderCache instanceof RenderCache)) {
			List<CachedBlock> blocks = new ArrayList<>();
			List<CachedBlockEntity> blockEntities = new ArrayList<>();
			for (ShipBlock block : ship.getBlocks()) {
				if (block.state().getRenderShape() != RenderShape.INVISIBLE) {
					MovingBlockRenderState mbrs = new MovingBlockRenderState();
					mbrs.blockState = block.state();
					mbrs.randomSeedPos = block.pos();
					blocks.add(new CachedBlock(block.pos(), mbrs));
				}
				if (block.state().getBlock() instanceof EntityBlock) {
					blockEntities.add(new CachedBlockEntity(block.pos(), block.state(), block.blockEntity()));
				}
			}
			ship.renderCache = new RenderCache(blocks, blockEntities);
		}

		RenderCache cache = (RenderCache) ship.renderCache;

		// Lighting comes from wherever each block currently is in the world.
		double baseX = ship.getX() - 0.5, baseY = ship.getY(), baseZ = ship.getZ() - 0.5;
		BlockPos center = BlockPos.containing(ship.getX(), ship.getY(), ship.getZ());
		var biome = level.getBiome(center);

		for (CachedBlock block : cache.blocks()) {
			MovingBlockRenderState mbrs = block.state();
			mbrs.blockPos = worldCell(baseX, baseY, baseZ, block.local());
			mbrs.biome = biome;
			mbrs.cardinalLighting = level.cardinalLighting();
			mbrs.lightEngine = level.getLightEngine();
		}

		state.blocks = cache.blocks();
		state.blockEntities = extractBlockEntities(level, cache.blockEntities(), baseX, baseY, baseZ, partialTick);
		state.turnAngle = ship.turnAngle(partialTick);
	}

	private static BlockPos worldCell(double baseX, double baseY, double baseZ, BlockPos local) {
		return BlockPos.containing(baseX + local.getX() + 0.5, baseY + local.getY() + 0.5, baseZ + local.getZ() + 0.5);
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static List<BlockEntityToDraw> extractBlockEntities(ClientLevel level, List<CachedBlockEntity> cached,
			double baseX, double baseY, double baseZ, float partialTick) {
		if (cached.isEmpty()) return List.of();

		Minecraft mc = Minecraft.getInstance();
		BlockEntityRenderDispatcher dispatcher = mc.getBlockEntityRenderDispatcher();
		Vec3 cameraPos = mc.gameRenderer.mainCamera().position();
		List<BlockEntityToDraw> result = new ArrayList<>(cached.size());

		for (CachedBlockEntity entry : cached) {
			if (entry.unsupported) continue;

			BlockPos cell = worldCell(baseX, baseY, baseZ, entry.local);
			if (entry.blockEntity == null || !entry.blockEntity.getBlockPos().equals(cell)) {
				entry.blockEntity = createBlockEntity(level, entry, cell);
				if (entry.blockEntity == null) {
					entry.unsupported = true;
					continue;
				}
			}

			BlockEntityRenderer renderer = dispatcher.getRenderer(entry.blockEntity);
			if (renderer == null) {
				entry.unsupported = true; // drawn by its plain block model only
				continue;
			}

			BlockEntityRenderState renderState = renderer.createRenderState();
			renderer.extractRenderState(entry.blockEntity, renderState, partialTick, cameraPos, null);
			result.add(new BlockEntityToDraw(entry.local, renderState));
		}

		return result;
	}

	private static @Nullable BlockEntity createBlockEntity(ClientLevel level, CachedBlockEntity entry, BlockPos cell) {
		try {
			BlockEntity be = entry.data.isPresent()
					? BlockEntity.loadStatic(cell, entry.state, entry.data.get().copy(), level.registryAccess())
					: ((EntityBlock) entry.state.getBlock()).newBlockEntity(cell, entry.state);
			if (be != null) be.setLevel(level);
			return be;
		} catch (RuntimeException e) {
			AirshipsMod.LOGGER.debug("Can't draw block entity for {} on a ship", entry.state, e);
			return null;
		}
	}

	@Override
	public void submit(ShipRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		// poseStack is at the ship's position (bottom-centre of the helm block).
		// While a turn swings round, draw the new shape rotated back toward the old heading.
		poseStack.pushPose();
		if (state.turnAngle != 0f) poseStack.rotateDegrees(Axis.YP, state.turnAngle);

		for (CachedBlock block : state.blocks) {
			poseStack.pushPose();
			poseStack.translate(block.local().getX() - 0.5, block.local().getY(), block.local().getZ() - 0.5);
			collector.submitMovingBlock(poseStack, block.state(), 0);
			poseStack.popPose();
		}

		if (!state.blockEntities.isEmpty()) {
			BlockEntityRenderDispatcher dispatcher = Minecraft.getInstance().getBlockEntityRenderDispatcher();
			for (BlockEntityToDraw be : state.blockEntities) {
				poseStack.pushPose();
				poseStack.translate(be.local().getX() - 0.5, be.local().getY(), be.local().getZ() - 0.5);
				dispatcher.submit(be.state(), poseStack, collector, camera);
				poseStack.popPose();
			}
		}

		poseStack.popPose();
		lastBlockEntitiesDrawn = state.blockEntities.size();
		super.submit(state, poseStack, collector, camera);
	}
}
