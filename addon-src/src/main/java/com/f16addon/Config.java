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
      PROJECTILE_MAX_LIFETIME = b.comment("Lifetime for managed addon ammunition. Vanilla arrows and tridents are excluded. 500 = 25 s.")
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
      F16_COMBAT_CAN_LEAVE_LEASH = b.comment("Allow a fighting aircraft to temporarily leave its home leash.").define("combatCanLeaveLeash", true);
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

      b.push("combatTuning");
      F16_IR_DAMAGE = b.comment("Standalone IR missile entities have no gun-data defaults; explicitly configure AI damage.").defineInRange("f16IRDamage", 260, 0, 10000);
      F16_IR_EXPLOSION_DAMAGE = b.defineInRange("f16IRExplosionDamage", 160, 0, 10000);
      F16_IR_EXPLOSION_RADIUS = b.defineInRange("f16IRExplosionRadius", 6.0, 0.0, 32.0);
      F16_CANNON_EXPLOSION_RADIUS = b.comment("Direct cannon damage remains at the original entity default.").defineInRange("f16CannonExplosionRadius", 1.5, 0.0, 16.0);
      AI_PROJECTILE_CHUNK_LOADING = b.comment("Allow marked AI missiles to force-load chunks. False keeps them inside the loaded, ticking battlefield; player missiles retain SBW behavior.").define("aiProjectileChunkLoading", false);
      SAM_MISSILE_LIFETIME = b.comment("Own SAM missile lifetime; independent from short-range F-16 IR missile lifetime.").defineInRange("samMissileLifetime", 400, 40, 4000);
      DEBUG_AI = b.comment("Detailed per-aircraft state logging. Disabled in normal play.").define("debugAI", false);
      AI_SHOTS_PER_TICK = b.comment("Per dimension, only ammunition spawned by this addon.").defineInRange("aiShotsPerTick", 32, 1, 512);
      INTERCEPT_RESERVED_SHOTS = b.comment("Extra shots and live-projectile slots reserved for urgent SAM interception.").defineInRange("interceptReservedShots", 8, 0, 128);
      MAX_AI_PROJECTILES = b.comment("Active marked AI ammunition limit per dimension; player ammunition is never deleted.").defineInRange("maxAIProjectiles", 512, 32, 8192);
      CRUISE_MAX_LIFETIME = b.comment("Separate lifetime for cruise missiles: default two minutes.").defineInRange("cruiseMaxLifetime", 2400, 200, 72000);
      MANAGE_EXTERNAL_PROJECTILES = b.comment("Also expire SBW/DragonRise ammunition not spawned by this addon. Vanilla projectiles are always excluded.").define("manageExternalProjectiles", false);
      GROUND_NATIVE_WEAPONS = b.comment("With a mob crew, use native weapons instead of addon shells. Unmanned units keep addon fire; finite magazines do not govern native fire.").define("groundNativeWeapons", false);
      BMPT_BURST_SIZE = b.comment("Shots before a cooling pause.").defineInRange("bmptBurstSize", 6, 1, 64);
      BMPT_SHOT_INTERVAL = b.comment("Ticks between shots within a burst.").defineInRange("bmptShotInterval", 6, 2, 100);
      BMPT_BURST_PAUSE = b.comment("Ticks after a complete BMPT burst.").defineInRange("bmptBurstPause", 40, 5, 400);
      TANK_RELOAD_TICKS = b.comment("T-80 main gun reload time.").defineInRange("tankReloadTicks", 50, 10, 400);
      F16_GUN_BURST_SIZE = b.comment("Cannon shots before cooling.").defineInRange("f16GunBurstSize", 12, 1, 100);
      F16_GUN_BURST_PAUSE = b.comment("Cannon cooling time after a burst.").defineInRange("f16GunBurstPause", 40, 4, 400);
      BMPT_EXPLOSION_RADIUS = b.comment("Addon BMPT HE radius. Direct-hit damage is unchanged.").defineInRange("bmptExplosionRadius", 2.5, 0.0, 16.0);
      TANK_EXPLOSION_RADIUS = b.comment("Addon T-80 HE radius. Direct-hit damage is unchanged.").defineInRange("tankExplosionRadius", 6.0, 0.0, 32.0);
      PRESERVE_TERRAIN = b.comment("Skip SBW terrain sampling for marked addon ammunition. Entity damage is preserved; ordinary Forge explosion events also receive terrain protection.").define("preserveTerrain", true);
      FINITE_AMMO = b.comment("Use persistent virtual AI magazines; this is independent of the native inventory.").define("finiteAmmo", true);
      BMPT_AMMO = b.comment("Rounds per BMPT supply cycle.").defineInRange("bmptAmmo", 120, 1, 10000);
      TANK_AMMO = b.comment("Rounds per T-80 supply cycle.").defineInRange("tankAmmo", 18, 1, 10000);
      SAM_AMMO = b.comment("Missiles per SAM supply cycle.").defineInRange("samAmmo", 6, 1, 256);
      F16_CANNON_AMMO = b.comment("F-16 cannon rounds per supply cycle.").defineInRange("f16CannonAmmo", 240, 1, 10000);
      F16_IR_AMMO = b.comment("F-16 short-range missiles per supply cycle.").defineInRange("f16IRAmmo", 4, 1, 256);
      F16_RADAR_AMMO = b.comment("F-16 radar missiles per supply cycle.").defineInRange("f16RadarAmmo", 4, 1, 256);
      F16_AGM_AMMO = b.comment("F-16 air-to-ground missiles per supply cycle.").defineInRange("f16AGMAmmo", 4, 1, 256);
      F16_BOMB_AMMO = b.comment("F-16 bombs per supply cycle.").defineInRange("f16BombAmmo", 4, 1, 256);
      SUPPLY_WAIT_TICKS = b.comment("Uninterrupted ticks near home before depleted magazines refill.").defineInRange("supplyWaitTicks", 200, 20, 72000);
      SUPPLY_RADIUS = b.comment("Horizontal distance from the deployment point considered a supply zone.").defineInRange("supplyRadius", 128.0, 128.0, 512.0);
      F16_INFLIGHT_ENERGY = b.comment("Restore legacy energy refill anywhere. Default only refills near the home supply zone.").define("f16InflightEnergyRefill", false);
      RETREAT_HEALTH = b.comment("Withdraw to home below this health fraction; zero disables. Requires a compatible health API.").defineInRange("retreatHealthFraction", 0.3, 0.0, 0.9);
      SAM_INTERCEPT_SMALL_SHELLS = b.comment("Spend SAM missiles on small cannon shells. Disabled to avoid costly, unproductive interception.").define("samInterceptSmallShells", false);
      MAP_QUEUE_SCAN_LIMIT = b.comment("Maximum queue entries considered in one tactical map update.").defineInRange("mapQueueScanLimit", 512, 32, 8192);
      MAP_DISK_MAX_MIB = b.comment("Upper bound for pending coalesced asynchronous map writes; excess remains with the producer.").defineInRange("mapDiskMaxMiB", 32, 4, 256);
      b.pop();
      SPEC = b.build();
   }

   private Config() {
   }

   public static Set<EntityType<?>> extraTargetTypes() {
      return extraTypes;
   }

   static void onConfigChanged(ModConfigEvent modConfigEvent) {
       if (modConfigEvent.getConfig().getSpec() != SPEC) {
           return;
       }
       HashSet<EntityType<?>> hashSet = new HashSet<EntityType<?>>();
       for (Object e : (List) EXTRA_TARGET_TYPES.get()) {
           ResourceLocation resourceLocation;
           if (!(e instanceof String) || (resourceLocation = ResourceLocation.tryParse((String) ((String) e))) == null || !ForgeRegistries.ENTITY_TYPES.containsKey(resourceLocation))
               continue;
           hashSet.add((EntityType) ForgeRegistries.ENTITY_TYPES.getValue(resourceLocation));
       }
       extraTypes = Set.copyOf(hashSet);
   }

   public static final ForgeConfigSpec.IntValue F16_IR_DAMAGE;

   public static final ForgeConfigSpec.IntValue F16_IR_EXPLOSION_DAMAGE;

   public static final ForgeConfigSpec.DoubleValue F16_IR_EXPLOSION_RADIUS;

   public static final ForgeConfigSpec.DoubleValue F16_CANNON_EXPLOSION_RADIUS;

   public static final ForgeConfigSpec.BooleanValue AI_PROJECTILE_CHUNK_LOADING;

   public static final ForgeConfigSpec.IntValue SAM_MISSILE_LIFETIME;

   public static final ForgeConfigSpec.BooleanValue F16_COMBAT_CAN_LEAVE_LEASH;

   public static final ForgeConfigSpec.BooleanValue DEBUG_AI;

   public static final ForgeConfigSpec.IntValue AI_SHOTS_PER_TICK;

   public static final ForgeConfigSpec.IntValue INTERCEPT_RESERVED_SHOTS;

   public static final ForgeConfigSpec.IntValue MAX_AI_PROJECTILES;

   public static final ForgeConfigSpec.IntValue CRUISE_MAX_LIFETIME;

   public static final ForgeConfigSpec.BooleanValue MANAGE_EXTERNAL_PROJECTILES;

   public static final ForgeConfigSpec.BooleanValue GROUND_NATIVE_WEAPONS;

   public static final ForgeConfigSpec.IntValue BMPT_BURST_SIZE;

   public static final ForgeConfigSpec.IntValue BMPT_SHOT_INTERVAL;

   public static final ForgeConfigSpec.IntValue BMPT_BURST_PAUSE;

   public static final ForgeConfigSpec.IntValue TANK_RELOAD_TICKS;

   public static final ForgeConfigSpec.IntValue F16_GUN_BURST_SIZE;

   public static final ForgeConfigSpec.IntValue F16_GUN_BURST_PAUSE;

   public static final ForgeConfigSpec.DoubleValue BMPT_EXPLOSION_RADIUS;

   public static final ForgeConfigSpec.DoubleValue TANK_EXPLOSION_RADIUS;

   public static final ForgeConfigSpec.BooleanValue PRESERVE_TERRAIN;

   public static final ForgeConfigSpec.BooleanValue FINITE_AMMO;

   public static final ForgeConfigSpec.IntValue BMPT_AMMO;

   public static final ForgeConfigSpec.IntValue TANK_AMMO;

   public static final ForgeConfigSpec.IntValue SAM_AMMO;

   public static final ForgeConfigSpec.IntValue F16_CANNON_AMMO;

   public static final ForgeConfigSpec.IntValue F16_IR_AMMO;

   public static final ForgeConfigSpec.IntValue F16_RADAR_AMMO;

   public static final ForgeConfigSpec.IntValue F16_AGM_AMMO;

   public static final ForgeConfigSpec.IntValue F16_BOMB_AMMO;

   public static final ForgeConfigSpec.IntValue SUPPLY_WAIT_TICKS;

   public static final ForgeConfigSpec.DoubleValue SUPPLY_RADIUS;

   public static final ForgeConfigSpec.BooleanValue F16_INFLIGHT_ENERGY;

   public static final ForgeConfigSpec.DoubleValue RETREAT_HEALTH;

   public static final ForgeConfigSpec.BooleanValue SAM_INTERCEPT_SMALL_SHELLS;

   public static final ForgeConfigSpec.IntValue MAP_QUEUE_SCAN_LIMIT;

   public static final ForgeConfigSpec.IntValue MAP_DISK_MAX_MIB;
}
