package ace.actually.airraid.config;

import ace.actually.airraid.AirRaidMod;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

public class IAAddonConfig {
    private static final File CONFIG_FILE = FMLPaths.CONFIGDIR.get().resolve("ia_pillager_addon.json").toFile();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static IAAddonConfig INSTANCE = new IAAddonConfig();

    public List<Faction> factions = new ArrayList<>();

    // Vanilla raid air support
    public RaidConfig raidConfig = new RaidConfig();

    // Guard Villagers air defence (only active when Guard Villagers is installed)
    public GuardConfig guardConfig = new GuardConfig();

    public int checkIntervalTicks = 200;
    public int minSpawnDistance = 60;
    public int spawnHeight = 40;

    /**
     * Damage multiplier for shells/arrows fired by AI aircrews when they hit an aircraft.
     * 1.0 = Immersive Aircraft default (6 cannon hits destroy a biplane); 0.35 = about 17 hits.
     */
    public float aiDamageToAircraftMultiplier = 0.35f;
    /** Ticks between rotary cannon shots for AI gunners. 4 = same rate as a player. */
    public int aiRotaryCannonFireInterval = 4;

    /** Remove arrows fired from AI-crewed aircraft once they land (they can't be picked up anyway). */
    public boolean cleanupAircraftArrows = true;
    /** Maximum lifetime (ticks) of those arrows even if they never land. 100 = 5 seconds. */
    public int aircraftArrowLifetimeTicks = 100;

    /** How far (in blocks) from a player a configured structure counts as "nearby". */
    public int structureSearchRadius = 100;

    public static class RaidConfig {
        public boolean enabled = true;
        public float spawnChancePerWave = 0.4f;
        public int startWave = 3;
        public int squadSize = 2;

        public Map<String, Integer> vehicles = new HashMap<>();
        public List<String> mobs = new ArrayList<>(List.of(
                "minecraft:pillager",
                "minecraft:vindicator"
        ));

        public boolean enableCrossbows = true;
        public boolean enableRotaryCannons = true;
        public boolean enableBombBay = true;

        public String fuelItem = "minecraft:coal";
        public Map<String, Integer> weaponAmmoCounts = new HashMap<>();

        public RaidConfig() {
            vehicles.put("immersive_aircraft:biplane", 60);
            vehicles.put("immersive_aircraft:gyrodyne", 40);

            weaponAmmoCounts.put("immersive_aircraft:rotary_cannon", 300);
            weaponAmmoCounts.put("immersive_aircraft:heavy_crossbow", 600);
            weaponAmmoCounts.put("immersive_aircraft:bomb_bay", 15);
        }
    }

    public static class GuardConfig {
        public boolean enabled = true;

        /** Mobs that fly for the villages. */
        public List<String> pilotMobs = new ArrayList<>(List.of("guardvillagers:guard"));

        /** Chance that a village scrambles interceptors when an illager squadron appears nearby. */
        public float scrambleChance = 0.75f;
        /** Chance per raid wave (from raidStartWave on) that the village scrambles interceptors. */
        public float raidScrambleChance = 0.5f;
        public int raidStartWave = 3;

        /** How far (blocks) from a threat to look for a village bell. */
        public int villageSearchRadius = 128;
        /** Only villages that already have at least one guard within 64 blocks of the bell scramble. */
        public boolean requireGuardsInVillage = true;
        /** Max guard aircraft around one village at the same time. */
        public int maxAircraftPerVillage = 4;

        public int minSquadSize = 1;
        public int maxSquadSize = 2;
        /** Height above the bell (and terrain) where interceptors take off. */
        public int launchHeight = 30;

        /** Guard aircraft with nothing to fight for this long, and no player within 48 blocks, are removed. */
        public int idleDespawnSeconds = 180;

        public Map<String, Integer> vehicles = new HashMap<>();
        public boolean enableCrossbows = true;
        public boolean enableRotaryCannons = true;
        public boolean enableBombBay = false; // bombs over your own village are a bad idea

        public String fuelItem = "minecraft:coal";
        public Map<String, Integer> weaponAmmoCounts = new HashMap<>();

        public GuardConfig() {
            vehicles.put("immersive_aircraft:biplane", 60);
            vehicles.put("immersive_aircraft:gyrodyne", 40);

            weaponAmmoCounts.put("immersive_aircraft:rotary_cannon", 300);
            weaponAmmoCounts.put("immersive_aircraft:heavy_crossbow", 600);
            weaponAmmoCounts.put("immersive_aircraft:bomb_bay", 10);
        }

        /** The guard settings as a spawnable faction. */
        public Faction toFaction() {
            Faction f = new Faction();
            f.name = "Village Guards";
            f.vehicles = vehicles;
            f.mobs = pilotMobs;
            f.weaponAmmoCounts = weaponAmmoCounts;
            f.enableCrossbows = enableCrossbows;
            f.enableRotaryCannons = enableRotaryCannons;
            f.enableBombBay = enableBombBay;
            f.minSquadSize = minSquadSize;
            f.maxSquadSize = maxSquadSize;
            f.fuelItem = fuelItem;
            f.structures = new ArrayList<>();
            f.defenders = true;
            return f;
        }
    }

