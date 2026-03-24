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

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import io.helidon.service.registry.Service;

import dev.langchain4j.agentic.observability.AfterAgentToolExecution;
import dev.langchain4j.agentic.observability.AgentInvocationError;
import dev.langchain4j.agentic.observability.AgentListener;
import dev.langchain4j.agentic.observability.AgentRequest;
import dev.langchain4j.agentic.observability.AgentResponse;
import dev.langchain4j.agentic.observability.BeforeAgentToolExecution;
import dev.langchain4j.agentic.scope.AgentInvocation;
import dev.langchain4j.agentic.scope.AgenticScope;

@Service.Singleton
final class LangChain4jDevUiRecorder implements AgentListener {
    private static final int MAX_EVENTS_PER_CAPTURE = 200;

    private final ThreadLocal<Deque<Capture>> captures = ThreadLocal.withInitial(ArrayDeque::new);

    Capture capture() {
        Capture capture = new Capture(this);
        captures.get().addLast(capture);
        return capture;
    }

    @Override
    public void beforeAgentInvocation(AgentRequest agentRequest) {
        captureScope(agentRequest.agenticScope());
        recordEvent("before-agent", event -> {
            event.put("agent", agentRequest.agentName());
            event.put("agentId", agentRequest.agentId());
            event.put("agentType", agentRequest.agent().type().getName());
            event.put("outputKey", agentRequest.agent().outputKey());
            event.put("inputs", LangChain4jDevUiJsonSupport.normalize(agentRequest.inputs()));
        });
    }

    @Override
    public void afterAgentInvocation(AgentResponse agentResponse) {
        captureScope(agentResponse.agenticScope());
        recordEvent("after-agent", event -> {
            event.put("agent", agentResponse.agentName());
            event.put("agentId", agentResponse.agentId());
            event.put("agentType", agentResponse.agent().type().getName());
            event.put("outputKey", agentResponse.agent().outputKey());
            event.put("inputs", LangChain4jDevUiJsonSupport.normalize(agentResponse.inputs()));
            event.put("output", LangChain4jDevUiJsonSupport.normalize(agentResponse.output()));
        });
    }

    @Override
    public void onAgentInvocationError(AgentInvocationError agentInvocationError) {
        captureScope(agentInvocationError.agenticScope());
        recordEvent("agent-error", event -> {
            event.put("agent", agentInvocationError.agentName());
            event.put("agentId", agentInvocationError.agentId());
            event.put("agentType", agentInvocationError.agent().type().getName());
            event.put("outputKey", agentInvocationError.agent().outputKey());
            event.put("inputs", LangChain4jDevUiJsonSupport.normalize(agentInvocationError.inputs()));
            event.put("error", LangChain4jDevUiJsonSupport.normalize(agentInvocationError.error()));
        });
    }

    @Override
    public void afterAgenticScopeCreated(AgenticScope agenticScope) {
        captureScope(agenticScope);
        recordEvent("scope-created", event -> event.put("scope", agenticScope.getClass().getSimpleName()));
    }

    @Override
    public void beforeAgenticScopeDestroyed(AgenticScope agenticScope) {
        captureScope(agenticScope);
        recordEvent("scope-destroyed", event -> event.put("scope", agenticScope.getClass().getSimpleName()));
    }

    @Override
    public void beforeAgentToolExecution(BeforeAgentToolExecution beforeAgentToolExecution) {
        captureScope(beforeAgentToolExecution.agenticScope());
        recordEvent("before-tool", event -> {
            event.put("agent", beforeAgentToolExecution.agentInstance().name());
            event.put("tool", beforeAgentToolExecution.toolExecution().request().name());
            event.put("toolId", beforeAgentToolExecution.toolExecution().request().id());
            event.put("arguments", beforeAgentToolExecution.toolExecution().request().arguments());
        });
    }

