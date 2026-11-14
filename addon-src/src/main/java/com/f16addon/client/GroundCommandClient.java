package com.f16addon.client;

import net.minecraft.client.Minecraft;

public final class GroundCommandClient {
   private GroundCommandClient() {
   }

   public static void open() {
      Minecraft.getInstance().setScreen(new GroundCommandScreen());
   }
}
