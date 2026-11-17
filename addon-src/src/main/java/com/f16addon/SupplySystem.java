package com.f16addon;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/** Persistent virtual magazines. Existing SBW inventories are deliberately left untouched. */
public final class SupplySystem {
    private static final String PREFIX = "ef_supply_";
    private SupplySystem() {}

    public static String weaponKey(Entity unit, String projectileId) {
        if (unit.getTags().contains(UnitTracker.TAG_SAM)) return "sam";
        if (unit.getTags().contains(UnitTracker.TAG_GROUND)) return "ground";
        if (projectileId.endsWith("aim120")) return "radar";
        if (projectileId.endsWith("ru_9m336_missile")) return "ir";
        if (projectileId.endsWith("mk_82")) return "bomb";
        if (projectileId.endsWith("kh_39")) return "agm";
        return "cannon";
    }

    private static String[] weapons(Entity unit) {
        if (unit.getTags().contains(UnitTracker.TAG_F16)) return new String[]{"cannon", "ir", "radar", "agm", "bomb"};
        return new String[]{unit.getTags().contains(UnitTracker.TAG_SAM) ? "sam" : "ground"};
    }

    private static int capacity(Entity unit, String weapon) {
        return switch (weapon) {
            case "cannon" -> Config.F16_CANNON_AMMO.get();
            case "ir" -> Config.F16_IR_AMMO.get();
            case "radar" -> Config.F16_RADAR_AMMO.get();
            case "agm" -> Config.F16_AGM_AMMO.get();
            case "bomb" -> Config.F16_BOMB_AMMO.get();
            case "sam" -> Config.SAM_AMMO.get();
            default -> Classify.is(unit, Classify.BMPT) ? Config.BMPT_AMMO.get() : Config.TANK_AMMO.get();
        };
    }

    private static void initialize(Entity unit) {
        CompoundTag data = unit.getPersistentData();
        if (!data.contains(PREFIX + "home_x")) {
            String home = unit.getTags().contains(UnitTracker.TAG_F16) ? "f16_home_" : "ground_home_";
            data.putDouble(PREFIX + "home_x", data.contains(home + "x") ? data.getDouble(home + "x") : unit.getX());
            data.putDouble(PREFIX + "home_y", data.contains(home + "y") ? data.getDouble(home + "y") : unit.getY());
            data.putDouble(PREFIX + "home_z", data.contains(home + "z") ? data.getDouble(home + "z") : unit.getZ());
        }
        for (String key : weapons(unit)) if (!data.contains(PREFIX + key)) data.putInt(PREFIX + key, capacity(unit, key));
    }

    public static boolean canFire(Entity unit, String projectileId) {
        if (!Config.FINITE_AMMO.get()) return true;
        initialize(unit);
        return unit.getPersistentData().getInt(PREFIX + weaponKey(unit, projectileId)) > 0;
    }

    public static void consume(Entity unit, String projectileId) {
        if (!Config.FINITE_AMMO.get()) return;
        initialize(unit);
        CompoundTag data = unit.getPersistentData();
        String key = PREFIX + weaponKey(unit, projectileId);
        data.putInt(key, Math.max(0, data.getInt(key) - 1));
    }

    public static boolean depleted(Entity unit) {
        if (!Config.FINITE_AMMO.get() || (unit.getTags().contains(UnitTracker.TAG_GROUND) && GroundUnitAI.nativeWeaponsActive(unit))) return false;
        initialize(unit);
        for (String key : weapons(unit)) if (unit.getPersistentData().getInt(PREFIX + key) > 0) return false;
        return true;
    }

    /** Aircraft return when no usable air-combat ammunition remains against their current air target. */
    public static boolean needsSupply(Entity unit) {
        if (depleted(unit)) return true;
        if (!Config.FINITE_AMMO.get() || !unit.getTags().contains(UnitTracker.TAG_F16)) return false;
        initialize(unit);
        CompoundTag data = unit.getPersistentData();
        if (!(unit.level() instanceof ServerLevel level)) return false;
        Entity target = Targeting.byUuid(level, data.getString("f16_target"));
        return target != null && Targeting.isAirborne(target)
                && data.getInt(PREFIX + "cannon") <= 0 && data.getInt(PREFIX + "ir") <= 0 && data.getInt(PREFIX + "radar") <= 0;
    }

    public static Vec3 home(Entity unit) {
        initialize(unit);
        CompoundTag data = unit.getPersistentData();
        return new Vec3(data.getDouble(PREFIX + "home_x"), data.getDouble(PREFIX + "home_y"), data.getDouble(PREFIX + "home_z"));
    }

    public static boolean nearHome(Entity unit) {
        Vec3 home = home(unit);
        double dx = home.x - unit.getX(), dz = home.z - unit.getZ();
        double radius = unit.getTags().contains(UnitTracker.TAG_F16) ? Config.SUPPLY_RADIUS.get() : Math.min(24.0, Config.SUPPLY_RADIUS.get());
        return dx * dx + dz * dz <= radius * radius;
    }

    /** A return request is latched until service completes, including when its old target disappears. */
    public static boolean update(ServerLevel level, Entity unit) {
        initialize(unit);
        CompoundTag data = unit.getPersistentData();
        if (!Config.FINITE_AMMO.get()) { data.remove(PREFIX + "return"); data.remove(PREFIX + "service_start"); return false; }
        if (needsSupply(unit)) data.putBoolean(PREFIX + "return", true);
        if (!data.getBoolean(PREFIX + "return")) return false;
        if (!nearHome(unit)) { data.remove(PREFIX + "service_start"); return true; }
        long now = level.getGameTime();
        if (!data.contains(PREFIX + "service_start") || now < data.getLong(PREFIX + "service_start")) data.putLong(PREFIX + "service_start", now);
        if (now - data.getLong(PREFIX + "service_start") >= Config.SUPPLY_WAIT_TICKS.get()) { refill(unit); return false; }
        return true;
    }

    public static void refill(Entity unit) {
        initialize(unit);
        CompoundTag data = unit.getPersistentData();
        for (String key : weapons(unit)) data.putInt(PREFIX + key, capacity(unit, key));
        data.remove(PREFIX + "return");
        data.remove(PREFIX + "service_start");
        data.putInt("ground_burst_count", 0);
        data.putInt("f16_gun_burst_count", 0);
    }

    public static boolean wounded(Entity unit) {
        double threshold = Config.RETREAT_HEALTH.get();
        if (threshold <= 0) return false;
        Object hp = Sbw.call(unit, Sbw.GET_HEALTH);
        Object max = Sbw.call(unit, Sbw.GET_MAX_HEALTH);
        if (!(hp instanceof Number health) || !(max instanceof Number maximum) || maximum.doubleValue() <= 0 || health.doubleValue() <= 0) return false;
        CompoundTag data = unit.getPersistentData();
        double fraction = health.doubleValue() / maximum.doubleValue();
        if (fraction < threshold) data.putBoolean("ef_withdrawing", true);
        else if (fraction >= Math.min(1.0, threshold + 0.15)) data.putBoolean("ef_withdrawing", false);
        return data.getBoolean("ef_withdrawing");
    }
}
