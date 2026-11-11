package io.github.zrandrews.airships.test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import io.github.zrandrews.airships.AirshipsConfig;
import io.github.zrandrews.airships.block.HelmBlockEntity;
import io.github.zrandrews.airships.registry.ModBlocks;
import io.github.zrandrews.airships.ship.FuelHelper;
import io.github.zrandrews.airships.ship.ShipAssembler;
import io.github.zrandrews.airships.ship.ShipBlock;
import io.github.zrandrews.airships.ship.ShipEntity;

/**
 * Server-side game tests. They run headless with {@code gradlew runGameTest}
 * (and as part of {@code gradlew build}).
 *
 * <p>Each test gets an empty 8x8x8 box. Coordinates below are relative to that box.
 * The standard raft is a 3x3 plank floor at y=1 (x,z 2..4), a Helm on top at
 * (3,2,3) and a Lift Block at (4,2,3): 11 blocks.
 */
public class AirshipsGameTest {
	private static final BlockPos HELM = new BlockPos(3, 2, 3);
	private static final BlockPos LIFT = new BlockPos(4, 2, 3);

	// ------------------------------------------------------------------ helpers

	private static void floor(GameTestHelper h, Block block) {
		for (int x = 0; x < 8; x++) {
			for (int z = 0; z < 8; z++) {
				h.setBlock(x, 0, z, block);
			}
		}
	}

	/** Builds the standard 11-block raft with its floor at {@code y} (default 1). */
	private static void raft(GameTestHelper h, int y) {
		for (int x = 2; x <= 4; x++) {
			for (int z = 2; z <= 4; z++) {
				h.setBlock(x, y, z, Blocks.OAK_PLANKS);
			}
		}
		h.setBlock(3, y + 1, 3, ModBlocks.HELM);
		h.setBlock(4, y + 1, 3, ModBlocks.LIFT_BLOCK);
	}

	private static ShipEntity launch(GameTestHelper h, BlockPos helm) {
		ShipAssembler.Result result = ShipAssembler.assemble(h.getLevel(), h.absolutePos(helm));
		h.assertTrue(result.launched(), "Expected launch, got " + result.status() + ": " + result.message().getString());
		return result.ship();
	}

	private static Map<BlockPos, BlockState> snapshot(GameTestHelper h, int y0, int y1) {
		Map<BlockPos, BlockState> map = new HashMap<>();
		for (int x = 0; x < 8; x++) {
			for (int y = y0; y <= y1; y++) {
				for (int z = 0; z < 8; z++) {
					BlockPos p = new BlockPos(x, y, z);
					map.put(p, h.getBlockState(p));
				}
			}
		}
		return map;
	}

	private static void assertNoError(GameTestHelper h, Component error, String what) {
		h.assertTrue(error == null, what + " failed: " + (error == null ? "" : error.getString()));
	}

	// ------------------------------------------------------------------ assembly

	@GameTest
	public void launchTakesConnectedBlocks(GameTestHelper h) {
		floor(h, Blocks.STONE);
		raft(h, 1);

		ShipEntity ship = launch(h, HELM);

		h.assertValueEqual(ship.getBlocks().size(), 11, "ship block count");
		h.assertBlockPresent(Blocks.AIR, 3, 1, 3);
		h.assertBlockPresent(Blocks.AIR, 3, 2, 3);
		h.assertBlockPresent(Blocks.STONE, 3, 0, 3); // floor stays behind
		h.succeed();
	}

	@GameTest
	public void launchIgnoresGrassAndDirt(GameTestHelper h) {
		floor(h, Blocks.GRASS_BLOCK);
		h.setBlock(1, 0, 1, Blocks.DIRT);
		h.setBlock(5, 0, 5, Blocks.PODZOL);
		raft(h, 1);

		ShipEntity ship = launch(h, HELM);

		h.assertValueEqual(ship.getBlocks().size(), 11, "ship block count (ground must not be included)");
		h.assertBlockPresent(Blocks.GRASS_BLOCK, 3, 0, 3);
		h.assertBlockPresent(Blocks.DIRT, 1, 0, 1);
		h.succeed();
	}

