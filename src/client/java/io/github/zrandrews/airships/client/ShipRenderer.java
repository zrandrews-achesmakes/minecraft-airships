package io.github.zrandrews.airships.client;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.RenderShape;

import io.github.zrandrews.airships.ship.ShipBlock;
import io.github.zrandrews.airships.ship.ShipEntity;

/**
 * Draws every block of a ship, the same way vanilla draws falling sand.
 */
public class ShipRenderer extends EntityRenderer<ShipEntity, ShipRenderState> {
	/** One drawable block: its offset from the helm plus a reusable render state. */
	public record CachedBlock(BlockPos local, MovingBlockRenderState state) {
	}

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
	@SuppressWarnings("unchecked")
	public void extractRenderState(ShipEntity ship, ShipRenderState state, float partialTick) {
		super.extractRenderState(ship, state, partialTick);

		if (!(ship.level() instanceof ClientLevel level)) {
			state.blocks = List.of();
			return;
		}

		if (!(ship.renderCache instanceof List<?>)) {
			List<CachedBlock> cache = new ArrayList<>();
			for (ShipBlock block : ship.getBlocks()) {
				if (block.state().getRenderShape() == RenderShape.INVISIBLE) continue;

				MovingBlockRenderState mbrs = new MovingBlockRenderState();
				mbrs.blockState = block.state();
				mbrs.randomSeedPos = block.pos();
				cache.add(new CachedBlock(block.pos(), mbrs));
			}
			ship.renderCache = cache;
		}

		List<CachedBlock> cache = (List<CachedBlock>) ship.renderCache;

		// Lighting comes from wherever each block currently is in the world.
		double baseX = ship.getX() - 0.5, baseY = ship.getY(), baseZ = ship.getZ() - 0.5;
		BlockPos center = BlockPos.containing(ship.getX(), ship.getY(), ship.getZ());
		var biome = level.getBiome(center);

		for (CachedBlock block : cache) {
			MovingBlockRenderState mbrs = block.state();
			mbrs.blockPos = BlockPos.containing(baseX + block.local().getX() + 0.5, baseY + block.local().getY() + 0.5, baseZ + block.local().getZ() + 0.5);
			mbrs.biome = biome;
			mbrs.cardinalLighting = level.cardinalLighting();
			mbrs.lightEngine = level.getLightEngine();
		}

		state.blocks = cache;
	}

	@Override
	public void submit(ShipRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		// poseStack is at the ship's position (bottom-centre of the helm block).
		for (CachedBlock block : state.blocks) {
			poseStack.pushPose();
			poseStack.translate(block.local().getX() - 0.5, block.local().getY(), block.local().getZ() - 0.5);
			collector.submitMovingBlock(poseStack, block.state(), 0);
			poseStack.popPose();
		}

		super.submit(state, poseStack, collector, camera);
	}
}
