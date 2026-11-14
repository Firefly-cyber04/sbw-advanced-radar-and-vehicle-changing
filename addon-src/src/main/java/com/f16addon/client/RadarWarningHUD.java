package com.f16addon.client;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiEvent.Post;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;

@EventBusSubscriber(
   modid = "f16addon",
   value = {Dist.CLIENT}
)
public final class RadarWarningHUD {
   private static final int SCAN_INTERVAL = 5;
   private static final List<RadarWarningHUD.Incoming> INCOMING = new ArrayList<>();
   private static long lastScan;

   private RadarWarningHUD() {
   }

   @SubscribeEvent
   public static void onRenderGui(Post event) {
      Minecraft mc = Minecraft.getInstance();
      Player player = mc.player;
      if (player != null && mc.level != null) {
         Entity vehicle = player.getVehicle();
         if (vehicle != null && isAircraft(vehicle) && !vehicle.onGround()) {
            Entity controller = vehicle.getControllingPassenger();
            if (controller == null || controller == player) {
               long now = mc.level.getGameTime();
               if (now - lastScan >= 5L) {
                  lastScan = now;
                  scan(mc, vehicle);
               }

               if (!INCOMING.isEmpty()) {
                  GuiGraphics graphics = event.getGuiGraphics();
                  int width = 230;
                  int height = 34;
                  int x = (mc.getWindow().getGuiScaledWidth() - width) / 2;
                  int y = 58;
                  boolean flash = now / 8L % 2L == 0L;
                  int border = flash ? -53200 : -32640;
                  graphics.fill(x, y, x + width, y + height, -803209216);
                  graphics.fill(x, y, x + width, y + 1, border);
                  graphics.fill(x, y + height - 1, x + width, y + height, border);
                  graphics.fill(x, y, x + 1, y + height, border);
                  graphics.fill(x + width - 1, y, x + width, y + height, border);
                  double nearest = INCOMING.stream().mapToDouble(RadarWarningHUD.Incoming::distance).min().orElse(0.0);
                  graphics.drawCenteredString(mc.font, "⚠ 雷达弹锁定  " + INCOMING.size() + " 枚", x + width / 2, y + 7, border);
                  graphics.drawCenteredString(mc.font, "最近 " + (int)nearest + "m  ·  立即机动 / 释放干扰弹", x + width / 2, y + 20, 16777130);
               }
            }
         } else {
            INCOMING.clear();
         }
      }
   }

   private static void scan(Minecraft mc, Entity aircraft) {
      INCOMING.clear();
      if (mc.level != null) {
         UUID selfId = aircraft.getUUID();
         String pilotId = mc.player == null ? "" : mc.player.getStringUUID();

         for (Projectile projectile : mc.level.getEntitiesOfClass(Projectile.class, aircraft.getBoundingBox().inflate(1024.0))) {
            if (isRadarAam(projectile)) {
               String targetId = targetId(projectile);
               if (selfId.toString().equals(targetId) || pilotId.equals(targetId)) {
                  INCOMING.add(new RadarWarningHUD.Incoming((double)projectile.distanceTo(aircraft)));
               }
            }
         }
      }
   }

   private static boolean isRadarAam(Projectile projectile) {
      String name = projectile.getClass().getSimpleName();
      return name.contains("Aim120") || name.contains("Pl12") || name.contains("Pl15");
   }

   private static String targetId(Projectile projectile) {
      try {
         return projectile.getClass().getMethod("getTargetUUID").invoke(projectile) instanceof String s ? s : "";
      } catch (RuntimeException | ReflectiveOperationException var3) {
         return "";
      }
   }

   private static boolean isAircraft(Entity vehicle) {
      try {
         Object type = vehicle.getClass().getMethod("getVehicleType").invoke(vehicle);
         String name = type instanceof Enum<?> e ? e.name() : String.valueOf(type);
         return name.contains("AIRPLANE") || name.contains("HELICOPTER") || name.contains("AIRSHIP") || name.contains("DRONE");
      } catch (RuntimeException | ReflectiveOperationException var4) {
         return false;
      }
   }

   private static record Incoming(double distance) {
   }
}
