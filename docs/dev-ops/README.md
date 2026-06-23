# Docker deployment

This directory contains the local and server Docker Compose files for Loom Agent.

## Start everything

Create `docs/env/.env` from `docs/env/.env.example`, fill `DEEPSEEK_API_KEY`, then run:

```bash
cd docs/dev-ops
docker compose --env-file ../env/.env -f docker-compose.yml up -d --build
```

OrbStack will show the `loom-agent-app`, `loom-agent-mysql`, and `loom-agent-redis` containers automatically after Compose starts them.

## Start only MySQL and Redis

Use this when you run the Spring Boot app from your IDE:

```bash
cd docs/dev-ops
docker compose --env-file ../env/.env -f docker-compose-environment.yml up -d
```

Then start `cn.lunalhx.ai.Application` from IDEA with the `dev` profile. The
default dev config connects to the Docker services through the host ports:

```env
MYSQL_HOST=127.0.0.1
MYSQL_PORT=13306
REDIS_HOST=127.0.0.1
REDIS_PORT=16379
```

The app does not have to run in Docker during development. Running only MySQL
and Redis in Docker is usually the fastest local loop.

The Spring config automatically imports `docs/env/.env` when IDEA starts from
either the repository root or the `Loom_Agent-app` module directory. If you use a
custom working directory, add the `.env` values to the IDEA run configuration
environment variables.

## Start app with an existing environment compose

```bash
cd docs/dev-ops
docker compose --env-file ../env/.env \
  -f docker-compose-environment.yml \
  -f docker-compose-app.yml \
  up -d --build
```

## Admin tools

The full compose file keeps phpMyAdmin and Redis Commander behind the `admin` profile:

```bash
cd docs/dev-ops
docker compose --env-file ../env/.env -f docker-compose.yml --profile admin up -d
```

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
