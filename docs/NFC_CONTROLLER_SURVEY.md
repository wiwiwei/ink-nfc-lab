# 手机 NFC 控制器调查（2026-10-22）

2026-10-27 适配更新：已移植 THN31 Root Q 轮询与自有 NCI DATA 后端，不设手机型号或 Android 版本白名单。
源探针已完成刷屏确认，移植后的 App 尚待真机复验；不依赖系统 Tag 派发，
空口是否为 7 位 7A 仍无仪器测量。下方历史调查中的“本项目未测”和未知项保留其当时范围；
当前实现及验收边界以 [THN31 后端说明](THN31_BACKEND.md) 为准。

用途：为本项目选择实验后端，不作为某型号“已经能刷屏”的声明。芯片、系统 NFC 服务、固件版本、Shell 权限和实际 Tag/D1 均须分别验证。同名机型的区域/批次可能不同。

## 与 0.5.1 App 的关系

App 现在依据活动 NFC 包路径和明确的芯片属性尝试识别 ST/NXP；证据不足或冲突时显示未知，并提供手动实验入口。这个识别不是控制器芯片 ID 的硬件实读，也不能证明 ROM 开放所需命令。NXP 后端借鉴社区 QTag/Q-Poll 路线，提供电脑 ADB Shell 或 App Root 入口；本项目尚无 NXP 真机完整刷屏记录。ST Root 后端仍只允许已验证的小米 17 profile，其余 ST 机型不会自动套用。见 [多设备与免 Root 分析](DEVICE_COMPATIBILITY.md)。

统一验收阶梯：①控制器型号/实际活动栈；②私有命令被系统接受；③射频寻卡被控制器执行；④系统交付 ISO-DEP Tag；⑤D1 返回并通过目标校验；⑥所有数据包收到成功状态；⑦刷新状态确认且实屏画面完整。前一阶段不能替代后一阶段。

### 为什么 NXP 与 ST 不能共用唤醒实现

| 层次 | ST54L（已测小米 17） | NXP（社区 Q-Poll 路线） |
| --- | --- | --- |
| 特殊寻卡入口 | ST 私有轮询配置中加入 7-bit `0x7A` 短帧 | NXP 私有 QTag 开关让控制器执行 Q-Poll；社区日志以 `0x71` 寻卡条目为进一步证据 |
| 权限 | 当前完整路线依赖 Root；Shell 可做配置设置/读回，但无法阻止本机主机栈重选 MIFARE | 社区项目通过 ADB/Shizuku Shell 或 Root 调用厂商接口；本项目 0.5.1 仅接入电脑 ADB Shell 与 App Root，未集成 Shizuku |
| 板端图像协议 | ISO-DEP 建立后可复用 D1、认证、D3、D4、DE | 同一原厂驱动板协议可复用，但仍需该手机成功交付 ISO-DEP Tag，且逐包/刷新经实机验证 |

已知 UID 也不能直接跳过 7A/Q-Poll：UID 是防冲突与选卡过程中的标识，ISO-DEP APDU 只能在控制器已经发现并激活目标之后发送。小米 17 的纯 Shell 实验已证明“7A 配置读回成功”与“Tag 交付”之间仍可能被主机侧 MIFARE 重选阻断；详见 [ADB 实验结论](ADB_BACKEND_FINDINGS.md)。

