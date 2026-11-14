package com.f16addon;

import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;

public final class GroundUnitAI {
   private static final double ADVANCE_STANDOFF = 48.0;
   private static final double FOLLOW_STANDOFF = 24.0;
   private static final double MAX_SPEED = 0.18;
   private static final double PATROL_SPEED = 0.135;
   private static final double PATROL_RADIUS = 512.0;
   private static final double PATROL_MIN_RADIUS = 64.0;
   private static final float MAX_YAW_STEP = 2.2F;
   /** Max terrain step a tracked vehicle climbs per move; 1.0.0 teleported straight up cliffs. */
   private static final double MAX_STEP_UP = 1.25;
   private static final int STUCK_LIMIT = 60;
   /** Blocked this many ticks in a row → back up and try the other side. */
   private static final int REVERSE_AFTER = 20;
   private static final int REVERSE_TICKS = 25;
   private static final double REVERSE_SPEED = 0.09;
   /** Keep shooting at a target that ducked behind cover for this long before re-scanning. */
   private static final int LOS_MEMORY = 60;
   /** Without line of sight for this long, close in past the normal standoff to find a firing position. */
   private static final int BLIND_CLOSE_IN = 20;
   private static final double MIN_STANDOFF = 16.0;
   /** Route probing: candidate heading offsets, re-evaluated every PROBE_INTERVAL ticks. */
   private static final float[] STEER_OFFSETS = {0.0F, 25.0F, -25.0F, 50.0F, -50.0F, 80.0F, -80.0F, 115.0F, -115.0F};
   private static final int PROBE_INTERVAL = 10;
   private static final int PROBE_STEPS = 7;
   private static final double PROBE_STEP = 1.2;
   private static final double MAX_DROP = 4.0;

   private GroundUnitAI() {
   }

   public static void tick(ServerLevel level, Entity self) {
      CompoundTag data = self.getPersistentData();
      if (Sbw.isWrecked(self)) {
         clearTarget(self, data);
         self.setDeltaMovement(Vec3.ZERO);
         return;
      }

      String mode = data.getString("ground_mode");
      if (mode.isEmpty()) {
         mode = "advance";
         data.putString("ground_mode", mode);
      }

      if (mode.equals("stop")) {
         clearTarget(self, data);
         return;
      }

      int fireCooldown = data.getInt("ground_fire_cd");
      if (fireCooldown > 0) {
         data.putInt("ground_fire_cd", fireCooldown - 1);
      }

      Entity target = resolveTarget(level, self, data);
      if (target == null) {
         clearTarget(self, data);
         if (!mode.equals("hold")) {
            patrol(level, self, data);
         }

         return;
      }

      applyTarget(self, target);
      data.putBoolean("ground_cleared", false);
      Vec3 aim = leadPoint(self, target);
      Sbw.call(self, Sbw.TURRET_AIM, aim);
      Sbw.call(self, Sbw.ADJUST_TURRET);
      Vec3 toTarget = target.position().subtract(self.position());
      if (toTarget.lengthSqr() < 0.001) {
         return;
      }

      double distance = Math.sqrt(toTarget.lengthSqr());
      double standoff = mode.equals("follow") ? FOLLOW_STANDOFF : ADVANCE_STANDOFF;
      if (data.getInt("ground_blind_ticks") >= BLIND_CLOSE_IN) {
         standoff = MIN_STANDOFF;
      }

      if (!mode.equals("hold") && distance > standoff) {
         navigate(level, self, data, toTarget, MAX_SPEED);
      } else {
         // Stationary: keep the frontal armour toward the enemy.
         face(self, toTarget);
      }

      if (distance <= Config.GROUND_ATTACK_RANGE.get() && data.getInt("ground_fire_cd") <= 0) {
         if (WorldQueries.lineOfSight(level, shootPos(self), aim, self)) {
            data.putInt("ground_blind_ticks", 0);
            fire(level, self, data, aim);
         } else {
            data.putInt("ground_fire_cd", 5);
            int blind = data.getInt("ground_blind_ticks") + 5;
            data.putInt("ground_blind_ticks", blind);
            if (blind >= LOS_MEMORY) {
               // Target hid; look for something we can actually see.
               data.putString("ground_target", "");
               data.putInt("ground_blind_ticks", 0);
            }
         }
      }
   }

