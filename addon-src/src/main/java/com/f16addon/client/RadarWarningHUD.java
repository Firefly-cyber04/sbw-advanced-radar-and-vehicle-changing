package com.f16addon.client;

import com.f16addon.Classify;
import com.f16addon.F16Addon;
import com.f16addon.Sbw;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;

/** RWR: warns a pilot when radar-guided AAMs are locked on them. */
@EventBusSubscriber(modid = F16Addon.MODID, value = Dist.CLIENT)
public final class RadarWarningHUD {
   private static final int SCAN_INTERVAL = 5;
   private static final double RANGE_SQR = 1024.0 * 1024.0;
   private static int incoming;
   private static double nearest;
   private static long lastScan = Long.MIN_VALUE;

   private RadarWarningHUD() {
   }

   @SubscribeEvent
   public static void onRenderGui(RenderGuiEvent.Post event) {
      Minecraft mc = Minecraft.getInstance();
      Player player = mc.player;
      if (player == null || mc.level == null) {
         return;
      }

      Entity vehicle = player.getVehicle();
      if (vehicle == null || vehicle.onGround() || !Sbw.isAircraft(vehicle)) {
         incoming = 0;
         return;
      }

      Entity controller = vehicle.getControllingPassenger();
      if (controller != null && controller != player) {
         return;
      }

      long now = mc.level.getGameTime();
      if (now - lastScan >= SCAN_INTERVAL || now < lastScan) {
         lastScan = now;
         scan(mc.level, vehicle, player);
      }

      if (incoming == 0) {
         return;
      }

      GuiGraphics graphics = event.getGuiGraphics();
      int width = 230;
      int height = 34;
      int x = (mc.getWindow().getGuiScaledWidth() - width) / 2;
      int y = 58;
      boolean flash = now / 8L % 2L == 0L;
      int border = flash ? 0xFFFF3030 : 0xFFFF8080;
      graphics.fill(x, y, x + width, y + height, 0xD0200000);
      graphics.fill(x, y, x + width, y + 1, border);
      graphics.fill(x, y + height - 1, x + width, y + height, border);
      graphics.fill(x, y, x + 1, y + height, border);
      graphics.fill(x + width - 1, y, x + width, y + height, border);
      graphics.drawCenteredString(mc.font, "⚠ 雷达弹锁定  " + incoming + " 枚", x + width / 2, y + 7, border);
      graphics.drawCenteredString(mc.font, "最近 " + (int) nearest + "m  ·  立即机动 / 释放干扰弹", x + width / 2, y + 20, 0xFFFFAA);
   }

   /** Walks the client entity list once (1.0.0 did a 2048-block AABB query plus a reflective lookup per projectile). */
   private static void scan(ClientLevel level, Entity aircraft, Player pilot) {
      incoming = 0;
      nearest = Double.MAX_VALUE;
      String selfId = aircraft.getStringUUID();
      String pilotId = pilot.getStringUUID();
      for (Entity entity : level.entitiesForRendering()) {
         if (!Classify.is(entity, Classify.RADAR_AAM)) {
            continue;
         }

         double d = entity.distanceToSqr(aircraft);
         if (d > RANGE_SQR) {
            continue;
         }

         String target = Sbw.targetUuid(entity);
         if (selfId.equals(target) || pilotId.equals(target)) {
            incoming++;
            nearest = Math.min(nearest, Math.sqrt(d));
         }
      }
   }
}
