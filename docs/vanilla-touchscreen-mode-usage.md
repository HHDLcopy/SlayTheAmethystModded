# 原版 `Settings.isTouchScreen` 判断点总结

> 用户提到的 `isTouchMode` 在原版 `tools/desktop-1.0.jar` 里没有同名符号；原版实际字段名是 `com.megacrit.cardcrawl.core.Settings.isTouchScreen`。本文按该字段统计。

## 来源与完整性

- 原版来源：`tools/desktop-1.0.jar`
- 临时反编译/反汇编目录：`agent-tmp/is-touchscreen-scan/`
- 扫描方式：
  - 解出 `com/megacrit/cardcrawl` class 后，用二进制搜索定位包含 `isTouchScreen` 的 class。
  - 对命中 class 用 `javap -c -p -l` 做字节码核对。
  - 对命中 class 用 CFR 反编译后阅读上下文。
- 结果：原版共有 36 个条件判断点直接读取 `Settings.isTouchScreen`；另有若干写入点负责切换这个状态。

## 总体规律

`Settings.isTouchScreen` 不是单纯的“是否有触摸设备”，而是原版用来切换交互语义的全局运行时模式：

- 桌面模式通常是“点击即执行”，例如买牌、拿 boss 遗物、选奖励牌、点营火选项。
- 触屏模式通常改成“两步确认”，先点中目标，再显示 `ConfirmButton`，点确认后才执行。
- 战斗手牌逻辑会改变拖拽坐标、出牌区域阈值、单体目标模式、触屏点按查看牌的行为。
- 光标、提示框、主菜单按钮尺寸、跳过提示文案等渲染也会随触屏模式变化。
- 控制器模式会主动把 `isTouchScreen` 关掉；离开控制器模式时再按保存的触屏设置恢复。

## 条件判断点

### 输入与光标

| 编号 | 位置 | 判断 | 作用 |
| --- | --- | --- | --- |
| 1 | `InputHelper.updateFirst()` | `!Settings.isTouchScreen` | 非触屏直接读取鼠标坐标并 clamp 到窗口；触屏分支会额外加 letterbox 偏移。 |
| 2 | `InputHelper.moveCursorToNeutralPosition()` | `Settings.isTouchScreen && !Settings.isControllerMode` | 只有触屏非控制器模式才把光标移到中性位置，并把原版触摸光标透明度归零。 |
| 3 | `GameCursor.render(SpriteBatch)` | `!Settings.isTouchScreen || Settings.isDev` | 桌面或开发模式渲染普通鼠标/放大镜光标；触屏模式改为渲染淡出的触摸光点。 |

### 战斗手牌与出牌

| 编号 | 位置 | 判断 | 作用 |
| --- | --- | --- | --- |
| 4 | `AbstractPlayer.updateInput()` | `!Settings.isTouchScreen` | 桌面出牌 drop zone 使用 `hoverStartLine` 或 300px 阈值；触屏分支固定使用 350px 阈值，避免手指拖牌时过早进入出牌区。 |
| 5 | `AbstractPlayer.updateInput()` | `Settings.isTouchScreen` | 拖牌进入过出牌区后又拖到底部时，触屏模式会先把光标移到中性位置再放牌。 |
| 6 | `AbstractPlayer.updateSingleTargetInput()` | `Settings.isTouchScreen && !Settings.isControllerMode && !isUsingClickDragControl && !InputHelper.isMouseDown` | 单体目标模式下，触屏会把光标缓慢推向屏幕上方/中央，避免停留在无效位置。 |
| 7 | `AbstractPlayer.updateSingleTargetInput()` | `Settings.isTouchScreen` | 单体目标取消时，触屏模式移动光标到中性位置后再释放卡牌。 |
| 8 | `AbstractPlayer.clickAndDragCards()` | `Settings.isTouchScreen && !Settings.isControllerMode && touchscreenInspectCount == 0` | 第一次点按手牌时，把牌移动到屏幕下方固定查看位置，并重置触屏查看计数。 |
| 9 | `AbstractPlayer.clickAndDragCards()` | `Settings.isTouchScreen && !Settings.isControllerMode` | 拖拽手牌时，触屏模式把卡牌目标 y 坐标加上 `270 * scale`，使牌显示在手指上方。 |
| 10 | `AbstractPlayer.clickAndDragCards()` | `Settings.isTouchScreen && !Settings.isControllerMode` | 进入敌人/自身加敌人目标模式时，触屏模式把牌居中放在较低位置并恢复 1.0 缩放。 |
| 11 | `AbstractPlayer.clickAndDragCards()` | `!Settings.isTouchScreen || Settings.isControllerMode` | 桌面或控制器模式走“松开鼠标后出牌/进入目标模式/短按转 click-drag”的分支；触屏非控制器不走这个分支。 |
| 12 | `AbstractPlayer.clickAndDragCards()` | `Settings.isTouchScreen && !Settings.isControllerMode && InputHelper.justReleasedClickLeft && hoveredCard != null` | 触屏松开手牌时进入触屏专用分支：增加查看计数；若在出牌区且可用则直接打出；二次查看后释放或切换到新 hover 的牌。 |

