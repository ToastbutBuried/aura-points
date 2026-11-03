package com.toast.aurapoints.shim.neoforge.common.util;

import com.mojang.authlib.GameProfile;
import com.toast.aurapoints.shim.Platform;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** The loader's fake players (one per level and profile); test with Platform.isFakePlayer. */
public final class FakePlayerFactory {
	private FakePlayerFactory() {
	}

	public static ServerPlayer get(ServerLevel level, GameProfile profile) {
		return Platform.get().fakePlayer(level, profile);
	}
}
