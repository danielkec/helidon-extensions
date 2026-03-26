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

import io.helidon.service.registry.Service;

import dev.langchain4j.observability.api.event.AiServiceCompletedEvent;
import dev.langchain4j.observability.api.event.AiServiceErrorEvent;
import dev.langchain4j.observability.api.event.AiServiceRequestIssuedEvent;
import dev.langchain4j.observability.api.event.AiServiceResponseReceivedEvent;
import dev.langchain4j.observability.api.event.AiServiceStartedEvent;
import dev.langchain4j.observability.api.event.InputGuardrailExecutedEvent;
import dev.langchain4j.observability.api.event.OutputGuardrailExecutedEvent;
import dev.langchain4j.observability.api.event.ToolExecutedEvent;
import dev.langchain4j.observability.api.listener.AiServiceCompletedListener;
import dev.langchain4j.observability.api.listener.AiServiceErrorListener;
import dev.langchain4j.observability.api.listener.AiServiceRequestIssuedListener;
import dev.langchain4j.observability.api.listener.AiServiceResponseReceivedListener;
import dev.langchain4j.observability.api.listener.AiServiceStartedListener;
import dev.langchain4j.observability.api.listener.InputGuardrailExecutedListener;
import dev.langchain4j.observability.api.listener.OutputGuardrailExecutedListener;
import dev.langchain4j.observability.api.listener.ToolExecutedEventListener;

@Service.Singleton
final class LangChain4jDevUiAiServiceStartedListener implements AiServiceStartedListener {
    private final LangChain4jDevUiRecorder recorder;

    @Service.Inject
    LangChain4jDevUiAiServiceStartedListener(LangChain4jDevUiRecorder recorder) {
        this.recorder = recorder;
    }

    @Override
    public void onEvent(AiServiceStartedEvent event) {
        recorder.onAiServiceStarted(event);
    }
}

@Service.Singleton
final class LangChain4jDevUiAiServiceCompletedListener implements AiServiceCompletedListener {
    private final LangChain4jDevUiRecorder recorder;

    @Service.Inject
    LangChain4jDevUiAiServiceCompletedListener(LangChain4jDevUiRecorder recorder) {
        this.recorder = recorder;
    }

    @Override
    public void onEvent(AiServiceCompletedEvent event) {
        recorder.onAiServiceCompleted(event);
    }
}

@Service.Singleton
final class LangChain4jDevUiAiServiceErrorListener implements AiServiceErrorListener {
    private final LangChain4jDevUiRecorder recorder;

    @Service.Inject
    LangChain4jDevUiAiServiceErrorListener(LangChain4jDevUiRecorder recorder) {
        this.recorder = recorder;
    }

    @Override
    public void onEvent(AiServiceErrorEvent event) {
        recorder.onAiServiceError(event);
    }
}

@Service.Singleton
final class LangChain4jDevUiAiServiceRequestIssuedListener implements AiServiceRequestIssuedListener {
    private final LangChain4jDevUiRecorder recorder;

    @Service.Inject
    LangChain4jDevUiAiServiceRequestIssuedListener(LangChain4jDevUiRecorder recorder) {
        this.recorder = recorder;
    }

    @Override
    public void onEvent(AiServiceRequestIssuedEvent event) {
        recorder.onAiServiceRequestIssued(event);
    }
}

@Service.Singleton
final class LangChain4jDevUiAiServiceResponseReceivedListener implements AiServiceResponseReceivedListener {
    private final LangChain4jDevUiRecorder recorder;

    @Service.Inject
    LangChain4jDevUiAiServiceResponseReceivedListener(LangChain4jDevUiRecorder recorder) {
        this.recorder = recorder;
    }

    @Override
    public void onEvent(AiServiceResponseReceivedEvent event) {
        recorder.onAiServiceResponseReceived(event);
    }
}

@Service.Singleton
final class LangChain4jDevUiToolExecutedEventListener implements ToolExecutedEventListener {
    private final LangChain4jDevUiRecorder recorder;

    @Service.Inject
    LangChain4jDevUiToolExecutedEventListener(LangChain4jDevUiRecorder recorder) {
        this.recorder = recorder;
    }

    @Override
    public void onEvent(ToolExecutedEvent event) {
        recorder.onToolExecuted(event);
    }
}

@Service.Singleton
final class LangChain4jDevUiInputGuardrailExecutedListener implements InputGuardrailExecutedListener {
    private final LangChain4jDevUiRecorder recorder;

    @Service.Inject
    LangChain4jDevUiInputGuardrailExecutedListener(LangChain4jDevUiRecorder recorder) {
        this.recorder = recorder;
    }

    @Override
    public void onEvent(InputGuardrailExecutedEvent event) {
        recorder.onInputGuardrailExecuted(event);
    }
}

@Service.Singleton
final class LangChain4jDevUiOutputGuardrailExecutedListener implements OutputGuardrailExecutedListener {
    private final LangChain4jDevUiRecorder recorder;

    @Service.Inject
    LangChain4jDevUiOutputGuardrailExecutedListener(LangChain4jDevUiRecorder recorder) {
        this.recorder = recorder;
    }

    @Override
    public void onEvent(OutputGuardrailExecutedEvent event) {
        recorder.onOutputGuardrailExecuted(event);
    }
}
