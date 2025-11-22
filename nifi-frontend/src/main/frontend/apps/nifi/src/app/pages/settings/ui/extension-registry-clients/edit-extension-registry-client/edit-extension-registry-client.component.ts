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

import { Component, Input, inject } from '@angular/core';
import { ReactiveFormsModule, UntypedFormBuilder, UntypedFormGroup, Validators } from '@angular/forms';
import { MatDialogModule } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { NiFiCommon } from '@nifi/shared';

@Component({
    selector: 'edit-extension-registry-client',
    templateUrl: './edit-extension-registry-client.component.html',
    styleUrls: ['./edit-extension-registry-client.component.scss'],
    standalone: true,
    imports: [ReactiveFormsModule, MatDialogModule, MatFormFieldModule, MatInputModule, MatSelectModule]
})
export class EditExtensionRegistryClient {
    @Input() types: any[] = [];

    editExtensionRegistryClientForm: UntypedFormGroup;

    private formBuilder = inject(UntypedFormBuilder);
    private nifiCommon = inject(NiFiCommon);

    constructor() {
        this.editExtensionRegistryClientForm = this.formBuilder.group({
            type: ['', Validators.required],
            name: ['', Validators.required],
            description: ['']
        });
    }

    getFormValue(): any {
        return this.editExtensionRegistryClientForm.value;
    }

    formattedBundle(bundle: any): string {
        return this.nifiCommon.formatBundle(bundle);
    }
}
