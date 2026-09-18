# Batch F3 progress

## S1 baseline e3d3984. Task tsk_7d4435dbf559f4c2.

## S2 code
- `SendCostPolicy` (pure): startOfDay / isOverLimit (inclusive, PENDING+UNKNOWN+SENT count) / sanitizeLimit / messages.
  `SendCostPolicyTest` 4 cases (72/72 total).
- CloudSettingsManager: REMOTE_SEND_ALLOWED (default true), REMOTE_SEND_DAILY_LIMIT (default 50, clamp 0..10000).
- DsimDao.sumSendSegmentsSince(since) - query only, no schema change.
- MqttInboundHandler.handleSendCommand: switch gate before dispatcher; unknown UUID -> publishSendCommandResult(false,
  REMOTE_SEND_DISABLED_MESSAGE) (durable path, W21); known UUID still goes to the dispatcher for a result re-query.
- OutgoingSmsDispatcher.submit: limit check after the existing-UUID branch and before claim; throws -> existing
  prepare-failed path -> FAILED result.
- SmsChatActivity.sendCommand: divideMessage(body).size > 1 -> AlertDialog confirm -> dispatchSendCommand().
- Settings: Switch + EditText(limit) + usage TextView under the reconnect switch; commit on focus loss / IME done.

## S3 build: WMI gradle DONE exit=0 04:44; JVM 72/72; debug+release APKs.

## S4 emulator (API 36, probe = dsim_probe_w21.py sendcmd ICCID_89860318640220133814; prefs edited via
   run-as cat -> host -> push -> run-as cp, force-stop first; script C:\Users\admin\AgentDock\dsim_f3.ps1)
- baseline (allowed, limit 50): SEND_CMD_RESULT SENT true 1.7 s, sent-sync status 1.
- switch off: RESULT success=false 1.3 s, message 对方设备已关闭「允许其他设备代发」，已拒绝; log "Rejected SEND_CMD from
  peerprobeW5: remote send disabled"; no dSIM_Send claim; no sent-sync.
- limit 2 with today=2 already: rejected (dSIM_Send "daily segment limit 2 reached (today=2, requested=1)").
- limit 3 (today=2): #1 SENT true 1.3 s; #2 FAILED 对方设备今日代发已达上限（3 条）, log today=3. Limit restored to 50.
- Note: PowerShell console shows the Chinese message as GBK mojibake; the wire payload is UTF-8 JSON (decoded by the
  Python probe, printed through the GBK console).

## S5 docs: AGENTS C21 + component line; REFACTORING test list; arch doc settings row + constants; HANDOVER §5 item 4.
## S6 commit below.
