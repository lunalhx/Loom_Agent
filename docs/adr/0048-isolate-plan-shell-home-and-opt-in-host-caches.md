# Isolate Plan Shell HOME and opt in host artifact caches

Status: Accepted.

Plan Shell starts with an empty Run-scoped HOME and disposable cache locations, cannot read host credentials or developer configuration, has no network, and never writes real host caches. A user-local Host Resource Grant may expose an explicit artifact cache such as `~/.m2/repository` read-only for one Workspace, while project policy cannot request that access and credential-bearing files such as Maven settings, npm configuration, Gradle credentials, SSH material, and cloud profiles remain hidden. Tool-specific adapters may direct locks and metadata to Disposable State, but the first capability does not require a general overlay filesystem; missing cached dependencies fail explicitly.
