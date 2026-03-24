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

import io.helidon.extensions.langchain4j.AgentBuilderCustomizer;
import io.helidon.service.registry.Service;
import io.helidon.service.registry.ServiceRegistry;

import dev.langchain4j.agentic.AgenticServices;

@Service.Singleton
final class LangChain4jDevUiAgentBuilderCustomizer implements AgentBuilderCustomizer {
    private final LangChain4jDevUiRecorder recorder;

    @Service.Inject
    LangChain4jDevUiAgentBuilderCustomizer(LangChain4jDevUiRecorder recorder) {
        this.recorder = recorder;
    }

    @Override
    public void customize(AgenticServices.DeclarativeAgentCreationContext<?> ctx, ServiceRegistry serviceRegistry) {
        ctx.agentBuilder().listener(recorder);
    }
}
