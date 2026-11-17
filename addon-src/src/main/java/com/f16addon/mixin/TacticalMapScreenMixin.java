package com.f16addon.mixin;

import com.atsuishio.superbwarfare.client.map.AttackModeHandler;
import com.atsuishio.superbwarfare.client.screens.TacticalMapScreen;
import com.f16addon.client.map.SituationPanel;
import com.f16addon.client.map.TerminalChrome;
import com.f16addon.client.map.TerminalStyle;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Tactical terminal look: adds the situation panel left of SBW's map and replaces the map's panel, grid, frame,
 * compass, status line and tool buttons with {@link TerminalChrome}. {@code m_88315_} / {@code m_6375_} are the SRG
 * names of {@code Screen.render} / {@code mouseClicked} as they appear in the (already SRG-named) SBW jar.
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
   @Shadow
   private int panelX;
   @Shadow
   private int panelY;
   @Shadow
   private int panelWidth;
   @Shadow
   private int panelHeight;
   @Shadow
   private float mapCenterX;
   @Shadow
   private float mapCenterY;
   @Shadow
   private int mapLeft;
   @Shadow
   private int mapTop;
   @Shadow
   private int mapAreaW;
   @Shadow
   private int mapAreaH;
   @Shadow
   private double zoom;
   @Shadow
   private boolean connectionMode;
   @Shadow
   @Final
   private AttackModeHandler attackHandler;
   @Shadow
   @Final
   private Button centerBtn;
   @Shadow
   @Final
   private Button followBtn;

   @Inject(method = { "render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V", "m_88315_(Lnet/minecraft/client/gui/GuiGraphics;IIF)V" }, at = { @At(value = "HEAD") })
private void f16addon$renderPanel(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
    Screen screen = (Screen) (Object) this;
    SituationPanel.render(graphics, screen.width, screen.height, mouseX, mouseY);
}

   @Inject(method = "renderPanelBg(Lnet/minecraft/client/gui/GuiGraphics;)V", at = @At("HEAD"), cancellable = true)
   private void f16addon$panelBg(GuiGraphics graphics, CallbackInfo ci) {
      TerminalChrome.panel(graphics, this.panelX, this.panelY, this.panelWidth, this.panelHeight, this.f16addon$modeColor());
      ci.cancel();
   }

   @Inject(method = "renderGridLines(Lnet/minecraft/client/gui/GuiGraphics;)V", at = @At("HEAD"), cancellable = true)
   private void f16addon$grid(GuiGraphics graphics, CallbackInfo ci) {
      TerminalChrome.grid(graphics, this.viewBlockX, this.viewBlockZ, this.zoom, this.mapCenterX, this.mapCenterY,
         this.mapLeft, this.mapTop, this.mapAreaW, this.mapAreaH);
      ci.cancel();
   }

   @Inject(method = "renderMapBorder(Lnet/minecraft/client/gui/GuiGraphics;)V", at = @At("HEAD"), cancellable = true)
   private void f16addon$border(GuiGraphics graphics, CallbackInfo ci) {
      TerminalChrome.border(graphics, this.mapLeft, this.mapTop, this.mapAreaW, this.mapAreaH, this.f16addon$modeColor(), this.f16addon$modeHint());
      ci.cancel();
   }

   @Inject(method = "renderCompassRose(Lnet/minecraft/client/gui/GuiGraphics;)V", at = @At("HEAD"), cancellable = true)
   private void f16addon$compass(GuiGraphics graphics, CallbackInfo ci) {
      TerminalChrome.compass(graphics, this.mapLeft, this.mapTop, this.mapAreaW, this.mapAreaH);
      ci.cancel();
   }

   @Inject(method = "renderHudText(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/world/entity/player/Player;II)V", at = @At("HEAD"), cancellable = true)
   private void f16addon$hud(GuiGraphics graphics, Player player, int mouseX, int mouseY, CallbackInfo ci) {
      TerminalChrome.hud(graphics, player, mouseX, mouseY, this.viewBlockX, this.viewBlockZ, this.zoom, this.mapCenterX, this.mapCenterY,
         this.mapLeft, this.mapTop, this.mapAreaW, this.mapAreaH);
      ci.cancel();
   }


   @Inject(method = { "render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V", "m_88315_(Lnet/minecraft/client/gui/GuiGraphics;IIF)V" }, at = { @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/Screen;render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V", shift = At.Shift.AFTER) }, require = 0)
private void f16addon$buttons(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
    TerminalChrome.buttons(graphics, this.centerBtn, this.followBtn, this.followPlayer, mouseX, mouseY);
}

   @Inject(method = { "mouseClicked(DDI)Z", "m_6375_(DDI)Z" }, at = { @At(value = "HEAD") }, cancellable = true)
private void f16addon$clickPanel(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> cir) {
    if (button != 0) {
        return;
    }
    double[] target = SituationPanel.click(mouseX, mouseY);
    if (target == null) {
        return;
    }
    if (target.length == 2) {
        this.viewBlockX = target[0];
        this.viewBlockZ = target[1];
        this.followPlayer = false;
        savedFollowPlayer = false;
    }
    cir.setReturnValue(true);
}

   @Unique
   private int f16addon$modeColor() {
      AttackModeHandler.Mode mode = this.attackHandler.getMode();
      if (mode == AttackModeHandler.Mode.DIRECT || mode == AttackModeHandler.Mode.BOMBARDMENT) {
         return TerminalStyle.MODE_ATTACK;
      }

      if (mode == AttackModeHandler.Mode.QUEUE) {
         return TerminalStyle.MODE_QUEUE;
      }

      return this.connectionMode ? TerminalStyle.MODE_CONNECT : TerminalStyle.ACCENT;
   }

   @Unique
   private String f16addon$modeHint() {
      AttackModeHandler.Mode mode = this.attackHandler.getMode();
      String key = null;
      if (mode == AttackModeHandler.Mode.DIRECT) {
         key = "context.superbwarfare.tactical_map.direct_attack_mode";
      } else if (mode == AttackModeHandler.Mode.QUEUE) {
         key = "context.superbwarfare.tactical_map.queue_attack_mode";
      } else if (mode == AttackModeHandler.Mode.BOMBARDMENT) {
         key = "context.superbwarfare.tactical_map.range_bombardment_mode";
      } else if (this.connectionMode) {
         key = "context.superbwarfare.tactical_map.connect_mode";
      }

      return key == null ? null : Component.translatable(key).getString();
   }

   @Inject(method = { "render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V", "m_88315_(Lnet/minecraft/client/gui/GuiGraphics;IIF)V" }, at = { @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/Screen;m_88315_(Lnet/minecraft/client/gui/GuiGraphics;IIF)V", shift = At.Shift.AFTER) }, require = 0)
private void f16addon$buttonsProduction(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
    TerminalChrome.buttons(graphics, this.centerBtn, this.followBtn, this.followPlayer, mouseX, mouseY);
}
}
