package io.github.zrandrews.airships.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.Minecraft;

import io.github.zrandrews.airships.client.ShipBlockUse;

/** Right-click: if the crosshair is on a usable block of a flying ship, use that instead (#25). */
@Mixin(Minecraft.class)
public abstract class MinecraftUseMixin {
	@Shadow
	private int rightClickDelay;

	@Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
	private void airships$useShipBlock(CallbackInfo ci) {
		if (ShipBlockUse.tryUse((Minecraft) (Object) this)) {
			rightClickDelay = 4; // same as vanilla after a click
			ci.cancel();
		}
	}
}
