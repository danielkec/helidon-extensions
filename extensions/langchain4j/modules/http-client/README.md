# Helidon HTTP client for LangChain4j

This opt-in module adapts a configured Helidon `WebClient` to LangChain4j's HTTP client SPI.
It targets the JSON and SSE traffic used by the MCP Streamable HTTP transport.
The WebClient's native services, TLS settings and timeouts apply to requests.

## Development status

This module requires the MCP SPI changes from LangChain4j PR #6726 and the aligned
`1.23.0-SNAPSHOT` BOM. It is enabled by the `http-client` Maven profile while those
changes are under review. The other Helidon LangChain4j modules still use 1.18.1;
combining them with this adapter requires upgrading and validating the integration first.

After installing the LangChain4j PR's MCP module, BOM and reactor prerequisites locally:

```shell
mvn -Phttp-client -pl extensions/langchain4j/modules/http-client -am test
```

## MCP context and header propagation

Configure header propagation on the WebClient, then supply the adapter and the MCP
context factory. The factory captures a caller's context before MCP defers work for
initialization or retries. The adapter carries that context into its own virtual thread.

```java
WebClient webClient = WebClient.builder()
        .addService(WebClientContextService.create(config -> config.addRecord(record -> record
                .header(HeaderNames.create("X-Identity"))
                .classifier("identity"))))
        .build();

HelidonHttpClient httpClient = HelidonHttpClient.builder()
        .webClient(webClient)
        .build();

StreamableHttpMcpTransport transport = StreamableHttpMcpTransport.builder()
        .url("http://localhost:8080/mcp")
        .httpClient(httpClient)
        .requestContextFactory(() -> {
            Context captured = Contexts.context().orElseGet(Context::create);
            return action -> () -> Contexts.runInContext(captured, action);
        })
        .build();
```

The types above come from `io.helidon.webclient.api`, `io.helidon.webclient.context`,
`io.helidon.http`, `io.helidon.common.context`,
`io.helidon.extensions.langchain4j.httpclient` and
`dev.langchain4j.mcp.client.transport.http`. Add `helidon-webclient-context` to use
the native header service. Register the desired value under the `identity` classifier
in the caller's Helidon Context; no MCP `customHeaders` supplier is needed.

The fresh empty context for an unbound caller is deliberate: it prevents that caller
from inheriting a connection or completion thread's headers. Initialize a shared MCP
client under an application or session context with the appropriate lifetime. MCP uses
that capture for initialization and connection traffic; individual requests and retries
retain their caller's capture. Servers that bind a session to one principal may require
separate MCP clients for different principals.

## Streaming and ownership

The publisher uses the supplied incremental SSE parser and reads bounded byte chunks.
Downstream demand controls event delivery. After draining a parsed batch, one further
chunk can be read to discover EOF or the next events; those events must be delivered
before another read. A partial event may span multiple chunks and is retained by the
parser. Each subscription creates a separate
HTTP exchange. Response callbacks run on a virtual thread with the captured Helidon context.

Cancellation terminates the exchange and releases its response resources. Close the
transport when finished, then close the adapter to cancel any remaining adapter work.
The supplied WebClient remains caller-owned and should be closed separately with
`webClient.closeResource()` when no longer shared.
This also applies to WebClients created through the generated builder's configuration,
consumer or supplier overloads; access that client through `httpClient.prototype().webClient()`.
Supplying an existing WebClient, as above, makes ownership explicit.

Multipart requests are outside this module's current MCP scope and are rejected explicitly.
Configure WebClient's connect/read timeouts before passing it to the adapter.

## Keycloak end-to-end test

The opt-in `keycloak-e2e` profile tests the complete path:

```text
Keycloak JWT -> secured Helidon application -> LangChain4j MCP client
            -> Helidon WebClient -> secured Helidon MCP server -> whoami tool
```

Both servers use Helidon OIDC authentication and independently verify Keycloak's JWT
signature, issuer and audience. Native `ContextFeature` and `WebClientContextService`
forward the bearer header. Native `TracingObserver` and `WebClientTracing` create and
propagate real OpenTelemetry spans. The application handler does not copy headers and
the MCP transport has no `customHeaders` supplier.

The test checks two users with overlapping calls through one MCP client, exact JWT
fingerprints at the MCP tool, distinct `traceparent` and `tracestate` values, and the
parent chain from the incoming trace through both servers and the outbound client.
Missing tokens and real tokens with corrupted signatures must be rejected at both
servers. Test output never prints access tokens.

Prerequisites: Java 27, Docker Compose, and the locally installed LangChain4j 1.23
snapshot artifacts from PR #6726 (including `langchain4j-bom` and `langchain4j-mcp`).
The test uses released Helidon and Helidon MCP 27.0.0, and Keycloak 26.8.0.

From the repository root:

```shell
docker compose --project-name lc4j-2910-e2e \
    -f extensions/langchain4j/modules/http-client/src/it/keycloak/compose.yaml up -d

docker compose --project-name lc4j-2910-e2e \
    -f extensions/langchain4j/modules/http-client/src/it/keycloak/compose.yaml port keycloak 8080
```

Use the printed loopback address below in place of `127.0.0.1:PORT`. Wait until realm
discovery responds before running Maven:

```shell
curl --fail http://127.0.0.1:PORT/realms/lc4j-mcp-e2e/.well-known/openid-configuration

mvn -Phttp-client,keycloak-e2e -pl extensions/langchain4j/modules/http-client -am \
    -Dkeycloak.url=http://127.0.0.1:PORT clean verify
```

Always remove the fixture container afterward, including after a failed test:

```shell
docker compose --project-name lc4j-2910-e2e \
    -f extensions/langchain4j/modules/http-client/src/it/keycloak/compose.yaml down --volumes
```

The realm contains only test credentials and is exposed on a loopback port. The integration
profile fails if `keycloak.url` is missing; the regular unit-test build does not require Docker.
Container startup/import follows the [Keycloak container guide](https://www.keycloak.org/server/containers).
