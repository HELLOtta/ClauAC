package io.github.hellotta.clauac.simulation.registry;

import java.util.Arrays;
import java.util.function.Supplier;
import net.minecraft.core.RegistryAccess;

// - Registries received during configuration, shared between connections that received identical data -
// - Building them also applies the server's tags and item components to the built-in registries, which are static -
// - state of the isolated runtime shared by every connection. Sharing one registry access per configuration keeps -
// - those static updates consistent with the registries every connection uses. Only the latest configuration is -
// - kept: when a connection brings different data (for example after a data pack reload), the registries are built -
// - again, which re-applies the static updates for that data. Connections still using older registries then see -
// - the newer static updates, and simulations running on other threads can observe them mid-update -
public final class ServerRegistryCache {

    private final Object lock = new Object();
    private byte[] currentFingerprint = new byte[0];
    private ReceivedRegistries currentRegistries;

    ReceivedRegistries getOrBuild(byte[] fingerprint, Supplier<RegistryAccess.Frozen> builder) {
        synchronized (this.lock) {
            if (this.currentRegistries == null || !Arrays.equals(this.currentFingerprint, fingerprint)) {
                this.currentRegistries = new ReceivedRegistries(builder.get());
                this.currentFingerprint = fingerprint.clone();
            }
            return this.currentRegistries;
        }
    }
}
