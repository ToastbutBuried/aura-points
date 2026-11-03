package com.toast.aurapoints.fabric.client;

/** Model faces and baked quads with NeoForge's "neoforge_data" light (packed light | 1, 0 = none). */
public interface Emissive {
	int aurapoints$light();

	void aurapoints$setLight(int light);
}
