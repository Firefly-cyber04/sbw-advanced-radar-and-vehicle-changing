package com.f16addon;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * World queries that never load chunks.
 *
 * <p>On the server, {@code Level.getHeight} and {@code Level.clip} call {@code getChunk(x, z, FULL, true)}, which
 * <b>synchronously loads or even generates</b> the chunk on the main thread when it is not loaded. 1.0.0 did that
 * every tick: jets probed terrain 50 blocks ahead at 5 blocks/tick, ground units snapped to the heightmap of the next
 * step, and the SAM raycast up to 1000 blocks to every candidate. Any of those crossing into unloaded terrain caused a
 * multi-millisecond to multi-second server stall — the main "卡顿" source.
 */
public final class WorldQueries {
   private WorldQueries() {
   }

   public static boolean isLoaded(ServerLevel level, double x, double z) {
      return level.getChunkSource().hasChunk(Mth.floor(x) >> 4, Mth.floor(z) >> 4);
   }

   /** Entity-ticking means vanilla would tick an entity here; AI outside that area would act on frozen chunks. */
   public static boolean isEntityTicking(ServerLevel level, double x, double y, double z) {
      return isLoaded(level, x, z) && level.isPositionEntityTicking(BlockPos.containing(x, y, z));
   }

   public static boolean isEntityTicking(ServerLevel level, Entity entity) {
      return level.isPositionEntityTicking(entity.blockPosition());
   }

   /** Heightmap value, or {@code fallback} if the column is not loaded. */
   public static int height(ServerLevel level, Heightmap.Types type, double x, double z, int fallback) {
      int bx = Mth.floor(x);
      int bz = Mth.floor(z);
      if (!level.getChunkSource().hasChunk(bx >> 4, bz >> 4)) {
         return fallback;
      }

      return level.getHeight(type, bx, bz);
   }

   public static double heightAboveGround(ServerLevel level, Entity entity) {
      int ground = height(level, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, entity.getX(), entity.getZ(), level.getMinBuildHeight());
      return entity.getY() - ground;
   }

   /** Every chunk column crossed by the horizontal projection of the segment is loaded (2D DDA on the chunk grid). */
   public static boolean segmentLoaded(ServerLevel level, Vec3 from, Vec3 to) {
      int cx = Mth.floor(from.x) >> 4;
      int cz = Mth.floor(from.z) >> 4;
      int ex = Mth.floor(to.x) >> 4;
      int ez = Mth.floor(to.z) >> 4;
      double dx = to.x - from.x;
      double dz = to.z - from.z;
      int stepX = dx > 0 ? 1 : (dx < 0 ? -1 : 0);
      int stepZ = dz > 0 ? 1 : (dz < 0 ? -1 : 0);
      double tDeltaX = stepX == 0 ? Double.POSITIVE_INFINITY : 16.0 / Math.abs(dx);
      double tDeltaZ = stepZ == 0 ? Double.POSITIVE_INFINITY : 16.0 / Math.abs(dz);
      double tMaxX = stepX == 0 ? Double.POSITIVE_INFINITY : (stepX > 0 ? ((cx + 1) * 16.0 - from.x) : (from.x - cx * 16.0)) / Math.abs(dx);
      double tMaxZ = stepZ == 0 ? Double.POSITIVE_INFINITY : (stepZ > 0 ? ((cz + 1) * 16.0 - from.z) : (from.z - cz * 16.0)) / Math.abs(dz);
      int steps = Math.abs(ex - cx) + Math.abs(ez - cz) + 1;

      for (int i = 0; i < steps; i++) {
         if (!level.getChunkSource().hasChunk(cx, cz)) {
            return false;
         }

         if (cx == ex && cz == ez) {
            return true;
         }

         if (tMaxX < tMaxZ) {
            cx += stepX;
            tMaxX += tDeltaX;
         } else {
            cz += stepZ;
            tMaxZ += tDeltaZ;
         }
      }

      return true;
   }

   /**
    * Block line of sight. Returns {@code false} (not visible) instead of loading chunks when the ray would cross
    * unloaded terrain.
    */
   public static boolean lineOfSight(ServerLevel level, Vec3 from, Vec3 to, Entity self) {
      if (!segmentLoaded(level, from, to)) {
         return false;
      }

      BlockHitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, self));
      return hit.getType() == HitResult.Type.MISS || hit.getLocation().distanceToSqr(from) >= from.distanceToSqr(to) - 1.0;
   }

   public static Vec3 center(Entity entity) {
      return entity.position().add(0.0, entity.getBbHeight() * 0.5, 0.0);
   }
}
