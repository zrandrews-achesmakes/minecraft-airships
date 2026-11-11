package io.github.zrandrews.airships.registry;

import java.util.function.Function;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.references.BlockItemId;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;

import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;

import io.github.zrandrews.airships.AirshipsMod;
import io.github.zrandrews.airships.block.HelmBlock;
import io.github.zrandrews.airships.block.LiftBlock;

public class ModBlocks {
	public static final Block LIFT_BLOCK = register(
			"lift_block",
			LiftBlock::new,
			BlockBehaviour.Properties.of()
					.strength(2.0f, 6.0f)
					.sound(SoundType.AMETHYST)
					.lightLevel(state -> 7)
					.requiresCorrectToolForDrops()
	);

	public static final Block HELM = register(
			"helm",
			HelmBlock::new,
			BlockBehaviour.Properties.of()
					.strength(2.0f, 3.0f)
					.sound(SoundType.WOOD)
					.noOcclusion()
	);

	private static Block register(String name, Function<BlockBehaviour.Properties, Block> factory, BlockBehaviour.Properties properties) {
		Identifier id = AirshipsMod.id(name);
		BlockItemId ids = BlockItemId.create(id, id);

		Block block = factory.apply(properties.setId(ids.block()));
		Registry.register(BuiltInRegistries.BLOCK, ids.block(), block);

		BlockItem item = new BlockItem(block, new Item.Properties().useBlockDescriptionPrefix().setId(ids.item()));
		Registry.register(BuiltInRegistries.ITEM, ids.item(), item);

		return block;
	}

	public static void initialize() {
		CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.FUNCTIONAL_BLOCKS).register(tab -> {
			tab.accept(LIFT_BLOCK.asItem());
			tab.accept(HELM.asItem());
		});
	}
}
