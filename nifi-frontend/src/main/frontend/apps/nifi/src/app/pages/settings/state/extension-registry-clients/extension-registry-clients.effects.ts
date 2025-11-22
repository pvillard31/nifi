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
import { Store } from '@ngrx/store';
import { NiFiState } from '../../../../state';
import {
    clearExtensionRegistryClientBulletins,
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
    navigateToEditExtensionRegistryClient,
    openConfigureExtensionRegistryClientDialog,
    openNewExtensionRegistryClientDialog,
    promptExtensionRegistryClientDeletion,
    selectExtensionRegistryClient
} from './extension-registry-clients.actions';
import { catchError, from, map, of, switchMap, take, takeUntil, tap } from 'rxjs';
import { ExtensionRegistryClientService } from '../../service/extension-registry-client.service';
import { concatLatestFrom } from '@ngrx/operators';
import { HttpErrorResponse } from '@angular/common/http';
import { initialState } from './extension-registry-clients.reducer';
import { MatDialog } from '@angular/material/dialog';
import { selectLoadedTimestamp, selectSaving } from './extension-registry-clients.selectors';
import {
    selectExtensionRegistryClientTypes,
    selectExtensionTypesLoadingStatus
} from '../../../../state/extension-types/extension-types.selectors';
import { RegistryClientEntity } from '../../../../state/shared';
import { ErrorHelper } from '../../../../service/error-helper.service';
import * as ErrorActions from '../../../../state/error/error.actions';
import { LARGE_DIALOG, SMALL_DIALOG, XL_DIALOG, YesNoDialog } from '@nifi/shared';
import { Router } from '@angular/router';
import { ManagementControllerServiceService } from '../../service/management-controller-service.service';
import { EditExtensionRegistryClientRequest } from './index';
import { PropertyTableHelperService } from '../../../../service/property-table-helper.service';
import { resetPropertyVerificationState, verifyProperties } from '../../../../state/property-verification/property-verification.actions';
import {
    selectPropertyVerificationResults,
    selectPropertyVerificationStatus
} from '../../../../state/property-verification/property-verification.selectors';
import { VerifyPropertiesRequestContext } from '../../../../state/property-verification';
import { BackNavigation } from '../../../../state/navigation';
import { ErrorContextKey } from '../../../../state/error';
import { CreateRegistryClient } from '../../ui/registry-clients/create-registry-client/create-registry-client.component';
import { EditRegistryClient } from '../../ui/registry-clients/edit-registry-client/edit-registry-client.component';

@Injectable()
export class ExtensionRegistryClientsEffects {
    private actions$ = inject(Actions);
    private store = inject<Store<NiFiState>>(Store);
    private extensionRegistryClientService = inject(ExtensionRegistryClientService);
    private managementControllerServiceService = inject(ManagementControllerServiceService);
    private errorHelper = inject(ErrorHelper);
    private dialog = inject(MatDialog);
    private router = inject(Router);
    private propertyTableHelperService = inject(PropertyTableHelperService);

    loadExtensionRegistryClients$ = createEffect(() =>
        this.actions$.pipe(
            ofType(loadExtensionRegistryClients),
            concatLatestFrom(() => this.store.select(selectLoadedTimestamp)),
            switchMap(([, loadedTimestamp]) =>
                from(this.extensionRegistryClientService.getExtensionRegistryClients()).pipe(
                    map((response) =>
                        loadExtensionRegistryClientsSuccess({
                            response: {
                                extensionRegistryClients: response.registries,
                                loadedTimestamp: response.currentTime
                            }
                        })
                    ),
                    catchError((errorResponse: HttpErrorResponse) => {
                        const status = loadedTimestamp !== initialState.loadedTimestamp ? 'success' : 'pending';
                        return of(
                            loadExtensionRegistryClientsError({
                                errorResponse,
                                loadedTimestamp,
                                status
                            })
                        );
                    })
                )
            )
        )
    );

