# THN31 Root 后端

此适配移植自 [kur0neko/huawei-epd-pn532](https://gitee.com/kur0neko/huawei-epd-pn532)
的 Android 探针。保留原作者署名与 PolyForm Noncommercial 1.0.0 许可。

## 使用范围

- 根据 `persist.sys.nfc.chip_name=THN31` 启用 Root 路线，不检查手机型号或固定 Android API 版本。
- NFC 必须已开启，手机保持亮屏解锁。`libnfc_nci_jni.so` 的目标符号、偏移和 16 字节机器码必须匹配移植来源的实机；不匹配时中止。
- 首页按实时芯片属性识别 THN31。选择图片后点击刷入，在 THN31 弹窗选择“仅读 D1”或明确确认覆盖并刷写。停止/离开前台沿用辅助进程停止机制。
- 不打开 `/dev/tms_nfc`，不刷写控制器固件，不改变发射功率，不安装屏幕驱动配方。
- 仅写入现有 552×768 四色、压缩模式兼容板；D1 的 UID 必须与本轮激活 UID 匹配。其他板型仍会拒绝。

## 真实通信路径

```text
App → su → nsenter -t 1 -m → app_process Thn31Worker
    → 暂停普通系统轮询
    → sendVendorNciMessage(1, 1, 3, [01 71 01])
    → NCI RF_DISCOVER：21 03 03 01 71 01
    → 收齐本轮发现通知，只选择唯一 ISO-DEP 目标
    → RF_DISCOVER_SELECT / 匹配的 ISO-DEP 激活通知
    → 自有 NCI DATA 接收通道 + 已有 INfc HAL write
    → 上游 D1 校验 / 认证 / D3 / D4 / DE
    → 撤销租约、去激活、恢复普通轮询
```

**没有系统 Tag / Android `IsoDep` 接管。** NFCC 处理空口 ISO-DEP block、CRC 和 WTX；
App 管理主机侧 NCI credit、DATA 分片/重组和 APDU 超时。`ImageTransfer.ApduLink` 使其复用
原有图像协议；已有 `send(IsoDep, ...)` 保留并委托同一实现。

THN31 的 Q 轮询命令中没有显式 `7A` 或有效位数字段。源项目已获得屏幕应答、D1 和用户
确认完整刷屏的功能证据；**没有仪器测量证明空口恰好是 7 位 `0x7A`**。不能将此适配
推定所有 THN31、所有 OPlus 手机或 NXP 设备均已验证可用；是否运行取决于实际接口和库指纹，
不以手机型号或 Android 版本白名单阻断。

## 会话与恢复

Frida inject 复用仓库已有、SHA-256 固定的 17.24.0 arm64 运行组件。专用脚本只在随机令牌
租约存活时截获本次 Q 发现、匹配激活及连接 0 的 DATA/credit/去激活，防止系统栈把尚未
接管的会话主动断开。租约删除/过期后恢复透传；驻留 trampoline 不在 NFC 回调运行中拆卸。

辅助进程在 init 挂载命名空间运行，避免 Root 管理器的私有挂载视图隐藏 NFC 进程。
HAL 接口必须是冻结的 INfc v1 及匹配哈希，写入只允许连接 0 的合法 NCI DATA 包。
接收 APDU 不能靠可能限流的 logcat，logcat 只负责本轮发现/激活同步；数据阶段使用
NFC 进程写入的受限接收文件。HAL write 成功只是本地接受，仍必须等待板端真实应答。

保留源项目的抽象 socket 锁和 `epdprobe-owned-*` 租约路径，使两个 App 并存时互斥。
无法取得会话锁、残留桥接未空闲、其他 Frida 注入或库指纹不匹配时，不开始 RF 会话。
同一 NFC 进程复用协议版本 2 的空闲桥接，不重复注入。

正常上限为 180 秒，另有 220 秒看门狗；租约最多 240 秒。不自动重发不确定 APDU，
不自动切换全局 NFC 电源。退出报告分别列出操作结果和恢复结果；断电/强杀后的恢复
不由正常退出保证，异常时需检查系统 NFC。`RESTORED=true` 表示桥接撤销、去激活及
轮询恢复 API 检查通过、NFC 仍开启，不是新一次门禁/支付功能验收。

## 验证边界

源项目此前已经验证 Q 发现、ISO-DEP 激活、D1；用户确认完整 Root 刷屏成功。
这份移植改变了 App 包名和调用入口，**合入版本没有新增真机刷屏验收**。
不上传真实 UID、设备日志、图像、签名私钥或反编译的厂商二进制。

离线验证入口：`bash test.sh`，其中 `bash tests/thn31_test.sh` 覆盖：

- NCI DATA 分片、重组、credit、476 个长 APDU 连续传输；超时/异常/取消不重发。
- 实际接收文件的 credit/应答读取、拒绝损坏及非归属数据。
- 排除过期发现、未收齐列表、多目标、其他系统命令及激活不匹配。
- 会话锁争用、有限等待、取消及非争用错误；芯片属性校验不接收型号或 SDK 参数。
- Frida 脚本的库指纹、通知归属、接收捕获、租约失效透传及新令牌重置。
- `ApduLink` 复用认证、476 个 D3 包和 D4/DE；认证失败及图像长度错误不会继续写入。

构建沿用 `bash build.sh`。Windows 可用相同 javac/aapt2/d8/zipalign/apksigner 步骤验证；
完整构建与测试结果见 PR 描述。APK 构建通过不能替代本机固件/贴屏刷新验收。
