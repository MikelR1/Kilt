package xyz.bluspring.kilt.loader.remap

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import net.fabricmc.loader.api.FabricLoader
import net.fabricmc.loader.impl.game.GameProviderHelper
import net.fabricmc.loader.impl.launch.FabricLauncherBase
import net.fabricmc.loader.impl.util.SystemProperties
import net.fabricmc.mapping.tree.TinyMappingFactory
import net.minecraftforge.fart.api.ClassProvider
import net.minecraftforge.fart.internal.EnhancedClassRemapper
import net.minecraftforge.fart.internal.EnhancedRemapper
import net.minecraftforge.fart.internal.RenamingTransformer
import net.minecraftforge.srgutils.IMappingFile
import org.apache.commons.codec.digest.DigestUtils
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.slf4j.LoggerFactory
import xyz.bluspring.kilt.Kilt
import xyz.bluspring.kilt.loader.KiltLoader
import xyz.bluspring.kilt.loader.mod.ForgeMod
import xyz.bluspring.kilt.loader.remap.fixers.*
import xyz.bluspring.kilt.util.KiltHelper
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.function.Consumer
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import java.util.stream.Collectors
import kotlin.io.path.absolutePathString
import kotlin.io.path.toPath


object KiltRemapper {
    // Keeps track of the remapper changes, so every time I update the remapper,
    // it remaps all the mods following the remapper changes.
    // this can update by like 12 versions in 1 update, so don't worry too much about it.
    const val REMAPPER_VERSION = 137

    const val MC_MAPPED_JAR_VERSION = 2

    val logConsumer = Consumer<String> {
        logger.debug(it)
    }

    private val logger = LoggerFactory.getLogger("Kilt Remapper")

    private val launcher = FabricLauncherBase.getLauncher()
    internal val useNamed = launcher.targetNamespace != "intermediary"

    // Mainly for debugging, to make sure all Forge mods remap correctly in production environments
    // without needing to actually launch a production environment.
    internal val forceProductionRemap = System.getProperty("kilt.forceProductionRemap")?.lowercase() == "true"

    // This is created automatically using https://github.com/BluSpring/srg2intermediary
    // srg -> intermediary
    val srgIntermediaryMapping = IMappingFile.load(this::class.java.getResourceAsStream("/srg_intermediary.tiny")!!)
        .run {
            if (!forceProductionRemap)
                this.rename(DevMappingRenamer())
            else
                this
        }
    val intermediarySrgMapping = srgIntermediaryMapping.reverse()

    // Some workaround mappings, to remap some names to Kilt equivalents.
    // This fixes some compatibility issues.
    private val kiltWorkaroundTree = TinyMappingFactory.load(this::class.java.getResourceAsStream("/kilt_workaround_mappings.tiny")!!.bufferedReader())

    // Mainly for debugging, so already-remapped Forge mods will be remapped again.
    private val forceRemap = System.getProperty("kilt.forceRemap")?.lowercase() == "true"

    // Mainly for debugging, used to test unobfuscated mods and ensure that Kilt is running as intended.
    private val disableRemaps = System.getProperty("kilt.noRemap")?.lowercase() == "true"

    private val mappingResolver = if (forceProductionRemap) NoopMappingResolver() else FabricLoader.getInstance().mappingResolver
    private val namespace: String = if (useNamed) launcher.targetNamespace else "intermediary"

    private lateinit var remappedModsDir: File

    // Just stores literally every class node that's been processed by the remapper.
    private val classNodeList = mutableSetOf<ClassNode>()

    // SRG name -> (parent class name, intermediary/mapped name)
    val srgMappedFields = srgIntermediaryMapping.classes.flatMap {
        it.fields.map { f -> f.original to
            if (!forceProductionRemap)
                mappingResolver.mapFieldName("intermediary", it.mapped.replace("/", "."), f.mapped, f.mappedDescriptor)
            else
                f.mapped
        }
    }.associateBy { it.first }

    // SRG name -> (parent class name, intermediary/mapped name)
    val srgMappedMethods = mutableMapOf<String, MutableMap<String, String>>()

