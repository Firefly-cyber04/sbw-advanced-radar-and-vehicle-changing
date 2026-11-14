package com.f16addon;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;

/**
 * Reflection bridge to Superb Warfare / DragonRise.
 *
 * <p>1.0.0 called {@code getClass().getMethod(...)} on every invocation (several times per unit per tick). That walks
 * the whole public method table of a Kotlin class with hundreds of members and allocates a fresh {@link Method} copy
 * each time. Here every lookup is resolved once per (class, signature) and cached in a {@link ClassValue}.
 */
public final class Sbw {
   public static final String VEHICLE_CLASS_NAME = "com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity";
   public static final String MISSILE_CLASS_NAME = "com.atsuishio.superbwarfare.entity.projectile.MissileProjectile";

   // Identity-keyed signatures: each constant is a singleton, so the per-class map never builds string keys.
   public static final Sig IS_WRECK = new Sig("isWreck");
   public static final Sig GET_HEALTH = new Sig("getHealth");
   public static final Sig GET_ROLL = new Sig("getRoll");
   public static final Sig SET_ROLL = new Sig("setRoll", float.class);
   public static final Sig SET_PREV_ROLL = new Sig("setPrevRoll", float.class);
   public static final Sig SET_GEAR_UP = new Sig("setGearUp", boolean.class);
   public static final Sig GET_GUN_DATA_MAP = new Sig("getGunDataMap");
   public static final Sig SET_GUN_DATA_MAP = new Sig("setGunDataMap", Map.class);
   public static final Sig GET_MAX_ENERGY = new Sig("getMaxEnergy");
   public static final Sig GET_ENERGY = new Sig("getEnergy");
   public static final Sig SET_ENERGY = new Sig("setEnergy", int.class);
   public static final Sig TURRET_AIM = new Sig("turretAutoAimFromVector", Vec3.class);
   public static final Sig ADJUST_TURRET = new Sig("adjustTurretAngle");
   public static final Sig GET_SHOOT_POS = new Sig("getShootPos", String.class, float.class);
   public static final Sig SET_AI_TURRET_TARGET = new Sig("setAiTurretTargetUUID", String.class);
   public static final Sig SET_AI_PASSENGER_TARGET = new Sig("setAiPassengerWeaponTargetUUID", String.class);
   public static final Sig GET_DECOY_COUNT = new Sig("getDecoyCount");
   public static final Sig RELEASE_DECOY = new Sig("releaseDecoy");
   public static final Sig GET_VEHICLE_TYPE = new Sig("getVehicleType");
   public static final Sig GET_TARGET_UUID = new Sig("getTargetUUID");
   public static final Sig SET_TARGET_UUID = new Sig("setTargetUUID", String.class);
   public static final Sig SET_TARGET_POS = new Sig("setTargetPos", Vec3.class);
   public static final Sig SET_GUIDE_TYPE = new Sig("setGuideType", int.class);
   public static final Sig SET_DISTRACTED = new Sig("setDistracted", boolean.class);
   public static final Sig SET_DAMAGE = new Sig("setDamageValue", float.class);
   public static final Sig SET_EXPLOSION_DAMAGE = new Sig("setExplosionDamageValue", float.class);
   public static final Sig SET_EXPLOSION_RADIUS = new Sig("setExplosionRadiusValue", float.class);

   private static final Optional<Method> MISSING = Optional.empty();
   private static final ClassValue<Map<Sig, Optional<Method>>> METHODS = new ClassValue<>() {
      @Override
      protected Map<Sig, Optional<Method>> computeValue(Class<?> type) {
         return new ConcurrentHashMap<>();
      }
   };
   private static final ClassValue<Boolean> IS_VEHICLE = new ClassValue<>() {
      @Override
      protected Boolean computeValue(Class<?> type) {
         return extendsClassNamed(type, VEHICLE_CLASS_NAME);
      }
   };
   private static final ClassValue<Boolean> IS_MISSILE = new ClassValue<>() {
      @Override
      protected Boolean computeValue(Class<?> type) {
         return extendsClassNamed(type, MISSILE_CLASS_NAME);
      }
   };
   /** Vehicle type comes from the vehicle JSON of the entity type, so it is constant per type. */
   private static final Map<EntityType<?>, String> VEHICLE_TYPES = new ConcurrentHashMap<>();

   private Sbw() {
   }

