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

/**
 * Utility for applying declarative agent service customizers.
 */
public final class AgentServiceCustomizers {
    private AgentServiceCustomizers() {
    }

    /**
     * Apply all discovered declarative agent service customizers in registry order.
     *
     * @param agentServiceClass declarative agent service contract
     * @param agent agent instance created for the current application
     * @param serviceRegistry service registry backing the current application
     * @param <T> service type
     * @return customized agent instance
     */
    public static <T> T customize(Class<T> agentServiceClass, T agent, ServiceRegistry serviceRegistry) {
        T customized = agent;
        for (AgentServiceCustomizer customizer : serviceRegistry.all(AgentServiceCustomizer.class)) {
            customized = customizer.customize(agentServiceClass, customized, serviceRegistry);
        }
        return customized;
    }
}
