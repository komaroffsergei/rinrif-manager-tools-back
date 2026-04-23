# Manager Tools Backend

Java backend for the RINRIF Manager Tools Angular frontend.

The project follows the RINRIF backend layout used by `adm`: Maven multi-module root with `model`, `core`, `remote-api`, and `server` modules.

## Runtime

- Java 8
- Maven 3.5.2
- Repository operations are executed through embedded JGit

## Local Run

Run the `server` module with `bootstrap-local.yml`.

The local context path is:

```text
http://127.0.0.1:6756/app/rinrif/manager-tools
```

The deploy context behind Nginx is:

```text
/app/rinrif/manager-tools
```

The application servlet context is:

```text
/manager-tools
```

Optional environment:

```text
MANAGER_TOOLS_ENV_FILE=.env
GITLAB_BASE_URL=https://builder.reinform-int.ru/gitlab
GITLAB_PAT=<personal access token>
MANAGER_TOOLS_STORAGE_ROOT=storage
GIT_COMMAND_TIMEOUT_MS=300000
SEARCH_MAX_QUERY_LENGTH=512
SEARCH_DEFAULT_MAX_COMMITS=30
SEARCH_SCAN_LIMIT=1000
```

If `MANAGER_TOOLS_ENV_FILE` is not set, the backend also reads an optional `.env` file from the current working directory. JVM properties and process environment variables take precedence over Spring config values; Spring config values take precedence over `.env`.

The same values can be supplied by Spring Config Server through:

```yaml
manager-tools:
  storage-root: storage
  env-file: .env
  gitlab:
    base-url: https://builder.reinform-int.ru/gitlab
    pat: ${MANAGER_TOOLS_GITLAB_PAT:}
  git:
    command-timeout-ms: 300000
  search:
    max-query-length: 512
    default-max-commits: 30
    scan-limit: 1000
```
