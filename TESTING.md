# dSIM 测试指南（AI 可无人值守执行）

本文件描述如何在这台机器上把 dSIM 跑起来并验证它。目标读者是**AI 智能体与人类协作者**，因此所有步骤都是可直接执行的命令，并标注了实测结论与踩坑点。

配套脚本：

| 脚本 | 作用 |
|---|---|
| `scripts/emu-up.sh N` | 启动 N 台独立模拟器设备 |
| `scripts/emu-down.sh` | 关闭全部实例并清理残留锁 |
| `scripts/uiauto.sh ...` | adb + uiautomator 的 UI 驱动（dump / tap / type / wait / 收键盘） |
| `scripts/onboard-device.sh <serial> <topic> [password]` | 无人值守完成首次引导 + 云端配置 + SIM 绑定 |
| `scripts/pull-app-db.sh <serial>` | 拉取并可直接用 sqlite 读取的应用数据库 |

---

## 0. 前置自检（每次开工先跑）

```bash
SDK="$HOME/AppData/Local/Android/Sdk"
"$SDK/platform-tools/adb.exe" devices
```

### 0.1 唯一的硬性前置：宿主出站 IPv6 必须可用

模拟器的 modem 字符设备**硬编码走 IPv6 回环**（`-chardev socket,host=::1,...,ipv6,id=modem`）。
宿主的出站 IPv6 一旦被拦，modem 就挂不上，表现为 **SIM 不激活、短信注不进去、`adb emu sms send` 返回 OK 但毫无反应**。

```bash
python -c "
import socket
s=socket.socket(socket.AF_INET6); s.bind(('::1',0))
c=socket.socket(socket.AF_INET6); c.connect(('::1', s.getsockname()[1])); print('IPv6 OK')
"
```

报 `PermissionError [WinError 10013]` 即被拦。**本机曾由 sing-box 的 TUN 模式触发，关闭 TUN 后恢复。**

宾客侧快速判定（`simSlotIndex=-1` 就是没挂上）：

```bash
adb -s emulator-5554 shell "dumpsys isub | grep -E 'mSimState|simSlotIndex' | head -2"
# 期望：mSimState[0]=LOADED 且 simSlotIndex=0
```

---

## 1. 启动设备

```bash
bash scripts/emu-up.sh 2        # A=5554 (dSIM_B)  B=5556 (dSIM_C)
```

启动参数的三条硬约束（脚本里已固定，改动前请读脚本头注释）：

1. **必须 `-feature -Vulkan`** —— headless 下不禁用会卡在 lavapipe 软件 Vulkan 初始化，
   表现为进程活着但 5554/5555 端口永不监听。
2. **必须用独立 AVD**，不能用 `-read-only` 双开同一 AVD
   —— 两实例会共享 `ANDROID_ID`，应用无法区分设备。
3. **必须作为受管后台任务启动**。`nohup ... &` 会在该条命令结束时被连同进程树回收。

不要传 `-netsim-args "-i N"`（实测无效且会弄坏蓝牙），也不要传 `-dns-server`
（显式指定会让宾客完全无法解析域名）。

---

## 2. 装包与配置（一键）

```bash
bash scripts/onboard-device.sh emulator-5554 "dsim/test/your-topic" "yourPassword"
bash scripts/onboard-device.sh emulator-5556 "dsim/test/your-topic" "yourPassword"
```

脚本内部完成：装 APK（覆盖安装）→ 授权 8 项权限 → 设为默认短信应用 → 走完 6 步引导
（反诈告知 → 权限 → 默认短信 → 云端配置 → SIM 绑定 → 完成）。

**两台必须用同一个 topic 与 password**，否则不在同一个同步组里。

### 引导页的三个坑（脚本已处理，手工操作时务必注意）

| 坑 | 现象 | 处理 |
|---|---|---|
| 反诈告知有 8 秒倒计时 | 倒计时未结束时点确认被拒 | 等按钮文案变成「我已了解风险，继续使用」再点 |
| **未确认反诈前选择使用模式会被静默忽略** | 点了「双向同步」但徽章仍是「本地模式」 | 必须先确认反诈，再选模式 |
| 软键盘遮挡下半屏按钮 | 点 y>1400 的按钮"没反应"，实际把字符打进了聚焦的输入框 | 点击前先 `input keyevent KEYCODE_BACK` 收键盘（`uiauto.sh` 已自动做） |
| `input text` **不认识中文** | 中文输入后是乱码或空 | 只用于 ASCII；中文文案只能用 tap/wait 匹配 |
| `input text` **把空格当参数分隔符** | 传 `"Hello World"` 只输入 `Hello` | 转义成 `Hello%sworld`（`uiauto.sh type` 已自动处理） |

---

## 3. 验证单机短信链路

```bash
A="emulator-5554"
adb -s $A shell am start -n com.example.dsim/.SmsListActivity   # 必须！见下
adb -s $A logcat -c
adb -s $A emu sms send 10086 "【测试】验证码 246810"
sleep 8
adb -s $A logcat -d | grep dSIM_Receiver
```

期望输出：

```
D dSIM_Receiver: Captured incoming SMS action=android.provider.Telephony.SMS_DELIVER, ...
```

> **Android 16 上新装（或刚重启）的应用处于 stopped 状态，不先启动一次收不到任何广播。**
> 漏掉这一步会把"注入失败"误判很久。

