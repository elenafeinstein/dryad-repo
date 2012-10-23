package org.dspace.dataonemn;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.sql.SQLException;
import java.text.ParseException;
import java.util.Date;
import java.util.Enumeration;

import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import nu.xom.Document;
import nu.xom.Serializer;

import org.apache.solr.client.solrj.SolrServerException;
import org.dspace.core.ConfigurationManager;
import org.dspace.core.Context;
import org.dspace.content.Bitstream;
import org.dspace.content.Bundle;
import org.dspace.content.Collection;
import org.dspace.content.DCValue;
import org.dspace.content.Item;
import org.dspace.eperson.EPerson;

import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;

import com.ibm.icu.text.DateFormat;

import org.apache.log4j.Level;
import org.apache.log4j.Logger;

import org.apache.commons.lang.StringEscapeUtils;

/**
 * This class accepts an HTTP request and passes off to the appropriate location
 * to perform an action. It is very lightweight, just for testing some initial
 * setup. It will eventually be merged into other code.
 * 
 * @author Ryan Scherle
 * @author Kevin S. Clarke
 **/
public class DataOneMN extends HttpServlet implements Constants {
    
    private static final long serialVersionUID = -3545762362447908735L;    
    private static final Logger log = Logger.getLogger(DataOneMN.class);
    private static final String XML_CONTENT_TYPE = "application/xml; charset=UTF-8";
    private static final String TEXT_XML_CONTENT_TYPE = "text/xml; charset=UTF-8";
    private static final int DATA_FILE_COLLECTION = 1;
    
    
    private String myData;
    
    private String mySolr;
    
    private DataOneLogger myRequestLogger;
    

    //////////////////////////////////////////////////////////////////////////////////////////////////////////
    /**
       Opens a DSpace context, and cleanly recovers if there is a problem opening it.
    **/
    private Context getContext() throws ServletException {
	Context ctxt = null;
	
	try {
	    ctxt = new Context();
	    
	    log.debug("DSpace context initialized");
	    return ctxt;
	}
	catch (SQLException e) {
	    log.error("Unable to initialize DSpace context", e);
	    
	    try {
		if (ctxt != null) {
		    ctxt.complete();
		}
	    }
	    catch (SQLException e2) {
		log.warn("unable to close context cleanly;" + e2.getMessage(), e2);
	    }
	    
	    throw new ServletException("Unable to initialize DSpace context", e);
	}
    }

    
    //////////////////////////////////////////////////////////////////////////////////////////////////////////
    private void closeContext(Context ctxt) throws ServletException {
	log.debug("closing context " + ctxt);
	try {
	    if (ctxt != null && ctxt.getDBConnection() != null) {   //if the connection is null how can we commit?
		ctxt.complete();
	    }
	    log.debug("DSpace context closed.");
	} catch (SQLException e) {
	    log.warn("unable to close context cleanly;" + e.getMessage(), e);
	}
    }
    
    //////////////////////////////////////////////////////////////////////////////////////////////////////////
    /**
     * Receives the HEAD HTTP call and passes off to the appropriate method.
     **/
    protected void doHead(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
	log.debug("starting doHead with request " + request.getPathInfo());

	String reqPath = buildReqPath(request.getPathInfo());
	log.debug("pathinfo=" + reqPath);

	Context ctxt = getContext();
	
	if (reqPath.startsWith("/object/")) {
	    ObjectManager objManager = new ObjectManager(ctxt, myData, mySolr);
	    describe(reqPath, response, objManager);
	} else {
	    response.sendError(HttpServletResponse.SC_NOT_IMPLEMENTED);
	}
	
	closeContext(ctxt);
    }
    
    //////////////////////////////////////////////////////////////////////////////////////////////////////////
    /**
     * Receives the HEAD POST call and passes off to the appropriate method.
     */
    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
	throws ServletException, IOException {
	log.debug("starting doPost with request " + request.getPathInfo());

	
	String reqPath = buildReqPath(request.getPathInfo());

	if (reqPath.startsWith("/error")) {
	    synchronizationFailed(request, response);
	} else {   
	    response.sendError(HttpServletResponse.SC_NOT_IMPLEMENTED);
	}
    }
    
    //////////////////////////////////////////////////////////////////////////////////////////////////////////
    /**
     * We don't implement this yet.
     */
    @Override
    protected void doPut(HttpServletRequest request, HttpServletResponse response)
	throws ServletException, IOException {
	log.debug("starting doPut with request " + request.getPathInfo());

	response.sendError(HttpServletResponse.SC_NOT_IMPLEMENTED);
    }
    
