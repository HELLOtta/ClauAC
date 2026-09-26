package io.github.hellotta.clauac.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.slf4j.Logger;

// - Prepares the files of the isolated vanilla runtime: the simulation jar shipped inside this plugin, and the -
// - vanilla server jar with its libraries, taken from the same official server bundler the simulation was compiled -
// - against. Paperclip already keeps that bundler in the server's cache directory; it is only downloaded from -
// - Mojang when it is not there. Every file is verified against the hashes the bundler and Mojang publish -
final class VanillaRuntimeFiles {

    // - Where the build puts the simulation jar inside the plugin jar -
    private static final String SIMULATION_RESOURCE = "/simulation/clauac-simulation.jar";
    // - Written by the ExtractVanillaServer build task into the simulation jar -
    private static final String VANILLA_PROPERTIES = "META-INF/clauac/vanilla.properties";
    // - Paperclip's copy of the bundler, relative to the server's working directory -
    private static final String PAPERCLIP_CACHE_DIRECTORY = "cache";

    record Layout(String minecraftVersion, List<Path> classPath) {
    }

    private record Bundler(String minecraftVersion, URI url, String sha1, long size) {
    }

    private VanillaRuntimeFiles() {
    }

    static Layout prepare(Path dataDirectory, Logger logger) throws IOException {
        Path runtimeDirectory = dataDirectory.resolve("runtime");
        Files.createDirectories(runtimeDirectory);
        Path simulationJar = runtimeDirectory.resolve("clauac-simulation.jar");
        installSimulationJar(simulationJar);
        Bundler bundler = readBundler(simulationJar);
        Path bundlerJar = locateBundler(bundler, runtimeDirectory, logger);

        Path versionDirectory = runtimeDirectory.resolve(bundler.minecraftVersion());
        List<Path> classPath = new ArrayList<>();
        classPath.add(simulationJar);
        try (ZipFile zip = new ZipFile(bundlerJar.toFile())) {
            classPath.addAll(extractListed(zip, "META-INF/versions.list", "META-INF/versions/", versionDirectory.resolve("server")));
            classPath.addAll(extractListed(zip, "META-INF/libraries.list", "META-INF/libraries/", versionDirectory.resolve("libraries")));
        }
        return new Layout(bundler.minecraftVersion(), classPath);
    }

    // - Replaces the extracted simulation jar only when the one in the plugin differs, so that an unchanged plugin -
    // - never rewrites a jar another server instance may have open -
    private static void installSimulationJar(Path target) throws IOException {
        byte[] shipped;
        try (InputStream input = VanillaRuntimeFiles.class.getResourceAsStream(SIMULATION_RESOURCE)) {
            if (input == null) {
                throw new IOException("The plugin jar does not contain " + SIMULATION_RESOURCE);
            }
            shipped = input.readAllBytes();
        }
        if (Files.isRegularFile(target) && MessageDigest.isEqual(digest("SHA-256", shipped), digest("SHA-256", Files.readAllBytes(target)))) {
            return;
        }
        Path temporary = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
        Files.write(temporary, shipped);
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static Bundler readBundler(Path simulationJar) throws IOException {
        Properties properties = new Properties();
        try (ZipFile zip = new ZipFile(simulationJar.toFile())) {
            ZipEntry entry = zip.getEntry(VANILLA_PROPERTIES);
            if (entry == null) {
                throw new IOException(simulationJar + " does not contain " + VANILLA_PROPERTIES);
            }
            try (InputStream input = zip.getInputStream(entry)) {
                properties.load(input);
            }
        }
        return new Bundler(
                requireProperty(properties, "minecraftVersion"),
                URI.create(requireProperty(properties, "bundlerUrl")),
                requireProperty(properties, "bundlerSha1"),
                Long.parseLong(requireProperty(properties, "bundlerSize"))
        );
    }

    private static String requireProperty(Properties properties, String key) throws IOException {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IOException(VANILLA_PROPERTIES + " has no " + key);
        }
        return value;
    }

