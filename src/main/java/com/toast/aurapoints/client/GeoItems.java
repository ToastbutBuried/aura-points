package com.toast.aurapoints.client;

import com.toast.aurapoints.item.MjolnirItem;
import com.toast.aurapoints.item.WorldTreeBowItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLivingEvent;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.model.DefaultedItemGeoModel;
import software.bernie.geckolib.renderer.GeoItemRenderer;
import software.bernie.geckolib.renderer.layer.AutoGlowingGeoLayer;

/**
 * CLIENT ONLY. GeckoLib-drawn handhelds (their item models are builtin/entity, which hands the drawing to these
 * renderers):
 * <ul>
 * <li>Mjolnir (geo/item/mjolnir_geo): the hammer with lightning arcs flickering around its head.</li>
 * <li>World Tree Bow (geo/item/world_tree_bow_geo): one model whose string pulls back smoothly with the real draw time,
 * the limbs bending and the arrow sliding in with it (instead of three fixed pulling models).</li>
 * </ul>
 * The models come from ~/blockbench-models/aurapoints/geo_items/convert.py (java coordinates below are the old item
 * models'; GeckoLib draws the geo at java (8, 8.16, 8), X mirrored).
 */
@EventBusSubscriber(modid = "aurapoints", value = Dist.CLIENT)
public final class GeoItems {

	private GeoItems() {
	}

	/** Whoever is being drawn right now (an item in their hand is theirs), null outside an entity's render. */
	private static LivingEntity holder;

	@SubscribeEvent
	public static void onRenderPre(RenderLivingEvent.Pre<?, ?> event) {
		holder = event.getEntity();
	}

	@SubscribeEvent
	public static void onRenderPost(RenderLivingEvent.Post<?, ?> event) {
		holder = null;
	}

	@SubscribeEvent
	public static void onRegisterExtensions(RegisterClientExtensionsEvent event) {
		event.registerItem(new IClientItemExtensions() {
			private BlockEntityWithoutLevelRenderer renderer;

			@Override
			public BlockEntityWithoutLevelRenderer getCustomRenderer() {
				if (renderer == null) {
					var r = new GeoItemRenderer<MjolnirItem>(new DefaultedItemGeoModel<>(id("mjolnir_geo")));
					r.addRenderLayer(new AutoGlowingGeoLayer<>(r));
					renderer = r;
				}
				return renderer;
			}
		}, com.toast.aurapoints.AuraPoints.MJOLNIR_ITEM.get());
		event.registerItem(new IClientItemExtensions() {
			private BlockEntityWithoutLevelRenderer renderer;

			@Override
			public BlockEntityWithoutLevelRenderer getCustomRenderer() {
				if (renderer == null) renderer = new BowRenderer();
				return renderer;
			}
		}, com.toast.aurapoints.AuraPoints.WORLD_TREE_BOW.get());
	}

	private static ResourceLocation id(String path) {
		return ResourceLocation.fromNamespaceAndPath("aurapoints", path);
	}

	// ---------------------------------------------------------------
	// The bow
	// ---------------------------------------------------------------

	/** Java-space points of the old model (pixels): the string's ends, its middle at rest, and the way it's drawn. */
	private static final float TIP_UP_X = 16.27f, TIP_UP_Y = 14.26f, TIP_DN_X = 1.74f, TIP_DN_Y = -0.27f;
	private static final float NOCK_X = 9.0f, NOCK_Y = 7.0f, DRAW = 4.0f, BACK_X = 0.7071f, BACK_Y = -0.7071f;
	/** Limb pivots (where they leave the grip) and how far they bend at full draw. */
	private static final float LIMB_UP_X = 6.4f, LIMB_UP_Y = 12.4f, LIMB_DN_X = 4.6f, LIMB_DN_Y = 8.6f, BEND = (float) Math.toRadians(7);

	private static final class BowRenderer extends GeoItemRenderer<WorldTreeBowItem> {

		BowRenderer() {
			super(new BowModel());
			addRenderLayer(new AutoGlowingGeoLayer<>(this));
		}

