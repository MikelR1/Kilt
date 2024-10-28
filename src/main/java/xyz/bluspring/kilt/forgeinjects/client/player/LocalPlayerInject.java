// TRACKED HASH: 4a532f665aabef3c36a9818c82e846213791c292
package xyz.bluspring.kilt.forgeinjects.client.player;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraftforge.common.ForgeHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xyz.bluspring.kilt.injections.client.player.LocalPlayerInjection;

@Mixin(LocalPlayer.class)
public class LocalPlayerInject implements LocalPlayerInjection {
    @Shadow private double xLast;
    @Shadow private double yLast1;
    @Shadow private double zLast;
    @Shadow private float yRotLast;
    @Shadow private float xRotLast;
    @Shadow private boolean lastOnGround;
    @Shadow private boolean wasShiftKeyDown;
    @Shadow private boolean wasSprinting;
    @Shadow private int positionReminder;

    @Inject(method = "hurt", at = @At("HEAD"))
    private void kilt$runPlayerAttackEvent(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        ForgeHooks.onPlayerAttack((LocalPlayer) (Object) this, source, amount);
    }

    @Override
    public void updateSyncFields(LocalPlayer old) {
        this.xLast = ((LocalPlayerInject) (Object) old).xLast;
        this.yLast1 = ((LocalPlayerInject) (Object) old).yLast1;
        this.zLast = ((LocalPlayerInject) (Object) old).zLast;
        this.yRotLast = ((LocalPlayerInject) (Object) old).yRotLast;
        this.xRotLast = ((LocalPlayerInject) (Object) old).xRotLast;
        this.lastOnGround = ((LocalPlayerInject) (Object) old).lastOnGround;
        this.wasShiftKeyDown = ((LocalPlayerInject) (Object) old).wasShiftKeyDown;
        this.wasSprinting = ((LocalPlayerInject) (Object) old).wasSprinting;
        this.positionReminder = ((LocalPlayerInject) (Object) old).positionReminder;
    }
}