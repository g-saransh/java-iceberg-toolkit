/**
 * (c) Copyright IBM Corp. 2022. All Rights Reserved.
 */

package iceberg;

import iceberg.utils.DataConversion;

import java.util.*;
import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URI;

import org.apache.iceberg.exceptions.NoSuchNamespaceException;
import org.apache.iceberg.exceptions.NoSuchTableException;
import org.apache.iceberg.hadoop.HadoopInputFile;
import org.apache.iceberg.exceptions.NamespaceNotEmptyException;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.shaded.com.google.common.collect.ImmutableList;
import org.apache.iceberg.AppendFiles;
import org.apache.iceberg.CombinedScanTask;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.DataFiles;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.FileScanTask;
import org.apache.iceberg.HasTableOperations;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.RewriteFiles;
import org.apache.iceberg.io.CloseableIterable;
import org.apache.iceberg.io.FileAppender;
import org.apache.iceberg.io.LocationProvider;
import org.apache.iceberg.io.OutputFile;
import org.apache.iceberg.parquet.Parquet;
import org.apache.iceberg.hive.HiveCatalog;
import org.apache.iceberg.data.parquet.GenericParquetReaders;
import org.apache.iceberg.data.parquet.GenericParquetWriter;
import org.apache.iceberg.exceptions.AlreadyExistsException;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.data.GenericRecord;
import org.apache.iceberg.data.IcebergGenerics;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableScan;
import org.apache.iceberg.Transaction;
import org.apache.iceberg.aws.s3.S3FileIO;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.types.Type;
import org.apache.iceberg.types.Types;
import org.apache.iceberg.util.SerializableSupplier;
import org.apache.parquet.ParquetReadOptions;
import org.apache.parquet.avro.AvroParquetReader;
import org.apache.parquet.column.ParquetProperties;
import org.apache.parquet.hadoop.Footer;
import org.apache.parquet.hadoop.ParquetFileReader;
import org.apache.parquet.hadoop.ParquetReader;
import org.apache.parquet.hadoop.metadata.ParquetMetadata;
import org.apache.parquet.io.InputFile;
import org.apache.thrift.TException;
import org.apache.iceberg.TableMetadata;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.google.common.io.Files;
import com.amazonaws.regions.Regions;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

public class IcebergConnector extends MetastoreConnector
{
    HiveCatalog m_catalog;
    TableIdentifier m_tableIdentifier;
    Table iceberg_table;
    TableScan m_scan;

    public IcebergConnector(String metastoreUri, String warehouse, String namespace, String tableName) {
        // TODO: Get type of catalog that the user wants and then initialize accordingly
        super(metastoreUri, warehouse, namespace, tableName);
        initCatalog(metastoreUri, warehouse);
        if (tableName != null)
            setTableIdentifier(namespace, tableName);
    }
    
    private void initCatalog(String metastoreUri, String warehouse) {
        m_catalog = new HiveCatalog();
        
        // Set Hadoop configuration
        Configuration config = new Configuration();
        if (System.getenv("AWS_ACCESS_KEY_ID") != null)
            config.set("fs.s3a.access.key", System.getenv("AWS_ACCESS_KEY_ID"));
        if (System.getenv("AWS_SECRET_ACCESS_KEY") != null)
            config.set("fs.s3a.secret.key", System.getenv("AWS_SECRET_ACCESS_KEY"));
        if (warehouse != null)
            config.set("hive.metastore.warehouse.dir", warehouse);
        m_catalog.setConf(config);
        
        // Set properties
        Map <String, String> properties = new HashMap<String, String>();
        properties.put("uri", metastoreUri);
        properties.put("list-all-tables", "true");
        if (warehouse != null)
            properties.put("warehouse", warehouse);
        
        // Initialize Hive catalog
        m_catalog.initialize("hive", properties);
    }
    
    public void setTableIdentifier(String namespace, String tableName) {
        m_tableIdentifier = TableIdentifier.of(namespace, tableName);
    }

    public Table loadTable(TableIdentifier identifier) {
        // Check if the table exists
        if (!m_catalog.tableExists(identifier)) {
            throw new TableNotFoundException("ERROR: Table " + identifier + " does not exist");
        }

        Table table = m_catalog.loadTable(identifier);
        // Double check if the table was loaded properly
        if (table == null)
            throw new TableNotLoaded("ERROR Loading table: " + identifier);
        
        return table;
    }
    
