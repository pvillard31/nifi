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

import { Component, OnDestroy, OnInit, inject } from '@angular/core';
import { Store } from '@ngrx/store';
import { selectCurrentUser } from '../../../../state/current-user/current-user.selectors';
import { NiFiState } from '../../../../state';
import { filter, switchMap, take } from 'rxjs';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ExtensionRegistryClientEntity } from '../../../../state/shared';
import { AsyncPipe } from '@angular/common';
import { NgxSkeletonLoaderComponent } from 'ngx-skeleton-loader';
import { MatIconButton } from '@angular/material/button';
import { ComponentType, NiFiCommon } from '@nifi/shared';
import { ExtensionRegistryClientTable } from './extension-registry-client-table/extension-registry-client-table.component';
import {
    clearExtensionRegistryClientBulletins,
    configureExtensionRegistryClient,
    deleteExtensionRegistryClient,
    loadExtensionRegistryClients,
    navigateToEditExtensionRegistryClient,
    openConfigureExtensionRegistryClientDialog,
    openNewExtensionRegistryClientDialog,
    promptExtensionRegistryClientDeletion,
    resetExtensionRegistryClientsState,
    selectExtensionRegistryClient
} from '../../state/extension-registry-clients/extension-registry-clients.actions';
import {
    selectExtensionRegistryClient as selectExtensionRegistryClientSelector,
    selectExtensionRegistryClientIdFromRoute,
    selectExtensionRegistryClientsState,
    selectSingleEditedExtensionRegistryClient
} from '../../state/extension-registry-clients/extension-registry-clients.selectors';
import { ExtensionRegistryClientsState } from '../../state/extension-registry-clients';
import { initialState } from '../../state/extension-registry-clients/extension-registry-clients.reducer';

@Component({
    selector: 'extension-registry-clients',
    templateUrl: './extension-registry-clients.component.html',
    imports: [AsyncPipe, NgxSkeletonLoaderComponent, MatIconButton, ExtensionRegistryClientTable],
    styleUrls: ['./extension-registry-clients.component.scss']
})
export class ExtensionRegistryClients implements OnInit, OnDestroy {
    private store = inject<Store<NiFiState>>(Store);
    private nifiCommon = inject(NiFiCommon);

    extensionRegistryClientsState$ = this.store.select(selectExtensionRegistryClientsState);
    selectedExtensionRegistryClientId$ = this.store.select(selectExtensionRegistryClientIdFromRoute);
    currentUser$ = this.store.select(selectCurrentUser);

    constructor() {
        this.store
            .select(selectSingleEditedExtensionRegistryClient)
            .pipe(
                filter((id: string) => id != null),
                switchMap((id: string) =>
                    this.store.select(selectExtensionRegistryClientSelector(id)).pipe(
                        filter((entity) => entity != null),
                        take(1)
                    )
                ),
                takeUntilDestroyed()
            )
            .subscribe((entity) => {
                if (entity) {
                    this.store.dispatch(
                        openConfigureExtensionRegistryClientDialog({
                            request: {
                                extensionRegistryClient: entity
                            }
                        })
                    );
                }
            });
    }

    ngOnInit(): void {
        this.store.dispatch(loadExtensionRegistryClients());
    }

    isInitialLoading(state: ExtensionRegistryClientsState): boolean {
        return state.loadedTimestamp == initialState.loadedTimestamp;
    }

    openNewExtensionRegistryClientDialog(): void {
        this.store.dispatch(openNewExtensionRegistryClientDialog());
    }

    refreshExtensionRegistryClientListing(): void {
        this.store.dispatch(loadExtensionRegistryClients());
    }

    selectExtensionRegistryClient(entity: ExtensionRegistryClientEntity): void {
        this.store.dispatch(
            selectExtensionRegistryClient({
                request: {
                    id: entity.id
                }
            })
        );
    }

    configureExtensionRegistryClient(entity: ExtensionRegistryClientEntity): void {
        this.store.dispatch(
            navigateToEditExtensionRegistryClient({
                id: entity.id
            })
        );
    }

    deleteExtensionRegistryClient(entity: ExtensionRegistryClientEntity): void {
        this.store.dispatch(
            promptExtensionRegistryClientDeletion({
                request: {
                    extensionRegistryClient: entity
                }
            })
        );
    }

    clearBulletinsExtensionRegistryClient(entity: ExtensionRegistryClientEntity): void {
        const fromTimestamp = this.nifiCommon.getMostRecentBulletinTimestamp(entity.bulletins || []);
        if (fromTimestamp === null) {
            return;
        }

        this.store.dispatch(
            clearExtensionRegistryClientBulletins({
                request: {
                    uri: entity.uri,
                    fromTimestamp,
                    componentId: entity.id,
                    componentType: ComponentType.ExtensionRegistryClient
                }
            })
        );
    }

    ngOnDestroy(): void {
        this.store.dispatch(resetExtensionRegistryClientsState());
    }
}
