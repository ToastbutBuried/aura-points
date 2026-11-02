package com.toast.aurapoints.menu;

import com.toast.aurapoints.data.AuraPlayerData;
import com.toast.aurapoints.data.AuraState;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The Aura Book's tutorial. The book offers it ("Tutorial?" Yes / No / Don't show again) until it is
 * finished or dismissed. Each step is done by doing it (rolling, opening a book page), explained in chat
 * with a reminder on the actionbar. Server-only: no packets.
 *
 * State in AuraPlayerData: tutorialState (OFFER, STARTED, DONE, DISMISSED) + tutorialStep.
 */
public final class Tutorial {

	private Tutorial() {
	}

	public static final int OFFER = 0, STARTED = 1, DONE = 2, DISMISSED = 3;

	public enum Step {
		ROLL("Roll your aura", "Open the Aura Book and click Roll your aura. The first roll is free.",
				"Your aura decides your abilities. Passives work at once."),
		AURA("Look at your aura", "Click your aura in the Aura Book to see what it does.",
				"Actives are bought there with Aura, then used with your keys (Options > Controls > Aura Points)."),
		SKILLS("Get stronger", "Open Skill points in the Aura Book.",
				"You earn Aura from aura-touched mobs (glowing, named), quests, bosses and Trials. Spend it on actives and skill points."),
		SETTINGS("Make it yours", "Open Settings in the Aura Book.",
				"Cosmetics and weekly challenges are in the book too. Every command still works: type /aura.");

		public final String title, task, tip;

		Step(String title, String task, String tip) {
			this.title = title;
			this.task = task;
			this.tip = tip;
		}
	}

	/** Players who said Yes/continue this session; a started tutorial from an earlier session asks "Continue?" again. */
	private static final Set<UUID> RUNNING = new HashSet<>();

	static boolean started(AuraPlayerData data) {
		return data.tutorialState == STARTED;
	}

	static boolean wantsPrompt(ServerPlayer player, AuraPlayerData data) {
		return data.tutorialState == OFFER || (data.tutorialState == STARTED && !RUNNING.contains(player.getUUID()));
	}

	private static void save(ServerPlayer player) {
		AuraState.get(player.getServer()).markDirty();
	}

	private static Step step(AuraPlayerData data) {
		Step[] all = Step.values();
		return data.tutorialStep >= 0 && data.tutorialStep < all.length ? all[data.tutorialStep] : null;
	}

	static void start(ServerPlayer player, AuraPlayerData data) {
		if (data.tutorialState != STARTED) data.tutorialStep = 0;
		data.tutorialState = STARTED;
		RUNNING.add(player.getUUID());
		save(player);
		skipDone(player, data);
		announce(player, data);
	}

	static void dismiss(ServerPlayer player, AuraPlayerData data) {
		data.tutorialState = DISMISSED;
		RUNNING.remove(player.getUUID());
		save(player);
		player.sendSystemMessage(Component.literal("The Aura Book won't offer the tutorial again. Start it any time from the book's menu.")
				.withStyle(ChatFormatting.GRAY));
	}

	/** A book page opened: finishes that step if it is the current one. */
	static void visited(ServerPlayer player, AuraPlayerData data, Step page) {
		if (data.tutorialState == STARTED && RUNNING.contains(player.getUUID()) && step(data) == page) advance(player, data);
	}

	/** Steps already done (an aura from before the tutorial) are skipped. */
	private static void skipDone(ServerPlayer player, AuraPlayerData data) {
		while (step(data) == Step.ROLL && data.aura != null) data.tutorialStep++;
		if (step(data) == null) finish(player, data);
	}

	private static void advance(ServerPlayer player, AuraPlayerData data) {
		Step done = step(data);
		data.tutorialStep++;
		save(player);
		if (done != null) {
			player.sendSystemMessage(Component.literal("✔ " + done.title + ". ").withStyle(ChatFormatting.GREEN)
					.append(Component.literal(done.tip).withStyle(ChatFormatting.GRAY)));
		}
		player.playNotifySound(SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 0.6f, 1.2f);
		if (step(data) == null) finish(player, data);
		else announce(player, data);
	}

