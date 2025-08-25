/**
 * H2GIS is a library that brings spatial support to the H2 Database Engine
 * <a href="http://www.h2database.com">http://www.h2database.com</a>. H2GIS is developed by CNRS
 * <a href="http://www.cnrs.fr/">http://www.cnrs.fr/</a>.
 *
 * This code is part of the H2GIS project. H2GIS is free software; 
 * you can redistribute it and/or modify it under the terms of the GNU
 * Lesser General Public License as published by the Free Software Foundation;
 * version 3.0 of the License.
 *
 * H2GIS is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License
 * for more details <http://www.gnu.org/licenses/>.
 *
 *
 * For more information, please consult: <a href="http://www.h2gis.org/">http://www.h2gis.org/</a>
 * or contact directly: info_at_h2gis.org
 */

package org.h2gis.functions.io.osm;

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.h2.api.ErrorCode;
import org.h2gis.api.EmptyProgressVisitor;
import org.h2gis.api.ProgressVisitor;
import org.h2gis.utilities.JDBCUtilities;
import org.h2gis.utilities.TableLocation;
import org.h2gis.utilities.TableUtilities;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;
import org.xml.sax.helpers.XMLReaderFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import org.h2gis.utilities.dbtypes.DBTypes;
import org.h2gis.utilities.dbtypes.DBUtils;

/**
 * Parse an OSM file and store the elements into a database. The database model
 * contains 10 tables.
 *
 *
 * @author Erwan Bocher
 */
public class OSMParser extends DefaultHandler {


    private final File fileName;
    private final String encoding;
    private final boolean deleteTable;
    // Set the same batch size as OSMOSIS
    private static final int BATCH_SIZE = 8000;
    private PreparedStatement nodePreparedStmt;
    private PreparedStatement nodeTagPreparedStmt;
    private PreparedStatement wayPreparedStmt;
    private PreparedStatement wayTagPreparedStmt;
    private PreparedStatement relationPreparedStmt;
    private PreparedStatement relationTagPreparedStmt;
    private PreparedStatement nodeMemberPreparedStmt;
    private PreparedStatement wayMemberPreparedStmt;
    private PreparedStatement relationMemberPreparedStmt;
    private PreparedStatement wayNodePreparedStmt;
    private int nodePreparedStmtBatchSize = 0;
    private int nodeTagPreparedStmtBatchSize = 0;
    private int wayPreparedStmtBatchSize = 0;
    private int wayTagPreparedStmtBatchSize = 0;
    private int relationPreparedStmtBatchSize = 0;
    private int relationTagPreparedStmtBatchSize = 0;
    private int nodeMemberPreparedStmtBatchSize = 0;
    private int wayMemberPreparedStmtBatchSize = 0;
    private int relationMemberPreparedStmtBatchSize = 0;
    private int wayNodePreparedStmtBatchSize = 0;
    private Set<String> insertedTagsKeys = new HashSet<String>();
    private int idMemberOrder = 1;
    private TAG_LOCATION tagLocation;
    private final GeometryFactory gf = new GeometryFactory(new PrecisionModel(), 4326);
    private NodeOSMElement nodeOSMElement;
    private WayOSMElement wayOSMElement;
    private OSMElement relationOSMElement;
    private ProgressVisitor progress = new EmptyProgressVisitor();
    private SeekableByteChannel channel;
    private long fileSize = 0;
    private long readFileSizeEachNode = 1;
    private long nodeCountProgress = 0;
    // For progression information return
    private static final int AVERAGE_NODE_SIZE = 500;
    private static String TAG_DUPLICATE_EXCEPTION = String.valueOf(ErrorCode.DUPLICATE_KEY_1);
    private Connection connection;

    public OSMParser(Connection connection, File fileName, String encoding, boolean deleteTable) {
        this.connection=connection;
        this.fileName=fileName;
        this.encoding=encoding;
        this.deleteTable=deleteTable;
    }

