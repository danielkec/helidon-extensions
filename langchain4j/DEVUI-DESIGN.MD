# Helidon LangChain4j Dev UI Design

## Goal

Add a browser-based development UI for Helidon LangChain4j agents that supports:

- agent discovery
- interactive invocation and chat-style testing
- browser-local conversation history
- latest invocation state, trace, and event inspection

The feature should feel similar in spirit to the ADK web UI, but fit Helidon's extension and webserver model.

## Repository And Module Placement

Implementation target:

- repo: `helidon-extensions/langchain4j`
- new module: `modules/web-ui`

Why:

- the feature belongs to the Helidon LangChain4j integration, not Helidon core
- the repo already owns declarative agent discovery and builder wiring
- Helidon extensions already have a proven static UI delivery pattern in `openapi-ui`

## Architectural Shape

The feature is split into two parts:

1. Core runtime hook in `modules/langchain4j`
2. New browser UI module in `modules/web-ui`

### Core Runtime Hook

Introduce a small extension point in the core module so auxiliary modules can customize every generated declarative agent builder without reflection hacks.

Working name:

- `AgentBuilderCustomizer`

Responsibilities:

- receive the declarative agent creation context
- receive the `ServiceRegistry`
- mutate the builder before agent construction completes

Initial use:

- the web UI module installs a listener for invocation and tool execution traces

This is safer than auto-attaching every discovered `AgentListener`, which can duplicate nested events.

### Web UI Module

Add a new Helidon module that provides:

- declarative `ServerFeature` auto-registering the UI when the dependency is present
- `HttpService` serving the UI and JSON API for programmatic registration
- static frontend assets from classpath resources
- agent registry and reflective invoker
- latest-invocation inspection snapshot
- listener-backed event recorder scoped to UI-triggered invocations
- recorder-backed scope snapshot fallback for ephemeral workflows that do not leave a retrievable live scope behind after invocation
- recorder-derived enrichment for incomplete scope snapshots so the inspector can still show conventional output variables and invocation trace entries when the underlying scope lifetime drops those details

Working artifact name:

- `helidon-extensions-langchain4j-web-ui`

Working package:

- `io.helidon.extensions.langchain4j.webui`

Registration model for v1:

- declarative Helidon registration is automatic through a `ServerFeatureProvider`
- adding the `web-ui` dependency is enough to expose the UI on the default server socket
- the feature reuses the same typed config and builder helpers as the underlying `HttpService`
- rely on the same Helidon global `Services` registry assumptions already used by the LangChain4j integration

Manual `LangChain4jDevUi.create()` registration remains available for explicitly programmatic server assembly, but it is not the primary declarative experience.

## Backend Responsibilities

### 1. Agent Discovery

Discover agents using existing generated `AgentMetadata` services from the `ServiceRegistry`.

Implementation note for v1:

- resolve both agent metadata and the corresponding agent service instances when the UI service starts
- keep those agent instances stable for the lifetime of the UI service instead of lazily constructing them on first HTTP invocation

For each agent expose:

- logical agent name
- Java interface name
- invocable methods
- parameter metadata
- whether a method declares `@MemoryId`

### 2. Invocation

Invoke agent methods reflectively on the pre-resolved agent service instance associated with the discovered agent.

Input handling:

- path and request identify agent + method
- request body supplies arguments as JSON object
- `@MemoryId` parameters remain supported, but the browser handles them as hidden conversation keys instead of a user-managed session field
- additional agentic scope variables can be supplied for workflows whose hidden inputs are not part of the root Java method signature

Practical requirement for browser invocation:

- conditional/router agents such as the HOL `flavor-router` must expose activation-condition variables like `flavor` in the UI
- those extra values must be written into the agentic scope before invocation, with type conversion matching the target Java parameter type
- if an agentic workflow returns `null` but writes a conventional `response` value into scope, the UI should surface that response as the effective result

Invocation output should include:

- returned value
- latest inspection data for that invocation:
  - state snapshot when available
  - normalized invocation trace
  - recorded listener events

Live browser-progress requirement:

- the UI must be able to surface recorded events while an invocation is still running, not only after the final response arrives
- backend support uses an async invocation job with incremental inspection snapshots that the browser can poll during execution

Inspection fallback behavior for ephemeral or partially retained scopes:

- if a workflow returns `null`, recover the effective result from the recorded agent output key or conventional `response` state slot when possible
- if scope state is present but missing the final output variable, enrich the inspection state with the returned result under the recorded output key
- if scope invocation history is empty, derive invocation trace entries from recorded before/after agent events so browser debugging still shows the executed agent chain

### 3. Debugging Integration

Use LangChain4j agentic observability APIs:

- `AgentListener`
- `AgenticScope`
- `AgenticScopeAccess`
- `ResultWithAgenticScope`

Recorder captures:

- before/after agent invocation
- invocation errors
- before/after tool execution
- scope creation/destruction

