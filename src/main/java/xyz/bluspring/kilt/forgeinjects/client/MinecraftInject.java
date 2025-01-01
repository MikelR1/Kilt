// TRACKED HASH: 8a008dde196be8f110c6df462a387035cbfd879c
package xyz.bluspring.kilt.forgeinjects.client;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.Timer;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.color.item.ItemColors;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.main.GameConfig;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.searchtree.SearchRegistry;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.resources.ReloadableResourceManager;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.CreativeModeTabSearchRegistry;
import net.minecraftforge.client.ForgeHooksClient;
import net.minecraftforge.client.extensions.IForgeMinecraft;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.loading.ClientModLoader;
import net.minecraftforge.event.ForgeEventFactory;
import net.minecraftforge.fml.ModLoader;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xyz.bluspring.kilt.client.ClientStartingCallback;
import xyz.bluspring.kilt.injections.client.MinecraftInjection;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Mixin(Minecraft.class)
public abstract class MinecraftInject implements MinecraftInjection, IForgeMinecraft {
    @Shadow @Final @Mutable
    private ItemColors itemColors;

    @Shadow @Final private SearchRegistry searchRegistry;
    @Shadow @Final private ReloadableResourceManager resourceManager;
    @Shadow @Final public Options options;
    @Shadow private volatile boolean pause;
    @Shadow private float pausePartialTick;
    @Shadow @Final private Timer timer;
    @Shadow @Final public ParticleEngine particleEngine;
    @Shadow @Final private PackRepository resourcePackRepository;

    @Shadow public abstract BlockColors getBlockColors();

    @Mutable
    @Shadow @Final private BlockColors blockColors;
    @Unique
    private float realPartialTick;

    // This has to be public, it's a field that is used by the WorkaroundFixer.
    public Gui kilt$forgeGui = null;

    @Override
    public float getPartialTick() {
        return realPartialTick;
    }

    @Inject(method = "getBlockColors", at = @At("HEAD"))
    private void kilt$workaroundEmptyBlockColors(CallbackInfoReturnable<BlockColors> cir) {
        if (this.blockColors == null)
            this.blockColors = BlockColors.createDefault();
    }

    @Override
    public ItemColors getItemColors() {
        if (this.itemColors == null)
            this.itemColors = ItemColors.createDefault(this.getBlockColors());

        return this.itemColors;
    }

    @Override
    public SearchRegistry getSearchTreeManager() {
        return this.searchRegistry;
    }

