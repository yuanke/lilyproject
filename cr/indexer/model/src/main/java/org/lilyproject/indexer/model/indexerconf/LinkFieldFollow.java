package org.lilyproject.indexer.model.indexerconf;

import java.util.Collections;
import java.util.List;

import org.lilyproject.repository.api.FieldType;
import org.lilyproject.repository.api.IdGenerator;
import org.lilyproject.repository.api.Link;
import org.lilyproject.repository.api.Record;
import org.lilyproject.repository.api.RecordId;
import org.lilyproject.repository.api.RecordNotFoundException;
import org.lilyproject.repository.api.RepositoryException;
import org.lilyproject.repository.api.VersionNotFoundException;
import org.lilyproject.util.repo.VersionTag;

public class LinkFieldFollow implements Follow {
    private FieldType fieldType;
    /**
     * If the link field follow is after one or more record follows, then from
     * the point of view of the link index, the link belongs to the same field
     * as the top-level record field. We keep a reference to that field here.
     */
    private FieldType ownerFieldType;

    public LinkFieldFollow(FieldType fieldType) {
        this.fieldType = fieldType;
        this.ownerFieldType = fieldType;
    }

    public FieldType getFieldType() {
        return fieldType;
    }

    public FieldType getOwnerFieldType() {
        return ownerFieldType;
    }

    public void setOwnerFieldType(FieldType ownerFieldType) {
        this.ownerFieldType = ownerFieldType;
    }

    public void follow(IndexUpdateBuilder indexUpdateBuilder, FollowCallback callback) throws RepositoryException, InterruptedException {
        if (!indexUpdateBuilder.getSystemFields().isSystemField(fieldType.getName())) {
            indexUpdateBuilder.addDependency(fieldType.getId());
        }
        IdGenerator idGenerator = indexUpdateBuilder.getRepository().getIdGenerator();

        RecordContext ctx = indexUpdateBuilder.getRecordContext();

        // FIXME: it's more efficient to read all records at once
        // but make sure missing records are also treated (handled here via null linkedRecord in case of RecordNotFoundException
        if (ctx.record != null) {
            List links = IndexerUtils.flatList(ctx.record, fieldType);
            for (Link link: (List<Link>)links) {
                RecordId linkedRecordId = link.resolve(ctx.contextRecord, idGenerator);
                Record linkedRecord = null;
                try {
                    linkedRecord = VersionTag.getIdRecord(linkedRecordId, indexUpdateBuilder.getVTag(), indexUpdateBuilder.getRepository());
                } catch (RecordNotFoundException rnfe) {
                    // ok, continue with null value
                } catch (VersionNotFoundException e) {
                    // ok, continue with null value
                }
                indexUpdateBuilder.push(linkedRecord, new Dep(linkedRecordId, Collections.<String>emptySet()));
                callback.call();
                indexUpdateBuilder.pop();
            }
        }
    }


}
