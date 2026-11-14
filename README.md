# Enemy Forces Addon 1.1.0

Minecraft **1.20.1 / Forge 47.4.x** 的敌方部队与空战扩展整合包。

本仓库包含：

- `enemyforces-1.1.0.jar`：Enemy Forces 附属本体（源码见 `addon-src/`）
- `superbwarfare-0.8.9.2-...jar`：修改过的 Superb Warfare
- `dragonrise_reforge-1.5.1-release.jar`：修改过的 DragonRise Reforge
- `docs/F16_ADDON.md`：完整功能与参数说明
- `config/superbwarfare-server.toml`：当前使用的服务端配置参考
- `config/f16addon-server.toml`：附属 AI 配置（1.1.0 新增）
- `docs/PERF_AND_TARGETING_1.1.0.md`：1.1.0 卡顿与索敌修复说明、基准数据

> **重要**：Superb Warfare 与 DragonRise 是第三方模组，本仓库中的对应 jar 是**修改版**。原模组版权归原作者所有；使用前请备份原 jar。

---

## 1.1.0 更新摘要

- **卡顿**：不再每 tick 遍历全部实体、不再做 2000 格 AABB 扫描；高度图/视线查询不再同步加载区块；AI 只在实体 tick 区块运行，空维度暂停时 AI 也暂停；反射与类名判断全部缓存。同场景基准 MSPT 由 6–22 ms（持续上升）降至约 3 ms，AI 自身约 0.05 ms/tick。
- **索敌**：修复 F-16 永远识别不到载具、却会攻击牛/村民的问题；不再随机丢掉正在交战的目标；玩家与所乘载具合并为一个目标；黄蜂按威胁和剩余飞行时间排序、多台发射架共享交战板（shoot-look-shoot）、修正发射朝向；地面单位优先打可见目标、不再瞬移上悬崖。
- **新增**：`serverconfig/f16addon-server.toml` 全部参数可调；`/f16addon perf` 性能统计；启动时 SBW API 自检。
- 详见 [docs/PERF_AND_TARGETING_1.1.0.md](docs/PERF_AND_TARGETING_1.1.0.md)。

---

## 安装

1. 完全退出 Minecraft。
2. 备份 `.minecraft/mods/` 中现有的三个同名 jar。
3. 将 `mods/` 下的三个 jar 复制到 `.minecraft/mods/`。
4. 可选：将 `config/superbwarfare-server.toml`、`config/f16addon-server.toml` 放到存档的 `serverconfig/`（附属配置不放也会自动生成默认值）。
5. 重新启动游戏/服务器。

不要在游戏运行时覆盖 jar，否则 Forge 的 `SecureJarHandler` 可能混用旧类索引并崩溃。

---

## 主要功能

### 敌方 AI 部队

- 敌方 F-16 AI：自定义飞控、目标选择、机炮/导弹、干扰弹与 RWR。
- 敌方地面单位：BMPT-72、T-80，支持移动、炮塔、攻击、雷达、视线与高度限制。
- 敌方固定防空：DragonRise AKM「黄蜂」。
- 黄蜂只攻击对它而言敌对的目标：
  - 导弹、炮弹、火箭弹
  - `AIRPLANE / HELICOPTER / DRONE / AIRSHIP`
  - 玩家驾驶敌机时按敌机处理
- 黄蜂不再攻击徒步玩家、地面载具、步兵、中立生物、友军飞机或友军投射物。

### 黄蜂 / 9M336

- AKM 载具自身的 `AAMissile` 武器配置已清空，避免载具武器 AI 绕过 `SamUnitAI` 自动开火。
- 黄蜂导弹由 `SamUnitAI` 直接生成。
- `Ru9m336MissileEntity` 本体硬限制：
  - `tickCount > 160`（8 秒）→ 销毁
  - `Y > 520` → 立即销毁
- 缓存目标每 tick 重新校验类型/阵营/owner；LOS 与高度只在扫描和开火时检查，避免机动目标因单帧遮挡掉锁。

### 导弹寿命与脱锁

- 普通投射物：**500 tick（25 秒）**
- 红外 AAM：**160 tick（8 秒）**
- 巡航导弹（`Ru3m14`）：不自动销毁
- 制导导弹脱锁：**160 tick（8 秒）** 后销毁
- `TARGET_POS` 有效（包括地面坐标）视为仍锁定：
  - 对地导弹不会因为 UUID 为 `"none"` 被误删
  - 目标实体消失但仍有最后坐标时，继续飞向坐标
- 只有明确 `lostTarget`，或既无有效实体目标也无有效目标坐标时才累计脱锁。

### 区块加载

- 所有 `MissileProjectile` 保持强制加载，包括无玩家、未渲染、超高导弹。
- 通过全局限制控制消耗：
  - `max_projectile_chunks_force_loaded = 64`
  - `max_projectile_chunks_loaded_each_tick = 8`
  - `projectile_chunk_age = 10`
- 导弹不会因区块卸载停止 tick，能正常超时/脱锁销毁。

