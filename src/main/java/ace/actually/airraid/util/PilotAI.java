package ace.actually.airraid.util;

import ace.actually.airraid.config.IAAddonConfig;
import immersive_aircraft.entity.*;
import immersive_aircraft.entity.weapon.BombBay;
import immersive_aircraft.entity.weapon.RotaryCannon;
import immersive_aircraft.entity.weapon.Weapon;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Flies an Immersive Aircraft vehicle on behalf of a mob pilot.
 * Called from {@link ace.actually.airraid.mixin.VehicleEntityMixin} every server tick,
 * right after Immersive Aircraft resets the inputs of non-player pilots.
 * <p>
 * Two sides: illager attackers (hunt players and guard aircraft) and village defenders
 * (guards; hunt illager aircraft, raiders and monsters, never fire through players or villagers).
 */
public final class PilotAI {

    /** Per-vehicle runtime state (server thread only). */
    private static final class State {
        int fireCooldown = 0;
        int burstTicks = 0;
        int burstCooldownTicks = 0;
        int idleTicks = 0;
        int retargetCooldown = 0;
    }

    private static final Map<VehicleEntity, State> STATES = new WeakHashMap<>();

    private static final String ANCHOR_X = "airraid_anchor_x";
    private static final String ANCHOR_Y = "airraid_anchor_y";
    private static final String ANCHOR_Z = "airraid_anchor_z";

    private PilotAI() {
    }

    public static void tick(VehicleEntity vehicle) {
        if (vehicle.level().isClientSide) return;
        if (!(vehicle.getFirstPassenger() instanceof Mob mob)) return;

        Sides.Side side = Sides.of(mob);
        if (side == Sides.Side.NONE) return; // a random mob that wandered into a plane: leave it alone

        // NoAI pilot: the aircraft is parked in mid-air exactly where it was put, and doesn't fight
        CompoundTag data = vehicle.getPersistentData();
        if (mob.isNoAi()) {
            if (!data.contains(ANCHOR_X)) {
                data.putDouble(ANCHOR_X, vehicle.getX());
                data.putDouble(ANCHOR_Y, vehicle.getY());
                data.putDouble(ANCHOR_Z, vehicle.getZ());
            }
            double ax = data.getDouble(ANCHOR_X), ay = data.getDouble(ANCHOR_Y), az = data.getDouble(ANCHOR_Z);
            vehicle.setInputs(0.0f, 0.0f, 0.0f);
            vehicle.setDeltaMovement(Vec3.ZERO);
            if (vehicle.distanceToSqr(ax, ay, az) > 1.0E-4) vehicle.setPos(ax, ay, az);
            mob.setTarget(null);
            return;
        } else if (data.contains(ANCHOR_X)) {
            // NoAI was removed again: fly normally from here on
            data.remove(ANCHOR_X);
            data.remove(ANCHOR_Y);
            data.remove(ANCHOR_Z);
        }

        State state = STATES.computeIfAbsent(vehicle, v -> new State());

        float inputYaw = 0.0f;
        float inputPitch = 0.0f;
        float inputThrottle = 0.0f;
        boolean wantToFire = false;

        // 1. Target validation & acquisition
        LivingEntity target = mob.getTarget();
        if (target != null && !Sides.isValidTarget(side, target)) {
            mob.setTarget(null);
            target = null;
        }

        if (target == null && --state.retargetCooldown <= 0) {
            state.retargetCooldown = 10; // search twice a second, not every tick
            target = side == Sides.Side.DEFENDER ? findDefenderTarget(vehicle) : findAttackerTarget(vehicle);
            if (target != null) mob.setTarget(target);
        }

        boolean isVtol = vehicle instanceof AirshipEntity || vehicle instanceof GyrodyneEntity || vehicle instanceof QuadrocopterEntity;

        // Weapon capability & type detection
        boolean isWarship = vehicle instanceof WarshipEntity;
        boolean isRotary = false;
        boolean hasBombBay = false;

        if (vehicle instanceof InventoryVehicleEntity invVehicle) {
            for (List<Weapon> list : invVehicle.getWeapons().values()) {
                for (Weapon w : list) {
                    if (w instanceof RotaryCannon) isRotary = true;
                    if (w instanceof BombBay) hasBombBay = true;
                }
            }
        }

        boolean hasTurret = isWarship || isRotary;

        // 2. Flight
        if (target != null) {
            state.idleTicks = 0;

            Vec3 selfPos = vehicle.position();
            Vec3 targetPos = target.position();
            Vec3 toTarget = targetPos.subtract(selfPos);
            double realDist = toTarget.length();

            // Collision avoidance with other aircraft
            Vec3 avoidance = Vec3.ZERO;
            List<Entity> nearbyVehicles = vehicle.level().getEntities(vehicle, vehicle.getBoundingBox().inflate(25.0), e -> e instanceof VehicleEntity);

            for (Entity neighbor : nearbyVehicles) {
                Vec3 toSelf = selfPos.subtract(neighbor.position());
                double distSq = toSelf.lengthSqr();
                if (distSq < 625.0 && distSq > 0.01) {
                    double dist = Math.sqrt(distSq);
                    double force = (25.0 - dist) / 25.0;
                    avoidance = avoidance.add(toSelf.normalize().scale(force * 2.5));
                }
            }

            Vec3 desiredDir = toTarget.normalize().add(avoidance).normalize();
            Vec3 virtualTargetPos = selfPos.add(desiredDir.scale(realDist));

            double dx = virtualTargetPos.x - vehicle.getX();
            double dy = virtualTargetPos.y - vehicle.getY();
            double dz = virtualTargetPos.z - vehicle.getZ();
            double hDist = Math.sqrt(dx * dx + dz * dz);

            int groundY = vehicle.level().getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(vehicle.getX()), Mth.floor(vehicle.getZ()));
            boolean unsafeLow = vehicle.getY() < groundY + 15;

            int targetGroundY = vehicle.level().getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(target.getX()), Mth.floor(target.getZ()));
            boolean isAirTarget = target.getY() > targetGroundY + 10;

