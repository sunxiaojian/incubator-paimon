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

package org.apache.paimon.operation;

import org.apache.paimon.KeyValueFileStore;
import org.apache.paimon.manifest.ManifestEntry;
import org.apache.paimon.manifest.ManifestFile;
import org.apache.paimon.manifest.ManifestList;
import org.apache.paimon.predicate.Predicate;
import org.apache.paimon.schema.KeyValueFieldsExtractor;
import org.apache.paimon.schema.SchemaManager;
import org.apache.paimon.stats.BinaryTableStats;
import org.apache.paimon.stats.FieldStatsArraySerializer;
import org.apache.paimon.stats.FieldStatsConverters;
import org.apache.paimon.types.RowType;
import org.apache.paimon.utils.SnapshotManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** {@link FileStoreScan} for {@link KeyValueFileStore}. */
public class KeyValueFileStoreScan extends AbstractFileStoreScan {

    private final FieldStatsConverters fieldKeyStatsConverters;
    private final FieldStatsConverters fieldValueStatsConverters;

    private Predicate keyFilter;
    private Predicate valueFilter;

    public KeyValueFileStoreScan(
            RowType partitionType,
            ScanBucketFilter bucketFilter,
            SnapshotManager snapshotManager,
            SchemaManager schemaManager,
            long schemaId,
            KeyValueFieldsExtractor keyValueFieldsExtractor,
            ManifestFile.Factory manifestFileFactory,
            ManifestList.Factory manifestListFactory,
            int numOfBuckets,
            boolean checkNumOfBuckets,
            Integer scanManifestParallelism) {
        super(
                partitionType,
                bucketFilter,
                snapshotManager,
                schemaManager,
                manifestFileFactory,
                manifestListFactory,
                numOfBuckets,
                checkNumOfBuckets,
                scanManifestParallelism);
        this.fieldKeyStatsConverters =
                new FieldStatsConverters(
                        sid -> keyValueFieldsExtractor.keyFields(scanTableSchema(sid)), schemaId);
        this.fieldValueStatsConverters =
                new FieldStatsConverters(
                        sid -> keyValueFieldsExtractor.valueFields(scanTableSchema(sid)), schemaId);
    }

    public KeyValueFileStoreScan withKeyFilter(Predicate predicate) {
        this.keyFilter = predicate;
        this.bucketKeyFilter.pushdown(predicate);
        return this;
    }

    public KeyValueFileStoreScan withValueFilter(Predicate predicate) {
        this.valueFilter = predicate;
        return this;
    }

    /** Note: Keep this thread-safe. */
    @Override
    protected boolean filterByStats(ManifestEntry entry) {
        if (keyFilter == null) {
            return true;
        }

        FieldStatsArraySerializer serializer =
                fieldKeyStatsConverters.getOrCreate(entry.file().schemaId());
        BinaryTableStats stats = entry.file().keyStats();
        return keyFilter.test(
                entry.file().rowCount(),
                serializer.evolution(stats.minValues()),
                serializer.evolution(stats.maxValues()),
                serializer.evolution(stats.nullCounts(), entry.file().rowCount()));
    }

    /** Note: Keep this thread-safe. */
    @Override
    protected List<ManifestEntry> filterWholeBucketByStats(List<ManifestEntry> entries) {
        if (valueFilter == null) {
            return entries;
        }

        return noOverlapping(entries)
                ? filterWholeBucketPerFile(entries)
                : filterWholeBucketAllFiles(entries);
    }

    private List<ManifestEntry> filterWholeBucketPerFile(List<ManifestEntry> entries) {
        List<ManifestEntry> filtered = new ArrayList<>();
        for (ManifestEntry entry : entries) {
            if (filterByValueFilter(entry)) {
                filtered.add(entry);
            }
        }
        return filtered;
    }

    private List<ManifestEntry> filterWholeBucketAllFiles(List<ManifestEntry> entries) {
        // entries come from the same bucket, if any of it doesn't meet the request, we could
        // filter the bucket.
        for (ManifestEntry entry : entries) {
            if (filterByValueFilter(entry)) {
                return entries;
            }
        }
        return Collections.emptyList();
    }

    private boolean filterByValueFilter(ManifestEntry entry) {
        FieldStatsArraySerializer serializer =
                fieldValueStatsConverters.getOrCreate(entry.file().schemaId());
        BinaryTableStats stats = entry.file().valueStats();
        return valueFilter.test(
                entry.file().rowCount(),
                serializer.evolution(stats.minValues()),
                serializer.evolution(stats.maxValues()),
                serializer.evolution(stats.nullCounts(), entry.file().rowCount()));
    }

    private static boolean noOverlapping(List<ManifestEntry> entries) {
        if (entries.size() <= 1) {
            return true;
        }

        Integer previousLevel = null;
        for (ManifestEntry entry : entries) {
            int level = entry.file().level();
            // level 0 files have overlapping
            if (level == 0) {
                return false;
            }

            if (previousLevel == null) {
                previousLevel = level;
            } else {
                // different level, have overlapping
                if (previousLevel != level) {
                    return false;
                }
            }
        }

        return true;
    }
}
