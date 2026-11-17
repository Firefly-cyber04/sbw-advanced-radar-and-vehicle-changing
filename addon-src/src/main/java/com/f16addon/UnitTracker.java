package com.f16addon;

import it.unimi.dsi.fastutil.objects.ReferenceLinkedOpenHashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;

/**
 * Keeps per-level indices of the entities the addon cares about, maintained from join / leave events.
 *
 * <p>1.0.0 copied {@code level.getAllEntities()} into a new list every tick (twice, counting WaspEvents) and ran
 * string checks on each of them; target scans then did 2000x2000x2000-block AABB queries. With these indices a tick
 * only touches our own units and the (small) set of live projectiles, and target scans only look at players and
 * vehicles.
 */
public final class UnitTracker {
   public static final String TAG_F16 = "enemy_f16";
   public static final String TAG_GROUND = "enemy_ground";
   public static final String TAG_SAM = "enemy_sam";

   private static final Entity[] EMPTY = new Entity[0];
   private static final Map<ServerLevel, Units> LEVELS = new IdentityHashMap<>();
   private static final int RECONCILE_INTERVAL = 600;

   private UnitTracker() {
   }

   public static final class Units {
      final Set<Entity> f16 = new ReferenceLinkedOpenHashSet<>();
      final Set<Entity> ground = new ReferenceLinkedOpenHashSet<>();
      final Set<Entity> sam = new ReferenceLinkedOpenHashSet<>();
      final Set<Entity> vehicles = new ReferenceLinkedOpenHashSet<>();
      final Set<Entity> projectiles = new ReferenceLinkedOpenHashSet<>();
      final Set<Entity> extra = new ReferenceLinkedOpenHashSet<>();
      int emptyTicks;


      public Entity[] f16() {
          Entity[] entityArray = this.f16Snapshot;
          if (entityArray == null) {
              this.f16Snapshot = entityArray = this.f16.toArray(EMPTY);
          }
          return entityArray;
      }

      public Entity[] ground() {
          Entity[] entityArray = this.groundSnapshot;
          if (entityArray == null) {
              this.groundSnapshot = entityArray = this.ground.toArray(EMPTY);
          }
          return entityArray;
      }

      public Entity[] sams() {
          Entity[] entityArray = this.samSnapshot;
          if (entityArray == null) {
              this.samSnapshot = entityArray = this.sam.toArray(EMPTY);
          }
          return entityArray;
      }

      public Entity[] vehicles() {
          Entity[] entityArray = this.vehicleSnapshot;
          if (entityArray == null) {
              this.vehicleSnapshot = entityArray = this.vehicles.toArray(EMPTY);
          }
          return entityArray;
      }

      public Entity[] projectiles() {
          Entity[] entityArray = this.projectileSnapshot;
          if (entityArray == null) {
              this.projectileSnapshot = entityArray = this.projectiles.toArray(EMPTY);
          }
          return entityArray;
      }

      public Entity[] extra() {
          Entity[] entityArray = this.extraSnapshot;
          if (entityArray == null) {
              this.extraSnapshot = entityArray = this.extra.toArray(EMPTY);
          }
          return entityArray;
      }

      public int vehicleCount() {
         return vehicles.size();
      }

      public int projectileCount() {
         return projectiles.size();
      }

      public int unitCount() {
         return f16.size() + ground.size() + sam.size();
      }

      void add(Entity entity) {
          Set set = entity.getTags();
          if (set.contains(UnitTracker.TAG_F16)) {
              if (this.f16.add(entity)) {
                  this.f16Snapshot = null;
              }
          } else if (set.contains(UnitTracker.TAG_GROUND)) {
              if (this.ground.add(entity)) {
                  this.groundSnapshot = null;
              }
          } else if (set.contains(UnitTracker.TAG_SAM) && this.sam.add(entity)) {
              this.samSnapshot = null;
          }
          if (entity instanceof Projectile) {
              if (!Classify.is(entity, Classify.MISSILE | Classify.AIR_DEFENSE_TARGET | Classify.DECOY) && !entity.getTags().contains(BattleBudget.AI_SHOT))
                  return;
              if (entity.getTags().contains(BattleBudget.AI_SHOT))
                  aiProjectiles.add(entity);
              if (this.projectiles.add(entity)) {
                  this.projectileSnapshot = null;
                  if (Classify.is(entity, 7) && this.aams.add(entity)) {
                      this.aamSnapshot = null;
                  }
              }
          } else if (Sbw.isVehicle(entity)) {
              if (this.vehicles.add(entity)) {
                  this.vehicleSnapshot = null;
              }
          } else if (Config.extraTargetTypes().contains(entity.getType()) && this.extra.add(entity)) {
              this.extraSnapshot = null;
          }
      }

