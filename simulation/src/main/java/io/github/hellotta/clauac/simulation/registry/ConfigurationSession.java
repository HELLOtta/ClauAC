package io.github.hellotta.clauac.simulation.registry;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.function.Function;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.protocol.common.ClientboundUpdateTagsPacket;
import net.minecraft.network.protocol.configuration.ClientboundRegistryDataPacket;
import net.minecraft.network.protocol.configuration.ClientboundSelectKnownPacks;
import net.minecraft.network.protocol.configuration.ClientboundUpdateEnabledFeaturesPacket;
import net.minecraft.network.protocol.configuration.ServerboundSelectKnownPacks;
import net.minecraft.server.packs.repository.KnownPack;
import net.minecraft.server.packs.resources.CloseableResourceManager;
import net.minecraft.server.packs.resources.ResourceProvider;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.flag.FeatureFlags;

// - Mirrors the client's configuration phase (ClientConfigurationPacketListenerImpl) for the packets that decide -
// - the registries, tags and enabled features the client plays with -
public final class ConfigurationSession {

    private final ServerRegistryCache registryCache;
    private final RegistryAccess.Frozen previousRegistries;
    private final ServerRegistryCollector collector = new ServerRegistryCollector();
    private final MessageDigest fingerprint;
    private FeatureFlagSet enabledFeatures;
    private KnownPackSelection knownPacks;
    private List<KnownPack> expectedKnownPackAnswer = List.of();

    // - previousRegistries and previousFeatures are what the client had before this configuration: the built-in -
    // - registries and default features on the first configuration, the last configured ones afterwards -
    public ConfigurationSession(ServerRegistryCache registryCache, RegistryAccess.Frozen previousRegistries, FeatureFlagSet previousFeatures) {
        this.registryCache = registryCache;
        this.previousRegistries = previousRegistries;
        this.enabledFeatures = previousFeatures;
        this.fingerprint = ReceivedRegistries.newSha256();
    }

    // - The registries of a client before its first configuration: only the built-in ones -
    public static ReceivedRegistries initialRegistries() {
        return new ReceivedRegistries(SandboxRegistryLayer.createRegistryAccess().compositeAccess());
    }

    public static FeatureFlagSet initialFeatures() {
        return FeatureFlags.DEFAULT_FLAGS;
    }

    // - encodedPacket is the raw packet; identical raw data means identical registries, which lets connections -
    // - share them -
    public void handleRegistryData(ClientboundRegistryDataPacket packet, byte[] encodedPacket) {
        this.fingerprint.update(encodedPacket);
        this.collector.appendContents(packet.registry(), packet.entries());
    }

    public void handleUpdateTags(ClientboundUpdateTagsPacket packet, byte[] encodedPacket) {
        this.fingerprint.update(encodedPacket);
        this.collector.appendTags(packet.tags());
    }

    public void handleEnabledFeatures(ClientboundUpdateEnabledFeaturesPacket packet) {
        this.enabledFeatures = FeatureFlags.REGISTRY.fromNames(packet.features());
    }

    public void handleServerKnownPacks(ClientboundSelectKnownPacks packet) {
        if (this.knownPacks == null) {
            this.knownPacks = new KnownPackSelection();
        }
        this.expectedKnownPackAnswer = this.knownPacks.expectedClientAnswer(packet.knownPacks());
    }

    // - Returns whether the client answered like a vanilla client with the same packs would -
    public boolean handleClientKnownPacks(ServerboundSelectKnownPacks packet) {
        if (this.knownPacks == null) {
            throw new IllegalStateException("The client selected known packs without being offered any");
        }
        this.knownPacks.selectClientAnswer(packet.knownPacks());
        this.fingerprint.update(packet.knownPacks().toString().getBytes(StandardCharsets.UTF_8));
        return packet.knownPacks().equals(this.expectedKnownPackAnswer);
    }

    public FeatureFlagSet enabledFeatures() {
        return this.enabledFeatures;
    }

    // - What the client does on ClientboundFinishConfigurationPacket: load the registries from the received -
    // - data plus the data of the selected known packs -
    public ReceivedRegistries finish() {
        if (!this.collector.hasContents()) {
            // - Only tags were received: the client updates the tags of its previous registries in place, so they -
            // - cannot be shared with other connections -
            return new ReceivedRegistries(this.runWithResources(provider -> this.collector.collectGameRegistries(provider, this.previousRegistries)));
        }
        return this.registryCache.getOrBuild(
                this.fingerprint.digest(),
                () -> this.runWithResources(provider -> this.collector.collectGameRegistries(provider, this.previousRegistries))
        );
    }

    private RegistryAccess.Frozen runWithResources(Function<ResourceProvider, RegistryAccess.Frozen> operation) {
        if (this.knownPacks == null) {
            return operation.apply(ResourceProvider.EMPTY);
        }
        try (CloseableResourceManager manager = this.knownPacks.createResourceManager()) {
            return operation.apply(manager);
        }
    }
}
