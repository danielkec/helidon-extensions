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
import io.helidon.common.features.api.Features;
import io.helidon.common.features.api.HelidonFlavor;
import io.helidon.common.features.api.Preview;

/**
 * Browser-based development UI for Helidon LangChain4j agents.
 */
@Features.Name("LangChain4j Dev UI")
@Features.Description("Browser UI for interactive LangChain4j agent development and debugging")
@Features.Flavor(HelidonFlavor.SE)
@Features.Preview
module io.helidon.extensions.langchain4j.webui {
    requires static io.helidon.common.features.api;
    requires static io.helidon.config.metadata;

    requires io.helidon.builder.api;
    requires io.helidon.common;
    requires io.helidon.common.media.type;
    requires io.helidon.config;
    requires io.helidon.extensions.langchain4j;
    requires io.helidon.service.registry;
    requires io.helidon.webserver;
    requires io.helidon.webserver.staticcontent;
    requires jakarta.json.bind;
    requires langchain4j;
    requires langchain4j.agentic;
    requires org.eclipse.yasson;

    provides io.helidon.webserver.spi.ServerFeatureProvider
            with io.helidon.extensions.langchain4j.webui.LangChain4jDevUiFeatureProvider;

    exports io.helidon.extensions.langchain4j.webui;
}
