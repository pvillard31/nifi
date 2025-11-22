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

import { createReducer, on } from '@ngrx/store';
import {
    clearExtensionRegistryClientBulletinsSuccess,
    configureExtensionRegistryClient,
    configureExtensionRegistryClientSuccess,
    createExtensionRegistryClient,
    createExtensionRegistryClientSuccess,
    deleteExtensionRegistryClient,
    deleteExtensionRegistryClientSuccess,
    extensionRegistryClientsBannerApiError,
    extensionRegistryClientsSnackbarApiError,
    loadExtensionRegistryClients,
    loadExtensionRegistryClientsError,
    loadExtensionRegistryClientsSuccess,
    resetExtensionRegistryClientsState
} from './extension-registry-clients.actions';
import { ExtensionRegistryClientsState } from './index';

export const initialState: ExtensionRegistryClientsState = {
    extensionRegistryClients: [],
    saving: false,
    loadedTimestamp: 'Never',
    status: 'pending'
};

export const extensionRegistryClientsReducer = createReducer(
    initialState,
    on(resetExtensionRegistryClientsState, () => initialState),
    on(loadExtensionRegistryClients, (state) => ({
        ...state,
        status: 'loading'
    })),
    on(loadExtensionRegistryClientsSuccess, (state, { response }) => ({
        ...state,
        extensionRegistryClients: response.extensionRegistryClients,
        status: 'success',
        loadedTimestamp: response.loadedTimestamp
    })),
    on(loadExtensionRegistryClientsError, (state, { loadedTimestamp, status }) => ({
        ...state,
        status,
        loadedTimestamp
    })),
    on(extensionRegistryClientsBannerApiError, (state) => ({
        ...state,
        status: 'pending'
    })),
    on(extensionRegistryClientsSnackbarApiError, (state) => ({
        ...state,
        status: 'pending'
    })),
    on(createExtensionRegistryClient, (state) => ({
        ...state,
        saving: true
    })),
    on(createExtensionRegistryClientSuccess, (state, { response }) => ({
        ...state,
        extensionRegistryClients: [...state.extensionRegistryClients, response.extensionRegistryClient],
        saving: false
    })),
    on(configureExtensionRegistryClient, (state) => ({
        ...state,
        saving: true
    })),
    on(configureExtensionRegistryClientSuccess, (state, { response }) => {
        const updated = state.extensionRegistryClients.map((client) =>
            client.id === response.id ? response.extensionRegistryClient : client
        );
        return {
            ...state,
            extensionRegistryClients: updated,
            saving: false
        };
    }),
    on(deleteExtensionRegistryClient, (state) => ({
        ...state,
        saving: true
    })),
    on(deleteExtensionRegistryClientSuccess, (state, { response }) => ({
        ...state,
        saving: false,
        extensionRegistryClients: state.extensionRegistryClients.filter(
            (client) => client.id !== response.extensionRegistryClient.id
        )
    })),
    on(clearExtensionRegistryClientBulletinsSuccess, (state, { response }) => ({
        ...state,
        extensionRegistryClients: state.extensionRegistryClients.map((client) =>
            client.id === response.componentId ? { ...client, bulletins: [] } : client
        )
    }))
);
