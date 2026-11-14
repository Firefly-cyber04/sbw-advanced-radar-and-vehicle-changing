package com.f16addon;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item.Properties;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.registries.ForgeRegistries;

public class EnemyF16DeployerItem extends Item {
   private static final String[] AMMO = new String[]{
      "small_shell_ap",
      "small_shell_ap",
      "small_shell_ap",
      "small_shell_ap",
      "medium_anti_air_missile",
      "medium_anti_air_missile",
      "medium_anti_air_missile",
      "large_anti_ground_missile",
      "large_anti_ground_missile",
      "large_anti_ground_missile",
      "medium_aerial_bomb",
      "medium_aerial_bomb"
   };

   public EnemyF16DeployerItem(Properties properties) {
      super(properties);
   }

   public InteractionResult useOn(UseOnContext context) {
      Level level = context.getLevel();
      Player player = context.getPlayer();
      if (level.isClientSide) {
         return InteractionResult.SUCCESS;
      } else {
         Vec3 pos = context.getClickLocation().add(0.0, 1.0, 0.0);
         return this.spawn(level, player, pos) ? InteractionResult.CONSUME : InteractionResult.FAIL;
      }
   }

   public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
      ItemStack stack = player.getItemInHand(hand);
      if (level.isClientSide) {
         return InteractionResultHolder.success(stack);
      } else {
         Vec3 pos = player.position().add(player.getLookAngle().scale(6.0)).add(0.0, 2.0, 0.0);
         return this.spawn(level, player, pos) ? InteractionResultHolder.consume(stack) : InteractionResultHolder.fail(stack);
      }
   }

   private boolean spawn(Level level, Player player, Vec3 pos) {
      EntityType<?> type = (EntityType<?>)ForgeRegistries.ENTITY_TYPES.getValue(ResourceLocation.tryParse("dragonrise_reforge:f16c"));
      if (type == null) {
         if (player != null) {
            player.displayClientMessage(Component.literal("Missing entity type dragonrise_reforge:f16c"), true);
         }

         return false;
      } else {
         Entity entity = type.create(level);
         if (entity == null) {
            return false;
         } else {
            float yaw = player != null ? player.getYRot() : 0.0F;
            entity.moveTo(pos.x, pos.y, pos.z, yaw, 0.0F);
            entity.setCustomName(Component.literal("Enemy F-16"));
            entity.setCustomNameVisible(true);
            entity.addTag("enemy_f16");
            if (!level.addFreshEntity(entity)) {
               return false;
            } else if (!F16Addon.initializeGunData(entity)) {
               entity.discard();
               if (player != null) {
                  player.displayClientMessage(Component.literal("F-16 gun data failed to initialize; see the log.").withStyle(ChatFormatting.RED), false);
               }

               return false;
            } else {
               CompoundTag data = entity.getPersistentData();
               data.putDouble("f16_home_x", entity.getX());
               data.putDouble("f16_home_y", entity.getY());
               data.putDouble("f16_home_z", entity.getZ());
               data.putFloat("f16_yaw", yaw);
               data.putFloat("f16_orbit_angle", 0.0F);
               data.putBoolean("f16_airborne", false);
               data.putBoolean("f16addon_gun_init", true);
               joinEnemyTeam(level, entity);
               fillAmmo(entity);
               if (player != null) {
                  player.displayClientMessage(
                     Component.literal("Enemy F-16 deployed. Sneak + right-click it with the Vehicle Crew Core.").withStyle(ChatFormatting.YELLOW), false
                  );
               }

               return true;
            }
         }
      }
   }

   private static void joinEnemyTeam(Level level, Entity entity) {
      try {
         Scoreboard scoreboard = level.getScoreboard();
         PlayerTeam team = scoreboard.getPlayerTeam("enemy");
         if (team == null) {
            team = scoreboard.addPlayerTeam("enemy");
            team.setColor(ChatFormatting.RED);
            team.setAllowFriendlyFire(false);
         }

         scoreboard.addPlayerToTeam(entity.getStringUUID(), team);
      } catch (Exception var4) {
      }
   }

   private static void fillAmmo(Entity entity) {
      entity.getCapability(ForgeCapabilities.ITEM_HANDLER).ifPresent(handler -> {
         for (int slot = 0; slot < AMMO.length && slot < handler.getSlots(); slot++) {
            Item item = (Item)ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse("superbwarfare:" + AMMO[slot]));
            if (item != null) {
               handler.insertItem(slot, new ItemStack(item, 64), false);
            }
         }
      });
   }
}
