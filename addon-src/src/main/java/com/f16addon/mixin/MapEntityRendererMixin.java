package com.f16addon.mixin;

import com.atsuishio.superbwarfare.client.map.MapEntityRenderer;
import com.f16addon.client.map.ContactRenderer;
import java.util.List;
import kotlin.Unit;
import kotlin.jvm.functions.Function3;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tactical map contact rendering, batched (see {@link com.f16addon.client.map.MapDraw}) and with richer symbology.
 * SBW is already SRG-named and its own members are never obfuscated, so everything here is {@code remap = false}.
 */
@Mixin(value = MapEntityRenderer.class, remap = false)
public abstract class MapEntityRendererMixin {
   @Shadow
   private List<Component> buildEntityTooltip(Entity entity, Level level, String relationKey) {
      throw new AssertionError();
   }

   @Inject(method = "renderEntityBatch", at = @At("HEAD"), cancellable = true)
   private void f16addon$renderEntityBatch(GuiGraphics graphics, List<? extends Entity> entities, Level level, int tintColor,
                                           String relationKey, double viewBlockX, double viewBlockZ, float mapCenterX, float mapCenterY,
                                           int mapLeft, int mapTop, int mapAreaW, int mapAreaH, double scale, float partialTick,
                                           int mouseX, int mouseY, List<? extends Entity> selectedEntities,
                                           List<MapEntityRenderer.EntityRenderEntry> outEntries,
                                           Function3<? super List<? extends Component>, ? super Integer, ? super Integer, Unit> onHover,
                                           boolean isDraggingLoiter, double loiterDragNewX, double loiterDragNewZ, long loiterDragExpireTime,
                                           CallbackInfo ci) {
      ci.cancel();
      ContactRenderer.renderBatch((MapEntityRenderer) (Object) this, graphics, entities, level, tintColor, relationKey, viewBlockX, viewBlockZ,
         mapCenterX, mapCenterY, mapLeft, mapTop, mapAreaW, mapAreaH, scale, partialTick, mouseX, mouseY, selectedEntities, outEntries,
         onHover, e -> this.buildEntityTooltip(e, level, relationKey), isDraggingLoiter, loiterDragNewX, loiterDragNewZ, loiterDragExpireTime);
   }

   @Inject(method = "renderTargetPos", at = @At("HEAD"), cancellable = true)
   private void f16addon$renderTargetPos(Vec3 targetPos, double scale, float screenX, float screenY, GuiGraphics graphics, Entity entity,
                                         double viewBlockX, double viewBlockZ, float mapCenterX, float mapCenterY, int mapLeft, int mapTop,
                                         int mapAreaW, int mapAreaH, CallbackInfo ci) {
      ci.cancel();
      ContactRenderer.renderTargetPos(graphics, targetPos, scale, screenX, screenY, entity, viewBlockX, viewBlockZ, mapCenterX, mapCenterY,
         mapLeft, mapTop, mapAreaW, mapAreaH);
   }

   @Inject(method = "renderRadars", at = @At("RETURN"))
   private void f16addon$renderRadarOverlay(Level level, double scale, GuiGraphics graphics, float mapCenterX, float mapCenterY,
                                            double viewBlockX, double viewBlockZ, CallbackInfo ci) {
      ContactRenderer.renderRadarOverlay(graphics, level, scale, mapCenterX, mapCenterY, viewBlockX, viewBlockZ);
   }
}