    public void loadTable() {
        iceberg_table = loadTable(m_tableIdentifier);

        // Use snapshot passed by the user.
        // By default, use the latest snapshot.
        m_scan = iceberg_table.newScan();
        if (m_snapshotId != null) {
            m_scan = m_scan.useSnapshot(m_snapshotId);
        }
    }
    
    public boolean createTable(Schema schema, PartitionSpec spec, boolean overwrite) {
        if (m_catalog.tableExists(m_tableIdentifier)) {
            if (overwrite) {
                // To overwrite an existing table, drop it first
                m_catalog.dropTable(m_tableIdentifier);
            } else {
                throw new RuntimeException("Table " + m_tableIdentifier + " already exists");
            }
        }
        
        System.out.println("Creating the table " + m_tableIdentifier);
        m_catalog.createTable(m_tableIdentifier, schema, spec);
        System.out.println("Table created successfully");
        
        return true;
    }
    
    public boolean dropTable() {
        if (iceberg_table == null)
            loadTable();
        
        System.out.println("Dropping the table " + m_tableIdentifier);
        if (m_catalog.dropTable(m_tableIdentifier)) {
            System.out.println("Table dropped successfully");
            return true;
        }
        return false;
    }
    
    public List<List<String>> readTable() throws UnsupportedEncodingException {
        if (iceberg_table == null)
            loadTable();
        
        // Get records
        System.out.println("Records in " + m_tableIdentifier + " :");
        IcebergGenerics.ScanBuilder scanBuilder = IcebergGenerics.read(iceberg_table);
        // Use specified snapshot, latest by default
        CloseableIterable<Record> records = scanBuilder.useSnapshot(m_scan.snapshot().snapshotId()).build();
        List<List<String>> output = new ArrayList<List<String>>();
        for (Record record : records) {
            int numFields = record.size();
            List<String> rec = new ArrayList<String>(numFields);
            for(int x = 0; x < numFields; x++) {
                // A field can be optional, add a check for null values
                Object value = record.get(x);
                rec.add(value == null ? "null" : value.toString());
            }
            output.add(rec);
        }
        return output;
    }

    public Map<Integer, List<Map<String, String>>> getPlanFiles() {
        if (iceberg_table == null)
            loadTable();
        
        Iterable<CombinedScanTask> scanTasks = m_scan.planTasks();
        Map<Integer, List<Map<String, String>>> tasks = new HashMap<Integer, List<Map<String, String>>>();
        int index = 0;
        for (CombinedScanTask scanTask : scanTasks) {
            List<Map<String, String>> taskMapList = new ArrayList<Map<String, String>>();
            for (FileScanTask fileTask : scanTask.files()) {
                Map<String, String> taskMap = new HashMap<String, String>();
                DataFile file = fileTask.file();
                taskMap.put("content", file.content().toString());
                taskMap.put("file_path", file.path().toString());
                taskMap.put("file_format", file.format().toString());
                taskMap.put("start", Long.toString(fileTask.start()));
                taskMap.put("length", Long.toString(fileTask.length()));
                taskMap.put("spec", fileTask.spec().toString());
                taskMap.put("residual", fileTask.residual().toString());
                taskMapList.add(taskMap);
            }
            tasks.put(index++, taskMapList);
        }
        
        return tasks;
    }
    
    public java.util.List<String> listTables(String namespace) {
        List<TableIdentifier> tables = m_catalog.listTables(Namespace.of(namespace));
        return tables.stream().map(TableIdentifier::name).toList();
    }
    
    public java.util.List<Namespace> listNamespaces() {
        return m_catalog.listNamespaces();
    }
    
    public boolean createNamespace(Namespace namespace) throws AlreadyExistsException, UnsupportedOperationException {
        m_catalog.createNamespace(namespace);
        System.out.println("Namespace " + namespace + " created");
        
        return true;
        
    }
    
    public boolean dropNamespace(Namespace namespace) throws NamespaceNotEmptyException {
        if(m_catalog.dropNamespace(namespace)) {
            System.out.println("Namespace " + namespace + " dropped");
            return true;
        }
        return false;
    }
    
