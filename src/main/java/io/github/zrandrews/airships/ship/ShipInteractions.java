package io.github.zrandrews.airships.ship;

import org.jspecify.annotations.Nullable;

import io.github.zrandrews.airships.mixin.AbstractFurnaceBlockEntityAccessor;
import io.github.zrandrews.airships.mixin.BrewingStandBlockEntityAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.BlastFurnaceMenu;
import net.minecraft.world.inventory.BrewingStandMenu;
import net.minecraft.world.inventory.CartographyTableMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.DispenserMenu;
import net.minecraft.world.inventory.FurnaceMenu;
import net.minecraft.world.inventory.GrindstoneMenu;
import net.minecraft.world.inventory.HopperMenu;
import net.minecraft.world.inventory.LoomMenu;
import net.minecraft.world.inventory.MenuConstructor;
import net.minecraft.world.inventory.PlayerEnderChestContainer;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.inventory.SmithingMenu;
import net.minecraft.world.inventory.SmokerMenu;
import net.minecraft.world.inventory.StonecutterMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BrewingStandBlock;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;

/**
 * Right-clicking blocks on a flying ship (#25): open chests and other containers, open doors,
 * trapdoors and fence gates, flip levers, press buttons, use crafting tables.
 *
 * <p>Ship blocks aren't real world blocks, so vanilla can't do this. Containers work on a live
 * copy of the block entity that the ship keeps and saves back into its block data.
 */
public final class ShipInteractions {
	/** How far past the player's normal reach a ship block may be (the ship moves; packets lag). */
	public static final double REACH_SLACK = 2.0;

	private ShipInteractions() {
	}

	private enum ContainerKind { CHEST, BARREL, SHULKER_BOX, DISPENSER, HOPPER, FURNACE, BREWING_STAND }

	private static @Nullable ContainerKind containerKind(BlockState state) {
		Block block = state.getBlock();
		if (block instanceof ChestBlock) return ContainerKind.CHEST; // also trapped and copper chests
		if (block instanceof BarrelBlock) return ContainerKind.BARREL;
		if (block instanceof ShulkerBoxBlock) return ContainerKind.SHULKER_BOX;
		if (block instanceof DispenserBlock) return ContainerKind.DISPENSER; // also droppers
		if (block instanceof HopperBlock) return ContainerKind.HOPPER;
		if (block instanceof AbstractFurnaceBlock) return ContainerKind.FURNACE; // also smokers, blast furnaces
		if (block instanceof BrewingStandBlock) return ContainerKind.BREWING_STAND;
		return null;
	}

	/** Workstations with no stored contents: the screen is all there is (#31). */
	private enum Workstation { CRAFTING_TABLE, STONECUTTER, LOOM, CARTOGRAPHY_TABLE, GRINDSTONE, SMITHING_TABLE, ANVIL }

	private static @Nullable Workstation workstation(BlockState state) {
		if (state.is(Blocks.CRAFTING_TABLE)) return Workstation.CRAFTING_TABLE;
		if (state.is(Blocks.STONECUTTER)) return Workstation.STONECUTTER;
		if (state.is(Blocks.LOOM)) return Workstation.LOOM;
		if (state.is(Blocks.CARTOGRAPHY_TABLE)) return Workstation.CARTOGRAPHY_TABLE;
		if (state.is(Blocks.GRINDSTONE)) return Workstation.GRINDSTONE;
		if (state.is(Blocks.SMITHING_TABLE)) return Workstation.SMITHING_TABLE;
		if (state.is(BlockTags.ANVIL)) return Workstation.ANVIL;
		return null;
	}

	/** Whether right-clicking this block on a ship does something. The client uses this to decide whether to send a click. */
	public static boolean isUsable(BlockState state) {
		Block block = state.getBlock();
		if (block instanceof DoorBlock door) return door.type().canOpenByHand();
		if (block instanceof TrapDoorBlock) return !state.is(Blocks.IRON_TRAPDOOR);
		if (block instanceof FenceGateBlock || block instanceof LeverBlock || block instanceof ButtonBlock) return true;
		if (block instanceof EnderChestBlock) return true;
		return workstation(state) != null || containerKind(state) != null;
	}

