package io.github.hellotta.clauac.simulation.registry;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.protocol.common.ClientboundUpdateTagsPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagNetworkSerialization;

// - The registries a client plays with after a configuration phase, possibly shared by several connections that -
// - received identical configuration data -
public final class ReceivedRegistries {

    private final RegistryAccess.Frozen access;
    private final Object tagLock = new Object();
    private byte[] lastPlayTagUpdate = new byte[0];

    ReceivedRegistries(RegistryAccess.Frozen access) {
        this.access = access;
    }

    public RegistryAccess.Frozen access() {
        return this.access;
    }

    // - Port of ClientPacketListener.handleUpdateTags for a remote connection, which replaces the tags of the -
    // - registries in place. The server broadcasts the same update to every connection, so an update that equals -
    // - the last one applied to these registries is not applied again. Connections sharing the registries that -
    // - have not reached the update yet already see the new tags, and simulations running on other threads can -
    // - observe them mid-update; see ServerRegistryCache for the same limitation of the static registries -
    public void applyPlayTagUpdate(ClientboundUpdateTagsPacket packet, byte[] encodedPacket) {
        byte[] digest = sha256(encodedPacket);
        synchronized (this.tagLock) {
            if (Arrays.equals(this.lastPlayTagUpdate, digest)) {
                return;
            }
            List<Registry.PendingTags<?>> pendingTags = new ArrayList<>(packet.getTags().size());
            packet.getTags().forEach((registryKey, payload) -> pendingTags.add(this.prepareTags(registryKey, payload)));
            pendingTags.forEach(Registry.PendingTags::apply);
            this.lastPlayTagUpdate = digest;
        }
    }

    private <T> Registry.PendingTags<T> prepareTags(ResourceKey<? extends Registry<? extends T>> registryKey, TagNetworkSerialization.NetworkPayload payload) {
        Registry<T> registry = this.access.lookupOrThrow(registryKey);
        return registry.prepareTagReload(payload.resolve(registry));
    }

    static byte[] sha256(byte[] data) {
        return newSha256().digest(data);
    }

    static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", exception);
        }
    }
}
