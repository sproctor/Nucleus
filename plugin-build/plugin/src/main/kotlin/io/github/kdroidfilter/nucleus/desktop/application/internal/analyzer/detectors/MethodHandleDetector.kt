package io.github.kdroidfilter.nucleus.desktop.application.internal.analyzer.detectors

import io.github.kdroidfilter.nucleus.desktop.application.internal.analyzer.MethodSignature
import io.github.kdroidfilter.nucleus.desktop.application.internal.analyzer.ReflectionEntry
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * Detects `MethodHandles.Lookup.findVirtual/findStatic/findGetter/findSetter` calls
 * with string literal method/field names and produces reflection entries.
 */
internal object MethodHandleDetector {

    private val FIND_METHOD_NAMES = setOf(
        "findVirtual",
        "findStatic",
        "findSpecial",
    )

    private val FIND_FIELD_NAMES = setOf(
        "findGetter",
        "findSetter",
        "findStaticGetter",
        "findStaticSetter",
    )

    fun detect(classBytes: ByteArray): Set<ReflectionEntry> {
        val entries = mutableSetOf<ReflectionEntry>()
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
                            if (value is String) {
                                lastStringConstant = value
                            }
                        }

                        override fun visitMethodInsn(
                            opcode: Int,
                            owner: String,
                            name: String,
                            descriptor: String,
                            isInterface: Boolean,
                        ) {
                            if (opcode == Opcodes.INVOKEVIRTUAL &&
                                owner == "java/lang/invoke/MethodHandles\$Lookup"
                            ) {
                                val memberName = lastStringConstant
                                if (memberName != null) {
                                    when {
                                        name in FIND_METHOD_NAMES -> {
                                            // We can't easily determine the target class from bytecode
                                            // Only record if we have a valid member name
                                            entries.add(
                                                ReflectionEntry(
                                                    type = "?",
                                                    methods = setOf(MethodSignature(memberName)),
                                                ),
                                            )
                                        }
                                        name in FIND_FIELD_NAMES -> {
                                            entries.add(
                                                ReflectionEntry(
                                                    type = "?",
                                                    fields = setOf(memberName),
                                                ),
                                            )
                                        }
                                        name == "findConstructor" -> {
                                            entries.add(
                                                ReflectionEntry(
                                                    type = "?",
                                                    methods = setOf(MethodSignature("<init>")),
                                                ),
                                            )
                                        }
                                    }
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

        // Filter out entries with unknown target class
        return entries.filter { it.type != "?" }.toSet()
    }
}
