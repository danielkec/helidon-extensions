# Helidon LangChain4j Web UI

Browser-based development UI for Helidon-managed LangChain4j agents.

The module provides:

- automatic declarative Helidon webserver registration
- a manually registerable `HttpService`
- a static browser UI
- JSON endpoints for agent discovery and invocation
- browser-local chat and invocation history
- latest invocation state, trace, and event inspection
- optional app-wide event tracking for agent runs started outside the browser UI

## Add The Module

Add the module dependency to your application:

```xml
<dependency>
    <groupId>io.helidon.extensions.langchain4j</groupId>
    <artifactId>helidon-extensions-langchain4j-web-ui</artifactId>
</dependency>
```

If your project already imports the Helidon LangChain4j extension BOM, the version is managed there.

## Declarative Helidon

In declarative Helidon webserver setup, adding the dependency is enough.

The module contributes a `ServerFeatureProvider`, so the UI is auto-registered on the default socket without any
explicit `routing.register(...)` call.

Disable or customize it through server feature config:

```yaml
server:
  features:
    langchain4j-dev-ui:
      enabled: true
      web-context: /langchain4j/ui
```

## Programmatic Registration

If you are assembling routing manually, you can still register the `HttpService` yourself:

```java
import io.helidon.extensions.langchain4j.webui.LangChain4jDevUi;
import io.helidon.webserver.http.HttpRouting;

class Main {
    static void routing(HttpRouting.Builder routing) {
        routing.register(LangChain4jDevUi.create());
    }
}
```

If you need explicit control over the registry or config, use one of the other factory methods:

```java
LangChain4jDevUi.create(serviceRegistry);
LangChain4jDevUi.create(config);
LangChain4jDevUi.create(builder -> builder.webContext("/dev/agents"));
```

## Configuration

For declarative webserver usage, configure the feature under `server.features.langchain4j-dev-ui`:

```yaml
server:
  features:
    langchain4j-dev-ui:
      enabled: true
      web-context: /langchain4j/ui
```

Defaults:

- `enabled=true`
- `web-context=/langchain4j/ui`

When using `LangChain4jDevUi.create(config)` programmatically, pass the feature node itself rather than the whole
`server` config tree.

## Browser Usage

After registration, open:

```text
http://<host>:<port>/langchain4j/ui/
```

The UI supports:

- browsing discovered agents and methods
- invoking chat-like and structured methods
- providing extra agentic state values required by workflow routing, such as conditional-agent activation keys
- keeping conversation history in the browser for the selected method
- inspecting the latest raw result, state snapshot, invocation trace, and event log
- toggling app-wide event tracking so the inspector and progress strip can follow agent runs triggered elsewhere in the application
- automatically focusing the event log when app-wide tracking is enabled so external agent activity becomes visible immediately

For methods that declare `@MemoryId`, the browser generates and reuses a hidden conversation key automatically. There
is no manual session field or session management UI.

## Runtime Expectations

- Agents must be Helidon-managed LangChain4j agents with generated `AgentMetadata`.
- Latest-invocation state and trace inspection work when the invoked flow exposes an `AgenticScope`, either directly or
  through `AgenticScopeAccess`.
- By default event logs are capture-on-invoke only; the UI does not maintain a server-side session store.
- App-wide tracking is opt-in from the browser toggle and keeps only a rolling in-memory buffer of recent events plus the latest observed scope snapshot.
- Tracking covers both declarative agents and generated `@Ai.Service` interfaces, including AI-service request/response and guardrail events.
- Agent services are resolved when the UI service starts, not lazily on first request.

## JSON API

The browser UI is backed by these endpoints under the configured base path:

- `GET /api/agents`
- `POST /api/invoke`
- `POST /api/invocations`
- `GET /api/invocations/{id}`
- `GET /api/tracking`
- `POST /api/tracking`

`POST /api/invoke` accepts:

- `arguments`: direct method inputs keyed by parameter name
- `memoryId`: optional conversation key for methods that declare `@MemoryId`
- `state`: optional extra agentic scope values for hidden workflow inputs such as activation-condition variables

The response includes:

- `result`: normalized method result
- `inspection`: latest invocation snapshot with state, trace, and event data when available

## Local Verification

Run the module test suite with:

```bash
mvn -pl modules/web-ui -am test
```
