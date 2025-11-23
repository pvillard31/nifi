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

import { Injectable, inject } from '@angular/core';
import { Actions, createEffect, ofType } from '@ngrx/effects';
import { ExtensionsManagerService } from '../service/extensions-manager.service';
import {
    deleteNarBundle,
    deleteNarBundleFailure,
    deleteNarBundleSuccess,
    loadNarBundles,
    loadNarBundlesFailure,
    loadNarBundlesSuccess,
    loadNarDetails,
    loadNarDetailsFailure,
    loadNarDetailsSuccess
} from './extensions-manager.actions';
import { catchError, map, mergeMap, of } from 'rxjs';

@Injectable()
export class ExtensionsManagerEffects {
    private actions$ = inject(Actions);
    private extensionsManagerService = inject(ExtensionsManagerService);

    loadNarBundles$ = createEffect(() =>
        this.actions$.pipe(
            ofType(loadNarBundles),
            mergeMap(() =>
                this.extensionsManagerService.getNarSummaries().pipe(
                    map((response) => loadNarBundlesSuccess({ bundles: response.narSummaries || [] })),
                    catchError(() => of(loadNarBundlesFailure()))
                )
            )
        )
    );

    deleteNarBundle$ = createEffect(() =>
        this.actions$.pipe(
            ofType(deleteNarBundle),
            mergeMap(({ id, force }) =>
                this.extensionsManagerService.deleteNar(id, force).pipe(
                    mergeMap(() => [deleteNarBundleSuccess(), loadNarBundles()]),
                    catchError(() => of(deleteNarBundleFailure()))
                )
            )
        )
    );

    loadNarDetails$ = createEffect(() =>
        this.actions$.pipe(
            ofType(loadNarDetails),
            mergeMap(({ id }) =>
                this.extensionsManagerService.getNarDetails(id).pipe(
                    map((response) => loadNarDetailsSuccess({ details: response })),
                    catchError(() => of(loadNarDetailsFailure()))
                )
            )
        )
    );
}
