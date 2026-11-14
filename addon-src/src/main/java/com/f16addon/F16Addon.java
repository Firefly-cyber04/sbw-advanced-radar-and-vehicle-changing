package com.f16addon;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(F16Addon.MODID)
public final class F16Addon {
   public static final String MODID = "f16addon";
   static final Logger LOGGER = LogUtils.getLogger();
   private static final double COMMAND_RANGE = 512.0;
   private static final int ENERGY_REFILL_INTERVAL = 20;

   private static final Perf PERF = new Perf();

   @SuppressWarnings("removal")
   public F16Addon() {
      IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
      ModItems.ITEMS.register(modBus);
      ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, Config.SPEC);
      modBus.addListener(EventPriority.NORMAL, false, ModConfigEvent.Loading.class, Config::onConfigChanged);
      modBus.addListener(EventPriority.NORMAL, false, ModConfigEvent.Reloading.class, Config::onConfigChanged);

      IEventBus bus = MinecraftForge.EVENT_BUS;
      bus.addListener(F16Addon::onServerTick);
      bus.addListener(F16Addon::onServerStopped);
      bus.addListener(EventPriority.NORMAL, false, ServerStartedEvent.class, e -> Sbw.selfCheck(LOGGER));
      bus.addListener(PlayerFireControl::onEntityJoin);
      // LOWEST + not receiving cancelled: only entities that really join are tracked.
      bus.addListener(EventPriority.LOWEST, false, EntityJoinLevelEvent.class, UnitTracker::onJoin);
      bus.addListener(UnitTracker::onLeave);
      bus.addListener(UnitTracker::onLevelUnload);
      bus.addListener(F16Addon::onRegisterCommands);
   }

   // ----------------------------------------------------------------------------------------------------------------
   // Tick
   // ----------------------------------------------------------------------------------------------------------------

   private static void onServerTick(TickEvent.ServerTickEvent event) {
      if (event.phase != TickEvent.Phase.END) {
         return;
      }

      MinecraftServer server = event.getServer();
      if (server == null) {
         return;
      }

      long start = System.nanoTime();
      for (ServerLevel level : server.getAllLevels()) {
         tickLevel(level);
      }

      if (server.getTickCount() % 20 == 0) {
         Targeting.pruneBoard();
      }

      PERF.record(System.nanoTime() - start);
   }

   private static void tickLevel(ServerLevel level) {
      UnitTracker.maintain(level);
      if (!UnitTracker.levelTicksEntities(level)) {
         return;
      }

      UnitTracker.Units units = UnitTracker.get(level);

      tickProjectiles(level, units);

      long time = level.getGameTime();
      for (Entity f16 : units.f16()) {
         if (!readyToThink(level, f16)) {
            continue;
         }

         if (F16FlightAI.isWrecked(f16)) {
            F16FlightAI.tick(level, f16);
            continue;
         }

         initializeGunData(f16);
         F16FlightAI.tick(level, f16);
         if ((time + f16.getId()) % ENERGY_REFILL_INTERVAL == 0L) {
            refillEnergy(f16);
         }
      }

      for (Entity unit : units.ground()) {
         if (readyToThink(level, unit)) {
            GroundUnitAI.tick(level, unit);
         }
      }

      for (Entity sam : units.sams()) {
         if (readyToThink(level, sam)) {
            SamUnitAI.tick(level, sam);
         }
      }
   }

   /**
    * AI only runs where vanilla would tick the entity. 1.0.0 drove every loaded unit, including ones in border chunks
    * that do not tick, so units walked / flew out of the loaded area and dragged chunk loads with them.
    */
   private static boolean readyToThink(ServerLevel level, Entity unit) {
      if (!UnitTracker.isLive(level, unit) || !WorldQueries.isEntityTicking(level, unit)) {
         return false;
      }

      // A player at the controls (captured / hijacked unit) flies it themselves; the AI must not fight the stick.
      return !(unit.getControllingPassenger() instanceof Player);
   }

   private static void tickProjectiles(ServerLevel level, UnitTracker.Units units) {
      int normal = Config.PROJECTILE_MAX_LIFETIME.get();
      int ir = Config.IR_AAM_MAX_LIFETIME.get();
      List<Entity> expired = null;
      for (Entity entity : units.projectiles()) {
         if (entity.isRemoved()) {
            continue;
         }

         int traits = Classify.traits(entity.getClass());
         int max = (traits & Classify.CRUISE) != 0 ? Integer.MAX_VALUE : ((traits & Classify.IR_AAM) != 0 ? ir : normal);
         if (entity.tickCount > max) {
            if (expired == null) {
               expired = new ArrayList<>();
            }

            expired.add(entity);
            continue;
         }

         if ((traits & (Classify.IR_AAM | Classify.RADAR_AAM)) != 0 && F16MissileDefense.isEnemyAam(entity)) {
            F16MissileDefense.tick(level, entity);
         }
      }

      if (expired != null) {
         for (Entity e : expired) {
            e.discard();
         }
      }
   }

   private static void onServerStopped(ServerStoppedEvent event) {
      Targeting.clearBoard();
      PERF.reset();
   }

   // ----------------------------------------------------------------------------------------------------------------
   // SBW helpers
   // ----------------------------------------------------------------------------------------------------------------

   public static boolean initializeGunData(Entity entity) {
      CompoundTag data = entity.getPersistentData();
      if (data.getBoolean("f16addon_gun_init")) {
         return true;
      }

      if (data.getBoolean("f16addon_gun_failed")) {
         return false;
      }

      Object map = Sbw.call(entity, Sbw.GET_GUN_DATA_MAP);
      if (map instanceof Map<?, ?> && Sbw.has(entity, Sbw.SET_GUN_DATA_MAP)) {
         try {
            Sbw.method(entity.getClass(), Sbw.SET_GUN_DATA_MAP).invoke(entity, map);
            data.putBoolean("f16addon_gun_init", true);
            return true;
         } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.error("Failed to initialize GunDataMap for {}", entity, e);
         }
      } else {
         LOGGER.error("Failed to initialize GunDataMap for {}: SBW API not found", entity);
      }

      data.putBoolean("f16addon_gun_failed", true);
      return false;
   }

   private static void refillEnergy(Entity entity) {
      if (Sbw.call(entity, Sbw.GET_MAX_ENERGY) instanceof Integer max && Sbw.has(entity, Sbw.SET_ENERGY)) {
         if (!(Sbw.call(entity, Sbw.GET_ENERGY) instanceof Integer current) || current < max) {
            Sbw.call(entity, Sbw.SET_ENERGY, max);
         }

         return;
      }

      entity.getCapability(ForgeCapabilities.ENERGY).ifPresent(storage -> {
         int missing = storage.getMaxEnergyStored() - storage.getEnergyStored();
         if (missing > 0) {
            storage.receiveEnergy(missing, false);
         }
      });
   }

   // ----------------------------------------------------------------------------------------------------------------
   // Commands
   // ----------------------------------------------------------------------------------------------------------------

   private static void onRegisterCommands(RegisterCommandsEvent event) {
      LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("f16addon")
         .then(Commands.literal("lock").then(Commands.argument("uuid", StringArgumentType.string())
            .executes(ctx -> lockTarget(ctx.getSource(), StringArgumentType.getString(ctx, "uuid")))))
         .then(Commands.literal("clear").executes(ctx -> clearTargets(ctx.getSource())))
         .then(Commands.literal("mode").then(Commands.argument("mode", StringArgumentType.word())
            .executes(ctx -> setMode(ctx.getSource(), StringArgumentType.getString(ctx, "mode")))))
         .then(Commands.literal("playerlock").then(Commands.argument("uuid", StringArgumentType.string())
            .executes(ctx -> setPlayerLock(ctx.getSource(), StringArgumentType.getString(ctx, "uuid")))))
         .then(Commands.literal("playerclear").executes(ctx -> clearPlayerLock(ctx.getSource())))
         .then(Commands.literal("groundmode").then(Commands.argument("mode", StringArgumentType.word())
            .executes(ctx -> setGroundMode(ctx.getSource(), StringArgumentType.getString(ctx, "mode")))))
         .then(Commands.literal("perf").requires(s -> s.hasPermission(2)).executes(ctx -> perf(ctx.getSource())));
      event.getDispatcher().register(root);
   }

   private static ServerPlayer player(CommandSourceStack source) {
      try {
         return source.getPlayerOrException();
      } catch (Exception e) {
         source.sendFailure(Component.literal("必须由玩家执行"));
         return null;
      }
   }

   /** Applies {@code action} to our units with {@code tag} within {@link #COMMAND_RANGE} of the player. */
   private static int forUnitsNear(ServerPlayer player, Entity[] units, Consumer<Entity> action) {
      int count = 0;
      double rangeSqr = COMMAND_RANGE * COMMAND_RANGE;
      for (Entity unit : units) {
         if (!unit.isRemoved() && player.distanceToSqr(unit) <= rangeSqr) {
            action.accept(unit);
            count++;
         }
      }

      return count;
   }

   private static int setPlayerLock(CommandSourceStack source, String uuidText) {
      ServerPlayer player = player(source);
      if (player == null) {
         return 0;
      }

      Entity target = Targeting.byUuid(player.serverLevel(), uuidText);
      if (target == null || !target.isAlive() || target.isSpectator()) {
         source.sendFailure(Component.literal("目标不存在或已死亡"));
         return 0;
      }

      if (player.distanceToSqr(target) > COMMAND_RANGE * COMMAND_RANGE) {
         source.sendFailure(Component.literal("目标超出 512 格"));
         return 0;
      }

      player.getPersistentData().putString(PlayerFireControl.PLAYER_TARGET, target.getStringUUID());
      source.sendSuccess(() -> Component.literal("本机火控已锁定：" + target.getDisplayName().getString()).withStyle(ChatFormatting.GREEN), false);
      return 1;
   }

   private static int clearPlayerLock(CommandSourceStack source) {
      ServerPlayer player = player(source);
      if (player == null) {
         return 0;
      }

      player.getPersistentData().remove(PlayerFireControl.PLAYER_TARGET);
      source.sendSuccess(() -> Component.literal("已清除本机火控锁定").withStyle(ChatFormatting.YELLOW), false);
      return 1;
   }

   private static int setGroundMode(CommandSourceStack source, String modeText) {
      ServerPlayer player = player(source);
      if (player == null) {
         return 0;
      }

      String mode = modeText.toLowerCase(Locale.ROOT);
      if (!mode.equals("advance") && !mode.equals("hold") && !mode.equals("follow") && !mode.equals("stop")) {
         source.sendFailure(Component.literal("模式只能是 advance / hold / follow / stop"));
         return 0;
      }

      int count = forUnitsNear(player, UnitTracker.get(player.serverLevel()).ground(), u -> u.getPersistentData().putString("ground_mode", mode));
      if (count == 0) {
         source.sendFailure(Component.literal("512 格内没有敌方地面单位"));
         return 0;
      }

      String label = switch (mode) {
         case "advance" -> "推进";
         case "hold" -> "防守";
         case "follow" -> "跟随";
         default -> "停火";
      };
      source.sendSuccess(() -> Component.literal("已设置 " + count + " 辆地面单位：" + label).withStyle(ChatFormatting.GREEN), false);
      return count;
   }

   private static int setMode(CommandSourceStack source, String modeText) {
      ServerPlayer player = player(source);
      if (player == null) {
         return 0;
      }

      String mode = modeText.toLowerCase(Locale.ROOT);
      if (!mode.equals("auto") && !mode.equals("patrol") && !mode.equals("return")) {
         source.sendFailure(Component.literal("模式只能是 auto / patrol / return"));
         return 0;
      }

      int count = forUnitsNear(player, UnitTracker.get(player.serverLevel()).f16(), f16 -> {
         f16.getPersistentData().putString("f16_mode", mode);
         f16.getPersistentData().putString("f16_target", "");
      });
      if (count == 0) {
         source.sendFailure(Component.literal("512 格内没有敌方 F-16"));
         return 0;
      }

      String label = mode.equals("auto") ? "自动索敌" : (mode.equals("patrol") ? "只巡逻/不攻击" : "返航");
      source.sendSuccess(() -> Component.literal("已设置 " + count + " 架 F-16：" + label).withStyle(ChatFormatting.GREEN), false);
      return count;
   }

   private static int lockTarget(CommandSourceStack source, String uuidText) {
      ServerPlayer player = player(source);
      if (player == null) {
         return 0;
      }

      UUID targetId;
      try {
         targetId = UUID.fromString(uuidText);
      } catch (IllegalArgumentException e) {
         source.sendFailure(Component.literal("无效的目标 UUID"));
         return 0;
      }

      Entity target = player.serverLevel().getEntity(targetId);
      if (target == null || !target.isAlive()) {
         source.sendFailure(Component.literal("目标不存在或已死亡"));
         return 0;
      }

      int count = forUnitsNear(player, UnitTracker.get(player.serverLevel()).f16(),
         f16 -> f16.getPersistentData().putString("f16_locked_target", targetId.toString()));
      if (count == 0) {
         source.sendFailure(Component.literal("512 格内没有敌方 F-16"));
         return 0;
      }

      source.sendSuccess(() -> Component.literal("已锁定 " + target.getDisplayName().getString() + "（" + count + " 架 F-16）").withStyle(ChatFormatting.GREEN), false);
      return count;
   }

   private static int clearTargets(CommandSourceStack source) {
      ServerPlayer player = player(source);
      if (player == null) {
         return 0;
      }

      int count = forUnitsNear(player, UnitTracker.get(player.serverLevel()).f16(), f16 -> f16.getPersistentData().remove("f16_locked_target"));
      source.sendSuccess(() -> Component.literal("已清除 " + count + " 架 F-16 的锁定").withStyle(ChatFormatting.YELLOW), false);
      return count;
   }

   private static int perf(CommandSourceStack source) {
      int f16 = 0;
      int ground = 0;
      int sam = 0;
      int vehicles = 0;
      int projectiles = 0;
      for (ServerLevel level : source.getServer().getAllLevels()) {
         UnitTracker.Units u = UnitTracker.get(level);
         f16 += u.f16().length;
         ground += u.ground().length;
         sam += u.sams().length;
         vehicles += u.vehicleCount();
         projectiles += u.projectileCount();
      }

      String text = String.format(Locale.ROOT,
         "Enemy Forces AI: avg %.3f ms / tick, max %.3f ms (last %d ticks)%n units: %d F-16, %d ground, %d SAM | tracked: %d vehicles, %d projectiles",
         PERF.averageMs(), PERF.maxMs(), PERF.window(), f16, ground, sam, vehicles, projectiles);
      source.sendSuccess(() -> Component.literal(text).withStyle(ChatFormatting.AQUA), false);
      return 1;
   }

   /** Rolling AI cost over the last 200 ticks, for {@code /f16addon perf}. */
   private static final class Perf {
      private final long[] samples = new long[200];
      private int index;
      private int filled;

      void record(long nanos) {
         samples[index] = nanos;
         index = (index + 1) % samples.length;
         filled = Math.min(filled + 1, samples.length);
      }

      void reset() {
         index = 0;
         filled = 0;
      }

      int window() {
         return filled;
      }

      double averageMs() {
         if (filled == 0) {
            return 0.0;
         }

         long sum = 0;
         for (int i = 0; i < filled; i++) {
            sum += samples[i];
         }

         return sum / (double) filled / 1.0E6;
      }

      double maxMs() {
         long max = 0;
         for (int i = 0; i < filled; i++) {
            max = Math.max(max, samples[i]);
         }

         return max / 1.0E6;
      }
   }
}
