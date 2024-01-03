/**
 * (c) Copyright IBM Corp. 2022. All Rights Reserved.
 */

package iceberg_cli;

import org.apache.commons.cli.*;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.SchemaParser;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.json.JSONObject;

import iceberg_cli.cli.OptionsParser;
import iceberg_cli.cli.Parser;
import iceberg_cli.utils.AwsCredentials;
import iceberg_cli.utils.CatalogUtils;
import iceberg_cli.utils.Credentials;
import iceberg_cli.utils.PrintUtils;

public class IcebergApplication {
    private String namespace;
    private String tableName;
    private String action;
    /**
     * @param args
     * @return formatted result
     */
    public String processRequest( String[] args ) throws Exception
    {
        String output = null;
        
        Parser parser = new Parser();
        parser.parseArguments(args);
        OptionsParser optParser = parser.optParser();
        // Get options and parameters
        String uri = optParser.uri();
        String warehouse = optParser.warehouse();
        String tableFormat = optParser.tableFormat();
        String outputFormat = optParser.outputFormat();
        String snapshotId = optParser.snapshotId();
        String credentials = optParser.credentials();
        String catalog = optParser.catalog();
        // Get positional arguments
        namespace = parser.namespace();
        action = parser.command();
        tableName = parser.table();
        String schemaJsonString = parser.getPositionalArg("schema");
        
        // Validate identifier
        validateIdentifier();
        
        // Set credentials
        Credentials creds = null;
        JSONObject jsonCreds = new JSONObject();
        String type = "AWS";
        // Get credentials, if passed by user
        if (credentials != null) {
            JSONObject values = new JSONObject(credentials);
            jsonCreds = values.getJSONObject("credentials");
            type = values.getString("type");
        }
        if (type.equalsIgnoreCase("AWS"))
            creds = new AwsCredentials(jsonCreds);
                
        // Initialize HiveCatalog
        MetastoreConnector connector = CatalogUtils.getConnector(catalog, tableFormat, uri, warehouse, namespace, tableName, creds);
        
        // Set user specified snapshot ID, if any
        if (snapshotId != null)
            connector.setSnapshotId(Long.valueOf(snapshotId));
        
        PrintUtils printUtils = new PrintUtils(connector, outputFormat);
        // Perform action
        switch (action) {
        case "read":
            output = printUtils.printTable();
            break;
        case "create":
            if (tableName != null) {
                if (schemaJsonString == null)
                    throw new ParseException("Missing required argument: schema");
                Schema schema = SchemaParser.fromJson(schemaJsonString);
                boolean overwrite = parser.overwrite();
                // TODO: Get PartitionSpec from user 
                PartitionSpec spec = PartitionSpec.unpartitioned();
                output = "Operation successful? " + connector.createTable(schema, spec, overwrite);
            } else if (namespace != null) {
                // Set default warehouse if no warehouse argument passed in
                if (warehouse == null) {
                    warehouse = connector.loadNamespaceMetadata(Namespace.of("default")).get("location");
                    connector = CatalogUtils.getConnector(catalog, tableFormat, uri, warehouse, namespace, tableName, creds);
                }
                output = "Operation successful? " + connector.createNamespace(Namespace.of(namespace));
            }
            break;
        case "describe":
            if (tableName != null)
                output = printUtils.printTableDetails();
            else if (namespace != null)
                output = printUtils.printNamespaceDetails(namespace);
            break;
        case "list":
            if (namespace != null)
                output = printUtils.printTables(namespace);
            else {
                boolean fetchAll = parser.fetchAll();
                if (fetchAll)
                    output = printUtils.printAllTables();
                else
                    output = printUtils.printNamespaces();
            }
            break;
        case "location":
            output = connector.getTableLocation();
            break;
        case "files":
            output = printUtils.printFiles();
            break;
        case "metadata":
            output = printUtils.printTableMetadata();
            break;
        case "tasks":
            output = printUtils.printTasks();
            break;
        case "snapshot":
            boolean fetchAll = parser.fetchAll();
            if (fetchAll)
                output = printUtils.printSnapshots();
            else
                output = printUtils.printCurrentSnapshot();
            break;
        case "rename":
            String tableNewNameString = parser.getPositionalArg("to_identifier");
            TableIdentifier tableOrigName = TableIdentifier.of(namespace, tableName);
            TableIdentifier tableNewName = TableIdentifier.parse(tableNewNameString);
            output = "Operation successful? " + connector.renameTable(tableOrigName, tableNewName);
            break;
        case "schema":
            output = printUtils.printSchema();
            break;
        case "spec":
            output = printUtils.printSpec();
            break;
        case "type":
            output = connector.getTableType(namespace, tableName);
            break;
        case "uuid":
            output = printUtils.printUUID();
            break;
        case "write":
            String record = parser.getPositionalArg("records");
            String outputFile = parser.outputFile();
            String dataFiles = connector.writeTable(record, outputFile);
            output = "Operation successful? " + connector.commitTable(dataFiles);
            break;
        case "commit":
            String dataFile = parser.getPositionalArg("data-files");
            output = "Operation successful? " + connector.commitTable(dataFile);
            break;
        case "rewrite":
            String rwDataFiles = parser.getPositionalArg("data-files");
            output = "Operation successful? " + connector.rewriteFiles(rwDataFiles);
            break;
        case "transaction":
            String transactionData = parser.getPositionalArg("transaction-data");
            String tag = parser.getPositionalArg("tag");
            boolean txn_status = connector.tableTransaction(transactionData);
            output = "Operation successful? " + txn_status;
            if (txn_status & (tag != null))
                connector.loadTable();
                output = output + "; Tag successful? " + connector.addTag(tag);
            break;
        case "drop":
            if (tableName != null)
                output = "Operation successful? " + connector.dropTable();
            else if (namespace != null)
                output = "Operation successful? " + connector.dropNamespace(Namespace.of(namespace));
            break;
        case "alter":
            if (schemaJsonString == null)
               throw new ParseException("Missing required argument: schema");
            output = "Operation successful? " + connector.alterTable(schemaJsonString);
            break;
        case "truncate":
            boolean overwrite = parser.overwrite();
            output = "Operation successful? " + connector.truncateTable(overwrite);
            break;
        default:
            System.err.println("Error: Invalid action");
            break;
        }
        
        return output;
    }
    
    private void validateIdentifier() throws ParseException {
        switch (action) {
            case "list":
                break;
            case "describe":
            case "drop":
            case "create":
                validateNamespace(namespace);
                break;
            case "files":
            case "snapshot":
            case "read":
            case "schema":
            case "spec":
            case "uuid":
            case "rename":
            case "commit":
            case "rewrite":
            case "transaction":
            case "write":
            case "location":
            case "metadata":
            case "tasks":
            case "type":
            case "alter":
            case "truncate":
                validateNamespace(namespace);
                validateTable(tableName);
                break;
            case "default":
                throw new ParseException("Invalid command: " + action);
        }
    }
    
    private void validateNamespace(String namespace) throws ParseException {
        if (namespace == null)
            throw new ParseException("Missing namespace");
    }

    private void validateTable(String table) throws ParseException {
        if (table == null)
            throw new ParseException("Missing table name");
    }
}
