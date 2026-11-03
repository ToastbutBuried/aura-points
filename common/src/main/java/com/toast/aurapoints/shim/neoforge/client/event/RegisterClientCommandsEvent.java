package com.toast.aurapoints.shim.neoforge.client.event;

// Generated shim event (genevents.py): fields in, results read back by the loader layer.
public class RegisterClientCommandsEvent extends com.toast.aurapoints.shim.bus.api.Event {
	private com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack> dispatcher;
	private net.minecraft.commands.CommandBuildContext buildContext;

	public RegisterClientCommandsEvent(com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack> dispatcher, net.minecraft.commands.CommandBuildContext buildContext) {
		this.dispatcher = dispatcher;
		this.buildContext = buildContext;
	}

	public com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack> getDispatcher() {
		return dispatcher;
	}

	public net.minecraft.commands.CommandBuildContext getBuildContext() {
		return buildContext;
	}
}