    /**
     * Read the OSM file and create its corresponding tables.
     *
     * @param tableName table name
     * @param progress Progress visitor following the execution.
     * @return list of table names created
     */
    public String[] read(String tableName, ProgressVisitor progress) throws SQLException {
        if(fileName == null || !(fileName.getName().endsWith(".osm") || fileName.getName().endsWith("osm.gz") || fileName.getName().endsWith("osm.bz2"))) {
            throw new SQLException(new IllegalArgumentException("This driver handle only .osm, .osm.gz and .osm.bz2 files"));
        }
        this.progress = progress.subProcess(100);
        // Initialisation
        final DBTypes dbType = DBUtils.getDBType(connection);
        TableLocation requestedTable = TableLocation.parse(tableName, dbType);
        String osmTableName = requestedTable.toString();
        if(deleteTable){
            OSMTablesFactory.dropOSMTables(connection, requestedTable.toString());
        }
        checkOSMTables(connection, dbType, requestedTable, osmTableName);
        connection.setAutoCommit(false);
        String[] outputOSMTableNames = createOSMDatabaseModel(connection, dbType, requestedTable, osmTableName);


        FileInputStream fs = null;
        try {
            fs = new FileInputStream(fileName);
            this.channel = fs.getChannel();
            this.fileSize = channel.size();
            if (fileSize > 0) {
                // Given the file size and an average node file size.
                // Skip how many nodes in order to update progression at a step of 1%
                readFileSizeEachNode = Math.max(1, (this.fileSize / AVERAGE_NODE_SIZE) / 100);
                nodeCountProgress = 0;
                XMLReader parser = XMLReaderFactory.createXMLReader();
                parser.setErrorHandler(this);
                parser.setContentHandler(this);
                if (fileName.getName().endsWith(".osm")) {
                    InputSource is = new InputSource(fs);
                    if(encoding!=null && !encoding.isEmpty()){
                        is.setEncoding(encoding);
                    }
                    parser.parse(is);
                    return outputOSMTableNames;
                } else if (fileName.getName().endsWith(".osm.gz")) {
                    InputSource is = new InputSource(new GZIPInputStream(fs));
                    if(encoding!=null && !encoding.isEmpty()){
                        is.setEncoding(encoding);
                    }
                    parser.parse(is);
                    return outputOSMTableNames;
                } else if (fileName.getName().endsWith(".osm.bz2")) {
                    InputSource is = new InputSource(new BZip2CompressorInputStream(fs));
                    if(encoding!=null && !encoding.isEmpty()){
                        is.setEncoding(encoding);
                    }
                    parser.parse(is);
                    return outputOSMTableNames;
                } else {
                    throw new SQLException("Supported formats are .osm, .osm.gz, .osm.bz2");
                }
            }
        } catch (SAXException ex) {
            throw new SQLException(ex);
        } catch (IOException ex) {
            throw new SQLException("Cannot parse the file " + fileName.getAbsolutePath(), ex);
        } finally {
            try {
                if (fs != null) {
                    fs.close();
                }
            } catch (IOException ex) {
                throw new SQLException("Cannot close the file " + fileName.getAbsolutePath(), ex);
            }
            // When the reading ends, close() method has to be called
            if (nodePreparedStmt != null) {
                nodePreparedStmt.close();
            }
            if (nodeTagPreparedStmt != null) {
                nodeTagPreparedStmt.close();
            }
            if (wayPreparedStmt != null) {
                wayPreparedStmt.close();
            }
            if (wayTagPreparedStmt != null) {
                wayTagPreparedStmt.close();
            }
            if (wayNodePreparedStmt != null) {
                wayNodePreparedStmt.close();
            }
            if (relationPreparedStmt != null) {
                relationPreparedStmt.close();
            }
            if (relationTagPreparedStmt != null) {
                relationTagPreparedStmt.close();
            }
            if (nodeMemberPreparedStmt != null) {
                nodeMemberPreparedStmt.close();
            }
            if (wayMemberPreparedStmt != null) {
                wayMemberPreparedStmt.close();
            }
            if (relationMemberPreparedStmt != null) {
                relationMemberPreparedStmt.close();
            }
            connection.setAutoCommit(true);
        }
        return null;
    }