	private static void finish(ServerPlayer player, AuraPlayerData data) {
		data.tutorialState = DONE;
		data.tutorialStep = 0;
		RUNNING.remove(player.getUUID());
		save(player);
		player.playNotifySound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 0.6f, 1.0f);
		player.sendSystemMessage(Component.literal("Tutorial done! ").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
				.append(Component.literal("Replay it any time from the Aura Book.").withStyle(ChatFormatting.GRAY)));
	}

	private static Component openLink() {
		return Component.literal("[Open the Aura Book]").withStyle(style -> style.withColor(ChatFormatting.AQUA)
				.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/aura menu"))
				.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Same as right-clicking the book"))));
	}

	private static void announce(ServerPlayer player, AuraPlayerData data) {
		Step s = step(data);
		if (s == null) return;
		int n = data.tutorialStep + 1, total = Step.values().length;
		player.sendSystemMessage(Component.literal("Tutorial " + n + "/" + total + ": ").withStyle(ChatFormatting.GOLD)
				.append(Component.literal(s.title).withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD)));
		player.sendSystemMessage(Component.literal(s.task + " ").withStyle(ChatFormatting.GRAY).append(openLink()));
	}

	/** Every second: the roll step finishes when the player has an aura; actionbar reminder every 20 s. */
	public static void tick(MinecraftServer server) {
		int t = server.getTickCount();
		if (t % 20 != 0 || RUNNING.isEmpty()) return;
		AuraState state = AuraState.get(server);
		for (UUID id : new ArrayList<>(RUNNING)) {
			ServerPlayer player = server.getPlayerList().getPlayer(id);
			if (player == null) {
				RUNNING.remove(id);
				continue;
			}
			AuraPlayerData data = state.getOrCreate(id);
			if (data.tutorialState != STARTED) {
				RUNNING.remove(id);
				continue;
			}
			if (step(data) == Step.ROLL && data.aura != null) {
				// the roll reveal takes ~2 s; let it play before the next step talks
				if (data.tutorialStepTicks++ >= 3) {
					data.tutorialStepTicks = 0;
					advance(player, data);
				}
				continue;
			}
			Step s = step(data);
			if (s != null && t % 400 == 0 && player.containerMenu == player.inventoryMenu) {
				player.displayClientMessage(Component.literal("Tutorial: " + s.task).withStyle(ChatFormatting.YELLOW), true);
			}
		}
	}

	public static void onLogout(ServerPlayer player) {
		RUNNING.remove(player.getUUID());
	}

	/** The menu's Tutorial icon: start, continue or replay. */
	static ItemStack menuIcon(AuraPlayerData data) {
		Step s = step(data);
		List<Component> lore = new ArrayList<>();
		String name;
		if (data.tutorialState == STARTED && s != null) {
			name = "Tutorial (" + (data.tutorialStep + 1) + "/" + Step.values().length + ")";
			lore.add(AuraBook.text("Now: " + s.task, ChatFormatting.GRAY));
			lore.add(AuraBook.text("Click: show the step again", ChatFormatting.YELLOW));
		} else {
			name = data.tutorialState == DONE ? "Replay the tutorial" : "Tutorial";
			lore.add(AuraBook.text("A short walk through the Aura Book.", ChatFormatting.GRAY));
			lore.add(AuraBook.text("Click: start", ChatFormatting.YELLOW));
		}
		return AuraBook.icon(Items.KNOWLEDGE_BOOK, AuraBook.text(name, ChatFormatting.GOLD), lore, data.tutorialState == STARTED);
	}

	static void menuClick(ServerPlayer player, AuraPlayerData data) {
		if (data.tutorialState == STARTED) {
			RUNNING.add(player.getUUID());
			skipDone(player, data);
			announce(player, data);
		} else {
			data.tutorialState = OFFER;
			start(player, data);
		}
		AuraBook.pageSound(player);
	}

	/** Glints the icon the current step wants clicked on the main page. */
	static void highlight(AuraPlayerData data, ItemStack[] slots) {
		if (data.tutorialState != STARTED) return;
		Step s = step(data);
		if (s == null) return;
		int slot = switch (s) {
			case ROLL -> AuraBook.MainPage.ROLL;
			case AURA -> AuraBook.MainPage.AURA;
			case SKILLS -> AuraBook.MainPage.SKILLS;
			case SETTINGS -> AuraBook.MainPage.SETTINGS;
		};
		ItemStack stack = slots[slot];
		if (stack.isEmpty()) return;
		stack.set(net.minecraft.core.component.DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
		var lore = new ArrayList<>(stack.getOrDefault(net.minecraft.core.component.DataComponents.LORE, net.minecraft.world.item.component.ItemLore.EMPTY).lines());
		lore.add(0, Component.literal("▶ Tutorial: click here").withStyle(ChatFormatting.GOLD).withStyle(st -> st.withItalic(false)));
		stack.set(net.minecraft.core.component.DataComponents.LORE, new net.minecraft.world.item.component.ItemLore(lore));
	}
}
