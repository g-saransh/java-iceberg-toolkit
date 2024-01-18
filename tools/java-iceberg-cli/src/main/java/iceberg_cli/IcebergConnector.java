/**
 * (c) Copyright IBM Corp. 2022. All Rights Reserved.
 */

package iceberg_cli;


import java.util.*;
import java.util.stream.Collectors;
import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;

import org.apache.iceberg.exceptions.NoSuchNamespaceException;
import org.apache.iceberg.exceptions.NoSuchTableException;
import org.apache.iceberg.hadoop.HadoopFileIO;
import org.apache.iceberg.hadoop.HadoopInputFile;
import org.apache.iceberg.exceptions.NamespaceNotEmptyException;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.security.UserGroupInformation;
import com.google.common.collect.ImmutableList;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.iceberg.AppendFiles;
import org.apache.iceberg.CombinedScanTask;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.DataFiles;
import org.apache.iceberg.DeleteFile;
import org.apache.iceberg.DeleteFiles;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.FileMetadata;
import org.apache.iceberg.FileScanTask;
import org.apache.iceberg.HasTableOperations;
import org.apache.iceberg.HistoryEntry;
import org.apache.iceberg.ManageSnapshots;
import org.apache.iceberg.Metrics;
import org.apache.iceberg.MetricsConfig;
import org.apache.iceberg.OverwriteFiles;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.RewriteFiles;
import org.apache.iceberg.RowDelta;
import org.apache.iceberg.expressions.Literal;
import org.apache.iceberg.io.CloseableIterable;
import org.apache.iceberg.io.FileAppender;
import org.apache.iceberg.io.FileIO;
import org.apache.iceberg.io.LocationProvider;
import org.apache.iceberg.io.OutputFile;
import org.apache.iceberg.parquet.Parquet;
import org.apache.iceberg.parquet.ParquetUtil;
import org.apache.iceberg.hive.HiveCatalog;
import org.apache.iceberg.data.parquet.GenericParquetReaders;
import org.apache.iceberg.data.parquet.GenericParquetWriter;
import org.apache.iceberg.exceptions.AlreadyExistsException;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.data.GenericRecord;
import org.apache.iceberg.data.IcebergGenerics;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.SnapshotRef;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.TableScan;
import org.apache.iceberg.Transaction;
import org.apache.iceberg.UpdateSchema;
import org.apache.iceberg.UpdateProperties;
import org.apache.iceberg.aws.s3.S3FileIO;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.types.Conversions;
import org.apache.iceberg.types.Type;
import org.apache.iceberg.types.Types;
import org.apache.iceberg.util.SerializableSupplier;
import org.apache.parquet.ParquetReadOptions;
import org.apache.parquet.avro.AvroParquetReader;
import org.apache.parquet.bytes.BytesUtils;
import org.apache.parquet.column.ParquetProperties;
import org.apache.parquet.column.statistics.Statistics;
import org.apache.parquet.hadoop.Footer;
import org.apache.parquet.hadoop.ParquetFileReader;
import org.apache.parquet.hadoop.ParquetReader;
import org.apache.parquet.hadoop.metadata.ParquetMetadata;
import org.apache.parquet.io.InputFile;
import org.apache.parquet.schema.DecimalMetadata;
import org.apache.parquet.schema.LogicalTypeAnnotation;
import org.apache.parquet.schema.OriginalType;
import org.apache.parquet.schema.PrimitiveType;
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName;
import org.apache.parquet.schema.Type.Repetition;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.google.common.collect.Maps;
import com.google.common.io.Files;

import iceberg_cli.catalog.CustomCatalog;
import iceberg_cli.utils.Credentials;
import iceberg_cli.utils.DataConversion;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.CompletedPart;

public class IcebergConnector extends MetastoreConnector
{
    HiveCatalog m_catalog;
    TableIdentifier m_tableIdentifier;
    Credentials creds;
    Table iceberg_table;
    TableScan m_scan;

    public IcebergConnector(CustomCatalog catalog, String namespace, String tableName, Credentials creds) throws IOException {
        // TODO: Get type of catalog that the user wants and then initialize accordingly
        super(catalog, namespace, tableName, creds);
        
        // Initialize members
        this.creds = creds;
        initCatalog(catalog);
        if (tableName != null)
            setTableIdentifier(namespace, tableName);
    }
    
