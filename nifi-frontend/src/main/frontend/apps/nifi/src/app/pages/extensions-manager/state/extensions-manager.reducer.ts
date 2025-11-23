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
import { ExtensionsManagerState } from './extensions-manager.state';
import {
    deleteNarBundle,
    deleteNarBundleFailure,
    deleteNarBundleSuccess,
    loadNarBundles,
    loadNarBundlesFailure,
    loadNarBundlesSuccess,
    loadNarDetails,
    loadNarDetailsFailure,
    loadNarDetailsSuccess,
    resetNarDetails
} from './extensions-manager.actions';

export const extensionsManagerFeatureKey = 'extensionsManager';

export const initialState: ExtensionsManagerState = {
    bundles: [],
    status: 'pending',
    details: null,
    detailsStatus: 'pending'
};

export const extensionsManagerReducer = createReducer(
    initialState,
    on(loadNarBundles, (state) => ({
        ...state,
        status: 'loading' as const
    })),
    on(loadNarBundlesSuccess, (state, { bundles }) => ({
        ...state,
        status: 'success' as const,
        bundles
    })),
    on(loadNarBundlesFailure, (state) => ({
        ...state,
        status: 'error' as const
    })),
    on(deleteNarBundle, (state) => ({
        ...state,
        status: 'loading' as const
    })),
    on(deleteNarBundleSuccess, (state) => ({
        ...state,
        status: 'pending' as const
    })),
    on(deleteNarBundleFailure, (state) => ({
        ...state,
        status: 'error' as const
    })),
    on(loadNarDetails, (state) => ({
        ...state,
        detailsStatus: 'loading' as const,
        details: null
    })),
    on(loadNarDetailsSuccess, (state, { details }) => ({
        ...state,
        detailsStatus: 'success' as const,
        details
    })),
    on(loadNarDetailsFailure, (state) => ({
        ...state,
        detailsStatus: 'error' as const
    })),
    on(resetNarDetails, (state) => ({
        ...state,
        details: null,
        detailsStatus: 'pending' as const
    }))
);
