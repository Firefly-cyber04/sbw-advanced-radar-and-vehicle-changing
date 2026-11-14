package com.f16addon;

import com.atsuishio.superbwarfare.entity.projectile.Agm65Entity;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;

/**
 * Corrections to SBW / DragonRise missile flight, used by the missile mixins and the projectile tick.
 *
 * <ul>
 *    <li><b>Target velocity</b>: the guidance computes an intercept point from {@code target.getDeltaMovement()}. Kinematic
 *        units (this addon's jets: {@code setPos} + zero motion) and player-driven SBW vehicles report zero motion on the
 *        server, so every missile flew pure pursuit. {@link #velocity} measures it from the position instead.</li>
 *    <li><b>Fuze arming</b>: proximity fuzes were live from the first tick, so a missile detonated on any vehicle parked
 *        next to its launcher. {@link #armed} keeps them safe until the missile is clear of the launcher.</li>
 *    <li><b>Overshoot</b>: an AGM-65-family missile (AIM-120, Kh-39, AGM-65) that flew past its target stopped steering
 *        but never counted as lost, so it flew on until the lifetime cap. {@link #checkOvershoot} marks it lost and SBW's
 *        lost-target rule detonates it.</li>
 * </ul>
 */
public final class MissileFlight {
   /** Proximity fuze arms once the missile is this far from its launcher (fuze radius is 5). */
   private static final double ARM_DISTANCE_SQR = 12.0 * 12.0;
   private static final int ARM_MIN_TICKS = 3;
   private static final int OVERSHOOT_MIN_TICKS = 40;
   /**
    * The intercept solver needs the missile clearly faster than the target. Right after launch (9M336 starts steering on
    * its 2nd tick at launch speed: 0.75–3 blocks/tick) it is not, the "solution" points anywhere, and the 9M336 drops
    * the lock for a > 120° error. Below this ratio the target velocity is reported as zero (SBW's direct aim) for that tick.
    */
   private static final double MIN_SPEED_RATIO_SQR = 1.5 * 1.5;
   /** Faster than anything that flies: treat as a teleport and restart the estimate. */
   private static final double MAX_SPEED_SQR = 40.0 * 40.0;

   private static final Map<Entity, Track> SERVER = new WeakHashMap<>();
   private static final Map<Entity, Track> CLIENT = new WeakHashMap<>();

   private MissileFlight() {
   }

   private static final class Track {
      Vec3 pos;
      long tick;
      Vec3 velocity;

      Track(Vec3 pos, long tick, Vec3 velocity) {
         this.pos = pos;
         this.tick = tick;
         this.velocity = velocity;
      }
   }

   /**
    * Smoothed velocity (blocks/tick) of a missile's target, measured from its position. Missiles ask every tick while
    * they track a target, which is what keeps the samples one tick apart.
    */
   public static Vec3 velocity(Entity target, Entity missile) {
      if (!measuredVelocityEnabled()) {
         return target.getDeltaMovement();
      }

      Vec3 v = measure(target);
      return missile.getDeltaMovement().lengthSqr() < v.lengthSqr() * MIN_SPEED_RATIO_SQR ? Vec3.ZERO : v;
   }

   private static Vec3 measure(Entity target) {
      Map<Entity, Track> map = target.level().isClientSide ? CLIENT : SERVER;
      long now = target.level().getGameTime();
      Vec3 pos = target.position();
      Track t = map.get(target);
      if (t == null) {
         t = new Track(pos, now, target.getDeltaMovement());
         map.put(target, t);
         return t.velocity;
      }

      if (now != t.tick) {
         long dt = now - t.tick;
         if (dt > 0 && dt <= 10) {
            Vec3 measured = pos.subtract(t.pos).scale(1.0 / dt);
            t.velocity = measured.lengthSqr() > MAX_SPEED_SQR ? Vec3.ZERO : t.velocity.scale(0.5).add(measured.scale(0.5));
         } else {
            t.velocity = target.getDeltaMovement();
         }

         t.pos = pos;
         t.tick = now;
      }

      return t.velocity;
   }

   private static boolean measuredVelocityEnabled() {
      try {
         return Config.MISSILE_MEASURED_TARGET_VELOCITY.get();
      } catch (IllegalStateException notLoadedYet) {
         // Client-side missile ticks can run before the server config has been synced.
         return true;
      }
   }

   /** Whether the missile's proximity fuze may fire: a few ticks old and clear of the launcher. */
   public static boolean armed(Entity missile) {
      if (missile.tickCount < ARM_MIN_TICKS) {
         return false;
      }

      Entity owner = missile instanceof Projectile p ? p.getOwner() : null;
      return owner == null || missile.distanceToSqr(owner.getRootVehicle()) >= ARM_DISTANCE_SQR;
   }

   /** Server tick: an AGM-65-family missile whose target is behind it has missed; let SBW's lost-target rule end it. */
   static void checkOvershoot(ServerLevel level, Entity entity) {
      if (!(entity instanceof Agm65Entity missile) || missile.tickCount <= OVERSHOOT_MIN_TICKS || missile.isLostTarget()) {
         return;
      }

      Vec3 target = null;
      String id = missile.getTargetUUID();
      if (!id.isEmpty() && !"none".equals(id)) {
         Entity e = Targeting.byUuid(level, id);
         if (e != null) {
            target = e.position();
         }
      } else if (missile.getGuideType() != 0) {
         target = missile.getTargetPos();
      }

      if (target == null) {
         return;
      }

      Vec3 to = target.subtract(missile.position());
      if (to.lengthSqr() > 1.0 && missile.getLookAngle().dot(to) < 0.0) {
         missile.setLostTarget(true);
      }
   }
}
