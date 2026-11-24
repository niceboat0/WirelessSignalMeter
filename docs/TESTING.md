# 无线无忧测试指南

2026-11-18 用户已确认 `android-sinr-network-survey` 测试包在原问题场景下
SINR 恢复正常。以下完整回归清单仍需在更多机型及双卡/CA 场景执行。

## 信号、信令和地图回归

1. 在无 Root 或未授权 DIAG 的设备上，分别在 LTE 和 NR 下查看服务小区。检查运营商、注册 PLMN、频点与双工模式。系统未提供 5G 带宽且没有该小区的保存值时，带宽行应隐藏。
2. 在兼容设备开启 Root DIAG，检查信令列表和详情。手动滚动历史记录应停止自动跟随。开始、停止并导出一份 QMDL，确认文件可读取。
3. 导入含经纬度的 CSV 工参，按基站 ID 搜索。打开基站地图并授权定位，检查服务小区蓝线和已匹配实测邻区的橙线。切换小区或关闭连线图层时，旧连线应消失。
4. 导出一次常规 JSONL，确认文件可读取。带宽缺失时可检查 `android_nr_bandwidth` 记录中的 `reported_khz` 和 `cell_matches`。

## LTE SINR 兼容性回归

无 Root LTE SINR 优先对齐 android-network-survey 提交 `4b92316d50a5`。
扫描时直接读取订阅专属 `TelephonyManager.getSignalStrength()`，
取首个 LTE 项的 RSSNR，优先于 CellInfo，包括覆盖其 0/1 或其它值。
公共来源按上游只排除 MIN_VALUE/MAX_VALUE，不裁剪、不除以 10、不使用 CQI。
直接快照不再受时间差、小区数量或样本数量门槛限制。

直接读取为 null/异常时保留同订阅 `SignalStrengthsListener` 缓存补读，
缓存仍限制 10 秒时效。停扫、换卡和销毁时注销监听并清空快照。
公共值缺失时保留明确 LTE 方法、字段及限定 LTE 段文本兼容读取，
这些非标准来源仍限制 -20..30；最后保留 CellInfo 自身有效值补读。
邻区及明确标记的 LTE CA 辅小区不借用主服务快照。
完整上游证据及保留差异见
[ANDROID-NO-ROOT-NETWORK-SURVEY.md](ANDROID-NO-ROOT-NETWORK-SURVEY.md)。

1. 在原来能显示 SINR 的设备上检查 LTE 数值继续更新，核对 0 和负数。
2. 在原来 LTE RSRP/RSRQ 正常、仅 SINR 缺失的设备上，与同机其它工具对照。
   分别验证标准接口和厂商接口上报的场景；固件未提供或系统拒绝反射时仍可能缺失。
3. 双卡切换时核对所选 SIM；LTE CA 辅小区及邻区不应显示借用的主服务 SINR。
4. 核对页面、地图、邻区和导出，缺失时不得出现 MIN_VALUE、MAX_VALUE 或 CQI。
5. 停扫再开始、切卡或重启服务后确认数值继续更新、旧订阅缓存已清理。
6. 构造 CellInfo 为 0/1、快照为 17 的情况，最终必须是 17；
   快照的真实 0 则必须保留。旧时间戳或多份 LTE 样本不能导致有效首项被丢弃。

`LteSinrUtilsTest` 为纯 JVM 测试，覆盖公共值缺失标志、私有/继承方法、
异常回退、缺失 API、字段和公开文本回退、兼容数值边界及 CQI/NR 字段排除。
2026-11-18 使用缓存 JDK 与 JUnit 直接编译运行，当前 21 项通过；
复现脚本为 Git 忽略的 `.temp/test-lte-sinr.ps1`。
`SurveyRecordProcessorTest` 新增四项 LTE 主小区计数与 CA 限制测试，需 Robolectric/Mockito；
本机缺少缓存依赖，尚未运行该 Android 测试套件。
ADB 当前没有连接设备，尚未进行上述真机验证。

### 无 Root 原始值诊断

常规 JSONL 导出包含最近 128 条 `android_lte_sinr`，不需要 Root。
头部策略标记应为 `android_lte_sinr_policy=network_survey_signal_strength_first_v1`。
分别记录双卡注册 LTE 小区的 `cell_rssnr_raw`、`snapshot_rssnr_raw`、
时间、`snapshot_decision`、`selected_sinr_db` 和 `selected_source`。
不会导出完整信号对象、小区身份、号码或定位。切换/重建订阅资源会清空旧采样。
已知旧日志只有 Android 模式和 DIAG 状态，没有这些字段，不能用来判断固件
是否返回过 SINR。旧版证据和字段解释见 [ANDROID-LTE-SINR-390.md](ANDROID-LTE-SINR-390.md)。

