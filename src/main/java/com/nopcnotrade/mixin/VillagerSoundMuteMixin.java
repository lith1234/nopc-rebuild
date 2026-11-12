package com.nopcnotrade.mixin;

import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Silences vanilla villager sounds locally while leaving this mod's custom voice clips untouched. */
@Mixin(SoundManager.class)
abstract class VillagerSoundMuteMixin {
    @Inject(
            method = "play(Lnet/minecraft/client/resources/sounds/SoundInstance;)Lnet/minecraft/client/sounds/SoundEngine$PlayResult;",
            at = @At("HEAD"),
            cancellable = true
    )
    private void nopc$muteVillagerSound(SoundInstance sound,
                                        CallbackInfoReturnable<SoundEngine.PlayResult> cir) {
        Identifier id = sound.getIdentifier();
        if (id != null && id.getNamespace().equals("minecraft")
                && id.getPath().startsWith("entity.villager.")) {
            cir.setReturnValue(SoundEngine.PlayResult.NOT_STARTED);
        }
    }

    @Inject(
            method = "playDelayed(Lnet/minecraft/client/resources/sounds/SoundInstance;I)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void nopc$muteDelayedVillagerSound(SoundInstance sound, int delay, CallbackInfo ci) {
        Identifier id = sound.getIdentifier();
        if (id != null && id.getNamespace().equals("minecraft")
                && id.getPath().startsWith("entity.villager.")) {
            ci.cancel();
        }
    }
}
