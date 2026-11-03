package com.toast.aurapoints.fabric.client;

import com.toast.aurapoints.config.AuraClientConfig;
import com.toast.aurapoints.shim.neoforge.common.ModConfigSpec;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.CommonComponents;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The client settings (config/aurapoints-client.toml) as a screen, built from the same spec NeoForge's config screen
 * uses: a heading per section, a toggle for on/off and choice settings, a text box for numbers and text. Done saves.
 */
public final class ConfigScreen extends Screen {
	private final Screen parent;
	private OptionList list;
	private final List<Runnable> apply = new ArrayList<>();

	public ConfigScreen(Screen parent) {
		super(Component.literal("Aura Points"));
		this.parent = parent;
	}

	private static Component label(String translation, String fallback) {
		return translation != null && Language.getInstance().has(translation) ? Component.translatable(translation) : Component.literal(fallback);
	}

	@Override
	protected void init() {
		apply.clear();
		list = new OptionList(minecraft, width, height - 64, 32, 24);
		for (ModConfigSpec.Op op : AuraClientConfig.SPEC.ops()) {
			if (op instanceof ModConfigSpec.Push push) list.add(new Heading(label(push.translation(), push.name())));
			else if (op instanceof ModConfigSpec.Value v) list.add(row(v.value()));
		}
		addRenderableWidget(list);
		addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).bounds(width / 2 - 100, height - 27, 200, 20).build());
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private Row row(ModConfigSpec.ConfigValue<?> v) {
		String key = v.getPath().get(v.getPath().size() - 1);
		Component name = label(v.translation(), key);
		Component tip = Component.literal(String.join("\n", v.comment()));
		AbstractWidget widget;
		if (v instanceof ModConfigSpec.BooleanValue b) {
			CycleButton<Boolean> c = CycleButton.onOffBuilder(b.get()).displayOnlyValue().create(0, 0, 120, 20, name);
			apply.add(() -> b.set(c.getValue()));
			widget = c;
		} else if (v instanceof ModConfigSpec.EnumValue e) {
			Enum<?>[] values = (Enum<?>[]) e.type.getEnumConstants();
			CycleButton<Enum<?>> c = CycleButton.<Enum<?>>builder(x -> Component.literal(x.name())).withValues(Arrays.asList(values))
					.withInitialValue((Enum<?>) e.get()).displayOnlyValue().create(0, 0, 120, 20, name);
			apply.add(() -> ((ModConfigSpec.ConfigValue) e).set(c.getValue()));
			widget = c;
		} else {
			EditBox box = new EditBox(font, 0, 0, 120, 20, name);
			box.setMaxLength(64);
			box.setValue(String.valueOf(v.get()));
			apply.add(() -> {
				Object value = parse(v, box.getValue());
				if (value != null && v.valid(value)) ((ModConfigSpec.ConfigValue) v).set(value);
			});
			widget = box;
		}
		widget.setTooltip(net.minecraft.client.gui.components.Tooltip.create(tip));
		return new Row(name, widget);
	}

	private static Object parse(ModConfigSpec.ConfigValue<?> v, String text) {
		try {
			if (v instanceof ModConfigSpec.IntValue) return Integer.parseInt(text.strip());
			if (v instanceof ModConfigSpec.DoubleValue) return Double.parseDouble(text.strip());
			return text;
		} catch (NumberFormatException e) {
			return null;
		}
	}

	@Override
	public void onClose() {
		apply.forEach(Runnable::run);
		AuraClientConfig.SPEC.save();
		minecraft.setScreen(parent);
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
		super.render(g, mouseX, mouseY, partial);
		g.drawCenteredString(font, title, width / 2, 12, 0xFFFFFF);
	}

	abstract static class OptEntry extends ContainerObjectSelectionList.Entry<OptEntry> {
	}

	final class Heading extends OptEntry {
		private final Component text;

		Heading(Component text) {
			this.text = text;
		}

		@Override
		public void render(GuiGraphics g, int index, int top, int left, int w, int h, int mouseX, int mouseY, boolean hovered, float partial) {
			g.drawCenteredString(font, text.copy().withStyle(net.minecraft.ChatFormatting.YELLOW), ConfigScreen.this.width / 2, top + 6, 0xFFFFFF);
		}

		@Override
		public List<? extends GuiEventListener> children() {
			return List.of();
		}

		@Override
		public List<? extends NarratableEntry> narratables() {
			return List.of();
		}
	}

	final class Row extends OptEntry {
		private final Component name;
		private final AbstractWidget widget;

		Row(Component name, AbstractWidget widget) {
			this.name = name;
			this.widget = widget;
		}

		@Override
		public void render(GuiGraphics g, int index, int top, int left, int w, int h, int mouseX, int mouseY, boolean hovered, float partial) {
			g.drawString(font, name, left, top + 6, 0xFFFFFF);
			widget.setX(left + w - widget.getWidth());
			widget.setY(top);
			widget.render(g, mouseX, mouseY, partial);
		}

		@Override
		public List<? extends GuiEventListener> children() {
			return List.of(widget);
		}

		@Override
		public List<? extends NarratableEntry> narratables() {
			return List.of(widget);
		}
	}

	final class OptionList extends ContainerObjectSelectionList<OptEntry> {
		OptionList(Minecraft mc, int width, int height, int top, int itemHeight) {
			super(mc, width, height, top, itemHeight);
		}

		void add(OptEntry e) {
			addEntry(e);
		}

		@Override
		public int getRowWidth() {
			return Math.min(360, width - 40);
		}
	}
}
