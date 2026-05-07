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

import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.PatchSet;
import com.google.gerrit.server.change.RebaseUtil;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class UpdateTreeTest {

  @Mock private Change change;
  @Mock private PatchSet.Id patchsetId;
  @Mock private ObjectInserter inserter;
  @Mock private ObjectReader reader;
  @Mock private RebaseUtil rebaseUtil;
  @Mock private Repository repo;
  @Mock private UpdateUtil updateUtil;

  private UpdateTree update;

  @Before
  public void setUp() {
    when(repo.newObjectInserter()).thenReturn(inserter);
    when(inserter.newReader()).thenReturn(reader);
    update = new UpdateTree(repo, updateUtil, rebaseUtil);
  }

  @Test
  public void test_construction() {
    assertThat(update).isNotNull();
  }
}
