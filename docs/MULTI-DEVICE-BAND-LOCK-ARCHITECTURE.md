# WirelessSignalMeter 多设备锁频适配层架构设计

版本：v0.4（2026-11-23）

## 0. 修订记录

| 版本 | 内容 |
|---|---|
| v0.1 | 初稿：分层结构、Tier、通道优先级 |
| v0.2 | 持久写入需要恢复基线；即时设置移到 SSR 之后 |
| v0.4 | 再修 4 处逻辑问题、补 3 条约束：① 只有明确且经过验证的"不存在"结果才算 ABSENT，超时、权限不足、协议不支持一律拒绝写入；② 恢复流程不再以 SIM 已注册为前提，顺序改为控制通道就绪 → 恢复 → 等待注册；③ 试锁改为原事务内的 TRIAL_PENDING 状态，回退是同一事务的补偿阶段，沿用同一把锁，接受试锁时原子取消回退计时；④ 开发者模式只放宽正向功能验证，持久写入仍须满足快照、回退路径已验证、写前日志；⑤ 持久性和生效方式分开记录，按重启依赖编排；⑥ 原生层保留硬截止时间，应用被杀后也能自行停止；⑦ 验证样本必须晚于最后一次相关写入和最后一次重启 |
| v0.3 | 按评审意见修改 6 处：① 区分三类状态，删除"只锁不解、重启恢复"；② 写能力按"操作＋通道＋格式＋固件"分别验证，Tier 只用于归类，探测时禁止试写；③ 执行顺序按依赖安排，加入"通道恢复"；④ 写前保存事务日志，每次操作单独快照，结果支持部分完成、状态未知、恢复失败；⑤ 验证拆成三项证据，只用操作后的新鲜样本，请求指定目标 SIM；⑥ 超时回退恢复到本次操作前状态，写明回退失败的处理，不承诺不断网 |

保留的设计：统一请求接口、只读能力画像、SA/NSA 分开记录、ACK 与实际效果分开、操作日志、减少不必要的基带重启。

---

## 1. 目标与边界

**目标**
- 一套上层接口覆盖多种机型：锁频段、锁频点、锁小区、网络模式、5G SA/NSA 模式、CA 开关、解锁
- 每次写操作都能回到**本次操作前状态**，失败时如实报告
- 能区分"命令被接受""配置已写入""手机确实按锁定驻留"

**第一期范围**
- 高通平台：DIAG（diag-router 或 `/dev/diag`）、AT 口、Android RIL
- 厂商系统服务（vivo、三星 CustomDL）只探测是否存在，不写入

**明确不做 / 不承诺**
- MTK、Exynos、麒麟、紫光展锐的锁频
- EFS2 内存模式写入（已证实不会持久生效）
- 不承诺"不会断网"，也不承诺"重启手机就能恢复"。NV 持久配置本来就是重启后才生效，重启不是恢复手段

---

## 2. 总体分层

```
┌────────────────────────────────────────────────┐
│ UI 层    BandLockScreen / ViewModel             │ 只认 LockRequest / LockResult
├────────────────────────────────────────────────┤
│ 编排层   LockOrchestrator                        │ 计划 → 快照 → 写日志 → 执行 → 通道恢复 → 验证 → 回退
│          TransactionJournal（写前日志）            │
│          Verifier（三项证据）                      │
├────────────────────────────────────────────────┤
│ 能力层   DeviceFacts（只读探测）                    │
│          SupportRegistry（操作级验证记录）           │
├────────────────────────────────────────────────┤
│ 通道层   NvSsrChannel │ CmFrameChannel │ AtChannel │ RilChannel │ VendorServiceChannel(只探测)
├────────────────────────────────────────────────┤
│ 原生层   band_lock（root helper）                 │ DIAG 收发、EFS PUT/GET、AT 透传、SSR
└────────────────────────────────────────────────┘
```

---

## 3. 三类状态，必须分开

