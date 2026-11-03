package com.toast.aurapoints.fabric.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.toast.aurapoints.fabric.DropCapture;
import com.toast.aurapoints.shim.AuraBus;
import com.toast.aurapoints.shim.neoforge.event.entity.EntityTravelToDimensionEvent;
import com.toast.aurapoints.shim.neoforge.event.entity.living.LivingDeathEvent;
import com.toast.aurapoints.shim.neoforge.event.entity.player.PlayerEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.DimensionTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/** ServerPlayer's NeoForge hooks: death, tab list name, dropping items while dying, changing dimension, menu switching. */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin {
	@Shadow
	public abstract void doCloseContainer();

	@Inject(method = "die", at = @At("HEAD"), cancellable = true)
	private void aurapoints$death(DamageSource source, CallbackInfo ci) {
		if (AuraBus.BUS.post(new LivingDeathEvent((ServerPlayer) (Object) this, source)).isCanceled()) ci.cancel();
	}

	@ModifyReturnValue(method = "getTabListDisplayName", at = @At("RETURN"))
	private Component aurapoints$tabListName(Component name) {
		return AuraBus.BUS.post(new PlayerEvent.TabListNameFormat((ServerPlayer) (Object) this, name)).getDisplayName();
	}

	/** Items a dying player drops (inventory, accessories) join the death drops list instead of the level right away. */
	@WrapOperation(method = "drop(Lnet/minecraft/world/item/ItemStack;ZZ)Lnet/minecraft/world/entity/item/ItemEntity;",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z"))
	private boolean aurapoints$captureDrop(Level level, Entity entity, Operation<Boolean> original) {
		List<ItemEntity> capture = ((DropCapture) this).aurapoints$capture();
		if (capture != null && entity instanceof ItemEntity item) {
			capture.add(item);
			return true;
		}
		return original.call(level, entity);
	}

	@Inject(method = "changeDimension", at = @At("HEAD"), cancellable = true)
	private void aurapoints$travel(DimensionTransition transition, CallbackInfoReturnable<Entity> cir) {
		if (AuraBus.BUS.post(new EntityTravelToDimensionEvent((ServerPlayer) (Object) this, transition.newLevel().dimension())).isCanceled()) {
			cir.setReturnValue(null);
		}
	}

	/** NeoForge's MenuProvider.shouldTriggerClientSideContainerClosingOnOpen(): false = switch menus without closing the screen. */
	@WrapOperation(method = "openMenu", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;closeContainer()V"))
	private void aurapoints$seamlessMenu(ServerPlayer self, Operation<Void> original, @Local(argsOnly = true) MenuProvider provider) {
		if (provider != null && !closesOnOpen(provider)) doCloseContainer();
		else original.call(self);
	}

	private static boolean closesOnOpen(MenuProvider provider) {
		try {
			var m = provider.getClass().getMethod("shouldTriggerClientSideContainerClosingOnOpen");
			m.setAccessible(true);
			return !(m.invoke(provider) instanceof Boolean b) || b;
		} catch (ReflectiveOperationException e) {
			return true;
		}
	}
}
