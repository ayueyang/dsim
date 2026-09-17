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

# PING/PONG 在广播（每 20 秒一次快照）
adb -s emulator-5554 logcat -d | grep dSIM_SyncService | tail -3
```

---

## 5. 已知限制与对应处置

### 5.1 ✅ 模拟器各实例共用同一 ICCID —— 绕法已实测跑通

实测：两台模拟器报告的 ICCID 都是 `89860318640220133897`。而应用在 Root 模式下
`mappingKey = ICCID_<iccid>`（**不含 deviceId**），于是两端的 key 完全相同：

```
设备 A: key=ICCID_89860318640220133897  phone=+13800138001 deviceId=d9e0118b7d57f7c6
设备 B: key=ICCID_89860318640220133897  phone=+13800138002 deviceId=96fad9a2276863b0
```

后果：A 收到 B 的快照后**不会创建远端影子卡**。原因在 `MqttSyncService.kt:989-992`：

```kotlin
val existingConfig = dao.getSimConfigByKey(mappingKey)
if (existingConfig != null && existingConfig.bindMode != "REMOTE_SHADOW") {
    continue   // 本机已有同键的非影子配置 → 跳过创建
}
```

A 用同一个 key 查到了**自己的** `ROOT_ICCID` 配置，于是 `continue`。
没有影子卡，会话里就选不到"用对端的卡发信"，SEND_CMD 无法触发。

**这不是应用缺陷**——真实物理卡的 ICCID 全球唯一。但它暴露了一个设计前提：
`mappingKey` 在 Root 模式下不含设备维度，而它又是 `sim_card_configs` 的主键，没有唯一性兜底。

#### 绕法（已实测有效，只需改一台设备）

让**其中一台**改用无 Root 模式，键就变成设备作用域的 `DEV_<deviceId>_SUBID_1`，两端不再碰撞。

1. 在该设备上：收件箱 → **设置** → **测试功能**（`btnOpenTestTools`）
2. 滚动到底部，点 **「关闭 Root 探测（开启无 Root 测试）」**（`btnToggleRootMock`）。
   按钮文案变为「恢复 Root 探测 (结束测试)」即表示开关已生效
3. 返回设置 → **SIM 绑定管理**（`btnManageSimSetting`）→ 点「解绑」并在确认弹窗点「解绑」
4. 点「探测并绑定本机 SIM」——弹窗标题应显示 **「免 Root / 设备卡槽模式」**（原来是「Root / ICCID 模式」）
5. 点「保存绑定」

验证（B 侧应出现两条配置，新键为 `NOROOT_DEVICE`）：

```bash
bash scripts/pull-app-db.sh emulator-5556
python -c "
import sqlite3
c=sqlite3.connect('tmp_dbpull/emulator-5556/dsim_core_database')
for r in c.execute('SELECT mappingKey,bindMode,isActive,deviceId,subscriptionId FROM sim_card_configs'): print(r)
"
# ICCID_89860318640220133897    ROOT_ICCID     0  ...
# DEV_96fad9a2276863b0_SUBID_1  NOROOT_DEVICE  1  ...
```

等一个快照周期后，A 侧应出现 `REMOTE_SHADOW` 条目（此为端到端成功的分水岭）：

```bash
bash scripts/pull-app-db.sh emulator-5554   # 等 20s 后再拉
# ICCID_89860318640220133897    ROOT_ICCID      1  d9e0118b7d57f7c6   ← A 自己的卡
# DEV_96fad9a2276863b0_SUBID_1  REMOTE_SHADOW   1  96fad9a2276863b0   ← B 的卡 ← ★
```

**注意 `isMockNoRootMode` 不持久化**（`HardwareProbeUtils` 里 `object` 的普通 `var`），
进程重启即失效。做这一串操作期间不要 `am force-stop` 该应用。

另一个未验证的方向：模拟器支持 `-icc-profile <file>` 指定 ICC 配置，若能给不同实例配置不同
ICCID，即可从根上解决（无需切模式）。本次未找到该文件的格式样例
（`emulator -help-all` 只说明"ICC configuration file for SIM card"，SDK 里也无样例文件）。

### 5.2 跨设备发信（SEND_CMD）验证 —— 已实测跑通

前置：完成 §5.1 的绕法，A 侧已出现 `REMOTE_SHADOW` 配置。

1. 在 A 上打开任一会话（收件箱点某条会话）
2. 点左下角的发件卡按钮（`btnSelectSim`，当前显示如「本机 卡1」）
3. 底部弹出「选择发送号码」，标题会显示 **「本机 1 张 · 云端 1 张」**。
   选带「**云端**」徽章、说明为「远程发送 · 卡1」的那张，即对端的卡
4. 点输入框（`etSmsInput`）输入内容（**仅 ASCII**），点发送（`btnSendSms`）

期望日志：

```bash
# 执行端（B）
adb -s emulator-5556 logcat -d | grep dSIM_SyncService | tail -3
# D dSIM_SyncService: 执行 SEND_CMD：mappingKey=DEV_..., subId=1, target=10086
# D dSIM_SyncService: 已发送加密短信到云端: 10086

