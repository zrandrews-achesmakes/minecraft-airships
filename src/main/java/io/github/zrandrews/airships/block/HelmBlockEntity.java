package io.github.zrandrews.airships.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import io.github.zrandrews.airships.registry.ModBlockEntities;

/** Stores the fuel loaded into a Helm while the ship is parked. */
public class HelmBlockEntity extends BlockEntity {
	public static final String FUEL_KEY = "fuel";

	private int fuel = 0;

	public HelmBlockEntity(BlockPos pos, BlockState state) {
		super(ModBlockEntities.HELM, pos, state);
	}

	public int getFuel() {
		return fuel;
	}

	public void setFuel(int fuel) {
		this.fuel = Math.max(0, fuel);
		setChanged();
	}

	@Override
	protected void saveAdditional(ValueOutput output) {
		super.saveAdditional(output);
		output.putInt(FUEL_KEY, fuel);
	}

	@Override
	protected void loadAdditional(ValueInput input) {
		super.loadAdditional(input);
		fuel = input.getIntOr(FUEL_KEY, 0);
	}
}