   public static final class Sig {
      final String name;
      final Class<?>[] params;

      public Sig(String name, Class<?>... params) {
         this.name = name;
         this.params = params;
      }
   }

   private static boolean extendsClassNamed(Class<?> type, String name) {
      for (Class<?> c = type; c != null; c = c.getSuperclass()) {
         if (c.getName().equals(name)) {
            return true;
         }
      }

      return false;
   }

   /** True for every Superb Warfare / DragonRise vehicle (1.0.0 matched the class *name* and never hit a subclass). */
   public static boolean isVehicle(Entity entity) {
      return entity != null && IS_VEHICLE.get(entity.getClass());
   }

   public static boolean isMissile(Entity entity) {
      return entity != null && IS_MISSILE.get(entity.getClass());
   }

   static boolean isMissileClass(Class<?> type) {
      return IS_MISSILE.get(type);
   }

   public static Method method(Class<?> type, Sig sig) {
      Map<Sig, Optional<Method>> cache = METHODS.get(type);
      Optional<Method> found = cache.get(sig);
      if (found == null) {
         found = lookup(type, sig);
         cache.put(sig, found);
      }

      return found.orElse(null);
   }

   private static Optional<Method> lookup(Class<?> type, Sig sig) {
      try {
         return Optional.of(type.getMethod(sig.name, sig.params));
      } catch (NoSuchMethodException | RuntimeException ignored) {
      }

      // Non-public setters (e.g. setDamageValue on some projectiles) are declared further up the hierarchy.
      for (Class<?> c = type; c != null; c = c.getSuperclass()) {
         try {
            Method m = c.getDeclaredMethod(sig.name, sig.params);
            m.setAccessible(true);
            return Optional.of(m);
         } catch (NoSuchMethodException ignored) {
         } catch (RuntimeException e) {
            return MISSING;
         }
      }

      return MISSING;
   }

   public static boolean has(Object target, Sig sig) {
      return target != null && method(target.getClass(), sig) != null;
   }

   /** Invokes {@code sig} on {@code target}; returns {@code null} if the method does not exist or throws. */
   public static Object call(Object target, Sig sig, Object... args) {
      if (target == null) {
         return null;
      }

      Method m = method(target.getClass(), sig);
      if (m == null) {
         return null;
      }

      try {
         return m.invoke(target, args);
      } catch (ReflectiveOperationException | RuntimeException e) {
         return null;
      }
   }

   public static boolean isWrecked(Entity entity) {
      if (Boolean.TRUE.equals(call(entity, IS_WRECK))) {
         return true;
      }

      return call(entity, GET_HEALTH) instanceof Number health && health.floatValue() <= 0.0F;
   }

   public static String targetUuid(Entity missile) {
      return call(missile, GET_TARGET_UUID) instanceof String s ? s : "";
   }

   /** Writes the lock exactly like DragonRise does for player-fired missiles: UUID + last position + active guidance. */
   public static void lockMissile(Entity missile, Entity target, Vec3 aim) {
      call(missile, SET_TARGET_UUID, target.getStringUUID());
      call(missile, SET_TARGET_POS, aim);
      call(missile, SET_GUIDE_TYPE, 0);
   }

   /** Upper-case vehicle type name ("AIRPLANE", "HELICOPTER", "TANK" ...) or "" for non-vehicles. */
   public static String vehicleType(Entity entity) {
      if (!isVehicle(entity)) {
         return "";
      }

      String cached = VEHICLE_TYPES.get(entity.getType());
      if (cached != null) {
         return cached;
      }

      Object type = call(entity, GET_VEHICLE_TYPE);
      if (type == null) {
         return "";
      }

      String name = (type instanceof Enum<?> e ? e.name() : String.valueOf(type)).toUpperCase(Locale.ROOT);
      VEHICLE_TYPES.put(entity.getType(), name);
      return name;
   }

   public static boolean isAircraft(Entity entity) {
      String type = vehicleType(entity);
      return type.equals("AIRPLANE") || type.equals("HELICOPTER") || type.equals("DRONE") || type.equals("AIRSHIP");
   }

   public static boolean isJet(Entity entity) {
      return vehicleType(entity).equals("AIRPLANE");
   }

   public static Vec3 shootPos(Entity entity, String weapon, Vec3 fallback) {
      return call(entity, GET_SHOOT_POS, weapon, 1.0F) instanceof Vec3 v ? v : fallback;
   }
}
