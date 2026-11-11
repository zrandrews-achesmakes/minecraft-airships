package io.github.zrandrews.airships.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;

import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.EntityTrackingEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import io.github.zrandrews.airships.AirshipsMod;
import io.github.zrandrews.airships.ship.FuelHelper;
import io.github.zrandrews.airships.ship.ShipEntity;
import io.github.zrandrews.airships.ship.ShipInteractions;

public final class AirshipsNetworking {
	private AirshipsNetworking() {
	}

	/** Server -> client: the blocks that make up a ship. */
	public record ShipShapePayload(int entityId, CompoundTag shape) implements CustomPacketPayload {
		public static final Type<ShipShapePayload> TYPE = new Type<>(AirshipsMod.id("ship_shape"));
		public static final StreamCodec<RegistryFriendlyByteBuf, ShipShapePayload> CODEC = StreamCodec.composite(
				ByteBufCodecs.VAR_INT, ShipShapePayload::entityId,
				ByteBufCodecs.COMPOUND_TAG, ShipShapePayload::shape,
				ShipShapePayload::new
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/**
	 * Client -> server, sent every tick while piloting.
	 * forward/strafe/vertical are -1, 0 or 1. yaw is where the pilot is looking.
	 */
	public record PilotInputPayload(byte forward, byte strafe, byte vertical, float yaw) implements CustomPacketPayload {
		public static final Type<PilotInputPayload> TYPE = new Type<>(AirshipsMod.id("pilot_input"));
		public static final StreamCodec<RegistryFriendlyByteBuf, PilotInputPayload> CODEC = StreamCodec.composite(
				ByteBufCodecs.BYTE, PilotInputPayload::forward,
				ByteBufCodecs.BYTE, PilotInputPayload::strafe,
				ByteBufCodecs.BYTE, PilotInputPayload::vertical,
				ByteBufCodecs.FLOAT, PilotInputPayload::yaw,
				PilotInputPayload::new
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Client -> server: one-off pilot actions. */
	public record PilotActionPayload(byte action) implements CustomPacketPayload {
		public static final byte ROTATE_LEFT = 1;
		public static final byte ROTATE_RIGHT = 2;
		public static final byte LAND = 3;

		public static final Type<PilotActionPayload> TYPE = new Type<>(AirshipsMod.id("pilot_action"));
		public static final StreamCodec<RegistryFriendlyByteBuf, PilotActionPayload> CODEC = StreamCodec.composite(
				ByteBufCodecs.BYTE, PilotActionPayload::action,
				PilotActionPayload::new
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Client -> server: the player right-clicked a block on a flying ship (open a chest, a door...). */
	public record UseShipBlockPayload(int shipId, BlockPos local) implements CustomPacketPayload {
		public static final Type<UseShipBlockPayload> TYPE = new Type<>(AirshipsMod.id("use_ship_block"));
		public static final StreamCodec<RegistryFriendlyByteBuf, UseShipBlockPayload> CODEC = StreamCodec.composite(
				ByteBufCodecs.VAR_INT, UseShipBlockPayload::shipId,
				BlockPos.STREAM_CODEC, UseShipBlockPayload::local,
				UseShipBlockPayload::new
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public static void initialize() {
		PayloadTypeRegistry.clientboundPlay().register(ShipShapePayload.TYPE, ShipShapePayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(PilotInputPayload.TYPE, PilotInputPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(PilotActionPayload.TYPE, PilotActionPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(UseShipBlockPayload.TYPE, UseShipBlockPayload.CODEC);

		ServerPlayNetworking.registerGlobalReceiver(UseShipBlockPayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			if (player.isSpectator() || !(player.level().getEntity(payload.shipId()) instanceof ShipEntity ship)) return;
			if (ship.getLocalState(payload.local()) == null) return;

			double reach = player.blockInteractionRange() + ShipInteractions.REACH_SLACK;
			if (player.getEyePosition().distanceToSqr(ship.worldCenter(payload.local())) > reach * reach) return;

			ShipInteractions.use(ship, payload.local(), player);
		});

		ServerPlayNetworking.registerGlobalReceiver(PilotInputPayload.TYPE, (payload, context) -> {
			ShipEntity ship = pilotedShip(context.player());
			if (ship != null) {
				ship.setPilotInput(payload.forward(), payload.strafe(), payload.vertical(), payload.yaw());
			}
		});

		ServerPlayNetworking.registerGlobalReceiver(PilotActionPayload.TYPE, (payload, context) -> {
			ShipEntity ship = pilotedShip(context.player());
			if (ship != null) {
				ship.handleAction(payload.action(), context.player());
			}
		});

		// Send the ship's blocks to each player as they come into range.
		EntityTrackingEvents.START_TRACKING.register((entity, player) -> {
			if (entity instanceof ShipEntity ship) {
				ServerPlayNetworking.send(player, ship.createShapePayload());
			}
		});

		// Right-clicking the helm of a flying ship: add fuel, or take the wheel.
		UseEntityCallback.EVENT.register((player, level, hand, entity, hitResult) -> {
			if (!(entity instanceof ShipEntity ship)) return InteractionResult.PASS;
			if (level instanceof ServerLevel) {
				ship.onPlayerUse(player, hand);
			}
			return InteractionResult.SUCCESS;
		});

		// The pilot can't click the helm they're sitting on (vanilla never lets a rider target
		// their own vehicle), so at the wheel, right-clicking with fuel in hand refuels the ship.
		UseItemCallback.EVENT.register((player, level, hand) -> {
			ShipEntity ship = pilotedShip(player);
			if (ship == null || FuelHelper.fuelValue(level, player.getItemInHand(hand)) <= 0) return InteractionResult.PASS;
			if (level instanceof ServerLevel) {
				ship.onPlayerUse(player, hand);
			}
			return InteractionResult.SUCCESS;
		});
	}

	private static ShipEntity pilotedShip(Player player) {
		if (player.getVehicle() instanceof ShipEntity ship && ship.getFirstPassenger() == player) {
			return ship;
		}
		return null;
	}

	public static void sendShapeToTrackers(ShipEntity ship) {
		sendShapeToTrackers(ship, 0);
	}

	/** @param turn 1 = this shape comes from a right turn, -1 = left turn (clients animate it), 0 = no turn */
	public static void sendShapeToTrackers(ShipEntity ship, int turn) {
		ShipShapePayload payload = ship.createShapePayload(turn);
		for (ServerPlayer player : net.fabricmc.fabric.api.networking.v1.PlayerLookup.tracking(ship)) {
			ServerPlayNetworking.send(player, payload);
		}
	}
}
