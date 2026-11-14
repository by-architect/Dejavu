/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.backup

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * The backup file: a zip with backup.json, see [BackupJson], and the pictures of the wallpapers in a folder named
 * wallpapers, by their names on the phone. Only the entries it expects are read, each up to a size, so a damaged or
 * made up file can neither fill the phone nor write outside the folder of the wallpapers.
 */
internal object BackupZip {
    const val MIME_TYPE = "application/zip"

    /** The types a backup to restore may have, as file managers name zip files in more than one way. */
    val OPEN_TYPES = arrayOf(MIME_TYPE, "application/x-zip-compressed", "application/octet-stream")

    /** backup.json holds more than this only when it is no backup. */
    const val MAX_JSON_SIZE = 32L * 1024 * 1024

    /** A wallpaper is a picture just big enough to cover a screen, well below this. */
    const val MAX_PICTURE_SIZE = 32L * 1024 * 1024

    private const val JSON_ENTRY = "backup.json"
    private const val WALLPAPER_FOLDER = "wallpapers/"
    private const val BUFFER_SIZE = 64 * 1024

    /** The name of a backup made at [time], like dejavu-backup-2026-11-14.zip. */
    fun name(time: Long): String =
        "dejavu-backup-" + SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date(time)) + ".zip"

    /** Writes a backup of [json] and the wallpaper pictures [pictures], by their names, to [output]. */
    fun write(output: OutputStream, json: String, pictures: Map<String, File>) {
        ZipOutputStream(output.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(JSON_ENTRY))
            zip.write(json.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            pictures.forEach { (name, file) ->
                zip.putNextEntry(ZipEntry(WALLPAPER_FOLDER + name))
                file.inputStream().use { it.copyTo(zip, BUFFER_SIZE) }
                zip.closeEntry()
            }
        }
    }

    /** The backup.json of the backup in [input], or `null` when it is no zip, has none, or it is too large. */
    fun readJson(input: InputStream): String? = ZipInputStream(input.buffered()).use { zip ->
        val found = generateSequence<ZipEntry> { zip.nextEntry }.any { !it.isDirectory && it.name == JSON_ENTRY }
        if (found) readLimited(zip, MAX_JSON_SIZE)?.toString(Charsets.UTF_8) else null
    }

    /**
     * Gives [save] each picture of [names] that the backup in [input] holds in its folder of wallpapers, with its name
     * and its content. Other entries are skipped.
     */
    fun readPictures(input: InputStream, names: Set<String>, save: (String, InputStream) -> Unit) {
        ZipInputStream(input.buffered()).use { zip ->
            generateSequence<ZipEntry> { zip.nextEntry }.forEach { entry ->
                val name = entry.name.removePrefix(WALLPAPER_FOLDER)
                if (!entry.isDirectory && entry.name.startsWith(WALLPAPER_FOLDER) && name in names) save(name, zip)
            }
        }
    }

    /**
     * Copies [input] to [file] through a file next to it, so a picture is never left half written under its own name.
     * Returns whether it was copied, which it is not when it is larger than [MAX_PICTURE_SIZE].
     */
    fun copyTo(input: InputStream, file: File): Boolean {
        val parent = file.parentFile ?: return false
        parent.mkdirs()
        val partial = File(parent, file.name + PARTIAL_SUFFIX)
        val copied = runCatching {
            partial.outputStream().use { output -> copyLimited(input, output, MAX_PICTURE_SIZE) }
        }.getOrDefault(false)
        if (copied && (partial.renameTo(file) || (file.delete() && partial.renameTo(file)))) return true
        partial.delete()
        return false
    }

    /** All of [input], or `null` when it holds more than [max] bytes. */
    fun readLimited(input: InputStream, max: Long): ByteArray? {
        val bytes = ByteArrayOutputStream()
        return if (copyLimited(input, bytes, max)) bytes.toByteArray() else null
    }

    /** Copies [input] to [output] while it holds at most [max] bytes; returns whether all of it fit. */
    private fun copyLimited(input: InputStream, output: OutputStream, max: Long): Boolean {
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0L
        var read = input.read(buffer)
        while (read >= 0) {
            total += read
            if (total > max) return false
            output.write(buffer, 0, read)
            read = input.read(buffer)
        }
        return true
    }

    private const val PARTIAL_SUFFIX = ".part"
}