    loadExtensionRegistryClientsError$ = createEffect(() =>
        this.actions$.pipe(
            ofType(loadExtensionRegistryClientsError),
            switchMap((action) =>
                of(this.errorHelper.handleLoadingError(action.status === 'success', action.errorResponse))
            )
        )
    );

    openNewExtensionRegistryClientDialog$ = createEffect(
        () =>
            this.actions$.pipe(
                ofType(openNewExtensionRegistryClientDialog),
                tap(() => {
                    const dialogReference = this.dialog.open(CreateRegistryClient, {
                        ...LARGE_DIALOG
                    });

                    dialogReference.componentInstance.saving$ = this.store.select(selectSaving);
                    dialogReference.componentInstance.registryClientTypes$ =
                        this.store.select(selectExtensionRegistryClientTypes);
                    dialogReference.componentInstance.registryClientTypesLoadingStatus$ =
                        this.store.select(selectExtensionTypesLoadingStatus);

                    dialogReference.componentInstance.createRegistryClient.pipe(take(1)).subscribe((request) => {
                        this.store.dispatch(
                            createExtensionRegistryClient({
                                request
                            })
                        );
                    });
                })
            ),
        { dispatch: false }
    );

    createExtensionRegistryClient$ = createEffect(() =>
        this.actions$.pipe(
            ofType(createExtensionRegistryClient),
            map((action) => action.request),
            switchMap((request) =>
                from(this.extensionRegistryClientService.createExtensionRegistryClient(request)).pipe(
                    map((response) =>
                        createExtensionRegistryClientSuccess({
                            response: {
                                extensionRegistryClient: response
                            }
                        })
                    ),
                    catchError((errorResponse: HttpErrorResponse) => {
                        this.dialog.closeAll();
                        return of(
                            extensionRegistryClientsSnackbarApiError({
                                error: this.errorHelper.getErrorString(errorResponse)
                            })
                        );
                    })
                )
            )
        )
    );

    createExtensionRegistryClientSuccess$ = createEffect(() =>
        this.actions$.pipe(
            ofType(createExtensionRegistryClientSuccess),
            map((action) => action.response),
            tap(() => {
                this.dialog.closeAll();
            }),
            switchMap((response) =>
                of(
                    selectExtensionRegistryClient({
                        request: {
                            id: response.extensionRegistryClient.id
                        }
                    })
                )
            )
        )
    );

    extensionRegistryClientsBannerApiError$ = createEffect(() =>
        this.actions$.pipe(
            ofType(extensionRegistryClientsBannerApiError),
            map((action) => action.error),
            switchMap((error) =>
                of(
                    ErrorActions.addBannerError({
                        errorContext: { errors: [error], context: ErrorContextKey.REGISTRY_CLIENTS }
                    })
                )
            )
        )
    );

    extensionRegistryClientsSnackbarApiError$ = createEffect(() =>
        this.actions$.pipe(
            ofType(extensionRegistryClientsSnackbarApiError),
            map((action) => action.error),
            switchMap((error) => of(ErrorActions.snackBarError({ error })))
        )
    );

    navigateToEditExtensionRegistryClient$ = createEffect(
        () =>
            this.actions$.pipe(
                ofType(navigateToEditExtensionRegistryClient),
                map((action) => action.id),
                tap((id) => {
                    this.router.navigate(['/settings', 'extension-registry-clients', id, 'edit']);
                })
            ),
        { dispatch: false }
    );