| 状态 | 定义 | 来源 | 用途 |
|---|---|---|---|
| **PreOpSnapshot**（本次操作前状态） | 本次事务开始前，每个将要改动的项的当前值，或"当前不存在" | 本次事务开始时现场读取 | 本次失败回退、试锁超时回退 |
| **SoftwareBaseline**（本软件锁定前基线） | 本软件**第一次**改动该项之前的值 | 首次事务的 PreOpSnapshot 复制一份，之后只读、不覆盖 | 用户选择"解除本软件的锁定" |
| **SupportedSet**（设备支持频段全集） | 硬件和固件理论上支持的频段 | AT 查询、机型表、白名单真机采集 | 只用来限定界面上能选的范围，**不能**作为写回值 |

注意：
- SoftwareBaseline **不等于出厂状态**。第一次读到的值可能已经被其他软件锁窄了。所以建立基线时要和 SupportedSet 比较，如果比全集窄，标记为 `NARROWED_AT_CAPTURE`，并在界面上说明："本软件锁定前，该设备已被限制为 n1、n78，解除后会回到这个状态，而不是出厂状态。"
- 用户想要"恢复到全部频段"，这是一次**新的锁定操作**（目标值等于 SupportedSet），按普通写操作的规则走：要有 PreOpSnapshot，SupportedSet 的来源必须已验证，并且要用户确认。它不是"解锁"。
- 读不到当前值的项，PreOpSnapshot 就无法建立，**这一项不允许持久写入**（例外见第 7 节的 VERIFIED_ABSENT）。

---

## 4. 数据模型

### 4.1 LockRequest

```kotlin
data class LockRequest(
    val target: SubscriptionTarget,          // 必填
    val ops: List<LockOp>,                   // 一次请求可以包含多项
    val trial: TrialPolicy? = null,          // 试锁：超时自动回退到 PreOpSnapshot
)
data class SubscriptionTarget(
    val slotIndex: Int,                      // 物理卡槽
    val subId: Int,                          // Android subscription id
    val modemStack: Int,                     // 基带协议栈 / AS id（对应 NV 的 _SubscriptionNN 后缀等）
)
sealed interface LockOp {
    data class LteBands(val bands: Set<Int>) : LockOp
    data class NrSaBands(val bands: Set<Int>) : LockOp
    data class NrNsaBands(val bands: Set<Int>) : LockOp
    data class LteFreq(val earfcns: List<Int>) : LockOp
    data class LteCells(val cells: List<LteCell>) : LockOp
    data class NrFreq(val freqs: List<NrFreq>) : LockOp
    data class NrCells(val cells: List<NrCell>) : LockOp
    data class RatMode(val mode: Rat) : LockOp
    data class Nr5gMode(val mode: Nr5g) : LockOp
    data class CaDisabled(val disabled: Boolean) : LockOp
    data class RestoreSoftwareBaseline(val items: Set<LockItem>) : LockOp
}
```

- `target` 必须明确指定。双卡设备上，如果无法确定 subId、modemStack 和 NV 路径后缀之间的对应关系，**拒绝执行**，不按默认卡猜测。
- 验证阶段只采集同一个 subId 的数据（见第 10 节）。

### 4.2 DeviceFacts（只读探测结果）

```kotlin
data class DeviceFacts(
    val device: DeviceId,                    // brand / model / soc / basebandVersion / buildFingerprint
    val platform: Platform,
    val channels: Map<ChannelKind, ChannelProbe>,      // 通道能不能连上，只读
    val nvReadable: Map<NvItem, ReadState>,           // 见下方 ReadState
    val subscriptions: List<SubscriptionInfo>,        // slot / subId / modemStack 对应关系
    val tier: Tier,                                   // 只用于归类和展示
    val probedAt: Long, val probeVersion: Int,
)
```

```kotlin
sealed interface ReadState {
    data class Present(val bytes: ByteArray, val oracle: OracleId) : ReadState   // 读到真实内容
    data class Absent(val oracle: OracleId, val code: Int) : ReadState           // 明确的"不存在"结果，如 EFS2 STAT 返回 ENOENT
    data class Failed(val reason: FailReason) : ReadState                        // TIMEOUT / EPERM / UNSUPPORTED / ECHO_ONLY / MALFORMED / OTHER
}
```

