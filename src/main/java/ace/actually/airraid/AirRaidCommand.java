package ace.actually.airraid;

import ace.actually.airraid.config.IAAddonConfig;
import ace.actually.airraid.util.GuardAirDefense;
import ace.actually.airraid.util.RaidSpawner;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;

public class AirRaidCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("airraid")
                .requires(source -> source.hasPermission(2))

                .then(Commands.literal("reload")
                        .executes(ctx -> {
                            IAAddonConfig.load();
                            ctx.getSource().sendSuccess(() -> Component.literal("AirRaid config reloaded!"), true);
                            return 1;
                        })
                )

                .then(Commands.literal("defend")
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 20))
                                .executes(ctx -> executeDefend(ctx, IntegerArgumentType.getInteger(ctx, "count"), null))
                                .then(Commands.argument("vehicle", ResourceLocationArgument.id()).suggests(SUGGEST_AIRCRAFT)
                                        .executes(ctx -> executeDefend(ctx, IntegerArgumentType.getInteger(ctx, "count"),
                                                ResourceLocationArgument.getId(ctx, "vehicle")))
                                )
                        )
                )

                .then(Commands.literal("spawn")
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 20))
                                .executes(ctx -> executeSpawn(ctx, IntegerArgumentType.getInteger(ctx, "count"), null, null))
                                .then(Commands.argument("vehicle", ResourceLocationArgument.id()).suggests(SUGGEST_AIRCRAFT)
                                        .executes(ctx -> executeSpawn(ctx, IntegerArgumentType.getInteger(ctx, "count"),
                                                ResourceLocationArgument.getId(ctx, "vehicle"), null))
                                        .then(Commands.argument("weapon", ResourceLocationArgument.id()).suggests(SUGGEST_WEAPON)
                                                .executes(ctx -> executeSpawn(ctx, IntegerArgumentType.getInteger(ctx, "count"),
                                                        ResourceLocationArgument.getId(ctx, "vehicle"),
                                                        ResourceLocationArgument.getId(ctx, "weapon")))
                                        )
                                )
                        )
                )
        );
    }

    private static int executeSpawn(CommandContext<CommandSourceStack> context, int count, ResourceLocation vehicleId, ResourceLocation weaponId) {
        CommandSourceStack source = context.getSource();

        if (vehicleId != null && !BuiltInRegistries.ENTITY_TYPE.containsKey(vehicleId)) {
            source.sendFailure(Component.literal("Unknown vehicle: " + vehicleId));
            return 0;
        }

        // Temporary "manual" faction for this command
        IAAddonConfig.Faction manualFaction = new IAAddonConfig.Faction();
        manualFaction.minSquadSize = count;
        manualFaction.maxSquadSize = count;

        if (vehicleId != null) {
            manualFaction.vehicles.clear();
            manualFaction.vehicles.put(vehicleId.toString(), 100);
        }

        if (weaponId != null) {
            String w = weaponId.toString();
            manualFaction.enableBombBay = w.contains("bomb");
            manualFaction.enableRotaryCannons = w.contains("rotary");
            manualFaction.enableCrossbows = w.contains("bow");
        }

        float yaw = source.getLevel().random.nextFloat() * 360.0f;

        RaidSpawner.spawnSquadronAt(
                source.getLevel(),
                source.getPosition().x,
                source.getPosition().y + 50,
                source.getPosition().z,
                yaw,
                manualFaction
        );

        source.sendSuccess(() -> Component.literal("Spawned " + count + " aircraft above you."), true);
        return count;
    }

    private static int executeDefend(CommandContext<CommandSourceStack> context, int count, ResourceLocation vehicleId) {
        CommandSourceStack source = context.getSource();

        if (!GuardAirDefense.isAvailable()) {
            source.sendFailure(Component.literal("Guard air defence is off: install Guard Villagers (or set guardConfig.pilotMobs) and check guardConfig.enabled."));
            return 0;
        }
        if (vehicleId != null && !BuiltInRegistries.ENTITY_TYPE.containsKey(vehicleId)) {
            source.sendFailure(Component.literal("Unknown vehicle: " + vehicleId));
            return 0;
        }

        IAAddonConfig.Faction guards = IAAddonConfig.INSTANCE.guardConfig.toFaction();
        // copies, so the command never edits the loaded config
        guards.vehicles = new HashMap<>(guards.vehicles);
        guards.minSquadSize = count;
        guards.maxSquadSize = count;
        if (vehicleId != null) {
            guards.vehicles.clear();
            guards.vehicles.put(vehicleId.toString(), 100);
        }

        // Guards defend the nearest village if there is one, otherwise the spot you're standing on
        BlockPos here = BlockPos.containing(source.getPosition());
        BlockPos home = GuardAirDefense.findBell(source.getLevel(), here,
                IAAddonConfig.INSTANCE.guardConfig.villageSearchRadius).orElse(here);

        GuardAirDefense.launch(source.getLevel(), home, here, guards);
        source.sendSuccess(() -> Component.literal("Scrambled " + count + " guard aircraft from " + home.toShortString() + "."), true);
        return count;
    }

    public static final SuggestionProvider<CommandSourceStack> SUGGEST_AIRCRAFT = (context, builder) ->
            SharedSuggestionProvider.suggestResource(
                    BuiltInRegistries.ENTITY_TYPE.keySet().stream()
                            .filter(id -> id.getNamespace().equals("immersive_aircraft")
                                    && !id.getPath().contains("bullet") && !id.getPath().contains("tnt")),
                    builder);

    public static final SuggestionProvider<CommandSourceStack> SUGGEST_WEAPON = (context, builder) ->
            SharedSuggestionProvider.suggestResource(
                    BuiltInRegistries.ITEM.keySet().stream()
                            .filter(id -> id.getNamespace().equals("immersive_aircraft")
                                    && (id.getPath().contains("cannon") || id.getPath().contains("bow") || id.getPath().contains("bomb"))),
                    builder);
}
