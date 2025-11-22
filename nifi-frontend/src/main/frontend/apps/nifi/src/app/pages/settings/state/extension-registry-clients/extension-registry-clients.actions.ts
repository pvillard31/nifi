/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { createAction, props } from '@ngrx/store';
import {
    CreateExtensionRegistryClientRequest,
    CreateExtensionRegistryClientSuccess,
    DeleteExtensionRegistryClientRequest,
    DeleteExtensionRegistryClientSuccess,
    EditExtensionRegistryClientDialogRequest,
    EditExtensionRegistryClientRequest,
    EditExtensionRegistryClientRequestSuccess,
    LoadExtensionRegistryClientsResponse,
    SelectExtensionRegistryClientRequest
} from './index';
import { ClearBulletinsRequest, ClearBulletinsResponse } from '../../../../state/shared';

export const resetExtensionRegistryClientsState = createAction('[Extension Registry Clients] Reset State');

export const loadExtensionRegistryClients = createAction('[Extension Registry Clients] Load');

export const loadExtensionRegistryClientsSuccess = createAction(
    '[Extension Registry Clients] Load Success',
    props<{ response: LoadExtensionRegistryClientsResponse }>()
);

export const loadExtensionRegistryClientsError = createAction(
    '[Extension Registry Clients] Load Error',
    props<{ errorResponse: any; loadedTimestamp: string; status: 'pending' | 'success' }>()
);

export const extensionRegistryClientsBannerApiError = createAction(
    '[Extension Registry Clients] Banner Api Error',
    props<{ error: string }>()
);

export const extensionRegistryClientsSnackbarApiError = createAction(
    '[Extension Registry Clients] Snackbar Api Error',
    props<{ error: string }>()
);

export const openNewExtensionRegistryClientDialog = createAction('[Extension Registry Clients] Open New Dialog');

export const createExtensionRegistryClient = createAction(
    '[Extension Registry Clients] Create',
    props<{ request: CreateExtensionRegistryClientRequest }>()
);

export const createExtensionRegistryClientSuccess = createAction(
    '[Extension Registry Clients] Create Success',
    props<{ response: CreateExtensionRegistryClientSuccess }>()
);

export const navigateToEditExtensionRegistryClient = createAction(
    '[Extension Registry Clients] Navigate To Edit',
    props<{ id: string }>()
);

export const openConfigureExtensionRegistryClientDialog = createAction(
    '[Extension Registry Clients] Open Configure Dialog',
    props<{ request: EditExtensionRegistryClientDialogRequest }>()
);

export const configureExtensionRegistryClient = createAction(
    '[Extension Registry Clients] Configure',
    props<{ request: EditExtensionRegistryClientRequest }>()
);

export const configureExtensionRegistryClientSuccess = createAction(
    '[Extension Registry Clients] Configure Success',
    props<{ response: EditExtensionRegistryClientRequestSuccess }>()
);

export const promptExtensionRegistryClientDeletion = createAction(
    '[Extension Registry Clients] Prompt Deletion',
    props<{ request: DeleteExtensionRegistryClientRequest }>()
);

export const deleteExtensionRegistryClient = createAction(
    '[Extension Registry Clients] Delete',
    props<{ request: DeleteExtensionRegistryClientRequest }>()
);

export const deleteExtensionRegistryClientSuccess = createAction(
    '[Extension Registry Clients] Delete Success',
    props<{ response: DeleteExtensionRegistryClientSuccess }>()
);

export const selectExtensionRegistryClient = createAction(
    '[Extension Registry Clients] Select',
    props<{ request: SelectExtensionRegistryClientRequest }>()
);

export const clearExtensionRegistryClientBulletins = createAction(
    '[Extension Registry Clients] Clear Bulletins',
    props<{ request: ClearBulletinsRequest }>()
);

export const clearExtensionRegistryClientBulletinsSuccess = createAction(
    '[Extension Registry Clients] Clear Bulletins Success',
    props<{ response: ClearBulletinsResponse }>()
);