    openConfigureExtensionRegistryClientDialog$ = createEffect(
        () =>
            this.actions$.pipe(
                ofType(openConfigureExtensionRegistryClientDialog),
                map((action) => action.request),
                tap((request) => {
                    const extensionRegistryClientId: string = request.extensionRegistryClient.id;

                    this.store.dispatch(resetPropertyVerificationState());

                    const editDialogReference = this.dialog.open(EditRegistryClient, {
                        ...XL_DIALOG,
                        data: { registryClient: request.extensionRegistryClient as unknown as RegistryClientEntity },
                        id: extensionRegistryClientId
                    });

                    editDialogReference.componentInstance.saving$ = this.store.select(selectSaving);
                    editDialogReference.componentInstance.propertyVerificationResults$ = this.store.select(
                        selectPropertyVerificationResults
                    );
                    editDialogReference.componentInstance.propertyVerificationStatus$ = this.store.select(
                        selectPropertyVerificationStatus
                    );

                    editDialogReference.componentInstance.verify
                        .pipe(takeUntil(editDialogReference.afterClosed()))
                        .subscribe((verificationRequest: VerifyPropertiesRequestContext) => {
                            this.store.dispatch(
                                verifyProperties({
                                    request: verificationRequest
                                })
                            );
                        });

                    editDialogReference.componentInstance.createNewProperty = this.propertyTableHelperService.createNewProperty(
                        extensionRegistryClientId,
                        this.extensionRegistryClientService
                    );

                    editDialogReference.componentInstance.goToService = (serviceId: string) => {
                        const commandBoundary: string[] = ['/settings', 'management-controller-services'];
                        const commands: string[] = [...commandBoundary, serviceId];

                        if (editDialogReference.componentInstance.editRegistryClientForm.dirty) {
                            const saveChangesDialogReference = this.dialog.open(YesNoDialog, {
                                ...SMALL_DIALOG,
                                data: {
                                    title: 'Extension Registry Client Configuration',
                                    message: `Save changes before going to this Controller Service?`
                                }
                            });

                            saveChangesDialogReference.componentInstance.yes.pipe(take(1)).subscribe(() => {
                                editDialogReference.componentInstance.submitForm(commands, commandBoundary);
                            });

                            saveChangesDialogReference.componentInstance.no.pipe(take(1)).subscribe(() => {
                                this.router.navigate(commands, {
                                    state: {
                                        backNavigation: {
                                            route: ['/settings', 'extension-registry-clients', extensionRegistryClientId, 'edit'],
                                            routeBoundary: commandBoundary,
                                            context: 'Extension Registry Client'
                                        } as BackNavigation
                                    }
                                });
                            });
                        } else {
                            this.router.navigate(commands, {
                                state: {
                                    backNavigation: {
                                        route: ['/settings', 'extension-registry-clients', extensionRegistryClientId, 'edit'],
                                        routeBoundary: commandBoundary,
                                        context: 'Extension Registry Client'
                                    } as BackNavigation
                                }
                            });
                        }
                    };

                    editDialogReference.componentInstance.createNewService = this.propertyTableHelperService.createNewService(
                        extensionRegistryClientId,
                        this.managementControllerServiceService,
                        this.extensionRegistryClientService
                    );

                    editDialogReference.componentInstance.editRegistryClient
                        .pipe(takeUntil(editDialogReference.afterClosed()))
                        .subscribe((editRequest: EditExtensionRegistryClientRequest) => {
                            this.store.dispatch(
                                configureExtensionRegistryClient({
                                    request: editRequest
                                })
                            );
                        });

                    editDialogReference.afterClosed().subscribe((response) => {
                        this.store.dispatch(resetPropertyVerificationState());

                        if (response != 'ROUTED') {
                            this.store.dispatch(
                                selectExtensionRegistryClient({
                                    request: {
                                        id: extensionRegistryClientId
                                    }
                                })
                            );
                        }
                    });
                })
            ),
        { dispatch: false }
    );

    configureExtensionRegistryClient$ = createEffect(() =>
        this.actions$.pipe(
            ofType(configureExtensionRegistryClient),
            map((action) => action.request),
            switchMap((request) =>
                from(this.extensionRegistryClientService.updateExtensionRegistryClient(request)).pipe(
                    map((response) =>
                        configureExtensionRegistryClientSuccess({
                            response: {
                                id: request.id,
                                extensionRegistryClient: response,
                                postUpdateNavigation: request.postUpdateNavigation,
                                postUpdateNavigationBoundary: request.postUpdateNavigationBoundary
                            }
                        })
                    ),
                    catchError((errorResponse: HttpErrorResponse) =>
                        of(
                            extensionRegistryClientsSnackbarApiError({
                                error: this.errorHelper.getErrorString(errorResponse)
                            })
                        )
                    )
                )
            )
        )
    );