- 只有 `Present` 和 `Absent` 是确定结果，`Failed` 一律视为"状态未知"。
- `Absent` 要成立，产生它的查询方式（`OracleId`，比如"DIAG EFS2 STAT 在该固件上"）必须**事先验证过能可靠区分存在和不存在**：同一固件上，对已知存在的项返回 Present，对已知不存在的项（包括故意写错的路径）返回 Absent，并且两者的回包可区分。cellular-pro 报告里已经证实，EFS2 内存模式的 OPEN 对任何路径都返回 0、READ 只是回显，这类查询方式不能作为 oracle。
- 没有可靠 oracle 的固件上，任何项都只能是 `Present` 或 `Failed`，不会出现 `Absent`。
- 探测过程中**只允许读操作**：建立连接、DIAG 注册、GET、AT 查询命令（`=?` 或 `?`）、RIL 查询。禁止 PUT、删除、SSR，以及任何设置类 AT 命令。

### 4.3 SupportRegistry（写能力的唯一依据）

```kotlin
data class OperationKey(
    val op: OpKind,                    // 比如 NR_SA_BANDS、NR_CELLS_MULTI、LTE_BANDS_EXT、CA_DISABLE
    val channel: ChannelKind,
    val format: FormatId,              // 比如 PCI_LOCK_V1_12B、PCI_LOCK_MULTI_8B
    val firmware: FirmwareMatch,       // 机型 + 基带版本范围 + 指纹前缀
)
data class SupportRecord(
    val key: OperationKey,
    val state: SupportState,           // VERIFIED / UNVERIFIED / BLOCKED
    val persistence: Persistence,      // PERSISTENT / VOLATILE / UNKNOWN（同样按固件验证）
    val readback: Boolean,             // 这台固件上能不能读回确认
    val absentRestore: Boolean,        // 是否验证过"删除该项 + SSR = 恢复出厂行为"
    val evidence: String,              // 验证记录编号、日期、测试人
)
```

- **Tier 不授予任何写能力。** 每个操作能不能执行，只看匹配到的 SupportRecord。
- 一项操作验证通过，不会带动其他操作开放。例如某机型 LTE 频段已验证，NR 频段、CA、多 PCI 仍然是 UNVERIFIED。
- 固件版本超出记录覆盖的范围时，状态视为 UNVERIFIED。
- 验证记录随应用打包；在线更新必须走 HTTPS 并校验签名。

---

## 5. Tier 的作用（只用于归类）

| Tier | 含义 | 用途 |
|---|---|---|
| T0 | 非高通，或没有可用的 DIAG 通道 | 界面只显示读信号和切网络模式 |
| T1–T4 | 按 Pioneer 命令集代际粗分（LTE 基础 / LTE 完整 / NR / NR 新版） | 排序探测项、匹配候选格式、在诊断日志里归类、决定采集新机型数据时优先测哪些操作 |

Tier 根据 `platform`、SoC 代际和机型表推断。**`nvReadable` 和读到的长度只是参考信息**，不能用来推断 Tier，也不能用来推断支持哪种格式（比如 `pci_lock_info` 长于 12 字节，不能说明支持多 PCI）。

---

## 6. 通道、持久性与生效方式

每个"操作＋通道＋格式＋固件"在 SupportRecord 里**分开记录两个属性**：

| 属性 | 取值 | 含义 |
|---|---|---|
| `persistence` | PERSISTENT / VOLATILE / UNKNOWN | 基带重启后这个设置还在不在 |
| `activation` | INSTANT / NEEDS_SSR / NEEDS_REBOOT / UNKNOWN | 写入后要怎样才生效 |

| 通道 | 机制 | 已知情况 |
|---|---|---|
| NvSsrChannel | DIAG EFS PUT | 通常 PERSISTENT + NEEDS_SSR |
| CmFrameChannel | DIAG CM 帧 | SM-S9370 上为 VOLATILE + INSTANT，其他固件 UNKNOWN |
| AtChannel | `AT+QNWPREFCFG` / `QNWLOCK` / `QNWCFG` | 手机上两项都 UNKNOWN |
| RilChannel | `setPreferredNetworkType` | 因机型而异 |
| VendorServiceChannel | vivo / 三星系统服务 | 第一期只探测 |

