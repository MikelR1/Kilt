// TRACKED HASH: da42f0fcd542552388a5aff060abf470c54f9f10
package xyz.bluspring.kilt.forgeinjects.world.entity.player;

import net.minecraft.core.BlockPos;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.common.extensions.IForgePlayer;
import net.minecraftforge.event.ForgeEventFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xyz.bluspring.kilt.helpers.mixin.CreateStatic;
import xyz.bluspring.kilt.injections.entity.PlayerInjection;

import javax.annotation.Nullable;
import java.util.concurrent.atomic.AtomicReference;

@Mixin(Player.class)
public abstract class PlayerInject extends LivingEntity implements IForgePlayer, PlayerInjection {
    @CreateStatic
    private static final String PERSISTED_NBT_TAG = PlayerInjection.PERSISTED_NBT_TAG;

    @Shadow public abstract float getDestroySpeed(BlockState state);

    protected PlayerInject(EntityType<? extends LivingEntity> entityType, Level level) {
        super(entityType, level);
    }

    @Inject(method = "tick", at = @At("HEAD"))
    public void kilt$playerTickStart(CallbackInfo ci) {
        ForgeEventFactory.onPlayerPreTick((Player) (Object) this);
    }

    @Inject(method = "tick", at = @At("TAIL"))
    public void kilt$playerTickEnd(CallbackInfo ci) {
        ForgeEventFactory.onPlayerPostTick((Player) (Object) this);
    }

    private final AtomicReference<BlockPos> kilt$dugBlockPos = new AtomicReference<>();

    public float getDigSpeed(BlockState blockState, @Nullable BlockPos blockPos) {
        if (blockPos != null)
            this.kilt$dugBlockPos.set(blockPos);
        return this.getDestroySpeed(blockState);
    }

    @Inject(at = @At("TAIL"), method = "getDestroySpeed", cancellable = true)
    public void kilt$modifyBreakSpeed(BlockState state, CallbackInfoReturnable<Float> cir) {
        var blockPos = this.kilt$dugBlockPos.getAndSet(null);

        if (blockPos != null)
            cir.setReturnValue(ForgeEventFactory.getBreakSpeed((Player) (Object) this, state, cir.getReturnValue(), blockPos));
    }

    @Inject(method = "hurt", at = @At("HEAD"), cancellable = true)
    private void kilt$checkPlayerAttack(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        if (!ForgeHooks.onPlayerAttack((Player) (Object) this, source, amount))
            cir.setReturnValue(false);
    }

    @Inject(method = "attack", at = @At("HEAD"), cancellable = true)
    private void kilt$checkPlayerTargetAttack(Entity target, CallbackInfo ci) {
        if (!ForgeHooks.onPlayerAttackTarget((Player) (Object) this, target))
            ci.cancel();
    }

    /*@ModifyReturnValue(method = "createAttributes", at = @At("RETURN"))
    private static AttributeSupplier.Builder kilt$addForgeAttributes(AttributeSupplier.Builder original) {
        return original
            .add(ForgeMod.BLOCK_REACH.get())
            .add(Attributes.ATTACK_KNOCKBACK)
            .add(ForgeMod.ENTITY_REACH.get());
    }*/
}