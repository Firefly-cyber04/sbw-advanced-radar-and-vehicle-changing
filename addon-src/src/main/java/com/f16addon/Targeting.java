package com.f16addon;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.scores.Team;

/**
 * Shared friend-or-foe logic and contact picture for all enemy AI.
 *
 * <p>1.0.0 had three slightly different copies of this. The F-16 accepted any {@code LivingEntity} (cows, villagers,
 * zombies) and recognised vehicles by {@code getClass().getName().contains("VehicleEntity")}, which is false for every
 * concrete SBW / DragonRise vehicle — so jets chased sheep but never engaged a tank or an empty enemy jet.
 */
public final class Targeting {
   public static final String ENEMY_TEAM = "enemy";

   private Targeting() {
   }

   /** One radar contact. A player in a vehicle is merged into the vehicle so it is seen (and locked) once. */
   public record Contact(Entity entity, boolean playerManned, boolean air, double distSqr) {
   }

   public static boolean isEnemyForceUnit(Entity e) {
      Set<String> tags = e.getTags();
      return tags.contains(UnitTracker.TAG_F16) || tags.contains(UnitTracker.TAG_GROUND) || tags.contains(UnitTracker.TAG_SAM);
   }

   /** True if {@code other} is on our side (an enemy-force unit, same / allied team, or the "enemy" team). */
   public static boolean isFriendly(Entity self, Entity other) {
      if (other == self || isEnemyForceUnit(other)) {
         return true;
      }

      Team theirs = other.getTeam();
      if (theirs == null) {
         return false;
      }

      Team ours = self.getTeam();
      if (ours != null && (ours == theirs || ours.isAlliedTo(theirs))) {
         return true;
      }

      return ENEMY_TEAM.equals(theirs.getName());
   }

   /**
    * A vehicle is friendly if it is one of ours or whoever is in it is friendly — unless a hostile player is at the
    * controls (a hijacked enemy jet is an enemy jet; 1.0.0 handled this for the SAM only).
    */
   public static boolean isFriendlyVehicle(Entity self, Entity vehicle) {
      if (vehicle == self) {
         return true;
      }

      if (vehicle.getControllingPassenger() instanceof Player driver && !isFriendly(self, driver)) {
         return false;
      }

      if (isFriendly(self, vehicle)) {
         return true;
      }

      for (Entity passenger : vehicle.getPassengers()) {
         if (isFriendly(self, passenger)) {
            return true;
         }
      }

      return false;
   }

   /** Vehicle-aware hostility test used for manual locks and cached targets. */
   public static boolean isHostile(Entity self, Entity other) {
      return Sbw.isVehicle(other) ? !isFriendlyVehicle(self, other) : !isFriendly(self, other);
   }

   public static boolean isFriendlyOwner(Entity self, Projectile projectile) {
      Entity owner = projectile.getOwner();
      if (owner == null) {
         return false;
      }

      if (isFriendly(self, owner)) {
         return true;
      }

      Entity ride = owner.getVehicle();
      return ride != null && isFriendly(self, ride.getRootVehicle());
   }

   public static boolean isTargetablePlayer(Player player) {
      return player.isAlive() && !player.isSpectator() && (!player.isCreative() || Config.TARGET_CREATIVE_PLAYERS.get());
   }

   public static boolean hasPlayer(Entity vehicle) {
      for (Entity passenger : vehicle.getIndirectPassengers()) {
         if (passenger instanceof Player) {
            return true;
         }
      }

      return false;
   }

   /**
    * Counts as a combatant: has passengers, is a drone (remote-controlled, never has passengers), or was put on a
    * scoreboard team by a map maker (non-friendly teams are checked by the caller).
    */
   public static boolean isCrewed(Entity vehicle) {
      return !vehicle.getPassengers().isEmpty() || vehicle.getTeam() != null || "DRONE".equals(Sbw.vehicleType(vehicle));
   }

   /** Airborne = elytra flight, or an aircraft that is off the ground. */
   public static boolean isAirborne(Entity e) {
      if (e instanceof Player p && p.isFallFlying()) {
         return true;
      }

      return Sbw.isAircraft(e) && !e.onGround();
   }