    //////////////////////////////////////////////////////////////////////////////////////////////////////////
    /**
     * Cleans up the URL request path.
     **/
    private String buildReqPath(String aPath) {
	String reqPath = aPath;
	
	// handle (and remove) the version indicator
	// TODO: throw an error for requests that do not have a version indicator -- need to notify potential users first
	if(reqPath.startsWith("/v1")) {
	    log.debug("version 1 detected, removing");
	    reqPath = reqPath.substring("/v1".length());
	}
	
	// remove any trailing slash, but not if the entire path is a slash
	if(reqPath.endsWith("/") && reqPath.length() > 1) {
	    reqPath = reqPath.substring(0, reqPath.length() - 1);
	}
	
	return reqPath;
    }
    
    //////////////////////////////////////////////////////////////////////////////////////////////////////////
    /**
     * Receives the GET HTTP call and passes off to the appropriate method.
     **/
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
	log.debug("starting doGet with request " + request.getPathInfo());  //why is this not printing ?count parameters...
	
	String reqPath = buildReqPath(request.getPathInfo());
       	log.debug("reqPath=" + reqPath);
	
	Context ctxt = null;
	
	LogEntry le = new LogEntry();
	final String requestIP = request.getHeader("x-forwarded-for");
	//final String requestHost = request.getRemoteHost();
	final String requestUser = request.getRemoteUser();
	le.setIPAddress(requestIP);
	le.setUserAgent(request.getHeader("user-agent"));
	StringBuilder subjectBuff = new StringBuilder();
	if (requestUser != null){
	    subjectBuff.append("CN=testcn, ");
	}
	subjectBuff.append("DC=dataone, DC=org");
	le.setSubject(subjectBuff.toString());
	le.setNodeIdentifier(DRYAD_NODE_IDENTIFIER);
	//code for setting the log time moved to the LogEntry class, since the format needs to work with solr
	