    init {
        srgIntermediaryMapping.classes.forEach {
            it.methods.forEach m@{ f ->
                // otherwise FunctionalInterface methods don't get remapped properly???
                if (!f.mapped.startsWith("method_") && !FabricLoader.getInstance().isDevelopmentEnvironment)
                    return@m

                val map = srgMappedMethods.computeIfAbsent(f.original) { mutableMapOf() }
                val mapped = if (!forceProductionRemap)
                    (mappingResolver.mapMethodName("intermediary", it.mapped.replace("/", "."), f.mapped, f.mappedDescriptor))
                else
                    f.mapped

                map[f.parent.original] = mapped
            }
        }
    }

    fun remapMods(modLoadingQueue: ConcurrentLinkedQueue<ForgeMod>, remappedModsDir: File): List<Exception> {
        if (disableRemaps) {
            logger.warn("Mod remapping has been disabled! Mods built normally using ForgeGradle will not function with this enabled.")
            logger.warn("Only have this enabled if you know what you're doing!")

            modLoadingQueue.forEach {
                if (it.modFile != null)
                    it.remappedModFile = it.modFile
            }

            return listOf()
        }

        this.remappedModsDir = remappedModsDir

        if (forceRemap)
            logger.warn("Forced remaps enabled! All Forge mods will be remapped.")

        srgGamePath = remapMinecraft()

        val exceptions = mutableListOf<Exception>()

        val modRemapQueue = ArrayList(modLoadingQueue)

        // Sort according to what dependencies are required and should be loaded first.
        // If a mod fails to remap because a dependency isn't listed, welp,
        // that's their problem now i guess.
        modRemapQueue.sortWith { a, b ->
            if (a.dependencies.any { it.modId == b.modId })
                1
            else if (b.dependencies.any { it.modId == a.modId })
                -1
            else 0
        }

        logger.info("Remapping Forge mods...")

        // Trying to see if we can multi-thread remapping, so it can be much faster.
        runBlocking {
            val modRemappingCoroutines = mutableMapOf<ForgeMod, Deferred<ForgeMod>>()

            modRemapQueue.forEach { mod ->
                if (mod.modFile == null)
                    return@forEach

                if (mod.isRemapped())
                    return@forEach

                val jar = JarFile(mod.modFile)
                for (entry in jar.entries()) {
                    if (entry.name.endsWith(".class")) {
                        val classReader = ClassReader(jar.getInputStream(entry))

                        // we need the info for this for the class writer
                        val classNode = ClassNode(Opcodes.ASM9)
                        classReader.accept(classNode, 0)

                        classNodeList.add(classNode)
                    }
                }

                modRemappingCoroutines[mod] = (async {
                    if (mod.isRemapped())
                        return@async mod

                    try {
                        val startTime = System.currentTimeMillis()
                        logger.info("Remapping ${mod.displayName} (${mod.modId})")

                        exceptions.addAll(remapMod(mod.modFile, mod,
                            modRemapQueue
                        ))

                        logger.info("Remapped ${mod.displayName} (${mod.modId}) [took ${System.currentTimeMillis() - startTime}ms]")
                    } catch (e: Exception) {
                        exceptions.add(e)
                        e.printStackTrace()
                    }

                    mod
                })
            }

            awaitAll(*modRemappingCoroutines.values.toTypedArray())
        }

        logger.info("Finished remapping mods!")

        if (exceptions.isNotEmpty()) {
            logger.error("Ran into some errors, we're not going to continue with the repairing process.")
            return exceptions
        }

        return exceptions
    }

    private val nameMappingCache = mutableMapOf<String, String>()

