package com.f16addon.client.map;

import static com.f16addon.client.map.TerminalStyle.*;

import com.atsuishio.superbwarfare.client.map.TacticalMapCache;
import com.mojang.blaze3d.platform.Window;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Restyled chrome of SBW's tactical map: panel, grid, frame, compass, status bar and the two tool buttons. Everything
 * is drawn through {@link MapDraw}, so each part costs one or two draw calls (SBW's grid issued a fill and a string
 * draw per grid line).
 */
public final class TerminalChrome {
   private static final int[] GRID_STEPS = {10, 20, 50, 100, 200, 250, 500, 1000, 2000, 2500, 5000, 10000, 20000, 50000, 100000};
   private static final int[] SCALE_STEPS = {5, 10, 20, 25, 50, 100, 200, 250, 500, 1000, 2000, 2500, 5000, 10000, 20000, 50000, 100000};
   private static final float LABEL = 0.75F;

   private TerminalChrome() {
   }

   // ----------------------------------------------------------------------------------------------------------------
   // Panel background (replaces renderPanelBg; drawn before the map)
   // ----------------------------------------------------------------------------------------------------------------

   public static void panel(GuiGraphics g, int px, int py, int pw, int ph, int modeColor) {
      MapDraw d = MapDraw.get();
      d.rectV(px, py, px + pw, py + ph, BG_TOP, BG_BOTTOM);
      d.frame(px, py, px + pw, py + ph, 1.0F, FRAME_DIM);
      d.corners(px, py, px + pw, py + ph, 8.0F, 1.0F, modeColor);
      d.flush(g);
   }

   // ----------------------------------------------------------------------------------------------------------------
   // Map overlay (replaces renderGridLines; drawn inside the map scissor, under every contact)
   // ----------------------------------------------------------------------------------------------------------------

   public static void grid(GuiGraphics g, double viewX, double viewZ, double zoom, float cx, float cy, int mapL, int mapT, int mapW, int mapH) {
      MapDraw d = MapDraw.get();
      double scale = zoom / 5.0;
      int mapR = mapL + mapW;
      int mapB = mapT + mapH;

      // Slight phosphor tint + scanlines: reads as a display, keeps terrain colors legible.
      d.rect(mapL, mapT, mapR, mapB, 0x14003018);
      for (int y = mapT + 1; y < mapB; y += 3) {
         d.rect(mapL, y, mapR, y + 1, 0x0E000000);
      }

      int major = GRID_STEPS[GRID_STEPS.length - 1];
      for (int s : GRID_STEPS) {
         if (s * scale >= 64.0) {
            major = s;
            break;
         }
      }

      boolean chunkGrid = 16.0 * scale >= 32.0;
      int minor = 0;
      if (chunkGrid) {
         minor = 16;
      } else if (major % 5 == 0 && major / 5 * scale >= 10.0) {
         minor = major / 5;
      } else if (major % 2 == 0 && major / 2 * scale >= 10.0) {
         minor = major / 2;
      }

      double halfW = mapW / 2.0 / scale;
      double halfH = mapH / 2.0 / scale;
      int minorColor = chunkGrid ? 0x26FFC860 : MapDraw.withAlpha(ACCENT_RGB, 0.10F);
      int majorColor = MapDraw.withAlpha(ACCENT_RGB, 0.32F);
      if (minor > 0) {
         gridLines(d, viewX, halfW, minor, scale, cx, mapL, mapR, true, mapT, mapB, minorColor);
         gridLines(d, viewZ, halfH, minor, scale, cy, mapT, mapB, false, mapL, mapR, minorColor);
      }

      gridLines(d, viewX, halfW, major, scale, cx, mapL, mapR, true, mapT, mapB, majorColor);
      gridLines(d, viewZ, halfH, major, scale, cy, mapT, mapB, false, mapL, mapR, majorColor);

      // Edge vignette.
      int edge = 0x78000000;
      int clear = 0x00000000;
      float v = Math.min(22.0F, Math.min(mapW, mapH) * 0.12F);
      d.rectV(mapL, mapT, mapR, mapT + v, edge, clear);
      d.rectV(mapL, mapB - v, mapR, mapB, clear, edge);
      d.rectH(mapL, mapT, mapL + v, mapB, edge, clear);
      d.rectH(mapR - v, mapT, mapR, mapB, clear, edge);

      // Slow sweep band rolling down the display.
      long now = System.currentTimeMillis();
      float band = mapT + (now % 7000L) / 7000.0F * (mapH + 40) - 20;
      d.rectV(mapL, band - 18, mapR, band, 0x006CFF9A, 0x106CFF9A);
      d.rect(mapL, band, mapR, band + 1, 0x186CFF9A);

      // Major grid coordinates in small chips along the top and left edges.
      majorLabels(d, viewX, halfW, major, scale, cx, mapL, mapR - 52, true, mapT);
      majorLabels(d, viewZ, halfH, major, scale, cy, mapT + 14, mapB - 12, false, mapL);

      measure(d, scale, viewX, viewZ, cx, cy, mapL, mapT, mapR, mapB);
      d.flush(g);
   }