		@Override
		public void renderByItem(ItemStack stack, ItemDisplayContext context, com.mojang.blaze3d.vertex.PoseStack pose,
				net.minecraft.client.renderer.MultiBufferSource buffers, int light, int overlay) {
			LivingEntity who = context.firstPerson() ? Minecraft.getInstance().player : holder;
			((BowModel) model).pull = pull(who, stack);
			super.renderByItem(stack, context, pose, buffers, light, overlay);
		}
	}

	/** How far it's drawn, 0..1, like the vanilla bow's "pull" (20 ticks to full), smooth between ticks. */
	private static float pull(LivingEntity who, ItemStack stack) {
		// DEV ONLY (gradle runClient -Pgeotest): a bow nobody holds (an item display) draws by itself every 2 s
		if (who == null && com.toast.aurapoints.DevGeoTest.on()) return Mth.clamp((System.currentTimeMillis() % 2000) / 1500f, 0f, 1f);
		if (who == null || !who.isUsingItem() || who.getUseItem() != stack) return 0f;
		float partial = Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(true);
		float ticks = stack.getUseDuration(who) - who.getUseItemRemainingTicks() + partial;
		return Mth.clamp(ticks / 20f, 0f, 1f);
	}

	private static final class BowModel extends DefaultedItemGeoModel<WorldTreeBowItem> {

		float pull;

		BowModel() {
			super(id("world_tree_bow_geo"));
		}

		@Override
		public void setCustomAnimations(WorldTreeBowItem item, long instanceId, AnimationState<WorldTreeBowItem> state) {
			super.setCustomAnimations(item, instanceId, state);
			// ease out: most of the bend comes early, like a real draw
			float p = 1f - (1f - pull) * (1f - pull);
			float nx = NOCK_X + BACK_X * DRAW * p, ny = NOCK_Y + BACK_Y * DRAW * p;
			float bendUp = -BEND * p, bendDn = BEND * p;
			GeoBone limbUp = getBone("limb_up").orElse(null), limbDn = getBone("limb_dn").orElse(null);
			if (limbUp != null) limbUp.setRotZ(limbUp.getRotZ() + bendUp);
			if (limbDn != null) limbDn.setRotZ(limbDn.getRotZ() + bendDn);
			aimString("string_up", TIP_UP_X, TIP_UP_Y, LIMB_UP_X, LIMB_UP_Y, limbUp, nx, ny);
			aimString("string_dn", TIP_DN_X, TIP_DN_Y, LIMB_DN_X, LIMB_DN_Y, limbDn, nx, ny);
			getBone("arrow").ifPresent(arrow -> {
				arrow.setHidden(pull <= 0f);
				// the model holds it fully drawn: slide it forward by what's not drawn yet (GeckoLib's X is mirrored)
				float off = DRAW * (1f - p);
				// (set, not added: no animation touches this bone, so GeckoLib doesn't reset it between frames)
				arrow.setPosX(BACK_X * off);
				arrow.setPosY(-BACK_Y * off);
			});
		}

		/** Turns and stretches one half of the string so it runs from its (bent) limb's tip to the nock. */
		private void aimString(String name, float tx, float ty, float px, float py, GeoBone limb, float nx, float ny) {
			GeoBone s = getBone(name).orElse(null);
			if (s == null) return;
			float bend = limb == null ? 0f : limb.getRotZ();
			// the tip, carried round the limb's pivot by its bend
			float dx = tx - px, dy = ty - py, c = Mth.cos(bend), sn = Mth.sin(bend);
			float wx = px + dx * c - dy * sn, wy = py + dx * sn + dy * c;
			float vx = nx - wx, vy = ny - wy;
			float len = Mth.sqrt(vx * vx + vy * vy);
			// the bar points along +Y: turn it onto (vx, vy), relative to the limb it hangs from
			s.setRotZ((float) Math.atan2(-vx, vy) - bend);
			s.setScaleY(len);
		}
	}
}
