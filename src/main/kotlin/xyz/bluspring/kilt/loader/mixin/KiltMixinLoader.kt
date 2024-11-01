package xyz.bluspring.kilt.loader.mixin

import net.fabricmc.loader.impl.ModContainerImpl
import net.fabricmc.loader.impl.launch.FabricLauncherBase
import org.slf4j.LoggerFactory
import org.spongepowered.asm.mixin.FabricUtil
import org.spongepowered.asm.mixin.Mixins
import xyz.bluspring.kilt.Kilt
import xyz.bluspring.kilt.loader.mod.ForgeMod
import xyz.bluspring.kilt.util.DeltaTimeProfiler
import kotlin.io.path.toPath

object KiltMixinLoader {
    private val logger = LoggerFactory.getLogger("Kilt Mixin Loader")
    val forgeMixinPackages = mutableListOf<String>()

    fun init(mods: List<ForgeMod>) {
        DeltaTimeProfiler.push("loadForgeMixin")

        val configToModMap = mutableMapOf<String, ModContainerImpl>()

        mods.forEach { mod ->
            if (mod.manifest == null) {
                Kilt.loader.addModToFabric(mod)
                FabricLauncherBase.getLauncher().addToClassPath(mod.remappedModFile.toURI().toPath())
                return@forEach
            }

            try {
                Kilt.loader.addModToFabric(mod)
                FabricLauncherBase.getLauncher().addToClassPath(mod.remappedModFile.toURI().toPath())

                val configs = mod.manifest!!.mainAttributes.getValue("MixinConfigs") ?: return@forEach
                configs.split(",").forEach {
                    configToModMap[it] = mod.container.fabricModContainer

                    Mixins.addConfiguration(it)
                }
            } catch (e: Exception) {
                logger.error("Failed to load mixins for ${mod.modId}")
                e.printStackTrace()
            }
        }

        Mixins.getConfigs().forEach { rawConfig ->
            val mod = configToModMap[rawConfig.name] ?: return@forEach

            val config = rawConfig.config
            config.decorate(FabricUtil.KEY_MOD_ID, mod.metadata.id)
            config.decorate(FabricUtil.KEY_COMPATIBILITY, FabricUtil.COMPATIBILITY_LATEST)

            forgeMixinPackages.add(config.mixinPackage)
        }

        DeltaTimeProfiler.pop()
    }
}