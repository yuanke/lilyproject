/*
 * Copyright 2010 Outerthought bvba
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.lilycms.repository.api;

import java.util.Map;
import java.util.Map.Entry;

public class RecordNotFoundException extends Exception {

    private final Record record;

    public RecordNotFoundException(Record record) {
        this.record = record;
    }
    
    public Record getRecord() {
        return record;
    }
    
    @Override
    public String getMessage() {
        StringBuffer message = new StringBuffer();
        message.append("Record <");
        message.append(record.getRecordId());
        message.append("> ");
        Map<String, String> variantProperties = record.getVariantProperties();
        if (variantProperties != null && !variantProperties.isEmpty()) {
            for (Entry<String, String> variantEntry : variantProperties.entrySet()) {
                message.append("<");
                message.append(variantEntry.getKey());
                message.append(":");
                message.append(variantEntry.getValue());
                message.append("> ");
            }
        }
        Long version = record.getRecordVersion();
        if (version != null) {
            message.append("<version:");
            message.append(version);
            message.append(">");
        }
        message.append("not found");
        return message.toString();
    }
}
