package com.f16addon.client;

import com.f16addon.Classify;
import com.f16addon.F16Addon;
import com.f16addon.Sbw;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.fml.common.Mod;

/** RWR: warns a pilot when radar-guided AAMs are locked on them. */
@EventBusSubscriber(modid = F16Addon.MODID, value = Dist.CLIENT)
public final class RadarWarningHUD {
   private static final int SCAN_INTERVAL = 5;
   private static final double RANGE_SQR = 1024.0 * 1024.0;
   private static int incoming;
   private static double nearest;
   private static long lastScan = com.f16addon.core.TickMath.NEVER;

   private RadarWarningHUD() {
   }

   @SubscribeEvent
public static void onRenderGui(RenderGuiEvent.Post event) {
    Minecraft mc = Minecraft.getInstance();
    LocalPlayer player = mc.player;
    if (player == null || mc.level == null) {
        lastLevel = null;
        lastAircraft = -1;
        lastScan = com.f16addon.core.TickMath.NEVER;
        incoming = 0;
        return;
    }
    Entity vehicle = player.getVehicle();
    if (vehicle == null || vehicle.onGround() || !Sbw.isAircraft(vehicle)) {
        incoming = 0;
        lastAircraft = -1;
        return;
    }
    LivingEntity controller = vehicle.getControllingPassenger();
    if (controller != null && controller != player) {
        return;
    }
    long now = mc.level.getGameTime();
    if (lastLevel != mc.level || lastAircraft != vehicle.getId()) {
        lastLevel = mc.level;
        lastAircraft = vehicle.getId();
        lastScan = com.f16addon.core.TickMath.NEVER;
    }
    if (com.f16addon.core.TickMath.due(now, lastScan, SCAN_INTERVAL)) {
        lastScan = now;
        RadarWarningHUD.scan(mc.level, vehicle, (Player) player);
    }
    if (incoming == 0) {
        return;
    }
    GuiGraphics graphics = event.getGuiGraphics();
    int width = 230;
    int height = 34;
    int x = (mc.getWindow().getGuiScaledWidth() - width) / 2;
    int y = 58;
    boolean flash = now / 8L % 2L == 0L;
    int border = flash ? -53200 : -32640;
    graphics.fill(x, y, x + width, y + height, -803209216);
    graphics.fill(x, y, x + width, y + 1, border);
    graphics.fill(x, y + height - 1, x + width, y + height, border);
    graphics.fill(x, y, x + 1, y + height, border);
    graphics.fill(x + width - 1, y, x + width, y + height, border);
    graphics.drawCenteredString(mc.font, "\u26a0 \u96f7\u8fbe\u5f39\u9501\u5b9a  " + incoming + " \u679a", x + width / 2, y + 7, border);
    graphics.drawCenteredString(mc.font, "\u6700\u8fd1 " + (int) nearest + "m  \u00b7  \u7acb\u5373\u673a\u52a8 / \u91ca\u653e\u5e72\u6270\u5f39", x + width / 2, y + 20, 0xFFFFAA);
}


   private static void scan(ClientLevel level, Entity aircraft, Player pilot) {
       incoming = 0;
       nearest = Double.MAX_VALUE;
       String selfId = aircraft.getStringUUID();
       String pilotId = pilot.getStringUUID();
       for (Entity entity : ClientMissileIndex.missiles(level)) {
           String target;
           double d;
           if (!Classify.is(entity, 2) || (d = entity.distanceToSqr(aircraft)) > 1048576.0 || !selfId.equals(target = Sbw.targetUuid(entity)) && !pilotId.equals(target))
               continue;
           ++incoming;
           nearest = Math.min(nearest, Math.sqrt(d));
       }
   }

   private static ClientLevel lastLevel;

   private static int lastAircraft = -1;

   public static void reset() {
       lastLevel = null;
       lastAircraft = -1;
       lastScan = com.f16addon.core.TickMath.NEVER;
       incoming = 0;
       nearest = Double.MAX_VALUE;
   }
}
