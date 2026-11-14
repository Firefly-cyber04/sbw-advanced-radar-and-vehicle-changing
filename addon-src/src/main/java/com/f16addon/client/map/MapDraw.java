package com.f16addon.client.map;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;

/**
 * Batched 2D drawing for the tactical map.
 *
 * <p>In 1.20.1 every {@code GuiGraphics.fill} / {@code drawString} outside {@code drawManaged} ends the buffer batch, and
 * every {@code blit} is its own immediate draw call. Superb Warfare's map drew dashed missile→target lines one
 * 1-pixel {@code fill} per dash (hundreds of draw calls per missile per frame) and one {@code blit} per contact icon
 * (up to 3 × 256 per frame). This collects colored triangles, textured quads (grouped by texture) and text, and
 * emits them in a handful of draw calls in {@link #flush}.
 *
 * <p>Not re-entrant: render code is single threaded and every user flushes before returning.
 */
public final class MapDraw {
   private static final MapDraw SHARED = new MapDraw();
   private static final int COLOR_STRIDE = 6;
   private static final int TEX_STRIDE = 8;

   private final BufferBuilder builder = new BufferBuilder(1 << 16);
   private float[] color = new float[COLOR_STRIDE * 3 * 512];
   private int colorSize;
   private final Map<ResourceLocation, FloatList> textured = new IdentityHashMap<>();
   private final List<ResourceLocation> textureOrder = new ArrayList<>();
   private final List<Text> texts = new ArrayList<>();

   private record Text(String text, float x, float y, int argb, float scale, boolean shadow) {
   }

   private static final class FloatList {
      float[] data = new float[TEX_STRIDE * 4 * 32];
      int size;

      void ensure(int extra) {
         if (size + extra > data.length) {
            data = java.util.Arrays.copyOf(data, Math.max(data.length * 2, size + extra));
         }
      }
   }

   private MapDraw() {
   }

   /** The shared batch; callers must {@link #flush} before returning. */
   public static MapDraw get() {
      return SHARED;
   }

   // ----------------------------------------------------------------------------------------------------------------
   // Colored geometry
   // ----------------------------------------------------------------------------------------------------------------

   public void triangle(float x1, float y1, float x2, float y2, float x3, float y3, int argb) {
      triangle(x1, y1, argb, x2, y2, argb, x3, y3, argb);
   }

   public void triangle(float x1, float y1, int c1, float x2, float y2, int c2, float x3, float y3, int c3) {
      if (colorSize + COLOR_STRIDE * 3 > color.length) {
         color = java.util.Arrays.copyOf(color, color.length * 2);
      }

      putColorVertex(x1, y1, c1);
      putColorVertex(x2, y2, c2);
      putColorVertex(x3, y3, c3);
   }

   private void putColorVertex(float x, float y, int argb) {
      float[] c = color;
      int i = colorSize;
      c[i] = x;
      c[i + 1] = y;
      c[i + 2] = (argb >> 16 & 255) / 255.0F;
      c[i + 3] = (argb >> 8 & 255) / 255.0F;
      c[i + 4] = (argb & 255) / 255.0F;
      c[i + 5] = (argb >>> 24) / 255.0F;
      colorSize = i + COLOR_STRIDE;
   }

   public void rect(float x1, float y1, float x2, float y2, int argb) {
      triangle(x1, y1, x1, y2, x2, y2, argb);
      triangle(x1, y1, x2, y2, x2, y1, argb);
   }

   public void line(float x1, float y1, float x2, float y2, float width, int argb) {
      float dx = x2 - x1;
      float dy = y2 - y1;
      float len = Mth.sqrt(dx * dx + dy * dy);
      if (len < 1.0E-3F) {
         return;
      }

      float nx = -dy / len * width * 0.5F;
      float ny = dx / len * width * 0.5F;
      triangle(x1 + nx, y1 + ny, x1 - nx, y1 - ny, x2 - nx, y2 - ny, argb);
      triangle(x1 + nx, y1 + ny, x2 - nx, y2 - ny, x2 + nx, y2 + ny, argb);
   }

