package com.googlesource.gerrit.plugins.reviewtarget;

import com.google.gerrit.entities.BranchNameKey;
import com.google.gerrit.entities.Change;
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.extensions.restapi.PreconditionFailedException;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.change.ChangeResource;
import com.google.gerrit.server.permissions.ChangePermission;
import com.google.gerrit.server.permissions.PermissionBackend;
import com.google.gerrit.server.query.change.ChangeData;
import com.google.inject.Provider;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.time.Instant;

import static com.google.gerrit.testing.GerritJUnit.assertThrows;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class FollowPreconditionsTest {

  private Change change;
  @Mock private ChangeData changeData;
  @Mock private ChangeResource rsrc;
  @Mock private Configuration cfg;
  @Mock private CurrentUser currentUser;
  @Mock private Provider<CurrentUser> userProvider;
  @Mock private PermissionBackend permissionBackend;
  @Mock private PermissionBackend.ForChange forChange;
  @Mock private PermissionBackend.WithUser userPermission;

  @Before
  public void setUp() throws Exception {
    change = new Change(null, null, null, null, Instant.now());
    when(permissionBackend.user(currentUser)).thenReturn(userPermission);
    when(rsrc.getChange()).thenReturn(change);
    when(rsrc.getChangeData()).thenReturn(changeData);
    when(userPermission.change(changeData)).thenReturn(forChange);
    when(userProvider.get()).thenReturn(currentUser);
  }

  private FollowPreconditions preconditions() {
    return new FollowPreconditions(cfg, userProvider, permissionBackend);
  }

  @Test
  public void onReviewBranch_exactMatch() {
    whenOnBranch("review");
    when(cfg.getReviewBranch()).thenReturn("refs/heads/review");
    assertTrue(preconditions().onReviewBranch(change));
  }

  @Test
  public void onReviewBranch_onlyExactMatch() {
    whenOnBranch("review-1");
    when(cfg.getReviewBranch()).thenReturn("refs/heads/review");
    assertFalse(preconditions().onReviewBranch(change));
  }

  @Test
  public void onReviewBranch_matchesWildcard() {
    whenOnBranch("review-1");
    when(cfg.getReviewBranch()).thenReturn("refs/heads/review-*");
    assertTrue(preconditions().onReviewBranch(change));
  }

  @Test
  public void onReviewBranch_matchesFolder() {
    // FastIgnoreRule treats a pattern without a trailing slash as matching both
    // a file and a directory with that name, so "review" matches "review/1".
    whenOnBranch("review/1");
    when(cfg.getReviewBranch()).thenReturn("refs/heads/review");
    assertTrue(preconditions().onReviewBranch(change));
  }

  @Test
  public void assertCanChangeReviewTarget_passes() throws PreconditionFailedException {
    whenOnBranch("review");
    when(cfg.getReviewBranch()).thenReturn("refs/heads/review");
    preconditions().assertCanChangeReviewTarget(rsrc);
  }

  @Test
  public void assertCanChangeReviewTarget_wrongBranch() {
    whenOnBranch("other");
    when(cfg.getReviewBranch()).thenReturn("refs/heads/review");
    assertThrows(PreconditionFailedException.class,
        () -> preconditions().assertCanChangeReviewTarget(rsrc)
    );
  }

  @Test
  public void assertCanChangeReviewTarget_rejectMerged() {
    whenChangeMerged();
    when(cfg.getReviewBranch()).thenReturn("refs/heads/review");
    assertThrows(PreconditionFailedException.class,
        () -> preconditions().assertCanChangeReviewTarget(rsrc)
    );
  }

  @Test
  public void assertCanChangeReviewTarget_rejectAbandoned() {
    whenChangeAbandoned();
    when(cfg.getReviewBranch()).thenReturn("refs/heads/review");
    assertThrows(PreconditionFailedException.class,
        () -> preconditions().assertCanChangeReviewTarget(rsrc)
    );
  }

  @Test
  public void canAddTest_passes() throws AuthException {
    when(forChange.testOrFalse(ChangePermission.ADD_PATCH_SET)).thenReturn(true);
    when(cfg.getReviewBranch()).thenReturn("refs/heads/review");
    preconditions().assertAddPatchSetPermission(rsrc);
  }

  @Test
  public void canAddTest_rejects() {
    when(forChange.testOrFalse(ChangePermission.ADD_PATCH_SET)).thenReturn(false);
    when(cfg.getReviewBranch()).thenReturn("refs/heads/review");
    assertThrows(AuthException.class,
        () -> preconditions().assertAddPatchSetPermission(rsrc)
    );
  }

  private void whenOnBranch(String branchName) {
    change.setDest(BranchNameKey.create("repo", branchName));
  }

  private void whenChangeMerged() {
    change.setStatus(Change.Status.MERGED);
  }

  private void whenChangeAbandoned() {
    change.setStatus(Change.Status.ABANDONED);
  }
}
