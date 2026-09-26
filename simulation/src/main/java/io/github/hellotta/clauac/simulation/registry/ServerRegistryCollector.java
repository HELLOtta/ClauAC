package io.github.hellotta.clauac.simulation.registry;

import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import net.minecraft.CrashReport;
import net.minecraft.CrashReportCategory;
import net.minecraft.ReportedException;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.RegistrySynchronization;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.packs.resources.ResourceProvider;
import net.minecraft.tags.TagLoader;
import net.minecraft.tags.TagNetworkSerialization;
import net.minecraft.util.Util;
import org.slf4j.Logger;

// - Port of the client-only RegistryDataCollector for a remote connection, where the client applies tags and -
// - item components to every registry, not only to the synchronized ones -
final class ServerRegistryCollector {

    private static final Logger LOGGER = LogUtils.getLogger();
    private final Map<ResourceKey<? extends Registry<?>>, List<RegistrySynchronization.PackedRegistryEntry>> contents = new HashMap<>();
    private final Map<ResourceKey<? extends Registry<?>>, TagNetworkSerialization.NetworkPayload> tags = new HashMap<>();
    private boolean contentsReceived;
    private boolean tagsReceived;

    void appendContents(ResourceKey<? extends Registry<?>> registry, List<RegistrySynchronization.PackedRegistryEntry> elementData) {
        this.contentsReceived = true;
        this.contents.computeIfAbsent(registry, ignored -> new ArrayList<>()).addAll(elementData);
    }

    void appendTags(Map<ResourceKey<? extends Registry<?>>, TagNetworkSerialization.NetworkPayload> data) {
        this.tagsReceived = true;
        this.tags.putAll(data);
    }

    boolean hasContents() {
        return this.contentsReceived;
    }

    private static <T> Registry.PendingTags<T> resolveRegistryTags(
            RegistryAccess.Frozen context, ResourceKey<? extends Registry<? extends T>> registryKey, TagNetworkSerialization.NetworkPayload payload
    ) {
        Registry<T> staticRegistry = context.lookupOrThrow(registryKey);
        return staticRegistry.prepareTagReload(payload.resolve(staticRegistry));
    }

    private RegistryAccess loadNewElementsAndTags(ResourceProvider knownDataSource) {
        LayeredRegistryAccess<SandboxRegistryLayer> base = SandboxRegistryLayer.createRegistryAccess();
        RegistryAccess.Frozen loadingContext = base.getAccessForLoading(SandboxRegistryLayer.REMOTE);
        Map<ResourceKey<? extends Registry<?>>, RegistryDataLoader.NetworkedRegistryData> entriesToLoad = new HashMap<>();
        this.contents.forEach((registryKey, elements) -> entriesToLoad.put(
                registryKey, new RegistryDataLoader.NetworkedRegistryData(elements, TagNetworkSerialization.NetworkPayload.EMPTY)));
        List<Registry.PendingTags<?>> pendingStaticTags = new ArrayList<>();
        if (this.tagsReceived) {
            this.tags.forEach((registryKey, payload) -> {
                if (!payload.isEmpty()) {
                    if (RegistrySynchronization.isNetworkable(registryKey)) {
                        entriesToLoad.compute(registryKey, (key, previousData) -> {
                            List<RegistrySynchronization.PackedRegistryEntry> elements = previousData != null ? previousData.elements() : List.of();
                            return new RegistryDataLoader.NetworkedRegistryData(elements, payload);
                        });
                    } else {
                        pendingStaticTags.add(resolveRegistryTags(loadingContext, registryKey, payload));
                    }
                }
            });
        }

        List<HolderLookup.RegistryLookup<?>> contextRegistriesWithTags = TagLoader.buildUpdatedLookups(loadingContext, pendingStaticTags);

        RegistryAccess.Frozen receivedRegistries;
        try {
            long start = Util.getMillis();
            receivedRegistries = RegistryDataLoader.load(
                    entriesToLoad, knownDataSource, contextRegistriesWithTags, RegistryDataLoader.SYNCHRONIZED_REGISTRIES, Util.backgroundExecutor()
            ).join();
            long end = Util.getMillis();
            LOGGER.debug("Loading network data took {} ms", end - start);
        } catch (Exception exception) {
            CrashReport report = CrashReport.forThrowable(exception, "Network Registry Load");
            addCrashDetails(report, entriesToLoad, pendingStaticTags);
            throw new ReportedException(report);
        }

        RegistryAccess registries = base.replaceFrom(SandboxRegistryLayer.REMOTE, receivedRegistries).compositeAccess();
        pendingStaticTags.forEach(Registry.PendingTags::apply);
        return registries;
    }

    private static void addCrashDetails(
            CrashReport report,
            Map<ResourceKey<? extends Registry<?>>, RegistryDataLoader.NetworkedRegistryData> dynamicRegistries,
            List<Registry.PendingTags<?>> staticRegistries
    ) {
        CrashReportCategory details = report.addCategory("Received Elements and Tags");
        details.setDetail(
                "Dynamic Registries",
                () -> dynamicRegistries.entrySet().stream()
                        .sorted(Comparator.comparing(entry -> entry.getKey().identifier()))
                        .map(entry -> String.format(Locale.ROOT, "\n\t\t%s: elements=%d tags=%d",
                                entry.getKey().identifier(), entry.getValue().elements().size(), entry.getValue().tags().size()))
                        .collect(Collectors.joining())
        );
        details.setDetail(
                "Static Registries",
                () -> staticRegistries.stream()
                        .sorted(Comparator.comparing(entry -> entry.key().identifier()))
                        .map(entry -> String.format(Locale.ROOT, "\n\t\t%s: tags=%d", entry.key().identifier(), entry.size()))
                        .collect(Collectors.joining())
        );
    }

    private void loadOnlyTags(RegistryAccess.Frozen originalRegistries) {
        this.tags.forEach((registryKey, payload) -> resolveRegistryTags(originalRegistries, registryKey, payload).apply());
    }

    private static void updateComponents(RegistryAccess.Frozen frozenRegistries) {
        BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(frozenRegistries).forEach(pendingComponents -> pendingComponents.apply());
    }

    RegistryAccess.Frozen collectGameRegistries(ResourceProvider knownDataSource, RegistryAccess.Frozen originalRegistries) {
        RegistryAccess registries;
        if (this.contentsReceived) {
            registries = this.loadNewElementsAndTags(knownDataSource);
        } else {
            if (this.tagsReceived) {
                this.loadOnlyTags(originalRegistries);
            }
            registries = originalRegistries;
        }

        RegistryAccess.Frozen frozenRegistries = registries.freeze();
        updateComponents(frozenRegistries);
        return frozenRegistries;
    }
}
