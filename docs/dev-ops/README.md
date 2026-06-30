# Docker deployment

This directory contains the Docker Compose deployment for Loom Agent.

## Start everything

Create `docs/env/.env` from `docs/env/.env.example`, fill `DEEPSEEK_API_KEY`, then run:

```bash
cd docs/dev-ops
docker compose --env-file ../env/.env -f docker-compose.yml up -d --build
```

OrbStack will show the `loom-agent-app` and `loom-agent-playwright` containers.
SQLite, context artifacts, and logs are persisted in the `app-data` volume.

## Local development

SQLite is embedded, so no infrastructure containers are required. Start
`cn.lunalhx.ai.Application` from IDEA with the `dev` profile, or run:

```bash
mvn -pl Loom_Agent-app -am spring-boot:run
```

The default data directory is `~/.loom-agent`. Set `LOOM_DATA_DIR` to use a
different location.

The Spring config automatically imports `docs/env/.env` when IDEA starts from
either the repository root or the `Loom_Agent-app` module directory. If you use a
custom working directory, add the `.env` values to the IDEA run configuration
environment variables.

## Workspace mount

`HOST_WORKSPACE_ROOT` is mounted into the app container at `CONTAINER_WORKSPACE_ROOT`.
Any project you want to open later must live under this mounted root, or it must
be added as another Compose bind mount before the app container is recreated.
For local development you can mount a broad but still intentional folder such as
your Desktop:

```env
HOST_WORKSPACE_ROOT=/Users/you/Desktop
CONTAINER_WORKSPACE_ROOT=/Users/you/Desktop
CONTAINER_WORKSPACE_ROOTS=/Users/you/Desktop
```

On a server, prefer one dedicated workspace directory:

```env
HOST_WORKSPACE_ROOT=/srv/loom-agent/workspace
CONTAINER_WORKSPACE_ROOT=/workspace
CONTAINER_WORKSPACE_ROOTS=/workspace
```

If you intentionally mount more than one root, set `CONTAINER_WORKSPACE_ROOTS`
to a comma-separated list of the corresponding container paths. The backend
allows absolute workspaces only when they are inside one of these roots.

## Workspace execution model

The current implementation uses a `local` workspace provider: file tools and
shell tools operate on paths visible to the app process. In local development,
that can be your host machine. In the full Docker stack, that is the path mounted
into the app container.

The domain model now carries a workspace reference with a provider name and
location. Today the wired provider is still `local`, so local debugging keeps
working. A future desktop runner or cloud workspace can add another provider
without changing the agent loop API.
