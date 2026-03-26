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
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import io.helidon.extensions.langchain4j.Ai;
import io.helidon.service.registry.Service;

import dev.langchain4j.agentic.observability.AfterAgentToolExecution;
import dev.langchain4j.agentic.observability.AgentInvocationError;
import dev.langchain4j.agentic.observability.AgentListener;
import dev.langchain4j.agentic.observability.AgentRequest;
import dev.langchain4j.agentic.observability.AgentResponse;
import dev.langchain4j.agentic.observability.BeforeAgentToolExecution;
import dev.langchain4j.agentic.scope.AgentInvocation;
import dev.langchain4j.agentic.scope.AgenticScope;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.guardrail.InputGuardrailRequest;
import dev.langchain4j.guardrail.OutputGuardrailRequest;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.observability.api.event.AiServiceCompletedEvent;
import dev.langchain4j.observability.api.event.AiServiceErrorEvent;
import dev.langchain4j.observability.api.event.AiServiceEvent;
import dev.langchain4j.observability.api.event.AiServiceRequestIssuedEvent;
import dev.langchain4j.observability.api.event.AiServiceResponseReceivedEvent;
import dev.langchain4j.observability.api.event.AiServiceStartedEvent;
import dev.langchain4j.observability.api.event.GuardrailExecutedEvent;
import dev.langchain4j.observability.api.event.InputGuardrailExecutedEvent;
import dev.langchain4j.observability.api.event.OutputGuardrailExecutedEvent;
import dev.langchain4j.observability.api.event.ToolExecutedEvent;

@Service.Singleton
final class LangChain4jDevUiRecorder implements AgentListener {
    private static final int MAX_EVENTS_PER_CAPTURE = 200;
    private static final int MAX_EVENTS_FOR_GLOBAL_TRACKING = 500;
    // Declarative applications can resolve the UI service and the generated agents from
    // different registry-backed recorder instances. Share capture/tracking state across
    // instances so event capture remains coherent even when recorder identity differs.
    private static final ThreadLocal<Deque<Capture>> CAPTURES = ThreadLocal.withInitial(ArrayDeque::new);
    private static final GlobalCapture GLOBAL_CAPTURE = new GlobalCapture();
    private static final Map<String, String> SERVICE_NAMES = new ConcurrentHashMap<>();

    Capture capture() {
        Capture capture = new Capture();
        CAPTURES.get().addLast(capture);
        return capture;
    }

    boolean trackAllEvents() {
        return GLOBAL_CAPTURE.enabled();
    }

    void trackAllEvents(boolean enabled) {
        GLOBAL_CAPTURE.enabled(enabled);
    }

