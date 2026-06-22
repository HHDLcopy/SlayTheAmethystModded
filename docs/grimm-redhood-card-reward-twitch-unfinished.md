# Grimm 小红帽选牌抽搐：通用修复未完成记录

状态：通用 launcher/runtime 兼容修复未完成。当前保留方案是 `GrimmRedHoodPowerCompatPatches`，即针对 GrimmFairyTalesDLC 小红帽能力的专用序列化补丁。

本文档记录本次调查中已经验证但最终失败的通用修复尝试，避免后续把这些方向误认为已解决方案。

## 复现场景

- 模组 jar：`E:/Resources/GrimmFairyTalesDLC..jar`
- 仓库：`D:/Desktop/SlayTheAmethystModded`
- 关键流程：
  1. 角色 `GRIMM`，敌人 `Looter`。
  2. 第一回合手牌只有 `GrimmFairyTalesDLC:RedHoodStory`。
  3. 第一回合打出小红帽能力牌。
  4. 第二回合开始，小红帽能力连续打开选牌页面。
  5. 选择任意牌时，移动端仍可观察到画面/卡牌抽搐。

使用过的 single-room spec：

```properties
schemaVersion=1
character=GRIMM
monster=Looter
cards=GrimmFairyTalesDLC:RedHoodStory
```

## 已确认的源码链路

对外部模组 jar 的静态追踪结果：

- `RedHoodStory.use()` 施加 `RedHoodPower(2)`。
- `RedHoodPower.atStartOfTurn()` 连续排入 `RedHoodAction`。
- `RedHoodAction.update()` 调用 `CardRewardScreen.customCombatOpen(...)`，之后读取 `CardRewardScreen.discoveryCard`。

桌面原版不复现该视觉问题。用户在启动器设置里切到桌面输入模式后问题仍存在，因此不能简单定性为 vanilla `Settings.isTouchScreen` 或 Android 触摸兼容层单独导致的问题。

## 失败尝试

### 1. 让 autoplay 能自动处理发现牌页面

实现内容：

- 新增/补全 `AutoplayChoiceScreenActions`。
- 支持在 `CardRewardScreen` 上随机选择一张可见 reward card。
- 通过正常 hitbox click 路径选择，而不是直接写 `discoveryCard`。
- 增加 `amethyst.debug.autoplay.choice_delay_ms`，方便人工观察选牌页面。

结果：

- 自动化能力有效，后续 single-room 场景可以跑通。
- 这不是问题修复，只是复现/观察能力。

### 2. 直接写 `discoveryCard` 并关闭选牌页

尝试方向：

- autoplay/补丁曾尝试绕过 UI，直接设置 `CardRewardScreen.discoveryCard` 并关闭页面。

结果：

- 可以推进流程，但绕开了 vanilla reward 选择清理路径，不能证明真实交互问题已解决。
- 后续改回正常 hitbox click 路径。

### 3. 把 CardRewardScreen 从 native-touch allowlist 中移除

假设：

- 移动端 vanilla touchscreen 的 `touchCard`/confirm 状态机可能和 modded discovery 页面冲突。

尝试内容：

- 让 card reward/discovery 页面在 allowlist 模式下走桌面 direct-pick 语义。

结果：

- 用户仍观察到抽搐。
- 已恢复到原本的 CardRewardScreen allowlist 行为，不再作为修复保留。

### 4. 修改桥接输入/鼠标语义

假设：

- 启动器向游戏暴露的鼠标 click 语义或 touch-to-mouse 桥接残留可能导致卡牌 hover/click 状态异常。

尝试内容：

- 调整过桥接输入行为，并用 autoplay 跑过对照。
- 部分运行曾看似改善，但后续复现仍能观察到抽搐。

结果：

- 不能作为稳定修复结论。
- 问题不能归因于单一“Android 触摸兼容层”实现错误。

### 5. 抑制战斗手牌 overlay/布局干扰

假设：

- 新获得的牌可能同时被战斗手牌布局和选牌/特效绘制驱动。

尝试内容：

- 曾引入 `CardRewardCombatHandOverlayCompatPatches` 类的方向，试图在 reward/discovery 页面期间压制手牌 overlay 干扰。

结果：

- 用户仍观察到抽搐。
- 该方向已移除，不作为保留补丁。

### 6. 处理“同一对象被手牌布局和获得特效同时驱动”

假设：

