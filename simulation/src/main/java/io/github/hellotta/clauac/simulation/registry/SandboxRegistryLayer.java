package io.github.hellotta.clauac.simulation.registry;

import java.util.List;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;

// - Port of the client-only ClientRegistryLayer: built-in registries below the registries received from the server -
enum SandboxRegistryLayer {
    STATIC,
    REMOTE;

    private static final List<SandboxRegistryLayer> VALUES = List.of(values());
    private static final RegistryAccess.Frozen STATIC_ACCESS = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);

    static LayeredRegistryAccess<SandboxRegistryLayer> createRegistryAccess() {
        return new LayeredRegistryAccess<>(VALUES).replaceFrom(STATIC, STATIC_ACCESS);
    }
}
