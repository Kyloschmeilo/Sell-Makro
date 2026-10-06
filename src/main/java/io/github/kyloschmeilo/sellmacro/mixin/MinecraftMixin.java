package io.github.kyloschmeilo.sellmacro.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.Minecraft;

import io.github.kyloschmeilo.sellmacro.SellMacro;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
	// Minecraft opens the pause menu when the window loses focus (alt-tab). That would block the
	// macro between two rounds, so it is skipped while the macro runs and the window is in the background.
	@Inject(method = "pauseGame", at = @At("HEAD"), cancellable = true)
	private void sellmacro$keepRunningInBackground(boolean suppressPauseMenu, CallbackInfo ci) {
		if (SellMacro.isRunning() && !((Minecraft) (Object) this).isWindowActive()) {
			ci.cancel();
		}
	}
}
