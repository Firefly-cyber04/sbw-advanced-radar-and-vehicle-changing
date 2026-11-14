package com.f16addon;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Item.Properties;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModItems {
   public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, "f16addon");
   public static final RegistryObject<Item> ENEMY_F16_DEPLOYER = ITEMS.register(
      "enemy_f16_deployer", () -> new EnemyF16DeployerItem(new Properties().stacksTo(1))
   );
   public static final RegistryObject<Item> ENEMY_BMPT72_DEPLOYER = ITEMS.register(
      "enemy_bmpt72_deployer",
      () -> new EnemyGroundDeployerItem(
            new Properties().stacksTo(1),
            "bmpt72",
            "Enemy BMPT-72",
            "enemy_bmpt72",
            "small_shell_ap",
            "small_shell_he",
            "rifle_ammo",
            "medium_anti_ground_missile"
         )
   );
   public static final RegistryObject<Item> ENEMY_T80_DEPLOYER = ITEMS.register(
      "enemy_t80_deployer",
      () -> new EnemyGroundDeployerItem(
            new Properties().stacksTo(1), "t80", "Enemy T-80", "enemy_t80", "large_shell_ap", "large_shell_he", "rifle_ammo", "heavy_ammo"
         )
   );
   public static final RegistryObject<Item> ENEMY_WASP_DEPLOYER = ITEMS.register(
      "enemy_wasp_deployer", () -> new EnemySamDeployerItem(new Properties().stacksTo(1), "akm", "Enemy Wasp SAM", "enemy_wasp")
   );
   public static final RegistryObject<Item> GROUND_COMMAND_TABLET = ITEMS.register(
      "ground_command_tablet", () -> new GroundCommandItem(new Properties().stacksTo(1))
   );
   public static final RegistryObject<Item> TARGETING_POD = ITEMS.register("f16_targeting_pod", () -> new TargetLockItem(new Properties().stacksTo(1)));

   private ModItems() {
   }
}
