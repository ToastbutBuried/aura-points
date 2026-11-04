package com.toast.aurapoints.client;

import com.toast.aurapoints.ability.AuraSounds;
import com.toast.aurapoints.ability.AuraType;
import com.toast.aurapoints.config.AuraClientConfig;
import com.toast.aurapoints.network.ClientAuraState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.resources.sounds.TickableSoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import com.toast.aurapoints.shim.api.distmarker.Dist;
import com.toast.aurapoints.shim.bus.api.SubscribeEvent;
import com.toast.aurapoints.shim.fml.common.EventBusSubscriber;
import com.toast.aurapoints.shim.neoforge.client.event.ClientTickEvent;
import com.toast.aurapoints.shim.neoforge.client.event.sound.PlaySoundEvent;

/**
 * CLIENT ONLY. Aura Points sounds on this side:
 *  - every aurapoints:* sound (the abilities' own, the HUD's) follows the Aura Points volume slider;
 *  - {@link #ui}: a HUD sound, heard only here;
 *  - the charge hums: a loop that rises in pitch and volume while an ability charges (the same bar
 *    the HUD shows), and a sting the moment it's full.
 */
@EventBusSubscriber(modid = "aurapoints", value = Dist.CLIENT)
public final class ClientSounds {

	private ClientSounds() {
	}

