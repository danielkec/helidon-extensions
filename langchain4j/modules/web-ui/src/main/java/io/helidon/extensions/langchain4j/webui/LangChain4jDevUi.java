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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import io.helidon.builder.api.RuntimeType;
import io.helidon.common.media.type.MediaTypes;
import io.helidon.extensions.langchain4j.AgentMetadata;
import io.helidon.http.HeaderNames;
import io.helidon.http.Status;
import io.helidon.service.registry.ServiceRegistry;
import io.helidon.service.registry.Services;
import io.helidon.webserver.http.HttpRules;
import io.helidon.webserver.http.HttpService;
import io.helidon.webserver.http.ServerRequest;
import io.helidon.webserver.http.ServerResponse;
import io.helidon.webserver.staticcontent.ClasspathHandlerConfig;
import io.helidon.webserver.staticcontent.StaticContentFeature;

import dev.langchain4j.agentic.agent.MissingArgumentException;
import dev.langchain4j.agentic.declarative.ActivationCondition;
import dev.langchain4j.agentic.declarative.ConditionalAgent;
import dev.langchain4j.agentic.declarative.SequenceAgent;
import dev.langchain4j.agentic.declarative.TypedKey;
import dev.langchain4j.agentic.internal.AgenticScopeOwner;
import dev.langchain4j.agentic.scope.AgentInvocation;
import dev.langchain4j.agentic.scope.AgenticScope;
import dev.langchain4j.agentic.scope.AgenticScopeAccess;
import dev.langchain4j.agentic.scope.AgenticScopeRegistry;
import dev.langchain4j.agentic.scope.DefaultAgenticScope;
import dev.langchain4j.agentic.scope.ResultWithAgenticScope;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.ParameterNameResolver;
import dev.langchain4j.service.V;

/**
 * Browser-based development UI for Helidon-managed LangChain4j agents.
 */
public final class LangChain4jDevUi implements HttpService, RuntimeType.Api<LangChain4jDevUiConfig> {
    /**
     * Configuration key used by the declarative webserver feature.
     */
    public static final String CONFIG_KEY = "langchain4j-dev-ui";

    /**
     * Default base web context.
     */
    public static final String DEFAULT_WEB_CONTEXT = "/langchain4j/ui";

    private static final String STATIC_LOCATION = "helidon-langchain4j-devui";
    private static final Duration INVOCATION_JOB_RETENTION = Duration.ofMinutes(10);
    private static final int MAX_INVOCATION_JOBS = 200;

    private final LangChain4jDevUiConfig config;
    private final ServiceRegistry registry;
    private final LangChain4jDevUiRecorder recorder;
    private final Map<String, AgentHandle> agents;
    private final ConcurrentMap<String, InvocationJob> invocationJobs;

    private LangChain4jDevUi(LangChain4jDevUiConfig config,
                             ServiceRegistry registry,
                             LangChain4jDevUiRecorder recorder) {
        this.config = Objects.requireNonNull(config);
        this.registry = Objects.requireNonNull(registry);
        this.recorder = Objects.requireNonNull(recorder);
        this.agents = Collections.unmodifiableMap(discoverAgents());
        this.invocationJobs = new ConcurrentHashMap<>();
    }

    /**
     * Returns a new builder.
     *
     * @return config builder
     */
    public static LangChain4jDevUiConfig.Builder builder() {
        return LangChain4jDevUiConfig.builder();
    }

    /**
     * Create a new UI instance using the application-wide {@link Services} registry.
     *
     * @return new UI instance
     */
    public static LangChain4jDevUi create() {
        return builder().build();
    }

    /**
     * Create a new UI instance customizing the configuration builder.
     *
     * @param consumer configuration builder customizer
     * @return new UI instance
     */
    public static LangChain4jDevUi create(Consumer<LangChain4jDevUiConfig.Builder> consumer) {
        return builder().update(consumer).build();
    }

    /**
     * Create a new UI instance using the application-wide {@link Services} registry.
     *
     * @param config typed configuration
     * @return new UI instance
     */
    public static LangChain4jDevUi create(LangChain4jDevUiConfig config) {
        return new LangChain4jDevUi(config,
                                    Services.get(ServiceRegistry.class),
                                    Services.get(LangChain4jDevUiRecorder.class));
    }

    /**
     * Create a new UI instance bound to the provided service registry.
     *
     * @param registry service registry used for agent discovery and invocation
     * @return new UI instance
     */
    public static LangChain4jDevUi create(ServiceRegistry registry) {
        return create(registry, LangChain4jDevUiConfig.builder().buildPrototype());
    }

    /**
     * Create a new UI instance bound to the provided service registry and configuration.
     *
     * @param registry service registry used for agent discovery and invocation
     * @param config typed configuration
     * @return new UI instance
     */
    public static LangChain4jDevUi create(ServiceRegistry registry, LangChain4jDevUiConfig config) {
        return new LangChain4jDevUi(config, registry, registry.get(LangChain4jDevUiRecorder.class));
    }

    @Override
    public LangChain4jDevUiConfig prototype() {
        return config;
    }

    @Override
    public void routing(HttpRules rules) {
        if (!config.isEnabled()) {
            return;
        }

        String context = config.webContext();
        rules.get(context, this::redirectIndex)
                .get(context + "/api/agents", this::listAgents)
                .post(context + "/api/invoke", this::invoke)
                .post(context + "/api/invocations", this::startInvocation)
                .get(context + "/api/invocations/{invocationId}", this::invocationStatus)
                .register(context, StaticContentFeature.createService(ClasspathHandlerConfig.builder()
                                                                             .location(STATIC_LOCATION)
                                                                             .welcome("index.html")
                                                                             .build()));
    }

    private void redirectIndex(ServerRequest req, ServerResponse res) {
        res.status(Status.TEMPORARY_REDIRECT_307);
        res.header(HeaderNames.LOCATION, config.webContext() + "/");
        res.send();
    }

    private void listAgents(ServerRequest req, ServerResponse res) {
        var entity = agents.values().stream().map(AgentHandle::apiView).toList();
        json(res, entity);
    }

    private void invoke(ServerRequest req, ServerResponse res) {
        try {
            ResolvedInvokeRequest request = resolveInvokeRequest(req);
            InvocationResult result = invoke(request.agent(),
                                             request.method(),
                                             request.memoryId(),
                                             request.arguments(),
                                             request.state());

            LinkedHashMap<String, Object> response = new LinkedHashMap<>();
            response.put("agent", request.agent().name());
            response.put("method", request.method().id());
            response.put("result", result.result());
            if (result.inspection() != null) {
                response.put("inspection", result.inspection());
            }
            json(res, response);
        } catch (DevUiException e) {
            error(res, e.status(), e.getMessage());
        } catch (Exception e) {
            error(res, Status.INTERNAL_SERVER_ERROR_500, rootMessage(e));
        }
    }