	@GameTest
	public void refusesWithoutEnoughLift(GameTestHelper h) {
		floor(h, Blocks.STONE);
		// Two 6x6 layers (72) + helm + lift = 74 blocks; one Lift Block carries 48.
		// (Kept 1 block away from the test box's barrier walls.)
		for (int x = 1; x <= 6; x++) {
			for (int z = 1; z <= 6; z++) {
				h.setBlock(x, 1, z, Blocks.OAK_PLANKS);
				h.setBlock(x, 2, z, Blocks.OAK_PLANKS);
			}
		}
		BlockPos helm = new BlockPos(3, 3, 3);
		h.setBlock(3, 3, 3, ModBlocks.HELM);
		h.setBlock(4, 3, 3, ModBlocks.LIFT_BLOCK);

		ShipAssembler.Result result = ShipAssembler.assemble(h.getLevel(), h.absolutePos(helm));

		h.assertValueEqual(result.status(), ShipAssembler.Status.NOT_ENOUGH_LIFT, "status");
		h.assertBlockPresent(Blocks.OAK_PLANKS, 1, 1, 1); // nothing moved
		h.assertBlockPresent(ModBlocks.HELM, 3, 3, 3);
		h.succeed();
	}

	@GameTest
	public void refusesTooBig(GameTestHelper h) {
		floor(h, Blocks.STONE);
		raft(h, 1);

		AirshipsConfig cfg = AirshipsConfig.get();
		int old = cfg.maxShipBlocks;
		ShipAssembler.Result result;
		try {
			cfg.maxShipBlocks = 5;
			result = ShipAssembler.assemble(h.getLevel(), h.absolutePos(HELM));
		} finally {
			cfg.maxShipBlocks = old;
		}

		h.assertValueEqual(result.status(), ShipAssembler.Status.TOO_BIG, "status");
		h.assertBlockPresent(ModBlocks.HELM, 3, 2, 3);
		h.succeed();
	}

	@GameTest
	public void refusesUnmovableBlocks(GameTestHelper h) {
		floor(h, Blocks.STONE);
		raft(h, 1);
		h.setBlock(2, 2, 2, Blocks.BARRIER); // unbreakable, touching the deck

		ShipAssembler.Result result = ShipAssembler.assemble(h.getLevel(), h.absolutePos(HELM));

		h.assertValueEqual(result.status(), ShipAssembler.Status.UNMOVABLE, "status");
		h.assertBlockPresent(ModBlocks.HELM, 3, 2, 3);
		h.succeed();
	}

	// ------------------------------------------------------------------ landing

	@GameTest
	public void landPutsEveryBlockBack(GameTestHelper h) {
		floor(h, Blocks.STONE);
		raft(h, 1);
		h.setBlock(2, 2, 2, Blocks.TORCH); // fragile block must survive too
		Map<BlockPos, BlockState> before = snapshot(h, 0, 4);

		ShipEntity ship = launch(h, HELM);
		assertNoError(h, ship.land(), "Landing");

		h.assertTrue(ship.isRemoved(), "Ship entity should be gone after landing");
		before.forEach((pos, state) -> h.assertBlockState(pos, s -> s.equals(state),
				s -> Component.literal("Expected " + state + " but found " + s)));
		h.succeed();
	}

	@GameTest
	public void chestKeepsItemsThroughFlight(GameTestHelper h) {
		floor(h, Blocks.STONE);
		raft(h, 1);
		h.setBlock(2, 2, 2, Blocks.CHEST);
		ChestBlockEntity chest = (ChestBlockEntity) h.getLevel().getBlockEntity(h.absolutePos(new BlockPos(2, 2, 2)));
		chest.setItem(0, new ItemStack(Items.DIAMOND, 5));

		ShipEntity ship = launch(h, HELM);
		h.assertBlockPresent(Blocks.AIR, 2, 2, 2);
		assertNoError(h, ship.land(), "Landing");

		ChestBlockEntity after = (ChestBlockEntity) h.getLevel().getBlockEntity(h.absolutePos(new BlockPos(2, 2, 2)));
		h.assertTrue(after != null, "Chest block entity missing after landing");
		h.assertTrue(after.getItem(0).is(Items.DIAMOND) && after.getItem(0).getCount() == 5, "Chest lost its diamonds: " + after.getItem(0));
		h.succeed();
	}