- `ShowCardAndAddToHandEffect` 构造后把同一个 `AbstractCard` 对象加入手牌，而获得特效仍继续 update/render 该对象，导致 VFX 与 hand layout 同时驱动。

尝试内容：

- `CardObtainEffectOwnershipCompatPatches`
- 在手牌获得对象所有权后退休或抑制 `ShowCardAndAddToHandEffect` 的后续驱动。

结果：

- 逻辑上解释了一部分移动端更明显的视觉冲突，但用户仍观察到抽搐。
- 已移除，不作为保留补丁。

### 7. 连续 discovery action 等待和 settle barrier

假设：

- 多个 `RedHoodAction` 连续打开 discovery 页面，前一次选择/获得特效/点击边沿未稳定。

尝试内容：

- `CardRewardDiscoveryActionWaitCompatPatches`
- `CardRewardChoiceSettleCompatPatches`
- 当前 action 打开 discovery 页面后，等待用户或 autoplay 选择。
- 选择后插入 settle barrier，再允许下一次选择页打开。

结果：

- 日志显示 action wait 和 settle barrier 生效。
- 用户仍观察到抽搐。
- 已移除，不作为保留补丁。

### 8. 预置 reward card 初始 target 坐标

假设：

- `customCombatOpen()` 的 `placeCards(...)` 只设置 `current_x/current_y`，首帧 `target_x/target_y` 可能仍为 `(0,0)`，移动端 update/render 顺序更容易暴露这帧错误移动。

尝试内容：

- `CardRewardInitialTargetCompatPatches`
- 在选牌页打开后立即把 `target_x/target_y` 设为 render 稍后会计算出的布局目标。

证据：

- 日志中 `target=(0.0,0.0)` 的首帧问题被消除。

结果：

- 用户仍观察到抽搐。
- 已移除，不作为保留补丁。

### 9. 清理 stale cursor / stale hover

假设：

- 选择上一张 reward card 后，虚拟鼠标停在卡牌区域。下一页打开时，新卡牌滑入旧鼠标位置并立即 hover 放大，表现为抽搐。

尝试内容：

- `CardRewardIdleCursorCompatPatches`
- 选牌页打开后短时间内将 idle input 移到 `(0,1)`。
- 清理 reward card hover/click 状态。
- 修正 autoplay 复用同一个 `CardRewardScreen` 实例时没有重新等待 `choice_delay_ms` 的问题。

证据：

- 日志显示 `CardRewardScreen idle cursor guard armed`。
- 可观察延迟窗口内，reward card 保持 `hovered=false scale=0.8`。

结果：

- 用户仍观察到抽搐。
- 已移除，不作为保留补丁。

## 保留的当前方案

恢复并保留：

- `mods/amethyst-runtime-compat/src/main/java/io/stamethyst/compatmod/GrimmRedHoodPowerCompatPatches.java`

该补丁只针对 GrimmFairyTalesDLC 的 `RedHoodPower.atStartOfTurn()`：

- 替换原本连续排入 `RedHoodAction` 的行为。
- 一次只打开一个小红帽选牌页。
- 等待所选牌进入手牌或弃牌的获得特效路径。
- 清理 reward screen 和手牌输入状态。
- 再打开下一次小红帽选择。

关闭参数：

```text
amethyst.runtime_compat.grimm_red_hood_serial_choices=false
```

## 仍未解决的问题

通用根因仍未被证明。已经排除或削弱的解释包括：

- 纯 `Settings.isTouchScreen` 触摸状态机问题。
- 单纯桥接输入语义问题。
- 单纯 stale cursor hover 问题。
- 单纯首帧 target 坐标问题。
- 单纯 obtain effect 与 hand layout 同对象驱动问题。
- 单纯连续 action 未等待问题。

后续若继续查通用修复，应优先收集以下证据：

- `CardRewardScreen.takeReward()` / `AbstractDungeon.closeCurrentScreen()` 后，隐藏的 reward cards 是否在移动端额外 render/update 一帧。
- 选中卡牌复制品和获得特效对象之间是否还有未记录的对象共享。
- 移动端 render/update/present 顺序是否让 screen close、effect insertion、hand layout refresh 的中间态可见。
- GrimmFairyTalesDLC 的 RedHoodAction 是否复用了 vanilla 假设外的对象或 action 时序。

在证明这些问题前，通用兼容修复应视为未完成。