            float angleToTarget = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0f;
            float yawDiff = Mth.wrapDegrees(angleToTarget - vehicle.getYRot());

            if (isVtol) {
                double desiredY = target.getY() + 10;
                if (hasBombBay) desiredY = target.getY() + 30;

                float[] v = vtolAltitudeHold(vehicle.getY(), desiredY, hDist);
                inputPitch = v[0];
                inputThrottle = v[1];
                inputYaw = -Mth.clamp(yawDiff / 45.0f, -1.0f, 1.0f);
            } else {
                inputThrottle = 1.0f;
                float targetPitchDeg;

                if (unsafeLow) {
                    targetPitchDeg = -30.0f;
                } else if (hasBombBay) {
                    if (hDist > 20) targetPitchDeg = (vehicle.getY() > target.getY() + 40) ? 5.0f : 0.0f;
                    else targetPitchDeg = 0.0f;
                } else if (isAirTarget) {
                    if (hDist < 30) {
                        targetPitchDeg = -40.0f;
                    } else {
                        float directPitch = (float) -Math.toDegrees(Math.atan2(dy, hDist));
                        targetPitchDeg = Mth.clamp(directPitch, -30, 30);
                    }
                } else {
                    if (hDist > 50) {
                        float directPitch = (float) -Math.toDegrees(Math.atan2(dy, hDist));
                        targetPitchDeg = Mth.clamp(directPitch, -25, 10);
                    } else {
                        targetPitchDeg = -30.0f;
                    }
                }

                inputYaw = -Mth.clamp(yawDiff / 60.0f, -0.8f, 0.8f);
                inputPitch = planeElevator(vehicle.getXRot(), targetPitchDeg, inputYaw);
            }

            // Fire trigger
            if (hasBombBay) {
                double realHDist = Math.sqrt(toTarget.x * toTarget.x + toTarget.z * toTarget.z);
                if (realHDist < 4.0 && vehicle.getY() > target.getY() + 5) wantToFire = true;
            } else if (hasTurret) {
                // cannon shells slow down and vanish after ~70 blocks, so only fire when they can arrive
                if (realDist < (isRotary ? ROTARY_RANGE : 120)) wantToFire = true;
            } else {
                float tolerance = isVtol ? 35.0f : 25.0f;
                if (Math.abs(yawDiff) < tolerance && realDist < 90) wantToFire = true;
            }