    private void initCatalog(CustomCatalog catalog) throws IOException {
        m_catalog = new HiveCatalog();
        
        // Get catalog configuration
        Configuration conf = catalog.getConf();
        // Set credentials, if any
        if (creds.isValid()) {
            conf.set("fs.s3a.access.key", creds.getValue("AWS_ACCESS_KEY_ID"));
            conf.set("fs.s3a.secret.key", creds.getValue("AWS_SECRET_ACCESS_KEY"));
            
            String endpoint = creds.getValue("ENDPOINT");
            if(endpoint != null) {
            	conf.set("fs.s3a.endpoint", endpoint);
            	// Set path style access for non-aws endpoints
            	conf.set("fs.s3a.path.style.access", "true");
            }
        }
        m_catalog.setConf(conf);
        
        // Get catalog properties
        Map <String, String> properties = catalog.getProperties();
        properties.put("list-all-tables", "true");
                
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

    /*
     * Multiple operations can be performed simultaneously with the exception of DROP, which can
     * be executed only by itself.
     *
     * The schema changes are expected in the following format:
     * ADDING and RENAMING columns at the same time
     * {
     *  "add":[
     *      {"name":"c4","type":"boolean"}
     *   ],
     *  "rename":[
     *      {"name":"c2","newName":"col2"}
     *   ]
     * }
     * DROPPING columns
     * {
     *  "drop":["c1","c2"]
     * }
     * 
     * The property changes are expected in the following format:
     * SETTING key/value property
     * {
     *  "set_property":[
     *      {"key":"p1","value":"v1"}
     *   ]
     * }
     * REMOVING property key
     * {
     *  "remove_property":["p1","p2"]
     * }
     *
     *
     * There are more ALTER operations that can be performed according to the documentation,
     * and since the argument comes as a JSON object, we can simply expand it here without any
     * changes to the protocol itself.
     */
    public boolean alterTable(String newSchema) throws Exception {
        final int OP_NONE = 0;
        final int OP_ADD = 1;
        final int OP_DROP = 2;
        final int OP_RENAME = 4;
        final int OP_SET_PROP = 8;
        final int OP_RM_PROP = 16;
        final int UPDATE_SCHEMA = OP_ADD | OP_DROP | OP_RENAME;
        final int UPDATE_PROP = OP_SET_PROP | OP_RM_PROP;
        loadTable();
        UpdateSchema updateSchema = iceberg_table.updateSchema();
        JSONObject schemaSpecs =  new JSONObject(newSchema);
        int op = OP_NONE;

        // ADD NEW COLUMNS
        try {
            JSONArray newCols = schemaSpecs.getJSONArray("add");
            for (int i = 0; i < newCols.length(); i++) {
                try {
                    JSONObject jo = newCols.getJSONObject(i);
                    String name = jo.getString("name");
                    String type = jo.getString("type");
                    updateSchema.addColumn(name, Types.fromPrimitiveString(type));
                    op |= OP_ADD;
                } catch (JSONException e) {
                    System.out.println("Invalid new column schema.");
                    return false;
                }
            }
        } catch (JSONException e) {
            // no new columns to add, move on
        }

        // DROP COLUMNS
        try {
            JSONArray dropCols = schemaSpecs.getJSONArray("drop");
            for (int i = 0; i < dropCols.length(); i++) {
                try {
                    String colName = dropCols.getString(i);
                    updateSchema.deleteColumn(colName);
                    op |= OP_DROP;
                } catch (JSONException e) {
                    System.out.println("Invalid drop column schema.");
                    return false;
                }
            }
        } catch (JSONException e) {
            // no columns to drop, move on
        }

        // RENAME COLUMNS
        try {
            JSONArray renameCols = schemaSpecs.getJSONArray("rename");
            for (int i = 0; i < renameCols.length(); i++) {
                try {
                    JSONObject jo = renameCols.getJSONObject(i);
                    String name = jo.getString("name");
                    String newName = jo.getString("newName");
                    updateSchema.renameColumn(name, newName);
                    op |= OP_RENAME;
                } catch (JSONException e) {
                    System.out.println("Invalid rename column schema.");
                    return false;
                }
            }
        } catch (JSONException e) {
            // no columns to rename, move on
        }

        // check for updates to table properties
        UpdateProperties updateProperties = iceberg_table.updateProperties();
        try {
            JSONArray setProps = schemaSpecs.getJSONArray("set_property");
            for (int i = 0; i < setProps.length(); i++) {
                try {
                    JSONObject jo = setProps.getJSONObject(i);
                    String key = jo.getString("key");
                    String value = jo.getString("value");
                    updateProperties.set(key, value);
                    op |= OP_SET_PROP;
                } catch (JSONException e) {
                    System.out.println("Invalid key/value property.");
                    return false;
                }
            }
        } catch (JSONException e) {
            // no properties to set, move on
        }

        try {
            JSONArray rmProps = schemaSpecs.getJSONArray("remove_property");
            for (int i = 0; i < rmProps.length(); i++) {
                try {
                    String key = rmProps.getString(i);
                    updateProperties.remove(key);
                    op |= OP_RM_PROP;
                } catch (JSONException e) {
                    System.out.println("Invalid property key.");
                    return false;
                }
            }
        } catch (JSONException e) {
            // no properties to remove, move on
        }
        
        // confirm DROP wasn't bundled with any other ALTERs
        if ((op & OP_DROP) == OP_DROP && op != OP_DROP) {
            System.out.println("Cannot perform DROP along with other ALTER operations.");
            return false;
        }

        // have we altered anything?
        if (op == OP_NONE) {
            System.out.println("Unrecognized ALTER operation.");
            return false;
        }

        // all good - commit changes
        if ((op & UPDATE_SCHEMA) != 0) {
            updateSchema.commit();
        }
        if ((op & UPDATE_PROP) != 0) {
            updateProperties.commit();
        }

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
        // Use specified snapshot, latest by default
        Long snapshotId = getCurrentSnapshotId();
        if (snapshotId == null)
            return new ArrayList<List<String>>();
        IcebergGenerics.ScanBuilder scanBuilder = IcebergGenerics.read(iceberg_table);
        CloseableIterable<Record> records = scanBuilder.useSnapshot(snapshotId).build();
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

    /**
     * Returns list of tasks with single data files
     */
    public Map<Integer, List<Map<String, String>>> getPlanFiles() {
        if (iceberg_table == null)
            loadTable();
        
        Iterable<FileScanTask> scanTasks = m_scan.planFiles();
        Map<Integer, List<Map<String, String>>> tasks = new HashMap<Integer, List<Map<String, String>>>();
        int index = 0;
        for (FileScanTask scanTask : scanTasks) {
            List<Map<String, String>> taskMapList = new ArrayList<Map<String, String>>();
            Map<String, String> taskMap = new HashMap<String, String>();
            DataFile file = scanTask.file();
            taskMap.put("content", file.content().toString());
            taskMap.put("file_path", file.path().toString());
            taskMap.put("file_format", file.format().toString());
            taskMap.put("start", Long.toString(scanTask.start()));
            taskMap.put("length", Long.toString(scanTask.length()));
            taskMap.put("spec", scanTask.spec().toString());
            taskMap.put("residual", scanTask.residual().toString());
            taskMapList.add(taskMap);
            
            tasks.put(index++, taskMapList);
        }
        
        return tasks;
    }
    
    /**
     * Returns list of balanced tasks which may have partial data files,
     * multiple data files or both.
     */
    public Map<Integer, List<Map<String, String>>> getPlanTasks() {
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
        return tables.stream().map(TableIdentifier::name).collect(Collectors.toList());
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
    
    public S3FileIO initS3FileIO() {
        AwsBasicCredentials awsCreds = AwsBasicCredentials.create(
                creds.getValue("AWS_ACCESS_KEY_ID"),
                creds.getValue("AWS_SECRET_ACCESS_KEY"));

        SdkHttpClient client = ApacheHttpClient.builder()
                .maxConnections(100)
                .build();
        
        SerializableSupplier<S3Client> supplier = () -> { 
            S3ClientBuilder clientBuilder = S3Client.builder()
                .region(Region.of(creds.getValue("AWS_REGION")))
                .credentialsProvider(StaticCredentialsProvider.create(awsCreds))
                .httpClient(client);
            String uri = creds.getValue("ENDPOINT");
            if (uri != null) {
                clientBuilder.endpointOverride(URI.create(uri));
            }
            return clientBuilder.build();
        };
        
        return  new S3FileIO(supplier);
    }
    
    public String writeTable(String records, String outputFile) throws Exception {
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
                throw new IllegalArgumentException("Number of fields in the record doesn't match the number of required columns in schema.\n");
            
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

        S3FileIO io = initS3FileIO();
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
    
    ParquetFileReader getParquetFileReader(FileIO io, OutputFile outputFile)  throws Exception {
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
        return reader;
    }

    static <T> Literal<T> parquetToIceberg(Type type, PrimitiveType parquetType, Object value) throws Exception {
        Class<?> pifClass = Class.forName("org.apache.iceberg.parquet.ParquetConversions");
        Constructor<?> pifCstr = pifClass.getDeclaredConstructor();
        pifCstr.setAccessible(true);
        Object pifInst = pifCstr.newInstance();
        Method pifMthd = pifClass.getDeclaredMethod("fromParquetPrimitive", Type.class, PrimitiveType.class,
                Object.class);
        pifMthd.setAccessible(true);
        Object iceberg_value = pifMthd.invoke(pifInst, type, parquetType, value);
        return (Literal<T>) iceberg_value;
    }

    ByteBuffer parquetToByteBuffer(Type type, PrimitiveType parquetType, Object value) throws Exception {
        if (value == null) {
            return null;
        }

        Literal<?> iceberg_literal = parquetToIceberg(type, parquetType, value);
        return Conversions.toByteBuffer(type, iceberg_literal.value());
    }

    Metrics parseColMetrics(Long numRecords, JSONArray colMetrics) throws Exception {
        Map<Integer, Long> columnSizes = Maps.newHashMap();
        Map<Integer, Long> valueCounts = Maps.newHashMap();
        Map<Integer, Long> nullValueCounts = Maps.newHashMap();
        Map<Integer, Long> nanValueCounts = Maps.newHashMap();
        Map<Integer, ByteBuffer> lowerBounds = Maps.newHashMap();
        Map<Integer, ByteBuffer> upperBounds = Maps.newHashMap();
        Schema schema = iceberg_table.schema();
        List<Types.NestedField> columns = schema.columns();

        for (int index = 0; index < colMetrics.length(); ++index) {
            JSONObject metric = colMetrics.getJSONObject(index);
            String colName = metric.getString("name"); // Use later to verify
            // column names to support missing columns
            Long colSize = getJsonLongOrDefault(metric, "column_size", null);
            Long valueCount = getJsonLongOrDefault(metric, "value_count", null);
            Long nullValueCount = getJsonLongOrDefault(metric, "null_value_count", null);
            String lowerBound = metric.getString("lower_bound");
            String upperBound = metric.getString("upper_bound");
            String encoding = metric.optString("encoding");

            // Type Parsing
            String colParquetType = metric.getString("parquet_type");
            String colConvertedType = metric.optString("converted_type", null);
            int colParquetTypeLength = metric.optInt("parquet_type_length");
            int colTypePrecision = metric.optInt("type_precision");
            int colTypeScale = metric.optInt("type_scale");

            PrimitiveType.PrimitiveTypeName parquetPrimitiveTypeName = PrimitiveType.PrimitiveTypeName
                    .valueOf(colParquetType);
            PrimitiveType parquetPrimitiveType = new PrimitiveType(Repetition.OPTIONAL,
                    parquetPrimitiveTypeName,
                    colParquetTypeLength,
                    "tmpType",
                    (colConvertedType != null) ? OriginalType.valueOf(colConvertedType) : null,
                    new DecimalMetadata(colTypePrecision, colTypeScale),
                    null);

            Statistics.Builder statsBuilder = Statistics.getBuilderForReading(parquetPrimitiveType);
            switch (encoding.toLowerCase()) {
                case "base64":
                    statsBuilder = statsBuilder.withMin(Base64.getDecoder().decode(lowerBound))
                            .withMax(Base64.getDecoder().decode(upperBound));
                case "":
                    break;
                default:
                    throw new Exception("Unsupported encoding: " + encoding);
            }
            Statistics<?> stats = statsBuilder.build();
            Type colIcebergType = columns.get(index).type();
            columnSizes.put(index + 1, colSize);
            valueCounts.put(index + 1, valueCount);
            nullValueCounts.put(index + 1, nullValueCount);

            try {
                ByteBuffer min = parquetToByteBuffer(colIcebergType, parquetPrimitiveType, stats.genericGetMin());
                ByteBuffer max = parquetToByteBuffer(colIcebergType, parquetPrimitiveType, stats.genericGetMax());
                if (min != null) {
                    lowerBounds.put(index + 1, min);
                }
                if (max != null) {
                    upperBounds.put(index + 1, max);
                }
            } catch (Exception e) {
                e.printStackTrace();
                throw new Exception("Error converting Parquet type to ByteBuffer for column: " + colName);
            }
        }

        return new Metrics(numRecords, columnSizes, valueCounts, nullValueCounts, nanValueCounts, lowerBounds,
                upperBounds);
    }

    DataFile getDataFile(FileIO io, String filePath, Long fileSize, Long numRecords, JSONArray colMetrics)
            throws Exception {
        PartitionSpec ps = iceberg_table.spec();
        OutputFile outputFile = io.newOutputFile(filePath);

        DataFile data = DataFiles.builder(ps)
                .withPath(outputFile.location())
                .withFileSizeInBytes(fileSize)
                .withMetrics(parseColMetrics(numRecords, colMetrics))
                .build();

        return data;
    }

    DataFile getDataFile(FileIO io, String filePath, String fileFormatStr, Long fileSize, Long numRecords)
            throws Exception {
        PartitionSpec ps = iceberg_table.spec();
        OutputFile outputFile = io.newOutputFile(filePath);

        if(fileSize == null) {
            try {
                FileSystem fs = FileSystem.get(new URI(outputFile.location()), m_catalog.getConf());
                FileStatus fstatus = fs.getFileStatus(new Path(outputFile.location()));
                fileSize = fstatus.getLen();
            } catch (Exception e) {
                throw new Exception("Unable to infer the filesize of the file to be committed: " + outputFile.location());
            }
        }

        DataFile data = DataFiles.builder(ps)
                .withPath(outputFile.location())
                .withFileSizeInBytes(fileSize)
                .withMetrics(ParquetUtil.fileMetrics(outputFile.toInputFile(), MetricsConfig.getDefault()))
                .build();

        return data;
    }

    DeleteFile getDeleteFile(FileIO io, String filePath, Long fileSize, Long numRecords, String deleteType,
            String deleteIds, JSONArray colMetrics)
            throws Exception {
        PartitionSpec ps = iceberg_table.spec();
        OutputFile outputFile = io.newOutputFile(filePath);

        if (!(deleteType.toLowerCase().equals("equality"))) {
            throw new Exception("Empty or unsupported delete type: " + deleteType);
        }
        if (deleteIds.equals("")) {
            throw new Exception("Empty delete ids provided");
        }

        String[] fieldIdsArray = deleteIds.split(", ");
        int[] equalityFieldIds = new int[fieldIdsArray.length];
        for (int i = 0; i < fieldIdsArray.length; i++) {
            equalityFieldIds[i] = Integer.parseInt(fieldIdsArray[i]);
        }
        DeleteFile delete = FileMetadata.deleteFileBuilder(ps)
                .ofEqualityDeletes(equalityFieldIds)
                .withPath(outputFile.location())
                .withFileSizeInBytes(fileSize)
                .withMetrics(parseColMetrics(numRecords, colMetrics))
                .build();

        return delete;
    }

    DeleteFile getDeleteFile(FileIO io, String filePath, String fileFormatStr, Long fileSize, Long numRecords)
            throws Exception {
        PartitionSpec ps = iceberg_table.spec();
        OutputFile outputFile = io.newOutputFile(filePath);

        if(fileSize == null) {
            try {
                FileSystem fs = FileSystem.get(new URI(outputFile.location()), m_catalog.getConf());
                FileStatus fstatus = fs.getFileStatus(new Path(outputFile.location()));
                fileSize = fstatus.getLen();
            } catch (Exception e) {
                throw new Exception("Unable to infer the filesize of the file to be committed: " + outputFile.location());
            }
        }
        
        ParquetFileReader reader = null;
        try {
            reader = getParquetFileReader(io, outputFile);
        } catch (Exception e) {
            throw new Exception("Unable to get file reader of the file to be committed: " + outputFile.location());
        }

        int[] equalityFieldIds;
        try {
            String fieldIds = reader.getFileMetaData().getKeyValueMetaData().get("delete-field-ids");
            String[] fieldIdsArray = fieldIds.split(", ");
            equalityFieldIds = new int[fieldIdsArray.length];
            for (int i = 0; i < fieldIdsArray.length; i++) {
                equalityFieldIds[i] = Integer.parseInt(fieldIdsArray[i]);
            }
        } catch (Exception e) {
            throw new Exception("Unable to get metadata of the file to be committed: " + outputFile.location());
        }

        DeleteFile delete = FileMetadata.deleteFileBuilder(ps)
                .ofEqualityDeletes(equalityFieldIds)
                .withPath(outputFile.location())
                .withFileSizeInBytes(fileSize)
                .withMetrics(ParquetUtil.fileMetrics(outputFile.toInputFile(), MetricsConfig.getDefault()))
                .build();

        return delete;
    }

    Set<DataFile> getDataFileSet(FileIO io, JSONArray files) throws Exception {
        Set<DataFile> dataFiles = new HashSet<DataFile>();

        for (int index = 0; index < files.length(); ++index) {
            JSONObject file = files.getJSONObject(index);
            // Required
            String filePath = file.getString("file_path");

            // Optional (but slower if not given)
            String fileFormatStr = getJsonStringOrDefault(file, "file_format", null);
            Long fileSize = getJsonLongOrDefault(file, "file_size_in_bytes", null);
            Long numRecords = getJsonLongOrDefault(file, "record_count", null);
            
            try {
                if (file.has("col_metrics")) {
                    dataFiles.add(getDataFile(
                            io,
                            filePath,
                            fileSize,
                            numRecords,
                            file.getJSONArray("col_metrics")));
                } else {
                    dataFiles.add(getDataFile(
                            io,
                            filePath,
                            fileFormatStr,
                            fileSize,
                            numRecords));
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            } 
        }
        return dataFiles;
    }

    Set<DeleteFile> getDeleteFileSet(FileIO io, JSONArray files) throws Exception {
        Set<DeleteFile> deleteFiles = new HashSet<DeleteFile>();

        for (int index = 0; index < files.length(); ++index) {
            JSONObject file = files.getJSONObject(index);
            // Required
            String filePath = file.getString("file_path");

            // Optional (but slower if not given)
            String fileFormatStr = getJsonStringOrDefault(file, "file_format", null);
            Long fileSize = getJsonLongOrDefault(file, "file_size_in_bytes", null);
            Long numRecords = getJsonLongOrDefault(file, "record_count", null);
            String deleteType = file.optString("delete_type");
            String deleteIds = file.optString("delete_field_ids");
            
            try {
                if (file.has("col_metrics")) {
                    deleteFiles.add(getDeleteFile(
                            io,
                            filePath,
                            fileSize,
                            numRecords,
                            deleteType,
                            deleteIds,
                            file.getJSONArray("col_metrics")));
                } else {
                    deleteFiles.add(getDeleteFile(
                            io,
                            filePath,
                            fileFormatStr,
                            fileSize,
                            numRecords));
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        return deleteFiles;
    }

    public boolean commitTable(String dataFiles) throws Exception {
        if (iceberg_table == null)
            loadTable();
        
        System.out.println("Commiting to the Iceberg table");
        
        S3FileIO io = initS3FileIO();
        
        JSONArray files = new JSONObject(dataFiles).getJSONArray("files");
        Transaction transaction = iceberg_table.newTransaction();
        AppendFiles append = transaction.newAppend();
        // Commit data files
        System.out.println("Starting Txn");
        for (int index = 0; index < files.length(); ++index) {
            JSONObject file = files.getJSONObject(index);
            // Required
            String filePath = file.getString("file_path");

            // Optional (but slower if not given)
            String fileFormatStr = getJsonStringOrDefault(file, "file_format", null);
            Long fileSize = getJsonLongOrDefault(file, "file_size_in_bytes", null);
            Long numRecords = getJsonLongOrDefault(file, "record_count", null);
            
            try {
                append.appendFile(getDataFile(
                    io,
                    filePath,
                    fileFormatStr,
                    fileSize,
                    numRecords));
            } catch (Exception e) {
                throw new RuntimeException(e);
            } 
        }
        append.commit();
        transaction.commitTransaction();
        io.close();
        System.out.println("Txn Complete!");
        
        return true;
    }

    public boolean rewriteFiles(String dataFiles) throws Exception {
        if (iceberg_table == null)
            loadTable();
        
        System.out.println("Rewriting files in the Iceberg table");
        
        S3FileIO io = initS3FileIO();

        Set<DataFile> oldDataFiles = new HashSet<DataFile>();
        Set<DataFile> newDataFiles = new HashSet<DataFile>();

        try {
            oldDataFiles = getDataFileSet(io, new JSONObject(dataFiles).getJSONArray("files_to_del"));
            newDataFiles = getDataFileSet(io, new JSONObject(dataFiles).getJSONArray("files_to_add"));
        } catch (Exception e) {
                throw new RuntimeException(e);
        } 

        Transaction transaction = iceberg_table.newTransaction();
        RewriteFiles rewrite = transaction.newRewrite();

        // Rewrite data files
        System.out.println("Starting Txn");
        for (DataFile dataFile : oldDataFiles)
            rewrite.deleteFile(dataFile);
        for (DataFile dataFile : newDataFiles)
            rewrite.addFile(dataFile);
        rewrite.commit();
        transaction.commitTransaction();
        io.close();
        System.out.println("Txn Complete!");

        return true;
    }
    
    public AppendFiles opAppend(AppendFiles append, FileIO io, JSONArray files) throws Exception {
        Set<DataFile> dataFiles = getDataFileSet(io, files);
        for (DataFile dataFile : dataFiles)
            append.appendFile(dataFile);
        return append;
    }

    public DeleteFiles opDelete(DeleteFiles delete, FileIO io, JSONArray files) throws Exception {
        for (int index = 0; index < files.length(); ++index) {
            JSONObject file = files.getJSONObject(index);
            String filePath = file.getString("file_path");
            delete.deleteFile(filePath);
        }
        return delete;
    }

    public OverwriteFiles opOverwrite(OverwriteFiles overwrite, FileIO io, JSONArray files_to_del, JSONArray files_to_add) throws Exception {
        Set<DataFile> oldDataFiles = getDataFileSet(io, files_to_del);
        Set<DataFile> newDataFiles = getDataFileSet(io, files_to_add);
        for (DataFile dataFile : oldDataFiles)
            overwrite.deleteFile(dataFile);
        for (DataFile dataFile : newDataFiles)
            overwrite.addFile(dataFile);
        return overwrite;
    }

    public RewriteFiles opRewrite(RewriteFiles rewrite, FileIO io, JSONArray files_to_del, JSONArray files_to_add) throws Exception {
        Set<DataFile> oldDataFiles = getDataFileSet(io, files_to_del);
        Set<DataFile> newDataFiles = getDataFileSet(io, files_to_add);
        for (DataFile dataFile : oldDataFiles)
            rewrite.deleteFile(dataFile);
        for (DataFile dataFile : newDataFiles)
            rewrite.addFile(dataFile);
        return rewrite;
    }

    public RowDelta opRowDelta(RowDelta rowDelta, FileIO io, JSONArray files_to_del, JSONArray files_to_add) throws Exception {
        Set<DeleteFile> deleteFiles = getDeleteFileSet(io, files_to_del);
        Set<DataFile> dataFiles = getDataFileSet(io, files_to_add);
        for (DeleteFile deleteFile : deleteFiles)
            rowDelta.addDeletes(deleteFile);
        for (DataFile dataFile : dataFiles)
            rowDelta.addRows(dataFile);
        return rowDelta;
    }

    public void upgradeTableVersion() throws Exception {
        String schema = "{\"set_property\":[{\"key\":\"format-version\",\"value\":\"2\"}]}";
        alterTable(schema);
    }

    public boolean tableTransaction(String transactionData, String tag) throws Exception {
        if (iceberg_table == null)
            loadTable();

        FileIO io;
        if (transactionData.contains("s3a://")) {
            io = initS3FileIO();
        } else {
            io = new HadoopFileIO(m_catalog.getConf());
        }
       
        System.out.println("Starting Txn");
        while (true) {
            Transaction transaction = iceberg_table.newTransaction();
            JSONArray ops = new JSONArray(transactionData);

            try {
                // We are adding tags for each snapshot of the transaction for better debugging.
                // However, we only need tags for the first and last snapshots generated by the transaction.
                // If performance of this function becomes a bottleneck, intermediate tags can be removed.
                for (int index = 0; index < ops.length(); ++index) {
                    JSONObject opData = ops.getJSONObject(index);
                    String op = opData.getString("op");
                    Long snapshotId;
                    switch(op.toLowerCase()) {
                        case "append":
                            AppendFiles append = transaction.newAppend();
                            append = opAppend(append, io, opData.getJSONArray("files_to_add"));
                            snapshotId = append.apply().snapshotId();
                            append.commit();
                            break;
                        case "delete":
                            DeleteFiles delete = transaction.newDelete();
                            delete = opDelete(delete, io, opData.getJSONArray("files_to_del"));
                            snapshotId = delete.apply().snapshotId();
                            delete.commit();
                            break;
                        case "fastappend":
                            AppendFiles fastAppend = transaction.newFastAppend();
                            fastAppend = opAppend(fastAppend, io, opData.getJSONArray("files_to_add"));
                            snapshotId = fastAppend.apply().snapshotId();
                            fastAppend.commit();
                            break;
                        case "overwrite":
                            OverwriteFiles overwrite = transaction.newOverwrite();
                            overwrite = opOverwrite(overwrite, io, opData.getJSONArray("files_to_del"), opData.getJSONArray("files_to_add"));
                            snapshotId = overwrite.apply().snapshotId();
                            overwrite.commit();
                            break;
                        case "rewrite":
                            RewriteFiles rewrite = transaction.newRewrite();
                            rewrite = opRewrite(rewrite, io, opData.getJSONArray("files_to_del"), opData.getJSONArray("files_to_add"));
                            snapshotId = rewrite.apply().snapshotId();
                            rewrite.commit();
                            break;
                        case "rowdelta":
                            RowDelta rowDelta = transaction.newRowDelta();
                            rowDelta = opRowDelta(rowDelta, io, opData.getJSONArray("files_to_del"), opData.getJSONArray("files_to_add"));
                            snapshotId = rowDelta.apply().snapshotId();
                            rowDelta.commit();
                            break;
                        default:
                            throw new Exception("Invalid Operation: " + op);
                    }
                    if (tag != null) {
                        String tagAdded = tag + "-" + index;
                        transaction.manageSnapshots().createTag(tagAdded, snapshotId).commit();
                    }
                }
                transaction.commitTransaction();
                break;
            } catch (Exception e) {
                String v1Detected = "Cannot write delete files in a v1 table";
                if (e.getMessage().contains(v1Detected)) {
                    System.out.println(v1Detected + ". Upgrading table to v2.");
                    upgradeTableVersion();
                } else {
                    throw new RuntimeException(e);
                }
            }
        }
        io.close();
        System.out.println("Txn Complete!");
        
        return true;
    }

    public boolean truncateTable(boolean overwrite) throws Exception {
        if (iceberg_table == null)
            loadTable();

        PartitionSpec ps = iceberg_table.spec();
        System.out.println("Starting truncate operation!");

        if (overwrite) {
            Schema schema = getTableSchema();
            System.out.println("Overwriting existing table!");
            return createTable(schema, ps, overwrite);
        }

        Iterable<FileScanTask> scanTasks = m_scan.planFiles();
        Transaction transaction = iceberg_table.newTransaction();
        DeleteFiles delete = transaction.newDelete();

        for (FileScanTask scanTask : scanTasks) {
            DataFile file = scanTask.file();
            delete.deleteFile(file);
        }

        delete.commit();
        transaction.commitTransaction();

        System.out.println("Truncate operation completed!");
        return true;
    }

    public boolean addTag(String tag) throws Exception {
        Long snapshotID = getCurrentSnapshotId();
        ManageSnapshots manageSnapshots = iceberg_table.manageSnapshots();
        manageSnapshots.createTag(tag, snapshotID);
        manageSnapshots.commit();
        
        System.out.println("Tag added: " + tag);
        return true;
    }

    public String getTag(String subTag, boolean all) throws Exception {
        if (iceberg_table == null)
            loadTable();

        Set<String> tags = new HashSet<String>(iceberg_table.refs().keySet());
        if (tags.isEmpty()) {
            System.out.println("Empty ref map!");
            return "null";
        }

        try {
            tags.removeIf(key -> !key.startsWith(subTag));
        } catch (Exception e) {
            System.out.println("Exception while filtering");
            return "null";
        }

        if (tags.isEmpty()) {
            System.out.println("Empty filtered tags!");
            return "null";
        }
        
        if (all) {
            return String.join(",", tags);
        } else 
            return Collections.max(tags);
    }

    public boolean rollbackToSnapshot(Long snapshotId) throws Exception {
        if (iceberg_table == null)
            loadTable();

        ManageSnapshots manageSnapshots = iceberg_table.manageSnapshots();
        manageSnapshots.rollbackTo(snapshotId);
        manageSnapshots.commit();
        System.out.println("Table rolled back to snapshotId: " + snapshotId);
        return true;
    }

    public boolean rollbackTable(String tag, boolean all, boolean force) throws Exception {
        if (iceberg_table == null)
            loadTable();

        System.out.println("Starting rollback");
        
        if (force) {
            Snapshot snapshot = iceberg_table.snapshot(tag);
            if (snapshot == null) {
                System.out.println("Tag (" + tag + ") not found in the table");
                return false;
            }
            
            return rollbackToSnapshot(snapshot.snapshotId());
        }
        
        Map<String, SnapshotRef> refs = iceberg_table.refs();
        SnapshotRef snapshotToRollback = refs.get(tag);
        if (snapshotToRollback == null) {
            System.out.println("Tag (" + tag + ") not found in the table");
            return false;
        }
        
        Long snapshotIdToRollback = snapshotToRollback.snapshotId();        
        List<Long> histSnapshots;
        List<Long> postSnapshots;
        Long targetSnapshot;
        Map<Long, String> snapToTag;
        int idx;

        try {
            histSnapshots =
                iceberg_table.history().stream()
                    .map(HistoryEntry::snapshotId)
                    .collect(Collectors.toList());
            
            if (histSnapshots.isEmpty()) {
                System.out.println("Trying rollback on a table with no history");
                return false;
            }

            snapToTag =
                refs.entrySet().stream()
                    .filter(e -> e.getValue().isTag())
                    .collect(Collectors.toMap(entry -> entry.getValue().snapshotId(), entry -> entry.getKey()));

            // Snapshots are ordered by commit time in iceberg_table.history(), with the latest snapshot being the last.
            idx = histSnapshots.lastIndexOf(snapshotIdToRollback);
            postSnapshots = new ArrayList<>(histSnapshots.subList((idx + 1), histSnapshots.size()));
            
            // Now only need tagged snapshots
            histSnapshots.removeIf(entry -> !snapToTag.containsKey(entry));
            idx = histSnapshots.indexOf(snapshotIdToRollback);
            if (idx == 0) {
                System.out.println("Provided tag corresponds to the first tagged commit. Cannot rollback, exiting!");
                return false;
            }
            targetSnapshot = histSnapshots.get(idx - 1);
            histSnapshots.removeIf(snapshotIdToRollback::equals);
        } catch (Exception e) {
            e.printStackTrace();
            throw new Exception("Exception while processing snapshots: " + e.getMessage());
        }

        ManageSnapshots ms;
        ms = iceberg_table.manageSnapshots().rollbackTo(targetSnapshot).removeTag(tag);

        if (all) {
            // Untag postSnapshots
            List<Long> snapsToUntag = histSnapshots.subList(idx, histSnapshots.size());
            for (Long snap : snapsToUntag)
                ms.removeTag(snapToTag.get(snap));
            ms.commit();
        } else {
            ms.commit();
            // Need to apply succeeding transactions again
            ManageSnapshots ms_ = iceberg_table.manageSnapshots();
            for (int i = 0; i < postSnapshots.size();) {
                Long postSnapshot = postSnapshots.get(i);
                ms_.cherrypick(postSnapshot);
                if (histSnapshots.contains(postSnapshot)) {
                    ms_.commit();
                    iceberg_table.refresh();
                    ms_ = iceberg_table.manageSnapshots().replaceTag(snapToTag.get(postSnapshot), getCurrentSnapshotId());
                }
                i = postSnapshots.lastIndexOf(postSnapshot) + 1;
            }
            ms_.commit();
        }

        System.out.println("Rollback Complete!");
        return true;
    }

    public Schema getTableSchema() {
        if (iceberg_table == null)
            loadTable();
        return m_scan.schema();
    }
    
    public String getTableType() throws Exception {
        if (iceberg_table == null) {
            loadTable();
        }
        
        return "ICEBERG";
    }
    
    public String getTableType(String database, String table) throws Exception {
        loadTable(TableIdentifier.of(database, table));
        // No exception would be thrown if the table loaded successfully
        return "ICEBERG";
    }
    
    @SuppressWarnings("serial")
    public class TableNotFoundException extends RuntimeException {
        public TableNotFoundException(String message) {
            super(message);
        }
    }
    
    public class TableNotLoaded extends RuntimeException {
        public TableNotLoaded(String message) {
            super(message);
        }
    }
}
