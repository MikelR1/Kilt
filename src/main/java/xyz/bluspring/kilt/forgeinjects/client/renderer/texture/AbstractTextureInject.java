// TRACKED HASH: d6c99e89bffae0bd644b2ba83c1c0a920ac2fb71
package xyz.bluspring.kilt.forgeinjects.client.renderer.texture;

import net.minecraft.client.renderer.texture.AbstractTexture;
import org.spongepowered.asm.mixin.Mixin;
import xyz.bluspring.kilt.injections.client.renderer.texture.AbstractTextureInjection;

@Mixin(AbstractTexture.class)
public abstract class AbstractTextureInject implements AbstractTextureInjection {
    // implemented by Porting Lib
    /*@Shadow protected boolean blur;
    @Shadow protected boolean mipmap;

    @Shadow public abstract void setFilter(boolean blur, boolean mipmap);

    @Unique private boolean lastBlur;
    @Unique private boolean lastMipmap;

    public void setBlurMipmap(boolean blur, boolean mipmap) {
        this.lastBlur = this.blur;
        this.lastMipmap = this.mipmap;
        setFilter(blur, mipmap);
    }

    public void restoreLastBlurMipmap() {
        setFilter(this.lastBlur, this.lastMipmap);
    }*/
}