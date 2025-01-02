package xyz.bluspring.kilt.injections.server.level;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraftforge.entity.PartEntity;

public interface ServerLevelInjection {
    Int2ObjectMap<PartEntity<?>> kilt$getEntityParts();
}
