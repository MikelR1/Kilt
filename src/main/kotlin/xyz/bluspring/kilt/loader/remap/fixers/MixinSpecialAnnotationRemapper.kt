package xyz.bluspring.kilt.loader.remap.fixers

import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode
import xyz.bluspring.kilt.loader.remap.KiltRemapper

object MixinSpecialAnnotationRemapper {
    fun remapClass(classNode: ClassNode) {
        for (method in classNode.methods) {
            if (method.visibleAnnotations == null)
                continue

            val annotationsToReplace = mutableMapOf<AnnotationNode, AnnotationNode>()

            for (annotation in method.visibleAnnotations) {
                var shouldChange = false
                val values = mutableListOf<Any>()

                if (annotation.values == null)
                    continue

                for (value in annotation.values) {
                    when (value) {
                        is String -> {
                            values.add(tryRemapString(value).apply {
                                if (this != value)
                                    shouldChange = true
                            })
                        }

                        is List<*> -> {
                            val list = mutableListOf<Any?>()
                            value.forEach {
                                if (it is String)
                                    list.add(tryRemapString(it).apply {
                                        if (this != it)
                                            shouldChange = true
                                    })
                                else
                                    list.add(it)
                            }
                            values.add(list)
                        }

                        else -> {
                            values.add(value)
                        }
                    }
                }

                if (shouldChange) {
                    annotationsToReplace[annotation] = AnnotationNode(Opcodes.ASM9, annotation.desc)
                    annotationsToReplace[annotation]!!.values = values
                }
            }

            for ((old, new) in annotationsToReplace) {
                method.visibleAnnotations.remove(old)
                method.visibleAnnotations.add(new)
            }
        }
    }

    private fun tryRemapString(fullDescriptor: String): String {
        if (!fullDescriptor.contains("<") || !fullDescriptor.startsWith("L"))
            return fullDescriptor

        val originalClassName = fullDescriptor.replaceAfter(";", "")
        val originalDescriptor = fullDescriptor.replaceBefore(">", "").removePrefix(">")

        val mappedClassName = KiltRemapper.remapDescriptor(originalClassName, toIntermediary = KiltRemapper.forceProductionRemap)
        val mappedDescriptor = KiltRemapper.remapDescriptor(originalDescriptor, toIntermediary = KiltRemapper.forceProductionRemap)

        return fullDescriptor.replace(originalClassName, mappedClassName).replace(originalDescriptor, mappedDescriptor)
    }

    private fun getValues(value: Any): List<String> {
        return when (value) {
            is String -> listOf(value)
            is List<*> -> value as List<String>
            else -> listOf()
        }
    }
}