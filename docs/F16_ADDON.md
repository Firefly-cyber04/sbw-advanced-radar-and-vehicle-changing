# 敌方 F-16 附属模组（独立 Forge Addon）

## 1. 为什么没有直接写进 Doggies

直接改 Doggies 的 `ModItems` / `ModEntities` / 客户端渲染也能做，但：

- Doggies 更新会整个覆盖；
- 新 `EntityType` 要同时注册实体、刷怪蛋、渲染器、车辆 JSON、AI 适配器；
- 和 DragonRise 的 F-16C 数据容易变成两份。

所以做成独立附属模组：**不碰 Doggies 本体**，只注册一个召唤道具，生成时复用 DragonRise 的 `dragonrise_reforge:f16c`。

## 2. 做了什么

- 模组 ID：`f16addon`
- 道具：`f16addon:enemy_f16_deployer`（敌方 F-16 召唤器）
- 右键地面/空中生成一架 `dragonrise_reforge:f16c`：
  - 自定义名 `Enemy F-16`
  - 标签 `enemy_f16`
  - 加入红色 `enemy` 队伍，关闭友伤
  - 预装机炮/AIM-9/AIM-120/AGM/炸弹弹药
  - 给玩家一个 `doggiesnewvehicle:vehicle_crew_core`
- 无限电量：服务端每 tick 把 `enemy_f16` 的能量补满（不依赖改 F16C JSON）
- 内置 Doggies AI 适配器：
  `data/f16addon/dnv/vehicle_crew_adapters/sbw_0_8_9_1/f16c.json`
- 飞行参数、模型、血量、武器完全来自 DragonRise 的 F-16C。

## 3. 使用

1. 把 `f16addon-1.0.0.jar` 放进 `mods/`，启动游戏。
2. 创造模式战斗栏里拿“敌方 F-16 召唤器”，或：
   ```mcfunction
   /give @p f16addon:enemy_f16_deployer
   ```
3. 右键地面/空中生成。
4. **潜行 + 右键** F-16，用 `Vehicle Crew Core` 绑定炮位/座位。
5. 再**右键**核心打开 UI，把飞行模式设为 AI，配置武器站交战策略。
6. AI 会自动起飞、盘旋、对地投弹/对地导弹、对空 AAM + 机炮。

## 4. 说明

- 召唤出来的是**现有 F-16C 实体**，不是新的 `EntityType`。如果你要独立单位 ID/刷怪蛋（例如 `f16addon:enemy_f16`），需要再注册一个实体类型并复制 F-16C 的车辆 JSON/渲染，工作量更大。
- 核心绑定/模式配置仍需玩家操作一次；数据包/道具无法模拟“潜行右键绑定核心”。要全自动需要写程序化安装 Doggies 核心的代码。
- 如果 Doggies 日志出现 `LAYOUT_CHANGED`，把实际指纹填进内置适配器 JSON（当前 `3549237616`）。
- 旧的数据包版本已删除，避免和内置适配器重复。

## 5. 文件

- 构建工程：`D:\f16addon`
- 成品：`D:\f16addon\build\libs\f16addon-1.0.0.jar`
- 已安装：`C:\Users\12051\Desktop\.minecraft\versions\1.20.1-\mods\f16addon-1.0.0.jar`
- 源码：`D:\f16addon\src\main\java\com\f16addon\`

## 6. 21:22 崩溃原因与修复

日志里的 `EXCEPTION_ACCESS_VIOLATION` 不是普通 Java 异常，是 JVM 原生崩溃。关键栈：

```
ClientSyncedEntityHandler.syncWorldRender
  VehicleEntity.getGunDataMap
    DefaultGunData$$serializer.deserialize
      AmmoConsumer$$serializer.deserialize
        GsonObjectSerializer.deserialize
          JsonUtil.convertKxJsonToGson
            AbstractJsonLexer.consumeNumericLiteral
              JsonDecodingException.<init>
                Throwable.fillInStackTrace
                  jvm.dll+0x39388e  (EXCEPTION_ACCESS_VIOLATION)
```

根因：

- 新生成的 F-16 是 `EntityType.create()` 直接创建的，**同步数据里的 `GUN_DATA_MAP` 是空的**；
- 客户端 `getGunDataMap()` 对每个武器走 `GunData.from(...)` 默认数据回退；
- F-16 的 `AmmoConsumer` 数据触发 `JsonDecodingException`，Java 17.0.8 在 `fillInStackTrace` 时原生崩溃。

修复：

- 召唤器生成后立刻 `getGunDataMap()` + `setGunDataMap(...)`，把完整炮数据写入同步数据；
- 服务端 tick 对已存在的 `enemy_f16` 补一次初始化（`f16addon_gun_init` 标记，失败则 `f16addon_gun_failed`）；
- 初始化失败时会丢弃该 F-16 并在日志打印 `Failed to initialize F-16 GunDataMap`，避免再次触发 JVM 崩溃。

`crusty_chunks:assembly` 的 recipe 报错只是配方类型警告，和这次崩溃无关。

## 7. 飞控（自定义，不依赖 Doggies 核心）

Doggies 的 `Vehicle Crew Core` 只有武器站，`resolveFlightActuator()` 对 SBW 载具始终返回空，所以核心 UI 里没有起飞。现在 `f16addon` 自己带一套服务端飞控。

```text
地面/低空 -> 垂直起飞到 homeY+70
         -> 盘旋：home 为中心，半径 80，高度 homeY+100
         -> 发现目标：空中目标优先，进入战斗
