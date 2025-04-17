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
package org.apache.nifi.kafka.processors.producer.wrapper;

import org.apache.nifi.kafka.service.api.header.RecordHeader;
import org.apache.nifi.serialization.SimpleRecordSchema;
import org.apache.nifi.serialization.record.MapRecord;
import org.apache.nifi.serialization.record.Record;
import org.apache.nifi.serialization.record.RecordField;
import org.apache.nifi.serialization.record.RecordFieldType;
import org.apache.nifi.serialization.record.RecordSchema;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Record implementation for use with {@link org.apache.nifi.kafka.processors.PublishKafka} publish strategy
 * {@link org.apache.nifi.kafka.shared.property.PublishStrategy#USE_WRAPPER}.
 */
public class WrapperRecord extends MapRecord {

    public static final String TOPIC = "topic";
    public static final String PARTITION = "partition";
    public static final String OFFSET = "offset";
    public static final String TIMESTAMP = "timestamp";
    public static final String HEADERS = "headers";
    public static final String KEY = "key";

    public static String METADATA_FIELD_NAME = "metadata";
    public static String HEADERS_FIELD_NAME = null;
    public static String KEY_FIELD_NAME = null;
    public static String VALUE_FIELD_NAME = "value";

    private static final RecordField FIELD_TOPIC = new RecordField(TOPIC, RecordFieldType.STRING.getDataType());
    private static final RecordField FIELD_PARTITION = new RecordField(PARTITION, RecordFieldType.INT.getDataType());
    private static final RecordField FIELD_OFFSET = new RecordField(OFFSET, RecordFieldType.LONG.getDataType());
    private static final RecordField FIELD_TIMESTAMP = new RecordField(TIMESTAMP, RecordFieldType.TIMESTAMP.getDataType());
    public static final RecordField FIELD_HEADERS = new RecordField(HEADERS, RecordFieldType.MAP.getMapDataType(RecordFieldType.STRING.getDataType()));

    public WrapperRecord(final Record record, final String messageKeyField,
                         final List<RecordHeader> headers, final Charset headerCharset,
                         final String topic, final int partition, final long offset, final long timestamp) {
        super(toRecordSchema(record, messageKeyField),
                toValues(record, headers, headerCharset, messageKeyField, topic, partition, offset, timestamp));
    }

    public static void setFieldNames(final String metadataFieldName, final String headersFieldName, final String keyFieldName, final String valueFieldName) {
        METADATA_FIELD_NAME = metadataFieldName;
        HEADERS_FIELD_NAME = headersFieldName;
        KEY_FIELD_NAME = keyFieldName;
        VALUE_FIELD_NAME = valueFieldName;
    }

    private static RecordSchema toRecordSchema(final Record record, final String messageKeyField) {
        final Record recordKey = (Record) record.getValue(messageKeyField);
        final RecordSchema recordSchemaKey = ((recordKey == null) ? null : recordKey.getSchema());
        final RecordField fieldKey = new RecordField(KEY, RecordFieldType.RECORD.getRecordDataType(recordSchemaKey));
        return toWrapperSchema(fieldKey, record.getSchema());
    }

    private static Map<String, Object> toValues(
            final Record record, final List<RecordHeader> headers, final Charset headerCharset,
            final String messageKeyField, final String topic, final int partition, final long offset, final long timestamp) {

        final Map<String, Map<String, Object>> valuesMap = new HashMap<>();
        final Map<String, Object> valuesWrapper = new HashMap<>();

        if (METADATA_FIELD_NAME != null) {
            final Map<String, Object> valuesMetadata = new HashMap<>();
            valuesMetadata.put(TOPIC, topic);
            valuesMetadata.put(PARTITION, partition);
            valuesMetadata.put(OFFSET, offset);
            valuesMetadata.put(TIMESTAMP, timestamp);
            valuesMap.put(METADATA_FIELD_NAME, valuesMetadata);
        } else {
            valuesWrapper.put(TOPIC, topic);
            valuesWrapper.put(PARTITION, partition);
            valuesWrapper.put(OFFSET, offset);
            valuesWrapper.put(TIMESTAMP, timestamp);
        }

        final Map<String, Object> valuesHeaders = new HashMap<>();
        for (RecordHeader header : headers) {
            valuesHeaders.put(header.key(), new String(header.value(), headerCharset));
        }

        if (HEADERS_FIELD_NAME != null) {
            if (valuesMap.containsKey(HEADERS_FIELD_NAME)) {
                valuesMap.get(HEADERS_FIELD_NAME).put(HEADERS, valuesHeaders);
            } else {
                Map<String, Object> headersMap = new HashMap<>();
                headersMap.put(HEADERS, valuesHeaders);
                valuesMap.put(HEADERS_FIELD_NAME, headersMap);
            }
        } else {
            valuesWrapper.put(HEADERS, valuesHeaders);
        }

        if (KEY_FIELD_NAME != null) {
            if (valuesMap.containsKey(KEY_FIELD_NAME)) {
                valuesMap.get(KEY_FIELD_NAME).put(KEY, record.getValue(messageKeyField));
            } else {
                Map<String, Object> keyMap = new HashMap<>();
                keyMap.put(KEY, record.getValue(messageKeyField));
                valuesMap.put(KEY_FIELD_NAME, keyMap);
            }
        } else {
            valuesWrapper.put(KEY, record.getValue(messageKeyField));
        }

        if (VALUE_FIELD_NAME != null) {
            if (valuesMap.containsKey(VALUE_FIELD_NAME)) {
                valuesMap.get(VALUE_FIELD_NAME).putAll(record.toMap());
            } else {
                valuesMap.put(VALUE_FIELD_NAME, record.toMap());
            }
        } else {
            valuesWrapper.putAll(record.toMap());
        }

        for (String key : valuesMap.keySet()) {
            valuesWrapper.put(key, valuesMap.get(key));
        }

        return valuesWrapper;
    }

    public static RecordSchema toWrapperSchema(final RecordField fieldKey, final RecordSchema recordSchema) {
        final Map<String, List<RecordField>> fields = new HashMap<>();
        final List<RecordField> rootLevelFields = new ArrayList<>();

        if (KEY_FIELD_NAME == null) {
            rootLevelFields.add(fieldKey);
        } else {
            if (fields.containsKey(KEY_FIELD_NAME)) {
                fields.get(KEY_FIELD_NAME).add(fieldKey);
            } else {
                List<RecordField> keyField = new ArrayList<>();
                keyField.add(fieldKey);
                fields.put(KEY_FIELD_NAME, keyField);
            }
        }

        if (HEADERS_FIELD_NAME == null) {
            rootLevelFields.add(FIELD_HEADERS);
        } else {
            if (fields.containsKey(HEADERS_FIELD_NAME)) {
                fields.get(HEADERS_FIELD_NAME).add(FIELD_HEADERS);
            } else {
                List<RecordField> headersField = new ArrayList<>();
                headersField.add(FIELD_HEADERS);
                fields.put(HEADERS_FIELD_NAME, headersField);
            }
        }

        if (VALUE_FIELD_NAME == null) {
            rootLevelFields.addAll(recordSchema.getFields());
        } else {
            if (fields.containsKey(VALUE_FIELD_NAME)) {
                fields.get(VALUE_FIELD_NAME).addAll(recordSchema.getFields());
            } else {
                List<RecordField> valueField = new ArrayList<>();
                valueField.addAll(recordSchema.getFields());
                fields.put(VALUE_FIELD_NAME, valueField);
            }
        }

        if (METADATA_FIELD_NAME == null) {
            rootLevelFields.add(FIELD_TOPIC);
            rootLevelFields.add(FIELD_PARTITION);
            rootLevelFields.add(FIELD_OFFSET);
            rootLevelFields.add(FIELD_TIMESTAMP);
        } else {
            if (fields.containsKey(METADATA_FIELD_NAME)) {
                fields.get(METADATA_FIELD_NAME).add(FIELD_TOPIC);
                fields.get(METADATA_FIELD_NAME).add(FIELD_PARTITION);
                fields.get(METADATA_FIELD_NAME).add(FIELD_OFFSET);
                fields.get(METADATA_FIELD_NAME).add(FIELD_TIMESTAMP);
            } else {
                List<RecordField> metadataField = new ArrayList<>();
                metadataField.add(FIELD_TOPIC);
                metadataField.add(FIELD_PARTITION);
                metadataField.add(FIELD_OFFSET);
                metadataField.add(FIELD_TIMESTAMP);
                fields.put(METADATA_FIELD_NAME, metadataField);
            }
        }

        for (String fieldName : fields.keySet()) {
            rootLevelFields.add(new RecordField(fieldName, RecordFieldType.RECORD.getRecordDataType(new SimpleRecordSchema(fields.get(fieldName)))));
        }

        return new SimpleRecordSchema(rootLevelFields);
    }
}
