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

package io.helidon.extensions.langchain4j.examples.agentic.chess.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import io.helidon.extensions.langchain4j.examples.agentic.chess.dto.AiMoveProposal;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;

public final class ChessAiJsonSupport {
    private static final Jsonb JSONB = JsonbBuilder.create();

    private ChessAiJsonSupport() {
    }

    public static AiMoveProposal parseProposal(String rawText) {
        Objects.requireNonNull(rawText);
        String cleaned = cleanJson(rawText);
        AiMoveProposal proposal = JSONB.fromJson(cleaned, AiMoveProposal.class);
        if (proposal == null) {
            throw new IllegalArgumentException("Model response did not contain JSON");
        }
        if (proposal.getCandidateLines() == null) {
            proposal.setCandidateLines(new ArrayList<>());
        }
        proposal.getCandidateLines().forEach(line -> {
            if (line.getMoves() == null) {
                line.setMoves(List.of());
            }
            if (line.getSummary() == null) {
                line.setSummary("");
            }
        });
        return proposal;
    }

    public static String toJson(AiMoveProposal proposal) {
        return JSONB.toJson(proposal);
    }

    public static String cleanJson(String rawText) {
        String trimmed = rawText.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            int closingFence = trimmed.lastIndexOf("```");
            if (firstNewline >= 0 && closingFence > firstNewline) {
                trimmed = trimmed.substring(firstNewline + 1, closingFence).trim();
            }
        }

        int firstBrace = trimmed.indexOf('{');
        int lastBrace = trimmed.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace >= firstBrace) {
            return trimmed.substring(firstBrace, lastBrace + 1);
        }
        throw new IllegalArgumentException("Expected JSON object in model response");
    }
}
