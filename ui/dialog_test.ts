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
import './dialog';
import {SelectReviewTargetDialog} from './dialog';
import {FollowInfo} from './api';
import {fixture, html, assert} from '@open-wc/testing';
import sinon from 'sinon';
import {PluginApi} from '@gerritcodereview/typescript-api/plugin';
import {PopupPluginApi} from '@gerritcodereview/typescript-api/popup';
import {ChangeInfo} from '@gerritcodereview/typescript-api/rest-api';

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

suite('gr-select-reviewtarget-dialog', () => {
  let element: SelectReviewTargetDialog;
  let postStub: sinon.SinonStub;

  setup(async () => {
    postStub = sinon.stub().resolves(makeFollowInfo());

    const mockPlugin = {
      restApi: () => ({
        post: postStub,
        get: sinon.stub().resolves(makeFollowInfo()),
      }),
    } as unknown as PluginApi;

    // Pass plugin and change into the fixture so they're available on
    // the first render (willUpdate calls loadPaths which needs both).
    element = await fixture<SelectReviewTargetDialog>(html`
      <gr-select-reviewtarget-dialog
        .plugin=${mockPlugin}
        .change=${{id: '42'} as unknown as ChangeInfo}
        .popupApi=${{close: sinon.stub()} as unknown as PopupPluginApi}
      ></gr-select-reviewtarget-dialog>
    `);
    // Wait for the initial loadPaths to complete (two cycles: one for the
    // Lit update itself, one for the async REST response to settle).
    await element.updateComplete;
    await element.updateComplete;
  });

  /** Call initialize() and wait for the triggered loadPaths to finish. */
  async function init(overrides: Partial<FollowInfo> = {}) {
    element.initialize(makeFollowInfo(overrides));
    await element.updateComplete;
    await element.updateComplete;
  }

  function dialog(): HTMLElement {
    return element.shadowRoot!.querySelector('gr-dialog')!;
  }

  function sectionByTitle(title: string): HTMLElement | undefined {
    return Array.from(element.shadowRoot!.querySelectorAll('section'))
        .find(s => s.textContent?.includes(title));
  }

  // ---------------------------------------------------------------------------
  // Update button enabled state (_canUpdate reflected via gr-dialog[disabled])
  // ---------------------------------------------------------------------------

  suite('update button', () => {
    test('disabled when nothing changed', async () => {
      await init();
      assert.isTrue(dialog().hasAttribute('disabled'));
    });

    test('enabled when review target changed', async () => {
      await init();
      element.reviewTarget = 'refs/tags/v2';
      await element.updateComplete;
      await element.updateComplete;
      assert.isFalse(dialog().hasAttribute('disabled'));
    });

    test('enabled when paths were added', async () => {
      await init();
      element.addedPaths = ['new.txt'];
      await element.updateComplete;
      assert.isFalse(dialog().hasAttribute('disabled'));
    });

    test('enabled when paths were updated', async () => {
      await init();
      element.updatedPaths = ['changed.txt'];
      await element.updateComplete;
      assert.isFalse(dialog().hasAttribute('disabled'));
    });

    test('enabled when rebase required', async () => {
      await init();
      element.rebaseRequired = true;
      await element.updateComplete;
      assert.isFalse(dialog().hasAttribute('disabled'));
    });

    test('disabled when target is invalid (overrides path changes)', async () => {
      await init();
      element.addedPaths = ['new.txt'];
      element.validReviewTarget = false;
      await element.updateComplete;
      assert.isTrue(dialog().hasAttribute('disabled'));
    });
  });

  // ---------------------------------------------------------------------------
  // Tooltip text (_updateTooltip reflected via gr-dialog[confirm-tooltip])
  // ---------------------------------------------------------------------------

  suite('confirm tooltip', () => {
    test('"No changes necessary" when nothing differs', async () => {
      await init();
      assert.equal(dialog().getAttribute('confirm-tooltip'), 'No changes necessary');
    });

    test('shows "not valid" when review target is invalid', async () => {
      await init();
      element.validReviewTarget = false;
      await element.updateComplete;
      assert.include(dialog().getAttribute('confirm-tooltip'), 'not valid');
    });

    test('"one file" for a single path change', async () => {
      await init();
      element.addedPaths = ['a.txt'];
      await element.updateComplete;
      assert.include(dialog().getAttribute('confirm-tooltip'), 'one file');
    });

    test('numeric count for multiple path changes', async () => {
      await init();
      element.addedPaths = ['a.txt', 'b.txt'];
      await element.updateComplete;
      assert.include(dialog().getAttribute('confirm-tooltip'), '2 files');
    });

    test('"rebased" prefix when rebase is required', async () => {
      await init();
      element.rebaseRequired = true;
      await element.updateComplete;
      assert.include(dialog().getAttribute('confirm-tooltip'), 'rebased');
    });

    test('"updating commit message" for target-only change', async () => {
      await init();
      element.reviewTarget = 'refs/tags/v2';
      await element.updateComplete;
      await element.updateComplete;
      assert.include(dialog().getAttribute('confirm-tooltip'), 'updating commit message');
    });
  });

  // ---------------------------------------------------------------------------
  // Conditional path sections
  // ---------------------------------------------------------------------------

  suite('path sections', () => {
    setup(() => init());

    test('all three path sections hidden when empty', async () => {
      assert.isTrue(sectionByTitle('To be added')?.hasAttribute('hidden'));
      assert.isTrue(sectionByTitle('To be updated')?.hasAttribute('hidden'));
      assert.isTrue(sectionByTitle('To be removed')?.hasAttribute('hidden'));
    });

    test('"To be added" visible when addedPaths is non-empty', async () => {
      element.addedPaths = ['new.txt'];
      await element.updateComplete;
      assert.isFalse(sectionByTitle('To be added')?.hasAttribute('hidden'));
      assert.isTrue(sectionByTitle('To be updated')?.hasAttribute('hidden'));
      assert.isTrue(sectionByTitle('To be removed')?.hasAttribute('hidden'));
    });

    test('"To be updated" visible when updatedPaths is non-empty', async () => {
      element.updatedPaths = ['changed.txt'];
      await element.updateComplete;
      assert.isTrue(sectionByTitle('To be added')?.hasAttribute('hidden'));
      assert.isFalse(sectionByTitle('To be updated')?.hasAttribute('hidden'));
      assert.isTrue(sectionByTitle('To be removed')?.hasAttribute('hidden'));
    });

    test('"To be removed" visible when removedPaths is non-empty', async () => {
      element.removedPaths = ['gone.txt'];
      await element.updateComplete;
      assert.isTrue(sectionByTitle('To be added')?.hasAttribute('hidden'));
      assert.isTrue(sectionByTitle('To be updated')?.hasAttribute('hidden'));
      assert.isFalse(sectionByTitle('To be removed')?.hasAttribute('hidden'));
    });
  });

  // ---------------------------------------------------------------------------
  // Rebase section
  // ---------------------------------------------------------------------------

  suite('rebase section', () => {
    test('hidden when rebase is not required', async () => {
      await init({rebase_required: false});
      assert.isTrue(sectionByTitle('Rebase')?.hasAttribute('hidden'));
    });

    test('visible when rebase is required', async () => {
      await init();
      element.rebaseRequired = true;
      await element.updateComplete;
      assert.isFalse(sectionByTitle('Rebase')?.hasAttribute('hidden'));
    });
  });
});
