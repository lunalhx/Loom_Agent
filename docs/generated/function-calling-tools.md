# Function Calling 工具目录

本文件由 `ToolCatalogMarkdownRenderer` 自动生成，基于代码中所有内置、Context、Skill 和 Sub-Agent 工具的 `ToolSpec`。动态 MCP 工具不包含在内。

**Schema Dialect**: [JSON Schema Draft 2020-12](https://json-schema.org/draft/2020-12)

工具数量：18

---
## 1. `activate_skill` (Skill)

Activate a skill by name. Activated skills provide specialized instructions and resources to the agent. Use to load project-specific or user-level skills.

<details>
<summary>Schema</summary>

```json
{
  "type" : "object",
  "properties" : {
    "name" : {
      "type" : "string",
      "minLength" : 1,
      "description" : "Name of the skill to activate"
    }
  },
  "required" : [ "name" ],
  "additionalProperties" : false
}
```

</details>

---

## 2. `code_search` (Built-in)

在工作区内执行不区分大小写的文本子串搜索，返回匹配文件、行号和代码片段。不是语义搜索，不是正则搜索。何时使用：需要根据代码内容定位文件时。何时不要使用：按文件名查找请用 find_files，读取已知路径文件请用 read_file。权限：只读自动放行

<details>
<summary>Schema</summary>

```json
{
  "type" : "object",
  "properties" : {
    "query" : {
      "type" : "string",
      "minLength" : 1,
      "description" : "搜索词，不区分大小写的文本子串搜索"
    },
    "path" : {
      "type" : "string",
      "default" : ".",
      "description" : "相对路径"
    },
    "limit" : {
      "type" : "integer",
      "minimum" : 1,
      "default" : 20,
      "description" : "最大结果数"
    }
  },
  "required" : [ "query" ],
  "additionalProperties" : false
}
```

</details>

---

## 3. `context_recall` (Context)

读取被压缩或外置的当前运行的上下文 artifact。只读取 Agent 上下文历史，不读取工作区文件。何时使用：需要回顾被压缩的旧 Observation 或子 Agent 摘要时。何时不要使用：读取工作区文件请用 read_file，搜索代码请用 code_search。支持 list/search/get 三种操作。权限：只读自动放行

<details>
<summary>Schema</summary>

```json
{
  "type" : "object",
  "properties" : {
    "action" : {
      "type" : "string",
      "enum" : [ "list", "search", "get" ]
    },
    "artifactId" : {
      "type" : "string",
      "description" : "get 操作的 artifact ID"
    },
    "query" : {
      "type" : "string",
      "description" : "search 操作的关键词"
    },
    "offset" : {
      "type" : "integer",
      "minimum" : 0,
      "default" : 0,
      "description" : "get 操作的字符偏移"
    },
    "maxChars" : {
      "type" : "integer",
      "minimum" : 1,
      "maximum" : 20000,
      "default" : 8000,
      "description" : "get 操作的最大返回字符数"
    },
    "limit" : {
      "type" : "integer",
      "minimum" : 1,
      "default" : 20,
      "description" : "list/search 的最大结果数"
    }
  },
  "required" : [ "action" ],
  "additionalProperties" : false
}
```

</details>

---

## 4. `copy_skill_resource` (Skill)

Copy a binary or text resource from an activated skill's snapshot to the workspace. Scripts must be copied before execution.

<details>
<summary>Schema</summary>

```json
{
  "type" : "object",
  "properties" : {
    "skill" : {
      "type" : "string",
      "minLength" : 1,
      "description" : "Skill name"
    },
    "path" : {
      "type" : "string",
      "minLength" : 1,
      "description" : "Relative path to the resource within the skill directory"
    },
    "destination" : {
      "type" : "string",
      "minLength" : 1,
      "description" : "Destination path relative to workspace root"
    },
    "overwrite" : {
      "type" : "boolean",
      "default" : false,
      "description" : "Whether to overwrite existing file"
    }
  },
  "required" : [ "skill", "path", "destination" ],
  "additionalProperties" : false
}
```

</details>

---

## 5. `create_skill` (Skill)

Create a new project-level skill. The skill is written to .agents/skills/<name>/SKILL.md in the workspace. After creation the skill becomes available in the catalog for the current and future runs. Only project skills can be created; user skills must be created manually.

<details>
<summary>Schema</summary>

```json
{
  "type" : "object",
  "properties" : {
    "name" : {
      "type" : "string",
      "minLength" : 1,
      "description" : "Skill name, must match ^[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}$"
    },
    "description" : {
      "type" : "string",
      "minLength" : 1,
      "description" : "Short one-line description of the skill"
    },
    "content" : {
      "type" : "string",
      "minLength" : 1,
      "description" : "Full SKILL.md body content (after frontmatter). The system will wrap it with correct YAML frontmatter automatically."
    }
  },
  "required" : [ "name", "description", "content" ],
  "additionalProperties" : false
}
```

</details>

---

## 6. `delete_files` (Built-in)

删除工作区内明确指定的文件或目录；目录会在高危审批后递归删除，每次最多 20 个目标，不支持通配符

<details>
<summary>Schema</summary>

```json
{
  "type" : "object",
  "properties" : {
    "paths" : {
      "type" : "array",
      "items" : {
        "type" : "string",
        "minLength" : 1
      },
      "minItems" : 1,
      "maxItems" : 20,
      "description" : "1~20 个工作区相对文件或目录路径"
    }
  },
  "required" : [ "paths" ],
  "additionalProperties" : false
}
```

</details>

---

## 7. `find_files` (Built-in)

按文件名 Glob 模式递归查找文件，返回相对路径列表。只匹配文件名/路径，不搜索文件内容。何时使用：已知文件名模式需要定位文件时。何时不要使用：搜索文件内容请用 code_search，浏览已知目录结构请用 list_dir。限制：自动跳过 .git/.idea/target/node_modules 等目录

<details>
<summary>Schema</summary>

```json
{
  "type" : "object",
  "properties" : {
    "pattern" : {
      "type" : "string",
      "minLength" : 1,
      "description" : "Glob 模式，如 *hello*.py 或 src/**/*.java"
    },
    "path" : {
      "type" : "string",
      "default" : ".",
      "description" : "搜索起点"
    },
    "maxDepth" : {
      "type" : "integer",
      "minimum" : 1,
      "maximum" : 50,
      "default" : 20,
      "description" : "最大深度"
    },
    "limit" : {
      "type" : "integer",
      "minimum" : 1,
      "default" : 50,
      "description" : "最大结果数"
    },
    "caseSensitive" : {
      "type" : "boolean",
      "default" : false,
      "description" : "是否大小写敏感"
    }
  },
  "required" : [ "pattern" ],
  "additionalProperties" : false
}
```

</details>

---

## 8. `git_op` (Built-in)

执行受限 Git 操作；status/diff/log 自动放行，init/add/commit 需普通审批，push/reset/clean/rebase/checkout 需高危审批

<details>
<summary>Schema</summary>

```json
{
  "type" : "object",
  "properties" : {
    "operation" : {
      "type" : "string",
      "enum" : [ "status", "diff", "log", "init", "add", "commit", "push", "reset", "clean", "rebase", "checkout" ]
    }
  },
  "required" : [ "operation" ],
  "oneOf" : [ {
    "properties" : {
      "operation" : {
        "const" : "status"
      }
    },
    "additionalProperties" : false
  }, {
    "properties" : {
      "operation" : {
        "const" : "diff"
      },
      "path" : {
        "type" : "string",
        "description" : "可选相对路径"
      }
    },
    "additionalProperties" : false
  }, {
    "properties" : {
      "operation" : {
        "const" : "log"
      },
      "limit" : {
        "type" : "integer",
        "minimum" : 1,
        "maximum" : 50,
        "default" : 10
      }
    },
    "additionalProperties" : false
  }, {
    "properties" : {
      "operation" : {
        "const" : "init"
      }
    },
    "additionalProperties" : false
  }, {
    "properties" : {
      "operation" : {
        "const" : "add"
      },
      "path" : {
        "type" : "string",
        "description" : "可选相对路径"
      }
    },
    "additionalProperties" : false
  }, {
    "properties" : {
      "operation" : {
        "const" : "commit"
      },
      "message" : {
        "type" : "string",
        "minLength" : 1
      }
    },
    "additionalProperties" : false
  }, {
    "properties" : {
      "operation" : {
        "const" : "push"
      },
      "remote" : {
        "type" : "string",
        "description" : "可选 remote"
      },
      "refspec" : {
        "type" : "string",
        "description" : "可选 refspec"
      },
      "force" : {
        "type" : "boolean",
        "default" : false
      }
    },
    "additionalProperties" : false
  }, {
    "properties" : {
      "operation" : {
        "const" : "reset"
      },
      "branch" : {
        "type" : "string",
        "description" : "可选分支"
      },
      "force" : {
        "type" : "boolean",
        "default" : false
      }
    },
    "additionalProperties" : false
  }, {
    "properties" : {
      "operation" : {
        "const" : "clean"
      },
      "dryRun" : {
        "type" : "boolean",
        "default" : true
      }
    },
    "additionalProperties" : false
  }, {
    "properties" : {
      "operation" : {
        "const" : "rebase"
      },
      "branch" : {
        "type" : "string",
        "description" : "可选分支"
      }
    },
    "additionalProperties" : false
  }, {
    "properties" : {
      "operation" : {
        "const" : "checkout"
      },
      "branch" : {
        "type" : "string",
        "description" : "可选分支"
      },
      "path" : {
        "type" : "string",
        "description" : "可选相对路径"
      }
    },
    "additionalProperties" : false
  } ]
}
```

</details>

---

## 9. `list_dir` (Built-in)

列出已知工作区目录内的文件和子目录结构。何时使用：浏览已知路径的目录内容时。何时不要使用：名称未知需要递归按模式匹配时请用 find_files，搜索文件内容请用 code_search。限制：最大深度 3 层。权限：只读自动放行

<details>
<summary>Schema</summary>

```json
{
  "type" : "object",
  "properties" : {
    "path" : {
      "type" : "string",
      "default" : ".",
      "description" : "相对路径"
    },
    "maxDepth" : {
      "type" : "integer",
      "minimum" : 1,
      "maximum" : 3,
      "default" : 1,
      "description" : "最大深度"
    }
  },
  "additionalProperties" : false
}
```

</details>

---

## 10. `memory_save` (Memory)

保存一条长期记忆，供后续对话使用。type: PREFERENCE/WORKFLOW/PROJECT/REFERENCE/PITFALL

<details>
<summary>Schema</summary>

```json
{
  "type" : "object",
  "properties" : {
    "type" : {
      "type" : "string",
      "enum" : [ "PREFERENCE", "WORKFLOW", "PROJECT", "REFERENCE", "PITFALL" ]
    },
    "title" : {
      "type" : "string",
      "maxLength" : 200
    },
    "body" : {
      "type" : "string",
      "maxLength" : 10000
    },
    "importance" : {
      "type" : "integer",
      "minimum" : 0,
      "maximum" : 100
    }
  },
  "required" : [ "type", "title", "body" ],
  "additionalProperties" : false
}
```

</details>

---

## 11. `memory_search` (Memory)

搜索长期记忆，返回匹配的记忆条目（只读）

<details>
<summary>Schema</summary>

```json
{
  "type" : "object",
  "properties" : {
    "query" : {
      "type" : "string",
      "description" : "搜索关键词"
    },
    "limit" : {
      "type" : "integer",
      "minimum" : 1,
      "maximum" : 20,
      "default" : 10
    }
  },
  "required" : [ "query" ],
  "additionalProperties" : false
}
```

</details>

---

## 12. `read_file` (Built-in)

读取工作区内已知路径的单个文本文件，返回指定行号范围的内容。何时使用：已确知文件路径需要查看内容时。何时不要使用：路径未知请先用 find_files 定位，搜索内容请用 code_search。限制：最多返回 200 行。权限：只读自动放行

<details>
<summary>Schema</summary>

```json
{
  "type" : "object",
  "properties" : {
    "path" : {
      "type" : "string",
      "minLength" : 1,
      "description" : "相对路径"
    },
    "startLine" : {
      "type" : "integer",
      "minimum" : 1,
      "default" : 1,
      "description" : "起始行号"
    },
    "endLine" : {
      "type" : "integer",
      "minimum" : 1,
      "description" : "结束行号，最多 200 行"
    }
  },
  "required" : [ "path" ],
  "additionalProperties" : false
}
```

</details>

---

## 13. `read_skill_resource` (Skill)

Read a text resource from an activated skill's snapshot. Use to access skill-provided scripts, templates, or reference files.

<details>
<summary>Schema</summary>

```json
{
  "type" : "object",
  "properties" : {
    "skill" : {
      "type" : "string",
      "minLength" : 1,
      "description" : "Skill name"
    },
    "path" : {
      "type" : "string",
      "minLength" : 1,
      "description" : "Relative path to the resource within the skill directory"
    },
    "offset" : {
      "type" : "integer",
      "minimum" : 0,
      "default" : 0,
      "description" : "Character offset to start reading from"
    },
    "maxChars" : {
      "type" : "integer",
      "minimum" : 1,
      "default" : 8000,
      "description" : "Maximum characters to read"
    }
  },
  "required" : [ "skill", "path" ],
  "additionalProperties" : false
}
```

</details>

---

## 14. `replace_in_file` (Built-in)

精确替换工作区内文本文件的一段内容。何时使用：局部修改已知文件时。何时不要使用：创建新文件或整体重写请用 write_file。oldText 必须精确匹配（含空白），expectedOccurrences 指定期望匹配次数。权限：写入前必须人工确认

<details>
<summary>Schema</summary>

```json
{
  "type" : "object",
  "properties" : {
    "path" : {
      "type" : "string",
      "minLength" : 1,
      "description" : "相对路径"
    },
    "oldText" : {
      "type" : "string",
      "minLength" : 1,
      "description" : "要替换的旧文本"
    },
    "newText" : {
      "type" : "string",
      "description" : "替换后的新文本"
    },
    "expectedOccurrences" : {
      "type" : "integer",
      "minimum" : 1,
      "default" : 1,
      "description" : "期望匹配次数"
    }
  },
  "required" : [ "path", "oldText", "newText" ],
  "additionalProperties" : false
}
```

</details>

---

## 15. `run_shell` (Built-in)

在工作区沙箱内执行已分类命令。何时使用：构建、测试、受支持的 CLI 命令。何时不要使用：Git 操作优先用 git_op，文件搜索用 find_files/code_search，文件删除用 delete_files。限制：禁止 shell 解释器、管道、重定向等元字符；只读命令自动放行，写命令需确认，高危命令需高危确认

<details>
<summary>Schema</summary>

```json
{
  "type" : "object",
  "properties" : {
    "command" : {
      "type" : "string",
      "minLength" : 1,
      "description" : "要执行的 shell 命令"
    },
    "cwd" : {
      "type" : "string",
      "default" : ".",
      "description" : "相对工作目录"
    },
    "timeoutMs" : {
      "type" : "integer",
      "minimum" : 1,
      "default" : 30000,
      "description" : "超时毫秒，受系统配置上限限制"
    }
  },
  "required" : [ "command" ],
  "additionalProperties" : false
}
```

</details>

---

## 16. `spawn_agents` (Sub-Agent)

派生多个隔离上下文的子 Agent 并发执行可独立的子任务，只向主 Agent 回传结构化摘要；只读并发优先，编辑角色只能串行

<details>
<summary>Schema</summary>

```json
{
  "type" : "object",
  "properties" : {
    "reason" : {
      "type" : "string",
      "minLength" : 1,
      "description" : "派生原因"
    },
    "maxConcurrency" : {
      "type" : "integer",
      "minimum" : 1,
      "maximum" : 10,
      "default" : 2,
      "description" : "最大并发数，受配置上限约束"
    },
    "returnMode" : {
      "type" : "string",
      "const" : "summary_only"
    },
    "tasks" : {
      "type" : "array",
      "minItems" : 1,
      "items" : {
        "type" : "object",
        "properties" : {
          "taskId" : {
            "type" : "string",
            "minLength" : 1,
            "description" : "稳定 ID"
          },
          "role" : {
            "type" : "string",
            "enum" : [ "explorer", "reviewer", "editor" ]
          },
          "question" : {
            "type" : "string",
            "minLength" : 1,
            "description" : "子任务"
          },
          "pathScope" : {
            "type" : "string",
            "description" : "可选相对路径"
          },
          "expectedOutput" : {
            "type" : "string",
            "description" : "可选输出要求"
          },
          "maxSteps" : {
            "type" : "integer",
            "minimum" : 1,
            "description" : "可选最大步数"
          }
        },
        "required" : [ "taskId", "role", "question" ],
        "additionalProperties" : false
      }
    }
  },
  "required" : [ "reason", "tasks", "returnMode" ],
  "additionalProperties" : false
}
```

</details>

---

## 17. `todo_write` (Built-in)

更新当前 Agent 计划和子任务状态，不修改工作区文件

<details>
<summary>Schema</summary>

```json
{
  "type" : "object",
  "properties" : {
    "todos" : {
      "type" : "array",
      "minItems" : 1,
      "items" : {
        "type" : "object",
        "properties" : {
          "id" : {
            "type" : "string",
            "description" : "可选任务ID"
          },
          "content" : {
            "type" : "string",
            "minLength" : 1,
            "description" : "任务内容"
          },
          "status" : {
            "type" : "string",
            "enum" : [ "pending", "in_progress", "completed", "blocked", "skipped" ]
          },
          "evidence" : {
            "type" : "string",
            "description" : "可选完成证据"
          },
          "blocker" : {
            "type" : "string",
            "description" : "可选阻塞原因"
          }
        },
        "required" : [ "content", "status" ],
        "additionalProperties" : false
      }
    }
  },
  "required" : [ "todos" ],
  "additionalProperties" : false
}
```

</details>

---

## 18. `write_file` (Built-in)

创建新文件或整体覆盖工作区内文本文件。何时使用：创建新文件或完整重写文件内容时。何时不要使用：局部修改请用 replace_in_file。权限：写入前必须人工确认，mode 默认为 create（文件已存在时失败）

<details>
<summary>Schema</summary>

```json
{
  "type" : "object",
  "properties" : {
    "path" : {
      "type" : "string",
      "minLength" : 1,
      "description" : "相对路径"
    },
    "content" : {
      "type" : "string",
      "description" : "文件内容"
    },
    "mode" : {
      "type" : "string",
      "enum" : [ "create", "overwrite" ],
      "default" : "create",
      "description" : "create 或 overwrite"
    }
  },
  "required" : [ "path", "content" ],
  "additionalProperties" : false
}
```

</details>

---


*Generated by `ToolCatalogMarkdownRenderer`. Do not edit manually.*
