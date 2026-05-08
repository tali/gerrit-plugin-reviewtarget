/**
 * @license
 * Copyright (C) 2022 Siemens Mobility GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import '@gerritcodereview/typescript-api/gerrit';
import {EventType} from '@gerritcodereview/typescript-api/plugin';
import {ChangeUpdatesPublisher} from '@gerritcodereview/typescript-api/change-updates';
import {ChangeInfo, RevisionInfo} from '@gerritcodereview/typescript-api/rest-api';
import {ActionType} from '@gerritcodereview/typescript-api/change-actions';

import {changeFollowGet, changeFollowGetById} from './api';
import {SelectReviewTargetDialog} from './dialog';

const POLL_INTERVAL_MS = 5 * 60 * 1000;

window.Gerrit.install(plugin => {
  const restApi = plugin.restApi();
  var selectAction: string | null;

  // Track the follow_version seen at page load so we can detect branch advances.
  let knownFollowVersion: string | null = null;

  let pollTimer: ReturnType<typeof setInterval> | null = null;

  const publisher: ChangeUpdatesPublisher = {
    subscribe(_repo: string, changeNum: number, callback: () => void) {
      if (pollTimer !== null) clearInterval(pollTimer);
      pollTimer = setInterval(async () => {
        if (knownFollowVersion === null) return;
        try {
          const info = await changeFollowGetById(restApi, changeNum);
          if (!info.on_review_branch) return;
          if (info.follow_version !== knownFollowVersion) {
            callback();
          }
        } catch (_e) {
          // ignore transient errors
        }
      }, POLL_INTERVAL_MS);
    },
    unsubscribe() {
      if (pollTimer !== null) {
        clearInterval(pollTimer);
        pollTimer = null;
      }
      knownFollowVersion = null;
    },
  };

  plugin.changeUpdates().register(publisher);

  let lastManagedChangeId: string | null = null;

  function applyActions(change: ChangeInfo) {
    const actions = plugin.changeActions();
    if (selectAction != null) {
      actions.remove(selectAction);
    }
    selectAction = actions.add(ActionType.REVISION, 'select-reviewtarget');
    actions.setEnabled(selectAction, true);
    actions.setLabel(selectAction, "Select");
    actions.setTitle(selectAction, `Change Review-Target or Review-Files`);
    actions.setIcon(selectAction, "rule");
    actions.addTapListener(selectAction, async () => {
      const info = await changeFollowGet(restApi, change);
      const popupApi = await plugin.popup();
      const openDialog = await popupApi.open();
      var dialog = new SelectReviewTargetDialog();
      dialog.initialize(info);
      dialog.plugin = plugin;
      dialog.change = change;
      dialog.popupApi = popupApi;
      openDialog.appendContent(dialog);
    });
    // hide actions which would mess with our managed changes
    actions.setActionHidden(ActionType.CHANGE, 'edit', true);
    actions.setActionHidden(ActionType.CHANGE, 'rebase', true);
    actions.setActionHidden(ActionType.REVISION, 'cherrypick', true);
  }

  plugin.on(EventType.SHOW_CHANGE, async (change: ChangeInfo, _revision: RevisionInfo, _mergeable: boolean) => {
    if (change.id === undefined) return;
    const info = await changeFollowGet(restApi, change);
    if (!info.on_review_branch) {
      // this change is not managed by our plugin
      knownFollowVersion = null;
      lastManagedChangeId = null;
      return;
    }
    knownFollowVersion = info.follow_version;
    lastManagedChangeId = change.id;
    applyActions(change);
  });

  // gr-change-actions resets additionalActions and hiddenActions when the user
  // navigates back from a diff view without a full page reload. SHOW_CHANGE does
  // not re-fire in that case because the change model serves cached data, but
  // SHOW_REVISION_ACTIONS always fires after gr-change-actions.reload().
  plugin.on(EventType.SHOW_REVISION_ACTIONS, (_revisionActions: unknown, change: ChangeInfo) => {
    if (knownFollowVersion === null) return;
    if (change.id !== lastManagedChangeId) return;
    applyActions(change);
  });
});
