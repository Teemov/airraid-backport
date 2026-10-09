package ace.actually.airraid.util;

import ace.actually.airraid.config.IAAddonConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.entity.raid.Raider;

import java.util.*;

/**
 * Calls in air support when a new raid wave arrives.
 * <p>
 * The Fabric original hooked {@code Raid.spawnGroup} with a mixin. Here we watch raiders joining the
 * world instead: vanilla tags each wave member with its raid and wave number before adding it, so the
 * first raider of a new wave tells us the wave started and where. The actual spawn is deferred to the
 * next server tick, since adding entities from inside an entity-join callback is unsafe.
 */
public final class RaidAirSupport {

    private record Pending(ServerLevel level, BlockPos pos, int wave) {
    }

    /** Highest wave already handled, per raid (keyed by dimension + raid id). */
    private static final Map<String, Integer> HANDLED_WAVES = new HashMap<>();
    private static final List<Pending> PENDING = new ArrayList<>();

    private RaidAirSupport() {
    }

    public static void onRaiderJoined(ServerLevel level, Raider raider) {
        Raid raid = raider.getCurrentRaid();
        if (raid == null) return;

        int wave = raider.getWave();
        String key = level.dimension().location() + "#" + raid.getId();
        Integer handled = HANDLED_WAVES.get(key);
        if (handled != null && handled >= wave) return;
        HANDLED_WAVES.put(key, wave);

        PENDING.add(new Pending(level, raider.blockPosition(), wave));
    }

    public static void tick() {
        if (PENDING.isEmpty()) return;
        List<Pending> batch = new ArrayList<>(PENDING);
        PENDING.clear();

        IAAddonConfig.RaidConfig config = IAAddonConfig.INSTANCE.raidConfig;

        for (Pending p : batch) {
            // The village may send up its own guards (independent of illager air support)
            GuardAirDefense.onRaidWave(p.level(), p.pos(), p.wave());

            if (!config.enabled) continue;
            if (p.wave() < config.startWave) continue;
            if (p.level().random.nextFloat() >= config.spawnChancePerWave) continue;

            IAAddonConfig.Faction raidFaction = new IAAddonConfig.Faction();
            raidFaction.vehicles = config.vehicles;
            raidFaction.mobs = config.mobs;
            raidFaction.weaponAmmoCounts = config.weaponAmmoCounts;
            raidFaction.enableCrossbows = config.enableCrossbows;
            raidFaction.enableRotaryCannons = config.enableRotaryCannons;
            raidFaction.enableBombBay = config.enableBombBay;
            raidFaction.minSquadSize = config.squadSize;
            raidFaction.maxSquadSize = config.squadSize;
            raidFaction.fuelItem = config.fuelItem;

            float yaw = p.level().random.nextFloat() * 360.0f;
            RaidSpawner.spawnSquadronAt(p.level(), p.pos().getX(), p.pos().getY() + 50, p.pos().getZ(), yaw, raidFaction);
        }
    }

    public static void clear() {
        HANDLED_WAVES.clear();
        PENDING.clear();
    }
}
