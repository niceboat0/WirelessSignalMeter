# 后台采集、悬浮窗、IMS 与多机型锁频

本页对应 `feature/background-overlay-ims-lock` 的首版实现。目标是可测试、可降级，不把未验证路径写成已经适配。

## 功能

### 后台采集

- 采集生命周期从界面 `LifecycleStartEffect` 中拆出：离开前台时，若开启了“后台采集”且用户未手动停止，采集进程继续运行。
- `RootDiagCollectorService` 以前台服务（`dataSync`）和通知维持进程。通知可停止采集。
- 使用进程级 `RootDiagViewModelStore`，避免 Activity 销毁后 `viewModelScope` 被取消。
- 尽力在熄屏、切应用后保持采集。强制停止应用、系统杀进程或用户点通知停止后，采集会结束。不会绕过 force-stop。
- 进程清理只结束本应用拉起的采集子进程和自己的 `logcat` 子进程，不使用 `killall` 或 `logcat -c`。

### 悬浮窗

- 需要用户在设置里主动打开。未授予“显示在其他应用上层”时保持关闭，并提示用户可自行前往系统设置；应用不会自动跳转。
- 拒绝权限时降级为无悬浮窗，采集不受影响。
- 窗口可拖动、可关闭，只显示少量可配置状态（采集/射频/IMS/解码丢弃）。
- 后台显示悬浮窗需要同时开启后台采集，因为窗口由前台服务添加。

### 解析解耦

- DCI 回调先写 QMDL，再把副本交给解码线程。信令和测量走 32 × 65535 字节的高优先级队列。按 TB、CQI 和功率的样本走同样大小的低优先级队列。
- 两条队列满时丢弃对应解码、保留原始 QMDL。丢弃次数、高水位和超长计数写入 `status` JSON（`decode_dropped` 等）。解码线程先排空高优先级队列，样本不会插到信令前面。
- NR 累积计数（PDSCH `0xB888` 和 PDCP/RLC/MAC 层统计）只保留每个日志码尚未解码的最新一帧。新帧替换旧帧，替换次数写入 `decode_coalesced`，不计入 `decode_dropped`。计数器是累积值，下一次解出的差值仍覆盖这段间隔。
- 停止时置位并 flush 队列。

### 多机型 IMS

- Provider 架构：`samsung_logcat`（三星 AP 侧 `logcat -b radio -b main -v epoch -T 1`）、`qualcomm_diag`（0x156E 等，作为其他高通机型补充）、`mediatek` 明确 `unsupported`。
- 接到已有 `uc_sip()` 状态机。RCS 方向标记会被跳过。注册/呼叫/振铃/接听/结束/编码/RAT 带来源或置信标记；推断注册标记为 `reg_inferred`。
- logcat 在约 20 秒内看不到三星 IMS 标签时，状态为 `unavailable`（不可用），而不是未注册。
- 源头脱敏，不把原始 SIP 或私密标识写入应用文件。内存中的信令页仍有保留上限。
- 未实现也不声称存在联发科或其他厂商的 IMS 后端。

### 锁频 / 锁网

- 在现有探测之上增加 `BandLockProfiles`：按厂商/型号/SoC/硬件和现场通道探测分类为已验证、仅探测、不支持。
- 已验证：三星 SM-S9370 / SM8750，且探测到 DIAG 通道。
- 其他高通机型若探测成功，属于“仅探测”，确认框额外警告未验证；不会把三星固定偏移或命令直接当成通用实现。
- 联发科、Exynos、麒麟以及探测失败的机型为不支持，按钮不可用。不会伪造后端。
- 只有用户确认后才下发命令；确认文案说明可能断网或影响通话。命令行同样需要确认，且仅在探测通过时可用。恢复/清除入口保持不变。
- 只凭静态推测、或依赖商用服务/授权/加固的路径仅在文档说明，没有伪实现。本仓库开发过程中不会向已连接手机下发锁频命令。

### 隐私

- 诊断 JSONL 导出（含测量样本和邻区探测样本）统一走 `redactDiagnostic`。
- 导出头增加 QMDL 含完整信令的说明。开始录制 QMDL 的对话框也有同样提示。原始 QMDL 仍含完整报文，不能当脱敏数据分享。

## 兼容矩阵