**两个属性各管各的：**
- **`activation` 决定执行顺序**（第 9 节）：NEEDS_SSR 的写在 SSR 之前；INSTANT 的写在**最后一次** SSR 之后，否则可能被这次重启清掉。
- **`persistence` 决定恢复要求和后续维护**：PERSISTENT 和 UNKNOWN 都必须满足第 7 节的快照和回退要求；VOLATILE 和 UNKNOWN 都要记入 LockState，之后任何一次基带重启后，重新下发或在界面上标为已失效。
- `activation = UNKNOWN` 的操作**不允许执行**，因为无法安排它在 SSR 前还是 SSR 后。先在实验室验证清楚。
- 如果某个操作本身会触发基带重启（比如个别机型切网络模式），记为 `activation = INSTANT` 并额外标注 `triggersRestart = true`，编排时把它当作一次 SSR 看待：它之前的 INSTANT 设置都会失效，必须排在它之后。

## 7. 持久写入的前置条件

对每一个 `persistence` 为 PERSISTENT 或 UNKNOWN 的写入项，**以下条件全部满足**才允许执行。**这些条件在任何模式下都不能跳过，包括开发者模式**（见第 12 节）：

1. **操作前快照是确定结果**：本次事务现场读取，得到下面两种之一，并已落盘：
   - `Present(bytes)`：产生它的查询方式已验证返回真实内容（不是回显）
   - `Absent`：产生它的查询方式已验证为可靠的"不存在"判断（第 4.2 节）

   `Failed`（超时、权限不足、协议不支持、只回显、格式异常等）**一律拒绝写入**，不能当成"不存在"。
2. **回退路径已验证**：在这个通道和固件上，以下操作已验证能把该项恢复到快照状态：
   - 快照为 `Present` → "写回旧字节"已验证，并且写回后能读回一致
   - 快照为 `Absent` → "删除该项（+ SSR）后行为与原来一致"已验证（`absentRestore = true`）

   能读出旧值不代表能写回，所以读和写回分别验证。
3. **写前日志已落盘**（第 8 节）
4. 用户选的频段是 SupportedSet 的子集

以下条件是**正向功能验证**，正常模式必须满足，开发者模式可以放宽：

5. 正向写入本身（这个操作＋格式＋固件）的 SupportRecord 为 VERIFIED

任何一项不满足，这一项就拒绝执行，并告诉用户具体原因。

对现有 `band_lock.c` 的影响：
- `nr_band_command` 读不到原值时写 `nr-no-original` 标记后照样锁定、恢复时写 `NR_FULL_SET`：违反第 1、2 条，**删除**。
- `read_mask_to` 现在把"超时"、"回包不匹配"、"读到全 0"都返回同一个失败码 1，上层无法区分，也无法得到 Absent。要改成分别返回 Present / Absent / Failed(原因)，并标明查询方式。
- 注释里提到 `nr_band_pref` 要第一次 PUT 之后才能 GET。这类固件上第一次锁 NR 之前只能得到 `Failed`，除非找到并验证了可靠的不存在判断方式，否则**不开放 NR 频段锁定**。
- 现有 `backup_mask` 只对应 SoftwareBaseline，不能当作每次操作的快照。

## 8. 事务日志（写前保存）

### 8.1 日志结构

```json
{
  "txId": "2026-11-23T16:20:11Z-7f3a",
  "target": {"slot": 0, "subId": 3, "modemStack": 0},
  "firmware": {"model": "SM-S9370", "baseband": "S9370ZCU2AYJ1", "fingerprint": "..."},
  "state": "EXECUTING",
  "steps": [
    {"seq": 1, "item": "NR_BAND_PREF", "channel": "NV_SSR",
     "pre": {"kind": "OLD_BYTES", "hex": "..."}, "planned": "...",
     "status": "WRITTEN_ACK"},
    {"seq": 2, "item": "NR_NSA_BAND_PREF", "channel": "NV_SSR",
     "pre": {"kind": "OLD_BYTES", "hex": "..."}, "planned": "...",
     "status": "SENT_NO_ACK"},
    {"seq": 3, "item": "SSR", "status": "PLANNED"},
    {"seq": 4, "item": "RAT_MODE", "channel": "RIL", "pre": {"value": 33}, "planned": 23, "status": "PLANNED"}
  ],
  "rollback": {"status": "NOT_STARTED", "steps": []}
}
```