    @Inject(at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/Window;updateVsync(Z)V", shift = At.Shift.BEFORE), method = "<init>")
    public void kilt$initializeForge(GameConfig gameConfig, CallbackInfo ci) {
        ForgeHooksClient.initClientHooks((Minecraft) (Object) this, this.resourceManager);
    }

    @Inject(method = "<init>", at = @At("TAIL"))
    private void kilt$callStartingEvent(GameConfig gameConfig, CallbackInfo ci) {
        ClientStartingCallback.EVENT.invoker().onClientStarting((Minecraft) (Object) this);
    }

    @Inject(at = @At(value = "INVOKE", target = "Lnet/minecraft/server/packs/repository/PackRepository;reload()V"), method = "<init>")
    public void kilt$initializeClientModLoader(GameConfig gameConfig, CallbackInfo ci) {
        ClientModLoader.begin((Minecraft) (Object) this, this.resourcePackRepository, this.resourceManager);
    }

    @Inject(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;<init>(Lnet/minecraft/client/Minecraft;Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;Lnet/minecraft/client/renderer/blockentity/BlockEntityRenderDispatcher;Lnet/minecraft/client/renderer/RenderBuffers;)V", shift = At.Shift.AFTER))
    private void kilt$postRegisterStageEvent(GameConfig gameConfig, CallbackInfo ci) {
        ModLoader.get().postEvent(new net.minecraftforge.client.event.RenderLevelStageEvent.RegisterStageEvent());
    }

    @WrapWithCondition(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;createSearchTrees()V"))
    private boolean kilt$delayModdedSearchTrees(Minecraft instance) {
        return false;
    }

    @Inject(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/particle/ParticleEngine;<init>(Lnet/minecraft/client/multiplayer/ClientLevel;Lnet/minecraft/client/renderer/texture/TextureManager;)V", shift = At.Shift.BY, by = 2))
    private void kilt$postRegisterParticleProviders(GameConfig gameConfig, CallbackInfo ci) {
        ForgeHooksClient.onRegisterParticleProviders(this.particleEngine);
    }

    @Inject(at = @At(value = "INVOKE", target = "Lnet/minecraft/util/profiling/ProfilerFiller;popPush(Ljava/lang/String;)V", ordinal = 0, shift = At.Shift.BEFORE), method = "runTick")
    public void kilt$setPartialTicks(boolean bl, CallbackInfo ci) {
        realPartialTick = this.pause ? this.pausePartialTick : this.timer.partialTick;
        ForgeEventFactory.onRenderTickStart(realPartialTick);
    }

    @Inject(at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;render(FJZ)V", shift = At.Shift.BY, by = 2), method = "runTick")
    public void kilt$callRenderTickEnd(boolean bl, CallbackInfo ci) {
        ForgeEventFactory.onRenderTickEnd(realPartialTick);
    }

    @Inject(method = "method_29338", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;onGameLoadFinished()V"))
    private void kilt$finishModLoading(CallbackInfo ci) {
        ClientModLoader.completeModLoading();
    }

    @Unique private Map<CreativeModeTab, SearchRegistry.Key<ItemStack>> kilt$nameSearchKeys;
    @Unique private Map<CreativeModeTab, SearchRegistry.Key<ItemStack>> kilt$tagSearchKeys;
    @Unique private SearchRegistry.Key<ItemStack> kilt$nameSearchKey;
    @Unique private SearchRegistry.Key<ItemStack> kilt$tagSearchKey;

    @Inject(method = "createSearchTrees", at = @At("HEAD"))
    private void kilt$storeNameSearchKeys(CallbackInfo ci) {
        this.kilt$nameSearchKeys = CreativeModeTabSearchRegistry.getNameSearchKeys();
        this.kilt$tagSearchKeys = CreativeModeTabSearchRegistry.getTagSearchKeys();
    }

    @WrapOperation(method = "createSearchTrees", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/searchtree/SearchRegistry;register(Lnet/minecraft/client/searchtree/SearchRegistry$Key;Lnet/minecraft/client/searchtree/SearchRegistry$TreeBuilderSupplier;)V", ordinal = 0))
    private <T> void kilt$searchMultipleNameKeys(SearchRegistry instance, SearchRegistry.Key<T> key, SearchRegistry.TreeBuilderSupplier<T> factory, Operation<Void> original) {
        for (SearchRegistry.Key<ItemStack> nameSearchKey : this.kilt$nameSearchKeys.values()) {
            original.call(instance, nameSearchKey, factory);
        }
    }

    @WrapOperation(method = "createSearchTrees", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/searchtree/SearchRegistry;register(Lnet/minecraft/client/searchtree/SearchRegistry$Key;Lnet/minecraft/client/searchtree/SearchRegistry$TreeBuilderSupplier;)V", ordinal = 1))
    private <T> void kilt$searchMultipleTagKeys(SearchRegistry instance, SearchRegistry.Key<T> key, SearchRegistry.TreeBuilderSupplier<T> factory, Operation<Void> original) {
        for (SearchRegistry.Key<ItemStack> tagSearchKey : this.kilt$tagSearchKeys.values()) {
            original.call(instance, tagSearchKey, factory);
        }
    }

    @WrapOperation(method = "createSearchTrees", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/CreativeModeTab;setSearchTreeBuilder(Ljava/util/function/Consumer;)V"))
    private void kilt$setMultipleSearchTreeBuilders(CreativeModeTab instance, Consumer<List<ItemStack>> searchTreeBuilder, Operation<Void> original) {
        this.kilt$nameSearchKeys.forEach((tab, nameSearchKey) -> {
            this.kilt$nameSearchKey = nameSearchKey;
            this.kilt$tagSearchKey = this.kilt$tagSearchKeys.get(tab);

            original.call(tab, searchTreeBuilder);

            this.kilt$nameSearchKey = null;
            this.kilt$tagSearchKey = null;
        });
    }

    @WrapOperation(method = "method_46740", at = @At(value = "FIELD", target = "Lnet/minecraft/client/searchtree/SearchRegistry;CREATIVE_NAMES:Lnet/minecraft/client/searchtree/SearchRegistry$Key;"))
    private SearchRegistry.Key<ItemStack> kilt$useNameSearchKey(Operation<SearchRegistry.Key<ItemStack>> original) {
        if (this.kilt$nameSearchKey == null)
            return original.call();

        return this.kilt$nameSearchKey;
    }

    @WrapOperation(method = "method_46740", at = @At(value = "FIELD", target = "Lnet/minecraft/client/searchtree/SearchRegistry;CREATIVE_TAGS:Lnet/minecraft/client/searchtree/SearchRegistry$Key;"))
    private SearchRegistry.Key<ItemStack> kilt$useTagSearchKey(Operation<SearchRegistry.Key<ItemStack>> original) {
        if (this.kilt$tagSearchKey == null)
            return original.call();

        return this.kilt$tagSearchKey;
    }

    @Override
    public void kilt$setForgeGui(ForgeGui gui) {
        this.kilt$forgeGui = gui;
    }
}