	/** A player right-clicked the ship block at {@code local}. Server only. */
	public static void use(ShipEntity ship, BlockPos local, ServerPlayer player) {
		BlockState state = ship.getLocalState(local);
		if (state == null || !isUsable(state)) return;
		Block block = state.getBlock();

		if (block instanceof DoorBlock door) {
			boolean open = !state.getValue(DoorBlock.OPEN);
			ship.setLocalState(local, state.setValue(DoorBlock.OPEN, open));
			// The other half of the door.
			BlockPos other = state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER ? local.above() : local.below();
			BlockState otherState = ship.getLocalState(other);
			if (otherState != null && otherState.is(block)) ship.setLocalState(other, otherState.setValue(DoorBlock.OPEN, open));
			playSound(ship, local, open ? door.type().doorOpen() : door.type().doorClose());
		} else if (block instanceof TrapDoorBlock || block instanceof FenceGateBlock) {
			boolean open = !state.getValue(BlockStateProperties.OPEN);
			ship.setLocalState(local, state.setValue(BlockStateProperties.OPEN, open));
			if (block instanceof FenceGateBlock) {
				playSound(ship, local, open ? SoundEvents.FENCE_GATE_OPEN : SoundEvents.FENCE_GATE_CLOSE);
			} else {
				playSound(ship, local, open ? SoundEvents.WOODEN_TRAPDOOR_OPEN : SoundEvents.WOODEN_TRAPDOOR_CLOSE);
			}
		} else if (block instanceof LeverBlock) {
			boolean on = !state.getValue(LeverBlock.POWERED);
			ship.setLocalState(local, state.setValue(LeverBlock.POWERED, on));
			playSound(ship, local, SoundEvents.LEVER_CLICK);
		} else if (block instanceof ButtonBlock) {
			if (state.getValue(BlockStateProperties.POWERED)) return; // already pressed
			ship.setLocalState(local, state.setValue(BlockStateProperties.POWERED, true));
			ship.pressButton(local, state.is(BlockTags.WOODEN_BUTTONS) ? 30 : 20);
			playButtonSound(ship, local, state, true);
		} else if (block instanceof EnderChestBlock) {
			openEnderChest(ship, local, player);
		} else if (workstation(state) != null) {
			openWorkstation(ship, local, workstation(state), player);
		} else {
			openContainer(ship, local, state, player);
		}
	}

	static void playButtonSound(ShipEntity ship, BlockPos local, BlockState state, boolean on) {
		boolean wooden = state.is(BlockTags.WOODEN_BUTTONS);
		SoundEvent sound = wooden
				? (on ? SoundEvents.WOODEN_BUTTON_CLICK_ON : SoundEvents.WOODEN_BUTTON_CLICK_OFF)
				: (on ? SoundEvents.STONE_BUTTON_CLICK_ON : SoundEvents.STONE_BUTTON_CLICK_OFF);
		playSound(ship, local, sound);
	}

	private static void playSound(ShipEntity ship, BlockPos local, SoundEvent sound) {
		Vec3 c = ship.worldCenter(local);
		ship.level().playSound(null, c.x, c.y, c.z, sound, SoundSource.BLOCKS, 1.0f, 1.0f);
	}

	/** True while the player is close enough to keep using the ship block. */
	private static boolean inReach(ShipEntity ship, BlockPos local, Player player) {
		double reach = player.blockInteractionRange() + REACH_SLACK;
		return !ship.isRemoved() && player.isAlive() && player.getEyePosition().distanceToSqr(ship.worldCenter(local)) <= reach * reach;
	}

