package io.github.kdroidfilter.nucleus.desktop.application.internal.analyzer

import io.github.kdroidfilter.nucleus.desktop.application.internal.analyzer.detectors.ClassForNameDetector
import io.github.kdroidfilter.nucleus.desktop.application.internal.analyzer.detectors.KotlinSerializableDetector
import io.github.kdroidfilter.nucleus.desktop.application.internal.analyzer.detectors.MethodHandleDetector
import io.github.kdroidfilter.nucleus.desktop.application.internal.analyzer.detectors.NativeMethodDetector
import io.github.kdroidfilter.nucleus.desktop.application.internal.analyzer.detectors.ProxyDetector
import io.github.kdroidfilter.nucleus.desktop.application.internal.analyzer.detectors.ReflectionApiDetector
import io.github.kdroidfilter.nucleus.desktop.application.internal.analyzer.detectors.ResourceAccessDetector
import io.github.kdroidfilter.nucleus.desktop.application.internal.analyzer.detectors.ResourceBundleDetector
import io.github.kdroidfilter.nucleus.desktop.application.internal.analyzer.detectors.ServiceLoaderDetector
import java.io.File
import java.util.jar.JarFile

/**
 * Main entry point: scans one or more JARs and produces an [AnalysisResult].
 */
internal object BytecodeAnalyzer {

    /**
     * Analyzes a single JAR file.
     */
    fun analyzeJar(jarPath: File): AnalysisResult {
        require(jarPath.exists() && jarPath.name.endsWith(".jar")) {
            "Not a valid JAR: $jarPath"
        }

        val jniEntries = mutableSetOf<JniEntry>()
        val reflectionEntries = mutableSetOf<ReflectionEntry>()
        val resourcePatterns = mutableSetOf<ResourcePattern>()
        val serviceLoaderEntries = mutableSetOf<ReflectionEntry>()
        val jniReferencedTypes = mutableSetOf<String>()

        JarFile(jarPath).use { jar ->
            // Service loader detection (scans META-INF/services/)
            val serviceResult = ServiceLoaderDetector.detect(jar)
            serviceLoaderEntries.addAll(serviceResult.reflectionEntries)
            resourcePatterns.addAll(serviceResult.resourcePatterns)

            // Scan all .class files
            for (entry in jar.entries()) {
                if (!entry.name.endsWith(".class") || entry.name.startsWith("META-INF/")) continue

                val classBytes = try {
                    jar.getInputStream(entry).use { it.readBytes() }
                } catch (_: Exception) {
                    continue
                }

                // Native method detection -> JNI entries + referenced types
                val nativeResult = NativeMethodDetector.detectWithReferences(classBytes)
                jniEntries.addAll(nativeResult.jniEntries)
                jniReferencedTypes.addAll(nativeResult.referencedTypes)

                // Class.forName detection -> reflection entries
                reflectionEntries.addAll(ClassForNameDetector.detect(classBytes))

                // Reflection API detection (getMethod, getDeclaredField, .class, etc.)
                reflectionEntries.addAll(ReflectionApiDetector.detect(classBytes))

                // ResourceBundle.getBundle detection
                resourcePatterns.addAll(ResourceBundleDetector.detect(classBytes))

                // getResource/getResourceAsStream detection
                resourcePatterns.addAll(ResourceAccessDetector.detect(classBytes))

                // MethodHandle lookup detection
                reflectionEntries.addAll(MethodHandleDetector.detect(classBytes))

                // Proxy.newProxyInstance detection
                reflectionEntries.addAll(ProxyDetector.detect(classBytes))
            }
        }

        // Add JNI-referenced types (from native method signatures) as JNI entries
        for (refType in jniReferencedTypes) {
            if (jniEntries.none { it.type == refType }) {
                jniEntries.add(JniEntry(type = refType))
            }
        }

        return AnalysisResult(
            reflectionEntries = reflectionEntries,
            jniEntries = jniEntries,
            resourcePatterns = resourcePatterns,
            serviceLoaderEntries = serviceLoaderEntries,
        )
    }

