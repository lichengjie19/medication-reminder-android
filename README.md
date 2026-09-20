# 用药记

Android 本地用药事项、药单 OCR、提醒及服药记录。单人使用，一个事项可以包含多种药品，每个每日时刻可设置不同用量。

## 使用

1. 在「事项」中新建用药事项，填写标题、病因和备注。
2. 手动添加药品，或拍照/从相册选择药单，经本地 OCR 识别后逐项核对。
3. 填写药品规格、每次数量、每日时刻、开始日期和可选结束日期。饭前/饭后/随餐仅作为说明，时刻由用户设置。
4. 在「设置」开启通知和准时提醒权限，并发送测试通知。
5. 在通知或「今日」中选择已服用、稍后提醒或跳过本次。「历史」可更正已服记录的实际时间、数量和备注；超时记录只能补充备注。

图片和数据保存在应用私有目录。中文 OCR 模型随安装包提供，应用合并清单移除了网络权限，不需要账号或外部 OCR 服务。相机通过系统拍照应用调用。

## 提醒规则

- 每轮计划提醒时刻起 **30 分钟**内可以操作，恰好到截止时刻即失效。
- 超时自动记录为「超时自动跳过」，通知和所有页面均不可重新选择。
- 稍后提醒从点击时刻延后 **10 分钟**，新一轮重新获得 30 分钟。等待期间不可操作；旧轮次按钮或超时任务失效。
- 例如 08:00 提醒，08:25 点击稍后，08:35 再次提醒，09:05 截止。
- 通知延迟、划走通知、重启或恢复备份都不重置截止时间。每种药的每次事项独立处理。
- 后台执行晚到时按已保存的截止时间补写状态；任何操作都会先校验时间和轮次。用户强行停止应用或关闭权限后，系统可能停止投递提醒，重新打开后仍按原截止时间结算。
- 暂停或结束计划时，未来提醒取消，已经开始但未完成的事项标记「计划已停止」，保留历史。
- 修改剂量或时刻仅重建未来未开始事项；当天已经开始的同一安排不会因为编辑而新增另一条可服记录。

规格和服用量分别保存。仅对明确的单粒含量作 g/mg 换算；复方、缺少规格或单位不兼容时手填。非整数粒数不自动取整，必须手动核对确认。

## 备份

设置页手动导出普通 ZIP，包含 `snapshot.json`、图片及超时锁记录，**不加密**。恢复先校验并预览数量，确认后覆盖，不进行合并。损坏或不完整文件不会覆盖原库；本机已有的超时锁保留，恢复旧备份不能重新开放已超时事项。

## 构建

- JDK 17、Android SDK Platform 36、Build Tools 35.0.0。
- Gradle Wrapper 8.14.5，AGP 8.13.2，Kotlin 2.1.20。
- `minSdk 26`、`targetSdk 36`。Kotlin、Compose Material 3、Room、ML Kit bundled 中文模型。

在项目根目录创建不提交的 `local.properties`，填入本机路径：

```properties
sdk.dir=/absolute/path/to/Android/sdk
```

```sh
export JAVA_HOME=/path/to/jdk-17
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
# 已启动模拟器或连接测试设备时：
./gradlew :app:connectedDebugAndroidTest
```

调试 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。调试签名用于本地测试；发布商店前需配置自己的正式签名。卸载应用会删除本机记录，换签名重新安装前先导出备份。

## 工程结构

- `data/`：Room 数据库与事务仓储，不使用数据库外键；用逻辑 ID 维护关系。
- `domain/`：纯提醒状态转换、每日安排生成及十进制剂量换算。
- `reminders/`：系统通知、AlarmManager 和重启恢复。
- `media/`：私有图片、旋转裁剪、ML Kit 和保守的药单草稿解析。
- `backup/`：带版本的 ZIP 校验、预览及事务恢复。
- `ui/`：今日、事项、药品编辑、OCR 核对、历史和设置页面。

`OccurrenceEntity` 的身份由安排 ID 和日期确定。实际执行使用轮次和截止时间校验；已超时身份另外写入 `expiry_locks`，覆盖恢复不会清除此表。图片先以新文件名写入，数据库事务失败只清理新文件。

## 验证

测试包含纯状态规则、日期生成、剂量换算、药单解析、备份结构与 ZIP 安全，以及 Room 事务的并发操作、超时边界和恢复锁定。具体执行结果见 `VALIDATION.md`。模拟器检查与真实手机后台提醒验证分别记录，不以构建通过代替运行验收。

## 技术参考

- [ML Kit 中文本地文字识别与 bundled 模型](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)
- [Android AlarmManager、精确闹钟授权和系统限制](https://developer.android.com/develop/background-work/services/alarms)
- [Android 通知分组](https://developer.android.com/develop/ui/views/notifications/group)
