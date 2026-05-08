/**
 * @license
 * Copyright (C) 2025 Siemens Mobility GmbH
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
import './test/test-setup';
import {assert} from '@open-wc/testing';
import sinon from 'sinon';
import {EventType} from '@gerritcodereview/typescript-api/plugin';
import {ActionType} from '@gerritcodereview/typescript-api/change-actions';
import {ChangeInfo} from '@gerritcodereview/typescript-api/rest-api';
import {FollowInfo} from './api';

function makeFollowInfo(overrides: Partial<FollowInfo> = {}): FollowInfo {
  return {
    on_review_branch: true,
    valid_review_target: true,
    rebase_required: false,
    new_patchset_id: 0,
    version: 'v1',
    follow_branch: 'refs/heads/follow',
    follow_version: 'v2',
    review_target: 'refs/tags/v1',
    review_files: '',
    added_paths: [],
    updated_paths: [],
    removed_paths: [],
    ...overrides,
  };
}

suite('plugin', () => {
  // Stubs created once; histories are reset in setup() between tests.
  let sandbox: sinon.SinonSandbox;
  let getStub: sinon.SinonStub;
  let addActionStub: sinon.SinonStub;
  let removeActionStub: sinon.SinonStub;
  let addTapListenerStub: sinon.SinonStub;
  let setActionHiddenStub: sinon.SinonStub;
  let popupOpenStub: sinon.SinonStub;

  // Event handlers registered by the plugin via plugin.on()
  const handlers = new Map<string, Function>();

  const managedChange = {id: 'change-1'} as unknown as ChangeInfo;
  const otherChange = {id: 'change-99'} as unknown as ChangeInfo;

  suiteSetup(async () => {
    sandbox = sinon.createSandbox();
    getStub = sandbox.stub().resolves(makeFollowInfo());
    addActionStub = sandbox.stub().returns('action-key');
    removeActionStub = sandbox.stub();
    addTapListenerStub = sandbox.stub();
    setActionHiddenStub = sandbox.stub();
    popupOpenStub = sandbox.stub().resolves({appendContent: sandbox.stub()});

    const mockActions = {
      add: addActionStub,
      remove: removeActionStub,
      setEnabled: sandbox.stub(),
      setLabel: sandbox.stub(),
      setTitle: sandbox.stub(),
      setIcon: sandbox.stub(),
      addTapListener: addTapListenerStub,
      setActionHidden: setActionHiddenStub,
    };

    // plugin.ts calls window.Gerrit.install() at module evaluation time.
    // We override install before the dynamic import so the callback gets
    // our mock plugin instead of the real one.
    (window.Gerrit as unknown as {install: Function}).install =
      (fn: (p: unknown) => void) => {
        fn({
          restApi: () => ({get: getStub}),
          changeActions: () => mockActions,
          changeUpdates: () => ({register: () => {}}),
          on: (type: string, h: Function) => handlers.set(type, h),
          popup: sandbox.stub().resolves({open: popupOpenStub}),
        });
      };

    await import('./plugin');
  });

  suiteTeardown(() => {
    sandbox.restore();
  });

  setup(() => {
    sandbox.resetHistory();
    getStub.resolves(makeFollowInfo());
    addActionStub.returns('action-key');
  });

  // Trigger the SHOW_CHANGE handler and wait for the async GET to settle.
  async function showChange(change: ChangeInfo, overrides: Partial<FollowInfo> = {}) {
    getStub.resolves(makeFollowInfo(overrides));
    await handlers.get(EventType.SHOW_CHANGE)!(change, {}, false);
  }

  function showRevisionActions(change: ChangeInfo) {
    handlers.get(EventType.SHOW_REVISION_ACTIONS)!(undefined, change);
  }

  // ---------------------------------------------------------------------------
  // SHOW_CHANGE basics
  // ---------------------------------------------------------------------------

  suite('SHOW_CHANGE', () => {
    test('registers the select action when on a review branch', async () => {
      await showChange(managedChange);
      assert.isTrue(addActionStub.calledOnce);
    });

    test('does not register an action when not on a review branch', async () => {
      await showChange(managedChange, {on_review_branch: false});
      assert.isFalse(addActionStub.called);
    });

    test('hides edit, rebase, and cherrypick actions', async () => {
      await showChange(managedChange);
      assert.isTrue(setActionHiddenStub.calledWith(ActionType.CHANGE, 'edit', true));
      assert.isTrue(setActionHiddenStub.calledWith(ActionType.CHANGE, 'rebase', true));
      assert.isTrue(setActionHiddenStub.calledWith(ActionType.REVISION, 'cherrypick', true));
    });
  });

  // ---------------------------------------------------------------------------
  // SHOW_REVISION_ACTIONS — the navigation-bug fix
  // ---------------------------------------------------------------------------

  suite('SHOW_REVISION_ACTIONS', () => {
    test('re-adds select button after navigating back from diff view', async () => {
      await showChange(managedChange);
      sandbox.resetHistory();

      showRevisionActions(managedChange);

      assert.isTrue(addActionStub.calledOnce);
    });

    test('re-hides edit/rebase/cherrypick after navigating back', async () => {
      await showChange(managedChange);
      sandbox.resetHistory();

      showRevisionActions(managedChange);

      assert.isTrue(setActionHiddenStub.calledWith(ActionType.CHANGE, 'edit', true));
      assert.isTrue(setActionHiddenStub.calledWith(ActionType.CHANGE, 'rebase', true));
      assert.isTrue(setActionHiddenStub.calledWith(ActionType.REVISION, 'cherrypick', true));
    });

    test('does nothing when the previous change was not on a review branch', async () => {
      // Visiting a non-managed change clears lastManagedChangeId.
      await showChange(managedChange, {on_review_branch: false});
      sandbox.resetHistory();

      showRevisionActions(managedChange);

      assert.isFalse(addActionStub.called);
    });

    test('does nothing for a different change id', async () => {
      await showChange(managedChange);
      sandbox.resetHistory();

      showRevisionActions(otherChange);

      assert.isFalse(addActionStub.called);
    });
  });

  // ---------------------------------------------------------------------------
  // Tap listener — opens dialog with fresh server data
  // ---------------------------------------------------------------------------

  suite('tap listener', () => {
    test('fetches fresh info from the server when the dialog is opened', async () => {
      await showChange(managedChange);
      // Capture tapFn before resetting history (firstCall becomes null after reset).
      const tapFn: Function = addTapListenerStub.firstCall.args[1];
      sandbox.resetHistory();
      getStub.resolves(makeFollowInfo({version: 'v-fresh'}));

      await tapFn();

      assert.isTrue(getStub.calledOnce, 'dialog open should trigger a GET request');
    });
  });
});