    /**
     * Check if one table already exists
     *
     * @param connection database connection
     * @param dbType Database type.
     * @param requestedTable input table name
     * @param osmTableName prefixed table name
     */
    private void checkOSMTables(Connection connection, DBTypes dbType, TableLocation requestedTable, String osmTableName) throws SQLException {
        String[] omsTables = new String[]{OSMTablesFactory.NODE, OSMTablesFactory.NODE_TAG, OSMTablesFactory.WAY, OSMTablesFactory.WAY_NODE, 
            OSMTablesFactory.WAY_TAG, OSMTablesFactory.RELATION, OSMTablesFactory.RELATION_TAG, OSMTablesFactory.NODE_MEMBER, OSMTablesFactory.WAY_MEMBER, OSMTablesFactory.RELATION_MEMBER};
        for (String omsTableSuffix : omsTables) {
            String osmTable = TableUtilities.caseIdentifier(requestedTable, osmTableName + omsTableSuffix, dbType);
            if (JDBCUtilities.tableExists(connection, TableLocation.parse(osmTable, dbType))) {
                throw new SQLException("The table " + osmTable + " already exists.");
            }
        }
    }    
     

    

    /**
     * Create the OMS data model to store the content of the file
     *
     * @param connection database
     * @param dbType Database type.
     * @param requestedTable table saved
     * @param osmTableName osm table
     */
    private String[] createOSMDatabaseModel(Connection connection, DBTypes dbType, TableLocation requestedTable, String osmTableName) throws SQLException {
        String nodeTableName = TableUtilities.caseIdentifier(requestedTable, osmTableName + OSMTablesFactory.NODE, dbType);
        nodePreparedStmt = OSMTablesFactory.createNodeTable(connection, nodeTableName);
        String nodeTagTableName = TableUtilities.caseIdentifier(requestedTable, osmTableName + OSMTablesFactory.NODE_TAG, dbType);
        nodeTagPreparedStmt = OSMTablesFactory.createNodeTagTable(connection, nodeTagTableName);
        String wayTableName = TableUtilities.caseIdentifier(requestedTable, osmTableName + OSMTablesFactory.WAY, dbType);
        wayPreparedStmt = OSMTablesFactory.createWayTable(connection, wayTableName);
        String wayTagTableName = TableUtilities.caseIdentifier(requestedTable, osmTableName + OSMTablesFactory.WAY_TAG, dbType);
        wayTagPreparedStmt = OSMTablesFactory.createWayTagTable(connection, wayTagTableName);
        String wayNodeTableName = TableUtilities.caseIdentifier(requestedTable, osmTableName + OSMTablesFactory.WAY_NODE, dbType);
        wayNodePreparedStmt = OSMTablesFactory.createWayNodeTable(connection, wayNodeTableName);
        String relationTableName = TableUtilities.caseIdentifier(requestedTable, osmTableName + OSMTablesFactory.RELATION, dbType);
        relationPreparedStmt = OSMTablesFactory.createRelationTable(connection, relationTableName);
        String relationTagTableName = TableUtilities.caseIdentifier(requestedTable, osmTableName + OSMTablesFactory.RELATION_TAG, dbType);
        relationTagPreparedStmt = OSMTablesFactory.createRelationTagTable(connection, relationTagTableName);
        String nodeMemberTableName = TableUtilities.caseIdentifier(requestedTable, osmTableName + OSMTablesFactory.NODE_MEMBER, dbType);
        nodeMemberPreparedStmt = OSMTablesFactory.createNodeMemberTable(connection, nodeMemberTableName);
        String wayMemberTableName = TableUtilities.caseIdentifier(requestedTable, osmTableName + OSMTablesFactory.WAY_MEMBER, dbType);
        wayMemberPreparedStmt = OSMTablesFactory.createWayMemberTable(connection, wayMemberTableName);
        String relationMemberTableName = TableUtilities.caseIdentifier(requestedTable, osmTableName + OSMTablesFactory.RELATION_MEMBER, dbType);
        relationMemberPreparedStmt = OSMTablesFactory.createRelationMemberTable(connection, relationMemberTableName);
        return new String[]{nodeTableName,nodeTagTableName,wayTableName,wayTagTableName, wayNodeTableName, relationTableName, relationTagTableName,
        nodeMemberTableName, wayMemberTableName, relationMemberTableName};
    }

