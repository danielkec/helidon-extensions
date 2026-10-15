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

package io.helidon.extensions.messaging.connectors.aq;

import java.util.Optional;

import javax.jms.ConnectionFactory;
import javax.sql.DataSource;

import io.helidon.messaging.MessagingException;
import io.helidon.service.registry.ServiceRegistry;

import oracle.jms.AQjmsConnectionFactory;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.sameInstance;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

class AqResourceResolverTest {
    @Test
    void testDirectDataSourceDoesNotBorrowConnectionDuringResolution() {
        DataSource source = mock(DataSource.class);
        ServiceRegistry registry = mock(ServiceRegistry.class);
        var config = config(AqConnectorConfig.builder().name("aq").dataSource(source).buildPrototype());

        assertThat(new AqResourceResolver(registry).resolve(config), instanceOf(AQjmsConnectionFactory.class));
        verifyZeroInteractions(source, registry);
    }

    @Test
    void testNamedDataSourceIsResolvedExactly() {
        DataSource source = mock(DataSource.class);
        ServiceRegistry registry = mock(ServiceRegistry.class);
        when(registry.firstNamed(DataSource.class, "shared-db")).thenReturn(Optional.of(source));
        var config = config(AqConnectorConfig.builder().name("aq").dataSourceName("shared-db").buildPrototype());

        assertThat(new AqResourceResolver(registry).resolve(config), instanceOf(AQjmsConnectionFactory.class));
        verify(registry).firstNamed(DataSource.class, "shared-db");
        verifyNoMoreInteractions(registry);
        verifyZeroInteractions(source);
    }

    @Test
    void testMissingNamedDataSourceDoesNotFallBack() {
        ServiceRegistry registry = mock(ServiceRegistry.class);
        when(registry.firstNamed(DataSource.class, "missing")).thenReturn(Optional.empty());
        var config = config(AqConnectorConfig.builder().name("aq").dataSourceName("missing").buildPrototype());

        var failure = assertThrows(MessagingException.class, () -> new AqResourceResolver(registry).resolve(config));
        assertThat(failure.getMessage(), containsString("DataSource named missing"));
        verify(registry).firstNamed(DataSource.class, "missing");
        verifyNoMoreInteractions(registry);
    }

    @Test
    void testDirectAndNamedFactoriesArePreserved() {
        ConnectionFactory factory = mock(ConnectionFactory.class);
        ServiceRegistry registry = mock(ServiceRegistry.class);
        var direct = config(AqConnectorConfig.builder().name("aq").connectionFactory(factory).buildPrototype());

        assertThat(new AqResourceResolver(registry).resolve(direct), sameInstance(factory));
        verifyZeroInteractions(registry, factory);

        when(registry.firstNamed(ConnectionFactory.class, "factory")).thenReturn(Optional.of(factory));
        var named = config(AqConnectorConfig.builder().name("aq").connectionFactoryName("factory").buildPrototype());
        assertThat(new AqResourceResolver(registry).resolve(named), sameInstance(factory));
        verify(registry).firstNamed(ConnectionFactory.class, "factory");
        verifyNoMoreInteractions(registry);
        verifyZeroInteractions(factory);
    }

    @Test
    void testMissingNamedFactoryDoesNotFallBack() {
        ServiceRegistry registry = mock(ServiceRegistry.class);
        when(registry.firstNamed(ConnectionFactory.class, "missing")).thenReturn(Optional.empty());
        var config = config(AqConnectorConfig.builder().name("aq").connectionFactoryName("missing").buildPrototype());

        var failure = assertThrows(MessagingException.class, () -> new AqResourceResolver(registry).resolve(config));
        assertThat(failure.getMessage(), containsString("ConnectionFactory named missing"));
        verify(registry).firstNamed(ConnectionFactory.class, "missing");
        verifyNoMoreInteractions(registry);
    }

    @Test
    void testDefaultDataSourcePrecedesDefaultFactory() {
        DataSource source = mock(DataSource.class);
        ServiceRegistry registry = mock(ServiceRegistry.class);
        when(registry.first(DataSource.class)).thenReturn(Optional.of(source));
        var config = config(AqConnectorConfig.builder().name("aq").buildPrototype());

        assertThat(new AqResourceResolver(registry).resolve(config), instanceOf(AQjmsConnectionFactory.class));
        verify(registry).first(DataSource.class);
        verifyNoMoreInteractions(registry);
        verifyZeroInteractions(source);
    }

    @Test
    void testJdbcUrlDoesNotRequireRegistryOrConnection() {
        ServiceRegistry registry = mock(ServiceRegistry.class);
        var config = config(AqConnectorConfig.builder()
                                    .name("aq")
                                    .jdbcUrl("jdbc:oracle:thin:@localhost:1521/FREEPDB1")
                                    .buildPrototype());

        assertThat(new AqResourceResolver(registry).resolve(config), instanceOf(AQjmsConnectionFactory.class));
        verifyZeroInteractions(registry);
    }

    private static AqRuntimeConfig config(AqConnectorConfig defaults) {
        return AqRuntimeConfig.outgoing(defaults, AqOutgoingConfig.builder()
                .connector("aq")
                .channelName("orders")
                .destination("ORDERS")
                .build());
    }
}
