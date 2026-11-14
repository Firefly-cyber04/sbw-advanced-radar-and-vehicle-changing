package com.f16addon.client.map;

/** Shared palette of the tactical terminal (phosphor-green tactical display). */
public final class TerminalStyle {
   public static final int BG_TOP = 0xF6111B18;
   public static final int BG_BOTTOM = 0xF6070C0B;
   public static final int CARD = 0xC0101A16;
   public static final int CARD_HOVER = 0xD0173024;
   public static final int FRAME = 0xFF2C5B3C;
   public static final int FRAME_DIM = 0xFF1C3526;
   public static final int ACCENT = 0xFF6CFF9A;
   public static final int ACCENT_RGB = 0x6CFF9A;
   public static final int TEXT = 0xFFDDE7E0;
   public static final int DIM = 0xFF8FA39A;
   public static final int FAINT = 0xFF5D7067;

   public static final int HOSTILE = 0xFFFF5A5A;
   public static final int HOSTILE_AIR = 0xFFFFB347;
   public static final int FRIENDLY = 0xFF5AB4FF;
   public static final int NEUTRAL = 0xFFFFD24A;
   public static final int WARN = 0xFFFF3B3B;

   /** Frame colors of the map while a SBW interaction mode is active. */
   public static final int MODE_ATTACK = 0xFFFF4B4B;
   public static final int MODE_QUEUE = 0xFFFFA23B;
   public static final int MODE_CONNECT = 0xFF4FD8FF;

   private TerminalStyle() {
   }
}