    private void startInvocation(ServerRequest req, ServerResponse res) {
        try {
            cleanupInvocationJobs();
            ResolvedInvokeRequest request = resolveInvokeRequest(req);
            InvocationJob job = new InvocationJob(UUID.randomUUID().toString(),
                                                  request.agent().name(),
                                                  request.method().id());
            invocationJobs.put(job.id(), job);
            Thread.startVirtualThread(() -> runInvocationJob(job, request));

            res.status(Status.ACCEPTED_202);
            json(res, job.snapshot());
        } catch (DevUiException e) {
            error(res, e.status(), e.getMessage());
        } catch (Exception e) {
            error(res, Status.INTERNAL_SERVER_ERROR_500, rootMessage(e));
        }
    }

    private void invocationStatus(ServerRequest req, ServerResponse res) {
        try {
            cleanupInvocationJobs();
            String invocationId = pathParameter(req, "invocationId");
            InvocationJob job = invocationJobs.get(invocationId);
            if (job == null) {
                throw new DevUiException(Status.NOT_FOUND_404, "Invocation " + invocationId + " not found");
            }
            json(res, job.snapshot());
        } catch (DevUiException e) {
            error(res, e.status(), e.getMessage());
        }
    }

    private ResolvedInvokeRequest resolveInvokeRequest(ServerRequest req) {
        InvokeRequest request = LangChain4jDevUiJsonSupport.read(req.content().inputStream(), InvokeRequest.class);
        if (request == null || request.agent() == null || request.method() == null) {
            throw new DevUiException(Status.BAD_REQUEST_400, "Agent and method must be provided");
        }

        AgentHandle agent = agent(request.agent());
        AgentMethodHandle method = agent.method(request.method());
        Map<String, Object> arguments = request.arguments() == null ? Map.of() : request.arguments();
        Map<String, Object> state = request.state() == null ? Map.of() : request.state();
        return new ResolvedInvokeRequest(agent, method, request.memoryId(), arguments, state);
    }

    private void runInvocationJob(InvocationJob job, ResolvedInvokeRequest request) {
        try {
            InvocationResult result = invoke(request.agent(),
                                             request.method(),
                                             request.memoryId(),
                                             request.arguments(),
                                             request.state(),
                                             job::progress);
            job.complete(result);
        } catch (DevUiException e) {
            job.fail(e.status(), e.getMessage());
        } catch (Exception e) {
            job.fail(Status.INTERNAL_SERVER_ERROR_500, rootMessage(e));
        }
    }

    private InvocationResult invoke(AgentHandle agent,
                                    AgentMethodHandle method,
                                    String requestedMemoryId,
                                    Map<String, Object> arguments,
                                    Map<String, Object> initialState) {
        return invoke(agent, method, requestedMemoryId, arguments, initialState, null);
    }

    private InvocationResult invoke(AgentHandle agent,
                                    AgentMethodHandle method,
                                    String requestedMemoryId,
                                    Map<String, Object> arguments,
                                    Map<String, Object> initialState,
                                    Consumer<Map<String, Object>> progressConsumer) {
        Object[] invocationArguments = bindArguments(method, requestedMemoryId, arguments);
        Map<String, Object> boundState = bindState(method, initialState);
        PreparedInvocation preparedInvocation = prepareInvocation(agent, method, requestedMemoryId, boundState);
        Map<String, Object> normalizedInput = normalizeInput(method, invocationArguments, boundState);

        try (LangChain4jDevUiRecorder.Capture capture = recorder.capture()) {
            if (progressConsumer != null) {
                capture.progressListener(progress -> progressConsumer.accept(progressInspection(agent,
                                                                                              method,
                                                                                              normalizedInput,
                                                                                              progress)));
            }
            Object invocationResult = method.method().invoke(preparedInvocation.target(), invocationArguments);
            List<Map<String, Object>> events = capture.events();
            Object normalizedResult;
            AgenticScope scope = null;

            if (invocationResult instanceof ResultWithAgenticScope<?> scopedResult) {
                scope = scopedResult.agenticScope();
                normalizedResult = LangChain4jDevUiJsonSupport.normalize(scopedResult.result());
            } else if (preparedInvocation.scope() != null) {
                scope = preparedInvocation.scope();
                normalizedResult = LangChain4jDevUiJsonSupport.normalize(invocationResult);
            } else {
                Object memoryId = findMemoryId(method, requestedMemoryId);
                scope = memoryId == null ? null : findScope(agent, memoryId);
                normalizedResult = LangChain4jDevUiJsonSupport.normalize(invocationResult);
            }

            LangChain4jDevUiRecorder.ScopeSnapshot scopeSnapshot = inspectionScope(scope, capture.scopeSnapshot());

            if (normalizedResult == null && scopeSnapshot != null) {
                Object scopedResult = scopeResult(scopeSnapshot, events);
                if (scopedResult != null) {
                    normalizedResult = scopedResult;
                }
            }

            if (normalizedResult == null) {
                normalizedResult = latestAgentOutput(events);
            }

            scopeSnapshot = enrichInspectionScope(scopeSnapshot, normalizedResult, events);
            Map<String, Object> inspection = inspection(agent, method, normalizedInput, normalizedResult, scopeSnapshot, events);
            if (progressConsumer != null) {
                progressConsumer.accept(inspection);
            }
            return new InvocationResult(normalizedResult, inspection);
        } catch (IllegalAccessException e) {
            throw new DevUiException(Status.INTERNAL_SERVER_ERROR_500, e.getMessage(), e);
        } catch (InvocationTargetException e) {
            Throwable target = e.getTargetException();
            Status status = hasCause(target, MissingArgumentException.class)
                    ? Status.BAD_REQUEST_400
                    : Status.INTERNAL_SERVER_ERROR_500;
            throw new DevUiException(status, rootMessage(target), target);
        }
    }

    private Map<String, Object> progressInspection(AgentHandle agent,
                                                   AgentMethodHandle method,
                                                   Map<String, Object> normalizedInput,
                                                   LangChain4jDevUiRecorder.ProgressSnapshot progress) {
        LangChain4jDevUiRecorder.ScopeSnapshot scopeSnapshot = inspectionScope(null, progress.scopeSnapshot());
        return inspection(agent, method, normalizedInput, null, scopeSnapshot, progress.events());
    }

    private Map<String, Object> inspection(AgentHandle agent,
                                           AgentMethodHandle method,
                                           Map<String, Object> input,
                                           Object result,
                                           LangChain4jDevUiRecorder.ScopeSnapshot scopeSnapshot,
                                           List<Map<String, Object>> recordedEvents) {
        LinkedHashMap<String, Object> inspection = new LinkedHashMap<>();
        List<Map<String, Object>> invocations = inspectionInvocations(scopeSnapshot, recordedEvents);
        List<Map<String, Object>> events = inspectionEvents(agent, method, input, scopeSnapshot, invocations, recordedEvents);
        inspection.put("lastUpdated", Instant.now().toString());
        inspection.put("input", input);
        inspection.put("result", result);
        inspection.put("scopeAvailable", scopeSnapshot != null);
        inspection.put("state", scopeSnapshot == null ? Map.of() : scopeSnapshot.state());
        inspection.put("invocations", invocations);
        inspection.put("events", events == null ? List.of() : List.copyOf(events));
        return inspection;
    }