    public boolean renameTable(TableIdentifier from, TableIdentifier to) throws NoSuchTableException, AlreadyExistsException {
        m_catalog.renameTable(from, to);
        System.out.println("Table " + from + " renamed to " + to);
        
        return true;
    }
    
    public java.util.Map<java.lang.String,java.lang.String> loadNamespaceMetadata(Namespace namespace) throws NoSuchNamespaceException {
        return m_catalog.loadNamespaceMetadata(namespace);
    }
    
    public String getTableLocation() {
        if (iceberg_table == null)
            loadTable();
        
        String tableLocation = iceberg_table.location();
        
        // Remove trailing backslash
        if (tableLocation.endsWith("/"))
            return tableLocation.substring(0, tableLocation.length() - 1);
        return tableLocation;
    }

    public String getTableDataLocation() {
        if (iceberg_table == null)
            loadTable();

        LocationProvider provider = iceberg_table.locationProvider();
        String dataLocation = provider.newDataLocation("");
        
        // Remove trailing backslash
        if (dataLocation.endsWith("/"))
            return dataLocation.substring(0, dataLocation.length() - 1);
        return dataLocation;
    }
    
    public PartitionSpec getSpec() {
        if (iceberg_table == null)
            loadTable();

        PartitionSpec spec = iceberg_table.spec();
        
        return spec;
    }
    
    public String getUUID() {
        if (iceberg_table == null)
            loadTable();
        TableMetadata metadata = ((HasTableOperations) iceberg_table).operations().current();
        return metadata.uuid();
    }
         
    public Snapshot getCurrentSnapshot() {
        if (iceberg_table == null)
            loadTable();

        return m_scan.snapshot();
    }
    
    public Long getCurrentSnapshotId() {
        if (iceberg_table == null)
            loadTable();
        
        Snapshot snapshot = getCurrentSnapshot();
        if (snapshot != null)
            return snapshot.snapshotId();
        return null;
    }

    public java.lang.Iterable<Snapshot> getListOfSnapshots() {
        if (iceberg_table == null)
            loadTable();

        java.lang.Iterable<Snapshot> snapshots = iceberg_table.snapshots();
        
        return snapshots;
    }
    
    public String writeTable(String records, String outputFile) throws IOException {
        if (iceberg_table == null)
            loadTable();
        
        System.out.println("Writing to the table " + m_tableIdentifier);
        
        // Check if outFilePath or name is passed by the user
        if (outputFile == null) {
            outputFile = String.format("%s/icebergdata-%s.parquet", getTableDataLocation(), UUID.randomUUID());
        }
        
        JSONObject result = new JSONObject();
        JSONArray files = new JSONArray();
        
        Schema schema = iceberg_table.schema();
        ImmutableList.Builder<Record> builder = ImmutableList.builder();
        
        JSONArray listOfRecords = new JSONObject(records).getJSONArray("records");
        for (int index = 0; index < listOfRecords.length(); ++index) {
            JSONObject fields = listOfRecords.getJSONObject(index);
            List<Types.NestedField> columns = schema.columns();
            String[] fieldNames = JSONObject.getNames(fields);
            // Verify if input columns are the same number as the required fields
            // Optional fields shouldn't be part of the check
            if (fieldNames.length > columns.size()) 
                throw new IllegalArgumentException("Record has invalid number of fields");
            
            Record genericRecord = GenericRecord.create(schema);
            for (Types.NestedField col : columns) {
                String colName = col.name();
                Type colType = col.type();
                // Validate that a required field is present in the record
                if (!fields.has(colName)) {
                    if (col.isRequired())
                        throw new IllegalArgumentException("Record is missing a required field: " + colName);
                    else
                        continue;
                }
                
                // Trim the input value
                String value = fields.get(colName).toString().trim();
                
                // Check for null values
                if (col.isRequired() && value.equalsIgnoreCase("null"))
                    throw new IllegalArgumentException("Required field cannot be null: " + colName);
                
                // Store the value as an iceberg data type
                genericRecord.setField(colName, DataConversion.stringToIcebergType(value, colType));
            }
            builder.add(genericRecord.copy());
        }
                                            
        AwsBasicCredentials awsCreds = AwsBasicCredentials.create(
                System.getenv("AWS_ACCESS_KEY_ID"),
                System.getenv("AWS_SECRET_ACCESS_KEY"));
    
        SdkHttpClient client = ApacheHttpClient.builder()
                .maxConnections(100)
                .build();
            
        SerializableSupplier<S3Client> supplier = () -> S3Client.builder()
                .region(Region.of(System.getenv("AWS_REGION")))
                .credentialsProvider(StaticCredentialsProvider.create(awsCreds))
                .httpClient(client)
                .build();

        S3FileIO io = new S3FileIO(supplier);
        OutputFile location = io.newOutputFile(outputFile);
        System.out.println("New file created at: " + location);
                    
        FileAppender<Record> appender;
        appender = Parquet.write(location)
                        .schema(schema)
                        .createWriterFunc(GenericParquetWriter::buildWriter)
                        .build();
        appender.addAll(builder.build());
        io.close();
        appender.close();
        
        // Add file info to the JSON object
        JSONObject file = new JSONObject();
        file.put("file_path", outputFile);
        file.put("file_format", FileFormat.fromFileName(outputFile));
        file.put("file_size_in_bytes", appender.length());
        file.put("record_count", listOfRecords.length());
        files.put(file);
        
        result.put("files", files);
                
        return result.toString();
    }
    
