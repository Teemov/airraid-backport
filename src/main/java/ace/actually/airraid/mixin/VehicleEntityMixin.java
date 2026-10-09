package ace.actually.airraid.mixin;

import ace.actually.airraid.util.PilotAI;
import immersive_aircraft.entity.VehicleEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Immersive Aircraft's private {@code tickPilot()} zeroes the controls for any pilot that isn't a player.
 * Injecting at its end lets the mob pilot AI set the controls just before the vehicle applies them.
 * The target is Immersive Aircraft's own (never obfuscated) method name, so no refmap is needed.
 */
@Mixin(value = VehicleEntity.class, remap = false)
public abstract class VehicleEntityMixin {

    @Inject(method = "tickPilot", at = @At("TAIL"))
    private void airraid$mobPilot(CallbackInfo ci) {
        PilotAI.tick((VehicleEntity) (Object) this);
    }
}
