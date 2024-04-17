/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.paimon.flink.action.cdc.postgres;

import org.apache.paimon.catalog.Identifier;
import org.apache.paimon.flink.action.MultiTablesSinkMode;
import org.apache.paimon.flink.action.cdc.TypeMapping;
import org.apache.paimon.flink.action.cdc.schema.JdbcSchemaUtils;
import org.apache.paimon.flink.action.cdc.schema.JdbcSchemasInfo;
import org.apache.paimon.options.OptionsUtils;
import org.apache.paimon.schema.Schema;
import org.apache.paimon.utils.Pair;

import com.ververica.cdc.connectors.base.options.StartupOptions;
import com.ververica.cdc.connectors.base.source.jdbc.JdbcIncrementalSource;
import com.ververica.cdc.connectors.postgres.source.PostgresSourceBuilder;
import com.ververica.cdc.connectors.postgres.source.PostgresSourceBuilder.PostgresIncrementalSource;
import com.ververica.cdc.connectors.postgres.source.config.PostgresSourceOptions;
import com.ververica.cdc.debezium.JsonDebeziumDeserializationSchema;
import com.ververica.cdc.debezium.table.DebeziumOptions;
import org.apache.flink.configuration.Configuration;
import org.apache.kafka.connect.json.JsonConverterConfig;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.apache.paimon.flink.action.MultiTablesSinkMode.COMBINED;
import static org.apache.paimon.flink.action.MultiTablesSinkMode.DIVIDED;
import static org.apache.paimon.flink.action.cdc.postgres.PostgresTypeUtils.toPaimonTypeVisitor;

/** Utils for Postgres Action. */
public class PostgresActionUtils {

    static Connection getConnection(Configuration postgresConfig) throws Exception {
        String url =
                String.format(
                        "jdbc:postgresql://%s:%d/%s",
                        postgresConfig.get(PostgresSourceOptions.HOSTNAME),
                        postgresConfig.get(PostgresSourceOptions.PG_PORT),
                        postgresConfig.get(PostgresSourceOptions.DATABASE_NAME));

        return DriverManager.getConnection(
                url,
                postgresConfig.get(PostgresSourceOptions.USERNAME),
                postgresConfig.get(PostgresSourceOptions.PASSWORD));
    }

    public static JdbcSchemasInfo getPostgresTableInfos(
            Configuration postgresConfig,
            Predicate<String> monitorTablePredication,
            List<Pair<Identifier, String>> excludedTables,
            TypeMapping typeMapping)
            throws Exception {

        String databaseName = postgresConfig.get(PostgresSourceOptions.DATABASE_NAME);
        Pattern schemaPattern =
                Pattern.compile(postgresConfig.get(PostgresSourceOptions.SCHEMA_NAME));
        JdbcSchemasInfo jdbcSchemasInfo = new JdbcSchemasInfo();
        try (Connection conn = PostgresActionUtils.getConnection(postgresConfig)) {
            DatabaseMetaData metaData = conn.getMetaData();
            try (ResultSet schemas = metaData.getSchemas()) {
                while (schemas.next()) {
                    String schemaName = schemas.getString("TABLE_SCHEM");
                    Matcher schemaMatcher = schemaPattern.matcher(schemaName);
                    if (!schemaMatcher.matches()) {
                        continue;
                    }
                    try (ResultSet tables =
                            metaData.getTables(
                                    databaseName, schemaName, "%", new String[] {"TABLE"})) {
                        while (tables.next()) {
                            String tableName = tables.getString("TABLE_NAME");
                            String tableComment = tables.getString("REMARKS");
                            Identifier identifier = Identifier.create(databaseName, tableName);
                            if (monitorTablePredication.test(tableName)) {
                                Schema schema =
                                        JdbcSchemaUtils.buildSchema(
                                                metaData,
                                                databaseName,
                                                schemaName,
                                                tableName,
                                                tableComment,
                                                typeMapping,
                                                toPaimonTypeVisitor());
                                jdbcSchemasInfo.addSchema(identifier, schemaName, schema);
                            } else {
                                excludedTables.add(Pair.of(identifier, schemaName));
                            }
                        }
                    }
                }
            }
        }

        return jdbcSchemasInfo;
    }

