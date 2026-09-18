# Batch F3 - send cost guard (HANDOVER §5 item 4 "发送费用防护")

Base: e3d3984. Plan dir: .planning/2026-09-18-send-cost-guard.
Product premise: every carrier SMS costs ~0.1 CNY per segment; remote SEND_CMD makes the executor pay. User decided:
all three parts, executor switch defaults ON (no behaviour change for existing users).

## Scope
1. Segment count visible on the requester: SmsChatActivity.sendCommand() computes `SmsManager.divideMessage(body).size`
   (pure text, no SIM needed); if > 1 show a confirm dialog "本条将拆成 N 条计费短信，确认发送？" before publishing.
   Single-segment sends unchanged.
2. Executor switch 允许其他设备通过本机发送短信 (`CloudSettingsManager.isRemoteSendAllowed`, key REMOTE_SEND_ALLOWED,
   default true). When off, MqttInboundHandler.handleSendCommand does not touch the dispatcher: replies SEND_CMD_RESULT
   success=false "对方设备已关闭代发" via the durable path (publisher.publishSendCommandResult) so the requester's bubble
   turns FAILED instead of hanging. Settings UI: Switch + hint under the reconnect switch.
3. Daily cap per executing device: `SendCostPolicy.DEFAULT_DAILY_LIMIT = 50` segments per calendar day (device local
   time), counted from `send_commands` rows with createdAt >= start of day and state != FAILED (PENDING/UNKNOWN/SENT
   all cost money or may have). New DAO query `sumSegmentsSince(since)`. Checked in OutgoingSmsDispatcher.submit BEFORE
   the claim (after the duplicate-UUID branch so re-queries of an existing command are never blocked); over limit ->
   IllegalStateException("今日代发已达上限 N 条") -> existing prepare-failed path publishes FAILED. Limit adjustable in
   settings (EditText, 0 = unlimited) - kept minimal: a number field next to the switch.
   Pure logic in `SendCostPolicy` (startOfDay, isOverLimit) with unit tests.

## Invariants
C11 (result via outbox path - publishSendCommandResult already goes through enqueueControl), C13, C18 (no new
wire fields: reuse SendCmdResult.message). No Room schema change (query only) -> no version bump.

## Acceptance
- JVM tests incl. SendCostPolicyTest; assembleDebug + assembleRelease(-x lintVital*).
- Emulator: (a) switch off on executor -> probe SEND_CMD -> RESULT success=false message contains 已关闭代发, no SMS sent
  (no dSIM_Send claim log); (b) set limit 1, send one SEND_CMD (SENT), second -> FAILED 上限; (c) limit back to 0/50,
  SEND_CMD works. Use dsim_probe_w21.py style probe (has SEND_CMD flow).
- c8check + diff --check; one local commit, no push.

## Steps
- [x] S1 plan + task + baseline e3d3984
- [x] S2 SendCostPolicy + test; CloudSettingsManager keys; DAO query; dispatcher + inbound handler; chat confirm; settings UI
- [x] S3 build
- [x] S4 emulator a/b/c
- [x] S5 docs (AGENTS C21, arch doc settings row, HANDOVER §5)
- [x] S6 c8check + commit
