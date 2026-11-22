package com.toast.aurapoints.rift;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;

import java.util.List;

/** Choir of Bells (5.49.0). STUB - to be written. */
final class TrialBells extends Trials.Trial {

	TrialBells(Rifts.Run run, int index) {
		super(run, index, Trials.Kind.BELLS);
	}

	@Override
	String name() {
		return "Choir of Bells";
	}

	@Override
	String hint() {
		return "";
	}

	@Override
	void build(ServerLevel level, RandomSource r) {
		for (int f = -10; f <= 10; f++) for (int l = -10; l <= 10; l++) set(level, f, l, 0, pal.floor());
	}

	@Override
	void tick(ServerLevel level, List<ServerPlayer> here, long now) {
	}

	@Override
	void onReset(ServerLevel level, RandomSource r) {
	}
}