    //helper...there is a getString(key, default) function online but not available to our version of jdk it seems
    String getJsonStringOrDefault(JSONObject o, String key, String defVal) {
    	try {
    		return o.getString(key);
    	} catch (JSONException e) {
    		return defVal;
    	}
    }

    Long getJsonLongOrDefault(JSONObject o, String key, Long defVal) {
    	try {
    		return o.getLong(key);
    	} catch (JSONException e) {
    		return defVal;
    	}
    }

    AwsBasicCredentials getSourceAwsCreds() {
        // Checking whether secondary credentials are declared. 
        // If yes, use those for the source files, other set it to the default credentials.
        
        AwsBasicCredentials awsCreds;
        String srcAccessKeyId = System.getenv("SECONDARY_AWS_ACCESS_KEY_ID");
        String srcSecretKey = System.getenv("SECONDARY_AWS_SECRET_ACCESS_KEY");
        
        if((srcAccessKeyId != null) && (srcSecretKey != null)) {
            awsCreds = AwsBasicCredentials.create(srcAccessKeyId, srcSecretKey);
        } else {
            awsCreds = AwsBasicCredentials.create(
                    System.getenv("AWS_ACCESS_KEY_ID"),
                    System.getenv("AWS_SECRET_ACCESS_KEY"));
        }

        return awsCreds;
    }

    AwsBasicCredentials getTargetAwsCreds() {
        AwsBasicCredentials awsCreds = AwsBasicCredentials.create(
                    System.getenv("AWS_ACCESS_KEY_ID"),
                    System.getenv("AWS_SECRET_ACCESS_KEY"));
        return awsCreds;
    }

