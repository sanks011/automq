/*
 * Copyright 2025, AutoMQ HK Limited.
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package kafka.log.streamaspect;

import com.automq.stream.api.RecordBatch;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Map;

public class RawPayloadRecordBatch implements RecordBatch {
    private final ByteBuffer rawPayload;

    private RawPayloadRecordBatch(ByteBuffer rawPayload) {
        this.rawPayload = rawPayload.duplicate();
    }

    public static RecordBatch of(ByteBuffer rawPayload) {
        return new RawPayloadRecordBatch(rawPayload);
    }

    @Override
    public int count() {
        return rawPayload.remaining();
    }

    @Override
    public long baseTimestamp() {
        return 0;
    }

    @Override
    public Map<String, String> properties() {
        return Collections.emptyMap();
    }

    @Override
    public ByteBuffer rawPayload() {
        return rawPayload.duplicate();
    }
}
