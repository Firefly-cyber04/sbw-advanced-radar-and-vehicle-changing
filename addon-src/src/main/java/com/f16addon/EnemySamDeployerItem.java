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
import net.minecraftforge.common.extensions.IForgeEntity;
import net.minecraftforge.registries.ForgeRegistries;

public class EnemySamDeployerItem extends Item {
   private final String entityPath;
   private final String displayName;
   private final String enemyTag;

   public EnemySamDeployerItem(Properties var1, String var2, String var3, String var4) {
      super(var1);
      this.entityPath = var2;
      this.displayName = var3;
      this.enemyTag = var4;
   }

   public InteractionResult useOn(UseOnContext var1) {
      Level var2 = var1.getLevel();
      Player var3 = var1.getPlayer();
      if (var2.isClientSide) {
         return InteractionResult.SUCCESS;
      } else {
         Vec3 var4 = var1.getClickLocation().add(0.0, 1.0, 0.0);
         return this.spawn(var2, var3, var4) ? InteractionResult.CONSUME : InteractionResult.FAIL;
      }
   }

   public InteractionResultHolder<ItemStack> use(Level var1, Player var2, InteractionHand var3) {
      ItemStack var4 = var2.getItemInHand(var3);
      if (var1.isClientSide) {
         return InteractionResultHolder.success(var4);
      } else {
         Vec3 var5 = var2.position().add(var2.getLookAngle().scale(6.0)).add(0.0, 1.0, 0.0);
         return this.spawn(var1, var2, var5) ? InteractionResultHolder.consume(var4) : InteractionResultHolder.fail(var4);
      }
   }

   private boolean spawn(Level var1, Player var2, Vec3 var3) {
      EntityType var4 = (EntityType)ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation("dragonrise_reforge", this.entityPath));
      if (var4 == null) {
         message(var2, "Missing entity type dragonrise_reforge:" + this.entityPath, ChatFormatting.RED);
         return false;
      } else {
         Entity var5 = var4.create(var1);
         if (var5 == null) {
            return false;
         } else {
            float var6 = var2 != null ? var2.getYRot() : 0.0F;
            var5.moveTo(var3.x, var3.y, var3.z, var6, 0.0F);
            var5.setCustomName(Component.literal(this.displayName));
            var5.setCustomNameVisible(true);
            var5.addTag("enemy_sam");
            var5.addTag(this.enemyTag);
            if (!var1.addFreshEntity(var5)) {
               return false;
            } else if (!F16Addon.initializeGunData(var5)) {
               var5.discard();
               message(var2, "Wasp SAM gun data failed to initialize; see the log.", ChatFormatting.RED);
               return false;
            } else {
               joinEnemyTeam(var1, var5);
               this.fillAmmo(var5);
               message(var2, this.displayName + " deployed as a stationary enemy SAM.", ChatFormatting.YELLOW);
               return true;
            }
         }
      }
   }

   private static void joinEnemyTeam(Level var0, Entity var1) {
      try {
         Scoreboard var2 = var0.getScoreboard();
         PlayerTeam var3 = var2.getPlayerTeam("enemy");
         if (var3 == null) {
            var3 = var2.addPlayerTeam("enemy");
            var3.setColor(ChatFormatting.RED);
            var3.setAllowFriendlyFire(false);
         }

         var2.addPlayerToTeam(var1.getStringUUID(), var3);
      } catch (Exception var4) {
      }
   }

   private void fillAmmo(Entity var1) {
      ((IForgeEntity)var1).getCapability(ForgeCapabilities.ITEM_HANDLER).ifPresent(var0 -> {
         Item var1x = (Item)ForgeRegistries.ITEMS.getValue(new ResourceLocation("superbwarfare", "medium_anti_air_missile"));
         if (var1x != null) {
            for (int var2 = 0; var2 < var0.getSlots(); var2++) {
               var0.insertItem(var2, new ItemStack(var1x, 64), false);
            }
         }
      });
   }

   private static void message(Player var0, String var1, ChatFormatting var2) {
      if (var0 != null) {
         var0.displayClientMessage(Component.literal(var1).withStyle(var2), false);
      }
   }
}
