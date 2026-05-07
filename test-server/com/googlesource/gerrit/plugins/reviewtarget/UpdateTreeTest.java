// Copyright (C) 2022 Siemens Mobility GmbH
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.googlesource.gerrit.plugins.reviewtarget;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.google.gerrit.entities.Change;
import com.google.gerrit.server.change.RebaseUtil;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.TreeFormatter;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class UpdateTreeTest {

  @Mock private UpdateUtil updateUtil;
  @Mock private RebaseUtil rebaseUtil;
  @Mock private Change change;

  private InMemoryRepository repo;
  private UpdateTree update;

  @Before
  public void setUp() {
    repo = new InMemoryRepository(new DfsRepositoryDescription("test"));
    update = new UpdateTree(repo, updateUtil, rebaseUtil);
  }

  @After
  public void tearDown() {
    update.close();
    repo.close();
  }

  private ObjectId emptyTree() throws Exception {
    try (ObjectInserter ins = repo.newObjectInserter()) {
      ObjectId id = ins.insert(new TreeFormatter());
      ins.flush();
      return id;
    }
  }

  /** Build a flat tree (all files at root) from a name→content map. */
  private ObjectId makeTree(Map<String, String> files) throws Exception {
    try (ObjectInserter ins = repo.newObjectInserter()) {
      TreeFormatter tf = new TreeFormatter();
      files.entrySet().stream()
          .sorted(Map.Entry.comparingByKey())
          .forEach(e -> {
            try {
              byte[] bytes = e.getValue().getBytes(StandardCharsets.UTF_8);
              ObjectId blob = ins.insert(Constants.OBJ_BLOB, bytes);
              tf.append(e.getKey(), FileMode.REGULAR_FILE, blob);
            } catch (IOException ex) {
              throw new UncheckedIOException(ex);
            }
          });
      ObjectId id = ins.insert(tf);
      ins.flush();
      return id;
    }
  }

  private RevCommit makeCommit(String message, ObjectId tree, ObjectId... parents) throws Exception {
    try (ObjectInserter ins = repo.newObjectInserter();
         RevWalk rw = new RevWalk(repo)) {
      PersonIdent ident = new PersonIdent("Test", "test@example.com");
      CommitBuilder cb = new CommitBuilder();
      cb.setAuthor(ident);
      cb.setCommitter(ident);
      cb.setMessage(message);
      cb.setTreeId(tree);
      cb.setParentIds(parents);
      ObjectId id = ins.insert(cb);
      ins.flush();
      return rw.parseCommit(id);
    }
  }

  private void useChange(RevCommit current, RevCommit target, List<String> reviewFiles)
      throws Exception {
    when(updateUtil.getCurrentCommit(any(), any(), any())).thenReturn(current);
    when(updateUtil.getReviewTarget(current)).thenReturn("refs/tags/v1");
    when(updateUtil.getReferenceCommit(any(), any(), eq("refs/tags/v1"))).thenReturn(target);
    when(updateUtil.getReviewFiles(current)).thenReturn(reviewFiles);
    update.useChange(change);
  }

  // -------------------------------------------------------------------------
  // Construction / basic setup
  // -------------------------------------------------------------------------

  @Test
  public void test_construction() {
    assertThat(update).isNotNull();
  }

  @Test
  public void test_invalidReviewTarget() throws Exception {
    ObjectId tree = emptyTree();
    RevCommit parent = makeCommit("parent\n", tree);
    RevCommit current = makeCommit("current\n\nReview-Target: refs/tags/v1\n", tree, parent);

    when(updateUtil.getCurrentCommit(any(), any(), any())).thenReturn(current);
    when(updateUtil.getReviewTarget(current)).thenReturn("refs/tags/v1");
    when(updateUtil.getReferenceCommit(any(), any(), eq("refs/tags/v1"))).thenReturn(null);
    when(updateUtil.getReviewFiles(current)).thenReturn(List.of());

    update.useChange(change);

    assertThat(update.isValidReviewTarget()).isFalse();
  }

  @Test
  public void test_validReviewTarget() throws Exception {
    ObjectId tree = emptyTree();
    RevCommit parent = makeCommit("parent\n", tree);
    RevCommit current = makeCommit("current\n\nReview-Target: refs/tags/v1\n", tree, parent);
    RevCommit target = makeCommit("target\n", tree);

    useChange(current, target, List.of());

    assertThat(update.isValidReviewTarget()).isTrue();
  }

  // -------------------------------------------------------------------------
  // rewritePaths / hasCurrentPaths — matchAll (no Review-Files)
  // -------------------------------------------------------------------------

  @Test
  public void rewritePaths_matchAll_sameTree() throws Exception {
    ObjectId tree = emptyTree();
    RevCommit parent = makeCommit("parent\n", tree);
    RevCommit current = makeCommit("current\n\nReview-Target: refs/tags/v1\n", tree, parent);
    RevCommit target = makeCommit("target\n", tree);

    useChange(current, target, List.of());
    update.rewritePaths();

    assertThat(update.hasCurrentPaths()).isTrue();
  }

  @Test
  public void rewritePaths_matchAll_treeChanged() throws Exception {
    // Target has a different tree — hasCurrentPaths() should be false.
    // This also verifies the bug fix: treeChanged must be set in the matchAll path.
    ObjectId emptyTree = emptyTree();
    ObjectId targetTree = makeTree(Map.of("file.txt", "new content"));
    RevCommit parent = makeCommit("parent\n", emptyTree);
    RevCommit current = makeCommit("current\n\nReview-Target: refs/tags/v1\n", emptyTree, parent);
    RevCommit target = makeCommit("target\n", targetTree);

    useChange(current, target, List.of());
    update.rewritePaths();

    assertThat(update.hasCurrentPaths()).isFalse();
  }

  // -------------------------------------------------------------------------
  // rewritePaths — with Review-Files filter
  // -------------------------------------------------------------------------

  @Test
  public void rewritePaths_withFilter_selectsMatchingFiles() throws Exception {
    // a.txt is selected by the filter → comes from target.
    // b.txt is not selected → stays from parent (newParent).
    ObjectId parentTree = makeTree(Map.of("a.txt", "parent-a", "b.txt", "parent-b"));
    ObjectId targetTree = makeTree(Map.of("a.txt", "target-a", "b.txt", "target-b"));
    RevCommit parent = makeCommit("parent\n", parentTree);
    RevCommit current = makeCommit("current\n\nReview-Target: refs/tags/v1\n", parentTree, parent);
    RevCommit target = makeCommit("target\n", targetTree);

    useChange(current, target, List.of("a.txt"));
    update.rewritePaths();

    List<String> added = new ArrayList<>(), updated = new ArrayList<>(), removed = new ArrayList<>();
    update.getChangedPaths(added, updated, removed);

    // a.txt was from parent in current, now comes from target → added to review
    assertThat(added).containsExactly("a.txt");
    assertThat(updated).isEmpty();
    assertThat(removed).isEmpty();
  }

  // -------------------------------------------------------------------------
  // getChangedPaths
  // -------------------------------------------------------------------------

  @Test
  public void getChangedPaths_noDiff() throws Exception {
    ObjectId tree = emptyTree();
    RevCommit parent = makeCommit("parent\n", tree);
    RevCommit current = makeCommit("current\n\nReview-Target: refs/tags/v1\n", tree, parent);
    RevCommit target = makeCommit("target\n", tree);

    useChange(current, target, List.of());
    update.rewritePaths();

    List<String> added = new ArrayList<>(), updated = new ArrayList<>(), removed = new ArrayList<>();
    update.getChangedPaths(added, updated, removed);

    assertThat(added).isEmpty();
    assertThat(updated).isEmpty();
    assertThat(removed).isEmpty();
  }

  @Test
  public void getChangedPaths_fileAddedByTarget() throws Exception {
    // Target introduces a file not present in parent/current.
    // From a review perspective: the file is newly included → added.
    ObjectId emptyTree = emptyTree();
    ObjectId targetTree = makeTree(Map.of("new.txt", "content"));
    RevCommit parent = makeCommit("parent\n", emptyTree);
    RevCommit current = makeCommit("current\n\nReview-Target: refs/tags/v1\n", emptyTree, parent);
    RevCommit target = makeCommit("target\n", targetTree);

    useChange(current, target, List.of());
    update.rewritePaths();

    List<String> added = new ArrayList<>(), updated = new ArrayList<>(), removed = new ArrayList<>();
    update.getChangedPaths(added, updated, removed);

    assertThat(added).containsExactly("new.txt");
    assertThat(updated).isEmpty();
    assertThat(removed).isEmpty();
  }

  @Test
  public void getChangedPaths_fileUpdatedByTarget() throws Exception {
    // File was already part of the review (differs from parent in current),
    // and target has a new version of it → updated.
    ObjectId parentTree = makeTree(Map.of("file.txt", "v1"));
    ObjectId currentTree = makeTree(Map.of("file.txt", "v2"));
    ObjectId targetTree = makeTree(Map.of("file.txt", "v3"));
    RevCommit parent = makeCommit("parent\n", parentTree);
    RevCommit current = makeCommit("current\n\nReview-Target: refs/tags/v1\n", currentTree, parent);
    RevCommit target = makeCommit("target\n", targetTree);

    useChange(current, target, List.of());
    update.rewritePaths();

    List<String> added = new ArrayList<>(), updated = new ArrayList<>(), removed = new ArrayList<>();
    update.getChangedPaths(added, updated, removed);

    assertThat(updated).containsExactly("file.txt");
    assertThat(added).isEmpty();
    assertThat(removed).isEmpty();
  }

  @Test
  public void getChangedPaths_fileRemovedFromReview() throws Exception {
    // File was specifically different from parent in the current review.
    // Target has the same content as the parent → file goes back to parent version → removed.
    ObjectId parentTree = makeTree(Map.of("file.txt", "from-parent"));
    ObjectId currentTree = makeTree(Map.of("file.txt", "review-specific"));
    ObjectId targetTree = makeTree(Map.of("file.txt", "from-parent"));
    RevCommit parent = makeCommit("parent\n", parentTree);
    RevCommit current = makeCommit("current\n\nReview-Target: refs/tags/v1\n", currentTree, parent);
    RevCommit target = makeCommit("target\n", targetTree);

    useChange(current, target, List.of());
    update.rewritePaths();

    List<String> added = new ArrayList<>(), updated = new ArrayList<>(), removed = new ArrayList<>();
    update.getChangedPaths(added, updated, removed);

    assertThat(removed).containsExactly("file.txt");
    assertThat(added).isEmpty();
    assertThat(updated).isEmpty();
  }
}
