package com.f16addon.mixin;

import com.atsuishio.superbwarfare.client.screens.TacticalMapScreen;
import com.f16addon.client.map.SituationPanel;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Adds the situation panel to the left of SBW's tactical map. {@code m_88315_} / {@code m_6375_} are the SRG names of
 * {@code Screen.render} / {@code mouseClicked} as they appear in the (already SRG-named) SBW jar.
 */
@Mixin(value = TacticalMapScreen.class, remap = false)
public abstract class TacticalMapScreenMixin {
   @Shadow
   private double viewBlockX;
   @Shadow
   private double viewBlockZ;
   @Shadow
   private boolean followPlayer;
   @Shadow
   private static boolean savedFollowPlayer;

   @Inject(method = "m_88315_(Lnet/minecraft/client/gui/GuiGraphics;IIF)V", at = @At("HEAD"))
   private void f16addon$renderPanel(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
      Screen screen = (Screen) (Object) this;
      SituationPanel.render(graphics, screen.width, screen.height, mouseX, mouseY);
   }

   @Inject(method = "m_6375_(DDI)Z", at = @At("HEAD"), cancellable = true)
   private void f16addon$clickPanel(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> cir) {
      if (button != 0) {
         return;
      }

      double[] target = SituationPanel.click(mouseX, mouseY);
      if (target != null) {
         this.viewBlockX = target[0];
         this.viewBlockZ = target[1];
         this.followPlayer = false;
         savedFollowPlayer = false;
         cir.setReturnValue(true);
      }
   }
}
