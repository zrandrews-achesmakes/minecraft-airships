package io.github.zrandrews.airships.block;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import io.github.zrandrews.airships.AirshipsConfig;
import io.github.zrandrews.airships.ship.FuelHelper;
import io.github.zrandrews.airships.ship.ShipAssembler;

/**
 * The Helm. Right-click with fuel to load it; right-click empty-handed to
 * launch the ship (turn the connected blocks into a flying vehicle).
 */
public class HelmBlock extends BaseEntityBlock {
	public HelmBlock(Properties properties) {
		super(properties);
	}

	@Override
	protected RenderShape getRenderShape(BlockState state) {
		return RenderShape.MODEL;
	}

	@Nullable
	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new HelmBlockEntity(pos, state);
	}

	@Override
	protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hitResult) {
		int burn = FuelHelper.fuelValue(level, stack);

		if (burn <= 0) {
			// Not fuel: fall through to useWithoutItem (launch).
			return super.useItemOn(stack, state, level, pos, player, hand, hitResult);
		}

		if (!level.isClientSide() && level.getBlockEntity(pos) instanceof HelmBlockEntity helm) {
			int max = AirshipsConfig.get().maxFuel;

			if (helm.getFuel() + burn > max) {
				player.sendOverlayMessage(Component.translatable("message.airships.fuel_full"));
				return InteractionResult.SUCCESS;
			}

			helm.setFuel(helm.getFuel() + burn);
			FuelHelper.consumeOne(player, hand, stack);
			player.sendOverlayMessage(Component.translatable("message.airships.fuel", FuelHelper.percent(helm.getFuel())));
		}

		return InteractionResult.SUCCESS;
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
		if (level instanceof ServerLevel serverLevel) {
			ShipAssembler.tryAssemble(serverLevel, pos, player);
		}

		return InteractionResult.SUCCESS;
	}
}