    GlobalSnapshot globalSnapshot() {
        return GLOBAL_CAPTURE.snapshot();
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

    void onAiServiceStarted(AiServiceStartedEvent aiServiceStartedEvent) {
        recordEvent("before-agent", event -> {
            populateAiServiceContext(aiServiceStartedEvent, event);
            event.put("kind", "service");
            event.put("systemMessage", aiServiceStartedEvent.systemMessage().map(SystemMessage::text).orElse(null));
            event.put("userMessage", normalizeUserMessage(aiServiceStartedEvent.userMessage()));
        });
    }

    void onAiServiceCompleted(AiServiceCompletedEvent aiServiceCompletedEvent) {
        recordEvent("after-agent", event -> {
            populateAiServiceContext(aiServiceCompletedEvent, event);
            event.put("kind", "service");
            event.put("output", LangChain4jDevUiJsonSupport.normalize(aiServiceCompletedEvent.result().orElse(null)));
        });
    }

    void onAiServiceError(AiServiceErrorEvent aiServiceErrorEvent) {
        recordEvent("agent-error", event -> {
            populateAiServiceContext(aiServiceErrorEvent, event);
            event.put("kind", "service");
            event.put("error", LangChain4jDevUiJsonSupport.normalize(aiServiceErrorEvent.error()));
        });
    }

    void onAiServiceRequestIssued(AiServiceRequestIssuedEvent aiServiceRequestIssuedEvent) {
        recordEvent("service-request", event -> {
            populateAiServiceContext(aiServiceRequestIssuedEvent, event);
            event.put("kind", "service");
            event.put("request", normalizeChatRequest(aiServiceRequestIssuedEvent.request()));
        });
    }

    void onAiServiceResponseReceived(AiServiceResponseReceivedEvent aiServiceResponseReceivedEvent) {
        recordEvent("service-response", event -> {
            populateAiServiceContext(aiServiceResponseReceivedEvent, event);
            event.put("kind", "service");
            event.put("request", normalizeChatRequest(aiServiceResponseReceivedEvent.request()));
            event.put("response", normalizeChatResponse(aiServiceResponseReceivedEvent.response()));
            event.put("text", responseText(aiServiceResponseReceivedEvent.response()));
        });
    }

    void onToolExecuted(ToolExecutedEvent toolExecutedEvent) {
        recordEvent("after-tool", event -> {
            populateAiServiceContext(toolExecutedEvent, event);
            event.put("kind", "service");
            event.put("tool", toolExecutedEvent.request().name());
            event.put("toolId", toolExecutedEvent.request().id());
            event.put("arguments", toolExecutedEvent.request().arguments());
            event.put("result", LangChain4jDevUiJsonSupport.normalize(toolExecutedEvent.resultText()));
            event.put("failed", false);
        });
    }

    void onInputGuardrailExecuted(InputGuardrailExecutedEvent inputGuardrailExecutedEvent) {
        recordGuardrailEvent("input-guardrail", inputGuardrailExecutedEvent, event -> {
            event.put("request", normalizeInputGuardrailRequest(inputGuardrailExecutedEvent.request()));
            event.put("rewrittenUserMessage", normalizeUserMessage(inputGuardrailExecutedEvent.rewrittenUserMessage()));
        });
    }

    void onOutputGuardrailExecuted(OutputGuardrailExecutedEvent outputGuardrailExecutedEvent) {
        recordGuardrailEvent("output-guardrail", outputGuardrailExecutedEvent, event ->
                event.put("request", normalizeOutputGuardrailRequest(outputGuardrailExecutedEvent.request())));
    }

    void onSyntheticRootAgentStarted(dev.langchain4j.agentic.planner.AgentInstance agent,
                                     Map<String, Object> inputs,
                                     AgenticScope scope) {
        beforeAgentInvocation(new AgentRequest(scope, agent, inputs));
    }

    void onSyntheticRootAgentCompleted(dev.langchain4j.agentic.planner.AgentInstance agent,
                                       Map<String, Object> inputs,
                                       Object output,
                                       AgenticScope scope) {
        afterAgentInvocation(new AgentResponse(scope, agent, inputs, output));
    }

    void onSyntheticRootAgentError(dev.langchain4j.agentic.planner.AgentInstance agent,
                                   Map<String, Object> inputs,
                                   Throwable error,
                                   AgenticScope scope) {
        onAgentInvocationError(new AgentInvocationError(scope, agent, inputs, error));
    }

    private void captureScope(AgenticScope scope) {
        if (scope == null) {
            return;
        }
        Capture capture = currentCapture();
        if (capture != null) {
            capture.scope(scope);
        }
        GLOBAL_CAPTURE.scope(scope);
    }

    private void recordEvent(String type, Consumer<LinkedHashMap<String, Object>> customizer) {
        Capture capture = currentCapture();
        if (capture == null && !GLOBAL_CAPTURE.enabled()) {
            return;
        }

        LinkedHashMap<String, Object> event = new LinkedHashMap<>();
        event.put("timestamp", Instant.now().toString());
        event.put("type", type);
        customizer.accept(event);
        if (capture != null) {
            capture.add(new LinkedHashMap<>(event));
        }
        GLOBAL_CAPTURE.add(event);
    }

    private static Capture currentCapture() {
        Deque<Capture> activeCaptures = CAPTURES.get();
        return activeCaptures.peekLast();
    }

    private static void release(Capture capture) {
        Deque<Capture> activeCaptures = CAPTURES.get();
        activeCaptures.removeLastOccurrence(capture);
        if (activeCaptures.isEmpty()) {
            CAPTURES.remove();
        }
    }

    private void populateAiServiceContext(AiServiceEvent aiServiceEvent, LinkedHashMap<String, Object> event) {
        var invocationContext = aiServiceEvent.invocationContext();
        String agentType = invocationContext.interfaceName();
        event.put("agent", serviceName(agentType));
        event.put("agentId", invocationContext.invocationId().toString());
        event.put("agentType", agentType);
        event.put("method", invocationContext.methodName());
        event.put("inputs", LangChain4jDevUiJsonSupport.normalize(invocationContext.methodArguments()));
        event.put("chatMemoryId", LangChain4jDevUiJsonSupport.normalize(invocationContext.chatMemoryId()));
        event.put("source", "service");
    }

    private void recordGuardrailEvent(String type,
                                      GuardrailExecutedEvent<?, ?, ?> guardrailExecutedEvent,
                                      Consumer<LinkedHashMap<String, Object>> customizer) {
        recordEvent(type, event -> {
            populateAiServiceContext(guardrailExecutedEvent, event);
            event.put("kind", "service");
            event.put("guardrail", guardrailExecutedEvent.guardrailClass().getSimpleName());
            event.put("guardrailType", guardrailExecutedEvent.guardrailClass().getName());
            event.put("status", String.valueOf(guardrailExecutedEvent.result().result()));
            event.put("durationMs", guardrailExecutedEvent.duration().toMillis());
            event.put("result", LangChain4jDevUiJsonSupport.normalize(guardrailExecutedEvent.result()));
            String resultText = guardrailExecutedEvent.result().successfulText();
            if (resultText != null && !resultText.isBlank()) {
                event.put("text", resultText);
            }
            customizer.accept(event);
        });
    }

    private String serviceName(String typeName) {
        return SERVICE_NAMES.computeIfAbsent(typeName, LangChain4jDevUiRecorder::resolveServiceName);
    }

    private static String resolveServiceName(String typeName) {
        return loadClass(typeName)
                .flatMap(type -> {
                    Ai.Service aiService = type.getAnnotation(Ai.Service.class);
                    if (aiService != null && !aiService.value().isBlank()) {
                        return Optional.of(aiService.value());
                    }

                    Ai.Agent aiAgent = type.getAnnotation(Ai.Agent.class);
                    if (aiAgent != null && !aiAgent.value().isBlank()) {
                        return Optional.of(aiAgent.value());
                    }

                    return Optional.empty();
                })
                .orElse(typeName);
    }

    private static Optional<Class<?>> loadClass(String typeName) {
        ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        try {
            return Optional.of(Class.forName(typeName, false, contextClassLoader));
        } catch (ClassNotFoundException | LinkageError ignored) {
            try {
                return Optional.of(Class.forName(typeName, false, LangChain4jDevUiRecorder.class.getClassLoader()));
            } catch (ClassNotFoundException | LinkageError ignoredAgain) {
                return Optional.empty();
            }
        }
    }

    private static Object normalizeUserMessage(UserMessage userMessage) {
        if (userMessage == null) {
            return null;
        }
        if (userMessage.hasSingleText()) {
            return userMessage.singleText();
        }
        return LangChain4jDevUiJsonSupport.normalize(userMessage.contents());
    }

    private static Object normalizeChatRequest(ChatRequest chatRequest) {
        if (chatRequest == null) {
            return null;
        }
        LinkedHashMap<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("messages", chatRequest.messages().stream()
                .map(LangChain4jDevUiRecorder::normalizeChatMessage)
                .toList());
        if (chatRequest.modelName() != null) {
            normalized.put("modelName", chatRequest.modelName());
        }
        if (chatRequest.toolSpecifications() != null && !chatRequest.toolSpecifications().isEmpty()) {
            normalized.put("toolSpecifications", LangChain4jDevUiJsonSupport.normalize(chatRequest.toolSpecifications()));
        }
        if (chatRequest.responseFormat() != null) {
            normalized.put("responseFormat", LangChain4jDevUiJsonSupport.normalize(chatRequest.responseFormat()));
        }
        return normalized;
    }

    private static Object normalizeChatResponse(ChatResponse chatResponse) {
        if (chatResponse == null) {
            return null;
        }
        LinkedHashMap<String, Object> normalized = new LinkedHashMap<>();
        if (chatResponse.modelName() != null) {
            normalized.put("modelName", chatResponse.modelName());
        }
        if (chatResponse.finishReason() != null) {
            normalized.put("finishReason", chatResponse.finishReason().name());
        }
        if (chatResponse.tokenUsage() != null) {
            normalized.put("tokenUsage", LangChain4jDevUiJsonSupport.normalize(chatResponse.tokenUsage()));
        }
        if (chatResponse.aiMessage() != null) {
            normalized.put("message", normalizeAiMessage(chatResponse.aiMessage()));
        }
        return normalized;
    }

    private static Object normalizeInputGuardrailRequest(InputGuardrailRequest inputGuardrailRequest) {
        if (inputGuardrailRequest == null) {
            return null;
        }
        LinkedHashMap<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("userMessage", normalizeUserMessage(inputGuardrailRequest.userMessage()));
        return normalized;
    }

    private static Object normalizeOutputGuardrailRequest(OutputGuardrailRequest outputGuardrailRequest) {
        if (outputGuardrailRequest == null) {
            return null;
        }
        LinkedHashMap<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("response", normalizeChatResponse(outputGuardrailRequest.responseFromLLM()));
        return normalized;
    }

    private static Object normalizeChatMessage(ChatMessage chatMessage) {
        return switch (chatMessage) {
        case null -> null;
        case SystemMessage systemMessage -> systemMessage.text();
        case UserMessage userMessage -> normalizeUserMessage(userMessage);
        case AiMessage aiMessage -> normalizeAiMessage(aiMessage);
        default -> LangChain4jDevUiJsonSupport.normalize(chatMessage);
        };
    }

    private static Object normalizeAiMessage(AiMessage aiMessage) {
        if (aiMessage == null) {
            return null;
        }
        LinkedHashMap<String, Object> normalized = new LinkedHashMap<>();
        if (aiMessage.text() != null && !aiMessage.text().isBlank()) {
            normalized.put("text", aiMessage.text());
        }
        if (aiMessage.thinking() != null && !aiMessage.thinking().isBlank()) {
            normalized.put("thinking", aiMessage.thinking());
        }
        if (aiMessage.toolExecutionRequests() != null && !aiMessage.toolExecutionRequests().isEmpty()) {
            normalized.put("toolExecutionRequests", LangChain4jDevUiJsonSupport.normalize(aiMessage.toolExecutionRequests()));
        }
        return normalized;
    }

    private static String responseText(ChatResponse chatResponse) {
        if (chatResponse == null || chatResponse.aiMessage() == null) {
            return null;
        }
        return chatResponse.aiMessage().text();
    }

    static final class Capture implements AutoCloseable {
        private final Deque<Map<String, Object>> events = new ArrayDeque<>();
        private ScopeSnapshot scopeSnapshot;
        private Consumer<ProgressSnapshot> progressListener;

        private Capture() {
        }

        List<Map<String, Object>> events() {
            return List.copyOf(new ArrayList<>(events));
        }

        ScopeSnapshot scopeSnapshot() {
            return scopeSnapshot;
        }

        Capture progressListener(Consumer<ProgressSnapshot> progressListener) {
            this.progressListener = progressListener;
            notifyProgress();
            return this;
        }

        @Override
        public void close() {
            release(this);
        }

        private void add(Map<String, Object> event) {
            while (events.size() >= MAX_EVENTS_PER_CAPTURE) {
                events.removeFirst();
            }
            events.addLast(event);
            notifyProgress();
        }

        private void scope(AgenticScope scope) {
            ScopeSnapshot newSnapshot = ScopeSnapshot.create(scope);
            scopeSnapshot = mergeScopeSnapshots(scopeSnapshot, newSnapshot);
            notifyProgress();
        }

        private void notifyProgress() {
            if (progressListener == null) {
                return;
            }
            progressListener.accept(new ProgressSnapshot(events(), scopeSnapshot));
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

    record ProgressSnapshot(List<Map<String, Object>> events, ScopeSnapshot scopeSnapshot) {
    }

    record GlobalSnapshot(boolean enabled,
                          Instant lastUpdated,
                          ScopeSnapshot scopeSnapshot,
                          List<Map<String, Object>> events) {
    }

    private static ScopeSnapshot mergeScopeSnapshots(ScopeSnapshot existingSnapshot, ScopeSnapshot newSnapshot) {
        if (existingSnapshot == null) {
            return newSnapshot;
        }

        LinkedHashMap<String, Object> mergedState = new LinkedHashMap<>(existingSnapshot.state());
        newSnapshot.state().forEach((name, value) -> {
            if (value != null || !mergedState.containsKey(name)) {
                mergedState.put(name, value);
            }
        });

        List<Map<String, Object>> mergedInvocations = newSnapshot.invocations().size() >= existingSnapshot.invocations().size()
                ? newSnapshot.invocations()
                : existingSnapshot.invocations();
        return new ScopeSnapshot(mergedState, mergedInvocations);
    }

    private static ScopeSnapshot copyScopeSnapshot(ScopeSnapshot scopeSnapshot) {
        if (scopeSnapshot == null) {
            return null;
        }

        LinkedHashMap<String, Object> copiedState = new LinkedHashMap<>(scopeSnapshot.state());
        List<Map<String, Object>> copiedInvocations = scopeSnapshot.invocations().stream()
                .map(LinkedHashMap::new)
                .map(map -> (Map<String, Object>) map)
                .toList();
        return new ScopeSnapshot(copiedState, List.copyOf(copiedInvocations));
    }

    private static List<Map<String, Object>> copyEvents(Deque<Map<String, Object>> events) {
        return List.copyOf(events.stream()
                                       .map(LinkedHashMap::new)
                                       .map(map -> (Map<String, Object>) map)
                                       .toList());
    }

    private static final class GlobalCapture {
        private final Object lock = new Object();

        private boolean enabled;
        private Instant lastUpdated;
        private final Deque<Map<String, Object>> events = new ArrayDeque<>();
        private ScopeSnapshot scopeSnapshot;

        private boolean enabled() {
            synchronized (lock) {
                return enabled;
            }
        }

        private void enabled(boolean enabled) {
            synchronized (lock) {
                if (this.enabled == enabled) {
                    return;
                }
                this.enabled = enabled;
                lastUpdated = null;
                events.clear();
                scopeSnapshot = null;
            }
        }

        private void add(Map<String, Object> event) {
            synchronized (lock) {
                if (!enabled) {
                    return;
                }
                while (events.size() >= MAX_EVENTS_FOR_GLOBAL_TRACKING) {
                    events.removeFirst();
                }
                events.addLast(new LinkedHashMap<>(event));
                lastUpdated = Instant.now();
            }
        }

        private void scope(AgenticScope scope) {
            synchronized (lock) {
                if (!enabled || scope == null) {
                    return;
                }
                scopeSnapshot = mergeScopeSnapshots(scopeSnapshot, ScopeSnapshot.create(scope));
                lastUpdated = Instant.now();
            }
        }

        private GlobalSnapshot snapshot() {
            synchronized (lock) {
                return new GlobalSnapshot(enabled,
                                          lastUpdated,
                                          copyScopeSnapshot(scopeSnapshot),
                                          copyEvents(events));
            }
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
