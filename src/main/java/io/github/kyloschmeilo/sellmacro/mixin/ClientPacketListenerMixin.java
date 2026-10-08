package io.github.kyloschmeilo.sellmacro.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;

import io.github.kyloschmeilo.sellmacro.SellMacro;

@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
	// Proxies move players between servers with a respawn packet. The handler first runs on the
	// network thread only to hand itself over to the game thread, so only react on the game thread.
	@Inject(method = "handleRespawn", at = @At("HEAD"))
	private void sellmacro$onRespawn(ClientboundRespawnPacket packet, CallbackInfo ci) {
		if (Minecraft.getInstance().isSameThread()) {
			SellMacro.onWorldChange();
		}
	}
}
