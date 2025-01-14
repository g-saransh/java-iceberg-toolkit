/**
 * (c) Copyright IBM Corp. 2022. All Rights Reserved.
 */

package iceberg_cli.utils;

import java.util.*;

import org.apache.iceberg.Schema;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.PartitionSpec;
import org.json.JSONObject;

import iceberg_cli.HiveConnector;
import iceberg_cli.IcebergConnector;
import iceberg_cli.MetastoreConnector;
import iceberg_cli.utils.output.*;

/**
 * 
 * Provides functions to output files, snapshot, schema, and table details
 * as a string, JSON, and CSV formats.
 *
 */
public class PrintUtils {
    private MetastoreConnector metaConn;
    private String format;
    private Output output;
    
    public PrintUtils(MetastoreConnector metaConn, String format) {
        this.metaConn = metaConn;
        this.format = format;
        switch (format) {
            case "json":
                output = new JsonOutput();
                break;
            case "csv":
                output = new CsvOutput();
                break;
            default:
                output = new Output();
        }
    }
    
    /**
     * Get table metadata from MetastoreConnector and return to the user is the given format
     * @throws Exception 
     */
    public String printTableMetadata() throws Exception {
        Snapshot snapshot = metaConn.getCurrentSnapshot();
        Schema targetSchema = metaConn.getTableSchema();
        String tableLocation = metaConn.getTableLocation();
        String dataLocation = metaConn.getTableDataLocation();
        String type = metaConn.getTableType();
        
        return output.tableMetadata(snapshot, targetSchema, tableLocation, dataLocation, type);
    }
    
    /**
     * Get table details from MetastoreConnector and output to the user is the given format
     * @throws Exception 
     */
    public String printTableDetails() throws Exception {
        Map<Integer, List<Map<String, String>>> planFileTasks = metaConn.getPlanFiles();
        Snapshot snapshot = metaConn.getCurrentSnapshot();
        Schema targetSchema = metaConn.getTableSchema();
        String tableLocation = metaConn.getTableLocation();
        String dataLocation = metaConn.getTableDataLocation();
        String type = metaConn.getTableType();
        
        return output.tableDetails(planFileTasks, snapshot, targetSchema, tableLocation, dataLocation, type);
    }
    
    /**
     * Get list of tables in all namespaces
     * @throws Exception 
     */
    public String printAllTables() throws Exception {
        List<Namespace> namespaces = metaConn.listNamespaces();
        Map<String, List<String>> tables = new HashMap<String, List<String>>();
        for (Namespace namespace : namespaces) {
            String namespaceName = namespace.toString();
            tables.put(namespaceName, metaConn.listTables(namespaceName));
        }
        
        return output.listAllTables(tables);
    }
    
    /**
     * Get list of tables in a namespace in the user specified format
     * @param namespace
     * @throws Exception 
     */
    public String printTables(String namespace) throws Exception {
        java.util.List<String> tables = metaConn.listTables(namespace);
        return output.listTables(tables);
    }

    /**
     * Get list of namespaces from MetastoreConnector and output to the user in the given format
     */
    public String printNamespaces() throws Exception {
        java.util.List<Namespace> namespaces = metaConn.listNamespaces();
        ArrayList <String> namespace_location = new ArrayList<String>();
        for (Namespace nmspc : namespaces) {
            String location = metaConn.loadNamespaceMetadata(nmspc).get("location");
            String nmspc_str = nmspc.toString();
            namespace_location.add(nmspc_str + ": " + location);
        }
        return output.listNamespaces(namespace_location);
    }
    
    /**
     * Get details of namespaces from MetastoreConnector and output to the user in the given format
     * @param namespace
     */
    public String printNamespaceDetails(String namespace) throws Exception {
        java.util.Map<java.lang.String,java.lang.String> details_nmspc = metaConn.loadNamespaceMetadata(Namespace.of(namespace));
        return output.namespaceDetails(details_nmspc);
    }
    
    /**
     * Get the table spec from MetastoreConnector and output to the user in the given format
     */
    public String printSpec() throws Exception {
        PartitionSpec spec = metaConn.getSpec();
        return spec.toString();
    }
    
    /**
     * Get the table UUID from MetastoreConnector and output to the user in the given format
     */
    public String printUUID() throws Exception {
        return metaConn.getUUID();
    }
    
    /**
     * Get all table files from MetastoreConnector and output to the user is the given format
     * @throws Exception 
     */
    public String printFiles() throws Exception {
        String outputString = null;
        
        String planFiles = output.tableFiles(metaConn.getPlanFiles());
        Long snapshotId = metaConn.getCurrentSnapshotId();
        switch (format) {
            case "json":
                JSONObject filesAsJson = new JSONObject(planFiles);
                filesAsJson.put("snaphotId", snapshotId);
                outputString = filesAsJson.toString();
                break;
            default:
                StringBuilder builder = new StringBuilder(String.format("SNAPSHOT ID : %d\n", snapshotId));
                builder.append(planFiles);
                outputString = builder.toString();
        }
        return outputString;
    }
    
    /**
     * Get all table files from MetastoreConnector and output to the user is the given format
     * @throws Exception 
     */
    public String printTasks() throws Exception {
        String outputString = null;
        
        String planFiles = output.tableFiles(metaConn.getPlanTasks());
        Long snapshotId = metaConn.getCurrentSnapshotId();
        switch (format) {
            case "json":
                JSONObject filesAsJson = new JSONObject(planFiles);
                filesAsJson.put("snaphotId", snapshotId);
                outputString = filesAsJson.toString();
                break;
            default:
                StringBuilder builder = new StringBuilder(String.format("SNAPSHOT ID : %d\n", snapshotId));
                builder.append(planFiles);
                outputString = builder.toString();
        }
        return outputString;
    }
    
    /**
     * Get all snapshots for a table from MetastoreConnector and output to the user is the given format
     */
    public String printSnapshots() throws Exception {
        java.lang.Iterable<Snapshot> snapshots = metaConn.getListOfSnapshots();
        return output.allSnapshots(snapshots);
    }
    
    /**
     * Get default snapshot for a table from MetastoreConnector and output to the user is the given format
     */
    public String printCurrentSnapshot() throws Exception {
        Snapshot currentSnapshot = metaConn.getCurrentSnapshot();
        return output.currentSnapshot(currentSnapshot);
    }
    
    /**
     * Get table schema from MetastoreConnector and output to the user is the given format
     * @throws Exception
     */
    public String printSchema() throws Exception {
        String outputString = null;
        
        String schema = output.tableSchema(metaConn.getTableSchema());
        Long snapshotId = metaConn.getCurrentSnapshotId();
        switch (format) {
            case "json":
                JSONObject schemaAsJson = new JSONObject(schema);
                schemaAsJson.put("snaphotId", snapshotId);
                outputString = schemaAsJson.toString();
                break;
            default:
                StringBuilder builder = new StringBuilder(String.format("SNAPSHOT ID : %d\n", snapshotId));
                builder.append("SCHEMA\n");
                builder.append(schema);
                outputString = builder.toString();
        }
        return outputString;
    }
    
    /**
     * Get table records from MetastoreConnector and output to the user is the given format
     * @throws Exception 
     */
    public String printTable() throws Exception {
        List<List<String>> records = metaConn.readTable();
        return output.tableRecords(records);
    }
}