### 卡牌奖励与选牌

| 编号 | 位置 | 判断 | 作用 |
| --- | --- | --- | --- |
| 13 | `CardRewardScreen.update()` | `Settings.isTouchScreen` | 触屏模式更新奖励牌确认按钮；点确认后才执行选牌、发现、Codex、choose-one 或获得卡牌逻辑。 |
| 14 | `CardRewardScreen.cardSelectUpdate()` | `!Settings.isTouchScreen` | 非触屏点击奖励牌后立即选择/获得/关闭奖励界面。 |
| 15 | `CardRewardScreen.cardSelectUpdate()` | `InputHelper.justReleasedClickLeft && Settings.isTouchScreen && hoveredCard == null && !confirmButton.isDisabled && !confirmButton.hb.hovered` | 触屏模式下，选中奖励牌后如果松开在空白区域且没有按确认，则隐藏确认按钮并清空 `touchCard`。 |

### 商店

| 编号 | 位置 | 判断 | 作用 |
| --- | --- | --- | --- |
| 16 | `ShopScreen.resetTouchscreenVars()` | `Settings.isTouchScreen` | 只在触屏模式重置商店确认按钮和 `touchRelic/touchCard/touchPotion/touchPurge` 状态。 |
| 17 | `ShopScreen.update()` | `Settings.isTouchScreen` | 触屏模式更新商店确认按钮；确认后执行购买遗物、卡牌、药水或删牌。 |
| 18 | `ShopScreen.update()` | `!Settings.isTouchScreen` | 非触屏点击商店卡牌后立即购买；触屏分支改为检查金币并显示确认按钮。 |
| 19 | `ShopScreen.updatePurgeCard()` | `!Settings.isTouchScreen` | 非触屏点删牌位后立即执行删牌；触屏分支改为显示确认按钮。 |
| 20 | `ShopScreen.render(SpriteBatch)` | `Settings.isTouchScreen` | 只有触屏模式渲染商店确认按钮。 |
| 21 | `StoreRelic.update(float)` | `!Settings.isTouchScreen` | 非触屏点击商店遗物后立即购买；触屏分支改为显示确认按钮并保存 `touchRelic`。 |
| 22 | `StorePotion.update(float)` | `!Settings.isTouchScreen` | 非触屏点击药水后立即购买；触屏分支改为显示确认按钮并保存 `touchPotion`。 |

### Boss 奖励遗物/疫病

| 编号 | 位置 | 判断 | 作用 |
| --- | --- | --- | --- |
| 23 | `AbstractRelic.update()` | `!Settings.isTouchScreen` | 非触屏点击 boss 遗物后立即 `bossObtainLogic()`；触屏分支显示 boss 遗物确认按钮并写入 `touchRelic`。 |
| 24 | `AbstractBlight.update()` | `!Settings.isTouchScreen` | 非触屏点击 boss blight 后立即 `bossObtainLogic()`；触屏分支显示确认按钮并写入 `touchBlight`。 |

### 营火

| 编号 | 位置 | 判断 | 作用 |
| --- | --- | --- | --- |
| 25 | `AbstractCampfireOption.update()` | `!Settings.isTouchScreen` | 非触屏点击营火选项后立即 `useOption()` 并标记 `somethingSelected`；触屏分支保存 `touchOption` 并显示确认按钮。 |
| 26 | `CampfireUI.updateTouchscreen()` | `!Settings.isTouchScreen` | 非触屏直接跳过触屏确认按钮逻辑。 |
| 27 | `CampfireUI.render(SpriteBatch)` | `Settings.isTouchScreen` | 只有触屏模式渲染营火确认按钮。 |

### 提示、卡牌说明与结束回合

| 编号 | 位置 | 判断 | 作用 |
| --- | --- | --- | --- |
| 28 | `AbstractCard.renderCardTip(SpriteBatch)` | `AbstractDungeon.player.isDraggingCard && !Settings.isTouchScreen` | 非触屏拖牌时不渲染该牌 tooltip；触屏拖牌时允许继续显示。 |
| 29 | `TipHelper.render(SpriteBatch)` | `player.inSingleTargetMode || player.isDraggingCard && !Settings.isTouchScreen` | 单体目标模式或非触屏拖牌时清空并跳过 tooltip。 |
| 30 | `TipHelper.render(SpriteBatch)` | `Settings.isTouchScreen && player.isHoveringDropZone` | 触屏模式进入出牌区时清空 tooltip，避免遮挡出牌交互。 |
| 31 | `EndTurnButton.render(SpriteBatch)` | `hb.hovered && !AbstractDungeon.isScreenUp && !Settings.isTouchScreen` | 非触屏 hover 结束回合按钮时显示手牌上限提示；触屏模式不显示这个 hover tooltip。 |

