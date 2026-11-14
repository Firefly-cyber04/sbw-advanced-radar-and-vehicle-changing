# Enemy Forces 1.1.0：卡顿与索敌修复说明

1.1.0 只改附属 `enemyforces`（modId 仍为 `f16addon`），**不改** SBW / DragonRise 的 jar。
源码在 `addon-src/`（ForgeGradle 6，官方映射）。

---

## 1. 卡顿：根因与修复

| # | 1.0.0 的问题 | 影响 | 1.1.0 修复 |
|---|---|---|---|
| 1 | `F16Addon` 每 tick 把 `level.getAllEntities()` 复制成列表，再对每个实体做字符串匹配；`WaspEvents` 每 100 tick 再全扫一遍 | 实体越多越卡，和本附属单位数量无关 | `UnitTracker`：用 `EntityJoinLevelEvent / EntityLeaveLevelEvent` 维护“本附属单位 / 载具 / 投射物”索引，tick 只遍历这些；每 30 秒做一次全量对账 |
| 2 | F-16 每 40 tick 用 `inflate(1024)`、黄蜂每 10 tick 用 `inflate(1000)` 做 AABB 查询（约 2000×2000×2000 格） | 周期性尖峰，单位越多尖峰越密 | 索敌只看 `level.players()` + 已跟踪载具（O(玩家+载具)） |
| 3 | 黄蜂在 AABB 的**过滤谓词里**对每个候选做最长 1000 格的 `Level.clip` 视线检测 | 投射物/飞机多时每次扫描几十上百次长射线 | 先按优先级排序，只对前 `maxLosChecksPerScan`（默认 4）个做 LOS |
| 4 | `Level.getHeight` / `Level.clip` 在服务端遇到未加载区块会**同步加载甚至生成区块**：F-16 前视 50 格地形、地面单位贴地、黄蜂长射线都会触发 | 主线程卡死几十毫秒到数秒，是“偶发大卡顿”的主因 | `WorldQueries`：先 `hasChunk` 再查；射线先用区块网格 DDA 确认沿途区块已加载，否则视为不可见 |
| 5 | AI 对所有已加载实体运行，包括**不 tick 的边界区块**；单位被 `setPos` 推进未加载区域 | 单位“冻结在半空”、拖着区块加载走 | 只在 `isPositionEntityTicking` 的位置运行 AI；下一步进入未加载区块时不移动；F-16 有拴绳（`leashRadius`）和“前方即将出模拟区就返航” |
| 6 | 维度无玩家（且无 forceload）300 tick 后原版会暂停全部实体 tick，但 AI 挂在 `ServerTickEvent` 上照跑 | 空服时黄蜂对着冻结世界发射导弹（导弹悬停在发射架上），F-16 被 setPos 拖着飞 | `UnitTracker.levelTicksEntities` 复刻原版判定，暂停时 AI 同步暂停 |
| 7 | 每次调用 SBW API 都 `getClass().getMethod(...)`（F-16 每 tick 至少 5 次）；`getSimpleName().toUpperCase().contains(...)` 链对每个实体每 tick 执行 | 稳定的 CPU 开销 + 大量临时对象 | `Sbw`：按 (类, 签名) 缓存在 `ClassValue`；`Classify`：类特征按类只算一次 |
| 8 | 所有单位扫描用同一个 NBT 计数器，同 tick 部署的一队单位**在同一 tick 扫描** | 尖峰叠加 | 扫描按 `(gameTime + entityId) % interval` 错峰 |
| 9 | 能量每 tick 反射补满；客户端 RWR 每 5 tick 做 2048 格 AABB + 反射 | 小开销 | 能量 20 tick 一次；RWR 改为遍历客户端实体列表 + 缓存反射 |

### 基准（同一场景，全新超平坦世界，Forge 47.4.23 专用服）

场景：4 黄蜂 + 4 敌方 F-16 + 6 敌方 T-80，蓝队 30 架 AH-6（悬停）+ 20 辆 T-90A，300 头无 AI 的牛，forceload 16×16 区块。部署 15 秒后每 5 秒取一次 `/forge tps`：