	/** Plays a HUD sound here (volume before the slider). */
	public static void ui(SoundEvent sound, float volume, float pitch) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.getSoundManager() == null) return;
		mc.getSoundManager().play(SimpleSoundInstance.forUI(sound, pitch, volume));
	}

	/** The cooldown-ready chime: a soft shimmer, and the aura's own voice over it. */
	public static void readyChime(AuraType aura) {
		if (!AuraClientConfig.READY_CHIME.get()) return;
		ui(AuraSounds.UI_READY.value(), 0.7f, 1.0f);
		SoundEvent voice = aura == null ? null : switch (aura) {
			case SHADOW -> AuraSounds.UI_READY_SHADOW.value();
			case WATER -> AuraSounds.UI_READY_WATER.value();
			case FIRE -> AuraSounds.UI_READY_FIRE.value();
			case WIND -> AuraSounds.UI_READY_WIND.value();
			case FROST -> AuraSounds.UI_READY_FROST.value();
			case VERDANT -> AuraSounds.UI_READY_VERDANT.value();
			case THUNDER -> AuraSounds.UI_READY_THUNDER.value();
			case RESONANCE -> AuraSounds.UI_READY_RESONANCE.value();
			case EARTH -> net.minecraft.sounds.SoundEvents.BASALT_PLACE;
			case LIGHT -> net.minecraft.sounds.SoundEvents.AMETHYST_BLOCK_CHIME;
			case BLOOD -> net.minecraft.sounds.SoundEvents.WARDEN_HEARTBEAT;
		};
		if (voice != null) ui(voice, 1.0f, 1.0f);
	}

	// ---------------------------------------------------------------
	// Volume slider
	// ---------------------------------------------------------------

	@SubscribeEvent
	public static void onPlaySound(PlaySoundEvent event) {
		SoundInstance sound = event.getSound();
		if (sound == null || !"aurapoints".equals(sound.getLocation().getNamespace())) return;
		// the hums scale themselves
		if (sound instanceof TickableSoundInstance) return;
		double volume = AuraClientConfig.ABILITY_VOLUME.get();
		if (volume >= 0.999) return;
		event.setSound(volume <= 0.001 ? null : new Scaled(sound, (float) volume));
	}

	/** Another sound, just quieter. */
	private record Scaled(SoundInstance inner, float factor) implements SoundInstance {
		@Override
		public ResourceLocation getLocation() {
			return inner.getLocation();
		}

		@Override
		public WeighedSoundEvents resolve(SoundManager manager) {
			return inner.resolve(manager);
		}

		@Override
		public Sound getSound() {
			return inner.getSound();
		}

		@Override
		public SoundSource getSource() {
			return inner.getSource();
		}

		@Override
		public boolean isLooping() {
			return inner.isLooping();
		}

		@Override
		public boolean isRelative() {
			return inner.isRelative();
		}

		@Override
		public int getDelay() {
			return inner.getDelay();
		}

		@Override
		public float getVolume() {
			return inner.getVolume() * factor;
		}

		@Override
		public float getPitch() {
			return inner.getPitch();
		}

		@Override
		public double getX() {
			return inner.getX();
		}

		@Override
		public double getY() {
			return inner.getY();
		}

		@Override
		public double getZ() {
			return inner.getZ();
		}

		@Override
		public Attenuation getAttenuation() {
			return inner.getAttenuation();
		}

		@Override
		public boolean canStartSilent() {
			return inner.canStartSilent();
		}

		@Override
		public boolean canPlaySound() {
			return inner.canPlaySound();
		}
	}

	// ---------------------------------------------------------------
	// Charge hums
	// ---------------------------------------------------------------

	/** What is charging, by the same rules the charge bar picks its colors. */
	enum Charge {
		FIRE, WIND, FROST, THUNDER, RESONANCE
	}

	static Charge chargeKind(Minecraft mc) {
		boolean hammer = mc.player != null && mc.player.getMainHandItem().is(com.toast.aurapoints.AuraPoints.MJOLNIR_ITEM.get());
		AuraType aura = ClientAuraState.aura;
		if (hammer || aura == AuraType.THUNDER) return Charge.THUNDER;
		if (aura == AuraType.RESONANCE) return Charge.RESONANCE;
		if (aura == AuraType.WIND) return Charge.WIND;
		if (aura == AuraType.FROST) return Charge.FROST;
		return Charge.FIRE;
	}

	private static Hum main, layer;
	private static Charge humKind;
	private static boolean wasReady;

	@SubscribeEvent
	public static void onClientTick(ClientTickEvent.Post event) {
		Minecraft mc = Minecraft.getInstance();
		boolean on = mc.player != null && mc.level != null && ClientAuraState.fireBending && AuraClientConfig.CHARGE_HUMS.get();
		if (!on) {
			stopHums();
			wasReady = false;
			return;
		}
		Charge kind = chargeKind(mc);
		if (kind != humKind || main == null || main.isStopped()) {
			stopHums();
			humKind = kind;
			main = new Hum(switch (kind) {
				case FIRE -> AuraSounds.CHARGE_FIRE.value();
				case WIND -> AuraSounds.CHARGE_WIND.value();
				case FROST -> AuraSounds.CHARGE_FROST.value();
				case THUNDER -> AuraSounds.CHARGE_THUNDER.value();
				case RESONANCE -> AuraSounds.CHARGE_RESONANCE.value();
			}, 0.55f, 0.75f, 1.35f);
			mc.getSoundManager().play(main);
			// a second voice under some of them: the fire's roaring core, the lightning's thousand birds
			SoundEvent under = kind == Charge.FIRE ? AuraSounds.CHARGE_CORE.value() : kind == Charge.THUNDER ? AuraSounds.CHARGE_BUZZ.value() : null;
			if (under != null) {
				layer = new Hum(under, kind == Charge.THUNDER ? 0.25f : 0.35f, kind == Charge.THUNDER ? 1.4f : 0.8f, kind == Charge.THUNDER ? 2.0f : 1.4f);
				mc.getSoundManager().play(layer);
			}
		}
		boolean ready = ClientAuraState.fireCharge >= ClientAuraState.fireReadyAt;
		if (ready && !wasReady) {
			float pitch = switch (kind) {
				case FIRE -> 1.0f;
				case WIND -> 1.4f;
				case FROST -> 1.7f;
				case THUNDER -> 1.9f;
				case RESONANCE -> 0.7f;
			};
			ui(AuraSounds.CHARGE_READY.value(), 0.9f, pitch);
		}
		wasReady = ready;
	}

	private static void stopHums() {
		if (main != null) main.fade();
		if (layer != null) layer.fade();
		main = null;
		layer = null;
		humKind = null;
	}

	/** A looped sound on the listener whose pitch and volume follow the charge. */
	private static final class Hum extends AbstractTickableSoundInstance {
		private final float baseVolume, lowPitch, highPitch;
		private boolean fading;
		private float shown;

		Hum(SoundEvent sound, float baseVolume, float lowPitch, float highPitch) {
			super(sound, SoundSource.PLAYERS, SoundInstance.createUnseededRandom());
			this.baseVolume = baseVolume;
			this.lowPitch = lowPitch;
			this.highPitch = highPitch;
			this.looping = true;
			this.delay = 0;
			this.relative = true;
			this.attenuation = Attenuation.NONE;
			this.volume = 0.01f;
			this.pitch = lowPitch;
		}

		void fade() {
			fading = true;
		}

		@Override
		public boolean canStartSilent() {
			return true;
		}

		@Override
		public void tick() {
			float slider = (float) (double) AuraClientConfig.ABILITY_VOLUME.get();
			if (fading) {
				volume -= 0.08f;
				if (volume <= 0.01f) stop();
				return;
			}
			float charge = Math.min(1f, ClientAuraState.fireCharge);
			shown += (charge - shown) * 0.3f;
			volume = Math.max(0.01f, baseVolume * slider * (0.35f + 0.65f * shown));
			pitch = lowPitch + (highPitch - lowPitch) * shown;
		}
	}
}
