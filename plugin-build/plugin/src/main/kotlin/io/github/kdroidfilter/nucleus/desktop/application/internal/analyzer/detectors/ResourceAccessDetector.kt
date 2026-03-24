package io.github.kdroidfilter.nucleus.desktop.application.internal.analyzer.detectors

import io.github.kdroidfilter.nucleus.desktop.application.internal.analyzer.ResourcePattern
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * Detects `getResource("literal")` and `getResourceAsStream("literal")` calls
 * and produces resource glob entries.
 */
internal object ResourceAccessDetector {

    private val RESOURCE_METHODS = setOf("getResource", "getResourceAsStream")

    private val RESOURCE_OWNERS = setOf(
        "java/lang/Class",
        "java/lang/ClassLoader",
    )

    fun detect(classBytes: ByteArray): Set<ResourcePattern> {
        val patterns = mutableSetOf<ResourcePattern>()
        val reader = ClassReader(classBytes)
        reader.accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor =
                    object : MethodVisitor(Opcodes.ASM9) {
                        private var lastStringConstant: String? = null

                        override fun visitLdcInsn(value: Any?) {
                            lastStringConstant = value as? String
                        }

                        override fun visitMethodInsn(
                            opcode: Int,
                            owner: String,
                            name: String,
                            descriptor: String,
                            isInterface: Boolean,
                        ) {
                            if (opcode == Opcodes.INVOKEVIRTUAL &&
                                owner in RESOURCE_OWNERS &&
                                name in RESOURCE_METHODS &&
                                lastStringConstant != null
                            ) {
                                val path = normalizeResourcePath(lastStringConstant!!)
                                if (path.isNotEmpty()) {
                                    patterns.add(ResourcePattern(glob = path))
                                }
                            }
                            lastStringConstant = null
                        }

                        override fun visitInsn(opcode: Int) {
                            lastStringConstant = null
                        }

                        override fun visitVarInsn(opcode: Int, varIndex: Int) {
                            lastStringConstant = null
                        }

                        override fun visitFieldInsn(
                            opcode: Int,
                            owner: String,
                            name: String,
                            descriptor: String,
                        ) {
                            lastStringConstant = null
                        }

                        override fun visitIntInsn(opcode: Int, operand: Int) {
                            lastStringConstant = null
                        }

                        override fun visitTypeInsn(opcode: Int, type: String) {
                            lastStringConstant = null
                        }

                        override fun visitJumpInsn(opcode: Int, label: org.objectweb.asm.Label) {
                            lastStringConstant = null
                        }
                    }
            },
            0,
        )
        return patterns
    }

    /**
     * Removes leading slash from absolute resource paths.
     */
    private fun normalizeResourcePath(path: String): String =
        path.removePrefix("/")
}
