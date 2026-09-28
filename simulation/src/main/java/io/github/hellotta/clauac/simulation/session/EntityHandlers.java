package io.github.hellotta.clauac.simulation.session;

import com.mojang.logging.LogUtils;
import io.github.hellotta.clauac.simulation.player.SandboxPlayer;
import io.github.hellotta.clauac.simulation.player.SandboxPlayerInfo;
import io.github.hellotta.clauac.simulation.player.SandboxRemotePlayer;
import io.github.hellotta.clauac.simulation.world.SandboxLevel;
import java.util.OptionalInt;
import java.util.Set;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundHurtAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundMoveMinecartPacket;
import net.minecraft.network.protocol.game.ClientboundMoveVehiclePacket;
import net.minecraft.network.protocol.game.ClientboundProjectilePowerPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveMobEffectPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityLinkPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateMobEffectPacket;
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket;
import net.minecraft.network.protocol.game.VecDeltaCodec;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.hurtingprojectile.AbstractHurtingProjectile;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.entity.vehicle.minecart.NewMinecartBehavior;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

// - Port of the entity handlers of the client-only ClientPacketListener. The client keeps every entity the server -
// - shows it and ticks them itself, mostly interpolating between the server's positions; the sandbox does the same, -
// - because those entities push the player, carry it and block its way. Sounds, particles, the debug overlay and -
// - the renderer's bookkeeping are left out -
final class EntityHandlers {

    private static final Logger LOGGER = LogUtils.getLogger();
    // - Entity events the client consumes itself for sounds, particles or the totem animation, without passing -
    // - them on to the entity -
    private static final byte GUARDIAN_ATTACK_SOUND = 21;
    private static final byte TOTEM_OF_UNDYING = 35;
    private static final byte SNIFFER_DIGGING_SOUND = 63;

    private final PlayConnection connection;

    EntityHandlers(PlayConnection connection) {
        this.connection = connection;
    }

    void handleAddEntity(ClientboundAddEntityPacket packet, SandboxLevel level) {
        OptionalInt removedVehicle = this.connection.removedPlayerVehicleId();
        if (removedVehicle.isPresent() && removedVehicle.getAsInt() == packet.getId()) {
            this.connection.setRemovedPlayerVehicleId(OptionalInt.empty());
        }

        Entity entity = this.createEntityFromPacket(packet, level);
        if (entity != null) {
            entity.recreateFromPacket(packet);
            level.addEntity(entity);
        } else {
            LOGGER.warn("Skipping Entity with id {}", packet.getType());
        }
    }

    private @Nullable Entity createEntityFromPacket(ClientboundAddEntityPacket packet, SandboxLevel level) {
        EntityType<?> type = packet.getType();
        if (type == EntityTypes.PLAYER) {
            SandboxPlayerInfo playerInfo = this.connection.getPlayerInfo(packet.getUUID());
            if (playerInfo == null) {
                LOGGER.warn("Server attempted to add player prior to sending player info (Player id {})", packet.getUUID());
                return null;
            }
            return new SandboxRemotePlayer(level, playerInfo.getProfile(), this.connection);
        }
        // - EntityType.create with a reason leaves out an entity the level cannot spawn (EntityType.canSpawn): one whose -
        // - features are disabled, or a monster in peaceful difficulty -
        return type.create(level, EntitySpawnReason.LOAD);
    }

    static void handleSetEntityMotion(ClientboundSetEntityMotionPacket packet, SandboxLevel level) {
        Entity entity = level.getEntity(packet.id());
        if (entity != null) {
            entity.lerpMotion(packet.movement());
        }
    }

    static void handleSetEntityData(ClientboundSetEntityDataPacket packet, SandboxLevel level) {
        Entity entity = level.getEntity(packet.id());
        if (entity != null) {
            entity.getEntityData().assignValues(packet.packedItems());
        }
    }

    static void handleEntityPositionSync(ClientboundEntityPositionSyncPacket packet, SandboxLevel level, SandboxPlayer player) {
        Entity entity = level.getEntity(packet.id());
        if (entity != null) {
            Vec3 pos = packet.values().position();
            entity.getPositionCodec().setBase(pos);
            if (!entity.isLocalInstanceAuthoritative()) {
                float yRot = packet.values().yRot();
                float xRot = packet.values().xRot();
                boolean tooBigToInterpolate = entity.position().distanceToSqr(pos) > 4096.0;
                if (level.isTickingEntity(entity) && !tooBigToInterpolate) {
                    entity.moveOrInterpolateTo(pos, yRot, xRot);
                } else {
                    entity.snapTo(pos, yRot, xRot);
                }

                if (!entity.isInterpolating() && entity.hasIndirectPassenger(player)) {
                    entity.positionRider(player);
                    player.setOldPosAndRot();
                }

                entity.setOnGround(packet.onGround());
            }
        }
    }

