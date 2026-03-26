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

import io.helidon.extensions.langchain4j.Ai;

import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.service.guardrail.InputGuardrails;
import dev.langchain4j.service.guardrail.OutputGuardrails;

@Ai.Service("browser-majordomo-service")
@Ai.ChatModel("dev-ui-model")
@Ai.Tools(BrowserLookupTool.class)
@InputGuardrails(BrowserServiceInputGuardrail.class)
interface BrowserMajordomoService {
    @UserMessage("Majordomo request: {{request}}")
    @OutputGuardrails(BrowserServiceOutputGuardrail.class)
    String welcome(@V("request") String request);
}
