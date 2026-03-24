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

import java.io.InputStream;
import java.lang.reflect.Array;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import jakarta.json.bind.JsonbConfig;

import static org.eclipse.yasson.YassonConfig.ZERO_TIME_PARSE_DEFAULTING;

final class LangChain4jDevUiJsonSupport {
    private static final Jsonb JSONB = JsonbBuilder.newBuilder()
            .withConfig(new JsonbConfig()
                                .setProperty(ZERO_TIME_PARSE_DEFAULTING, true)
                                .withNullValues(true))
            .build();

    private LangChain4jDevUiJsonSupport() {
    }

    static <T> T read(InputStream inputStream, Class<T> type) {
        return JSONB.fromJson(inputStream, type);
    }

    static String write(Object entity) {
        return JSONB.toJson(entity);
    }

    static Object convert(Object rawValue, Type targetType) {
        if (rawValue == null) {
            return null;
        }
        if (targetType instanceof Class<?> targetClass) {
            if (targetClass.isInstance(rawValue)) {
                return rawValue;
            }
            if (targetClass == String.class) {
                return String.valueOf(rawValue);
            }
            if (targetClass == Integer.class || targetClass == int.class) {
                return rawValue instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(rawValue));
            }
            if (targetClass == Long.class || targetClass == long.class) {
                return rawValue instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(rawValue));
            }
            if (targetClass == Double.class || targetClass == double.class) {
                return rawValue instanceof Number number ? number.doubleValue() : Double.parseDouble(String.valueOf(rawValue));
            }
            if (targetClass == Float.class || targetClass == float.class) {
                return rawValue instanceof Number number ? number.floatValue() : Float.parseFloat(String.valueOf(rawValue));
            }
            if (targetClass == Boolean.class || targetClass == boolean.class) {
                if (rawValue instanceof Boolean booleanValue) {
                    return booleanValue;
                }
                return Boolean.parseBoolean(String.valueOf(rawValue));
            }
            if (targetClass.isEnum()) {
                @SuppressWarnings({"unchecked", "rawtypes"})
                Enum<?> enumValue = Enum.valueOf((Class<? extends Enum>) targetClass, String.valueOf(rawValue));
                return enumValue;
            }
        }
        return JSONB.fromJson(write(rawValue), targetType);
    }

    static Object normalize(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Enum<?> enumValue) {
            return enumValue.name();
        }
        if (value instanceof Optional<?> optional) {
            return optional.map(LangChain4jDevUiJsonSupport::normalize).orElse(null);
        }
        if (value instanceof Class<?> type) {
            return type.getName();
        }
        if (value instanceof Throwable throwable) {
            LinkedHashMap<String, Object> error = new LinkedHashMap<>();
            error.put("type", throwable.getClass().getName());
            error.put("message", throwable.getMessage());
            if (throwable.getCause() != null) {
                error.put("cause", normalize(throwable.getCause()));
            }
            return error;
        }
        if (value instanceof Map<?, ?> mapValue) {
            LinkedHashMap<String, Object> normalized = new LinkedHashMap<>();
            mapValue.forEach((key, mapEntryValue) -> normalized.put(String.valueOf(key), normalize(mapEntryValue)));
            return normalized;
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream()
                    .map(LangChain4jDevUiJsonSupport::normalize)
                    .toList();
        }
        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            Object[] normalized = new Object[length];
            for (int i = 0; i < length; i++) {
                normalized[i] = normalize(Array.get(value, i));
            }
            return normalized;
        }
        try {
            return JSONB.fromJson(write(value), Object.class);
        } catch (Exception ignored) {
            return String.valueOf(value);
        }
    }
}