```

### 7.1 平滑重构

旧版是“每 tick 直接朝目标瞬移 + 瞬间转头”，所以盘旋和攻击会抖。现在改为：

- 持久化速度，按 tick 逐渐加速/减速（不再直接跳到目标）
- 限制转向速率：巡逻 2.6°/tick，战斗 3.2°/tick，脱离 4.2°/tick
- 盘旋沿**轨道切线**飞，只用少量径向修正回轨道，不再直冲轨道点
- 目标锁定 20 tick，不每 tick 重新选最近目标
- 近距离进入 32 tick 的脱离/延伸段，再重新进入攻击，避免原地打转
- 位置沿当前机头方向积分，速度和机头方向一致
- 每 tick 把 deltaMovement 清零，避免 SBW 载具体物理再叠加一次位移

### 7.2 目标与武器

- 扫描半径 256 格
- 排除：自己、`enemy_f16`、旁观者、掉落物/经验球/盔甲架/区域效果云/悬挂实体/投掷物、同队
- **玩家优先**：扫描时先选最近的 `Player`，即使旁边的怪/载具更近；没有玩家才选空中/地面目标
- **空中优先级**：先导弹锁定，近距离才机炮
  - 距离 > 95：AIM-9（≤160）/ AIM-120（≤500）锁定
  - 距离 ≤ 95 且机头对准：机炮优先；机炮冷却时才补导弹
- **地面优先级**：先炸弹/对地导弹，机炮只补刀
  - 高于目标 30 格且距离 <75：MK-82 炸弹
  - 炸弹冷却/不满足：KH-39 对地导弹锁定（≤220）
  - 两者都不可用：机炮（≤125 且机头对准）
- 如果载具容器里有 `vehicle_crew_core`，飞控只负责飞，武器交给 Doggies 核心
- 没有核心时，飞控直接发射 F-16 对应武器

### 7.3 速度与导弹锁定 UI

速度区间：**最低 240 km/h，最高 360 km/h**：

- 盘旋 `3.4` 格/tick ≈ 244.8 km/h
- 战斗 `4.5` 格/tick ≈ 324 km/h
- 最高 `5.0` 格/tick = 360 km/h
- 脱离 3.0 格/tick ≈ 216 km/h
- 盘旋角速度同步提高，避免提速后飞出轨道
- 起飞离地后速度直接设为 3.4 格/tick，不再从低速慢慢加速

盘旋参数：

- 最小盘旋半径：**100 格**
- 最小离地高度：**50 格**（目标高度 = 地面 +55）
- 普通目标索敌距离：**1024 格**
- 五代机（J-20/J-20C/J-35/F-22/F-35/Su-57/FC-31/TJGC）：**512 格**
- 扫描间隔：40 tick；新目标反应延迟 15 tick
- 每次扫描有 **30%** 概率突然丢失目标，丢失后至少 2 秒不重新索敌

新增道具 **`f16addon:f16_targeting_pod`（F-16 导弹锁定器）**：

- 右键打开目标选择界面
- 列出 512 格内最多 8 个目标，排序：**玩家 → 空中 → 地面 → 距离**
- 每行标注 `[玩家]`、`[空中]/[地面]`、距离，以及推荐武器：
  - 空中：`AIM-9/120`
  - 地面：`炸弹/AGM`
- 点击目标：附近 512 格内所有 `enemy_f16` 把它写入 `f16_locked_target`，飞控优先攻击
- “清除锁定”：恢复自动索敌
- 手动锁定可以选任意目标；自动索敌才是玩家优先
- 也可以直接用命令：
  ```mcfunction
  /f16addon lock <目标UUID>
  /f16addon clear
  ```

锁定目标死亡、消失或超过 512 格时，飞控会自动清除锁定并恢复自动扫描。

### 7.4 AIM-9 / AIM-120 空中锁定

以前只是朝目标方向发射，导弹没有写入锁定实体，所以不会真正跟踪。现在发射 AIM-9/AIM-120 时会：

- `setTargetUUID(target)`：导弹同步数据里写入目标 UUID
- `setTargetPos(target.position())`：写入目标位置，作为丢失目标后的惯性参考
- `setGuideType(0)`：强制走“目标 UUID 主动制导”，不是无制导

对应关系：

- AIM-9：`superbwarfare:ru_9m336_missile`（F-16 JSON 里的 AIM-9 条目）
- AIM-120：`dragonrise_reforge:aim120`

只有 **空中目标** 才触发 AAM 锁定：

- 玩家使用鞘翅飞行
- 玩家驾驶的空中载具（`VehicleEntity` 不在地面且高于 F-16）
- 其他飞行中的空中单位

地面目标不会浪费 AIM-9/AIM-120，仍走 KH-39/MK-82/机炮。KH-39 对地导弹现在也会写入目标 UUID/位置，所以是“对地导弹锁定”，不是无制导直飞。导弹锁定后由导弹自己的寻的逻辑跟踪；目标死亡/丢失后按导弹自身的惯性/近炸逻辑处理。

### 7.5 起落架 / 干扰弹 / 残骸状态

**自动收放起落架**

- 起飞到达 `homeY + 70` 后，飞控调用 `setGearUp(true)` 自动收起起落架
- 只有一次；不会在空中反复收放
- 如果载具没有起落架 API，飞控仍正常飞

**导弹受干扰弹影响**

- 飞控发射的 AIM-9/AIM-120 会打上 `f16_aam` 标签
- `F16MissileDefense` 每 10 tick 检查一次导弹锁定目标周围 48 格
- 发现 `FlareDecoyEntity` / `SmokeDecoyEntity` 时：
  - 基础干扰概率 32%，每多一个干扰弹 +10%，最高 75%
  - 命中干扰后把导弹 `TARGET_UUID` 改到干扰弹、`TARGET_POS` 改到干扰弹位置
  - 标记 `setDistracted(true)`，导弹丢失原目标，干扰弹消失后按惯性飞
- 只影响 AIM-9/AIM-120，不影响 KH-39 对地导弹、炸弹、机炮
- 除了飞控自己打的 `f16_aam`，也会识别**任何由敌方 F-16 发射的 Aim9/Aim120/Ru9m336**（包括 Doggies 核心发射的），所以装核心时干扰弹同样有效

**血量 0 后的第二状态**

- 飞控每 tick 检查 `isWreck()`，并兜底检查 `getHealth() <= 0`
- 进入残骸状态后：
  - 不再执行起飞/盘旋/战斗/位置积分
  - 不再发射任何武器
  - 清除锁定目标
  - 不再补电量/初始化炮数据
  - 只把速度清零一次，之后交给 SBW 残骸物理，不再干预

### 7.6 飞控 / UI 优化

**飞控**

- 地形前视：不再只看脚下，而是沿机头方向前视 `max(24, 速度×10)` 格
- 前方地形高于安全高度时，提前加入爬升分量；脚下高度不足时改为平滑爬升，只有一 tick 内爬不出去才硬抬升
- 转向时按偏航角速度压坡度（`getRoll/setRoll/setPrevRoll` 反射调用），盘旋和攻击不再平飞转弯
- 巡逻/战斗速度、最小离地 50 格、半径 100 格保持不变

**飞行模式**

飞控持久数据 `f16_mode`：

- `auto`：自动索敌/攻击
- `patrol`：只巡逻盘旋，不攻击
- `return`：返回出生点盘旋（不降落）

命令会作用于 512 格内所有敌方 F-16：

```mcfunction
/f16addon mode auto
/f16addon mode patrol
/f16addon mode return
```

**UI**

`f16_targeting_pod` 右键界面改为火控页：

- 顶部显示飞行模式、显示目标数/总数、512 格范围
- 飞行模式按钮：自动 / 巡逻 / 返航
- 目标分类：全部 / 空中 / 地面 / 玩家
- 目标列表仍按 玩家 → 空中 → 地面 → 距离 排序
- 增加“刷新”和“清除锁定”
- 手动锁定目标仍写入 `f16_locked_target`

### 7.7 玩家飞机单兵战术终端

不再添加自定义雷达/火控面板；玩家驾驶 `AIRPLANE / HELICOPTER / AIRSHIP / DRONE` 时，右键 `f16_targeting_pod` 会直接打开：

```text
com.atsuishio.superbwarfare.client.screens.TacticalMapScreen
```

也就是 SBW **单兵战术终端 / 战术地图**的原版界面：

- 地图、实体标记、队伍/载具信息
- 选择目标、标记、导弹打击等原版交互
- 用反射打开，不需要在 addon 里重新实现地图

不在飞行载具里时，同一个道具仍打开原来的 AI F-16 控制面板（飞行模式/目标列表）。

**战术地图雷达**

两个 jar 里共 54 架飞行载具（SBW 12 + DragonRise 42）的载具 JSON 都补了 `Radar` 数据：

```json
"Radar": {
  "Direction": "Default",
  "Range": 1500.0,
  "Angle": 120.0,
  "RotateSpeed": 10.0,
  "ShareWithTeammates": true,
  "MaxTargetHeight": 114514.0,
  "MinTargetHeight": -64.0,
  "AffectedByStealthTarget": true
}
```

`vehicleRadar()` 会按机头方向生成 `SyncedRadar`，战术地图用 `ClientSyncedEntityHandler.getSyncedRadars()` 画出飞机雷达锥：

- 机头朝向
- **Airplane：1500 格**
- **Helicopter：512 格**
- 120° 扫描角
- 只扫描载具类目标
- 雷达图标/锥形会显示在战术地图上

AI F-16 对普通目标索敌 **1024 格**，对五代机保持 **512 格**。

### 7.8 投掷物寿命：普通 25 秒 / 口径 60 秒

服务端每 tick 扫描全部 `Projectile`：

- 普通投掷物：`tickCount > 500`（25 秒 @20 TPS）→ `discard()`
- 口径导弹 `Ru3m14MissileEntity`：保留 `tickCount > 1200`（60 秒）
- 覆盖箭、投掷物、导弹、炸弹、炮弹、火箭等所有 projectile
- 只处理服务端已加载实体
- 先收集到期实体，遍历结束后统一 discard，避免边遍历边删
- 同时 SBW `FastThrowableProjectile.m_8119_` 写入相同规则，DragonRise 投掷物继承该类，所以两个 mod 自身也生效
- addon 的全 `Projectile` 扫描作为覆盖其他模组的双保险

常数位置：

```java
F16Addon.PROJECTILE_MAX_LIFETIME = 500
F16Addon.KALIBR_MAX_LIFETIME = 1200
```

要改普通投掷物时间：25 秒 = 500，30 秒 = 600，60 秒 = 1200。

### 7.9 所有制导导弹随机脱锁（含远距离/尾后加成）

直接写进 SBW `MissileProjectile.m_8119_`，DragonRise 的 AIM-9/120、PL-10/12/15、R-73 等继承该基类，所以两个 mod 都生效。

每 20 tick（1 秒）检查一次：

- 只处理 `TARGET_UUID` 非空且不是 `none` 的导弹
- 基础脱锁率 **1%/秒**
- 距离加成：
  ```text
  + min(距离 / 1024, 1) * 3%/秒
  ```
  即 1024 格时额外 +3%/秒
- 尾后加成：
  ```text
  dot(机头, 目标方向) < -0.3  → 再 +2%/秒
  ```
- 总范围：近距离正前约 1%/秒，远距离尾后最高约 6%/秒

脱锁时：

```text
setTargetVec(最后目标位置)
→ TARGET_UUID = none
→ 导弹飞向最后已知点，不再持续跟踪
setLostTarget(true)
```

补丁位置：

- `FastThrowableProjectile.m_8119_`：1200 tick 硬超时，SBW/DragonRise 全部投掷物生效
- `MissileProjectile.m_8119_`：调用 `f16addon$lockCheck`

AI F-16 对普通目标为 **1024 格**（`SCAN_RANGE`），对五代机为 **512 格**（`FIFTH_GEN_RANGE`）。

### 7.10 敌对 F-16 独立化与武器调整

- 移除了 Doggies `vehicle_crew_core` 依赖、`hasCrewCore` 检查、crew adapter 和 `mods.toml` 依赖
- 现在 F-16 addon 只依赖 `superbwarfare` + `dragonrise_reforge`，不需要 Doggies
- F-16 雷达弹发射距离：**500 格**
- 雷达弹使用 `dragonrise_reforge:aim120`
- 锁定代码保持 DragonRise 一致：
  ```text
  setTargetUUID
  setTargetPos(target center)
  setGuideType(0)
  ```
- F-16 在被雷达/红外 AAM 锁定时有概率释放干扰弹：
  - 每 10 tick 检查一次
  - 50% 概率释放
  - 40 tick 冷却

### 7.11 雷达弹锁定告警与红外弹 8 秒

- 新增客户端 `RadarWarningHUD`
- 玩家驾驶飞行载具时，若 `Aim120 / Pl12 / Pl15` 的 `TARGET_UUID` 是自己，屏幕顶部显示红色雷达弹告警、数量和最近距离
- 只对雷达弹告警；红外弹不触发
- 红外空空导弹寿命改为 **160 tick（8 秒）**：
  - AIM-9 / PL-10 / PL-8 / R-73 / `Ru9m336`
  - 由 addon 全局清理覆盖，backup SBW 或当前 SBW 都生效

### 7.12 导弹过载调整

雷达弹在 `10.5` 基础上再砍 25%：

| 导弹 | 原版 | 现在 |
|---|---|---|
| AIM-120 / PL-12 / PL-15（雷达） | 25 | **7.875** |
| AIM-9 / PL-10 / PL-8 / R-73 / FIM-92（红外） | 15 | **17.5** |
| Igla / 9M100（红外） | 15 | **17.5** |

### 7.13 敌方地面单位

新增：

- `f16addon:enemy_bmpt72_deployer`
- `f16addon:enemy_t80_deployer`

生成实体：

- `dragonrise_reforge:bmpt72`
- `dragonrise_reforge:t80`

部署行为：

- 红色 `enemy` 队伍
- 标签 `enemy_ground` + `enemy_bmpt72` / `enemy_t80`
- 自定义名称 `Enemy BMPT-72` / `Enemy T-80`
- 自动初始化 GunDataMap
- 自动装填：
  - BMPT-72：30mm AP/HE、步枪弹、反坦克导弹
  - T-80：125mm AP/HE、步枪弹、重机枪弹
- 不需要 Doggies crew core

物品图标改为原版纹理，不再引用 `doggiesnewvehicle:item/dnv_icon`。

**地面 AI 与 UI**

- 新增 `f16addon:ground_command_tablet`（地面单位指挥终端）
- 右键打开指挥 UI，可显示 512 格内地面单位
- 移动规则：
  - `advance` 推进：接近目标至 48 格
  - `hold` 防守：原地转向目标
  - `follow` 跟随：接近目标至 24 格
  - `stop` 停火：停止索敌和移动
- 索敌范围：**328 格**
- 每 20 tick 重新索敌，目标优先玩家
- 地面移动速度 0.18 格/tick，直接贴地移动
- 无目标时按 `advance/follow` 在出生点 **512 格**范围内随机选点巡逻，不会原地不动
- 瘫痪判定：`isWreck()` 或血量 ≤0 后：
  - 立即清除目标
  - 不再开火
  - 不再移动
  - 速度清零
- 炮塔使用 `turretAutoAimFromVector` + `adjustTurretAngle` 跟随目标
- 开火前用 `Level.clip` 做视线检测：**只有目标可见才开火**，隔墙不开火
- 攻击距离 **160 格**：
  - BMPT-72：30mm 炮弹（15/18/5，6 tick 冷却）
  - T-80：125mm 炮弹（250/120/10，30 tick 冷却）
- 目标 UUID 同步写入 `setAiTurretTargetUUID` / `setAiPassengerWeaponTargetUUID`
- 同阵营过滤：
  - 使用 `Team.isAlliedTo` 排除同队/盟友
  - 排除 `enemy_ground` / `enemy_f16` 标签
  - 不会对同阵营载具产生仇恨或开火
- 与敌方 F-16 无敌对关系：
  - F16 AI 排除 `enemy_ground`
  - 地面 AI 排除 `enemy` 队伍
  - 双方不会自动互相攻击
- 命令：
  ```mcfunction
  /f16addon groundmode advance
  /f16addon groundmode hold
  /f16addon groundmode follow
  /f16addon groundmode stop
  ```

jar 文件重命名为 **`enemyforces-1.0.0.jar`**，modId 仍是 `f16addon`，命令 `/f16addon ...` 不变。

### 7.14 地面防空 / 第五代机 / J-16 调整

**地面防空雷达**

- 两个 jar 中 `Type=AA/Defense` 及带防空导弹的地面载具补 `Radar`：
  - `Range=1500`
  - `Angle=360`
  - `Direction=Default`
- 覆盖 `lav_ad`、`625e`、`akm`、`flarakpz1`、`l1a2`、`tunguska`、`zsu234` 等

**防空导弹装填**

- 地面防空导弹 `EmptyReloadTime` 减半，最低 40 tick：
  - LAV-AD：260 → 130
  - 625E / AKM / ZSU-234：320 → 160
  - FlarakPz1：150 → 75
  - L1A2：200 → 100
  - Tunguska：300 → 150

**防空导弹锁定时间减半**

`SeekWeaponInfo.SeekTime`：

- LAV-AD：10 → **5**
- 625E / AKM / FlarakPz1 / L1A2 / Tunguska / ZSU-234：20 → **10**

**过载 +30%**

- `Ru9m336` / `Igla` / `Ru9m100` 最大转向：17.5 → **22.75**
- FIM-92 继承 Ru9m336，同步生效
- 这些类也被空射 AIM-9/PL-10 使用，所以空射红外弹同样 +30%

**五代机雷达探测减半**

- `j20.json` / `j20vtol.json` / `j35.json`：
  ```json
  "TrackDistanceMultiply": 0.5
  ```
- `RadarScanner` 会对这些目标把有效探测距离减半

**J-16 雷达弹挂架**

- 新增 `Pylon7/8/9`
- 雷达弹挂架总数变为 **8**：`Pylon2` ~ `Pylon9`
- 新挂架加入第二个座位

**雷达 AAM 不对地锁定**

- 所有 `Aim120/Pl12/Pl15` 武器 `SeekWeaponInfo.MinTargetHeight=8`
- 地面目标高度为 0，不会被雷达 AAM 锁定
- addon 的 `PlayerFireControl` 也增加判断：雷达 AAM 不把地面目标写入
- 低于 8 格高度的空中目标同样不会被锁定

**单兵战术终端：地空导弹对投掷物锁定距离**

只改地面防空导弹，不动普通导弹：

- `SeekRange`：318 / 384 / 400 → **4096**
- `MaxGuidedRange`：→ **4096**
- 覆盖：
  - LAV-AD `FimMissile`
  - 625E / AKM / FlarakPz1 / L1A2 / Tunguska / ZSU-234 防空导弹
- `OnlyLockEntity=true`，战术终端里可以锁定投掷物/导弹实体
- IGLA-9K38 单兵防空导弹：
  - `SeekRange`：384 → **1024**

### 7.15 敌方黄蜂（AKM）固定防空系统

新增物品：

- `f16addon:enemy_wasp_deployer`
- 中文：敌方黄蜂防空导弹召唤器

生成实体：

- `dragonrise_reforge:akm`

行为：

- **完全固定，不移动**
- 索敌/攻击范围 **1000 格**
- 目标优先级：
  1. 敌方导弹/投掷物
  2. 其他未贴地的空中目标
- 不会锁定自己或同阵营发射的导弹：
  - `owner == self`
  - 同队/盟友（`Team.isAlliedTo`）
  - owner 带 `enemy_f16` / `enemy_ground` / `enemy_sam` 标签
- 只攻击“对黄蜂来说是敌人”的目标：
  - 投射物：导弹、炮弹、火箭弹（`MISSILE` / `SHELL` / `CANNON` / `ARTILLERY` / `MORTAR` / `ROCKET` / `RPG` / `GRENADE`）
  - 飞机：`VehicleType = AIRPLANE / HELICOPTER / DRONE / AIRSHIP`
  - 玩家驾驶敌机时按敌机处理
- 不再攻击：
  - 徒步玩家
  - 地面载具/步兵/中立生物
  - 友军飞机与友军投射物
- 缓存目标每 tick 重新校验：
  - 每 tick 只校验目标类型/阵营/owner
  - LOS 与高度只在扫描和真正开火时检查，避免机动目标因单 tick 遮挡掉锁
  - 玩家下机、目标变成步兵、换队或投射物归属变化后会立即清锁
- AKM 载具的 `AAMissile` 武器配置已清空：
  - 不再由载具武器 AI 触发自动开火
  - 黄蜂导弹只由 `SamUnitAI` 直接生成
- 备份：`...jar.bak-before-akmweapon`、`...bak-before-samcache-...`
- 不再向 SBW 的 `AI_TURRET_TARGET_UUID` / `AI_PASSENGER_WEAPON_TARGET_UUID` 写目标
  - 避免载具炮塔 AI 绕过黄蜂的目标过滤自行开火
  - 只保留 `turretAutoAimFromVector` 转向和 SamUnitAI 直射导弹
- 普通目标索敌/攻击 **1000 格**
- 五代机（J-20/J-35/F-22/F-35/Su-57/FC-31/TJGC）索敌半径缩短为 **400 格**（原来的 2/5）
- 必须视线可见：`Level.clip` 检查炮口到目标，被地形遮挡的目标不选、不开火
- 目标离地高度必须 ≥ **8 格**；贴地/超低空目标不攻击
- 这两条同时作用于普通空中目标和优先拦截的导弹
- 炮塔使用 `turretAutoAimFromVector` 旋转
- 每 40 tick 发射一枚 `superbwarfare:ru_9m336_missile`
- 导弹威力：`260` 直接伤害 / **`160` 爆炸伤害** / `6` 格爆炸半径
- AKM `AAMissile` 武器数据和 AI 直接生成的导弹保持一致
- `Ru9m336MissileEntity` 本体硬超时：`tickCount > 160` 直接 `discard()`
  - 不依赖 `F16Addon` 的全实体扫描
  - 黄蜂导弹稳定在 8 秒后销毁
- 地空导弹高度保护：`Y > 520` 直接 `discard()`
  - 防止地空导弹被高抛/异常制导带到超高区域
- 导弹写入 `TargetUUID` / `TargetPos` / `GuideType`
- 瘫痪后停止转动和开火
- 与 F-16、BMPT/T80 同属 `enemy`，不互相攻击
- AKM 原雷达 1500 格，装填 160 tick，保留

实现类：

- `EnemySamDeployerItem`
- `SamUnitAI`
- `WaspEvents`（自动注册 tick 和创造模式物品）

### 7.16 SBW / DragonRise 性能优化

两个模组共用 SBW 的 `VehicleEntity` 雷达与 BVR 同步：

- `VehicleEntity.vehicleRadar()` 从每 tick 扫描改为 **每 3 tick 一次**（约 20Hz → 6.7Hz）
- 世界 `serverconfig` 的 `sync_entity_interval`：`1` → **`3`**
- BVR 实体渲染仍关闭（`enable_render_synced_entities=false`）
- 雷达探测、目标数据、HUD、战术终端功能保留
- 单兵战术终端/BVR 目标刷新约 6.7Hz，视觉上仍连续

适用范围：

- Superb Warfare 所有载具
- DragonRise 载具（继承同一 `VehicleEntity`）
- 之前新增的 AKM 黄蜂雷达

配置文件：

```text
.minecraft\versions\1.20.1-\saves\新的世界 (1)\serverconfig\superbwarfare-server.toml
```

### 7.17 投掷物统一超时

- 普通投掷物：**500 tick**（25 秒）
- 红外 AAM：**160 tick**（8 秒）
- 巡航导弹（`Ru3m14MissileEntity` / Kalibr）：**不自动销毁**
- 覆盖三层：
  - 附属 `F16Addon` 的全局 `Projectile` 清理
  - SBW `FastThrowableProjectile.m_8119_` 内部超时
  - `WaspEvents` 对敌对 owner（`enemy_f16` / `enemy_ground` / `enemy_sam`）投射物的显式清理
- 两处的巡航上限都从 `1200` 改为 `Integer.MAX_VALUE`
- 普通导弹仍由两层超时清理，避免投射物堆积造成持续掉帧

### 7.20 地面载具装甲调整

- 所有 `Type = Tank / Car / Artillery / APC / Defense / AA` 的地面载具：
  - `All * X` 统一提高 **10%**（例如 `0.2 → 0.22`）
  - 不再额外补 `All * 1.1`
  - 原本没有 `All *` 的载具不会被这一项削弱
- 所有地面载具的固定减伤阈值 `All - X` 下调 **40%**：
  - 例如 `All - 20 → All - 12`
  - `All - 13 → All - 7.8`
  - `All - 11 → All - 6.6`
- 角度弱点：
  - 已有 `$entity.getSourceAngle(source, factor) * damage` 的载具，factor 提高约 **33%**
  - 例如 `0.3 → 0.4`、`0.25 → 0.333`
  - 正面更硬，背面受到伤害更高；侧面继续享受 `All *` 的整体削弱
- 空中载具、船不受影响
- 主战坦克额外加强空地导弹防御：
  - `@#superbwarfare:at_rocket`：`1.1 → 0.85`
  - `superbwarfare:projectile_explosion`：`0.65 → 0.55`
  - `superbwarfare:projectile_hit`：`1.3 → 1.15`