    public boolean commitTable(String dataFiles) throws Exception {
        if (iceberg_table == null)
            loadTable();
        
        System.out.println("Commiting to the table " + m_tableIdentifier);
        
        PartitionSpec ps = iceberg_table.spec();

        AwsBasicCredentials awsCreds = getSourceAwsCreds();
       
        String srcRegion;
        if (System.getenv("SECONDARY_AWS_REGION") != null) {
            srcRegion = System.getenv("SECONDARY_AWS_REGION");
        } else {
            srcRegion = System.getenv("AWS_REGION");
        }
        // final String srcRegion_final = srcRegion;

        // We also need to set the source configuration accordingly,
        // for hadoop FileSystem to use it correctly.
        // The catalog and destination files (if any) should use the main credentials.
        Configuration srcConfig = m_catalog.getConf();
        srcConfig.set("fs.s3a.access.key", awsCreds.accessKeyId());
        srcConfig.set("fs.s3a.secret.key", awsCreds.secretAccessKey());

        SdkHttpClient client = ApacheHttpClient.builder()
                .maxConnections(100)
                .build();

        SerializableSupplier<S3Client> supplier = () -> S3Client.builder()
                .region(Region.of(srcRegion))
                .credentialsProvider(StaticCredentialsProvider.create(awsCreds))
                .httpClient(client)
                .build();
        
        S3FileIO io = new S3FileIO(supplier);
        
        JSONArray files = new JSONObject(dataFiles).getJSONArray("files");
        Transaction transaction = iceberg_table.newTransaction();
        AppendFiles append = transaction.newAppend();
        // Commit data files
        System.out.println("Starting Txn");
        for (int index = 0; index < files.length(); ++index) {
            JSONObject file = files.getJSONObject(index);
            // Required
            String filePath = file.getString("file_path");
            OutputFile outputFile = io.newOutputFile(filePath);
            
            // Optional (but slower if not given)
            String fileFormatStr = getJsonStringOrDefault(file, "file_format", null);
            Long fileSize = getJsonLongOrDefault(file, "file_size_in_bytes", null);
            Long numRecords = getJsonLongOrDefault(file, "record_count", null);

            if(fileFormatStr == null) {
            	// if file format is not provided, we'll try to infer from the file extension (if any)
            	String fileLocation = outputFile.location();
            	if(fileLocation.contains("."))
            		fileFormatStr = fileLocation.substring(fileLocation.lastIndexOf('.') + 1, fileLocation.length());
            	else
            		fileFormatStr = "";
            }

            FileFormat fileFormat = null;
            if(fileFormatStr.isEmpty())
            	throw new Exception("Unable to infer the file format of the file to be committed: " + outputFile.location());
            else if(fileFormatStr.toLowerCase().equals("parquet"))
            	fileFormat = FileFormat.PARQUET;
            else
            	throw new Exception("Unsupported file format " + fileFormatStr + " cannot be committed: " + outputFile.location());

            if(fileSize == null) {
            	try {
            		FileSystem fs = FileSystem.get(new URI(outputFile.location()), srcConfig);
            		FileStatus fstatus = fs.getFileStatus(new Path(outputFile.location()));
            		fileSize = fstatus.getLen();
            	} catch (Exception e) {
            		throw new Exception("Unable to infer the filesize of the file to be committed: " + outputFile.location());
            	}
            }

            if (numRecords == null) {
            	try {
                	/* The apache parquet reader code wants a type of apache.parquet.io.InputFile, however the iceberg apis have no way
                	 * to provide that object and the apache.iceberg.io.InputFile may not be passed to the parquet read functions directly.
                	 * the iceberg apis want to prevent you from reading the parquet files directly and instead push you to go through
                	 * it's own built in reader classes...but we cannot use those builtin reader classes because they require a scan of
                	 * the iceberg table...which we haven't committed these files to yet. Internally iceberg provides a ParquetIO
                	 * class which accepts an apache.iceberg.io.InputFile and implements the apache.parquet.io.InputFile interface, and this
                	 * is what is then used to read the parquet files within their tablescan code. Since this class is private, it can only be
                	 * instantiated using reflection, but we can make use of it to directly open the parquet file to collect the row count.
                	 */
            		Class<?> pifClass = Class.forName("org.apache.iceberg.parquet.ParquetIO");
            		Constructor<?> pifCstr =pifClass.getDeclaredConstructor();
            		pifCstr.setAccessible(true);
            		Object pifInst = pifCstr.newInstance();
            		Method pifMthd = pifClass.getDeclaredMethod("file", org.apache.iceberg.io.InputFile.class);
            		pifMthd.setAccessible(true);
            		org.apache.iceberg.io.InputFile pif = io.newInputFile(outputFile.location());
            		Object parquetInputFile = pifMthd.invoke(pifInst, pif);

            		ParquetFileReader reader = ParquetFileReader.open((InputFile) parquetInputFile);
            		numRecords = reader.getRecordCount();
            	} catch (Exception e) {
            		throw new Exception("Unable to infer the number of records of the file to be committed: " + outputFile.location());
            	}
            }

            DataFile data = DataFiles.builder(ps)
            		.withPath(outputFile.location())
            		.withFormat(fileFormat)
            		.withFileSizeInBytes(fileSize)
            		.withRecordCount(numRecords)
            		.build();
            
            append.appendFile(data);
        }
        append.commit();
        transaction.commitTransaction();
        io.close();
        System.out.println("Txn Complete!");
        
        return true;
    }

