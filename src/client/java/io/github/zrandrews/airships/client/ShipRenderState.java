package io.github.zrandrews.airships.client;

import java.util.List;

import net.minecraft.client.renderer.entity.state.EntityRenderState;

public class ShipRenderState extends EntityRenderState {
	/** Points at the ship's cached block list for this frame. */
	public List<ShipRenderer.CachedBlock> blocks = List.of();
	/** Block-entity models (chests, signs, beds...) to draw this frame. */
	public List<ShipRenderer.BlockEntityToDraw> blockEntities = List.of();
	/** Degrees about the helm's vertical axis while a turn is swinging round (#27); 0 otherwise. */
	public float turnAngle;
}
