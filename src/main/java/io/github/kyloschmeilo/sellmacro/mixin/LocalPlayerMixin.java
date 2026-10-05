package io.github.kyloschmeilo.sellmacro.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.player.LocalPlayer;

import io.github.kyloschmeilo.sellmacro.SellMacro;

@Mixin(LocalPlayer.class)
public abstract class LocalPlayerMixin {
	// Runs for every close the client starts (Esc, inventory key, death, ...), but not when the server closes the GUI.
	@Inject(method = "closeContainer", at = @At("HEAD"))
	private void sellmacro$onCloseContainer(CallbackInfo ci) {
		SellMacro.onClientCloseContainer(((LocalPlayer) (Object) this).containerMenu.containerId);
	}
}