    DataFile getDataFile(S3FileIO io, Configuration config, String filePath, String fileFormatStr, Long fileSize, Long numRecords) throws Exception {
        PartitionSpec ps = iceberg_table.spec();
        
        OutputFile outputFile = io.newOutputFile(filePath);

        if(fileFormatStr == null) {
            // if file format is not provided, we'll try to infer from the file extension (if any)
            String fileLocation = outputFile.location();
            if(fileLocation.contains("."))
                fileFormatStr = fileLocation.substring(fileLocation.lastIndexOf('.') + 1, fileLocation.length());
            else
                fileFormatStr = "";
        }

        FileFormat fileFormat = null;
        if(fileFormatStr.isEmpty())
            throw new Exception("Unable to infer the file format of the file to be committed: " + outputFile.location());
        else if(fileFormatStr.toLowerCase().equals("parquet"))
            fileFormat = FileFormat.PARQUET;
        else
            throw new Exception("Unsupported file format " + fileFormatStr + " cannot be committed: " + outputFile.location());

        if(fileSize == null) {
            try {
                FileSystem fs = FileSystem.get(new URI(outputFile.location()), config);
                FileStatus fstatus = fs.getFileStatus(new Path(outputFile.location()));
                fileSize = fstatus.getLen();
            } catch (Exception e) {
                throw new Exception("Unable to infer the filesize of the file to be committed: " + outputFile.location());
            }
        }

        if (numRecords == null) {
            try {
                /* The apache parquet reader code wants a type of apache.parquet.io.InputFile, however the iceberg apis have no way
                    * to provide that object and the apache.iceberg.io.InputFile may not be passed to the parquet read functions directly.
                    * the iceberg apis want to prevent you from reading the parquet files directly and instead push you to go through
                    * it's own built in reader classes...but we cannot use those builtin reader classes because they require a scan of
                    * the iceberg table...which we haven't committed these files to yet. Internally iceberg provides a ParquetIO
                    * class which accepts an apache.iceberg.io.InputFile and implements the apache.parquet.io.InputFile interface, and this
                    * is what is then used to read the parquet files within their tablescan code. Since this class is private, it can only be
                    * instantiated using reflection, but we can make use of it to directly open the parquet file to collect the row count.
                    */
                Class<?> pifClass = Class.forName("org.apache.iceberg.parquet.ParquetIO");
                Constructor<?> pifCstr =pifClass.getDeclaredConstructor();
                pifCstr.setAccessible(true);
                Object pifInst = pifCstr.newInstance();
                Method pifMthd = pifClass.getDeclaredMethod("file", org.apache.iceberg.io.InputFile.class);
                pifMthd.setAccessible(true);
                org.apache.iceberg.io.InputFile pif = io.newInputFile(outputFile.location());
                Object parquetInputFile = pifMthd.invoke(pifInst, pif);

                ParquetFileReader reader = ParquetFileReader.open((InputFile) parquetInputFile);
                numRecords = reader.getRecordCount();
            } catch (Exception e) {
                throw new Exception("Unable to infer the number of records of the file to be committed: " + outputFile.location());
            }
        }

        DataFile data = DataFiles.builder(ps)
                .withPath(outputFile.location())
                .withFormat(fileFormat)
                .withFileSizeInBytes(fileSize)
                .withRecordCount(numRecords)
                .build();

        return data;
    }