   private static void gridLines(MapDraw d, double view, double half, int step, double scale, float center, int from, int to,
                                 boolean vertical, int crossFrom, int crossTo, int color) {
      long first = (long) Math.floor((view - half) / step) * step;
      long last = (long) Math.ceil((view + half) / step) * step;
      for (long w = first; w <= last; w += step) {
         float s = (float) (center + (w - view) * scale);
         if (s < from || s >= to) {
            continue;
         }

         if (vertical) {
            d.rect(s, crossFrom, s + 1, crossTo, color);
         } else {
            d.rect(crossFrom, s, crossTo, s + 1, color);
         }
      }
   }

   private static void majorLabels(MapDraw d, double view, double half, int step, double scale, float center, int from, int to,
                                   boolean vertical, int edge) {
      long first = (long) Math.floor((view - half) / step) * step;
      long last = (long) Math.ceil((view + half) / step) * step;
      for (long w = first; w <= last; w += step) {
         float s = (float) (center + (w - view) * scale);
         String text = String.format(Locale.ROOT, "%,d", w);
         float tw = MapDraw.width(text, LABEL);
         float x = vertical ? s + 2 : edge + 2;
         float y = vertical ? edge + 2 : s + 2;
         if (vertical ? (s < from || x + tw + 3 > to) : (s < from || s > to)) {
            continue;
         }

         d.rect(x - 1, y - 1, x + tw + 2, y + 7, 0xA0050A08);
         d.text(text, x + 1, y, MapDraw.withAlpha(0xCFFFE0, 0.85F), LABEL, false);
      }
   }

   /** Hold Shift: dashed range/bearing line from own ship to the cursor. Label is drawn by {@link #hud}. */
   private static void measure(MapDraw d, double scale, double viewX, double viewZ, float cx, float cy, int mapL, int mapT, int mapR, int mapB) {
      Minecraft mc = Minecraft.getInstance();
      if (mc.player == null || !Screen.hasShiftDown()) {
         return;
      }

      double[] m = mouse();
      if (m[0] < mapL || m[0] >= mapR || m[1] < mapT || m[1] >= mapB) {
         return;
      }

      Entity body = mc.player.getRootVehicle();
      float ox = (float) (cx + (body.getX() - viewX) * scale);
      float oy = (float) (cy + (body.getZ() - viewZ) * scale);
      float mx = (float) m[0];
      float my = (float) m[1];
      d.dashed(ox, oy, mx, my, 1.5F, 6.0F, 4.0F, MapDraw.withAlpha(0xFFFFFF, 0.85F), mapL, mapT, mapR, mapB);
      float r = (float) Math.sqrt(Mth.square(mx - ox) + Mth.square(my - oy));
      if (r > 6.0F) {
         d.ring(ox, oy, r, 1.0F, MapDraw.withAlpha(0xFFFFFF, 0.18F));
      }

      d.ring(mx, my, 4.0F, 1.5F, 0xFFFFFFFF);
   }

   // ----------------------------------------------------------------------------------------------------------------
   // Map frame + mode banner + cursor edge markers (replaces renderMapBorder; drawn after the scissor)
   // ----------------------------------------------------------------------------------------------------------------