    private fun remapMod(file: File, mod: ForgeMod, forgeModsList: List<ForgeMod>): List<Exception> {
        val exceptions = mutableListOf<Exception>()

        val hash = DigestUtils.md5Hex(file.inputStream())
        val modifiedJarFile = File(remappedModsDir, "${mod.modId}_${REMAPPER_VERSION}_$hash.jar")

        if (modifiedJarFile.exists() && !forceRemap) {
            mod.remappedModFile = modifiedJarFile
            return exceptions
        }

        val jar = JarFile(file)
        val output = modifiedJarFile.outputStream()
        val jarOutput = JarOutputStream(output)

        val mixinClasses = mutableListOf<String>()
        val refmaps = mutableListOf<String>()

        // Use the regular mod file
        val classProvider = ClassProvider.builder().apply {
            this.addLibrary(srgGamePath)

            // List down Forge paths
            for (path in KiltHelper.getKiltPaths()) {
                this.addLibrary(path)
            }

            // Add all Fabric mods
            for (container in FabricLoader.getInstance().allMods) {
                for (rootPath in container.rootPaths) {
                    this.addLibrary(rootPath)
                }
            }

            // add mapped path too
            for (path in getGameClassPath()) {
                this.addLibrary(path)
            }

            // Add all Forge mods to the library path, because dependencies don't have to be specified
            // in order to use mods lmao
            for (forgeMod in forgeModsList) {
                this.addLibrary(forgeMod.modFile?.toPath())
            }

            this.addLibrary(mod.modFile?.toPath())
        }.build()

        val remapper = KiltEnhancedRemapper(classProvider, srgIntermediaryMapping, logConsumer)
        val entriesToMap = mutableListOf<Pair<JarEntry, ClassNode>>()

        // JAR validation information stripping.
        // If we can find out how to use this to our advantage prior to remapping,
        // we may still be able to utilize this information safely.
        val manifestEntry = jar.getJarEntry("META-INF/MANIFEST.MF")
        if (manifestEntry != null) {
            // Modify the manifest to avoid hash checking, because if
            // hash checking occurs, the JAR will fail to load entirely.
            val manifest = Manifest(jar.getInputStream(manifestEntry))

            val hashes = mutableListOf<String>()
            manifest.entries.forEach { (name, attr) ->
                if (attr.entries.any { it.toString().startsWith("SHA-256-Digest") || it.toString().startsWith("SHA-1-Digest") }) {
                    hashes.add(name)
                }
            }

            val mixinConfigs = manifest.mainAttributes.getValue("MixinConfigs")?.split(",") ?: listOf()

            // Read mixin configs and add them to the list of mixins to fix
            for (mixinConfig in mixinConfigs) {
                val jsonEntry = jar.getJarEntry(mixinConfig) ?: continue
                val data = jar.getInputStream(jsonEntry).bufferedReader()

                val json = JsonParser.parseReader(data).asJsonObject

                if (!json.has("package"))
                    continue

                val mixinPackage = json.get("package").asString

                if (json.has("mixins")) {
                    for (element in json.getAsJsonArray("mixins")) {
                        val className = element.asString
                        mixinClasses.add("$mixinPackage.$className")
                    }
                }

                if (json.has("client")) {
                    for (element in json.getAsJsonArray("client")) {
                        val className = element.asString
                        mixinClasses.add("$mixinPackage.$className")
                    }
                }

                if (json.has("server")) {
                    for (element in json.getAsJsonArray("server")) {
                        val className = element.asString
                        mixinClasses.add("$mixinPackage.$className")
                    }
                }

                if (json.has("refmap")) {
                    refmaps.add(json.get("refmap").asString)
                }
            }

            hashes.forEach {
                manifest.entries.remove(it)
            }

            val outputStream = ByteArrayOutputStream()
            manifest.write(outputStream)

            jarOutput.putNextEntry(manifestEntry)
            jarOutput.write(outputStream.toByteArray())
            jarOutput.closeEntry()
        }

        for (entry in jar.entries()) {
            if (!entry.name.endsWith(".class")) {
                if (entry.name.lowercase() == "meta-inf/manifest.mf") {
                    continue
                } else if (entry.name.lowercase().endsWith(".rsa") || entry.name.lowercase().endsWith(".sf")) {
                    // ignore JAR signatures.
                    // Due to Kilt remapping the JAR files, we are unable to use this to our advantage.
                    // TODO: Maybe run a verification step in the mod loading process prior to remapping?
                    logger.warn("Detected that ${mod.displayName} (${mod.modId}) is a signed JAR! This is a security measure by mod developers to verify that the distributed mod JARs are theirs, however Kilt is unable to use this verification step properly, and is thus stripping this information.")

                    continue
                }

                // Mixin remapping
                if (refmaps.any { entry.name.lowercase() == it.lowercase() }) {
                    val refmapData = JsonParser.parseString(String(jar.getInputStream(entry).readAllBytes())).asJsonObject

                    val refmapMappings = refmapData.getAsJsonObject("mappings")
                    val newMappings = JsonObject()

                    refmapMappings.keySet().forEach { className ->
                        val mapped = refmapMappings.getAsJsonObject(className)
                        val properMapped = JsonObject()

                        mapped.entrySet().forEach { (name, element) ->
                            val srgMappedString = element.asString
                            val srgClass = if (srgMappedString.startsWith("L"))
                                srgMappedString.replaceAfter(";", "")
                            else
                                ""
                            val intermediaryClass = if (srgClass.isNotBlank()) remapDescriptor(srgClass, toIntermediary = forceProductionRemap) else ""

                            if (srgMappedString.contains(":")) {
                                // field

                                val split = srgMappedString.split(":")
                                val srgField = split[0].removePrefix(srgClass)
                                val srgDesc = split[1]

                                val intermediaryDesc = remapDescriptor(srgDesc, toIntermediary = forceProductionRemap)

                                val intermediaryField = "".run {
                                    if (srgClass.isNotBlank()) {
                                        if (nameMappingCache.contains(srgField)) {
                                            nameMappingCache[srgField]!!
                                        } else {
                                            // Remap SRG to Intermediary, then to whatever the current FabricMC environment
                                            // is using.
                                            mappingResolver.mapFieldName(
                                                "intermediary",
                                                intermediaryClass
                                                    .replace("/", ".")
                                                    .removePrefix("L").removeSuffix(";"),
                                                (remapper.mapFieldName(
                                                    srgClass.removePrefix("L").removeSuffix(";"),
                                                    srgField,
                                                    srgDesc
                                                ).run a@{
                                                    if (this == srgField) {
                                                        val possibleClass = srgIntermediaryMapping.classes.firstOrNull { it.getField(srgField) != null } ?: return@run srgField

                                                        mappingResolver.mapFieldName(
                                                            "intermediary",
                                                            possibleClass.mapped.replace("/", "."),
                                                            possibleClass.remapField(srgField),
                                                            intermediaryDesc
                                                        )
                                                    } else this
                                                }).apply {
                                                    // Cache the field we found, so we don't have to go through this again
                                                    nameMappingCache[srgField] = this
                                                } ?: srgField,
                                                intermediaryDesc
                                            )
                                        }
                                    } else {
                                        // If the refmap is missing an owner class, try to figure it out
                                        if (!srgField.startsWith("f_") || !srgField.endsWith("_"))
                                            srgField // short-circuit if it doesn't look like a field
                                        else {
                                            if (nameMappingCache.contains(srgField))
                                                nameMappingCache[srgField]!!
                                            else {
                                                val possibleClass = srgIntermediaryMapping.classes.firstOrNull { it.getField(srgField) != null } ?: return@run srgField

                                                mappingResolver.mapFieldName(
                                                    "intermediary",
                                                    possibleClass.mapped.replace("/", "."),
                                                    possibleClass.remapField(srgField),
                                                    intermediaryDesc
                                                ).apply {
                                                    // Cache the field we found, so we don't have to go through this again
                                                    nameMappingCache[srgField] = this
                                                }
                                            }
                                        }
                                    }
                                }

                                properMapped.addProperty(name, "$intermediaryClass$intermediaryField:$intermediaryDesc")
                            } else {
                                // method

                                val srgMethod = srgMappedString.replaceAfter("(", "").removeSuffix("(").removePrefix(srgClass)
                                val srgDesc = srgMappedString.replaceBefore("(", "")

                                val intermediaryDesc = remapDescriptor(srgDesc, toIntermediary = forceProductionRemap)
                                val intermediaryMethod = "".run {
                                    if (srgClass.isNotBlank()) {
                                        if (nameMappingCache.contains(srgMethod)) {
                                            nameMappingCache[srgMethod]!!
                                        } else {
                                            mappingResolver.mapMethodName(
                                                "intermediary",
                                                intermediaryClass
                                                    .replace("/", ".")
                                                    .removePrefix("L").removeSuffix(";"),
                                                (remapper.mapMethodName(
                                                    srgClass
                                                        .removePrefix("L").removeSuffix(";"),
                                                    srgMethod, srgDesc
                                                ).run a@{
                                                    if (this == srgMethod) {
                                                        val possibleClass = srgIntermediaryMapping.classes.firstOrNull { it.getMethod(srgMethod, srgDesc) != null } ?: return@a srgMethod

                                                        mappingResolver.mapMethodName(
                                                            "intermediary",
                                                            possibleClass.mapped.replace("/", "."),
                                                            possibleClass.remapMethod(srgMethod, srgDesc),
                                                            intermediaryDesc
                                                        )
                                                    } else this
                                                }).apply {
                                                    nameMappingCache[srgMethod] = this
                                                } ?: srgMethod,
                                                intermediaryDesc
                                            )
                                        }
                                    } else {
                                        // If the refmap is missing an owner class, try to figure it out
                                        // Since record classes can provide methods with f_num_, these have to be
                                        // taken into account.
                                        if (!(srgMethod.startsWith("f_") || srgMethod.startsWith("m_")) || !srgMethod.endsWith("_"))
                                            srgMethod // short-circuit if it doesn't look like a method
                                        else {
                                            if (nameMappingCache.contains(srgMethod))
                                                nameMappingCache[srgMethod]!!
                                            else {
                                                val possibleClass = srgIntermediaryMapping.classes.firstOrNull { it.getMethod(srgMethod, srgDesc) != null } ?: return@run srgMethod

                                                mappingResolver.mapMethodName(
                                                    "intermediary",
                                                    possibleClass.mapped.replace("/", "."),
                                                    possibleClass.remapMethod(srgMethod, srgDesc),
                                                    intermediaryDesc
                                                ).apply {
                                                    // Cache the method we found, so we don't have to go through this again
                                                    nameMappingCache[srgMethod] = this
                                                }
                                            }
                                        }
                                    }
                                }

                                properMapped.addProperty(name, "$intermediaryClass$intermediaryMethod$intermediaryDesc")
                            }
                        }

                        newMappings.add(className, properMapped)
                    }

                    refmapData.add("mappings", newMappings)
                    refmapData.add("data", JsonObject().apply {
                        this.add("named:intermediary", newMappings)
                    })

                    jarOutput.putNextEntry(entry)
                    jarOutput.write(Kilt.gson.toJson(refmapData).toByteArray())
                    jarOutput.closeEntry()

                    continue
                }

                jarOutput.putNextEntry(entry)
                jarOutput.write(jar.getInputStream(entry).readAllBytes())
                jarOutput.closeEntry()
                continue
            }

            val classReader = ClassReader(jar.getInputStream(entry))

            // we need the info for this for the class writer
            val classNode = ClassNode(Opcodes.ASM9)
            classReader.accept(classNode, 0)

            entriesToMap.add(JarEntry(entry.name) to classNode)
        }

        val classes = entriesToMap.map { it.second }.intersect(KiltHelper.getForgeClassNodes().toSet()).intersect(classNodeList).toList()

        for ((entry, classNode) in entriesToMap) {
            try {
                val classWriter = ClassWriter(0)

                val visitor = EnhancedClassRemapper(classWriter, remapper, RenamingTransformer(remapper, false))
                classNode.accept(visitor)

                val classReader2 = ClassReader(classWriter.toByteArray())
                val classNode2 = ClassNode(Opcodes.ASM9)
                classReader2.accept(classNode2, 0)

                // only do this on mixin classes, please
                if (mixinClasses.contains(classNode2.name.replace("/", "."))) {
                    MixinShadowRemapper.remapClass(classNode2)
                }

                EventClassVisibilityFixer.fixClass(classNode2)
                EventEmptyInitializerFixer.fixClass(classNode2, classes)
                ObjectHolderDefinalizer.processClass(classNode2)
                WorkaroundFixer.fixClass(classNode2)
                ConflictingStaticMethodFixer.fixClass(classNode2)
                MixinSpecialAnnotationRemapper.remapClass(classNode2)

                val classWriter2 = ClassWriter(0)
                classNode2.accept(classWriter2)

                jarOutput.putNextEntry(entry)
                jarOutput.write(classWriter2.toByteArray())
                jarOutput.closeEntry()
            } catch (e: Exception) {
                logger.error("Failed to remap class ${entry.name}!")
                e.printStackTrace()

                exceptions.add(e)
            }
        }

        jarOutput.close()
        mod.remappedModFile = modifiedJarFile

        return exceptions
    }

