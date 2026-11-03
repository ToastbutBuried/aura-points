package com.toast.aurapoints.shim.neoforge.event;

// Generated shim event (genevents.py): fields in, results read back by the loader layer.
public class RegisterCommandsEvent extends com.toast.aurapoints.shim.bus.api.Event {
	private com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack> dispatcher;
	private net.minecraft.commands.Commands.CommandSelection selection;
	private net.minecraft.commands.CommandBuildContext buildContext;

	public RegisterCommandsEvent(com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack> dispatcher, net.minecraft.commands.Commands.CommandSelection selection, net.minecraft.commands.CommandBuildContext buildContext) {
		this.dispatcher = dispatcher;
		this.selection = selection;
		this.buildContext = buildContext;
	}

	public com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack> getDispatcher() {
		return dispatcher;
	}

	public net.minecraft.commands.Commands.CommandSelection getCommandSelection() {
		return selection;
	}

	public net.minecraft.commands.CommandBuildContext getBuildContext() {
		return buildContext;
	}
}
