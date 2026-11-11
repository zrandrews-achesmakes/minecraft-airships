package io.github.zrandrews.airships.ship;

import java.util.Optional;

import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CookingFuel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.providers.number.ints.ResolvableInt;

import io.github.zrandrews.airships.AirshipsConfig;
import io.github.zrandrews.airships.AirshipsMod;

/** Fuel is anything that burns in a furnace, using the furnace burn time. */
public final class FuelHelper {
	/** Used if an item's burn time can't be worked out outside a furnace. */
	private static final int FALLBACK_BURN_TIME = 200;

	private FuelHelper() {
	}

	/**
	 * Burn time of the item in ticks, or 0 if it isn't fuel.
	 * On the client this only tells you whether it's fuel (returns 1).
	 */
	public static int fuelValue(Level level, ItemStack stack) {
		if (stack.isEmpty() || !stack.has(DataComponents.COOKING_FUEL)) return 0;
		if (!(level instanceof ServerLevel serverLevel)) return 1;

		try {
			LootContext context = new LootContext.Builder(
					new LootParams.Builder(serverLevel).create(LootContextParamSets.EMPTY)
			).create(Optional.empty());

			int burn = ResolvableInt.getFromItem(stack, DataComponents.COOKING_FUEL, CookingFuel::burnTime, context, 0);
			return burn > 0 ? burn : FALLBACK_BURN_TIME;
		} catch (RuntimeException e) {
			AirshipsMod.LOGGER.debug("Couldn't read burn time for {}", stack, e);
			return FALLBACK_BURN_TIME;
		}
	}

	public static void consumeOne(Player player, InteractionHand hand, ItemStack stack) {
		if (player.getAbilities().instabuild) return;

		// Lava buckets leave an empty bucket behind, like in a furnace.
		if (stack.is(Items.LAVA_BUCKET)) {
			player.setItemInHand(hand, new ItemStack(Items.BUCKET));
		} else {
			stack.shrink(1);
		}
	}

	public static int percent(int fuel) {
		return (int) Math.round(100.0 * fuel / Math.max(1, AirshipsConfig.get().maxFuel));
	}
}
