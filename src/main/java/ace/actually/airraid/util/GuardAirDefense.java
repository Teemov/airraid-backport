package ace.actually.airraid.util;

import ace.actually.airraid.config.IAAddonConfig;
import immersive_aircraft.entity.VehicleEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.fml.ModList;

import java.util.Comparator;
import java.util.Optional;

/**
 * Village air defence: guards (from Guard Villagers) take off in Immersive Aircraft to meet illager aircraft.
 * Interceptors launch from above the village bell and return to it when the sky is clear.
 */
public final class GuardAirDefense {

    private GuardAirDefense() {
    }

    /** Whether the guard side can run at all (feature enabled and its pilot mod installed). */
    public static boolean isAvailable() {
        IAAddonConfig.GuardConfig cfg = IAAddonConfig.INSTANCE.guardConfig;
        if (!cfg.enabled || cfg.pilotMobs.isEmpty()) return false;
        // Default pilots come from Guard Villagers; custom pilot lists may name other mods' mobs
        for (String id : cfg.pilotMobs) {
            int colon = id.indexOf(':');
            String modId = colon > 0 ? id.substring(0, colon) : "minecraft";
            if (modId.equals("minecraft") || ModList.get().isLoaded(modId)) return true;
        }
        return false;
    }

    /** An illager squadron just appeared at {@code threat}: maybe scramble the nearest village. */
    public static void onIllagerSquadron(ServerLevel level, BlockPos threat) {
        if (!isAvailable()) return;
        IAAddonConfig.GuardConfig cfg = IAAddonConfig.INSTANCE.guardConfig;
        if (level.random.nextFloat() >= cfg.scrambleChance) return;
        tryScramble(level, threat, false);
    }

    /** A raid wave started at {@code pos}. */
    public static void onRaidWave(ServerLevel level, BlockPos pos, int wave) {
        if (!isAvailable()) return;
        IAAddonConfig.GuardConfig cfg = IAAddonConfig.INSTANCE.guardConfig;
        if (wave < cfg.raidStartWave) return;
        if (level.random.nextFloat() >= cfg.raidScrambleChance) return;
        tryScramble(level, pos, false);
    }

    /**
     * Launches interceptors from the village nearest to {@code near}.
     *
     * @param force skip the "village must have guards" and per-village limit checks (used by the command)
     * @return true if a squadron was launched
     */
    public static boolean tryScramble(ServerLevel level, BlockPos near, boolean force) {
        IAAddonConfig.GuardConfig cfg = IAAddonConfig.INSTANCE.guardConfig;

        // Search from ground level: the threat itself is usually high in the air
        BlockPos ground = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, near);
        Optional<BlockPos> bell = findBell(level, ground, cfg.villageSearchRadius);
        if (bell.isEmpty()) return false;

        BlockPos home = bell.get();
        AABB village = new AABB(home).inflate(64.0, 48.0, 64.0);

        if (!force) {
            if (cfg.requireGuardsInVillage) {
                boolean hasGuards = !level.getEntitiesOfClass(Mob.class, village,
                        m -> m.isAlive() && !m.isPassenger() && Sides.isDefender(m)).isEmpty();
                if (!hasGuards) return false;
            }

            AABB airspace = new AABB(home).inflate(160.0, 256.0, 160.0);
            int active = level.getEntitiesOfClass(VehicleEntity.class, airspace,
                    v -> v.getTags().contains(Sides.DEFENDER_VEHICLE_TAG)).size();
            if (active >= cfg.maxAircraftPerVillage) return false;
        }

        launch(level, home, near, cfg.toFaction());
        return true;
    }

    /** Nearest village bell (meeting point) within {@code radius} blocks of {@code pos}. */
    public static Optional<BlockPos> findBell(ServerLevel level, BlockPos pos, int radius) {
        return level.getPoiManager()
                .getInRange(holder -> holder.is(PoiTypes.MEETING), pos, radius, PoiManager.Occupancy.ANY)
                .map(PoiRecord::getPos)
                .min(Comparator.comparingDouble(p -> p.distSqr(pos)));
    }

    /** Spawns a guard squadron above {@code home}, heading toward {@code toward}. */
    public static void launch(ServerLevel level, BlockPos home, BlockPos toward, IAAddonConfig.Faction guardFaction) {
        IAAddonConfig.GuardConfig cfg = IAAddonConfig.INSTANCE.guardConfig;
        guardFaction.defenders = true;
        guardFaction.home = home;

        int groundY = level.getHeight(Heightmap.Types.MOTION_BLOCKING, home.getX(), home.getZ());
        double y = Math.max(home.getY(), groundY) + cfg.launchHeight;

        double dx = toward.getX() - home.getX();
        double dz = toward.getZ() - home.getZ();
        float yaw = (dx == 0 && dz == 0)
                ? level.random.nextFloat() * 360.0f
                : (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0F);

        RaidSpawner.spawnSquadronAt(level, home.getX() + 0.5, y, home.getZ() + 0.5, yaw, guardFaction);
    }
}
