package com.f16addon;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Server config: {@code <world>/serverconfig/f16addon-server.toml}.
 * Every value that used to be a hard-coded constant and that players were tuning by editing the jar lives here.
 */
public final class Config {
   public static final ForgeConfigSpec SPEC;

   // --- general -------------------------------------------------------------------------------------------------
   public static final ForgeConfigSpec.IntValue PROJECTILE_MAX_LIFETIME;
   public static final ForgeConfigSpec.IntValue IR_AAM_MAX_LIFETIME;
   public static final ForgeConfigSpec.IntValue MAX_LOS_CHECKS_PER_SCAN;
   public static final ForgeConfigSpec.BooleanValue TARGET_CREATIVE_PLAYERS;
   public static final ForgeConfigSpec.BooleanValue TARGET_EMPTY_VEHICLES;
   public static final ForgeConfigSpec.BooleanValue MISSILE_MEASURED_TARGET_VELOCITY;
   public static final ForgeConfigSpec.ConfigValue<List<? extends String>> EXTRA_TARGET_TYPES;

   // --- F-16 ------------------------------------------------------------------------------------------------------
   public static final ForgeConfigSpec.DoubleValue F16_SCAN_RANGE;
   public static final ForgeConfigSpec.DoubleValue F16_STEALTH_RANGE;
   public static final ForgeConfigSpec.IntValue F16_SCAN_INTERVAL;
   public static final ForgeConfigSpec.IntValue F16_REACTION_TICKS;
   public static final ForgeConfigSpec.DoubleValue F16_DETECTION_FAILURE;
   public static final ForgeConfigSpec.IntValue F16_MAX_MISSILES_PER_TARGET;
   public static final ForgeConfigSpec.DoubleValue F16_LEASH_RADIUS;
   public static final ForgeConfigSpec.DoubleValue F16_MIN_CLEARANCE;
   public static final ForgeConfigSpec.BooleanValue F16_EVASIVE;
   public static final ForgeConfigSpec.DoubleValue F16_EVASION_RANGE;

   // --- ground units ------------------------------------------------------------------------------------------------
   public static final ForgeConfigSpec.DoubleValue GROUND_SCAN_RANGE;
   public static final ForgeConfigSpec.DoubleValue GROUND_ATTACK_RANGE;
   public static final ForgeConfigSpec.IntValue GROUND_SCAN_INTERVAL;
   public static final ForgeConfigSpec.BooleanValue GROUND_ENGAGE_JETS;
   public static final ForgeConfigSpec.BooleanValue GROUND_AVOID_WATER;

   // --- SAM (Wasp) --------------------------------------------------------------------------------------------------
   public static final ForgeConfigSpec.DoubleValue SAM_RANGE;
   public static final ForgeConfigSpec.DoubleValue SAM_STEALTH_RANGE;
   public static final ForgeConfigSpec.IntValue SAM_SCAN_INTERVAL;
   public static final ForgeConfigSpec.IntValue SAM_FIRE_INTERVAL;
   public static final ForgeConfigSpec.IntValue SAM_MISSILES_PER_AIRCRAFT;
   public static final ForgeConfigSpec.IntValue SAM_MISSILES_PER_PROJECTILE;
   public static final ForgeConfigSpec.BooleanValue SAM_ENGAGE_NON_THREATS;

   private static volatile Set<EntityType<?>> extraTypes = Set.of();