The recorder is invocation-scoped rather than session-scoped:

- only UI-triggered invocations are captured
- captured events are returned with the response for the latest invocation
- no server-side session list, eviction API, or editable session store is maintained

Declarative runtime requirement:

- the module contributes a service-loader visible `ServerFeatureProvider`
- feature config lives under `server.features.langchain4j-dev-ui`
- the feature is enabled by default so dependency-only adoption works in declarative Helidon

## HTTP Surface

Working base path:

- `/langchain4j/ui`

Endpoints:

- `GET /langchain4j/ui`
- `GET /langchain4j/ui/`
- `GET /langchain4j/ui/index.html`
- `GET /langchain4j/ui/api/agents`
- `POST /langchain4j/ui/api/invoke`
- `POST /langchain4j/ui/api/invocations`
- `GET /langchain4j/ui/api/invocations/{id}`

`POST /api/invoke` accepts:

- `arguments`: direct method inputs keyed by parameter name
- `memoryId`: optional hidden conversation key for methods that declare `@MemoryId`
- `state`: optional extra agentic scope values for hidden workflow inputs such as activation-condition variables

## Frontend Scope

No npm toolchain. Use static:

- `index.html`
- `app.css`
- `app.js`

Layout direction:

- left rail: agents
- center: transcript-first invoke/chat workspace
- right rail: tabs for latest state, invocations, and events

Visual language for v1:

- mirror the current `helidon.io` palette
- use a dark navy and slate shell with glassy panels, bright text, pale blue highlights, and pink action accents
- keep the UI recognizably Helidon rather than generic admin-dashboard styling
- long Java identifiers such as fully qualified agent interface names must wrap inside cards, chips, and trace titles instead of overflowing their container

UI capabilities:

- switch agents and methods
- run chat-like prompts quickly
- provide extra workflow state inputs required by agentic routing or activation conditions
- show browser conversation history as chat bubbles, with a composer anchored below the transcript
- support consistent keyboard invocation shortcuts across agents and forms, with explicit multiline-editor shortcuts where needed
- show an in-flight progress bubble and a visually active run/send button while the UI is waiting for an agent response
- refresh the inspector dynamically while a workflow is running so events and trace details appear progressively in the browser
- show a live agentic-run progress strip in the conversation header, deriving agent and tool marbles from the event stream with dotted connectors, small name labels, and pulsing in-flight steps until matching completion events arrive
- render object-shaped input and output payloads inside chat bubbles as named variable bullets, with string values interpreted as markdown
- handle indented markdown blocks inside bubble values, including fenced code blocks emitted under list items and markdown tables
- syntax-highlight fenced markdown code blocks in the browser, with built-in support for Java, JSON, YAML, XML, and bash without adding a frontend toolchain
- preserve original code line wrapping inside fenced blocks and use horizontal scrolling instead of forced line breaks for wide snippets
- render enum-typed arguments and agentic state variables as browser select controls populated from the discovered Java enum constants
- present agentic context variables in the same compact `key: value` bullet style as events, showing short single-line values inline and collapsing longer or structured values behind expandable sections without a separate value label
- render string values in the state inspector as markdown only when valid markdown syntax is detected, otherwise fall back to a plain text block
- render event-log payloads as compact `key: value` bullets, with tight line spacing for scanability, showing short single-line values inline and collapsing longer or structured values behind expandable sections
- render invocation trace payloads in the same compact bullet style as events, keeping agent identity in the card header and showing invocation input/output as `key: value` details
- keep non-chat methods usable by rendering their request and response pairs inside the same transcript surface
- inspect the latest raw JSON result
- inspect the latest state snapshot, invocation trace, and event log
- distinguish plain non-agentic invocations from workflows with captured scope state so the inspector does not imply that every agent produces agentic context
- clear the browser-local conversation history for the selected method

Intentional v1 exclusion:

- no session browsing UI
- no session id chip or manual memory id form field
- no session state editing or eviction API

## Testing Strategy

Add tests in the new module covering:

- UI page is served
- agents endpoint lists generated agents
- invoke endpoint calls an agent successfully
- conditional-agent state inputs are surfaced and applied
- latest invocation inspection contains trace and events
- async invocation polling exposes live progress before workflow completion
- legacy session routes are not exposed

Use:

- annotated test agents in test sources
- real LangChain4j agentic method annotations on test agents
- test-local named Helidon services for agent dependencies such as chat models and chat memory providers
- declarative `@ServerTest` boot to verify the auto-registered `ServerFeatureProvider` path rather than manually registering routes
- Helidon WebServer test support

## Current Direction

Implement a useful v1 with:

- dependency-only declarative registration
- transcript-first browser workflow
- hidden conversation ids for memory-backed chat methods
- explicit workflow-state inputs for router and conditional agents
- per-invocation state/trace/event inspection without any user-managed session model