   private static void patrol(ServerLevel level, Entity self, CompoundTag data) {
      ensureHome(self, data);
      double pointX = data.getDouble("ground_patrol_x");
      double pointZ = data.getDouble("ground_patrol_z");
      boolean needPoint = !data.contains("ground_patrol_x")
         || self.distanceToSqr(pointX, self.getY(), pointZ) < 64.0
         || data.getBoolean("ground_repath");
      if (needPoint) {
         double homeX = data.getDouble("ground_home_x");
         double homeZ = data.getDouble("ground_home_z");
         // Prefer points inside the simulated area so the unit does not drive to the edge and stall there.
         for (int attempt = 0; attempt < 6; attempt++) {
            double angle = level.random.nextDouble() * Math.PI * 2.0;
            double radius = PATROL_MIN_RADIUS + level.random.nextDouble() * (PATROL_RADIUS - PATROL_MIN_RADIUS);
            pointX = homeX + Math.cos(angle) * radius;
            pointZ = homeZ + Math.sin(angle) * radius;
            if (WorldQueries.isEntityTicking(level, pointX, self.getY(), pointZ)) {
               break;
            }
         }

         data.putDouble("ground_patrol_x", pointX);
         data.putDouble("ground_patrol_z", pointZ);
         data.putBoolean("ground_repath", false);
      }

      Vec3 toPoint = new Vec3(pointX, self.getY(), pointZ).subtract(self.position());
      if (toPoint.lengthSqr() >= 4.0) {
         navigate(level, self, data, toPoint, PATROL_SPEED);
      }
   }

   private static void ensureHome(Entity self, CompoundTag data) {
      if (!data.contains("ground_home_x")) {
         data.putDouble("ground_home_x", self.getX());
         data.putDouble("ground_home_z", self.getZ());
      }
   }

   /**
    * Local navigation toward {@code toGoal}: probe a fan of headings for a drivable route (no walls higher than a
    * step, no cliffs, no water / lava, nothing outside the simulated area), turn the hull onto the best one, pivot in
    * place for large corrections, and back up when stuck. 1.1.0 drove straight at the goal and only noticed a wall when
    * it hit it.
    */
   private static void navigate(ServerLevel level, Entity self, CompoundTag data, Vec3 toGoal, double speed) {
      int reverse = data.getInt("ground_reverse");
      if (reverse > 0) {
         data.putInt("ground_reverse", reverse - 1);
         float side = data.getFloat("ground_steer_side") >= 0.0F ? 1.0F : -1.0F;
         turnTo(self, self.getYRot() + side * 90.0F);
         drive(level, self, data, -REVERSE_SPEED);
         return;
      }

      float goalYaw = F16FlightAI.yawOf(toGoal);
      float heading = goalYaw + steerOffset(level, self, data, goalYaw);
      float error = Math.abs(Mth.wrapDegrees(heading - self.getYRot()));
      turnTo(self, heading);
      if (error < 30.0F) {
         drive(level, self, data, speed);
      } else if (error < 70.0F) {
         drive(level, self, data, speed * 0.5);
      }
      // else: pivot in place (tracked vehicles can) instead of driving off in the wrong direction.
   }

   /** Heading offset (degrees) from the goal direction to the nearest drivable route, cached between probes. */
   private static float steerOffset(ServerLevel level, Entity self, CompoundTag data, float goalYaw) {
      boolean due = (level.getGameTime() + self.getId()) % PROBE_INTERVAL == 0L || !data.contains("ground_steer_off");
      if (!due) {
         return data.getFloat("ground_steer_off");
      }

      float previous = data.getFloat("ground_steer_off");
      float best = 180.0F;
      for (float offset : STEER_OFFSETS) {
         // Prefer the side we already chose so the hull does not oscillate around an obstacle.
         float candidate = offset != 0.0F && previous != 0.0F && Math.signum(offset) != Math.signum(previous) ? -offset : offset;
         if (passable(level, self, goalYaw + candidate)) {
            best = candidate;
            break;
         }
      }

      data.putFloat("ground_steer_off", best);
      if (best != 0.0F && best != 180.0F) {
         data.putFloat("ground_steer_side", Math.signum(best));
      }

      return best;
   }