            mob.lookAt(EntityAnchorArgument.Anchor.EYES, target.position());
        } else {
            state.idleTicks++;

            BlockPos home = side == Sides.Side.DEFENDER ? getHome(vehicle) : null;
            if (home != null) {
                // Defenders return to their village and circle above it
                double cruiseY = Math.max(home.getY(), vehicle.level().getHeight(Heightmap.Types.MOTION_BLOCKING, home.getX(), home.getZ()))
                        + IAAddonConfig.INSTANCE.guardConfig.launchHeight;
                double dx = home.getX() + 0.5 - vehicle.getX();
                double dz = home.getZ() + 0.5 - vehicle.getZ();
                double hDist = Math.sqrt(dx * dx + dz * dz);
                float angleToHome = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0f;
                float yawDiff = Mth.wrapDegrees(angleToHome - vehicle.getYRot());

                if (isVtol) {
                    float[] v = vtolAltitudeHold(vehicle.getY(), cruiseY, hDist > 24 ? hDist : 0);
                    inputPitch = v[0];
                    inputThrottle = hDist > 24 ? Math.max(v[1], 0.4f) : 0.0f;
                    inputYaw = hDist > 24 ? -Mth.clamp(yawDiff / 45.0f, -1.0f, 1.0f) : 0.1f;
                } else {
                    inputThrottle = 0.8f;
                    // fly home, then hold a wide circle around the bell
                    inputYaw = hDist > 48 ? -Mth.clamp(yawDiff / 60.0f, -0.8f, 0.8f) : -0.25f;
                    float targetPitchDeg = (float) Mth.clamp((vehicle.getY() - cruiseY) * 0.8, -15, 15);
                    inputPitch = planeElevator(vehicle.getXRot(), targetPitchDeg, inputYaw);
                }
            } else {
                // Patrol (original behaviour)
                if (vehicle.getY() < vehicle.level().getSeaLevel() + 60) inputPitch = isVtol ? 0.3f : -0.1f;
                inputThrottle = isVtol ? 0.3f : 0.8f;
                inputYaw = 0.05f;
            }

            // Defender aircraft go away after a quiet spell, but never in front of a player
            if (side == Sides.Side.DEFENDER && vehicle.getTags().contains(Sides.DEFENDER_VEHICLE_TAG)) {
                int limit = IAAddonConfig.INSTANCE.guardConfig.idleDespawnSeconds * 20;
                if (limit > 0 && state.idleTicks > limit && vehicle.level().getNearestPlayer(vehicle, 48) == null) {
                    for (Entity passenger : new ArrayList<>(vehicle.getPassengers())) passenger.discard();
                    vehicle.discard();
                    STATES.remove(vehicle);
                    return;
                }
            }
        }

        if (isVtol) vehicle.setInputs(inputYaw, inputPitch, inputThrottle);
        else vehicle.setInputs(inputYaw, 0.0f, inputPitch);

        // --- Burst fire ---
        int burstDuration = 100; // crossbows / warship: 5 s of fire
        int burstRest = 200;     // then 10 s rest
        int shotInterval = 10;   // 2 shots per second

        if (isRotary) {
            burstDuration = 40;
            burstRest = 100;
            // same rate as a player holding the trigger (1 shell every 4 ticks)
            shotInterval = Math.max(1, IAAddonConfig.INSTANCE.aiRotaryCannonFireInterval);
        } else if (hasBombBay) {
            burstDuration = 10;
            burstRest = 40;
            shotInterval = 5;
        }

        if (state.burstCooldownTicks > 0) {
            state.burstCooldownTicks--;
            wantToFire = false;
        } else if (wantToFire) {
            state.burstTicks++;
            if (state.burstTicks >= burstDuration) {
                state.burstCooldownTicks = burstRest;
                state.burstTicks = 0;
                wantToFire = false;
            }
        } else if (state.burstTicks > 0) {
            state.burstTicks--;
        }

        // --- Execute fire ---
        if (wantToFire && state.fireCooldown <= 0 && target != null && vehicle instanceof InventoryVehicleEntity invVehicle) {
            Vec3 aimPoint = hasBombBay ? target.getBoundingBox().getCenter() : leadTarget(vehicle, target, isRotary);
            Vec3 diff = aimPoint.subtract(vehicle.getBoundingBox().getCenter()).normalize();
            Vector3f aimDir = new Vector3f((float) diff.x, (float) diff.y, (float) diff.z);

            if (isLineOfFireSafe(vehicle, side, aimDir, hasBombBay)) {
                boolean fired = false;

                // Warship turret is not part of the weapons map
                if (vehicle instanceof WarshipEntity warship) {
                    warship.fireWeapon(-1, 0, aimDir);
                    fired = true;
                }

                for (List<Weapon> weaponList : invVehicle.getWeapons().values()) {
                    if (weaponList == null) continue;
                    for (Weapon weapon : weaponList) {
                        weapon.fire(aimDir);
                        fired = true;
                    }
                }
                if (fired) state.fireCooldown = shotInterval;
            }
        }
        if (state.fireCooldown > 0) state.fireCooldown--;

        if (vehicle instanceof EngineVehicle engineVehicle && engineVehicle.getEngineTarget() < 1.0f) {
            engineVehicle.setEngineTarget(1.0f);
        }

        mob.setYRot(vehicle.getYRot());
        mob.setYHeadRot(vehicle.getYRot());
    }

    // ------------------------------------------------------------------ targeting

    /** Illagers: nearest survival player, or a guard flying an aircraft. */
    private static LivingEntity findAttackerTarget(VehicleEntity vehicle) {
        List<LivingEntity> candidates = new ArrayList<>();
        for (Player p : vehicle.level().players()) {
            if (!p.isSpectator() && !p.isCreative() && p.isAlive()) candidates.add(p);
        }
        for (VehicleEntity v : vehicle.level().getEntitiesOfClass(VehicleEntity.class, vehicle.getBoundingBox().inflate(200.0),
                v -> v != vehicle && Sides.ofVehicle(v) == Sides.Side.DEFENDER)) {
            if (v.getFirstPassenger() instanceof LivingEntity pilot) candidates.add(pilot);
        }
        return nearest(vehicle, candidates, 300.0);
    }

    /** Guards: illager aircraft first, then raiders and monsters near the plane. */
    private static LivingEntity findDefenderTarget(VehicleEntity vehicle) {
        List<LivingEntity> air = new ArrayList<>();
        for (VehicleEntity v : vehicle.level().getEntitiesOfClass(VehicleEntity.class, vehicle.getBoundingBox().inflate(200.0),
                v -> v != vehicle && Sides.ofVehicle(v) == Sides.Side.ATTACKER)) {
            if (v.getFirstPassenger() instanceof LivingEntity pilot) air.add(pilot);
        }
        LivingEntity best = nearest(vehicle, air, 200.0);
        if (best != null) return best;

        List<LivingEntity> ground = new ArrayList<>(vehicle.level().getEntitiesOfClass(Mob.class, vehicle.getBoundingBox().inflate(96.0),
                m -> m.isAlive() && (m instanceof Enemy || Sides.of(m) == Sides.Side.ATTACKER) && !Sides.isFriendlyToDefenders(m)));
        return nearest(vehicle, ground, 96.0);
    }

    private static LivingEntity nearest(Entity from, List<? extends LivingEntity> candidates, double maxDist) {
        return candidates.stream()
                .filter(e -> e.distanceToSqr(from) < maxDist * maxDist)
                .min(Comparator.comparingDouble(e -> e.distanceToSqr(from)))
                .orElse(null);
    }

    // ------------------------------------------------------------------ aiming

    /** Rotary cannon shells start at 4 blocks/tick and lose 5% speed per tick (max reach ~74 blocks). */
    private static final double ROTARY_RANGE = 60.0;

    /**
     * Where to aim so the projectile meets the target: its predicted position after the flight time,
     * minus the drift the shooter's own speed adds to the projectile.
     */
    private static Vec3 leadTarget(VehicleEntity vehicle, LivingEntity target, boolean rotary) {
        Vec3 from = vehicle.getBoundingBox().getCenter();
        Vec3 center = target.getBoundingBox().getCenter();
        double dist = from.distanceTo(center);

        // target velocity from its last movement (works for players too, whose server-side motion is unreliable)
        Entity mover = target.getRootVehicle();
        Vec3 targetVel = new Vec3(mover.getX() - mover.xo, mover.getY() - mover.yo, mover.getZ() - mover.zo);
        Vec3 ownVel = vehicle.getDeltaMovement();

        double ticks;
        double ownDrift;
        if (rotary) {
            double frac = Math.min(0.95, dist / 80.0); // distance = 80 * (1 - 0.95^t)
            ticks = Math.log(1.0 - frac) / Math.log(0.95);
            ownDrift = 20.0 * frac;                     // own speed added to the shell decays the same way
        } else {
            ticks = dist / 3.0;                         // heavy crossbow arrows: roughly constant speed
            ownDrift = ticks;
        }
        ticks = Math.min(ticks, 40.0);

        return center.add(targetVel.scale(ticks)).subtract(ownVel.scale(ownDrift));
    }

    // ------------------------------------------------------------------ flight helpers

    /** Returns {lift, forward throttle} for VTOL aircraft holding {@code desiredY}. */
    private static float[] vtolAltitudeHold(double y, double desiredY, double hDist) {
        float forward = hDist > 15 ? 0.6f : 0.0f;
        if (y < desiredY - 5) return new float[]{1.0f, 0.0f};
        if (y < desiredY) return new float[]{1.0f, forward};
        if (y > desiredY + 10) return new float[]{-1.0f, forward};
        return new float[]{0.15f, forward};
    }

    /** Elevator input steering a plane toward {@code targetPitchDeg}. */
    private static float planeElevator(float currentPitch, float targetPitchDeg, float inputYaw) {
        float elevator = (targetPitchDeg - currentPitch) * 0.04f;
        if (currentPitch < -45.0f) elevator = Math.max(elevator, 0.1f);
        if (currentPitch > 45.0f) elevator = Math.min(elevator, -0.3f);
        if (Math.abs(inputYaw) > 0.2f) elevator -= 0.15f;
        return Mth.clamp(elevator, -0.8f, 0.8f);
    }

    // ------------------------------------------------------------------ fire safety

    private static boolean isLineOfFireSafe(VehicleEntity vehicle, Sides.Side side, Vector3f aimDir, boolean hasBombBay) {
        if (hasBombBay) {
            // Defenders never drop bombs onto their own people
            if (side != Sides.Side.DEFENDER) return true;
            AABB below = vehicle.getBoundingBox().inflate(4.0, 0.0, 4.0).expandTowards(0, -64, 0);
            return vehicle.level().getEntities(vehicle, below, e -> e != vehicle && Sides.isFriendlyToDefenders(e)).isEmpty();
        }

        Vec3 rayStart = vehicle.getEyePosition();
        Vec3 rayDirVec = new Vec3(aimDir.x, aimDir.y, aimDir.z).scale(100.0);
        Vec3 rayEnd = rayStart.add(rayDirVec);
        AABB box = vehicle.getBoundingBox().expandTowards(rayDirVec).inflate(1.0);

        EntityHitResult hit = ProjectileUtil.getEntityHitResult(
                vehicle, rayStart, rayEnd, box,
                e -> !e.isSpectator() && e.isAlive() && e != vehicle && !e.isPassengerOfSameVehicle(vehicle),
                100.0 * 100.0
        );
        if (hit == null) return true;

        Entity hitE = hit.getEntity();
        return side == Sides.Side.DEFENDER ? !Sides.isFriendlyToDefenders(hitE) : !Sides.isFriendlyToAttackers(hitE);
    }

    // ------------------------------------------------------------------ home

    public static void setHome(Entity vehicle, BlockPos home) {
        vehicle.getPersistentData().putLong(Sides.HOME_KEY, home.asLong());
    }

    private static BlockPos getHome(Entity vehicle) {
        CompoundTag data = vehicle.getPersistentData();
        return data.contains(Sides.HOME_KEY) ? BlockPos.of(data.getLong(Sides.HOME_KEY)) : null;
    }
}
