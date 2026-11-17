package com.f16addon;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** One persistent, four-unit combined-arms encounter per dimension; no chunk tickets or endless waves. */
@Mod.EventBusSubscriber(modid = F16Addon.MODID)
public final class EncounterDirector {
    private static final String MARKER = "enemyforces_encounter";
    private EncounterDirector() {}

    private static State state(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(State::load, State::new, "enemyforces_encounter");
    }

    public static int start(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        State state = state(level);
        if (state.stage != 0) { player.sendSystemMessage(Component.literal("本维度已有战术任务，使用 /f16addon encounter status 查看。")); return 0; }
        Vec3 origin = player.position();
        Vec3 samPosition = ground(level, origin.x + 80, origin.z);
        Vec3 tankPosition = ground(level, origin.x + 56, origin.z + 24);
        Vec3 bmptPosition = ground(level, origin.x + 56, origin.z - 24);
        if (samPosition == null || tankPosition == null || bmptPosition == null) {
            player.sendSystemMessage(Component.literal("附近没有足够的已加载安全地面，请在开阔地区开始任务。")); return 0;
        }
        List<Entity> spawned = new ArrayList<>();
        Entity sam = ((EnemySamDeployerItem) ModItems.ENEMY_WASP_DEPLOYER.get()).deployAt(level, null, samPosition);
        if (sam != null) spawned.add(sam);
        Entity tank = ((EnemyGroundDeployerItem) ModItems.ENEMY_T80_DEPLOYER.get()).deployAt(level, null, tankPosition);
        if (tank != null) spawned.add(tank);
        Entity bmpt = ((EnemyGroundDeployerItem) ModItems.ENEMY_BMPT72_DEPLOYER.get()).deployAt(level, null, bmptPosition);
        if (bmpt != null) spawned.add(bmpt);
        if (spawned.size() != 3) {
            spawned.forEach(Entity::discard);
            player.sendSystemMessage(Component.literal("载具创建失败，已撤销本次生成；请检查模组依赖和日志。")); return 0;
        }
        state.owner = player.getUUID(); state.origin = origin; state.sam = sam.getUUID(); state.stage = 1;
        for (Entity unit : spawned) { unit.addTag(MARKER); state.alive.add(unit.getUUID()); }
        state.setDirty();
        player.sendSystemMessage(Component.literal("任务开始：摧毁黄蜂防空车。T-80 和 BMPT 正在保护阵地。"));
        return 1;
    }

    private static Vec3 ground(ServerLevel level, double x, double z) {
        if (!WorldQueries.isLoaded(level, x, z)) return null;
        int height = WorldQueries.height(level, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z, Integer.MIN_VALUE);
        if (height == Integer.MIN_VALUE || !WorldQueries.isEntityTicking(level, x, height, z)) return null;
        var fluid = WorldQueries.fluid(level, net.minecraft.core.BlockPos.containing(x, height - 1, z));
        return fluid != null && fluid.isEmpty() ? new Vec3(x, height + 1, z) : null;
    }

    public static void tick(ServerLevel level) {
        if (level.getGameTime() % 20 != 0) return;
        State state = state(level);
        if (state.stage == 0) return;
        boolean changed = state.alive.removeIf(id -> {
            Entity unit = level.getEntity(id);
            // A null entity may be unloaded: never count it as a kill.
            return unit != null && (!unit.isAlive() || Sbw.isWrecked(unit));
        });
        if (changed) state.setDirty();
        ServerPlayer owner = level.getServer().getPlayerList().getPlayer(state.owner);
        if (owner == null || owner.serverLevel() != level) return;
        if (state.stage == 1 && !state.alive.contains(state.sam)) {
            Vec3 airPosition = ground(level, state.origin.x + 96, state.origin.z - 64);
            if (airPosition == null) return; // Wait for the original area to load; do not force-load it.
            Entity fighter = ((EnemyF16DeployerItem) ModItems.ENEMY_F16_DEPLOYER.get()).deployAt(level, null, airPosition);
            if (fighter == null) { state.stage = 3; state.setDirty(); owner.sendSystemMessage(Component.literal("防空已摧毁；空中增援创建失败，继续清理地面守军。")); }
            else {
                fighter.addTag(MARKER); state.alive.add(fighter.getUUID()); state.stage = 2; state.setDirty();
                owner.sendSystemMessage(Component.literal("防空阵地已失守，一架 F-16 正在增援。击败剩余敌军完成任务。"));
            }
        }
        if ((state.stage == 2 || state.stage == 3) && state.alive.isEmpty()) {
            state.stage = 0; state.setDirty();
            owner.giveExperiencePoints(200);
            owner.sendSystemMessage(Component.literal("战术任务完成，获得 200 点经验。"));
        }
    }

    public static int status(ServerPlayer player) {
        State state = state(player.serverLevel());
        String message = state.stage == 0 ? "当前没有战术任务。" : "战术任务阶段 " + state.stage + "，剩余敌军 " + state.alive.size() + "。";
        player.sendSystemMessage(Component.literal(message)); return state.stage;
    }

    public static int stop(ServerPlayer player) {
        State state = state(player.serverLevel());
        state.stage = 0; state.alive.clear(); state.setDirty();
        player.sendSystemMessage(Component.literal("任务已结束，已生成的敌军保留在世界中。")); return 1;
    }

    @SubscribeEvent
    public static void onLeave(EntityLeaveLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !event.getEntity().getTags().contains(MARKER)) return;
        var reason = event.getEntity().getRemovalReason();
        if (reason != null && reason.shouldDestroy()) {
            State state = state(level);
            if (state.alive.remove(event.getEntity().getUUID())) state.setDirty();
        }
    }

    public static final class State extends SavedData {
        int stage;
        UUID owner = new UUID(0, 0), sam = new UUID(0, 0);
        Vec3 origin = Vec3.ZERO;
        final Set<UUID> alive = new HashSet<>();

        static State load(CompoundTag tag) {
            State state = new State();
            state.stage = tag.getInt("stage");
            if (tag.hasUUID("owner")) state.owner = tag.getUUID("owner");
            if (tag.hasUUID("sam")) state.sam = tag.getUUID("sam");
            state.origin = new Vec3(tag.getDouble("x"), tag.getDouble("y"), tag.getDouble("z"));
            ListTag alive = tag.getList("alive", Tag.TAG_STRING);
            for (int i = 0; i < alive.size(); i++) {
                try { state.alive.add(UUID.fromString(alive.getString(i))); } catch (IllegalArgumentException ignored) { }
            }
            return state;
        }

        @Override
        public CompoundTag save(CompoundTag tag) {
            tag.putInt("stage", stage); tag.putUUID("owner", owner); tag.putUUID("sam", sam);
            tag.putDouble("x", origin.x); tag.putDouble("y", origin.y); tag.putDouble("z", origin.z);
            ListTag list = new ListTag(); for (UUID id : alive) list.add(StringTag.valueOf(id.toString()));
            tag.put("alive", list); return tag;
        }
    }
}