- MBT 列表：
  - T-90A、ZTZ-99A、M1A2
  - T-80/T-80B/T-72B3/T-90M
  - Leopard 2A4、Challenger DS、K1A1
  - VT-4A1/VT-4B、ZTZ-96A、AMX-56、Project 640
- BMPT-72、BMP-2/3、LAV、BTR 等只享受“整体略弱”，不获得 MBT 对 AGM 的额外加强

### 7.21 美空军 AGM 过载

- 对地 AGM 过载统一为 **4.0**：
  - `superbwarfare:agm_65`
  - `dragonrise_reforge:agm65`
  - `dragonrise_reforge:kd88a`（继承）
  - `dragonrise_reforge:kh29`（继承）
  - 其他继承 `Agm65CustomEntity` 的 AGM
- 速度/重力统一：
  - `Velocity = 2.5`
  - `Gravity = 0.02`
- 覆盖弹种与载具：
  - 原版 AGM-65：A-10A、F-14、AC-130、J-16
  - 自定义 AGM：F-15E、FA-18E、JAS39
  - KH-29 / KD-88：J-16
  - KH-39：F-16C（AGM 槽实际弹种）
- 直击伤害统一提高：
  - `Damage = 1400`
  - 爆炸伤害/半径保持不变
- 只改 AGM 的过载、速度、重力和直击伤害