步骤状态：`PLANNED → SENDING → WRITTEN_ACK | SENT_NO_ACK | REJECTED`，读回后为 `READBACK_MATCH | READBACK_MISMATCH | READBACK_UNAVAILABLE`，回退后为 `ROLLED_BACK | ROLLBACK_FAILED`。

### 8.2 规则

- **先写日志，再执行**：所有 PreOpSnapshot 都落盘并 fsync 之后，才开始第一次写入。每一步执行前把状态改成 `SENDING` 并 fsync，执行后立即更新结果。
- 日志存放在应用私有目录，权限 0600，O_NOFOLLOW，内容带校验和。
- 每次事务都有自己的 PreOpSnapshot；SoftwareBaseline 单独存放，只在第一次建立。
- **ACK 丢失**（`SENT_NO_ACK`）：不重试写入。能读回的项就读回判断实际状态；读不回的标记为"状态未知"。
- **应用被杀或手机重启**：下次启动时扫描未完成的事务，逐项读回当前值，和 `pre`、`planned` 对比，向用户展示结果，由用户选择"继续""回退到操作前""保持现状"。不自动执行。
- 同一时间只允许一个事务（沿用现有的 `operation.lock`）。上一个事务没有结束，不能开始新的。试锁的回退不是新事务，而是原事务的补偿阶段，见 8.4。
- 日志里记录 `lastMutationAt`：最后一次相关写入、即时设置或基带重启完成的时间，供验证阶段判断样本是否新鲜。

### 8.3 LockResult

```kotlin
data class LockResult(
    val txId: String,
    val outcome: TxOutcome,            // SUCCEEDED / PARTIAL / UNKNOWN / ROLLED_BACK / ROLLBACK_FAILED / REJECTED
    val items: List<ItemResult>,       // 每一项的状态
    val evidence: VerifyEvidence,      // 第 10 节
    val volatileItems: List<LockItem>, // 下次基带重启后会失效的设置
    val log: List<String>,
)
```

### 8.4 事务状态机与试锁

```
PLANNED → SNAPSHOTTED → EXECUTING → APPLIED ─┬─(无试锁)──────────→ COMMITTED
                                             └─(有试锁)→ TRIAL_PENDING ─┬─ 用户接受 ──→ COMMITTED
                                                                        ├─ 超时 / 用户放弃 → COMPENSATING ─┬→ ROLLED_BACK
                                                                        │                                  └→ ROLLBACK_FAILED
                                                                        └─ 应用被杀 → 启动时恢复处理（8.2）
任一执行阶段失败 → PARTIAL / UNKNOWN（由用户选择回退或保持）
```

- `TRIAL_PENDING` 期间事务**没有结束**，继续持有 `operation.lock`，不允许开始新事务。
- 回退是同一事务的 `COMPENSATING` 阶段，沿用同一份日志、同一把锁、同一份操作前快照，不另开事务。
- 日志里记录 `trialDeadline` 和一个递增的 `generation`。回退计时器触发时，用 compare-and-set 检查"状态仍是 TRIAL_PENDING 且 generation 未变"才进入 COMPENSATING；用户点"接受"时同样用 compare-and-set 把状态改成 COMMITTED 并让 generation 加 1。两者只有一个能成功，旧计时器不可能覆盖后来的设置。
- 计时器在应用被杀后不会自己恢复。下次启动发现 TRIAL_PENDING 且已过 `trialDeadline` 时，不自动回退，按 8.2 的方式让用户选择。

---

## 9. 执行顺序（按依赖安排）

### 9.1 锁定流程

```
resolve()              读 DeviceFacts，匹配 SupportRecord；固件变了就重新探测
plan()                 拆分操作、选通道，按 activation 排序；有 activation=UNKNOWN 或不满足第 7 节的项 → 拒绝并说明
preflight()            root 可用、电量 > 20%、不在通话、没有未完成的事务、目标 SIM 可识别（不要求已注册）
snapshot()             读取每个写入项的操作前快照；有 Failed → 中止
journal.begin()        日志落盘
ssrBatch()             所有 activation=NEEDS_SSR 的项依次写入，每步更新日志；能读回的立即读回
ssr()                  有 NEEDS_SSR 项时才执行，最多一次
controlReady()         等控制通道就绪：DIAG 重新连上、AT 口重新出现、RIL 可调用（不等注册）
instantBatch()         所有 activation=INSTANT 的项：CM 帧、AT 即时设置、网络模式、5G 模式
                       triggersRestart 的项排在最前，之后再做一次 controlReady()
journal.mark(lastMutationAt)
awaitRegistration()    等目标 SIM 注册，超时只报告（第 11 节）
verify()               第 10 节
journal.commit() 或 进入 TRIAL_PENDING
```

