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
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.registries.ForgeRegistries;

public class EnemySamDeployerItem extends Item {
   private final String entityPath;
   private final String displayName;
   private final String enemyTag;

   public EnemySamDeployerItem(Item.Properties properties, String entityPath, String displayName, String enemyTag) {
      super(properties);
      this.entityPath = entityPath;
      this.displayName = displayName;
      this.enemyTag = enemyTag;
   }

   @Override
   public InteractionResult useOn(UseOnContext context) {
      Level level = context.getLevel();
      if (level.isClientSide) {
         return InteractionResult.SUCCESS;
      }

      Vec3 pos = context.getClickLocation().add(0.0, 1.0, 0.0);
      return this.spawn(level, context.getPlayer(), pos) ? InteractionResult.CONSUME : InteractionResult.FAIL;
   }

   @Override
   public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
      ItemStack stack = player.getItemInHand(hand);
      if (level.isClientSide) {
         return InteractionResultHolder.success(stack);
      }

      Vec3 pos = player.position().add(player.getLookAngle().scale(6.0)).add(0.0, 1.0, 0.0);
      return this.spawn(level, player, pos) ? InteractionResultHolder.consume(stack) : InteractionResultHolder.fail(stack);
   }

   private boolean spawn(Level level, Player player, Vec3 pos) {
      return deployAt(level, player, pos) != null;
   }

   /** Reuse deployment initialization for bounded tactical encounters. */
   public Entity deployAt(Level level, Player player, Vec3 pos) {
      EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(ResourceLocation.tryParse("dragonrise_reforge:" + this.entityPath));
      if (type == null) {
         message(player, "Missing entity type dragonrise_reforge:" + this.entityPath, ChatFormatting.RED);
         return null;
      }

      Entity entity = type.create(level);
      if (entity == null) {
         return null;
      }

      float yaw = player != null ? player.getYRot() : 0.0F;
      entity.moveTo(pos.x, pos.y, pos.z, yaw, 0.0F);
      entity.setCustomName(Component.literal(this.displayName));
      entity.setCustomNameVisible(true);
      entity.addTag(UnitTracker.TAG_SAM);
      entity.addTag(this.enemyTag);
      if (!level.addFreshEntity(entity)) {
         return null;
      }

      if (!F16Addon.initializeGunData(entity)) {
         entity.discard();
         message(player, "Wasp SAM gun data failed to initialize; see the log.", ChatFormatting.RED);
         return null;
      }

      joinEnemyTeam(level, entity);
      fillAmmo(entity);
      message(player, this.displayName + " deployed as a stationary enemy SAM.", ChatFormatting.YELLOW);
      return entity;
   }

   private static void joinEnemyTeam(Level level, Entity entity) {
      Scoreboard scoreboard = level.getScoreboard();
      PlayerTeam team = scoreboard.getPlayerTeam(Targeting.ENEMY_TEAM);
      if (team == null) {
         team = scoreboard.addPlayerTeam(Targeting.ENEMY_TEAM);
         team.setColor(ChatFormatting.RED);
         team.setAllowFriendlyFire(false);
      }

      scoreboard.addPlayerToTeam(entity.getStringUUID(), team);
   }

   private static void fillAmmo(Entity entity) {
      Item missile = ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse("superbwarfare:medium_anti_air_missile"));
      if (missile == null) {
         return;
      }

      entity.getCapability(ForgeCapabilities.ITEM_HANDLER).ifPresent(handler -> {
         for (int slot = 0; slot < handler.getSlots(); slot++) {
            handler.insertItem(slot, new ItemStack(missile, 64), false);
         }
      });
   }

   private static void message(Player player, String text, ChatFormatting color) {
      if (player != null) {
         player.displayClientMessage(Component.literal(text).withStyle(color), false);
      }
   }
}