   public static void border(GuiGraphics g, int mapL, int mapT, int mapW, int mapH, int modeColor, String modeHint) {
      MapDraw d = MapDraw.get();
      int mapR = mapL + mapW;
      int mapB = mapT + mapH;
      long now = System.currentTimeMillis();
      boolean moded = modeHint != null;
      float pulse = moded ? 0.55F + 0.45F * Mth.sin(now % 1200L / 1200.0F * Mth.TWO_PI) : 1.0F;
      int frame = moded ? MapDraw.withAlpha(modeColor, 0.35F + 0.4F * pulse) : FRAME;
      d.frame(mapL - 1, mapT - 1, mapR + 1, mapB + 1, 1.0F, frame);
      d.corners(mapL - 3, mapT - 3, mapR + 3, mapB + 3, 12.0F, 2.0F, moded ? MapDraw.withAlpha(modeColor, 0.6F + 0.4F * pulse) : modeColor);

      // Edge ticks (every 1/8 of the frame), like a plotting board.
      int tick = MapDraw.withAlpha(ACCENT_RGB, 0.45F);
      for (int i = 1; i < 8; i++) {
         float x = mapL + mapW * i / 8.0F;
         float y = mapT + mapH * i / 8.0F;
         float len = i == 4 ? 5.0F : 3.0F;
         d.rect(x, mapT, x + 1, mapT + len, tick);
         d.rect(x, mapB - len, x + 1, mapB, tick);
         d.rect(mapL, y, mapL + len, y + 1, tick);
         d.rect(mapR - len, y, mapR, y + 1, tick);
      }

      double[] m = mouse();
      float mx = (float) m[0];
      float my = (float) m[1];
      if (mx >= mapL && mx < mapR && my >= mapT && my < mapB) {
         int c = 0xE0FFFFFF;
         d.triangle(mx - 3, mapT - 4, mx + 3, mapT - 4, mx, mapT, c);
         d.triangle(mx - 3, mapB + 4, mx, mapB, mx + 3, mapB + 4, c);
         d.triangle(mapL - 4, my - 3, mapL, my, mapL - 4, my + 3, c);
         d.triangle(mapR + 4, my - 3, mapR + 4, my + 3, mapR, my, c);
      }

      if (moded) {
         int bh = 14;
         d.rectV(mapL, mapB - bh - 6, mapR, mapB, MapDraw.withAlpha(modeColor, 0.0F), MapDraw.withAlpha(modeColor, 0.30F));
         d.rect(mapL, mapB - bh, mapR, mapB - bh + 1, MapDraw.withAlpha(modeColor, 0.8F));
         String text = clip(modeHint, mapW - 12);
         float tw = MapDraw.width(text, 1.0F);
         d.text(text, mapL + (mapW - tw) / 2.0F, mapB - bh + 3, 0xFFFFFFFF, 1.0F, true);
      }

      d.flush(g);
   }

   // ----------------------------------------------------------------------------------------------------------------
   // Compass (replaces SBW's static compass-rose texture)
   // ----------------------------------------------------------------------------------------------------------------

   public static void compass(GuiGraphics g, int mapL, int mapT, int mapW, int mapH) {
      Minecraft mc = Minecraft.getInstance();
      if (mc.player == null || mapW < 120 || mapH < 90) {
         return;
      }

      MapDraw d = MapDraw.get();
      float r = 17.0F;
      float cx = mapL + mapW - r - 7;
      float cy = mapT + r + 7;
      d.disc(cx, cy, r + 1, 0xB8050A08);
      d.ring(cx, cy, r, 1.0F, FRAME);
      for (int i = 0; i < 36; i++) {
         float a = i * 10.0F * Mth.DEG_TO_RAD;
         float inner = i % 9 == 0 ? r - 5 : (i % 3 == 0 ? r - 3.5F : r - 2);
         float sx = Mth.sin(a);
         float sy = -Mth.cos(a);
         d.line(cx + sx * inner, cy + sy * inner, cx + sx * r, cy + sy * r, i % 9 == 0 ? 1.2F : 0.7F,
            i % 9 == 0 ? ACCENT : MapDraw.withAlpha(ACCENT_RGB, 0.45F));
      }

      // North pointer.
      d.triangle(cx, cy - r - 4, cx - 3.5F, cy - r + 3, cx + 3.5F, cy - r + 3, WARN);
      d.text("N", cx - 2, cy - r + 5, 0xFFFF8080, LABEL, false);

      // Own heading needle (yaw 0 = south = screen down).
      Entity body = mc.player.getRootVehicle();
      float yaw = body.getYRot() * Mth.DEG_TO_RAD;
      float hx = -Mth.sin(yaw);
      float hy = Mth.cos(yaw);
      float tip = r - 6;
      d.triangle(cx + hx * tip, cy + hy * tip, cx - hy * 3.0F, cy + hx * 3.0F, cx + hy * 3.0F, cy - hx * 3.0F, ACCENT);
      d.line(cx, cy, cx - hx * 6.0F, cy - hy * 6.0F, 1.5F, MapDraw.withAlpha(ACCENT_RGB, 0.5F));
      d.disc(cx, cy, 1.5F, 0xFFFFFFFF);

      String hdg = String.format(Locale.ROOT, "%03d°", Math.floorMod(Math.round(body.getYRot() + 180.0F), 360));
      float tw = MapDraw.width(hdg, LABEL);
      d.rect(cx - tw / 2 - 2, cy + r + 3, cx + tw / 2 + 2, cy + r + 11, 0xB8050A08);
      d.text(hdg, cx - tw / 2, cy + r + 4, ACCENT, LABEL, false);
      d.flush(g);
   }

