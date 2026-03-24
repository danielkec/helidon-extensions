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

package io.helidon.extensions.langchain4j;

import io.helidon.service.registry.ServiceRegistry;

import dev.langchain4j.agentic.AgenticServices;

/**
 * Extension point for customizing declarative agent builders before the agent is created.
 * <p>
 * Implementations can attach listeners, monitors, or other builder-level integrations that
 * should apply consistently to Helidon-managed LangChain4j agents.
 */
public interface AgentBuilderCustomizer {
    /**
     * Customize the builder provided by the declarative agent creation context.
     *
     * @param ctx declarative agent creation context
     * @param serviceRegistry service registry backing the current application
     */
    void customize(AgenticServices.DeclarativeAgentCreationContext<?> ctx, ServiceRegistry serviceRegistry);
}