### 3.1 落库验证

```bash
bash scripts/pull-app-db.sh emulator-5554
python -c "
import sqlite3
c=sqlite3.connect('tmp_dbpull/emulator-5554/dsim_core_database')
for r in c.execute('SELECT id,address,body,type,status,simId,mappingKey FROM sms_messages ORDER BY id'):
    print(r)
"
```

> 必须三件套一起拉（db + wal + shm）。Room 默认 WAL 模式，**只拉 .db 会得到 4 KB 空头文件**。
> 另外 Android 镜像里没有 sqlite3 二进制，不能在设备上直接查。

### 3.2 双接收器互斥验证（覆盖 AGENTS.md 的 C6 约束）

```bash
adb -s $A shell cmd role remove-role-holder android.app.role.SMS com.example.dsim
adb -s $A logcat -c
adb -s $A emu sms send 10086 "非默认应用测试"
sleep 8
adb -s $A logcat -d | grep dSIM_Receiver      # 期望 action=...SMS_RECEIVED
adb -s $A shell cmd role add-role-holder android.app.role.SMS com.example.dsim
```

已实测：默认短信应用时走 `SMS_DELIVER`，移除角色后走 `SMS_RECEIVED`，**不会重复处理**。

> 重装 APK 后运行时权限会被重置，`RECEIVE_SMS` 没授予时系统会在入队阶段直接跳过广播
> （`dumpsys activity broadcasts` 里能看到 `skipped by policy at enqueue: Permission Denial ... requires
> android.permission.RECEIVE_SMS`），表现为「一条日志都没有」。先 `pm grant` 再测：
> `for p in RECEIVE_SMS READ_SMS SEND_SMS RECEIVE_MMS READ_PHONE_STATE READ_PHONE_NUMBERS READ_CONTACTS POST_NOTIFICATIONS; do adb -s $A shell pm grant com.example.dsim android.permission.$p; done`

### 3.2.1 接收器权限限定验证（覆盖 C15，批次 C 新增）

两个接收器都必须带 `android:permission="android.permission.BROADCAST_SMS"`。检查清单并确认真实投递未被挡：

```bash
# 1) 清单里两个接收器都有权限限定（debug 与 release 都要查）
AAPT=$LOCALAPPDATA/Android/Sdk/build-tools/36.1.0/aapt2
$AAPT dump xmltree app/build/outputs/apk/release/app-release-unsigned.apk --file AndroidManifest.xml \
  | grep -A4 -E 'SmsReceiver|SmsReceivedReceiver' | grep -c permission     # 期望 2

# 2) 真实投递仍然成功（这才是有效证据）
adb -s $A shell cmd role add-role-holder android.app.role.SMS com.google.android.apps.messaging
adb -s $A logcat -c && adb -s $A emu sms send 10010 "perm-probe" && sleep 8
adb -s $A logcat -d | grep dSIM_     # 期望 Captured ... action=...SMS_RECEIVED + outbox sent=1 remaining=0
adb -s $A shell cmd role add-role-holder android.app.role.SMS com.example.dsim
```

**不要**用 `adb shell am broadcast -a android.provider.Telephony.SMS_RECEIVED` 判定修复是否生效：
该动作是 protected broadcast，shell（uid 2000）在任何版本下都会被拒（`Permission Denial: not allowed
to send broadcast`），加不加 `BROADCAST_SMS` 结果都一样。

### 3.2.2 调试面板不在 release（覆盖 W15，批次 C 新增）

```bash
$AAPT dump xmltree app/build/outputs/apk/release/app-release-unsigned.apk --file AndroidManifest.xml | grep -c MainActivity   # 期望 0
$AAPT dump xmltree app/build/outputs/apk/debug/app-debug.apk           --file AndroidManifest.xml | grep -c MainActivity   # 期望 2
```

debug 包里「设置 → 测试功能」应仍可进入（`dumpsys activity activities | grep topResumedActivity`
显示 `.MainActivity`）。注意 `MainActivity` 是 `exported=false`，用 `adb am start` 一定被拒，
这属于预期，必须从应用界面点进去验证。

### 3.3 通知验证

```bash
adb -s $A shell "dumpsys notification --noredact | grep -E 'pkg=com.example.dsim|dsim_loud_v1' | head -3"
```

期望：`channel=dsim_loud_v1`、`importance=4`、带 1 个动作（验证码复制）。

---

## 4. 验证多设备联调

两台按 §2 配置好、且已连上同一 topic 后：

```bash
sleep 30   # 等一个快照周期（DEVICE_SNAPSHOT_INTERVAL_MS = 20000）
bash scripts/pull-app-db.sh emulator-5554
bash scripts/pull-app-db.sh emulator-5556
python -c "
import sqlite3
for tag,ser in (('A','emulator-5554'),('B','emulator-5556')):
    c=sqlite3.connect('tmp_dbpull/%s/dsim_core_database'%ser)
    print('---', tag, '---')
    for r in c.execute('SELECT deviceId,isLocalDevice,source FROM device_profiles ORDER BY isLocalDevice DESC'):
        print('   ', r)
"
```

**已验证的期望结果**（两台互见）：

