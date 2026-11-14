package com.f16addon.mixin;

import com.atsuishio.superbwarfare.tools.RadarScanner;
import com.f16addon.UnitTracker;
import java.util.Set;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * SBW's radar reports every vehicle without a driver, owner and last driver as "neutral", whatever its team. This
 * addon's AI units are exactly such vehicles, so the tactical terminal showed enemy jets, tanks and SAMs as neutral
 * and the hostile list stayed empty. AI units now skip that rule and are classified by team like everything else
 * (non-friendly → hostile, friendly → still synced as friendly by the vehicle itself).
 */
@Mixin(value = RadarScanner.class, remap = false)
public abstract class RadarScannerMixin {
   @Inject(method = "isNeutral(Lnet/minecraft/world/entity/Entity;)Z", at = @At("HEAD"), cancellable = true)
   private void f16addon$aiUnitsAreNotNeutral(Entity entity, CallbackInfoReturnable<Boolean> cir) {
      Set<String> tags = entity.getTags();
      if (tags.contains(UnitTracker.TAG_F16) || tags.contains(UnitTracker.TAG_GROUND) || tags.contains(UnitTracker.TAG_SAM)) {
         cir.setReturnValue(false);
      }
   }
}
