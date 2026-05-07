// Copyright (C) 2023 Siemens Mobility GmbH
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

import com.google.common.flogger.FluentLogger;

import com.google.gerrit.common.Nullable;
import com.google.gerrit.entities.BranchNameKey;
import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.PatchSet;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.extensions.restapi.UnprocessableEntityException;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.change.RebaseUtil;
import com.google.gerrit.server.notedb.ChangeNotes;
import com.google.gerrit.server.update.UpdateException;

import java.io.IOException;
import java.util.List;
import java.time.Instant;
import java.time.ZoneId;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheBuilder;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.MutableObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.NameConflictTreeWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.errors.ConfigInvalidException;

import static java.util.Objects.requireNonNull;


class UpdateTree implements AutoCloseable {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final Repository repo;
  private final RevWalk rw;
  private final ObjectReader reader;
  private final ObjectInserter inserter;
  private final UpdateUtil updateUtil;
  private final RebaseUtil rebaseUtil;

  private Change change;
  private RevCommit current;
  private RevCommit newParent;
  private RevCommit target;
  private RevCommit followBranch;
  private String reviewTarget;
  private String reviewFiles;
  private ReviewFilter reviewFilter;
  private boolean treeChanged;
  private boolean parentChanged;
  private boolean reviewTargetChanged;
  private boolean reviewFilesChanged;
  private ObjectId updatedTree;

  UpdateTree(Repository repo, UpdateUtil updateUtil, RebaseUtil rebaseUtil) {
    this.updateUtil = requireNonNull(updateUtil);
    this.rebaseUtil = requireNonNull(rebaseUtil);
    this.repo = requireNonNull(repo);

    this.inserter = requireNonNull(repo.newObjectInserter());
    this.reader = requireNonNull(inserter.newReader());
    this.rw = new RevWalk(reader);
  }

  public void close() {
    rw.close();
    reader.close();
    inserter.close();
  }

  /**
   * Select the change which is to be updated
   */
  public void useChange(Change change) throws RestApiException, IOException {
    this.change = requireNonNull(change);
    current = updateUtil.getCurrentCommit(repo, rw, change);
    if (current.getParentCount() != 1) {
      throw new UnprocessableEntityException("change must have a single parent");
    }
    newParent = rw.parseCommit(current.getParent(0));
    reviewTarget = updateUtil.getReviewTarget(current);
    target = updateUtil.getReferenceCommit(repo, rw, reviewTarget);
    List<String> lines = updateUtil.getReviewFiles(current);
    reviewFiles = String.join("\n", lines);
    reviewFilter = new ReviewFilter(lines);
  }

  public void newReviewTarget(String targetName) throws IOException {
    if (reviewTarget.equals(targetName))
      return;
    reviewTarget = targetName;
    reviewTargetChanged = true;
    target = updateUtil.getReferenceCommit(repo, rw, reviewTarget);
  }

  public String getReviewTarget() {
    return reviewTarget;
  }

  public boolean isValidReviewTarget() {
    return target != null;
  }

  public void useFollowBranch(String branchName) throws IOException {
    followBranch = updateUtil.getReferenceCommit(repo, rw, branchName);
    if (followBranch == null) {
      logger.atWarning().log("followBranch %s does not exist", branchName);
    }
  }

  public void newReviewFiles(String lines) {
    if (reviewFiles.equals(lines))
      return;
    reviewFiles = lines;
    reviewFilesChanged = true;
    reviewFilter = new ReviewFilter(lines);
  }

  public String getReviewFiles() {
    return reviewFiles;
  }

  void rebaseWhenNecessary(PatchSet patchset) throws IOException {
    try {
      // find a new parent commit based on new version of parent change/branch
      BranchNameKey branch = change.getDest();
      ObjectId baseId = rebaseUtil.findBaseRevision(patchset, branch, repo, rw, true);
      newParent = rw.parseCommit(baseId);
      parentChanged = true;
    } catch (RestApiException e) {
      // no rebase possible
    }
  }