| 设备与范围 | 控制器 | 证据 | 刷屏状态 |
| --- | --- | --- | --- |
| 小米 17，当前实机 25113PN0EC / pudding | ST54L | 本机系统 ST NFC 服务与固件实读；[小米 17 全球型号 25113PN0EG 的厂商技术文件](https://cdn.alsgp0.fds.api.mi-img.com/phone-pads/Xiaomi_17%2025113PN0EG.pdf)亦写 ST54L，全球型号不能代替中国型号实测 | 本项目 Root 后端已完整刷图；Shell 已设置/恢复 7A，但 MIFARE 重选阻止 Tag/D1 |
| 小米 15 Pro，2410DPN6CC / haotian | ST54L | [喵墨作者的实机兼容记录](https://github.com/3dhaoxinren/miaomo/blob/main/docs/COMPATIBILITY.md)给出芯片与固件版本 | 该项目尚未成功专用寻卡或刷图 |
| 小米 17 Ultra，全球型号 2512BPNDAG | ST54L | [小米撰写的智利产品申报文件镜像](https://manuals.plus/m/b6d67f789c051397732f06d0f1ab3331a8ce147ef9ff21afed8da4c8cda7ad26)列明 NFC 模块 | 本项目未测 |
| 小米 15T Pro，全球型号 2506BPN68G | NXP 220U | [小米厂商技术文件](https://cdn.alsgp0.fds.api.mi-img.com/phone-pads/Xiaomi_15T_Pro_2506BPN68G.pdf)明确列出 NFC 模块 | 本项目未测；芯片厂家不能证明其 ROM 开放 Q-Poll |
| 小米 5，2016 年产品 | NXP，具体型号未由该来源确定 | [NXP 与小米联合公告](https://www.nxp.com/company/about-nxp/newsroom/NW-XIAOMI-MOBILE-PAYMENT)明确称采用 NXP NFC 方案 | 与当前屏幕适配无实测关联 |
| 一加 Ace 6，PLQ110 / OP6113 | 紫光青藤 THN31，社区报告 | [该机型固件移植者的底包分析](https://github.com/Shlc57/OnePlus-Port-Script/blob/main/README_ACE6.md)；[NFC Forum 的 THN31 认证](https://nfc-forum.org/uploads/Certified-Products-Certificates/Beijing-Tsingteng-Microsystem-Co.%2CLtd._THN31_Certificate_ID_60145.pdf)确认芯片厂家 | 本项目未测；机型结论需原厂固件/真机二次核验 |

其他厂商确实存在：THN31 的制造商是**紫光青藤 TsingTeng**，不是 Thales。OPPO 的[公开内核源码](https://github.com/oppo-source/android_kernel_modules_and_devicetree_oppo_mt6993/blob/oppo/mt6993_b_16.0.0_find_x9/vendor/oplus/kernel/nfc/thn31/tms_common.h)包含 THN31 驱动，但“源码树包含驱动”不能单独证明某款量产机实际装配该芯片。

未查到足够一手材料确定 Redmi K100 Pro、Redmi K80 Pro、普通小米 15 或华为各系列的具体 NFC 控制器，不按品牌/SoC 猜测。NXP 在小米 15S Pro 发布的 UWB 芯片也不能作为该手机 NFC 控制器型号的证据。

对本项目的实现建议：先按实读控制器与系统接口选择 ST、NXP 或其他 profile；每个 profile 独立验证特殊寻卡配置、Tag 派发、D1、完整刷新。不要把“厂商 API 返回成功”标作设备支持。

## THN31 研究备注

[紫光青藤产品页](https://www.tsingtengms.com/jctx_nfc)说明 THN31 系列支持 NFC 全协议、NCI 2.1；THN31S/F 为不同产品形态。[NFC Forum 认证](https://nfc-forum.org/uploads/Certified-Products-Certificates/Beijing-Tsingteng-Microsystem-Co.%2CLtd._THN31_Certificate_ID_60145.pdf)确认 THN31 为移动 NFC 设备控制器。OPPO [公开内核源码](https://github.com/oppo-source/android_kernel_modules_and_devicetree_oppo_mt6993/blob/oppo/mt6993_b_16.0.0_find_x9/vendor/oplus/kernel/nfc/thn31/tms_common.h)包含 `thn31`/`tms` 驱动。上述证据只能证明第三条厂商技术路线存在，不能证明任何量产机开放 7-bit `0x7A` 发射或 Shell 访问。

在原厂墨水屏任务中，一旦正常激活 ISO-DEP，D1/D3/D4/DE 图像协议仍可复用；未知部分是 THN31 控制器上的特殊寻卡帧入口、系统标签派发策略及 Android 权限。优先获取 THN31 真机的芯片/固件实读、vendor NFC 服务与接口签名，再做有恢复路径的只读寻卡/D1 实验。不要把普通 NFC、iTAP 支持或 `NCI 2.1` 等同于支持自定义 7-bit 7A；也不要把 Android 的 polling-frame 通知接口当作发射自定义帧的 API。

## 其他控制器厂商与优先级

- **紫光青藤 TsingTeng**：THN31 系列，国内 OPlus 机型有实际采用线索；当前应列为第三条研究路线。
- **联发科 MediaTek**：官方曾推出 [MT6605 手机 NFC 控制器](https://www.mediatek.com/zh-cn/press-room/mediatek-launches-worlds-1st-3-swp-single-wire-protocol-nfc-solution-for-adoption-in-mainstream-mobile-platforms)，但资料较旧，不应由“手机采用天玑 SoC”推断其 NFC 也是联发科。
- **三星 Samsung**：官方发布 [S3FWRN5/S3FWRN5P 控制器](https://news.samsung.com/global/everything-you-need-to-know-about-samsungs-newest-nfc)，在较早的 Galaxy 设备使用；国内现售其他品牌的覆盖范围尚无充分证据。
- **博通 Broadcom**：Android 早期 [AOSP NFC HAL](https://android.googlesource.com/platform/external/libnfc-nci/+/5a993f32b29a07a8a26119965fde9fdaef5ffa59/Android.mk)含 BCM2079x 目标；主要是历史兼容考虑。

优先顺序基于当前项目已有证据：ST 已在小米 17 通过 Root 刷屏；NXP 由另一开源项目在兼容机型演示，但本项目尚未真机验证 NXP 后端；THN31 有手机控制器实用线索，尚无原厂屏 7-bit 寻卡证据；其他路线等用户设备反馈再投入。