   // ----------------------------------------------------------------------------------------------------------------
   // Status bar under the map (replaces renderHudText)
   // ----------------------------------------------------------------------------------------------------------------

   public static void hud(GuiGraphics g, Player player, int mouseX, int mouseY, double viewX, double viewZ, double zoom, float cx, float cy,
                          int mapL, int mapT, int mapW, int mapH) {
      MapDraw d = MapDraw.get();
      double scale = zoom / 5.0;
      int mapR = mapL + mapW;
      int mapB = mapT + mapH;
      boolean inside = mouseX >= mapL && mouseX <= mapR && mouseY >= mapT && mouseY <= mapB;
      Entity body = player.getRootVehicle();

      // Row 1: cursor coordinates (left), range / bearing from own ship to the cursor (right).
      float y1 = mapB + 4;
      float x = mapL;
      String cX = "---";
      String cY = "---";
      String cZ = "---";
      String range = null;
      if (inside) {
         int wx = Mth.floor(viewX + (mouseX - cx) / scale);
         int wz = Mth.floor(viewZ + (mouseY - cy) / scale);
         cX = Integer.toString(wx);
         cZ = Integer.toString(wz);
         cY = surface(player.level(), wx, wz);
         double dx = wx + 0.5 - body.getX();
         double dz = wz + 0.5 - body.getZ();
         double bearing = Math.toDegrees(Math.atan2(dx, -dz));
         range = ContactRenderer.formatDistance(Math.sqrt(dx * dx + dz * dz)) + "  "
            + String.format(Locale.ROOT, "%03d°", (int) Math.floorMod(Math.round(bearing), 360));
      }

      d.text("◎", x, y1, ACCENT, 1.0F, false);
      x += 10;
      x = field(d, "X", cX, x, y1);
      x = field(d, "Y", cY, x, y1);
      x = field(d, "Z", cZ, x, y1);
      if (range != null) {
         float tw = MapDraw.width(range, 1.0F);
         float rx = mapR - tw;
         if (rx - 9 >= x) {
            d.text("↗", rx - 9, y1, DIM, 1.0F, false);
            d.text(range, rx, y1, ACCENT, 1.0F, false);
         }

         if (Screen.hasShiftDown()) {
            float lw = MapDraw.width(range, 1.0F) + 6;
            float lx = Math.min(mouseX + 8, mapR - lw);
            float ly = Math.max(mouseY - 16, mapT + 2);
            d.rect(lx, ly, lx + lw, ly + 12, 0xD0050A08);
            d.frame(lx, ly, lx + lw, ly + 12, 1.0F, FRAME);
            d.text(range, lx + 3, ly + 2, 0xFFFFFFFF, 1.0F, false);
         }
      }

      // Row 2 (right of the two tool buttons): scale bar, zoom, own position.
      float y2 = mapB + 19;
      float sx = mapL + 50;
      int dist = SCALE_STEPS[0];
      for (int s : SCALE_STEPS) {
         if (s * scale <= 90.0) {
            dist = s;
         }
      }

      float len = (float) (dist * scale);
      float barY = y2 + 4;
      for (int i = 0; i < 4; i++) {
         d.rect(sx + len * i / 4.0F, barY, sx + len * (i + 1) / 4.0F, barY + 3, i % 2 == 0 ? ACCENT : 0xFF14261C);
      }

      d.frame(sx - 1, barY - 1, sx + len + 1, barY + 4, 1.0F, FRAME);
      d.rect(sx - 1, barY - 4, sx, barY + 4, ACCENT);
      d.rect(sx + len, barY - 4, sx + len + 1, barY + 4, ACCENT);
      d.text("0", sx - 2, barY + 6, DIM, LABEL, false);
      String distText = ContactRenderer.formatDistance(dist);
      d.text(distText, sx + len - MapDraw.width(distText, LABEL) / 2.0F, barY + 6, DIM, LABEL, false);
      float after = sx + len + 10;
      String zoomText = String.format(Locale.ROOT, "×%.1f", zoom);
      d.text(zoomText, after, y2 + 2, TEXT, 1.0F, false);
      after += MapDraw.width(zoomText, 1.0F) + 12;

      String own = String.format(Locale.ROOT, "本机 %d, %d, %d", Mth.floor(body.getX()), Mth.floor(body.getY()), Mth.floor(body.getZ()));
      float ow = MapDraw.width(own, 1.0F);
      if (mapR - ow >= after) {
         d.text(own, mapR - ow, y2 + 2, DIM, 1.0F, false);
      }

      d.flush(g);
   }

