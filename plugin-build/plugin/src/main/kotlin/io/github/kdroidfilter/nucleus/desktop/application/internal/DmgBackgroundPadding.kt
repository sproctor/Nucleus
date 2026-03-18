/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package io.github.kdroidfilter.nucleus.desktop.application.internal

import org.gradle.api.logging.Logger
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Height of the macOS DMG window title bar in pixels.
 *
 * When electron-builder uses a background image, it sets the window frame size
 * equal to the image dimensions. Since the title bar occupies this many pixels,
 * the visible content area is shorter, clipping the bottom of the image.
 *
 * @see padDmgBackgroundForTitleBar
 */
internal const val MACOS_DMG_TITLE_BAR_HEIGHT = 28

/**
 * Pads a DMG background image at the bottom to compensate for the macOS title bar.
 *
 * electron-builder sets the DMG window size to match the background image dimensions,
 * ignoring any explicit `window.width`/`window.height` configuration. Because the macOS
 * title bar takes [MACOS_DMG_TITLE_BAR_HEIGHT] pixels, the actual content area is smaller
 * than the image, causing the bottom to be clipped.
 *
 * This function adds transparent padding at the bottom of the image so that:
 * - electron-builder creates a window of `(image_height + 28)` pixels
 * - The content area becomes `(image_height + 28) - 28 = image_height`
 * - The original image fits perfectly, with the padding in the clipped region
 *
 * Returns the padded image file, or the original file if padding fails.
 *
 * @see <a href="https://github.com/kdroidFilter/Nucleus/issues/26">Issue #26</a>
 */
internal fun padDmgBackgroundForTitleBar(
    source: File,
    outputDir: File,
    logger: Logger? = null,
): File {
    try {
        val original =
            ImageIO.read(source) ?: run {
                logger?.warn("Could not read DMG background image: ${source.absolutePath}, skipping title bar padding")
                return source
            }

        val paddedHeight = original.height + MACOS_DMG_TITLE_BAR_HEIGHT
        val imageType = if (original.type != 0) original.type else BufferedImage.TYPE_INT_ARGB
        val padded = BufferedImage(original.width, paddedHeight, imageType)
        val g = padded.createGraphics()
        g.drawImage(original, 0, 0, null)
        g.dispose()

        val extension = source.extension.ifEmpty { "png" }
        val formatName =
            when (extension.lowercase()) {
                "tiff", "tif" -> "tiff"
                else -> "png"
            }
        val output = File(outputDir, "background-padded.$extension")
        outputDir.mkdirs()
        ImageIO.write(padded, formatName, output)

        logger?.info(
            "Padded DMG background image with ${MACOS_DMG_TITLE_BAR_HEIGHT}px for title bar compensation: " +
                "${original.width}x${original.height} → ${padded.width}x${padded.height}",
        )
        return output
    } catch (e: Exception) {
        logger?.warn(
            "Failed to pad DMG background image for title bar compensation, using original: ${e.message}",
        )
        return source
    }
}

/**
 * Reads the dimensions of an image file.
 *
 * @return a [Pair] of (width, height) in pixels, or `null` if the image cannot be read.
 */
internal fun readImageDimensions(file: File): Pair<Int, Int>? {
    return try {
        val readers = ImageIO.getImageReadersBySuffix(file.extension)
        if (!readers.hasNext()) return null
        val reader = readers.next()
        ImageIO.createImageInputStream(file).use { stream ->
            reader.input = stream
            val width = reader.getWidth(0)
            val height = reader.getHeight(0)
            reader.dispose()
            width to height
        }
    } catch (_: Exception) {
        null
    }
}
