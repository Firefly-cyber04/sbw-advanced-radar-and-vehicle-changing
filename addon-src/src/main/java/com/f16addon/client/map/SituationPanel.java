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
import java.util.Map;
import net.minecraft.world.level.Level;

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
       int w = SituationPanel.panelWidth(screenWidth);
       Minecraft mc = Minecraft.getInstance();
       if (w < 150 || mc.player == null || mc.level == null) {
           return;
       }
       long now = System.currentTimeMillis();
       if (model == null || modelWorld != mc.level || now - builtAt >= 150L || now < builtAt) {
           if (modelWorld != mc.level)
               LAST_DIST.clear();
           modelWorld = mc.level;
           builtAt = now;
           model = SituationPanel.build(mc.level, mc.player);
       }
       Model m = model;
       MapDraw d = MapDraw.get();
       int x0 = 8;
       int y0 = 8;
       int x1 = x0 + w;
       int y1 = y0 + (int) ((double) screenHeight * 0.85);
       int h = y1 - y0;
       boolean blink = now / 250L % 2L == 0L;
       d.rectV(x0, y0, x1, y1, -166651112, -167310325);
       d.frame(x0, y0, x1, y1, 1.0f, -14928602);
       d.corners(x0, y0, x1, y1, 8.0f, 1.0f, m.threats() > 0 ? -50373 : -9633894);
       d.rectH(x0 + 1, y0 + 1, x1 - 1, y0 + 16, -15387098, 1390118);
       d.rect(x0 + 1, y0 + 16, x1 - 1, y0 + 17, -13870276);
       d.text("\u25c6 \u6218\u672f\u6001\u52bf", x0 + 6, y0 + 4, -9633894, 1.0f, false);
       String link = "LINK";
       float lw = MapDraw.width(link, 0.75f);
       d.text(link, (float) (x1 - 12) - lw, y0 + 6, -7363686, 0.75f, false);
       d.disc(x1 - 7, (float) y0 + 8.5f, 2.0f, now / 600L % 2L == 0L ? -9633894 : -13870276);
       int tx = x0 + 6;
       int inner = w - 12;
       int y = y0 + 22;
       int below = 122;
       int scope = Math.min(Math.min(y1 - y - 6 - below, inner - 86), (int) ((float) h * 0.42f));
       if (scope >= 64) {
           SituationPanel.scope(d, tx, y, scope, m, now, mouseX, mouseY);
           int cx = tx + scope + 6;
           SituationPanel.ownCards(d, m, cx, y, x1 - 6 - cx, (scope - 6) / 4, 1);
           y += scope + 6;
       } else {
           SituationPanel.ownCards(d, m, tx, y, inner, 22, 2);
           y += 52;
       }
       d.text("\u8f7d\u5177", tx, y, -7363686, 1.0f, false);
       d.text(TerminalChrome.clip(m.vehicle().isEmpty() ? "\u2014 \u6b65\u884c" : m.vehicle(), inner - 30), tx + 26, y, -2234400, 1.0f, false);
       y += 11;
       if (m.healthPct() >= 0) {
           d.text("\u7ed3\u6784", tx, y, -7363686, 1.0f, false);
           String pct = m.healthPct() + "%";
           float pw = MapDraw.width(pct, 1.0f);
           int hpColor = m.healthPct() > 60 ? -9633894 : (m.healthPct() > 30 ? -19641 : -50373);
           float bx = tx + 26;
           float bw = (float) (inner - 26) - pw - 4.0f;
           int segments = 10;
           float seg = bw / (float) segments;
           int lit = Math.round((float) m.healthPct() / 10.0f);
           for (int i = 0; i < segments; ++i) {
               d.rect(bx + (float) i * seg, y + 1, bx + (float) (i + 1) * seg - 1.0f, y + 7, i < lit ? hpColor : -15064032);
           }
           d.text(pct, (float) (tx + inner) - pw, y, hpColor, 1.0f, false);
           y += 11;
       }
       y += 3;
       if (m.threats() > 0) {
           int boxH = 26;
           d.rect(x0 + 3, y - 2, x1 - 3, y - 2 + boxH, blink ? -1065349104 : -1874849776);
           d.frame(x0 + 3, y - 2, x1 - 3, y - 2 + boxH, 1.0f, blink ? -50373 : -8380384);
           d.text("\u26a0 \u5bfc\u5f39\u544a\u8b66  \u00d7" + m.threats(), tx, y, blink ? -7968 : -50373, 1.0f, true);
           d.text(String.format(Locale.ROOT, "\u6700\u8fd1 %s   \u65b9\u4f4d %03d\u00b0", ContactRenderer.formatDistance(m.threatDist()), (int) m.threatBearing()), tx, y += 11, -16192, 1.0f, false);
           y += 16;
       } else {
           d.text("RWR", tx, y, -7363686, 1.0f, false);
           d.disc(tx + 27, (float) y + 3.5f, 2.0f, -9633894);
           d.text("\u65e0\u5bfc\u5f39\u9501\u5b9a", tx + 33, y, MapDraw.withAlpha(7143322, 0.8f), 1.0f, false);
           y += 14;
       }
       int chipW = (inner - 6) / 4;
       SituationPanel.chip(d, tx, y, chipW, "\u654c", m.hostile(), -42406);
       SituationPanel.chip(d, tx + (chipW + 2), y, chipW, "\u53cb", m.friendly(), -10832641);
       SituationPanel.chip(d, tx + 2 * (chipW + 2), y, chipW, "\u4e2d", m.neutral(), -11702);
       SituationPanel.chip(d, tx + 3 * (chipW + 2), y, chipW, "\u5f39", m.missiles(), m.missiles() > 0 && blink ? -50373 : -19641);
       d.text("\u654c\u65b9\u63a5\u89e6", tx, y += 18, -9633894, 1.0f, false);
       String sorted = "\u7531\u8fd1\u5230\u8fdc";
       d.text(sorted, (float) (x1 - 6) - MapDraw.width(sorted, 1.0f), y, -10653593, 1.0f, false);
       d.rectH(tx, y += 11, x1 - 6, y + 1, -13870276, 2906940);
       y += 3;
       int footer = y1 - 13;
       if (m.rows().isEmpty()) {
           d.text("\u96f7\u8fbe/\u6570\u636e\u94fe\u65e0\u654c\u65b9\u63a5\u89e6", tx, y + 2, -10653593, 1.0f, false);
       }
       int index = 0;
       for (Row row : m.rows()) {
           boolean hover;
           if (y + 22 > footer)
               break;
           int top = y;
           int bottom = y + 22 - 1;
           boolean bl = hover = mouseX >= x0 && mouseX < x1 && mouseY >= top && mouseY < bottom;
           if (hover) {
               d.rect(x0 + 3, top, x1 - 3, bottom, -803786716);
               d.frame(x0 + 3, top, x1 - 3, bottom, 1.0f, -9633894);
           } else if (index % 2 == 0) {
               d.rect(x0 + 3, top, x1 - 3, bottom, 1074797078);
           }
           d.rect(x0 + 3, top, x0 + 5, bottom, row.color());
           float kw = MapDraw.width(row.kind(), 1.0f);
           d.rect(tx + 1, top + 1, (float) tx + kw + 5.0f, top + 11, MapDraw.withAlpha(row.color(), 0.22f));
           d.text(row.kind(), tx + 3, top + 2, row.color(), 1.0f, false);
           String dist = ContactRenderer.formatDistance(row.dist());
           float dw = MapDraw.width(dist, 1.0f);
           d.text(dist, (float) (x1 - 7) - dw, top + 2, -2234400, 1.0f, false);
           d.text(TerminalChrome.clip(row.name(), (float) (x1 - 7) - dw - 6.0f - ((float) tx + kw + 8.0f)), (float) tx + kw + 8.0f, top + 2, -2234400, 1.0f, false);
           d.text(String.format(Locale.ROOT, "%03d\u00b0   \u2191%dm", (int) row.bearing(), row.alt()), tx + 3, top + 14, -7363686, 0.75f, false);
           if (row.trend() != 0) {
               String trend = row.trend() > 0 ? "\u25b2\u63a5\u8fd1" : "\u25bc\u8fdc\u79bb";
               d.text(trend, (float) (x1 - 7) - MapDraw.width(trend, 1.0f), top + 12, row.trend() > 0 ? -16256 : -10653593, 1.0f, false);
           }
           HITS.add(new double[] { x0, top, x1, bottom, row.x(), row.z() });
           y += 22;
           ++index;
       }
       d.rect(x0 + 3, footer - 2, x1 - 3, footer - 1, -14928602);
       d.text(TerminalChrome.clip("\u70b9\u9009\u5b9a\u4f4d \u00b7 \u70b9\u96f7\u8fbe\u5207\u91cf\u7a0b \u00b7 Shift\u6d4b\u8ddd", inner), tx, footer + 2, -10653593, 1.0f, false);
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
       VehicleEntity ve;
       Entity body = player.getRootVehicle();
       Vec3 own = body.position();
       String vehicle = body == player ? "" : body.getDisplayName().getString();
       int alt = Mth.floor((double) own.y);
       int agl = -1;
       if (level.hasChunk(Mth.floor((double) own.x) >> 4, Mth.floor((double) own.z) >> 4)) {
           agl = Math.max(0, alt - level.getHeight(Heightmap.Types.WORLD_SURFACE, Mth.floor((double) own.x), Mth.floor((double) own.z)));
       }
       Vec3 v = ContactRenderer.velocityOf((Level) level, body);
       int speed = (int) Math.round(v.length() * 20.0 * 3.6);
       int heading = Math.floorMod(Math.round(body.getYRot() + 180.0f), 360);
       int health = -1;
       if (body instanceof VehicleEntity && (ve = (VehicleEntity) body).getMaxHealth() > 0.0f) {
           health = Mth.clamp((int) Math.round(ve.getHealth() / ve.getMaxHealth() * 100.0f), (int) 0, (int) 100);
       }
       String myId = player.getStringUUID();
       String bodyId = body.getStringUUID();
       IntOpenHashSet seen = new IntOpenHashSet();
       IntOpenHashSet incomingIds = new IntOpenHashSet();
       ArrayList<Blip> blips = new ArrayList<Blip>();
       int threats = 0;
       double threatDist = Double.MAX_VALUE;
       double threatBearing = 0.0;
       List<Entity> missiles = com.f16addon.client.ClientMissileIndex.missiles(level);
       for (Entity e : missiles) {
           Object lock;
           if (!(e instanceof MissileProjectile))
               continue;
           MissileProjectile m = (MissileProjectile) e;
           if (!seen.add(e.getId()) || (lock = m.getTargetUUID()) == null || !((String) lock).equals(myId) && !((String) lock).equals(bodyId))
               continue;
           Vec3 p = ClientSyncedEntityHandler.getExtrapolatedPos((Level) level, (Entity) e);
           double d = p.distanceTo(own);
           ++threats;
           incomingIds.add(e.getId());
           blips.add(new Blip(5, e.getDisplayName().getString(), p.x, p.z));
           if (!(d < threatDist))
               continue;
           threatDist = d;
           threatBearing = SituationPanel.bearing(p.x - own.x, p.z - own.z);
       }
       ArrayList<Row> rows = new ArrayList<Row>();
       int hostileMissiles = 0;
       int hostileCount = 0;
       for (Entity e : ClientSyncedEntityHandler.getSyncedHostileEntities((Level) level)) {
           boolean air;
           if (e.getVehicle() != null)
               continue;
           ++hostileCount;
           boolean missile = e instanceof MissileProjectile;
           boolean bl = air = !missile && Sbw.isAircraft((Entity) e);
           if (missile) {
               ++hostileMissiles;
           }
           Vec3 p = ClientSyncedEntityHandler.getExtrapolatedPos((Level) level, (Entity) e);
           String name = e.getDisplayName().getString();
           rows.add(SituationPanel.row(e.getId(), SituationPanel.kindOf((Entity) e), name, own, p, missile ? -50373 : (air ? -19641 : -42406)));
           if (incomingIds.contains(e.getId()))
               continue;
           SituationPanel.blip(blips, missile ? 2 : (air ? 1 : 0), name, p);
       }
       int friendlyCount = 0;
       for (Entity e : ClientSyncedEntityHandler.getSyncedFriendlyEntities((Level) level)) {
           if (e.getVehicle() != null)
               continue;
           ++friendlyCount;
           if (e.getId() == body.getId() || e.getId() == player.getId())
               continue;
           SituationPanel.blip(blips, 3, e.getDisplayName().getString(), ClientSyncedEntityHandler.getExtrapolatedPos((Level) level, (Entity) e));
       }
       int neutralCount = 0;
       for (Entity e : ClientSyncedEntityHandler.getSyncedNeutralEntities((Level) level)) {
           if (e.getVehicle() != null)
               continue;
           ++neutralCount;
           SituationPanel.blip(blips, 4, e.getDisplayName().getString(), ClientSyncedEntityHandler.getExtrapolatedPos((Level) level, (Entity) e));
       }
       for (ClientSyncedEntityHandler.ClientSyncedPlayer info : ClientSyncedEntityHandler.getSyncedPlayerInfo((Level) level)) {
           if (info.getOnVehicle() || info.getUuid().equals(player.getUUID()))
               continue;
           if ("hostile".equals(info.getRelation())) {
               ++hostileCount;
               rows.add(SituationPanel.row(info.getEntityId(), "\u6b65\u5175", info.getName(), own, info.getPos(), -42406));
               SituationPanel.blip(blips, 0, info.getName(), info.getPos());
               continue;
           }
           if (!"friendly".equals(info.getRelation()))
               continue;
           SituationPanel.blip(blips, 3, info.getName(), info.getPos());
       }
       rows.sort(Comparator.comparingDouble(Row::dist));
       if (rows.size() > 12) {
           rows = new ArrayList(rows.subList(0, 12));
       }
       Int2DoubleOpenHashMap next = new Int2DoubleOpenHashMap();
       for (Row r : rows) {
           next.put(r.id(), r.dist());
       }
       LAST_DIST.clear();
       LAST_DIST.putAll((Map) next);
       return new Model(vehicle, own.x, own.z, alt, agl, speed, heading, health, threats, threatDist, threatBearing, hostileCount, friendlyCount, neutralCount, hostileMissiles, List.copyOf(rows), List.copyOf(blips));
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

   private static ClientLevel modelWorld;

   public static void reset() {
       modelWorld = null;
       model = null;
       builtAt = 0;
       LAST_DIST.clear();
       HITS.clear();
   }

}
