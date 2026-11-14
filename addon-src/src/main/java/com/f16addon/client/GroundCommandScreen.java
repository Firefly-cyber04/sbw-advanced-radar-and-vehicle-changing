package com.f16addon.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

public class GroundCommandScreen extends Screen {
   private static String lastMode = "advance";
   private final List<Entity> units = new ArrayList<>();
   private int totalUnits;

   public GroundCommandScreen() {
      super(Component.literal("地面单位指挥"));
   }

   protected void init() {
      this.units.clear();
      Minecraft mc = Minecraft.getInstance();
      Player player = mc.player;
      if (player != null && mc.level != null) {
         List<Entity> found = mc.level.getEntitiesOfClass(Entity.class, player.getBoundingBox().inflate(512.0), e -> e.getTags().contains("enemy_ground"));
         this.totalUnits = found.size();
         found.sort(Comparator.comparingDouble(player::distanceToSqr));
         this.units.addAll(found.subList(0, Math.min(8, found.size())));
      } else {
         this.totalUnits = 0;
      }

      int center = this.width / 2;
      int modeY = 62;
      this.addMode(center - 168, modeY, 80, "推进", "advance");
      this.addMode(center - 83, modeY, 80, "防守", "hold");
      this.addMode(center + 2, modeY, 80, "跟随", "follow");
      this.addMode(center + 87, modeY, 80, "停火", "stop");
      int bottom = this.height - 42;
      this.addRenderableWidget(Button.builder(Component.literal("刷新"), b -> this.rebuildWidgets()).bounds(center - 170, bottom, 108, 20).build());
      this.addRenderableWidget(Button.builder(Component.literal("关闭"), b -> this.onClose()).bounds(center + 62, bottom, 108, 20).build());
   }

   private void addMode(int x, int y, int width, String label, String mode) {
      boolean active = lastMode.equals(mode);
      Button button = Button.builder(Component.literal((active ? "[ " : "") + label + (active ? " ]" : "")), b -> {
         lastMode = mode;
         this.sendCommand("f16addon groundmode " + mode);
         this.rebuildWidgets();
      }).bounds(x, y, width, 20).build();
      button.active = !active;
      this.addRenderableWidget(button);
   }

   private void sendCommand(String command) {
      Minecraft mc = Minecraft.getInstance();
      if (mc.player != null && mc.player.connection != null) {
         mc.player.connection.sendCommand(command);
      }
   }

   public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
      this.renderBackground(graphics);
      int center = this.width / 2;
      int panelBottom = Math.min(this.height - 52, 104 + Math.max(1, this.units.size()) * 14 + 10);
      graphics.fill(center - 188, 8, center + 188, panelBottom, -1728053248);
      super.render(graphics, mouseX, mouseY, partialTick);
      graphics.drawCenteredString(this.font, this.title, center, 18, 16777215);
      graphics.drawCenteredString(
         this.font, Component.literal("移动规则：" + modeLabel(lastMode) + "  ·  单位 " + this.totalUnits + "  ·  索敌 328 格"), center, 36, 8454016
      );
      graphics.drawCenteredString(this.font, Component.literal("推进：接近至48格  ·  防守：原地转向  ·  跟随：接近至24格  ·  停火：停止索敌"), center, 50, 10526880);
      if (this.units.isEmpty()) {
         graphics.drawCenteredString(this.font, Component.literal("512 格内没有敌方地面单位"), center, 112, 16755200);
      } else {
         int y = 104;

         for (Entity unit : this.units) {
            int distance = (int)Math.sqrt(Minecraft.getInstance().player.distanceToSqr(unit));
            graphics.drawString(this.font, "• " + unit.getDisplayName().getString() + "  " + distance + "m", center - 150, y, 16777215, false);
            y += 14;
         }
      }
   }

   private static String modeLabel(String mode) {
      return switch (mode) {
         case "hold" -> "防守";
         case "follow" -> "跟随";
         case "stop" -> "停火";
         default -> "推进";
      };
   }

   public boolean isPauseScreen() {
      return false;
   }
}
