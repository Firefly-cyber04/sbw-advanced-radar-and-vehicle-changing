package com.f16addon;

import net.minecraft.ChatFormatting;
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

public class EnemyGroundDeployerItem extends Item {
   private final String entityPath;
   private final String displayName;
   private final String enemyTag;
   private final String[] ammo;

   public EnemyGroundDeployerItem(Properties properties, String entityPath, String displayName, String enemyTag, String... ammo) {
      super(properties);
      this.entityPath = entityPath;
      this.displayName = displayName;
      this.enemyTag = enemyTag;
      this.ammo = ammo;
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
         Vec3 pos = player.position().add(player.getLookAngle().scale(5.0)).add(0.0, 1.0, 0.0);
         return this.spawn(level, player, pos) ? InteractionResultHolder.consume(stack) : InteractionResultHolder.fail(stack);
      }
   }

   private boolean spawn(Level level, Player player, Vec3 pos) {
      return deployAt(level, player, pos) != null;
   }

   /** Reuse deployment initialization for bounded tactical encounters. */
   public Entity deployAt(Level level, Player player, Vec3 pos) {
      EntityType<?> type = (EntityType<?>)ForgeRegistries.ENTITY_TYPES.getValue(ResourceLocation.tryParse("dragonrise_reforge:" + this.entityPath));
      if (type == null) {
         if (player != null) {
            player.displayClientMessage(Component.literal("Missing entity type dragonrise_reforge:" + this.entityPath), true);
         }

         return null;
      } else {
         Entity entity = type.create(level);
         if (entity == null) {
            return null;
         } else {
            float yaw = player != null ? player.getYRot() : 0.0F;
            entity.moveTo(pos.x, pos.y, pos.z, yaw, 0.0F);
            entity.setCustomName(Component.literal(this.displayName));
            entity.setCustomNameVisible(true);
            entity.addTag("enemy_ground");
            entity.addTag(this.enemyTag);
            if (!level.addFreshEntity(entity)) {
               return null;
            } else if (!F16Addon.initializeGunData(entity)) {
               entity.discard();
               if (player != null) {
                  player.displayClientMessage(Component.literal("Ground vehicle gun data failed to initialize; see the log.").withStyle(ChatFormatting.RED), false);
               }

               return null;
            } else {
               entity.getPersistentData().putString("ground_mode", "advance");
               entity.getPersistentData().putDouble("ground_home_x", entity.getX());
               entity.getPersistentData().putDouble("ground_home_z", entity.getZ());
               entity.getPersistentData().putFloat("ground_patrol_angle", 0.0F);
               joinEnemyTeam(level, entity);
               this.fillAmmo(entity);
               if (player != null) {
                  player.displayClientMessage(Component.literal(this.displayName + " deployed as an enemy unit.").withStyle(ChatFormatting.YELLOW), false);
               }

               return entity;
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

   private void fillAmmo(Entity entity) {
      entity.getCapability(ForgeCapabilities.ITEM_HANDLER).ifPresent(handler -> {
         for (int slot = 0; slot < this.ammo.length && slot < handler.getSlots(); slot++) {
            Item item = (Item)ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse("superbwarfare:" + this.ammo[slot]));
            if (item != null) {
               handler.insertItem(slot, new ItemStack(item, 64), false);
            }
         }
      });
   }
}
