package com.f16addon.client.map;

import com.atsuishio.superbwarfare.client.ClientSyncedEntityHandler;
import com.atsuishio.superbwarfare.entity.projectile.MissileProjectile;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.f16addon.Sbw;
import it.unimi.dsi.fastutil.ints.Int2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * Left-hand "situation" panel of the tactical terminal: own-ship data, missile warning, force summary and the nearest
 * hostile contacts (click a row to center the map on it). SBW's screen leaves that side of the screen empty.
 *
 * <p>The picture is rebuilt at most every {@link #REFRESH_MS} ms; drawing is one batched flush.
 */
public final class SituationPanel {
   private static final long REFRESH_MS = 150L;
   private static final int MAX_ROWS = 12;
   private static final int ROW_H = 20;
   private static final int LINE_H = 11;
   private static final int PANEL_MAX_W = 232;
   private static final int PANEL_MIN_W = 150;

   private static final int BG = 0xD80B1116;
   private static final int BORDER = 0xFF2C5B3C;
   private static final int HEADER = 0xFF12301E;
   private static final int ACCENT = 0xFF6CFF9A;
   private static final int TEXT = 0xFFDDE7E0;
   private static final int DIM = 0xFF8FA39A;
   private static final int HOSTILE = 0xFFFF6464;
   private static final int AIR = 0xFFFFB347;
   private static final int WARN = 0xFFFF3B3B;

   private static long builtAt;
   private static Model model = Model.EMPTY;
   private static final Int2DoubleOpenHashMap LAST_DIST = new Int2DoubleOpenHashMap();
   /** Row hit boxes from the last frame: {x1, y1, x2, y2, worldX, worldZ}. */
   private static final List<double[]> HITS = new ArrayList<>();

   private SituationPanel() {
   }

   private record Row(int id, String kind, String name, double dist, double bearing, int alt, int trend, double x, double z, int color) {
   }

   private record Model(String vehicle, int alt, int agl, int speedKmh, int heading, int healthPct, int threats, double threatDist,
                        double threatBearing, int hostile, int friendly, int neutral, int missiles, List<Row> rows) {
      static final Model EMPTY = new Model("", 0, -1, 0, 0, -1, 0, 0.0, 0.0, 0, 0, 0, 0, List.of());
   }

   /** Width available left of SBW's map panel (which takes 55 % of the screen, 8 px from the right edge). */
   private static int panelWidth(int screenWidth) {
      int mapPanelX = screenWidth - (int) (screenWidth * 0.55) - 8;
      return Math.min(PANEL_MAX_W, mapPanelX - 16);
   }

   public static void render(GuiGraphics graphics, int screenWidth, int screenHeight, int mouseX, int mouseY) {
      HITS.clear();
      int w = panelWidth(screenWidth);
      Minecraft mc = Minecraft.getInstance();
      if (w < PANEL_MIN_W || mc.player == null || mc.level == null) {
         return;
      }

      long now = System.currentTimeMillis();
      if (now - builtAt >= REFRESH_MS || now < builtAt) {
         builtAt = now;
         model = build(mc.level, mc.player);
      }

      Model m = model;
      MapDraw draw = MapDraw.get();
      int x0 = 8;
      int y0 = 8;
      int x1 = x0 + w;
      int y1 = y0 + (int) (screenHeight * 0.85);
      draw.rect(x0, y0, x1, y1, BG);
      draw.rect(x0, y0, x1, y0 + 16, HEADER);
      outline(draw, x0, y0, x1, y1, BORDER);
      draw.text("◆ 战术态势  TACTICAL", x0 + 6, y0 + 4, ACCENT, 1.0F, false);

      int tx = x0 + 6;
      int y = y0 + 22;
      draw.text("载具  " + (m.vehicle().isEmpty() ? "— 步行" : m.vehicle()), tx, y, TEXT, 1.0F, false);
      y += LINE_H;
      draw.text("高度  " + m.alt() + "m" + (m.agl() >= 0 ? "   离地 " + m.agl() + "m" : ""), tx, y, TEXT, 1.0F, false);
      y += LINE_H;
      draw.text(String.format(Locale.ROOT, "速度  %dkm/h   航向 %03d°", m.speedKmh(), m.heading()), tx, y, TEXT, 1.0F, false);
      y += LINE_H;
      if (m.healthPct() >= 0) {
         int barW = w - 48;
         draw.text("结构", tx, y, TEXT, 1.0F, false);
         int bx = tx + 30;
         draw.rect(bx, y + 2, bx + barW, y + 7, 0xFF26302B);
         int hpColor = m.healthPct() > 60 ? ACCENT : (m.healthPct() > 30 ? AIR : WARN);
         draw.rect(bx, y + 2, bx + barW * m.healthPct() / 100.0F, y + 7, hpColor);
         y += LINE_H;
      }

      y += 4;
      if (m.threats() > 0) {
         boolean blink = now / 250L % 2L == 0L;
         draw.rect(x0 + 3, y - 2, x1 - 3, y + 2 * LINE_H + 1, blink ? 0xC0801010 : 0x90401010);
         draw.text("⚠ 导弹告警  ×" + m.threats(), tx, y, blink ? 0xFFFFE0E0 : WARN, 1.0F, true);
         y += LINE_H;
         draw.text(String.format(Locale.ROOT, "最近 %s   方位 %03d°", ContactRenderer.formatDistance(m.threatDist()), (int) m.threatBearing()),
            tx, y, 0xFFFFC0C0, 1.0F, false);
         y += LINE_H + 4;
      } else {
         draw.text("RWR  无导弹锁定", tx, y, DIM, 1.0F, false);
         y += LINE_H + 4;
      }

      draw.rect(x0 + 3, y - 2, x1 - 3, y - 1, BORDER);
      draw.text("敌 " + m.hostile() + "   友 " + m.friendly() + "   中立 " + m.neutral() + "   导弹 " + m.missiles(), tx, y + 2, TEXT, 1.0F, false);
      y += LINE_H + 6;
      draw.text("敌方接触（由近到远）", tx, y, ACCENT, 1.0F, false);
      y += LINE_H + 2;

      int footer = y1 - 14;
      if (m.rows().isEmpty()) {
         draw.text("雷达/数据链无敌方接触", tx, y, DIM, 1.0F, false);
      }

      for (Row row : m.rows()) {
         if (y + ROW_H > footer) {
            break;
         }

         boolean hover = mouseX >= x0 && mouseX < x1 && mouseY >= y - 2 && mouseY < y - 2 + ROW_H;
         if (hover) {
            draw.rect(x0 + 2, y - 2, x1 - 2, y - 2 + ROW_H, 0x5030A060);
         }

         draw.rect(x0 + 3, y, x0 + 5, y + ROW_H - 5, row.color());
         draw.text("[" + row.kind() + "] " + clip(row.name(), w - 60), tx + 2, y, row.color(), 1.0F, false);
         String trend = row.trend() > 0 ? "  ▲接近" : (row.trend() < 0 ? "  ▼远离" : "");
         String detail = String.format(Locale.ROOT, "%s  %03d°  ↑%dm%s", ContactRenderer.formatDistance(row.dist()), (int) row.bearing(), row.alt(), trend);
         draw.text(detail, tx + 2, y + 10, row.trend() > 0 ? 0xFFFFD0A0 : DIM, 0.75F, false);
         HITS.add(new double[]{x0, y - 2, x1, y - 2 + ROW_H, row.x(), row.z()});
         y += ROW_H;
      }

      draw.text("点击接触：地图定位", tx, footer + 2, DIM, 0.75F, false);
      draw.flush(graphics);
   }

   /** World (x, z) of the clicked contact row, or null. */
   public static double[] click(double mouseX, double mouseY) {
      for (double[] hit : HITS) {
         if (mouseX >= hit[0] && mouseX < hit[2] && mouseY >= hit[1] && mouseY < hit[3]) {
            return new double[]{hit[4], hit[5]};
         }
      }

      return null;
   }

   private static String clip(String text, int maxPx) {
      var font = Minecraft.getInstance().font;
      if (font.width(text) <= maxPx) {
         return text;
      }

      return font.plainSubstrByWidth(text, Math.max(0, maxPx - font.width("…"))) + "…";
   }

   private static void outline(MapDraw draw, float x0, float y0, float x1, float y1, int argb) {
      draw.rect(x0, y0, x1, y0 + 1, argb);
      draw.rect(x0, y1 - 1, x1, y1, argb);
      draw.rect(x0, y0, x0 + 1, y1, argb);
      draw.rect(x1 - 1, y0, x1, y1, argb);
   }

   // ----------------------------------------------------------------------------------------------------------------
   // Model
   // ----------------------------------------------------------------------------------------------------------------

   private static double bearing(double dx, double dz) {
      double deg = Math.toDegrees(Math.atan2(dx, -dz));
      return deg < 0.0 ? deg + 360.0 : deg;
   }

   private static Model build(ClientLevel level, LocalPlayer player) {
      Entity body = player.getRootVehicle();
      Vec3 own = body.position();
      String vehicle = body == player ? "" : body.getDisplayName().getString();
      int alt = Mth.floor(own.y);
      int agl = -1;
      if (level.hasChunk(Mth.floor(own.x) >> 4, Mth.floor(own.z) >> 4)) {
         agl = Math.max(0, alt - level.getHeight(Heightmap.Types.WORLD_SURFACE, Mth.floor(own.x), Mth.floor(own.z)));
      }

      Vec3 v = ContactRenderer.velocityOf(level, body);
      int speed = (int) Math.round(v.length() * 20.0 * 3.6);
      int heading = Math.floorMod(Math.round(body.getYRot() + 180.0F), 360);
      int health = -1;
      if (body instanceof VehicleEntity ve && ve.getMaxHealth() > 0.0F) {
         health = Mth.clamp(Math.round(ve.getHealth() / ve.getMaxHealth() * 100.0F), 0, 100);
      }

      // Missiles locked on us: real client entities plus BVR phantoms beyond render distance.
      String myId = player.getStringUUID();
      String bodyId = body.getStringUUID();
      IntOpenHashSet seen = new IntOpenHashSet();
      int threats = 0;
      double threatDist = Double.MAX_VALUE;
      double threatBearing = 0.0;
      List<Entity> missiles = new ArrayList<>();
      for (Entity e : level.entitiesForRendering()) {
         if (e instanceof MissileProjectile) {
            missiles.add(e);
         }
      }

      missiles.addAll(ClientSyncedEntityHandler.getSyncedWorldRenderEntities(level));
      for (Entity e : missiles) {
         if (!(e instanceof MissileProjectile m) || !seen.add(e.getId())) {
            continue;
         }

         String lock = m.getTargetUUID();
         if (lock == null || !(lock.equals(myId) || lock.equals(bodyId))) {
            continue;
         }

         Vec3 p = ClientSyncedEntityHandler.getExtrapolatedPos(level, e);
         double d = p.distanceTo(own);
         threats++;
         if (d < threatDist) {
            threatDist = d;
            threatBearing = bearing(p.x - own.x, p.z - own.z);
         }
      }

      // Force picture from the server-side relation sync (the same lists the map draws).
      List<Row> rows = new ArrayList<>();
      int hostileMissiles = 0;
      int hostileCount = 0;
      for (Entity e : ClientSyncedEntityHandler.getSyncedHostileEntities(level)) {
         if (e.getVehicle() != null) {
            continue;
         }

         hostileCount++;
         boolean missile = e instanceof MissileProjectile;
         if (missile) {
            hostileMissiles++;
         }

         Vec3 p = ClientSyncedEntityHandler.getExtrapolatedPos(level, e);
         rows.add(row(e.getId(), kindOf(e), e.getDisplayName().getString(), own, p, missile ? WARN : (Sbw.isAircraft(e) ? AIR : HOSTILE)));
      }

      for (ClientSyncedEntityHandler.ClientSyncedPlayer info : ClientSyncedEntityHandler.getSyncedPlayerInfo(level)) {
         if ("hostile".equals(info.getRelation()) && !info.getOnVehicle() && !info.getUuid().equals(player.getUUID())) {
            hostileCount++;
            rows.add(row(info.getEntityId(), "步兵", info.getName(), own, info.getPos(), HOSTILE));
         }
      }

      rows.sort(Comparator.comparingDouble(Row::dist));
      if (rows.size() > MAX_ROWS) {
         rows = new ArrayList<>(rows.subList(0, MAX_ROWS));
      }

      Int2DoubleOpenHashMap next = new Int2DoubleOpenHashMap();
      for (Row r : rows) {
         next.put(r.id(), r.dist());
      }

      LAST_DIST.clear();
      LAST_DIST.putAll(next);
      return new Model(vehicle, alt, agl, speed, heading, health, threats, threatDist, threatBearing, hostileCount,
         countLoose(ClientSyncedEntityHandler.getSyncedFriendlyEntities(level)), countLoose(ClientSyncedEntityHandler.getSyncedNeutralEntities(level)),
         hostileMissiles, List.copyOf(rows));
   }

   private static Row row(int id, String kind, String name, Vec3 own, Vec3 p, int color) {
      double dist = Math.sqrt(Mth.square(p.x - own.x) + Mth.square(p.z - own.z));
      int trend = 0;
      if (LAST_DIST.containsKey(id)) {
         double prev = LAST_DIST.get(id);
         trend = dist < prev - 0.5 ? 1 : (dist > prev + 0.5 ? -1 : 0);
      }

      return new Row(id, kind, name, dist, bearing(p.x - own.x, p.z - own.z), Mth.floor(p.y), trend, p.x, p.z, color);
   }

   private static int countLoose(List<Entity> entities) {
      int n = 0;
      for (Entity e : entities) {
         if (e.getVehicle() == null) {
            n++;
         }
      }

      return n;
   }

   private static String kindOf(Entity e) {
      if (e instanceof MissileProjectile) {
         return "导弹";
      }

      if (e instanceof Player) {
         return "步兵";
      }

      return switch (Sbw.vehicleType(e)) {
         case "AIRPLANE" -> "固定翼";
         case "HELICOPTER" -> "直升机";
         case "DRONE" -> "无人机";
         case "AIRSHIP" -> "飞艇";
         case "TANK" -> "坦克";
         case "APC" -> "装甲车";
         case "AA" -> "防空";
         case "ARTILLERY" -> "火炮";
         case "CAR" -> "车辆";
         case "BOAT" -> "舰艇";
         case "DEFENSE" -> "阵地";
         default -> "目标";
      };
   }
}
