package io.github.hellotta.clauac.simulation.session;

import io.github.hellotta.clauac.simulation.api.PacketDirection;
import io.github.hellotta.clauac.simulation.api.ProtocolPhase;
import java.util.BitSet;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.PacketType;
import net.minecraft.network.protocol.common.CommonPacketTypes;
import net.minecraft.network.protocol.configuration.ConfigurationPacketTypes;
import net.minecraft.network.protocol.configuration.ConfigurationProtocols;
import net.minecraft.network.protocol.game.GamePacketTypes;
import net.minecraft.network.protocol.game.GameProtocols;

// - The packets ClientSession handles, as network ids of the vanilla protocols. Everything else neither changes -
// - the state the client's player moves in nor tells what the client did in a tick -
public final class RelevantPackets {

    private static final Set<PacketType<?>> CONFIGURATION_CLIENTBOUND = Set.of(
            ConfigurationPacketTypes.CLIENTBOUND_REGISTRY_DATA,
            ConfigurationPacketTypes.CLIENTBOUND_SELECT_KNOWN_PACKS,
            ConfigurationPacketTypes.CLIENTBOUND_UPDATE_ENABLED_FEATURES,
            ConfigurationPacketTypes.CLIENTBOUND_FINISH_CONFIGURATION,
            CommonPacketTypes.CLIENTBOUND_UPDATE_TAGS
    );
    private static final Set<PacketType<?>> CONFIGURATION_SERVERBOUND = Set.of(
            ConfigurationPacketTypes.SERVERBOUND_SELECT_KNOWN_PACKS
    );
    private static final Set<PacketType<?>> PLAY_CLIENTBOUND = Set.of(
            GamePacketTypes.CLIENTBOUND_LOGIN,
            GamePacketTypes.CLIENTBOUND_RESPAWN,
            GamePacketTypes.CLIENTBOUND_START_CONFIGURATION,
            GamePacketTypes.CLIENTBOUND_LEVEL_CHUNK_WITH_LIGHT,
            GamePacketTypes.CLIENTBOUND_FORGET_LEVEL_CHUNK,
            GamePacketTypes.CLIENTBOUND_CHUNKS_BIOMES,
            GamePacketTypes.CLIENTBOUND_BLOCK_UPDATE,
            GamePacketTypes.CLIENTBOUND_SECTION_BLOCKS_UPDATE,
            GamePacketTypes.CLIENTBOUND_BLOCK_CHANGED_ACK,
            GamePacketTypes.CLIENTBOUND_SET_CHUNK_CACHE_CENTER,
            GamePacketTypes.CLIENTBOUND_SET_CHUNK_CACHE_RADIUS,
            GamePacketTypes.CLIENTBOUND_SET_SIMULATION_DISTANCE,
            GamePacketTypes.CLIENTBOUND_PLAYER_POSITION,
            GamePacketTypes.CLIENTBOUND_PLAYER_ROTATION,
            GamePacketTypes.CLIENTBOUND_TELEPORT_ENTITY,
            GamePacketTypes.CLIENTBOUND_PLAYER_ABILITIES,
            GamePacketTypes.CLIENTBOUND_GAME_EVENT,
            GamePacketTypes.CLIENTBOUND_PLAYER_INFO_UPDATE,
            GamePacketTypes.CLIENTBOUND_PLAYER_INFO_REMOVE,
            GamePacketTypes.CLIENTBOUND_UPDATE_ATTRIBUTES,
            GamePacketTypes.CLIENTBOUND_SET_ENTITY_MOTION,
            GamePacketTypes.CLIENTBOUND_EXPLODE,
            GamePacketTypes.CLIENTBOUND_UPDATE_MOB_EFFECT,
            GamePacketTypes.CLIENTBOUND_REMOVE_MOB_EFFECT,
            GamePacketTypes.CLIENTBOUND_SET_ENTITY_DATA,
            GamePacketTypes.CLIENTBOUND_SET_HEALTH,
            GamePacketTypes.CLIENTBOUND_SET_PASSENGERS,
            GamePacketTypes.CLIENTBOUND_REMOVE_ENTITIES,
            GamePacketTypes.CLIENTBOUND_SET_CAMERA,
            GamePacketTypes.CLIENTBOUND_INITIALIZE_BORDER,
            GamePacketTypes.CLIENTBOUND_SET_BORDER_CENTER,
            GamePacketTypes.CLIENTBOUND_SET_BORDER_LERP_SIZE,
            GamePacketTypes.CLIENTBOUND_SET_BORDER_SIZE,
            GamePacketTypes.CLIENTBOUND_SET_BORDER_WARNING_DELAY,
            GamePacketTypes.CLIENTBOUND_SET_BORDER_WARNING_DISTANCE,
            GamePacketTypes.CLIENTBOUND_SET_TIME,
            GamePacketTypes.CLIENTBOUND_TICKING_STATE,
            GamePacketTypes.CLIENTBOUND_TICKING_STEP,
            CommonPacketTypes.CLIENTBOUND_UPDATE_TAGS,
            CommonPacketTypes.CLIENTBOUND_PING
    );
    private static final Set<PacketType<?>> PLAY_SERVERBOUND = Set.of(
            CommonPacketTypes.SERVERBOUND_PONG,
            GamePacketTypes.SERVERBOUND_ACCEPT_TELEPORTATION,
            GamePacketTypes.SERVERBOUND_CONFIGURATION_ACKNOWLEDGED,
            GamePacketTypes.SERVERBOUND_PLAYER_LOADED,
            GamePacketTypes.SERVERBOUND_CLIENT_TICK_END,
            GamePacketTypes.SERVERBOUND_PLAYER_INPUT,
            GamePacketTypes.SERVERBOUND_MOVE_PLAYER_POS,
            GamePacketTypes.SERVERBOUND_MOVE_PLAYER_POS_ROT,
            GamePacketTypes.SERVERBOUND_MOVE_PLAYER_ROT,
            GamePacketTypes.SERVERBOUND_MOVE_PLAYER_STATUS_ONLY,
            GamePacketTypes.SERVERBOUND_PLAYER_COMMAND,
            GamePacketTypes.SERVERBOUND_PLAYER_ABILITIES,
            GamePacketTypes.SERVERBOUND_PLAYER_ACTION,
            GamePacketTypes.SERVERBOUND_USE_ITEM,
            GamePacketTypes.SERVERBOUND_USE_ITEM_ON,
            GamePacketTypes.SERVERBOUND_ATTACK,
            GamePacketTypes.SERVERBOUND_INTERACT
    );

