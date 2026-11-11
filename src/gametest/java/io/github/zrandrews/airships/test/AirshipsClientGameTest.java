package io.github.zrandrews.airships.test;

import java.util.Arrays;
import java.util.List;

import net.minecraft.client.CameraType;
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
 * descend, turn, land, and take screenshots along the way.
 *
 * <p>Run it with {@code gradlew runClientGameTest}. Add {@code -Pwatch} to slow it
 * down so a person can follow along: every step is announced with an on-screen
 * title and in chat, and a failure stays on screen for a while before the game closes.
 */
@SuppressWarnings("UnstableApiUsage")
public class AirshipsClientGameTest implements FabricClientGameTest {
	/** Watch mode: set by {@code -Pwatch} (Gradle) or the AIRSHIPS_WATCH environment variable. */
	private static final boolean WATCH = Boolean.getBoolean("airships.watch") || System.getenv("AIRSHIPS_WATCH") != null;

	private static final int TOTAL_STEPS = 6;

	private ClientGameTestContext context;
	private TestSingleplayerContext world;
	private int step;

	@Override
	public void runTest(ClientGameTestContext context) {
		this.context = context;

		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			this.world = world;
			world.getConnection().waitForChunksRender();
			runCommand("time set day");
			runCommand("weather clear");

			try {
				runSteps();
				announce("All passed", "The airship test finished OK");
				pause(100);
			} catch (Throwable failure) {
				String reason = failure.getMessage() == null ? failure.toString() : failure.getMessage();
				announce("Test failed", "Step " + step + " - see chat", "Test failed at step " + step + ": " + reason);
				context.takeScreenshot("airships-failure");
				pause(300);
				throw failure;
			}
		}
	}

	private void runSteps() {
		// 1 ------------------------------------------------------------------------
		step("Launch", "Building a raft in the air and taking the wheel");
		world.getServer().runOnServer(server -> {
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
		});
		context.waitTicks(20);
		// Third-person view so the whole ship is visible (on screen and in screenshots).
		context.runOnClient(mc -> mc.options.setCameraType(CameraType.THIRD_PERSON_BACK));
		context.getInput().lookAt(0f, 25f);
		context.waitTicks(5);

		int clientBlocks = context.computeOnClient(mc -> clientShip(mc).getBlocks().size());
		check(clientBlocks == 12, "Client sees " + clientBlocks + " ship blocks, expected 12");
		context.takeScreenshot("airships-1-launched");
		pass();

		// 2 ------------------------------------------------------------------------
		step("Fly forward and climb (#4, #8)", "Holding W + Space. Watch for judder");
		Vec3 start = shipPos();
		context.getInput().holdKey(options -> options.keyUp);
		context.getInput().holdKey(options -> options.keyJump);
		context.waitTicks(WATCH ? 100 : 40);
		context.takeScreenshot("airships-2-flying");
		context.getInput().releaseKey(options -> options.keyUp);
		context.getInput().releaseKey(options -> options.keyJump);
		context.waitTicks(60); // coast to a stop

		Vec3 afterFlight = shipPos();
		check(horizontal(start, afterFlight) > 2.0, "Ship didn't fly forward: " + start + " -> " + afterFlight);
		check(afterFlight.y > start.y + 1.0, "Ship didn't climb: " + start + " -> " + afterFlight);
		pass();

		// 3 ------------------------------------------------------------------------
		step("Descend with X (#9)", "Holding the descend key");
		double beforeDescend = shipPos().y;
		KeyMapping descend = modKey("key.airships.descend");
		context.getInput().holdKey(descend);
		context.waitTicks(WATCH ? 50 : 30);
		context.getInput().releaseKey(descend);
		context.waitTicks(40);
		double afterDescend = shipPos().y;
		check(afterDescend < beforeDescend - 0.5, "Ship didn't descend: y " + beforeDescend + " -> " + afterDescend);
		pass();

		// 4 ------------------------------------------------------------------------
		step("Turn right with C (#10)", "Pressing the turn-right key");
		BlockPos liftBefore = liftOffset();
		context.getInput().pressKey(modKey("key.airships.turn_right"));
		context.waitTicks(10);
		BlockPos liftAfter = liftOffset();
		check(liftAfter.equals(new BlockPos(-liftBefore.getZ(), liftBefore.getY(), liftBefore.getX())),
				"Ship didn't turn right: lift block moved " + liftBefore + " -> " + liftAfter);
		context.takeScreenshot("airships-3-turned");
		pass();

		// 5 ------------------------------------------------------------------------
		step("Turn back left with Z (#10)", "Pressing the turn-left key");
		context.getInput().pressKey(modKey("key.airships.turn_left"));
		context.waitTicks(10);
		check(liftOffset().equals(liftBefore), "Ship didn't turn back left: lift block is at " + liftOffset());
		pass();

		// 6 ------------------------------------------------------------------------
		step("Land with G (#6)", "Pressing the land key");
		context.getInput().pressKey(modKey("key.airships.land"));
		context.waitTicks(10);
		boolean landed = world.getServer().computeOnServer(server -> {
			List<ShipEntity> ships = player(server).level().getEntitiesOfClass(ShipEntity.class, player(server).getBoundingBox().inflate(64));
			return ships.isEmpty();
		});
		check(landed, "Ship is still flying after pressing the land key");
		world.getConnection().waitForChunksRender();
		context.takeScreenshot("airships-4-landed");
		pass();
	}

	// ------------------------------------------------------------------ watch mode

	/** Starts a step: shows "Step n of N" as a title and in chat, then gives the viewer a moment. */
	private void step(String name, String detail) {
		step++;
		String header = "Step " + step + " of " + TOTAL_STEPS + ": " + name;
		System.out.println("[Airships test] " + header + " - " + detail);
		// Big title stays short so it fits the window; the step name goes underneath.
		announce("Step " + step + " of " + TOTAL_STEPS, name, header + " - " + detail);
		pause(60);
	}

	private void pass() {
		System.out.println("[Airships test]   passed");
		say("  passed");
		pause(40);
	}

	private void check(boolean ok, String message) {
		if (!ok) throw new AssertionError(message);
	}

	private void announce(String title, String subtitle) {
		announce(title, subtitle, title + " - " + subtitle);
	}

	private void announce(String title, String subtitle, String chatLine) {
		if (!WATCH) return;
		runCommand("title @a times 5 70 10");
		runCommand("title @a subtitle " + quote(subtitle));
		runCommand("title @a title " + quote(title));
		say(chatLine);
	}

	private void say(String text) {
		if (WATCH) runCommand("tellraw @a " + quote(text));
	}

	private void pause(int ticks) {
		if (WATCH) context.waitTicks(ticks);
	}

	private void runCommand(String command) {
		try {
			world.getServer().runCommand(command);
		} catch (RuntimeException e) {
			System.out.println("[Airships test] couldn't run /" + command + ": " + e);
		}
	}

	private static String quote(String text) {
		return "\"" + text.replace("\\", "\\\\").replace("\"", "'") + "\"";
	}

	// ------------------------------------------------------------------ helpers

	private Vec3 shipPos() {
		return world.getServer().computeOnServer(server -> ship(server).position());
	}

	/** Where the Lift Block sits relative to the Helm, as the server sees it. */
	private BlockPos liftOffset() {
		return world.getServer().computeOnServer(server -> ship(server).getBlocks().stream()
				.filter(b -> b.state().is(ModBlocks.LIFT_BLOCK))
				.findFirst()
				.orElseThrow(() -> new AssertionError("Ship has no Lift Block"))
				.pos());
	}

	private KeyMapping modKey(String name) {
		return context.computeOnClient(mc -> Arrays.stream(mc.options.keyMappings)
				.filter(k -> k.getName().equals(name))
				.findFirst()
				.orElseThrow(() -> new AssertionError("Key mapping not registered: " + name)));
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

	private static double horizontal(Vec3 a, Vec3 b) {
		double dx = a.x - b.x, dz = a.z - b.z;
		return Math.sqrt(dx * dx + dz * dz);
	}
}
