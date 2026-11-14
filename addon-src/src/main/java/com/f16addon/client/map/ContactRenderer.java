package com.f16addon.client.map;

import com.atsuishio.superbwarfare.client.ClientSyncedEntityHandler;
import com.atsuishio.superbwarfare.client.map.MapEntityRenderer;
import com.atsuishio.superbwarfare.entity.projectile.MissileProjectile;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.f16addon.Sbw;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import java.util.List;
import java.util.function.Function;
import kotlin.Unit;
import kotlin.jvm.functions.Function3;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * Replacement for {@code MapEntityRenderer.renderEntityBatch / renderTargetPos / renderRadars}: same data, same
 * hit-testing and context-menu bookkeeping, but drawn through {@link MapDraw} in a few draw calls, plus NTDS-style
 * symbology (relation frames, velocity leaders, altitude tags, threat rings, radar sweep, range rings).
 */
public final class ContactRenderer {
   public static final String RELATION_PREFIX = "context.superbwarfare.tactical_map.relation.";
   private static final ResourceLocation SEL_TARGET = ResourceLocation.tryParse("superbwarfare:textures/overlay/tactical_map/sel_target.png");
   private static final ResourceLocation TARGET_POS = ResourceLocation.tryParse("superbwarfare:textures/overlay/tactical_map/target_pos.png");
   /** SBW draws at most this many contacts per relation group. */
   private static final int MAX_CONTACTS = 256;
   private static final float ICON_HALF = 6.0F;
   /** Velocity leader shows where the contact will be in this many ticks. */
   private static final float LEADER_TICKS = 30.0F;
   private static final float LEADER_MAX_PX = 90.0F;
   private static final int THREAT_RGB = 0xFF3B3B;
   private static final int TARGET_LINE_RGB = 0xFFAA00;
   private static final int RADAR_RGB = 0x3CFF7A;
   private static final int RING_RGB = 0x5FC8FF;
   private static final int[] RING_STEPS = {50, 100, 250, 500, 1000, 2000, 5000, 10000, 20000};

   private ContactRenderer() {
   }

   private enum Relation { FRIENDLY, HOSTILE, NEUTRAL }

   private static Relation relationOf(String relation) {
      return switch (relation) {
         case "hostile" -> Relation.HOSTILE;
         case "neutral" -> Relation.NEUTRAL;
         default -> Relation.FRIENDLY;
      };
   }

   public static String stripRelation(String key) {
      return key.startsWith(RELATION_PREFIX) ? key.substring(RELATION_PREFIX.length()) : key;
   }

   // ----------------------------------------------------------------------------------------------------------------
   // Contacts
   // ----------------------------------------------------------------------------------------------------------------

