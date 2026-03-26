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

import java.util.List;

import io.helidon.config.Config;
import io.helidon.service.registry.ServiceRegistry;

import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.agentic.agent.AgentBuilder;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static io.helidon.common.media.type.MediaTypes.APPLICATION_YAML;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentsConfigTest {

    @Test
    void testOutputGuardrailsConfigFromYaml() {
        //language=YAML
        var cfg = """
                langchain4j:
                  agents:
                    chess-opponent:
                      output-guardrails-config:
                        max-retries: 7
                """;

        var agentsConfig = AgentsConfig.create(Config.just(cfg, APPLICATION_YAML)
                                                       .get("langchain4j")
                                                       .get("agents")
                                                       .get("chess-opponent"));

        assertThat(agentsConfig.outputGuardrailsConfig().isPresent(), is(true));
        assertThat(agentsConfig.outputGuardrailsConfig().orElseThrow().maxRetries(), is(7));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void testConfigureAppliesOutputGuardrailsConfig() {
        //language=YAML
        var cfg = """
                langchain4j:
                  agents:
                    chess-opponent:
                      output-guardrails-config:
                        max-retries: 7
                """;

        var agentsConfig = AgentsConfig.create(Config.just(cfg, APPLICATION_YAML)
                                                       .get("langchain4j")
                                                       .get("agents")
                                                       .get("chess-opponent"));
        var serviceRegistry = mock(ServiceRegistry.class);
        when(serviceRegistry.all(AgentBuilderCustomizer.class)).thenReturn(List.of());

        AgentBuilder agentBuilder = mock(AgentBuilder.class);
        AgenticServices.DeclarativeAgentCreationContext<?> ctx =
                mock(AgenticServices.DeclarativeAgentCreationContext.class);
        when(ctx.agentBuilder()).thenReturn(agentBuilder);

        AgentsConfigSupport.configure(agentsConfig, ctx, serviceRegistry);

        var configCaptor = ArgumentCaptor.forClass(dev.langchain4j.guardrail.config.OutputGuardrailsConfig.class);
        verify(agentBuilder).outputGuardrailsConfig(configCaptor.capture());
        assertThat(configCaptor.getValue().maxRetries(), is(7));
    }
}