### 7.22 空空雷达弹过载

- `Aim120Entity.turn()` 实际转向参数：`7.875 → 8.5`
- 继承关系：
  - AIM-120
  - PL-12
  - PL-15
  - R-77
- 全部使用同一套 `Aim120Entity.turn()`，因此统一为 **8.5**

### 7.19 导弹飞出渲染区后的区块加载

- `MissileProjectile.forceLoadChunk()` 恢复为**恒为 `true`**：
  - 无玩家、未渲染、超出限高的导弹都继续强制加载
  - 确保导弹不会因区块卸载停止 tick，能正常超时/脱锁销毁
- 所有 `MissileProjectile`（包括 AI 发射）共用该逻辑
- 通过全局限制降低强制加载消耗：
  - `max_projectile_chunks_force_loaded = 64`（原 256）
  - `max_projectile_chunks_loaded_each_tick = 8`（原 32）
  - `projectile_chunk_age = 10`（原 20）
  - `sync_entity_interval = 5`
- `projectile_chunk_loading` 仍为 `true`

### 7.18 导弹脱锁与战术终端卡顿

脱锁问题：

- `MissileProjectile.f16addon$lockCheck()` 原本只设置 `lostTarget`，从不递增 `lostTargetTick`
- 新增 `MissileLostTargetGuard.tick()` 后：
  - 已脱锁：`lostTargetTick + 1`
  - 有有效目标实体：归零
  - 没有实体但 `TARGET_POS` 有效（包括地面坐标）：视为仍锁定，归零
  - 既无有效实体也无有效目标坐标：累计
  - 巡航导弹跳过