   private static boolean passable(ServerLevel level, Entity self, float yawDeg) {
      double rad = Math.toRadians(yawDeg);
      double dx = -Math.sin(rad);
      double dz = Math.cos(rad);
      double prevY = self.getY() - 0.1;
      // Only refuse to drive *into* liquid: a unit that is already in water must be allowed to drive out.
      boolean avoidWater = Config.GROUND_AVOID_WATER.get() && !inLiquid(level, self);
      for (int step = 1; step <= PROBE_STEPS; step++) {
         double x = self.getX() + dx * step * PROBE_STEP;
         double z = self.getZ() + dz * step * PROBE_STEP;
         if (!WorldQueries.isEntityTicking(level, x, prevY, z)) {
            return false;
         }

         int h = WorldQueries.height(level, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z, Integer.MIN_VALUE);
         if (h == Integer.MIN_VALUE || h - prevY > MAX_STEP_UP || prevY - h > MAX_DROP) {
            return false;
         }

         if (avoidWater && isLiquid(level, x, h, z)) {
            return false;
         }

         prevY = h;
      }

      return true;
   }

   /** The heightmap top is a fluid surface (MOTION_BLOCKING counts fluids). */
   private static boolean isLiquid(ServerLevel level, double x, int surfaceY, double z) {
      FluidState fluid = WorldQueries.fluid(level, BlockPos.containing(x, surfaceY - 1, z));
      return fluid != null && !fluid.isEmpty();
   }

   private static boolean inLiquid(ServerLevel level, Entity self) {
      FluidState here = WorldQueries.fluid(level, self.blockPosition());
      FluidState below = WorldQueries.fluid(level, self.blockPosition().below());
      return here != null && !here.isEmpty() || below != null && !below.isEmpty();
   }

   private static void face(Entity self, Vec3 toTarget) {
      turnTo(self, F16FlightAI.yawOf(toTarget));
   }

   private static void turnTo(Entity self, float yaw) {
      float oldYaw = self.getYRot();
      self.setYRot(oldYaw + Mth.clamp(Mth.wrapDegrees(yaw - oldYaw), -MAX_YAW_STEP, MAX_YAW_STEP));
      self.yRotO = oldYaw;
   }

   /**
    * One step along the hull heading (negative speed = reverse); refuses to enter non-ticking chunks, climb walls,
    * fall off cliffs or drive into water.
    */
   private static void drive(ServerLevel level, Entity self, CompoundTag data, double speed) {
      double yaw = Math.toRadians(self.getYRot());
      Vec3 next = self.position().add(-Math.sin(yaw) * speed, 0.0, Math.cos(yaw) * speed);
      if (!WorldQueries.isEntityTicking(level, next.x, next.y, next.z)) {
         blocked(data);
         return;
      }

      int ground = WorldQueries.height(level, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, next.x, next.z, Mth.floor(self.getY()));
      double y = ground + 0.1;
      if (y - self.getY() > MAX_STEP_UP || self.getY() - y > MAX_DROP
         || (Config.GROUND_AVOID_WATER.get() && isLiquid(level, next.x, ground, next.z) && !inLiquid(level, self))) {
         blocked(data);
         return;
      }

      data.putInt("ground_stuck", 0);
      self.setPos(next.x, y, next.z);
      self.setDeltaMovement(Vec3.ZERO);
      self.hasImpulse = true;
   }

   private static void blocked(CompoundTag data) {
      int stuck = data.getInt("ground_stuck") + 1;
      data.putInt("ground_stuck", stuck);
      if (stuck % REVERSE_AFTER == 0) {
         data.putInt("ground_reverse", REVERSE_TICKS);
         data.putFloat("ground_steer_side", -data.getFloat("ground_steer_side"));
         data.remove("ground_steer_off");
      }

      if (stuck >= STUCK_LIMIT) {
         data.putInt("ground_stuck", 0);
         data.putBoolean("ground_repath", true);
      }
   }

   private static boolean isBmpt(Entity self) {
      return Classify.is(self, Classify.BMPT);
   }

   private static Vec3 shootPos(Entity self) {
      Vec3 fallback = self.position().add(0.0, 1.2, 0.0).add(self.getLookAngle().scale(2.0));
      return Sbw.shootPos(self, isBmpt(self) ? "Cannon1" : "Cannon", fallback);
   }

   private static Vec3 leadPoint(Entity self, Entity target) {
      Vec3 point = WorldQueries.center(target);
      Vec3 velocity = target.getDeltaMovement();
      if (velocity.lengthSqr() < 1.0E-4) {
         return point;
      }

      double speed = isBmpt(self) ? 6.0 : 12.0;
      double ticks = Mth.clamp(point.distanceTo(self.position()) / speed, 0.0, 20.0);
      return point.add(velocity.scale(ticks));
   }

