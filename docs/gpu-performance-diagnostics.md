# GPU 性能诊断与配对基准

## 配对方法

目标是只改变 GPU 资源计数插桩，避免把诊断自身或战斗随机性算成开销。

1. 固定设备、刷新率、FPS、模组、角色、怪物、手牌和战斗结果。
2. 两组都使用 `-PperformanceDeepDiagnostics=true`，保持 frame ring、GC 诊断和采样路径一致。
3. OFF 关闭 GPU 资源诊断，ON 开启；按 `OFF/ON`、`ON/OFF` 交替执行至少 5 对。
4. 仅接受动作数、回合数和结果一致的轮次；异常轮次直接重跑。
5. 报告每对 `ON - OFF`，以配对中位数、范围和正向配对数为主，不用单轮结果下结论。

固定房间示例：

```properties
schemaVersion=1
character=IRONCLAD
monster=Lagavulin
cards=Strike_R,Strike_R,Strike_R,Strike_R,Strike_R
```

启动示例：

```bash
./gradlew :app:stsStartAutoplay \
  -PdeviceSerial=<serial> \
  -PperformanceDeepDiagnostics=true \
  -PautoplaySaveMode=fresh \
  -PautoplayMode=single_room \
  -PautoplaySingleRoomSpec=<device-spec-path>
```

标准 `frame-probe-incidents.jsonl` 只写入超过预算的帧，适合定位卡顿，不适合计算全量 p50/p95 或亚毫秒插桩成本。量化小开销时必须使用临时全帧采集版本，并保证 OFF/ON 使用同一版本；测试后恢复标准阈值。

## 指标含义

| 指标 | 含义 |
|---|---|
| `totalMs` | 一帧从开始到 `Display.update()` 完成的总时间。 |
| `renderMs` | 游戏 `listener.render()` 的 CPU 侧时间。 |
| `swapMs` | `Display.update()` / EGL swap 时间，受帧同步和设备调度影响。 |
| `guardianMs` | GPU resource guardian 本帧耗时。 |
| `reclaimMs` | texture/FBO reclaim 本帧耗时。 |
| `flushes` | `SpriteBatch` flush 次数；升高通常表示批次被切碎。 |
| `switches` | 纹理切换次数；需结合 flush 一起判断。 |
| p50/p95/p99 | 50%、95%、99% 的样本不超过该值。仅对全帧数据成立。 |
| `>=8/16/33ms` | 越过对应帧时阈值的全量帧比例。 |

`action` 是采样时的当前 action，用于比较 `DrawCardAction`、`EmptyDeckShuffleAction` 等同类阶段。它不是整帧耗时的唯一归因对象。

## 当前基线

5 对固定战斗、每种状态约 14,700 个战斗帧的结果：

- 总帧均值配对中位差：`-0.127ms`，范围 `-1.217 ~ +0.193ms`。
- swap 均值配对中位差：`+0.033ms`，范围 `-0.387 ~ +0.127ms`。
- `>=16ms` 帧比例中位差：`+0.038` 个百分点。
- 抽牌和洗牌未出现可重复回归。

结论：低扰动模式下未检出稳定的 GPU 资源计数开销；单轮小于约 `0.2ms` 的差值应视为设备噪声，除非更多配对呈现一致方向。