  boolean isRebased() {
    return parentChanged;
  }

  /**
   * Walk all paths and choose elements from either the parent or the target tree
   */
  void rewritePaths() throws IOException {
    RevTree targetTree = rw.parseTree(target.getTree());
    if (reviewFilter.matchAll()) {
      // Without a Review-Files specification, use the whole Review-Target
      this.updatedTree = targetTree;
      this.treeChanged = !targetTree.equals(current.getTree());
      return;
    }

    RevTree parentTree = rw.parseTree(newParent.getTree());

    DirCache cache = DirCache.newInCore();
    DirCacheBuilder builder = cache.builder();

    MutableObjectId oid = new MutableObjectId();

    TreeWalk walk = new NameConflictTreeWalk(repo, reader);
    int idPar = walk.addTree(parentTree);
    int idTar = walk.addTree(targetTree);

    while (walk.next()) {

      int id;
      boolean isSubtree = walk.isSubtree();
      String path = walk.getPathString();
      ReviewFilter.Selected selected = reviewFilter.isPathToBeReviewed(path, isSubtree);

      if (selected == ReviewFilter.Selected.POSITIVE) {
        id = idTar;
      } else {
        id = idPar;
      }
      if (isSubtree && selected == ReviewFilter.Selected.NO_MATCH) {
        // not decided yet, have to check individual contents of tree
        walk.enterSubtree();
      } else if (isSubtree) {
        // add whole directory
        walk.getObjectId(oid, id);
        builder.addTree(walk.getRawPath(), 0, reader, oid);
      } else {
        // add individual file
        FileMode mode = walk.getFileMode(id);
        if (!mode.equals(FileMode.TYPE_MISSING)) {
          DirCacheEntry e = new DirCacheEntry(walk.getRawPath(), 0);
          walk.getObjectId(oid, id);
          e.setObjectId(oid);
          e.setFileMode(mode);
          builder.add(e);
        }
      }
    }
    builder.finish();

    this.updatedTree = cache.writeTree(inserter);
    this.treeChanged = !updatedTree.equals(current.getTree());
  }

  boolean hasCurrentPaths() throws IOException {
    RevTree currentTree = rw.parseTree(current.getTree());
    return this.updatedTree.equals(currentTree);
  }
  void getChangedPaths(List<String> added, List<String> updated, List<String> removed) throws IOException {
    RevCommit oldParent = rw.parseCommit(current.getParent(0));

    RevTree currentTree = rw.parseTree(current.getTree());
    RevTree oldParentTree = rw.parseTree(oldParent.getTree());
    RevTree newParentTree = rw.parseTree(newParent.getTree());

    TreeWalk walk = new NameConflictTreeWalk(repo, reader);
    int idOld = walk.addTree(currentTree);
    int idNew = walk.addTree(updatedTree);
    int idOldPar = walk.addTree(oldParentTree);
    int idNewPar = walk.addTree(newParentTree);
    walk.setRecursive(true);

    while (walk.next()) {

      FileMode modeOld = walk.getFileMode(idOld);
      FileMode modeNew = walk.getFileMode(idNew);
      FileMode modeOldPar = walk.getFileMode(idOldPar);
      FileMode modeNewPar = walk.getFileMode(idNewPar);

      if (walk.idEqual(idOld, idNew) && (modeOld == modeNew) &&
          walk.idEqual(idOldPar, idNewPar) && (modeOldPar == modeNewPar)) {
        // not changed
        continue;
      }

      boolean sameOld = walk.idEqual(idOld, idOldPar) && (modeOld == modeOldPar);
      boolean sameNew = walk.idEqual(idNew, idNewPar) && (modeNew == modeNewPar);
      String path = walk.getPathString();

      if (sameOld) {
        added.add(path);
      } else if (sameNew) {
        removed.add(path);
      } else {
        updated.add(path);
      }
    }
  }

