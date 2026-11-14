package com.f16addon;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.logging.LogUtils;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent.Phase;
import net.minecraftforge.event.TickEvent.ServerTickEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod("f16addon")
public final class F16Addon {
   public static final String MODID = "f16addon";
   private static final int PROJECTILE_MAX_LIFETIME = 500;
   private static final int KALIBR_MAX_LIFETIME = 1200;
   private static final int IR_AAM_MAX_LIFETIME = 160;
   private static final Logger LOGGER = LogUtils.getLogger();

   public F16Addon() {
      IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
      ModItems.ITEMS.register(modBus);
      modBus.addListener(this::addCreative);
      MinecraftForge.EVENT_BUS.addListener(F16Addon::onServerTick);
      MinecraftForge.EVENT_BUS.addListener(PlayerFireControl::onEntityJoin);
      MinecraftForge.EVENT_BUS.addListener(F16Addon::onRegisterCommands);
   }

   private void addCreative(BuildCreativeModeTabContentsEvent var1) {
   }

   private static boolean isEnemyAam(Entity entity) {
      if (entity instanceof Projectile projectile) {
         String name = entity.getClass().getSimpleName();
         if (!name.contains("Aim9") && !name.contains("Aim120") && !name.contains("Ru9m336")) {
            return false;
         } else {
            Entity owner = projectile.getOwner();
            return owner != null && owner.getTags().contains("enemy_f16");
         }
      } else {
         return false;
      }
   }

