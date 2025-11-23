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

import { AfterViewInit, Component, OnDestroy, OnInit, ViewChild, inject } from '@angular/core';
import { Store } from '@ngrx/store';
import { NiFiState } from '../../../state';
import { MatTableDataSource } from '@angular/material/table';
import { NarSummary } from '../state/extensions-manager.state';
import {
    deleteNarBundle,
    loadNarBundles,
    resetNarDetails
} from '../state/extensions-manager.actions';
import { selectNarBundles, selectNarBundlesStatus } from '../state/extensions-manager.selectors';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatSort } from '@angular/material/sort';
import { ExtensionsManagerService } from '../service/extensions-manager.service';
import { MatDialog } from '@angular/material/dialog';
import { ExtensionDetailsDialog } from '../ui/extension-details-dialog/extension-details-dialog.component';
import { NiFiCommon } from '@nifi/shared';

@Component({
    selector: 'extensions-manager',
    templateUrl: './extensions-manager.component.html',
    styleUrls: ['./extensions-manager.component.scss'],
    standalone: false
})
export class ExtensionsManager implements OnInit, AfterViewInit, OnDestroy {
    private store = inject<Store<NiFiState>>(Store);
    private extensionsManagerService = inject(ExtensionsManagerService);
    private dialog = inject(MatDialog);
    private nifiCommon = inject(NiFiCommon);

    @ViewChild(MatSort) sort!: MatSort;

    displayedColumns: string[] = ['artifact', 'group', 'version', 'apiVersion', 'remote', 'dependency', 'actions'];

    dataSource = new MatTableDataSource<NarSummary>([]);
    status$ = this.store.select(selectNarBundlesStatus);

    private downloading: Set<string> = new Set<string>();

    constructor() {
        this.store
            .select(selectNarBundles)
            .pipe(takeUntilDestroyed())
            .subscribe((bundles) => {
                const summaries = bundles.map((bundle) => bundle.narSummary);
                this.dataSource.data = summaries;
                if (this.sort) {
                    this.dataSource.sort = this.sort;
                }
            });
    }

    ngOnInit(): void {
        this.store.dispatch(loadNarBundles());
    }

    ngAfterViewInit(): void {
        this.dataSource.sort = this.sort;
        this.dataSource.sortingDataAccessor = (item: NarSummary, property: string) => {
            switch (property) {
                case 'apiVersion':
                    return item.systemApiVersion || '';
                case 'remote':
                    return this.isRemote(item) ? 0 : 1;
                default:
                    return (item.coordinate as any)[property] || '';
            }
        };
    }

    ngOnDestroy(): void {
        this.store.dispatch(resetNarDetails());
    }

    refresh(): void {
        this.store.dispatch(loadNarBundles());
    }

    isRemote(summary: NarSummary): boolean {
        return !summary.installComplete;
    }

    formatCoordinate(coordinate: { group: string; artifact: string; version: string } | undefined | null): string {
        if (!coordinate) {
            return '';
        }
        return `${coordinate.group}:${coordinate.artifact}:${coordinate.version}`;
    }

    isDownloading(summary: NarSummary): boolean {
        return this.downloading.has(summary.identifier);
    }

    downloadBundle(summary: NarSummary): void {
        if (this.downloading.has(summary.identifier)) {
            return;
        }
        this.downloading.add(summary.identifier);
        this.extensionsManagerService
            .downloadNar(summary.identifier)
            .pipe(takeUntilDestroyed())
            .subscribe({
                next: (response) => {
                    const blob = response.body;
                    if (blob) {
                        const filename = `${summary.coordinate.artifact}-${summary.coordinate.version}.nar`;
                        const url = window.URL.createObjectURL(blob);
                        const link = document.createElement('a');
                        link.href = url;
                        link.download = filename;
                        document.body.appendChild(link);
                        link.click();
                        document.body.removeChild(link);
                        window.URL.revokeObjectURL(url);
                    }
                    this.downloading.delete(summary.identifier);
                },
                error: () => {
                    this.downloading.delete(summary.identifier);
                }
            });
    }

    deleteBundle(summary: NarSummary): void {
        const confirmed = window.confirm(
            `Delete downloaded bundle ${this.nifiCommon.formatBundle(summary.coordinate)}? This will remove the local copy.`
        );
        if (!confirmed) {
            return;
        }
        this.store.dispatch(
            deleteNarBundle({
                id: summary.identifier,
                force: false
            })
        );
    }

    showDetails(summary: NarSummary): void {
        this.store.dispatch(resetNarDetails());
        this.dialog.open(ExtensionDetailsDialog, {
            width: '760px',
            data: {
                id: summary.identifier,
                coordinate: summary.coordinate
            }
        });
    }
}
