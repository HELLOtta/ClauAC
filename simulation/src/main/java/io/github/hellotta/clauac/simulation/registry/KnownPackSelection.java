package io.github.hellotta.clauac.simulation.registry;

import com.google.common.collect.ImmutableMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.KnownPack;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.CloseableResourceManager;
import net.minecraft.server.packs.resources.MultiPackResourceManager;

// - Port of the client-only KnownPacksManager. The client answers the server's list of known packs with the ones it -
// - also has, and later reads the data of those packs locally instead of receiving it. The sandbox has the same -
// - vanilla packs as the client (they ship in the server jar too) and selects exactly what the client answered -
final class KnownPackSelection {

    private final PackRepository repository = ServerPacksSource.createVanillaTrustedRepository();
    private final Map<KnownPack, String> knownPackToId;

    KnownPackSelection() {
        this.repository.reload();
        ImmutableMap.Builder<KnownPack, String> knownPacks = ImmutableMap.builder();
        this.repository.getAvailablePacks().forEach(pack -> {
            PackLocationInfo location = pack.location();
            location.knownPackInfo().ifPresent(knownPack -> knownPacks.put(knownPack, location.id()));
        });
        this.knownPackToId = knownPacks.build();
    }

    // - The answer a vanilla client with the same packs gives to the server's offer -
    List<KnownPack> expectedClientAnswer(List<KnownPack> offeredPacks) {
        List<KnownPack> answer = new ArrayList<>(offeredPacks.size());
        for (KnownPack knownPack : offeredPacks) {
            if (this.knownPackToId.containsKey(knownPack)) {
                answer.add(knownPack);
            }
        }
        return answer;
    }

    // - Selects the packs the client actually answered with; fails when the client claims a pack the sandbox lacks, -
    // - because its data could then not be reproduced -
    void selectClientAnswer(List<KnownPack> clientAnswer) {
        List<String> selectedPacks = new ArrayList<>(clientAnswer.size());
        for (KnownPack knownPack : clientAnswer) {
            String knownPackId = this.knownPackToId.get(knownPack);
            if (knownPackId == null) {
                throw new IllegalStateException("The client selected known pack " + knownPack + ", which the vanilla runtime does not have");
            }
            selectedPacks.add(knownPackId);
        }
        this.repository.setSelected(selectedPacks);
    }

    CloseableResourceManager createResourceManager() {
        List<PackResources> openedPacks = this.repository.openAllSelected();
        return new MultiPackResourceManager(PackType.SERVER_DATA, openedPacks);
    }
}