	@GameTest
	public void helmFuelTravelsWithShip(GameTestHelper h) {
		floor(h, Blocks.STONE);
		raft(h, 1);
		((HelmBlockEntity) h.getLevel().getBlockEntity(h.absolutePos(HELM))).setFuel(1600);

		ShipEntity ship = launch(h, HELM);
		h.assertValueEqual(ship.getServerFuel(), 1600, "ship fuel after launch");

		assertNoError(h, ship.land(), "Landing");
		HelmBlockEntity helm = (HelmBlockEntity) h.getLevel().getBlockEntity(h.absolutePos(HELM));
		h.assertValueEqual(helm.getFuel(), 1600, "helm fuel after landing");
		h.succeed();
	}

	@GameTest
	public void landingBlockedByTerrain(GameTestHelper h) {
		floor(h, Blocks.STONE);
		raft(h, 1);

		ShipEntity ship = launch(h, HELM);
		h.setBlock(2, 1, 2, Blocks.STONE); // something grew where a plank was

		Component error = ship.land();

		h.assertTrue(error != null, "Landing should be refused when a spot is taken");
		h.assertTrue(!ship.isRemoved(), "Ship should still exist after a refused landing");
		h.assertBlockPresent(Blocks.STONE, 2, 1, 2);
		h.succeed();
	}

	// ------------------------------------------------------------------ flying

	@GameTest(maxTicks = 80)
	public void climbsAndBurnsFuel(GameTestHelper h) {
		floor(h, Blocks.STONE);
		raft(h, 1);

		ShipEntity ship = launch(h, HELM);
		ship.setServerFuel(1600);
		double startY = ship.getY();
		ship.setAutopilot(true, 0, 0, 1, 0f);

		h.runAfterDelay(30, () -> {
			ship.setAutopilot(false, 0, 0, 0, 0f);
			h.assertTrue(ship.getY() > startY + 0.5, "Ship should have climbed; y went " + startY + " -> " + ship.getY());
			h.assertTrue(ship.getServerFuel() < 1600, "Climbing should burn fuel");
			ship.discard();
			h.succeed();
		});
	}

	@GameTest(maxTicks = 60)
	public void cannotClimbWithoutFuel(GameTestHelper h) {
		floor(h, Blocks.STONE);
		raft(h, 1);

		ShipEntity ship = launch(h, HELM);
		ship.setServerFuel(0);
		double startY = ship.getY();
		ship.setAutopilot(true, 1, 0, 1, 0f);

		h.runAfterDelay(20, () -> {
			h.assertTrue(Math.abs(ship.getY() - startY) < 1.0E-3, "Ship climbed with no fuel");
			ship.discard();
			h.succeed();
		});
	}

	@GameTest(maxTicks = 100)
	public void sinksWithoutFuelAndStopsOnTheGround(GameTestHelper h) {
		floor(h, Blocks.STONE);
		raft(h, 4); // deck at y=4, floor top at y=1

		ShipEntity ship = launch(h, new BlockPos(3, 5, 3));
		ship.setServerFuel(0);
		double startY = ship.getY();
		double floorTop = h.absoluteVec(new Vec3(0, 1, 0)).y;
		ship.setAutopilot(true, 0, 0, -1, 0f);

		h.runAfterDelay(80, () -> {
			h.assertTrue(ship.getY() < startY - 1.0, "Ship should sink without fuel");
			h.assertTrue(ship.worldBounds().minY >= floorTop - 1.0E-3, "Ship sank into the ground: bottom " + ship.worldBounds().minY + " floor " + floorTop);
			ship.discard();
			h.succeed();
		});
	}

	@GameTest(maxTicks = 120)
	public void stopsAtAWall(GameTestHelper h) {
		floor(h, Blocks.STONE);
		raft(h, 1);
		for (int y = 1; y <= 6; y++) {
			for (int z = 0; z < 8; z++) {
				h.setBlock(7, y, z, Blocks.STONE);
			}
		}

		ShipEntity ship = launch(h, HELM);
		ship.setServerFuel(10000);
		double startX = ship.getX();
		double wallX = h.absoluteVec(new Vec3(7, 0, 0)).x;
		ship.setAutopilot(true, 1, 0, 0, -90f); // yaw -90 = facing +X

		h.runAfterDelay(100, () -> {
			h.assertTrue(ship.getX() > startX + 1.0, "Ship should have moved toward the wall");
			h.assertTrue(ship.worldBounds().maxX <= wallX + 1.0E-3, "Ship went into the wall: front " + ship.worldBounds().maxX + " wall " + wallX);
			ship.discard();
			h.succeed();
		});
	}