| 能力 | 已真机验证 | 静态适配 / 探测后可用 | 不支持 |
| --- | --- | --- | --- |
| Root DIAG 采集 | 三星 SM-S9370（SM8750 / sun）历史验证 | 其他能打开同一类 diag-router 通道的高通机型，需真机确认 | 无 Root、无兼容 DIAG 通道 |
| 后台采集 / 通知 / 悬浮窗 | 未在本轮真机验证 | 通用 Android 前台服务与 `SYSTEM_ALERT_WINDOW` 实现 | 强制停止后不能保持；未授权悬浮窗时无窗口 |
| 三星 AP IMS（logcat） | 解析格式来自既有 SM-S9370 日志；本轮未重新跑真机 | 其他输出相同 `SIPMSG` / `#IMSREGI` 等标签的三星设备 | logcat 看不到这些标签时显示不可用 |
| 高通 DIAG IMS（0x156E 等） | 该三星机型历史上 0 包 | 其他会输出这些日志码的高通设备 | 无包时不把状态显示成已注册 |
| 联发科 IMS / 锁频 | — | — | 明确不支持，无后端 |
| 锁频 / 锁网 | SM-S9370 既有路径（含回包与备份修复） | 探测成功的其他高通机型（未验证布局） | 探测失败、联发科、Exynos、麒麟、未知通道 |

## 测试命令

主机 C 测试（不访问手机）：

```bash
networksurvey/src/test/cpp/test_diag_host.sh
```

Kotlin 分类测试（需要 JDK 与 Android SDK 的 Gradle 单元测试）：

```bash
./gradlew :networksurvey:testCdrDebugUnitTest --tests com.craxiom.networksurvey.ui.bandlock.BandLockProfileTest
```

若环境有完整 SDK，也可编译安装包：

```bash
./gradlew :networksurvey:assembleCdrRelease
```

NDK 交叉编译采集器（本环境用 NDK r27 clang，不访问手机）：

```bash
clang --target=aarch64-linux-android33 -std=gnu17 -O2 -fPIE -pie \
  networksurvey/src/main/cpp/root_diag.c -o libwireless_root_diag.so -ldl -llog -lm
```

## 本轮构建与测试（2026-11-20 08:43 CST，Asia/Shanghai）

在开发机上完成，未连接手机，未下发锁频命令。

- `networksurvey/src/test/cpp/test_diag_host.sh`：`diag_queue_test ok`，`ims_logcat_test ok`
- NDK r27 clang 交叉编译 `root_diag.c` 得到 AArch64 可执行文件，含 `ims_provider` / `decode_dropped` 等字段
- `./gradlew :networksurvey:testCdrDebugUnitTest --tests com.craxiom.networksurvey.ui.bandlock.BandLockProfileTest --tests com.craxiom.networksurvey.ui.bandlock.BandLockCommandTest`：12 项通过（Profile 5，Command 7），0 失败
- `./gradlew :networksurvey:assembleCdrDebug` 成功。本地产物（不入库）：`networksurvey/build/outputs/apk/cdr/debug/WirelessSignalMeter-1.2.0-cdr-arm64-v8a-debug.apk`，其中包含 `libwireless_root_diag.so` 和 `libwireless_band_lock.so`。SHA-256：`031f76311818ad5fea217b357d799f7d553b569f63738464626980056ac3087e`

未运行 `assembleCdrRelease` 与仪器/真机测试。

## 尚未真机验证

- 后台采集在熄屏、切应用、通知停止中的行为
- 悬浮窗授权、拒绝降级、拖动与关闭
- 新接入的三星 logcat IMS 在当前系统版本上的标签稳定性
- 非 SM-S9370 机型的 DIAG 通道、IMS 和高通锁频探测
- 解析队列在高速率 RRC 下的丢弃情况与 UI 指示

## 许可证

沿用仓库现有 Apache 2.0 / 来源声明。本轮不复制参考商用 APK 的受保护源码，也不涉及授权、DRM 或加固绕过。


## 2026-11-20 多设备能力跟进

在同一 PR 上补充了运行时能力矩阵、双卡显示与 subscriptionId、采集/logcat 有限退避重连、Android IMS 注册 API 降级，以及失败原因展示。默认设置未改。详细清单、合成测试与剩余真机项见提交说明；独立报告在开发机 `/workspace/out/WirelessSignalMeter_optimization_followup.md`。