   public static void renderBatch(MapEntityRenderer self, GuiGraphics graphics, List<? extends Entity> entities, Level level, int tint,
                                  String relationKey, double viewX, double viewZ, float centerX, float centerY, int left, int top,
                                  int width, int height, double scale, float partialTick, int mouseX, int mouseY,
                                  List<? extends Entity> selected, List<MapEntityRenderer.EntityRenderEntry> out,
                                  Function3<? super List<? extends Component>, ? super Integer, ? super Integer, Unit> onHover,
                                  Function<Entity, List<Component>> tooltip,
                                  boolean draggingLoiter, double loiterX, double loiterZ, long loiterExpire) {
      String relationName = stripRelation(relationKey);
      Relation relation = relationOf(relationName);
      int count = Math.min(entities.size(), MAX_CONTACTS);
      if (count == 0) {
         return;
      }

      IntOpenHashSet selectedIds = null;
      if (!selected.isEmpty()) {
         selectedIds = new IntOpenHashSet(selected.size());
         for (Entity e : selected) {
            selectedIds.add(e.getId());
         }
      }

      Minecraft mc = Minecraft.getInstance();
      Player me = mc.player;
      Entity myVehicle = me == null ? null : me.getVehicle();
      String myId = me == null ? "" : me.getStringUUID();
      String myVehicleId = myVehicle == null ? "" : myVehicle.getStringUUID();
      boolean labels = scale >= 0.5 && count <= 96;
      float right = left + width;
      float bottom = top + height;
      int tintRgb = tint & 0xFFFFFF;
      long now = System.currentTimeMillis();
      MapDraw draw = MapDraw.get();

      for (int i = 0; i < count; i++) {
         Entity e = entities.get(i);
         Vec3 pos = ClientSyncedEntityHandler.getExtrapolatedPos(level, e);
         float sx = (float) (centerX + (pos.x - viewX) * scale);
         float sy = (float) (centerY + (pos.z - viewZ) * scale);
         float x = Mth.clamp(sx, left, right);
         float y = Mth.clamp(sy, top, bottom);
         boolean inside = x == sx && y == sy;
         boolean missile = e instanceof MissileProjectile;
         boolean vehicle = e instanceof VehicleEntity;

         if (inside) {
            Vec3 velocity = velocityOf(level, e);
            double speedSqr = velocity.x * velocity.x + velocity.z * velocity.z;
            if (speedSqr > 0.0025) {
               float lx = (float) (velocity.x * LEADER_TICKS * scale);
               float lz = (float) (velocity.z * LEADER_TICKS * scale);
               float len = Mth.sqrt(lx * lx + lz * lz);
               if (len > LEADER_MAX_PX) {
                  lx *= LEADER_MAX_PX / len;
                  lz *= LEADER_MAX_PX / len;
               }

               if (len > ICON_HALF) {
                  draw.line(x, y, x + lx, y + lz, 1.0F, MapDraw.withAlpha(missile ? TARGET_LINE_RGB : tintRgb, 0.8F));
               }
            }

            if (!missile) {
               frame(draw, relation, x, y, MapDraw.withAlpha(tintRgb, 0.6F));
            }
         }

         if (vehicle && myVehicle == e && ((VehicleEntity) e).getLoiterActive()) {
            // The loiter route editor only exists for the player's own aircraft; keep SBW's drawing for it.
            self.renderMapEntity(e, level, scale, partialTick, graphics, tint, viewX, viewZ, centerX, centerY, left, top, width, height,
               draggingLoiter, loiterX, loiterZ, loiterExpire);
         } else {
            float degrees = vehicle || missile ? e.getYRot() + 180.0F : 0.0F;
            draw.icon(self.getVehicleIcon(e), x, y, ICON_HALF, degrees, MapDraw.withAlpha(tintRgb, inside ? 1.0F : 0.5F));
         }

         if (missile) {
            MissileProjectile m = (MissileProjectile) e;
            String lock = m.getTargetUUID();
            boolean threat = lock != null && !lock.isEmpty() && (lock.equals(myId) || lock.equals(myVehicleId));
            if (threat && inside) {
               float pulse = 9.0F + 3.0F * Mth.sin(now / 90.0F);
               draw.ring(x, y, pulse, 1.5F, MapDraw.withAlpha(THREAT_RGB, 0.9F));
            }

            Vec3 target = targetOf(level, m);
            if (target != null) {
               targetLine(draw, target, sx, sy, e, viewX, viewZ, centerX, centerY, left, top, right, bottom, scale, threat, now);
            }
         }

         if (selectedIds != null && selectedIds.contains(e.getId())) {
            draw.icon(SEL_TARGET, x, y, 8.0F, 0.0F, 0xFFFFFFFF);
         }

         if (inside && labels && (missile || Sbw.isAircraft(e))) {
            String tag = altitudeTag(level, e, pos);
            if (tag != null) {
               draw.text(tag, x + ICON_HALF + 2.0F, y - 3.0F, MapDraw.withAlpha(missile ? TARGET_LINE_RGB : tintRgb, 0.9F), 0.75F, true);
            }
         }

         if (x - ICON_HALF <= mouseX && mouseX <= x + ICON_HALF && y - ICON_HALF <= mouseY && mouseY <= y + ICON_HALF) {
            onHover.invoke(tooltip.apply(e), mouseX, mouseY);
         }

         out.add(new MapEntityRenderer.EntityRenderEntry(e, x, y, relationName));
      }

      draw.flush(graphics);
   }

   /** Hostile: diamond. Friendly: circle. Neutral: square (MIL-STD-2525 / NTDS shapes). */
   private static void frame(MapDraw draw, Relation relation, float x, float y, int argb) {
      float r = ICON_HALF + 3.0F;
      switch (relation) {
         case HOSTILE -> {
            draw.line(x, y - r, x + r, y, 1.0F, argb);
            draw.line(x + r, y, x, y + r, 1.0F, argb);
            draw.line(x, y + r, x - r, y, 1.0F, argb);
            draw.line(x - r, y, x, y - r, 1.0F, argb);
         }
         case NEUTRAL -> {
            float s = r * 0.8F;
            draw.line(x - s, y - s, x + s, y - s, 1.0F, argb);
            draw.line(x + s, y - s, x + s, y + s, 1.0F, argb);
            draw.line(x + s, y + s, x - s, y + s, 1.0F, argb);
            draw.line(x - s, y + s, x - s, y - s, 1.0F, argb);
         }
         default -> draw.arc(x, y, r * 0.9F, 0.0F, Mth.TWO_PI, 14, 1.0F, argb);
      }
   }

