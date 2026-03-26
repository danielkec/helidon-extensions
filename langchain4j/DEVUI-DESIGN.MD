# Helidon LangChain4j Web UI Design

## Goal

Add a browser-based web UI for Helidon LangChain4j agents that supports:

- agent discovery
- interactive invocation and chat-style testing
- browser-local conversation history
- latest invocation state, trace, and event inspection
- optional app-wide live event tracking for agent runs started outside the browser UI

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
- `AgentServiceCustomizer`

Responsibilities:

- receive the declarative agent creation context
- receive the `ServiceRegistry`
- mutate the builder before agent construction completes
- receive the created top-level declarative agent service
- allow wrapping the returned root proxy before Helidon exposes it from the generated supplier

Initial use:

- the web UI module installs a listener for invocation and tool execution traces
- the web UI module wraps top-level declarative agent proxies so browser tracking still receives root agent events for workflows and plain `@Ai.Agent` services whose upstream runtime path does not emit listener callbacks consistently at the root boundary

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
- the declarative `ServerFeature` must bind the UI to the actual application `ServiceRegistry` from `WebServerConfig`, not to a static global registry lookup, so browser tracking toggles and agent listeners always share the same recorder instance

Manual `LangChain4jDevUi.create()` registration remains available for explicitly programmatic server assembly, but it is not the primary declarative experience.

## Backend Responsibilities

### 1. Agent Discovery

Discover agents using existing generated `AgentMetadata` services from the `ServiceRegistry`.

Implementation note for v1:

- resolve both agent metadata and the corresponding agent service instances when the UI service starts
- keep those agent instances stable for the lifetime of the UI service instead of lazily constructing them on first HTTP invocation
- support `@Ai.Service` without any codegen or core-runtime changes by scanning supplier-backed service descriptors in the `ServiceRegistry` and resolving provided contract types annotated with `@Ai.Service`
- merge effective runtime `langchain4j.agents.<name>` config into displayed agent metadata so graph nodes also expose config-defined tools, MCP clients, and input/output guardrails that are not present as Java annotations

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
- synthetic conditional-routing events derived from `@ActivationCondition` metadata, available scope state, and invocation trace so routers expose branch checks and selected sub-agents even though upstream listener callbacks do not fire for activation-condition evaluation

The recorder is invocation-scoped rather than session-scoped:

- only UI-triggered invocations are captured
- captured events are returned with the response for the latest invocation
- no server-side session list, eviction API, or editable session store is maintained

Optional browser monitoring mode:

- the UI can toggle app-wide tracking on and off
- when enabled, the recorder also keeps a rolling global buffer of recent agent and tool events plus the latest scope snapshot from any invocation observed in the process
- monitoring must cover both declarative agentic flows and `@Ai.Service` invocations, so generated AI service suppliers auto-register all discovered LangChain4j AI-service observability listeners from the Helidon registry
- monitoring must also cover top-level declarative `@Ai.Agent` entry points that do not surface root listener callbacks from upstream builder wiring, so Helidon wraps the generated root proxy and synthesizes matching before/after/error root events from the invoked method, resolved agent metadata, and any recoverable scope
- this global buffer is separate from the normal per-invocation capture path, so browser-started runs still keep isolated inspection data
- turning tracking off clears the rolling global buffer so the next monitoring session starts cleanly

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
- `GET /langchain4j/ui/api/tracking`
- `POST /langchain4j/ui/api/tracking`

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

- top: full-width clickable graph of discovered agents, workflow edges, and tool relationships
- bottom left: transcript-first invoke/chat workspace
- bottom right: tabs for latest state, invocations, and events

Visual language for v1:

