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

package io.helidon.extensions.messaging.tests.aq;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

import javax.sql.DataSource;

import oracle.jdbc.pool.OracleDataSource;

final class AqDatabase implements AutoCloseable {
    private static final String SCHEMA_PASSWORD = "AqTestSchema176";

    private final OracleDataSource administrator;
    private final OracleDataSource dataSource;
    private final String schema;

    private AqDatabase(OracleDataSource administrator, OracleDataSource dataSource, String schema) {
        this.administrator = administrator;
        this.dataSource = dataSource;
        this.schema = schema;
    }

    static AqDatabase create() throws SQLException, IOException {
        String schema = "AQ176_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT);
        String url = Objects.requireNonNull(System.getProperty("aq.test.url"));
        OracleDataSource administrator = dataSource(url,
                                                    System.getProperty("aq.test.user", "system"),
                                                    Objects.requireNonNull(System.getProperty("aq.test.password")));
        AqDatabase database = new AqDatabase(administrator, dataSource(url, schema, SCHEMA_PASSWORD), schema);
        boolean created = false;
        try {
            try (Connection connection = administrator.getConnection(); var statement = connection.createStatement()) {
                statement.execute("CREATE USER " + schema + " IDENTIFIED BY " + SCHEMA_PASSWORD);
                created = true;
                statement.execute("GRANT CONNECT, RESOURCE, AQ_ADMINISTRATOR_ROLE, AQ_USER_ROLE, UNLIMITED TABLESPACE TO " + schema);
                statement.execute("GRANT EXECUTE ON SYS.DBMS_AQ TO " + schema);
                statement.execute("GRANT EXECUTE ON SYS.DBMS_AQADM TO " + schema);
            }
            database.initialize("/schema.sql");
            if (Boolean.getBoolean("aq.test.xml")) {
                database.initialize("/xml-schema.sql");
            }
            return database;
        } catch (SQLException | IOException e) {
            if (created) {
                try {
                    database.close();
                } catch (SQLException closeFailure) {
                    e.addSuppressed(closeFailure);
                }
            }
            throw e;
        }
    }

    DataSource dataSource() {
        return dataSource;
    }

    String schema() {
        return schema;
    }

    @Override
    public void close() throws SQLException {
        try (Connection connection = administrator.getConnection(); var statement = connection.createStatement()) {
            statement.execute("DROP USER " + schema + " CASCADE");
        }
    }

    private static OracleDataSource dataSource(String url, String user, String password) throws SQLException {
        OracleDataSource result = new OracleDataSource();
        result.setURL(url);
        result.setUser(user);
        result.setPassword(password);
        return result;
    }

    private void initialize(String resource) throws SQLException, IOException {
        String script;
        try (var input = Objects.requireNonNull(AqDatabase.class.getResourceAsStream(resource))) {
            script = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (Connection connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            for (String sql : script.split("(?m)^/\\s*$")) {
                if (!sql.isBlank()) {
                    statement.execute(sql.strip());
                }
            }
        }
    }
}
