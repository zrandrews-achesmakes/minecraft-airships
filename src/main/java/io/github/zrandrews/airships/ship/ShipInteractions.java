package io.github.zrandrews.airships.ship;

import org.jspecify.annotations.Nullable;

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
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.DispenserMenu;
import net.minecraft.world.inventory.HopperMenu;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.DoorBlock;
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

	private enum ContainerKind { CHEST, BARREL, SHULKER_BOX, DISPENSER, HOPPER }

	private static @Nullable ContainerKind containerKind(BlockState state) {
		Block block = state.getBlock();
		if (block instanceof ChestBlock) return ContainerKind.CHEST; // also trapped chests
		if (block instanceof BarrelBlock) return ContainerKind.BARREL;
		if (block instanceof ShulkerBoxBlock) return ContainerKind.SHULKER_BOX;
		if (block instanceof DispenserBlock) return ContainerKind.DISPENSER; // also droppers
		if (block instanceof HopperBlock) return ContainerKind.HOPPER;
		return null;
	}

	/** Whether right-clicking this block on a ship does something. The client uses this to decide whether to send a click. */
	public static boolean isUsable(BlockState state) {
		Block block = state.getBlock();
		if (block instanceof DoorBlock door) return door.type().canOpenByHand();
		if (block instanceof TrapDoorBlock) return !state.is(Blocks.IRON_TRAPDOOR);
		if (block instanceof FenceGateBlock || block instanceof LeverBlock || block instanceof ButtonBlock) return true;
		if (state.is(Blocks.CRAFTING_TABLE)) return true;
		return containerKind(state) != null;
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
		} else if (state.is(Blocks.CRAFTING_TABLE)) {
			openCraftingTable(ship, local, player);
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

	private static void openCraftingTable(ShipEntity ship, BlockPos local, ServerPlayer player) {
		// The access only decides where leftover grid items go when the screen closes: back to the player.
		ContainerLevelAccess access = ContainerLevelAccess.create(player.level(), player.blockPosition());
		player.openMenu(new SimpleMenuProvider((id, inventory, p) -> new CraftingMenu(id, inventory, access) {
			@Override
			public boolean stillValid(Player user) {
				return inReach(ship, local, user);
			}
		}, Component.translatable("container.crafting")));
	}

	private static void openContainer(ShipEntity ship, BlockPos local, BlockState state, ServerPlayer player) {
		ContainerKind kind = containerKind(state);
		if (kind == null || !(ship.liveBlockEntity(local) instanceof Container)) return;
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
			default -> {
				return;
			}
		}

		AbstractContainerMenuFactory finalFactory = factory;
		player.openMenu(new SimpleMenuProvider((id, inventory, p) -> finalFactory.create(id, inventory), title));
		if (kind != ContainerKind.DISPENSER && kind != ContainerKind.HOPPER) playSound(ship, local, openSound);
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
	 * A ship container as a menu sees it: reads and writes the ship's live block entity, and
	 * stays open while the player is near the (moving) ship block.
	 */
	private static final class ShipContainer implements Container {
		private final ShipEntity ship;
		private final BlockPos local;
		private final BlockEntity blockEntity;
		private final Container inner;

		ShipContainer(ShipEntity ship, BlockPos local) {
			this.ship = ship;
			this.local = local.immutable();
			this.blockEntity = ship.liveBlockEntity(local);
			this.inner = (Container) blockEntity;
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
			return ship.isLiveBlockEntity(local, blockEntity) && inReach(ship, local, player);
		}

		@Override
		public void clearContent() {
			inner.clearContent();
		}
	}
}
