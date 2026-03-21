import org.apache.tools.ant.taskdefs.condition.Os
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    alias(libs.plugins.vanniktechMavenPublish)
}

val publishVersion =
    providers
        .environmentVariable("GITHUB_REF")
        .orNull
        ?.removePrefix("refs/tags/v")
        ?: "1.0.0"

dependencies {
    compileOnly(project(":core-runtime"))
    testImplementation(project(":core-runtime"))
    testImplementation(kotlin("test"))
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

val nativeResourceDir = layout.projectDirectory.dir("src/main/resources/nucleus/native")

val buildNativeWindows by tasks.registering(Exec::class) {
    description = "Compiles the C JNI bridge into Windows DLLs (x64 + ARM64)"
    group = "build"
    val hasPrebuilt =
        nativeResourceDir
            .dir("win32-x64")
            .file("nucleus_taskbar_progress.dll")
            .asFile
            .exists()
    enabled = Os.isFamily(Os.FAMILY_WINDOWS) && !hasPrebuilt

    val nativeDir = layout.projectDirectory.dir("src/main/native/windows")
    inputs.dir(nativeDir)
    outputs.dir(nativeResourceDir)
    workingDir(nativeDir)
    commandLine("cmd", "/c", nativeDir.file("build.bat").asFile.absolutePath)
}

tasks.processResources {
    dependsOn(buildNativeWindows)
}

tasks.configureEach {
    if (name == "sourcesJar") {
        dependsOn(buildNativeWindows)
    }
}

mavenPublishing {
    coordinates("io.github.kdroidfilter", "nucleus.taskbar-progress", publishVersion)

    pom {
        name.set("Nucleus Taskbar Progress")
        description.set("Windows taskbar progress indicator (ITaskbarList3) for Compose Desktop")
        url.set("https://github.com/kdroidFilter/Nucleus")

        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
            }
        }

        developers {
            developer {
                id.set("kdroidfilter")
                name.set("kdroidFilter")
                url.set("https://github.com/kdroidFilter")
            }
        }

        scm {
            url.set("https://github.com/kdroidFilter/Nucleus")
            connection.set("scm:git:git://github.com/kdroidFilter/Nucleus.git")
            developerConnection.set("scm:git:ssh://git@github.com/kdroidFilter/Nucleus.git")
        }
    }

    publishToMavenCentral()
    if (project.hasProperty("signingInMemoryKey")) {
        signAllPublications()
    }
}
