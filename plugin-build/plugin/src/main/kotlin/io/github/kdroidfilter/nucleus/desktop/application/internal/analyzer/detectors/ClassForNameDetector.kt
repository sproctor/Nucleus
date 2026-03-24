package io.github.kdroidfilter.nucleus.desktop.application.internal.analyzer.detectors

import io.github.kdroidfilter.nucleus.desktop.application.internal.analyzer.ReflectionEntry
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * Detects `Class.forName("literal")` calls and produces reflection entries.
 *
 * Uses a local variable tracker to follow string constants through ASTORE/ALOAD
 * pairs that typically appear between LDC and INVOKESTATIC in compiled bytecode.
 * Also collects all string constants in the method that look like class names.
 */
internal object ClassForNameDetector {

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
                        // Track string constants in local variable slots
                        private val localStrings = mutableMapOf<Int, String>()
                        // The most recent string constant on the stack
                        private var stackString: String? = null
                        // All string constants seen that look like class names, for forName calls
                        // that use variables loaded from elsewhere
                        private var hasForNameCall = false
                        private val candidateClassNames = mutableSetOf<String>()

                        override fun visitLdcInsn(value: Any?) {
                            stackString = value as? String
                            val str = value as? String
                            if (str != null && isValidClassName(str)) {
                                candidateClassNames.add(str)
                            }
                        }

                        override fun visitVarInsn(opcode: Int, varIndex: Int) {
                            when (opcode) {
                                Opcodes.ASTORE -> {
                                    // Store the current stack string into the local variable slot
                                    val str = stackString
                                    if (str != null) {
                                        localStrings[varIndex] = str
                                    }
                                    stackString = null
                                }
                                Opcodes.ALOAD -> {
                                    // Load a string from a local variable back to the stack
                                    stackString = localStrings[varIndex]
                                }
                                else -> stackString = null
                            }
                        }

                        override fun visitMethodInsn(
                            opcode: Int,
                            owner: String,
                            name: String,
                            descriptor: String,
                            isInterface: Boolean,
                        ) {
                            if (opcode == Opcodes.INVOKESTATIC &&
                                owner == "java/lang/Class" &&
                                name == "forName"
                            ) {
                                hasForNameCall = true
                                val className = stackString
                                if (className != null && isValidClassName(className)) {
                                    entries.add(ReflectionEntry(type = className))
                                }
                            }
                            stackString = null
                        }

                        override fun visitInsn(opcode: Int) {
                            // DUP preserves stack state, others clear it
                            if (opcode != Opcodes.DUP && opcode != Opcodes.DUP2) {
                                stackString = null
                            }
                        }

                        override fun visitFieldInsn(
                            opcode: Int,
                            owner: String,
                            name: String,
                            descriptor: String,
                        ) {
                            stackString = null
                        }

                        override fun visitIntInsn(opcode: Int, operand: Int) {
                            stackString = null
                        }

                        override fun visitTypeInsn(opcode: Int, type: String) {
                            stackString = null
                        }

                        override fun visitJumpInsn(opcode: Int, label: org.objectweb.asm.Label) {
                            // Don't clear stack string on jumps — conditional forName patterns
                        }

                        override fun visitEnd() {
                            // If this method calls Class.forName, add all candidate class name
                            // literals as reflection entries (covers cases where the string
                            // is built dynamically but we can still see the literals)
                            if (hasForNameCall) {
                                for (name in candidateClassNames) {
                                    entries.add(ReflectionEntry(type = name))
                                }
                            }
                        }
                    }
            },
            0,
        )
        return entries
    }
}

/**
 * Basic validation that a string looks like a fully qualified class name.
 */
internal fun isValidClassName(name: String): Boolean {
    if (name.isBlank() || name.length < 3) return false
    if (name.contains(' ') || name.contains('\t') || name.contains('\n')) return false
    if (!name.contains('.')) return false
    // Allow array types like "[Ljava.lang.String;"
    val cleaned = name.removePrefix("[").removePrefix("L").removeSuffix(";")
    return cleaned.split('.').all { segment ->
        segment.isNotEmpty() && (segment[0].isJavaIdentifierStart() || segment[0] == '$')
    }
}