    fun remapClass(name: String, toIntermediary: Boolean = false, ignoreWorkaround: Boolean = false): String {
        val workaround = if (!ignoreWorkaround)
            kiltWorkaroundTree.classes.firstOrNull { it.getRawName("forge") == name }?.getRawName("kilt")
        else null
        val intermediary = srgIntermediaryMapping.remapClass(name.replace(".", "/"))
        if (toIntermediary) {
            return workaround ?: intermediary ?: name
        }

        return (workaround ?: if (intermediary != null)
            mappingResolver.mapClassName("intermediary", intermediary.replace("/", ".")) ?: name
        else name).replace(".", "/")
    }

    fun unmapClass(name: String): String {
        val intermediary = mappingResolver.unmapClassName("intermediary", name.replace("/", "."))
        return intermediarySrgMapping.remapClass(intermediary.replace(".", "/"))
    }

    val gameFile = getMCGameFile()
    lateinit var srgGamePath: Path

    private fun getDeobfJarDir(gameDir: Path, gameId: String, gameVersion: String): Path {
        return GameProviderHelper::class.java
            .getDeclaredMethod("getDeobfJarDir", Path::class.java, String::class.java, String::class.java)
            .apply {
                isAccessible = true
            }
            .invoke(null, gameDir, gameId, gameVersion) as Path
    }