    private List<Map<String, Object>> inspectionEvents(AgentHandle agent,
                                                       AgentMethodHandle method,
                                                       Map<String, Object> input,
                                                       LangChain4jDevUiRecorder.ScopeSnapshot scopeSnapshot,
                                                       List<Map<String, Object>> invocations,
                                                       List<Map<String, Object>> recordedEvents) {
        List<Map<String, Object>> baseEvents = recordedEvents == null ? List.of() : List.copyOf(recordedEvents);
        List<ConditionalRoutingDescriptor> descriptors = conditionalRoutingDescriptors(agent, method, invocations, baseEvents);
        if (descriptors.isEmpty()) {
            return baseEvents;
        }

        Map<String, Object> evaluationState = conditionalEvaluationState(input, scopeSnapshot);
        List<SyntheticEventGroup> groups = descriptors.stream()
                .map(descriptor -> synthesizeConditionalRoutingEvents(descriptor, evaluationState, invocations, baseEvents))
                .filter(group -> !group.events().isEmpty())
                .sorted(Comparator.comparingInt(SyntheticEventGroup::anchorIndex))
                .toList();
        if (groups.isEmpty()) {
            return baseEvents;
        }

        ArrayList<Map<String, Object>> merged = new ArrayList<>(baseEvents.size()
                                                                        + groups.stream()
                                                                                .mapToInt(group -> group.events().size())
                                                                                .sum());
        int groupIndex = 0;
        for (int eventIndex = 0; eventIndex < baseEvents.size(); eventIndex++) {
            while (groupIndex < groups.size() && groups.get(groupIndex).anchorIndex() <= eventIndex) {
                merged.addAll(groups.get(groupIndex).events());
                groupIndex++;
            }
            merged.add(baseEvents.get(eventIndex));
        }
        while (groupIndex < groups.size()) {
            merged.addAll(groups.get(groupIndex).events());
            groupIndex++;
        }
        return List.copyOf(merged);
    }

    private LangChain4jDevUiRecorder.ScopeSnapshot enrichInspectionScope(LangChain4jDevUiRecorder.ScopeSnapshot scopeSnapshot,
                                                                         Object result,
                                                                         List<Map<String, Object>> events) {
        if (scopeSnapshot == null || result == null) {
            return scopeSnapshot;
        }

        LinkedHashMap<String, Object> enrichedState = new LinkedHashMap<>(scopeSnapshot.state());
        String outputKey = latestAgentOutputKey(events);
        if (outputKey != null && !outputKey.isBlank()) {
            enrichedState.putIfAbsent(outputKey, result);
        } else if (!enrichedState.containsValue(result)) {
            enrichedState.put("response", result);
        }

        if (enrichedState.equals(scopeSnapshot.state())) {
            return scopeSnapshot;
        }
        return new LangChain4jDevUiRecorder.ScopeSnapshot(enrichedState, scopeSnapshot.invocations());
    }

    private LangChain4jDevUiRecorder.ScopeSnapshot inspectionScope(AgenticScope liveScope,
                                                                   LangChain4jDevUiRecorder.ScopeSnapshot capturedScope) {
        if (liveScope != null) {
            return new LangChain4jDevUiRecorder.ScopeSnapshot(normalizeState(liveScope), normalizeInvocations(liveScope));
        }
        return capturedScope;
    }

    private Object latestAgentOutput(List<Map<String, Object>> events) {
        for (int index = events.size() - 1; index >= 0; index--) {
            Map<String, Object> event = events.get(index);
            if (!"after-agent".equals(event.get("type"))) {
                continue;
            }
            Object output = event.get("output");
            if (output != null) {
                return output;
            }
        }
        return null;
    }

