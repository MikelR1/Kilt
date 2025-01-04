package xyz.bluspring.kilt.loader

object KiltFlags {
    // Mainly for debugging, so already-remapped Forge mods will be remapped again.
    val FORCE_REMAPPING = "kilt.forceRemap".checkPropertyBoolean()

    // Mainly for debugging, used to test unobfuscated mods and ensure that Kilt is running as intended.
    val DISABLE_REMAPPING = "kilt.noRemap".checkPropertyBoolean()

    // Mainly for debugging, to make sure all Forge mods remap correctly in production environments
    // without needing to actually launch a production environment.
    val FORCE_PRODUCTION_REMAPPING = "kilt.forceProductionRemap".checkPropertyBoolean()

    // Enables coremods in all loaded Forge mods. Currently experimental, so its usage is currently not recommended.
    val ENABLE_COREMODS = "kilt.enableCoreMods".checkPropertyBoolean()

    // Mainly for debugging, enables profiling if the DeltaTimeProfiler#dumpTree method is called.
    val ENABLE_PROFILING = "kilt.enableProfiling".checkPropertyBoolean()

    // Mainly for debugging, enables logging access transformer info under the INFO level.
    // By default, AT info is logged under the DEBUG level, so it may still be found there.
    val ENABLE_ACCESS_TRANSFORMER_DEBUG = "kilt.printATDebug".checkPropertyBoolean()

    private fun String.checkPropertyBoolean(): Boolean {
        return System.getProperty(this)?.lowercase() == "true"
    }
}