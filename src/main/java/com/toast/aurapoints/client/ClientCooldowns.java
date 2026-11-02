package com.toast.aurapoints.client;

import com.toast.aurapoints.ability.AuraAbilities;
import com.toast.aurapoints.ability.AuraAbilities.Ability;
import com.toast.aurapoints.ability.AuraSounds;
import com.toast.aurapoints.ability.AuraType;
import com.toast.aurapoints.config.AuraClientConfig;
import com.toast.aurapoints.network.ClientAuraState;
import com.toast.aurapoints.network.IceShellPacket;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * CLIENT ONLY. Watches the cooldowns every client tick - not only while the HUD draws them, so a
 * cooldown that ends while its slot isn't shown (gliding sets, F1, another screen) chimes when it
 * really ends, not the next time the slot shows up. Also keeps the moments the HUD animates:
 * when a slot came off cooldown, was pressed, or was pressed too early.
 */
@EventBusSubscriber(modid = "aurapoints", value = Dist.CLIENT)
public final class ClientCooldowns {

	private ClientCooldowns() {
	}

	/** When each ability last came off cooldown (millis), main and flight sets. */
	private static final Map<Ability, Long> READY_AT = new EnumMap<>(Ability.class);
	private static final Map<Ability, Long> READY_AT_FLIGHT = new EnumMap<>(Ability.class);
	/** Pressed (it went off) / pressed while still cooling down. */
	private static final Map<Ability, Long> PRESSED_AT = new EnumMap<>(Ability.class);
	private static final Map<Ability, Long> DENIED_AT = new EnumMap<>(Ability.class);
	/** Abilities whose last-second tick already played this cooldown. */
	private static final java.util.Set<Ability> TICKED = java.util.EnumSet.noneOf(Ability.class);

	/** Ice Shell: whether it was whole last tick, and when it became whole. */
	private static boolean shellWasWhole = true;
	private static long shellWholeAt = 0;

	public static Long readyAt(Ability ability, boolean flight) {
		return (flight ? READY_AT_FLIGHT : READY_AT).get(ability);
	}

	public static Long pressedAt(Ability ability) {
		return PRESSED_AT.get(ability);
	}

	public static Long deniedAt(Ability ability) {
		return DENIED_AT.get(ability);
	}

	public static long shellWholeAt() {
		return shellWholeAt;
	}

	public static void clear() {
		READY_AT.clear();
		READY_AT_FLIGHT.clear();
		PRESSED_AT.clear();
		DENIED_AT.clear();
		TICKED.clear();
		shellWasWhole = true;
	}

	@SubscribeEvent
	public static void onClientTick(ClientTickEvent.Post event) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;
		long now = System.currentTimeMillis();
		AuraType aura = ClientAuraState.aura;
		check(null, READY_AT, aura, now);
		check(AuraAbilities.Slot.FLIGHT, READY_AT_FLIGHT, aura, now);
		// the Wind launch bar just runs out, no chime of its own
		for (Ability a : Ability.values()) {
			long[] cd = ClientAuraState.cooldown(a, AuraAbilities.Slot.LAUNCH);
			if (cd != null && now >= cd[1]) ClientAuraState.clearCooldown(a, AuraAbilities.Slot.LAUNCH);
		}

		IceShellPacket shell = ClientAuraState.iceShell;
		boolean whole = shell == null || (shell.layers() >= Math.max(1, shell.maxLayers()) && shell.top() >= 0.999f);
		if (whole && !shellWasWhole) {
			shellWholeAt = now;
			if (aura != null && aura.has(Ability.ICE_SHELL)) ClientSounds.readyChime(aura);
		}
		shellWasWhole = whole;
	}

	private static void check(AuraAbilities.Slot slot, Map<Ability, Long> readyAt, AuraType aura, long now) {
		List<Ability> done = new ArrayList<>();
		for (Ability a : Ability.values()) {
			long[] cd = ClientAuraState.cooldown(a, slot);
			if (cd == null) continue;
			if (now >= cd[1]) {
				done.add(a);
			} else if (slot == null && cd[1] - now <= 1000 && cd[1] - cd[0] > 2000 && TICKED.add(a)
					&& AuraClientConfig.ALMOST_READY_TICK.get()) {
				ClientSounds.ui(AuraSounds.UI_ALMOST_READY.value(), 1.0f, 1.0f);
			}
		}
		for (Ability a : done) {
			ClientAuraState.clearCooldown(a, slot);
			readyAt.put(a, now);
			if (slot == null) TICKED.remove(a);
			// only for abilities this aura shows; leftovers from an old aura stay quiet
			if (aura != null && aura.has(a)) ClientSounds.readyChime(aura);
		}
	}

	/** An Ability key went down (ClientKeyHandler): punch the slot, or shake it if it's still cooling down. */
	public static void onKeyPressed(int index) {
		AuraType aura = ClientAuraState.aura;
		Minecraft mc = Minecraft.getInstance();
		if (aura == null || mc.player == null) return;
		Ability ability = aura.slot(index);
		if (ability == null || !ClientAuraState.isOwned(ability) || ClientAuraState.isDisabled(ability)) return;
		boolean flight = mc.player.isFallFlying() && ClientAuraState.hasFlightVersion(ability);
		long[] cd = ClientAuraState.cooldown(ability, flight ? AuraAbilities.Slot.FLIGHT : null);
		long now = System.currentTimeMillis();
		if (cd != null && now < cd[1]) {
			DENIED_AT.put(ability, now);
			if (AuraClientConfig.NOT_READY_SOUND.get()) ClientSounds.ui(AuraSounds.UI_NOT_READY.value(), 1.0f, 1.0f);
		} else {
			PRESSED_AT.put(ability, now);
		}
	}
}
