package ace.actually.airraid.util;

import ace.actually.airraid.config.IAAddonConfig;
import immersive_aircraft.entity.VehicleEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Removes arrows fired by AI-crewed aircraft once they stop moving (stuck in the ground or a wall)
 * or after a short lifetime, so dogfights don't litter the world with arrows nobody can pick up.
 * Only arrows whose shooter is an AI pilot/crew member sitting in an Immersive Aircraft vehicle are tracked.
 */
public final class ArrowCleanup {

    private static final class Tracked {
        final AbstractArrow arrow;
        Vec3 lastPos;
        int stillTicks = 0;

        Tracked(AbstractArrow arrow) {
            this.arrow = arrow;
            this.lastPos = arrow.position();
        }
    }

    private static final List<Tracked> TRACKED = new ArrayList<>();

    private ArrowCleanup() {
    }

    /** Called when an arrow enters the world (server side). */
    public static void onArrowSpawned(AbstractArrow arrow) {
        if (!IAAddonConfig.INSTANCE.cleanupAircraftArrows) return;
        Entity owner = arrow.getOwner();
        if (owner == null) return;
        if (!(owner.getVehicle() instanceof VehicleEntity)) return;
        if (Sides.of(owner) == Sides.Side.NONE) return; // players' and other mobs' arrows are left alone
        TRACKED.add(new Tracked(arrow));
    }

    public static void tick() {
        if (TRACKED.isEmpty()) return;
        int maxAge = Math.max(20, IAAddonConfig.INSTANCE.aircraftArrowLifetimeTicks);

        Iterator<Tracked> it = TRACKED.iterator();
        while (it.hasNext()) {
            Tracked t = it.next();
            AbstractArrow arrow = t.arrow;
            if (arrow.isRemoved()) {
                it.remove();
                continue;
            }

            Vec3 pos = arrow.position();
            if (pos.distanceToSqr(t.lastPos) < 1.0E-4) t.stillTicks++;
            else t.stillTicks = 0;
            t.lastPos = pos;

            // stuck for a few ticks (landed), or simply too old
            if (t.stillTicks >= 3 || arrow.tickCount > maxAge) {
                arrow.discard();
                it.remove();
            }
        }
    }

    public static void clear() {
        TRACKED.clear();
    }
}
