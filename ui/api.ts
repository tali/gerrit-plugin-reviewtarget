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

import {ChangeInfo} from '@gerritcodereview/typescript-api/rest-api';
import {RestPluginApi} from '@gerritcodereview/typescript-api/rest';

export declare interface FollowInfo {
  on_review_branch: boolean;
  valid_review_target: boolean;
  rebase_required: boolean;
  new_patchset_id: number;
  version: string;
  follow_branch: string;
  follow_version: string;
  review_target: string;
  review_files: string;
  added_paths: string[];
  updated_paths: string[];
  removed_paths: string[];
}

export async function changeFollowGet(restApi: RestPluginApi, change: ChangeInfo): Promise<FollowInfo> {
  return changeFollowGetById(restApi, change.id);
}

export async function changeFollowGetById(restApi: RestPluginApi, changeId: string | number): Promise<FollowInfo> {
  const endpoint = `/changes/${changeId}/follow`;
  const resp = await restApi.get<FollowInfo>(endpoint)
  console.debug("success GET", endpoint, resp);
  return resp;
}

export async function changeFollowPost(restApi: RestPluginApi, change: ChangeInfo, doUpdate: boolean, reviewTarget: string, reviewFiles: string): Promise<FollowInfo> {
  const endpoint = `/changes/${change.id}/follow`;
  const content = {
    do_update: doUpdate,
    new_review_target: reviewTarget,
    new_review_files: reviewFiles,
  };
  const resp = await restApi.post<FollowInfo>(endpoint, content)
  console.debug("success POST", endpoint, content, resp);
  return resp;
}
