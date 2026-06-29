# Loom Agent 数据库脚本

## 新服务器或全新数据库

只执行根目录的完整结构脚本：

```bash
mysql -h <host> -P <port> -u <user> -p < loom-agent-schema.sql
```

Docker Compose 会把本目录挂载到 `/docker-entrypoint-initdb.d`。根目录只有
`loom-agent-schema.sql`，因此 MySQL 首次初始化只会执行这一份 SQL。

## 已有数据库升级

`migrations/` 保存历史增量迁移，按文件名前的日期顺序执行。增量脚本不是完整
建库脚本，也不应和 `loom-agent-schema.sql` 在同一个全新数据库上重复执行。