   /** Per-tick velocity: BVR phantoms carry it in the sync entry, real entities from their last tick's movement. */
   public static Vec3 velocityOf(Level level, Entity e) {
      if (level.getEntity(e.getId()) == null) {
         ClientSyncedEntityHandler.ClientSyncedEntity entry = ClientSyncedEntityHandler.getSyncedEntry(level, e.getId());
         if (entry != null) {
            return entry.getVelocity();
         }
      }

      return new Vec3(e.getX() - e.xo, e.getY() - e.yo, e.getZ() - e.zo);
   }

   private static Vec3 targetOf(Level level, MissileProjectile missile) {
      ClientSyncedEntityHandler.ClientSyncedEntity entry = ClientSyncedEntityHandler.getSyncedEntry(level, missile.getId());
      Vec3 target = entry == null ? null : entry.getTargetPos();
      return target != null ? target : missile.getTargetPos();
   }

   /** Height above ground when known (BVR sync carries it, or the column is loaded), else null. */
   private static String altitudeTag(Level level, Entity e, Vec3 pos) {
      ClientSyncedEntityHandler.ClientSyncedEntity entry = ClientSyncedEntityHandler.getSyncedEntry(level, e.getId());
      double agl = -1.0;
      if (entry != null && entry.getHeightAboveGround() >= 0.0) {
         agl = entry.getHeightAboveGround();
      } else if (level.hasChunk(Mth.floor(pos.x) >> 4, Mth.floor(pos.z) >> 4)) {
         agl = pos.y - level.getHeight(Heightmap.Types.WORLD_SURFACE, Mth.floor(pos.x), Mth.floor(pos.z));
      }

      return agl < 0.0 ? null : (int) agl + "m";
   }

   private static void targetLine(MapDraw draw, Vec3 target, float fromX, float fromY, Entity source, double viewX, double viewZ,
                                  float centerX, float centerY, float left, float top, float right, float bottom, double scale,
                                  boolean threat, long now) {
      float tx = (float) (centerX + (target.x - viewX) * scale);
      float ty = (float) (centerY + (target.z - viewZ) * scale);
      float dx = tx - fromX;
      float dy = ty - fromY;
      int rgb = threat ? THREAT_RGB : TARGET_LINE_RGB;
      if (dx * dx + dy * dy > 4.0F) {
         draw.dashed(fromX, fromY, tx, ty, 1.0F, 3.0F, 3.0F, MapDraw.withAlpha(rgb, 0.75F), left, top, right, bottom);
         if (tx >= left && tx <= right && ty >= top && ty <= bottom) {
            double dist = Math.sqrt(Mth.square(target.x - source.getX()) + Mth.square(target.z - source.getZ()));
            draw.text(formatDistance(dist), tx + 10.0F, ty + 6.0F, MapDraw.withAlpha(rgb, 1.0F), 1.0F, true);
         }
      }

      if (tx >= left - 16 && tx <= right + 16 && ty >= top - 16 && ty <= bottom + 16) {
         float breath = (float) (1.0 + 0.25 * Math.sin(now / 1000.0 * 4.0));
         draw.icon(TARGET_POS, tx, ty, 8.0F * breath, 0.0F, threat ? 0xFFFF6060 : 0xFFFFFFFF);
      }
   }

   /** Stand-alone {@code renderTargetPos} (SBW calls it outside the batch too, e.g. for the player's own missiles). */
   public static void renderTargetPos(GuiGraphics graphics, Vec3 target, double scale, float fromX, float fromY, Entity source,
                                      double viewX, double viewZ, float centerX, float centerY, int left, int top, int width, int height) {
      MapDraw draw = MapDraw.get();
      targetLine(draw, target, fromX, fromY, source, viewX, viewZ, centerX, centerY, left, top, left + width, top + height, scale, false,
         System.currentTimeMillis());
      draw.flush(graphics);
   }

   public static String formatDistance(double meters) {
      return meters >= 1000.0 ? String.format(java.util.Locale.ROOT, "%.1fkm", meters / 1000.0) : (int) meters + "m";
   }

   // ----------------------------------------------------------------------------------------------------------------
   // Radar picture: range rings + sweep
   // ----------------------------------------------------------------------------------------------------------------

