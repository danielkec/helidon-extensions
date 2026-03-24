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

import io.helidon.builder.api.Option;
import io.helidon.builder.api.Prototype;

/**
 * Configuration for {@link LangChain4jDevUi} and {@link LangChain4jDevUiFeature}.
 */
@Prototype.Blueprint
@Prototype.Configured(value = LangChain4jDevUi.CONFIG_KEY, root = false)
interface LangChain4jDevUiConfigBlueprint extends Prototype.Factory<LangChain4jDevUi> {
    /**
     * Whether the UI should be enabled.
     *
     * @return whether the UI is enabled
     */
    @Option.Configured("enabled")
    @Option.DefaultBoolean(true)
    boolean isEnabled();

    /**
     * Base web context of the UI.
     *
     * @return base UI context
     */
    @Option.Configured
    @Option.Default(LangChain4jDevUi.DEFAULT_WEB_CONTEXT)
    String webContext();
}
