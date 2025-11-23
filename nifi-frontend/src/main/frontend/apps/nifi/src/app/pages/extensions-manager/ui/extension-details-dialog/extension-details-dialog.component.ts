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
import { NiFiState } from '../../../../state';
import { MAT_DIALOG_DATA } from '@angular/material/dialog';
import { NarDetailsEntity, NarSummary, NarCoordinate } from '../../state/extensions-manager.state';
import { loadNarDetails, resetNarDetails } from '../../state/extensions-manager.actions';
import { selectNarDetails, selectNarDetailsStatus } from '../../state/extensions-manager.selectors';
import { DocumentedType } from '../../../../state/shared';
import { NiFiCommon } from '@nifi/shared';

interface ExtensionRow {
    type: string;
    name: string;
}

@Component({
    selector: 'extension-details-dialog',
    templateUrl: './extension-details-dialog.component.html',
    styleUrls: ['./extension-details-dialog.component.scss'],
    standalone: false
})
export class ExtensionDetailsDialog implements OnInit, OnDestroy {
    private store = inject<Store<NiFiState>>(Store);
    private dialogData: { id: string; coordinate: NarCoordinate } = inject(MAT_DIALOG_DATA);
    private nifiCommon = inject(NiFiCommon);

    details$ = this.store.select(selectNarDetails);
    status$ = this.store.select(selectNarDetailsStatus);
    columns: string[] = ['type', 'name'];

    ngOnInit(): void {
        this.store.dispatch(
            loadNarDetails({
                id: this.dialogData.id
            })
        );
    }

    ngOnDestroy(): void {
        this.store.dispatch(resetNarDetails());
    }

    get coordinate(): NarSummary['coordinate'] {
        return this.dialogData.coordinate;
    }

    getRows(details: NarDetailsEntity): ExtensionRow[] {
        const rows: ExtensionRow[] = [];
        rows.push(...this.toRows('Processor', details.processorTypes));
        rows.push(...this.toRows('Controller Service', details.controllerServiceTypes));
        rows.push(...this.toRows('Reporting Task', details.reportingTaskTypes));
        rows.push(...this.toRows('Parameter Provider', details.parameterProviderTypes));
        rows.push(...this.toRows('Flow Registry Client', details.flowRegistryClientTypes));
        rows.push(...this.toRows('Flow Analysis Rule', details.flowAnalysisRuleTypes));
        rows.push(...this.toRows('Extension Registry Client', details.extensionRegistryClientTypes));
        return rows;
    }

    formatCoordinate(coordinate: NarCoordinate | undefined): string {
        if (!coordinate) {
            return '';
        }
        return `${coordinate.group}:${coordinate.artifact}:${coordinate.version}`;
    }

    private toRows(label: string, types: DocumentedType[] = []): ExtensionRow[] {
        return types.map((type) => ({
            type: label,
            name: this.nifiCommon.formatType(type as any)
        }));
    }
}
