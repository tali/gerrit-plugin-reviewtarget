/**
 * @license
 * Copyright (C) 2023 Siemens Mobility GmbH
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

import {css, CSSResult, html, LitElement, PropertyValues} from 'lit';
import {customElement, property, state} from 'lit/decorators.js';

import {PluginApi} from '@gerritcodereview/typescript-api/plugin';
import {PopupPluginApi} from '@gerritcodereview/typescript-api/popup';
import {ChangeInfo} from '@gerritcodereview/typescript-api/rest-api';

import {changeFollowPost, FollowInfo} from './api';
import {BindValueChangeEvent} from './types';
import {fireReload} from './event-util';
import './gr-show-files';

declare global {
  interface HTMLElementTagNameMap {
    'gr-select-reviewtarget-dialog': SelectReviewTargetDialog,
  }
}

@customElement('gr-select-reviewtarget-dialog')
export class SelectReviewTargetDialog extends LitElement {

  @property({type: Object})
  plugin!: PluginApi;

  @property({type: Object})
  popupApi!: PopupPluginApi;

  @property({type: Object})
  change!: ChangeInfo;

  @state() reviewTarget = "loading...";
  @state() reviewTargetOrig = "";

  @state() reviewFiles = "";
  @state() reviewFilesOrig = "";

  @state() loading = true;

  @state() version = "loading...";

  @state() followVersion = "loading...";

  @state() followBranch = "";

  @state() addedPaths: string[] = [];
  @state() removedPaths: string[] = [];
  @state() updatedPaths: string[] = [];

  @state() rebaseRequired = false;
  @state() validReviewTarget = false;

  static override styles = [
    window.Gerrit.styles.form as CSSResult,
    css`
      :host {
        display: block;
      }
      :host([disabled]) {
        opacity: 0.5;
        pointer-events: none;
      }
      section[hidden] {
        display: none !important;
      }
      input[error] {
        color: red;
      }
      #reviewfiles {
        height: 3em;
        width: 20em;
      }
      .main {
        display: flex;
        flex-direction: column;
        width: 100%;
      }
    `
  ];

  private _numberPathChanges() {
    return this.addedPaths.length + this.updatedPaths.length + this.removedPaths.length;
  }

  private _anyPathChanges() {
    return this.addedPaths.length > 0 ||
           this.updatedPaths.length > 0 ||
           this.removedPaths.length > 0;
  }

  /** determine whether the 'UPDATE' button is enabled */
  private _canUpdate() {
    if (!this.validReviewTarget)
      return false;

    // would we update anything?
    if (this._anyPathChanges())
      return true;
    if (this.reviewTargetOrig != this.reviewTarget || this.reviewFilesOrig != this.reviewFiles)
      return true;
    if (this.rebaseRequired)
      return true;

    return false;
  }

  /** determine tooltip of 'UPDATE' button */
  private _updateTooltip() {
    if (!this.validReviewTarget)
      return `Review-Target '${this.reviewTarget}' is not valid`;

    const pathChanges = this._numberPathChanges();
    const targetChanges = this.reviewTarget != this.reviewTargetOrig;
    const filesChanges = this.reviewFiles != this.reviewFilesOrig;

    const createPatchset = this.rebaseRequired ?
      `Create new rebased patchset` : `Create new patchset`;
    const numberFiles = pathChanges == 1 ?
      `one file` : `${pathChanges} files`

    if (pathChanges > 0 && targetChanges)
      return `${createPatchset}, updating ${numberFiles} to ${this.version}`;
    if (pathChanges > 0)
      return `${createPatchset}, updating ${numberFiles}`;
    if (targetChanges || filesChanges)
      return `${createPatchset}, updating commit message`;
    if (this.rebaseRequired)
      return createPatchset;

    return `No changes necessary`;
  }

  /** render one section for a list of changed paths */
  private _renderChangedPaths(name: string, tooltip: string, paths: string[]) {
    return html`
      <section ?hidden="${paths.length == 0}">
        <span class="title">
          <gr-tooltip-content
            has-tooltip
            title="${tooltip}"
          >
            ${name}
          </gr-tooltip-content>
        </span>
        <gr-show-files .files=${paths}>
        </gr-show-files>
      </section>
    `;
  }

  override render() {
    return html`
      <gr-dialog
        ?disabled=${!this._canUpdate()}
        ?loading=${this.loading}
        confirm-label="Update"
        confirm-tooltip=${this._updateTooltip()}
        @confirm=${this.handleConfirmTap}
        @cancel=${this.handleCancelTap}
      >
        <div class="header" slot="header">Select contents of review</div>
        <div class="main" slot="main">

          <div class="gr-form-styles">
            <section>
              <span class="title">
                <gr-tooltip-content
                  has-tooltip
                  title="Select the version which is to be reviewed"
                >
                  Review-Target
                </gr-tooltip-content>
              </span>
              <iron-input
                .bindValue=${this.reviewTarget}
                @bind-value-changed=${(e: BindValueChangeEvent) => {
                  if (e.detail.value) {
                    this.reviewTarget = e.detail.value;
                  }
                }}
              >
                <input ?error=${!this.validReviewTarget}>
              </iron-input>
              <gr-button
                @click=${this.updateReviewTarget}
                ?disabled=${this.reviewTarget == this.followVersion}
                title="Update to ${this.followVersion} on ${this.followBranch}."
              >
                <gr-icon icon="update"></gr-icon>
              </gr-button>
            </section>
            <section>
              <span class="title">
                <gr-tooltip-content
                  has-tooltip
                  title="Select which files are to be reviewed. Use multiple lines to add multiple patterns. Use '*' and '**' just as in GIT .ignore rules."
                >
                  Review-Files
                </gr-tooltip-content>
              </span>
              <iron-input
                .bindValue=${this.reviewFiles}
                @bind-value-changed=${(e: BindValueChangeEvent) => {
                  if (e.detail.value) {
                    this.reviewFiles = e.detail.value;
                  }
                }}
              >
                <textarea id="reviewfiles"></textarea>
              </iron-input>
            </section>
            ${this._renderChangedPaths(
              "To be added",
              "Additional files which will be included in the review",
              this.addedPaths
            )}
            ${this._renderChangedPaths(
              "To be updated",
              "Updates are available for files which are being reviewed",
              this.updatedPaths
            )}
            ${this._renderChangedPaths(
              "To be removed",
              "Files will no longer be included in the review",
              this.removedPaths
            )}
            <section ?hidden="${!this.rebaseRequired}">
              <span class="title">Rebase</span>
              Update to new version of parent change
            </section>
          </div>

        </div>
      </gr-dialog>
    `;
  }

  /** read initial properties from the provided REST GET result */
  initialize(info: FollowInfo) {
    this.version = info.version;
    this.followBranch = info.follow_branch;
    this.followVersion = info.follow_version;
    this.validReviewTarget = info.valid_review_target;
    this.rebaseRequired = info.rebase_required;

    if (this.validReviewTarget) {
      this.reviewTarget = info.review_target;
      this.reviewFiles = info.review_files
    } else {
      // this is a newly created change
      this.reviewTarget = this.followVersion;
      this.reviewFiles = "*";
    }
    this.reviewTargetOrig = this.reviewTarget;
    this.reviewFilesOrig = this.reviewFiles;
  }

  private async loadPaths() {
    this.loading = true;
    const restApi = this.plugin.restApi();
    try {
      const info = await changeFollowPost(restApi, this.change, false, this.reviewTarget, this.reviewFiles);
      this.version = info.version;
      this.addedPaths = info.added_paths || [];
      this.updatedPaths = info.updated_paths || [];
      this.removedPaths = info.removed_paths || [];
      this.validReviewTarget = info.valid_review_target;
    } catch (e) {
      this.version = "<error>";
      this.addedPaths = this.updatedPaths = this.removedPaths = [];
      this.validReviewTarget = false;
    }
    this.loading = false;
  }

  override willUpdate(changedProperties: PropertyValues) {
    if (changedProperties.has('reviewTarget') || changedProperties.has('reviewFiles')) {
      this.loadPaths();
    }
  }

  close() {
    this.popupApi.close();
  }

  private updateReviewTarget() {
    this.reviewTarget = this.followVersion;
  }

  /** Update the change according to the selected target and files pattern
   */
  private async doUpdate() {
    console.debug("doUpdate", this.reviewTarget, this.reviewFiles);

    const restApi = this.plugin.restApi();
    await changeFollowPost(restApi, this.change, true, this.reviewTarget, this.reviewFiles);

    this.close();
    fireReload(this, /*clearPatchset=*/ true);
  }

  private async handleConfirmTap(e: Event) {
    e.preventDefault();
    e.stopPropagation();
    await this.doUpdate();
  }

  private async handleCancelTap(e: Event) {
    e.preventDefault();
    e.stopPropagation();
    this.close();
  }
}
