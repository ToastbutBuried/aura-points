package com.toast.aurapoints.ability;

import com.toast.aurapoints.AuraPoints;
import com.toast.aurapoints.arena.Match;
import net.minecraft.server.level.ServerPlayer;
import com.toast.aurapoints.shim.fml.ModList;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Who counts as an ally (team combos, party protection): two different players in the same FTB Teams
 * party, or on the same side of a running arena match (Team Battle teammates, everyone in Wave Survival).
 * FTB Teams is optional and reached by reflection, so the mod needs no compile dependency on it; if it is
 * missing or its API changes, only the arena sides count.
 */
public final class Allies {

	private Allies() {
	}

	private static boolean looked;
	private static Method api, manager, sameTeam;

	/** Same FTB Teams party (every player also has a solo "player team"; two solo players are not allies). */
	public static boolean party(ServerPlayer a, ServerPlayer b) {
		if (a == null || b == null || a == b) return false;
		return sameTeam(a.getUUID(), b.getUUID());
	}

	/** Dev test: these (fake) players count as one party. */
	public static final java.util.Set<UUID> TEST_PARTY = new java.util.HashSet<>();

	/** Party members or arena teammates. */
	public static boolean allied(ServerPlayer a, ServerPlayer b) {
		if (a == null || b == null || a == b) return false;
		if (TEST_PARTY.contains(a.getUUID()) && TEST_PARTY.contains(b.getUUID())) return true;
		return Match.teammates(a, b) || party(a, b);
	}

	/** A player's team for shrines (5.42): their FTB party, or just themselves (no party, no FTB Teams). */
	public record TeamInfo(UUID id, String name, boolean party) {
	}

	private static final UUID TEST_TEAM = UUID.fromString("5d1e3c1a-0000-4000-8000-0000000071a3");
	private static Method teamFor, teamId, teamShortName, teamIsParty;

	public static TeamInfo teamOf(ServerPlayer p) {
		if (TEST_PARTY.contains(p.getUUID())) return new TeamInfo(TEST_TEAM, "Test Party", true);
		sameTeam(p.getUUID(), p.getUUID());
		if (api != null) {
			try {
				if (teamFor == null) {
					Class<?> tm = Class.forName("dev.ftb.mods.ftbteams.api.TeamManager"), team = Class.forName("dev.ftb.mods.ftbteams.api.Team");
					teamFor = tm.getMethod("getTeamForPlayerID", UUID.class);
					teamId = team.getMethod("getId");
					teamShortName = team.getMethod("getShortName");
					teamIsParty = team.getMethod("isPartyTeam");
				}
				Object m = manager.invoke(api.invoke(null));
				Object opt = m == null ? null : teamFor.invoke(m, p.getUUID());
				if (opt instanceof java.util.Optional<?> o && o.isPresent() && Boolean.TRUE.equals(teamIsParty.invoke(o.get()))) {
					return new TeamInfo((UUID) teamId.invoke(o.get()), String.valueOf(teamShortName.invoke(o.get())), true);
				}
			} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
				// no team info: the player alone
			}
		}
		return new TeamInfo(p.getUUID(), p.getGameProfile().getName(), false);
	}

	private static boolean sameTeam(UUID a, UUID b) {
		if (!looked) {
			looked = true;
			if (ModList.get() != null && ModList.get().isLoaded("ftbteams")) {
				try {
					Class<?> c = Class.forName("dev.ftb.mods.ftbteams.api.FTBTeamsAPI");
					api = c.getMethod("api");
					manager = Class.forName("dev.ftb.mods.ftbteams.api.FTBTeamsAPI$API").getMethod("getManager");
					sameTeam = Class.forName("dev.ftb.mods.ftbteams.api.TeamManager").getMethod("arePlayersInSameTeam", UUID.class, UUID.class);
				} catch (ReflectiveOperationException | LinkageError e) {
					AuraPoints.LOGGER.warn("FTB Teams found but its API could not be reached; team combos only see arena teams", e);
					api = null;
				}
			}
		}
		if (api == null) return false;
		try {
			Object m = manager.invoke(api.invoke(null));
			return m != null && Boolean.TRUE.equals(sameTeam.invoke(m, a, b));
		} catch (ReflectiveOperationException | RuntimeException e) {
			// the team manager isn't up yet (very early in a start), or something else went wrong: no allies
			return false;
		}
	}
}
