package org.lilycms.rest;

import org.lilycms.repository.api.*;
import org.lilycms.rest.import_.*;
import org.springframework.beans.factory.annotation.Autowired;

import javax.ws.rs.*;
import javax.ws.rs.core.*;

import java.net.URI;

import static javax.ws.rs.core.Response.Status.*;

@Path("/schema/fieldType/{name}")
public class FieldTypeResource {
    private Repository repository;

    @GET
    @Produces("application/json")
    public FieldType get(@PathParam("name") String name, @Context UriInfo uriInfo) {
        QName qname = ResourceClassUtil.parseQName(name, uriInfo.getQueryParameters());
        try {
            return repository.getTypeManager().getFieldTypeByName(qname);
        } catch (FieldTypeNotFoundException e) {
            throw new ResourceException(e, NOT_FOUND.getStatusCode());
        } catch (TypeException e) {
            throw new ResourceException("Error loading field type with name " + qname, e, INTERNAL_SERVER_ERROR.getStatusCode());
        }
    }

    @PUT
    @Produces("application/json")
    @Consumes("application/json")
    public Response put(@PathParam("id") String id, FieldType fieldType) {
        // Since the name can be updated, in this case we allow that the name in the submitted field type
        // is different from the name in the URI.

        ImportResult<FieldType> result;
        try {
            result = FieldTypeImport.importFieldType(fieldType, ImportMode.CREATE_OR_UPDATE, IdentificationMode.NAME,
                    repository.getTypeManager());
        } catch (RepositoryException e) {
            throw new ResourceException("Error creating or updating field type with id " + id, e,
                    INTERNAL_SERVER_ERROR.getStatusCode());
        }

        fieldType = result.getEntity();
        Response response;

        ImportResultType resultType = result.getResultType();
        switch (resultType) {
            case CREATED:
                URI uri = UriBuilder.fromResource(FieldTypeByIdResource.class).build(fieldType.getId());
                response = Response.created(uri).entity(fieldType).build();
                break;
            case UPDATED:
            case UP_TO_DATE:
                response = Response.ok(fieldType).build();
                break;
            default:
                throw new RuntimeException("Unexpected import result type: " + resultType);
        }

        return response;
    }

    @Autowired
    public void setRepository(Repository repository) {
        this.repository = repository;
    }
}