package com.toast.aurapoints.client;

import com.toast.aurapoints.ability.AuraType;
import com.toast.aurapoints.ability.Relics;
import com.toast.aurapoints.rift.RelicWarden;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.DefaultedEntityGeoModel;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * CLIENT ONLY. The Relic Warden (5.46.0): geo/entity/relic_warden.geo.json, one skin per relic aura
 * (textures/entity/relic_warden_&lt;aura&gt;.png, glowmask beside it: eyes, seams, core, crown). Models face -Z.
 * Source: blockbench-models/aurapoints/relic_warden/build.py.
 */
public class RelicWardenRenderer extends GeoEntityRenderer<RelicWarden> {
	private static final ResourceLocation[] SKINS = new ResourceLocation[Relics.AURAS.size()];

	static {
		for (int i = 0; i < SKINS.length; i++) {
			AuraType a = Relics.AURAS.get(i);
			SKINS[i] = ResourceLocation.fromNamespaceAndPath("aurapoints", "textures/entity/relic_warden_" + a.id() + ".png");
		}
	}

	public RelicWardenRenderer(EntityRendererProvider.Context context) {
		super(context, new DefaultedEntityGeoModel<RelicWarden>(ResourceLocation.fromNamespaceAndPath("aurapoints", "relic_warden")) {
			@Override
			public ResourceLocation getTextureResource(RelicWarden w) {
				return SKINS[w.skin()];
			}
		});
		this.shadowRadius = 1.6f;
		addRenderLayer(new software.bernie.geckolib.renderer.layer.AutoGlowingGeoLayer<>(this));
	}

	/** Overdrive and the last stand: tinted red, pulsing. */
	@Override
	public software.bernie.geckolib.util.Color getRenderColor(RelicWarden w, float partialTick, int packedLight) {
		int ph = w.phase();
		if (ph != RelicWarden.OVERDRIVE && ph != RelicWarden.LAST_STAND) return super.getRenderColor(w, partialTick, packedLight);
		float pulse = 0.5f + 0.5f * (float) Math.sin((w.tickCount + partialTick) * (ph == RelicWarden.LAST_STAND ? 0.4 : 0.15));
		int gb = (int) (255 * (0.55f + 0.2f * pulse));
		return software.bernie.geckolib.util.Color.ofRGBA(255, gb, gb, 255);
	}
}