- `MissileProjectile` 的 `lostTargetTick` 阈值改为 **160 tick**，脱锁约 **8 秒**后销毁
- 对地导弹 `TARGET_UUID = "none"` 不会被误删；目标消失后继续飞向最后坐标
- 适用于所有制导导弹；巡航导弹仍跳过脱锁计时
- 适用于所有 `MissileProjectile`，包括 AIM-120、PL-12、PL-15、9M336、Igla 等

战术终端：

- BVR 同步 `sync_entity_interval = 5`
- BVR 发包按玩家距离裁剪：
  - 导弹：最多 **1024 格**
  - 其他载具/玩家：最多 **2048 格**
  - `removed` 删除包始终保留
- 载具雷达 `vehicleRadar()` 每 3 tick 扫描
- `MapEntityRenderer.renderEntityBatch` 每组实体最多渲染 **256** 个标记
- 雷达扇形原本最多 12 段，保持不变
- BVR `ServerSyncedEntityHandler.register` 的生物分支由 `LivingEntity` 改为 `Player`
  - 僵尸/动物/普通生物不再进入战术终端同步
  - 玩家、载具、导弹保留
  - 玩家队友信息仍由独立的 `syncPlayerInfo` 同步

实体扫描：

- `WaspEvents` 不再每 tick 扫描全实体找黄蜂
  - 每 **100 tick** 重扫一次 `enemy_sam`
  - 中间只 tick 缓存中的黄蜂
  - 新部署的黄蜂最多延迟 5 秒加入缓存