   /** Dashed line, clipped to the rectangle first so a target 100 km away does not produce millions of dashes. */
   public void dashed(float x1, float y1, float x2, float y2, float width, float dash, float gap, int argb,
                      float clipLeft, float clipTop, float clipRight, float clipBottom) {
      float[] seg = clip(x1, y1, x2, y2, clipLeft, clipTop, clipRight, clipBottom);
      if (seg == null) {
         return;
      }

      float dx = x2 - x1;
      float dy = y2 - y1;
      float total = Mth.sqrt(dx * dx + dy * dy);
      if (total < 1.0E-3F) {
         return;
      }

      float ux = dx / total;
      float uy = dy / total;
      float period = dash + gap;
      // Keep the dash phase anchored to the real start point so dashes do not crawl while the map is panned.
      float from = (float) Math.floor(seg[0] * total / period) * period;
      float to = seg[1] * total;
      for (float t = from; t < to; t += period) {
         float a = Math.max(t, seg[0] * total);
         float b = Math.min(t + dash, to);
         if (b > a) {
            line(x1 + ux * a, y1 + uy * a, x1 + ux * b, y1 + uy * b, width, argb);
         }
      }
   }

   /** Liang-Barsky: returns {t0, t1} of the visible part of the segment, or null. */
   static float[] clip(float x1, float y1, float x2, float y2, float left, float top, float right, float bottom) {
      float dx = x2 - x1;
      float dy = y2 - y1;
      float t0 = 0.0F;
      float t1 = 1.0F;
      float[] p = {-dx, dx, -dy, dy};
      float[] q = {x1 - left, right - x1, y1 - top, bottom - y1};
      for (int i = 0; i < 4; i++) {
         if (Math.abs(p[i]) < 1.0E-6F) {
            if (q[i] < 0.0F) {
               return null;
            }

            continue;
         }

         float r = q[i] / p[i];
         if (p[i] < 0.0F) {
            if (r > t1) {
               return null;
            }

            t0 = Math.max(t0, r);
         } else {
            if (r < t0) {
               return null;
            }

            t1 = Math.min(t1, r);
         }
      }

      return t0 <= t1 ? new float[]{t0, t1} : null;
   }

   public void ring(float cx, float cy, float radius, float width, int argb) {
      int segments = Mth.clamp((int) (radius * 0.5F), 16, 96);
      arc(cx, cy, radius, 0.0F, Mth.TWO_PI, segments, width, argb);
   }

   /** Arc from {@code start} sweeping {@code sweep} radians (screen space: 0 = +x, +π/2 = +y). */
   public void arc(float cx, float cy, float radius, float start, float sweep, int segments, float width, int argb) {
      float px = cx + Mth.cos(start) * radius;
      float py = cy + Mth.sin(start) * radius;
      for (int i = 1; i <= segments; i++) {
         float a = start + sweep * i / segments;
         float nx = cx + Mth.cos(a) * radius;
         float ny = cy + Mth.sin(a) * radius;
         line(px, py, nx, ny, width, argb);
         px = nx;
         py = ny;
      }
   }

   /** Filled sector with a center color fading to an edge color. */
   public void sector(float cx, float cy, float radius, float start, float sweep, int centerArgb, int edgeArgb) {
      int segments = Mth.clamp((int) (Math.abs(sweep) * radius / 12.0F), 2, 48);
      float px = cx + Mth.cos(start) * radius;
      float py = cy + Mth.sin(start) * radius;
      for (int i = 1; i <= segments; i++) {
         float a = start + sweep * i / segments;
         float nx = cx + Mth.cos(a) * radius;
         float ny = cy + Mth.sin(a) * radius;
         triangle(cx, cy, centerArgb, px, py, edgeArgb, nx, ny, edgeArgb);
         px = nx;
         py = ny;
      }
   }

   // ----------------------------------------------------------------------------------------------------------------
   // Textured quads
   // ----------------------------------------------------------------------------------------------------------------

