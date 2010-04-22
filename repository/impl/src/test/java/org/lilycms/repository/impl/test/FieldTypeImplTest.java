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
package org.lilycms.repository.impl.test;


import static org.easymock.classextension.EasyMock.createControl;
import static org.junit.Assert.assertEquals;

import org.easymock.classextension.IMocksControl;
import org.junit.After;
import org.junit.Test;
import org.lilycms.repository.api.FieldType;
import org.lilycms.repository.api.QName;
import org.lilycms.repository.api.Scope;
import org.lilycms.repository.api.ValueType;
import org.lilycms.repository.impl.FieldTypeImpl;
/**
 *
 */
public class FieldTypeImplTest {

    private IMocksControl control = createControl();

    /**
     * @throws java.lang.Exception
     */
    @After
    public void tearDown() throws Exception {
        control.reset();
    }

    @Test
    public void testClone() {
        ValueType valueType = control.createMock(ValueType.class);
        control.replay();
        FieldType fieldType = new FieldTypeImpl("id", valueType, new QName("DS9", "name"), Scope.VERSIONED);
        assertEquals(fieldType, fieldType.clone());
        assertEquals(fieldType, fieldType.clone());
        control.verify();
    }
}