package io.github.zrandrews.airships.test;

import java.util.Arrays;
import java.util.List;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import io.github.zrandrews.airships.registry.ModBlocks;
import io.github.zrandrews.airships.ship.ShipAssembler;
import io.github.zrandrews.airships.ship.ShipEntity;

/**
 * End-to-end test with a real client: launch a ship, fly it with the keyboard,
 * turn, land, and take screenshots along the way. Runs with
 * {@code gradlew runClientGameTest} (headless on CI using a virtual display).
 */
@SuppressWarnings("UnstableApiUsage")
public class AirshipsClientGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			world.getConnection().waitForChunksRender();

			// Build a raft in the air in front of the player and put them at the wheel.
			BlockPos helm = world.getServer().computeOnServer(server -> {
				ServerPlayer player = player(server);
				ServerLevel level = player.level();
				BlockPos base = player.blockPosition().offset(0, 6, 0);

				for (int x = -1; x <= 1; x++) {
					for (int z = -1; z <= 1; z++) {
						level.setBlockAndUpdate(base.offset(x, 0, z), Blocks.OAK_PLANKS.defaultBlockState());
					}
				}
				level.setBlockAndUpdate(base.offset(-1, 1, -1), Blocks.TORCH.defaultBlockState());
				level.setBlockAndUpdate(base.offset(1, 1, 0), ModBlocks.LIFT_BLOCK.defaultBlockState());
				BlockPos helmPos = base.above();
				level.setBlockAndUpdate(helmPos, ModBlocks.HELM.defaultBlockState());

				ShipAssembler.Result result = ShipAssembler.assemble(level, helmPos);
				if (!result.launched()) throw new AssertionError("Launch failed: " + result.message().getString());

				result.ship().setServerFuel(20000);
				player.startRiding(result.ship(), true, true);
				return helmPos;
			});

			context.waitTicks(20);
			context.getInput().lookAt(0f, 20f);
			context.waitTicks(5);
			context.takeScreenshot("airships-1-launched");

			// The client must have received the ship's blocks.
			int clientBlocks = context.computeOnClient(mc -> clientShip(mc).getBlocks().size());
			if (clientBlocks != 12) throw new AssertionError("Client sees " + clientBlocks + " ship blocks, expected 12");

			// Fly forward and up with the real keys.
			Vec3 start = world.getServer().computeOnServer(server -> ship(server).position());
			context.getInput().holdKey(options -> options.keyUp);
			context.getInput().holdKey(options -> options.keyJump);
			context.waitTicks(40);
			context.takeScreenshot("airships-2-flying");
			context.getInput().releaseKey(options -> options.keyUp);
			context.getInput().releaseKey(options -> options.keyJump);
			context.waitTicks(60); // coast to a stop

			Vec3 end = world.getServer().computeOnServer(server -> ship(server).position());
			if (horizontalDistance(start, end) < 2.0) throw new AssertionError("Ship didn't fly forward: " + start + " -> " + end);
			if (end.y < start.y + 1.0) throw new AssertionError("Ship didn't climb: " + start + " -> " + end);

			// Turn right with the mod's key.
			context.getInput().pressKey(modKey(context, "key.airships.turn_right"));
			context.waitTicks(10);
			context.takeScreenshot("airships-3-turned");

			// Land with the mod's key.
			context.getInput().pressKey(modKey(context, "key.airships.land"));
			context.waitTicks(10);

			boolean landed = world.getServer().computeOnServer(server -> {
				ServerLevel level = player(server).level();
				List<ShipEntity> ships = level.getEntitiesOfClass(ShipEntity.class, player(server).getBoundingBox().inflate(64));
				return ships.isEmpty();
			});
			if (!landed) throw new AssertionError("Ship is still flying after pressing the land key");

			world.getConnection().waitForChunksRender();
			context.takeScreenshot("airships-4-landed");
		}
	}

	private static ServerPlayer player(MinecraftServer server) {
		return server.getPlayerList().getPlayers().getFirst();
	}

	private static ShipEntity ship(MinecraftServer server) {
		if (player(server).getVehicle() instanceof ShipEntity ship) return ship;
		throw new AssertionError("Player is not at the wheel of a ship");
	}

	private static ShipEntity clientShip(Minecraft mc) {
		if (mc.player != null && mc.player.getVehicle() instanceof ShipEntity ship) return ship;
		throw new AssertionError("Client player is not riding a ship");
	}

	private static KeyMapping modKey(ClientGameTestContext context, String name) {
		return context.computeOnClient(mc -> Arrays.stream(mc.options.keyMappings)
				.filter(k -> k.getName().equals(name))
				.findFirst()
				.orElseThrow(() -> new AssertionError("Key mapping not registered: " + name)));
	}

	private static double horizontalDistance(Vec3 a, Vec3 b) {
		double dx = a.x - b.x, dz = a.z - b.z;
		return Math.sqrt(dx * dx + dz * dz);
	}
}