    private String latestAgentOutputKey(List<Map<String, Object>> events) {
        for (int index = events.size() - 1; index >= 0; index--) {
            Map<String, Object> event = events.get(index);
            if (!"after-agent".equals(event.get("type"))) {
                continue;
            }
            Object outputKey = event.get("outputKey");
            if (outputKey instanceof String value && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private Object scopeResult(LangChain4jDevUiRecorder.ScopeSnapshot scopeSnapshot,
                               List<Map<String, Object>> events) {
        String outputKey = latestAgentOutputKey(events);
        if (outputKey != null && !outputKey.isBlank()) {
            Object scopedResult = scopeSnapshot.state().get(outputKey);
            if (scopedResult != null) {
                return scopedResult;
            }
        }
        return scopeSnapshot.state().get("response");
    }

    private List<Map<String, Object>> inspectionInvocations(LangChain4jDevUiRecorder.ScopeSnapshot scopeSnapshot,
                                                            List<Map<String, Object>> events) {
        List<Map<String, Object>> snapshotInvocations = scopeSnapshot == null ? List.of() : scopeSnapshot.invocations();
        List<Map<String, Object>> derivedInvocations = deriveInvocations(events);
        return derivedInvocations.size() > snapshotInvocations.size() ? derivedInvocations : snapshotInvocations;
    }

    private List<Map<String, Object>> deriveInvocations(List<Map<String, Object>> events) {
        return events.stream()
                .filter(event -> "after-agent".equals(event.get("type")) || "agent-error".equals(event.get("type")))
                .map(this::normalizeRecordedInvocation)
                .toList();
    }

    private List<ConditionalRoutingDescriptor> conditionalRoutingDescriptors(AgentHandle agent,
                                                                             AgentMethodHandle method,
                                                                             List<Map<String, Object>> invocations,
                                                                             List<Map<String, Object>> events) {
        LinkedHashMap<String, ConditionalRoutingDescriptor> descriptors = new LinkedHashMap<>();
        conditionalRoutingDescriptor(agent, method.method()).ifPresent(descriptor -> descriptors.put(descriptor.key(), descriptor));

        events.stream()
                .filter(event -> event.get("agentType") instanceof String)
                .forEach(event -> conditionalRoutingDescriptor((String) event.get("agentType"),
                                                               event.get("method") instanceof String methodName ? methodName : null,
                                                               event.get("agent") instanceof String agentName ? agentName : null)
                        .ifPresent(descriptor -> descriptors.putIfAbsent(descriptor.key(), descriptor)));
        invocations.stream()
                .filter(invocation -> invocation.get("agentType") instanceof String)
                .forEach(invocation -> conditionalRoutingDescriptor((String) invocation.get("agentType"),
                                                                    invocation.get("agentName") instanceof String agentName
                                                                            ? agentName
                                                                            : null,
                                                                    null)
                        .ifPresent(descriptor -> descriptors.putIfAbsent(descriptor.key(), descriptor)));
        return List.copyOf(descriptors.values());
    }

    private Optional<ConditionalRoutingDescriptor> conditionalRoutingDescriptor(AgentHandle agent, Method method) {
        if (!method.isAnnotationPresent(ConditionalAgent.class)) {
            return Optional.empty();
        }
        return Optional.of(buildConditionalRoutingDescriptor(method.getDeclaringClass(), method, agent.name()));
    }

    private Optional<ConditionalRoutingDescriptor> conditionalRoutingDescriptor(String agentTypeName,
                                                                               String methodNameHint,
                                                                               String agentDisplayName) {
        Optional<Class<?>> agentType = agentType(agentTypeName);
        if (agentType.isEmpty()) {
            return Optional.empty();
        }

        List<Method> conditionalMethods = Arrays.stream(agentType.get().getMethods())
                .filter(LangChain4jDevUi::isInvocable)
                .filter(method -> method.isAnnotationPresent(ConditionalAgent.class))
                .toList();
        if (conditionalMethods.isEmpty()) {
            return Optional.empty();
        }

        Optional<Method> namedMethod = conditionalMethods.stream()
                .filter(method -> Objects.equals(conditionalAgentName(method), methodNameHint))
                .findFirst();
        if (namedMethod.isPresent()) {
            return Optional.of(buildConditionalRoutingDescriptor(agentType.get(),
                                                                 namedMethod.get(),
                                                                 agentDisplayName == null || agentDisplayName.isBlank()
                                                                         ? agentName(agentType.get())
                                                                         : agentDisplayName));
        }
        if (conditionalMethods.size() == 1) {
            return Optional.of(buildConditionalRoutingDescriptor(agentType.get(),
                                                                 conditionalMethods.get(0),
                                                                 agentDisplayName == null || agentDisplayName.isBlank()
                                                                         ? agentName(agentType.get())
                                                                         : agentDisplayName));
        }
        return Optional.empty();
    }

    private ConditionalRoutingDescriptor buildConditionalRoutingDescriptor(Class<?> agentType,
                                                                          Method conditionalMethod,
                                                                          String agentDisplayName) {
        ConditionalAgent annotation = conditionalMethod.getAnnotation(ConditionalAgent.class);
        ArrayList<ConditionalBranchDescriptor> branches = new ArrayList<>();
        for (Class<?> subagentType : annotation.subAgents()) {
            activationConditionMethod(agentType, subagentType).ifPresent(activationMethod -> {
                ActivationCondition activationCondition = activationMethod.getAnnotation(ActivationCondition.class);
                branches.add(new ConditionalBranchDescriptor(activationMethod,
                                                             activationCondition.description(),
                                                             subagentType.getName(),
                                                             agentName(subagentType)));
            });
        }
        return new ConditionalRoutingDescriptor(agentType.getName(),
                                                conditionalAgentName(conditionalMethod),
                                                agentDisplayName == null || agentDisplayName.isBlank()
                                                        ? agentName(agentType)
                                                        : agentDisplayName,
                                                List.copyOf(branches));
    }

    private Optional<Method> activationConditionMethod(Class<?> agentType, Class<?> subagentType) {
        return Arrays.stream(agentType.getMethods())
                .filter(candidate -> Modifier.isStatic(candidate.getModifiers()))
                .filter(candidate -> candidate.isAnnotationPresent(ActivationCondition.class))
                .filter(candidate -> Arrays.asList(candidate.getAnnotation(ActivationCondition.class).value()).contains(subagentType))
                .findFirst();
    }

    private String conditionalAgentName(Method method) {
        ConditionalAgent annotation = method.getAnnotation(ConditionalAgent.class);
        if (annotation == null || annotation.name().isBlank()) {
            return method.getName();
        }
        return annotation.name();
    }

    private Optional<Class<?>> agentType(String agentTypeName) {
        Class<?> discoveredType = agents.values().stream()
                .map(AgentHandle::agentType)
                .filter(agentType -> agentType.getName().equals(agentTypeName))
                .findFirst()
                .orElse(null);
        if (discoveredType != null) {
            return Optional.of(discoveredType);
        }

        try {
            return Optional.of(Class.forName(agentTypeName));
        } catch (ClassNotFoundException e) {
            return Optional.empty();
        }
    }

    private String agentName(Class<?> agentType) {
        return agents.values().stream()
                .filter(handle -> handle.agentType().equals(agentType))
                .map(AgentHandle::name)
                .findFirst()
                .orElse(agentType.getSimpleName());
    }

    private Map<String, Object> conditionalEvaluationState(Map<String, Object> input,
                                                           LangChain4jDevUiRecorder.ScopeSnapshot scopeSnapshot) {
        LinkedHashMap<String, Object> state = new LinkedHashMap<>();
        if (input != null) {
            input.forEach((name, value) -> {
                if (!"agenticState".equals(name)) {
                    state.put(name, value);
                }
            });
            Object injectedState = input.get("agenticState");
            if (injectedState instanceof Map<?, ?> injectedStateMap) {
                injectedStateMap.forEach((name, value) -> {
                    if (name != null) {
                        state.put(String.valueOf(name), value);
                    }
                });
            }
        }
        if (scopeSnapshot != null) {
            state.putAll(scopeSnapshot.state());
        }
        return state;
    }

    private SyntheticEventGroup synthesizeConditionalRoutingEvents(ConditionalRoutingDescriptor descriptor,
                                                                   Map<String, Object> state,
                                                                   List<Map<String, Object>> invocations,
                                                                   List<Map<String, Object>> events) {
        if (descriptor.branches().isEmpty()) {
            return new SyntheticEventGroup(events.isEmpty() ? 0 : events.size(), List.of());
        }

        List<ConditionOutcome> outcomes = descriptor.branches().stream()
                .map(branch -> evaluateCondition(descriptor, branch, state, invocations, events))
                .toList();
        boolean hasResolvedOutcome = outcomes.stream().anyMatch(outcome -> outcome.matched() != null || outcome.selected());
        if (!hasResolvedOutcome) {
            return new SyntheticEventGroup(events.isEmpty() ? 0 : events.size(), List.of());
        }

        String timestamp = conditionalEventTimestamp(descriptor, events);
        ArrayList<Map<String, Object>> syntheticEvents = new ArrayList<>(outcomes.size() + 1);
        outcomes.stream()
                .map(outcome -> conditionalCheckEvent(descriptor, outcome, timestamp))
                .forEach(syntheticEvents::add);
        syntheticEvents.add(conditionalRouteEvent(descriptor, outcomes, timestamp));
        return new SyntheticEventGroup(conditionalAnchorIndex(descriptor, events), List.copyOf(syntheticEvents));
    }

    private ConditionOutcome evaluateCondition(ConditionalRoutingDescriptor descriptor,
                                               ConditionalBranchDescriptor branch,
                                               Map<String, Object> state,
                                               List<Map<String, Object>> invocations,
                                               List<Map<String, Object>> events) {
        boolean selectedByInvocation = selectedByInvocation(branch, invocations, events);
        Boolean matched = selectedByInvocation ? Boolean.TRUE : evaluateCondition(branch, state, invocations);
        return new ConditionOutcome(descriptor, branch, matched, selectedByInvocation || Boolean.TRUE.equals(matched));
    }

    private Boolean evaluateCondition(ConditionalBranchDescriptor branch,
                                      Map<String, Object> state,
                                      List<Map<String, Object>> invocations) {
        try {
            Object[] arguments = new Object[branch.activationMethod().getParameterCount()];
            SyntheticAgenticScope scope = new SyntheticAgenticScope(state, invocations);
            for (int index = 0; index < branch.activationMethod().getParameterCount(); index++) {
                Parameter parameter = branch.activationMethod().getParameters()[index];
                if (AgenticScope.class.isAssignableFrom(parameter.getType())) {
                    arguments[index] = scope;
                    continue;
                }

                String parameterName = ParameterNameResolver.name(parameter);
                if (parameterName == null || !state.containsKey(parameterName)) {
                    return null;
                }
                arguments[index] = LangChain4jDevUiJsonSupport.convert(state.get(parameterName),
                                                                       branch.activationMethod().getGenericParameterTypes()[index]);
            }
            Object evaluation = branch.activationMethod().invoke(null, arguments);
            return evaluation instanceof Boolean bool ? bool : null;
        } catch (IllegalAccessException | InvocationTargetException | RuntimeException e) {
            return null;
        }
    }

    private boolean selectedByInvocation(ConditionalBranchDescriptor branch,
                                         List<Map<String, Object>> invocations,
                                         List<Map<String, Object>> events) {
        boolean selectedByTrace = invocations.stream()
                .anyMatch(invocation -> branch.subagentTypeName().equals(invocation.get("agentType")));
        if (selectedByTrace) {
            return true;
        }
        return events.stream()
                .anyMatch(event -> branch.subagentTypeName().equals(event.get("agentType")));
    }

    private String conditionalEventTimestamp(ConditionalRoutingDescriptor descriptor, List<Map<String, Object>> events) {
        return events.stream()
                .filter(event -> conditionalRelatedEvent(descriptor, event))
                .map(event -> event.get("timestamp"))
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .findFirst()
                .orElseGet(() -> Instant.now().toString());
    }

    private int conditionalAnchorIndex(ConditionalRoutingDescriptor descriptor, List<Map<String, Object>> events) {
        for (int index = 0; index < events.size(); index++) {
            Map<String, Object> event = events.get(index);
            if (descriptor.agentTypeName().equals(event.get("agentType"))
                    && (descriptor.agentDisplayName().equals(event.get("agent"))
                        || descriptor.methodName().equals(event.get("agent")))) {
                return "before-agent".equals(event.get("type")) ? index + 1 : index;
            }
            if (descriptor.hasBranchType((String) event.get("agentType"))) {
                return index;
            }
        }
        return events.isEmpty() ? 0 : events.size();
    }

    private boolean conditionalRelatedEvent(ConditionalRoutingDescriptor descriptor, Map<String, Object> event) {
        Object eventAgentType = event.get("agentType");
        return Objects.equals(descriptor.agentTypeName(), eventAgentType)
                || (eventAgentType instanceof String typeName && descriptor.hasBranchType(typeName));
    }

    private Map<String, Object> conditionalCheckEvent(ConditionalRoutingDescriptor descriptor,
                                                      ConditionOutcome outcome,
                                                      String timestamp) {
        LinkedHashMap<String, Object> event = new LinkedHashMap<>();
        event.put("timestamp", timestamp);
        event.put("type", "conditional-check");
        event.put("agent", descriptor.agentDisplayName());
        event.put("agentType", descriptor.agentTypeName());
        event.put("method", descriptor.methodName());
        event.put("subAgent", outcome.branch().subagentName());
        event.put("subAgentType", outcome.branch().subagentTypeName());
        event.put("condition", outcome.branch().description());
        event.put("matched", outcome.matched() == null ? "unknown" : outcome.matched());
        event.put("selected", outcome.selected());
        event.put("synthetic", true);
        return event;
    }

    private Map<String, Object> conditionalRouteEvent(ConditionalRoutingDescriptor descriptor,
                                                      List<ConditionOutcome> outcomes,
                                                      String timestamp) {
        LinkedHashMap<String, Object> event = new LinkedHashMap<>();
        List<String> selectedAgents = outcomes.stream()
                .filter(ConditionOutcome::selected)
                .map(outcome -> outcome.branch().subagentName())
                .distinct()
                .toList();
        List<String> selectedAgentTypes = outcomes.stream()
                .filter(ConditionOutcome::selected)
                .map(outcome -> outcome.branch().subagentTypeName())
                .distinct()
                .toList();
        event.put("timestamp", timestamp);
        event.put("type", "conditional-route");
        event.put("agent", descriptor.agentDisplayName());
        event.put("agentType", descriptor.agentTypeName());
        event.put("method", descriptor.methodName());
        event.put("selectedAgents", selectedAgents);
        event.put("selectedAgentTypes", selectedAgentTypes);
        event.put("status", selectedAgents.isEmpty() ? "no-match" : "selected");
        event.put("synthetic", true);
        return event;
    }

    private Map<String, Object> normalizeRecordedInvocation(Map<String, Object> event) {
        LinkedHashMap<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("agentType", event.get("agentType"));
        normalized.put("agentName", event.get("agent"));
        normalized.put("agentId", event.get("agentId"));
        normalized.put("input", event.get("inputs"));

        if ("agent-error".equals(event.get("type"))) {
            LinkedHashMap<String, Object> error = new LinkedHashMap<>();
            error.put("error", event.get("error"));
            normalized.put("output", error);
        } else {
            normalized.put("output", event.get("output"));
        }
        return normalized;
    }

    private AgenticScope findScope(AgentHandle agent, Object memoryId) {
        if (agent.service() instanceof AgenticScopeAccess scopeAccess) {
            return scopeAccess.getAgenticScope(memoryId);
        }
        return null;
    }

    private Object findMemoryId(AgentMethodHandle method, String requestedMemoryId) {
        return method.memoryIdParameterIndex().map(index -> LangChain4jDevUiJsonSupport.convert(requestedMemoryId,
                                                                                                method.method()
                                                                                                        .getGenericParameterTypes()[index]))
                .orElse(null);
    }

    private Object[] bindArguments(AgentMethodHandle method, String requestedMemoryId, Map<String, Object> arguments) {
        Object[] boundArguments = new Object[method.method().getParameterCount()];
        for (int i = 0; i < method.method().getParameterCount(); i++) {
            Parameter parameter = method.method().getParameters()[i];
            ParameterDescriptor descriptor = method.parameters().get(i);

            Object rawValue;
            if (descriptor.memoryId()) {
                if (requestedMemoryId == null || requestedMemoryId.isBlank()) {
                    throw new DevUiException(Status.BAD_REQUEST_400,
                                             "Method " + method.id() + " requires a memoryId value");
                }
                rawValue = requestedMemoryId;
            } else {
                if (!arguments.containsKey(descriptor.name())) {
                    throw new DevUiException(Status.BAD_REQUEST_400,
                                             "Missing argument '" + descriptor.name() + "' for method " + method.id());
                }
                rawValue = arguments.get(descriptor.name());
            }

            try {
                boundArguments[i] = LangChain4jDevUiJsonSupport.convert(rawValue, descriptor.type());
            } catch (RuntimeException e) {
                throw new DevUiException(Status.BAD_REQUEST_400,
                                         "Cannot convert argument '" + descriptor.name() + "' to "
                                                 + parameter.getType().getTypeName(),
                                         e);
            }
        }
        return boundArguments;
    }

    private Map<String, Object> bindState(AgentMethodHandle method, Map<String, Object> state) {
        LinkedHashMap<String, Object> boundState = new LinkedHashMap<>();
        state.forEach((name, rawValue) -> {
            Type targetType = method.stateParameter(name)
                    .map(ParameterDescriptor::type)
                    .orElse(Object.class);
            try {
                boundState.put(name, LangChain4jDevUiJsonSupport.convert(rawValue, targetType));
            } catch (RuntimeException e) {
                throw new DevUiException(Status.BAD_REQUEST_400,
                                         "Cannot convert state '" + name + "' to " + targetType.getTypeName(),
                                         e);
            }
        });
        return boundState;
    }

    private PreparedInvocation prepareInvocation(AgentHandle agent,
                                                 AgentMethodHandle method,
                                                 String requestedMemoryId,
                                                 Map<String, Object> initialState) {
        if (initialState.isEmpty()) {
            return new PreparedInvocation(agent.service(), null);
        }
        if (!(agent.service() instanceof AgenticScopeOwner scopeOwner)) {
            throw new DevUiException(Status.BAD_REQUEST_400,
                                     "Agent " + agent.name() + " does not support injected agentic state");
        }

        Object memoryId = findMemoryId(method, requestedMemoryId);
        AgenticScopeRegistry registry = scopeOwner.registry();
        DefaultAgenticScope scope = memoryId == null
                ? registry.createEphemeralAgenticScope()
                : Optional.ofNullable(registry.get(memoryId)).orElseGet(() -> registry.create(memoryId));
        scope.writeStates(initialState);
        return new PreparedInvocation(scopeOwner.withAgenticScope(scope), scope);
    }

    private Map<String, Object> normalizeInput(AgentMethodHandle method,
                                               Object[] invocationArguments,
                                               Map<String, Object> initialState) {
        LinkedHashMap<String, Object> normalized = new LinkedHashMap<>();
        for (int i = 0; i < invocationArguments.length; i++) {
            normalized.put(method.parameters().get(i).name(), LangChain4jDevUiJsonSupport.normalize(invocationArguments[i]));
        }
        if (!initialState.isEmpty()) {
            normalized.put("agenticState", LangChain4jDevUiJsonSupport.normalize(initialState));
        }
        return normalized;
    }

    private Map<String, Object> normalizeState(AgenticScope scope) {
        @SuppressWarnings("unchecked")
        Map<String, Object> normalized = (Map<String, Object>) LangChain4jDevUiJsonSupport.normalize(scope.state());
        return normalized;
    }

    private List<Map<String, Object>> normalizeInvocations(AgenticScope scope) {
        return scope.agentInvocations().stream()
                .map(this::normalizeInvocation)
                .toList();
    }

    private Map<String, Object> normalizeInvocation(AgentInvocation invocation) {
        LinkedHashMap<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("agentType", invocation.agentType().getName());
        normalized.put("agentName", invocation.agentName());
        normalized.put("agentId", invocation.agentId());
        normalized.put("input", LangChain4jDevUiJsonSupport.normalize(invocation.input()));
        normalized.put("output", LangChain4jDevUiJsonSupport.normalize(invocation.output()));
        return normalized;
    }

    private AgentHandle agent(String agentName) {
        AgentHandle agent = agents.get(agentName);
        if (agent == null) {
            throw new DevUiException(Status.NOT_FOUND_404, "Agent " + agentName + " not found");
        }
        return agent;
    }

    private String pathParameter(ServerRequest req, String name) {
        return req.path().pathParameters().get(name);
    }

    private void cleanupInvocationJobs() {
        Instant retentionCutoff = Instant.now().minus(INVOCATION_JOB_RETENTION);
        invocationJobs.entrySet().removeIf(entry -> entry.getValue().terminal()
                && entry.getValue().lastUpdated().isBefore(retentionCutoff));

        int overflow = invocationJobs.size() - MAX_INVOCATION_JOBS;
        if (overflow <= 0) {
            return;
        }

        invocationJobs.values().stream()
                .filter(InvocationJob::terminal)
                .sorted((left, right) -> left.lastUpdated().compareTo(right.lastUpdated()))
                .limit(overflow)
                .map(InvocationJob::id)
                .forEach(invocationJobs::remove);
    }

    private void json(ServerResponse res, Object entity) {
        res.headers().contentType(MediaTypes.APPLICATION_JSON);
        res.send(LangChain4jDevUiJsonSupport.write(entity));
    }

    private void error(ServerResponse res, Status status, String message) {
        res.status(status);
        json(res, Map.of("error", message, "status", status.code()));
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getName() : current.getMessage();
    }

    private boolean hasCause(Throwable throwable, Class<? extends Throwable> expectedType) {
        Throwable current = throwable;
        while (current != null) {
            if (expectedType.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private Map<String, AgentHandle> discoverAgents() {
        LinkedHashMap<String, AgentHandle> discovered = new LinkedHashMap<>();
        registry.all(AgentMetadata.class)
                .stream()
                .sorted(Comparator.comparing(AgentMetadata::agentName))
                .forEach(metadata -> discovered.put(metadata.agentName(), new AgentHandle(metadata, registry)));
        return discovered;
    }

    private static boolean isInvocable(Method method) {
        if (Modifier.isStatic(method.getModifiers())) {
            return false;
        }
        if (method.isDefault()) {
            return false;
        }
        if (method.getDeclaringClass() == Object.class) {
            return false;
        }
        return method.getDeclaringClass() != AgenticScopeAccess.class;
    }

    private static String methodId(Method method) {
        String signature = Arrays.stream(method.getParameterTypes())
                .map(Class::getSimpleName)
                .reduce((left, right) -> left + "," + right)
                .orElse("");
        return method.getName() + "(" + signature + ")";
    }

    private static Map<String, Object> descriptorView(AgentDescriptor descriptor) {
        LinkedHashMap<String, Object> view = new LinkedHashMap<>();
        view.put("name", descriptor.name());
        view.put("interfaceName", descriptor.interfaceName());
        view.put("description", descriptor.description());
        view.put("methods", descriptor.methods().stream().map(LangChain4jDevUi::methodView).toList());
        return view;
    }

    private static Map<String, Object> methodView(MethodDescriptor descriptor) {
        LinkedHashMap<String, Object> view = new LinkedHashMap<>();
        view.put("id", descriptor.id());
        view.put("name", descriptor.name());
        view.put("returnType", descriptor.returnType());
        view.put("chatLike", descriptor.chatLike());
        view.put("parameters", descriptor.parameters().stream().map(LangChain4jDevUi::parameterView).toList());
        view.put("stateParameters", descriptor.stateParameters().stream().map(LangChain4jDevUi::parameterView).toList());
        return view;
    }

    private static Map<String, Object> parameterView(ParameterDescriptor descriptor) {
        LinkedHashMap<String, Object> view = new LinkedHashMap<>();
        view.put("name", descriptor.name());
        view.put("typeName", descriptor.typeName());
        view.put("memoryId", descriptor.memoryId());
        view.put("simpleText", descriptor.simpleText());
        view.put("enumValues", descriptor.enumValues());
        return view;
    }

    private static ParameterDescriptor parameterDescriptor(Parameter parameter, Type parameterType, int index) {
        V v = parameter.getAnnotation(V.class);
        String name = v != null ? v.value() : parameter.isAnnotationPresent(MemoryId.class) ? "memoryId" : "arg" + index;
        Class<?> rawType = parameter.getType();
        return new ParameterDescriptor(name,
                                       parameterType,
                                       rawType.getTypeName(),
                                       parameter.isAnnotationPresent(MemoryId.class),
                                       isSimpleFormType(rawType),
                                       enumValues(rawType));
    }

    private static List<ParameterDescriptor> stateParameters(Class<?> agentType,
                                                             Method method,
                                                             List<ParameterDescriptor> parameters) {
        LinkedHashMap<String, ParameterDescriptor> discovered = new LinkedHashMap<>();
        collectStateParameters(agentType, method, discovered, new HashSet<>());

        parameters.stream()
                .map(ParameterDescriptor::name)
                .forEach(discovered::remove);
        return List.copyOf(discovered.values());
    }

    private static void collectStateParameters(Class<?> agentType,
                                               Method method,
                                               Map<String, ParameterDescriptor> discovered,
                                               Set<String> visited) {
        String visitKey = agentType.getName() + "#" + methodId(method);
        if (!visited.add(visitKey)) {
            return;
        }

        if (method.isAnnotationPresent(ConditionalAgent.class)) {
            collectConditionalStateParameters(agentType, discovered);
        }
        if (method.isAnnotationPresent(SequenceAgent.class)) {
            SequenceAgent annotation = method.getAnnotation(SequenceAgent.class);
            for (Class<?> subagentType : annotation.subAgents()) {
                collectNestedWorkflowStateParameters(subagentType, discovered, visited);
            }
        }
    }

    private static void collectNestedWorkflowStateParameters(Class<?> agentType,
                                                             Map<String, ParameterDescriptor> discovered,
                                                             Set<String> visited) {
        Arrays.stream(agentType.getMethods())
                .filter(LangChain4jDevUi::isInvocable)
                .forEach(method -> collectStateParameters(agentType, method, discovered, visited));
    }

    private static void collectConditionalStateParameters(Class<?> agentType,
                                                          Map<String, ParameterDescriptor> discovered) {
        Arrays.stream(agentType.getMethods())
                .filter(candidate -> Modifier.isStatic(candidate.getModifiers()))
                .filter(candidate -> candidate.isAnnotationPresent(ActivationCondition.class))
                .forEach(candidate -> IntStream.range(0, candidate.getParameterCount())
                        .mapToObj(index -> stateParameterDescriptor(candidate.getParameters()[index],
                                                                   candidate.getGenericParameterTypes()[index],
                                                                   index))
                        .filter(Objects::nonNull)
                        .forEach(descriptor -> discovered.putIfAbsent(descriptor.name(), descriptor)));
    }

    private static ParameterDescriptor stateParameterDescriptor(Parameter parameter, Type parameterType, int index) {
        if (AgenticScope.class.isAssignableFrom(parameter.getType())) {
            return null;
        }
        ParameterDescriptor descriptor = parameterDescriptor(parameter, parameterType, index);
        return descriptor.memoryId() ? null : descriptor;
    }

    private static boolean isSimpleFormType(Class<?> rawType) {
        return rawType == String.class
                || rawType.isPrimitive()
                || rawType.isEnum()
                || Number.class.isAssignableFrom(rawType)
                || rawType == Boolean.class
                || rawType == Character.class;
    }

    private static List<String> enumValues(Class<?> rawType) {
        if (!rawType.isEnum()) {
            return List.of();
        }
        return Arrays.stream(rawType.getEnumConstants())
                .map(Enum.class::cast)
                .map(Enum::name)
                .toList();
    }

    private record ConditionalRoutingDescriptor(String agentTypeName,
                                                String methodName,
                                                String agentDisplayName,
                                                List<ConditionalBranchDescriptor> branches) {
        private String key() {
            return agentTypeName + "#" + methodName;
        }

        private boolean hasBranchType(String agentTypeName) {
            return branches.stream().anyMatch(branch -> branch.subagentTypeName().equals(agentTypeName));
        }
    }

    private record ConditionalBranchDescriptor(Method activationMethod,
                                               String description,
                                               String subagentTypeName,
                                               String subagentName) {
    }

    private record ConditionOutcome(ConditionalRoutingDescriptor descriptor,
                                    ConditionalBranchDescriptor branch,
                                    Boolean matched,
                                    boolean selected) {
    }

    private record SyntheticEventGroup(int anchorIndex, List<Map<String, Object>> events) {
    }

    private static final class SyntheticAgenticScope implements AgenticScope {
        private final Map<String, Object> state;
        private final List<AgentInvocation> invocations;

        private SyntheticAgenticScope(Map<String, Object> state, List<Map<String, Object>> normalizedInvocations) {
            this.state = new HashMap<>(state);
            this.invocations = normalizedInvocations.stream()
                    .map(SyntheticAgenticScope::toAgentInvocation)
                    .toList();
        }

        @Override
        public Object memoryId() {
            return null;
        }

        @Override
        public void writeState(String key, Object value) {
            if (value == null) {
                state.remove(key);
            } else {
                state.put(key, value);
            }
        }

        @Override
        public <T> void writeState(Class<? extends TypedKey<T>> key, T value) {
            writeState(key.getSimpleName(), value);
        }

        @Override
        public void writeStates(Map<String, Object> newState) {
            state.putAll(newState);
        }

        @Override
        public boolean hasState(String key) {
            return state.containsKey(key);
        }

        @Override
        public boolean hasState(Class<? extends TypedKey<?>> key) {
            return hasState(key.getSimpleName());
        }

        @Override
        public Object readState(String key) {
            return state.get(key);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T readState(String key, T defaultValue) {
            Object value = state.get(key);
            if (value == null) {
                return defaultValue;
            }
            if (defaultValue == null) {
                return (T) value;
            }
            try {
                return (T) LangChain4jDevUiJsonSupport.convert(value, defaultValue.getClass());
            } catch (RuntimeException e) {
                return (T) value;
            }
        }

        @Override
        public <T> T readState(Class<? extends TypedKey<T>> key) {
            return readState(key.getSimpleName(), null);
        }

        @Override
        public Map<String, Object> state() {
            return Collections.unmodifiableMap(state);
        }

        @Override
        public String contextAsConversation(String... agentNames) {
            return "";
        }

        @Override
        public String contextAsConversation(Object... agents) {
            return "";
        }

        @Override
        public List<AgentInvocation> agentInvocations() {
            return invocations;
        }

        @Override
        public List<AgentInvocation> agentInvocations(String agentName) {
            return invocations.stream()
                    .filter(invocation -> invocation.agentName().equals(agentName))
                    .toList();
        }

        @Override
        public List<AgentInvocation> agentInvocations(Class<?> agentType) {
            return invocations.stream()
                    .filter(invocation -> invocation.agentType().equals(agentType))
                    .toList();
        }

        @SuppressWarnings("unchecked")
        private static AgentInvocation toAgentInvocation(Map<String, Object> normalizedInvocation) {
            Class<?> agentType = normalizedInvocation.get("agentType") instanceof String agentTypeName
                    ? resolveClass(agentTypeName)
                    : Object.class;
            Map<String, Object> input = normalizedInvocation.get("input") instanceof Map<?, ?> normalizedInput
                    ? (Map<String, Object>) normalizedInput
                    : Map.of();
            return new AgentInvocation(agentType,
                                       String.valueOf(normalizedInvocation.getOrDefault("agentName", "agent")),
                                       String.valueOf(normalizedInvocation.getOrDefault("agentId", "")),
                                       input,
                                       normalizedInvocation.get("output"));
        }

        private static Class<?> resolveClass(String typeName) {
            try {
                return Class.forName(typeName);
            } catch (ClassNotFoundException e) {
                return Object.class;
            }
        }
    }

    private static final class AgentHandle {
        private final Class<?> agentType;
        private final AgentDescriptor descriptor;
        private final Object service;
        private final Map<String, AgentMethodHandle> methods;

        private AgentHandle(AgentMetadata metadata, ServiceRegistry registry) {
            this.agentType = metadata.agentClass();
            this.descriptor = new AgentDescriptor(metadata.agentName(),
                                                  metadata.agentClass().getName(),
                                                  metadata.buildTimeConfig().description().orElse(null),
                                                  methods(metadata.agentClass()));
            this.service = registry.get(metadata.agentClass());
            this.methods = this.descriptor.methods().stream()
                    .collect(java.util.stream.Collectors.toMap(MethodDescriptor::id,
                                                               descriptor -> new AgentMethodHandle(
                                                                       findMethod(metadata.agentClass(), descriptor.id()),
                                                                       descriptor.parameters(),
                                                                       descriptor.stateParameters())));
        }

        private static List<MethodDescriptor> methods(Class<?> agentType) {
            return Arrays.stream(agentType.getMethods())
                    .filter(LangChain4jDevUi::isInvocable)
                    .sorted(Comparator.comparing(LangChain4jDevUi::methodId))
                    .map(method -> {
                        List<ParameterDescriptor> parameters = IntStream.range(0, method.getParameterCount())
                                .mapToObj(index -> parameterDescriptor(method.getParameters()[index],
                                                                       method.getGenericParameterTypes()[index],
                                                                       index))
                                .toList();
                        List<ParameterDescriptor> stateParameters = stateParameters(agentType, method, parameters);
                        long nonMemoryParameters = parameters.stream().filter(parameter -> !parameter.memoryId()).count();
                        boolean chatLike = nonMemoryParameters == 1
                                && parameters.stream().filter(parameter -> !parameter.memoryId()).findFirst()
                                .map(parameter -> parameter.type() == String.class)
                                .orElse(false);
                        return new MethodDescriptor(methodId(method),
                                                    method.getName(),
                                                    method.getGenericReturnType().getTypeName(),
                                                    chatLike,
                                                    parameters,
                                                    stateParameters);
                    })
                    .toList();
        }

        private static Method findMethod(Class<?> agentType, String methodId) {
            return Arrays.stream(agentType.getMethods())
                    .filter(LangChain4jDevUi::isInvocable)
                    .filter(method -> methodId(method).equals(methodId))
                    .findFirst()
                    .orElseThrow();
        }

        private String name() {
            return descriptor.name();
        }

        private Class<?> agentType() {
            return agentType;
        }

        private Map<String, Object> apiView() {
            return descriptorView(descriptor);
        }

        private Object service() {
            return service;
        }

        private AgentMethodHandle method(String methodId) {
            AgentMethodHandle method = methods.get(methodId);
            if (method == null) {
                throw new DevUiException(Status.NOT_FOUND_404,
                                         "Method " + methodId + " not found on agent " + name());
            }
            return method;
        }
    }

    private record AgentMethodHandle(Method method,
                                     List<ParameterDescriptor> parameters,
                                     List<ParameterDescriptor> stateParameters) {
        private String id() {
            return methodId(method);
        }

        private Optional<Integer> memoryIdParameterIndex() {
            return IntStream.range(0, parameters.size())
                    .filter(index -> parameters.get(index).memoryId())
                    .boxed()
                    .findFirst();
        }

        private Optional<ParameterDescriptor> stateParameter(String name) {
            return stateParameters.stream()
                    .filter(parameter -> parameter.name().equals(name))
                    .findFirst();
        }
    }

    private record AgentDescriptor(String name, String interfaceName, String description, List<MethodDescriptor> methods) {
    }

    private record MethodDescriptor(String id,
                                    String name,
                                    String returnType,
                                    boolean chatLike,
                                    List<ParameterDescriptor> parameters,
                                    List<ParameterDescriptor> stateParameters) {
    }

    private record ParameterDescriptor(String name,
                                       Type type,
                                       String typeName,
                                       boolean memoryId,
                                       boolean simpleText,
                                       List<String> enumValues) {
    }

    public record InvokeRequest(String agent,
                                String method,
                                String memoryId,
                                Map<String, Object> arguments,
                                Map<String, Object> state) {
    }

    private record ResolvedInvokeRequest(AgentHandle agent,
                                         AgentMethodHandle method,
                                         String memoryId,
                                         Map<String, Object> arguments,
                                         Map<String, Object> state) {
    }

    private record InvocationResult(Object result, Map<String, Object> inspection) {
    }

    private record PreparedInvocation(Object target, DefaultAgenticScope scope) {
    }

    private static final class InvocationJob {
        private final String id;
        private final String agent;
        private final String method;
        private final Instant createdAt = Instant.now();

        private volatile Instant lastUpdated = createdAt;
        private volatile String status = "running";
        private volatile Object result;
        private volatile Map<String, Object> inspection;
        private volatile String error;
        private volatile int statusCode = Status.ACCEPTED_202.code();

        private InvocationJob(String id, String agent, String method) {
            this.id = id;
            this.agent = agent;
            this.method = method;
        }

        private String id() {
            return id;
        }

        private Instant lastUpdated() {
            return lastUpdated;
        }

        private boolean terminal() {
            return !"running".equals(status);
        }

        private void progress(Map<String, Object> inspection) {
            this.inspection = inspection == null ? null : new LinkedHashMap<>(inspection);
            this.lastUpdated = Instant.now();
        }

        private void complete(InvocationResult result) {
            this.result = result.result();
            this.inspection = result.inspection() == null ? null : new LinkedHashMap<>(result.inspection());
            this.status = "completed";
            this.statusCode = Status.OK_200.code();
            this.lastUpdated = Instant.now();
        }

        private void fail(Status status, String error) {
            this.status = "failed";
            this.statusCode = status.code();
            this.error = error;
            this.lastUpdated = Instant.now();
        }

        private Map<String, Object> snapshot() {
            LinkedHashMap<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("invocationId", id);
            snapshot.put("agent", agent);
            snapshot.put("method", method);
            snapshot.put("status", status);
            snapshot.put("statusCode", statusCode);
            snapshot.put("createdAt", createdAt.toString());
            snapshot.put("lastUpdated", lastUpdated.toString());
            snapshot.put("result", result);
            if (inspection != null) {
                snapshot.put("inspection", inspection);
            }
            if (error != null) {
                snapshot.put("error", error);
            }
            return snapshot;
        }
    }

    private static class DevUiException extends RuntimeException {
        private final Status status;

        private DevUiException(Status status, String message) {
            super(message);
            this.status = status;
        }

        private DevUiException(Status status, String message, Throwable cause) {
            super(message, cause);
            this.status = status;
        }

        private Status status() {
            return status;
        }
    }
}
