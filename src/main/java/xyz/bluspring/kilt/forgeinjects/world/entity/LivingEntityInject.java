// TRACKED HASH: 0103ffc8bca3b91dd898021eb13bdca66921d3eb
package xyz.bluspring.kilt.forgeinjects.world.entity;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef;
import com.llamalad7.mixinextras.sugar.ref.LocalFloatRef;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import io.github.fabricators_of_create.porting_lib.entity.extensions.EntityExtensions;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.extensions.IForgeLivingEntity;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.event.ForgeEventFactory;
import net.minecraftforge.event.entity.living.*;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.items.wrapper.EntityEquipmentInvWrapper;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;
import xyz.bluspring.kilt.injections.CapabilityProviderInjection;

import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.function.Consumer;

@Mixin(LivingEntity.class)
public abstract class LivingEntityInject extends Entity implements IForgeLivingEntity, EntityExtensions, CapabilityProviderInjection {
    public LivingEntityInject(EntityType<?> entityType, Level level) {
        super(entityType, level);
    }

    @Shadow public abstract boolean isAlive();

    @Shadow @Final private static EntityDataAccessor<Integer> DATA_EFFECT_COLOR_ID;
    @Shadow @Final private static EntityDataAccessor<Boolean> DATA_EFFECT_AMBIENCE_ID;
    @Shadow @Nullable protected Player lastHurtByPlayer;

    @Shadow public abstract ItemStack getItemInHand(InteractionHand hand);

    private LazyOptional<?>[] handlers = EntityEquipmentInvWrapper.create((LivingEntity) (Object) this);

