package io.github.kdroidfilter.nucleus.desktop.application.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.util.Properties

@DisableCachingByDefault(because = "Lightweight task that writes a single properties file")
abstract class AbstractGenerateAppPropertiesTask : DefaultTask() {
    @get:Input
    abstract val appId: Property<String>

    @get:Input
    @get:Optional
    abstract val appVersion: Property<String>

    @get:Input
    @get:Optional
    abstract val appVendor: Property<String>

    @get:Input
    @get:Optional
    abstract val appDescription: Property<String>

    @get:Input
    @get:Optional
    abstract val startupWmClass: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val dir = outputDir.get().asFile.resolve("nucleus")
        dir.mkdirs()

        val props = Properties()
        props["app.id"] = appId.get()
        appVersion.orNull?.let { props["app.version"] = it }
        appVendor.orNull?.let { props["app.vendor"] = it }
        appDescription.orNull?.let { props["app.description"] = it }
        startupWmClass.orNull?.let { props["startup.wm.class"] = it }

        dir.resolve("nucleus-app.properties").writer().use { writer ->
            props.store(writer, null)
        }
    }
}