    @Override
    public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException {
        String type = attributes.getValue("type");
        if(progress.isCanceled()) {
            throw new SAXException("Canceled by user");
        }
        if (localName.compareToIgnoreCase("node") == 0) {
            nodeOSMElement = new NodeOSMElement(Double.valueOf(attributes.getValue("lat")),Double.valueOf(attributes.getValue("lon")));
            setCommonsAttributes(nodeOSMElement, attributes);
            tagLocation = TAG_LOCATION.NODE;
        } else if (localName.compareToIgnoreCase("way") == 0) {
            wayOSMElement = new WayOSMElement();
            setCommonsAttributes(wayOSMElement, attributes);
            tagLocation = TAG_LOCATION.WAY;
        } else if (localName.compareToIgnoreCase("tag") == 0) {
            String key = attributes.getValue("k");
            String value = attributes.getValue("v");
            boolean insertTag = true;
            switch (tagLocation) {
                case NODE:
                    insertTag = nodeOSMElement.addTag(key, value);
                    break;
                case WAY:
                    insertTag = wayOSMElement.addTag(key, value);
                    break;
                case RELATION:
                    insertTag = relationOSMElement.addTag(key, value);
                    break;
            }
        } else if (localName.compareToIgnoreCase("nd") == 0) {
            wayOSMElement.addRef(attributes.getValue("ref"));
        } else if (localName.compareToIgnoreCase("relation") == 0) {
            relationOSMElement = new OSMElement();
            setCommonsAttributes(relationOSMElement, attributes);
            tagLocation = TAG_LOCATION.RELATION;
        } else if (localName.compareToIgnoreCase("member") == 0) {
            if (type.equalsIgnoreCase("node")) {
                try {
                    nodeMemberPreparedStmt.setObject(1, relationOSMElement.getID());
                    nodeMemberPreparedStmt.setObject(2, Long.valueOf(attributes.getValue("ref")));
                    nodeMemberPreparedStmt.setObject(3, attributes.getValue("role"));
                    nodeMemberPreparedStmt.setObject(4, idMemberOrder);
                    nodeMemberPreparedStmt.addBatch();
                    nodeMemberPreparedStmtBatchSize++;
                } catch (SQLException ex) {
                    throw new SAXException("Cannot insert the node member for the relation :  " + relationOSMElement.getID(), ex);
                }
            } else if (type.equalsIgnoreCase("way")) {
                try {
                    wayMemberPreparedStmt.setObject(1, relationOSMElement.getID());
                    wayMemberPreparedStmt.setObject(2, Long.valueOf(attributes.getValue("ref")));
                    wayMemberPreparedStmt.setObject(3, attributes.getValue("role"));
                    wayMemberPreparedStmt.setObject(4, idMemberOrder);
                    wayMemberPreparedStmt.addBatch();
                    wayMemberPreparedStmtBatchSize++;
                } catch (SQLException ex) {
                    throw new SAXException("Cannot insert the way member for the relation :  " + relationOSMElement.getID(), ex);
                }
            } else if (type.equalsIgnoreCase("relation")) {
                try {
                    relationMemberPreparedStmt.setObject(1, relationOSMElement.getID());
                    relationMemberPreparedStmt.setObject(2, Long.valueOf(attributes.getValue("ref")));
                    relationMemberPreparedStmt.setObject(3, attributes.getValue("role"));
                    relationMemberPreparedStmt.setObject(4, idMemberOrder);
                    relationMemberPreparedStmt.addBatch();
                    relationMemberPreparedStmtBatchSize++;
                } catch (SQLException ex) {
                    throw new SAXException("Cannot insert the relation member for the relation :  " + relationOSMElement.getID(), ex);
                }
            }
        }
    }

    @Override
    public void endDocument() throws SAXException {
        // Execute remaining batch
        try {
            nodePreparedStmtBatchSize = insertBatch(nodePreparedStmt, nodePreparedStmtBatchSize, 1);
            nodeTagPreparedStmtBatchSize = insertBatch(nodeTagPreparedStmt, nodeTagPreparedStmtBatchSize, 1);
            wayPreparedStmtBatchSize = insertBatch(wayPreparedStmt, wayPreparedStmtBatchSize, 1);
            wayTagPreparedStmtBatchSize = insertBatch(wayTagPreparedStmt, wayTagPreparedStmtBatchSize, 1);
            relationPreparedStmtBatchSize = insertBatch(relationPreparedStmt, relationPreparedStmtBatchSize, 1);
            relationTagPreparedStmtBatchSize = insertBatch(relationTagPreparedStmt, relationTagPreparedStmtBatchSize, 1);
            nodeMemberPreparedStmtBatchSize = insertBatch(nodeMemberPreparedStmt,nodeMemberPreparedStmtBatchSize, 1);
            wayMemberPreparedStmtBatchSize = insertBatch(wayMemberPreparedStmt, wayMemberPreparedStmtBatchSize, 1);
            relationMemberPreparedStmtBatchSize = insertBatch(relationMemberPreparedStmt, relationMemberPreparedStmtBatchSize, 1);
            wayNodePreparedStmtBatchSize = insertBatch(wayNodePreparedStmt, wayNodePreparedStmtBatchSize, 1);
        } catch (SQLException ex) {
            throw new SAXException("Could not insert sql batch", ex);
        }
    }

