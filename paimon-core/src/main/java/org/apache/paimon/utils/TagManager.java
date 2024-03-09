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

package org.apache.paimon.utils;

import org.apache.paimon.Snapshot;
import org.apache.paimon.annotation.VisibleForTesting;
import org.apache.paimon.fs.FileIO;
import org.apache.paimon.fs.FileStatus;
import org.apache.paimon.fs.Path;
import org.apache.paimon.manifest.ManifestEntry;
import org.apache.paimon.operation.TagDeletion;
import org.apache.paimon.table.sink.TagCallback;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.apache.paimon.utils.BranchManager.DEFAULT_MAIN_BRANCH;
import static org.apache.paimon.utils.BranchManager.getBranchPath;
import static org.apache.paimon.utils.FileUtils.listVersionedFileStatus;
import static org.apache.paimon.utils.Preconditions.checkArgument;

/** Manager for {@code Tag}. */
public class TagManager {

    private static final Logger LOG = LoggerFactory.getLogger(TagManager.class);

    private static final String TAG_PREFIX = "tag-";

    private final FileIO fileIO;
    private final Path tablePath;

    public TagManager(FileIO fileIO, Path tablePath) {
        this.fileIO = fileIO;
        this.tablePath = tablePath;
    }

    /** Return the root Directory of tags. */
    public Path tagDirectory() {
        return new Path(tablePath + "/tag");
    }

    /** Return the root Directory of tags. */
    public Path tagDirectory(String branchName) {
        return branchName.equals(DEFAULT_MAIN_BRANCH)
                ? tagDirectory()
                : new Path(getBranchPath(tablePath, branchName) + "/tag");
    }

    /** Return the path of a tag. */
    public Path tagPath(String tagName) {
        return new Path(tablePath + "/tag/" + TAG_PREFIX + tagName);
    }

    /** Return the path of a tag in branch. */
    public Path tagPath(String branchName, String tagName) {
        return branchName.equals(DEFAULT_MAIN_BRANCH)
                ? tagPath(tagName)
                : new Path(getBranchPath(tablePath, branchName) + "/tag/" + TAG_PREFIX + tagName);
    }

    public void createTag(Snapshot snapshot, String tagName, List<TagCallback> callbacks) {
        createTag(snapshot, tagName, callbacks, DEFAULT_MAIN_BRANCH);
    }

    /** Create a tag from given snapshot and save it in the storage. */
    public void createTag(
            Snapshot snapshot, String tagName, List<TagCallback> callbacks, String branchName) {
        checkArgument(!StringUtils.isBlank(tagName), "Tag name '%s' is blank.", tagName);
        checkArgument(!tagExists(branchName, tagName), "Tag name '%s' already exists.", tagName);

        Path newTagPath = tagPath(branchName, tagName);
        try {
            fileIO.writeFileUtf8(newTagPath, snapshot.toJson());
        } catch (IOException e) {
            throw new RuntimeException(
                    String.format(
                            "Exception occurs when committing tag '%s' (path %s). "
                                    + "Cannot clean up because we can't determine the success.",
                            tagName, newTagPath),
                    e);
        }

        try {
            callbacks.forEach(callback -> callback.notifyCreation(tagName));
        } finally {
            for (TagCallback tagCallback : callbacks) {
                IOUtils.closeQuietly(tagCallback);
            }
        }
    }

    /** Make sure the tagNames are ALL tags of one snapshot. */
    public void deleteAllTagsOfOneSnapshot(
            List<String> tagNames,
            TagDeletion tagDeletion,
            SnapshotManager snapshotManager,
            String branchName) {
        Snapshot taggedSnapshot = taggedSnapshot(branchName, tagNames.get(0));
        List<Snapshot> taggedSnapshots;

        // skip file deletion if snapshot exists
        if (snapshotManager.snapshotExists(taggedSnapshot.id())) {
            tagNames.forEach(tagName -> fileIO.deleteQuietly(tagPath(tagName)));
            return;
        } else {
            // FileIO discovers tags by tag file, so we should read all tags before we delete tag
            taggedSnapshots = taggedSnapshots(branchName);
            tagNames.forEach(tagName -> fileIO.deleteQuietly(tagPath(branchName, tagName)));
        }

        doClean(taggedSnapshot, taggedSnapshots, snapshotManager, tagDeletion, branchName);
    }

    public void deleteTag(
            String tagName, TagDeletion tagDeletion, SnapshotManager snapshotManager) {
        deleteTag(tagName, tagDeletion, snapshotManager, DEFAULT_MAIN_BRANCH);
    }

