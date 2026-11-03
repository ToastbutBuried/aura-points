package com.toast.aurapoints.fabric.mixin;

import com.toast.aurapoints.shim.AuraBus;
import com.toast.aurapoints.shim.neoforge.event.entity.player.PlayerEvent;
import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Logging in (once the player is fully in the game, at the end of placeNewPlayer) and out (first thing in remove), like NeoForge. */
@Mixin(PlayerList.class)
public abstract class PlayerListMixin {

	@Inject(method = "placeNewPlayer", at = @At("TAIL"))
	private void aurapoints$loggedIn(Connection connection, ServerPlayer player, CommonListenerCookie cookie, CallbackInfo ci) {
		AuraBus.BUS.post(new PlayerEvent.PlayerLoggedInEvent(player));
	}

	@Inject(method = "remove", at = @At("HEAD"))
	private void aurapoints$loggedOut(ServerPlayer player, CallbackInfo ci) {
		AuraBus.BUS.post(new PlayerEvent.PlayerLoggedOutEvent(player));
	}
}
