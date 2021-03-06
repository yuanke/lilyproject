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
package org.lilyproject.rest;

import org.lilyproject.repository.api.FieldType;

import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriBuilder;
import java.net.URI;

@Path("schema/fieldType")
public class FieldTypeCollectionResource extends BaseFieldTypeCollectionResource {

    @POST
    @Consumes("application/json")
    @Produces("application/json")
    public Response post(PostAction<FieldType> postAction) {
        FieldType fieldType = processPost(postAction);
        URI uri = UriBuilder.fromResource(FieldTypeResource.class).
                queryParam("ns.n", fieldType.getName().getNamespace()).
                build("n$" + fieldType.getName().getName());
        return Response.created(uri).entity(Entity.create(fieldType)).build();
    }

}

