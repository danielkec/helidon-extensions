/*
 * Copyright (c) 2026 Oracle and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.helidon.extensions.langchain4j.webui;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.helidon.http.Status;
import io.helidon.webclient.api.ClientResponseTyped;
import io.helidon.webclient.api.WebClient;
import io.helidon.webserver.testing.junit5.ServerTest;

import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

@ServerTest
class LangChain4jDevUiTest {
    private static final Jsonb JSONB = JsonbBuilder.create();

    private final WebClient client;

    LangChain4jDevUiTest(WebClient client) {
        this.client = client;
    }

    @Test
    void servesUiAssets() {
        ClientResponseTyped<String> response = client.get("/langchain4j/ui/")
                .request(String.class);

        assertThat(response.status(), is(Status.OK_200));
        assertThat(response.entity(), containsString("Helidon LangChain4j Dev UI"));
        assertThat(response.entity(), containsString("app.js"));
        assertThat(response.entity(), containsString("chat-history"));
    }

    @Test
    void listsDiscoveredAgents() {
        ClientResponseTyped<String> response = client.get("/langchain4j/ui/api/agents")
                .request(String.class);

        assertThat(response.status(), is(Status.OK_200));
        List<?> agents = JSONB.fromJson(response.entity(), List.class);
        assertThat(agents.isEmpty(), is(false));

        @SuppressWarnings("unchecked")
        Map<String, Object> browserAgent = (Map<String, Object>) agents.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .filter(agent -> "browser-agent".equals(agent.get("name")))
                .findFirst()
                .orElseThrow();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> methods = (List<Map<String, Object>>) browserAgent.get("methods");
        assertThat(methods.stream().map(method -> String.valueOf(method.get("id"))).toList(),
                   hasItem("chat(String,String)"));
    }

    @Test
    void exposesAgentGraphMetadata() {
        ClientResponseTyped<String> response = client.get("/langchain4j/ui/api/agents")
                .request(String.class);

        assertThat(response.status(), is(Status.OK_200));
        List<?> agents = JSONB.fromJson(response.entity(), List.class);

        @SuppressWarnings("unchecked")
        Map<String, Object> workflowAgent = (Map<String, Object>) agents.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .filter(agent -> "browser-routed-workflow".equals(agent.get("name")))
                .findFirst()
                .orElseThrow();
        assertThat(workflowAgent.get("kind"), equalTo("workflow"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> workflowRelations = (List<Map<String, Object>>) workflowAgent.get("relations");
        @SuppressWarnings("unchecked")
        Map<String, Object> workflowRelation = workflowRelations.stream()
                .filter(relation -> "sequence".equals(relation.get("kind")))
                .findFirst()
                .orElseThrow();
        assertThat(workflowRelation.get("targetAgent"), equalTo("browser-flavor-router"));

        @SuppressWarnings("unchecked")
        Map<String, Object> routerAgent = (Map<String, Object>) agents.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .filter(agent -> "browser-flavor-router".equals(agent.get("name")))
                .findFirst()
                .orElseThrow();
        assertThat(routerAgent.get("kind"), equalTo("router"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> routerRelations = (List<Map<String, Object>>) routerAgent.get("relations");
        assertThat(routerRelations.stream().map(relation -> String.valueOf(relation.get("targetAgent"))).toList(),
                   hasItem("browser-se-expert"));
        assertThat(routerRelations.stream().map(relation -> String.valueOf(relation.get("targetAgent"))).toList(),
                   hasItem("browser-mp-expert"));

        @SuppressWarnings("unchecked")
        Map<String, Object> toolAgent = (Map<String, Object>) agents.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .filter(agent -> "browser-tool-agent".equals(agent.get("name")))
                .findFirst()
                .orElseThrow();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tools = (List<Map<String, Object>>) toolAgent.get("tools");
        @SuppressWarnings("unchecked")
        Map<String, Object> lookupTool = tools.stream()
                .filter(tool -> "lookupBrowserDocs".equals(tool.get("name")))
                .findFirst()
                .orElseThrow();
        assertThat(lookupTool.get("kind"), equalTo("tool"));
        assertThat(lookupTool.get("owner"), equalTo("BrowserLookupTool"));
        assertThat(lookupTool.get("description"), equalTo("Looks up browser-side Helidon references"));
    }

    @Test
    void exposesConditionalStateParameters() {
        ClientResponseTyped<String> response = client.get("/langchain4j/ui/api/agents")
                .request(String.class);

        assertThat(response.status(), is(Status.OK_200));
        List<?> agents = JSONB.fromJson(response.entity(), List.class);

        @SuppressWarnings("unchecked")
        Map<String, Object> routerAgent = (Map<String, Object>) agents.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .filter(agent -> "browser-flavor-router".equals(agent.get("name")))
                .findFirst()
                .orElseThrow();

        @SuppressWarnings("unchecked")
        Map<String, Object> routerMethod = ((List<Map<String, Object>>) routerAgent.get("methods")).stream()
                .filter(method -> "askExpert(String)".equals(method.get("id")))
                .findFirst()
                .orElseThrow();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> stateParameters = (List<Map<String, Object>>) routerMethod.get("stateParameters");
        assertThat(stateParameters.stream().map(parameter -> String.valueOf(parameter.get("name"))).toList(),
                   hasItem("flavor"));
        @SuppressWarnings("unchecked")
        Map<String, Object> flavorParameter = stateParameters.stream()
                .filter(parameter -> "flavor".equals(parameter.get("name")))
                .findFirst()
                .orElseThrow();
        assertThat((List<?>) flavorParameter.get("enumValues"), equalTo(List.of("SE", "MP")));
    }

    @Test
    void exposesEnumArgumentOptions() {
        ClientResponseTyped<String> response = client.get("/langchain4j/ui/api/agents")
                .request(String.class);

        assertThat(response.status(), is(Status.OK_200));
        List<?> agents = JSONB.fromJson(response.entity(), List.class);

        @SuppressWarnings("unchecked")
        Map<String, Object> enumAgent = (Map<String, Object>) agents.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .filter(agent -> "browser-enum-agent".equals(agent.get("name")))
                .findFirst()
                .orElseThrow();

        @SuppressWarnings("unchecked")
        Map<String, Object> enumMethod = ((List<Map<String, Object>>) enumAgent.get("methods")).stream()
                .filter(method -> "selectFlavor(BrowserFlavor)".equals(method.get("id")))
                .findFirst()
                .orElseThrow();

        assertThat(enumMethod.get("chatLike"), equalTo(false));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> parameters = (List<Map<String, Object>>) enumMethod.get("parameters");
        @SuppressWarnings("unchecked")
        Map<String, Object> flavorParameter = parameters.get(0);
        assertThat(flavorParameter.get("name"), equalTo("flavor"));
        assertThat((List<?>) flavorParameter.get("enumValues"), equalTo(List.of("SE", "MP")));
    }

    @Test
    void invokesConditionalAgentWithInjectedState() {
        LinkedHashMap<String, Object> invokeRequest = new LinkedHashMap<>();
        invokeRequest.put("agent", "browser-flavor-router");
        invokeRequest.put("method", "askExpert(String)");
        invokeRequest.put("arguments", Map.of("question", "How do I configure metrics?"));
        invokeRequest.put("state", Map.of("flavor", "SE"));

        ClientResponseTyped<String> invokeResponse = client.post("/langchain4j/ui/api/invoke")
                .submit(JSONB.toJson(invokeRequest), String.class);

        assertThat(invokeResponse.status(), is(Status.OK_200));
        @SuppressWarnings("unchecked")
        Map<String, Object> invoked = JSONB.fromJson(invokeResponse.entity(), LinkedHashMap.class);
        assertThat(invoked.get("result"), equalTo("Echo: SE expert: How do I configure metrics?"));

        @SuppressWarnings("unchecked")
        Map<String, Object> inspection = (Map<String, Object>) invoked.get("inspection");
        assertThat(inspection, notNullValue());
        assertThat(inspection.get("scopeAvailable"), equalTo(true));
        @SuppressWarnings("unchecked")
        Map<String, Object> state = (Map<String, Object>) inspection.get("state");
        assertThat(state.get("flavor"), equalTo("SE"));
        assertThat(inspection.get("invocations"), instanceOf(List.class));
        assertThat(inspection.get("events"), instanceOf(List.class));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> events = (List<Map<String, Object>>) inspection.get("events");
        @SuppressWarnings("unchecked")
        Map<String, Object> seCheck = events.stream()
                .filter(event -> "conditional-check".equals(event.get("type")))
                .filter(event -> "browser-se-expert".equals(event.get("subAgent")))
                .findFirst()
                .orElseThrow();
        assertThat(seCheck.get("agent"), equalTo("browser-flavor-router"));
        assertThat(seCheck.get("method"), equalTo("askExpert"));
        assertThat(seCheck.get("matched"), equalTo(true));
        assertThat(seCheck.get("selected"), equalTo(true));

        @SuppressWarnings("unchecked")
        Map<String, Object> mpCheck = events.stream()
                .filter(event -> "conditional-check".equals(event.get("type")))
                .filter(event -> "browser-mp-expert".equals(event.get("subAgent")))
                .findFirst()
                .orElseThrow();
        assertThat(mpCheck.get("matched"), equalTo(false));
        assertThat(mpCheck.get("selected"), equalTo(false));

        @SuppressWarnings("unchecked")
        Map<String, Object> route = events.stream()
                .filter(event -> "conditional-route".equals(event.get("type")))
                .findFirst()
                .orElseThrow();
        assertThat(route.get("agent"), equalTo("browser-flavor-router"));
        assertThat(route.get("method"), equalTo("askExpert"));
        assertThat(route.get("status"), equalTo("selected"));
        assertThat((List<?>) route.get("selectedAgents"), equalTo(List.of("browser-se-expert")));
    }

    @Test
    void exposesNestedConditionalRouterEventsFromInvocationTrace() {
        LinkedHashMap<String, Object> invokeRequest = new LinkedHashMap<>();
        invokeRequest.put("agent", "browser-routed-workflow");
        invokeRequest.put("method", "ask(String)");
        invokeRequest.put("arguments", Map.of("question", "Need SE guidance"));
        invokeRequest.put("state", Map.of("flavor", "SE"));

        ClientResponseTyped<String> invokeResponse = client.post("/langchain4j/ui/api/invoke")
                .submit(JSONB.toJson(invokeRequest), String.class);

        if (invokeResponse.status() != Status.OK_200) {
            throw new AssertionError("nested-router-response=" + invokeResponse.entity());
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> invoked = JSONB.fromJson(invokeResponse.entity(), LinkedHashMap.class);
        assertThat(invoked.get("result"), equalTo("Echo: SE expert: Need SE guidance"));

        @SuppressWarnings("unchecked")
        Map<String, Object> inspection = (Map<String, Object>) invoked.get("inspection");
        assertThat(inspection, notNullValue());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> invocations = (List<Map<String, Object>>) inspection.get("invocations");
        assertThat(invocations.stream()
                           .map(invocation -> invocation.get("agentType"))
                           .toList(),
                   hasItem(BrowserFlavorRouterAgent.class.getName()));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> events = (List<Map<String, Object>>) inspection.get("events");
        int routeIndex = indexOfEvent(events, event -> "conditional-route".equals(event.get("type"))
                && "browser-flavor-router".equals(event.get("agent")));
        int expertIndex = indexOfEvent(events, event -> "before-agent".equals(event.get("type"))
                && "browser-se-expert".equals(event.get("agent")));
        assertThat(routeIndex, greaterThan(-1));
        assertThat(expertIndex, greaterThan(-1));
        assertThat(routeIndex < expertIndex, is(true));
    }

    @Test
    void capturesInspectionForStatelessWorkflowInvocation() {
        LinkedHashMap<String, Object> invokeRequest = new LinkedHashMap<>();
        invokeRequest.put("agent", "browser-stateless-workflow");
        invokeRequest.put("method", "ask(String)");
        invokeRequest.put("arguments", Map.of("question", "Explain agentic state"));

        ClientResponseTyped<String> invokeResponse = client.post("/langchain4j/ui/api/invoke")
                .submit(JSONB.toJson(invokeRequest), String.class);

        assertThat(invokeResponse.status(), is(Status.OK_200));
        @SuppressWarnings("unchecked")
        Map<String, Object> invoked = JSONB.fromJson(invokeResponse.entity(), LinkedHashMap.class);
        assertThat(invoked.get("result"), equalTo("Echo: Stateless workflow: Explain agentic state"));

        @SuppressWarnings("unchecked")
        Map<String, Object> inspection = (Map<String, Object>) invoked.get("inspection");
        assertThat(inspection, notNullValue());
        assertThat(inspection.get("scopeAvailable"), equalTo(true));
        @SuppressWarnings("unchecked")
        Map<String, Object> state = (Map<String, Object>) inspection.get("state");
        assertThat(state.size(), greaterThan(0));
        assertThat(state.get("response"), equalTo("Echo: Stateless workflow: Explain agentic state"));
        assertThat(((List<?>) inspection.get("invocations")).size(), greaterThan(0));
    }

    @Test
    void reportsInvocationProgressWhileWorkflowIsRunning() throws Exception {
        LinkedHashMap<String, Object> invokeRequest = new LinkedHashMap<>();
        invokeRequest.put("agent", "browser-slow-workflow");
        invokeRequest.put("method", "ask(String)");
        invokeRequest.put("arguments", Map.of("question", "Show live progress"));

        ClientResponseTyped<String> startResponse = client.post("/langchain4j/ui/api/invocations")
                .submit(JSONB.toJson(invokeRequest), String.class);

        assertThat(startResponse.status(), is(Status.ACCEPTED_202));
        @SuppressWarnings("unchecked")
        Map<String, Object> started = JSONB.fromJson(startResponse.entity(), LinkedHashMap.class);
        String invocationId = String.valueOf(started.get("invocationId"));
        assertThat(String.valueOf(started.get("status")), equalTo("running"));

        @SuppressWarnings("unchecked")
        Map<String, Object> running = waitForInvocation(invocationId, snapshot -> {
            if (!"running".equals(snapshot.get("status"))) {
                return false;
            }
            Object inspection = snapshot.get("inspection");
            if (!(inspection instanceof Map<?, ?> inspectionMap)) {
                return false;
            }
            Object events = inspectionMap.get("events");
            return events instanceof List<?> eventList && !eventList.isEmpty();
        });

        assertThat(running.get("status"), equalTo("running"));
        @SuppressWarnings("unchecked")
        Map<String, Object> runningInspection = (Map<String, Object>) running.get("inspection");
        assertThat(runningInspection, notNullValue());
        assertThat(((List<?>) runningInspection.get("events")).size(), greaterThan(0));

        @SuppressWarnings("unchecked")
        Map<String, Object> completed = waitForInvocation(invocationId,
                                                          snapshot -> "completed".equals(snapshot.get("status")));
        assertThat(completed.get("status"), equalTo("completed"));
        assertThat(completed.get("result"), equalTo("Echo: Slow workflow: Show live progress"));
    }

    @Test
    void invokesAgentWithEnumArgument() {
        LinkedHashMap<String, Object> invokeRequest = new LinkedHashMap<>();
        invokeRequest.put("agent", "browser-enum-agent");
        invokeRequest.put("method", "selectFlavor(BrowserFlavor)");
        invokeRequest.put("arguments", Map.of("flavor", "MP"));

        ClientResponseTyped<String> invokeResponse = client.post("/langchain4j/ui/api/invoke")
                .submit(JSONB.toJson(invokeRequest), String.class);

        assertThat(invokeResponse.status(), is(Status.OK_200));
        @SuppressWarnings("unchecked")
        Map<String, Object> invoked = JSONB.fromJson(invokeResponse.entity(), LinkedHashMap.class);
        assertThat(invoked.get("result"), equalTo("Echo: Flavor selection: MP"));
    }

    @Test
    void capturesInspectionForStatefulInvocation() {
        String memoryId = "browser-chat-1";

        LinkedHashMap<String, Object> invokeRequest = new LinkedHashMap<>();
        invokeRequest.put("agent", "browser-agent");
        invokeRequest.put("method", "chat(String,String)");
        invokeRequest.put("memoryId", memoryId);
        invokeRequest.put("arguments", Map.of("message", "hello from the browser"));

        ClientResponseTyped<String> invokeResponse = client.post("/langchain4j/ui/api/invoke")
                .submit(JSONB.toJson(invokeRequest), String.class);

        assertThat(invokeResponse.status(), is(Status.OK_200));
        @SuppressWarnings("unchecked")
        Map<String, Object> invoked = JSONB.fromJson(invokeResponse.entity(), LinkedHashMap.class);
        assertThat(invoked.get("result"), equalTo("Echo: Remember this: hello from the browser"));
        assertThat(invoked.get("session"), equalTo(null));

        @SuppressWarnings("unchecked")
        Map<String, Object> inspection = (Map<String, Object>) invoked.get("inspection");
        assertThat(inspection, notNullValue());
        assertThat(inspection.get("scopeAvailable"), equalTo(true));
        @SuppressWarnings("unchecked")
        Map<String, Object> inspectionState = (Map<String, Object>) inspection.get("state");
        assertThat(inspectionState, instanceOf(Map.class));
        assertThat(inspection.get("invocations"), instanceOf(List.class));
        assertThat(((List<?>) inspection.get("events")).size(), greaterThan(0));
    }

    @Test
    void doesNotExposeSessionRoutes() {
        ClientResponseTyped<String> missingSessionList = client.get("/langchain4j/ui/api/agents/browser-agent/sessions")
                .request(String.class);

        assertThat(missingSessionList.status(), is(Status.NOT_FOUND_404));
    }

    private Map<String, Object> waitForInvocation(String invocationId,
                                                  java.util.function.Predicate<Map<String, Object>> condition)
            throws Exception {
        Map<String, Object> latest = null;
        for (int attempt = 0; attempt < 40; attempt++) {
            ClientResponseTyped<String> pollResponse = client.get("/langchain4j/ui/api/invocations/" + invocationId)
                    .request(String.class);

            assertThat(pollResponse.status(), is(Status.OK_200));
            @SuppressWarnings("unchecked")
            Map<String, Object> snapshot = JSONB.fromJson(pollResponse.entity(), LinkedHashMap.class);
            latest = snapshot;
            if (condition.test(snapshot)) {
                return snapshot;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("Invocation did not reach expected state: " + latest);
    }

    private static int indexOfEvent(List<Map<String, Object>> events,
                                    java.util.function.Predicate<Map<String, Object>> predicate) {
        for (int index = 0; index < events.size(); index++) {
            if (predicate.test(events.get(index))) {
                return index;
            }
        }
        return -1;
    }
}