      void remove(Entity entity) {
          aiProjectiles.remove(entity);
          if (this.f16.remove(entity)) {
              this.f16Snapshot = null;
          }
          if (this.ground.remove(entity)) {
              this.groundSnapshot = null;
          }
          if (this.sam.remove(entity)) {
              this.samSnapshot = null;
          }
          if (this.vehicles.remove(entity)) {
              this.vehicleSnapshot = null;
          }
          if (this.projectiles.remove(entity)) {
              this.projectileSnapshot = null;
              if (this.aams.remove(entity)) {
                  this.aamSnapshot = null;
              }
          }
          if (this.extra.remove(entity)) {
              this.extraSnapshot = null;
          }
      }

      final Set<Entity> aiProjectiles = new ReferenceLinkedOpenHashSet<>();

      final Set<Entity> aams = new ReferenceLinkedOpenHashSet();

      private Entity[] f16Snapshot;

      private Entity[] groundSnapshot;

      private Entity[] samSnapshot;

      private Entity[] vehicleSnapshot;

      private Entity[] projectileSnapshot;

      private Entity[] extraSnapshot;

      private Entity[] aamSnapshot;

      public Entity[] aams() {
          Entity[] entityArray = this.aamSnapshot;
          if (entityArray == null) {
              this.aamSnapshot = entityArray = this.aams.toArray(EMPTY);
          }
          return entityArray;
      }

      public int aiProjectileCount() {
          return this.aiProjectiles.size();
      }
   }

   public static Units get(ServerLevel level) {
      return LEVELS.computeIfAbsent(level, l -> new Units());
   }

   static void onJoin(EntityJoinLevelEvent event) {
      if (event.getLevel() instanceof ServerLevel level) {
         get(level).add(event.getEntity());
      }
   }

   static void onLeave(EntityLeaveLevelEvent event) {
      if (event.getLevel() instanceof ServerLevel level) {
         Units units = LEVELS.get(level);
         if (units != null) {
            units.remove(event.getEntity());
         }
      }
   }

   static void onLevelUnload(LevelEvent.Unload unload) {
       LevelAccessor levelAccessor = unload.getLevel();
       if (levelAccessor instanceof ServerLevel) {
           ServerLevel serverLevel = (ServerLevel) levelAccessor;
           LEVELS.remove(serverLevel);
           ThreatIndex.forget(serverLevel);
           BattleIndex.forget(serverLevel);
           BattleBudget.forget(serverLevel);
           Targeting.forget(serverLevel);
       }
   }

   /**
    * Entities that are no longer part of this level (join cancelled by another mod, dimension change, ...) are
    * dropped here. Every {@link #RECONCILE_INTERVAL} ticks a full pass also picks up units that were tagged after
    * they joined (e.g. {@code /tag @e add enemy_ground}) and config changes to {@code extraTargetTypes}.
    */
   static void maintain(ServerLevel level) {
      Units units = get(level);
      if (level.getGameTime() % RECONCILE_INTERVAL != 0L) {
         return;
      }

      Units fresh = new Units();
      for (Entity e : level.getAllEntities()) {
         if (e != null && !e.isRemoved()) {
            fresh.add(e);
         }
      }

      fresh.emptyTicks = units.emptyTicks;
      LEVELS.put(level, fresh);
      if (units.unitCount() != fresh.unitCount()) {
         F16Addon.LOGGER.debug("UnitTracker reconciled {}: {} -> {} units", level.dimension().location(), units.unitCount(), fresh.unitCount());
      }
   }

   /**
    * Mirrors {@code ServerLevel.tick}: with no players and no force-loaded chunks for 300 ticks, vanilla stops ticking
    * every entity in the dimension. 1.0.0 kept running its AI from the server tick event anyway, so on an empty
    * dedicated server SAMs launched missiles that hung frozen at the rail and jets were dragged around by setPos.
    */
   public static boolean levelTicksEntities(ServerLevel level) {
      Units units = get(level);
      if (!level.players().isEmpty() || !level.getForcedChunks().isEmpty()) {
         units.emptyTicks = 0;
         return true;
      }

      return units.emptyTicks++ < 300;
   }

   /** True if the entity is still a live member of {@code level}. */
   public static boolean isLive(ServerLevel level, Entity e) {
      return e != null && !e.isRemoved() && e.isAlive() && e.level() == level;
   }

   static void clear() {
       LEVELS.clear();
       ThreatIndex.clear();
       BattleIndex.clear();
       BattleBudget.clear();
   }
}
