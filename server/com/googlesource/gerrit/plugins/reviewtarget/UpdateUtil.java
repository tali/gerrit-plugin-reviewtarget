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

import com.google.common.flogger.FluentLogger;

import com.google.gerrit.common.Nullable;
import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.PatchSet;
import com.google.gerrit.extensions.api.changes.NotifyHandling;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.extensions.restapi.UnprocessableEntityException;
import com.google.gerrit.server.ChangeUtil;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.change.NotifyResolver;
import com.google.gerrit.server.change.PatchSetInserter;
import com.google.gerrit.server.notedb.ChangeNotes;
import com.google.gerrit.server.update.BatchUpdate;
import com.google.gerrit.server.update.UpdateException;
import com.google.gerrit.server.util.time.TimeUtil;
import com.google.inject.Inject;
import com.google.inject.Singleton;

import java.io.IOException;
import java.util.List;

import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.errors.ConfigInvalidException;

import static java.util.Objects.requireNonNull;


@Singleton
class UpdateUtil {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final Configuration cfg;
  private final PatchSetInserter.Factory patchSetInserterFactory;
  private final NotifyResolver notifyResolver;
  private final BatchUpdate.Factory updateFactory;

  @Inject
  UpdateUtil(
      Configuration cfg,
      PatchSetInserter.Factory patchSetInserterFactory,
      BatchUpdate.Factory updateFactory,
      NotifyResolver notifyResolver) {
    this.cfg = requireNonNull(cfg);
    this.patchSetInserterFactory = requireNonNull(patchSetInserterFactory);
    this.updateFactory = requireNonNull(updateFactory);
    this.notifyResolver = requireNonNull(notifyResolver);
  }

  RevCommit getReferenceCommit(Repository repo, RevWalk rw, String refName) throws IOException {
    Ref ref = repo.findRef(refName);
    if (ref == null)
      return null;

    ObjectId refId = ref.getObjectId();
    if (refId == null)
      return null;

    return rw.parseCommit(refId);
  }

  public String getReviewTarget(RevCommit current) throws RestApiException {
    String footerName = cfg.getReviewTargetFooter();
    List<String> footerLines = current.getFooterLines(footerName);
    if (footerLines.size() != 1) {
      throw new UnprocessableEntityException("need exactly one Review-Target footer");
    }
    return footerLines.get(0);
  }

  public List<String> getReviewFiles(RevCommit current) {
    String footerName = cfg.getReviewFilesFooter();
    return current.getFooterLines(footerName);
  }

  private boolean hasChangeId(String message, int start) {
    return message.indexOf("\nChange-Id:", start) >= 0;
  }

  public String insertFooters(String oldMessage, String key, String values) {
    final StringBuilder message = new StringBuilder();

    // search for start and end of any existing footer within the last paragraph
    int start = oldMessage.lastIndexOf("\n\n");
    boolean existingFooter = false;
    if (start >= 0) {
      existingFooter = hasChangeId(oldMessage, start + 1);
      start = oldMessage.indexOf("\n" + key + ":", start + 1);
    }
    if (start >= 0) {
        // include message before old footer
        message.append(oldMessage, 0, start + 1);
    } else {
      // we already have footers, but not for our key
      message.append(oldMessage);
    }
    if (message.charAt(message.length()-1) != '\n') {
      // message did not end with newline, insert one
      message.append("\n");
    }
    if (!existingFooter) {
      // start new footer paragraph
      message.append("\n");
    }
    // append our new footer entries
    for (String value : values.split("\n")) {
      value = value.strip();
      if (value.isEmpty()) continue;

      message.append(key);
      message.append(": ");
      message.append(value);
      message.append("\n");
    }
    // append the rest of the original footers
    while (start >= 0) {
      // skip this line
      start = oldMessage.indexOf("\n", start + 1);
      if (start < 0) {
        // already at the end (no newline)
        break;
      }
      // copy everything up to the next footer with our key
      int next = oldMessage.indexOf("\n" + key + ":", start);
      if (next > 0) {
        message.append(oldMessage, start + 1, next + 1);
        start = next;
      } else {
        message.append(oldMessage.substring(start + 1));
        start = -1;
      }
    }

    return message.toString();
  }

  public int createPatchSet(
        Repository repo, RevWalk rw, ObjectInserter inserter,
        CurrentUser user,
        Change change, RevCommit updated, String patchSetDesc, String patchSetMsg,
        ChangeNotes notes
  ) throws IOException, ConfigInvalidException, UpdateException, RestApiException {
    PatchSet.Id psId = ChangeUtil.nextPatchSetId(repo, change.currentPatchSetId());

    StringBuilder builder = new StringBuilder("Created patch set ").append(psId.get()).append(": ");
    builder.append(patchSetMsg);
    String message = builder.toString();

    logger.atFine().log("creating patchSet %s: msg=%s desc=%s", psId.get(), patchSetMsg, patchSetDesc);
    PatchSetInserter patchSet =
        patchSetInserterFactory
            .create(notes, psId, updated)
            .setSendEmail(!change.isWorkInProgress())
            .setDescription(patchSetDesc)
            .setMessage(message);

    try (BatchUpdate bu = updateFactory.create(change.getProject(), user, TimeUtil.now())) {
      bu.setRepository(repo, rw, inserter);
      bu.setNotify(notifyResolver.resolve(NotifyHandling.ALL, null));
      bu.addOp(change.getId(), patchSet);
      bu.execute();
    }
    logger.atFine().log("created patchSet %s", psId.get());

    return psId.get();
  }

  public RevCommit getCurrentCommit(Repository repo, RevWalk rw, Change change) throws IOException, RestApiException {
    PatchSet.Id id = change.currentPatchSetId();
    if (id == null) {
      throw new UnprocessableEntityException("change must have a current patchset");
    }
    Ref ref = repo.exactRef(id.toRefName());
    if (ref == null) {
      throw new UnprocessableEntityException("patchset ref not found: " + id.toRefName());
    }
    return rw.parseCommit(ref.getObjectId());
  }
}