### 主菜单、Credits 与布局

| 编号 | 位置 | 判断 | 作用 |
| --- | --- | --- | --- |
| 32 | `MenuButton.MenuButton(ClickResult, int)` | `Settings.isTouchScreen || Settings.isMobile` | 触屏/移动端主菜单按钮使用更大的 hitbox 和双倍纵向间距。 |
| 33 | `MenuButton.render(SpriteBatch)` | `Settings.isTouchScreen || Settings.isMobile` | 触屏/移动端主菜单按钮使用更高的高亮背景。 |
| 34 | `MenuButton.render(SpriteBatch)` | `Settings.isTouchScreen || Settings.isMobile` | 触屏/移动端主菜单按钮使用更大的字体渲染。 |
| 35 | `MainMenuScreen.renderNameEdit(SpriteBatch)` | `Settings.isTouchScreen || Settings.isMobile` | 触屏/移动端显示触屏可用的名字编辑提示；非触屏根据鼠标/控制器分别显示不同提示。 |
| 36 | `CreditsScreen.render(SpriteBatch)` | `Settings.isTouchScreen` | Credits 底部跳过提示使用触屏文案；非触屏再分鼠标和控制器文案。 |

## 状态写入与切换点

这些不是“判断点”，但决定了上面所有判断看到的运行时值。

| 编号 | 位置 | 写入 | 作用 |
| --- | --- | --- | --- |
| 1 | `Settings` 字段定义 | `isTouchScreen = false` | 默认关闭触屏模式。 |
| 2 | `Settings.initializeGamePref(boolean)` | `if (TOUCHSCREEN_ENABLED || isConsoleBuild) isTouchScreen = true` | 启动读取偏好；如果保存的触屏设置开启或是主机版，则进入触屏模式。 |
| 3 | `GiantToggleButton.useEffect()` | `Settings.isTouchScreen = this.ticked` | 选项界面的 Touchscreen Enabled 开关会立即改运行时触屏模式，并保存偏好。 |
| 4 | `InputHelper.leaveControllerMode()` | console build 写 `true`；否则写 `Settings.TOUCHSCREEN_ENABLED` | 鼠标/触摸输入使游戏离开控制器模式时，按平台和保存偏好恢复触屏状态。 |
| 5 | `CInputHelper.initializeIfAble()` | `Settings.isTouchScreen = false` | 启动检测到普通控制器时进入控制器模式并关闭触屏模式。 |
| 6 | `CInputHelper.setController(Controller)` | `Settings.isTouchScreen = false` | 手动切换控制器时关闭触屏模式。 |
| 7 | `CInputListener.buttonDown(...)` | `Settings.isTouchScreen = false` | 非控制器模式下按手柄按钮会进入控制器模式并关闭触屏模式。 |
| 8 | `CInputListener.axisMoved(...)` | `Settings.isTouchScreen = false` | 手柄轴正/负方向越过 deadzone 时进入控制器模式并关闭触屏模式。 |
| 9 | `CInputListener.povMoved(...)` | `Settings.isTouchScreen = false` | D-pad 输入会进入控制器模式并关闭触屏模式。 |
| 10 | `SteamInputDetect.run()` | `Settings.isTouchScreen = false` | Steam Input 检测到控制器后进入控制器模式并关闭触屏模式。 |

## 对兼容补丁的含义

- 如果全局强行开启 `Settings.isTouchScreen`，原版会同时改变商店、奖励牌、boss 遗物、营火、战斗手牌和 UI 渲染行为，不只是“允许触摸输入”。
- 最容易影响 Mod 的区域是“点击即执行改为触屏确认”的状态机：商店、营火、奖励牌、boss 遗物，以及战斗手牌的触屏查看/拖拽分支。
- 如果只想保留原版移动端某些交互，补丁应优先按类/方法白名单处理，而不是让所有 Mod 代码都看到全局 `Settings.isTouchScreen == true`。

## 混合模式还原的特性

1. 原版触摸光标透明度归零
2. 拖拽手牌时，触屏模式把卡牌目标 y 坐标加上 270 * scale，使牌显示在手指上方。
3. 触屏模式更新奖励牌确认按钮；点确认后才执行选牌、发现、Codex、choose-one 或获得卡牌逻辑。
4. 触屏模式下，选中奖励牌后如果松开在空白区域且没有按确认，则隐藏确认按钮并清空 touchCard
5. 触屏模式更新商店确认按钮；确认后执行购买遗物、卡牌、药水或删牌。
6. 非触屏点击商店卡牌后立即购买；触屏分支改为检查金币并显示确认按钮。
7. 非触屏点删牌位后立即执行删牌；触屏分支改为显示确认按钮。