    // - The player's own vehicle can be removed while the server still moves the player with it; the client then -
    // - applies that vehicle's teleports to the player and answers each with the player's resulting position -
    void handleTeleportEntity(ClientboundTeleportEntityPacket packet, SandboxLevel level, SandboxPlayer player) {
        Entity entity = level.getEntity(packet.id());
        if (entity == null) {
            OptionalInt removedVehicle = this.connection.removedPlayerVehicleId();
            if (removedVehicle.isPresent() && removedVehicle.getAsInt() == packet.id()) {
                LOGGER.debug("Trying to teleport entity with id {}, that was formerly player vehicle, applying teleport to player instead", packet.id());
                setValuesFromPositionPacket(packet.change(), packet.relatives(), player, false);
                this.connection.takeRemovedVehicleTeleportAnswer();
            }
        } else {
            boolean hasRelative = packet.relatives().contains(Relative.X) || packet.relatives().contains(Relative.Y) || packet.relatives().contains(Relative.Z);
            boolean interpolate = level.isTickingEntity(entity) || !entity.isLocalInstanceAuthoritative() || hasRelative;
            boolean wasInterpolated = setValuesFromPositionPacket(packet.change(), packet.relatives(), entity, interpolate);
            entity.setOnGround(packet.onGround());
            if (!wasInterpolated && entity.hasIndirectPassenger(player)) {
                entity.positionRider(player);
                player.setOldPosAndRot();
                // - The client answers with the vehicle's position when it steers the vehicle; the answer only goes -
                // - to the server -
            }
        }
    }

    // - ClientPacketListener.setValuesFromPositionPacket -
    static boolean setValuesFromPositionPacket(PositionMoveRotation change, Set<Relative> relatives, Entity entity, boolean interpolate) {
        PositionMoveRotation currentValues = PositionMoveRotation.of(entity);
        PositionMoveRotation newValues = PositionMoveRotation.calculateAbsolute(currentValues, change, relatives);
        boolean tooBigToInterpolate = currentValues.position().distanceToSqr(newValues.position()) > 4096.0;
        if (interpolate && !tooBigToInterpolate) {
            entity.moveOrInterpolateTo(newValues.position(), newValues.yRot(), newValues.xRot());
            entity.setDeltaMovement(newValues.deltaMovement());
            return true;
        }
        entity.setPos(newValues.position());
        entity.setDeltaMovement(newValues.deltaMovement());
        entity.setYRot(newValues.yRot());
        entity.setXRot(newValues.xRot());
        PositionMoveRotation currentInterpolationValues = new PositionMoveRotation(entity.oldPosition(), Vec3.ZERO, entity.yRotO, entity.xRotO);
        PositionMoveRotation interpolationValues = PositionMoveRotation.calculateAbsolute(currentInterpolationValues, change, relatives);
        entity.setOldPosAndRot(interpolationValues.position(), interpolationValues.yRot(), interpolationValues.xRot());
        return false;
    }

    static void handleMoveEntity(ClientboundMoveEntityPacket packet, SandboxLevel level) {
        Entity entity = packet.getEntity(level);
        if (entity != null) {
            if (entity.isLocalInstanceAuthoritative()) {
                VecDeltaCodec positionCodec = entity.getPositionCodec();
                Vec3 pos = positionCodec.decode(packet.getXa(), packet.getYa(), packet.getZa());
                positionCodec.setBase(pos);
            } else {
                if (packet.hasPosition()) {
                    VecDeltaCodec positionCodec = entity.getPositionCodec();
                    Vec3 pos = positionCodec.decode(packet.getXa(), packet.getYa(), packet.getZa());
                    positionCodec.setBase(pos);
                    if (packet.hasRotation()) {
                        entity.moveOrInterpolateTo(pos, packet.getYRot(), packet.getXRot());
                    } else {
                        entity.moveOrInterpolateTo(pos);
                    }
                } else if (packet.hasRotation()) {
                    entity.moveOrInterpolateTo(packet.getYRot(), packet.getXRot());
                }

                entity.setOnGround(packet.isOnGround());
            }
        }
    }