	/**
	 * Workstation menus check the world block at their access position, which a ship block isn't, so
	 * each one is subclassed to stay valid while the player is near the ship block instead. The access
	 * points at the player: it only decides where leftover input items go when the screen closes (back
	 * to the player) and where the anvil sound plays. A ship anvil never takes damage from use.
	 */
	private static void openWorkstation(ShipEntity ship, BlockPos local, Workstation kind, ServerPlayer player) {
		ContainerLevelAccess access = ContainerLevelAccess.create(player.level(), player.blockPosition());
		MenuConstructor menu = switch (kind) {
			case CRAFTING_TABLE -> (id, inv, p) -> new CraftingMenu(id, inv, access) {
				@Override
				public boolean stillValid(Player user) {
					return inReach(ship, local, user);
				}
			};
			case STONECUTTER -> (id, inv, p) -> new StonecutterMenu(id, inv, access) {
				@Override
				public boolean stillValid(Player user) {
					return inReach(ship, local, user);
				}
			};
			case LOOM -> (id, inv, p) -> new LoomMenu(id, inv, access) {
				@Override
				public boolean stillValid(Player user) {
					return inReach(ship, local, user);
				}
			};
			case CARTOGRAPHY_TABLE -> (id, inv, p) -> new CartographyTableMenu(id, inv, access) {
				@Override
				public boolean stillValid(Player user) {
					return inReach(ship, local, user);
				}
			};
			case GRINDSTONE -> (id, inv, p) -> new GrindstoneMenu(id, inv, access) {
				@Override
				public boolean stillValid(Player user) {
					return inReach(ship, local, user);
				}
			};
			case SMITHING_TABLE -> (id, inv, p) -> new SmithingMenu(id, inv, access) {
				@Override
				public boolean stillValid(Player user) {
					return inReach(ship, local, user);
				}
			};
			case ANVIL -> (id, inv, p) -> new AnvilMenu(id, inv, access) {
				@Override
				public boolean stillValid(Player user) {
					return inReach(ship, local, user);
				}
			};
		};
		Component title = Component.translatable(switch (kind) {
			case CRAFTING_TABLE -> "container.crafting";
			case STONECUTTER -> "container.stonecutter";
			case LOOM -> "container.loom";
			case CARTOGRAPHY_TABLE -> "container.cartography_table";
			case GRINDSTONE -> "container.grindstone_title";
			case SMITHING_TABLE -> "container.upgrade";
			case ANVIL -> "container.repair";
		});
		player.openMenu(new SimpleMenuProvider(menu, title));
	}

	/** The ender chest shows the player's own ender inventory; nothing is stored on the ship. */
	private static void openEnderChest(ShipEntity ship, BlockPos local, ServerPlayer player) {
		PlayerEnderChestContainer ender = player.getEnderChestInventory();
		// Vanilla ties the screen to a world ender chest block entity; there isn't one here.
		ender.setActiveChest(null);
		Container container = new ShipContainer(ship, local, ender, null);
		player.openMenu(new SimpleMenuProvider((id, inv, p) -> ChestMenu.threeRows(id, inv, container),
				Component.translatable("container.enderchest")));
		playSound(ship, local, SoundEvents.ENDER_CHEST_OPEN);
	}

	private static void openContainer(ShipEntity ship, BlockPos local, BlockState state, ServerPlayer player) {
		ContainerKind kind = containerKind(state);
		if (kind == null || !(ship.liveBlockEntity(local) instanceof Container)) return;
		BlockEntity live = ship.liveBlockEntity(local);
		Container container = new ShipContainer(ship, local);
		Component title = state.getBlock().getName();
		SoundEvent openSound = SoundEvents.CHEST_OPEN;

		AbstractContainerMenuFactory factory;
		switch (kind) {
			case CHEST -> {
				Container other = otherChestHalf(ship, local, state);
				if (other != null) {
					// Vanilla puts the left half's slots first.
					Container both = state.getValue(ChestBlock.TYPE) == ChestType.RIGHT
							? new CompoundContainer(container, other)
							: new CompoundContainer(other, container);
					factory = (id, inv) -> ChestMenu.sixRows(id, inv, both);
					title = Component.translatable("container.chestDouble");
				} else {
					factory = (id, inv) -> ChestMenu.threeRows(id, inv, container);
				}
			}
			case BARREL -> {
				factory = (id, inv) -> ChestMenu.threeRows(id, inv, container);
				openSound = SoundEvents.BARREL_OPEN;
			}
			case SHULKER_BOX -> {
				factory = (id, inv) -> new ShulkerBoxMenu(id, inv, container);
				openSound = SoundEvents.SHULKER_BOX_OPEN;
			}
			case DISPENSER -> factory = (id, inv) -> new DispenserMenu(id, inv, container);
			case HOPPER -> factory = (id, inv) -> new HopperMenu(id, inv, container);
			case FURNACE -> {
				ContainerData data = ((AbstractFurnaceBlockEntityAccessor) live).airships$getDataAccess();
				if (state.is(Blocks.SMOKER)) {
					factory = (id, inv) -> new SmokerMenu(id, inv, container, data);
				} else if (state.is(Blocks.BLAST_FURNACE)) {
					factory = (id, inv) -> new BlastFurnaceMenu(id, inv, container, data);
				} else {
					factory = (id, inv) -> new FurnaceMenu(id, inv, container, data);
				}
				openSound = null;
			}
			case BREWING_STAND -> {
				ContainerData data = ((BrewingStandBlockEntityAccessor) live).airships$getDataAccess();
				factory = (id, inv) -> new BrewingStandMenu(id, inv, container, data);
				openSound = null;
			}
			default -> {
				return;
			}
		}
		if (kind == ContainerKind.DISPENSER || kind == ContainerKind.HOPPER) openSound = null;

		AbstractContainerMenuFactory finalFactory = factory;
		player.openMenu(new SimpleMenuProvider((id, inventory, p) -> finalFactory.create(id, inventory), title));
		if (openSound != null) playSound(ship, local, openSound);
	}