   private static void onRegisterCommands(RegisterCommandsEvent event) {
      event.getDispatcher()
         .register(
            (LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)Commands.literal(
                                 "f16addon"
                              )
                              .then(
                                 Commands.literal("lock")
                                    .then(
                                       Commands.argument("uuid", StringArgumentType.string())
                                          .executes(ctx -> lockTarget((CommandSourceStack)ctx.getSource(), StringArgumentType.getString(ctx, "uuid")))
                                    )
                              ))
                           .then(Commands.literal("clear").executes(ctx -> clearTargets((CommandSourceStack)ctx.getSource()))))
                        .then(
                           Commands.literal("mode")
                              .then(
                                 Commands.argument("mode", StringArgumentType.word())
                                    .executes(ctx -> setMode((CommandSourceStack)ctx.getSource(), StringArgumentType.getString(ctx, "mode")))
                              )
                        ))
                     .then(
                        Commands.literal("playerlock")
                           .then(
                              Commands.argument("uuid", StringArgumentType.string())
                                 .executes(ctx -> setPlayerLock((CommandSourceStack)ctx.getSource(), StringArgumentType.getString(ctx, "uuid")))
                           )
                     ))
                  .then(Commands.literal("playerclear").executes(ctx -> clearPlayerLock((CommandSourceStack)ctx.getSource()))))
               .then(
                  Commands.literal("groundmode")
                     .then(
                        Commands.argument("mode", StringArgumentType.word())
                           .executes(ctx -> setGroundMode((CommandSourceStack)ctx.getSource(), StringArgumentType.getString(ctx, "mode")))
                     )
               )
         );
   }

   private static boolean isIrAam(Projectile projectile) {
      String name = projectile.getClass().getSimpleName();
      return name.contains("Ru9m336") || name.contains("Aim9") || name.contains("Pl10") || name.contains("Pl8") || name.contains("R73");
   }

   private static int setPlayerLock(CommandSourceStack source, String uuidText) {
      ServerPlayer player;
      try {
         player = source.getPlayerOrException();
      } catch (Exception var6) {
         source.sendFailure(Component.literal("必须由玩家执行"));
         return 0;
      }

      UUID uuid;
      try {
         uuid = UUID.fromString(uuidText);
      } catch (IllegalArgumentException var5) {
         source.sendFailure(Component.literal("无效的目标 UUID"));
         return 0;
      }

      Entity target = player.serverLevel().getEntity(uuid);
      if (target != null && target.isAlive() && !target.isSpectator()) {
         if (player.distanceToSqr(target) > 262144.0) {
            source.sendFailure(Component.literal("目标超出 512 格"));
            return 0;
         } else {
            player.getPersistentData().putString("f16_player_radar_target", uuid.toString());
            source.sendSuccess(() -> Component.literal("本机火控已锁定：" + target.getDisplayName().getString()).withStyle(ChatFormatting.GREEN), false);
            return 1;
         }
      } else {
         source.sendFailure(Component.literal("目标不存在或已死亡"));
         return 0;
      }
   }

   private static int clearPlayerLock(CommandSourceStack source) {
      ServerPlayer player;
      try {
         player = source.getPlayerOrException();
      } catch (Exception var3) {
         source.sendFailure(Component.literal("必须由玩家执行"));
         return 0;
      }

      player.getPersistentData().remove("f16_player_radar_target");
      source.sendSuccess(() -> Component.literal("已清除本机火控锁定").withStyle(ChatFormatting.YELLOW), false);
      return 1;
   }

   private static int setGroundMode(CommandSourceStack source, String modeText) {
      ServerPlayer player;
      try {
         player = source.getPlayerOrException();
      } catch (Exception var7) {
         source.sendFailure(Component.literal("必须由玩家执行"));
         return 0;
      }

      String mode = modeText.toLowerCase(Locale.ROOT);
      if (!mode.equals("advance") && !mode.equals("hold") && !mode.equals("follow") && !mode.equals("stop")) {
         source.sendFailure(Component.literal("模式只能是 advance / hold / follow / stop"));
         return 0;
      } else {
         int count = 0;

         for (Entity unit : player.serverLevel().getEntitiesOfClass(Entity.class, player.getBoundingBox().inflate(512.0), e -> e.getTags().contains("enemy_ground"))) {
            unit.getPersistentData().putString("ground_mode", mode);
            count++;
         }

         if (count == 0) {
            source.sendFailure(Component.literal("512 格内没有敌方地面单位"));
            return 0;
         } else {
            String label = mode.equals("advance") ? "推进" : (mode.equals("hold") ? "防守" : (mode.equals("follow") ? "跟随" : "停火"));
            int changed = count;
            source.sendSuccess(() -> Component.literal("已设置 " + changed + " 辆地面单位：" + label).withStyle(ChatFormatting.GREEN), false);
            return count;
         }
      }
   }

   private static int setMode(CommandSourceStack source, String modeText) {
      ServerPlayer player;
      try {
         player = source.getPlayerOrException();
      } catch (Exception var7) {
         source.sendFailure(Component.literal("必须由玩家执行"));
         return 0;
      }

      String mode = modeText.toLowerCase(Locale.ROOT);
      if (!mode.equals("auto") && !mode.equals("patrol") && !mode.equals("return")) {
         source.sendFailure(Component.literal("模式只能是 auto / patrol / return"));
         return 0;
      } else {
         int count = 0;

         for (Entity f16 : player.serverLevel().getEntitiesOfClass(Entity.class, player.getBoundingBox().inflate(512.0), e -> e.getTags().contains("enemy_f16"))) {
            f16.getPersistentData().putString("f16_mode", mode);
            f16.getPersistentData().putString("f16_target", "");
            count++;
         }

         if (count == 0) {
            source.sendFailure(Component.literal("512 格内没有敌方 F-16"));
            return 0;
         } else {
            String label = mode.equals("auto") ? "自动索敌" : (mode.equals("patrol") ? "只巡逻/不攻击" : "返航");
            int changed = count;
            source.sendSuccess(() -> Component.literal("已设置 " + changed + " 架 F-16：" + label).withStyle(ChatFormatting.GREEN), false);
            return count;
         }
      }
   }

   private static int lockTarget(CommandSourceStack source, String uuidText) {
      ServerPlayer player;
      try {
         player = source.getPlayerOrException();
      } catch (Exception var9) {
         source.sendFailure(Component.literal("必须由玩家执行"));
         return 0;
      }

      UUID targetId;
      try {
         targetId = UUID.fromString(uuidText);
      } catch (IllegalArgumentException var8) {
         source.sendFailure(Component.literal("无效的目标 UUID"));
         return 0;
      }

      Entity target = player.serverLevel().getEntity(targetId);
      if (target != null && target.isAlive()) {
         int count = 0;

         for (Entity f16 : player.serverLevel().getEntitiesOfClass(Entity.class, player.getBoundingBox().inflate(512.0), e -> e.getTags().contains("enemy_f16"))) {
            f16.getPersistentData().putString("f16_locked_target", targetId.toString());
            count++;
         }

         if (count == 0) {
            source.sendFailure(Component.literal("512 格内没有敌方 F-16"));
            return 0;
         } else {
            int locked = count;
            source.sendSuccess(
               () -> Component.literal("已锁定 " + target.getDisplayName().getString() + "（" + locked + " 架 F-16）").withStyle(ChatFormatting.GREEN), false
            );
            return count;
         }
      } else {
         source.sendFailure(Component.literal("目标不存在或已死亡"));
         return 0;
      }
   }

   private static int clearTargets(CommandSourceStack source) {
      ServerPlayer player;
      try {
         player = source.getPlayerOrException();
      } catch (Exception var5) {
         source.sendFailure(Component.literal("必须由玩家执行"));
         return 0;
      }

      int count = 0;

      for (Entity f16 : player.serverLevel().getEntitiesOfClass(Entity.class, player.getBoundingBox().inflate(512.0), e -> e.getTags().contains("enemy_f16"))) {
         f16.getPersistentData().remove("f16_locked_target");
         count++;
      }

      int cleared = count;
      source.sendSuccess(() -> Component.literal("已清除 " + cleared + " 架 F-16 的锁定").withStyle(ChatFormatting.YELLOW), false);
      return count;
   }

   private static void onServerTick(ServerTickEvent event) {
      if (event.phase == Phase.END) {
         MinecraftServer server = event.getServer();
         if (server != null) {
            for (ServerLevel level : server.getAllLevels()) {
               List<Entity> entities = new ArrayList<>();

               for (Entity entity : level.getAllEntities()) {
                  if (entity != null) {
                     entities.add(entity);
                  }
               }

               List<Entity> expired = new ArrayList<>();

               for (Entity entityx : entities) {
                  if (entityx != null) {
                     if (entityx instanceof Projectile) {
                        Projectile projectile = (Projectile)entityx;
                        int maxLifetime = projectile.getClass().getSimpleName().contains("Ru3m14") ? Integer.MAX_VALUE : 500;
                        if (isIrAam(projectile)) {
                           maxLifetime = 160;
                        }

                        if (projectile.tickCount > maxLifetime) {
                           expired.add(entityx);
                           continue;
                        }
                     }

                     if (!entityx.getTags().contains("f16_aam") && !isEnemyAam(entityx)) {
                        if (entityx.getTags().contains("enemy_ground")) {
                           GroundUnitAI.tick(level, entityx);
                        } else if (entityx.getTags().contains("enemy_f16")) {
                           if (F16FlightAI.isWrecked(entityx)) {
                              F16FlightAI.tick(level, entityx);
                           } else {
                              initializeGunData(entityx);
                              F16FlightAI.tick(level, entityx);
                              refillEnergy(entityx);
                           }
                        }
                     } else {
                        F16MissileDefense.tick(level, entityx);
                     }
                  }
               }

               for (Entity entityxx : expired) {
                  entityxx.discard();
               }
            }
         }
      }
   }

   public static boolean initializeGunData(Entity entity) {
      CompoundTag data = entity.getPersistentData();
      if (data.getBoolean("f16addon_gun_init")) {
         return true;
      } else if (data.getBoolean("f16addon_gun_failed")) {
         return false;
      } else {
         try {
            Method get = entity.getClass().getMethod("getGunDataMap");
            Object map = get.invoke(entity);
            Method set = entity.getClass().getMethod("setGunDataMap", Map.class);
            set.invoke(entity, map);
            data.putBoolean("f16addon_gun_init", true);
            return true;
         } catch (RuntimeException | ReflectiveOperationException var5) {
            data.putBoolean("f16addon_gun_failed", true);
            LOGGER.error("Failed to initialize F-16 GunDataMap", var5);
            return false;
         }
      }
   }

   private static void refillEnergy(Entity entity) {
      try {
         Method max = entity.getClass().getMethod("getMaxEnergy");
         Method set = entity.getClass().getMethod("setEnergy", int.class);
         set.invoke(entity, (Integer)max.invoke(entity));
      } catch (ReflectiveOperationException var3) {
         entity.getCapability(ForgeCapabilities.ENERGY).ifPresent(storage -> {
            int missing = storage.getMaxEnergyStored() - storage.getEnergyStored();
            if (missing > 0) {
               storage.receiveEnergy(missing, false);
            }
         });
      }
   }
}