    public static JdbcIncrementalSource<String> buildPostgresSource(
            Configuration postgresConfig, String[] schemaList, String[] tableList) {
        PostgresSourceBuilder<String> sourceBuilder = PostgresIncrementalSource.builder();

        sourceBuilder
                .hostname(postgresConfig.get(PostgresSourceOptions.HOSTNAME))
                .port(postgresConfig.get(PostgresSourceOptions.PG_PORT))
                .database(postgresConfig.get(PostgresSourceOptions.DATABASE_NAME))
                .schemaList(schemaList)
                .tableList(tableList)
                .slotName(postgresConfig.get(PostgresSourceOptions.SLOT_NAME))
                .username(postgresConfig.get(PostgresSourceOptions.USERNAME))
                .password(postgresConfig.get(PostgresSourceOptions.PASSWORD));

        // use pgoutput for PostgreSQL 10+
        postgresConfig
                .getOptional(PostgresSourceOptions.DECODING_PLUGIN_NAME)
                .ifPresent(sourceBuilder::decodingPluginName);

        // Postgres CDC using increment snapshot, splitSize is used instead of fetchSize (as in JDBC
        // connector). splitSize is the number of records in each snapshot split. see
        // https://ververica.github.io/flink-cdc-connectors/master/content/connectors/postgres-cdc.html#incremental-snapshot-options
        postgresConfig
                .getOptional(PostgresSourceOptions.SCAN_INCREMENTAL_SNAPSHOT_CHUNK_SIZE)
                .ifPresent(sourceBuilder::splitSize);
        postgresConfig
                .getOptional(PostgresSourceOptions.CONNECT_TIMEOUT)
                .ifPresent(sourceBuilder::connectTimeout);
        postgresConfig
                .getOptional(PostgresSourceOptions.CONNECT_MAX_RETRIES)
                .ifPresent(sourceBuilder::connectMaxRetries);
        postgresConfig
                .getOptional(PostgresSourceOptions.CONNECTION_POOL_SIZE)
                .ifPresent(sourceBuilder::connectionPoolSize);
        postgresConfig
                .getOptional(PostgresSourceOptions.HEARTBEAT_INTERVAL)
                .ifPresent(sourceBuilder::heartbeatInterval);

        String startupMode = postgresConfig.get(PostgresSourceOptions.SCAN_STARTUP_MODE);

        if ("initial".equalsIgnoreCase(startupMode)) {
            sourceBuilder.startupOptions(StartupOptions.initial());
        } else if ("latest-offset".equalsIgnoreCase(startupMode)) {
            sourceBuilder.startupOptions(StartupOptions.latest());
        }

        Properties debeziumProperties = new Properties();
        debeziumProperties.putAll(
                OptionsUtils.convertToPropertiesPrefixKey(
                        postgresConfig.toMap(), DebeziumOptions.DEBEZIUM_OPTIONS_PREFIX));
        sourceBuilder.debeziumProperties(debeziumProperties);

        Map<String, Object> customConverterConfigs = new HashMap<>();
        customConverterConfigs.put(JsonConverterConfig.DECIMAL_FORMAT_CONFIG, "numeric");
        JsonDebeziumDeserializationSchema schema =
                new JsonDebeziumDeserializationSchema(true, customConverterConfigs);
        return sourceBuilder.deserializer(schema).includeSchemaChanges(true).build();
    }

    public static String tableList(
            MultiTablesSinkMode mode,
            String schemaPattern,
            String includingTablePattern,
            List<Pair<Identifier, String>> monitoredTables,
            List<Pair<Identifier, String>> excludedTables) {
        if (mode == DIVIDED) {
            return dividedModeTableList(monitoredTables);
        } else if (mode == COMBINED) {
            return combinedModeTableList(schemaPattern, includingTablePattern, excludedTables);
        }
        throw new UnsupportedOperationException("Unknown MultiTablesSinkMode: " + mode);
    }

    private static String dividedModeTableList(List<Pair<Identifier, String>> monitoredTables) {
        // In DIVIDED mode, we only concern about existed tables
        return monitoredTables.stream()
                .map(t -> t.getRight() + "\\." + t.getLeft().getObjectName())
                .collect(Collectors.joining("|"));
    }

    public static String combinedModeTableList(
            String schemaPattern,
            String includingTablePattern,
            List<Pair<Identifier, String>> excludedTables) {
        String includingPattern =
                String.format("(%s)\\.(%s)", schemaPattern, includingTablePattern);
        if (excludedTables.isEmpty()) {
            return includingPattern;
        }

        String excludingPattern =
                excludedTables.stream()
                        .map(
                                t ->
                                        String.format(
                                                "(^%s$)",
                                                t.getRight() + "\\." + t.getLeft().getObjectName()))
                        .collect(Collectors.joining("|"));
        excludingPattern = "?!" + excludingPattern;
        return String.format("(%s)(%s)", excludingPattern, includingPattern);
    }

    public static void registerJdbcDriver() {
        try {
            Class.forName("org.postgresql.Driver");
        } catch (ClassNotFoundException ex) {
            throw new RuntimeException(
                    "No suitable driver found. Cannot find class org.postgresql.Driver.");
        }
    }
}
