/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { createReducer, on } from '@ngrx/store';
import {
    extensionRegistryClientDefinitionApiError,
    loadExtensionRegistryClientDefinition,
    loadExtensionRegistryClientDefinitionSuccess,
    resetExtensionRegistryClientDefinitionState
} from './extension-registry-client-definition.actions';
import { ExtensionRegistryClientDefinitionState } from './index';

export const initialState: ExtensionRegistryClientDefinitionState = {
    extensionRegistryClientDefinition: null,
    error: null,
    status: 'pending'
};

export const extensionRegistryClientDefinitionReducer = createReducer(
    initialState,
    on(loadExtensionRegistryClientDefinition, (state) => ({
        ...state,
        status: 'loading' as const,
        error: null
    })),
    on(loadExtensionRegistryClientDefinitionSuccess, (state, { extensionRegistryClientDefinition }) => ({
        ...state,
        extensionRegistryClientDefinition,
        status: 'success' as const,
        error: null
    })),
    on(extensionRegistryClientDefinitionApiError, (state, { error }) => ({
        ...state,
        error,
        status: 'error' as const
    })),
    on(resetExtensionRegistryClientDefinitionState, () => initialState)
);
