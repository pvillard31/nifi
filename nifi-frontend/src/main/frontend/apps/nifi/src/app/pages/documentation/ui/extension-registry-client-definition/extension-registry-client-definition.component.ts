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

import { Component, OnDestroy, inject } from '@angular/core';
import { Store } from '@ngrx/store';
import { NiFiState } from '../../../../state';
import { NgxSkeletonLoaderModule } from 'ngx-skeleton-loader';
import { ComponentType, isDefinedAndNotNull } from '@nifi/shared';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ConfigurableExtensionDefinitionComponent } from '../common/configurable-extension-definition/configurable-extension-definition.component';
import { selectDefinitionCoordinatesFromRouteForComponentType } from '../../state/documentation/documentation.selectors';
import { distinctUntilChanged } from 'rxjs';
import { ExtensionRegistryClientDefinitionState } from '../../state/extension-registry-client-definition';
import {
    loadExtensionRegistryClientDefinition,
    resetExtensionRegistryClientDefinitionState
} from '../../state/extension-registry-client-definition/extension-registry-client-definition.actions';
import { selectExtensionRegistryClientDefinitionState } from '../../state/extension-registry-client-definition/extension-registry-client-definition.selectors';

@Component({
    selector: 'extension-registry-client-definition',
    standalone: true,
    imports: [NgxSkeletonLoaderModule, ConfigurableExtensionDefinitionComponent],
    templateUrl: './extension-registry-client-definition.component.html',
    styleUrl: './extension-registry-client-definition.component.scss'
})
export class ExtensionRegistryClientDefinition implements OnDestroy {
    private store = inject<Store<NiFiState>>(Store);

    extensionRegistryClientDefinitionState: ExtensionRegistryClientDefinitionState | null = null;

    constructor() {
        this.store
            .select(selectDefinitionCoordinatesFromRouteForComponentType(ComponentType.ExtensionRegistryClient))
            .pipe(
                isDefinedAndNotNull(),
                distinctUntilChanged(
                    (a, b) =>
                        a.group === b.group && a.artifact === b.artifact && a.version === b.version && a.type === b.type
                ),
                takeUntilDestroyed()
            )
            .subscribe((coordinates) => {
                this.store.dispatch(
                    loadExtensionRegistryClientDefinition({
                        coordinates
                    })
                );
            });

        this.store
            .select(selectExtensionRegistryClientDefinitionState)
            .pipe(takeUntilDestroyed())
            .subscribe((extensionRegistryClientDefinitionState) => {
                this.extensionRegistryClientDefinitionState = extensionRegistryClientDefinitionState;

                if (extensionRegistryClientDefinitionState.status === 'loading') {
                    window.scrollTo({ top: 0, left: 0 });
                }
            });
    }

    isInitialLoading(state: ExtensionRegistryClientDefinitionState): boolean {
        return state.extensionRegistryClientDefinition === null && state.error === null;
    }

    ngOnDestroy(): void {
        this.store.dispatch(resetExtensionRegistryClientDefinitionState());
    }
}