    configureExtensionRegistryClientSuccess$ = createEffect(
        () =>
            this.actions$.pipe(
                ofType(configureExtensionRegistryClientSuccess),
                map((action) => action.response),
                tap((response) => {
                    if (response.postUpdateNavigation) {
                        if (response.postUpdateNavigationBoundary) {
                            this.router.navigate(response.postUpdateNavigation, {
                                state: {
                                    backNavigation: {
                                        route: ['/settings', 'extension-registry-clients', response.id, 'edit'],
                                        routeBoundary: response.postUpdateNavigationBoundary,
                                        context: 'Extension Registry Client'
                                    } as BackNavigation
                                }
                            });
                        } else {
                            this.router.navigate(response.postUpdateNavigation);
                        }
                    } else {
                        this.dialog.closeAll();
                    }
                })
            ),
        { dispatch: false }
    );

    promptExtensionRegistryClientDeletion$ = createEffect(
        () =>
            this.actions$.pipe(
                ofType(promptExtensionRegistryClientDeletion),
                map((action) => action.request),
                tap((request) => {
                    const dialogReference = this.dialog.open(YesNoDialog, {
                        ...SMALL_DIALOG,
                        data: {
                            title: 'Delete Extension Registry Client',
                            message: `Delete '${request.extensionRegistryClient.component.name}'?`
                        }
                    });

                    dialogReference.componentInstance.yes.pipe(take(1)).subscribe(() => {
                        this.store.dispatch(
                            deleteExtensionRegistryClient({
                                request
                            })
                        );
                    });
                })
            ),
        { dispatch: false }
    );

    deleteExtensionRegistryClient$ = createEffect(() =>
        this.actions$.pipe(
            ofType(deleteExtensionRegistryClient),
            map((action) => action.request),
            switchMap((request) =>
                from(this.extensionRegistryClientService.deleteExtensionRegistryClient(request)).pipe(
                    map((response) =>
                        deleteExtensionRegistryClientSuccess({
                            response: {
                                extensionRegistryClient: response
                            }
                        })
                    ),
                    catchError((errorResponse: HttpErrorResponse) => {
                        this.dialog.closeAll();
                        return of(
                            extensionRegistryClientsSnackbarApiError({
                                error: this.errorHelper.getErrorString(errorResponse)
                            })
                        );
                    })
                )
            )
        )
    );

    deleteExtensionRegistryClientSuccess$ = createEffect(() =>
        this.actions$.pipe(
            ofType(deleteExtensionRegistryClientSuccess),
            switchMap((action) => [
                loadExtensionRegistryClients()
            ])
        )
    );

    selectExtensionRegistryClient$ = createEffect(
        () =>
            this.actions$.pipe(
                ofType(selectExtensionRegistryClient),
                map((action) => action.request),
                tap((request) => {
                    this.router.navigate(['/settings', 'extension-registry-clients', request.id]);
                })
            ),
        { dispatch: false }
    );

    clearExtensionRegistryClientBulletins$ = createEffect(() =>
        this.actions$.pipe(
            ofType(clearExtensionRegistryClientBulletins),
            map((action) => action.request),
            switchMap((request) =>
                from(
                    this.extensionRegistryClientService.clearBulletins({
                        uri: request.uri,
                        fromTimestamp: request.fromTimestamp
                    })
                ).pipe(
                    map((response) =>
                        clearExtensionRegistryClientBulletinsSuccess({
                            response: {
                                componentId: request.componentId,
                                bulletinsCleared: response.bulletinsCleared || 0,
                                bulletins: response.bulletins || [],
                                componentType: request.componentType
                            }
                        })
                    ),
                    catchError((errorResponse: HttpErrorResponse) =>
                        of(
                            extensionRegistryClientsSnackbarApiError({
                                error: this.errorHelper.getErrorString(errorResponse)
                            })
                        )
                    )
                )
            )
        )
    );
}
