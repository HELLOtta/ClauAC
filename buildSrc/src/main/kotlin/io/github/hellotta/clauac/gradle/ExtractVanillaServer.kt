package io.github.hellotta.clauac.gradle

import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.io.InputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipFile

// - Downloads the official vanilla server bundler described by a pinned Mojang version JSON, verifies every file -
// - against the hashes Mojang publishes, and extracts the server jar and its libraries for compiling against them -
// - Output: server/<path> (unobfuscated server jar), libraries/<maven path> (bundled libraries), -
// - vanilla.properties (what the plugin needs at runtime to locate and verify the same bundler) -
abstract class ExtractVanillaServer : DefaultTask() {

    @get:Input
    abstract val versionJsonUrl: Property<String>

    @get:Input
    abstract val versionJsonSha1: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun extract() {
        val output = outputDirectory.get().asFile
        output.deleteRecursively()
        output.mkdirs()

        val versionJson = download(versionJsonUrl.get(), File(temporaryDir, "version.json"), versionJsonSha1.get(), null)
        val version = JsonSlurper().parse(versionJson) as Map<*, *>
        val versionId = version["id"] as String
        val server = ((version["downloads"] as Map<*, *>)["server"] as Map<*, *>)
        val bundlerUrl = server["url"] as String
        val bundlerSha1 = server["sha1"] as String
        val bundlerSize = (server["size"] as Number).toLong()
        val bundler = download(bundlerUrl, File(temporaryDir, "bundler.jar"), bundlerSha1, bundlerSize)

        ZipFile(bundler).use { zip ->
            extractListed(zip, "META-INF/versions.list", "META-INF/versions/", File(output, "server"))
            extractListed(zip, "META-INF/libraries.list", "META-INF/libraries/", File(output, "libraries"))
        }

        val properties = Properties()
        properties["minecraftVersion"] = versionId
        properties["bundlerUrl"] = bundlerUrl
        properties["bundlerSha1"] = bundlerSha1
        properties["bundlerSize"] = bundlerSize.toString()
        File(output, "vanilla.properties").outputStream().use { properties.store(it, "Vanilla server bundler the simulation was compiled against") }
    }

    // - Each line of a bundler list is "<sha256>\t<id>\t<path relative to the listed directory>" -
    private fun extractListed(zip: ZipFile, listName: String, entryPrefix: String, targetDirectory: File) {
        val list = zip.getEntry(listName) ?: throw GradleException("Bundler has no $listName")
        val lines = zip.getInputStream(list).bufferedReader().readLines().filter { it.isNotBlank() }
        for (line in lines) {
            val columns = line.split('\t')
            if (columns.size != 3) {
                throw GradleException("Malformed line in $listName: $line")
            }
            val (sha256, id, path) = columns
            val entry = zip.getEntry(entryPrefix + path) ?: throw GradleException("Bundler lists $id but has no $entryPrefix$path")
            val target = File(targetDirectory, path)
            target.parentFile.mkdirs()
            zip.getInputStream(entry).use { Files.copy(it, target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
            val actual = hash(target, "SHA-256")
            if (actual != sha256) {
                throw GradleException("SHA-256 mismatch for $id: expected $sha256, got $actual")
            }
        }
    }

    private fun download(url: String, target: File, sha1: String, size: Long?): File {
        if (target.isFile && hash(target, "SHA-1") == sha1) {
            return target
        }
        URI(url).toURL().openStream().use { input: InputStream -> Files.copy(input, target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        if (size != null && target.length() != size) {
            throw GradleException("$url: expected $size bytes, got ${target.length()}")
        }
        val actual = hash(target, "SHA-1")
        if (actual != sha1) {
            throw GradleException("$url: expected SHA-1 $sha1, got $actual")
        }
        return target
    }

    private fun hash(file: File, algorithm: String): String {
        val digest = MessageDigest.getInstance(algorithm)
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) {
                    break
                }
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