   /** Square icon of half-size {@code half} centered at (x, y), rotated by {@code degrees} (same as PoseStack ZP). */
   public void icon(ResourceLocation texture, float x, float y, float half, float degrees, int argb) {
      float cos = 1.0F;
      float sin = 0.0F;
      if (degrees != 0.0F) {
         float rad = degrees * Mth.DEG_TO_RAD;
         cos = Mth.cos(rad);
         sin = Mth.sin(rad);
      }

      FloatList list = textured.get(texture);
      if (list == null) {
         list = new FloatList();
         textured.put(texture, list);
      }

      if (list.size == 0) {
         textureOrder.add(texture);
      }

      list.ensure(TEX_STRIDE * 4);
      // Same vertex / uv order as GuiGraphics.innerBlit: (x1,y1) (x1,y2) (x2,y2) (x2,y1).
      putTex(list, x, y, -half, -half, cos, sin, 0.0F, 0.0F, argb);
      putTex(list, x, y, -half, half, cos, sin, 0.0F, 1.0F, argb);
      putTex(list, x, y, half, half, cos, sin, 1.0F, 1.0F, argb);
      putTex(list, x, y, half, -half, cos, sin, 1.0F, 0.0F, argb);
   }

   private static void putTex(FloatList list, float cx, float cy, float ox, float oy, float cos, float sin, float u, float v, int argb) {
      float[] d = list.data;
      int i = list.size;
      d[i] = cx + ox * cos - oy * sin;
      d[i + 1] = cy + ox * sin + oy * cos;
      d[i + 2] = u;
      d[i + 3] = v;
      d[i + 4] = (argb >> 16 & 255) / 255.0F;
      d[i + 5] = (argb >> 8 & 255) / 255.0F;
      d[i + 6] = (argb & 255) / 255.0F;
      d[i + 7] = (argb >>> 24) / 255.0F;
      list.size = i + TEX_STRIDE;
   }

   // ----------------------------------------------------------------------------------------------------------------
   // Text
   // ----------------------------------------------------------------------------------------------------------------

   public void text(String text, float x, float y, int argb, float scale, boolean shadow) {
      texts.add(new Text(text, x, y, argb, scale, shadow));
   }

   // ----------------------------------------------------------------------------------------------------------------
   // Flush
   // ----------------------------------------------------------------------------------------------------------------

   /** Draws geometry, then icons, then text, and resets the batch. Leaves blend enabled and shader color white. */
   public void flush(GuiGraphics graphics) {
      Matrix4f matrix = graphics.pose().last().pose();
      RenderSystem.enableBlend();
      RenderSystem.defaultBlendFunc();
      RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
      if (colorSize > 0) {
         RenderSystem.setShader(GameRenderer::getPositionColorShader);
         builder.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
         float[] c = color;
         for (int i = 0; i < colorSize; i += COLOR_STRIDE) {
            builder.vertex(matrix, c[i], c[i + 1], 0.0F).color(c[i + 2], c[i + 3], c[i + 4], c[i + 5]).endVertex();
         }

         BufferUploader.drawWithShader(builder.end());
         colorSize = 0;
      }

      if (!textureOrder.isEmpty()) {
         RenderSystem.setShader(GameRenderer::getPositionColorTexShader);
         for (ResourceLocation texture : textureOrder) {
            FloatList list = textured.get(texture);
            RenderSystem.setShaderTexture(0, texture);
            builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR_TEX);
            float[] d = list.data;
            for (int i = 0; i < list.size; i += TEX_STRIDE) {
               builder.vertex(matrix, d[i], d[i + 1], 0.0F).color(d[i + 4], d[i + 5], d[i + 6], d[i + 7]).uv(d[i + 2], d[i + 3]).endVertex();
            }

            BufferUploader.drawWithShader(builder.end());
            list.size = 0;
         }

         textureOrder.clear();
      }

      if (!texts.isEmpty()) {
         Font font = Minecraft.getInstance().font;
         graphics.drawManaged(() -> {
            for (Text t : texts) {
               if (t.scale() == 1.0F) {
                  graphics.drawString(font, t.text(), (int) t.x(), (int) t.y(), t.argb(), t.shadow());
               } else {
                  graphics.pose().pushPose();
                  graphics.pose().translate(t.x(), t.y(), 0.0F);
                  graphics.pose().scale(t.scale(), t.scale(), 1.0F);
                  graphics.drawString(font, t.text(), 0, 0, t.argb(), t.shadow());
                  graphics.pose().popPose();
               }
            }
         });
         texts.clear();
      }

      RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
   }

   public static int withAlpha(int rgb, float alpha) {
      return (Mth.clamp((int) (alpha * 255.0F), 0, 255) << 24) | (rgb & 0xFFFFFF);
   }
}