| 版本 | Mean tick time（6 次采样） | 300 头牛剩余 |
|---|---|---|
| 1.0.0 | 9.0 / 8.1 / 15.2 / 6.4 / 11.5 / **21.7** ms（持续上升） | **4** |
| 1.1.0 | 3.1 / 3.0 / 3.0 / 2.8 / 2.9 / 2.8 ms（平稳） | 229（损失来自交战区爆炸溅射） |

1.1.0 下 `/f16addon perf` 显示本附属 AI 自身开销 **≈0.05 ms/tick**。
注意两版的战斗过程不同（1.0.0 在打牛），这是端到端 MSPT，不是纯 AI 开销对比。

---

## 2. 索敌：根因与修复

1. **载具永远不会被 F-16 索敌**：1.0.0 用 `getClass().getName().contains("VehicleEntity")` 判断载具，而所有具体载具类（`F16cEntity`、`T90aEntity`…）的类名都不含这个字符串；`VehicleEntity` 又不是 `LivingEntity`。结果 F-16 只会打玩家和**任何活体生物**——牛、村民、僵尸。基准里 1.0.0 打掉了 296/300 头牛。
   → 现在通过类继承链识别 SBW/DragonRise 载具；默认目标只有玩家、有乘员/有敌对计分板队伍的载具、无人机，以及配置 `extraTargetTypes` 里列出的实体类型。
2. **30% 概率直接丢掉正在交战的目标**：1.0.0 每次扫描（40 tick）有 30% 把当前目标清空并锁 2 秒，加上 15 tick 反应延迟，F-16 经常“看见了却不打”。
   → 改为 `detectionFailureChance`（默认 15%）只作用于**获取新目标**，不会丢掉正在交战的目标；换目标加 0.6 倍距离的迟滞，防止在两个相近目标间来回切；目标丢失后立即重扫。
3. **玩家与其载具是两个目标**：玩家坐在坦克里时，F-16 锁的是玩家 UUID，导弹/雷达/干扰弹都以载具为对象，行为不一致。
   → 玩家合并到其载具，作为同一个“接触”，按“有玩家驾驶”优先级排序。
4. **劫持的敌机**：玩家坐进敌方 F-16 后，AI 仍在抢操纵杆。
   → 由非友方玩家驾驶的载具一律视为敌方；有玩家在驾驶位时 AI 不接管。
5. **黄蜂**：
   - 发射偏航角写成了 `Math.min(-x, z)`（应为 `atan2(-x, z)`），导弹初始朝向错误；
   - 1000 格内所有“非友方”投射物都优先于飞机拦截，哪怕它正飞离一切友军；
   → 威胁评估：锁定我方单位的制导弹 / 航向在 ±20° 内指向我方单位的非制导弹，按**剩余飞行时间**排序，最优先；其次飞机；不构成威胁的投射物默认不打（`engageNonThreatProjectiles` 可恢复旧行为）。
   - 多台黄蜂会同时向同一枚炮弹齐射 → **shoot-look-shoot 交战板**：所有发射架共享“某目标已有几枚我方导弹在飞”，默认飞机 2 枚、投射物 1 枚。
6. **地面单位**：会向牛开炮；隔墙的目标会被选中然后一直不开火；`setPos` 直接把坦克瞬移上悬崖。
   → 排序后优先选可见目标；目标躲到掩体后 60 tick 自动换目标；单步爬升上限 1.25 格，卡住 40 tick 自动换巡逻点；默认不拿主炮打固定翼飞机（`engageJets`）。
7. **提前量**：1.0.0 固定 8/10 tick 提前量，改为按弹速计算的一阶拦截点。

---

## 3. 新增

- `serverconfig/f16addon-server.toml`：上面所有阈值都可调，示例见 `config/f16addon-server.toml`。
- `/f16addon perf`（OP 2 级）：最近 200 tick 的 AI 平均/最大耗时、单位数和跟踪的载具/投射物数。
- 启动自检：服务器启动时检查附属依赖的 SBW 反射 API，缺失会在日志中点名（SBW 更新后不再静默失效）。

## 4. 构建

```bash
cd addon-src
./gradlew build          # 产物：build/libs/enemyforces-1.1.0.jar（已 reobf 为 SRG，可直接放进 mods/）
```

需要 JDK 17。`addon-src` 是从 1.0.0 jar 反编译、重映射为官方名称后整理的源码；第一次提交（`Recover ... source`）与 1.0.0 行为一致，方便对比 diff。
