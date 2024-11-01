package net.minecraftforge.fml

import fuzs.forgeconfigapiport.api.config.v2.ModConfigEvents
import net.fabricmc.api.EnvType
import net.fabricmc.loader.api.FabricLoader
import net.fabricmc.loader.impl.FabricLoaderImpl
import net.minecraftforge.eventbus.api.Event
import net.minecraftforge.fml.event.IModBusEvent
import net.minecraftforge.fml.event.config.ModConfigEvent
import net.minecraftforge.forgespi.language.IModInfo
import net.minecraftforge.forgespi.locating.ForgeFeature
import xyz.bluspring.kilt.Kilt
import xyz.bluspring.kilt.loader.mod.ForgeMod
import xyz.bluspring.kilt.util.DeltaTimeProfiler
import java.util.concurrent.Executor
import java.util.function.BiConsumer
import java.util.function.Function

class ModLoader {
    val warnings = mutableListOf<ModLoadingWarning>()

    fun addWarning(warning: ModLoadingWarning) {
        warnings.add(warning)
    }

    fun <T> postEvent(e: T) where T : Event, T : IModBusEvent {
        Kilt.loader.mods.forEach {
            it.eventBus.post(e)
        }
    }

    fun <T> runEventGenerator(generator: java.util.function.Function<ModContainer, T>) where T : Event, T : IModBusEvent {
        Kilt.loader.mods.forEach {
            it.eventBus.post(generator.apply(it.container))
        }
    }

    fun <T> postEventWithWrapInModOrder(e: T, pre: BiConsumer<ModContainer, T>, post: BiConsumer<ModContainer, T>) where T : Event, T : IModBusEvent {
        Kilt.loader.mods.forEach {
            pre.accept(it.container, e)
            it.eventBus.post(e)
            post.accept(it.container, e)
        }
    }

    fun <T> kiltPostEventWrappingMods(e: T) where T : Event, T : IModBusEvent {
        postEventWithWrapInModOrder(e, { pre, _ ->
            ModLoadingContext.kiltActiveModId = pre.modId
        }, { _, _ ->
            ModLoadingContext.kiltActiveModId = null
        })
    }

    fun <T> kiltPostEventWrappingModsBuildEvent(e: Function<ForgeMod, T>) where T : Event, T : IModBusEvent {
        Kilt.loader.mods.forEach {
            ModLoadingContext.kiltActiveModId = it.modId
            it.eventBus.post(e.apply(it))
            ModLoadingContext.kiltActiveModId = null
        }
    }

    fun <T> postEventWrapContainerInModOrder(e: T) where T : Event, T : IModBusEvent {
        kiltPostEventWrappingMods(e)
    }

    fun gatherAndInitializeMods(syncExecutor: ModWorkManager.DrivenExecutor, parallelExecutor: Executor, periodicTask: Runnable) {
        ForgeFeature.registerFeature("javaVersion", ForgeFeature.VersionFeatureTest.forVersionString(IModInfo.DependencySide.BOTH, System.getProperty("java.version")))
        ForgeFeature.registerFeature("openGLVersion", ForgeFeature.VersionFeatureTest.forVersionString(IModInfo.DependencySide.CLIENT, "3.2 Core")) // TODO: set this to the actual ver

        // TODO: test mod bounds

        Kilt.loader.loadMods()
        Kilt.load(FabricLoader.getInstance().environmentType == EnvType.SERVER)

        for (mod in Kilt.loader.mods) {
            ModConfigEvents.loading(mod.modId).register {
                val prevId = ModLoadingContext.kiltActiveModId
                ModLoadingContext.kiltActiveModId = mod.modId
                mod.eventBus.post(ModConfigEvent.Loading(it))
                ModLoadingContext.kiltActiveModId = prevId
            }

            ModConfigEvents.reloading(mod.modId).register {
                val prevId = ModLoadingContext.kiltActiveModId
                ModLoadingContext.kiltActiveModId = mod.modId
                mod.eventBus.post(ModConfigEvent.Reloading(it))
                ModLoadingContext.kiltActiveModId = prevId
            }

            ModConfigEvents.unloading(mod.modId).register {
                val prevId = ModLoadingContext.kiltActiveModId
                ModLoadingContext.kiltActiveModId = mod.modId
                mod.eventBus.post(ModConfigEvent.Unloading(it))
                ModLoadingContext.kiltActiveModId = prevId
            }
        }

        Kilt.loader.runPhaseExecutors(ModLoadingPhase.GATHER)
    }

    fun loadMods(syncExecutor: ModWorkManager.DrivenExecutor, parallelExecutor: Executor, periodicTask: Runnable) {
        try {
            Kilt.loader.runPhaseExecutors(ModLoadingPhase.LOAD)
        } catch (e: Exception) {
            DeltaTimeProfiler.popAll()
            throw e
        }
    }

    fun finishMods(syncExecutor: ModWorkManager.DrivenExecutor, parallelExecutor: Executor, periodicTask: Runnable) {
        Kilt.loader.runPhaseExecutors(ModLoadingPhase.COMPLETE)
    }

    companion object {
        private lateinit var instance: ModLoader

        @JvmStatic
        fun get(): ModLoader {
            return if (!this::instance.isInitialized)
                ModLoader().apply {
                    instance = this
                }
            else instance
        }

        @JvmStatic
        fun isLoadingStateValid(): Boolean {
            return FabricLoaderImpl.INSTANCE.gameProvider.isEnabled
        }
    }
}