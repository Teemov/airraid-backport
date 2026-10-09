package ace.actually.airraid.util;

import ace.actually.airraid.config.IAAddonConfig;
import immersive_aircraft.entity.VehicleEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class WorldTickHandler {
    private static int ticks = 0;

    private WorldTickHandler() {
    }

    public static void onServerTick(MinecraftServer server) {
        if (++ticks < IAAddonConfig.INSTANCE.checkIntervalTicks) return;
        ticks = 0;

        for (ServerLevel level : server.getAllLevels()) {
            // copy: spawning below must not touch the list we're iterating
            for (ServerPlayer player : new ArrayList<>(level.players())) {
                if (player.isSpectator() || player.isCreative()) continue;

                for (IAAddonConfig.Faction faction : IAAddonConfig.INSTANCE.factions) {

                    // A. Ambush check (flying squadron)
                    if (faction.ambushChance > 0 && level.random.nextFloat() < faction.ambushChance) {
                        RaidSpawner.spawnAmbush(level, player.position(), faction);
                        continue;
                    }

                    // B. Structure check
                    if (faction.structures.isEmpty()) continue;
                    if (faction.structureSpawnChance <= 0 && (faction.parkedVehicleChance <= 0 || faction.maxParkedVehicles <= 0)) continue;

                    BlockPos structurePos = getNearestStructurePos(level, player.blockPosition(), faction);
                    if (structurePos == null) continue;

                    // 1. Defensive squadron (flying)
                    if (faction.structureSpawnChance > 0 && level.random.nextFloat() < faction.structureSpawnChance) {
                        RaidSpawner.spawnAmbush(level, player.position(), faction);
                    }

                    // 2. Parked vehicles (ground)
                    if (faction.parkedVehicleChance > 0 && faction.maxParkedVehicles > 0
                            && level.random.nextFloat() < faction.parkedVehicleChance) {
                        AABB area = new AABB(
                                structurePos.getX() - 50, level.getMinBuildHeight(), structurePos.getZ() - 50,
                                structurePos.getX() + 50, level.getMaxBuildHeight(), structurePos.getZ() + 50);
                        int existing = level.getEntitiesOfClass(VehicleEntity.class, area, e -> true).size();

                        if (existing < faction.maxParkedVehicles) {
                            RaidSpawner.spawnParkedAt(level, structurePos, faction);
                        }
                    }
                }
            }
        }
    }

    /**
     * Finds a configured structure whose start lies within {@code structureSearchRadius} blocks (horizontally).
     * Entries may be structure ids ("minecraft:pillager_outpost") or structure tags ("#minecraft:village").
     * <p>
     * Only chunks that are already loaded are looked at, so this never generates or loads terrain
     * (the 1.1.0 version used the /locate machinery here, which could stall the server thread).
     */
    private static BlockPos getNearestStructurePos(ServerLevel level, BlockPos pos, IAAddonConfig.Faction faction) {
        Set<Structure> wanted = resolveStructures(level, faction.structures);
        if (wanted.isEmpty()) return null;

        int radiusBlocks = IAAddonConfig.INSTANCE.structureSearchRadius;
        int radiusChunks = Math.max(1, (radiusBlocks + 15) >> 4);
        int cx = pos.getX() >> 4;
        int cz = pos.getZ() >> 4;

        BlockPos best = null;
        double bestDist = (double) radiusBlocks * radiusBlocks;

        for (int x = cx - radiusChunks; x <= cx + radiusChunks; x++) {
            for (int z = cz - radiusChunks; z <= cz + radiusChunks; z++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(x, z);
                if (chunk == null) continue; // not loaded: skip, never load it

                for (Map.Entry<Structure, StructureStart> e : chunk.getAllStarts().entrySet()) {
                    if (!wanted.contains(e.getKey()) || !e.getValue().isValid()) continue;

                    BlockPos center = e.getValue().getBoundingBox().getCenter();
                    double ddx = center.getX() - pos.getX();
                    double ddz = center.getZ() - pos.getZ();
                    double d = ddx * ddx + ddz * ddz;
                    if (d < bestDist) {
                        bestDist = d;
                        best = center;
                    }
                }
            }
        }
        return best;
    }

    private static Set<Structure> resolveStructures(ServerLevel level, List<String> entries) {
        Registry<Structure> registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        Set<Structure> out = new HashSet<>();
        for (String entry : entries) {
            if (entry == null || entry.isBlank()) continue;
            if (entry.startsWith("#")) {
                ResourceLocation tagId = ResourceLocation.tryParse(entry.substring(1));
                if (tagId == null) continue;
                registry.getTag(TagKey.create(Registries.STRUCTURE, tagId))
                        .ifPresent(tag -> tag.forEach(h -> out.add(h.value())));
            } else {
                ResourceLocation id = ResourceLocation.tryParse(entry);
                if (id == null) continue;
                Structure s = registry.get(id);
                if (s != null) out.add(s);
            }
        }
        return out;
    }
}