    /**
     * Analyzes a directory of compiled .class files (e.g. build/classes/kotlin/jvm/main).
     */
    fun analyzeClassDir(dir: File): AnalysisResult {
        if (!dir.exists() || !dir.isDirectory) return AnalysisResult()

        val jniEntries = mutableSetOf<JniEntry>()
        val reflectionEntries = mutableSetOf<ReflectionEntry>()
        val resourcePatterns = mutableSetOf<ResourcePattern>()
        val serviceLoaderEntries = mutableSetOf<ReflectionEntry>()
        val jniReferencedTypes = mutableSetOf<String>()

        // Scan META-INF/services/ in class directories
        val servicesDir = File(dir, "META-INF/services")
        if (servicesDir.isDirectory) {
            servicesDir.listFiles()?.filter { it.isFile }?.forEach { serviceFile ->
                val serviceName = serviceFile.name
                resourcePatterns.add(ResourcePattern(glob = "META-INF/services/$serviceName"))
                val implementations = serviceFile.readLines()
                    .map { it.substringBefore('#').trim() }
                    .filter { it.isNotEmpty() }
                for (impl in implementations) {
                    serviceLoaderEntries.add(
                        ReflectionEntry(
                            type = impl,
                            methods = setOf(MethodSignature("<init>", emptyList())),
                        ),
                    )
                }
            }
        }

        // Scan all .class files recursively
        dir.walkTopDown()
            .filter { it.isFile && it.extension == "class" }
            .forEach { classFile ->
                val classBytes = try {
                    classFile.readBytes()
                } catch (_: Exception) {
                    return@forEach
                }
                analyzeClassBytes(classBytes, jniEntries, reflectionEntries, resourcePatterns, jniReferencedTypes)
            }

        for (refType in jniReferencedTypes) {
            if (jniEntries.none { it.type == refType }) {
                jniEntries.add(JniEntry(type = refType))
            }
        }

        return AnalysisResult(
            reflectionEntries = reflectionEntries,
            jniEntries = jniEntries,
            resourcePatterns = resourcePatterns,
            serviceLoaderEntries = serviceLoaderEntries,
        )
    }

    /**
     * Analyzes a classpath that may contain both JARs and class directories.
     */
    fun analyzeClasspath(files: Iterable<File>): AnalysisResult {
        var merged = AnalysisResult()
        for (file in files) {
            merged = merged + when {
                file.isDirectory -> analyzeClassDir(file)
                file.isFile && file.name.endsWith(".jar") -> analyzeJar(file)
                else -> continue
            }
        }
        return merged
    }

    /**
     * Analyzes multiple JARs and merges results.
     */
    fun analyzeJars(jarPaths: List<File>): AnalysisResult {
        var merged = AnalysisResult()
        for (jar in jarPaths) {
            merged = merged + analyzeJar(jar)
        }
        return merged
    }

    private fun analyzeClassBytes(
        classBytes: ByteArray,
        jniEntries: MutableSet<JniEntry>,
        reflectionEntries: MutableSet<ReflectionEntry>,
        resourcePatterns: MutableSet<ResourcePattern>,
        jniReferencedTypes: MutableSet<String>,
    ) {
        val nativeResult = NativeMethodDetector.detectWithReferences(classBytes)
        jniEntries.addAll(nativeResult.jniEntries)
        jniReferencedTypes.addAll(nativeResult.referencedTypes)
        reflectionEntries.addAll(ClassForNameDetector.detect(classBytes))
        reflectionEntries.addAll(ReflectionApiDetector.detect(classBytes))
        resourcePatterns.addAll(ResourceBundleDetector.detect(classBytes))
        resourcePatterns.addAll(ResourceAccessDetector.detect(classBytes))
        reflectionEntries.addAll(MethodHandleDetector.detect(classBytes))
        reflectionEntries.addAll(ProxyDetector.detect(classBytes))
        reflectionEntries.addAll(KotlinSerializableDetector.detect(classBytes))
    }
}
