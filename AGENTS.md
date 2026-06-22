# Repository Rules

## Mod-side fixes

- If a gameplay/runtime fix is shipped through a mod under `mods/`, document every individual fix in that mod's `README.md` in the same change.
- The mod `README.md` must state what each fix does, what symptom it addresses, and which patch class implements it.
- Do not accumulate unrelated Spire patches in one monolithic patch file. Split them by fix domain so each fix can be reviewed and reverted independently.

## Storage

- 对话过程中产生的反编译文件和临时文件等无需提交的文件放到：agent-tmp 目录下。

## Git

- 提交 Git 时，应当注意 message 规范：feat：新特性、fix：修复问题、perf：性能更改、chores：其它工作。