    static void handleMinecartAlongTrack(ClientboundMoveMinecartPacket packet, SandboxLevel level) {
        if (packet.getEntity(level) instanceof AbstractMinecart minecart && minecart.getBehavior() instanceof NewMinecartBehavior newMinecartBehavior) {
            newMinecartBehavior.lerpSteps.addAll(packet.lerpSteps());
        }
    }

    static void handleRotateMob(ClientboundRotateHeadPacket packet, SandboxLevel level) {
        Entity entity = packet.getEntity(level);
        if (entity != null) {
            entity.lerpHeadTo(packet.getYHeadRot(), 3);
        }
    }

    void handleRemoveEntities(ClientboundRemoveEntitiesPacket packet, SandboxLevel level, SandboxPlayer player) {
        packet.getEntityIds().forEach(entityId -> {
            Entity entity = level.getEntity(entityId);
            if (entity != null) {
                if (entity.hasIndirectPassenger(player)) {
                    LOGGER.debug("Remove entity {}:{} that has player as passenger", entity.typeHolder().getRegisteredName(), entityId);
                    this.connection.setRemovedPlayerVehicleId(OptionalInt.of(entityId));
                }

                level.removeEntity(entityId, Entity.RemovalReason.DISCARDED);
            }
        });
    }

    void handleSetEntityPassengersPacket(ClientboundSetPassengersPacket packet, SandboxLevel level, SandboxPlayer player) {
        Entity vehicle = level.getEntity(packet.getVehicle());
        if (vehicle == null) {
            LOGGER.warn("Received passengers for unknown entity");
            return;
        }
        boolean wasPlayerMounted = vehicle.hasIndirectPassenger(player);
        vehicle.ejectPassengers();

        for (int id : packet.getPassengers()) {
            Entity passenger = level.getEntity(id);
            if (passenger != null) {
                passenger.startRiding(vehicle, true, false);
                if (passenger == player) {
                    this.connection.setRemovedPlayerVehicleId(OptionalInt.empty());
                    if (!wasPlayerMounted && vehicle instanceof AbstractBoat) {
                        player.yRotO = vehicle.getYRot();
                        player.setYRot(vehicle.getYRot());
                        player.setYHeadRot(vehicle.getYRot());
                    }
                }
            }
        }
    }

    static void handleEntityLinkPacket(ClientboundSetEntityLinkPacket packet, SandboxLevel level) {
        if (level.getEntity(packet.getSourceId()) instanceof Leashable leashable) {
            leashable.setDelayedLeashHolderId(packet.getDestId());
        }
    }

    static void handleEntityEvent(ClientboundEntityEventPacket packet, SandboxLevel level) {
        Entity entity = packet.getEntity(level);
        if (entity != null) {
            byte eventId = packet.getEventId();
            if (eventId != GUARDIAN_ATTACK_SOUND && eventId != TOTEM_OF_UNDYING && eventId != SNIFFER_DIGGING_SOUND) {
                entity.handleEntityEvent(eventId);
            }
        }
    }

    static void handleDamageEvent(ClientboundDamageEventPacket packet, SandboxLevel level) {
        Entity entity = level.getEntity(packet.entityId());
        if (entity != null) {
            entity.handleDamageEvent(packet.getSource(level));
        }
    }

    static void handleHurtAnimation(ClientboundHurtAnimationPacket packet, SandboxLevel level) {
        Entity entity = level.getEntity(packet.id());
        if (entity != null) {
            entity.animateHurt(packet.yaw());
        }
    }

    // - Swinging a hand and waking up change an entity; the critical hits spawn particles. Returns the hand the -
    // - entity swung, null when it swung none -
    static @Nullable InteractionHand handleAnimate(ClientboundAnimatePacket packet, SandboxLevel level) {
        Entity entity = level.getEntity(packet.getId());
        if (entity != null) {
            if (packet.getAction() == ClientboundAnimatePacket.SWING_MAIN_HAND) {
                LivingEntity mob = (LivingEntity) entity;
                mob.swing(InteractionHand.MAIN_HAND);
                return InteractionHand.MAIN_HAND;
            } else if (packet.getAction() == ClientboundAnimatePacket.SWING_OFF_HAND) {
                LivingEntity mob = (LivingEntity) entity;
                mob.swing(InteractionHand.OFF_HAND);
                return InteractionHand.OFF_HAND;
            } else if (packet.getAction() == ClientboundAnimatePacket.WAKE_UP) {
                Player player = (Player) entity;
                player.stopSleepInBed(false, false);
            }
        }
        return null;
    }