```
--- A ---      ('d9e0118b7d57f7c6', 1, 'LOCAL')
               ('96fad9a2276863b0', 0, 'REMOTE')
--- B ---      ('96fad9a2276863b0', 1, 'LOCAL')
               ('d9e0118b7d57f7c6', 0, 'REMOTE')
```

其他可查的联调证据：

```bash
# 前台服务在跑
adb -s emulator-5554 shell "dumpsys activity services com.example.dsim | grep -c isForeground=true"

# 已订阅 <topic>/+ 并发布到 <topic>/<deviceId>；PONG 只在状态变化或每 120 秒发一次
adb -s emulator-5554 logcat -d -s dSIM_SyncService:D | grep -E "subscribed|PONG published" | tail -3
```

---

## 5. 已知限制与对应处置

### 5.1 ✅ 模拟器各实例共用同一 ICCID —— 已用 ICC Profile 根治（实测）

**现象**：两台模拟器报告的 ICCID 都是 `89860318640220133897`。而应用在 Root 模式下
`mappingKey = ICCID_<iccid>`（**不含 deviceId**），于是两端的 key 完全相同。

**后果**：A 收到 B 的快照后**不会创建远端影子卡**。原因在 `MqttSyncService.kt:989-992`：

```kotlin
val existingConfig = dao.getSimConfigByKey(mappingKey)
if (existingConfig != null && existingConfig.bindMode != "REMOTE_SHADOW") {
    continue   // 本机已有同键的非影子配置 → 跳过创建
}
```

A 用同一个 key 查到了**自己的** `ROOT_ICCID` 配置，于是 `continue`。
没有影子卡，会话里就选不到"用对端的卡发信"，SEND_CMD 无法触发。

这不是应用缺陷——真实物理卡的 ICCID 全球唯一。但它暴露了一个设计前提：
`mappingKey` 在 Root 模式下不含设备维度，而它又是 `sim_card_configs` 的主键，没有唯一性兜底。

#### 根治方案：给每台模拟器不同的 ICC Profile（已实测）

模拟器的 SIM 内容是一份**明文 XML**，就在系统镜像目录里（不在镜像内部）：

```
$ANDROID_SDK_ROOT/system-images/<镜像>/data/misc/modem_simulator/iccprofile_for_sim0.xml
```

其中 `<EF_ICCID>` 节点下的 `<CCID>89860318640220133897</CCID>` 就是 ICCID。
把它替换成不同的值、再用 `-icc-profile <文件>` 启动即可。

`test-fixtures/icc/` 下已备好 4 份（ICCID 末位为 Luhn 校验位，均已重算并通过校验）：

| 文件 | ICCID |
|---|---|
| `sim_a.xml` | 89860318640220133814 |
| `sim_b.xml` | 89860318640220133822 |
| `sim_c.xml` | 89860318640220133830 |
| `sim_d.xml` | 89860318640220133848 |

`scripts/emu-up.sh` 已按实例顺序自动传入，**无需手工干预**。

实测效果（`pm clear` 后从零配置）：

```
设备 A: mappingKey=ICCID_89860318640220133814   (本机)
设备 B: mappingKey=ICCID_89860318640220133822   (本机)
A 的 sim_card_configs:  ICCID_...814 (ROOT_ICCID) + ICCID_...822 (REMOTE_SHADOW ← B 的卡)
B 的 sim_card_configs:  ICCID_...822 (ROOT_ICCID) + ICCID_...814 (REMOTE_SHADOW ← A 的卡)
```

**注意**：系统镜像升级后应**基于新镜像的 `iccprofile_for_sim0.xml` 重新生成**这几个文件
（EF_DIR 的运营商列表、各 EF 的 SIMIO 响应都可能变），不要继续用这里的旧副本。

#### 备选方案：无 Root 模式（已实测，但绕法不持久）

让**其中一台**切到无 Root 模式，键就变成设备作用域的 `DEV_<deviceId>_SUBID_1`：

1. 收件箱 → 设置 → 测试功能（`btnOpenTestTools`）→ 滚动到底点
   **「关闭 Root 探测（开启无 Root 测试）」**（`btnToggleRootMock`）
2. 设置 → **SIM 绑定管理** → 解绑（确认弹窗里再点一次「解绑」）→ 重新探测并保存
3. 弹窗标题应显示「免 Root / 设备卡槽模式」

**注意 `isMockNoRootMode` 是 `HardwareProbeUtils` 里 `object` 的普通 `var`，不持久化**，
进程重启即失效。做这一串操作期间不要 `am force-stop` 该应用。若要多设备长期联调，
建议走上面的 ICC Profile 方案，或把该开关落盘。

### 5.2 跨设备发信（SEND_CMD）验证 —— 已实测跑通

前置：§5.1 已生效（两端 mappingKey 不同，A 侧出现对端卡的 `REMOTE_SHADOW` 配置）。

1. 在 A 上打开任一会话（收件箱点某条会话）
2. 点左下角的发件卡按钮（`btnSelectSim`，当前显示如「本机 卡1」）
3. 底部弹出「选择发送号码」，标题会显示 **「本机 1 张 · 云端 1 张」**。
   选带「**云端**」徽章、说明为「远程发送 · 卡1」的那张，即对端的卡
4. 点输入框（`etSmsInput`）输入内容（**仅 ASCII**），点发送（`btnSendSms`）

期望日志：

