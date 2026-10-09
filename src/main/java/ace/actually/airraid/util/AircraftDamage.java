package ace.actually.airraid.util;

import ace.actually.airraid.config.IAAddonConfig;
import immersive_aircraft.entity.VehicleEntity;
import immersive_aircraft.entity.bullet.BulletEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraftforge.event.entity.ProjectileImpactEvent;

/**
 * Immersive Aircraft lets 6 rotary-cannon shells (5 damage each) destroy a biplane. AI gunners fire non-stop
 * bursts, so aircraft were dying almost instantly. This scales down only the damage that AI-fired shells and
 * arrows do to aircraft; damage to mobs and players is unchanged, and so is anything a player fires.
 */
public final class AircraftDamage {

    private static final String SCALED = "airraid_scaled";

    private AircraftDamage() {
    }

    public static void onImpact(ProjectileImpactEvent event) {
        Projectile projectile = event.getProjectile();
        if (projectile.level().isClientSide) return;
        if (!(event.getRayTraceResult() instanceof EntityHitResult hit)) return;
        if (!(hit.getEntity() instanceof VehicleEntity)) return;
        if (!isAiFired(projectile)) return;
        if (projectile.getPersistentData().getBoolean(SCALED)) return;

        float mult = Math.max(0.0f, IAAddonConfig.INSTANCE.aiDamageToAircraftMultiplier);
        if (mult == 1.0f) return;

        if (projectile instanceof BulletEntity bullet) {
            bullet.setDamage(bullet.getDamage() * mult);
        } else if (projectile instanceof AbstractArrow arrow) {
            arrow.setBaseDamage(arrow.getBaseDamage() * mult);
        } else {
            return;
        }
        projectile.getPersistentData().putBoolean(SCALED, true);
    }

    /** Fired by an illager/guard aircrew: shells are owned by the aircraft, arrows by the crew member. */
    private static boolean isAiFired(Projectile projectile) {
        Entity owner = projectile.getOwner();
        if (owner instanceof VehicleEntity vehicle) {
            return Sides.ofVehicle(vehicle) != Sides.Side.NONE;
        }
        return owner != null && owner.getVehicle() instanceof VehicleEntity && Sides.of(owner) != Sides.Side.NONE;
    }
}
