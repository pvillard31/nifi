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
import { HttpClient, HttpParams, HttpResponse } from '@angular/common/http';
import { Observable } from 'rxjs';
import { ClusterConnectionService } from '../../../service/cluster-connection.service';

@Injectable({ providedIn: 'root' })
export class ExtensionsManagerService {
    private httpClient = inject(HttpClient);
    private clusterConnectionService = inject(ClusterConnectionService);

    private static readonly API: string = '../nifi-api';

    getNarSummaries(): Observable<any> {
        return this.httpClient.get(`${ExtensionsManagerService.API}/controller/nar-manager/nars`);
    }

    getNarDetails(id: string): Observable<any> {
        return this.httpClient.get(`${ExtensionsManagerService.API}/controller/nar-manager/nars/${id}/details`);
    }

    deleteNar(id: string, force: boolean): Observable<any> {
        const params = new HttpParams({
            fromObject: {
                force,
                disconnectedNodeAcknowledged: this.clusterConnectionService.isDisconnectionAcknowledged()
            }
        });
        return this.httpClient.delete(`${ExtensionsManagerService.API}/controller/nar-manager/nars/${id}`, { params });
    }

    downloadNar(id: string): Observable<HttpResponse<Blob>> {
        return this.httpClient.get(`${ExtensionsManagerService.API}/controller/nar-manager/nars/${id}/content`, {
            observe: 'response',
            responseType: 'blob'
        });
    }
}
