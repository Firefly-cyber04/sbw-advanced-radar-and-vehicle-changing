package com.f16addon.client;

import com.atsuishio.superbwarfare.client.ClientSyncedEntityHandler;
import com.f16addon.Classify;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Event-maintained local missile set shared by HUD and tactical panel. */
@Mod.EventBusSubscriber(modid = "f16addon", value = Dist.CLIENT)
public final class ClientMissileIndex {
    private static ClientLevel current;
    private static final Set<Entity> LOCAL = Collections.newSetFromMap(new IdentityHashMap<>());
    private ClientMissileIndex() {}

    private static void world(ClientLevel level) {
        if (current == level) return;
        LOCAL.clear(); current = level;
        for (Entity e : level.entitiesForRendering()) if (Classify.is(e, Classify.MISSILE)) LOCAL.add(e);
    }

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ClientLevel level)) return;
        world(level);
        if (Classify.is(event.getEntity(), Classify.MISSILE)) LOCAL.add(event.getEntity());
    }

    @SubscribeEvent
    public static void onLeave(EntityLeaveLevelEvent event) {
        if (event.getLevel() == current) LOCAL.remove(event.getEntity());
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        LOCAL.clear(); current = null;
        RadarWarningHUD.reset();
        com.f16addon.client.map.SituationPanel.reset();
    }

    public static List<Entity> missiles(ClientLevel level) {
        world(level);
        LOCAL.removeIf(e -> e.isRemoved() || e.level() != level);
        List<Entity> result = new ArrayList<>(LOCAL);
        Set<Integer> seen = new HashSet<>();
        for (Entity e : result) seen.add(e.getId());
        for (Entity e : ClientSyncedEntityHandler.getSyncedWorldRenderEntities((Level) level)) {
            if (Classify.is(e, Classify.MISSILE) && !e.isRemoved() && seen.add(e.getId())) result.add(e);
        }
        return result;
    }
}