### 9.2 恢复流程（回退、解除本软件锁定、补偿阶段共用）

```
preflight()            root 可用；不要求 SIM 已注册，不要求有网络
controlReady()         只等控制通道就绪
restoreSsrBatch()      按快照写回旧字节或删除该项
ssr()                  需要时执行一次
controlReady()
restoreInstantBatch()  恢复网络模式等即时设置（必要时先恢复模式，保证能重新搜网）
journal.mark(lastMutationAt)
awaitRegistration()    等注册；超时只报告，不再自动尝试
verify()               对比快照的读回结果
```

**锁定和恢复都不以"已注册"为前提。** 锁到当前位置没有覆盖的频段后，手机可能一直搜不到网，恢复流程必须照样能执行。注册状态只是执行后的观察结果。

## 10. 验证：三项证据分开显示

| 证据 | 判定方法 | 结果 |
|---|---|---|
| **命令确认** | 每一步都有 ACK，状态为 0 | 全部 / 部分 / 无 |
| **配置读回** | 读回值等于计划值（仅限支持读回的项） | 一致 / 不一致 / 不支持读回 |
| **驻留符合** | 用新鲜样本判断服务小区是否符合锁定条件 | 符合 / 不符合 / **无法判断** |

**新鲜样本的要求**
- 样本时间戳晚于日志中的 `lastMutationAt`，也就是**最后一次相关写入、即时设置和基带重启都完成之后**。如果验证期间又有任何相关操作（包括重新下发易失设置），`lastMutationAt` 更新，之前的样本全部作废，重新采样
- 每条样本带上来源和序号（RRC 信令的时间戳和序号，或 CellInfo 的 `timestampNanos`），**重复的缓存记录要去掉**；同一条记录发布两次只算一次
- 只采集 `target.subId` 的数据，双卡时不能用另一张卡的数据来判断
- 至少需要 N 条不同的新鲜样本（默认 3 条，间隔 ≥ 5 秒）

**无法判断的情况**
- 操作前的驻留就已经符合锁定条件（比如原来就在 B3，这次锁 B3）：驻留结果标记为"无法判断"，不能算作生效证据
- 能提高判断力的证据（可选）：操作前能观察到不在锁定集合内的服务小区或邻区，操作后不再驻留到这些小区；或者服务小区确实发生了变化
- 界面上三项证据分开显示，不再合并成一个"已生效"。只有"配置读回一致"并且"驻留符合（有判断力）"时，才显示为"已确认"

---

## 11. 超时与回退

- **没有开启试锁时**，注册超时只报告结果（比如"60 秒内未注册，可能是当前位置没有符合条件的小区"），**不自动回退**，由用户选择"回退到操作前""再等等""保持"。选择回退时，进入本事务的补偿阶段（9.2）。
- **开启试锁时**，事务进入 `TRIAL_PENDING`（8.4）。超时或用户放弃后，在同一事务内进入 `COMPENSATING`，回到**本次操作前快照**，不回到 SoftwareBaseline，也不写全集。
- 补偿阶段按 9.2 执行，不要求已注册。
- 回退失败（写回被拒、ACK 丢失、读回不一致）：
  - 状态记为 `ROLLBACK_FAILED`，日志保留，锁随事务结束释放，但界面始终显示该事务未恢复
  - 显示每一项的当前状态，提供"重试回退""导出诊断日志"和手动恢复说明
  - 不自动重复尝试，避免反复重启基带
- 文档和界面里都不写"保证不断网"。

## 12. 开放策略