    public void deleteTag(
            String tagName,
            TagDeletion tagDeletion,
            SnapshotManager snapshotManager,
            String branchName) {
        checkArgument(!StringUtils.isBlank(tagName), "Tag name '%s' is blank.", tagName);
        checkArgument(tagExists(branchName, tagName), "Tag '%s' doesn't exist.", tagName);

        Snapshot taggedSnapshot = taggedSnapshot(branchName, tagName);
        List<Snapshot> taggedSnapshots;

        // skip file deletion if snapshot exists
        if (snapshotManager.snapshotExists(taggedSnapshot.id())) {
            fileIO.deleteQuietly(tagPath(branchName, tagName));
            return;
        } else {
            // FileIO discovers tags by tag file, so we should read all tags before we delete tag
            SortedMap<Snapshot, List<String>> tags = tags();
            fileIO.deleteQuietly(tagPath(branchName, tagName));

            // skip data file clean if more than 1 tags are created based on this snapshot
            if (tags.get(taggedSnapshot).size() > 1) {
                return;
            }
            taggedSnapshots = new ArrayList<>(tags.keySet());
        }

        doClean(taggedSnapshot, taggedSnapshots, snapshotManager, tagDeletion, branchName);
    }

    private void doClean(
            Snapshot taggedSnapshot,
            List<Snapshot> taggedSnapshots,
            SnapshotManager snapshotManager,
            TagDeletion tagDeletion,
            String branchName) {
        // collect skipping sets from the left neighbor tag and the nearest right neighbor (either
        // the earliest snapshot or right neighbor tag)
        List<Snapshot> skippedSnapshots = new ArrayList<>();

        int index = findIndex(taggedSnapshot, taggedSnapshots);
        // the left neighbor tag
        if (index - 1 >= 0) {
            skippedSnapshots.add(taggedSnapshots.get(index - 1));
        }
        // the nearest right neighbor
        Snapshot right = snapshotManager.earliestSnapshot();
        if (index + 1 < taggedSnapshots.size()) {
            Snapshot rightTag = taggedSnapshots.get(index + 1);
            right = right.id() < rightTag.id() ? right : rightTag;
        }
        skippedSnapshots.add(right);

        // delete data files and empty directories
        Predicate<ManifestEntry> dataFileSkipper = null;
        boolean success = true;
        try {
            dataFileSkipper = tagDeletion.dataFileSkipper(skippedSnapshots);
        } catch (Exception e) {
            LOG.info(
                    String.format(
                            "Skip cleaning data files for tag of snapshot %s due to failed to build skipping set.",
                            taggedSnapshot.id()),
                    e);
            success = false;
        }
        if (success) {
            tagDeletion.cleanUnusedDataFiles(taggedSnapshot, dataFileSkipper);
            tagDeletion.cleanDataDirectories();
        }

        // delete manifests
        tagDeletion.cleanUnusedManifests(
                taggedSnapshot, tagDeletion.manifestSkippingSet(skippedSnapshots));
    }

    /** Check if a branch tag exists. */
    public boolean tagExists(String branchName, String tagName) {
        Path path = tagPath(branchName, tagName);
        try {
            return fileIO.exists(path);
        } catch (IOException e) {
            throw new RuntimeException(
                    String.format(
                            "Failed to determine if tag '%s' exists in path %s.", tagName, path),
                    e);
        }
    }

    /** Check if a tag exists. */
    public boolean tagExists(String tagName) {
        return tagExists(DEFAULT_MAIN_BRANCH, tagName);
    }

    /** Get the branch tagged snapshot by name. */
    public Snapshot taggedSnapshot(String tagName) {
        return taggedSnapshot(DEFAULT_MAIN_BRANCH, tagName);
    }

    /** Get the tagged snapshot by name. */
    public Snapshot taggedSnapshot(String branchName, String tagName) {
        checkArgument(tagExists(branchName, tagName), "Tag '%s' doesn't exist.", tagName);
        return Snapshot.fromPath(fileIO, tagPath(branchName, tagName));
    }