```powershell
.\networksurvey\src\test\host\test_android_lte_sinr.ps1
.\networksurvey\src\test\host\test_android_lte_presentation.ps1
.\networksurvey\src\test\host\test_signal_snapshot.ps1
```

第一项编译生产读取器并在 Android 边界使用替身，35 项检查通过；
第二项提取生产 Kotlin 显示合并与诊断序列化，使用真实 protobuf / JSON 库，
29 项记录/显示数据/导出检查通过；不运行 Compose UI。
第三项直接提取生产方法，验证直接读取优先以及读取 null/异常时使用缓存。
它们不能替代同机验证。新包装机后，两张卡分别在 LTE 下采样 15-30 秒，
导出 JSONL；若 `selected_sinr_db` 有值而界面为空，继续检查 UI 和所选 SIM。

## Android ICU 正则回归

用户提供的 v1.2.0 异常链指向 LTE 工具类静态初始化：
未转义的 `}` 导致 `PatternSyntaxException`（index 111），再被包装为
`ExceptionInInitializerError`。生产代码已将 Java 字符串内的 `}\\s*$`
改为 `\\}\\s*$`。即使直接读取到有效 SINR，首次调用该工具类仍会编译
所有静态 Pattern，因此这不是仅在文本回退时才会触发的问题。

Windows 下用系统 ICU 验证实际生产 Pattern，不用 JDK 的编译结果代替：

```powershell
.\networksurvey\src\test\host\test_lte_regex_icu.ps1
# 旧实现应失败，复现 U_REGEX_RULE_SYNTAX at index 111：
.\networksurvey\src\test\host\test_lte_regex_icu.ps1 -SourceRevision 03ecd62
```

ICU 72.1.0.4 上，旧实现失败、修改后的两个 Pattern 通过。
这是 Windows ICU 主机验证，不是 Android 真机结果。
新增 `LteSinrAndroidTest` 在 Android 引擎上检查类初始化、右花括号、SINR 0
及 NR 值排除；有连接设备和测试依赖时执行：

```powershell
.\gradlew.bat :networksurvey:connectedCdrDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.craxiom.networksurvey.util.LteSinrAndroidTest"
```

本轮未执行该 instrumentation 套件。升级到带 ICU 修复的新包后，重点验证
4G 冷启动、4G/5G 切换和 LTE CA；需要先重新启动应用使修正后的类重新初始化。
本轮构建的 `WirelessSignalMeter-1.2.0-lte-icu-fix-20261118.apk` 已检查最终 DEX
含转义后的正则、没有旧版终止表达式；校验信息见 `CRASH-COMPATIBILITY.md`。

## 4G 闪退异常边界回归

用户补充故障发生于 4G、5G 正常。现代 CellInfo 回调补读 SignalStrength
时曾允许运行时异常或链接错误向外逃逸；现在保留原 CellInfo 处理，并持久记录
首次连续失败。崩溃日志附带最近扫描的时间、订阅 ID、LTE/NR 数量和总数。
这是此前独立发现的异常出口；用户后来提供的 index 111 堆栈对应上面的正则
初始化缺陷，不能把本节保护当作该堆栈的修复。

```powershell
.\networksurvey\src\test\host\test_signal_snapshot.ps1
# 对照旧实现，不更改工作区；预期因缓存优先或异常逃逸而失败：
.\networksurvey\src\test\host\test_signal_snapshot.ps1 -SourceRevision 03ecd62
```

脚本直接提取生产方法，伪造 Android 和日志边界；当前实现覆盖直接读取优先、
直接读取、缺失、运行时异常、链接错误、持久日志、连续失败去重、恢复，
并检查致命 VM 错误仍向外传播。旧实现失败、当前实现通过。
此测试不能替代 Android Binder、UI 或真机测试。

真机还需覆盖：4G 冷启动、4G/5G 切换、停扫重启、双卡切换及 LTE 空闲/数据传输，
分别记录打开的页面与 Root DIAG 状态。当前主界面没有可达的日志导出或上传入口，
Release 应用私有文件也不能直接用普通 `run-as` 读取。连接故障手机并允许 USB 调试后，
在 PowerShell 中开始采集，再打开应用并复现 4G 闪退，最后按 Ctrl+C：

```powershell
$taskAdb = Join-Path $env:LOCALAPPDATA 'Android/Sdk/platform-tools/adb.exe'
& $taskAdb logcat -v threadtime -b main -b system -b crash > lte-crash.log
```

若已经闪退，可立即执行 `adb logcat -b crash -d -v threadtime` 查看尚未被覆盖的
崩溃缓冲区；它不包含所有普通采样告警。反馈时同时提供机型、Android 版本、
安装包名称及触发步骤。未在本轮自动采集或上传任何手机日志。

## 路测回放回归

