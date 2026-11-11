package io.github.zrandrews.airships.ship;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.InterpolationHandler;
import net.minecraft.world.entity.LinearInterpolationHandler;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import io.github.zrandrews.airships.AirshipsConfig;
import io.github.zrandrews.airships.AirshipsMod;
import io.github.zrandrews.airships.block.HelmBlockEntity;
import io.github.zrandrews.airships.network.AirshipsNetworking;
import io.github.zrandrews.airships.network.AirshipsNetworking.PilotActionPayload;
import io.github.zrandrews.airships.registry.ModBlocks;

/**
 * A flying ship. The entity sits where the Helm block was; every other block
 * is stored relative to it in {@link #blocks}.
 */
public class ShipEntity extends Entity {
	private static final EntityDataAccessor<Integer> FUEL = SynchedEntityData.defineId(ShipEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Integer> LIFT = SynchedEntityData.defineId(ShipEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Integer> MASS = SynchedEntityData.defineId(ShipEntity.class, EntityDataSerializers.INT);

	private static final String BLOCKS_KEY = "ship_blocks";
	private static final String FUEL_KEY = "fuel";

	/** Ticks without pilot input before the ship stops (pilot lagging or gone). */
	private static final int INPUT_TIMEOUT = 5;
	/** How quickly the ship speeds up / slows down (0-1 per tick). */
	private static final double ACCELERATION = 0.08;
	/** How long a 90-degree turn takes to swing round (#27). */
	public static final int TURN_TICKS = 10;

	/** Tick of the last turn (server: rate limit; client: animation start). */
	private int lastTurnTick = -1000;
	/** Client: 1 = last turn was to the right, -1 = left. */
	private int turnDirection;

	private List<ShipBlock> blocks = new ArrayList<>();
	private final Map<BlockPos, BlockState> localStates = new HashMap<>();
	/** Server only: block entities someone is using in flight (open chests...), by local position. Saved back into {@link #blocks}. */
	private final Map<BlockPos, BlockEntity> liveBlockEntities = new HashMap<>();
	/** Server only: pressed buttons and the ticks until they pop back out. */
	private final Map<BlockPos, Integer> buttonTimers = new HashMap<>();
	private int minX, minY, minZ, maxX, maxY, maxZ;

	private int fuel;
	private double fuelDebt;
	private Vec3 velocity = Vec3.ZERO;

	private int inForward, inStrafe, inVertical;
	private float inYaw;
	private int inputAge = INPUT_TIMEOUT + 1;
	/** When set, input never times out and no pilot is needed. Used by automated tests. */
	private boolean autopilot;

	private Vec3 lastTickPos = null;
	private Vec3 tickDelta = Vec3.ZERO;

	/** Client-only render cache, rebuilt when the shape changes. Typed Object to keep client classes out of common code. */
	public @Nullable Object renderCache;
	public int shapeVersion;

	public ShipEntity(EntityType<? extends ShipEntity> type, Level level) {
		super(type, level);
		this.noPhysics = true;
	}

	/** Smooth out position updates on the client (same as vanilla boats). */
	@Override
	protected InterpolationHandler createInterpolationHandler() {
		return LinearInterpolationHandler.create(this, 3);
	}

	// ------------------------------------------------------------------ shape

	public void initShip(List<ShipBlock> newBlocks, int startFuel) {
		setBlocks(newBlocks);
		this.fuel = startFuel;
		syncStats();
	}

	public void setBlocks(List<ShipBlock> newBlocks) {
		this.blocks = new ArrayList<>(newBlocks);
		this.localStates.clear();

		minX = minY = minZ = Integer.MAX_VALUE;
		maxX = maxY = maxZ = Integer.MIN_VALUE;

		for (ShipBlock block : this.blocks) {
			BlockPos p = block.pos();
			localStates.put(p.immutable(), block.state());
			minX = Math.min(minX, p.getX());
			minY = Math.min(minY, p.getY());
			minZ = Math.min(minZ, p.getZ());
			maxX = Math.max(maxX, p.getX());
			maxY = Math.max(maxY, p.getY());
			maxZ = Math.max(maxZ, p.getZ());
		}

		if (this.blocks.isEmpty()) {
			minX = minY = minZ = maxX = maxY = maxZ = 0;
		}

		this.renderCache = null;
		this.shapeVersion++;
	}

	public boolean hasBlocks() {
		return !blocks.isEmpty();
	}

	public List<ShipBlock> getBlocks() {
		return blocks;
	}

	public @Nullable BlockState getLocalState(BlockPos localPos) {
		return localStates.get(localPos);
	}

	/** The box around the whole ship, in world coordinates. */
	public AABB worldBounds() {
		return worldBoundsAt(position());
	}

	private AABB worldBoundsAt(Vec3 pos) {
		return new AABB(
				pos.x - 0.5 + minX, pos.y + minY, pos.z - 0.5 + minZ,
				pos.x + 0.5 + maxX, pos.y + 1.0 + maxY, pos.z + 0.5 + maxZ
		);
	}

	private static AABB blockBox(BlockPos local, Vec3 shipPos) {
		double x = shipPos.x - 0.5 + local.getX();
		double y = shipPos.y + local.getY();
		double z = shipPos.z - 0.5 + local.getZ();
		return new AABB(x, y, z, x + 1, y + 1, z + 1);
	}

	public Vec3 getTickDelta() {
		return tickDelta;
	}

	/** World position of the minimum corner of the cell at {@code local}. */
	public Vec3 worldCorner(BlockPos local) {
		return new Vec3(getX() - 0.5 + local.getX(), getY() + local.getY(), getZ() - 0.5 + local.getZ());
	}

	/** Centre of the ship block at {@code local}, in world coordinates. */
	public Vec3 worldCenter(BlockPos local) {
		return worldCorner(local).add(0.5, 0.5, 0.5);
	}

	// ------------------------------------------------------------------ using blocks in flight (#25)

	/** A ship block hit by a ray: its local position and the squared distance from the ray start. */
	public record BlockHit(BlockPos local, double distanceSqr) {
	}

	/**
	 * The closest ship block (not the Helm, which the entity itself handles) whose outline the
	 * ray from {@code from} to {@code to} passes through, or null.
	 */
	public @Nullable BlockHit clipBlocks(Vec3 from, Vec3 to) {
		AABB rayBox = new AABB(from, to);
		if (!worldBounds().intersects(rayBox)) return null;

		Vec3 origin = worldCorner(BlockPos.ZERO);
		Vec3 localFrom = from.subtract(origin), localTo = to.subtract(origin);
		AABB localRay = new AABB(localFrom, localTo).inflate(1.0);

		BlockHit best = null;
		for (ShipBlock block : blocks) {
			BlockPos p = block.pos();
			if (p.equals(BlockPos.ZERO) || !localRay.contains(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5)) continue;

			var shape = block.state().getShape(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
			if (shape.isEmpty()) continue;

			var hit = shape.clip(localFrom, localTo, p);
			if (hit == null) continue;

			double d = hit.getLocation().distanceToSqr(localFrom);
			if (best == null || d < best.distanceSqr()) best = new BlockHit(p.immutable(), d);
		}
		return best;
	}

	/** Changes one ship block in flight (a door opening...) and tells every client. */
	public void setLocalState(BlockPos local, BlockState state) {
		List<ShipBlock> updated = new ArrayList<>(blocks.size());
		for (ShipBlock block : blocks) {
			updated.add(block.pos().equals(local) ? new ShipBlock(block.pos(), state, block.blockEntity()) : block);
		}
		setBlocks(updated);
		AirshipsNetworking.sendShapeToTrackers(this);
	}

	/**
	 * The live block entity for a ship block (server only), created from the saved data on first use.
	 * Its level is left unset, so it can't touch the world around it.
	 */
	public @Nullable BlockEntity liveBlockEntity(BlockPos local) {
		BlockEntity live = liveBlockEntities.get(local);
		if (live != null) return live;

		for (ShipBlock block : blocks) {
			if (!block.pos().equals(local) || !(block.state().getBlock() instanceof net.minecraft.world.level.block.EntityBlock entityBlock)) continue;

			live = block.blockEntity()
					.map(data -> BlockEntity.loadStatic(local, block.state(), data, level().registryAccess()))
					.orElseGet(() -> entityBlock.newBlockEntity(local, block.state()));
			if (live != null) liveBlockEntities.put(local.immutable(), live);
			return live;
		}
		return null;
	}

	/** True while {@code be} is still this ship's live block entity at {@code local}. */
	public boolean isLiveBlockEntity(BlockPos local, BlockEntity be) {
		return !isRemoved() && liveBlockEntities.get(local) == be;
	}

	/** Writes live block entities (chest contents changed in flight) back into the ship's block list. */
	private void flushLiveBlockEntities() {
		if (liveBlockEntities.isEmpty() || level().isClientSide()) return;

		List<ShipBlock> updated = new ArrayList<>(blocks.size());
		for (ShipBlock block : blocks) {
			BlockEntity live = liveBlockEntities.get(block.pos());
			updated.add(live == null ? block
					: new ShipBlock(block.pos(), block.state(), Optional.of(live.saveWithFullMetadata(level().registryAccess()))));
		}
		blocks = updated;
	}

	/** Presses a button on deck; it pops back out after {@code ticks}. */
	public void pressButton(BlockPos local, int ticks) {
		buttonTimers.put(local.immutable(), ticks);
	}

	private void tickButtons() {
		if (buttonTimers.isEmpty()) return;

		var it = buttonTimers.entrySet().iterator();
		while (it.hasNext()) {
			var entry = it.next();
			if (entry.getValue() > 1) {
				entry.setValue(entry.getValue() - 1);
				continue;
			}
			it.remove();
			BlockState state = localStates.get(entry.getKey());
			if (state != null && state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.POWERED)) {
				setLocalState(entry.getKey(), state.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.POWERED, false));
				ShipInteractions.playButtonSound(this, entry.getKey(), state, false);
			}
		}
	}

	// ------------------------------------------------------------------ stats (synced for the pilot's HUD)

	public int getFuel() {
		return level().isClientSide() ? entityData.get(FUEL) : fuel;
	}

	public int getLift() {
		return entityData.get(LIFT);
	}

	public int getMass() {
		return entityData.get(MASS);
	}

	private void syncStats() {
		int lifters = 0;
		for (ShipBlock block : blocks) {
			if (block.state().is(ModBlocks.LIFT_BLOCK)) lifters++;
		}

		entityData.set(FUEL, fuel);
		entityData.set(LIFT, lifters * AirshipsConfig.get().liftPerLiftBlock);
		entityData.set(MASS, blocks.size());
	}

	// ------------------------------------------------------------------ entity basics

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		builder.define(FUEL, 0);
		builder.define(LIFT, 0);
		builder.define(MASS, 0);
	}

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
		return false; // Ships can't be broken by hitting them. Land them first.
	}

	@Override
	public boolean isPickable() {
		return !isRemoved();
	}

	@Override
	public boolean shouldRenderAtSqrDistance(double distance) {
		double span = Math.max(maxX - minX, Math.max(maxY - minY, maxZ - minZ));
		double range = 192.0 + span;
		return distance < range * range;
	}

	@Override
	protected boolean canAddPassenger(Entity passenger) {
		return getPassengers().isEmpty();
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput output) {
		flushLiveBlockEntities();
		output.store(BLOCKS_KEY, ShipBlock.LIST_CODEC, blocks);
		output.putInt(FUEL_KEY, fuel);
	}

	@Override
	protected void readAdditionalSaveData(ValueInput input) {
		setBlocks(input.read(BLOCKS_KEY, ShipBlock.LIST_CODEC).orElse(List.of()));
		fuel = input.getIntOr(FUEL_KEY, 0);
		syncStats();
	}

	// ------------------------------------------------------------------ networking

	public AirshipsNetworking.ShipShapePayload createShapePayload() {
		return createShapePayload(0);
	}

	/** @param turn 1 if this shape is the result of a right turn, -1 for left, 0 otherwise (clients animate turns) */
	public AirshipsNetworking.ShipShapePayload createShapePayload(int turn) {
		List<ShipBlock> forClients = new ArrayList<>(blocks.size());
		for (ShipBlock block : blocks) {
			forClients.add(new ShipBlock(block.pos(), block.state(), block.blockEntity().flatMap(data -> clientBlockEntityTag(block, data))));
		}

		CompoundTag shape = new CompoundTag();
		Tag list = ShipBlock.CLIENT_LIST_CODEC.encodeStart(NbtOps.INSTANCE, forClients).getOrThrow();
		shape.put("blocks", list);
		if (turn != 0) shape.putInt("turn", turn);
		return new AirshipsNetworking.ShipShapePayload(getId(), shape);
	}

	/**
	 * What a client needs to draw a block entity: its update tag (sign text, banner patterns...)
	 * plus its type id. Chest contents are not included.
	 */
	private Optional<CompoundTag> clientBlockEntityTag(ShipBlock block, CompoundTag data) {
		try {
			BlockEntity be = BlockEntity.loadStatic(BlockPos.ZERO, block.state(), data, level().registryAccess());
			if (be == null) return Optional.empty();
			CompoundTag tag = be.getUpdateTag(level().registryAccess());
			data.getString("id").ifPresent(id -> tag.putString("id", id));
			return Optional.of(tag);
		} catch (RuntimeException e) {
			AirshipsMod.LOGGER.debug("Couldn't build client data for {}", block.state(), e);
			return Optional.empty();
		}
	}

	/** Called on the client when the shape packet arrives. */
	public void applyShape(CompoundTag shape) {
		Tag list = shape.get("blocks");
		if (list == null) return;

		ShipBlock.CLIENT_LIST_CODEC.parse(NbtOps.INSTANCE, list)
				.resultOrPartial(err -> AirshipsMod.LOGGER.warn("Bad ship shape: {}", err))
				.ifPresent(this::setBlocks);

		int turn = shape.getIntOr("turn", 0);
		if (turn != 0) {
			turnDirection = turn;
			lastTurnTick = tickCount;
		}
	}

	/**
	 * Client: how far (degrees about +Y) to rotate the drawn ship back toward its old heading.
	 * Starts at ±90 when a turn arrives and eases to 0 over {@link #TURN_TICKS}.
	 */
	public float turnAngle(float partialTick) {
		float t = (tickCount - lastTurnTick + partialTick) / TURN_TICKS;
		if (turnDirection == 0 || t >= 1f) return 0f;
		if (t <= 0f) return turnDirection * 90f;
		float eased = t * t * (3f - 2f * t); // smoothstep
		return turnDirection * 90f * (1f - eased);
	}

	/** Client: true while a turn is still being animated. */
	public boolean isTurning() {
		return turnDirection != 0 && tickCount - lastTurnTick < TURN_TICKS;
	}

	public void setPilotInput(int forward, int strafe, int vertical, float yaw) {
		this.inForward = Mth.clamp(forward, -1, 1);
		this.inStrafe = Mth.clamp(strafe, -1, 1);
		this.inVertical = Mth.clamp(vertical, -1, 1);
		this.inYaw = yaw;
		this.inputAge = 0;
	}

	/** Drive the ship without a pilot (automated tests). Pass all zeros and false to stop. */
	public void setAutopilot(boolean enabled, int forward, int strafe, int vertical, float yaw) {
		this.autopilot = enabled;
		setPilotInput(forward, strafe, vertical, yaw);
	}

	public int getServerFuel() {
		return fuel;
	}

	public void setServerFuel(int fuel) {
		this.fuel = Math.max(0, fuel);
		entityData.set(FUEL, this.fuel);
	}

	public Vec3 getVelocity() {
		return velocity;
	}

	public void handleAction(byte action, Player pilot) {
		switch (action) {
			case PilotActionPayload.ROTATE_LEFT -> report(pilot, turn(Rotation.COUNTERCLOCKWISE_90));
			case PilotActionPayload.ROTATE_RIGHT -> report(pilot, turn(Rotation.CLOCKWISE_90));
			case PilotActionPayload.LAND -> {
				BlockPos origin = landingOrigin();
				Component error = land();
				if (error != null) {
					pilot.sendOverlayMessage(error);
				} else {
					// Put the pilot on top of the helm.
					pilot.teleportTo(origin.getX() + 0.5, origin.getY() + 1.0, origin.getZ() + 0.5);
					pilot.sendOverlayMessage(Component.translatable("message.airships.landed"));
				}
			}
			default -> {
			}
		}
	}

	/** Right-clicking the helm of a flying ship. */
	public void onPlayerUse(Player player, InteractionHand hand) {
		ItemStack stack = player.getItemInHand(hand);
		int burn = FuelHelper.fuelValue(level(), stack);

		if (burn > 0) {
			if (fuel + burn > AirshipsConfig.get().maxFuel) {
				player.sendOverlayMessage(Component.translatable("message.airships.fuel_full"));
				return;
			}

			fuel += burn;
			FuelHelper.consumeOne(player, hand, stack);
			entityData.set(FUEL, fuel);
			player.sendOverlayMessage(Component.translatable("message.airships.fuel", FuelHelper.percent(fuel)));
			return;
		}

		if (getPassengers().isEmpty() && !player.isPassenger()) {
			player.startRiding(this, true, true);
		}
	}

	// ------------------------------------------------------------------ ticking

	@Override
	public void tick() {
		super.tick();
		ShipTracker.track(this);

		Vec3 before = lastTickPos == null ? position() : lastTickPos;

		if (!level().isClientSide()) {
			if (blocks.isEmpty()) {
				discard();
				return;
			}

			serverMove();
			carryEntities(before);
			tickButtons();
		}

		tickDelta = position().subtract(before);
		lastTickPos = position();
	}

	private void serverMove() {
		AirshipsConfig cfg = AirshipsConfig.get();
		Entity pilot = getFirstPassenger();

		if (!autopilot && (!(pilot instanceof Player) || ++inputAge > INPUT_TIMEOUT)) {
			inForward = inStrafe = inVertical = 0;
		}

		// Desired velocity from the pilot's keys, relative to where they're looking.
		double fx = 0, fz = 0;
		if (inForward != 0 || inStrafe != 0) {
			float yawRad = inYaw * Mth.DEG_TO_RAD;
			double sin = Mth.sin(yawRad), cos = Mth.cos(yawRad);
			// Minecraft yaw: 0 = south (+Z), 90 = west (-X)
			fx = -sin * inForward + cos * -inStrafe;
			fz = cos * inForward + sin * -inStrafe;
			double len = Math.sqrt(fx * fx + fz * fz);
			if (len > 0) {
				fx /= len;
				fz /= len;
			}
		}

		boolean hasFuel = fuel > 0;
		double liftRatio = getMass() <= 0 ? 1.0 : Math.min(1.5, getLift() / (double) getMass());
		double speed = cfg.horizontalSpeed * liftRatio;

		Vec3 target = new Vec3(fx * speed, inVertical * cfg.verticalSpeed, fz * speed);

		if (!hasFuel) {
			// Out of fuel: no engines. You can still sink slowly to land.
			target = new Vec3(0, Math.min(0, target.y), 0);
			if (pilot instanceof Player player && tickCount % 40 == 0) {
				// Keybind components are resolved on the player's client, so they show their own keys.
				player.sendOverlayMessage(Component.translatable("message.airships.out_of_fuel",
						Component.keybind("key.airships.descend"), Component.keybind("key.airships.land")));
			}
		}

		velocity = velocity.add(target.subtract(velocity).scale(ACCELERATION));
		if (velocity.lengthSqr() < 1.0E-4) velocity = Vec3.ZERO;

		if (velocity == Vec3.ZERO) return;

		Vec3 start = position();
		double dx = moveAxis(Direction.Axis.X, velocity.x);
		double dy = moveAxis(Direction.Axis.Y, velocity.y);
		double dz = moveAxis(Direction.Axis.Z, velocity.z);

		// Stop the axes that hit something.
		velocity = new Vec3(dx == 0 ? 0 : velocity.x, dy == 0 ? 0 : velocity.y, dz == 0 ? 0 : velocity.z);

		if (position().distanceToSqr(start) > 1.0E-8) {
			burnFuel(cfg);
		}
	}

	private void burnFuel(AirshipsConfig cfg) {
		fuelDebt += cfg.fuelPerMovingTick + blocks.size() * cfg.fuelPerBlockPerMovingTick;
		int whole = (int) fuelDebt;
		if (whole > 0) {
			fuel = Math.max(0, fuel - whole);
			fuelDebt -= whole;
		}

		if (tickCount % 10 == 0 || fuel == 0) {
			entityData.set(FUEL, fuel);
		}
	}

	/**
	 * Try to move along one axis; if the hull would hit terrain, try smaller steps.
	 * Returns how far we actually moved.
	 */
	private double moveAxis(Direction.Axis axis, double amount) {
		if (Math.abs(amount) < 1.0E-6) return 0;

		for (double step : new double[] {amount, amount * 0.5, amount * 0.25, amount * 0.1}) {
			Vec3 offset = switch (axis) {
				case X -> new Vec3(step, 0, 0);
				case Y -> new Vec3(0, step, 0);
				case Z -> new Vec3(0, 0, step);
			};

			if (isFree(position().add(offset), axis, step > 0)) {
				setPos(getX() + offset.x, getY() + offset.y, getZ() + offset.z);
				return step;
			}
		}

		return 0;
	}

	/** Checks the leading face of the hull against world blocks. */
	private boolean isFree(Vec3 shipPos, Direction.Axis axis, boolean positive) {
		Direction facing = Direction.fromAxisAndDirection(axis, positive ? Direction.AxisDirection.POSITIVE : Direction.AxisDirection.NEGATIVE);

		for (ShipBlock block : blocks) {
			// Only blocks on the leading side can be the first to hit something.
			if (localStates.containsKey(block.pos().relative(facing))) continue;
			if (block.state().getCollisionShape(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty()) continue;

			AABB box = blockBox(block.pos(), shipPos).deflate(1.0E-3);
			if (level().getBlockCollisions(null, box).iterator().hasNext() || hitsOtherShip(box)) {
				return false;
			}
		}

		return true;
	}

	/** True if the box overlaps a solid block of any other ship. */
	private boolean hitsOtherShip(AABB box) {
		for (ShipEntity other : ShipTracker.ships(level())) {
			if (other == this || !other.hasBlocks() || !other.worldBounds().intersects(box)) continue;
			if (other.hasSolidBlockIn(box)) return true;
		}
		return false;
	}

	/** True if one of this ship's solid blocks overlaps the given world box. */
	public boolean hasSolidBlockIn(AABB box) {
		double ox = getX() - 0.5, oy = getY(), oz = getZ() - 0.5;
		int x0 = Mth.floor(box.minX - ox), x1 = Mth.floor(box.maxX - ox);
		int y0 = Mth.floor(box.minY - oy), y1 = Mth.floor(box.maxY - oy);
		int z0 = Mth.floor(box.minZ - oz), z1 = Mth.floor(box.maxZ - oz);

		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		for (int x = x0; x <= x1; x++) {
			for (int y = y0; y <= y1; y++) {
				for (int z = z0; z <= z1; z++) {
					BlockState state = localStates.get(cursor.set(x, y, z));
					if (state != null && !state.getCollisionShape(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty()) {
						return true;
					}
				}
			}
		}
		return false;
	}

	/** Moves mobs and items standing on deck along with the ship. Players are moved by their own client. */
	private void carryEntities(Vec3 oldShipPos) {
		Vec3 delta = position().subtract(oldShipPos);
		if (delta.lengthSqr() < 1.0E-10) return;

		AABB area = worldBoundsAt(oldShipPos).inflate(0, 1.0, 0);

		for (Entity entity : level().getEntities(this, area, e -> !(e instanceof Player) && !e.isPassenger() && !(e instanceof ShipEntity))) {
			if (isStandingOn(entity.getBoundingBox(), oldShipPos)) {
				entity.setPos(entity.getX() + delta.x, entity.getY() + delta.y, entity.getZ() + delta.z);
			}
		}
	}

	/** True if something with this hitbox is resting on top of the ship at the given ship position. */
	public boolean isStandingOn(AABB entityBox, Vec3 shipPos) {
		AABB feet = new AABB(entityBox.minX, entityBox.minY - 0.25, entityBox.minZ, entityBox.maxX, entityBox.minY + 0.05, entityBox.maxZ);

		double ox = shipPos.x - 0.5, oy = shipPos.y, oz = shipPos.z - 0.5;
		int x0 = Mth.floor(feet.minX - ox), x1 = Mth.floor(feet.maxX - ox);
		int y0 = Mth.floor(feet.minY - oy), y1 = Mth.floor(feet.maxY - oy);
		int z0 = Mth.floor(feet.minZ - oz), z1 = Mth.floor(feet.maxZ - oz);

		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		for (int x = x0; x <= x1; x++) {
			for (int y = y0; y <= y1; y++) {
				for (int z = z0; z <= z1; z++) {
					BlockState state = localStates.get(cursor.set(x, y, z));
					if (state != null && !state.getCollisionShape(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty()) {
						return true;
					}
				}
			}
		}

		return false;
	}

	// ------------------------------------------------------------------ rotating

	private static void report(Player player, @Nullable Component error) {
		if (error != null) player.sendOverlayMessage(error);
	}

	/**
	 * Turns the ship 90 degrees around the Helm. Returns an error message, or null if it turned.
	 * The blocks move straight away; clients animate the swing over {@link #TURN_TICKS} (#27).
	 * Works while moving too: velocity is in world space, so the ship keeps its course.
	 */
	public @Nullable Component turn(Rotation rotation) {
		if (tickCount - lastTurnTick < TURN_TICKS) return null; // still swinging round from the last turn

		flushLiveBlockEntities();
		List<ShipBlock> rotated = new ArrayList<>(blocks.size());
		for (ShipBlock block : blocks) {
			rotated.add(new ShipBlock(block.pos().rotate(rotation), block.state().rotate(rotation), block.blockEntity()));
		}

		// Make sure the turned ship doesn't overlap terrain.
		for (ShipBlock block : rotated) {
			if (block.state().getCollisionShape(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty()) continue;
			AABB box = blockBox(block.pos(), position()).deflate(1.0E-3);
			if (level().getBlockCollisions(null, box).iterator().hasNext() || hitsOtherShip(box)) {
				return Component.translatable("message.airships.no_room_to_turn");
			}
		}

		// Positions changed: drop live block entities (open chest screens close) and pending button presses.
		liveBlockEntities.clear();
		buttonTimers.clear();
		setBlocks(rotated);
		lastTurnTick = tickCount;
		AirshipsNetworking.sendShapeToTrackers(this, rotation == Rotation.CLOCKWISE_90 ? 1 : -1);
		return null;
	}

	// ------------------------------------------------------------------ landing

	/** Where the Helm block will go when the ship lands. */
	public BlockPos landingOrigin() {
		return BlockPos.containing(getX(), getY() + 0.5, getZ());
	}

	/** Puts every block back into the world. Returns an error message, or null if it landed. */
	public @Nullable Component land() {
		ServerLevel level = (ServerLevel) level();
		BlockPos origin = landingOrigin();

		// Every spot must be free (air, water, grass...).
		for (ShipBlock block : blocks) {
			BlockPos target = origin.offset(block.pos());
			BlockState existing = level.getBlockState(target);
			if (!existing.canBeReplaced() || hitsOtherShip(new AABB(target).deflate(1.0E-3))) {
				return Component.translatable("message.airships.blocked", target.getX(), target.getY(), target.getZ());
			}
		}

		ejectPassengers();
		flushLiveBlockEntities();
		liveBlockEntities.clear();

		// Solid blocks first, then torches/doors/carpets that hang off them.
		List<ShipBlock> order = new ArrayList<>(blocks);
		order.sort((a, b) -> Boolean.compare(!isSolid(a.state()), !isSolid(b.state())));

		for (ShipBlock block : order) {
			BlockPos target = origin.offset(block.pos());
			level.setBlock(target, block.state(), ShipAssembler.PLACE_FLAGS);

			Optional<CompoundTag> data = block.blockEntity();
			if (data.isPresent()) {
				BlockEntity be = BlockEntity.loadStatic(target, block.state(), data.get(), level.registryAccess());
				if (be != null) {
					level.setBlockEntity(be);
				}
			}

			if (block.pos().equals(BlockPos.ZERO) && level.getBlockEntity(target) instanceof HelmBlockEntity helm) {
				helm.setFuel(fuel);
			}
		}

		// Let neighbours (redstone, water...) react now that everything is back.
		for (ShipBlock block : order) {
			BlockPos target = origin.offset(block.pos());
			level.updateNeighborsAt(target, block.state().getBlock());
		}

		discard();
		return null;
	}

	private static boolean isSolid(BlockState state) {
		return state.isSolid();
	}
}
