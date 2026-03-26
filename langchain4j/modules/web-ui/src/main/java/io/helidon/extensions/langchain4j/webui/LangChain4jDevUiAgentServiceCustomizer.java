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

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;

import io.helidon.extensions.langchain4j.Ai;
import io.helidon.extensions.langchain4j.AgentServiceCustomizer;
import io.helidon.service.registry.Service;
import io.helidon.service.registry.ServiceRegistry;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.agentic.declarative.ConditionalAgent;
import dev.langchain4j.agentic.declarative.LoopAgent;
import dev.langchain4j.agentic.declarative.ParallelAgent;
import dev.langchain4j.agentic.declarative.ParallelMapperAgent;
import dev.langchain4j.agentic.declarative.PlannerAgent;
import dev.langchain4j.agentic.declarative.SequenceAgent;
import dev.langchain4j.agentic.declarative.SupervisorAgent;
import dev.langchain4j.agentic.planner.AgentInstance;
import dev.langchain4j.agentic.scope.AgenticScope;
import dev.langchain4j.agentic.scope.AgenticScopeAccess;
import dev.langchain4j.agentic.scope.ResultWithAgenticScope;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.V;

@Service.Singleton
final class LangChain4jDevUiAgentServiceCustomizer implements AgentServiceCustomizer {
    private final LangChain4jDevUiRecorder recorder;

    @Service.Inject
    LangChain4jDevUiAgentServiceCustomizer(LangChain4jDevUiRecorder recorder) {
        this.recorder = recorder;
    }

    @Override
    public <T> T customize(Class<T> agentServiceClass, T agent, ServiceRegistry serviceRegistry) {
        if (agent == null || !Proxy.isProxyClass(agent.getClass())) {
            return agent;
        }

        InvocationHandler currentHandler = Proxy.getInvocationHandler(agent);
        if (currentHandler instanceof TrackingInvocationHandler) {
            return agent;
        }

        Object wrapped = Proxy.newProxyInstance(agent.getClass().getClassLoader(),
                                                agent.getClass().getInterfaces(),
                                                new TrackingInvocationHandler(agent,
                                                                             rootAgentName(agentServiceClass),
                                                                             recorder));
        return agentServiceClass.cast(wrapped);
    }

    private static final class TrackingInvocationHandler implements InvocationHandler {
        private final Object delegate;
        private final LangChain4jDevUiRecorder recorder;
        private final AgentInstance trackedAgent;

        private TrackingInvocationHandler(Object delegate,
                                          String rootAgentName,
                                          LangChain4jDevUiRecorder recorder) {
            this.delegate = delegate;
            this.recorder = recorder;
            this.trackedAgent = delegate instanceof AgentInstance agentInstance
                    ? new NamedAgentInstance(agentInstance, rootAgentName)
                    : null;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (!trackable(method) || trackedAgent == null) {
                return invokeDelegate(method, args);
            }

            Map<String, Object> inputs = invocationInputs(method, args);
            AgenticScope initialScope = resolveScope(delegate, method, args, null);
            recorder.onSyntheticRootAgentStarted(trackedAgent, inputs, initialScope);
            try {
                Object result = invokeDelegate(method, args);
                AgenticScope scope = resolveScope(delegate, method, args, result);
                recorder.onSyntheticRootAgentCompleted(trackedAgent, inputs, syntheticOutput(result), scope);
                return result;
            } catch (Throwable throwable) {
                Throwable cause = unwrap(throwable);
                AgenticScope scope = resolveScope(delegate, method, args, null);
                recorder.onSyntheticRootAgentError(trackedAgent, inputs, cause, scope);
                throw cause;
            }
        }