1. 从工具页进入路测回放，加载本机打点。相同运营商的双卡应分为不同路线，超过两分钟的采样间隔应分段。
2. 导入旧版中文地图 CSV，以及包含 `deviceTime`、`ssRsrp`、`ssSinr`、`narfcn` 的 NR CSV。检查频点、PCI 0 和 SINR 0，导出后重新导入应保留数据。
3. 播放、暂停和拖动时间轴，切换 RSRP / SINR，选择弱覆盖路段后检查定位。缺失信号应显示为灰色，长时间断点和异常 GPS 跳点不应连线。
4. 弱覆盖按 RSRP 低于 -110 dBm 统计，连续路段至少包含三个采样点且持续五秒。恰好 -110 dBm 不算弱覆盖。
5. 暂停自动打点后加载路线，返回基站地图核对开关状态。升级数据库后旧坐标应保留，旧数据的 SIM 卡槽为未知。

自动测试：

```powershell
.\gradlew.bat :networksurvey:testCdrDebugUnitTest --tests '*RoadCoverageTest' --offline
```

`RoadCoverageTest` 包含旧 CSV 往返、原生 NR 列名、双卡分轨、缺失采样、GPS 跳点、阈值边界、无信号列和超过 50,000 行的文件检查。

## 锁网原生主机回归

Windows 下使用项目 NDK 直接执行协议、原始掩码备份与恢复测试：

```powershell
.\networksurvey\src\test\cpp\test_band_lock_host.ps1
```

测试编译真实生产函数，隔离设备和文件操作；覆盖 LTE 原掩码正确保存、
读取失败时限制持久锁定、备份创建/写入/同步失败，以及 NR 备份失败与缺失标记区分。
2026-11-18 两套主机测试通过，原实现会在新增备份回归中失败。

## QMDL2 容器回归

```powershell
.\networksurvey\src\test\cpp\test_qmdl2_host.ps1
.\networksurvey\src\test\cpp\test_nr_config_host.ps1
```

`qmdl2_test.c` 校验容器字节布局（8 字节 magic、记录类型 1、payload 长度、秒/微秒字段、
DIAG_LOG_F 前导与数组原样拷贝），`test_nr_config_host.ps1` 里的 `test_qmdl2_records`
用 stub 校验挂接：开始录制即写 magic、记录不外泄 HDLC 转义、时间戳取墙钟、关闭干净。
采集器默认仍是 v1 容器，`--qmdl2` 才切换；App 侧录制固定传 `--qmdl2`。

## 海外服务清理后的数据库迁移

`SurveyDatabase` v14 -> v15 清理海外服务表和上传标记，但保留六类本地测量记录、
Mission ID、观察列表和命中历史。Android 13 自带 SQLite 3.32.2，不支持
`DROP COLUMN`，因此迁移采用建表、复制、重命名，并保留自增 ID 的历史上限。

```powershell
node networksurvey/src/test/host/test_survey_migration.cjs
.\networksurvey\src\test\host\test_battery_pause.ps1
```

该检查从生产 Java 迁移直接提取 SQL，在主机 SQLite 上分别执行有记录及记录已
清空的 v14 升级；校验 NULL、SINR 0、64 位 NCI、记录 ID、自增序列、观察列表
和外键完整性。测试夹具是清理前 Room v14 的建表结构，不含用户数据。
电池暂停测试直接编译生产模型，校验 11 个本地标志的独立保存恢复及清理，
包含旧 JSON 的 CDR 和设备状态；只替换 Android Parcel 和日志边界。

Release 构建生成 Room 实现后，还可核对迁移结果与新装数据库的一致性：

```powershell
node networksurvey/src/test/host/test_survey_migration.cjs --room-schema `
  networksurvey/build/generated/ksp/cdrRelease/kotlin/com/craxiom/networksurvey/logging/db/SurveyDatabase_Impl.kt
