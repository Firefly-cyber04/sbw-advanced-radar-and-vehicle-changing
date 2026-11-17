package com.f16addon;

import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.extensions.IForgeEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Enemy F-16 autopilot.
 *
 * <p>1.2.0 flight model changes (all still kinematic: the jet is moved with {@code setPos}, SBW physics never fights it):
 * <ul>
 *    <li><b>Terrain following</b>: ground is probed at 0.4 / 1 / 2 s along both the current and the commanded heading,
 *        and the jet pitches up early to clear it. 1.1.0 probed one point and, when that failed, teleported the jet
 *        straight up by up to 0.9 blocks per tick.</li>
 *    <li><b>Coordinated turns</b>: the jet rolls into a turn first (6°/tick, up to 65°) and its turn rate scales with
 *        bank; turning bleeds speed, diving gains it.</li>
 *    <li><b>Missile defence</b>: a closing AAM within {@code evasionRange} makes the jet beam it (turn perpendicular to
 *        the missile's line of sight, descend, max power) while dispensing decoys.</li>
 *    <li><b>Ground attack runs</b>: ingress → dive → egress → re-attack, instead of orbiting tightly over the target.</li>
 *    <li><b>Air combat</b>: lead pursuit scaled by range; extends away and comes back instead of circling forever when
 *        the target is behind it at close range.</li>
 *    <li><b>Deconfliction</b>: jets keep ~40 blocks from each other.</li>
 *    <li><b>Simulation edge</b>: the look-ahead for leaving the entity-ticking area is the turn radius, so the jet turns
 *        back in time instead of stopping dead in mid-air at the border.</li>
 *    <li><b>Take-off</b>: accelerating climb-out along the runway heading instead of rising vertically.</li>
 * </ul>
 */
public final class F16FlightAI {
   private static final double PATROL_RADIUS = 100.0;
   private static final double TAKEOFF_ALTITUDE = 70.0;
   private static final double MIN_SPEED = 3.34;
   private static final double MAX_SPEED = 5.0;
   private static final double PATROL_SPEED = 3.4;
   private static final double COMBAT_SPEED = 4.5;
   private static final double BREAK_SPEED = 4.0;
   private static final double GUN_SPEED = 8.0;
   /** Never closer than this to the terrain, whatever the manoeuvre. */
   private static final double HARD_FLOOR = 10.0;
   private static final float MAX_BANK = 65.0F;
   private static final float ROLL_RATE = 6.0F;
   private static final float MAX_CLIMB = 55.0F;
   private static final float MAX_DIVE = 45.0F;
   /** Re-target only if the new contact is clearly better (prevents flip-flopping between two similar targets). */
   private static final double SWITCH_HYSTERESIS = 0.6;
   private static final int DECOY_CHECK_INTERVAL = 10;
   private static final double SEPARATION = 40.0;

   // Ground attack run
   private static final int RUN_INGRESS = 0;
   private static final int RUN_DIVE = 1;
   private static final int RUN_EGRESS = 2;
   private static final double ATTACK_ALTITUDE = 70.0;
   private static final double DIVE_START = 260.0;
   private static final double PULL_OUT_DISTANCE = 70.0;
   private static final double PULL_OUT_AGL = 30.0;
   private static final double EGRESS_DISTANCE = 420.0;

   private static final String CANNON = "superbwarfare:small_cannon_shell";
   private static final String AIM9 = "superbwarfare:ru_9m336_missile";
   private static final String AIM120 = "dragonrise_reforge:aim120";
   private static final String BOMB = "superbwarfare:mk_82";
   private static final String AGM = "superbwarfare:kh_39";

   private F16FlightAI() {
   }

   public static boolean isWrecked(Entity self) {
      return Sbw.isWrecked(self);
   }

   public static void tick(ServerLevel serverLevel, Entity entity) {
       branch = "none";
       F16FlightAI.tickFlight(serverLevel, entity);
       if (Config.DEBUG_AI.get())
           F16FlightAI.logState(serverLevel, entity);
   }

   private static void initHome(Entity self, CompoundTag data) {
      if (!data.contains("f16_home_x")) {
         data.putDouble("f16_home_x", self.getX());
         data.putDouble("f16_home_y", self.getY());
         data.putDouble("f16_home_z", self.getZ());
         data.putFloat("f16_yaw", self.getYRot());
         data.putFloat("f16_orbit_angle", 0.0F);
         data.putBoolean("f16_airborne", false);
         data.putDouble("f16_speed", 0.0);
         data.putString("f16_mode", "auto");
      }

      if (!data.contains("f16_mode")) {
         data.putString("f16_mode", "auto");
      }
   }

   private static Vec3 home(CompoundTag data) {
      return new Vec3(data.getDouble("f16_home_x"), data.getDouble("f16_home_y"), data.getDouble("f16_home_z"));
   }

   private static double clearance() {
      return Config.F16_MIN_CLEARANCE.get();
   }

   /** Jets break off and fly home when they wander further than {@code leashRadius} from their home point. */
   private static boolean isLeashed(Entity self, CompoundTag data) {
      double leash = Config.F16_LEASH_RADIUS.get();
      return horizontalDistSqr(self.position(), home(data)) > leash * leash;
   }

   /**
    * Simulation edge: if the area one turn radius ahead stops being entity-ticking, turn toward the nearest player (the
    * center of the simulated area) without dropping the target. 1.1.0 looked 48 blocks ahead — less than half the
    * turn radius — reached the border mid-turn and stopped dead in the air, or flew into chunks where nothing ticks
    * and froze there.
    */
   private static Vec3 edgeEscape(ServerLevel level, Entity self, CompoundTag data, double speed) {
      Vec3 heading = horizontal(self.getLookAngle());
      double turnRadius = Math.max(speed, PATROL_SPEED) / (2.6 * Mth.DEG_TO_RAD);
      for (double f : new double[]{0.5, 0.9}) {
         Vec3 ahead = self.position().add(heading.scale(turnRadius * f));
         if (!WorldQueries.isEntityTicking(level, ahead.x, self.getY(), ahead.z)) {
            Player anchor = level.getNearestPlayer(self, -1.0);
            Vec3 center = anchor != null ? anchor.position() : home(data);
            return horizontal(center.subtract(self.position()));
         }
      }

      return null;
   }

   private static double horizontalDistSqr(Vec3 a, Vec3 b) {
      double dx = a.x - b.x;
      double dz = a.z - b.z;
      return dx * dx + dz * dz;
   }

   private static Vec3 horizontal(Vec3 v) {
      Vec3 h = new Vec3(v.x, 0.0, v.z);
      return h.lengthSqr() < 1.0E-6 ? new Vec3(0.0, 0.0, 1.0) : h.normalize();
   }

   private static int ground(ServerLevel level, CompoundTag data, double x, double z) {
      int fallback = data.contains("f16_last_ground") ? data.getInt("f16_last_ground") : Mth.floor(data.getDouble("f16_home_y"));
      int ground = WorldQueries.height(level, Heightmap.Types.WORLD_SURFACE, x, z, fallback);
      if (ground != fallback) {
         data.putInt("f16_last_ground", ground);
      }

      return ground;
   }

   /** Highest terrain under the next ~2 s of flight along both the current and the commanded heading. */
   private static double terrainAhead(ServerLevel level, Entity self, CompoundTag data, Vec3 desired, double speed) {
      Vec3 pos = self.position();
      Vec3 now = horizontal(self.getLookAngle());
      Vec3 want = horizontal(desired);
      double max = ground(level, data, pos.x, pos.z);
      for (double t : new double[]{8.0, 20.0, 40.0}) {
         double d = Math.max(16.0, speed * t);
         max = Math.max(max, ground(level, data, pos.x + now.x * d, pos.z + now.z * d));
         max = Math.max(max, ground(level, data, pos.x + want.x * d, pos.z + want.z * d));
      }

      return max;
   }

   /**
    * Accelerating climb-out along the spawn heading. If terrain blocks the runway the jet climbs steeper, and as a last
    * resort straight up (the 1.1.0 behaviour), so it never drives into a hill.
    */
   private static void takeOff(ServerLevel level, Entity self, CompoundTag data) {
      double homeY = data.getDouble("f16_home_y");
      int groundHere = ground(level, data, self.getX(), self.getZ());
      double targetY = Math.max(homeY + TAKEOFF_ALTITUDE, groundHere + clearance() + 5.0);
      double speed = Math.min(PATROL_SPEED, data.getDouble("f16_climb_speed") + 0.06);
      data.putDouble("f16_climb_speed", speed);
      if (self.getY() >= targetY) {
         data.putBoolean("f16_airborne", true);
         data.putDouble("f16_climb_speed", 0.0);
         data.putDouble("f16_speed", Math.max(PATROL_SPEED, speed));
         if (!data.getBoolean("f16_gear_up")) {
            Sbw.call(self, Sbw.SET_GEAR_UP, true);
            data.putBoolean("f16_gear_up", true);
         }

         return;
      }

      float yaw = data.getFloat("f16_yaw");
      Vec3 heading = horizontal(forwardOf(yaw, 0.0F));
      double probe = Math.max(24.0, speed * 15.0);
      double aheadGround = ground(level, data, self.getX() + heading.x * probe, self.getZ() + heading.z * probe);
      float pitch;
      double forward;
      if (aheadGround > self.getY() + probe * 0.7) {
         pitch = -80.0F;
         forward = 0.0;
      } else if (aheadGround > self.getY() - 2.0) {
         pitch = -35.0F;
         forward = speed * 0.8;
      } else {
         pitch = -18.0F;
         forward = speed;
      }

      double climb = Math.max(0.25, speed * Math.sin(-pitch * Mth.DEG_TO_RAD));
      Vec3 next = new Vec3(self.getX() + heading.x * forward, Math.min(targetY, self.getY() + climb), self.getZ() + heading.z * forward);
      if (!WorldQueries.isEntityTicking(level, next.x, next.y, next.z)) {
         next = new Vec3(self.getX(), Math.min(targetY, self.getY() + climb), self.getZ());
      }

      float oldPitch = self.getXRot();
      self.setYRot(yaw);
      self.setXRot(oldPitch + Mth.clamp(pitch - oldPitch, -3.0F, 3.0F));
      self.yRotO = yaw;
      self.xRotO = oldPitch;
      self.setPos(next.x, next.y, next.z);
      self.setDeltaMovement(Vec3.ZERO);
      self.hasImpulse = true;
   }

   private static void patrol(ServerLevel serverLevel, Entity entity, CompoundTag compoundTag) {
       Vec3 vec3;
       Vec3 vec32 = F16FlightAI.home(compoundTag);
       float f = compoundTag.getFloat("f16_orbit_angle") + 0.034f;
       if (f > (float) Math.PI * 2) {
           f -= (float) Math.PI * 2;
       }
       compoundTag.putFloat("f16_orbit_angle", f);
       double d = vec32.x + Math.cos(f) * Math.min(100.0, Config.SUPPLY_RADIUS.get() * 0.65);
       double d2 = vec32.z + Math.sin(f) * Math.min(100.0, Config.SUPPLY_RADIUS.get() * 0.65);
       int n = F16FlightAI.ground(serverLevel, compoundTag, d, d2);
       double d3 = Math.max((double) n + F16FlightAI.clearance() + 5.0, vec32.y + 50.0);
       Vec3 vec33 = new Vec3(d, d3, d2).subtract(entity.position());
       Vec3 vec34 = new Vec3(-Math.sin(f), 0.0, Math.cos(f));
       if (vec33.lengthSqr() > 1.0) {
           Vec3 vec35 = vec33.normalize();
           vec3 = vec33.length() > 170.0 ? vec35 : vec34.scale(0.78).add(vec35.scale(0.42));
       } else {
           vec3 = vec34;
       }
       vec3 = vec3.add(0.0, F16FlightAI.altitudeCorrection(entity.getY(), d3, 0.06), 0.0);
       F16FlightAI.steer(serverLevel, entity, compoundTag, vec3, 3.4, 2.2f, 1.4f, F16FlightAI.clearance());
   }

   /** Beam the missile: fly perpendicular to its line of sight (kills its closure / doppler), low and fast. */
   private static void evade(ServerLevel level, Entity self, CompoundTag data, Entity missile) {
      Vec3 los = horizontal(missile.position().subtract(self.position()));
      Vec3 left = new Vec3(los.z, 0.0, -los.x);
      Vec3 look = horizontal(self.getLookAngle());
      // Keep turning the way we already face so the jet does not reverse its turn every tick.
      Vec3 beam = left.dot(look) >= 0.0 ? left : left.scale(-1.0);
      data.putInt("f16_break_ticks", 0);
      steer(level, self, data, beam.add(0.0, -0.2, 0.0), MAX_SPEED, 3.4F, 2.2F, clearance() * 0.6);
   }

   private static void combat(ServerLevel level, Entity self, CompoundTag data, Entity target) {
      trackTarget(data, target);
      double distance = self.position().distanceTo(target.position());
      boolean air = Targeting.isAirborne(target) || isAboveUs(self, target);
      if (air) {
         data.putInt("f16_run_phase", RUN_INGRESS);
         airCombat(level, self, data, target, distance);
      } else {
         groundAttack(level, self, data, target);
      }

      fire(level, self, data, target, distance, air);
   }

   private static void airCombat(ServerLevel level, Entity self, CompoundTag data, Entity target, double distance) {
      int breakTicks = data.getInt("f16_break_ticks");
      if (distance < 34.0 && breakTicks <= 0) {
         breakTicks = 32;
         data.putInt("f16_break_ticks", breakTicks);
         Vec3 toTarget = target.position().subtract(self.position()).normalize();
         double side = self.getLookAngle().cross(toTarget).y >= 0.0 ? 1.0 : -1.0;
         data.putFloat("f16_break_side", (float) side);
      }

      if (breakTicks > 0) {
         data.putInt("f16_break_ticks", breakTicks - 1);
         Vec3 away = self.position().subtract(target.position());
         if (away.lengthSqr() < 0.01) {
            away = self.getLookAngle();
         }

         away = away.normalize();
         Vec3 side = new Vec3(-away.z, 0.0, away.x).scale(data.getFloat("f16_break_side"));
         steer(level, self, data, away.add(side.scale(0.75)).add(0.0, 0.18, 0.0), BREAK_SPEED, 3.4F, 2.0F, clearance());
         return;
      }

      Vec3 toTarget = target.position().subtract(self.position());
      double facing = toTarget.lengthSqr() < 0.01 ? 1.0 : self.getLookAngle().normalize().dot(toTarget.normalize());
      int extend = data.getInt("f16_extend_ticks");
      if (extend <= 0 && facing < -0.25 && distance < 180.0) {
         // Target behind us and close: a tight circle never brings the nose around. Extend, then come back.
         extend = 50;
      }

      if (extend > 0) {
         data.putInt("f16_extend_ticks", extend - 1);
         steer(level, self, data, horizontal(self.getLookAngle()).add(0.0, 0.08, 0.0), MAX_SPEED, 1.0F, 1.2F, clearance());
         if (distance > 320.0) {
            data.putInt("f16_extend_ticks", 0);
         }

         return;
      }

      // Lead pursuit: aim ahead of the target by a fraction of the flight time to it.
      double speed = Math.max(data.getDouble("f16_speed"), MIN_SPEED);
      double leadTicks = Mth.clamp(distance / speed * 0.6, 3.0, 30.0);
      Vec3 aim = WorldQueries.center(target).add(targetVelocity(data).scale(leadTicks));
      Vec3 desired = aim.subtract(self.position());
      if (desired.lengthSqr() < 0.01) {
         desired = self.getLookAngle();
      }

      double throttle = distance > 400.0 ? MAX_SPEED : COMBAT_SPEED;
      steer(level, self, data, desired.normalize(), throttle, 2.6F, 1.7F, clearance() * 0.8);
   }

   /** Ingress at attack altitude → dive on the target → egress past it → turn around for the next run. */
   private static void groundAttack(ServerLevel level, Entity self, CompoundTag data, Entity target) {
      Vec3 targetPos = WorldQueries.center(target);
      Vec3 flat = new Vec3(targetPos.x - self.getX(), 0.0, targetPos.z - self.getZ());
      double hDist = flat.length();
      int groundAtTarget = ground(level, data, targetPos.x, targetPos.z);
      double agl = self.getY() - ground(level, data, self.getX(), self.getZ());
      Vec3 look = horizontal(self.getLookAngle());
      double facing = hDist < 0.01 ? 1.0 : look.dot(flat.scale(1.0 / hDist));
      int phase = data.getInt("f16_run_phase");
      int phaseTicks = data.getInt("f16_run_ticks") + 1;

      if (phase == RUN_DIVE && (hDist < PULL_OUT_DISTANCE || agl < PULL_OUT_AGL || facing < 0.3 || phaseTicks > 200)) {
         phase = RUN_EGRESS;
         phaseTicks = 0;
      } else if (phase == RUN_EGRESS && (hDist > EGRESS_DISTANCE || phaseTicks > 180)) {
         phase = RUN_INGRESS;
         phaseTicks = 0;
      } else if (phase == RUN_INGRESS && hDist < DIVE_START && facing > 0.85) {
         phase = RUN_DIVE;
         phaseTicks = 0;
      } else if (phase == RUN_INGRESS && hDist < PULL_OUT_DISTANCE * 1.5 && facing < 0.3) {
         // Overflew it without lining up: go out and set up a proper run.
         phase = RUN_EGRESS;
         phaseTicks = 0;
      }

      data.putInt("f16_run_phase", phase);
      data.putInt("f16_run_ticks", phaseTicks);
      switch (phase) {
         case RUN_DIVE -> {
            Vec3 aim = targetPos.add(targetVelocity(data).scale(10.0)).subtract(self.position());
            steer(level, self, data, aim.normalize(), MAX_SPEED, 1.6F, 2.0F, PULL_OUT_AGL * 0.8);
         }
         case RUN_EGRESS -> {
            Vec3 away = hDist < 0.01 ? look : flat.scale(-1.0 / hDist);
            // Blend with the current heading so the jet flies on past the target before it turns.
            Vec3 dir = phaseTicks < 40 ? look : away.add(look).normalize();
            double wantY = groundAtTarget + ATTACK_ALTITUDE + 20.0;
            steer(level, self, data, dir.add(0.0, altitudeCorrection(self.getY(), wantY, 0.05), 0.0), COMBAT_SPEED, 2.4F, 1.6F, clearance() * 0.8);
         }
         default -> {
            double wantY = Math.max(groundAtTarget + ATTACK_ALTITUDE, terrainAhead(level, self, data, flat, COMBAT_SPEED) + clearance() * 0.8);
            Vec3 dir = hDist < 0.01 ? look : flat.scale(1.0 / hDist);
            steer(level, self, data, dir.add(0.0, altitudeCorrection(self.getY(), wantY, 0.05), 0.0), COMBAT_SPEED, 2.6F, 1.7F, clearance() * 0.8);
         }
      }
   }

   /**
    * Smoothed per-tick velocity of the current target, measured from its position each tick. Player-driven SBW
    * vehicles move by client packets, so on the server their {@code getDeltaMovement()} is usually zero and every lead
    * computed from it was no lead at all.
    */
   private static void trackTarget(CompoundTag data, Entity target) {
      Entity body = target.getRootVehicle();
      String id = body.getStringUUID();
      Vec3 pos = body.position();
      if (id.equals(data.getString("f16_tv_id"))) {
         Vec3 measured = new Vec3(pos.x - data.getDouble("f16_tv_px"), pos.y - data.getDouble("f16_tv_py"), pos.z - data.getDouble("f16_tv_pz"));
         if (measured.lengthSqr() > 100.0) {
            measured = Vec3.ZERO; // teleport / respawn
         }

         Vec3 old = targetVelocity(data);
         Vec3 v = old.scale(0.6).add(measured.scale(0.4));
         data.putDouble("f16_tv_vx", v.x);
         data.putDouble("f16_tv_vy", v.y);
         data.putDouble("f16_tv_vz", v.z);
      } else {
         data.putString("f16_tv_id", id);
         Vec3 v = body.getDeltaMovement();
         data.putDouble("f16_tv_vx", v.x);
         data.putDouble("f16_tv_vy", v.y);
         data.putDouble("f16_tv_vz", v.z);
      }

      data.putDouble("f16_tv_px", pos.x);
      data.putDouble("f16_tv_py", pos.y);
      data.putDouble("f16_tv_pz", pos.z);
   }

   private static Vec3 targetVelocity(CompoundTag data) {
      return new Vec3(data.getDouble("f16_tv_vx"), data.getDouble("f16_tv_vy"), data.getDouble("f16_tv_vz"));
   }

   /** 1.0.0 treated anything 10 blocks above the jet as "air" — kept as a fallback for non-SBW flyers. */
   private static boolean isAboveUs(Entity self, Entity target) {
      return !target.onGround() && target.getY() > self.getY() + 10.0;
   }

   private static double altitudeCorrection(double currentY, double targetY, double gain) {
      return Mth.clamp((targetY - currentY) * gain, -0.28, 0.28);
   }


   private static Vec3 deconflict(ServerLevel level, Entity self, Vec3 desired) {
       Vec3 correction = Vec3.ZERO;
       for (Entity other : BattleIndex.vehicles(level, self, SEPARATION + 8.0)) {
           if (other == self || other.isRemoved() || !other.getTags().contains(UnitTracker.TAG_F16))
               continue;
           Vec3 delta = self.position().subtract(other.position());
           double distanceSquared = delta.lengthSqr();
           if (distanceSquared >= SEPARATION * SEPARATION || distanceSquared <= 0.0001)
               continue;
           double distance = Math.sqrt(distanceSquared);
           correction = correction.add(delta.scale((SEPARATION - distance) / (SEPARATION * distance)));
       }
       return correction.lengthSqr() < 0.000001 ? desired : desired.add(correction.scale(0.8)).normalize();
   }

   /**
    * The one place the jet moves. Rolls into the turn, turns at a bank-limited rate, keeps {@code minClearance} over
    * the terrain ahead, manages speed, and steps along the nose.
    */
   private static void steer(ServerLevel level, Entity self, CompoundTag data, Vec3 desiredDir, double targetSpeed, float maxYawStep,
                             float maxPitchStep, double minClearance) {
      if (desiredDir.lengthSqr() < 1.0E-4) {
         return;
      }

      Vec3 desired = deconflict(level, self, desiredDir.normalize());
      double speed = data.getDouble("f16_speed");
      Vec3 inward = edgeEscape(level, self, data, speed);
      if (inward != null) {
         desired = new Vec3(inward.x, Mth.clamp(desired.y, -0.2, 0.3), inward.z).normalize();
         maxYawStep = Math.max(maxYawStep, 2.6F);
      }

      double terrain = terrainAhead(level, self, data, desired, speed);
      double safeY = terrain + minClearance;
      boolean terrainLimited = self.getY() < safeY;
      if (terrainLimited) {
         // Climb angle that clears the terrain within ~1 s of flight; overrides any dive command.
         double need = Mth.clamp((safeY - self.getY()) / Math.max(20.0, speed * 20.0), 0.12, 0.85);
         Vec3 flat = horizontal(desired);
         desired = new Vec3(flat.x, Math.max(desired.y, need), flat.z).normalize();
      }

      float desiredYaw = yawOf(desired);
      float desiredPitch = Mth.clamp(pitchOf(desired), -MAX_CLIMB, MAX_DIVE);
      float oldYaw = self.getYRot();
      float oldPitch = self.getXRot();
      float yawError = Mth.wrapDegrees(desiredYaw - oldYaw);

      // Coordinated turn: bank toward the turn, turn rate follows bank (a little rudder authority wings-level).
      float roll = Sbw.call(self, Sbw.GET_ROLL) instanceof Number n ? n.floatValue() : 0.0F;
      float targetRoll = Mth.clamp(-yawError * 2.5F, -MAX_BANK, MAX_BANK);
      float newRoll = roll + Mth.clamp(targetRoll - roll, -ROLL_RATE, ROLL_RATE);
      float bankFactor = 0.35F + 0.65F * Math.min(1.0F, Math.abs(newRoll) / 45.0F);
      float yawLimit = maxYawStep * bankFactor;
      float yaw = oldYaw + Mth.clamp(yawError, -yawLimit, yawLimit);
      float pitchStep = terrainLimited ? Math.max(maxPitchStep, 3.0F) : maxPitchStep;
      float pitch = Mth.clamp(oldPitch + Mth.clamp(Mth.wrapDegrees(desiredPitch - oldPitch), -pitchStep, pitchStep), -MAX_CLIMB, MAX_DIVE);
      self.setYRot(yaw);
      self.setXRot(pitch);
      self.yRotO = oldYaw;
      self.xRotO = oldPitch;
      Sbw.call(self, Sbw.SET_PREV_ROLL, roll);
      Sbw.call(self, Sbw.SET_ROLL, newRoll);

      // Energy: hard turns bleed speed, dives (positive pitch = nose down) add it, climbs cost it.
      double turnLoad = maxYawStep <= 0.0F ? 0.0 : Math.abs(yaw - oldYaw) / maxYawStep;
      double wanted = targetSpeed - 0.5 * turnLoad + 0.9 * Math.sin(pitch * Mth.DEG_TO_RAD);
      wanted = Mth.clamp(wanted, MIN_SPEED, MAX_SPEED);
      speed = Mth.clamp(speed + Mth.clamp(wanted - speed, -0.08, 0.06), MIN_SPEED, MAX_SPEED);
      data.putDouble("f16_speed", speed);

      Vec3 next = self.position().add(forwardOf(yaw, pitch).scale(speed));
      if (!WorldQueries.isEntityTicking(level, next.x, next.y, next.z)) {
         // Never move into chunks that are unloaded or do not tick (the AI would freeze there); edgeEscape normally
         // turns the jet around long before this.
         self.setDeltaMovement(Vec3.ZERO);
         return;
      }

      double hardFloor = ground(level, data, next.x, next.z) + HARD_FLOOR;
      if (next.y < hardFloor) {
         next = new Vec3(next.x, Math.min(hardFloor, self.getY() + 1.5), next.z);
         if (next.y < hardFloor - 4.0) {
            next = new Vec3(next.x, hardFloor, next.z);
         }
      }

      self.setPos(next.x, next.y, next.z);
      self.setDeltaMovement(Vec3.ZERO);
      self.hasImpulse = true;
   }

   static float yawOf(Vec3 dir) {
      return (float) Math.toDegrees(Math.atan2(-dir.x, dir.z));
   }

   static float pitchOf(Vec3 dir) {
      return (float) -Math.toDegrees(Math.asin(Mth.clamp(dir.y, -1.0, 1.0)));
   }

   private static Vec3 forwardOf(float yawDeg, float pitchDeg) {
      double yaw = Math.toRadians(yawDeg);
      double pitch = Math.toRadians(pitchDeg);
      double cosPitch = Math.cos(pitch);
      return new Vec3(-Math.sin(yaw) * cosPitch, -Math.sin(pitch), Math.cos(yaw) * cosPitch);
   }

   // ----------------------------------------------------------------------------------------------------------------
   // Targeting
   // ----------------------------------------------------------------------------------------------------------------

   private static double rangeFor(Entity target) {
      return Classify.isFifthGen(target) ? Config.F16_STEALTH_RANGE.get() : Config.F16_SCAN_RANGE.get();
   }

   private static boolean inRange(Entity self, Entity target) {
      double range = rangeFor(target);
      return self.distanceToSqr(target) <= range * range;
   }

   /** Lower is more important: players first (1.0.0 design), then aircraft, then ground. */
   private static int tier(Targeting.Contact c) {
      return c.playerManned() || c.entity() instanceof Player ? 0 : (c.air() ? 1 : 2);
   }

   private static int tierOf(Entity e) {
      boolean manned = e instanceof Player || Targeting.hasPlayer(e);
      return manned ? 0 : (Targeting.isAirborne(e) ? 1 : 2);
   }

   private static Entity resolveTarget(ServerLevel level, Entity self, CompoundTag data) {
      String lockedId = data.getString("f16_locked_target");
      if (!lockedId.isEmpty()) {
         Entity locked = Targeting.byUuid(level, lockedId);
         // Manual locks may pick anything alive (e.g. a mob); only liveness / friendliness / range are checked.
         if (UnitTracker.isLive(level, locked) && Targeting.isHostile(self, locked) && inRange(self, locked)) {
            return locked;
         }

         data.putString("f16_locked_target", "");
      }

      String targetId = data.getString("f16_target");
      Entity current = Targeting.byUuid(level, targetId);
      if (current != null && (!Targeting.stillHostile(level, self, current) || !inRange(self, current))) {
         current = null;
      }

      boolean lost = false;
      if (current == null && !targetId.isEmpty()) {
         data.putString("f16_target", "");
         lost = true;
      }

      int interval = Config.F16_SCAN_INTERVAL.get();
      int lockout = data.getInt("f16_scan_lockout");
      if (lockout > 0) {
         data.putInt("f16_scan_lockout", lockout - 1);
         return current;
      }

      // Staggered by entity id so a squadron spawned in the same tick does not scan in the same tick;
      // losing the current target triggers an immediate re-scan instead of flying blind for up to 2 s.
      boolean scheduled = (level.getGameTime() + self.getId()) % interval == 0L;
      if (!scheduled && !lost) {
         return current;
      }

      Entity best = findBestTarget(level, self, current);
      if (best == null || best == current) {
         return current;
      }

      // Radar clutter: a scan may fail to pick up a NEW contact. A target already being fought is never dropped.
      if (level.random.nextFloat() < Config.F16_DETECTION_FAILURE.get()) {
         if (current == null) {
            data.putInt("f16_scan_lockout", interval);
         }

         return current;
      }

      data.putInt("f16_react_cd", Config.F16_REACTION_TICKS.get());
      data.putString("f16_target", best.getStringUUID());
      return best;
   }

   private static Entity findBestTarget(ServerLevel level, Entity self, Entity current) {
      List<Targeting.Contact> contacts = Targeting.contacts(level, self, Config.F16_SCAN_RANGE.get());
      Targeting.Contact best = null;
      for (Targeting.Contact c : contacts) {
         if (!inRange(self, c.entity())) {
            continue;
         }

         if (best == null || tier(c) < tier(best) || (tier(c) == tier(best) && c.distSqr() < best.distSqr())) {
            best = c;
         }
      }

      if (best == null || current == null || best.entity() == current) {
         return best == null ? current : best.entity();
      }

      int currentTier = tierOf(current);
      if (tier(best) < currentTier) {
         return best.entity();
      }

      if (tier(best) == currentTier && best.distSqr() < self.distanceToSqr(current) * SWITCH_HYSTERESIS) {
         return best.entity();
      }

      return current;
   }

   // ----------------------------------------------------------------------------------------------------------------
   // Weapons
   // ----------------------------------------------------------------------------------------------------------------

   private static void fire(ServerLevel serverLevel, Entity entity, CompoundTag compoundTag, Entity entity2, double d, boolean bl) {
       F16FlightAI.tickCooldown(compoundTag, "f16_cd_cannon");
       F16FlightAI.tickCooldown(compoundTag, "f16_cd_aim9");
       F16FlightAI.tickCooldown(compoundTag, "f16_cd_aim120");
       F16FlightAI.tickCooldown(compoundTag, "f16_cd_agm");
       F16FlightAI.tickCooldown(compoundTag, "f16_cd_bomb");
       int n = compoundTag.getInt("f16_react_cd");
       if (n > 0) {
           compoundTag.putInt("f16_react_cd", n - 1);
           return;
       }
       Vec3 vec3 = entity2.position().subtract(entity.position());
       double d2 = vec3.lengthSqr() < 0.01 ? 1.0 : entity.getLookAngle().normalize().dot(vec3.normalize());
       boolean bl2 = d2 > 0.35;
       boolean bl3 = d2 > 0.82;
       int n2 = (Integer) Config.F16_MAX_MISSILES_PER_TARGET.get();
       boolean bl4 = Targeting.missilesInFlight(entity2) < n2;
       String string = null;
       String string2 = null;
       int n3 = 0;
       double d3 = 0.0;
       boolean bl5 = false;
       boolean bl6 = false;
       if (bl) {
           if (d <= 95.0 && bl3 && compoundTag.getInt("f16_cd_cannon") <= 0 && SupplySystem.canFire(entity, CANNON)) {
               string = CANNON;
               string2 = "f16_cd_cannon";
               n3 = 4;
               d3 = 8.0;
           } else if (bl4 && d <= 160.0 && bl2 && compoundTag.getInt("f16_cd_aim9") <= 0 && SupplySystem.canFire(entity, AIM9)) {
               string = AIM9;
               string2 = "f16_cd_aim9";
               n3 = 60;
               d3 = 0.75;
               bl5 = true;
           } else if (bl4 && d <= 500.0 && bl2 && compoundTag.getInt("f16_cd_aim120") <= 0 && SupplySystem.canFire(entity, AIM120)) {
               string = AIM120;
               string2 = "f16_cd_aim120";
               n3 = 100;
               d3 = 1.0;
               bl5 = true;
           }
       } else if (entity.getY() > entity2.getY() + 30.0 && d < 75.0 && compoundTag.getInt("f16_cd_bomb") <= 0 && SupplySystem.canFire(entity, BOMB)) {
           string = BOMB;
           string2 = "f16_cd_bomb";
           n3 = 100;
           d3 = 0.6;
           bl6 = true;
       } else if (bl4 && d <= 220.0 && bl2 && compoundTag.getInt("f16_cd_agm") <= 0 && SupplySystem.canFire(entity, AGM)) {
           string = AGM;
           string2 = "f16_cd_agm";
           n3 = 80;
           d3 = 1.8;
           bl5 = true;
       } else if (d <= 125.0 && bl3 && compoundTag.getInt("f16_cd_cannon") <= 0 && SupplySystem.canFire(entity, CANNON)) {
           string = CANNON;
           string2 = "f16_cd_cannon";
           n3 = 4;
           d3 = 8.0;
       }
       if (string == null || !BattleBudget.canFire(serverLevel, false)) {
           return;
       }
       Vec3 vec32 = bl5 ? WorldQueries.center(entity2) : F16FlightAI.leadPoint(entity, compoundTag, entity2, d3);
       Vec3 vec33 = F16FlightAI.muzzle(entity);
       if (compoundTag.getInt("f16_los_cd") > 0) {
           F16FlightAI.tickCooldown(compoundTag, "f16_los_cd");
           return;
       }
       if (!bl6 && !WorldQueries.lineOfSight(serverLevel, vec33, WorldQueries.center(entity2), entity)) {
           compoundTag.putInt("f16_los_cd", 5);
           return;
       }
       Entity entity3 = F16FlightAI.fireProjectile(serverLevel, entity, string, vec33, vec32, d3, bl6, (Entity) (bl5 ? entity2 : null));
       if (entity3 != null) {
           SupplySystem.consume(entity, string);
           if (string.equals(CANNON)) {
               int shots = compoundTag.getInt("f16_gun_burst_count") + 1;
               n3 = com.f16addon.core.ThreatMath.burstCooldown(shots, Config.F16_GUN_BURST_SIZE.get(), n3, Config.F16_GUN_BURST_PAUSE.get());
               compoundTag.putInt("f16_gun_burst_count", shots >= Config.F16_GUN_BURST_SIZE.get() ? 0 : shots);
           }
           compoundTag.putInt(string2, n3);
           if (bl5) {
               Targeting.registerShot(entity2, entity3);
           }
       }
   }

   private static Vec3 muzzle(Entity self) {
      return self.position().add(0.0, 1.4, 0.0).add(self.getLookAngle().scale(2.5));
   }

   /** First-order intercept: aim where the target will be after the projectile's flight time (1.0.0 used a fixed 8 ticks). */
   private static Vec3 leadPoint(Entity self, CompoundTag data, Entity target, double projectileSpeed) {
      Vec3 point = WorldQueries.center(target);
      Vec3 velocity = targetVelocity(data);
      if (velocity.lengthSqr() <= 0.01 || projectileSpeed <= 0.0) {
         return point;
      }

      double flightTicks = Mth.clamp(point.distanceTo(muzzle(self)) / projectileSpeed, 0.0, 40.0);
      return point.add(velocity.scale(flightTicks));
   }

   private static EntityType<?> type(String id) {
      ResourceLocation rl = ResourceLocation.tryParse(id);
      return rl == null ? null : ForgeRegistries.ENTITY_TYPES.getValue(rl);
   }

   private static Entity fireProjectile(ServerLevel serverLevel, Entity entity, String string, Vec3 vec3, Vec3 vec32, double d, boolean bl, Entity entity2) {
       EntityType<?> entityType = F16FlightAI.type(string);
       if (entityType == null) {
           return null;
       }
       Vec3 vec33 = vec32.subtract(vec3);
       if (vec33.lengthSqr() < 0.01) {
           return null;
       }
       Entity entity3 = entityType.create((Level) serverLevel);
       if (entity3 == null) {
           return null;
       }
       vec33 = vec33.normalize();
       entity3.moveTo(vec3.x, vec3.y, vec3.z, entity.getYRot(), entity.getXRot());
       if (entity3 instanceof Projectile) {
           Projectile projectile = (Projectile) entity3;
           projectile.setOwner(entity);
       }
       entity3.setDeltaMovement(bl ? vec33.scale(d).add(0.0, -0.12, 0.0) : vec33.scale(d));
       if (entity2 != null) {
           Sbw.lockMissile(entity3, entity2, WorldQueries.center(entity2));
           if (string.equals(AIM120) || string.equals(AIM9)) {
               entity3.addTag("f16_aam");
           }
       }
       if (string.equals(AIM9)) {
           Sbw.call(entity3, Sbw.SET_DAMAGE, Config.F16_IR_DAMAGE.get().floatValue());
           Sbw.call(entity3, Sbw.SET_EXPLOSION_DAMAGE, Config.F16_IR_EXPLOSION_DAMAGE.get().floatValue());
           Sbw.call(entity3, Sbw.SET_EXPLOSION_RADIUS, Config.F16_IR_EXPLOSION_RADIUS.get().floatValue());
       } else if (string.equals(CANNON)) {
           Sbw.call(entity3, Sbw.SET_EXPLOSION_RADIUS, Config.F16_CANNON_EXPLOSION_RADIUS.get().floatValue());
       }
       BattleBudget.prepare(entity3);
       if (!serverLevel.addFreshEntity(entity3))
           return null;
       BattleBudget.fired(serverLevel);
       return entity3;
   }

   // ----------------------------------------------------------------------------------------------------------------
   // Countermeasures
   // ----------------------------------------------------------------------------------------------------------------

   private static void maybeReleaseDecoy(ServerLevel level, Entity self, CompoundTag data) {
      int cooldown = data.getInt("f16_decoy_cd");
      if (cooldown > 0) {
         data.putInt("f16_decoy_cd", cooldown - 1);
         return;
      }

      if ((level.getGameTime() + self.getId()) % DECOY_CHECK_INTERVAL != 0L || level.random.nextFloat() > 0.5F) {
         return;
      }

      if (ThreatIndex.incomingAams(level, self) > 0 && releaseDecoy(self)) {
         data.putInt("f16_decoy_cd", 40);
      }
   }

   private static boolean releaseDecoy(Entity self) {
      if (Sbw.call(self, Sbw.GET_DECOY_COUNT) instanceof Number n && n.intValue() <= 0) {
         return false;
      }

      if (!Sbw.has(self, Sbw.RELEASE_DECOY)) {
         return false;
      }

      Sbw.call(self, Sbw.RELEASE_DECOY);
      return true;
   }

   private static void tickCooldown(CompoundTag data, String key) {
      int value = data.getInt(key);
      if (value > 0) {
         data.putInt(key, value - 1);
      }
   }

   private static final Logger LOG = LoggerFactory.getLogger((String) "f16addon");

   private static String branch = "none";

   private static final double RETURN_SPEED = 3.4;

   private static final float RETURN_TURN_RATE = 4.5f;

   private static final float RETURN_PITCH_RATE = 2.0f;

   private static final double GEAR_ANGLE = 85.0;

   private static final int LOG_INTERVAL = 20;

   private static void tickFlight(ServerLevel serverLevel, Entity entity) {
       Object object;
       CompoundTag compoundTag = ((IForgeEntity) entity).getPersistentData();
       F16FlightAI.initHome(entity, compoundTag);
       F16FlightAI.updateGear(serverLevel, entity, compoundTag);
       if (F16FlightAI.isWrecked(entity)) {
           branch = "wreck";
           if (!compoundTag.getBoolean("f16_wreck_stopped")) {
               compoundTag.putBoolean("f16_wreck_stopped", true);
               compoundTag.putBoolean("f16_airborne", false);
               compoundTag.putString("f16_locked_target", "");
               compoundTag.putString("f16_target", "");
               entity.setDeltaMovement(Vec3.ZERO);
           }
           return;
       }
       boolean servicing = SupplySystem.update(serverLevel, entity);
       boolean wounded = SupplySystem.wounded(entity);
       if ((servicing || wounded) && compoundTag.getBoolean("f16_airborne")) {
           branch = wounded ? "withdraw" : "resupply";
           compoundTag.putString("f16_target", "");
           compoundTag.putString("f16_locked_target", "");
           F16FlightAI.maybeReleaseDecoy(serverLevel, entity, compoundTag);
           F16FlightAI.returnHome(serverLevel, entity, compoundTag);
           return;
       }
       F16FlightAI.recoverOutsideLeash(serverLevel, entity, compoundTag);
       if (F16FlightAI.isLeashed(entity, compoundTag)) {
           branch = "leash";
           compoundTag.putBoolean("f16_airborne", true);
           compoundTag.putString("f16_locked_target", "");
           compoundTag.putString("f16_target", "");
           compoundTag.putInt("f16_run_phase", 0);
           F16FlightAI.returnHome(serverLevel, entity, compoundTag);
           return;
       }
       if (entity.onGround() && compoundTag.getBoolean("f16_airborne")) {
           compoundTag.putBoolean("f16_airborne", false);
           compoundTag.putDouble("f16_climb_speed", 0.0);
       }
       if (!compoundTag.getBoolean("f16_airborne")) {
           branch = "takeoff";
           F16FlightAI.takeOff(serverLevel, entity, compoundTag);
           return;
       }
       F16FlightAI.maybeReleaseDecoy(serverLevel, entity, compoundTag);
       if (((Boolean) Config.F16_EVASIVE.get()).booleanValue()) {
           Entity incomingMissile = ThreatIndex.nearestClosingAam(serverLevel, entity);
           double d = (Double) Config.F16_EVASION_RANGE.get();
           if (incomingMissile != null && incomingMissile.distanceToSqr(entity) <= d * d) {
               branch = "evade";
               F16FlightAI.evade(serverLevel, entity, compoundTag, incomingMissile);
               return;
           }
       }
       if ((object = compoundTag.getString("f16_mode")).equals("patrol") || ((String) object).equals("return") || F16FlightAI.isLeashed(entity, compoundTag)) {
           compoundTag.putString("f16_target", "");
           compoundTag.putInt("f16_run_phase", 0);
           String string = branch = ((String) object).equals("return") ? "return" : "patrol";
           if (((String) object).equals("return")) {
               F16FlightAI.returnHome(serverLevel, entity, compoundTag);
           } else {
               F16FlightAI.patrol(serverLevel, entity, compoundTag);
           }
           return;
       }
       Entity entity2 = F16FlightAI.resolveTarget(serverLevel, entity, compoundTag);
       if (entity2 != null) {
           branch = "combat";
           F16FlightAI.combat(serverLevel, entity, compoundTag, entity2);
       } else {
           branch = "patrol";
           compoundTag.putInt("f16_run_phase", 0);
           F16FlightAI.patrol(serverLevel, entity, compoundTag);
       }
   }

   private static void returnHome(ServerLevel serverLevel, Entity entity, CompoundTag compoundTag) {
       Vec3 vec3 = F16FlightAI.home(compoundTag);
       Vec3 vec32 = vec3.subtract(entity.position());
       double d = vec32.length();
       if (d < 220.0) {
           F16FlightAI.patrol(serverLevel, entity, compoundTag);
           return;
       }
       Vec3 vec33 = vec32.normalize();
       double d2 = Math.max((double) F16FlightAI.ground(serverLevel, compoundTag, entity.getX(), entity.getZ()) + F16FlightAI.clearance() + 5.0, vec3.y + 50.0);
       vec33 = vec33.add(0.0, F16FlightAI.altitudeCorrection(entity.getY(), d2, 0.05), 0.0);
       F16FlightAI.steer(serverLevel, entity, compoundTag, vec33, 3.4, 4.5f, 2.0f, F16FlightAI.clearance());
   }

   private static double leashRadius() {
       return Math.max(128.0, (Double) Config.F16_LEASH_RADIUS.get());
   }

   private static double leashMargin() {
       double d = 43.29014452099553;
       return Math.max(120.0, Math.PI * d + 20.0);
   }

   private static double leashReturnRadius() {
       return Math.max(64.0, F16FlightAI.leashRadius() - F16FlightAI.leashMargin());
   }

   private static Vec3 clampToLeash(CompoundTag compoundTag, Vec3 vec3, Vec3 vec32) {
       Vec3 vec33 = F16FlightAI.home(compoundTag);
       double d = F16FlightAI.leashRadius();
       if (F16FlightAI.horizontalDistSqr(vec3, vec33) > d * d) {
           return vec32;
       }
       return F16FlightAI.clampToCircle(compoundTag, vec32);
   }

   private static Vec3 clampToCircle(CompoundTag compoundTag, Vec3 vec3) {
       Vec3 vec32 = F16FlightAI.home(compoundTag);
       double d = F16FlightAI.leashRadius();
       double d2 = F16FlightAI.horizontalDistSqr(vec3, vec32);
       if (d2 <= d * d || d2 < 1.0E-6) {
           return vec3;
       }
       double d3 = vec3.x - vec32.x;
       double d4 = vec3.z - vec32.z;
       double d5 = Math.sqrt(d3 * d3 + d4 * d4);
       double d6 = d / d5;
       return new Vec3(vec32.x + d3 * d6, vec3.y, vec32.z + d4 * d6);
   }

   private static Vec3 flightBase(Entity entity, CompoundTag compoundTag) {
       Vec3 vec3 = entity.position();
       if (!compoundTag.contains("f16_bx")) {
           return vec3;
       }
       Vec3 vec32 = new Vec3(compoundTag.getDouble("f16_bx"), compoundTag.getDouble("f16_by"), compoundTag.getDouble("f16_bz"));
       if (vec3.subtract(vec32).length() > 24.0) {
           return vec3;
       }
       return vec32;
   }

   private static void storeFlightState(CompoundTag compoundTag, Vec3 vec3, Vec3 vec32) {
       compoundTag.putDouble("f16_bx", vec3.x);
       compoundTag.putDouble("f16_by", vec3.y);
       compoundTag.putDouble("f16_bz", vec3.z);
       compoundTag.putDouble("f16_vx", vec32.x);
       compoundTag.putDouble("f16_vy", vec32.y);
       compoundTag.putDouble("f16_vz", vec32.z);
   }

   private static boolean recoverOutsideLeash(ServerLevel serverLevel, Entity entity, CompoundTag compoundTag) {
       if (F16FlightAI.combatOverflow(entity, compoundTag)) {
           return false;
       }
       Vec3 vec3 = F16FlightAI.home(compoundTag);
       double d = F16FlightAI.leashRadius();
       Vec3 vec32 = entity.position();
       double d2 = F16FlightAI.horizontalDistSqr(vec32, vec3);
       if (d2 <= d * d) {
           compoundTag.putBoolean("f16_outside_logged", false);
           return false;
       }
       double d3 = Math.sqrt(d2);
       Vec3 vec33 = new Vec3(vec32.x - vec3.x, 0.0, vec32.z - vec3.z);
       if (d3 < 1.0E-6) {
           vec33 = new Vec3(1.0, 0.0, 0.0);
       }
       vec33 = vec33.normalize();
       Vec3 vec34 = new Vec3(vec3.x + vec33.x * d, vec32.y, vec3.z + vec33.z * d);
       if (!WorldQueries.isEntityTicking(serverLevel, vec34.x, vec34.y, vec34.z)) {
           entity.setDeltaMovement(Vec3.ZERO);
           if (!compoundTag.getBoolean("f16_outside_logged")) {
               compoundTag.putBoolean("f16_outside_logged", true);
               LOG.info("[f16addon] F-16 {} is {} blocks outside its leash circle and the home area is not loaded, it stops here", (Object) entity.getId(), (Object) Math.round(d3));
           }
           return true;
       }
       Vec3 vec35 = F16FlightAI.horizontal(vec3.subtract(vec34)).normalize();
       entity.setYRot(F16FlightAI.yawOf(vec35));
       entity.yRotO = entity.getYRot();
       entity.setPos(vec34.x, vec34.y, vec34.z);
       entity.setDeltaMovement(vec35.scale(3.4));
       entity.hasImpulse = true;
       F16FlightAI.storeFlightState(compoundTag, vec34, vec35.scale(3.4));
       if (!compoundTag.getBoolean("f16_outside_logged")) {
           compoundTag.putBoolean("f16_outside_logged", true);
           LOG.info("[f16addon] F-16 {} was {} blocks outside its leash circle, it is put back on the border and flies home", (Object) entity.getId(), (Object) Math.round(d3));
       }
       return true;
   }

   private static void logState(ServerLevel serverLevel, Entity entity) {
       if ((serverLevel.getGameTime() + entity.getId()) % 100L != 0L) {
           return;
       }
       CompoundTag compoundTag = ((IForgeEntity) entity).getPersistentData();
       double d = Math.sqrt(F16FlightAI.horizontalDistSqr(entity.position(), F16FlightAI.home(compoundTag)));
       LOG.info("[f16addon] F-16 {} branch={} dist={} yaw={} speed={} airborne={} mode={} target={} gear={}", new Object[] { entity.getId(), branch, Math.round(d), Math.round(entity.getYRot()), compoundTag.getDouble("f16_speed"), compoundTag.getBoolean("f16_airborne"), compoundTag.getString("f16_mode"), compoundTag.getString("f16_target"), compoundTag.getBoolean("f16_gear_up") });
   }

   private static boolean combatOverflow(Entity entity, CompoundTag compoundTag) {
       if (!((Boolean) Config.F16_COMBAT_CAN_LEAVE_LEASH.get()).booleanValue()) {
           return false;
       }
       if (entity.onGround()) {
           return false;
       }
       if (!compoundTag.getString("f16_target").isEmpty()) {
           return true;
       }
       return !compoundTag.getString("f16_locked_target").isEmpty();
   }

   private static void updateGear(ServerLevel serverLevel, Entity entity, CompoundTag compoundTag) {
       boolean bl;
       boolean bl2 = bl = !F16FlightAI.isWrecked(entity) && !entity.onGround();
       if (bl) {
           bl = compoundTag.getBoolean("f16_airborne") || entity.getY() > (double) F16FlightAI.ground(serverLevel, compoundTag, entity.getX(), entity.getZ()) + 5.0;
       }
       float f = compoundTag.getFloat("f16_gear_rot");
       float f2 = bl ? 1.0f : 0.0f;
       float f3 = f < f2 ? Math.min(f + 0.05f, f2) : Math.max(f - 0.05f, f2);
       compoundTag.putFloat("f16_gear_rot", f3);
       compoundTag.putBoolean("f16_gear_up", f2 > 0.5f);
       Sbw.call(entity, Sbw.SET_GEAR_UP, f2 > 0.5f);
       Sbw.call(entity, Sbw.SET_SYNCED_GEAR_ROT, Float.valueOf(f3));
       Sbw.call(entity, Sbw.SET_GEAR_ROT, Float.valueOf((float) ((double) f3 * 85.0)));
   }

   private static float rollOf(Entity entity) {
       Object object = Sbw.call(entity, Sbw.GET_ROLL, new Object[0]);
       if (object instanceof Number) {
           Number number = (Number) object;
           return number.floatValue();
       }
       return 0.0f;
   }
}
