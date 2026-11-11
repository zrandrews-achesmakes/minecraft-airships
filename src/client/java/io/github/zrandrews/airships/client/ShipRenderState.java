package io.github.zrandrews.airships.client;

import java.util.List;

import net.minecraft.client.renderer.entity.state.EntityRenderState;

public class ShipRenderState extends EntityRenderState {
	/** Points at the ship's cached block list for this frame. */
	public List<ShipRenderer.CachedBlock> blocks = List.of();
}