	@GameTest
	public void turnsNinetyDegrees(GameTestHelper h) {
		floor(h, Blocks.STONE);
		raft(h, 1);

		ShipEntity ship = launch(h, HELM);
		h.assertTrue(ship.getLocalState(new BlockPos(1, 0, 0)).is(ModBlocks.LIFT_BLOCK), "Lift block should start at +X of the helm");

		assertNoError(h, ship.turn(Rotation.CLOCKWISE_90), "Turning");

		h.assertTrue(ship.getLocalState(new BlockPos(0, 0, 1)) != null && ship.getLocalState(new BlockPos(0, 0, 1)).is(ModBlocks.LIFT_BLOCK),
				"After a right turn the lift block should be at +Z of the helm");
		ship.discard();
		h.succeed();
	}

	// ------------------------------------------------------------------ riding on deck

	@GameTest(maxTicks = 60)
	public void deckHoldsEntities(GameTestHelper h) {
		floor(h, Blocks.STONE);
		raft(h, 1);

		ShipEntity ship = launch(h, HELM);
		ArmorStand stand = h.spawn(EntityTypes.ARMOR_STAND, new Vec3(2.5, 4.0, 2.5));
		double deckTop = h.absoluteVec(new Vec3(0, 2, 0)).y; // plank deck at y=1 -> top at y=2; stone floor top is y=1

		h.runAfterDelay(30, () -> {
			h.assertTrue(Math.abs(stand.getY() - deckTop) < 0.1,
					"Entity should stand on the deck at y=" + deckTop + " but is at " + stand.getY() + " (fell through to the floor?)");
			ship.discard();
			h.succeed();
		});
	}

	@GameTest(maxTicks = 120)
	public void deckCarriesEntities(GameTestHelper h) {
		floor(h, Blocks.STONE);
		raft(h, 1);

		ShipEntity ship = launch(h, HELM);
		ship.setServerFuel(10000);
		ArmorStand stand = h.spawn(EntityTypes.ARMOR_STAND, new Vec3(2.5, 2.1, 2.5));

		h.runAfterDelay(10, () -> {
			double standStartX = stand.getX();
			ship.setAutopilot(true, 1, 0, 0, -90f);

			h.runAfterDelay(25, () -> {
				ship.setAutopilot(false, 0, 0, 0, 0f);
				h.assertTrue(stand.getX() > standStartX + 0.5, "Entity on deck should move with the ship: x " + standStartX + " -> " + stand.getX());
				ship.discard();
				h.succeed();
			});
		});
	}

	// ------------------------------------------------------------------ data

	@GameTest
	public void shipDataSurvivesSaving(GameTestHelper h) {
		floor(h, Blocks.STONE);
		raft(h, 1);
		h.setBlock(2, 2, 2, Blocks.CHEST);
		((ChestBlockEntity) h.getLevel().getBlockEntity(h.absolutePos(new BlockPos(2, 2, 2)))).setItem(3, new ItemStack(Items.EMERALD, 7));

		ShipEntity ship = launch(h, HELM);
		Tag saved = ShipBlock.LIST_CODEC.encodeStart(NbtOps.INSTANCE, ship.getBlocks()).getOrThrow();
		List<ShipBlock> loaded = ShipBlock.LIST_CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow();

		h.assertValueEqual(loaded.size(), ship.getBlocks().size(), "block count after save/load");
		h.assertTrue(loaded.stream().anyMatch(b -> b.state().is(Blocks.CHEST) && b.blockEntity().isPresent()), "Chest data lost in save/load");
		ship.discard();
		h.succeed();
	}

	// ------------------------------------------------------------------ survival crafting (#20)

	private static ItemStack craft(GameTestHelper h, Item... grid) {
		List<ItemStack> stacks = Arrays.stream(grid).map(i -> i == null ? ItemStack.EMPTY : new ItemStack(i)).toList();
		CraftingInput input = CraftingInput.of(3, 3, stacks);
		return h.getLevel().recipeAccess().getRecipeFor(RecipeType.CRAFTING, input, h.getLevel())
				.map(recipe -> recipe.value().assemble(input))
				.orElse(ItemStack.EMPTY);
	}

