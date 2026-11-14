package com.f16addon.client.map;

import static com.f16addon.client.map.TerminalStyle.*;

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
 * Left-hand "situation" panel of the tactical terminal: a PPI radar scope (north-up, sweep with phosphor fade, click a
 * blip to center the map on it, click the scope to change range), own-ship data, missile warning, force summary and
 * the nearest hostile contacts (click a row to center the map). SBW's screen leaves that side of the screen empty.
 *
 * <p>The picture is rebuilt at most every {@link #REFRESH_MS} ms; drawing is one batched flush.
 */
public final class SituationPanel {
   private static final long REFRESH_MS = 150L;
   private static final long SWEEP_MS = 3000L;
   private static final int MAX_ROWS = 12;
   private static final int MAX_BLIPS = 160;
   private static final int ROW_H = 22;
   private static final int LINE_H = 11;
   private static final int PANEL_MAX_W = 232;
   private static final int PANEL_MIN_W = 150;
   private static final int[] SCOPE_RANGES = {250, 500, 1000, 2000, 4000, 8000, 16000};
   private static final int AUTO_DEFAULT_RANGE = 2000;

   private static final int K_HOSTILE = 0;
   private static final int K_HOSTILE_AIR = 1;
   private static final int K_MISSILE = 2;
   private static final int K_FRIENDLY = 3;
   private static final int K_NEUTRAL = 4;
   private static final int K_INCOMING = 5;

   private static long builtAt;
   private static Model model = Model.EMPTY;
   /** Index into {@link #SCOPE_RANGES}, or -1 for automatic range. */
   private static int scopeRange = -1;
   private static final Int2DoubleOpenHashMap LAST_DIST = new Int2DoubleOpenHashMap();
   /** Hit boxes from the last frame: {x1, y1, x2, y2, worldX, worldZ}; NaN world coordinates = scope (cycle range). */
   private static final List<double[]> HITS = new ArrayList<>();

   private SituationPanel() {
   }

   private record Row(int id, String kind, String name, double dist, double bearing, int alt, int trend, double x, double z, int color) {
   }

   private record Blip(int kind, String name, double x, double z) {
   }

   private record Model(String vehicle, double ownX, double ownZ, int alt, int agl, int speedKmh, int heading, int healthPct, int threats,
                        double threatDist, double threatBearing, int hostile, int friendly, int neutral, int missiles, List<Row> rows,
                        List<Blip> blips) {
      static final Model EMPTY = new Model("", 0.0, 0.0, 0, -1, 0, 0, -1, 0, 0.0, 0.0, 0, 0, 0, 0, List.of(), List.of());
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
      MapDraw d = MapDraw.get();
      int x0 = 8;
      int y0 = 8;
      int x1 = x0 + w;
      int y1 = y0 + (int) (screenHeight * 0.85);
      int h = y1 - y0;
      boolean blink = now / 250L % 2L == 0L;

      // Frame + header.
      d.rectV(x0, y0, x1, y1, BG_TOP, BG_BOTTOM);
      d.frame(x0, y0, x1, y1, 1.0F, FRAME_DIM);
      d.corners(x0, y0, x1, y1, 8.0F, 1.0F, m.threats() > 0 ? WARN : ACCENT);
      d.rectH(x0 + 1, y0 + 1, x1 - 1, y0 + 16, 0xFF153626, 0x00153626);
      d.rect(x0 + 1, y0 + 16, x1 - 1, y0 + 17, FRAME);
      d.text("◆ 战术态势", x0 + 6, y0 + 4, ACCENT, 1.0F, false);
      String link = "LINK";
      float lw = MapDraw.width(link, 0.75F);
      d.text(link, x1 - 12 - lw, y0 + 6, DIM, 0.75F, false);
      d.disc(x1 - 7, y0 + 8.5F, 2.0F, now / 600L % 2L == 0L ? ACCENT : FRAME);

      int tx = x0 + 6;
      int inner = w - 12;
      int y = y0 + 22;

      // Radar scope + own-ship data. The scope only takes the height the rest of the panel can spare (the force
      // summary and at least one contact row always fit), and the own-ship cards sit in a column beside it. The
      // missile-warning box is always reserved so the layout does not jump when a warning appears.
      int below = 2 * LINE_H + 3 + 2 * LINE_H + 5 + 18 + LINE_H + 3 + ROW_H + 16;
      int scope = Math.min(Math.min(y1 - y - 6 - below, inner - 86), (int) (h * 0.42F));
      if (scope >= 64) {
         scope(d, tx, y, scope, m, now, mouseX, mouseY);
         int cx = tx + scope + 6;
         ownCards(d, m, cx, y, x1 - 6 - cx, (scope - 6) / 4, 1);
         y += scope + 6;
      } else {
         ownCards(d, m, tx, y, inner, 22, 2);
         y += 2 * 24 + 4;
      }

      // Vehicle + structure.
      d.text("载具", tx, y, DIM, 1.0F, false);
      d.text(TerminalChrome.clip(m.vehicle().isEmpty() ? "— 步行" : m.vehicle(), inner - 30), tx + 26, y, TEXT, 1.0F, false);
      y += LINE_H;
      if (m.healthPct() >= 0) {
         d.text("结构", tx, y, DIM, 1.0F, false);
         String pct = m.healthPct() + "%";
         float pw = MapDraw.width(pct, 1.0F);
         int hpColor = m.healthPct() > 60 ? ACCENT : (m.healthPct() > 30 ? HOSTILE_AIR : WARN);
         float bx = tx + 26;
         float bw = inner - 26 - pw - 4;
         int segments = 10;
         float seg = bw / segments;
         int lit = Math.round(m.healthPct() / 10.0F);
         for (int i = 0; i < segments; i++) {
            d.rect(bx + i * seg, y + 1, bx + (i + 1) * seg - 1, y + 7, i < lit ? hpColor : 0xFF1A2420);
         }

         d.text(pct, tx + inner - pw, y, hpColor, 1.0F, false);
         y += LINE_H;
      }

      y += 3;
      // Missile warning.
      if (m.threats() > 0) {
         int boxH = 2 * LINE_H + 4;
         d.rect(x0 + 3, y - 2, x1 - 3, y - 2 + boxH, blink ? 0xC0801010 : 0x90401010);
         d.frame(x0 + 3, y - 2, x1 - 3, y - 2 + boxH, 1.0F, blink ? WARN : 0xFF802020);
         d.text("⚠ 导弹告警  ×" + m.threats(), tx, y, blink ? 0xFFFFE0E0 : WARN, 1.0F, true);
         y += LINE_H;
         d.text(String.format(Locale.ROOT, "最近 %s   方位 %03d°", ContactRenderer.formatDistance(m.threatDist()), (int) m.threatBearing()),
            tx, y, 0xFFFFC0C0, 1.0F, false);
         y += LINE_H + 5;
      } else {
         d.text("RWR", tx, y, DIM, 1.0F, false);
         d.disc(tx + 27, y + 3.5F, 2.0F, ACCENT);
         d.text("无导弹锁定", tx + 33, y, MapDraw.withAlpha(ACCENT_RGB, 0.8F), 1.0F, false);
         y += LINE_H + 3;
      }

      // Force summary chips.
      int chipW = (inner - 6) / 4;
      chip(d, tx, y, chipW, "敌", m.hostile(), HOSTILE);
      chip(d, tx + (chipW + 2), y, chipW, "友", m.friendly(), FRIENDLY);
      chip(d, tx + 2 * (chipW + 2), y, chipW, "中", m.neutral(), NEUTRAL);
      chip(d, tx + 3 * (chipW + 2), y, chipW, "弹", m.missiles(), m.missiles() > 0 && blink ? WARN : HOSTILE_AIR);
      y += 18;

      // Contact list.
      d.text("敌方接触", tx, y, ACCENT, 1.0F, false);
      String sorted = "由近到远";
      d.text(sorted, x1 - 6 - MapDraw.width(sorted, 1.0F), y, FAINT, 1.0F, false);
      y += LINE_H;
      d.rectH(tx, y, x1 - 6, y + 1, FRAME, 0x002C5B3C);
      y += 3;

      int footer = y1 - 13;
      if (m.rows().isEmpty()) {
         d.text("雷达/数据链无敌方接触", tx, y + 2, FAINT, 1.0F, false);
      }

      int index = 0;
      for (Row row : m.rows()) {
         if (y + ROW_H > footer) {
            break;
         }

         int top = y;
         int bottom = y + ROW_H - 1;
         boolean hover = mouseX >= x0 && mouseX < x1 && mouseY >= top && mouseY < bottom;
         if (hover) {
            d.rect(x0 + 3, top, x1 - 3, bottom, CARD_HOVER);
            d.frame(x0 + 3, top, x1 - 3, bottom, 1.0F, ACCENT);
         } else if (index % 2 == 0) {
            d.rect(x0 + 3, top, x1 - 3, bottom, 0x40101A16);
         }

         d.rect(x0 + 3, top, x0 + 5, bottom, row.color());
         float kw = MapDraw.width(row.kind(), 1.0F);
         d.rect(tx + 1, top + 1, tx + kw + 5, top + 11, MapDraw.withAlpha(row.color(), 0.22F));
         d.text(row.kind(), tx + 3, top + 2, row.color(), 1.0F, false);
         String dist = ContactRenderer.formatDistance(row.dist());
         float dw = MapDraw.width(dist, 1.0F);
         d.text(dist, x1 - 7 - dw, top + 2, TEXT, 1.0F, false);
         d.text(TerminalChrome.clip(row.name(), x1 - 7 - dw - 6 - (tx + kw + 8)), tx + kw + 8, top + 2, TEXT, 1.0F, false);
         d.text(String.format(Locale.ROOT, "%03d°   ↑%dm", (int) row.bearing(), row.alt()), tx + 3, top + 14, DIM, 0.75F, false);
         if (row.trend() != 0) {
            String trend = row.trend() > 0 ? "▲接近" : "▼远离";
            d.text(trend, x1 - 7 - MapDraw.width(trend, 1.0F), top + 12, row.trend() > 0 ? 0xFFFFC080 : FAINT, 1.0F, false);
         }

         HITS.add(new double[]{x0, top, x1, bottom, row.x(), row.z()});
         y += ROW_H;
         index++;
      }

      d.rect(x0 + 3, footer - 2, x1 - 3, footer - 1, FRAME_DIM);
      d.text(TerminalChrome.clip("点选定位 · 点雷达切量程 · Shift测距", inner), tx, footer + 2, FAINT, 1.0F, false);
      d.flush(graphics);
   }

   // ----------------------------------------------------------------------------------------------------------------
   // Radar scope (PPI, north-up, centered on own ship)
   // ----------------------------------------------------------------------------------------------------------------

   private static void scope(MapDraw d, int sx, int sy, int size, Model m, long now, int mouseX, int mouseY) {
      float cx = sx + size / 2.0F;
      float cy = sy + size / 2.0F;
      float r = size / 2.0F - 1.0F;
      boolean auto = scopeRange < 0 || scopeRange >= SCOPE_RANGES.length;
      int range = auto ? autoRange(m) : SCOPE_RANGES[scopeRange];

      d.disc(cx, cy, r + 1, 0xF0020604);
      d.sector(cx, cy, r, 0.0F, Mth.TWO_PI, 0x40124A28, 0x08061A0E);
      for (int k = 1; k <= 3; k++) {
         d.ring(cx, cy, r * k / 4.0F, 1.0F, MapDraw.withAlpha(ACCENT_RGB, 0.16F));
      }

      d.ring(cx, cy, r, 1.0F, FRAME);
      d.rect(cx - r, cy, cx + r, cy + 1, MapDraw.withAlpha(ACCENT_RGB, 0.12F));
      d.rect(cx, cy - r, cx + 1, cy + r, MapDraw.withAlpha(ACCENT_RGB, 0.12F));
      for (int i = 0; i < 36; i++) {
         float a = i * 10.0F * Mth.DEG_TO_RAD;
         float len = i % 9 == 0 ? 5.0F : (i % 3 == 0 ? 3.0F : 1.5F);
         float dx = Mth.sin(a);
         float dy = -Mth.cos(a);
         d.line(cx + dx * (r - len), cy + dy * (r - len), cx + dx * r, cy + dy * r, 0.8F, MapDraw.withAlpha(ACCENT_RGB, i % 3 == 0 ? 0.6F : 0.3F));
      }

      d.text("N", cx - 2, cy - r + 6, MapDraw.withAlpha(ACCENT_RGB, 0.8F), 0.75F, false);

      // Sweep with fading trail (screen angle: 0 = east, clockwise because y points down).
      float beam = (now % SWEEP_MS) / (float) SWEEP_MS * Mth.TWO_PI - Mth.HALF_PI;
      int slices = 14;
      float trail = 70.0F * Mth.DEG_TO_RAD;
      for (int s = 0; s < slices; s++) {
         float a0 = beam - trail * s / slices;
         float a1 = beam - trail * (s + 1) / slices;
         float alpha = 0.32F * (1.0F - (float) s / slices);
         d.triangle(cx, cy, MapDraw.withAlpha(ACCENT_RGB, alpha), cx + Mth.cos(a0) * r, cy + Mth.sin(a0) * r, MapDraw.withAlpha(ACCENT_RGB, alpha),
            cx + Mth.cos(a1) * r, cy + Mth.sin(a1) * r, MapDraw.withAlpha(ACCENT_RGB, alpha * 0.85F));
      }

      d.line(cx, cy, cx + Mth.cos(beam) * r, cy + Mth.sin(beam) * r, 1.5F, MapDraw.withAlpha(0xB0FFC8, 0.95F));

      // Incoming-missile bearing.
      if (m.threats() > 0 && now / 250L % 2L == 0L) {
         float tb = (float) m.threatBearing() * Mth.DEG_TO_RAD;
         d.line(cx, cy, cx + Mth.sin(tb) * r, cy - Mth.cos(tb) * r, 1.5F, WARN);
      }

      // Blips.
      float scale = r / range;
      Blip hovered = null;
      float hoverX = 0.0F;
      float hoverY = 0.0F;
      boolean blink = now / 200L % 2L == 0L;
      for (Blip b : m.blips()) {
         float px = (float) (b.x() - m.ownX()) * scale;
         float pz = (float) (b.z() - m.ownZ()) * scale;
         float dist = Mth.sqrt(px * px + pz * pz);
         float ang = (float) Mth.atan2(pz, px);
         float since = beam - ang;
         since = since - Mth.TWO_PI * (float) Math.floor(since / Mth.TWO_PI);
         float fade = 1.0F - 0.7F * since / Mth.TWO_PI;
         int color = blipColor(b.kind());
         if (dist > r - 2.0F) {
            // Out of range: hostile / missile contacts leave a tick on the rim.
            if (b.kind() == K_HOSTILE || b.kind() == K_HOSTILE_AIR || b.kind() == K_MISSILE || b.kind() == K_INCOMING) {
               float ux = px / Math.max(dist, 1.0E-3F);
               float uz = pz / Math.max(dist, 1.0E-3F);
               d.line(cx + ux * (r - 4), cy + uz * (r - 4), cx + ux * (r + 1), cy + uz * (r + 1), 2.0F, MapDraw.withAlpha(color, 0.75F * fade));
            }

            continue;
         }

         float bx = cx + px;
         float by = cy + pz;
         int c = MapDraw.withAlpha(color, fade);
         switch (b.kind()) {
            case K_HOSTILE -> d.diamond(bx, by, 2.5F, c);
            case K_HOSTILE_AIR -> d.triangle(bx, by - 3.0F, bx - 3.0F, by + 2.5F, bx + 3.0F, by + 2.5F, c);
            case K_MISSILE -> d.disc(bx, by, 1.5F, blink ? c : MapDraw.withAlpha(color, fade * 0.4F));
            case K_INCOMING -> {
               d.disc(bx, by, 2.0F, blink ? WARN : 0xFFFFFFFF);
               d.ring(bx, by, 4.5F, 1.0F, WARN);
            }
            case K_FRIENDLY -> d.disc(bx, by, 2.0F, c);
            default -> d.rect(bx - 1.5F, by - 1.5F, bx + 1.5F, by + 1.5F, c);
         }

         if (Math.abs(mouseX - bx) <= 4.0F && Math.abs(mouseY - by) <= 4.0F) {
            hovered = b;
            hoverX = bx;
            hoverY = by;
         }

         HITS.add(new double[]{bx - 4, by - 4, bx + 4, by + 4, b.x(), b.z()});
      }

      // Own ship.
      float hdg = m.heading() * Mth.DEG_TO_RAD;
      float hx = Mth.sin(hdg);
      float hy = -Mth.cos(hdg);
      d.triangle(cx + hx * 5.0F, cy + hy * 5.0F, cx - hx * 3.0F - hy * 3.0F, cy - hy * 3.0F + hx * 3.0F, cx - hx * 3.0F + hy * 3.0F,
         cy - hy * 3.0F - hx * 3.0F, 0xFFFFFFFF);

      // Range readout (bottom-right corner of the scope square) and ring value.
      String rangeText = (auto ? "A " : "") + ContactRenderer.formatDistance(range);
      float rw = MapDraw.width(rangeText, 0.75F);
      d.rect(sx + size - rw - 4, sy + size - 8, sx + size, sy + size, 0xC0050A08);
      d.text(rangeText, sx + size - rw - 2, sy + size - 7, ACCENT, 0.75F, false);
      String half = ContactRenderer.formatDistance(range / 2.0);
      d.text(half, cx + 2, cy - r / 2.0F - 7, MapDraw.withAlpha(ACCENT_RGB, 0.45F), 0.75F, false);

      boolean overScope = Mth.square(mouseX - cx) + Mth.square(mouseY - cy) <= r * r;
      if (overScope && hovered == null) {
         d.ring(cx, cy, r + 1.5F, 1.0F, MapDraw.withAlpha(ACCENT_RGB, 0.7F));
      }

      if (hovered != null) {
         String label = hovered.name() + "  "
            + ContactRenderer.formatDistance(Math.sqrt(Mth.square(hovered.x() - m.ownX()) + Mth.square(hovered.z() - m.ownZ())));
         label = TerminalChrome.clip(label, size - 6);
         float tw = MapDraw.width(label, 1.0F);
         float lx = Mth.clamp(hoverX + 6, sx, sx + size - tw - 4);
         float ly = hoverY - 14 < sy ? hoverY + 6 : hoverY - 14;
         d.ring(hoverX, hoverY, 5.0F, 1.0F, 0xFFFFFFFF);
         d.rect(lx, ly, lx + tw + 4, ly + 11, 0xE8050A08);
         d.text(label, lx + 2, ly + 1, 0xFFFFFFFF, 1.0F, false);
      }

      HITS.add(new double[]{cx - r, cy - r, cx + r, cy + r, Double.NaN, Double.NaN});
   }

   /** Smallest range that keeps the nearest few hostile contacts (rows are sorted by distance) well inside the rim. */
   private static int autoRange(Model m) {
      List<Row> rows = m.rows();
      if (rows.isEmpty()) {
         return AUTO_DEFAULT_RANGE;
      }

      double need = rows.get(Math.min(2, rows.size() - 1)).dist() * 1.3;
      for (int r : SCOPE_RANGES) {
         if (r >= need) {
            return r;
         }
      }

      return SCOPE_RANGES[SCOPE_RANGES.length - 1];
   }

   private static int blipColor(int kind) {
      return switch (kind) {
         case K_HOSTILE -> HOSTILE;
         case K_HOSTILE_AIR -> HOSTILE_AIR;
         case K_MISSILE, K_INCOMING -> WARN;
         case K_FRIENDLY -> FRIENDLY;
         default -> NEUTRAL;
      };
   }

   /** Speed / altitude / heading / AGL cards, either one column of four or two columns of two. */
   private static void ownCards(MapDraw d, Model m, int x, int y, int w, int cardH, int columns) {
      boolean lowAlt = m.agl() >= 0 && m.agl() < 20 && m.speedKmh() > 100;
      String[][] cards = {
         {"速度", m.speedKmh() + " km/h"},
         {"高度", m.alt() + " m"},
         {"航向", String.format(Locale.ROOT, "%03d°", m.heading())},
         {"离地", m.agl() >= 0 ? m.agl() + " m" : "---"}};
      int cw = columns == 1 ? w : (w - 4) / 2;
      int gap = 2;
      for (int i = 0; i < 4; i++) {
         int col = columns == 1 ? 0 : i % 2;
         int row = columns == 1 ? i : i / 2;
         float cx = x + col * (cw + 4);
         float cy = y + row * (cardH + gap);
         boolean warn = i == 3 && lowAlt;
         int valueColor = warn ? WARN : TEXT;
         d.rect(cx, cy, cx + cw, cy + cardH, CARD);
         d.frame(cx, cy, cx + cw, cy + cardH, 1.0F, warn ? WARN : FRAME_DIM);
         d.rect(cx, cy, cx + 2, cy + cardH, warn ? WARN : ACCENT);
         if (cardH >= 22) {
            // Label on top, value at the bottom.
            d.text(cards[i][0], cx + 5, cy + 2, DIM, 1.0F, false);
            d.text(TerminalChrome.clip(cards[i][1], cw - 8), cx + 5, cy + cardH - 10, valueColor, 1.0F, false);
         } else {
            // Compact: label left, value right on one line.
            float ty = cy + (cardH - 8) / 2.0F;
            d.text(cards[i][0], cx + 5, ty, DIM, 1.0F, false);
            String v = TerminalChrome.clip(cards[i][1], cw - 30);
            d.text(v, cx + cw - 3 - MapDraw.width(v, 1.0F), ty, valueColor, 1.0F, false);
         }
      }
   }

   private static void chip(MapDraw d, float x, float y, float w, String label, int count, int color) {
      d.rect(x, y, x + w, y + 14, CARD);
      d.frame(x, y, x + w, y + 14, 1.0F, FRAME_DIM);
      d.rect(x, y, x + 2, y + 14, color);
      d.text(label, x + 5, y + 3, color, 1.0F, false);
      String n = Integer.toString(count);
      d.text(n, x + w - 3 - MapDraw.width(n, 1.0F), y + 3, count > 0 ? TEXT : FAINT, 1.0F, false);
   }

   /** World (x, z) of the clicked contact row / scope blip, an empty array if the click was consumed otherwise, or null. */
   public static double[] click(double mouseX, double mouseY) {
      for (double[] hit : HITS) {
         if (mouseX >= hit[0] && mouseX < hit[2] && mouseY >= hit[1] && mouseY < hit[3]) {
            if (Double.isNaN(hit[4])) {
               // AUTO → 250 m → … → 16 km → AUTO
               scopeRange = scopeRange + 1 >= SCOPE_RANGES.length ? -1 : scopeRange + 1;
               return new double[0];
            }

            return new double[]{hit[4], hit[5]};
         }
      }

      return null;
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
      IntOpenHashSet incomingIds = new IntOpenHashSet();
      List<Blip> blips = new ArrayList<>();
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
         incomingIds.add(e.getId());
         blips.add(new Blip(K_INCOMING, e.getDisplayName().getString(), p.x, p.z));
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
         boolean air = !missile && Sbw.isAircraft(e);
         if (missile) {
            hostileMissiles++;
         }

         Vec3 p = ClientSyncedEntityHandler.getExtrapolatedPos(level, e);
         String name = e.getDisplayName().getString();
         rows.add(row(e.getId(), kindOf(e), name, own, p, missile ? WARN : (air ? HOSTILE_AIR : HOSTILE)));
         if (!incomingIds.contains(e.getId())) {
            blip(blips, missile ? K_MISSILE : (air ? K_HOSTILE_AIR : K_HOSTILE), name, p);
         }
      }

      int friendlyCount = 0;
      for (Entity e : ClientSyncedEntityHandler.getSyncedFriendlyEntities(level)) {
         if (e.getVehicle() != null) {
            continue;
         }

         friendlyCount++;
         if (e.getId() != body.getId() && e.getId() != player.getId()) {
            blip(blips, K_FRIENDLY, e.getDisplayName().getString(), ClientSyncedEntityHandler.getExtrapolatedPos(level, e));
         }
      }

      int neutralCount = 0;
      for (Entity e : ClientSyncedEntityHandler.getSyncedNeutralEntities(level)) {
         if (e.getVehicle() != null) {
            continue;
         }

         neutralCount++;
         blip(blips, K_NEUTRAL, e.getDisplayName().getString(), ClientSyncedEntityHandler.getExtrapolatedPos(level, e));
      }

      for (ClientSyncedEntityHandler.ClientSyncedPlayer info : ClientSyncedEntityHandler.getSyncedPlayerInfo(level)) {
         if (info.getOnVehicle() || info.getUuid().equals(player.getUUID())) {
            continue;
         }

         if ("hostile".equals(info.getRelation())) {
            hostileCount++;
            rows.add(row(info.getEntityId(), "步兵", info.getName(), own, info.getPos(), HOSTILE));
            blip(blips, K_HOSTILE, info.getName(), info.getPos());
         } else if ("friendly".equals(info.getRelation())) {
            blip(blips, K_FRIENDLY, info.getName(), info.getPos());
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
      return new Model(vehicle, own.x, own.z, alt, agl, speed, heading, health, threats, threatDist, threatBearing, hostileCount,
         friendlyCount, neutralCount, hostileMissiles, List.copyOf(rows), List.copyOf(blips));
   }

   private static void blip(List<Blip> blips, int kind, String name, Vec3 p) {
      if (blips.size() < MAX_BLIPS) {
         blips.add(new Blip(kind, name, p.x, p.z));
      }
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
