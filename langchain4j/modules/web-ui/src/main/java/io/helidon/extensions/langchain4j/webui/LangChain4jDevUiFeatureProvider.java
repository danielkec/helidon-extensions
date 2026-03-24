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

import io.helidon.common.Weight;
import io.helidon.config.Config;
import io.helidon.webserver.spi.ServerFeatureProvider;

/**
 * Declarative provider for {@link LangChain4jDevUiFeature}.
 */
@Weight(LangChain4jDevUiFeature.WEIGHT)
public class LangChain4jDevUiFeatureProvider implements ServerFeatureProvider<LangChain4jDevUiFeature> {
    /**
     * Required for {@link java.util.ServiceLoader}.
     *
     * @deprecated for {@link java.util.ServiceLoader} use only
     */
    @Deprecated
    public LangChain4jDevUiFeatureProvider() {
    }

    @Override
    public String configKey() {
        return LangChain4jDevUi.CONFIG_KEY;
    }

    @Override
    public LangChain4jDevUiFeature create(Config config, String name) {
        return LangChain4jDevUiFeature.create(name, LangChain4jDevUiConfig.create(config));
    }
}
