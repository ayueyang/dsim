# Findings
- SEND_CMD sends before UUID existence check; QoS 1 duplicates can submit repeatedly.
- sentIntent is null; current success means API returned, not SMS sent.
- currentBroker starts nonempty, shadowing prefs on sticky service recreation.
- SEND_CMD_RESULT accepts empty targetDeviceId.
- serviceScope not cancelled on destroy.
- Existing old plan confirmed default SMS role can be granted despite stub business methods; do not claim otherwise.
- Current protocol lacks recovery outbox; first batch must not pretend it fixes offline delivery.


## Verified repair invariants
- Room v6 claim table uses INSERT OR IGNORE, never REPLACE. 30 concurrent claim calls yield exactly one winner in device test.
- Pending sms_messages rows and send_commands claims are separate; the initiating device pre-insert does not suppress its first execution.
- UUID claims survive DB reopen and conversation deletion. Legacy outgoing rows are conservatively tombstoned on upgrade.
- Source-only success assignment removed: system sent callbacks aggregate all parts, and late callbacks can resolve UNKNOWN.
- Null-intent config resolution tested with a private SSL Broker; no in-memory public default can shadow it.
- Non-exported sent receiver completes its async broadcast before best-effort Broker publication.
- Existing script successfully checked receiving/Room/notification/service (8/8), not outgoing carrier behavior.
