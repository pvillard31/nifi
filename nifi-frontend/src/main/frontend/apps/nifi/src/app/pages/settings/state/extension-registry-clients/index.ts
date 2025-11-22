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

import { Bundle, ExtensionRegistryClientEntity } from '../../../../state/shared';
import { Revision } from '@nifi/shared';

export const extensionRegistryClientsFeatureKey = 'extensionRegistryClients';

export interface LoadExtensionRegistryClientsResponse {
    extensionRegistryClients: ExtensionRegistryClientEntity[];
    loadedTimestamp: string;
}

export interface CreateExtensionRegistryClientRequest {
    revision: Revision;
    disconnectedNodeAcknowledged: boolean;
    component: {
        name: string;
        type: string;
        bundle: Bundle;
        description?: string;
    };
}

export interface CreateExtensionRegistryClientSuccess {
    extensionRegistryClient: ExtensionRegistryClientEntity;
}

export interface EditExtensionRegistryClientDialogRequest {
    extensionRegistryClient: ExtensionRegistryClientEntity;
}

export interface EditExtensionRegistryClientRequest {
    id: string;
    uri: string;
    payload: any;
    postUpdateNavigation?: string[];
    postUpdateNavigationBoundary?: string[];
}

export interface EditExtensionRegistryClientRequestSuccess {
    id: string;
    extensionRegistryClient: ExtensionRegistryClientEntity;
    postUpdateNavigation?: string[];
    postUpdateNavigationBoundary?: string[];
}

export interface DeleteExtensionRegistryClientRequest {
    extensionRegistryClient: ExtensionRegistryClientEntity;
}

export interface DeleteExtensionRegistryClientSuccess {
    extensionRegistryClient: ExtensionRegistryClientEntity;
}

export interface SelectExtensionRegistryClientRequest {
    id: string;
}

export interface ExtensionRegistryClientsState {
    extensionRegistryClients: ExtensionRegistryClientEntity[];
    saving: boolean;
    loadedTimestamp: string;
    status: 'pending' | 'loading' | 'success';
}