    @Override
    public void endElement(String uri, String localName, String qName) throws SAXException {
        if (localName.compareToIgnoreCase("node") == 0) {
            tagLocation = TAG_LOCATION.OTHER;
            try {
                nodePreparedStmt.setObject(1, nodeOSMElement.getID());
                nodePreparedStmt.setObject(2, nodeOSMElement.getPoint(gf));
                nodePreparedStmt.setObject(3, nodeOSMElement.getElevation());
                nodePreparedStmt.setObject(4, nodeOSMElement.getUser());
                nodePreparedStmt.setObject(5, nodeOSMElement.getUID());
                nodePreparedStmt.setObject(6, nodeOSMElement.getVisible());
                nodePreparedStmt.setObject(7, nodeOSMElement.getVersion());
                nodePreparedStmt.setObject(8, nodeOSMElement.getChangeSet());
                nodePreparedStmt.setObject(9, nodeOSMElement.getTimeStamp(), Types.DATE);
                nodePreparedStmt.setString(10, nodeOSMElement.getName());
                nodePreparedStmt.addBatch();
                nodePreparedStmtBatchSize++;
                HashMap<String, String> tags = nodeOSMElement.getTags();
                for (Map.Entry<String, String> entry : tags.entrySet()) {
                    nodeTagPreparedStmt.setObject(1, nodeOSMElement.getID());
                    nodeTagPreparedStmt.setObject(2, entry.getKey());
                    nodeTagPreparedStmt.setObject(3, entry.getValue());
                    nodeTagPreparedStmt.addBatch();
                    nodeTagPreparedStmtBatchSize++;
                }
            } catch (SQLException ex) {
                throw new SAXException("Cannot insert the node  :  " + nodeOSMElement.getID(), ex);
            }
        } else if (localName.compareToIgnoreCase("way") == 0) {
            tagLocation = TAG_LOCATION.OTHER;
            try {
                wayPreparedStmt.setObject(1, wayOSMElement.getID());
                wayPreparedStmt.setObject(2, wayOSMElement.getUser());
                wayPreparedStmt.setObject(3, wayOSMElement.getUID());
                wayPreparedStmt.setObject(4, wayOSMElement.getVisible());
                wayPreparedStmt.setObject(5, wayOSMElement.getVersion());
                wayPreparedStmt.setObject(6, wayOSMElement.getChangeSet());
                wayPreparedStmt.setTimestamp(7, wayOSMElement.getTimeStamp());
                wayPreparedStmt.setString(8, wayOSMElement.getName());
                wayPreparedStmt.addBatch();
                wayPreparedStmtBatchSize++;
                HashMap<String, String> tags = wayOSMElement.getTags();
                for (Map.Entry<String, String> entry : tags.entrySet()) {
                    wayTagPreparedStmt.setObject(1, wayOSMElement.getID());
                    wayTagPreparedStmt.setObject(2, entry.getKey());
                    wayTagPreparedStmt.setObject(3, entry.getValue());
                    wayTagPreparedStmt.addBatch();
                    wayTagPreparedStmtBatchSize++;
                }
                int order = 1;
                for (long ref :  wayOSMElement.getNodesRef()) {
                    wayNodePreparedStmt.setObject(1, wayOSMElement.getID());
                    wayNodePreparedStmt.setObject(2, ref);
                    wayNodePreparedStmt.setObject(3, order++);
                    wayNodePreparedStmt.addBatch();
                    wayNodePreparedStmtBatchSize++;
                }
            } catch (SQLException ex) {
                throw new SAXException("Cannot insert the way  :  " + wayOSMElement.getID(), ex);
            }
        } else if (localName.compareToIgnoreCase("relation") == 0) {
            tagLocation = TAG_LOCATION.OTHER;
            try {
                relationPreparedStmt.setObject(1, relationOSMElement.getID());
                relationPreparedStmt.setObject(2, relationOSMElement.getUser());
                relationPreparedStmt.setObject(3, relationOSMElement.getUID());
                relationPreparedStmt.setObject(4, relationOSMElement.getVisible());
                relationPreparedStmt.setObject(5, relationOSMElement.getVersion());
                relationPreparedStmt.setObject(6, relationOSMElement.getChangeSet());
                relationPreparedStmt.setTimestamp(7, relationOSMElement.getTimeStamp());
                relationPreparedStmt.addBatch();
                relationPreparedStmtBatchSize++;
                HashMap<String, String> tags = relationOSMElement.getTags();
                for (Map.Entry<String, String> entry : tags.entrySet()) {
                    relationTagPreparedStmt.setObject(1, relationOSMElement.getID());
                    relationTagPreparedStmt.setObject(2, entry.getKey());
                    relationTagPreparedStmt.setObject(3, entry.getValue());
                    relationTagPreparedStmt.addBatch();
                    relationTagPreparedStmtBatchSize++;
                }
                idMemberOrder = 0;
            } catch (SQLException ex) {
                throw new SAXException("Cannot insert the relation  :  " + relationOSMElement.getID(), ex);
            }
        } else if (localName.compareToIgnoreCase("member") == 0) {
            idMemberOrder++;
        }
        try {
            insertBatch();
        } catch (SQLException ex) {
            throw new SAXException("Could not insert sql batch", ex);
        }
        if(nodeCountProgress++ % readFileSizeEachNode == 0) {
            // Update Progress
            try {
                progress.setStep((int) (((double) channel.position() / fileSize) * 100));
            } catch (IOException ex) {
                // Ignore
            }
        }
    }

