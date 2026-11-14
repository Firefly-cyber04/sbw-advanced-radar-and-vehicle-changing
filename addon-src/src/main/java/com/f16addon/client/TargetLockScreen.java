package com.f16addon.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;

public class TargetLockScreen extends Screen {
   private static String lastMode = "auto";
   private final List<Entity> targets = new ArrayList<>();
   private String filter = "all";
   private int totalTargets;
   private boolean nearbyEmpty;
   private boolean fireControl;
   private int targetY = 112;

   public TargetLockScreen() {
      super(Component.literal("F-16 火控 / 目标选择"));
   }

   protected void init() {
      this.targets.clear();
      Minecraft mc = Minecraft.getInstance();
      Player player = mc.player;
      Entity aircraft = player == null ? null : player.getVehicle();
      this.fireControl = isPilotingAircraft(player);
      if (player != null && mc.level != null) {
         List<Entity> found = mc.level.getEntitiesOfClass(Entity.class, player.getBoundingBox().inflate(512.0), e -> isValidTarget(player, e));
         if (this.fireControl) {
            found.removeIf(e -> !inNoseCone(aircraft, e));
         }

         this.totalTargets = found.size();
         found.removeIf(e -> !this.matchesFilter(e));
         found.sort(
            Comparator.<Entity, Boolean>comparing(e -> !(e instanceof Player)).thenComparing(e -> !isAirTarget(e)).thenComparingDouble(player::distanceToSqr)
         );
         this.targets.addAll(found.subList(0, Math.min(8, found.size())));
      } else {
         this.totalTargets = 0;
      }

      this.nearbyEmpty = this.targets.isEmpty();
      int center = this.width / 2;
      int filterY;
      if (this.fireControl) {
         filterY = 62;
         this.targetY = 88;
      } else {
         int modeY = 62;
         this.addModeButton(center - 168, modeY, 108, "自动", "auto");
         this.addModeButton(center - 54, modeY, 108, "巡逻", "patrol");
         this.addModeButton(center + 60, modeY, 108, "返航", "return");
         filterY = 86;
         this.targetY = 112;
      }

      this.addFilterButton(center - 167, filterY, 80, "全部", "all");
      this.addFilterButton(center - 82, filterY, 80, "空中", "air");
      this.addFilterButton(center + 3, filterY, 80, "地面", "ground");
      this.addFilterButton(center + 88, filterY, 80, "玩家", "player");
      int y = this.targetY;

      for (Entity entity : this.targets) {
         boolean air = isAirTarget(entity);
         String label = (entity instanceof Player ? "[玩家]" : "")
            + (air ? "[空中]" : "[地面]")
            + " "
            + entity.getDisplayName().getString()
            + "  "
            + (int)Math.sqrt(player.distanceToSqr(entity))
            + "m  ·  "
            + (air ? "AIM-9/120" : "炸弹/AGM");
         String command = this.fireControl ? "f16addon playerlock " + entity.getUUID() : "f16addon lock " + entity.getUUID();
         this.addRenderableWidget(Button.builder(Component.literal(label), b -> this.sendCommand(command, true)).bounds(center - 170, y, 340, 20).build());
         y += 22;
      }

      int bottom = this.height - 42;
      this.addRenderableWidget(Button.builder(Component.literal("刷新"), b -> this.rebuildWidgets()).bounds(center - 170, bottom, 108, 20).build());
      this.addRenderableWidget(
         Button.builder(
               Component.literal(this.fireControl ? "清除本机锁定" : "清除锁定"),
               b -> this.sendCommand(this.fireControl ? "f16addon playerclear" : "f16addon clear", true)
            )
            .bounds(center - 56, bottom, 108, 20)
            .build()
      );
      this.addRenderableWidget(Button.builder(Component.literal("关闭"), b -> this.onClose()).bounds(center + 58, bottom, 108, 20).build());
   }

   private void addModeButton(int x, int y, int width, String label, String mode) {
      boolean active = lastMode.equals(mode);
      Button button = Button.builder(Component.literal((active ? "[ " : "") + label + (active ? " ]" : "")), b -> {
         lastMode = mode;
         this.sendCommand("f16addon mode " + mode, false);
         this.rebuildWidgets();
      }).bounds(x, y, width, 20).build();
      button.active = !active;
      this.addRenderableWidget(button);
   }

