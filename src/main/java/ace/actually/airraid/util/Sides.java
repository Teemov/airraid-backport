package ace.actually.airraid.util;

import ace.actually.airraid.config.IAAddonConfig;
import immersive_aircraft.entity.VehicleEntity;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.raid.Raider;

/**
 * Which side a mob flies for.
 * Attackers: illagers (any {@link Raider}, which includes witches) and any mob listed in an attacker faction.
 * Defenders: mobs listed in {@code guardConfig.pilotMobs} (Guard Villagers' guards by default).
 */
public final class Sides {

    /** Tag put on aircraft spawned for the defenders, so only those are ever auto-despawned. */
    public static final String DEFENDER_VEHICLE_TAG = "airraid_defender";
    /** Persistent-data key holding a defender aircraft's home (village bell) position. */
    public static final String HOME_KEY = "airraid_home";

    public enum Side { ATTACKER, DEFENDER, NONE }

    private Sides() {
    }

    public static Side of(Entity entity) {
        if (entity == null) return Side.NONE;
        String id = idOf(entity);
        if (IAAddonConfig.INSTANCE.guardConfig.pilotMobs.contains(id)) return Side.DEFENDER;
        if (entity instanceof Raider) return Side.ATTACKER;
        if (IAAddonConfig.INSTANCE.raidConfig.mobs.contains(id)) return Side.ATTACKER;
        for (IAAddonConfig.Faction f : IAAddonConfig.INSTANCE.factions) {
            if (f.mobs.contains(id)) return Side.ATTACKER;
        }
        return Side.NONE;
    }

    public static boolean isDefender(Entity entity) {
        return of(entity) == Side.DEFENDER;
    }

    /** The side flying a vehicle, judged by its pilot. */
    public static Side ofVehicle(Entity vehicle) {
        return vehicle instanceof VehicleEntity v ? of(v.getFirstPassenger()) : Side.NONE;
    }

    /** True if {@code e} (or anyone riding it) is on the defenders' side: players, villagers, golems, guards. */
    public static boolean isFriendlyToDefenders(Entity e) {
        if (e instanceof Player || e instanceof AbstractVillager || e instanceof IronGolem || isDefender(e)) return true;
        if (e instanceof VehicleEntity) {
            for (Entity p : e.getPassengers()) {
                if (isFriendlyToDefenders(p)) return true;
            }
        }
        return false;
    }

    /** True if {@code e} (or anyone riding it) is on the attackers' side. */
    public static boolean isFriendlyToAttackers(Entity e) {
        if (of(e) == Side.ATTACKER) return true;
        if (e instanceof VehicleEntity) {
            for (Entity p : e.getPassengers()) {
                if (of(p) == Side.ATTACKER) return true;
            }
        }
        return false;
    }

    /** Whether a target the mob's own AI picked is acceptable for an aircraft on this side. */
    public static boolean isValidTarget(Side side, LivingEntity target) {
        if (target == null || !target.isAlive()) return false;
        if (target instanceof Player p && (p.isSpectator() || p.isCreative())) return false;
        if (side == Side.DEFENDER) return !isFriendlyToDefenders(target);
        if (side == Side.ATTACKER) return !isFriendlyToAttackers(target);
        return true;
    }

    private static String idOf(Entity entity) {
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        return id == null ? "" : id.toString();
    }
}
