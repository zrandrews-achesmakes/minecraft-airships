package io.github.zrandrews.airships.ship;

import java.util.List;
import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;

/**
 * One block carried by a ship.
 *
 * @param pos         position relative to the Helm (Helm is 0,0,0)
 * @param state       the block state
 * @param blockEntity saved block-entity data (chest contents etc.), server only
 */
public record ShipBlock(BlockPos pos, BlockState state, Optional<CompoundTag> blockEntity) {
	/** Full codec, used for saving the ship to disk. */
	public static final Codec<ShipBlock> CODEC = RecordCodecBuilder.create(i -> i.group(
			BlockPos.CODEC.fieldOf("pos").forGetter(ShipBlock::pos),
			BlockState.CODEC.fieldOf("state").forGetter(ShipBlock::state),
			CompoundTag.CODEC.optionalFieldOf("block_entity").forGetter(ShipBlock::blockEntity)
	).apply(i, ShipBlock::new));

	public static final Codec<List<ShipBlock>> LIST_CODEC = CODEC.listOf();

	/**
	 * Lightweight codec for sending the shape of the ship to players
	 * (no block-entity data, so big chests full of items don't blow up the packet).
	 */
	public static final Codec<List<ShipBlock>> CLIENT_LIST_CODEC = RecordCodecBuilder.<ShipBlock>create(i -> i.group(
			BlockPos.CODEC.fieldOf("p").forGetter(ShipBlock::pos),
			BlockState.CODEC.fieldOf("s").forGetter(ShipBlock::state)
	).apply(i, (pos, state) -> new ShipBlock(pos, state, Optional.empty()))).listOf();
}
