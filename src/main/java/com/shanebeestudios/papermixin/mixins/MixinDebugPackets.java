/*
 * This file is part of Ignite, licensed under the MIT License (MIT).
 *
 * Copyright (c) vectrix.space <https://vectrix.space/>
 * Copyright (c) contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.shanebeestudios.papermixin.mixins;

import it.unimi.dsi.fastutil.objects.Object2IntMap.Entry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.common.custom.BrainDebugPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.GoalDebugPayload;
import net.minecraft.network.protocol.common.custom.GoalDebugPayload.DebugGoal;
import net.minecraft.network.protocol.common.custom.NeighborUpdatesDebugPayload;
import net.minecraft.network.protocol.common.custom.PathfindingDebugPayload;
import net.minecraft.network.protocol.common.custom.PoiAddedDebugPayload;
import net.minecraft.network.protocol.common.custom.PoiRemovedDebugPayload;
import net.minecraft.network.protocol.common.custom.PoiTicketCountDebugPayload;
import net.minecraft.network.protocol.common.custom.StructuresDebugPayload;
import net.minecraft.network.protocol.common.custom.VillageSectionsDebugPayload;
import net.minecraft.network.protocol.common.custom.WorldGenAttemptDebugPayload;
import net.minecraft.network.protocol.game.DebugEntityNameGenerator;
import net.minecraft.network.protocol.game.DebugPackets;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.gossip.GossipType;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.pathfinder.Path;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@SuppressWarnings({"deprecation", "DataFlowIssue", "unused"})
@Mixin(DebugPackets.class)
public abstract class MixinDebugPackets {
    @Shadow
    private static void sendPacketToAllPlayers(ServerLevel level, CustomPacketPayload payload) {
    }

    @Shadow
    private static List<String> getMemoryDescriptions(LivingEntity entity, long currentTime) {
        return null;
    }

    @Overwrite
    public static void sendPathFindingPacket(Level level, Mob mob, Path path, float nodeReachProximity) {
        if (path != null) {
            sendPacketToAllPlayers((ServerLevel) level, new PathfindingDebugPayload(mob.getId(), path, nodeReachProximity));
        }
    }

    @Overwrite
    public static void sendNeighborsUpdatePacket(Level level, BlockPos pos) {
        if (!level.isClientSide()) {
            sendPacketToAllPlayers((ServerLevel) level, new NeighborUpdatesDebugPayload(level.getGameTime(), pos));
        }
    }

    @Overwrite
    public static void sendStructurePacket(WorldGenLevel worldGenLevel, StructureStart structureStart) {
        List<StructuresDebugPayload.PieceInfo> pieces = new ArrayList<>();

        boolean isStart = true;
        for (StructurePiece piece : structureStart.getPieces()) {
            pieces.add(new StructuresDebugPayload.PieceInfo(piece.getBoundingBox(), isStart));
            isStart = false;
        }

        ServerLevel serverWorld = worldGenLevel.getLevel();
        sendPacketToAllPlayers(serverWorld, new StructuresDebugPayload(serverWorld.dimension(), structureStart.getBoundingBox(), pieces));
    }

    @Overwrite
    public static void sendGoalSelector(Level world, Mob mob, GoalSelector goalSelector) {
        List<DebugGoal> goals = goalSelector.getAvailableGoals().stream().map(goal -> new DebugGoal(goal.getPriority(), goal.isRunning(), goal.getGoal().toString())).toList();
        sendPacketToAllPlayers((ServerLevel) world, new GoalDebugPayload(mob.getId(), mob.blockPosition(), goals));
    }

    @Overwrite
    public static void sendPoiPacketsForChunk(ServerLevel level, ChunkPos pos) {
        sendPacketToAllPlayers(level, new WorldGenAttemptDebugPayload(pos.getWorldPosition().above(100), 1.0F, 1.0F, 1.0F, 1.0F, 1.0F));
        level.getPoiManager().getInChunk(poi -> true, pos, PoiManager.Occupancy.ANY).forEach(poiRecord -> {
            BlockPos blockPos = poiRecord.getPos();
            sendPoiAddedPacket(level, blockPos);
        });
    }

    @Overwrite
    public static void sendPoiAddedPacket(ServerLevel level, BlockPos pos) {
        level.getPoiManager().getType(pos).ifPresent(poiTypeHolder -> {
            int freeTickets = level.getPoiManager().getFreeTickets(pos);
            String name = poiTypeHolder.getRegisteredName();
            sendPacketToAllPlayers(level, new PoiAddedDebugPayload(pos, name, freeTickets));
        });
        sendVillageSectionsPacket(level, pos);
    }

    @Overwrite
    public static void sendPoiRemovedPacket(ServerLevel level, BlockPos pos) {
        sendPacketToAllPlayers(level, new PoiRemovedDebugPayload(pos));
        sendVillageSectionsPacket(level, pos);
    }

    @Overwrite
    public static void sendPoiTicketCountPacket(ServerLevel level, BlockPos pos) {
        sendPacketToAllPlayers(level, new PoiTicketCountDebugPayload(pos, level.getPoiManager().getFreeTickets(pos)));
        sendVillageSectionsPacket(level, pos);
    }

    @Overwrite
    public static void sendVillageSectionsPacket(ServerLevel world, BlockPos pos) {
        Registry<Structure> registry = world.registryAccess().registryOrThrow(Registries.STRUCTURE);
        SectionPos chunkSectionPos = SectionPos.of(pos);
        Iterator<Holder<Structure>> structureIterator = registry.getTagOrEmpty(StructureTags.VILLAGE).iterator();

        Holder<Structure> entry;
        do {
            if (!structureIterator.hasNext()) {
                sendPacketToAllPlayers(world, new VillageSectionsDebugPayload(Set.of(), Set.of(chunkSectionPos)));
                return;
            }

            entry = structureIterator.next();
        } while (world.structureManager().startsForStructure(chunkSectionPos, entry.value()).isEmpty());

        sendPacketToAllPlayers(world, new VillageSectionsDebugPayload(Set.of(chunkSectionPos), Set.of()));
    }

    @Overwrite
    public static void sendEntityBrain(LivingEntity livingEntity) {
        Mob entity = (Mob) livingEntity;
        ServerLevel serverLevel = (ServerLevel) entity.level();
        int angerLevel;
        if (entity instanceof Warden wardenEntity) {
            angerLevel = wardenEntity.getClientAngerLevel();
        } else {
            angerLevel = -1;
        }

        List<String> gossips = new ArrayList<>();
        Set<BlockPos> pois = new HashSet<>();
        Set<BlockPos> potentialPois = new HashSet<>();
        String profession = "";
        int xp = 0;
        String inventory = "";
        boolean wantsGolem = false;
        if (entity instanceof Villager villager) {
            profession = villager.getVillagerData().getProfession().toString();
            xp = villager.getVillagerXp();
            inventory = villager.getInventory().toString();
            wantsGolem = villager.wantsToSpawnGolem(serverLevel.getGameTime());
            villager.getGossips().getGossipEntries().forEach((uuid, associatedGossip) -> {
                Entity gossipEntity = serverLevel.getEntity(uuid);
                if (gossipEntity != null) {
                    String name = DebugEntityNameGenerator.getEntityName(gossipEntity);

                    for (Entry<GossipType> gossipTypeEntry : associatedGossip.object2IntEntrySet()) {
                        gossips.add(name + ": " + gossipTypeEntry.getKey().getSerializedName() + " " + gossipTypeEntry.getValue());
                    }
                }

            });
            Brain<?> brain = villager.getBrain();
            addPoi(brain, MemoryModuleType.HOME, pois);
            addPoi(brain, MemoryModuleType.JOB_SITE, pois);
            addPoi(brain, MemoryModuleType.MEETING_POINT, pois);
            addPoi(brain, MemoryModuleType.HIDING_PLACE, pois);
            addPoi(brain, MemoryModuleType.POTENTIAL_JOB_SITE, potentialPois);
        }

        sendPacketToAllPlayers(serverLevel, new BrainDebugPayload(new BrainDebugPayload.BrainDump(entity.getUUID(), entity.getId(), entity.getName().getString(), profession, xp, entity.getHealth(), entity.getMaxHealth(), entity.position(), inventory, entity.getNavigation().getPath(), wantsGolem, angerLevel, entity.getBrain().getActiveActivities().stream().map(Activity::toString).toList(), entity.getBrain().getRunningBehaviors().stream().map(BehaviorControl::debugString).toList(), getMemoryDescriptions(entity, serverLevel.getGameTime()), gossips, pois, potentialPois)));
    }

    @Unique
    private static void addPoi(Brain<?> brain, MemoryModuleType<GlobalPos> memory, Set<BlockPos> pois) {
        Optional<BlockPos> blockPos = brain.getMemory(memory).map(GlobalPos::pos);
        Objects.requireNonNull(pois);
        blockPos.ifPresent(pois::add);
    }
}
