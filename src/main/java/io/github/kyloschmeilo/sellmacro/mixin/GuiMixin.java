package io.github.kyloschmeilo.sellmacro.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;

import io.github.kyloschmeilo.sellmacro.SellMacro;

@Mixin(Gui.class)
public abstract class GuiMixin {
	// Keeps the sell GUI opened by the macro invisible in background mode, so the mouse stays in game.
	@Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
	private void sellmacro$hideSellScreen(Screen screen, CallbackInfo ci) {
		if (SellMacro.shouldHideScreen(screen)) {
			ci.cancel();
		}
	}
}
