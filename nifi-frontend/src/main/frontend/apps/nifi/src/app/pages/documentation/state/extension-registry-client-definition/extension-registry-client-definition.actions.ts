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

import { createAction, props } from '@ngrx/store';
import { ExtensionRegistryClientDefinition } from './index';
import { DefinitionCoordinates } from '../index';

const EXTENSION_REGISTRY_CLIENT_DEFINITION_PREFIX = '[Extension Registry Client Definition]';

export const loadExtensionRegistryClientDefinition = createAction(
    `${EXTENSION_REGISTRY_CLIENT_DEFINITION_PREFIX} Load Extension Registry Client Definition`,
    props<{ coordinates: DefinitionCoordinates }>()
);

export const loadExtensionRegistryClientDefinitionSuccess = createAction(
    `${EXTENSION_REGISTRY_CLIENT_DEFINITION_PREFIX} Load Extension Registry Client Definition Success`,
    props<{ extensionRegistryClientDefinition: ExtensionRegistryClientDefinition }>()
);

export const extensionRegistryClientDefinitionApiError = createAction(
    `${EXTENSION_REGISTRY_CLIENT_DEFINITION_PREFIX} Load Extension Registry Client Definition Error`,
    props<{ error: string }>()
);

export const resetExtensionRegistryClientDefinitionState = createAction(
    `${EXTENSION_REGISTRY_CLIENT_DEFINITION_PREFIX} Reset Extension Registry Client Definition State`
);
