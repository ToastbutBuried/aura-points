package com.toast.aurapoints.rift;

import net.minecraft.ChatFormatting;

/**
 * A rift run's difficulty (5.47.0), voted on in the Reliquary's entry hall. Scales the trials (more elements, less
 * help, harsher strikes), the Relic Warden (health, damage), the time limit, knockouts and the rewards.
 * HARDCORE = one life: a knockout puts you out of the run for good.
 */
public enum Difficulty {
	EASY("Easy", ChatFormatting.GREEN, 0.6, 0.7, 50, 8, 0.75, 0.35),
	MEDIUM("Medium", ChatFormatting.YELLOW, 1.0, 1.0, 45, 12, 1.0, 0.5),
	HARD("Hard", ChatFormatting.GOLD, 1.4, 1.25, 40, 18, 1.4, 0.65),
	EXTREME("Extreme", ChatFormatting.RED, 1.9, 1.5, 36, 25, 1.8, 0.8),
	HARDCORE("Hardcore", ChatFormatting.DARK_RED, 2.2, 1.6, 36, -1, 2.5, 1.0);

	public final String label;
	public final ChatFormatting color;
	/** Boss health and damage multipliers. */
	public final double bossHealth, bossDamage;
	/** Time limit in minutes. */
	public final int minutes;
	/** Seconds a knocked out player waits; -1 = out for good (one life). */
	public final int knockoutSeconds;
	/** Aura reward multiplier, and the relic chance on a repeat clear (the first clear of an aura always gives it). */
	public final double reward, relicChance;

	Difficulty(String label, ChatFormatting color, double bossHealth, double bossDamage, int minutes, int knockoutSeconds, double reward, double relicChance) {
		this.label = label;
		this.color = color;
		this.bossHealth = bossHealth;
		this.bossDamage = bossDamage;
		this.minutes = minutes;
		this.knockoutSeconds = knockoutSeconds;
		this.reward = reward;
		this.relicChance = relicChance;
	}

	/** 0 (Easy) .. 4 (Hardcore): trials use it to size their puzzles. */
	public int tier() {
		return ordinal();
	}

	public boolean oneLife() {
		return knockoutSeconds < 0;
	}
}
