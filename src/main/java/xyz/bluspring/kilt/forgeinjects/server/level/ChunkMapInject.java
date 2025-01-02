// TRACKED HASH: cebcc0747792b8bfe53d24573c9609f5c22b61d1
package xyz.bluspring.kilt.forgeinjects.server.level;

import com.llamalad7.mixinextras.expression.Definition;
import com.llamalad7.mixinextras.expression.Expression;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.EnderDragonPart;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.entity.PartEntity;
import net.minecraftforge.event.ForgeEventFactory;
import net.minecraftforge.event.level.ChunkDataEvent;
import net.minecraftforge.event.level.ChunkEvent;
import org.apache.commons.lang3.mutable.MutableObject;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChunkMap.class)
public abstract class ChunkMapInject {
    @Shadow @Final private ServerLevel level;

    @Definition(id = "entity", local = @Local(type = Entity.class, argsOnly = true))
    @Definition(id = "EnderDragonPart", type = EnderDragonPart.class)
    @Expression("entity instanceof EnderDragonPart")
    @WrapOperation(method = "addEntity", at = @At("MIXINEXTRAS:EXPRESSION"))
    private boolean kilt$checkIfEntityMultipart(Object object, Operation<Boolean> original) {
        return original.call(object) || object instanceof PartEntity<?>;
    }

    @Inject(method = "updateChunkScheduling", at = @At(value = "RETURN", ordinal = 1))
    private void kilt$fireTicketUpdatedEvent(long chunkPos, int newLevel, ChunkHolder holder, int oldLevel, CallbackInfoReturnable<ChunkHolder> cir) {
        ForgeEventFactory.fireChunkTicketLevelUpdated(this.level, chunkPos, oldLevel, newLevel, holder);
    }

    @Inject(method = "method_17227", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/LevelChunk;registerTickContainerInLevel(Lnet/minecraft/server/level/ServerLevel;)V", shift = At.Shift.AFTER))
    private void kilt$callChunkLoadEvent(ChunkHolder chunkHolder, ChunkAccess chunkAccess, CallbackInfoReturnable<ChunkAccess> cir, @Local LevelChunk levelChunk, @Local ProtoChunk protoChunk) {
        MinecraftForge.EVENT_BUS.post(new ChunkEvent.Load(levelChunk, !(protoChunk instanceof ImposterProtoChunk)));
    }

    @Inject(method = "save", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ChunkMap;write(Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/nbt/CompoundTag;)V"))
    private void kilt$callChunkSaveEvent(ChunkAccess chunk, CallbackInfoReturnable<Boolean> cir, @Local CompoundTag tag) {
        MinecraftForge.EVENT_BUS.post(new ChunkDataEvent.Save(chunk, chunk.getWorldForge() != null ? chunk.getWorldForge() : this.level, tag));
    }

    @Inject(method = "updateChunkTracking", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;untrackChunk(Lnet/minecraft/world/level/ChunkPos;)V", shift = At.Shift.AFTER))
    private void kilt$fireChunkUnwatchEvent(ServerPlayer player, ChunkPos chunkPos, MutableObject<ClientboundLevelChunkWithLightPacket> packetCache, boolean wasLoaded, boolean load, CallbackInfo ci) {
        ForgeEventFactory.fireChunkUnWatch(player, chunkPos, this.level);
    }

    @Inject(method = "playerLoadedChunk", at = @At("TAIL"))
    private void kilt$fireChunkWatchEvent(ServerPlayer player, MutableObject<ClientboundLevelChunkWithLightPacket> packetCache, LevelChunk chunk, CallbackInfo ci) {
        ForgeEventFactory.fireChunkWatch(player, chunk, this.level);
    }
}