- 移除了 `WaspEvents` 里重复的敌方导弹超时扫描
  - 统一由 `F16Addon` 的全局投射物超时处理
- `F16Addon` 仍需每 tick 扫描一次实体，用于 F-16/地面单位 AI 与投射物超时

备份：

- `superbwarfare-...jar.bak-before-radar-throttle-...`
- `...jar.bak-before-cruise-lifetime-...`
- `...jar.bak-before-lockguard-...`
- `...jar.bak-before-lockguard2-...`
- `...jar.bak-before-forceload-restore-...`
- `...jar.bak-before-heightload-...`
- `...jar.bak-before-alwaysload-...`
- `...jar.bak-before-lost8s-...`
- `...jar.bak-before-bvrcull-...`
- `...jar.bak-before-wasptimeout-...`
- `...jar.bak-before-waspceiling-...`
- `...jar.bak-before-wasp520-...`
- `...jar.bak-before-groundarmor`
- `...jar.bak-before-weakspots`
- `...jar.bak-before-threshold60`
- `...jar.bak-before-agmoverload`
- `...jar.bak-before-agm15` / `...jar.bak-before-agm8`
- `...jar.bak-before-aam85`
- `...jar.bak-before-agmstats`
- `...jar.bak-before-samrevalidate-...`
- `...jar.bak-before-agm4`
- `...jar.bak-before-agm6`
- `...jar.bak-before-mapcap-...`
- `...jar.bak-before-scanfilter-...`
- `dragonrise...jar.bak-before-wasp-ir-stats-...`
- `superbwarfare-...jar.bak-before-aa-radar-20261023-161644`
- `dragonrise...jar.bak-before-aa-radar-20261023-161644`
- `superbwarfare-...jar.bak-before-aa-overload30-...`
- `superbwarfare-...jar.bak-before-aa-locktime-...`
- `...jar.bak-before-tactical-range-...`

注意：仍是简化飞控，起飞是垂直爬升，不是完整跑道滑跑；直接位置积分可能穿地形/动作生硬。先重启测试，有问题再按日志调。

构建工程：`D:\f16addon2`（原 `D:\f16addon` 的 Gradle 缓存卡住，已迁到干净目录）。