    static void handleTakeItemEntity(ClientboundTakeItemEntityPacket packet, SandboxLevel level) {
        Entity from = level.getEntity(packet.getItemId());
        if (from != null) {
            if (from instanceof ItemEntity itemEntity) {
                ItemStack itemStack = itemEntity.getItem();
                if (!itemStack.isEmpty()) {
                    itemStack.shrink(packet.getAmount());
                }

                if (itemStack.isEmpty()) {
                    level.removeEntity(packet.getItemId(), Entity.RemovalReason.DISCARDED);
                }
            } else if (!(from instanceof ExperienceOrb)) {
                level.removeEntity(packet.getItemId(), Entity.RemovalReason.DISCARDED);
            }
        }
    }

    static void handleSetEquipment(ClientboundSetEquipmentPacket packet, SandboxLevel level) {
        if (level.getEntity(packet.getEntity()) instanceof LivingEntity livingEntity) {
            packet.getSlots().forEach(slot -> livingEntity.setItemSlot(slot.getFirst(), slot.getSecond()));
        }
    }

    static void handleUpdateAttributes(ClientboundUpdateAttributesPacket packet, SandboxLevel level) {
        Entity entity = level.getEntity(packet.getEntityId());
        if (entity != null) {
            if (!(entity instanceof LivingEntity livingEntity)) {
                throw new IllegalStateException("Server tried to update attributes of a non-living entity (actually: " + entity + ")");
            }
            AttributeMap attributes = livingEntity.getAttributes();

            for (ClientboundUpdateAttributesPacket.AttributeSnapshot attribute : packet.getValues()) {
                AttributeInstance instance = attributes.getInstance(attribute.attribute());
                if (instance == null) {
                    LOGGER.warn("Entity {} does not have attribute {}", entity, attribute.attribute().getRegisteredName());
                } else {
                    instance.setBaseValue(attribute.base());
                    instance.removeModifiers();

                    for (AttributeModifier modifier : attribute.modifiers()) {
                        instance.addTransientModifier(modifier);
                    }
                }
            }
        }
    }

    static void handleUpdateMobEffect(ClientboundUpdateMobEffectPacket packet, SandboxLevel level) {
        if (level.getEntity(packet.getEntityId()) instanceof LivingEntity livingEntity) {
            MobEffectInstance mobEffectInstance = new MobEffectInstance(
                    packet.getEffect(),
                    packet.getEffectDurationTicks(),
                    packet.getEffectAmplifier(),
                    packet.isEffectAmbient(),
                    packet.isEffectVisible(),
                    packet.effectShowsIcon(),
                    null
            );
            if (!packet.shouldBlend()) {
                mobEffectInstance.skipBlending();
            }

            livingEntity.forceAddEffect(mobEffectInstance, null);
        }
    }

    static void handleRemoveMobEffect(ClientboundRemoveMobEffectPacket packet, SandboxLevel level) {
        if (packet.getEntity(level) instanceof LivingEntity entity) {
            entity.removeEffectNoUpdate(packet.effect());
        }
    }

    // - The server corrects the vehicle the player steers; the client answers right away with the vehicle's position -
    // - (ClientPacketListener.handleMoveVehicle), which is returned for the caller to check against the client's -
    // - answer. Null when the player steers no vehicle and the client ignores the correction -
    static @Nullable ServerboundMoveVehiclePacket handleMoveVehicle(ClientboundMoveVehiclePacket packet, SandboxPlayer player) {
        Entity vehicle = player.getRootVehicle();
        if (vehicle != player && vehicle.isLocalInstanceAuthoritative()) {
            Vec3 target = packet.position();
            Vec3 currentTarget;
            if (vehicle.isInterpolating()) {
                currentTarget = vehicle.getInterpolation().position();
            } else {
                currentTarget = vehicle.position();
            }

            if (target.distanceTo(currentTarget) > 1.0E-5F) {
                if (vehicle.isInterpolating()) {
                    vehicle.getInterpolation().cancel();
                }

                vehicle.absSnapTo(target.x(), target.y(), target.z(), packet.yRot(), packet.xRot());
            }

            return ServerboundMoveVehiclePacket.fromEntity(vehicle);
        }
        return null;
    }

    static void handleProjectilePowerPacket(ClientboundProjectilePowerPacket packet, SandboxLevel level) {
        if (level.getEntity(packet.getId()) instanceof AbstractHurtingProjectile projectile) {
            projectile.accelerationPower = packet.getAccelerationPower();
        }
    }
}
