package org.lilycms.repository.api.tutorial;

import org.apache.hadoop.hbase.HBaseTestingUtility;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.lilycms.repository.api.*;
import org.lilycms.repository.impl.*;
import org.lilycms.repoutil.PrintUtil;
import org.lilycms.testfw.TestHelper;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.util.*;

/**
 * The code in this class is used in the repository API tutorial (390-OTC). If this
 * code needs updating because of API changes, then the tutorial itself probably needs
 * to be updated too.
 */
public class Tutorial {
    private final static HBaseTestingUtility TEST_UTIL = new HBaseTestingUtility();

    private static final String NS = "org.lilycms.tutorial";

    private static TypeManager typeManager;
    private static Repository repository;

    @BeforeClass
    public static void setUpBeforeClass() throws Exception {
        TestHelper.setupLogging();
        TEST_UTIL.startMiniCluster(1);

        IdGenerator idGenerator = new IdGeneratorImpl();
        typeManager = new HBaseTypeManager(idGenerator, TEST_UTIL.getConfiguration());

        DFSBlobStoreAccess dfsBlobStoreAccess = new DFSBlobStoreAccess(TEST_UTIL.getDFSCluster().getFileSystem());
        SizeBasedBlobStoreAccessFactory blobStoreAccessFactory = new SizeBasedBlobStoreAccessFactory(dfsBlobStoreAccess);
        blobStoreAccessFactory.addBlobStoreAccess(Long.MAX_VALUE, dfsBlobStoreAccess);

        repository = new HBaseRepository(typeManager, idGenerator, blobStoreAccessFactory, TEST_UTIL.getConfiguration());

        repository.registerBlobStoreAccess(dfsBlobStoreAccess);
    }

    @AfterClass
    public static void tearDownAfterClass() throws Exception {
        TEST_UTIL.shutdownMiniCluster();
    }

    @Test
    public void createRecordType() throws Exception {
        // (1)
        ValueType stringValueType = typeManager.getValueType("STRING", false, false);

        // (2)
        FieldType title = typeManager.newFieldType(stringValueType, new QName(NS, "title"), Scope.VERSIONED);

        // (3)
        title = typeManager.createFieldType(title);

        // (4)
        RecordType book = typeManager.newRecordType("Book");
        book.addFieldTypeEntry(title.getId(), true);

        // (5)
        book = typeManager.createRecordType(book);

        // (6)
        PrintUtil.print(book, repository);
    }

    @Test
    public void updateRecordType() throws Exception {
        ValueType stringValueType = typeManager.getValueType("STRING", false, false);
        ValueType stringMvValueType = typeManager.getValueType("STRING", true, false);
        ValueType longValueType = typeManager.getValueType("LONG", false, false);
        ValueType dateValueType = typeManager.getValueType("DATE", false, false);
        ValueType blobValueType = typeManager.getValueType("BLOB", false, false);
        ValueType linkValueType = typeManager.getValueType("LINK", false, false);

        FieldType description = typeManager.newFieldType(blobValueType, new QName(NS, "description"), Scope.VERSIONED);
        description = typeManager.createFieldType(description);

        FieldType authors = typeManager.newFieldType(stringMvValueType, new QName(NS, "authors"), Scope.VERSIONED);
        authors = typeManager.createFieldType(authors);

        FieldType released = typeManager.newFieldType(dateValueType, new QName(NS, "released"), Scope.VERSIONED);
        released = typeManager.createFieldType(released);

        FieldType pages = typeManager.newFieldType(longValueType, new QName(NS, "pages"), Scope.VERSIONED);
        pages = typeManager.createFieldType(pages);

        FieldType sequelTo = typeManager.newFieldType(linkValueType, new QName(NS, "sequel_to"), Scope.VERSIONED);
        sequelTo = typeManager.createFieldType(sequelTo);

        FieldType manager = typeManager.newFieldType(stringValueType, new QName(NS, "manager"), Scope.NON_VERSIONED);
        manager = typeManager.createFieldType(manager);

        FieldType reviewStatus = typeManager.newFieldType(stringValueType, new QName(NS, "review_status"), Scope.VERSIONED_MUTABLE);
        reviewStatus = typeManager.createFieldType(reviewStatus);

        RecordType book = typeManager.getRecordType("Book", null);

        // The order in which fields are added does not matter
        book.addFieldTypeEntry(description.getId(), false);
        book.addFieldTypeEntry(authors.getId(), false);
        book.addFieldTypeEntry(released.getId(), false);
        book.addFieldTypeEntry(pages.getId(), false);
        book.addFieldTypeEntry(sequelTo.getId(), false);
        book.addFieldTypeEntry(manager.getId(), false);
        book.addFieldTypeEntry(reviewStatus.getId(), false);

        // Now we call updateRecordType instead of createRecordType
        book = typeManager.updateRecordType(book);

        PrintUtil.print(book, repository);
    }

    @Test
    public void createRecord() throws Exception {
        // (1)
        Record record = repository.newRecord();

        // (2)
        record.setRecordType("Book", null);

        // (3)
        record.setField(new QName(NS, "title"), "Lily, the definitive guide, 3rd edition");

        // (4)
        record = repository.create(record);

        // (5)
        PrintUtil.print(record, repository);
    }

