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

import { Component, EventEmitter, Input, Output, inject } from '@angular/core';
import { MatTableDataSource, MatTableModule } from '@angular/material/table';
import { MatSortModule, Sort } from '@angular/material/sort';
import { NgClass } from '@angular/common';
import { TextTip, NiFiCommon, NifiTooltipDirective } from '@nifi/shared';
import { BulletinsTip } from '../../../../../ui/common/tooltips/bulletins-tip/bulletins-tip.component';
import { ValidationErrorsTip } from '../../../../../ui/common/tooltips/validation-errors-tip/validation-errors-tip.component';
import { BulletinsTipInput, ExtensionRegistryClientEntity, ValidationErrorsTipInput } from '../../../../../state/shared';
import { MatIconButton } from '@angular/material/button';
import { MatMenu, MatMenuItem, MatMenuTrigger } from '@angular/material/menu';

@Component({
    selector: 'extension-registry-client-table',
    templateUrl: './extension-registry-client-table.component.html',
    styleUrls: ['./extension-registry-client-table.component.scss'],
    imports: [
        MatTableModule,
        MatSortModule,
        NgClass,
        NifiTooltipDirective,
        MatIconButton,
        MatMenuTrigger,
        MatMenu,
        MatMenuItem
    ]
})
export class ExtensionRegistryClientTable {
    private nifiCommon = inject(NiFiCommon);

    @Input() set extensionRegistryClients(extensionRegistryClientEntities: ExtensionRegistryClientEntity[]) {
        if (extensionRegistryClientEntities) {
            this.dataSource.data = this.sortEvents(extensionRegistryClientEntities, this.sort);
        }
    }

    @Input() selectedExtensionRegistryClientId!: string | null | undefined;

    @Output() selectExtensionRegistryClient: EventEmitter<ExtensionRegistryClientEntity> =
        new EventEmitter<ExtensionRegistryClientEntity>();
    @Output() configureExtensionRegistryClient: EventEmitter<ExtensionRegistryClientEntity> =
        new EventEmitter<ExtensionRegistryClientEntity>();
    @Output() deleteExtensionRegistryClient: EventEmitter<ExtensionRegistryClientEntity> =
        new EventEmitter<ExtensionRegistryClientEntity>();
    @Output() clearBulletinsExtensionRegistryClient: EventEmitter<ExtensionRegistryClientEntity> =
        new EventEmitter<ExtensionRegistryClientEntity>();

    protected readonly TextTip = TextTip;
    protected readonly BulletinsTip = BulletinsTip;
    protected readonly ValidationErrorsTip = ValidationErrorsTip;

    displayedColumns: string[] = ['moreDetails', 'name', 'description', 'type', 'bundle', 'actions'];
    dataSource: MatTableDataSource<ExtensionRegistryClientEntity> = new MatTableDataSource<ExtensionRegistryClientEntity>();

    sort: Sort = {
        active: 'name',
        direction: 'asc'
    };

    canRead(entity: ExtensionRegistryClientEntity): boolean {
        return entity.permissions.canRead;
    }

    canWrite(entity: ExtensionRegistryClientEntity): boolean {
        return entity.permissions.canWrite;
    }

    hasErrors(entity: ExtensionRegistryClientEntity): boolean {
        return this.canRead(entity) && !this.nifiCommon.isEmpty(entity.component.validationErrors);
    }

    getValidationErrorsTipData(entity: ExtensionRegistryClientEntity): ValidationErrorsTipInput {
        return {
            isValidating: false,
            validationErrors: entity.component.validationErrors
        };
    }

    hasBulletins(entity: ExtensionRegistryClientEntity): boolean {
        return this.canRead(entity) && !this.nifiCommon.isEmpty(entity.bulletins);
    }

    getBulletinsTipData(entity: ExtensionRegistryClientEntity): BulletinsTipInput {
        return {
            // @ts-ignore
            bulletins: entity.bulletins
        };
    }

    getBulletinSeverityClass(entity: ExtensionRegistryClientEntity): string {
        return this.nifiCommon.getBulletinSeverityClass(entity.bulletins || []);
    }

    formatType(entity: ExtensionRegistryClientEntity): string {
        return this.nifiCommon.formatType(entity.component);
    }

    formatBundle(entity: ExtensionRegistryClientEntity): string {
        return this.nifiCommon.formatBundle(entity.component.bundle);
    }

    updateSort(sort: Sort): void {
        this.sort = sort;
        this.dataSource.data = this.sortEvents(this.dataSource.data, sort);
    }

    sortEvents(entities: ExtensionRegistryClientEntity[], sort: Sort): ExtensionRegistryClientEntity[] {
        const data: ExtensionRegistryClientEntity[] = entities.slice();
        return data.sort((a, b) => {
            const isAsc = sort.direction === 'asc';

            let retVal = 0;
            switch (sort.active) {
                case 'name':
                    retVal = this.nifiCommon.compareString(a.component.name, b.component.name);
                    break;
                case 'description':
                    retVal = this.nifiCommon.compareString(a.component.description, b.component.description);
                    break;
                case 'type':
                    retVal = this.nifiCommon.compareString(this.formatType(a), this.formatType(b));
                    break;
                case 'bundle':
                    retVal = this.nifiCommon.compareString(this.formatBundle(a), this.formatBundle(b));
                    break;
            }

            return retVal * (isAsc ? 1 : -1);
        });
    }

    canConfigure(entity: ExtensionRegistryClientEntity): boolean {
        return this.canRead(entity) && this.canWrite(entity);
    }

    configureClicked(entity: ExtensionRegistryClientEntity): void {
        this.configureExtensionRegistryClient.next(entity);
    }

    canDelete(entity: ExtensionRegistryClientEntity): boolean {
        return this.canRead(entity) && this.canWrite(entity);
    }

    deleteClicked(entity: ExtensionRegistryClientEntity): void {
        this.deleteExtensionRegistryClient.next(entity);
    }

    canClearBulletins(entity: ExtensionRegistryClientEntity): boolean {
        return this.canWrite(entity) && !this.nifiCommon.isEmpty(entity.bulletins);
    }

    clearBulletinsClicked(entity: ExtensionRegistryClientEntity): void {
        this.clearBulletinsExtensionRegistryClient.next(entity);
    }

    select(entity: ExtensionRegistryClientEntity): void {
        this.selectExtensionRegistryClient.next(entity);
    }

    isSelected(entity: ExtensionRegistryClientEntity): boolean {
        if (this.selectedExtensionRegistryClientId) {
            return entity.id == this.selectedExtensionRegistryClientId;
        }
        return false;
    }
}
