package io.github.zrandrews.airships.block;

import net.minecraft.world.level.block.Block;

/**
 * A Lift Block keeps a ship in the air. Each one carries
 * {@code liftPerLiftBlock} blocks of ship (see config/airships.json).
 */
public class LiftBlock extends Block {
	public LiftBlock(Properties properties) {
		super(properties);
	}
}
