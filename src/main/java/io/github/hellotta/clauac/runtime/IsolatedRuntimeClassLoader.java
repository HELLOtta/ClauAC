package io.github.hellotta.clauac.runtime;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;

// - Loads the vanilla server, its libraries and the simulation code apart from Paper: its parent is the platform -
// - class loader, so none of Paper's (modified) Minecraft classes or libraries are visible to it. Only the -
// - simulation API, through which the plugin talks to the runtime, and the logging APIs, so that the runtime logs -
// - into the server's log, come from the plugin's class loader -
final class IsolatedRuntimeClassLoader extends URLClassLoader {

    static {
        ClassLoader.registerAsParallelCapable();
    }

    private static final List<String> SHARED_PACKAGE_PREFIXES = List.of(
            "io.github.hellotta.clauac.simulation.api.",
            "org.slf4j.",
            "org.apache.logging.log4j."
    );

    private final ClassLoader pluginClassLoader;

    IsolatedRuntimeClassLoader(List<Path> classPath, ClassLoader pluginClassLoader) {
        super("ClauAC vanilla runtime", toUrls(classPath), ClassLoader.getPlatformClassLoader());
        this.pluginClassLoader = pluginClassLoader;
    }

    private static URL[] toUrls(List<Path> classPath) {
        URL[] urls = new URL[classPath.size()];
        for (int i = 0; i < urls.length; i++) {
            try {
                urls[i] = classPath.get(i).toUri().toURL();
            } catch (MalformedURLException exception) {
                throw new UncheckedIOException(new IOException("Not usable as a class path entry: " + classPath.get(i), exception));
            }
        }
        return urls;
    }

    private static boolean isShared(String className) {
        for (String prefix : SHARED_PACKAGE_PREFIXES) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (isShared(name)) {
            return this.pluginClassLoader.loadClass(name);
        }
        return super.loadClass(name, resolve);
    }
}
