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

package io.helidon.extensions.langchain4j.examples.agentic.chess.engine;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import io.helidon.service.registry.Service;

@Service.Singleton
final class ChessSessionStore {
    private final ConcurrentMap<String, ChessSession> sessions = new ConcurrentHashMap<>();

    ChessSession create() {
        String sessionId = UUID.randomUUID().toString();
        ChessSession session = new ChessSession(sessionId);
        sessions.put(sessionId, session);
        return session;
    }

    ChessSession reset(String sessionId) {
        ChessSession session = new ChessSession(sessionId);
        sessions.put(sessionId, session);
        return session;
    }

    Optional<ChessSession> find(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    ChessSession require(String sessionId) {
        return find(sessionId).orElseThrow(() -> new IllegalArgumentException("Unknown session: " + sessionId));
    }

    boolean isCurrentGeneration(String sessionId, String generation) {
        return find(sessionId)
                .map(session -> session.generation().equals(generation))
                .orElse(false);
    }
}
