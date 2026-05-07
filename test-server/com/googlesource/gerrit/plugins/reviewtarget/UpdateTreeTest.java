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

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.lib.CommitBuilder;
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

    when(updateUtil.getCurrentCommit(any(), any(), any())).thenReturn(current);
    when(updateUtil.getReviewTarget(current)).thenReturn("refs/tags/v1");
    when(updateUtil.getReferenceCommit(any(), any(), eq("refs/tags/v1"))).thenReturn(target);
    when(updateUtil.getReviewFiles(current)).thenReturn(List.of());

    update.useChange(change);

    assertThat(update.isValidReviewTarget()).isTrue();
  }

  @Test
  public void test_rewritePaths_unchanged() throws Exception {
    // When the target has the same tree as the current commit, hasCurrentPaths() should be true.
    ObjectId tree = emptyTree();
    RevCommit parent = makeCommit("parent\n", tree);
    RevCommit current = makeCommit("current\n\nReview-Target: refs/tags/v1\n", tree, parent);
    RevCommit target = makeCommit("target\n", tree);

    when(updateUtil.getCurrentCommit(any(), any(), any())).thenReturn(current);
    when(updateUtil.getReviewTarget(current)).thenReturn("refs/tags/v1");
    when(updateUtil.getReferenceCommit(any(), any(), eq("refs/tags/v1"))).thenReturn(target);
    when(updateUtil.getReviewFiles(current)).thenReturn(List.of());

    update.useChange(change);
    update.rewritePaths();

    assertThat(update.hasCurrentPaths()).isTrue();
  }

  @Test
  public void test_getChangedPaths_noDiff() throws Exception {
    // No paths reported when current and updated trees are identical.
    ObjectId tree = emptyTree();
    RevCommit parent = makeCommit("parent\n", tree);
    RevCommit current = makeCommit("current\n\nReview-Target: refs/tags/v1\n", tree, parent);
    RevCommit target = makeCommit("target\n", tree);

    when(updateUtil.getCurrentCommit(any(), any(), any())).thenReturn(current);
    when(updateUtil.getReviewTarget(current)).thenReturn("refs/tags/v1");
    when(updateUtil.getReferenceCommit(any(), any(), eq("refs/tags/v1"))).thenReturn(target);
    when(updateUtil.getReviewFiles(current)).thenReturn(List.of());

    update.useChange(change);
    update.rewritePaths();

    List<String> added = new ArrayList<>();
    List<String> updated = new ArrayList<>();
    List<String> removed = new ArrayList<>();
    update.getChangedPaths(added, updated, removed);

    assertThat(added).isEmpty();
    assertThat(updated).isEmpty();
    assertThat(removed).isEmpty();
  }
}
