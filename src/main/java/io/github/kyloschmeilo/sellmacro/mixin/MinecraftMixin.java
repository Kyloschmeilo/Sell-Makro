package io.github.kyloschmeilo.sellmacro.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import io.github.kyloschmeilo.sellmacro.SellMacro;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
	// Only the pause menu's disconnect button calls this, so leaving on purpose doesn't resume the macro later.
	@Inject(method = "disconnectFromWorld", at = @At("HEAD"))
	private void sellmacro$onDisconnectButton(Component message, CallbackInfo ci) {
		SellMacro.onManualDisconnect();
	}
}
