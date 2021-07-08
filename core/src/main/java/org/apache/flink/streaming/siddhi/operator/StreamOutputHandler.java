/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.streaming.siddhi.operator;

import java.util.Map;
import java.util.TreeMap;

import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.tuple.Tuple;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.api.java.typeutils.PojoTypeInfo;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.DeserializationFeature;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.streaming.api.operators.Output;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.siddhi.utils.GenericRecord;
import org.apache.flink.streaming.siddhi.utils.SiddhiTupleFactory;
import org.apache.flink.types.Row;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.siddhi.core.event.Event;
import io.siddhi.core.stream.output.StreamCallback;
import io.siddhi.query.api.definition.AbstractDefinition;

/**
 * Siddhi Stream output callback handler and conver siddhi {@link Event} to required output type,
 * according to output {@link TypeInformation} and siddhi schema {@link AbstractDefinition}
 */
public class StreamOutputHandler<R> extends StreamCallback {
    private static final Logger LOGGER = LoggerFactory.getLogger(StreamOutputHandler.class);

    private String outputStreamId;
    private final AbstractDefinition definition;
    private final Output<StreamRecord<R>> output;
    private final TypeInformation<R> typeInfo;
    private final ObjectMapper objectMapper;

    public StreamOutputHandler(String outputStreamId, TypeInformation<R> typeInfo, AbstractDefinition definition, Output<StreamRecord<R>> output) {
        this.outputStreamId = outputStreamId;
        this.typeInfo = typeInfo;
        this.definition = definition;
        this.output = output;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @Override
    public void receive(Event[] events) {
        StreamRecord<R> reusableRecord = new StreamRecord<>(null, 0L);
        for (Event event : events) {
            if (typeInfo == null
                || (Map.class.isAssignableFrom(typeInfo.getTypeClass())
                || GenericRecord.class.isAssignableFrom(typeInfo.getTypeClass()))) {
                reusableRecord.replace(Tuple2.of(outputStreamId, new GenericRecord(buildMap(event))), event.getTimestamp());
                output.collect(reusableRecord);
            } else if (Row.class.isAssignableFrom(typeInfo.getTypeClass())) {
                reusableRecord.replace(Tuple2.of(outputStreamId, buildRow(event)), event.getTimestamp());
                output.collect(reusableRecord);
            } else if (typeInfo.isTupleType()) {
                Tuple tuple = this.toTuple(event);
                reusableRecord.replace(Tuple2.of(outputStreamId, tuple), event.getTimestamp());
                output.collect(reusableRecord);
            } else if (typeInfo instanceof PojoTypeInfo) {
                R obj;
                try {
                    obj = objectMapper.convertValue(buildMap(event), typeInfo.getTypeClass());
                } catch (IllegalArgumentException ex) {
                    LOGGER.error("Failed to map event: " + event + " into type: " + typeInfo, ex);
                    throw ex;
                }
                reusableRecord.replace(Tuple2.of(outputStreamId, obj), event.getTimestamp());
                output.collect(reusableRecord);
            } else {
                throw new IllegalArgumentException("Unable to format " + event + " as type " + typeInfo);
            }
        }
    }

    @Override
    public synchronized void stopProcessing() {
        super.stopProcessing();
    }

    private Row buildRow(Event event) {
        return Row.of(event.getData());
    }

    private TreeMap<String, Object> buildMap(Event event) {
        TreeMap<String, Object> map = new TreeMap<>();
        for (int i = 0; i < definition.getAttributeNameArray().length; i++) {
            map.put(definition.getAttributeNameArray()[i], event.getData(i));
        }
        return map;
    }

    private <T extends Tuple> T toTuple(Event event) {
        return SiddhiTupleFactory.newTuple(event.getData());
    }
}