| 场景 | 正向功能 | 持久写入的恢复要求（第 7 节 1–4） |
|---|---|---|
| 正常模式，SupportRecord VERIFIED | 开放 | 必须满足 |
| 正常模式，UNVERIFIED / BLOCKED | 不开放 | — |
| 开发者模式，UNVERIFIED | 可以用，每次确认风险，结果只写入诊断日志、**不会自动变成 VERIFIED** | **必须满足，不放宽**：快照为确定结果、回退路径已验证、写前日志已落盘 |
| 开发者模式，BLOCKED | 不开放 | — |
| T0 设备 | 只开放读信号；RIL 网络模式按 SupportRecord 决定 | — |

也就是说，开发者模式只能放宽"这个功能本身是否验证过"，不能放宽"失败后能不能恢复"。回退路径没验证过的项，开发者模式下同样拒绝持久写入。

命令控制台只在开发者模式下可用，AT 节点限白名单；控制台发出的写命令同样走事务日志和第 7 节检查。

## 13. 对现有代码的改动

| 模块 | 改动 |
|---|---|
| `band_lock.c` | 新增 `probe --json`，只读；新增 `snapshot <items>`，输出当前值或 UNREADABLE；新增 `batch`，按给定顺序 PUT 并逐项输出 ACK 和读回结果，**不自己决定 SSR**；删除 `nr-no-original` 放行逻辑和 `NR_FULL_SET`；恢复改为由上层传入要写回的字节；超时保护保留在原生层：`alarm(100)` 改为参数 `--deadline-ms`（上层传入，原生层设硬上限，比如 120 秒，超过上限按上限处理）；另外检测父进程退出（stdin 管道 EOF，或 `prctl(PR_SET_PDEATHSIG)`），应用被杀后原生进程自行停止；退出前不发半截命令，正在等 ACK 的步骤保持日志里的 SENDING 状态，由下次启动的恢复处理读回判断；`read_mask_to` 分别返回 Present / Absent / Failed(原因) |
| `BandLockProfile.kt` | 拆成 `DeviceFacts`（探测）和 `SupportRegistry`（验证记录）。现在"模型或 SoC 匹配就 Verified，否则 ProbeOnly 也可以锁"的逻辑要删掉 |
| `BandLockCommand.kt` | `executeWithMode` 固定等 5 秒后切模式，改为等 channelRecovery 完成；改成 `LockRequest` 加各通道的编码器 |
| `BandLockViewModel.kt` | 改为调用 `LockOrchestrator`；启动时检查有没有未完成的事务；分别显示三项证据 |
| 采集层 | 发布的每条记录带上 subId、时间戳和序号，方便 Verifier 去重、判断新鲜度 |
| 新增 | `TransactionJournal`、`Verifier`、`SupportRegistry` 数据文件（签名） |

---

## 14. 实施路线

| 阶段 | 内容 | 产出 |
|---|---|---|
| P0 | `probe --json`、`snapshot`，在 3～5 台机器上采集（只读） | DeviceFacts 样本，确认哪些项真的能读回 |
| P1 | 事务日志、三类状态、Verifier、SupportRegistry；把 SM-S9370 上已验证的操作迁移过来 | 现有功能在新框架下运行，有日志、可回退、三项证据 |
| P2 | 实验室逐项验证：每个"操作＋通道＋格式＋固件"测写入、读回、回退、持久性、VERIFIED_ABSENT | SupportRecord 数据 |
| P3 | AtChannel、通道选择、合并 SSR | 已验证的机型上减少重启次数 |
| P4 | 多 PCI、多频点、5G 模式、厂商服务通道 | 按验证结果逐项开放 |

---

## 15. 待验证问题

1. 每个 NV 项在各固件上，DIAG GET 返回的是真实内容还是回显
2. 哪些固件满足 VERIFIED_ABSENT（删除后 SSR 能否恢复出厂行为）
3. 各即时通道（CM 帧、AT QNW、RIL）在 SSR 后是否保留
4. 双卡设备上 subId、modemStack 与 NV 路径后缀（`_SubscriptionNN`）的对应关系
5. 0x2AB00 与 0x2AB01、MultPciLock 每条 8 字节的字段格式
6. 采集层是否会重复发布同一条缓存记录，以及时间戳的来源
7. 哪些机型切换网络模式会触发基带重启
8. 各固件上有没有可靠的"不存在"判断方式（比如 EFS2 STAT 是否返回 ENOENT，能否和超时、权限错误区分）
9. 每个操作的 `activation` 属性：INSTANT 还是需要 SSR
