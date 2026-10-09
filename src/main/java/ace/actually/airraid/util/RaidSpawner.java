package ace.actually.airraid.util;

import ace.actually.airraid.config.IAAddonConfig;
import immersive_aircraft.entity.EngineVehicle;
import immersive_aircraft.entity.inventory.VehicleInventoryDescription;
import immersive_aircraft.entity.inventory.slots.SlotDescription;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.*;

public class RaidSpawner {

    // --- Ambush Spawning ---
    public static void spawnAmbush(ServerLevel level, Vec3 targetPos, IAAddonConfig.Faction faction) {
        double angle = level.random.nextDouble() * 2 * Math.PI;
        double distance = IAAddonConfig.INSTANCE.minSpawnDistance;

        double spawnX = targetPos.x + Math.sin(angle) * distance;
        double spawnZ = targetPos.z + Math.cos(angle) * distance;
        double spawnY = targetPos.y + IAAddonConfig.INSTANCE.spawnHeight;

        double dx = targetPos.x - spawnX;
        double dz = targetPos.z - spawnZ;
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0F);

        spawnSquadronAt(level, spawnX, spawnY, spawnZ, yaw, faction);
    }

    // --- Parked Vehicle Spawning ---
    public static void spawnParkedAt(ServerLevel level, BlockPos center, IAAddonConfig.Faction faction) {
        RandomSource random = level.random;
        double angle = random.nextDouble() * 2 * Math.PI;
        double dist = 15 + random.nextDouble() * 25; // 15 to 40 blocks away

        int x = center.getX() + (int) (Math.sin(angle) * dist);
        int z = center.getZ() + (int) (Math.cos(angle) * dist);

        // Don't force-load chunks just to park a plane
        if (!level.hasChunk(x >> 4, z >> 4)) return;

        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        if (y <= level.getMinBuildHeight()) return;

        float yaw = random.nextFloat() * 360.0f;
        spawnVehicle(level, x + 0.5, y + 1, z + 0.5, 0, yaw, faction, true);
    }

    // --- Squadron Logic ---
    public static void spawnSquadronAt(ServerLevel level, double x, double y, double z, float yaw, IAAddonConfig.Faction faction) {
        int min = Math.max(1, faction.minSquadSize);
        int max = Math.max(min, faction.maxSquadSize);
        int size = min + level.random.nextInt(max - min + 1);

        spawnVehicle(level, x, y, z, 0, yaw, faction, false);

        for (int i = 1; i < size; i++) {
            int row = (i + 1) / 2;
            int side = (i % 2 == 1) ? 1 : -1;
            spawnVehicle(level, x, y, z, side * row, yaw, faction, false);
        }

        // Illager planes showing up near a village can make its guards scramble
        if (!faction.defenders) {
            GuardAirDefense.onIllagerSquadron(level, BlockPos.containing(x, y, z));
        }
    }

    // --- Core Spawner ---
    private static void spawnVehicle(ServerLevel level, double originX, double originY, double originZ, int formationIndex, float yaw, IAAddonConfig.Faction faction, boolean isParked) {
        ResourceLocation vehicleId = pickWeighted(faction.vehicles, level.random);
        if (vehicleId == null) return;

        // ENTITY_TYPE is a defaulted registry (falls back to pig), so look it up explicitly
        Optional<EntityType<?>> type = BuiltInRegistries.ENTITY_TYPE.getOptional(vehicleId);
        if (type.isEmpty()) return;
        Entity vehicle = type.get().create(level);
        if (vehicle == null) return;

        double x = originX;
        double z = originZ;

        // Only apply formation offset if flying
        if (!isParked && formationIndex != 0) {
            double offsetDist = 15.0;
            double rowDepth = 12.0;
            double rads = Math.toRadians(yaw);
            double perpRads = Math.toRadians(yaw + 90);
            double row = Math.abs(formationIndex);

            x += Math.sin(rads) * (row * rowDepth);
            z -= Math.cos(rads) * (row * rowDepth);
            x += Math.sin(perpRads) * (offsetDist * formationIndex);
            z -= Math.cos(perpRads) * (offsetDist * formationIndex);
        }

        double y = Math.min(originY, level.getMaxBuildHeight() - 10);

        vehicle.moveTo(x, y, z, yaw, 0.0f);

        if (faction.defenders) {
            vehicle.addTag(Sides.DEFENDER_VEHICLE_TAG);
            PilotAI.setHome(vehicle, faction.home != null ? faction.home : BlockPos.containing(x, y, z));
        }

        // Tags let the pilot AI identify formation roles
        if (!isParked) {
            if (formationIndex < 0) vehicle.addTag("formation_left");
            else if (formationIndex > 0) vehicle.addTag("formation_right");
            else vehicle.addTag("formation_leader");
        }

        // --- INVENTORY SETUP ---
        if (vehicle instanceof EngineVehicle engineVehicle) {
            Container inv = engineVehicle.getInventory();
            Set<Integer> protectedSlots = new HashSet<>();

            // 1. Fuel
            Item fuelItem = itemOrAir(faction.fuelItem);
            if (fuelItem == Items.AIR) fuelItem = Items.COAL;

            List<SlotDescription> boilerSlots = engineVehicle.getInventoryDescription()
                    .getSlots(VehicleInventoryDescription.BOILER);
            for (SlotDescription slot : boilerSlots) {
                protectedSlots.add(slot.index());
                inv.setItem(slot.index(), new ItemStack(fuelItem, 64));
            }

            // 2. Weapon & Ammo
            String weaponId = pickWeapon(faction, level.random);
            Item weaponItem = itemOrAir(weaponId);

            Item ammoItem = Items.ARROW;
            if (weaponId.contains("rotary")) ammoItem = Items.GUNPOWDER;
            else if (weaponId.contains("bomb")) ammoItem = Items.TNT;

            if (weaponItem != Items.AIR) {
                List<SlotDescription> weaponSlots = engineVehicle.getInventoryDescription()
                        .getSlots(VehicleInventoryDescription.WEAPON);

                if (!weaponSlots.isEmpty()) {
                    int weaponIndex = weaponSlots.get(0).index();
                    inv.setItem(weaponIndex, new ItemStack(weaponItem));
                    protectedSlots.add(weaponIndex);
                }

                // Place the configured amount of ammo in free slots
                int totalAmmoNeeded = faction.weaponAmmoCounts.getOrDefault(weaponId, 64);
                int maxStack = ammoItem.getMaxStackSize();

                for (int i = 0; i < inv.getContainerSize(); i++) {
                    if (totalAmmoNeeded <= 0) break;
                    if (protectedSlots.contains(i)) continue;
                    if (!inv.getItem(i).isEmpty()) continue;

                    int amountToAdd = Math.min(totalAmmoNeeded, maxStack);
                    inv.setItem(i, new ItemStack(ammoItem, amountToAdd));
                    totalAmmoNeeded -= amountToAdd;
                }
            }

            engineVehicle.setEngineTarget(isParked ? 0.0f : 1.0f);
        }

        // Launch velocity (only if flying)
        if (!isParked) {
            float yawRads = (float) Math.toRadians(yaw);
            double startSpeed = 1.2;
            vehicle.setDeltaMovement(-Math.sin(yawRads) * startSpeed, 0.0, Math.cos(yawRads) * startSpeed);
            vehicle.hurtMarked = true;
        }

        level.addFreshEntity(vehicle);

        // Add crew (only if flying)
        if (!isParked) {
            for (int safety = 0; safety < 10; safety++) {
                if (!addFactionRaider(level, vehicle, x, y, z, yaw, faction)) break;
            }
        }
    }

    private static boolean addFactionRaider(ServerLevel level, Entity vehicle, double x, double y, double z, float yaw, IAAddonConfig.Faction faction) {
        if (faction.mobs.isEmpty()) return false;

        String mobId = faction.mobs.get(level.random.nextInt(faction.mobs.size()));
        ResourceLocation id = ResourceLocation.tryParse(mobId);
        if (id == null) return false;
        Optional<EntityType<?>> type = BuiltInRegistries.ENTITY_TYPE.getOptional(id);
        if (type.isEmpty()) return false;

        Entity raider = type.get().create(level);
        if (!(raider instanceof Mob mobRaider)) {
            if (raider != null) raider.discard();
            return false;
        }

        if (mobId.contains("pillager")) {
            mobRaider.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.CROSSBOW));
        } else if (mobId.contains("vindicator")) {
            mobRaider.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_AXE));
        }

        mobRaider.moveTo(x, y, z, yaw, 0.0f);
        if (faction.defenders) {
            // Lets Guard Villagers pick the guard's biome look and hand out its weapons/armour
            mobRaider.finalizeSpawn(level, level.getCurrentDifficultyAt(BlockPos.containing(x, y, z)), MobSpawnType.MOB_SUMMONED, null, null);
        }
        mobRaider.setPersistenceRequired();

        if (mobRaider.startRiding(vehicle)) {
            level.addFreshEntity(mobRaider);
            return true;
        } else {
            mobRaider.discard();
            return false;
        }
    }

    private static Item itemOrAir(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null) return Items.AIR;
        return BuiltInRegistries.ITEM.get(rl); // defaulted registry: unknown ids give AIR
    }

    private static ResourceLocation pickWeighted(Map<String, Integer> weights, RandomSource random) {
        if (weights == null || weights.isEmpty()) return null;
        int totalWeight = 0;
        for (Integer w : weights.values()) totalWeight += Math.max(0, w == null ? 0 : w);
        if (totalWeight <= 0) return null;

        int r = random.nextInt(totalWeight);
        int count = 0;
        for (Map.Entry<String, Integer> entry : weights.entrySet()) {
            count += Math.max(0, entry.getValue() == null ? 0 : entry.getValue());
            if (r < count) return ResourceLocation.tryParse(entry.getKey());
        }
        return null;
    }

    private static String pickWeapon(IAAddonConfig.Faction faction, RandomSource random) {
        List<String> allowed = new ArrayList<>();
        if (faction.enableCrossbows) allowed.add("immersive_aircraft:heavy_crossbow");
        if (faction.enableRotaryCannons) allowed.add("immersive_aircraft:rotary_cannon");
        if (faction.enableBombBay) allowed.add("immersive_aircraft:bomb_bay");

        if (allowed.isEmpty()) return "minecraft:air";
        return allowed.get(random.nextInt(allowed.size()));
    }
}