    public boolean rewriteFiles(String dataFiles) throws Exception {
        if (iceberg_table == null)
            loadTable();
        
        System.out.println("Rewriting files in the table " + m_tableIdentifier);

        AwsBasicCredentials oldAwsCreds = getSourceAwsCreds();
        AwsBasicCredentials newAwsCreds = getTargetAwsCreds();
        String newRegion = System.getenv("AWS_REGION");
        String oldRegion;

        if (System.getenv("SECONDARY_AWS_REGION") != null) {
            oldRegion = System.getenv("SECONDARY_AWS_REGION");
        } else {
            oldRegion = newRegion;
        }
        // final String oldRegion_final = oldRegion;



        // We also need to set the source configuration accordingly,
        // for hadoop FileSystem to use it correctly.
        // The catalog and destination files (if any) should use the main credentials.
        Configuration oldConfig = m_catalog.getConf();
        Configuration newConfig = m_catalog.getConf();
        oldConfig.set("fs.s3a.access.key", oldAwsCreds.accessKeyId());
        oldConfig.set("fs.s3a.secret.key", oldAwsCreds.secretAccessKey());

        SdkHttpClient client = ApacheHttpClient.builder()
                .maxConnections(100)
                .build();

        SerializableSupplier<S3Client> oldSupplier = () -> S3Client.builder()
                .region(Region.of(oldRegion))
                .credentialsProvider(StaticCredentialsProvider.create(oldAwsCreds))
                .httpClient(client)
                .build();
        
        S3FileIO oldIo = new S3FileIO(oldSupplier);
        
        SerializableSupplier<S3Client> newSupplier = () -> S3Client.builder()
                .region(Region.of(newRegion))
                .credentialsProvider(StaticCredentialsProvider.create(newAwsCreds))
                .httpClient(client)
                .build();
        
        S3FileIO newIo = new S3FileIO(newSupplier);

        JSONArray oldFiles = new JSONObject(dataFiles).getJSONArray("files_to_del");
        JSONArray newFiles = new JSONObject(dataFiles).getJSONArray("files_to_add");
        
        Set<DataFile> oldDataFiles = new HashSet<DataFile>();
        Set<DataFile> newDataFiles = new HashSet<DataFile>();

        if (oldFiles.length() != newFiles.length()) {
            System.out.println("The numbers of old files and new files are different."
                                + "Aborting...");
            return false;
        }
        // To Test: If the order of files in the set matters

        
        for (int index = 0; index < newFiles.length(); ++index) {
            JSONObject newFile = newFiles.getJSONObject(index);
            JSONObject oldFile = oldFiles.getJSONObject(index);

            // Required
            String newFilePath = newFile.getString("file_path");            
            String oldFilePath = oldFile.getString("file_path");
            
            // Optional (but slower if not given)
            String newFileFormatStr = getJsonStringOrDefault(newFile, "file_format", null);
            Long newFileSize = getJsonLongOrDefault(newFile, "file_size_in_bytes", null);
            Long newNumRecords = getJsonLongOrDefault(newFile, "record_count", null);
            String oldFileFormatStr = getJsonStringOrDefault(oldFile, "file_format", null);
            Long oldFileSize = getJsonLongOrDefault(oldFile, "file_size_in_bytes", null);
            Long oldNumRecords = getJsonLongOrDefault(oldFile, "record_count", null);
            
            try {
                newDataFiles.add(getDataFile(
                    newIo,
                    newConfig,
                    newFilePath,
                    newFileFormatStr,
                    newFileSize,
                    newNumRecords));

                oldDataFiles.add(getDataFile(
                    oldIo,
                    oldConfig,
                    oldFilePath,
                    oldFileFormatStr,
                    oldFileSize,
                    oldNumRecords));
            } catch (Exception e) {
                throw new RuntimeException(e);
            } 
        }

        Transaction transaction = iceberg_table.newTransaction();
        RewriteFiles rewrite = transaction.newRewrite();
        
        // Rewrite data files
        System.out.println("Starting Txn");
        rewrite.rewriteFiles(oldDataFiles, newDataFiles);
        rewrite.commit();
        transaction.commitTransaction();
        oldIo.close();
        newIo.close();
        System.out.println("Txn Complete!");
        
        return true;
    }

    public Schema getTableSchema() {
        if (iceberg_table == null)
            loadTable();
        return m_scan.schema();
    }
    
    public String getTableType(String database, String table) throws Exception {
        setTableIdentifier(database, table);
        loadTable();
        if (iceberg_table == null) {
            return null;
        }
        return "ICEBERG";
    }
    
    @SuppressWarnings("serial")
    public class TableNotFoundException extends RuntimeException {
        public TableNotFoundException(String message) {
            super(message);
        }
    }
    
    @SuppressWarnings("serial")
    public class TableNotLoaded extends RuntimeException {
        public TableNotLoaded(String message) {
            super(message);
        }
    }
    
}