   private void addFilterButton(int x, int y, int width, String label, String filter) {
      boolean active = this.filter.equals(filter);
      Button button = Button.builder(Component.literal((active ? "[ " : "") + label + (active ? " ]" : "")), b -> {
         this.filter = filter;
         this.rebuildWidgets();
      }).bounds(x, y, width, 20).build();
      button.active = !active;
      this.addRenderableWidget(button);
   }

   private boolean matchesFilter(Entity entity) {
      String var2 = this.filter;

      return switch (var2) {
         case "air" -> isAirTarget(entity);
         case "ground" -> !isAirTarget(entity);
         case "player" -> entity instanceof Player;
         default -> true;
      };
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

   private static boolean inNoseCone(Entity aircraft, Entity target) {
      if (aircraft == null) {
         return false;
      } else {
         Vec3 offset = target.position().subtract(aircraft.position());
         double horizontal = Math.sqrt(offset.x * offset.x + offset.z * offset.z);
         if (horizontal < 0.5) {
            return true;
         } else {
            double yaw = Math.toRadians((double)aircraft.getYRot());
            Vec3 forward = new Vec3(-Math.sin(yaw), 0.0, Math.cos(yaw));
            double dot = (offset.x * forward.x + offset.z * forward.z) / horizontal;
            return dot >= Math.cos(Math.toRadians(60.0));
         }
      }
   }

   private static boolean isAirTarget(Entity entity) {
      if (entity instanceof Player player && player.isFallFlying()) {
         return true;
      }

      Entity ride = entity.getVehicle();
      return ride != null && !ride.onGround() ? true : !entity.onGround();
   }

   private static boolean isValidTarget(Player player, Entity entity) {
      return entity != player && entity.isAlive() && !entity.isSpectator()
         ? !(entity instanceof ItemEntity)
            && !(entity instanceof ExperienceOrb)
            && !(entity instanceof ArmorStand)
            && !(entity instanceof AreaEffectCloud)
            && !(entity instanceof HangingEntity)
            && !(entity instanceof Projectile)
         : false;
   }

   private void sendCommand(String command, boolean close) {
      Minecraft mc = Minecraft.getInstance();
      if (mc.player != null && mc.player.connection != null) {
         mc.player.connection.sendCommand(command);
      }

      if (close) {
         this.onClose();
      }
   }

   public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
      this.renderBackground(graphics);
      int center = this.width / 2;
      int panelBottom = Math.min(this.height - 52, this.targetY + Math.max(1, this.targets.size()) * 22 + 8);
      graphics.fill(center - 188, 8, center + 188, panelBottom, -1728053248);
      super.render(graphics, mouseX, mouseY, partialTick);
      String title = this.fireControl ? "FuMO25 火控面板" : "F-16 火控 / 目标选择";
      String status = this.fireControl
         ? "本机火控：机头 ±60°  ·  目标 " + this.targets.size() + " / " + this.totalTargets + "  ·  点击锁定本机 AAM"
         : "飞行模式：" + modeLabel(lastMode) + "  ·  显示 " + this.targets.size() + " / " + this.totalTargets + "  ·  范围 512";
      String subtitle = this.fireControl ? "机头锥形扫描 512 格  ·  锁定后本机 AIM-9/120、PL-10/12/15 发射时自动跟踪" : "空中：AIM-9/120 + 机炮  ·  地面：炸弹/AGM  ·  点目标锁定附近 F-16";
      graphics.drawCenteredString(this.font, Component.literal(title), center, 18, this.fireControl ? 5635925 : 16777215);
      graphics.drawCenteredString(this.font, Component.literal(status), center, 36, this.fireControl ? 8454016 : 8454016);
      graphics.drawCenteredString(this.font, Component.literal(subtitle), center, 50, 10526880);
      if (this.nearbyEmpty) {
         String text = this.totalTargets > 0 ? "当前分类没有目标" : "机头锥内没有可锁定目标";
         graphics.drawCenteredString(this.font, Component.literal(text), center, this.targetY + 12, 16755200);
      }
   }

   private static String modeLabel(String mode) {
      return switch (mode) {
         case "patrol" -> "只巡逻";
         case "return" -> "返航";
         default -> "自动索敌";
      };
   }

   public boolean isPauseScreen() {
      return false;
   }
}
