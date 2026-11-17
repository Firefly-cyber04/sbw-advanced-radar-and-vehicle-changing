package com.f16addon;

import java.util.Locale;
import net.minecraft.world.entity.Entity;

/**
 * Per-class traits, computed once per entity class instead of {@code getSimpleName().toUpperCase().contains(...)}
 * chains on every check (1.0.0 ran those for every loaded entity, every tick).
 */
public final class Classify {
   public static final int IR_AAM = 1;
   public static final int RADAR_AAM = 1 << 1;
   public static final int MANPADS = 1 << 2;
   public static final int MISSILE = 1 << 3;
   public static final int AIR_DEFENSE_TARGET = 1 << 4;
   public static final int CRUISE = 1 << 5;
   public static final int DECOY = 1 << 6;
   public static final int FIFTH_GEN = 1 << 7;
   public static final int BMPT = 1 << 8;
   public static final int AAM = IR_AAM | RADAR_AAM | MANPADS;

   private static final ClassValue<Integer> TRAITS = new ClassValue<>() {
      @Override
      protected Integer computeValue(Class<?> type) {
         return compute(type);
      }
   };

   private Classify() {
   }

   public static boolean is(Entity entity, int trait) {
      return entity != null && (traits(entity.getClass()) & trait) != 0;
   }

   public static int traits(Class<?> type) {
      return TRAITS.get(type);
   }

   private static boolean any(String name, String... needles) {
      for (String needle : needles) {
         if (name.contains(needle)) {
            return true;
         }
      }

      return false;
   }

   private static int compute(Class<?> type) {
       boolean missile;
       String n = type.getSimpleName().toUpperCase(Locale.ROOT);
       int t = 0;
       if (Classify.any(n, "RU9M336", "AIM9", "PL10", "PL8", "R73")) {
           t |= 1;
       }
       if (Classify.any(n, "AIM120", "PL12", "PL15", "R77")) {
           t |= 2;
       }
       if (Classify.any(n, "IGLA", "FIM92")) {
           t |= 4;
       }
       boolean bl = missile = Sbw.isMissileClass(type) || Classify.any(n, "MISSILE", "AIM120", "AIM9", "PL10", "PL12", "PL15", "RU9", "IGLA", "FIM", "AGM", "KH", "JAVELIN", "HELLFIRE", "TOW");
       if (missile) {
           t |= 8;
       }
       if (missile || Classify.any(n, "SHELL", "CANNON", "ARTILLERY", "MORTAR", "ROCKET", "RPG", "GRENADE")) {
           t |= 0x10;
       }
       if (n.contains("RU3M14")) {
           t |= 0x20;
       }
       if (Classify.any(n, "FLAREDECOY", "SMOKEDECOY", "DECOYENTITY")) {
           t |= 0x40;
       }
       if (Classify.any(n, "J20", "J35", "F22", "F35", "SU57", "FC31", "TJGC")) {
           t |= 0x80;
       }
       if (any(n, "SMALLCANNON", "SMALLSHELL"))
           t |= SMALL_SHELL;
       if (n.contains("BMPT")) {
           t |= 0x100;
       }
       return t;
   }

   /** Stealth check looks at the airframe, not the pilot riding it. */
   public static boolean isFifthGen(Entity entity) {
      Entity body = entity.getVehicle() != null ? entity.getRootVehicle() : entity;
      return is(body, FIFTH_GEN);
   }

   public static final int SMALL_SHELL = 512;

   public static boolean managedLifetime(Entity entity) {
       if (entity.getTags().contains(BattleBudget.AI_SHOT) || entity.getTags().contains("f16_aam"))
           return true;
       if (!Config.MANAGE_EXTERNAL_PROJECTILES.get())
           return false;
       net.minecraft.resources.ResourceLocation id = net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());
       if (id == null || !(id.getNamespace().equals("superbwarfare") || id.getNamespace().equals("dragonrise_reforge")))
           return false;
       return is(entity, MISSILE | AIR_DEFENSE_TARGET | DECOY);
   }
}