  private String _getVersion(RevCommit commit, String prefix, String dropPrefix) throws IOException {
    assert commit != null;
    for (Ref ref : repo.getRefDatabase().getTipsWithSha1(commit)) {
      var name = ref.getName();
      if (name.startsWith(prefix)) {
        if (name.startsWith(dropPrefix)) {
          name = name.substring(dropPrefix.length());
        }
        return name;
      }
    }

    return commit.getId().abbreviate(7).name();
  }

  public String getTargetVersion(String prefix, String dropPrefix) throws IOException {
    return _getVersion(target, prefix, dropPrefix);
  }

  public String getFollowVersion(String prefix, String dropPrefix) throws IOException {
    if (followBranch == null) {
      return "";
    }
    return _getVersion(followBranch, prefix, dropPrefix);
  }

  public int createPatchSet(
        CurrentUser user, String reviewTargetFooter, String reviewFilesFooter, ChangeNotes notes
  ) throws IOException, ConfigInvalidException, UpdateException, RestApiException {
    String currentMessage = current.getFullMessage();
    String message = getUpdatedMessage(currentMessage, reviewTargetFooter, reviewFilesFooter);
    boolean sameMsg = message.equals(currentMessage);
    boolean sameTree = !treeChanged;
    boolean sameParent = !parentChanged;
    logger.atFine().log("createPatchSet sameMsg=%s sameTree=%s sameParent=%s", sameMsg, sameTree, sameParent);
    if (sameMsg && sameTree && sameParent) {
      return 0;
    }

    RevCommit updated = getUpdatedCommit(user, message);
    String patchSetDesc = getPatchSetDescription();
    String patchSetMsg = getPatchSetMessage();

    return updateUtil.createPatchSet(repo, rw, inserter, user, change, updated, patchSetDesc, patchSetMsg, notes);
  }

  /**
   * Get the commit message for the updated commit.
   * @param original the old message
   * @param reviewTargetFooter the Review-Target: footer to insert
   * @param reviewFilesFooter the Review-Files: footer to insert
   * @return the updated message
   */
  private String getUpdatedMessage(String original, String reviewTargetFooter, String reviewFilesFooter) {
    String message = original;

    message = updateUtil.insertFooters(message, reviewTargetFooter, reviewTarget);
    message = updateUtil.insertFooters(message, reviewFilesFooter, reviewFiles);

    return message;
  }

  /**
   * Get the message which is shown in the change log
   */
  private String getPatchSetMessage() {
    if (reviewTargetChanged && reviewFilesChanged) {
      return "Updated Review-Target " + reviewTarget + " and Review-Files";
    }
    if (reviewTargetChanged) {
      return "Updated Review-Target " + reviewTarget;
    }
    if (reviewFilesChanged) {
      return "Updated Review-Files";
    }
    if (treeChanged) {
      return "Updated to match current Review-Target " + reviewTarget;
    }
    if (parentChanged) {
      return "Rebased to new parent change";
    }
    return "Updated commit message";
  }

  /**
   * Get the short description which is shown in the patchset selection drop-down
   */
  private @Nullable String getPatchSetDescription() {
    if (reviewTargetChanged) {
      return "Review-Target " + reviewTarget;
    }
    if (reviewFilesChanged) {
      return "Changed Review-Files";
    }
    if (parentChanged) {
      return "Rebase";
    }
    return null;
  }

  private RevCommit getUpdatedCommit(CurrentUser user, String message) throws IOException {
    ZoneId tz = current.getCommitterIdent().getZoneId();
    PersonIdent committer = user.asIdentifiedUser().newCommitterIdent(Instant.now(), tz);

    CommitBuilder updated = new CommitBuilder();

    updated.setAuthor(current.getAuthorIdent());
    updated.setCommitter(committer);
    updated.setMessage(message);
    updated.setParentId(newParent);
    updated.setTreeId(updatedTree);

    return rw.parseCommit(commit(updated));
  }

  private ObjectId commit(CommitBuilder builder)
      throws IOException {
    ObjectId id = inserter.insert(builder);
    inserter.flush();
    return id;
  }
}