    private static Path locateBundler(Bundler bundler, Path runtimeDirectory, Logger logger) throws IOException {
        String fileName = "mojang_" + bundler.minecraftVersion() + ".jar";
        Path paperclipCopy = Path.of(PAPERCLIP_CACHE_DIRECTORY, fileName).toAbsolutePath();
        if (matches(paperclipCopy, bundler)) {
            logger.info("Using the vanilla {} server from Paperclip's cache: {}", bundler.minecraftVersion(), paperclipCopy);
            return paperclipCopy;
        }
        Path ownCopy = runtimeDirectory.resolve(fileName);
        if (matches(ownCopy, bundler)) {
            logger.info("Using the vanilla {} server downloaded earlier: {}", bundler.minecraftVersion(), ownCopy);
            return ownCopy;
        }
        logger.info("Paperclip's cache has no matching vanilla {} server ({}); downloading it from {}",
                bundler.minecraftVersion(), paperclipCopy, bundler.url());
        download(bundler, ownCopy);
        return ownCopy;
    }

    private static boolean matches(Path file, Bundler bundler) throws IOException {
        return Files.isRegularFile(file) && Files.size(file) == bundler.size() && bundler.sha1().equals(hash("SHA-1", file));
    }

    private static void download(Bundler bundler, Path target) throws IOException {
        Path temporary = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".download");
        try (HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()) {
            HttpResponse<Path> response = client.send(HttpRequest.newBuilder(bundler.url()).GET().build(), HttpResponse.BodyHandlers.ofFile(temporary));
            if (response.statusCode() != 200) {
                throw new IOException(bundler.url() + " answered with HTTP " + response.statusCode());
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while downloading " + bundler.url(), exception);
        }
        long size = Files.size(temporary);
        if (size != bundler.size()) {
            throw new IOException(bundler.url() + ": expected " + bundler.size() + " bytes, got " + size);
        }
        String sha1 = hash("SHA-1", temporary);
        if (!bundler.sha1().equals(sha1)) {
            throw new IOException(bundler.url() + ": expected SHA-1 " + bundler.sha1() + ", got " + sha1);
        }
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    // - Each line of a bundler list is "<sha256>\t<id>\t<path relative to the listed directory>". Files that are -
    // - already extracted with the listed hash are kept -
    private static List<Path> extractListed(ZipFile zip, String listName, String entryPrefix, Path targetDirectory) throws IOException {
        ZipEntry list = zip.getEntry(listName);
        if (list == null) {
            throw new IOException("The bundler has no " + listName);
        }
        List<String> lines;
        try (InputStream input = zip.getInputStream(list)) {
            lines = new String(input.readAllBytes(), StandardCharsets.UTF_8).lines().filter(line -> !line.isBlank()).toList();
        }
        Path root = targetDirectory.toAbsolutePath().normalize();
        List<Path> extracted = new ArrayList<>(lines.size());
        for (String line : lines) {
            String[] columns = line.split("\t");
            if (columns.length != 3) {
                throw new IOException("Malformed line in " + listName + ": " + line);
            }
            String sha256 = columns[0];
            String id = columns[1];
            Path target = root.resolve(columns[2]).normalize();
            if (!target.startsWith(root)) {
                throw new IOException(listName + " places " + id + " outside " + root + ": " + columns[2]);
            }
            if (!Files.isRegularFile(target) || !sha256.equals(hash("SHA-256", target))) {
                ZipEntry entry = zip.getEntry(entryPrefix + columns[2]);
                if (entry == null) {
                    throw new IOException("The bundler lists " + id + " but has no " + entryPrefix + columns[2]);
                }
                Files.createDirectories(target.getParent());
                Path temporary = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
                try (InputStream input = zip.getInputStream(entry)) {
                    Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
                }
                String actual = hash("SHA-256", temporary);
                if (!sha256.equals(actual)) {
                    Files.delete(temporary);
                    throw new IOException("SHA-256 mismatch for " + id + ": expected " + sha256 + ", got " + actual);
                }
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
            extracted.add(target);
        }
        return extracted;
    }

    private static String hash(String algorithm, Path file) throws IOException {
        MessageDigest digest = newDigest(algorithm);
        try (InputStream input = new DigestInputStream(Files.newInputStream(file), digest)) {
            input.transferTo(OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static byte[] digest(String algorithm, byte[] data) {
        return newDigest(algorithm).digest(data);
    }

    private static MessageDigest newDigest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(algorithm + " is required by every Java platform", exception);
        }
    }
}
