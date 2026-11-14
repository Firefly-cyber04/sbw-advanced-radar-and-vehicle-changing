package com.f16addon.client;

import com.f16addon.F16Addon;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;

/**
 * Fix for "the view briefly goes out of control while flying".
 *
 * <p>Superb Warfare flies aircraft from {@code ClientMouseHandler.handleClientTick}: every client tick it takes the
 * <i>absolute</i> cursor position ({@code posN}), subtracts last tick's ({@code posO}), clamps the difference to
 * ±256 px and feeds it through a slow low-pass filter ({@code lerpSpeedX/Y}, rate ≥ 0.12 per tick) that is sent to
 * the server as stick input. The cursor position, however, is not continuous:
 * <ul>
 *    <li>while the mouse is grabbed it is GLFW's unbounded virtual position ({@code MouseHandler.xpos});</li>
 *    <li>while a screen is open it is the real cursor position, and Minecraft re-centres the cursor every time it
 *        grabs / releases the mouse.</li>
 * </ul>
 * Opening or closing any screen (the tactical terminal, chat, inventory, pause menu, alt-tab ...) therefore produces a
 * jump of up to 256 px in one tick. While the screen is open SBW only sends zero input, but it keeps updating the
 * filter from the cursor moving around the screen; the moment the screen closes the accumulated value is sent and
 * decays over 1-2 seconds — the aircraft (and the camera locked to it) pitches / rolls on its own.
 *
 * <p>This guard runs right before SBW's handler (HIGHEST priority, same END phase) and, whenever the input is not
 * continuous, re-bases {@code posN} on the current cursor position and zeroes the filter state, so the first tick
 * after any discontinuity produces exactly zero stick movement.
 */
@EventBusSubscriber(modid = F16Addon.MODID, value = Dist.CLIENT)
public final class FlightInputGuard {
   private static final String CLIENT_MOUSE_HANDLER = "com.atsuishio.superbwarfare.event.ClientMouseHandler";
   private static final String MOUSE_MOVEMENT_HANDLER = "com.atsuishio.superbwarfare.client.MouseMovementHandler";
   /** Filter / velocity state fed into MouseMoveMessage; all public static double fields of the Kotlin object. */
   private static final String[] FILTER_FIELDS = {
      "speedX", "speedY", "lerpSpeedX", "lerpSpeedY", "mouseXMoveTick", "mouseYMoveTick", "lerpNacelleSpeedX", "lerpNacelleSpeedY"
   };

   private static boolean resolved;
   private static Field posN;
   private static Field posO;
   private static Field[] filterFields;
   private static Method getMousePos;
   /** {@code MouseHandlerMixin} keeps last frame's turn deltas in two @Unique statics merged into MouseHandler. */
   private static Field[] mixinTurnFields;

   private static boolean wasLive;
   private static Entity lastVehicle;

   private FlightInputGuard() {
   }

   @SubscribeEvent(priority = EventPriority.HIGHEST)
   public static void onClientTick(TickEvent.ClientTickEvent event) {
      if (event.phase != TickEvent.Phase.END) {
         return;
      }

      Minecraft mc = Minecraft.getInstance();
      LocalPlayer player = mc.player;
      boolean live = player != null
         && mc.screen == null
         && mc.getOverlay() == null
         && mc.mouseHandler.isMouseGrabbed()
         && mc.isWindowActive();
      Entity vehicle = player == null ? null : player.getVehicle();
      if (!live || !wasLive || vehicle != lastVehicle) {
         reset();
      }

      wasLive = live;
      lastVehicle = vehicle;
   }

   /** Makes SBW's next delta zero and drops whatever its low-pass filter had accumulated. */
   static void reset() {
      if (!resolve()) {
         return;
      }

      try {
         Object now = getMousePos.invoke(null);
         if (now != null) {
            posN.set(null, now);
            posO.set(null, now);
         }

         for (Field f : filterFields) {
            f.setDouble(null, 0.0);
         }

         for (Field f : mixinTurnFields) {
            f.setDouble(null, 0.0);
         }
      } catch (ReflectiveOperationException | RuntimeException e) {
         // SBW internals changed: disable the guard instead of throwing every tick.
         F16Addon.LOGGER.warn("[Enemy Forces] Flight input guard disabled: {}", e.toString());
         getMousePos = null;
      }
   }

   private static boolean resolve() {
      if (resolved) {
         return getMousePos != null;
      }

      resolved = true;
      try {
         ClassLoader loader = FlightInputGuard.class.getClassLoader();
         Class<?> handler = Class.forName(CLIENT_MOUSE_HANDLER, false, loader);
         Class<?> movement = Class.forName(MOUSE_MOVEMENT_HANDLER, false, loader);
         posN = handler.getField("posN");
         posO = handler.getField("posO");
         filterFields = new Field[FILTER_FIELDS.length];
         for (int i = 0; i < FILTER_FIELDS.length; i++) {
            filterFields[i] = handler.getField(FILTER_FIELDS[i]);
         }

         getMousePos = movement.getMethod("getMousePos");
         mixinTurnFields = findMixinTurnFields();
         return true;
      } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
         F16Addon.LOGGER.warn("[Enemy Forces] Superb Warfare mouse handler not found, flight input guard inactive: {}", e.toString());
         getMousePos = null;
         return false;
      }
   }

   private static Field[] findMixinTurnFields() {
      java.util.List<Field> found = new java.util.ArrayList<>(2);
      for (Field f : MouseHandler.class.getDeclaredFields()) {
         String name = f.getName();
         if (Modifier.isStatic(f.getModifiers()) && f.getType() == double.class && (name.endsWith("sbw$x") || name.endsWith("sbw$y"))) {
            try {
               f.setAccessible(true);
               found.add(f);
            } catch (RuntimeException ignored) {
            }
         }
      }

      return found.toArray(new Field[0]);
   }
}