    private final BitSet configurationClientbound;
    private final BitSet configurationServerbound;
    private final BitSet playClientbound;
    private final BitSet playServerbound;

    public RelevantPackets() {
        this.configurationClientbound = networkIds(ConfigurationProtocols.CLIENTBOUND_TEMPLATE.details(), CONFIGURATION_CLIENTBOUND);
        this.configurationServerbound = networkIds(ConfigurationProtocols.SERVERBOUND_TEMPLATE.details(), CONFIGURATION_SERVERBOUND);
        this.playClientbound = networkIds(GameProtocols.CLIENTBOUND_TEMPLATE.details(), PLAY_CLIENTBOUND);
        this.playServerbound = networkIds(GameProtocols.SERVERBOUND_TEMPLATE.details(), PLAY_SERVERBOUND);
    }

    private static BitSet networkIds(ProtocolInfo.Details details, Set<PacketType<?>> relevantTypes) {
        BitSet ids = new BitSet();
        Set<PacketType<?>> found = new HashSet<>();
        details.listPackets((type, networkId) -> {
            if (relevantTypes.contains(type)) {
                ids.set(networkId);
                found.add(type);
            }
        });
        if (found.size() != relevantTypes.size()) {
            Set<PacketType<?>> missing = new HashSet<>(relevantTypes);
            missing.removeAll(found);
            throw new IllegalStateException("The " + details.id().id() + " " + details.flow().id() + " protocol has no packets " + missing);
        }
        return ids;
    }

    public boolean isRelevant(ProtocolPhase phase, PacketDirection direction, int packetId) {
        if (packetId < 0) {
            return false;
        }
        BitSet ids = switch (phase) {
            case CONFIGURATION -> direction == PacketDirection.CLIENTBOUND ? this.configurationClientbound : this.configurationServerbound;
            case PLAY -> direction == PacketDirection.CLIENTBOUND ? this.playClientbound : this.playServerbound;
        };
        return ids.get(packetId);
    }
}
