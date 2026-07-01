# Function Calling 设计

## 工具 Schema 规范

所有内置、Context、Skill 和 Sub-Agent 工具使用 [JSON Schema Draft 2020-12](https://json-schema.org/draft/2020-12) 定义参数约束。Schema 由代码生成，启动时编译，调用前校验。

## 工具目录

完整工具清单及 Schema 由 `ToolCatalogMarkdownRenderer` 自动生成，请参见：

- [generated/function-calling-tools.md](../generated/function-calling-tools.md)

运行 `mvn test -pl Loom_Agent-app -Dtest=ToolCatalogDocTest -DtoolDocs.update=true` 可重新生成。

## 校验流程

```text
解析 JSON
→ 校验 action/final 外层结构
→ 确认工具在当前 context.toolSpecs 中可见
→ 根据工具 Schema 校验 input
→ InstructionGate
→ 权限判断/审批
→ 工具执行
```

## Schema 方言

JSON Schema Draft 2020-12，通过 `com.networknt:json-schema-validator:2.0.0` 实现编译和校验。

## 工具分类

| 类别 | 工具 |
|------|------|
| Built-in | `code_search`, `delete_files`, `find_files`, `git_op`, `list_dir`, `read_file`, `replace_in_file`, `run_shell`, `todo_write`, `write_file` |
| Context | `context_recall` |
| Skill | `activate_skill`, `copy_skill_resource`, `create_skill`, `read_skill_resource` |
| Memory | `memory_save`, `memory_search` |
| Sub-Agent | `spawn_agents` |
| MCP | 动态发现，不写入仓库文档 |

## 工具描述规范

所有工具的 description 字段统一按以下顺序编写：
1. 核心能力和返回结果
2. 何时使用
3. 何时不要使用，以及应该改用哪个工具
4. 重要限制
5. 权限说明
