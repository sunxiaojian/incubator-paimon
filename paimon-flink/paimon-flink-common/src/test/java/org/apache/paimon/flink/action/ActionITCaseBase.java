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

package org.apache.paimon.flink.action;

import org.apache.paimon.catalog.Catalog;
import org.apache.paimon.catalog.CatalogContext;
import org.apache.paimon.catalog.CatalogFactory;
import org.apache.paimon.catalog.Identifier;
import org.apache.paimon.data.DataFormatTestUtil;
import org.apache.paimon.data.GenericRow;
import org.apache.paimon.data.InternalRow;
import org.apache.paimon.flink.util.AbstractTestBase;
import org.apache.paimon.fs.Path;
import org.apache.paimon.options.Options;
import org.apache.paimon.reader.RecordReader;
import org.apache.paimon.schema.Schema;
import org.apache.paimon.table.FileStoreTable;
import org.apache.paimon.table.sink.StreamTableCommit;
import org.apache.paimon.table.sink.StreamTableWrite;
import org.apache.paimon.table.source.Split;
import org.apache.paimon.table.source.TableRead;
import org.apache.paimon.types.RowType;
import org.apache.paimon.utils.BranchManager;

import org.apache.flink.api.common.RuntimeExecutionMode;
import org.apache.flink.api.common.restartstrategy.RestartStrategies;
import org.apache.flink.streaming.api.CheckpointingMode;
import org.apache.flink.streaming.api.environment.ExecutionCheckpointingOptions;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.table.api.EnvironmentSettings;
import org.apache.flink.table.api.TableEnvironment;
import org.apache.flink.table.api.bridge.java.StreamTableEnvironment;
import org.apache.flink.table.api.config.TableConfigOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.apache.paimon.CoreOptions.BRANCH;
import static org.apache.paimon.options.CatalogOptions.WAREHOUSE;

/** {@link Action} test base. */
public abstract class ActionITCaseBase extends AbstractTestBase {

    protected String warehouse;
    protected String database;
    protected String tableName;
    protected String commitUser;
    protected StreamTableWrite write;
    protected StreamTableCommit commit;
    protected Catalog catalog;
    private long incrementalIdentifier;
    protected String branch = BranchManager.DEFAULT_MAIN_BRANCH;

    @BeforeEach
    public void before() throws IOException {
        warehouse = getTempDirPath();
        database = "default";
        tableName = "test_table_" + UUID.randomUUID();
        commitUser = UUID.randomUUID().toString();
        incrementalIdentifier = 0;
        Map<String, String> options = new HashMap<>();
        options.put(WAREHOUSE.key(), new Path(warehouse).toUri().toString());
        if (!branch.equals(BranchManager.DEFAULT_MAIN_BRANCH)) {
            options.put(BRANCH.key(), branch);
        }
        catalog = CatalogFactory.createCatalog(CatalogContext.create(Options.fromMap(options)));
    }

    @AfterEach
    public void after() throws Exception {
        if (write != null) {
            write.close();
            write = null;
        }
        if (commit != null) {
            commit.close();
            commit = null;
        }
        catalog.close();
    }

    protected FileStoreTable createFileStoreTable(
            RowType rowType,
            List<String> partitionKeys,
            List<String> primaryKeys,
            Map<String, String> options)
            throws Exception {
        return createFileStoreTable(tableName, rowType, partitionKeys, primaryKeys, options);
    }

    protected FileStoreTable createFileStoreTable(
            String tableName,
            RowType rowType,
            List<String> partitionKeys,
            List<String> primaryKeys,
            Map<String, String> options)
            throws Exception {
        Identifier identifier = Identifier.create(database, tableName);
        catalog.createDatabase(database, true);
        Map<String, String> newOptions = new HashMap<>(options);
        if (!newOptions.containsKey("bucket")) {
            newOptions.put("bucket", "1");
        }
        catalog.createTable(
                identifier,
                new Schema(rowType.getFields(), partitionKeys, primaryKeys, newOptions, ""),
                false);
        return (FileStoreTable) catalog.getTable(identifier);
    }

    protected FileStoreTable getFileStoreTable(String tableName) throws Exception {
        Identifier identifier = Identifier.create(database, tableName);
        return (FileStoreTable) catalog.getTable(identifier);
    }

    protected GenericRow rowData(Object... values) {
        return GenericRow.of(values);
    }

    protected void writeData(GenericRow... data) throws Exception {
        for (GenericRow d : data) {
            write.write(d);
        }
        commit.commit(incrementalIdentifier, write.prepareCommit(true, incrementalIdentifier));
        incrementalIdentifier++;
    }

    protected List<String> getResult(TableRead read, List<Split> splits, RowType rowType)
            throws Exception {
        try (RecordReader<InternalRow> recordReader = read.createReader(splits)) {
            List<String> result = new ArrayList<>();
            recordReader.forEachRemaining(
                    row -> result.add(DataFormatTestUtil.internalRowToString(row, rowType)));
            return result;
        }
    }

    protected StreamExecutionEnvironment buildDefaultEnv(boolean isStreaming) {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.getConfig().setRestartStrategy(RestartStrategies.noRestart());
        env.setParallelism(ThreadLocalRandom.current().nextInt(2) + 1);

        if (isStreaming) {
            env.setRuntimeMode(RuntimeExecutionMode.STREAMING);
            env.getCheckpointConfig().setCheckpointingMode(CheckpointingMode.EXACTLY_ONCE);
            env.getCheckpointConfig().setCheckpointInterval(500);
        } else {
            env.setRuntimeMode(RuntimeExecutionMode.BATCH);
        }

        return env;
    }

    protected <T extends ActionBase> T createAction(Class<T> clazz, List<String> args) {
        return createAction(clazz, args.toArray(new String[0]));
    }

    protected <T extends ActionBase> T createAction(Class<T> clazz, String... args) {
        if (ThreadLocalRandom.current().nextBoolean()) {
            confuseArgs(args, "_", "-");
        } else {
            confuseArgs(args, "-", "_");
        }
        return ActionFactory.createAction(args)
                .filter(clazz::isInstance)
                .map(clazz::cast)
                .orElseThrow(() -> new RuntimeException("Failed to create action"));
    }

    // to test compatibility with old usage
    private void confuseArgs(String[] args, String regex, String replacement) {
        args[0] = args[0].replaceAll(regex, replacement);
        for (int i = 1; i < args.length; i += 2) {
            String arg = args[i].substring(2);
            args[i] = "--" + arg.replaceAll(regex, replacement);
        }
    }

    protected void callProcedure(String procedureStatement) {
        // default execution mode
        callProcedure(procedureStatement, true, false);
    }

    protected void callProcedure(String procedureStatement, boolean isStreaming, boolean dmlSync) {
        StreamExecutionEnvironment env = buildDefaultEnv(isStreaming);

        TableEnvironment tEnv;
        if (isStreaming) {
            tEnv = StreamTableEnvironment.create(env, EnvironmentSettings.inStreamingMode());
            tEnv.getConfig()
                    .set(
                            ExecutionCheckpointingOptions.CHECKPOINTING_INTERVAL,
                            Duration.ofMillis(500));
        } else {
            tEnv = StreamTableEnvironment.create(env, EnvironmentSettings.inBatchMode());
        }

        tEnv.getConfig().set(TableConfigOptions.TABLE_DML_SYNC, dmlSync);

        tEnv.executeSql(
                String.format(
                        "CREATE CATALOG PAIMON WITH ('type'='paimon', 'warehouse'='%s');",
                        warehouse));
        tEnv.useCatalog("PAIMON");

        tEnv.executeSql(procedureStatement);
    }
}
