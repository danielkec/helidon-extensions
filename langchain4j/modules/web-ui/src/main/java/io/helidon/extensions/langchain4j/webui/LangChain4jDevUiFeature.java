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

import java.util.Objects;
import java.util.function.Consumer;

import io.helidon.builder.api.RuntimeType;
import io.helidon.common.Weighted;
import io.helidon.webserver.WebServer;
import io.helidon.webserver.http.HttpFeature;
import io.helidon.webserver.http.HttpRouting;
import io.helidon.webserver.spi.ServerFeature;

/**
 * Declarative webserver feature that auto-registers {@link LangChain4jDevUi}.
 */
public final class LangChain4jDevUiFeature implements ServerFeature, Weighted, RuntimeType.Api<LangChain4jDevUiConfig> {
    /**
     * Weight aligned with Helidon's auxiliary browser tooling routes.
     */
    public static final double WEIGHT = 90;

    private final String name;
    private final LangChain4jDevUiConfig config;

    private LangChain4jDevUiFeature(String name, LangChain4jDevUiConfig config) {
        this.name = Objects.requireNonNull(name);
        this.config = Objects.requireNonNull(config);
    }

    /**
     * Create a feature using the default feature name.
     *
     * @param config typed configuration
     * @return new feature instance
     */
    public static LangChain4jDevUiFeature create(LangChain4jDevUiConfig config) {
        return new LangChain4jDevUiFeature(LangChain4jDevUi.CONFIG_KEY, config);
    }

    /**
     * Create a feature using the provided feature name.
     *
     * @param name feature name
     * @param config typed configuration
     * @return new feature instance
     */
    public static LangChain4jDevUiFeature create(String name, LangChain4jDevUiConfig config) {
        return new LangChain4jDevUiFeature(name, config);
    }

    /**
     * Returns a new builder for the shared UI configuration.
     *
     * @return config builder
     */
    public static LangChain4jDevUiConfig.Builder builder() {
        return LangChain4jDevUiConfig.builder();
    }

    /**
     * Create a feature customizing the shared UI configuration.
     *
     * @param consumer builder customizer
     * @return new feature instance
     */
    public static LangChain4jDevUiFeature create(Consumer<LangChain4jDevUiConfig.Builder> consumer) {
        return create(builder().update(consumer).buildPrototype());
    }

    @Override
    public void setup(ServerFeatureContext featureContext) {
        if (!config.isEnabled()) {
            return;
        }

        featureContext.socket(WebServer.DEFAULT_SOCKET_NAME)
                .httpRouting()
                .addFeature(new LangChain4jDevUiRoutingFeature(config));
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String type() {
        return LangChain4jDevUi.CONFIG_KEY;
    }

    @Override
    public double weight() {
        return WEIGHT;
    }

    @Override
    public LangChain4jDevUiConfig prototype() {
        return config;
    }

    private static final class LangChain4jDevUiRoutingFeature implements HttpFeature, Weighted {
        private final LangChain4jDevUiConfig config;

        private LangChain4jDevUiRoutingFeature(LangChain4jDevUiConfig config) {
            this.config = config;
        }

        @Override
        public void setup(HttpRouting.Builder routing) {
            routing.register(LangChain4jDevUi.create(config));
        }

        @Override
        public double weight() {
            return WEIGHT;
        }
    }
}
