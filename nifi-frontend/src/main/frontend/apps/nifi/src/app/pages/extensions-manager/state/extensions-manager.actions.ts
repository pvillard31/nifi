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
import { NarDetailsEntity, NarSummaryEntity } from './extensions-manager.state';

export const loadNarBundles = createAction('[Extensions Manager] Load Nar Bundles');

export const loadNarBundlesSuccess = createAction(
    '[Extensions Manager] Load Nar Bundles Success',
    props<{ bundles: NarSummaryEntity[] }>()
);

export const loadNarBundlesFailure = createAction('[Extensions Manager] Load Nar Bundles Failure');

export const deleteNarBundle = createAction(
    '[Extensions Manager] Delete Nar Bundle',
    props<{ id: string; force: boolean }>()
);

export const deleteNarBundleSuccess = createAction('[Extensions Manager] Delete Nar Bundle Success');

export const deleteNarBundleFailure = createAction('[Extensions Manager] Delete Nar Bundle Failure');

export const loadNarDetails = createAction(
    '[Extensions Manager] Load Nar Details',
    props<{ id: string }>()
);

export const loadNarDetailsSuccess = createAction(
    '[Extensions Manager] Load Nar Details Success',
    props<{ details: NarDetailsEntity }>()
);

export const loadNarDetailsFailure = createAction('[Extensions Manager] Load Nar Details Failure');

export const resetNarDetails = createAction('[Extensions Manager] Reset Nar Details');