    private fun getMCGameFile(): File? {
        if (!FabricLoader.getInstance().isDevelopmentEnvironment) {
            val commonJar = GameProviderHelper.getCommonGameJar()

            if (commonJar != null)
                return commonJar.toFile()

            val sidedJar = GameProviderHelper.getEnvGameJar(FabricLoader.getInstance().environmentType)

            if (sidedJar != null)
                return sidedJar.toFile()

            // this gives the obfuscated JAR, we don't want that
            //val inputGameJar = FabricLoader.getInstance().objectShare.get("fabric-loader:inputGameJar")
            //if (inputGameJar is Path)
                //return inputGameJar.toFile()

            // This is our best bet towards getting the Intermediary JAR.
            val deobfJar = getDeobfJarDir(FabricLoader.getInstance().gameDir, "minecraft", KiltLoader.MC_VERSION.friendlyString)
                .resolve("${FabricLoader.getInstance().environmentType.name.lowercase()}-${launcher.targetNamespace}.jar")
                .toFile()

            if (deobfJar.exists())
                return deobfJar
        } else {
            // TODO: is there a better way of doing this?
            val possibleMcGameJar = FabricLauncherBase.getLauncher().classPath.firstOrNull { path ->
                val str = path.absolutePathString()
                str.contains("net") && str.contains("minecraft") && str.contains("-loom.mappings.") && str.contains("minecraft-merged-")
            } ?: return null

            return possibleMcGameJar.toFile()
        }

        return null
    }