### BVR / 战术终端

- BVR 同步 `sync_entity_interval = 5`。
- BVR 发包按玩家距离裁剪：
  - 导弹最多 **1024 格**
  - 其他载具/玩家最多 **2048 格**
  - `removed` 删除包始终保留
- 战术终端不再同步普通 `LivingEntity` 生物，只保留玩家、载具、导弹。
- 战术地图每组实体标记最多 **256** 个。
- 载具雷达每 3 tick 扫描。
- `WaspEvents` 的黄蜂扫描改为 **100 tick** 缓存，移除重复的敌方导弹超时扫描。

### 地面载具装甲

- 所有地面载具（`Tank / Car / Artillery / APC / Defense / AA`）：
  - `All * X` 提高 **10%**（例如 `0.2 → 0.22`）
  - 不再额外补 `All * 1.1`
  - 固定减伤阈值 `All - X` 下调 **40%**（`20 → 12`、`13 → 7.8`、`11 → 6.6`）
- 角度弱点：
  - `$entity.getSourceAngle(source, factor)` 的 factor 提高约 **33%**
  - 正面更硬、背面更疼；侧面继续享受 `All *` 的整体削弱
- 主战坦克额外加强空地导弹防御：
  - `@#superbwarfare:at_rocket`：`1.1 → 0.85`
  - `superbwarfare:projectile_explosion`：`0.65 → 0.55`
  - `superbwarfare:projectile_hit`：`1.3 → 1.15`
- MBT 列表：
  - T-90A、ZTZ-99A、M1A2
  - T-80/T-80B/T-72B3/T-90M
  - Leopard 2A4、Challenger DS、K1A1
  - VT-4A1/VT-4B、ZTZ-96A、AMX-56、Project 640
- BMPT-72、BMP-2/3、LAV、BTR 等只享受整体削弱，不获得 MBT 的 AGM 加强。

### AGM / 对地导弹

- 对地 AGM 过载统一 **4.0**
- 速度/重力统一：
  - `Velocity = 2.5`
  - `Gravity = 0.02`
- 直击伤害统一：
  - `Damage = 1400`
  - 爆炸伤害/半径不变
- 覆盖：
  - 原版 AGM-65：A-10A、F-14、AC-130、J-16
  - 自定义 AGM：F-15E、FA-18E、JAS39
  - KH-29 / KD-88：J-16
  - KH-39：F-16C 的 AGM 槽
- KD-88、KH-29 继承 `Agm65CustomEntity`，过载自动为 4。

### 空空雷达弹

- `Aim120Entity.turn()` 实际转向参数：`7.875 → 8.5`
- 覆盖 AIM-120、PL-12、PL-15、R-77。

---

## 推荐服务端配置

```toml
sync_entity_over_range = true
sync_entity_interval = 5
enable_render_synced_entities = false
max_render_distance = 2048

projectile_chunk_loading = true
max_projectile_chunks_force_loaded = 64
max_projectile_chunks_loaded_each_tick = 8
projectile_chunk_age = 10
```

`config/superbwarfare-server.toml` 是完整参考文件；不同存档/服务端请按需合并，不要直接覆盖服务器全局配置而不备份。

---

## 运行要求

- Minecraft **1.20.1**
- Forge **47.4.x**（当前环境 47.4.23）
- Java **17**
- 需要 Superb Warfare 与 DragonRise Reforge 的对应修改版 jar
- Superb Warfare 需要 Kotlin for Forge ≥ 4.11.0
- 建议同时安装 Embeddium 等客户端优化模组；本包不包含第三方渲染模组

---

## 已知注意事项

- 修改了第三方模组的二进制数据/类；升级原模组后需要重新应用修改。
- 不要在游戏运行时替换 jar。
- 如果更换存档，`serverconfig` 配置可能不同。
- 详细参数与历史设计说明见 [docs/F16_ADDON.md](docs/F16_ADDON.md)。
- 校验和见 [SHA256SUMS.txt](SHA256SUMS.txt)。

---

## 文件结构

```text
.
├─ README.md
├─ SHA256SUMS.txt
├─ addon-src/                     # 附属源码（ForgeGradle，./gradlew build）
├─ config/
│  ├─ f16addon-server.toml
│  └─ superbwarfare-server.toml
├─ docs/
│  ├─ F16_ADDON.md
│  └─ PERF_AND_TARGETING_1.1.0.md
└─ mods/
   ├─ enemyforces-1.1.0.jar
   ├─ superbwarfare-0.8.9.2-mc1.20.1-65d985a64-all.jar
   └─ dragonrise_reforge-1.5.1-release.jar
```

---

## Credits / License

- Enemy Forces Addon：本项目代码与整合修改。
- Superb Warfare：版权归 Superb Warfare 原作者所有。
- DragonRise Reforge：版权归 DragonRise Reforge 原作者所有。

本仓库不声明拥有上述第三方模组的版权。请遵守各原模组的许可与分发条款；如不允许再分发修改版 jar，请只保留 README/配置，删除对应 jar。