   private static EntityType<?> shellType(boolean bmpt) {
      return ForgeRegistries.ENTITY_TYPES.getValue(ResourceLocation.tryParse(bmpt ? "superbwarfare:small_cannon_shell" : "superbwarfare:cannon_shell"));
   }

   private static void fire(ServerLevel level, Entity self, CompoundTag data, Vec3 aim) {
      boolean bmpt = isBmpt(self);
      float damage = bmpt ? 15.0F : 250.0F;
      float explosionDamage = bmpt ? 18.0F : 120.0F;
      float explosionRadius = bmpt ? 5.0F : 10.0F;
      double speed = bmpt ? 6.0 : 12.0;
      int delay = bmpt ? 6 : 30;
      Vec3 shootPos = shootPos(self);
      Vec3 direction = aim.subtract(shootPos);
      if (direction.lengthSqr() < 0.01) {
         return;
      }

      direction = direction.normalize();
      EntityType<?> type = shellType(bmpt);
      Entity projectile = type == null ? null : type.create(level);
      if (projectile == null) {
         return;
      }

      projectile.moveTo(shootPos.x, shootPos.y, shootPos.z, F16FlightAI.yawOf(direction), F16FlightAI.pitchOf(direction));
      projectile.setDeltaMovement(direction.scale(speed));
      if (projectile instanceof Projectile p) {
         p.setOwner(self);
      }

      Sbw.call(projectile, Sbw.SET_DAMAGE, damage);
      Sbw.call(projectile, Sbw.SET_EXPLOSION_DAMAGE, explosionDamage);
      Sbw.call(projectile, Sbw.SET_EXPLOSION_RADIUS, explosionRadius);
      if (level.addFreshEntity(projectile)) {
         data.putInt("ground_fire_cd", delay);
      }
   }

   private static boolean engageable(Targeting.Contact c) {
      // A tank gun cannot track a fast jet; helicopters / drones / elytra players are fair game.
      return !c.air() || !Sbw.isJet(c.entity()) || Config.GROUND_ENGAGE_JETS.get();
   }

   private static Entity resolveTarget(ServerLevel level, Entity self, CompoundTag data) {
      double range = Config.GROUND_SCAN_RANGE.get();
      String targetId = data.getString("ground_target");
      boolean lost = false;
      if (!targetId.isEmpty()) {
         Entity current = Targeting.byUuid(level, targetId);
         if (current != null && Targeting.stillHostile(level, self, current) && self.distanceToSqr(current) <= range * range) {
            return current;
         }

         data.putString("ground_target", "");
         lost = true;
      }

      boolean scheduled = (level.getGameTime() + self.getId()) % Config.GROUND_SCAN_INTERVAL.get() == 0L;
      if (!scheduled && !lost) {
         return null;
      }

      List<Targeting.Contact> contacts = Targeting.contacts(level, self, range);
      contacts.removeIf(c -> !engageable(c));
      // Players (and player-crewed vehicles) first, then nearest — same priority as 1.0.0.
      contacts.sort(Comparator.<Targeting.Contact>comparingInt(c -> c.playerManned() || c.entity() instanceof Player ? 0 : 1)
         .thenComparingDouble(Targeting.Contact::distSqr));
      if (contacts.isEmpty()) {
         return null;
      }

      // Prefer something we can see right now; fall back to the top contact so the unit still closes in.
      Vec3 eye = shootPos(self);
      int budget = Config.MAX_LOS_CHECKS_PER_SCAN.get();
      Entity best = contacts.get(0).entity();
      for (int i = 0; i < contacts.size() && i < budget; i++) {
         Entity candidate = contacts.get(i).entity();
         if (WorldQueries.lineOfSight(level, eye, WorldQueries.center(candidate), self)) {
            best = candidate;
            break;
         }
      }

      data.putString("ground_target", best.getStringUUID());
      return best;
   }

   private static void applyTarget(Entity self, Entity target) {
      Sbw.call(self, Sbw.SET_AI_TURRET_TARGET, target.getStringUUID());
      Sbw.call(self, Sbw.SET_AI_PASSENGER_TARGET, target.getStringUUID());
   }

   private static void clearTarget(Entity self, CompoundTag data) {
      if (!data.getString("ground_target").isEmpty() || !data.getBoolean("ground_cleared")) {
         data.putString("ground_target", "");
         data.putBoolean("ground_cleared", true);
         Sbw.call(self, Sbw.SET_AI_TURRET_TARGET, "");
         Sbw.call(self, Sbw.SET_AI_PASSENGER_TARGET, "");
      }
   }
}