    public static class Faction {
        /** Runtime-only (not saved): this squadron flies for the villages. */
        public transient boolean defenders = false;
        /** Runtime-only (not saved): where defender aircraft return to when idle. */
        public transient net.minecraft.core.BlockPos home = null;


        public String name = "Default Raiders";
        public float ambushChance = 0.01f;
        public float structureSpawnChance = 0.10f;

        public float parkedVehicleChance = 0.05f;
        public int maxParkedVehicles = 2;

        /** Structure ids (e.g. "minecraft:pillager_outpost") or structure tags prefixed with '#'. */
        public List<String> structures = new ArrayList<>(List.of("minecraft:pillager_outpost"));

        public List<String> mobs = new ArrayList<>(List.of(
                "minecraft:pillager",
                "minecraft:vindicator",
                "minecraft:witch"
        ));

        public Map<String, Integer> vehicles = new HashMap<>();
        public int minSquadSize = 2;
        public int maxSquadSize = 4;

        public boolean enableCrossbows = true;
        public boolean enableRotaryCannons = true;
        public boolean enableBombBay = false;

        public String fuelItem = "minecraft:coal";
        public Map<String, Integer> weaponAmmoCounts = new HashMap<>();

        public Faction() {
            vehicles.put("immersive_aircraft:biplane", 50);
            vehicles.put("immersive_aircraft:airship", 30);
            vehicles.put("immersive_aircraft:gyrodyne", 10);
            vehicles.put("immersive_aircraft:quadrocopter", 5);

            weaponAmmoCounts.put("immersive_aircraft:rotary_cannon", 300);
            weaponAmmoCounts.put("immersive_aircraft:heavy_crossbow", 600);
            weaponAmmoCounts.put("immersive_aircraft:bomb_bay", 10);
        }

        /** Fills in anything a hand-edited config left out, so the rest of the mod never sees nulls. */
        void sanitize() {
            Faction d = new Faction();
            if (name == null) name = d.name;
            if (structures == null) structures = new ArrayList<>();
            if (mobs == null) mobs = d.mobs;
            if (vehicles == null) vehicles = d.vehicles;
            if (weaponAmmoCounts == null) weaponAmmoCounts = d.weaponAmmoCounts;
            if (fuelItem == null) fuelItem = d.fuelItem;
            minSquadSize = Math.max(1, minSquadSize);
            maxSquadSize = Math.max(minSquadSize, maxSquadSize);
        }
    }

    private void sanitize() {
        if (factions == null) factions = new ArrayList<>();
        factions.removeIf(Objects::isNull);
        factions.forEach(Faction::sanitize);

        RaidConfig d = new RaidConfig();
        if (raidConfig == null) raidConfig = d;
        if (raidConfig.vehicles == null) raidConfig.vehicles = d.vehicles;
        if (raidConfig.mobs == null) raidConfig.mobs = d.mobs;
        if (raidConfig.weaponAmmoCounts == null) raidConfig.weaponAmmoCounts = d.weaponAmmoCounts;
        if (raidConfig.fuelItem == null) raidConfig.fuelItem = d.fuelItem;

        GuardConfig g = new GuardConfig();
        if (guardConfig == null) guardConfig = g;
        if (guardConfig.pilotMobs == null) guardConfig.pilotMobs = g.pilotMobs;
        if (guardConfig.vehicles == null) guardConfig.vehicles = g.vehicles;
        if (guardConfig.weaponAmmoCounts == null) guardConfig.weaponAmmoCounts = g.weaponAmmoCounts;
        if (guardConfig.fuelItem == null) guardConfig.fuelItem = g.fuelItem;
        guardConfig.minSquadSize = Math.max(1, guardConfig.minSquadSize);
        guardConfig.maxSquadSize = Math.max(guardConfig.minSquadSize, guardConfig.maxSquadSize);
        if (guardConfig.villageSearchRadius <= 0) guardConfig.villageSearchRadius = g.villageSearchRadius;
        raidConfig.squadSize = Math.max(1, raidConfig.squadSize);

        checkIntervalTicks = Math.max(1, checkIntervalTicks);
        if (structureSearchRadius <= 0) structureSearchRadius = 100;
    }

    public static void load() {
        if (CONFIG_FILE.exists()) {
            IAAddonConfig loaded = null;
            try (FileReader reader = new FileReader(CONFIG_FILE)) {
                loaded = GSON.fromJson(reader, IAAddonConfig.class);
            } catch (IOException | JsonParseException e) {
                AirRaidMod.LOGGER.error("Could not read {}, using defaults", CONFIG_FILE, e);
            }
            if (loaded == null) {
                loaded = new IAAddonConfig();
                loaded.factions.add(new Faction());
            }
            loaded.sanitize();
            INSTANCE = loaded;
            save(); // writes any settings added by a newer version back into the file
        } else {
            INSTANCE = new IAAddonConfig();
            INSTANCE.factions.add(new Faction());
            save();
        }
    }

    public static void save() {
        try {
            CONFIG_FILE.getParentFile().mkdirs();
            try (FileWriter writer = new FileWriter(CONFIG_FILE)) {
                GSON.toJson(INSTANCE, writer);
            }
        } catch (IOException e) {
            AirRaidMod.LOGGER.error("Could not write {}", CONFIG_FILE, e);
        }
    }
}
