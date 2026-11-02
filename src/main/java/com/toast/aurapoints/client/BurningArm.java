package com.toast.aurapoints.client;

import com.toast.aurapoints.network.BendingAnimPacket;
import com.toast.aurapoints.network.ClientAuraState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * CLIENT ONLY. Fire Bending at full charge (held at the side): the arm burns - the flame's own
 * particles (flames, embers, soul fire, smoke) spawned on the surface of the right arm, so they
 * outline it from shoulder to fingertips instead of forming a ball. Every client spawns them itself
 * from where the arm is in its own world (the same math the player model uses), so they never lag
 * behind with ping, and you see them in first person too.
 */
@EventBusSubscriber(modid = "aurapoints", value = Dist.CLIENT)
public final class BurningArm {

	private BurningArm() {
	}

	private static final DustParticleOptions EMBER = new DustParticleOptions(new Vector3f(1.0f, 0.69f, 0.13f), 1.3f);
	private static final DustParticleOptions FIRE = new DustParticleOptions(new Vector3f(1.0f, 0.35f, 0.12f), 1.3f);

	@SubscribeEvent
	public static void onClientTick(ClientTickEvent.Post event) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.isPaused()) return;
		for (AbstractClientPlayer player : mc.level.players()) {
			long[] s = ClientAuraState.bendState(player.getId());
			if (s == null || s[0] != BendingAnimPacket.READY_SIDE || player.isInvisible() || player.isSpectator()) continue;
			if (player.distanceToSqr(mc.gameRenderer.getMainCamera().getPosition()) > 64 * 64) continue;
			burn(mc, player);
		}
	}

	/** The right arm's model transform, the way PlayerRenderer + PlayerModel place it (pose from BendingAnimation). */
	private static Matrix4f armMatrix(AbstractClientPlayer player) {
		boolean crouch = player.isCrouching();
		return new Matrix4f()
				.translate((float) player.getX(), (float) player.getY() - (crouch ? 0.125f * player.getScale() : 0f), (float) player.getZ()) // PlayerRenderer's sneak offset
				.scale(player.getScale())
				.rotateY((float) Math.toRadians(180f - player.yBodyRot))
				.scale(-1f, -1f, 1f)
				.scale(0.9375f) // PlayerRenderer.scale
				.translate(0f, -1.501f, 0f)
				.translate(-5f / 16f, (crouch ? 5.2f : 2f) / 16f, 0f) // the right shoulder pivot
				.rotate(new Quaternionf().rotationZYX(BendingAnimation.HOLD_Z, BendingAnimation.HOLD_Y, BendingAnimation.HOLD_X));
	}

	private static void burn(Minecraft mc, AbstractClientPlayer player) {
		Matrix4f m = armMatrix(player);
		RandomSource r = player.getRandom();
		boolean slim = player.getSkin().model() == PlayerSkin.Model.SLIM;
		long t = player.tickCount;
		for (int i = 0; i < 6; i++) spawn(mc, m, r, slim, ParticleTypes.FLAME, 0.012);
		for (int i = 0; i < 2; i++) spawn(mc, m, r, slim, i == 0 ? EMBER : FIRE, 0.0);
		for (int i = 0; i < 2; i++) spawn(mc, m, r, slim, ParticleTypes.SOUL_FIRE_FLAME, 0.01);
		if (t % 4 == 0) spawn(mc, m, r, slim, ParticleTypes.SMOKE, 0.02);
	}

	/**
	 * One particle at a random spot on the arm's surface (the arm box: 4 pixels wide, 3 when slim,
	 * 12 long, plus a little past the hand), half a pixel out, mostly on the forearm and hand.
	 */
	private static void spawn(Minecraft mc, Matrix4f m, RandomSource r, boolean slim,
							  net.minecraft.core.particles.ParticleOptions particle, double rise) {
		float x0 = slim ? -2f : -3f, x1 = 1f, z0 = -2f, z1 = 2f;
		// a touch past the fingertips (the arm ends at 10), mostly toward the hand
		float y = -2f + 13.5f * (1f - (float) Math.pow(r.nextFloat(), 1.5));
		float x, z;
		switch (r.nextInt(4)) {
			case 0 -> { x = x0 - 0.5f; z = z0 + r.nextFloat() * (z1 - z0); }
			case 1 -> { x = x1 + 0.5f; z = z0 + r.nextFloat() * (z1 - z0); }
			case 2 -> { z = z0 - 0.5f; x = x0 + r.nextFloat() * (x1 - x0); }
			default -> { z = z1 + 0.5f; x = x0 + r.nextFloat() * (x1 - x0); }
		}
		Vector3f p = m.transformPosition(new Vector3f(x / 16f, y / 16f, z / 16f));
		mc.level.addParticle(particle, p.x(), p.y(), p.z(), 0, rise, 0);
	}
}
