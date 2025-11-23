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

import { NgModule } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ExtensionsManager } from './extensions-manager.component';
import { ExtensionsManagerRoutingModule } from './extensions-manager-routing.module';
import { BannerText } from '../../../ui/common/banner-text/banner-text.component';
import { Navigation } from '../../../ui/common/navigation/navigation.component';
import { MatTableModule } from '@angular/material/table';
import { MatSortModule } from '@angular/material/sort';
import { MatMenuModule } from '@angular/material/menu';
import { MatButtonModule } from '@angular/material/button';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatDialogModule } from '@angular/material/dialog';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { EffectsModule } from '@ngrx/effects';
import { ExtensionsManagerEffects } from '../state/extensions-manager.effects';
import { StoreModule } from '@ngrx/store';
import { extensionsManagerFeatureKey, extensionsManagerReducer } from '../state/extensions-manager.reducer';
import { NgxSkeletonLoaderModule } from 'ngx-skeleton-loader';
import { ExtensionDetailsDialog } from '../ui/extension-details-dialog/extension-details-dialog.component';

@NgModule({
    declarations: [ExtensionsManager, ExtensionDetailsDialog],
    imports: [
        CommonModule,
        ExtensionsManagerRoutingModule,
        BannerText,
        Navigation,
        MatTableModule,
        MatSortModule,
        MatMenuModule,
        MatButtonModule,
        MatTooltipModule,
        MatDialogModule,
        MatProgressSpinnerModule,
        NgxSkeletonLoaderModule,
        StoreModule.forFeature(extensionsManagerFeatureKey, extensionsManagerReducer),
        EffectsModule.forFeature([ExtensionsManagerEffects])
    ]
})
export class ExtensionsManagerModule {}
