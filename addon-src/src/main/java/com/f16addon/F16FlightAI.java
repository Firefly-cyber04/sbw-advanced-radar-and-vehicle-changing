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

   public static void tick(ServerLevel level, Entity self) {
      CompoundTag data = self.getPersistentData();
      initHome(self, data);
      if (isWrecked(self)) {
         if (!data.getBoolean("f16_wreck_stopped")) {
            data.putBoolean("f16_wreck_stopped", true);
            data.putBoolean("f16_airborne", false);
            data.putString("f16_locked_target", "");
            data.putString("f16_target", "");
            self.setDeltaMovement(Vec3.ZERO);
         }

         return;
      }

      if (self.onGround() && data.getBoolean("f16_airborne")) {
         data.putBoolean("f16_airborne", false);
         data.putDouble("f16_climb_speed", 0.0);
      }

      if (!data.getBoolean("f16_airborne")) {
         takeOff(level, self, data);
         return;
      }

      maybeReleaseDecoy(level, self, data);
      if (Config.F16_EVASIVE.get()) {
         Entity missile = ThreatIndex.nearestClosingAam(level, self);
         double range = Config.F16_EVASION_RANGE.get();
         if (missile != null && missile.distanceToSqr(self) <= range * range) {
            evade(level, self, data, missile);
            return;
         }
      }

      String mode = data.getString("f16_mode");
      if (mode.equals("patrol") || mode.equals("return") || isLeashed(self, data)) {
         data.putString("f16_target", "");
         data.putInt("f16_run_phase", RUN_INGRESS);
         patrol(level, self, data);
         return;
      }

      Entity target = resolveTarget(level, self, data);
      if (target != null) {
         combat(level, self, data, target);
      } else {
         data.putInt("f16_run_phase", RUN_INGRESS);
         patrol(level, self, data);
      }
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

   private static void patrol(ServerLevel level, Entity self, CompoundTag data) {
      Vec3 home = home(data);
      float angle = data.getFloat("f16_orbit_angle") + 0.034F;
      if (angle > (float) (Math.PI * 2)) {
         angle -= (float) (Math.PI * 2);
      }

      data.putFloat("f16_orbit_angle", angle);
      double orbitX = home.x + Math.cos(angle) * PATROL_RADIUS;
      double orbitZ = home.z + Math.sin(angle) * PATROL_RADIUS;
      int ground = ground(level, data, orbitX, orbitZ);
      double targetY = Math.max(ground + clearance() + 5.0, home.y + 50.0);
      Vec3 radial = new Vec3(orbitX, targetY, orbitZ).subtract(self.position());
      Vec3 tangent = new Vec3(-Math.sin(angle), 0.0, Math.cos(angle));
      Vec3 desired;
      if (radial.lengthSqr() > 1.0) {
         Vec3 radialDir = radial.normalize();
         desired = radial.length() > 170.0 ? radialDir : tangent.scale(0.78).add(radialDir.scale(0.42));
      } else {
         desired = tangent;
      }

      desired = desired.add(0.0, altitudeCorrection(self.getY(), targetY, 0.06), 0.0);
      steer(level, self, data, desired, PATROL_SPEED, 2.2F, 1.4F, clearance());
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

   /** Pushes the commanded direction away from other enemy jets closer than {@link #SEPARATION}. */
   private static Vec3 deconflict(ServerLevel level, Entity self, Vec3 desired) {
      Vec3 push = Vec3.ZERO;
      double sepSqr = SEPARATION * SEPARATION;
      for (Entity other : UnitTracker.get(level).f16()) {
         if (other == self || other.isRemoved()) {
            continue;
         }

         Vec3 away = self.position().subtract(other.position());
         double d2 = away.lengthSqr();
         if (d2 < sepSqr && d2 > 1.0E-4) {
            double d = Math.sqrt(d2);
            push = push.add(away.scale((SEPARATION - d) / (SEPARATION * d)));
         }
      }

      return push == Vec3.ZERO ? desired : desired.add(push.scale(0.8)).normalize();
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

   private static void fire(ServerLevel level, Entity self, CompoundTag data, Entity target, double distance, boolean air) {
      tickCooldown(data, "f16_cd_cannon");
      tickCooldown(data, "f16_cd_aim9");
      tickCooldown(data, "f16_cd_aim120");
      tickCooldown(data, "f16_cd_agm");
      tickCooldown(data, "f16_cd_bomb");
      int reaction = data.getInt("f16_react_cd");
      if (reaction > 0) {
         data.putInt("f16_react_cd", reaction - 1);
         return;
      }

      Vec3 toTarget = target.position().subtract(self.position());
      double facing = toTarget.lengthSqr() < 0.01 ? 1.0 : self.getLookAngle().normalize().dot(toTarget.normalize());
      boolean roughlyForward = facing > 0.35;
      boolean gunAligned = facing > 0.82;
      int maxMissiles = Config.F16_MAX_MISSILES_PER_TARGET.get();
      boolean missileSlot = Targeting.missilesInFlight(target) < maxMissiles;

      String weapon = null;
      String cooldownKey = null;
      int cooldown = 0;
      double speed = 0.0;
      boolean guided = false;
      boolean bomb = false;
      if (air) {
         if (distance <= 95.0 && gunAligned && data.getInt("f16_cd_cannon") <= 0) {
            weapon = CANNON; cooldownKey = "f16_cd_cannon"; cooldown = 4; speed = GUN_SPEED;
         } else if (missileSlot && distance <= 160.0 && roughlyForward && data.getInt("f16_cd_aim9") <= 0) {
            weapon = AIM9; cooldownKey = "f16_cd_aim9"; cooldown = 60; speed = 0.75; guided = true;
         } else if (missileSlot && distance <= 500.0 && roughlyForward && data.getInt("f16_cd_aim120") <= 0) {
            weapon = AIM120; cooldownKey = "f16_cd_aim120"; cooldown = 100; speed = 1.0; guided = true;
         }
      } else if (self.getY() > target.getY() + 30.0 && distance < 75.0 && data.getInt("f16_cd_bomb") <= 0) {
         weapon = BOMB; cooldownKey = "f16_cd_bomb"; cooldown = 100; speed = 0.6; bomb = true;
      } else if (missileSlot && distance <= 220.0 && roughlyForward && data.getInt("f16_cd_agm") <= 0) {
         weapon = AGM; cooldownKey = "f16_cd_agm"; cooldown = 80; speed = 1.8; guided = true;
      } else if (distance <= 125.0 && gunAligned && data.getInt("f16_cd_cannon") <= 0) {
         weapon = CANNON; cooldownKey = "f16_cd_cannon"; cooldown = 4; speed = GUN_SPEED;
      }

      if (weapon == null) {
         return;
      }

      Vec3 aim = guided ? WorldQueries.center(target) : leadPoint(self, data, target, speed);
      Vec3 muzzle = muzzle(self);
      // Bombs are dropped, not aimed through terrain; everything else needs a clear line (one raycast per shot).
      if (data.getInt("f16_los_cd") > 0) {
         tickCooldown(data, "f16_los_cd");
         return;
      }

      if (!bomb && !WorldQueries.lineOfSight(level, muzzle, WorldQueries.center(target), self)) {
         data.putInt("f16_los_cd", 5);
         return;
      }

      Entity projectile = fireProjectile(level, self, weapon, muzzle, aim, speed, bomb, guided ? target : null);
      if (projectile != null) {
         data.putInt(cooldownKey, cooldown);
         if (guided) {
            Targeting.registerShot(target, projectile);
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

   private static Entity fireProjectile(ServerLevel level, Entity self, String entityId, Vec3 start, Vec3 target, double speed, boolean bomb, Entity lockTarget) {
      EntityType<?> type = type(entityId);
      if (type == null) {
         return null;
      }

      Vec3 dir = target.subtract(start);
      if (dir.lengthSqr() < 0.01) {
         return null;
      }

      Entity projectile = type.create(level);
      if (projectile == null) {
         return null;
      }

      dir = dir.normalize();
      projectile.moveTo(start.x, start.y, start.z, self.getYRot(), self.getXRot());
      if (projectile instanceof Projectile p) {
         p.setOwner(self);
      }

      projectile.setDeltaMovement(bomb ? dir.scale(speed).add(0.0, -0.12, 0.0) : dir.scale(speed));
      if (lockTarget != null) {
         Sbw.lockMissile(projectile, lockTarget, WorldQueries.center(lockTarget));
         if (entityId.equals(AIM120) || entityId.equals(AIM9)) {
            projectile.addTag("f16_aam");
         }
      }

      return level.addFreshEntity(projectile) ? projectile : null;
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
}