   static {
      ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();

      b.push("general");
      PROJECTILE_MAX_LIFETIME = b.comment("Ticks before any projectile is discarded (cruise missiles exempt). 500 = 25 s.")
         .defineInRange("projectileMaxLifetime", 500, 20, 72000);
      IR_AAM_MAX_LIFETIME = b.comment("Ticks before IR air-to-air missiles (AIM-9 / PL-10 / R-73 / 9M336 ...) are discarded.")
         .defineInRange("irAamMaxLifetime", 160, 20, 72000);
      MAX_LOS_CHECKS_PER_SCAN = b.comment("Upper bound of line-of-sight raycasts a single unit may do per target scan.",
            "Candidates are sorted by priority first, so only the best few are ever raycast.")
         .defineInRange("maxLosChecksPerScan", 4, 1, 64);
      TARGET_CREATIVE_PLAYERS = b.comment("Whether AI units attack players in creative mode (spectators are never attacked).")
         .define("targetCreativePlayers", true);
      TARGET_EMPTY_VEHICLES = b.comment("Whether AI units attack unmanned vehicles that do not belong to a hostile scoreboard team.")
         .define("targetEmptyVehicles", false);
      EXTRA_TARGET_TYPES = b.comment("Extra entity type ids AI units treat as hostile, e.g. [\"minecraft:pillager\", \"somemod:soldier\"].",
            "By default only players and vehicles are targets (animals / villagers / random mobs are ignored).")
         .defineListAllowEmpty("extraTargetTypes", List.of(), o -> o instanceof String s && ResourceLocation.tryParse(s) != null);
      b.pop();

      b.push("missiles");
      MISSILE_MEASURED_TARGET_VELOCITY = b.comment(
            "SBW / DragonRise missiles (AIM-120, 9M336, AGM-65, Kh-39 ...) aim at a predicted intercept point computed from the",
            "target's motion. Player-driven vehicles and this addon's jets report zero motion on the server, so missiles fell back",
            "to pure pursuit. true = use the target's measured velocity (applies to every missile, players' included); false = SBW behaviour.")
         .define("measuredTargetVelocity", true);
      b.pop();

      b.push("f16");
      F16_SCAN_RANGE = b.defineInRange("scanRange", 1024.0, 64.0, 4096.0);
      F16_STEALTH_RANGE = b.comment("Detection range against 5th-gen aircraft (J-20 / F-22 / F-35 / Su-57 ...).")
         .defineInRange("stealthScanRange", 512.0, 32.0, 4096.0);
      F16_SCAN_INTERVAL = b.defineInRange("scanInterval", 40, 5, 400);
      F16_REACTION_TICKS = b.comment("Delay between acquiring a new target and the first shot.")
         .defineInRange("reactionTicks", 15, 0, 200);
      F16_DETECTION_FAILURE = b.comment("Chance that a scan fails to acquire a NEW target (radar clutter).",
            "Unlike 1.0.0 this never drops a target the jet is already fighting.")
         .defineInRange("detectionFailureChance", 0.15, 0.0, 0.95);
      F16_MAX_MISSILES_PER_TARGET = b.comment("Max air-to-air / air-to-ground missiles from enemy jets in flight against one target.")
         .defineInRange("maxMissilesPerTarget", 2, 1, 16);
      F16_LEASH_RADIUS = b.comment("Jets that get further than this from their home point break off and fly back.")
         .defineInRange("leashRadius", 1536.0, 128.0, 16384.0);
      F16_MIN_CLEARANCE = b.comment("Terrain clearance in cruise / patrol. Air combat flies down to 80 % of it, bomb / gun dives to 30 blocks,",
            "and nothing ever goes below 10 blocks above the ground.")
         .defineInRange("minGroundClearance", 50.0, 15.0, 200.0);
      F16_EVASIVE = b.comment("Jets beam (turn perpendicular to) closing air-to-air missiles locked on them, in addition to dropping decoys.")
         .define("evasiveManeuvers", true);
      F16_EVASION_RANGE = b.comment("Distance at which a closing missile triggers the evasive manoeuvre.")
         .defineInRange("evasionRange", 320.0, 32.0, 2048.0);
      b.pop();

      b.push("ground");
      GROUND_SCAN_RANGE = b.defineInRange("scanRange", 328.0, 16.0, 2048.0);
      GROUND_ATTACK_RANGE = b.defineInRange("attackRange", 160.0, 8.0, 2048.0);
      GROUND_SCAN_INTERVAL = b.defineInRange("scanInterval", 20, 5, 400);
      GROUND_ENGAGE_JETS = b.comment("Whether tanks waste main-gun rounds on fixed-wing aircraft. Helicopters / drones are always engaged.")
         .define("engageJets", false);
      GROUND_AVOID_WATER = b.comment("Ground units steer around water / lava instead of driving into it.")
         .define("avoidWater", true);
      b.pop();

      b.push("sam");
      SAM_RANGE = b.defineInRange("range", 1000.0, 32.0, 4096.0);
      SAM_STEALTH_RANGE = b.defineInRange("stealthRange", 400.0, 16.0, 4096.0);
      SAM_SCAN_INTERVAL = b.defineInRange("scanInterval", 10, 2, 200);
      SAM_FIRE_INTERVAL = b.defineInRange("fireInterval", 40, 5, 400);
      SAM_MISSILES_PER_AIRCRAFT = b.comment("Shoot-look-shoot: max SAM missiles in flight against one aircraft (all launchers combined).")
         .defineInRange("missilesPerAircraft", 2, 1, 16);
      SAM_MISSILES_PER_PROJECTILE = b.comment("Max SAM missiles in flight against one incoming missile / shell.")
         .defineInRange("missilesPerProjectile", 1, 1, 16);
      SAM_ENGAGE_NON_THREATS = b.comment("1.0.0 behaviour: intercept every hostile projectile in range, even ones flying away from",
            "everything friendly. Default false = only projectiles that threaten the SAM or a friendly unit.")
         .define("engageNonThreatProjectiles", false);
      b.pop();

      SPEC = b.build();
   }

   private Config() {
   }

   public static Set<EntityType<?>> extraTargetTypes() {
      return extraTypes;
   }

   static void onConfigChanged(ModConfigEvent event) {
      if (event.getConfig().getSpec() != SPEC) {
         return;
      }

      Set<EntityType<?>> types = new HashSet<>();
      for (String id : EXTRA_TARGET_TYPES.get()) {
         ResourceLocation rl = ResourceLocation.tryParse(id);
         if (rl != null && ForgeRegistries.ENTITY_TYPES.containsKey(rl)) {
            types.add(ForgeRegistries.ENTITY_TYPES.getValue(rl));
         }
      }

      extraTypes = Set.copyOf(types);
   }
}