    private void insertBatch() throws SQLException {
        nodePreparedStmtBatchSize = insertBatch(nodePreparedStmt, nodePreparedStmtBatchSize);
        nodeTagPreparedStmtBatchSize = insertBatch(nodeTagPreparedStmt, nodeTagPreparedStmtBatchSize);
        wayPreparedStmtBatchSize = insertBatch(wayPreparedStmt, wayPreparedStmtBatchSize);
        wayTagPreparedStmtBatchSize = insertBatch(wayTagPreparedStmt, wayTagPreparedStmtBatchSize);
        relationPreparedStmtBatchSize = insertBatch(relationPreparedStmt, relationPreparedStmtBatchSize);
        relationTagPreparedStmtBatchSize = insertBatch(relationTagPreparedStmt, relationTagPreparedStmtBatchSize);
        nodeMemberPreparedStmtBatchSize = insertBatch(nodeMemberPreparedStmt,nodeMemberPreparedStmtBatchSize);
        wayMemberPreparedStmtBatchSize = insertBatch(wayMemberPreparedStmt, wayMemberPreparedStmtBatchSize);
        relationMemberPreparedStmtBatchSize = insertBatch(relationMemberPreparedStmt, relationMemberPreparedStmtBatchSize);
        wayNodePreparedStmtBatchSize = insertBatch(wayNodePreparedStmt, wayNodePreparedStmtBatchSize);
    }
    private int insertBatch(PreparedStatement st, int batchSize, int maxBatchSize) throws SQLException {
        if(batchSize >= maxBatchSize) {
            st.executeBatch();
            connection.commit();
            st.clearBatch();
            return 0;
        } else {
            return batchSize;
        }
    }

    private int insertBatch(PreparedStatement st, int batchSize) throws SQLException {
        return insertBatch(st, batchSize, BATCH_SIZE);
    }

    /**
     * Init the commons OSM attributes
     * @param osmElement {@link OSMElement}
     * @param attributes {@link Attributes}
     */
    private void setCommonsAttributes(OSMElement osmElement, Attributes attributes) throws SAXException {
        osmElement.setId(attributes.getValue("id"));
        osmElement.setUser(attributes.getValue("user"));
        osmElement.setUid(attributes.getValue("uid"));
        osmElement.setVisible(attributes.getValue("visible"));
        osmElement.setVersion(attributes.getValue("version"));
        osmElement.setChangeset(attributes.getValue("changeset"));
        osmElement.setTimestamp(attributes.getValue("timestamp"));
    }

}
