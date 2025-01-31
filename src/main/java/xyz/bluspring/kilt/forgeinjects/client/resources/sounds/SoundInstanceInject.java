// TRACKED HASH: 0e6708ad54e01d212d8aaafd85dc2fd7b82ad930
package xyz.bluspring.kilt.forgeinjects.client.resources.sounds;

import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import org.spongepowered.asm.mixin.Mixin;

import java.util.concurrent.CompletableFuture;

@Mixin(SoundInstance.class)
public interface SoundInstanceInject {
    default CompletableFuture<AudioStream> getStream(SoundBufferLibrary soundBuffers, Sound sound, boolean looping) {
        return soundBuffers.getStream(sound.getPath(), looping);
    }
}