```

以上是主机检查，不是 Android instrumentation。真机升级仍需安装在有历史记录的
旧版之上，检查记录、观察列表、地图打点及测速历史；不能通过卸载重装替代升级验证。

## 上游电源管理测试

锁网协议、限制、本地验证结果和真机验收步骤见 [锁网工具](LOCKBAND.md)。

以下为继承自 Network Survey 的 Doze 和 Wake Lock 测试步骤，需在连接了 Android 设备的环境中执行。

## Power Management Tests

These tests verify that Network Survey continues to function properly when Android's power management features (like Doze mode) are active.

### Test #1: Doze Mode Behavior

This test verifies that the app continues logging data even when the device enters Doze mode.

#### Prerequisites

- Device connected via USB with ADB debugging enabled
- App running with at least one survey active (cellular, wifi, etc.)

#### Steps

1. **Start monitoring logs in a terminal:**
   ```bash
   adb logcat | grep -E "Wake lock|Device status|Doze"
   ```

2. **Force device into Doze mode:**
   ```bash
   adb shell dumpsys deviceidle force-idle
   ```
   
   You should see output like:
   ```
   Now forced in to idle mode
   ```

3. **Verify Doze mode is active:**
   ```bash
   adb shell dumpsys deviceidle get deep
   ```
   
   Should return: `IDLE`

4. **Monitor for 5-10 minutes**
   - Check if device status messages continue being generated
   - Look for any "Wake lock" related messages in logcat
   - Verify CSV files continue to receive entries

5. **Exit Doze mode:**
   ```bash
   adb shell dumpsys deviceidle unforce
   ```

6. **Reset device idle state (optional):**
   ```bash
   adb shell dumpsys deviceidle reset
   ```

#### Expected Results

- Device status messages should continue at regular intervals
- Wake lock should remain active during Doze
- No gaps in CSV logging

---

### Test #2: Wake Lock Monitoring

This test verifies proper wake lock management - acquisition when surveys start and release when they stop.

#### Steps

1. **Check initial wake lock state (no surveys running):**
   ```bash
   adb shell dumpsys power | grep -A 5 "NetworkSurvey"
   ```
   
   Should show no wake locks held.

2. **Start a survey (any type)**

3. **Check wake lock is acquired:**
   ```bash
   adb shell dumpsys power | grep -A 5 "NetworkSurvey"
   ```
   
   Should show something like:
   ```
   (com.craxiom.networksurvey.dev) - ACQ NetworkSurvey:WakeLock (partial)
   ```

4. **Start additional surveys** (e.g., if cellular is running, start wifi)
   - Run the wake lock check again
   - Should still show only ONE wake lock (not multiple)

5. **Stop one survey** (but leave others running)
   - Check wake lock is still held:
   ```bash
   adb shell dumpsys power | grep -A 5 "NetworkSurvey"
   ```

6. **Stop ALL surveys**
   - Check wake lock is released:
   ```bash
   adb shell dumpsys power | grep -A 5 "NetworkSurvey"
   ```
   - Should show no wake locks

#### Additional Wake Lock Monitoring

**Check battery stats for wake lock usage:**
```bash
adb shell dumpsys batterystats | grep -A 10 "NetworkSurvey"
```

**Monitor wake lock acquisition/release in real-time:**
```bash
adb logcat | grep -E "Acquiring wake lock|Releasing wake lock"
```

**Check if app is in battery optimization whitelist:**
```bash
adb shell dumpsys deviceidle whitelist | grep networksurvey
```

#### Expected Results

- Wake lock acquired when ANY survey starts
- Wake lock maintained while ANY survey is active
- Wake lock released ONLY when ALL surveys stop
- Only one wake lock instance at a time

---

## Task Association and Event Replay

On Windows with the cached project Kotlin dependencies and Android Studio JBR:

```powershell
.\networksurvey\src\test\host\test_road_events.ps1
node .\networksurvey\src\test\host\test_task_schema.cjs
```

The first command compiles the production Kotlin matcher, observation tracker and CSV code.
It covers task/SIM/clock isolation, stale positioning, device-only speed locations,
old/new CSV round trips, task-boundary backlog and a 50,000-point / 10,000-event window.
The second executes extracted production SQL on host SQLite; it is not an Android test.

`SurveyTaskStoreAndroidTest` checks the actual Android database lifecycle and captured-task
completion in an isolated test database. Run it with a connected device via the existing
Android instrumentation runner. It has not been run on the development machine.

Device checks: create a task; collect two SIMs; change LTE/NR cells; open road events;
select an event and then its map marker; verify the same row/sample is selected.
Try unavailable GPS, poor accuracy, navigation away/back, process restart, explicit task end,
and starting a new task during a repeated speed test. Old speed results must stay on the old
task, unknown DIAG subscription ownership must not be guessed, and default-route speed
locations must not be labeled as a SIM's throughput.

See [TASK-EVENT-MAP.md](TASK-EVENT-MAP.md) for export formats, limits and foreground-only scope.

## Native APK Compatibility

On Windows, check the assembled APK's ELF LOAD segments with the Android NDK:

```powershell
.\tools\check-apk-native-alignment.ps1 -ApkPath <release-apk>
```

The check rejects 4 KB-only arm64/x86_64 libraries even when `zipalign` passes.
Also run `apksigner verify` and `zipalign -c -P 16 4` separately. The script is
an artifact check, not a substitute for testing on 4 KB and 16 KB devices.

See [CRASH-COMPATIBILITY.md](CRASH-COMPATIBILITY.md) for the 2026-11-18 findings
and pending map/device checks.

## Future Test Sections

_This section will be expanded with additional test categories:_

- [ ] Survey Functionality Tests
- [ ] Data Export/Import Tests
- [ ] Network Connectivity Tests
- [ ] UI/UX Tests
- [ ] Performance Tests
- [ ] Compatibility Tests
