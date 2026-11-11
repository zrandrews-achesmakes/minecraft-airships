package io.github.zrandrews.airships.client;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.WeakHashMap;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.entity.EntityRenderers;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import io.github.zrandrews.airships.AirshipsMod;
import io.github.zrandrews.airships.network.AirshipsNetworking.PilotActionPayload;
import io.github.zrandrews.airships.network.AirshipsNetworking.PilotInputPayload;
import io.github.zrandrews.airships.network.AirshipsNetworking.ShipShapePayload;
import io.github.zrandrews.airships.registry.ModEntities;
import io.github.zrandrews.airships.ship.FuelHelper;
import io.github.zrandrews.airships.ship.ShipEntity;
import io.github.zrandrews.airships.ship.ShipTracker;

public class AirshipsClient implements ClientModInitializer {
	private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(AirshipsMod.id("airships"));

	private static final KeyMapping DESCEND = KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.airships.descend", InputConstants.Type.KEYBOARD, InputConstants.KEY_X, CATEGORY));
	private static final KeyMapping TURN_LEFT = KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.airships.turn_left", InputConstants.Type.KEYBOARD, InputConstants.KEY_Z, CATEGORY));
	private static final KeyMapping TURN_RIGHT = KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.airships.turn_right", InputConstants.Type.KEYBOARD, InputConstants.KEY_C, CATEGORY));
	private static final KeyMapping LAND = KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.airships.land", InputConstants.Type.KEYBOARD, InputConstants.KEY_G, CATEGORY));

	/** Shapes that arrived before their ship entity did. */
	private static final Map<Integer, CompoundTag> PENDING_SHAPES = new HashMap<>();
	/** Where each ship was last tick, for carrying the player along. */
	private static final Map<ShipEntity, Vec3> LAST_SEEN = new WeakHashMap<>();
	/** The turn animation angle each ship had last tick, to swing the pilot's view along with it. */
	private static final Map<ShipEntity, Float> LAST_TURN_ANGLE = new WeakHashMap<>();
	/** Ticks before another turn may be requested (one turn per swing). */
	private static int turnCooldown;

	@Override
	public void onInitializeClient() {
		EntityRenderers.register(ModEntities.SHIP, ShipRenderer::new);

		ClientPlayNetworking.registerGlobalReceiver(ShipShapePayload.TYPE, (payload, context) -> {
			var level = context.client().level;
			if (level == null) return;

			Entity entity = level.getEntity(payload.entityId());
			if (entity instanceof ShipEntity ship) {
				ship.applyShape(payload.shape());
			} else {
				PENDING_SHAPES.put(payload.entityId(), payload.shape());
			}
		});

		ClientTickEvents.START_CLIENT_TICK.register(AirshipsClient::carryPlayer);
		ClientTickEvents.END_CLIENT_TICK.register(AirshipsClient::endTick);
	}

	/** Before the player moves this tick: if they're standing on a ship that moved, move them with it. */
	private static void carryPlayer(Minecraft client) {
		if (client.level == null || client.player == null) {
			LAST_SEEN.clear();
			return;
		}

		LocalPlayer player = client.player;
		boolean carried = false;

		for (ShipEntity ship : ShipTracker.ships(client.level)) {
			Vec3 now = ship.position();
			Vec3 last = LAST_SEEN.put(ship, now);
			if (last == null || carried || player.isPassenger()) continue;

			Vec3 delta = now.subtract(last);
			if (delta.lengthSqr() < 1.0E-10 || delta.lengthSqr() > 4.0) continue;

			if (ship.isStandingOn(player.getBoundingBox(), last)) {
				player.setPos(player.getX() + delta.x, player.getY() + delta.y, player.getZ() + delta.z);
				player.resetFallDistance();
				carried = true;
			}
		}
	}

	private static void endTick(Minecraft client) {
		if (client.level == null || client.player == null) {
			PENDING_SHAPES.clear();
			return;
		}

		applyPendingShapes(client);

		LocalPlayer player = client.player;
		if (!(player.getVehicle() instanceof ShipEntity ship) || ship.getFirstPassenger() != player) {
			// Not piloting: drain key presses so they don't fire later.
			while (TURN_LEFT.consumeClick()) { }
			while (TURN_RIGHT.consumeClick()) { }
			while (LAND.consumeClick()) { }
			return;
		}

		int forward = (client.options.keyUp.isDown() ? 1 : 0) - (client.options.keyDown.isDown() ? 1 : 0);
		int strafe = (client.options.keyRight.isDown() ? 1 : 0) - (client.options.keyLeft.isDown() ? 1 : 0);
		int vertical = (client.options.keyJump.isDown() ? 1 : 0) - (DESCEND.isDown() ? 1 : 0);

		ClientPlayNetworking.send(new PilotInputPayload((byte) forward, (byte) strafe, (byte) vertical, player.getYRot()));

		// Turning (#27): a tap turns 90 degrees; holding the key keeps turning, one swing after another.
		boolean left = TURN_LEFT.consumeClick() | TURN_LEFT.isDown();
		boolean right = TURN_RIGHT.consumeClick() | TURN_RIGHT.isDown();
		while (TURN_LEFT.consumeClick()) { }
		while (TURN_RIGHT.consumeClick()) { }
		if (turnCooldown > 0) turnCooldown--;
		if (turnCooldown == 0 && !ship.isTurning() && left != right) {
			ClientPlayNetworking.send(new PilotActionPayload(left ? PilotActionPayload.ROTATE_LEFT : PilotActionPayload.ROTATE_RIGHT));
			turnCooldown = ShipEntity.TURN_TICKS + 2;
		}
		while (LAND.consumeClick()) ClientPlayNetworking.send(new PilotActionPayload(PilotActionPayload.LAND));

		// The pilot's view swings round with the ship, like a boat.
		// The drawn angle eases from ±90 to 0; rotate the view by however much it moved this tick.
		float angle = ship.turnAngle(0f);
		Float previous = LAST_TURN_ANGLE.put(ship, angle);
		float last = previous == null ? angle : previous;
		if (Math.abs(angle) > Math.abs(last) + 1.0E-3f) last = Math.signum(angle) * 90f; // a new turn just started
		if (last != angle) player.setYRot(player.getYRot() + (last - angle));

		if (player.tickCount % 10 == 0) {
			player.sendOverlayMessage(statusLine(ship));
		}
	}

	/** The pilot's status line. Key names come from the player's current bindings. */
	public static Component statusLine(ShipEntity ship) {
		return Component.translatable(
				"hud.airships.status",
				FuelHelper.percent(ship.getFuel()),
				ship.getMass(),
				ship.getLift(),
				Component.keybind(LAND.getName()),
				Component.keybind(TURN_LEFT.getName()),
				Component.keybind(TURN_RIGHT.getName())
		);
	}

	private static void applyPendingShapes(Minecraft client) {
		if (PENDING_SHAPES.isEmpty()) return;

		Iterator<Map.Entry<Integer, CompoundTag>> it = PENDING_SHAPES.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<Integer, CompoundTag> entry = it.next();
			Entity entity = client.level.getEntity(entry.getKey());
			if (entity instanceof ShipEntity ship) {
				ship.applyShape(entry.getValue());
				it.remove();
			}
		}

		// Don't let stale entries pile up forever.
		if (PENDING_SHAPES.size() > 64) PENDING_SHAPES.clear();
	}
}
