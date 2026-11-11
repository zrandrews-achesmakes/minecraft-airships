package io.github.zrandrews.airships.client;

import org.jspecify.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import io.github.zrandrews.airships.network.AirshipsNetworking.UseShipBlockPayload;
import io.github.zrandrews.airships.ship.ShipEntity;
import io.github.zrandrews.airships.ship.ShipInteractions;
import io.github.zrandrews.airships.ship.ShipTracker;

/**
 * Right-clicking blocks on flying ships (#25). Vanilla's crosshair only sees real world blocks,
 * so we cast our own ray against the ships' blocks.
 */
public final class ShipBlockUse {
	/** The usable ship block under the crosshair. */
	public record Target(ShipEntity ship, BlockPos local, BlockState state) {
	}

	private ShipBlockUse() {
	}

	/** The usable ship block the player is looking at, if it's closer than whatever vanilla is aiming at. */
	public static @Nullable Target target(Minecraft mc) {
		LocalPlayer player = mc.player;
		if (player == null || mc.level == null || player.isSpectator()) return null;

		double reach = player.blockInteractionRange();
		Vec3 from = player.getEyePosition();
		Vec3 to = from.add(player.getViewVector(1.0f).scale(reach));

		Target best = null;
		double bestDistSqr = Double.MAX_VALUE;
		for (ShipEntity ship : ShipTracker.ships(mc.level)) {
			ShipEntity.BlockHit hit = ship.clipBlocks(from, to);
			if (hit == null || hit.distanceSqr() >= bestDistSqr) continue;
			BlockState state = ship.getLocalState(hit.local());
			if (state == null) continue;
			best = new Target(ship, hit.local(), state);
			bestDistSqr = hit.distanceSqr();
		}

		if (best == null || !ShipInteractions.isUsable(best.state())) return null;

		// Something real (a block, a mob, the helm) is in front of the ship block: let vanilla have it.
		HitResult vanilla = mc.hitResult;
		if (vanilla != null && vanilla.getType() != HitResult.Type.MISS && vanilla.getLocation().distanceToSqr(from) < bestDistSqr) {
			return null;
		}
		return best;
	}

	/** Called instead of vanilla's right-click. Returns true if a ship block was used. */
	public static boolean tryUse(Minecraft mc) {
		Target target = target(mc);
		if (target == null) return false;

		ClientPlayNetworking.send(new UseShipBlockPayload(target.ship().getId(), target.local()));
		mc.player.swing(InteractionHand.MAIN_HAND, mc.player.getMainHandItem().getInteractAnimation(), true);
		return true;
	}
}