	@FunctionalInterface
	private interface AbstractContainerMenuFactory {
		AbstractContainerMenu create(int id, net.minecraft.world.entity.player.Inventory inventory);
	}

	/** The other half of a double chest on the same ship, or null. */
	private static @Nullable Container otherChestHalf(ShipEntity ship, BlockPos local, BlockState state) {
		if (state.getValue(ChestBlock.TYPE) == ChestType.SINGLE) return null;
		Direction toOther = ChestBlock.getConnectedDirection(state);
		BlockPos other = local.relative(toOther);
		BlockState otherState = ship.getLocalState(other);
		if (otherState == null || !otherState.is(state.getBlock())) return null;
		return ship.liveBlockEntity(other) instanceof Container ? new ShipContainer(ship, other) : null;
	}

	/**
	 * A ship container as a menu sees it: reads and writes the ship's live block entity (or, for an
	 * ender chest, the player's ender inventory), and stays open while the player is near the
	 * (moving) ship block.
	 */
	private static final class ShipContainer implements Container {
		private final ShipEntity ship;
		private final BlockPos local;
		private final @Nullable BlockEntity blockEntity;
		private final Container inner;

		ShipContainer(ShipEntity ship, BlockPos local) {
			this(ship, local, (Container) ship.liveBlockEntity(local), ship.liveBlockEntity(local));
		}

		ShipContainer(ShipEntity ship, BlockPos local, Container inner, @Nullable BlockEntity blockEntity) {
			this.ship = ship;
			this.local = local.immutable();
			this.blockEntity = blockEntity;
			this.inner = inner;
		}

		@Override
		public int getContainerSize() {
			return inner.getContainerSize();
		}

		@Override
		public boolean isEmpty() {
			return inner.isEmpty();
		}

		@Override
		public ItemStack getItem(int slot) {
			return inner.getItem(slot);
		}

		@Override
		public ItemStack removeItem(int slot, int count) {
			return inner.removeItem(slot, count);
		}

		@Override
		public ItemStack removeItemNoUpdate(int slot) {
			return inner.removeItemNoUpdate(slot);
		}

		@Override
		public void setItem(int slot, ItemStack stack) {
			inner.setItem(slot, stack);
		}

		@Override
		public int getMaxStackSize() {
			return inner.getMaxStackSize();
		}

		@Override
		public boolean canPlaceItem(int slot, ItemStack stack) {
			return inner.canPlaceItem(slot, stack);
		}

		@Override
		public void setChanged() {
			inner.setChanged();
		}

		@Override
		public boolean stillValid(Player player) {
			return (blockEntity == null || ship.isLiveBlockEntity(local, blockEntity)) && inReach(ship, local, player);
		}

		@Override
		public void clearContent() {
			inner.clearContent();
		}
	}
}
