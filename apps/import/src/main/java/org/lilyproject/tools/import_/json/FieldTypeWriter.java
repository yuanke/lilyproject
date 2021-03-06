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
package org.lilyproject.tools.import_.json;

import org.codehaus.jackson.node.JsonNodeFactory;
import org.codehaus.jackson.node.ObjectNode;
import org.lilyproject.repository.api.FieldType;
import org.lilyproject.repository.api.Repository;
import org.lilyproject.repository.api.ValueType;

public class FieldTypeWriter implements EntityWriter<FieldType> {
    public static EntityWriter<FieldType> INSTANCE = new FieldTypeWriter();

    @Override
    public ObjectNode toJson(FieldType fieldType, WriteOptions options, Repository repository) {
        Namespaces namespaces = new NamespacesImpl();

        ObjectNode fieldNode = toJson(fieldType, options, namespaces, repository);

        fieldNode.put("namespaces", NamespacesConverter.toJson(namespaces));

        return fieldNode;
    }

    @Override
    public ObjectNode toJson(FieldType fieldType, WriteOptions options, Namespaces namespaces, Repository repository) {
        return toJson(fieldType, namespaces, true);
    }

    public static ObjectNode toJson(FieldType fieldType, Namespaces namespaces, boolean includeName) {
        ObjectNode fieldNode = JsonNodeFactory.instance.objectNode();

        fieldNode.put("id", fieldType.getId().toString());

        if (includeName) {
            fieldNode.put("name", QNameConverter.toJson(fieldType.getName(), namespaces));
        }

        fieldNode.put("scope", fieldType.getScope().toString().toLowerCase());

        fieldNode.put("valueType", ValueTypeNSConverter.toJson(fieldType.getValueType().getName(), namespaces));

        return fieldNode;
    }
}
