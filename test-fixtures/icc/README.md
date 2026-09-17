# 模拟器 ICC Profile（用于给每台模拟器不同的 SIM 身份）

## 为什么需要

Android 模拟器各实例共用**同一个 ICCID**（`89860318640220133897`）。而 dSIM 在 Root 模式下
`mappingKey = ICCID_<iccid>`，且它是 `sim_card_configs` 的主键 —— 于是两台设备的卡会被认为是
同一张，对端卡的 `REMOTE_SHADOW` 配置不会被创建，跨设备发信无法触发。

给每台模拟器指定不同的 ICC profile 即可从根上解决，**无需改动应用代码**。

## 文件

由 `$ANDROID_SDK_ROOT/system-images/<镜像>/data/misc/modem_simulator/iccprofile_for_sim0.xml`
生成，仅替换 `<EF_ICCID>` 里的 `<CCID>` 值。ICCID 末位是 Luhn 校验位，变体均已重算并通过校验。

| 文件 | ICCID |
|---|---|
| `sim_a.xml` | 89860318640220133814 |
| `sim_b.xml` | 89860318640220133822 |
| `sim_c.xml` | 89860318640220133830 |
| `sim_d.xml` | 89860318640220133848 |

## 用法

```bash
emulator -avd <AVD> ... -icc-profile test-fixtures/icc/sim_a.xml
```
`scripts/emu-up.sh` 已按实例顺序自动传入。

## 注意

- 系统镜像升级后，原始 `iccprofile_for_sim0.xml` 的内容可能变化（EF_DIR 里的运营商列表、
  各 EF 的 SIMIO 响应等）。此时应**基于新镜像的文件重新生成**，而不是继续用这里的旧副本。
  再生成方法：读新镜像的 `iccprofile_for_sim0.xml`，把 `<CCID>` 原值替换成上表的 ICCID。
- `-icc-profile` 只影响 SIM 的 ICCID/运营商等标识，不改变号码；号码仍由 `-phone-number` 指定。
