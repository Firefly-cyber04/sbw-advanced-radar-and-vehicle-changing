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

      /** Snapshots: AI code spawns / discards entities while iterating, which fires join / leave events. */
      public Entity[] f16() {
         return f16.toArray(EMPTY);
      }

      public Entity[] ground() {
         return ground.toArray(EMPTY);
      }

      public Entity[] sams() {
         return sam.toArray(EMPTY);
      }

      public Entity[] vehicles() {
         return vehicles.toArray(EMPTY);
      }

      public Entity[] projectiles() {
         return projectiles.toArray(EMPTY);
      }

      public Entity[] extra() {
         return extra.toArray(EMPTY);
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

      void add(Entity e) {
         Set<String> tags = e.getTags();
         if (tags.contains(TAG_F16)) {
            f16.add(e);
         } else if (tags.contains(TAG_GROUND)) {
            ground.add(e);
         } else if (tags.contains(TAG_SAM)) {
            sam.add(e);
         }

         if (e instanceof Projectile) {
            projectiles.add(e);
         } else if (Sbw.isVehicle(e)) {
            vehicles.add(e);
         } else if (Config.extraTargetTypes().contains(e.getType())) {
            extra.add(e);
         }
      }

      void remove(Entity e) {
         f16.remove(e);
         ground.remove(e);
         sam.remove(e);
         vehicles.remove(e);
         projectiles.remove(e);
         extra.remove(e);
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

   static void onLevelUnload(LevelEvent.Unload event) {
      if (event.getLevel() instanceof ServerLevel level) {
         LEVELS.remove(level);
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
}