- mirror the current `helidon.io` palette
- use a dark navy and slate shell with glassy panels, bright text, pale blue highlights, and pink action accents
- keep the UI recognizably Helidon rather than generic admin-dashboard styling
- keep the top graph panel content-first without redundant local title-bar chrome
- keep the transcript workspace compact by dropping redundant local run-title chrome and leaving the transcript blank until the first request instead of showing an onboarding bubble
- discover both `@Ai.Agent` and `@Ai.Service` entry points directly from the runtime registry, and surface guardrails as first-class graph nodes alongside tools
- let graph nodes widen to keep agent or tool names on one line when practical, allowing roughly 35 visible name characters before wrapping so very long identifiers still wrap instead of stretching the graph excessively
- keep graph entry-point icons simple: agents and workflow agents use a plain robot glyph, while `@Ai.Service` nodes use a sparkles glyph to stay visually distinct without costume-style emoji
- show a compact spinner activity marker inside any live graph bubble whose `before-agent` or router-start event has arrived without its matching completion event yet, rendering the spinner as an explicit inline header element so agent and service nodes behave consistently
- long Java identifiers such as fully qualified agent interface names must wrap inside cards, chips, and trace titles instead of overflowing their container

UI capabilities:

- switch agents and methods from a graph-based navigator rather than a flat list
- visualize declared agent-to-agent workflow links and declared agent or service dependencies in the left rail, including conditional routes, sequence steps, explicit tool classes, input/output guardrails, and named tool-provider or MCP-backed tool sources when concrete tool names are not available at discovery time
- treat `@Ai.Service` interfaces as first-class browser nodes that can be invoked, inspected, graphed, and guarded the same way as declarative agents even though they are discovered from runtime service contracts rather than `AgentMetadata`
- run chat-like prompts quickly
- provide extra workflow state inputs required by agentic routing or activation conditions
- show browser conversation history as chat bubbles, with a composer anchored below the transcript
- support consistent keyboard invocation shortcuts across agents and forms, with explicit multiline-editor shortcuts where needed
- show an in-flight progress bubble and a visually active run/send button while the UI is waiting for an agent response
- refresh the inspector dynamically while a workflow is running so events and trace details appear progressively in the browser
- render event and trace timelines in reverse chronological order so the newest activity stays visible first
- expose a topbar toggle for app-wide event tracking and, when enabled, drive the inspector and progress strip from the rolling global event stream instead of only the latest browser-started invocation
- when app-wide tracking is enabled, move the inspector focus to the event log automatically and reflect tracking state in the summary/raw-result area so external activity is immediately visible instead of looking like an idle browser conversation
- app-wide tracking mode hides the conversation workspace pane entirely and lets the inspector expand into that area; disabling tracking restores the previous browser conversation workspace without clearing its local state
- keep state, trace, and event inspector panes interaction-stable during live tracking by avoiding redundant DOM rerenders when content has not changed and by preserving expanded detail sections across incremental updates
- show a live agentic-run progress strip in the conversation header, deriving agent, conditional-router, and tool marbles from the event stream with centered directional connector arrows, small name labels, and pulsing in-flight steps until matching completion events arrive, wrapping long workflows into alternating 3-step snake rows instead of a single overflowing line
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
- conditional routers expose synthetic routing events for branch checks and selected sub-agents, including nested conditional agents discovered from invocation trace inside sequence workflows
- latest invocation inspection contains trace and events
- async invocation polling exposes live progress before workflow completion
- app-wide tracking mode captures and serves events for agent runs started outside the web UI
- app-wide tracking mode also captures `@Ai.Service` request/response, guardrail, and completion events for service-driven runs started outside the web UI
- legacy session routes are not exposed

Use:

- annotated test agents in test sources
- real LangChain4j agentic method annotations on test agents
- test-local named Helidon services for agent dependencies such as chat models and chat memory providers
- declarative `@ServerTest` boot to verify the auto-registered `ServerFeatureProvider` path rather than manually registering routes
- declarative tracking tests must assert that the auto-registered UI reads from the same application `ServiceRegistry` instance used to build agents and AI services
- Helidon WebServer test support

## Current Direction

Implement a useful v1 with:

- dependency-only declarative registration
- transcript-first browser workflow
- hidden conversation ids for memory-backed chat methods
- explicit workflow-state inputs for router and conditional agents
- per-invocation state/trace/event inspection without any user-managed session model
- opt-in app-wide monitoring for externally triggered agent runs