    @Test
    public void createRecordUserSpecifiedId() throws Exception {
        RecordId id = repository.getIdGenerator().newRecordId("lily-definitive-guide-3rd-edition");
        Record record = repository.newRecord(id);
        record.setRecordType("Book", null);
        record.setField(new QName(NS, "title"), "Lily, the definitive guide, 3rd edition");
        record = repository.create(record);

        PrintUtil.print(record, repository);
    }

    @Test
    public void updateRecord() throws Exception {
        RecordId id = repository.getIdGenerator().newRecordId("lily-definitive-guide-3rd-edition");
        Record record = repository.newRecord(id);
        record.setRecordType("Book", null); // TODO should not be necessary (r29)
        record.setField(new QName(NS, "title"), "Lily, the definitive guide, third edition");
        record.setField(new QName(NS, "pages"), Long.valueOf(912));
        record.setField(new QName(NS, "manager"), "Manager M");
        record = repository.update(record);

        PrintUtil.print(record, repository);
    }

    @Test
    public void updateRecordViaRead() throws Exception {
        RecordId id = repository.getIdGenerator().newRecordId("lily-definitive-guide-3rd-edition");
        Record record = repository.read(id);
        record.setField(new QName(NS, "released"), new Date());
        record.setField(new QName(NS, "authors"), Arrays.asList("Author A", "Author B"));
        record.setField(new QName(NS, "review_status"), "reviewed");
        record = repository.update(record);

        PrintUtil.print(record, repository);
    }

    @Test
    public void readRecord() throws Exception {
        RecordId id = repository.getIdGenerator().newRecordId("lily-definitive-guide-3rd-edition");

        // (1)
        Record record = repository.read(id);
        System.out.println(record.getField(new QName(NS, "title")));

        // (2)
        record = repository.read(id, 1L);
        System.out.println(record.getField(new QName(NS, "title")));

        // (3)
        record = repository.read(id, 1L, Arrays.asList(new QName(NS, "title")));
        System.out.println(record.getField(new QName(NS, "title")));
    }

    @Test
    public void blob() throws Exception {
        //
        // Write a blob
        //

        String description = "<html><body>This book gives thorough insight into Lily, ...</body></html>";
        byte[] descriptionData = description.getBytes("UTF-8");

        // (1)
        Blob blob = new Blob("text/html", (long)descriptionData.length, "description.xml");
        OutputStream os = repository.getOutputStream(blob);
        try {
            os.write(descriptionData);
        } finally {
            os.close();
        }

        // (2)
        RecordId id = repository.getIdGenerator().newRecordId("lily-definitive-guide-3rd-edition");
        Record record = repository.newRecord(id);
        record.setRecordType("Book", null); // TODO should not be necessary (r29)
        record.setField(new QName(NS, "description"), blob);
        record = repository.update(record);

        //
        // Read a blob
        //
        InputStream is = null;
        try {
            is = repository.getInputStream((Blob)record.getField(new QName(NS, "description")));
            System.out.println("Data read from blob is:");
            Reader reader = new InputStreamReader(is, "UTF-8");
            char[] buffer = new char[20];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                System.out.print(new String(buffer, 0, read));
            }
            System.out.println();
        } finally {
            if (is != null) is.close();
        }        
    }

    @Test
    public void variantRecord() throws Exception {
        // TODO
//        Map<String, String> variantProps = new HashMap<String, String>();
//        variantProps.put("language", "en");
//
//        IdGenerator idGenerator = repository.getIdGenerator();
//
//        RecordId masterId = idGenerator.newRecordId();
//
//        RecordId enId = idGenerator.newRecordId(masterId, variantProps);
//        Record enRecord = repository.newRecord(enId);
//        enRecord.setRecordType("Book", null);
//        enRecord.setField(new QName(NS, "title"), "Car maintenance");
//        enRecord = repository.create(enRecord);
//
//        RecordId nlId = idGenerator.newRecordId(enRecord.getId().getMaster(), Collections.singletonMap("language", "nl"));
//        Record nlRecord = repository.newRecord(enId);
//        nlRecord.setRecordType("Book", null);
//        nlRecord.setField(new QName(NS, "title"), "Wagen onderhoud");
//        nlRecord = repository.create(enRecord);
    }

    @Test
    public void linkField() throws Exception {
        // (1)
        Record record1 = repository.newRecord();
        record1.setRecordType("Book", null);
        record1.setField(new QName(NS, "title"), "Fishing 1");
        record1 = repository.create(record1);

        // (2)
        Record record2 = repository.newRecord();
        record2.setRecordType("Book", null);
        record2.setField(new QName(NS, "title"), "Fishing 2");
        record2.setField(new QName(NS, "sequel_to"), record1.getId());
        record2 = repository.create(record2);

        // (3)
        RecordId sequelTo = (RecordId)record2.getField(new QName(NS, "sequel_to"));
        Record linkedRecord = repository.read(sequelTo);
        System.out.println(linkedRecord.getField(new QName(NS, "title")));
    }

}
