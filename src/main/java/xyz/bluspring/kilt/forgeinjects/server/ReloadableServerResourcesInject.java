// TRACKED HASH: d24928420f3c1ebf622411bc07206c361aa737b9
package xyz.bluspring.kilt.forgeinjects.server;

import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.commands.Commands;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.ReloadableServerResources;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.tags.TagManager;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.crafting.conditions.ConditionContext;
import net.minecraftforge.common.crafting.conditions.ICondition;
import net.minecraftforge.event.ForgeEventFactory;
import net.minecraftforge.event.TagsUpdatedEvent;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xyz.bluspring.kilt.injections.ReloadableServerResourcesInjection;

import java.util.ArrayList;
import java.util.List;

@Mixin(ReloadableServerResources.class)
public class ReloadableServerResourcesInject implements ReloadableServerResourcesInjection {
    @Shadow @Final private TagManager tagManager;
    @Unique
    private ICondition.IContext kilt$context;

    @Inject(at = @At("TAIL"), method = "<init>")
    public void kilt$addContext(RegistryAccess.Frozen registryAccess, FeatureFlagSet enabledFeatures, Commands.CommandSelection commandSelection, int functionCompilationLevel, CallbackInfo ci) {
        this.kilt$context = new ConditionContext(this.tagManager);
    }

    @NotNull
    @Override
    public ICondition.IContext getConditionContext() {
        return kilt$context;
    }

    @ModifyArg(method = "loadResources", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/packs/resources/SimpleReloadInstance;create(Lnet/minecraft/server/packs/resources/ResourceManager;Ljava/util/List;Ljava/util/concurrent/Executor;Ljava/util/concurrent/Executor;Ljava/util/concurrent/CompletableFuture;Z)Lnet/minecraft/server/packs/resources/ReloadInstance;"))
    private static List<PreparableReloadListener> kilt$addForgeResourceReloadListener(List<PreparableReloadListener> listeners, @Local ReloadableServerResources serverResources, @Local(argsOnly = true) RegistryAccess.Frozen registryAccess) {
        var list = new ArrayList<>(listeners);
        list.addAll(ForgeEventFactory.onResourceReload(serverResources, registryAccess));

        return list;
    }

    @Inject(method = "updateRegistryTags(Lnet/minecraft/core/RegistryAccess;)V", at = @At("TAIL"))
    private void kilt$callTagUpdateEvent(RegistryAccess registryAccess, CallbackInfo ci) {
        MinecraftForge.EVENT_BUS.post(new TagsUpdatedEvent(registryAccess, false, false));
    }
}