    public long tagCount(String branchName) {
        try {
            return listVersionedFileStatus(fileIO, tagDirectory(branchName), TAG_PREFIX).count();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public long tagCount() {
        try {
            return listVersionedFileStatus(fileIO, tagDirectory(), TAG_PREFIX).count();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /** Get all tagged snapshots sorted by snapshot id. */
    public List<Snapshot> taggedSnapshots() {
        return new ArrayList<>(tags().keySet());
    }

    /** Get all tagged snapshots sorted by snapshot id. */
    public List<Snapshot> taggedSnapshots(String branchName) {
        return new ArrayList<>(tags(branchName).keySet());
    }

    /** Get all tagged snapshots with names sorted by snapshot id. */
    public SortedMap<Snapshot, List<String>> tags(String branchName) {
        return tags(branchName, tagName -> true);
    }

    /** Get all tagged snapshots with names sorted by snapshot id. */
    public SortedMap<Snapshot, List<String>> tags() {
        return tags(tagName -> true);
    }

    public SortedMap<Snapshot, List<String>> tags(Predicate<String> filter) {
        return tags(DEFAULT_MAIN_BRANCH, filter);
    }

    /**
     * Retrieves a sorted map of snapshots filtered based on a provided predicate. The predicate
     * determines which tag names should be included in the result. Only snapshots with tag names
     * that pass the predicate test are included.
     *
     * @param filter A Predicate that tests each tag name. Snapshots with tag names that fail the
     *     test are excluded from the result.
     * @return A sorted map of filtered snapshots keyed by their IDs, each associated with its tag
     *     name.
     * @throws RuntimeException if an IOException occurs during retrieval of snapshots.
     */
    public SortedMap<Snapshot, List<String>> tags(String branchName, Predicate<String> filter) {
        TreeMap<Snapshot, List<String>> tags =
                new TreeMap<>(Comparator.comparingLong(Snapshot::id));
        try {
            List<Path> paths =
                    listVersionedFileStatus(fileIO, tagDirectory(branchName), TAG_PREFIX)
                            .map(FileStatus::getPath)
                            .collect(Collectors.toList());

            for (Path path : paths) {
                String tagName = path.getName().substring(TAG_PREFIX.length());

                if (!filter.test(tagName)) {
                    continue;
                }
                // If the tag file is not found, it might be deleted by
                // other processes, so just skip this tag
                Snapshot.safelyFromPath(fileIO, path)
                        .ifPresent(
                                snapshot ->
                                        tags.computeIfAbsent(snapshot, s -> new ArrayList<>())
                                                .add(tagName));
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return tags;
    }

    public List<String> sortTagsOfOneSnapshot(List<String> tagNames) {
        return sortTagsOfOneSnapshot(DEFAULT_MAIN_BRANCH, tagNames);
    }

    public List<String> sortTagsOfOneSnapshot(String branchName, List<String> tagNames) {
        return tagNames.stream()
                .map(
                        name -> {
                            try {
                                return fileIO.getFileStatus(tagPath(branchName, name));
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        })
                .sorted(Comparator.comparingLong(FileStatus::getModificationTime))
                .map(fileStatus -> fileStatus.getPath().getName().substring(TAG_PREFIX.length()))
                .collect(Collectors.toList());
    }

    @VisibleForTesting
    public List<String> allTagNames() {
        return tags().values().stream().flatMap(Collection::stream).collect(Collectors.toList());
    }

    private int findIndex(Snapshot taggedSnapshot, List<Snapshot> taggedSnapshots) {
        for (int i = 0; i < taggedSnapshots.size(); i++) {
            if (taggedSnapshot.id() == taggedSnapshots.get(i).id()) {
                return i;
            }
        }
        throw new RuntimeException(
                String.format(
                        "Didn't find tag with snapshot id '%s'.This is unexpected.",
                        taggedSnapshot.id()));
    }

    public static List<Snapshot> findOverlappedSnapshots(
            List<Snapshot> taggedSnapshots, long beginInclusive, long endExclusive) {
        List<Snapshot> snapshots = new ArrayList<>();
        int right = findPreviousTag(taggedSnapshots, endExclusive);
        if (right >= 0) {
            int left = Math.max(findPreviousOrEqualTag(taggedSnapshots, beginInclusive), 0);
            for (int i = left; i <= right; i++) {
                snapshots.add(taggedSnapshots.get(i));
            }
        }
        return snapshots;
    }

    public static int findPreviousTag(List<Snapshot> taggedSnapshots, long targetSnapshotId) {
        for (int i = taggedSnapshots.size() - 1; i >= 0; i--) {
            if (taggedSnapshots.get(i).id() < targetSnapshotId) {
                return i;
            }
        }
        return -1;
    }

    private static int findPreviousOrEqualTag(
            List<Snapshot> taggedSnapshots, long targetSnapshotId) {
        for (int i = taggedSnapshots.size() - 1; i >= 0; i--) {
            if (taggedSnapshots.get(i).id() <= targetSnapshotId) {
                return i;
            }
        }
        return -1;
    }
}
