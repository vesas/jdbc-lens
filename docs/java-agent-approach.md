# Java Agent Approach — Design Notes

An alternative architecture that profiles JDBC with no source or config
changes to the application under test. Requires only a JVM flag (or a
runtime attach).

## Motivation

The current wrapper design requires the app to explicitly hand its
`DataSource` to the profiler. The agent removes this friction entirely:
the app is unmodified; the profiler is a tool you attach, not a library
you adopt.

## Activation

```
java -javaagent:jdbc-prof-agent.jar [app args]
```

Or attach to an already-running JVM via the Attach API — zero startup
change needed.

## How it works

### 1. ClassFileTransformer — catching every JDBC implementation

On agent startup, register a Byte Buddy (or ASM) transformer:

```java
new AgentBuilder.Default()
    .type(hasSuperType(named("java.sql.PreparedStatement")))
    .transform((builder, ...) -> builder
        .method(named("execute").or(named("executeQuery")).or(named("executeUpdate")))
        .intercept(MethodDelegation.to(ExecuteInterceptor.class)))
    .installOn(instrumentation);
```

`hasSuperType` makes this driver-agnostic: PostgreSQL, MySQL, H2, Oracle,
any driver — all caught at class-load time without naming any concrete
class.

The same transformer also intercepts `java.sql.Connection.prepareStatement`
implementations to capture the SQL string.

### 2. SQL correlation without shared state

The challenge: at `execute()` time you need the SQL string that was passed
to `prepareStatement(sql)`. A shared `WeakHashMap<PreparedStatement, String>`
works but introduces a lock.

Cleaner: **inject a synthetic field** into every instrumented
`PreparedStatement` class at bytecode-transform time:

```java
// Added to e.g. PgPreparedStatement by the transformer:
String __jdbcprofSql;
```

The `Connection.prepareStatement` interceptor writes to this field on the
returned instance. The `execute` interceptor reads it. No shared data
structure; no lock.

### 3. JFR for recording

Instead of a custom binary format and sink thread, emit JFR events:

```java
@Name("fi.vesas.jdbc.Execute")
@Label("JDBC Execute")
@Category("JDBC")
class ExecuteEvent extends Event {
    @Label("SQL") String sql;
    @Label("Rows affected") int rowCount;
    // stack trace captured natively by JFR — no Thread.getStackTrace()
}
```

JFR handles:
- Binary storage (`.jfr` file)
- Stack trace capture (native, essentially free vs. manual capture)
- Event streaming (Java 14+ `RecordingStream`)
- Tooling: JDK Mission Control, async-profiler, etc.

The analysis layer reads the `.jfr` file via the JFR Event Streaming API
instead of the custom `.jdbclog` format.

### 4. Call-site attribution

JFR captures the stack trace natively at each event commit. The analysis
layer walks each stack to find the application frame — same attribution
logic as the current design, applied offline.

## Comparison to current design

| | Wrapper (current) | Agent + JFR |
|---|---|---|
| App change required | DataSource rewrap | None (JVM flag only) |
| SQL capture | At API boundary, exact | Same |
| Stack capture | `Thread.getStackTrace()` (expensive) | JFR native (cheap) |
| Binary format | Custom `.jdbclog` | JFR `.jfr` |
| Analysis input | Custom reader | JFR Event Streaming API |
| Driver compatibility | Universal | Universal (`hasSuperType`) |
| Tooling | Custom CLI only | JMC, async-profiler, custom CLI |

## Key implementation risks

**Synthetic field injection across classloaders** — the injected field is
added to the driver's class. If the driver is loaded by a child classloader
(common in app servers), the transformer must be registered to cover that
loader too. Byte Buddy's `AgentBuilder` handles this, but it needs
testing across container deployments.

**`prepareStatement` → `execute` linkage** — the synthetic field approach
requires that the transformer sees the `Connection` implementation as well,
not just `PreparedStatement`. Need to also match `hasSuperType(java.sql.Connection)`.

**`Statement` (non-prepared)** — ad-hoc `Statement.execute(String sql)`
passes SQL directly; no correlation needed, simpler to intercept.

**CallableStatement** — extends `PreparedStatement`; caught by the same
transformer automatically.

## What this doesn't change

The analysis layer logic (N+1 detection, call-site attribution, HTML report)
is identical regardless of whether the event source is `.jdbclog` or `.jfr`.
Only the reader changes.