    @Override
    public void afterAgentToolExecution(AfterAgentToolExecution afterAgentToolExecution) {
        captureScope(afterAgentToolExecution.agenticScope());
        recordEvent("after-tool", event -> {
            event.put("agent", afterAgentToolExecution.agentInstance().name());
            event.put("tool", afterAgentToolExecution.toolExecution().request().name());
            event.put("toolId", afterAgentToolExecution.toolExecution().request().id());
            event.put("arguments", afterAgentToolExecution.toolExecution().request().arguments());
            event.put("result", LangChain4jDevUiJsonSupport.normalize(afterAgentToolExecution.toolExecution().resultObject()));
            event.put("failed", afterAgentToolExecution.toolExecution().hasFailed());
        });
    }

    private void captureScope(AgenticScope scope) {
        Capture capture = currentCapture();
        if (capture == null || scope == null) {
            return;
        }
        capture.scope(scope);
    }

    private void recordEvent(String type, Consumer<LinkedHashMap<String, Object>> customizer) {
        Capture capture = currentCapture();
        if (capture == null) {
            return;
        }

        LinkedHashMap<String, Object> event = new LinkedHashMap<>();
        event.put("timestamp", Instant.now().toString());
        event.put("type", type);
        customizer.accept(event);
        capture.add(event);
    }

    private Capture currentCapture() {
        Deque<Capture> activeCaptures = captures.get();
        return activeCaptures.peekLast();
    }

    private void release(Capture capture) {
        Deque<Capture> activeCaptures = captures.get();
        activeCaptures.removeLastOccurrence(capture);
        if (activeCaptures.isEmpty()) {
            captures.remove();
        }
    }

    static final class Capture implements AutoCloseable {
        private final LangChain4jDevUiRecorder owner;
        private final Deque<Map<String, Object>> events = new ArrayDeque<>();
        private ScopeSnapshot scopeSnapshot;

        private Capture(LangChain4jDevUiRecorder owner) {
            this.owner = owner;
        }

        List<Map<String, Object>> events() {
            return List.copyOf(new ArrayList<>(events));
        }

        ScopeSnapshot scopeSnapshot() {
            return scopeSnapshot;
        }

        @Override
        public void close() {
            owner.release(this);
        }

        private void add(Map<String, Object> event) {
            while (events.size() >= MAX_EVENTS_PER_CAPTURE) {
                events.removeFirst();
            }
            events.addLast(event);
        }

        private void scope(AgenticScope scope) {
            ScopeSnapshot newSnapshot = ScopeSnapshot.create(scope);
            if (scopeSnapshot == null) {
                scopeSnapshot = newSnapshot;
                return;
            }

            LinkedHashMap<String, Object> mergedState = new LinkedHashMap<>(scopeSnapshot.state());
            newSnapshot.state().forEach((name, value) -> {
                if (value != null || !mergedState.containsKey(name)) {
                    mergedState.put(name, value);
                }
            });

            List<Map<String, Object>> mergedInvocations = newSnapshot.invocations().size() >= scopeSnapshot.invocations().size()
                    ? newSnapshot.invocations()
                    : scopeSnapshot.invocations();
            scopeSnapshot = new ScopeSnapshot(mergedState, mergedInvocations);
        }
    }

    record ScopeSnapshot(Map<String, Object> state, List<Map<String, Object>> invocations) {
        private static ScopeSnapshot create(AgenticScope scope) {
            @SuppressWarnings("unchecked")
            Map<String, Object> normalizedState = (Map<String, Object>) LangChain4jDevUiJsonSupport.normalize(scope.state());
            List<Map<String, Object>> normalizedInvocations = scope.agentInvocations().stream()
                    .map(LangChain4jDevUiRecorder::normalizeInvocation)
                    .toList();
            return new ScopeSnapshot(new LinkedHashMap<>(normalizedState), List.copyOf(normalizedInvocations));
        }
    }

    private static Map<String, Object> normalizeInvocation(AgentInvocation invocation) {
        LinkedHashMap<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("agentType", invocation.agentType().getName());
        normalized.put("agentName", invocation.agentName());
        normalized.put("agentId", invocation.agentId());
        normalized.put("input", LangChain4jDevUiJsonSupport.normalize(invocation.input()));
        normalized.put("output", LangChain4jDevUiJsonSupport.normalize(invocation.output()));
        return normalized;
    }
}
