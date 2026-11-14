package com.f16addon.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

public final class TargetLockClient {
   private TargetLockClient() {
   }

   public static void open() {
      Minecraft mc = Minecraft.getInstance();
      if (isPilotingAircraft(mc.player)) {
         Screen map = openTacticalMap();
         if (map != null) {
            mc.setScreen(map);
            return;
         }
      }

      mc.setScreen(new TargetLockScreen());
   }

   private static Screen openTacticalMap() {
      try {
         Class<?> clazz = Class.forName("com.atsuishio.superbwarfare.client.screens.TacticalMapScreen");
         Object screen = clazz.getConstructor().newInstance();
         return screen instanceof Screen ? (Screen)screen : null;
      } catch (RuntimeException | ReflectiveOperationException var2) {
         return null;
      }
   }

   private static boolean isPilotingAircraft(Player player) {
      if (player == null) {
         return false;
      } else {
         Entity vehicle = player.getVehicle();
         if (vehicle == null) {
            return false;
         } else {
            Entity controller = vehicle.getControllingPassenger();
            if (controller != null && controller != player) {
               return false;
            } else {
               try {
                  Object type = vehicle.getClass().getMethod("getVehicleType").invoke(vehicle);
                  String name = type instanceof Enum<?> e ? e.name() : String.valueOf(type);
                  return name.contains("AIRPLANE") || name.contains("HELICOPTER") || name.contains("AIRSHIP") || name.contains("DRONE");
               } catch (RuntimeException | ReflectiveOperationException var6) {
                  return false;
               }
            }
         }
      }
   }
}
