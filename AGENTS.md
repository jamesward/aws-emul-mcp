# AGENTS.md: aws-emul-mcp

A remote MCP server (Scala 3, Kyo, Scala Native) that relays the tools of the AWS Knowledge MCP server
(`https://knowledge-mcp.global.api.aws`) and adds tools that start private [AWS Emulator](https://github.com/aws/aws-emulator)
instances on the same dyno. AWS SDK/CLI requests signed with an emulator's keys are proxied to that emulator, so a user
adds the public MCP URL and works against AWS APIs with nothing installed.

## Skills

Follow the `zen-of-projects` Skill. Also:

- For Scala 3 and ZIO code (writing or reviewing it), follow `zen-of-scala`. This project uses Kyo instead of ZIO:
  apply the same rules (typed errors, no silent failures, typed models, `Scope`-managed resources) in Kyo's terms.
- For design, domain modeling, effects and testing strategy, follow `zen-of-james`.

Get them with `./sbta extractSkillsJars` (into `.kiro/skills/`) and read the relevant ones before working.

## MCP

The `sbta` server (`./sbta --mcp`, configured in `.mcp.json`, `.claude/settings.json` and `.kiro/settings/mcp.json`)
starts sbt itself; logs are in `project/target/sbta/`. In Claude Code its tools are deferred: load them with ToolSearch
(search `sbta`).

## Build, Test & Dev Workflow

- Use the MCP server `sbta` (this build's `./sbta --mcp`) for all sbt work: build, test and run
  with `compile`, `test` (`tests` for specific classes, `full` for all), `run`, `clean` and
  `publish-local`; inspect the build with `projects`, `test-names`, `main-classes`, `dependencies` and
  `show`. Use `sbt-task` for any other command (e.g. `reload`) and `list-tasks` to find tasks. Don't run
  `sbt` or `./sbta` in a shell while the MCP tools are available; if the server is unavailable, say so and
  fall back to `./sbta <command>`.
- After editing Scala 3 sources, check them with `check` (pass the edited files: a fast type check
  without compiling; `"scope":"module"` after changing an API), then run `compile` and `test` before
  declaring the work done.
- For Scala symbols use `glob-search` (find or list), `inspect` (members and signatures) and
  `symbol-location` (source location) rather than text search or guessing APIs. JavaDoc/ScalaDoc
  lookups are the proxied javadocs.dev tools of the same server.

Full validation (the native equivalent of `stage` is `nativeLink`):

```bash
./sbta extractSkillsJars
./sbta "Test / compile; testFull; nativeLink"
```

then the smoke test in `.github/workflows/ci.yml` (start the binary, request `/health` and `tools/list`).

- Needs Java 25, clang, `libssl-dev` and `liburing-dev`, the libraries buildpack-scala-native installs on Heroku; the link
  options are in `project/NativeLinking.scala` (liburing is linked statically when its headers are present). The thin sbt client hides test output; use
  `./sbta --server -no-colors testFull` (or the `test` tool) to see failures.
- `EmulatorIntegrationTest` runs a real emulator: it needs `aws-emulator` installed
  (`/opt/aws-emulator/bin/aws-emulator`, or `AWS_EMULATOR_BIN`). Without it the test is reported as skipped by `assume`.
  CI installs the package. Not paid or metered.
- The native binary: `target/out/native0.5/scala-3.10.1/aws-emul-mcp/aws-emul-mcp`.

## Production

- Heroku app (name and URL to be recorded once created), buildpacks `heroku/jvm` then
  `https://github.com/jamesward/buildpack-scala-native#aws-emulator` (see `app.json`). The `.aws-emulator` file makes the
  buildpack ship the emulator in the slug; `.profile.d` sets `AWS_EMULATOR_BIN`.
- Exactly one web dyno: emulators are child processes holding in-memory state, and requests are routed to them by access
  key on the dyno that started them. Each idle emulator holds about 180 MB of its own memory, plus about 110 MB of binary
  pages shared by all of them; `MAX_EMULATORS` (default 2) must fit the dyno size (`standard-2x` in `app.json`).
- The emulator's embedded mode (in-process, Rust only) was considered and not used: it needs the emulator built from source
  (100 GB `target/`) behind a C ABI wrapper, and most of an emulator's memory is its own state, which embedding would not
  share.
- Logs: one JSON object per line on stdout from `AppLog` (`timestamp`, `level`, `logger`, `message`, `cause`, extra fields),
  with the router's `X-Request-ID` as `request_id` on every entry written while a request is handled. Kyo's own `Log`
  output goes through `AppLog.JsonLogger`. `KYO_NET_BACKEND=epoll` in the Procfile avoids kyo-net's plain-text startup
  notice about io_uring (Heroku dynos can't create rings).
- Runtime metrics (`log-runtime-metrics`) are not on yet: the owner should enable them once the app exists.

## Maintenance routine

`.factory/MAINTENANCE.md`, on the schedule set for this repo's routine.

## Exceptions

- **Kyo `1.0.0-RC7`** (prerelease): Kyo has no stable 1.x release yet, and it is the only Kyo line with Scala Native
  builds of `kyo-http`, `kyo-net` and `kyo-system`.
- **No sbt-native-packager:** a Scala Native server is a single binary that `buildpack-scala-native` ships, and `nativeLink`
  stands in for `stage`. sbt-reload works on the JVM build only, so it is disabled when the build is Native.
- **kyo-ffi-plugin's tasks reimplemented:** Kyo's documented Native setup uses kyo-ffi-plugin's
  `ffiNativeDependencyLinkingOptions` / `ffiNativeDependencyCompileOptions`, but the plugin is published for sbt 1 only.
  `project/NativeLinking.scala` defines the same two tasks for sbt 2, reading the same `META-INF/kyo-ffi` manifests. Switch to
  the plugin once it is published for sbt 2.
- **No `AppTest` / Testcontainers:** the only service dependency is the emulator binary, which tests start as a child
  process exactly as production does.
- **Tests use kyo-test** (`assert` with plain boolean expressions) instead of ZIO Test, in a Kyo project.
- **`kyo-mcp` is not used:** it has stdio, Unix-socket and in-memory JSON-RPC transports but no Streamable HTTP, and its
  handlers advertise schemas derived from Scala types, so it cannot relay the upstream server's tools as they are.
- **`.jvmopts` sets `-Xmx3g`:** Scala Native's linker runs out of the default 1 GB heap on this build.
- **Custom HTTP/1.1 client for the emulator hop** (`http/Http1.scala`, `http/EmulatorConnection.scala`): kyo-http's
  client has no per-connection CA setting, and every emulator run has its own self-signed CA, so the proxy speaks HTTP/1.1
  over kyo-net's `connectTls` with `caCertPath`.
- **Buildpack branch:** the `aws-emulator` branch of `buildpack-scala-native` until it is merged.