	@GameTest
	public void helmRecipeWorks(GameTestHelper h) {
		Item s = Items.STICK, c = Items.COMPASS, l = Items.SPRUCE_LOG;
		ItemStack result = craft(h,
				s, null, s,
				s, c, s,
				l, l, l);
		h.assertTrue(result.is(ModBlocks.HELM.asItem()) && result.getCount() == 1, "Helm recipe gave " + result);
		h.succeed();
	}

	@GameTest
	public void liftBlockRecipeWorks(GameTestHelper h) {
		Item i = Items.IRON_INGOT, p = Items.PHANTOM_MEMBRANE, f = Items.FEATHER, g = Items.GLOWSTONE_DUST;
		ItemStack result = craft(h,
				i, p, i,
				f, g, f,
				i, p, i);
		h.assertTrue(result.is(ModBlocks.LIFT_BLOCK.asItem()) && result.getCount() == 1, "Lift Block recipe gave " + result);
		h.succeed();
	}

	/** A survival player breaks the block at {@code rel} holding {@code tool}; returns the items that dropped. */
	private static List<ItemStack> mine(GameTestHelper h, BlockPos rel, Item tool) {
		ServerPlayer player = h.makeMockServerPlayerInLevel();
		player.setGameMode(GameType.SURVIVAL);
		player.setItemInHand(InteractionHand.MAIN_HAND, tool == null ? ItemStack.EMPTY : new ItemStack(tool));
		BlockPos abs = h.absolutePos(rel);
		h.assertTrue(player.gameMode.destroyBlock(abs), "Player couldn't break the block at " + rel);
		List<ItemStack> drops = h.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(abs).inflate(1.5)).stream()
				.map(ItemEntity::getItem).toList();
		player.discard();
		return drops;
	}

	@GameTest
	public void liftBlockNeedsAPickaxe(GameTestHelper h) {
		floor(h, Blocks.STONE);
		h.setBlock(2, 1, 2, ModBlocks.LIFT_BLOCK);
		h.setBlock(5, 1, 5, ModBlocks.LIFT_BLOCK);

		List<ItemStack> byHand = mine(h, new BlockPos(2, 1, 2), null);
		h.assertTrue(byHand.isEmpty(), "Lift Block broken by hand should drop nothing, dropped " + byHand);
		h.assertBlockPresent(Blocks.AIR, 2, 1, 2);

		List<ItemStack> withPick = mine(h, new BlockPos(5, 1, 5), Items.IRON_PICKAXE);
		h.assertTrue(withPick.stream().anyMatch(s -> s.is(ModBlocks.LIFT_BLOCK.asItem())),
				"Lift Block broken with a pickaxe should drop itself, dropped " + withPick);
		h.succeed();
	}

	@GameTest
	public void helmDropsWithAnyTool(GameTestHelper h) {
		floor(h, Blocks.STONE);
		h.setBlock(2, 1, 2, ModBlocks.HELM);
		h.setBlock(5, 1, 5, ModBlocks.HELM);

		List<ItemStack> byHand = mine(h, new BlockPos(2, 1, 2), null);
		h.assertTrue(byHand.stream().anyMatch(s -> s.is(ModBlocks.HELM.asItem())), "Helm broken by hand should drop itself, dropped " + byHand);

		List<ItemStack> withAxe = mine(h, new BlockPos(5, 1, 5), Items.IRON_AXE);
		h.assertTrue(withAxe.stream().anyMatch(s -> s.is(ModBlocks.HELM.asItem())), "Helm broken with an axe should drop itself, dropped " + withAxe);
		h.succeed();
	}

	@GameTest
	public void coalBurnsLikeInAFurnace(GameTestHelper h) {
		ServerLevel level = h.getLevel();
		h.assertValueEqual(FuelHelper.fuelValue(level, new ItemStack(Items.COAL)), 1600, "coal burn time");
		h.assertValueEqual(FuelHelper.fuelValue(level, new ItemStack(Items.STICK)), 100, "stick burn time");
		h.assertValueEqual(FuelHelper.fuelValue(level, new ItemStack(Items.DIRT)), 0, "dirt is not fuel");
		h.succeed();
	}
}
