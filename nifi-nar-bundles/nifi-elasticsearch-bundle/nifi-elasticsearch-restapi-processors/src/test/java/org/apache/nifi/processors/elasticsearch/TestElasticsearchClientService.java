/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License") you may not use this file except in compliance with
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
package org.apache.nifi.processors.elasticsearch;

import org.apache.nifi.components.ConfigVerificationResult;
import org.apache.nifi.controller.AbstractControllerService;
import org.apache.nifi.controller.ConfigurationContext;
import org.apache.nifi.elasticsearch.DeleteOperationResponse;
import org.apache.nifi.elasticsearch.ElasticSearchClientService;
import org.apache.nifi.elasticsearch.IndexOperationRequest;
import org.apache.nifi.elasticsearch.IndexOperationResponse;
import org.apache.nifi.elasticsearch.SearchResponse;
import org.apache.nifi.elasticsearch.UpdateOperationResponse;
import org.apache.nifi.logging.ComponentLog;
import org.apache.nifi.processors.elasticsearch.mock.MockElasticsearchException;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class TestElasticsearchClientService extends AbstractControllerService implements ElasticSearchClientService {
    private static final String AGGS_RESULT;
    private static final String HITS_RESULT;

    static {
        try {
            AGGS_RESULT = JsonUtils.readString(Paths.get("src/test/resources/TestElasticsearchClientService/aggsResult.json"));
            HITS_RESULT = JsonUtils.readString(Paths.get("src/test/resources/TestElasticsearchClientService/hitsResult.json"));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private final boolean returnAggs;
    private boolean throwErrorInSearch;
    private boolean throwErrorInGet;
    private boolean throwNotFoundInGet;
    private boolean throwErrorInDelete;
    private boolean throwErrorInPit;
    private boolean throwErrorInUpdate;
    private int pageCount = 0;
    private int maxPages = 1;
    private Map<String, String> requestParameters;

    public TestElasticsearchClientService(boolean returnAggs) {
        this.returnAggs = returnAggs;
    }

    private void common(boolean throwError, Map<String, String> requestParameters) throws IOException {
        if (throwError) {
            if (throwNotFoundInGet) {
                throw new MockElasticsearchException(false, true);
            } else {
                throw new IOException("Simulated IOException");
            }
        }

        this.requestParameters = requestParameters;
    }

    @Override
    public List<ConfigVerificationResult> verify(ConfigurationContext context, ComponentLog verificationLogger, Map<String, String> variables) {
        return null;
    }

    @Override
    public IndexOperationResponse add(IndexOperationRequest operation, Map<String, String> requestParameters) {
        return bulk(Collections.singletonList(operation), requestParameters);
    }

    @Override
    public IndexOperationResponse bulk(List<IndexOperationRequest> operations, Map<String, String> requestParameters) {
        try {
            common(false, requestParameters);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return new IndexOperationResponse(100L);
    }

    @Override
    public Long count(String query, String index, String type, Map<String, String> requestParameters) {
        try {
            common(false, requestParameters);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return null;
    }

    @Override
    public DeleteOperationResponse deleteById(String index, String type, String id, Map<String, String> requestParameters) {
        return deleteById(index, type, Collections.singletonList(id), requestParameters);
    }

    @Override
    public DeleteOperationResponse deleteById(String index, String type, List<String> ids, Map<String, String> requestParameters) {
        try {
            common(throwErrorInDelete, requestParameters);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return new DeleteOperationResponse(100L);
    }

    @Override
    public DeleteOperationResponse deleteByQuery(String query, String index, String type, Map<String, String> requestParameters) {
        return deleteById(index, type, Collections.singletonList("1"), requestParameters);
    }

    @Override
    public UpdateOperationResponse updateByQuery(String query, String index, String type, Map<String, String> requestParameters) {
        try {
            common(throwErrorInUpdate, requestParameters);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return new UpdateOperationResponse(100L);
    }

    @Override
    public void refresh(final String index, final Map<String, String> requestParameters) {
    }

    @Override
    public boolean exists(final String index, final Map<String, String> requestParameters) {
        return true;
    }

    @Override
    public boolean documentExists(String index, String type, String id, Map<String, String> requestParameters) {
        return true;
    }

    @Override
    public Map<String, Object> get(String index, String type, String id, Map<String, String> requestParameters) {
        try {
            common(throwErrorInGet || throwNotFoundInGet, requestParameters);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        Map<String, Object> map = new LinkedHashMap<>(1);
        map.put("msg", "one");
        return map;
    }

    @Override
    public SearchResponse search(String query, String index, String type, Map<String, String> requestParameters) {
        try {
            common(throwErrorInSearch, requestParameters);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        final SearchResponse response;
        if (pageCount++ < maxPages) {
            List<Map<String, Object>> hits = JsonUtils.readListOfMaps(HITS_RESULT);
            Map<String, Object> aggs = returnAggs && pageCount == 1 ? JsonUtils.readMap(AGGS_RESULT) : null;
            response = new SearchResponse(hits, aggs, "pitId-" + pageCount, "scrollId-" + pageCount, "[\"searchAfter-" + pageCount + "\"]", 15, 5, false, null);
        } else {
            response = new SearchResponse(new ArrayList<>(), new LinkedHashMap<>(), "pitId-" + pageCount, "scrollId-" + pageCount, "[\"searchAfter-" + pageCount + "\"]", 0, 1, false, null);
        }

        return response;
    }

    @Override
    public SearchResponse scroll(String scroll) {
        if (throwErrorInSearch) {
            throw new RuntimeException(new IOException("Simulated IOException - scroll"));
        }


        return search(null, null, null, requestParameters);
    }

    @Override
    public String initialisePointInTime(String index, String keepAlive) {
        if (throwErrorInPit) {
            throw new RuntimeException(new IOException("Simulated IOException - initialisePointInTime"));
        }

        pageCount = 0;

        return "123";
    }

    @Override
    public DeleteOperationResponse deletePointInTime(String pitId) {
        if (throwErrorInDelete) {
            throw new RuntimeException(new IOException("Simulated IOException - deletePointInTime"));
        }

        return new DeleteOperationResponse(100L);
    }

    @Override
    public DeleteOperationResponse deleteScroll(String scrollId) {
        if (throwErrorInDelete) {
            throw new RuntimeException(new IOException("Simulated IOException - deleteScroll"));
        }

        return new DeleteOperationResponse(100L);
    }

    @Override
    public String getTransitUrl(final String index, final String type) {
        return "http://localhost:9400/" + index + "/" + type;
    }

    public void setThrowNotFoundInGet(boolean throwNotFoundInGet) {
        this.throwNotFoundInGet = throwNotFoundInGet;
    }

    public void setThrowErrorInGet(boolean throwErrorInGet) {
        this.throwErrorInGet = throwErrorInGet;
    }

    public void setThrowErrorInSearch(boolean throwErrorInSearch) {
        this.throwErrorInSearch = throwErrorInSearch;
    }

    public void setThrowErrorInDelete(boolean throwErrorInDelete) {
        this.throwErrorInDelete = throwErrorInDelete;
    }

    public void setThrowErrorInPit(boolean throwErrorInPit) {
        this.throwErrorInPit = throwErrorInPit;
    }

    public void setThrowErrorInUpdate(boolean throwErrorInUpdate) {
        this.throwErrorInUpdate = throwErrorInUpdate;
    }

    public void resetPageCount() {
        this.pageCount = 0;
    }

    public void setMaxPages(int maxPages) {
        this.maxPages = maxPages;
    }

    public Map<String, String> getRequestParameters() {
        return this.requestParameters;
    }
}