# 发起端（A）会看到自己发出的指令回声，正确行为是忽略
adb -s emulator-5554 logcat -d | grep dSIM_SyncService | tail -3
# D dSIM_SyncService: 收到非本机目标发信指令，忽略: DEV_...
```

期望落库（**两端 status 均应为 1，且 uuid 相同** —— 对应 AGENTS.md 的 C10 约束）：

```bash
bash scripts/pull-app-db.sh emulator-5554 && bash scripts/pull-app-db.sh emulator-5556
python -c "
import sqlite3
for tag,ser in (('A','emulator-5554'),('B','emulator-5556')):
    c=sqlite3.connect('tmp_dbpull/%s/dsim_core_database'%ser)
    print(tag, c.execute('SELECT uuid,body,type,status,mappingKey FROM sms_messages WHERE type=2 ORDER BY id DESC LIMIT 1').fetchone())
"
# A ('2d05ed3b-...', 'Hello', 2, 1, 'DEV_96fad9a2276863b0_SUBID_1')
# B ('2d05ed3b-...', 'Hello', 2, 1, 'DEV_96fad9a2276863b0_SUBID_1')
```

实测结论：A 落库 `status=0` → 发 `SEND_CMD` → B 用自己 SIM 实际发出并复用同一 uuid 落库
`status=1` → 回 `SEND_CMD_RESULT(success)` → A 更新为 `status=1`。全链路与幂等设计均正确。

### 5.3 官方 AVD 只有单卡

`emulator -help-all` 中没有任何多 SIM 开关（只有 `-no-sim`）。多卡场景只能靠多设备覆盖。

### 5.4 应用会收到自己发出的消息并打 WARNING 日志

因为客户端订阅的 topic 与发布 topic 相同且未设置 MQTT 的 no-local 选项，
自己发的 PING/PONG 会回环回来，落到 `handleIncomingMessage` 末尾的"无 sms 载荷"分支：

```
W dSIM_SyncService: 忽略不包含 sms 载荷的云端消息: {"action":"PING","deviceId":"..."}
```

行为是**正确**的（不重复处理），但日志级别是 WARNING，每 20 秒刷一条，排查时容易被误导。
建议改为按 action 提前静默返回。

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

---

## 6. 尚未覆盖的测试面

当前有效覆盖：引导流程、短信采集链路（双分支）、通知、跨设备发现、跨设备发信（SEND_CMD，需先做 §5.1 的绕法）。

**仍缺**（按建议优先级）：

1. **加密 V1/V2 交叉兼容** —— `DsimCryptoUtils` 的加解密往返与旧格式读取。
   这是纯逻辑，不需要设备，用 `androidTest` 或 `test` 即可覆盖。
2. **历史同步队列状态迁移** —— `HistorySyncQueueManager` 的 6 个状态与迁移条件。
3. **隐私模式号码变体匹配** —— `PrivacyModeManager` 的 8 种变体与掩码规则。
4. **Room v1→v5 逐级迁移** —— 每一步单独验证，别只测首尾。
SEND_CMD 端到端已按 §5.2 实测跑通，不再列为缺口。

前四项都可以在**不依赖 modem** 的前提下覆盖，是投入产出比最高的方向。