        private Object invokeDelegate(Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(delegate, args);
            } catch (InvocationTargetException exception) {
                throw exception.getCause() == null ? exception : exception.getCause();
            }
        }
    }

    private static boolean trackable(Method method) {
        return method.isAnnotationPresent(Agent.class)
                || method.isAnnotationPresent(SequenceAgent.class)
                || method.isAnnotationPresent(LoopAgent.class)
                || method.isAnnotationPresent(ConditionalAgent.class)
                || method.isAnnotationPresent(ParallelAgent.class)
                || method.isAnnotationPresent(ParallelMapperAgent.class)
                || method.isAnnotationPresent(PlannerAgent.class)
                || method.isAnnotationPresent(SupervisorAgent.class);
    }

    private static Map<String, Object> invocationInputs(Method method, Object[] args) {
        if (args == null || args.length == 0) {
            return Map.of();
        }

        LinkedHashMap<String, Object> inputs = new LinkedHashMap<>();
        Parameter[] parameters = method.getParameters();
        for (int index = 0; index < parameters.length && index < args.length; index++) {
            inputs.put(parameterName(parameters[index], index), args[index]);
        }
        return inputs;
    }

    private static String parameterName(Parameter parameter, int index) {
        V variable = parameter.getAnnotation(V.class);
        if (variable != null && !variable.value().isBlank()) {
            return variable.value();
        }

        if (parameter.isAnnotationPresent(MemoryId.class)) {
            return parameter.isNamePresent() ? parameter.getName() : "memoryId";
        }

        return parameter.isNamePresent() ? parameter.getName() : "arg" + index;
    }

    private static Object syntheticOutput(Object result) {
        if (result instanceof ResultWithAgenticScope<?> scopedResult) {
            return scopedResult.result();
        }
        return result;
    }

    private static AgenticScope resolveScope(Object agent, Method method, Object[] args, Object result) {
        if (result instanceof ResultWithAgenticScope<?> scopedResult) {
            return scopedResult.agenticScope();
        }

        if (!(agent instanceof AgenticScopeAccess scopeAccess)) {
            return null;
        }

        if (method == null || args == null) {
            return null;
        }

        Object memoryId = memoryId(method, args);
        if (memoryId == null && !hasMemoryIdParameter(method)) {
            return null;
        }
        return scopeAccess.getAgenticScope(memoryId);
    }

    private static boolean hasMemoryIdParameter(Method method) {
        for (Parameter parameter : method.getParameters()) {
            if (parameter.isAnnotationPresent(MemoryId.class)) {
                return true;
            }
        }
        return false;
    }

    private static Object memoryId(Method method, Object[] args) {
        Parameter[] parameters = method.getParameters();
        for (int index = 0; index < parameters.length && index < args.length; index++) {
            if (parameters[index].isAnnotationPresent(MemoryId.class)) {
                return args[index];
            }
        }
        return null;
    }

    private static Throwable unwrap(Throwable throwable) {
        if (throwable instanceof InvocationTargetException invocationTargetException
                && invocationTargetException.getCause() != null) {
            return invocationTargetException.getCause();
        }
        return throwable;
    }

    private static String rootAgentName(Class<?> agentServiceClass) {
        Ai.Agent aiAgent = agentServiceClass.getAnnotation(Ai.Agent.class);
        if (aiAgent != null && !aiAgent.value().isBlank()) {
            return aiAgent.value();
        }
        return agentServiceClass.getSimpleName();
    }

    private record NamedAgentInstance(AgentInstance delegate, String name) implements AgentInstance {
        @Override
        public Class<?> type() {
            return delegate.type();
        }

        @Override
        public Class<? extends dev.langchain4j.agentic.planner.Planner> plannerType() {
            return delegate.plannerType();
        }

        @Override
        public String agentId() {
            return delegate.agentId();
        }

        @Override
        public String description() {
            return delegate.description();
        }

        @Override
        public java.lang.reflect.Type outputType() {
            return delegate.outputType();
        }

        @Override
        public String outputKey() {
            return delegate.outputKey();
        }

        @Override
        public boolean async() {
            return delegate.async();
        }

        @Override
        public java.util.List<dev.langchain4j.agentic.planner.AgentArgument> arguments() {
            return delegate.arguments();
        }

        @Override
        public AgentInstance parent() {
            return delegate.parent();
        }

        @Override
        public java.util.List<AgentInstance> subagents() {
            return delegate.subagents();
        }

        @Override
        public dev.langchain4j.agentic.planner.AgenticSystemTopology topology() {
            return delegate.topology();
        }

        @Override
        public <T extends AgentInstance> T as(Class<T> agentInstanceClass) {
            return delegate.as(agentInstanceClass);
        }
    }
}