   private static float field(MapDraw d, String label, String value, float x, float y) {
      d.text(label, x, y, DIM, 1.0F, false);
      x += MapDraw.width(label, 1.0F) + 3;
      d.text(value, x, y, TEXT, 1.0F, false);
      return x + Math.max(MapDraw.width(value, 1.0F), MapDraw.width("-0000", 1.0F)) + 8;
   }

   private static String surface(Level level, int wx, int wz) {
      LevelChunk chunk = level.getChunkSource().getChunk(wx >> 4, wz >> 4, false);
      if (chunk != null && !chunk.isEmpty()) {
         int h = level.getHeight(Heightmap.Types.WORLD_SURFACE, wx, wz) - 1;
         return h > level.getMinBuildHeight() ? Integer.toString(h) : "---";
      }

      Short cached = TacticalMapCache.INSTANCE.getCachedHeight(wx, wz);
      return cached != null ? Integer.toString(cached - 1) : "---";
   }

   // ----------------------------------------------------------------------------------------------------------------
   // Tool buttons (drawn over SBW's vanilla buttons, before their tooltips)
   // ----------------------------------------------------------------------------------------------------------------

   public static void buttons(GuiGraphics g, Button center, Button follow, boolean following, int mouseX, int mouseY) {
      MapDraw d = MapDraw.get();
      button(d, center, false, mouseX, mouseY);
      button(d, follow, following, mouseX, mouseY);

      float cx = center.getX() + center.getWidth() / 2.0F;
      float cy = center.getY() + center.getHeight() / 2.0F;
      int c = center.isMouseOver(mouseX, mouseY) ? 0xFFFFFFFF : TEXT;
      d.ring(cx, cy, 4.5F, 1.2F, c);
      d.rect(cx - 0.5F, cy - 8, cx + 0.5F, cy - 3, c);
      d.rect(cx - 0.5F, cy + 3, cx + 0.5F, cy + 8, c);
      d.rect(cx - 8, cy - 0.5F, cx - 3, cy + 0.5F, c);
      d.rect(cx + 3, cy - 0.5F, cx + 8, cy + 0.5F, c);

      float fx = follow.getX() + follow.getWidth() / 2.0F;
      float fy = follow.getY() + follow.getHeight() / 2.0F;
      int f = following ? ACCENT : (follow.isMouseOver(mouseX, mouseY) ? 0xFFFFFFFF : TEXT);
      d.ring(fx, fy, 6.0F, 1.2F, f);
      d.disc(fx, fy, following ? 3.0F : 2.0F, f);
      if (following) {
         float a = System.currentTimeMillis() % 1500L / 1500.0F;
         d.ring(fx, fy, 6.0F + a * 3.0F, 1.0F, MapDraw.withAlpha(ACCENT_RGB, 1.0F - a));
      }

      d.flush(g);
   }

   private static void button(MapDraw d, Button b, boolean active, int mouseX, int mouseY) {
      float x1 = b.getX();
      float y1 = b.getY();
      float x2 = x1 + b.getWidth();
      float y2 = y1 + b.getHeight();
      boolean hover = b.isMouseOver(mouseX, mouseY);
      d.rectV(x1, y1, x2, y2, active ? 0xFF1A4A30 : (hover ? 0xFF1E3329 : 0xFF121D19), active ? 0xFF0F2A1C : 0xFF0A110E);
      d.frame(x1, y1, x2, y2, 1.0F, active || hover ? ACCENT : FRAME);
   }

   // ----------------------------------------------------------------------------------------------------------------

   static double[] mouse() {
      Minecraft mc = Minecraft.getInstance();
      Window w = mc.getWindow();
      return new double[]{mc.mouseHandler.xpos() * w.getGuiScaledWidth() / Math.max(1, w.getScreenWidth()),
         mc.mouseHandler.ypos() * w.getGuiScaledHeight() / Math.max(1, w.getScreenHeight())};
   }

   static String clip(String text, float maxPx) {
      var font = Minecraft.getInstance().font;
      if (font.width(text) <= maxPx) {
         return text;
      }

      return font.plainSubstrByWidth(text, Math.max(0, (int) maxPx - font.width("…"))) + "…";
   }
}