    @WrapWithCondition(method = "checkFallDamage", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;sendParticles(Lnet/minecraft/core/particles/ParticleOptions;DDDIDDDD)I"))
    private <T extends ParticleOptions> boolean kilt$checkIfShouldSpawnParticles(ServerLevel instance, T type, double posX, double posY, double posZ, int particleCount, double xOffset, double yOffset, double zOffset, double speed, @Local(argsOnly = true) BlockState state, @Local(argsOnly = true) BlockPos pos, @Local int i) {
        return !state.addLandingEffects(instance, pos, state, (LivingEntity) (Object) this, i);
    }

    @WrapOperation(method = "baseTick", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/LivingEntity;isInPowderSnow:Z"))
    private boolean kilt$checkIfCanExtinguish(LivingEntity instance, Operation<Boolean> original) {
        return original.call(instance) || instance.isInFluidType((fluidType, height) -> instance.canFluidExtinguish(fluidType));
    }

    @WrapOperation(method = "tickEffects", at = @At(value = "FIELD", target = "Lnet/minecraft/world/level/Level;isClientSide:Z", ordinal = 0))
    private boolean kilt$checkIfEffectExpired(Level instance, Operation<Boolean> original, @Local MobEffectInstance effect) {
        return original.call(instance) || MinecraftForge.EVENT_BUS.post(new MobEffectEvent.Expired((LivingEntity) (Object) this, effect));
    }

    @Inject(method = "updateInvisibilityStatus", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;setInvisible(Z)V", ordinal = 1))
    private void kilt$calculateEffectColors(CallbackInfo ci, @Local Collection<MobEffectInstance> effects) {
        var event = new PotionColorCalculationEvent((LivingEntity) (Object) this, this.entityData.get(DATA_EFFECT_COLOR_ID), this.entityData.get(DATA_EFFECT_AMBIENCE_ID), effects);
        MinecraftForge.EVENT_BUS.post(event);

        this.entityData.set(DATA_EFFECT_AMBIENCE_ID, event.areParticlesHidden());
        this.entityData.set(DATA_EFFECT_COLOR_ID, event.getColor());
    }

    @ModifyReturnValue(method = "getVisibilityPercent", at = @At("RETURN"))
    private double kilt$modifyVisibilityMultiplier(double original, @Local(argsOnly = true) Entity lookingEntity) {
        return ForgeHooks.getEntityVisibilityMultiplier((LivingEntity) (Object) this, lookingEntity, original);
    }

    @WrapWithCondition(method = "removeAllEffects", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;onEffectRemoved(Lnet/minecraft/world/effect/MobEffectInstance;)V"))
    private boolean kilt$callRemoveEffectEvent(LivingEntity instance, MobEffectInstance effectInstance, @Share("shouldRemove") LocalBooleanRef shouldCancel) {
        if (MinecraftForge.EVENT_BUS.post(new MobEffectEvent.Remove((LivingEntity) (Object) this, effectInstance))) {
            shouldCancel.set(true);
            return false;
        }

        return true;
    }

    @WrapWithCondition(method = "removeAllEffects", at = @At(value = "INVOKE", target = "Ljava/util/Iterator;remove()V"))
    private boolean kilt$checkIfCancelledAlready(Iterator<?> instance, @Share("shouldRemove") LocalBooleanRef shouldCancel) {
        return !shouldCancel.get();
    }

    @WrapOperation(method = "addEffect(Lnet/minecraft/world/effect/MobEffectInstance;Lnet/minecraft/world/entity/Entity;)Z", at = @At(value = "INVOKE", target = "Ljava/util/Map;get(Ljava/lang/Object;)Ljava/lang/Object;"))
    private <K, V> V kilt$callAddEffectEvent(Map<K, V> instance, K o, Operation<V> original, @Local(argsOnly = true) MobEffectInstance newEffect, @Local(argsOnly = true) Entity entity) {
        @SuppressWarnings("MixinExtrasOperationParameters")
        var oldEffect = (MobEffectInstance) original.call(instance, o);

        MinecraftForge.EVENT_BUS.post(new MobEffectEvent.Added((LivingEntity) (Object) this, oldEffect, newEffect, entity));
        return null;
    }

    @Inject(method = "canBeAffected", at = @At("HEAD"), cancellable = true)
    private void kilt$checkIsEffectApplicable(MobEffectInstance effectInstance, CallbackInfoReturnable<Boolean> cir) {
        var event = new MobEffectEvent.Applicable((LivingEntity) (Object) this, effectInstance);
        MinecraftForge.EVENT_BUS.post(event);

        if (event.getResult() != Event.Result.DEFAULT) {
            cir.setReturnValue(event.getResult() == Event.Result.ALLOW);
        }
    }

    @Inject(method = "removeEffect", at = @At("HEAD"), cancellable = true)
    private void kilt$checkRemoveEffect(MobEffect effect, CallbackInfoReturnable<Boolean> cir) {
        if (MinecraftForge.EVENT_BUS.post(new MobEffectEvent.Remove((LivingEntity) (Object) this, effect)))
            cir.setReturnValue(false);
    }

    @ModifyVariable(method = "heal", at = @At("HEAD"), argsOnly = true)
    private float kilt$callHealEvent(float value) {
        return ForgeEventFactory.onLivingHeal((LivingEntity) (Object) this, value);
    }

    @Inject(method = "heal", at = @At("HEAD"), cancellable = true)
    private void kilt$checkIfHealValueIsNegative(float healAmount, CallbackInfo ci) {
        if (healAmount <= 0)
            ci.cancel();
    }

    @Inject(method = "hurt", at = @At("HEAD"), cancellable = true)
    private void kilt$checkLivingAttackEvent(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        if (!ForgeHooks.onLivingAttack((LivingEntity) (Object) this, source, amount))
            cir.setReturnValue(false);
    }

    @WrapOperation(method = "hurt", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;isDamageSourceBlocked(Lnet/minecraft/world/damagesource/DamageSource;)Z"))
    private boolean kilt$checkShieldBlocked(LivingEntity instance, DamageSource damageSource, Operation<Boolean> original, @Share("event") LocalRef<ShieldBlockEvent> eventRef, @Local(argsOnly = true) float damage) {
        var isBlocked = original.call(instance, damageSource);

        if (isBlocked) {
            eventRef.set(ForgeHooks.onShieldBlock(instance, damageSource, damage));
            isBlocked = !eventRef.get().isCanceled();
        }

        return isBlocked;
    }

    @WrapWithCondition(method = "hurt", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;hurtCurrentlyUsedShield(F)V"))
    private boolean kilt$checkShieldTakesDamage(LivingEntity instance, float damageAmount, @Share("event") LocalRef<ShieldBlockEvent> eventRef) {
        return eventRef.get().shieldTakesDamage();
    }

    // TODO: handle blocked damage and set total damage based on blocked damage

    @ModifyExpressionValue(method = "hurt", at = @At(value = "CONSTANT", args = "intValue=1"))
    private int kilt$checkIfDamageNegative(int original, @Local(argsOnly = true) float damageAmount) {
        // why are bools handled this way
        return ((original == 1) && (damageAmount <= 0)) ? 1 : 0;
    }

    // TODO: implement TamableAnimal instanceof check

    @WrapOperation(method = "checkTotemDeathProtection", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;is(Lnet/minecraft/world/item/Item;)Z", ordinal = 0))
    private boolean kilt$checkTotemEvent(ItemStack instance, Item item, Operation<Boolean> original, @Local(argsOnly = true) DamageSource source, @Local InteractionHand hand) {
        return original.call(instance, item) && ForgeHooks.onLivingUseTotem((LivingEntity) (Object) this, source, instance, hand);
    }

    @Inject(method = "die", at = @At("HEAD"), cancellable = true)
    private void kilt$checkLivingDeath(DamageSource damageSource, CallbackInfo ci) {
        if (ForgeHooks.onLivingDeath((LivingEntity) (Object) this, damageSource))
            ci.cancel();
    }

    @WrapOperation(method = "createWitherRose", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/GameRules;getBoolean(Lnet/minecraft/world/level/GameRules$Key;)Z"))
    private boolean kilt$checkCanMobGrief(GameRules instance, GameRules.Key<GameRules.BooleanValue> key, Operation<Boolean> original, @Local(argsOnly = true, ordinal = 1) LivingEntity entity) {
        return original.call(instance, key) || ForgeEventFactory.getMobGriefingEvent(this.level(), entity);
    }

    @WrapOperation(method = "createWitherRose", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/state/BlockState;isAir()Z"))
    private boolean kilt$checkIsEmpty(BlockState instance, Operation<Boolean> original, @Local BlockPos pos) {
        return original.call(instance) || this.level().isEmptyBlock(pos);
    }

    // Looting Level and Capture Drops events handled by Porting Lib

    @ModifyArg(method = "dropExperience", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/ExperienceOrb;award(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/phys/Vec3;I)V"))
    private int kilt$modifyExperienceReward(int original) {
        return ForgeEventFactory.getExperienceDrop((LivingEntity) (Object) this, this.lastHurtByPlayer, original);
    }

    @ModifyArgs(method = "knockback", at = @At("HEAD"))
    private void kilt$modifyKnockbackArgs(Args args, @Share("event") LocalRef<LivingKnockBackEvent> eventRef) {
        var event = ForgeHooks.onLivingKnockBack((LivingEntity) (Object) this, args.get(0), args.get(1), args.get(2));

        args.set(0, event.getStrength());
        args.set(1, event.getRatioX());
        args.set(2, event.getRatioZ());
        eventRef.set(event);
    }

    @Inject(method = "knockback", at = @At("HEAD"), cancellable = true)
    private void kilt$checkIfCancelledKnockback(double strength, double x, double z, CallbackInfo ci, @Share("event") LocalRef<LivingKnockBackEvent> eventRef) {
        if (eventRef.get().isCanceled())
            ci.cancel();
    }

    @ModifyArgs(method = "causeFallDamage", at = @At("HEAD"))
    private void kilt$modifyFallDamageArgs(Args args, @Share("values") LocalRef<float[]> valueRef) {
        var values = ForgeHooks.onLivingFall((LivingEntity) (Object) this, args.get(0), args.get(1));
        args.set(0, values[0]);
        args.set(1, values[1]);
        valueRef.set(values);
    }

    @Inject(method = "causeFallDamage", at = @At("HEAD"), cancellable = true)
    private void kilt$checkIfCancelledFallDamage(float fallDistance, float multiplier, DamageSource source, CallbackInfoReturnable<Boolean> cir, @Share("values") LocalRef<float[]> valueRef) {
        if (valueRef.get() == null)
            cir.setReturnValue(false);
    }

    // TODO: handle custom Forge sound type

    @ModifyArg(method = "actuallyHurt", at = @At("HEAD"))
    private float kilt$setDamageValueFromEvent(float original, @Local(argsOnly = true) DamageSource source) {
        return ForgeHooks.onLivingHurt((LivingEntity) (Object) this, source, original);
    }

    @Inject(method = "actuallyHurt", at = @At("HEAD"), cancellable = true)
    private void kilt$cancelIfNegativeDamage(DamageSource damageSource, float damageAmount, CallbackInfo ci) {
        if (damageAmount <= 0)
            ci.cancel();
    }

    @Inject(method = "actuallyHurt", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;setAbsorptionAmount(F)V", shift = At.Shift.BY, by = 2))
    private void kilt$callDamageEvent(DamageSource damageSource, float damageAmount, CallbackInfo ci, @Local(argsOnly = true) DamageSource source, @Local(argsOnly = true) LocalFloatRef damageAmountRef) {
        damageAmountRef.set(ForgeHooks.onLivingDamage((LivingEntity) (Object) this, source, damageAmount));
    }

    @Inject(method = "swing(Lnet/minecraft/world/InteractionHand;)V", at = @At("HEAD"), cancellable = true)
    private void kilt$checkStackSwing(InteractionHand hand, CallbackInfo ci) {
        var stack = this.getItemInHand(hand);

        if (!stack.isEmpty() && stack.onEntitySwing((LivingEntity) (Object) this))
            ci.cancel();
    }

    @Inject(method = "swapHandItems", at = @At("HEAD"), cancellable = true)
    private void kilt$callSwapHandItemsEvent(CallbackInfo ci, @Share("event") LocalRef<LivingSwapItemsEvent.Hands> event) {
        event.set(ForgeHooks.onLivingSwapHandItems((LivingEntity) (Object) this));

        if (event.get().isCanceled())
            ci.cancel();
    }

    @WrapOperation(method = "swapHandItems", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;setItemSlot(Lnet/minecraft/world/entity/EquipmentSlot;Lnet/minecraft/world/item/ItemStack;)V"))
    private void kilt$changeHandItems(LivingEntity instance, EquipmentSlot slot, ItemStack itemStack, Operation<Void> original, @Share("event") LocalRef<LivingSwapItemsEvent.Hands> eventRef) {
        var event = eventRef.get();

        if (slot == EquipmentSlot.OFFHAND && !event.getItemSwappedToOffHand().equals(itemStack)) {
            itemStack = event.getItemSwappedToOffHand();
        } else if (slot == EquipmentSlot.MAINHAND && !event.getItemSwappedToMainHand().equals(itemStack)) {
            itemStack = event.getItemSwappedToMainHand();
        }

        original.call(instance, slot, itemStack);
    }

    @Inject(method = "jumpFromGround", at = @At("TAIL"))
    private void kilt$callJumpEvent(CallbackInfo ci) {
        ForgeHooks.onLivingJump((LivingEntity) (Object) this);
    }

    // TODO: implement more patches starting from L404

    @Override
    public @NotNull <T> LazyOptional<T> getCapability(@NotNull Capability<T> cap, @Nullable Direction side) {
        if (this.isAlive() && cap == ForgeCapabilities.ITEM_HANDLER) {
            if (side == null)
                return handlers[2].cast();
            else if (side.getAxis().isVertical())
                return handlers[0].cast();
            else if (side.getAxis().isHorizontal())
                return handlers[1].cast();
        }

        return super.getCapability(cap, side);
    }

    @Redirect(method = "dropFromLootTable", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/storage/loot/LootTable;getRandomItems(Lnet/minecraft/world/level/storage/loot/LootParams;JLjava/util/function/Consumer;)V"))
    public void kilt$disableVanillaLootTable(LootTable instance, LootParams params, long seed, Consumer<ItemStack> output) {
    }

    @Inject(at = @At("TAIL"), method = "dropFromLootTable", locals = LocalCapture.CAPTURE_FAILHARD)
    public void kilt$useForgeLootTables(DamageSource damageSource, boolean hitByPlayer, CallbackInfo ci, ResourceLocation resourceLocation, LootTable lootTable, LootParams.Builder builder, LootParams lootParams) {
        var ctx = builder.create(LootContextParamSets.ENTITY);
        lootTable.getRandomItems(ctx).forEach(((LivingEntity) (Object) this)::spawnAtLocation);
    }

    /*@ModifyReturnValue(method = "createLivingAttributes", at = @At("RETURN"))
    private static AttributeSupplier.Builder kilt$addForgeAttributes(AttributeSupplier.Builder original) {
        return original
            .add(ForgeMod.SWIM_SPEED.get())
            .add(ForgeMod.NAMETAG_DISTANCE.get())
            .add(ForgeMod.ENTITY_GRAVITY.get())
            .add(ForgeMod.STEP_HEIGHT.get());
    }*/
}