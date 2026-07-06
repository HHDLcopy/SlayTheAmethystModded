# Autoplay Module

游戏自动化控制模块。通过 AgentClient 向设备端的 AutoplayDriver 发送 EXEC 命令。

## 职责

- 卡片操作（PLAY_CARD）
- 回合控制（END_TURN, WAIT）
- 导航控制（PRESS_PROCEED, SKIP_ROOM）
- 模式切换（AUTONOMOUS / COMMAND_DRIVEN）

## 依赖

```
autoplay (自动化控制层)
  └── AgentClient → EXEC 命令
      └── ConnectorClient → 端口转发
```

## 文件结构

| 文件 | 职责 |
|------|------|
| `controller.py` | AutoplayController 类，封装 EXEC 命令 |
| `strategy.py` | 出牌策略（随机、优先攻击、自定义等） |

## EXEC 命令参考

| 命令 | 参数 | 说明 |
|------|------|------|
| `PLAY_CARD` | `{}` | 随机打一张可用的牌 |
| `END_TURN` | `{}` | 结束当前回合 |
| `PRESS_PROCEED` | `{}` | 点击继续按钮 |
| `SKIP_ROOM` | `{}` | 跳过当前房间 |
| `WAIT` | `{"ms": 500}` | 等待指定毫秒 |
| `MODE_COMMAND` | `{"mode":"COMMAND_DRIVEN"}` | 切换到命令驱动模式 |
| `MODE_COMMAND` | `{"mode":"AUTONOMOUS"}` | 切换到自动模式 |

## 使用

```python
from scripts.tools.autoplay.controller import AutoplayController
from scripts.tools.lib.agent_client import AgentClient

agent = AgentClient(connector=conn)
agent.connect()
ctrl = AutoplayController(agent)

ctrl.set_mode("COMMAND_DRIVEN")
ctrl.play_card()
ctrl.end_turn()
ctrl.wait(1000)
```