	try {
	    ctxt = getContext();
	    ObjectManager objManager = new ObjectManager(ctxt, myData, mySolr);     
		    
	    if (reqPath.startsWith("/monitor/ping")) {
		ping(response, objManager);
	    } else if (reqPath.startsWith("/log")) {
		getLogRecords(request, response);
	    } else if(reqPath.equals("") || reqPath.equals("/") || reqPath.equals("/node")) {
		getCapabilities(response);
	    } else if(reqPath.startsWith("/object")) {			
		if (reqPath.equals("/object")) {
		    listObjects(request, response, objManager);
		}
		else if (reqPath.startsWith("/object/")) {
		    getObject(reqPath, response, objManager, le);
		    String objid = reqPath.substring("/object/".length());
		    log.info("logging request for object id= " + objid);
		    le.setEvent(DataOneLogger.EVENT_READ);
		    myRequestLogger.log(le);
		}
		else {
		    response.sendError(HttpServletResponse.SC_NOT_FOUND,
				       "Did you mean '/object' or '/object/doi:...'");
		}		
	    } else if (reqPath.startsWith("/meta/")) {
		getSystemMetadata(reqPath, response, objManager);
	    } else if (reqPath.startsWith("/checksum/")) {
		getChecksum(reqPath, response, objManager);
	    } else if (reqPath.startsWith("/isAuthorized/")) {
		response.sendError(HttpServletResponse.SC_NOT_IMPLEMENTED);
	    } else if (reqPath.startsWith("/accessRules/")) {
		response.sendError(HttpServletResponse.SC_NOT_IMPLEMENTED);
	    } else if (reqPath.startsWith("/error")) {
		response.sendError(HttpServletResponse.SC_NOT_IMPLEMENTED);
	    } else if (reqPath.startsWith("/monitor/object")) {
		response.sendError(HttpServletResponse.SC_NOT_IMPLEMENTED);
	    } else if (reqPath.startsWith("/monitor/event")) {
		response.sendError(HttpServletResponse.SC_NOT_IMPLEMENTED);
	    } else if (reqPath.startsWith("/monitor/status")) {
		response.sendError(HttpServletResponse.SC_NOT_IMPLEMENTED);
	    } else if (reqPath.startsWith("/replica")) {
		getReplica(reqPath, response, objManager, le);
		String objid = reqPath.substring("/replica/".length());
		log.info("logging request for replica object id= " + objid);
		le.setEvent(DataOneLogger.EVENT_REPLICATE);
		myRequestLogger.log(le);
	    } else {
		response.sendError(HttpServletResponse.SC_BAD_REQUEST);
	    }
	} catch (Exception e) {
	    log.error("UNEXPECTED EXCEPTION", e);
	} finally {
	    closeContext(ctxt);
	}
    }
    
    //////////////////////////////////////////////////////////////////////////////////////////////////////////
    /**
     * Initializes the DSpace context, so we have access to the DSpace objects.
     * Requires the location of the dspace.cfg file to be set in the web.xml.
     **/
    public void init() throws ServletException {
	log.debug("initializing...");
	ServletContext context = this.getServletContext();
	String configFileName = context.getInitParameter("dspace.config");
	File aConfig = new File(configFileName);
	
	if (aConfig != null) {
	    if (aConfig.exists() && aConfig.canRead() && aConfig.isFile()) {
		ConfigurationManager.loadConfig(aConfig.getAbsolutePath());
		
		log.debug("DSpace config loaded from " + aConfig);
	    }
	    else if (!aConfig.exists()) {
		log.fatal("dspace.cfg file at " + aConfig.getAbsolutePath() + " doesn't exist");
		throw new RuntimeException(aConfig.getAbsolutePath() + " doesn't exist");
	    }
	    else if (!aConfig.canRead()) {
		log.fatal("dspace.cfg file at " + aConfig.getAbsolutePath() + " cannot be read");
		throw new RuntimeException("Can't read the dspace.cfg file");
	    }
	    else if (!aConfig.isFile()) {
		log.fatal("dspace.cfg file at " + aConfig.getAbsolutePath() + " is not a file!");
		throw new RuntimeException("Err, dspace.cfg isn't a file?");
	    }
	}
	
	myData = ConfigurationManager.getProperty("stats.datafiles.coll");
	mySolr = ConfigurationManager.getProperty("solr.dryad.server");
		
	myRequestLogger = new DataOneLogger();  //this assumes a configuration has been loaded
	log.debug("initialization complete");
    }
    
    //////////////////////////////////////////////////////////////////////////////////////////////////////////
    private int parseInt(HttpServletRequest request, String aParam, int aDefault) {
	String intString = request.getParameter(aParam);
	int intValue = aDefault;
	
	try {
	    if (intString != null) {
		intValue = Integer.parseInt(intString);
	    }
	}
	catch (NumberFormatException details) {
	    log.warn(aParam + " parameter not an int: " + intString);
	}
	
	return intValue;
    }
    
    //////////////////////////////////////////////////////////////////////////////////////////////////////////
    /**
       Parses a user-entered date. The date may appear in one of many common formats. If the date
       format is not recognized, null is returned.
    **/
    private Date parseDate(HttpServletRequest request, String aParam)
	throws ParseException {
	String date = request.getParameter(aParam);
	
	if (date == null) {
	    return null;
	}
	
	try {
	    return DateTimeFormat.fullDateTime().parseDateTime(date).toDate();
	}
	catch (IllegalArgumentException details) {}
	
	try {
	    return DateTimeFormat.forPattern("yyyyMMdd").parseDateTime(date).toDate();
	}
	catch (IllegalArgumentException details) {}
	
	try {
	    return DateTimeFormat.forPattern("yyyy-MM-dd").parseDateTime(date).toDate();
	}
	catch (IllegalArgumentException details) {}
	
	try {
	    return DateTimeFormat.forPattern("yyyy-MM-dd'T'HH:mm:ss.SSSS").parseDateTime(date).toDate();
	}
	catch (IllegalArgumentException details) {}
	
	try {
	    return DateTimeFormat.forPattern("yyyyMMdd'T'HHmmss.SSSS").parseDateTime(date).toDate();
	}
	catch (IllegalArgumentException details) {}
	// See http://joda-time.sourceforge.net/api-release/org/joda/time/format/DateTimeFormat.html
	// for explanation of ZZ time zone formatting
	try {  
	    return DateTimeFormat.forPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSZZ").parseDateTime(date).toDate();
	}
	catch (IllegalArgumentException details) {}
	
	try {
	    return DateTimeFormat.forPattern("yyyyMMdd'T'HHmmss.SSSSZZ").parseDateTime(date).toDate();
	}
	catch (IllegalArgumentException details) {}
	
	return null;
    }


    //////////////////////////////////////////////////////////////////////////////////////////////////////////
    /**
       Performs a basic test that this Member Node is alive
    **/
    private void ping(HttpServletResponse response,  ObjectManager objManager) throws IOException {
	log.info("ping");

	// try to get a single object. If it fails, return an error.
	try {
	    OutputStream dummyOutput = new OutputStream() { public void write(int b) throws IOException {}};
	    objManager.getMetadataObject("doi:10.5061/dryad.20/1", dummyOutput);	    
	} catch (Exception e) {
	    log.error("Unable to retrieve test metadata object doi:10.5061/dryad.20/1", e);
	    // if there is any problem, respond with an error
	    response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
	    return;
	}
	
	// the basic test passed; return some simple information about this node
	response.setContentType(XML_CONTENT_TYPE);
	OutputStream out = response.getOutputStream();
	PrintWriter pw = new PrintWriter(out);
	
	// basic node description
	pw.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?> \n" +
		 "<d1:node xmlns:d1=\"http://ns.dataone.org/service/types/v1\" replicate=\"true\" synchronize=\"true\" type=\"mn\" state=\"up\"> \n" +
		 "<identifier>urn:node:DRYAD</identifier>\n" +
		 "<name>Dryad Digital Repository</name>\n" +
		 "<description>Dryad is an international repository of data underlying peer-reviewed scientific and medical literature.</description>\n" +
		 "<baseURL>https://datadryad.org/mn</baseURL>\n");

    	// other random info
	pw.write("<ping success=\"true\"/>\n" +
		 "<subject>CN=urn:node:DRYAD, DC=dataone, DC=org</subject>\n" +
		 "<contactSubject>CN=METACAT1, DC=dataone, DC=org</contactSubject>\n");

	// close xml
	pw.write("</d1:node>\n");
    }

    
    //////////////////////////////////////////////////////////////////////////////////////////////////////////
    /**
       Returns logs of this Member Node's activities.
     **/
    private void getLogRecords(HttpServletRequest request, HttpServletResponse response) throws IOException {
        log.info("getLogRecords()");
        final OutputStream out = response.getOutputStream();
        final PrintWriter pw = new PrintWriter(out);
        try{
            Date from = parseDate(request, "fromDate");
            Date to = parseDate(request, "toDate");
            String event = request.getParameter("event");
            String pidFilter = request.getParameter("pidFilter");
            int start = parseInt(request, "start",
                    DataOneLogger.DEFAULT_START);
            int count = parseInt(request, "count",
                    DataOneLogger.DEFAULT_COUNT);

            if (myRequestLogger != null){
                log.info("Request string (from) is " + request.getParameter("fromDate"));
                log.info("Dates for log records; from= " + from + "; to= " + to);
                DataOneLogger.LogResults r = myRequestLogger.getLogRecords(from,to,event,pidFilter,start,count);
                response.setContentType(XML_CONTENT_TYPE);
                pw.write(r.getLogRecords());
            }
            else{
                //TBD
            }
        } catch (ParseException e) {
            log.error("unable to parse request info", e);
            response.sendError(HttpServletResponse.SC_BAD_REQUEST,
                    e.getMessage());

        } catch (StringIndexOutOfBoundsException e) {
            log.error("Passed request did not find a match", e);
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
        }

        pw.close();
    }


    //////////////////////////////////////////////////////////////////////////////////////////////////////////
    /**
       Responds with the capabilities of this Member Node.
    **/
    private void getCapabilities(HttpServletResponse response) throws IOException {
	log.info("getCapabilities()");
	response.setContentType(XML_CONTENT_TYPE);
	OutputStream out = response.getOutputStream();
	PrintWriter pw = new PrintWriter(out);

	// basic node description
	pw.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?> \n" +
		 "<?xml-stylesheet type=\"text/xsl\" href=\"/themes/Dryad/dataOne/dataone.types.v1.xsl\"?> \n" +
		 "<d1:node xmlns:d1=\"http://ns.dataone.org/service/types/v1\" replicate=\"true\" synchronize=\"true\" type=\"mn\" state=\"up\"> \n" +
		 "<identifier>urn:node:DRYAD</identifier>\n" +
		 "<name>Dryad Digital Repository</name>\n" +
		 "<description>Dryad is an international repository of data underlying peer-reviewed scientific and medical literature.</description>\n" +
		 "<baseURL>https://datadryad.org/mn</baseURL>\n");

	// supported services 
	pw.write("<services>\n" +
		 "<service name=\"MNRead\" version=\"v1\" available=\"true\"/>\n" +
		 "<service name=\"MNCore\" version=\"v1\" available=\"true\"/>\n" +
		 "<service name=\"MNAuthorization\" version=\"v1\" available=\"false\"/>\n" +
		 "<service name=\"MNStorage\" version=\"v1\" available=\"false\"/>\n" +
		 "<service name=\"MNReplication\" version=\"v1\" available=\"false\"/>\n" +
		 "</services>\n");

	// synchronization
	pw.write("<synchronization>\n" +
		 "<schedule hour=\"*\" mday=\"*\" min=\"0/3\" mon=\"*\" sec=\"10\" wday=\"?\" year=\"*\"/>\n" +
		 "</synchronization>\n");

	// other random info
	pw.write("<ping success=\"true\"/>\n" +
		 "<subject>CN=urn:node:DRYAD, DC=dataone, DC=org</subject>\n" +
		 "<contactSubject>CN=Ryan Scherle, DC=datadryad, DC=org</contactSubject>\n");

	// close xml
	pw.write("</d1:node>\n");
    
	pw.close();
		   
    }

    //////////////////////////////////////////////////////////////////////////////////////////////////////////
    /**
       Retrieve a particular object from this Member Node.
    **/
    private void getObject(String reqPath, HttpServletResponse response, ObjectManager objManager, LogEntry logent) throws ServletException, IOException {
	log.info("getObject()");
	
	String format = "";
	String fileName = "";
	String id = reqPath.substring("/object/".length());
	String simpleDOI = id.replace('/','_').replace(':','_');

	try {
	    if (!id.endsWith("/bitstream")) {
		// return a metadata record (file or package)
		fileName = simpleDOI + ".xml";
        response.setContentType(XML_CONTENT_TYPE);

		// throw early, try not to create an output stream
		Item item = objManager.getDSpaceItem(id);
		
		log.debug("getting science metadata object id=" + id);
		objManager.getMetadataObject(id, response.getOutputStream());
		logent.setIdentifier(id);
	    } else {
		// return a bitstream
		log.debug("bitstream requested");
		int bitsIndex = id.indexOf("/bitstream");
		id = id.substring(0,bitsIndex);
		logent.setIdentifier(id);

		// locate the bitstream
		Item item = objManager.getDSpaceItem(id);
		Bitstream bitstream = objManager.getFirstBitstream(item);
		

		// send it to output stream
		String mimeType = bitstream.getFormat().getMIMEType();
		response.setContentType(mimeType);
		log.debug("Setting data file MIME type to: " + mimeType);		
		fileName = bitstream.getName();
		response.setHeader("Content-Disposition", "attachment; filename=\"" + fileName);
		objManager.writeBitstream(bitstream.retrieve(), response.getOutputStream());
	    }
	    
	}
	catch (NotFoundException details) {
	    log.error("Passed request returned not found", details);
        response.setStatus(404);
        String resStr = generateNotFoundResponse(id, "mn.get","1020");
        OutputStream out = response.getOutputStream();
        PrintWriter pw = new PrintWriter(out);
        pw.write(resStr);
        pw.flush();
	} catch (StringIndexOutOfBoundsException e) {
	    log.error("Passed request did not find a match", e);
	    response.sendError(HttpServletResponse.SC_NOT_FOUND);
	} catch(Exception e) {
	    log.error("unable to getObject " + reqPath, e);
	    
	    throw new ServletException("unable to getObject" + reqPath, e);
	}
	
    }
    
    private String generateNotFoundResponse(String id, String method, String code) throws IOException{
        String responseStr = 
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?> \n" +
            "<error name='NotFound'" + "\n" + 
            "    errorCode='404'" + "\n" + 
            "    detailCode='" + code + "'\n" + 
            "    pid=" + "\"" + StringEscapeUtils.escapeXml(id) + "\"\n" + 
            "    nodeId='datadryad'>" + "\n" + 
            "  <description>The specified object does not exist on this node.</description>" + "\n" + 
            "  <traceInformation>" + "\n" + 
            "    method: " + method + "\n" +
            "    hint: http://cn.dataone.org/cn/resolve/" + StringEscapeUtils.escapeXml(id) + "\n" + 
            "  </traceInformation>" + "\n" + 
            "</error>" + "\n";
        return responseStr;
    }

    //////////////////////////////////////////////////////////////////////////////////////////////////////////
    /**
       Returns the system metadata associated with an object.       
    **/
    private void getSystemMetadata(String reqPath, HttpServletResponse response, ObjectManager objManager) throws ServletException, IOException {
	log.info("getSystemMetadata()");
	String id = reqPath.substring("/meta/".length());
	if (id.startsWith("http:/d")){
	    log.info("reinserting slash");
	    id = id.substring(0,5) + '/' + id.substring(5);
	}
	log.debug("id = " + id);
	
	response.setContentType(TEXT_XML_CONTENT_TYPE); // default for /meta
	
	try {
	    SystemMetadata sysMeta = new SystemMetadata(id);
	    XMLSerializer serializer = new XMLSerializer(response.getOutputStream());
	    
	    Item item = objManager.getDSpaceItem(id);
	    log.debug("retrieved item with internal ID " + item.getID());

	    EPerson ePerson = item.getSubmitter();
	    String epEmail = ePerson.getEmail();
	    DCValue [] accDateValue = item.getMetadata("dc.date.accessioned");
	    String accDateString;
	    if (accDateValue.length >0){
	        accDateString = accDateValue[0].value;
	    }
	    else {
	        accDateString = null;
	    }
	    Date date = item.getLastModified();
	    String lastMod = DataOneLogger.convertDate(date)+'Z'; // The converter in the logger object seems to do it right
	   
	    if (id.endsWith("/bitstream")) {
		//build sysmeta for a bistream
		Bitstream bitstream = objManager.getFirstBitstream(item);
		
		String format = translateMIMEToDataOneFormat(bitstream.getFormat().getMIMEType());
		sysMeta.setObjectFormat(format);
		
		// Add relationship between this bitstream and the science data that describes it
		sysMeta.setDescribedBy(id);

		String checksum = bitstream.getChecksum();
		String algorithm = bitstream.getChecksumAlgorithm();

		sysMeta.setChecksum(checksum, algorithm);  // reversed (?)

		sysMeta.setSize(bitstream.getSize());
	    } else {
		// build sysmeta for a science metadata object				
		sysMeta.setObjectFormat(DRYAD_NAMESPACE);

		long size = objManager.getObjectSize(id); 
        sysMeta.setSize(size);
		
		// Add relationship between this science metadata and the bitstream it describes.
		// Data packages don't have a bitstream, so they are skipped
		Collection collect = item.getOwningCollection();
		if(collect.getID() == DATA_FILE_COLLECTION) {
		    // how many mns can the current cns handle?
		    sysMeta.setDescribes(id + "/bitstream");
		}

		String[] checksumDetails = objManager.getObjectChecksum(id);
		sysMeta.setChecksum(checksumDetails[0], checksumDetails[1]);
	    }

	    //The date format in the dryad metadata appears to be acceptable
	    if (accDateString != null)
	        sysMeta.setDateUploaded(accDateString);
	    else {
	        sysMeta.setDateUploaded(lastMod);
	        log.warn("No acessioned date retrieved for filling DC uploaded string; will default to last modified");
	    }
	    sysMeta.setLastModified(lastMod);
	    sysMeta.setSubmitter(epEmail);
	    sysMeta.setRightsHolder(DRYAD_ADMIN); 
	    sysMeta.setAuthoritative(DRYAD_NODE_IDENTIFIER);
	    sysMeta.setOrigin(DRYAD_NODE_IDENTIFIER);

	    sysMeta.formatOutput();
	    serializer.write(new Document(sysMeta));
	    serializer.flush();
	    response.getOutputStream().close();
	}
	catch (NotFoundException details) {
	    log.error("Passed request returned not found", details);
	    response.setStatus(404);
	    String resStr = generateNotFoundResponse(id,"mn.getSystemMetadata","1060");
	    OutputStream out = response.getOutputStream();
	    PrintWriter pw = new PrintWriter(out);
	    pw.write(resStr);
	    pw.flush();
	}
	catch (SQLException details) {
	    throw new ServletException("unable to retrieve System Metadata for " + reqPath, details);
	}
	catch (IOException details) {
	    throw new ServletException("unable to retrieve System Metadata for " + reqPath, details);
	}
	catch (StringIndexOutOfBoundsException details) {
	    log.error("Passed request did not find a match", details);
	    response.sendError(HttpServletResponse.SC_NOT_FOUND);
	}	
    }

    private String translateMIMEToDataOneFormat(String dryadMIMEString){
        return dryadMIMEString;
    }
    
    //////////////////////////////////////////////////////////////////////////////////////////////////////////
    /**
       Returns the basic properties of an object.
    **/
    private void describe(String reqPath, HttpServletResponse response, ObjectManager objManager) throws ServletException, IOException {
	log.info("describe()");

	String id = reqPath.substring("/object/".length());
	
	try {
	    long length = objManager.getObjectSize(id);
	    response.setContentLength((int) length);

	    if (id.endsWith("/bitstream")) {
	    ServletContext context = getServletContext();

	    log.warn("NEED TO GET CORRECT MIME TYPE");
	    String mimeType = "application/octet-stream";
	    //String mimeType = context.getMimeType("f." + format);

	    if (mimeType == null || mimeType.equals("")) {
	        mimeType = "application/octet-stream";
	    }
	    Item item = objManager.getDSpaceItem(id);

	    Bitstream bitstream = objManager.getFirstBitstream(item);

	    String format = translateMIMEToDataOneFormat(bitstream.getFormat().getMIMEType());

	    response.setHeader("DataONE-formatId", format);
	    final String[] checksumDetails = objManager.getObjectChecksum(id);
	    final String checkSumAlg = checksumDetails[1];
	    final String checkSum = checksumDetails[0];
	    final String checkSumStr = checkSumAlg + "," + checkSum; 
	    response.setHeader("DataONE-Checksum",checkSumStr);
	    response.setHeader("DataONE-SerialVersion", "1");

	    log.debug("Setting data file MIME type to: "
	               + mimeType + " (this is configurable)");

	    response.setContentType(mimeType);
	    } else {
            response.setHeader("DataONE-formatId", DRYAD_NAMESPACE);
	        response.setContentType(XML_CONTENT_TYPE);
	        final String[] checksumDetails = objManager.getObjectChecksum(id);
	        final String checkSumAlg = checksumDetails[1];
	        final String checkSum = checksumDetails[0];
	        final String checkSumStr = checkSumAlg + "," + checkSum; 
            response.setHeader("DataONE-Checksum",checkSumStr);
            response.setHeader("DataONE-SerialVersion", "1");
	    }
	}
	catch (NotFoundException details) {
	    log.error("Passed request returned not found", details);
	    response.setStatus(404);
        response.setContentType(XML_CONTENT_TYPE);
        response.setHeader("DataONE-Exception-Name", "NotFound");
        response.setHeader("DataONE-Exception-DetailCode", "1380");
        response.setHeader("DataONE-Exception-Description", "The specified object does not exist on this node.");
        response.setHeader("DataONE-Exception-PID", StringEscapeUtils.escapeXml(id));
        response.addDateHeader("Last-Modified", System.currentTimeMillis());  
        String resStr = generateNotFoundResponse(id, "mn.describe","1380");
        response.setContentLength(resStr.length());
        OutputStream out = response.getOutputStream();
        PrintWriter pw = new PrintWriter(out);
        pw.write(resStr);
        pw.flush();

	} 
	catch (SQLException details) {
	    log.error(details.getMessage(), details);
	    throw new ServletException(details);
	}
	catch (StringIndexOutOfBoundsException details) {
	    log.error("Passed request did not find a match", details);
	    response.sendError(HttpServletResponse.SC_NOT_FOUND);
	}
	catch (Exception details) {
	    log.error("UNEXPECTED EXCEPTION", details);
	    response.sendError(HttpServletResponse.SC_NOT_FOUND);
	}
	finally {
	    objManager.completeContext();
	}
    }

    //////////////////////////////////////////////////////////////////////////////////////////////////////////
    /**
       Returns the checksum associated with an object.
    **/
    private void getChecksum(String reqPath, HttpServletResponse response, ObjectManager objManager) throws IOException {
	log.info("getChecksum()");

	String id = reqPath.substring("/checksum/".length());
       	response.setContentType(TEXT_XML_CONTENT_TYPE);
	
	try {
	    String[] checksum = objManager.getObjectChecksum(id);
	    PrintWriter writer = response.getWriter();
	    
	    writer.print("<checksum xmlns=\"" + D1_TYPES_NAMESPACE
			 + "\" algorithm=\"" + checksum[1] + "\">" + checksum[0]
			 + "</checksum>");
	    
	    writer.close();
	}
	catch (NotFoundException details) {
	    log.error("Passed request returned not found", details);
	    response.setStatus(404);
	    String resStr = generateNotFoundResponse(id, "mn.getChecksum","1420");
	    OutputStream out = response.getOutputStream();
	    PrintWriter pw = new PrintWriter(out);
	    pw.write(resStr);
	    pw.flush();
	}
	catch (SQLException details) {
	    response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
			       "unable to get checksum for " + reqPath + "; " + details.getMessage());
	}
	catch (IOException details) {
	    response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
			    details.getMessage());
	}
    }

    //////////////////////////////////////////////////////////////////////////////////////////////////////////
    /**
       List all objects available on this Member Node.
    **/
    private void listObjects(HttpServletRequest request, HttpServletResponse response, ObjectManager objManager) throws IOException {
	log.info("listObjects()");
	String format = request.getParameter("formatId");

	try {
	    Date from = parseDate(request, "fromDate");
	    Date to = parseDate(request, "toDate");
	    
	    int start = parseInt(request, "start",
				 ObjectManager.DEFAULT_START);
	    int count = parseInt(request, "count",
				 ObjectManager.DEFAULT_COUNT);
	    
	    response.setContentType(XML_CONTENT_TYPE);
	    
	    if (count <= 0) {
		OutputStream out = response.getOutputStream();
		objManager.printList(from, to, format, out);
	    }
	    else {
		OutputStream out = response.getOutputStream();
		objManager.printList(start, count, from, to, format,
				     out);
	    }
	} catch (ParseException e) {
	    log.error("unable to parse request info", e);
	    response.sendError(HttpServletResponse.SC_BAD_REQUEST,
			       e.getMessage());
	    
	} catch (SQLException e) {
	    response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
			       "unable to list objects; " + e.getMessage());
	} catch (StringIndexOutOfBoundsException e) {
	    log.error("Passed request did not find a match", e);
	    response.sendError(HttpServletResponse.SC_NOT_FOUND);
	}
    }

    //////////////////////////////////////////////////////////////////////////////////////////////////////////
    /**
       Record the fact that a synchronization has failed.
    **/
    private void synchronizationFailed(HttpServletRequest request, HttpServletResponse response) throws IOException {
	log.info("synchronizationFailed()");
	response.setStatus(HttpServletResponse.SC_OK);

	// todo: send email to admin.
    }

    //////////////////////////////////////////////////////////////////////////////////////////////////////////
    /**
       Retrieve a replica of an item.
    **/
    private void getReplica(String reqPath, HttpServletResponse response, ObjectManager objManager, LogEntry logent) throws IOException,ServletException {
        log.info("replicateObject()");
        String format = "";
        String fileName = "";
        String id = reqPath.substring("/replica/".length());
        String simpleDOI = id.replace('/','_').replace(':','_');

        try {
            if (!id.endsWith("/bitstream")) {
                // return a metadata record (file or package)
                format = "dap";
                fileName = simpleDOI + ".xml";
                response.setContentType(XML_CONTENT_TYPE);
        
                log.debug("replicating object id=" + id +", format=" + format);
                objManager.getMetadataObject(id, response.getOutputStream());
                logent.setIdentifier(id);
            }
            else {
                // return a bitstream
                log.debug("bitstream requested");
                logent.setIdentifier(id);

                // locate the bitstream
                Item item = objManager.getDSpaceItem(id);
 		Bitstream bitstream = objManager.getFirstBitstream(item);

                // send it to output stream
                String mimeType = bitstream.getFormat().getMIMEType();
                response.setContentType(mimeType);
                log.debug("Setting data file MIME type to: " + mimeType);       
                fileName = bitstream.getName();
                response.setHeader("Content-Disposition", "attachment; filename=\"" + fileName);
                objManager.writeBitstream(bitstream.retrieve(), response.getOutputStream());
            }
        
        }
        catch (NotFoundException details) {
            log.error("Passed request returned not found", details);
            response.setStatus(404);
            String resStr = generateNotFoundResponse(id, "mn.getReplica","2185");
            OutputStream out = response.getOutputStream();
            PrintWriter pw = new PrintWriter(out);
            pw.write(resStr);
            pw.flush();
        } catch (StringIndexOutOfBoundsException e) {
            log.error("Passed request did not find a match", e);
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
        } catch(Exception e) {
            log.error("unable to replicateObject " + reqPath, e);
            throw new ServletException("unable to replicateObject" + reqPath, e);
        }
    }


}