    fun getGameClassPath(): Array<out Path> {
        return if (!FabricLoader.getInstance().isDevelopmentEnvironment)
            arrayOf(
                getMCGameFile()?.toPath() ?: FabricLoader.getInstance().objectShare.get("fabric-loader:inputGameJar") as Path,
                Kilt::class.java.protectionDomain.codeSource.location.toURI().toPath()
            )
        else
            mutableListOf<Path>().apply {
                val remapClasspathFile = System.getProperty(SystemProperties.REMAP_CLASSPATH_FILE)
                    ?: throw RuntimeException("No remapClasspathFile provided")

                val content = String(Files.readAllBytes(Paths.get(remapClasspathFile)), StandardCharsets.UTF_8)

                this.addAll(Arrays.stream(content.split(File.pathSeparator.toRegex()).dropLastWhile { it.isEmpty() }
                    .toTypedArray())
                    .map { first ->
                        Paths.get(first)
                    }
                    .collect(Collectors.toList()))

                this.add(Kilt::class.java.protectionDomain.codeSource.location.toURI().toPath())
            }.toTypedArray()
    }

    private fun remapMinecraft(): Path {
        val srgFile = File(KiltLoader.kiltCacheDir, "minecraft_${KiltLoader.MC_VERSION.friendlyString}-srg_$MC_MAPPED_JAR_VERSION.jar")

        if (srgFile.exists() && !forceRemap)
            return srgFile.toPath()

        if (gameFile == null) {
            throw IllegalStateException("Minecraft JAR was not found!")
        }

        logger.info("Creating SRG-mapped Minecraft JAR for remapping Forge mods...")
        val startTime = System.currentTimeMillis()

        val classProvider = ClassProvider.builder().apply {
            this.addLibrary(gameFile.toPath())
            for (path in getGameClassPath()) {
                this.addLibrary(path)
            }
        }.build()
        val srgRemapper = EnhancedRemapper(classProvider, intermediarySrgMapping, logConsumer)

        val gameJar = JarFile(gameFile)
        srgFile.createNewFile()
        val outputJar = JarOutputStream(srgFile.outputStream())

        for (entry in gameJar.entries()) {
            if (entry.name.endsWith(".class")) {
                val classReader = ClassReader(gameJar.getInputStream(entry))

                val classNode = ClassNode(Opcodes.ASM9)
                classReader.accept(classNode, 0)

                val classWriter = ClassWriter(0)

                val visitor = EnhancedClassRemapper(classWriter, srgRemapper, RenamingTransformer(srgRemapper, false))
                classNode.accept(visitor)
                ConflictingStaticMethodFixer.fixClass(classNode)

                // We need to remap to the SRG name, otherwise the remapper completely fails in production environments.
                val srgName = intermediarySrgMapping.remapClass(entry.name.removePrefix("/").removeSuffix(".class"))

                outputJar.putNextEntry(JarEntry("$srgName.class"))
                outputJar.write(classWriter.toByteArray())
                outputJar.closeEntry()
            } else {
                outputJar.putNextEntry(entry)
                outputJar.write(gameJar.getInputStream(entry).readAllBytes())
                outputJar.closeEntry()
            }
        }

        outputJar.close()

        logger.info("Remapped Minecraft from Intermediary to SRG. (took ${System.currentTimeMillis() - startTime} ms)")

        return srgFile.toPath()
    }

    fun remapDescriptor(descriptor: String, reverse: Boolean = false, toIntermediary: Boolean = false): String {
        var formedString = ""

        var incompleteString = ""
        var isInClass = false
        descriptor.forEach {
            if (it == 'L' && !isInClass)
                isInClass = true

            if (isInClass) {
                incompleteString += it

                if (it == ';') {
                    isInClass = false

                    formedString += 'L'

                    val name = incompleteString.removePrefix("L").removeSuffix(";")
                    formedString += if (!reverse)
                        remapClass(name, toIntermediary)
                    else
                        unmapClass(name)

                    formedString += ';'

                    incompleteString = ""
                }
            } else {
                formedString += it
            }
        }

        return formedString
    }
}