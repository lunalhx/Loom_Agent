# CLI 运行

Loom Agent 已改为非 Web 的同步 CLI/REPL 形态，不再监听任何端口，也不存在 Docker/Compose 部署入口。旧的 `docker-compose.yml` 与容器化文档已失效。

构建并运行：

```bash
mvn clean package -DskipTests
java -jar Loom_Agent-app/target/Loom_Agent-app.jar --cwd /path/to/repo "prompt"
```