```bash
# 执行端（B）
adb -s emulator-5556 logcat -d | grep dSIM_SyncService | tail -3
# D dSIM_SyncService: 执行 SEND_CMD：mappingKey=ICCID_..., subId=2, target=10010
# D dSIM_SyncService: 已发送加密短信到云端: 10010

# 发起端（A）会看到自己发出的指令回声，正确行为是忽略
adb -s emulator-5554 logcat -d | grep dSIM_SyncService | tail -3
# D dSIM_SyncService: 收到非本机目标发信指令，忽略: ICCID_...
```

期望落库（**两端 status 均应为 1，且 uuid 相同** —— 对应 AGENTS.md 的 C10 约束）：

```bash
bash scripts/pull-app-db.sh emulator-5554 && bash scripts/pull-app-db.sh emulator-5556
python -c "
import os, sqlite3
base=os.path.abspath('tmp_dbpull')
for tag in ('A','B'):
    c=sqlite3.connect(os.path.join(base,tag,'dsim_core_database'))
    print(tag, c.execute('SELECT uuid,body,type,status,mappingKey FROM sms_messages WHERE type=2 ORDER BY id DESC LIMIT 1').fetchone())
"
# A ('15c887b7-...', 'Hello from A via B SIM', 2, 1, 'ICCID_89860318640220133822')
# B ('15c887b7-...', 'Hello from A via B SIM', 2, 1, 'ICCID_89860318640220133822')
```

实测结论：A 落库 `status=0` → 发 `SEND_CMD` → B 用自己 SIM 实际发出并复用同一 uuid 落库
`status=1` → 回 `SEND_CMD_RESULT(success)` → A 更新为 `status=1`。全链路与幂等设计均正确。
**且两端 `mappingKey` 都是真实的 `ICCID_` 键**（走的是正常 Root 模式，无任何绕法）。

### 5.3 官方 AVD 只有单卡

 中没有任何多 SIM 开关（只有 `-no-sim`）。多卡场景只能靠多设备覆盖。
（注：配合 §5.1 的 ICC Profile，可以做到每台设备一张不同的卡，已足够覆盖本项目的跨设备场景。）

`emulator -help-all` 中没有任何多 SIM 开关（只有 `-no-sim`）。多卡场景只能靠多设备覆盖。

### 5.4 自身回声（已解决）

每台设备发布到 `<topic>/<deviceId>`、订阅 `<topic>/+`，自己发的报文回环时在 `messageArrived` 里按 topic 后缀直接丢弃（`D dSIM_SyncService: skip own echo on ...`），不解密、不打 WARNING。若仍看到「忽略不包含 sms 载荷」的 WARNING，说明是**别的**设备发来了未知动作。

用外部客户端旁听一个同步组（口令 → 主密钥用 `hashlib.pbkdf2_hmac("sha256", pw, b"dSIM/v3/master-key", 120000, 32)`，AES-GCM AAD 为 `b"DSM3"`）时，订阅 `<topic>/+`；`am force-stop` 应用后 Broker 会代发 `{"action":"OFFLINE"}` 的 Last Will。

### 5.5 `ANDROID_ID` 对应用按签名作用域隔离

同一设备上，应用内读到的 `ANDROID_ID` 与 `adb shell settings get secure android_id`
**不相等**（Android 8+ 按 [签名密钥, 用户, 设备] 隔离）。
所以核对"应用日志里的 deviceId"时不要拿 shell 的值去比。两者都跨重启稳定。

### 5.6 旧 AVD 可能状态损坏

项目里既有的 `Medium_Phone_API_36.1` 拿不到宾客 `wlan0`（`Network is unreachable`），
补配置无法修复。**直接用 `avdmanager` 新建 AVD 替代**（复用已装镜像，10 秒）：

```bash
avdmanager create avd -n dSIM_X \
  -k "system-images;android-36.1;google_apis_playstore;x86_64" -d medium_phone --force
```

### 5.7 验证宾客联网的正确姿势

Android 的 mksh **不支持** `/dev/tcp/...`（会报 `can't create ... No such file or directory`，
极易误判成网络故障）。用：

```bash
adb -s emulator-5554 shell "toybox nc -w 4 broker.emqx.io 1883 </dev/null && echo OK"
```

### 5.8 仪器测试的权限与 fixture 前提（F-9/F-10，2026-09-20 独立复核）

**新装/清数据**用于排除应用遗留授权；**系统短信 fixture**决定导入去重断言是否执行。两者是独立前提：清空应用数据不等于清空系统短信库，空 fixture 的跳过也不等于导入验收通过。AGENTS §8.3 的每轮新装/清数据要求保持不变。

| 现象 | 含义 | 判读 |
|---|---|---|
| 未卸载/清数据的第二轮通过 | `GrantPermissionRule` 不自动撤销权限，后续测试可沿用既有授权 | 不能据此证明测试权限声明自足；须保留该轮新装/清数据证据 |
| 原20例完整执行，19 passed + 1 skipped、0 failures/errors | 若唯一跳过是历史导入且原因 `found 0`，属于已知 fixture 缺失 | 可接受为本次空库行为验证，不能写成“20例全部通过”或“去重断言已覆盖” |
| 有短信 fixture，目标历史导入用例 PASSED | 去重断言实际执行 | 同时检查目标用例没有 `<skipped>`、导入日志及该轮新装前提 |

