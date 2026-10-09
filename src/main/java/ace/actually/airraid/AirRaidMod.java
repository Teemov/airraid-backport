package ace.actually.airraid;

import ace.actually.airraid.config.IAAddonConfig;
import ace.actually.airraid.util.AircraftDamage;
import ace.actually.airraid.util.ArrowCleanup;
import ace.actually.airraid.util.RaidAirSupport;
import ace.actually.airraid.util.WorldTickHandler;
import com.mojang.logging.LogUtils;
import immersive_aircraft.entity.VehicleEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.ProjectileImpactEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

@Mod(AirRaidMod.MOD_ID)
public class AirRaidMod {
    public static final String MOD_ID = "airraid";
    public static final Logger LOGGER = LogUtils.getLogger();

    public AirRaidMod() {
        IAAddonConfig.load();
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        AirRaidCommand.register(event.getDispatcher());
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        RaidAirSupport.tick();
        ArrowCleanup.tick();
        WorldTickHandler.onServerTick(event.getServer());
    }

    @SubscribeEvent
    public void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.isCanceled() || event.loadedFromDisk()) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (event.getEntity() instanceof Raider raider) {
            RaidAirSupport.onRaiderJoined(level, raider);
        } else if (event.getEntity() instanceof AbstractArrow arrow) {
            ArrowCleanup.onArrowSpawned(arrow);
        }
    }

    @SubscribeEvent
    public void onProjectileImpact(ProjectileImpactEvent event) {
        AircraftDamage.onImpact(event);
    }

    /** Creative players removing an AI-crewed aircraft get the vehicle item back (as in the original). */
    @SubscribeEvent
    public void onAttack(AttackEntityEvent event) {
        if (event.getEntity().level().isClientSide) return;
        if (!event.getEntity().getAbilities().instabuild) return;
        if (event.getTarget() instanceof VehicleEntity vehicle && vehicle.getFirstPassenger() instanceof Mob) {
            vehicle.spawnAtLocation(new ItemStack(vehicle.asItem()));
        }
    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        RaidAirSupport.clear();
        ArrowCleanup.clear();
    }
}
