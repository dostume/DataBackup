package com.xayah.core.model.util

import com.xayah.core.model.CompressionType
import com.xayah.core.model.LZ4_SUFFIX
import com.xayah.core.model.TAR_SUFFIX
import com.xayah.core.model.ZSTD_SUFFIX

const val VOLUME_PART_TAG = "part"

data class VolumePartInfo(
    val baseName: String,
    val suffix: String,
    val compressionType: CompressionType,
    val index: Int,
)

fun volumePartFileName(baseName: String, suffix: String, index: Int): String =
    "$baseName.$suffix.$VOLUME_PART_TAG%05d".format(index)

fun volumePartPath(dstDir: String, baseName: String, suffix: String, index: Int): String =
    "$dstDir/${volumePartFileName(baseName, suffix, index)}"

/**
 * Parses a volume part file name such as "media.tar.zst.part00001".
 */
fun parseVolumePartFileName(fileName: String): VolumePartInfo? {
    val tag = ".$VOLUME_PART_TAG"
    val tagIndex = fileName.lastIndexOf(tag)
    if (tagIndex < 0) return null
    val index = fileName.substring(tagIndex + tag.length).toIntOrNull() ?: return null
    val rest = fileName.substring(0, tagIndex)
    val suffix = listOf(ZSTD_SUFFIX, LZ4_SUFFIX, TAR_SUFFIX).firstOrNull { rest.endsWith(".$it") } ?: return null
    val baseName = rest.removeSuffix(".$suffix")
    if (baseName.isEmpty()) return null
    val compressionType = CompressionType.suffixOf(suffix) ?: return null
    return VolumePartInfo(baseName = baseName, suffix = suffix, compressionType = compressionType, index = index)
}

/**
 * Strips the volume part suffix from an archive extension.
 * "tar.zst.part00001" -> "tar.zst", "tar.zst" -> "tar.zst".
 */
fun extensionWithoutVolume(extension: String): String = extension.substringBeforeLast(".$VOLUME_PART_TAG")

/**
 * Returns the volume index if [extension] ends with a volume part tag, otherwise -1.
 */
fun volumePartIndex(extension: String): Int {
    val tag = ".$VOLUME_PART_TAG"
    val tagIndex = extension.lastIndexOf(tag)
    if (tagIndex < 0) return -1
    return extension.substring(tagIndex + tag.length).toIntOrNull() ?: -1
}