**F-9 已证实（官方行为 + 两轮实测）**：官方 [GrantPermissionRule 文档](https://developer.android.com/reference/androidx/test/rule/GrantPermissionRule) 说明授权影响当前 instrumentation 全部测试，不能在该进程内撤销（会导致进程崩溃）。本项目 `androidx.test:rules:1.5.0` 的独立实验：新装且 READ_SMS=false → 第一轮带规则 `OK (1 test)`、权限true → 不卸载直接第二轮**无规则**仍 `OK (1 test)`、权限true；随后 `pm clear` → false。不能把“不自动撤销”理解为设备层面永远不能重置；也不能把 UTP 的收尾卸载误认作规则撤销。

#### adopted shell identity 插入：本镜像实测未落库，不能仅凭 URI 判成功

原文曾把“测试内 adopt 同样不够”写成两条“已实测”之一，实际当时只测过 shell `content insert`；adopt 属未经验证的推断。现已在新建 `dSIM_F9F10_20260920`（emulator-5558，API36，镜像 `android-36.1/google_apis_playstore/x86_64`）上补测：

- 临时仪器用例用 dSIM targetContext 的 ContentResolver，READ_SMS 已由规则授予；dSIM **没有默认 SMS 角色**（shell 查询持有者是 `com.google.android.apps.messaging`），未手工更改 AppOps、未使用 root。
- 不 adopt 与调用无参 `uiAutomation.adoptShellPermissionIdentity()` 后，均实际执行 `insert(content://sms, ContentValues)`，写入唯一标记、address、type=INBOX、date、read/seen。
- 每次在 finally 中 drop adopted identity，再以应用 READ_SMS 权限按唯一标记读回，避免把返回值当作落库证据。真实结果：

```text
SYSTEM_COUNT_BEFORE=0
INSERT adopt=false returnedUri=content://sms/0 persisted=0 exception=none
INSERT adopt=true returnedUri=content://sms/0 persisted=0 exception=none
REMAINING_MARKER_ROWS=0
SYSTEM_COUNT_AFTER=0
```

结论：**在本次镜像与未持有默认角色的前提下，单靠 adopt 不足以播种**。不是异常拒绝，而是返回非空 URI 却没有可读回的行；探针的 `OK (1 test)` 只表示观测/清理断言通过，不表示插入成功。不据此推断所有 Android 版本、特权应用或 AppOps 配置都如此，也不再将“只有默认短信应用才能写”作为无例外的普遍结论。

#### 已实测的播种路径：已有默认接收端即可，不要求先 onboarding dSIM

本次默认接收端为系统 Google Messages，SIM=LOADED。保持 dSIM 非默认、未 onboarding，执行模拟器入站 SMS 后系统库从0变1；目标历史导入用例实际通过，日志 `first(scanned=1,imported=1,skipped=0) second(scanned=1,imported=0,skipped=1) rows=1->1 system=1`。因此原文“先把 dSIM onboarding 为默认短信应用”的流程**不是必要前提**。

```bash
SERIAL=emulator-5558  # 明确指定测试模拟器，不默认操作真机
adb -s "$SERIAL" shell getprop gsm.sim.state
adb -s "$SERIAL" shell cmd role get-role-holders android.app.role.SMS
# 本次分别为 LOADED / com.google.android.apps.messaging
adb -s "$SERIAL" emu sms send 10086 "F10_EMULATOR_FIXTURE_20260920"
adb -s "$SERIAL" shell content query --uri content://sms --projection _id
# 等待真实入库；本次先两次 No result found，随后 Row: 0 _id=1
```

`emu sms send` 返回 OK 只是注入已接受，必须继续确认系统行或运行目标用例。若没有可工作的默认接收端，先配置接收端；可用 dSIM，但为了证明新装测试自足，应在播种后卸载/清数据并验证权限已重置，再让 Gradle 新装执行。不要把已 onboarding/手工授权的状态冒充新装证据。上述命令是模拟器注入，不是运营商付费发信。

#### 空库分支：从预测改为真实结果

本轮用 `avdmanager create avd -n dSIM_F9F10_20260920 -k "system-images;android-36.1;google_apis_playstore;x86_64" -d medium_phone` 新建独立 AVD，以5558启动；在播种和加入临时测试**之前**，原测试源码不变，dSIM 未安装，设置 `ANDROID_SERIAL=emulator-5558` 后运行 `:app:connectedDebugAndroidTest`（JDK21及 installations.paths 参数同 AGENTS.md §2）。

- `BUILD SUCCESSFUL in 5m 37s`，exit0。
- XML：`tests="20" failures="0" errors="0" skipped="1"`，20个唯一 testcase：**19 passed / 1 skipped / 0 failed**。
- 唯一跳过：`DeviceIdentityTest.historyImportDeduplicatesOnRerunWithSameDeviceId`；原始 instrumentation 原因 `AssumptionViolatedException: test needs system SMS rows to import, found 0`。textproto该例 `IGNORED`，其余19例 `PASSED`。
- 控制台却显示 `21/20 completed. (1 skipped) (0 failed)` / `Finished 21 tests`。这是本次观察到的汇总计数不一致，尚未定位原因；不擅自修改框架，也不把它当作21例或20例全过，判读以保存的用例级XML/textproto为准。

补充闭环：删除临时源码、卸载 app/test APK 后，在同一新AVD保留系统1行 fixture，重新执行原套件。`BUILD SUCCESSFUL in 3m 25s`、exit0；XML20例全部 PASSED，0 skipped/failures/errors，目标日志仍为 `first imported=1 / second imported=0 / rows=1->1`。这才是本次“有fixture且新装”的完整回归，不把前面的权限残留探针误作新装证据。

原有5554上的46行 fixture / 20 passed证据仍有效，但不能替代上述新AVD空库实测。详细命令、两轮权限探针、播种及原始报告见 `FIXES_2026-09-19.md` 本轮独立复核记录；临时探针源码只留仓库外证据副本，不纳入正式测试套件。

### 5.9 低 API 运行时验证（批次 A，2026-09-20）

新增 `PlatformCompatibilityTest`，必须在**实际低版本 Android** 上运行，不能以 lint/JVM 或改写 SDK_INT 代替。需要 READY SIM 与至少一条活动订阅；缺少这些前提会明确失败，不把未运行的硬件断言报为通过。

```bash
# JDK21与installations.paths参数按AGENTS §2；多设备时始终选定serial。
export ANDROID_SERIAL=emulator-5560  # 本轮新建 dSIM_BatchA_API24_20260920
./gradlew :app:connectedDebugAndroidTest \
  -Dorg.gradle.java.installations.paths="C:\Program Files\Microsoft\jdk-21.0.7.6-hotspot" \
  -Pandroid.testInstrumentationRunnerArguments.class=com.example.dsim.PlatformCompatibilityTest --console=plain
# API28新AVD：dSIM_BatchA_API28_20260920，serial=emulator-5562。
# 全套运行去掉class过滤参数；仍须先确认dSIM未安装或已清数据。
```

- `nonceAndDsm3SurviveRealQos1Publish`：生产codec生成nonce、Android Base64编码、DSM3加密；向设备内隔离MQTT对端实际QoS1发布、收到PUBACK，解密后核验完整JSON与nonce。不是运营商短信发信，也不连接用户保存的broker。
- `slotSimStateFallbackUsesRealTelephony`：为避免活动订阅正常分支掩盖fallback，反射进入**本应用**私有fallback函数，但传入真实TelephonyManager、不mock系统API；API24走无参getSimState，API28走getSimState(slot)。
- `slotMappingResolvesARealSubscription`：真实slot mapping解析；24/28均走<29旧路径，避开getSubscriptionIds/isValidSubscriptionId新API，实测slot0→subscription1。

本轮API24定向3/3（无skip/failure/error）；API28完整新装套件26/26（含上述3例，均无skip/failure/error）。仅覆盖这两份Google APIs/x86_64镜像的单SIM环境，不声称API25、全部OEM或多卡已测。

**API28 shell没有READ_SMS**：`content query` 的Permission Denial不能当作0行，也不能据此判断播种失败。模拟入站SMS后，由声明READ_SMS的原`DeviceIdentityTest`实际读取系统库、执行导入去重；本轮该用例PASSED、没有跳过。API36的shell查询经验不得直接外推低版本。详细环境、原始runner/XML与前置脚本纠错见FIXES本轮记录。

---

## 6. 尚未覆盖的测试面

当前有效覆盖：引导流程、短信采集链路（双分支）、通知、跨设备发现、跨设备发信（SEND_CMD，需先做 §5.1 的绕法）。

**仍缺**（按建议优先级）：

1. **加密往返与篡改拒收** —— `DsimCryptoUtils` 的加解密往返、篡改报文必须解密失败、
    错误口令必须解密失败。（V1 旧格式兼容已移除：项目无发布、无用户，该路径是死代码）
   这是纯逻辑，不需要设备，用 `androidTest` 或 `test` 即可覆盖。
2. **历史同步队列状态迁移** —— `HistorySyncQueueManager` 的 6 个状态与迁移条件。
3. **隐私模式号码变体匹配** —— `PrivacyModeManager` 的 8 种变体与掩码规则。
4. **Room v1→v5 逐级迁移** —— 每一步单独验证，别只测首尾。
    （项目尚无发布用户，优先级可下调；但迁移代码仍在，勿删除。）
SEND_CMD 端到端已按 §5.2 实测跑通，不再列为缺口。

前四项都可以在**不依赖 modem** 的前提下覆盖，是投入产出比最高的方向。


## 2026-09-18 入站同步发件箱回归（批次 A）

- 仪器测试：`adb shell am instrument -w -e class com.example.dsim.SyncOutboxDaoTest,com.example.dsim.SendCommandLedgerTest com.example.dsim.test/androidx.test.runner.AndroidJUnitRunner`，期望 `OK (10 tests)`。只用独立/内存数据库，不发布 MQTT。
- 断网补发端到端：`adb shell svc wifi disable; svc data disable` → `adb emu sms send 10010 x` 两次 → logcat 应只有 `dSIM_Receiver: Captured`，**没有** `dSIM_Outbox: flush sent=` → `svc wifi enable; svc data enable`，重新拉起应用或等心跳 → 期望 `dSIM_Outbox: flush sent=2 dropped=0 failed=0 remaining=0` 与 `dSIM_SyncService: outbox[connect] sent=2`。
- 注意 Paho 自动重连在模拟器切网后不一定立刻触发；本次验证是 `am force-stop` 后重新启动应用走 INIT_DAEMON 路径。这是测试手段，不是产品缺陷，但也说明 W20/连接状态机仍需完善。

## 2026-09-18 一行级加固回归（批次 C）

- `:app:assembleRelease` 会执行 `lintVital*`，需要联网拉 lint 依赖。本次在宿主网络受限时该任务挂死 15 分钟无进展（jstack 显示阻塞在 SSL socket read），用 `-x lintVitalAnalyzeRelease -x lintVitalReportRelease -x lintVitalRelease` 跳过后 release 构建正常。跳过 lint 不等于 lint 通过。
- 权限限定与 debug 面板的验证步骤见 §3.2.1 / §3.2.2。
- 行尾：`git ls-files --eol | grep w/crlf` 期望只剩 `gradlew.bat`（`.gitattributes` 有意保留它的 CRLF）。

## 2026-09-18 安全加固回归（批次 D）

- **TLS 连通**：改默认 broker 前先确认端点证书可被系统信任（宿主上 `ssl.create_default_context()` 握手 `broker.emqx.io:8883` 应 VERIFIED）。设备侧证据不是「没报错」，而是 `adb shell cat /proc/net/tcp6 | grep 22B3` 出现应用 uid 的 ESTABLISHED 行（`22B3` = 8883），配合 `logcat -s dSIM_SyncService` 的 `subscribed <base>/+`。
- **改已装应用的 prefs**：`adb shell run-as <pkg> sh -c "sed -i ..."` 在本镜像上会因 run-as 无法继承外层重定向而失败（`Permission denied`）。可行路径是 `run-as cat` 导出 → 宿主改 → `adb push /data/local/tmp/` → `run-as cp` 覆盖，改前先 `am force-stop`，否则进程退出时会用内存里的旧值覆盖回去。
- **备份排除必须做 A/B**：`bmgr fullbackup <pkg>` 在「没有可备份数据」和「传输层本身失败」两种情况下都可能不产出数据，单看一次结果不能下结论。正确做法是对照：deny 规则 → `PFTBT: Error -1002 … Transport rejected backup`；临时把规则换成空的宽松版本重新构建安装 → `Full backup completed with status: 0` 且无 -1002。跑完记得还原规则并重装。
- 前置：`bmgr enabled` 若为 disabled 需 `bmgr enable true`，并 `bmgr transport com.android.localtransport/.LocalTransport`（Google 传输在无账号的模拟器上不可用）。生产镜像 `adb root` 不可用，所以读不到 localtransport 落盘目录，以 PFTBT 日志为准。
- **回归短信链路**：TLS 生效后必须再跑一次真实入站短信（`adb emu sms send 10086 x`），期望 `dSIM_Receiver: Captured incoming SMS` + `dSIM_Outbox: flush sent=1 dropped=0 failed=0 remaining=0`。只验证「能连上」不够，要验证「连上之后同步仍然通」。
- 单测：`:app:testDebugUnitTest` 期望 52/52，其中 `CloudSettingsManagerTest` 覆盖房间号校验表（`+`/`#`/空白/控制符/前导 `/`/`//`/超长）、broker 分类与提示文案完备性，纯 JVM 不依赖模拟器。

## 2026-09-18 正确性回归补充

先读 `FIXES_2026-09-18.md`。`verify-dsim.sh` 仅覆盖其列出的烟雾场景；12/12 不能证明实际发送成功、命令不重复执行或全部设备落库。

- `:app:testDebugUnitTest`：新增发送分段回调、重复/越界回调、UNKNOWN 恢复、配置恢复测试。
- `SendCommandLedgerTest`：独立临时 Room 库验证并发认领、事务回滚、重开后去重和 v5→v6 迁移；不调用真实短信 API。
- 设备测试只指定 `emulator-*`；不得默认运行到当前连接的物理手机。
- 发信端/执行端都升级，系统回调前 status=0；全部分段回调成功后 status=1。未知为 -2，点击查询不会再次执行已认领 UUID。
- 新建短信才生成新的发送 UUID；已提交但无回调时不能将超时直接视为“没有发送”。
- 旧版发送记录在 v6 迁移为不可重放的 UNKNOWN 墓碑，原短信保留。

## 2026-09-20 批次 A 返工 / B：短信身份与发送状态验收

### 完整套件与来源记录

本轮最终源码的默认 instrumentation 集合是47例；应从源码重新提取，再与 XML 的 `(classname,name)` 完全比较，不能把这一数字当作未来不变常量。两次独立新装均47/47、0 failure/error/skipped；第二轮强制 JVM 单任务重跑115/115。原始逐例记录：仓库外 `C:/Users/admin/AgentDock/dsim-stage1-evidence/batchB-full-case-protection-audit.log`。lint 独立报告5个原有error、312warning、NewApi0；对比修复前5/313，唯一 issue ID 数量变化为 UnsafeProtectedBroadcastReceiver -1，五个error的文件/行/文案未变。

务必选独立测试 AVD，显式设置 ANDROID_SERIAL。全套默认分支不应直接运行在已配置的双机验收设备上；RemoteSenderUiTest 的空选择前提、权限自足验证都需要新装/清数据。本轮使用外部 `batchB-run-gradle.py` 检查 app/test 包均不存在后启动 Gradle，逐轮隔离并归档新报告，不把 UP-TO-DATE 的旧 XML 当作新测。

```bash
export ANDROID_SERIAL=emulator-5562
export JAVA_HOME='/c/Program Files/Microsoft/jdk-21.0.7.6-hotspot'
./gradlew :app:testDebugUnitTest --rerun :app:connectedDebugAndroidTest   '-Dorg.gradle.java.installations.paths=C:\Program Files\Microsoft\jdk-21.0.7.6-hotspot'
```

### T3.4 的两个独立反例

1. `IncomingSmsDedupTest.sameContentFourSecondsApartRepresentsTwoMessages` 使用精确相差4000ms的 PDU 时间，两条必须保留；同内容同时间重投则保留原 id/uuid。先红 expected2/actual1，再绿。
2. `HistoryImportTimestampTest.closeProviderRowsSurviveImportAndRepeatImportKeepsIdentity` 从真实 Telephony provider 读取两行，而不是假设 adopt 权限可插入。新装应用前，确认默认系统短信应用可接收，再在同一专用 AVD 向10086连续投递两次相同 `BATCHBIMPORT-<唯一标记>` 正文、命令间隔4秒。测试先验证真实 provider 的两个 id、不同 DATE 且间隔不超过120秒，再验证导入后仍两行、重复扫描的 id/uuid 不变。无这组 fixture 会明确 skip，不算该断言通过。

本轮两条历史 fixture 的 provider DATE 实际差3307ms（系统默认应用用自己的入库时刻，不保证等于命令间隔或 PDU 时间）。旧导入器确实合成一行，新导入器保留两行。本轮默认47例中该例无skip。

API28普通 shell 缺 READ_SMS 的限制仍成立。实际权限回归使用 APK 内 GrantPermissionRule；双机时仅对自建 userdebug API28 AVD 用 `su 0 content query` 作**只读系统库核对**，已经取得真实行，未修改 provider、未把它冒充 app 权限自足证明。API36端须独立确认自己的实际查询输出，不能泛化权限行为。

### 费用/状态与显式双机故障探针

- T2.1 的5例验证末段额度并发认领、重复 UUID 不重复预留、事务回滚、认领日计费、混合结果的保守上界。两段一成功一失败计2段上界，不宣称计1段或测得真实账单。
- T2.2 的6例验证四写回滚、重复补齐两类队列、终态/隐私/换组/无效段号与文件数据库重开。T2.3 的2例验证仅远端 active 卡可选以及本机卡 retry 守卫；`setOnClickListener(null)` 不会自动清除 clickable，须保留显式禁用断言。
- F4 的6例覆盖过期持久FAILED回执、任何已有账本不覆盖、身份/原因/模式/组守卫、SQLite故障不ACK且不消费nonce、真实 Paho loopback QoS1离线后排空、请求端目标/executor校验与0→-1。JVM另覆盖拒绝 outcome 与取消。

以下都是 **debug、显式 opt-in、专用设备** 分支。普通47例不自动执行它们；运行时必须保存原始 instrumentation 输出和实际双端数据库/MQTT观测，不能仅凭方法的默认分支通过就宣称双机完成。

| 入口 | 参数 | 外部驱动必须核实 |
|---|---|---|
| `SendResultTransactionTest#committedResultSurvivesDatabaseReopen` | `t22CrashUuid`、`t22Requester` | 合成 callback 调用生产 onSentResult 后，出现提交标记并真实 killProcess；实际账本SENT、短信1、两条outbox仍在，请求端仍0。不是运营商短信发送或真实 sent 广播回调 |
| `SendResultTransactionTest#duplicateCallbackRepairsEitherMissingOutboxRow` | `t22Resume=true` | 离线状态先启动恢复探针，再恢复原网络；队列排空，真实对端收到结果及最终SMS_SYNC并变1 |
| `RemoteSenderUiTest#emptyRemoteSelectionDisablesSend` | `t23LiveMarker` | 对端必须明确保持 remote-send disabled；真实聊天点击发布SEND_CMD、对端拒绝、请求端变-1，无执行账本/运营商调用 |
| `ExpiredSendCommandTest#staleCommandQueuesOneExplicitFailedResultWithoutClaiming` | `f4LiveUuid`、`f4Requester` | 请求端由合成SMS_SYNC预置0；执行端断网后调用真实解密/判拒处理器两次，仅一个expired队列、无账本；恢复网络后真实MQTT回执使请求端-1。另发真实broker过期SEND_CMD覆盖入站topic路径 |

故障探针的预期 `Process crashed` 不能当作普通47例中的通过，也不能隐藏成零失败；故障试验仅在标记、持久四写、恢复后对端终态均测得后成立。“一次”指该轮队列/传输观测，不保证 PUBACK 与删除之间再崩溃时的全局 exactly-once。

双机前先实际核实 HAS_SEEN_ONBOARDING=true、模式/默认短信角色、active本机卡、不同设备id及双向PONG。`onboard-device.sh` exit0不代表已经完成；本轮遇到停在绑定/完成页、非导出 Activity 不能从 shell 直接启动、熄屏空XML及按钮在屏外，均补走真实 UI，不能直写 prefs 冒充引导。
