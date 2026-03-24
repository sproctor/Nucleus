package io.github.kdroidfilter.nucleus.desktop.application.internal.analyzer.detectors

import io.github.kdroidfilter.nucleus.desktop.application.internal.analyzer.JniEntry
import io.github.kdroidfilter.nucleus.desktop.application.internal.analyzer.MethodSignature
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type

/**
 * Detects native methods (ACC_NATIVE flag) in class files and produces JNI entries.
 *
 * Also extracts types referenced in native method signatures (parameters AND return types)
 * since GraalVM JNI configs need all types accessible from native code.
 */
internal object NativeMethodDetector {

    data class NativeMethodResult(
        val jniEntries: Set<JniEntry>,
        val referencedTypes: Set<String>,
    )

    fun detect(classBytes: ByteArray): Set<JniEntry> =
        detectWithReferences(classBytes).jniEntries

    fun detectWithReferences(classBytes: ByteArray): NativeMethodResult {
        val entries = mutableMapOf<String, MutableSet<MethodSignature>>()
        val referencedTypes = mutableSetOf<String>()
        val reader = ClassReader(classBytes)
        reader.accept(
            object : ClassVisitor(Opcodes.ASM9) {
                private lateinit var className: String

                override fun visit(
                    version: Int,
                    access: Int,
                    name: String,
                    signature: String?,
                    superName: String?,
                    interfaces: Array<out String>?,
                ) {
                    className = name.replace('/', '.')
                }

                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor? {
                    if (access and Opcodes.ACC_NATIVE != 0) {
                        val argTypes = Type.getArgumentTypes(descriptor)
                        val retType = Type.getReturnType(descriptor)
                        val paramTypes = argTypes.map { asmTypeToJavaName(it) }

                        entries
                            .getOrPut(className) { mutableSetOf() }
                            .add(MethodSignature(name, paramTypes))

                        // Extract all object types from the signature — these are JNI-accessible
                        for (t in argTypes) {
                            collectObjectTypes(t, referencedTypes)
                        }
                        collectObjectTypes(retType, referencedTypes)
                    }
                    return null
                }
            },
            ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG,
        )

        val jniEntries = entries.map { (type, methods) -> JniEntry(type = type, methods = methods) }.toSet()
        return NativeMethodResult(jniEntries, referencedTypes)
    }

    /**
     * Recursively collects all object types from an ASM Type (handles arrays).
     */
    private fun collectObjectTypes(type: Type, into: MutableSet<String>) {
        when (type.sort) {
            Type.OBJECT -> into.add(type.className)
            Type.ARRAY -> collectObjectTypes(type.elementType, into)
            // Primitive types are not needed
        }
    }
}

/**
 * Converts an ASM Type to a Java source-level type name as used in GraalVM configs.
 */
internal fun asmTypeToJavaName(type: Type): String =
    when (type.sort) {
        Type.BOOLEAN -> "boolean"
        Type.BYTE -> "byte"
        Type.CHAR -> "char"
        Type.SHORT -> "short"
        Type.INT -> "int"
        Type.LONG -> "long"
        Type.FLOAT -> "float"
        Type.DOUBLE -> "double"
        Type.VOID -> "void"
        Type.ARRAY -> {
            val elementType = asmTypeToJavaName(type.elementType)
            elementType + "[]".repeat(type.dimensions)
        }
        Type.OBJECT -> type.className
        else -> type.className
    }
