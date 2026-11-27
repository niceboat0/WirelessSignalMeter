# 过程事件

2026-11-27。这一层加在现有 RRC / NAS / SIP 解码之上，不替换
`rrc_setup`、`rrc_release`、`meas_config`、`ims_call`、`nas_release` 这些已有记录。

信令是一条消息。过程事件是若干条消息拼出来的请求、进行中、成功、失败或释放。
事件详情列出证据和「不构成」说明，并可以打开同一会话里的对应信令。
任务时间线会记下这些过程事件和信令 `message_id`。这里不展示切换成功率。

名称按过程含义自定，不使用 Pioneer 的 `ET_*` 代码、`CUEventCode.xml` 或私有容器。

## 切换

只在下面两类消息上打开「切换请求」：

- NR/LTE 的 RRC 重配，并且解码看到 `reconfigurationWithSync` 或 `mobilityControlInfo`
- `mobilityFromNRCommand` / `mobilityFromEUTRACommand`

没有上述标志的重配仍然只是原来的 `rrc_reconfig`，不会变成切换。

| 阶段 | 要看到什么 | 不构成 |
| --- | --- | --- |
| 请求 | 上面的命令 | 命令本身不是成功；移动性命令也可能是重定向 |
| 进行中 | 同 RAT 的重配 Complete，或目标 RAT 的 RRC Setup | 单条 Complete 不是切换成功 |
| 成功（同 RAT） | 已经有 Complete，并且随后的 DIAG 服务小区 PCI 变了；若解出了 `targetPhysCellId`，还必须对上 | 这是一次过程，不是切换成功率 |
| 成功（跨 RAT） | 命令之后，目标 RAT 出现 RRC Setup Complete | 不能区分切换和重定向 |
| 失败 | 切换还开着时出现重建请求。只有原因解成 `handoverFailure` 才这么写 | 没有打开的切换时，重建不是掉话，也不是切换失败 |

10 秒内没有后续消息就结束跟踪，不记失败。小区变化如果发生在 Complete 之前，先记住，等 Complete 到了才可能记成功。只有小区变化、没有命令和 Complete，仍然只是观测。

## Attach / TAU / 5GS 注册

用已经解出的 EMM / 5GMM 消息类型。嵌在 RRC `dedicatedNAS` 里的明文 NAS 和独立 NAS 日志会去重。

| 过程 | 请求 | 进行中 | 成功 | 失败 |
| --- | --- | --- | --- | --- |
| Attach | Attach Request | Attach Accept。Accept 不是成功 | Attach Complete，且已经看到 Accept | Attach Reject |
| TAU | TAU Request |  | TAU Accept。没有 Complete 不是失败 | TAU Reject |
| 5GS 注册 | Registration Request |  | Registration Accept 是网络结果，不是终端已发 Complete 的证明。随后的 Registration Complete 另记一条 | Registration Reject |

60 秒没有下文就停止跟踪，不把超时记成失败。Service Request 不在这张表里。

## PRACH 与 RRC 接入

PRACH 来自已经解析的 `0xB889` / `0xB88A`：

- 触发是请求
- 尝试里没有 TA 是进行中。没有 TA 不是失败，日志可能只是没有 Msg2
- 有 TA 是成功，证据只到 Msg2（RAR）。不是 Msg4 竞争解决，也不是 RRC 建立成功

RRC 接入是另一条账：

- Request：Setup / Connection / Resume Request
- 进行中：网络下发的 Setup / Resume。这还不是接入成功
- 成功：对应的 Complete
- 失败：Reject

只有 Complete、没有 Setup 时仍记成功，但标明跟踪里没有建立消息。

## 呼叫与 IMS 注册

来自 `0x156E` 和已有的 logcat SIP 字段。Call-ID 只保留哈希。事件里不写号码、URI 或 Reason 原文。

| 阶段 | 消息 |
| --- | --- |
| 请求 | 第一条 INVITE |
| 进行中 | 100 / 183，以及 180 / 181 |
| 成功 | INVITE 的 2xx |
| 失败 | 3xx–6xx（401 / 407 除外）、CANCEL |
| 释放 | 建立之后的 BYE，Reason 不像异常 |
| 掉话 | 建立之后的 BYE，且 Reason 含 RTP、timeout 或 cause 503 / 408 / 41 / 38 / 47 |

掉话是这条文本启发式，不是运营商掉话计数。已建立对话上的 re-INVITE 不另开一次呼叫。

IMS REGISTER：请求、401/407 进行中、2xx 成功、其他 3xx+ 失败。`Expires: 0` 记成注销。已有的 `ims_call` / `ims_reg` 标签还在。

SIP 详情是一棵脱敏树：方法或状态码、CSeq、Call-ID 哈希、Max-Forwards、Content-Type、过期时间、接入类型、音频编码和是否有视频。不含 From、To、Contact、Via 或原始 Call-ID。

## 界面

信令页顶部是「信令」和「事件」。

- 信令：RRC、NAS、IMS SIP，以及原来的 PDU / QoS / IMS 日志。未知报文仍用菜单开关。
- 事件：过程事件按切换、接入、注册、呼叫过滤。原来的单次标签（测量配置、小区变化、`ims_call` 等）在「其他」。

## 还没做

不写 RCU / CU / DDIB。不复制 Pioneer 事件目录，也不做约 821 / 2145 条事件、层一/层二 PDU 列表、信令对比、KPI、自定义事件、事后重解码或 2G/3G 层三解码。录制生命周期不变：没有测试任务就不新开 QMDL。不在这里做锁网。
