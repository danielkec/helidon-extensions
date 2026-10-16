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
import java.util.Set;

import javax.jms.ConnectionFactory;
import javax.sql.DataSource;

import io.helidon.common.types.ResolvedType;
import io.helidon.common.types.TypeName;
import io.helidon.messaging.MessagingException;
import io.helidon.service.registry.Qualifier;
import io.helidon.service.registry.ServiceDescriptor;
import io.helidon.service.registry.ServiceRegistry;
import io.helidon.service.registry.ServiceRegistryConfig;
import io.helidon.service.registry.ServiceRegistryManager;

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
        ConnectionFactory factory = mock(ConnectionFactory.class);
        var config = config(AqConnectorConfig.builder().name("aq").buildPrototype());
        var manager = ServiceRegistryManager.create(registryConfig()
                .putContractInstance(DataSource.class, source)
                .putContractInstance(ConnectionFactory.class, factory)
                .build());
        try {
            assertThat(new AqResourceResolver(manager.registry()).resolve(config), instanceOf(AQjmsConnectionFactory.class));
            verifyZeroInteractions(source, factory);
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void testNamedDataSourceDoesNotInterceptDefaultFactory() {
        DataSource analytics = mock(DataSource.class);
        ConnectionFactory factory = mock(ConnectionFactory.class);
        var manager = ServiceRegistryManager.create(registryConfig()
                .putContractInstance(ConnectionFactory.class, factory)
                .putServiceInstance(new NamedDescriptor<>(DataSource.class, "analytics"), analytics)
                .build());
        try {
            var config = config(AqConnectorConfig.builder().name("aq").buildPrototype());
            assertThat(new AqResourceResolver(manager.registry()).resolve(config), sameInstance(factory));
            verifyZeroInteractions(analytics, factory);
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void testNamedDataSourceRequiresExplicitSelection() {
        DataSource analytics = mock(DataSource.class);
        var manager = ServiceRegistryManager.create(registryConfig()
                .putServiceInstance(new NamedDescriptor<>(DataSource.class, "analytics"), analytics)
                .build());
        try {
            var resolver = new AqResourceResolver(manager.registry());
            var defaults = config(AqConnectorConfig.builder().name("aq").buildPrototype());
            assertThrows(MessagingException.class, () -> resolver.resolve(defaults));

            var named = config(AqConnectorConfig.builder().name("aq").dataSourceName("analytics").buildPrototype());
            assertThat(resolver.resolve(named), instanceOf(AQjmsConnectionFactory.class));
            verifyZeroInteractions(analytics);
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void testNamedFactoryRequiresExplicitSelection() {
        ConnectionFactory factory = mock(ConnectionFactory.class);
        var manager = ServiceRegistryManager.create(registryConfig()
                .putServiceInstance(new NamedDescriptor<>(ConnectionFactory.class, "orders"), factory)
                .build());
        try {
            var resolver = new AqResourceResolver(manager.registry());
            var defaults = config(AqConnectorConfig.builder().name("aq").buildPrototype());
            assertThrows(MessagingException.class, () -> resolver.resolve(defaults));

            var named = config(AqConnectorConfig.builder().name("aq").connectionFactoryName("orders").buildPrototype());
            assertThat(resolver.resolve(named), sameInstance(factory));
            verifyZeroInteractions(factory);
        } finally {
            manager.shutdown();
        }
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

    private static ServiceRegistryConfig.Builder registryConfig() {
        return ServiceRegistryConfig.builder().discoverServices(false).discoverServicesFromServiceLoader(false);
    }

    private static final class NamedDescriptor<T> implements ServiceDescriptor<T> {
        private final Class<T> contract;
        private final String name;

        private NamedDescriptor(Class<T> contract, String name) {
            this.contract = contract;
            this.name = name;
        }

        @Override
        public TypeName serviceType() {
            return TypeName.create(AqResourceResolverTest.class.getName() + "." + name);
        }

        @Override
        public TypeName descriptorType() {
            return TypeName.create(NamedDescriptor.class);
        }

        @Override
        public Set<ResolvedType> contracts() {
            return Set.of(ResolvedType.create(contract));
        }

        @Override
        public Set<Qualifier> qualifiers() {
            return Set.of(Qualifier.createNamed(name));
        }
    }
}