   /**
    * Builds the contact picture around {@code self}: players (merged into their vehicle), manned / hostile vehicles,
    * and configured extra mob types. Costs O(players + vehicles) instead of an AABB query over millions of blocks.
    */
   public static List<Contact> contacts(ServerLevel level, Entity self, double range) {
      UnitTracker.Units units = UnitTracker.get(level);
      double rangeSqr = range * range;
      Map<Entity, Boolean> seen = new IdentityHashMap<>();
      List<Contact> out = new ArrayList<>();

      for (ServerPlayer player : level.players()) {
         if (!isTargetablePlayer(player) || isFriendly(self, player)) {
            continue;
         }

         Entity root = player.getRootVehicle();
         Entity body = root != player && Sbw.isVehicle(root) ? root : player;
         if (seen.put(body, Boolean.TRUE) != null || body == self || (body != player && isFriendlyVehicle(self, body))) {
            continue;
         }

         double d = self.distanceToSqr(body);
         if (d <= rangeSqr) {
            out.add(new Contact(body, true, isAirborne(body), d));
         }
      }

      boolean emptyOk = Config.TARGET_EMPTY_VEHICLES.get();
      for (Entity vehicle : units.vehicles()) {
         if (vehicle == self || seen.containsKey(vehicle) || !UnitTracker.isLive(level, vehicle) || vehicle.getVehicle() != null) {
            continue;
         }

         double d = self.distanceToSqr(vehicle);
         if (d > rangeSqr || isFriendlyVehicle(self, vehicle) || Sbw.isWrecked(vehicle)) {
            continue;
         }

         if (!emptyOk && !isCrewed(vehicle)) {
            continue;
         }

         seen.put(vehicle, Boolean.TRUE);
         out.add(new Contact(vehicle, hasPlayer(vehicle), isAirborne(vehicle), d));
      }

      for (Entity mob : units.extra()) {
         if (seen.containsKey(mob) || !UnitTracker.isLive(level, mob) || isFriendly(self, mob)) {
            continue;
         }

         double d = self.distanceToSqr(mob);
         if (d <= rangeSqr) {
            out.add(new Contact(mob, false, isAirborne(mob), d));
         }
      }

      return out;
   }

   /** Re-validates a remembered target without a scan. */
   public static boolean stillHostile(ServerLevel level, Entity self, Entity target) {
      if (!UnitTracker.isLive(level, target) || !isHostile(self, target)) {
         return false;
      }

      if (target instanceof Player player) {
         return isTargetablePlayer(player);
      }

      if (Sbw.isVehicle(target)) {
         if (Sbw.isWrecked(target)) {
            return false;
         }

         return Config.TARGET_EMPTY_VEHICLES.get() || isCrewed(target);
      }

      return Config.extraTargetTypes().contains(target.getType());
   }

   /** Resolves a target remembered by UUID string; returns null for empty / malformed ids. */
   public static Entity byUuid(ServerLevel level, String id) {
      if (id == null || id.isEmpty() || "none".equals(id)) {
         return null;
      }

      try {
         return level.getEntity(UUID.fromString(id));
      } catch (IllegalArgumentException e) {
         return null;
      }
   }

   // ----------------------------------------------------------------------------------------------------------------
   // Engagement board: who is already shooting at whom
   // ----------------------------------------------------------------------------------------------------------------

   private static final Map<UUID, List<Entity>> IN_FLIGHT = new HashMap<>();

   /** Records a missile fired by an enemy unit at {@code target}. */
   public static void registerShot(Entity target, Entity missile) {
      IN_FLIGHT.computeIfAbsent(target.getUUID(), k -> new ArrayList<>(2)).add(missile);
   }

   /** Live enemy-force missiles currently flying at {@code target}. */
   public static int missilesInFlight(Entity target) {
      List<Entity> list = IN_FLIGHT.get(target.getUUID());
      if (list == null) {
         return 0;
      }

      list.removeIf(m -> m.isRemoved() || !m.isAlive());
      if (list.isEmpty()) {
         IN_FLIGHT.remove(target.getUUID());
         return 0;
      }

      return list.size();
   }

   /** Drops board entries whose missiles are all gone (called once a second). */
   static void pruneBoard() {
      Iterator<List<Entity>> it = IN_FLIGHT.values().iterator();
      while (it.hasNext()) {
         List<Entity> list = it.next();
         list.removeIf(m -> m.isRemoved() || !m.isAlive());
         if (list.isEmpty()) {
            it.remove();
         }
      }
   }

   static void clearBoard() {
      IN_FLIGHT.clear();
   }
}