   /** Drawn right after SBW's translucent radar sectors, i.e. underneath every contact. */
   public static void renderRadarOverlay(GuiGraphics graphics, Level level, double scale, float centerX, float centerY, double viewX, double viewZ) {
      Minecraft mc = Minecraft.getInstance();
      Player me = mc.player;
      if (me == null) {
         return;
      }

      MapDraw draw = MapDraw.get();
      long now = System.currentTimeMillis();
      rangeRings(draw, me, scale, centerX, centerY, viewX, viewZ);

      for (ClientSyncedEntityHandler.SyncedRadar radar : ClientSyncedEntityHandler.getSyncedRadars(level)) {
         float rx = (float) (centerX + (radar.getPos().x - viewX) * scale);
         float ry = (float) (centerY + (radar.getPos().z - viewZ) * scale);
         float radius = (float) (radar.getRadius() * scale);
         if (radius < 6.0F) {
            continue;
         }

         float sweep = (float) Mth.clamp(radar.getSweepAngle(), 0.0, 360.0) * Mth.DEG_TO_RAD;
         float start = (float) (radar.getYRot() - radar.getSweepAngle() / 2.0 - 90.0) * Mth.DEG_TO_RAD;
         boolean full = sweep >= Mth.TWO_PI - 0.01F;
         int edge = MapDraw.withAlpha(RADAR_RGB, 0.45F);
         if (!full) {
            draw.line(rx, ry, rx + Mth.cos(start) * radius, ry + Mth.sin(start) * radius, 1.0F, edge);
            draw.line(rx, ry, rx + Mth.cos(start + sweep) * radius, ry + Mth.sin(start + sweep) * radius, 1.0F, edge);
         }

         draw.arc(rx, ry, radius, start, sweep, Mth.clamp((int) (sweep * radius / 10.0F), 6, 72), 1.0F, edge);

         // Beam: scans back and forth across a sector, rotates on a 360° radar; trail fades behind it.
         long period = 2400L;
         float phase = (now + (radar.getSourceId() == null ? 0 : radar.getSourceId().hashCode() & 1023)) % period / (float) period;
         float beam;
         float trailDir;
         if (full) {
            beam = start + Mth.TWO_PI * phase;
            trailDir = -1.0F;
         } else {
            float tri = phase < 0.5F ? phase * 2.0F : 2.0F - phase * 2.0F;
            beam = start + sweep * tri;
            trailDir = phase < 0.5F ? -1.0F : 1.0F;
         }

         float trail = Math.min(18.0F * Mth.DEG_TO_RAD, sweep * 0.25F);
         int slices = 6;
         for (int s = 0; s < slices; s++) {
            float a0 = beam + trailDir * trail * s / slices;
            float a1 = beam + trailDir * trail * (s + 1) / slices;
            if (!full) {
               a0 = Mth.clamp(a0, start, start + sweep);
               a1 = Mth.clamp(a1, start, start + sweep);
            }

            float alpha = 0.30F * (1.0F - (float) s / slices);
            draw.triangle(rx, ry, MapDraw.withAlpha(RADAR_RGB, alpha), rx + Mth.cos(a0) * radius, ry + Mth.sin(a0) * radius,
               MapDraw.withAlpha(RADAR_RGB, alpha), rx + Mth.cos(a1) * radius, ry + Mth.sin(a1) * radius, MapDraw.withAlpha(RADAR_RGB, alpha * 0.6F));
         }

         draw.line(rx, ry, rx + Mth.cos(beam) * radius, ry + Mth.sin(beam) * radius, 1.5F, MapDraw.withAlpha(RADAR_RGB, 0.85F));
      }

      draw.flush(graphics);
   }

   private static void rangeRings(MapDraw draw, Player me, double scale, float centerX, float centerY, double viewX, double viewZ) {
      int step = RING_STEPS[RING_STEPS.length - 1];
      for (int s : RING_STEPS) {
         if (s * scale >= 70.0) {
            step = s;
            break;
         }
      }

      Entity body = me.getRootVehicle();
      float px = (float) (centerX + (body.getX() - viewX) * scale);
      float py = (float) (centerY + (body.getZ() - viewZ) * scale);
      int ringColor = MapDraw.withAlpha(RING_RGB, 0.28F);
      int labelColor = MapDraw.withAlpha(RING_RGB, 0.75F);
      for (int k = 1; k <= 8; k++) {
         float r = (float) (step * k * scale);
         if (r > 2400.0F) {
            break;
         }

         draw.ring(px, py, r, 1.0F, ringColor);
         draw.text(formatDistance(step * k), px + 3.0F, py - r - 9.0F, labelColor, 0.75F, false);
      }

      // Own heading line out to the first ring.
      float yaw = body.getYRot() * Mth.DEG_TO_RAD;
      float r1 = (float) (step * scale);
      draw.line(px, py, px - Mth.sin(yaw) * r1, py + Mth.cos(yaw) * r1, 1.0F, MapDraw.withAlpha(RING_RGB, 0.55F));
   }
}
