/* 
 * GridDataSetThredds Copyright 2006, NOAA.
 * See the LICENSE.txt file in this file's directory.
 */
package gov.noaa.pfel.coastwatch.griddata;

import com.cohort.array.Attributes;
import com.cohort.array.FloatArray;
import com.cohort.array.IntArray;
import com.cohort.array.DoubleArray;
import com.cohort.array.PrimitiveArray;
import com.cohort.array.StringArray;
import com.cohort.util.Calendar2;
import com.cohort.util.File2;
import com.cohort.util.Math2;
import com.cohort.util.MustBe;
import com.cohort.util.String2;
import com.cohort.util.Test;
import com.cohort.util.XML;

import gov.noaa.pfel.coastwatch.OneOf;
import gov.noaa.pfel.coastwatch.pointdata.Table;
import gov.noaa.pfel.coastwatch.TimePeriods;
import gov.noaa.pfel.coastwatch.util.SSR;
import gov.noaa.pfel.coastwatch.util.StringObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.GregorianCalendar;
import java.util.Hashtable;
import java.util.Vector;

import javax.xml.xpath.XPath;   //requires java 1.5
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

// from netcdfAll-x.jar
import ucar.nc2.*;
import ucar.nc2.dataset.NetcdfDataset;
//import ucar.nc2.dods.*;
import ucar.nc2.util.*;
import ucar.ma2.*;

/** The Java DAP classes.  */
import dods.dap.*;

/** 
 * This class represents a gridDataSet which gets data and metadata from 
 * THREDDS/Opendap.
 *
 * @author Bob Simons (was bob.simons@noaa.gov, now BobSimons2.00@gmail.com) 2006-05-16
 */
public class GridDataSetThredds extends GridDataSet { 

    /** The active time period Opendaps related to an activeTimePeriodOption. */
    public Vector activeTimePeriodOpendaps = new Vector(); //set by the constructor

    public static boolean acceptDeflate = true;

    //the constructor sets
    public String directory;
    public String fileNameRegex;
    public String gridName;

    //the constructor also sets
    public long failTime = 0;
    public long newOpendapTime = 0;
    public long getIndexInfoTime = 0;
    public int opendapTimesCount = 0;


    /**
     * The constructor searches for available data and sets
     * activeTimePeriodOptions, activeTimePeriodTitles, activeTimePeriodTimes,
     * and activeTimePeriodFileNames.
     * This prints diagnostics to String2.log.
     *
     * @param fileNameUtility
     * @param internalName  TMOk490
     * @param baseUrl e.g., http://192.168.31.13:8081/thredds/Satellite/aggregsatMO/k490/
     * @param tPalette e.g., "Rainbow"
     * @param tPaletteScale if null, "Linear" will be used
     * @param tPaletteMin if null or NaN, it will be determined from 'actual_range' metadata
     * @param tPaletteMax if null or NaN, it will be determined from 'actual_range' metadata
     * @param tDaysTillDataAccessAllowed  e.g., -1 (for margin of safety) or 14 for seawifs.
     * @param tAnomalyDataSet  or "" if none
     * @param fgdc several lines of substitutions, 
     *    stored in one String with newline separators
     *    (null if unused)
     * @param flagDirectory  If flagDirectory is not null and this dataset needs to be
     *    reindexed (because the time values have changed), a file will
     *    be put in flagDirectory (by lowMakeGrid or another method in this class) 
     *    to signal the need for a new index.
     * @param tDefaultUnits 'S' for standard, 'A' for Alternate
     * @param tAltScaleFactor 1st step to get data from standard units to alt units
     * @param tAltOffset 2nd step to get data from standard units to alt units
     * @param tAltUnits "" if alt units not used
     * @param tAltMin the default paletteMin for the alt units
     * @param tAltMax the default paletteMax for the alt units
     * @throws Exception if trouble
     */
    public GridDataSetThredds(FileNameUtility fileNameUtility, String internalName, String baseUrl, 
        String tPalette, String tPaletteScale, String tPaletteMin, String tPaletteMax, 
        int tDaysTillDataAccessAllowed, String tAnomalyDataSet, String fgdc,
        String flagDirectory,
        String tDefaultUnits, double tAltScaleFactor, double tAltOffset,
        String tAltUnits, double tAltMin, double tAltMax) throws Exception {

        this.fileNameUtility = fileNameUtility;
        this.internalName    = internalName;
        this.directory       = baseUrl; 
        anomalyDataSet = tAnomalyDataSet == null? "" : tAnomalyDataSet;

        //this.fileNameRegex = fileNameRegex;
        if (verbose)
            String2.log("\n* GridDataSetThredds constructor (" + directory + ")");
        long time = System.currentTimeMillis();
        String errorInMethod = String2.ERROR + " in GridDataSetThredds.constructor for\n  " + baseUrl + ":  \n";

        //create vectors to hold info for available data
        Vector tActiveTimePeriodOptions     = new Vector();//holds the active timePeriodOptions for this dataset
        Vector tActiveTimePeriodTitles      = new Vector();//holds the active timePeriodTitles  for this dataset
        Vector tActiveTimePeriodOpendapUrls = new Vector();//holds the active timePeriodOpendapUrls for this dataset
        Vector tActiveTimePeriodNHours      = new Vector();//holds the active timePeriodNHours  for this dataset
        tActiveTimePeriodTitles.add(""); //always add a blank main title

        //make baseBaseUrl
        //from https://thredds1.pfeg.noaa.gov/thredds/Satellite/aggregsatMO/k490/
        //make https://thredds1.pfeg.noaa.gov
        int threddsPo = baseUrl.indexOf("/thredds/");
        Test.ensureTrue(threddsPo != -1, errorInMethod + "\"/thredds/\" not found in baseUrl.");
        String baseBaseUrl = baseUrl.substring(0, threddsPo);

/* 
top level catalog is at https://oceanwatch.pfeg.noaa.gov/thredds/catalog.xml
excerpt from https://oceanwatch.pfeg.noaa.gov/thredds/Satellite/aggregsatMO/k490/catalog.xml
to see opendap server e.g., https://oceanwatch.pfeg.noaa.gov/thredds/dodsC/satellite/MO/k490/1day.html
oceanwatch numerical ip from coastwatch computer is 192.168.31.13)

Most datasets have 2 levels: <dataset><dataset>
<?xml version="1.0" encoding="UTF-8"?>
<catalog xmlns="http://www.unidata.ucar.edu/namespaces/thredds/InvCatalog/v1.0" xmlns:xlink="https://www.w3.org/1999/xlink" name="Satellite Data Server" version="1.0.1">
  <service name="all" serviceType="Compound" base="">
    <service name="wcs" serviceType="WCS" base="/thredds/wcs/" suffix="?request=GetCapabilities&amp;version=1.0.0&amp;service=WCS" />
    <service name="ncdods" serviceType="OPENDAP" base="/thredds/dodsC/" />
  </service>
  <dataset name="Diffuse Attenuation coefficient at 490nm (Turbidity)">
    <dataset name="Single Scans" ID="satellite/MO/k490/hday" urlPath="satellite/MO/k490/hday">
      <serviceName>all</serviceName>
    </dataset>
    <dataset name="1-day" ID="satellite/MO/k490/1day" urlPath="satellite/MO/k490/1day">
      <serviceName>all</serviceName>
    </dataset>
    <dataset name="3-day" ID="satellite/MO/k490/3day" urlPath="satellite/MO/k490/3day">
      <serviceName>all</serviceName>
    </dataset>
    <dataset name="8-day" ID="satellite/MO/k490/8day" urlPath="satellite/MO/k490/8day">
      <serviceName>all</serviceName>
    </dataset>
    <dataset name="14-day" ID="satellite/MO/k490/14day" urlPath="satellite/MO/k490/14day">
      <serviceName>all</serviceName>
    </dataset>
  </dataset>
</catalog>

J1ugeo has just one level: <dataset>   *** So look for <dataset> with urlPath attribute
<?xml version="1.0" encoding="UTF-8"?>
<catalog xmlns="http://www.unidata.ucar.edu/namespaces/thredds/InvCatalog/v1.0" xmlns:xlink="https://www.w3.org/1999/xlink" name="GLOBEC Data Server" version="1.0.1">
  <service name="all" serviceType="Compound" base="">
    <service name="wcs" serviceType="WCS" base="/thredds/wcs/" suffix="?request=GetCapabilities&amp;version=1.0.0&amp;service=WCS" />
    <service name="ncdods" serviceType="OPENDAP" base="/thredds/dodsC/" />
  </service>
  <dataset name="10-day zonal geostrophic current" ID="satellite/J1/ugeo/10day" urlPath="satellite/J1/ugeo/10day">
    <serviceName>all</serviceName>
  </dataset>
</catalog>
*/

        //open the thredds catalog xml file
        //String2.log("getUrlString for " + baseUrl + "catalog.xml");
        //String2.log(XML.encodeAsTerminal(SSR.getURLResponseStringUnchanged(baseUrl + "catalog.xml")));
        Document document = XML.parseXml(SSR.getBufferedUrlReader(baseUrl + "catalog.xml"), false);
        XPath xPath = XML.getXPath();

        //get the opendapServiceBase, e.g., "/thredds/dodsC/"
        //find a service tag with serviceType='OPENDAP' and a base attribute
        NodeList nodeList = XML.getNodeList(document, xPath, 
            "/catalog/service/service[@serviceType='OPENDAP' and @base]"); //an xPath query
        Test.ensureNotEqual(nodeList.getLength(), 0, errorInMethod + 
            "catalog.xml has no /catalog/service/service tag with serviceType='OPENDAP' and a base attribute.");
        String opendapServiceBase = XML.getAttribute(nodeList.item(0), "base");
        if (verbose) String2.log("  opendapServiceBase=" + opendapServiceBase);

        //get all the dataset tags (at whatever level) with urlPath attribute
        nodeList = XML.getNodeList(document, xPath, "//dataset[@urlPath]");  //an xPath query
        for (int i = 0; i < nodeList.getLength(); i++) {

            //get the urlPath
            String urlPath = XML.getAttribute(nodeList.item(i), "urlPath"); //"satellite/MO/k490/8day"
            if (verbose) String2.log("  urlPath=" + urlPath);

//***This relies on end of directory being one of TimePeriods.IN_FILE_NAMES, e.g., 8day
            int po = urlPath.lastIndexOf('/');
            if (po < 0)
                po = urlPath.lastIndexOf('\\');
            String timePeriodInFileName = urlPath.substring(po + 1);
            int whichTimePeriod = String2.indexOf(TimePeriods.IN_FILE_NAMES, timePeriodInFileName);
            if (whichTimePeriod < 0) {
                String2.log(errorInMethod + "unrecognized timePeriod for urlPath: " + urlPath);
                continue; //continue to next while() loop 
            } else {
                String2.log("  timePeriodInFileName=" + timePeriodInFileName);
            }
            String urlPathNoTimePeriod = urlPath.substring(0, po + 1);

            String dataSetUrl = "";
            long tFailTime = System.currentTimeMillis();
            try {
                //try to create the opendap object
                long tTime = System.currentTimeMillis();

                //need to convert 
                //  baseUrl "https://oceanwatch.pfeg.noaa.gov/thredds/Satellite/aggregsatMO/k490/" + "catalog.xml", 
                //  to      "https://oceanwatch.pfeg.noaa.gov/thredds/dodsC/" + "satellite/MO/k490/hday",
                //  just add  ".html" to make user-friendly link
                dataSetUrl = baseBaseUrl + opendapServiceBase + urlPath;  

                Opendap opendap = new Opendap(dataSetUrl, acceptDeflate,
                    flagDirectory); //throws Exception if trouble
                newOpendapTime += System.currentTimeMillis() - tTime;

                //get the DAS and DDS
                DConnect dConnect = new DConnect(dataSetUrl, acceptDeflate, 1, 1);
                DAS das = dConnect.getDAS(OpendapHelper.DEFAULT_TIMEOUT);
                DDS dds = dConnect.getDDS(OpendapHelper.DEFAULT_TIMEOUT);

                //get the grid
                tTime = System.currentTimeMillis();
                StringArray gridNames = opendap.getGridNames(dds);
                if (gridNames.size() == 0)
                    Test.error(errorInMethod + "no grids found.");
                if (gridNames.size() > 1)
                    String2.log(errorInMethod + gridNames.size() + " grids found: " + gridNames.toString());
                gridName = gridNames.get(0);
                
                opendap.getGridInfo(das, dds, gridName, "-1.0e34");
                if (opendap.gridTimeDimension < 0 || 
                    opendap.gridDepthDimension < 0 || 
                    opendap.gridLatDimension < 0 || 
                    opendap.gridLonDimension < 0)
                    throw new RuntimeException(errorInMethod + "dataset (" + dataSetUrl + ")\n" +
                        "  grid (" + gridName + ") wasn't found.\n" +
                        "  timeDim=" + opendap.gridTimeDimension + 
                        "  depthDim=" + opendap.gridDepthDimension + 
                        "  latDim=" + opendap.gridLatDimension + 
                        "  lonDim=" + opendap.gridLonDimension);

               //get numberOfObservations (do before getTimeOptions)
               //This is inactive because the information is stored as metadata in each file.
               //But thredds aggregates files and only shows metadata from the penultimate file.
               //So this information is no longer available.
                /*try {
                    opendap.numberOfObservations = 
                        opendap.getDoubleArrayFromDArray(
                            "?numberOfObservations.numberOfObservations");
                } catch (Exception e) {
                    //no numberOfObservations, but dataset is still valid
                    String2.log("For url=" + dataSetUrl + " " + timePeriodInFileName + 
                        ":\n  numberOfObservations wasn't found!");
                }*/
                
                //get the attributes 
                String2.log("  getAttributes?  globalAtt size=0?  =" + globalAttributes.size());
                if (globalAttributes.size() == 0) { 
                    //global
String2.log("trying dataSetUrl=" + dataSetUrl);
                    NetcdfFile netcdfFile = NcHelper.openOpendapAsNetcdfDataset(dataSetUrl);
                    try {
                        //FUTURE: THIS COULD NOW BE DONE VIA OPENDAPHELPER,
                        //but not time critical, since just done once per GridDataSetThredds
                        Group rootGroup = netcdfFile.getRootGroup();

                        NcHelper.getGroupAttributes(rootGroup, globalAttributes);
                        //if (verbose) String2.log("\n// global attributes:\n" +
                        //    globalAttributes.toNcString("\t\t:", " ;"));

                        //lon
                        String tName = opendap.gridDimensionNames[opendap.gridLonDimension];
                        Variable variable = netcdfFile.findVariable(tName);
                        Test.ensureNotNull(variable, errorInMethod + "Lon variable '" + tName + "' not found."); 
                        NcHelper.getVariableAttributes(variable, lonAttributes);
                        //if (verbose) String2.log(lonAttributes.toNcString("GridDataSetThredds attributes: " + tName + ":", " ;"));

                        //lat
                        tName = opendap.gridDimensionNames[opendap.gridLatDimension];
                        variable = netcdfFile.findVariable(tName);
                        Test.ensureNotNull(variable, errorInMethod + "Lat variable '" + tName + "' not found."); 
                        NcHelper.getVariableAttributes(variable, latAttributes);
                        //if (verbose) String2.log(latAttributes.toNcString("GridDataSetThredds attributes: " + tName + ":", " ;"));

                        //depth
                        tName = opendap.gridDimensionNames[opendap.gridDepthDimension];
                        variable = netcdfFile.findVariable(tName);
                        Test.ensureNotNull(variable, errorInMethod + "Depth variable '" + tName + "' not found."); 
                        NcHelper.getVariableAttributes(variable, depthAttributes);
                        //if (verbose) String2.log(latAttributes.toNcString("GridDataSetThredds attributes: " + tName + ":", " ;"));

                        //time
                        tName = opendap.gridDimensionNames[opendap.gridTimeDimension];
                        variable = netcdfFile.findVariable(tName);
                        Test.ensureNotNull(variable, errorInMethod + "Time variable '" + tName + "' not found."); 
                        NcHelper.getVariableAttributes(variable, timeAttributes);
                        //if (verbose) String2.log(latAttributes.toNcString("GridDataSetThredds attributes: " + tName + ":", " ;"));
                        //fix for old ERD THREDDS datasets; actual time adjustment is made in Opendap getTimeOptions
                        timeAttributes.set("long_name", "Centered Time");

                        //data 
                        tName = opendap.gridName;
                        variable = netcdfFile.findVariable(tName);
                        Test.ensureNotNull(variable, errorInMethod + "Data variable '" + tName + "' not found."); 
                        NcHelper.getVariableAttributes(variable, dataAttributes);
                        //if (verbose) String2.log(dataAttributes.toNcString("GridDataSetThredds attributes: " + tName + ":", " ;"));

                    } finally {
                        try {if (netcdfFile != null) netcdfFile.close(); } catch (Exception e9) {}
                    }


                    //populate the gridDataSet variables
                    String title = globalAttributes.getString("title").trim();
                    String2.log("internalName=" + internalName + " title=" + String2.annotatedString(title));

                    double dPaletteMin = String2.parseDouble(tPaletteMin);
                    double dPaletteMax = String2.parseDouble(tPaletteMax);
                    if (Double.isNaN(dPaletteMin) || Double.isNaN(dPaletteMax)) {
                        PrimitiveArray actualRange = dataAttributes.get("actual_range"); 
                        Test.ensureNotNull(actualRange, errorInMethod + "No 'actual_range' metadata.");
                        Test.ensureEqual(actualRange.size(), 2, errorInMethod + "'actual_range' length must be 2.");
                        double lowHigh[] = Math2.suggestLowHigh(
                            actualRange.getDouble(0), 
                            actualRange.getDouble(1));
                        dPaletteMin = lowHigh[0];
                        dPaletteMax = lowHigh[1];

                        //center range on 0?  palette will be set to blueWhiteRed below
                        if (dPaletteMin < 0 && dPaletteMax > 0 &&  //range spans 0
                            dPaletteMax/-dPaletteMin >= 0.5 && dPaletteMax/-dPaletteMin <= 2) { //magnitudes are similar
                            dPaletteMax = Math.max(-dPaletteMin, dPaletteMax);
                            dPaletteMin = -dPaletteMax;
                        }
                    } 

                    Test.ensureTrue(tDefaultUnits.equals("S") || tDefaultUnits.equals("A"), 
                        errorInMethod + "defaultUnits=" + tDefaultUnits + " must be 'S' or 'A'.");
                    defaultUnits        = tDefaultUnits.charAt(0);
                    altUdUnits          = tAltUnits; //i.e., "" if alt system not used
                    altScaleFactor      = tAltScaleFactor;
                    altOffset           = tAltOffset;
                    altPaletteMin       = String2.genEFormat6(tAltMin); 
                    altPaletteMax       = String2.genEFormat6(tAltMax); 
                    if (altUdUnits.length() > 0)
                        Test.ensureTrue(!Double.isNaN(altScaleFactor) && !Double.isNaN(altOffset) ||
                                        !Double.isNaN(tAltMin)        && !Double.isNaN(tAltMax),
                            errorInMethod + "altUdUnits=" + altUdUnits + 
                                ", but altScaleFactor=" + altScaleFactor +
                                ", altOffset=" + altOffset +
                                ", altPaletteMin=" + altPaletteMin +
                                ", altPaletteMax=" + altPaletteMax);
                    if (defaultUnits == 'A' && altUdUnits.length() == 0)
                        Test.error(errorInMethod + "defaultUnits='A', but altUdUnits is ''.");
                    boldTitle           = title;
                    contourLinesAt  = String2.genEFormat6(
                        Math2.suggestDivisions(dPaletteMax - dPaletteMin)[0]); //[0]=primary division distance
                    if (altUdUnits.length() > 0) 
                        altContourLinesAt  = String2.genEFormat6(
                            Math2.suggestDivisions(tAltMax - tAltMin)[0]); //[0]=primary division distance
                    else altContourLinesAt = "1";
                    courtesy            = globalAttributes.getString("contributor_name");
                    if (courtesy == null)
                        courtesy        = globalAttributes.getString("creator_name");
                    if (courtesy == null) courtesy = "";
                    if (fgdc != null) 
                        fgdcSubstitutions = String2.splitNoTrim(fgdc, '\n');
                    keywords            = globalAttributes.getString("keywords");
                    keywordsVocabulary  = globalAttributes.getString("keywords_vocabulary");
                    daysTillDataAccessAllowed = tDaysTillDataAccessAllowed;
                    option              = title + "*"; 
                    palette             = tPalette == null?
                        (Math2.almostEqual(5, dPaletteMax, -dPaletteMin)? "BlueWhiteRed" : 
                         dPaletteMin == 0? "WhiteRedBlack" : "Rainbow") :
                        tPalette; 
                    paletteMin          = String2.genEFormat6(dPaletteMin);
                    paletteMax          = String2.genEFormat6(dPaletteMax);
                    paletteScale        = tPaletteScale == null? "Linear" : tPaletteScale; 
                    references          = globalAttributes.getString("references");
                    summary             = globalAttributes.getString("summary");
                    if (summary != null) {
                        //remove units info in summary -- too hard to maintain
                        int po1 = summary.indexOf(" The units of the data are ");
                        if (po1 >= 0) {
                            int po2 = summary.indexOf(".", po1);
                            if (po2 > 0)
                                summary = summary.substring(0, po1) + summary.substring(po2 + 1);
                        } else {
                            po1 = summary.indexOf(" Units are ");
                            if (po1 >= 0) {
                                int po2 = summary.indexOf(".", po1);
                                if (po2 > 0)
                                    summary = summary.substring(0, po1) + summary.substring(po2 + 1);
                            }
                        }
                    }
                    udUnits = dataAttributes.getString("units"); 
                    if (udUnits == null) udUnits = "(unknown units)";
                    if (altUdUnits.length() > 0)
                        unitsOptions = new String[]{DataHelper.makeUdUnitsReadable(udUnits),
                            DataHelper.makeUdUnitsReadable(altUdUnits)}; 
                    else unitsOptions = new String[]{DataHelper.makeUdUnitsReadable(udUnits)}; 
                }

                //kludge to treat 25 and 33 hour datasets correctly
                //ERD's thredds stores them in "hday" directories
                //  so without a kludge, they are treated as hday datasets 
                //  (e.g., EndTime not adjusted to be centered)
                int nHours = TimePeriods.N_HOURS[whichTimePeriod];
                if (nHours == 0 && 
                    (internalName.endsWith("24h") || internalName.endsWith("25h"))) {
                    whichTimePeriod = TimePeriods._25HOUR_INDEX;
                } else if (nHours == 0 && internalName.endsWith("33h")) {
                    whichTimePeriod = TimePeriods._33HOUR_INDEX;
                }
                nHours = TimePeriods.N_HOURS[whichTimePeriod]; //recalculate it
                
                //getTimeOptions (generate times[] as ISO 8601 strings)
                opendap.getTimeOptions(
                    TimePeriods.PICK_FROM[whichTimePeriod].indexOf('h') < 0, //true = show times; false = date only
                    opendap.gridTimeFactorToGetSeconds, opendap.gridTimeBaseSeconds, 
                    nHours); 
                String times[] = opendap.timeOptions;
                getIndexInfoTime += System.currentTimeMillis() - tTime;

                //if this sub dataset has data, save it
                if (times != null && times.length > 0) {

                    //store the information
                    int nTimes = times.length;
                    opendapTimesCount += nTimes;
                    if (verbose)  
                        String2.log(nTimes + " times found in " + dataSetUrl);
                    if (reallyVerbose) {
                        for (int locali = 0; locali < Math.min(7, nTimes); locali++)
                            String2.log(times[locali]);
                        if (nTimes > 7)
                            String2.log("...");
                    }

                    //save the info
                    tActiveTimePeriodOptions.add(TimePeriods.OPTIONS[whichTimePeriod]);
                    tActiveTimePeriodTitles.add(TimePeriods.TITLES[whichTimePeriod]);
                    tActiveTimePeriodOpendapUrls.add(dataSetUrl);
                    tActiveTimePeriodNHours.add("" + nHours);
                    activeTimePeriodTimes.add(times);
                    activeTimePeriodOpendaps.add(opendap);
                }
                
            } catch (Exception e) {
                tFailTime = System.currentTimeMillis() - tFailTime;
                failTime += tFailTime;
                String2.log(MustBe.throwable(
                    Opendap.WAIT_THEN_TRY_AGAIN + 
                    "\n(GridDataSetThredds can't open Opendap " + baseUrl + "\n" +                        
                    " failTime=" + tFailTime + ")", e));
            }

        } 

        //convert vectors to string[]'s
        activeTimePeriodOptions     = String2.toStringArray(tActiveTimePeriodOptions.toArray());
        activeTimePeriodTitles      = String2.toStringArray(tActiveTimePeriodTitles.toArray());
        activeTimePeriodOpendapUrls = String2.toStringArray(tActiveTimePeriodOpendapUrls.toArray());
        activeTimePeriodNHours      = String2.toIntArray(tActiveTimePeriodNHours.toArray());

        //check validity
        checkValidity();
        if (verbose) String2.log(
            "  Options: "     + String2.toCSSVString(activeTimePeriodOptions) + "\n" +
            "  Titles: "      + String2.toCSSVString(activeTimePeriodTitles) + "\n" +
            "  NHours: "      + String2.toCSSVString(activeTimePeriodNHours) + "\n" +
            "  OpendapUrls: " + String2.toCSSVString(activeTimePeriodOpendapUrls) + "\n" +
            "  GridDataSetThredds constructor " + internalName + " done. TIME=" + 
                (System.currentTimeMillis() - time));
    }        

    /**
     * This makes the specified grid as best it can.
     * See the superclass' documentation.
     */
    public Grid makeGrid(String timePeriodValue, String timeValue,         
        double minX, double maxX, 
        double minY, double maxY,
        int desiredNWide, int desiredNHigh)  throws Exception {

        long time = System.currentTimeMillis();
        String msg = "GridDataSetThredds.makeGrid(timePeriod=" + timePeriodValue +
               " date=" + timeValue +
            "\n  minX=" + minX +
               " maxX=" + maxX +
               " minY=" + minY +
               " maxY=" + maxY +
               " nWide=" + desiredNWide +
               " nHigh=" + desiredNHigh + ")";
        if (verbose) String2.log("/* " + msg); 
        String errorInMethod = String2.ERROR + " in " + msg + ":\n";

        //get indexes
        int timePeriodIndex = String2.indexOf(activeTimePeriodOptions, timePeriodValue);
        Test.ensureNotEqual(timePeriodIndex, -1, 
            errorInMethod + "timePeriod not found: " + timePeriodValue +
            "\ntimePeriodOptions=" + String2.toCSSVString(activeTimePeriodOptions));
        String tActiveTimePeriodTimes[] = (String[])activeTimePeriodTimes.get(timePeriodIndex);
        int timeIndex = String2.indexOf(tActiveTimePeriodTimes, timeValue);
        Test.ensureNotEqual(timeIndex, -1, 
            errorInMethod + "time (" + timeValue + ") must be one of\n" + 
            String2.toCSSVString(tActiveTimePeriodTimes));

        //get the data
        Opendap opendap = (Opendap)activeTimePeriodOpendaps.get(timePeriodIndex); 
        Grid grid = opendap.makeGrid(
            ((String[])activeTimePeriodTimes.get(timePeriodIndex))[timeIndex],  
            minX, maxX, minY, maxY,
            desiredNWide, desiredNHigh);  

        if (verbose) String2.log("\\* GridDataSetThredds.makeGrid " + 
            internalName + " done. TIME=" + 
            (System.currentTimeMillis() - time) + "\n");

        return grid;

    }


    /**
     * Make a Table with time series data for the grid point nearest to x,y.
     * If x,y is outside the range of the grid, an empty table will be
     * returned.
     *
     * <p> This is a THREDDS-specific implementation. 
     *
     * @param newDir  the directory for the intermediate grd file (if any)
     * @param x the desired latitude
     * @param y the desired longitude
     * @param isoMinTime an ISO format date/time for the minimum ok time.
     *    The searches are done based on the activeTimePeriodTimes.
     *    So, for example, searches for 1 day files are based on just date (2006-06-16),
     *    not dateTtime (e.g., 2006-06-16T23:59:59). 
     *    If isoMinTime and isoMaxTime are between two adjacent available times,
     *    the datum for the single closest time is returned.
     * @param isoMaxTime an ISO format date/time for the maximum ok time
     * @param timePeriod one of the activeTimePeriodOptions 
     * @return a Table with 6 columns: lon, lat, depth (meters, positive=down, currently always 0), 
     *     centered time (seconds since 1970-01-01), id (internalName), data).
     *   The data will be unpacked, in the standard units.
     *   Numeric column types may vary.
     *   Rows with missing values are NOT removed.
     *   The metadata (e.g., actual_range) will be correct (as correct as I can make it). 
     *   If there are no matching time points, this returns a table with all the
     *   usual columns but 0 rows.
     * @throws Exception if trouble (e.g., invalid isoMinTime or isoMaxtime)
     */
    public Table getTimeSeries(String newDir, double x, double y,
        String isoMinTime, String isoMaxTime, String timePeriod) throws Exception {

        if (verbose) String2.log("GridDataSetThredds.getTimeSeries x=" + x + " y=" + y + 
            "\n  isoMinTime=" + isoMinTime + " isoMaxTime=" + isoMaxTime);
        String errorInMethod = String2.ERROR + " in GridDataSetThredds.getTimeSeries:  x=" + x + " y=" + y + 
            "\n  isoMinTime=" + isoMinTime + " isoMaxTime=" + isoMaxTime;
        long time = System.currentTimeMillis();

        //ensure times have space connector (since all options have space connector)
        isoMinTime = String2.replaceAll(isoMinTime, "T", " ");
        isoMaxTime = String2.replaceAll(isoMaxTime, "T", " ");
        int timePeriodNHours = TimePeriods.getNHours(timePeriod);
        double expectedGapSeconds; //a gap of longer than this means data is missing
        if (timePeriodNHours == 0 || timePeriodNHours % 24 != 0) { //timePeriod is 0 or 25 or 33 hours
            expectedGapSeconds = Calendar2.SECONDS_PER_HOUR; //1 hour
        } else if (timePeriodNHours < 30 * 24) { //timePeriod is nDays
            expectedGapSeconds = Calendar2.SECONDS_PER_DAY; //1 day
        } else { //timePeriod is >= 1 month               
            expectedGapSeconds = 32 * Calendar2.SECONDS_PER_DAY;  //e.g., gap is 32 days (more than 1 month)
        }

        //make the resultsTable
        DoubleArray xColumn = new DoubleArray();
        DoubleArray yColumn = new DoubleArray();
        DoubleArray depthColumn = new DoubleArray();
        DoubleArray tColumn = new DoubleArray();
        StringArray idColumn = new StringArray();
        FloatArray dataColumn = new FloatArray(); //currently, all grid datasets are floats (Future: better if dynamically chosen)
        Table results = new Table();
        globalAttributes.copyTo(results.globalAttributes());
        results.addColumn(0, "LON", xColumn, (Attributes)lonAttributes.clone());
        results.addColumn(1, "LAT", yColumn, (Attributes)latAttributes.clone());
        results.addColumn(2, "DEPTH", depthColumn, (Attributes)depthAttributes.clone());
        results.addColumn(3, "TIME", tColumn, (Attributes)timeAttributes.clone());
        results.addColumn(4, "ID", idColumn, new Attributes());
        results.addColumn(5, internalName, dataColumn, (Attributes)dataAttributes.clone());

        //!!! If 'L'ocal dataset   (stored in .zipped .grd files),
        //  if timePeriodNHours == 0 or  %24 !=0 (for 25 and 33 hours), limit access to 3 months
        //  if timePeriodNHours >= 1 month, no limit
        //  else (nDays) limit to 1 year
        String dataAccessLimit = null;
        double originalMinSeconds = Calendar2.isoStringToEpochSeconds(isoMinTime); //throws Exception if trouble

        //thredds has no need for dataAccessLimit (like GridDataSet)
        if (verbose) String2.log("  origMinSeconds=" + originalMinSeconds + " isoMinTime=" + isoMinTime + 
            "\n  dataAccessLimit=" + dataAccessLimit + " timePeriodNHours=" + timePeriodNHours);

        //find the timePeriod
        int timePeriodIndex = String2.indexOf(activeTimePeriodOptions, timePeriod);
        Test.ensureNotEqual(timePeriodIndex, -1, 
            errorInMethod + "unrecognized timePeriod: " + timePeriod);
        String timePeriodInFileName = TimePeriods.getInFileName(timePeriod);

        //get the opendap object for this timePeriod
        Opendap opendap = (Opendap)activeTimePeriodOpendaps.get(timePeriodIndex); 

        //get the times for that timePeriod
        String[] activeTimes = (String[])activeTimePeriodTimes.get(timePeriodIndex);   

        //find the first and last time indexes (use activeTimes, since they have been adjusted to be centeredTime)
//should this (and in GridDataSet) be binaryFindClosest?
        int firstActiveTimeIndex = Calendar2.binaryFindFirstGE(activeTimes, isoMinTime);
        int lastActiveTimeIndex  = Calendar2.binaryFindLastLE( activeTimes, isoMaxTime);

        boolean hasData = true; 
        if (firstActiveTimeIndex == activeTimes.length ||
            lastActiveTimeIndex == -1) {
            String2.log("GridDataSetThredds.getTimeSeries no relevant times!\n" +
                " activeTimes[0]=" + activeTimes[0] + 
                " activeTimes[n-1]=" + activeTimes[activeTimes.length - 1]);
            hasData = false;
        }


        double lonValues[] = null;
        double latValues[] = null;
        double xAdjust = Double.NaN; //value needed to put x into opendap lon range 
        int firstTimeIndex = -1;  
        int lastTimeIndex  = -1;  
        if (hasData) {
            //range is between two adjacent times? return datum for single closest day
            if (firstActiveTimeIndex == lastActiveTimeIndex + 1) {
                firstActiveTimeIndex = Calendar2.binaryFindClosest(activeTimes, isoMinTime);
                lastActiveTimeIndex = firstActiveTimeIndex;
            }

            //convert activeTimeIndexes back to index in time dimension (since some times are skipped)
            firstTimeIndex = opendap.timeOptionsIndex[firstActiveTimeIndex];  
            lastTimeIndex  = opendap.timeOptionsIndex[lastActiveTimeIndex];  
            if (verbose) String2.log(
                "  firstActiveTime=" + activeTimes[firstActiveTimeIndex] + " >= isoMinTime=" + isoMinTime + " index=" + firstTimeIndex + "\n" +
                "  lastActiveTime="  + activeTimes[lastActiveTimeIndex]  + " <= isoMaxTime=" + isoMaxTime + " index=" + lastTimeIndex);

            //find the closest opendap lon, lat indexes
            lonValues = opendap.gridDimensionData[opendap.gridLonDimension];
            latValues = opendap.gridDimensionData[opendap.gridLatDimension];
            int nLonValues = lonValues.length;
            int nLatValues = latValues.length;
            if      (x       >= lonValues[0] && x       <= lonValues[nLonValues - 1]) xAdjust = 0;
            else if (x + 360 >= lonValues[0] && x + 360 <= lonValues[nLonValues - 1]) xAdjust = 360;
            else if (x - 360 >= lonValues[0] && x - 360 <= lonValues[nLonValues - 1]) xAdjust = -360;
            if (Double.isNaN(xAdjust)) {
                String2.log("  x=" + x + " is out of range: min=" + lonValues[0] + " max=" + lonValues[nLonValues - 1]);
                hasData = false;
            }
            if (hasData && (y < latValues[0] || y > latValues[nLatValues - 1])) {
                String2.log("  y=" + y + " is out of range: min=" + latValues[0] + " max=" + latValues[nLatValues - 1]);
                hasData = false;
            }
        }

        if (hasData) {
            int lonIndex = Math2.binaryFindClosest(lonValues, x + xAdjust);
            int latIndex = Math2.binaryFindClosest(latValues, y);
            double resultLon = lonValues[lonIndex] - xAdjust;
            double resultLat = latValues[latIndex];
            String2.log("  xAdjust=" + xAdjust + 
                " lonIndex=" + lonIndex + " latIndex=" + latIndex + 
                " resultLon=" + resultLon + " resultLat=" + resultLat);

            //prepare opendap query   
            int nDimensions = opendap.gridDimensionData.length;
            int minIndex[] = new int[nDimensions];
            int maxIndex[] = new int[nDimensions];
            int stride[]   = new int[nDimensions];
            Arrays.fill(minIndex, 0); //altitide dimension uses the defaults
            Arrays.fill(maxIndex, 0);
            Arrays.fill(stride,   1);
            minIndex[opendap.gridLonDimension] = lonIndex;
            maxIndex[opendap.gridLonDimension] = lonIndex;
            minIndex[opendap.gridLatDimension] = latIndex;
            maxIndex[opendap.gridLatDimension] = latIndex;
            minIndex[opendap.gridTimeDimension] = firstTimeIndex;
            maxIndex[opendap.gridTimeDimension] = lastTimeIndex;
            StringBuilder sb = new StringBuilder("?" + opendap.gridName);
            for (int index = 0; index < minIndex.length; index++) 
                sb.append("[" + minIndex[index] + ":" + stride[index] + ":" + maxIndex[index] + "]");
            String query = sb.toString();
            if (verbose) String2.log("  query=" + query);
            long getTime = System.currentTimeMillis();

            //get the data from opendap 
            PrimitiveArray pa[] = null;
            try {
                //throw new Exception("test opendap exception"); //normally this line is commented out
                pa = OpendapHelper.getPrimitiveArrays(new DConnect(opendap.url, opendap.acceptDeflate, 1, 1), 
                    query); //query already percentEncoded as needed //throws Exception if trouble
            } catch (Exception e) {
                Test.error(Opendap.WAIT_THEN_TRY_AGAIN + //this message encourages getting new Shared in CWBrowser.java
                    "\n(Opendap dataset not available:\n  " + query + 
                    "\n" + e + ")");
            }
            getTime = System.currentTimeMillis() - getTime;

            //verify return values are as expected
            PrimitiveArray dataPA = pa[0]; 
            PrimitiveArray timePA = pa[opendap.gridTimeDimension + 1]; //+1 since data array is [0]
            PrimitiveArray lonPA  = pa[opendap.gridLonDimension  + 1]; 
            PrimitiveArray latPA  = pa[opendap.gridLatDimension  + 1]; 
            int expectedSize = lastTimeIndex - firstTimeIndex + 1;
            Test.ensureEqual(dataPA.size(), expectedSize, "pa[0] != expectedSize.");
            Test.ensureEqual(timePA.size(), expectedSize, "pa[timeDimension] != expectedSize.");
            Test.ensureEqual(lonPA.size(), 1, "pa[lonDimension] != 1.");
            Test.ensureEqual(latPA.size(), 1, "pa[latDimension] != 1.");
            Test.ensureEqual(lonPA.getNiceDouble(0) - xAdjust, resultLon, "observedLon != expectedLon");
            Test.ensureEqual(latPA.getNiceDouble(0),           resultLat, "observedLat != expectedLat");
            //use the time values directly

            //put the data in the table
            //find the data for each relevant time point
            double lastTime = 0;
            double depth = 0;  //currently, all grid depths treated as 0 !!!!!
            float missingValue = String2.parseFloat(opendap.gridMissingValue); //round to float is needed
            for (int timeIndex = 0; timeIndex < timePA.size(); timeIndex++) {

                //get raw time
                double thisTime = timePA.getDouble(timeIndex);

                //need to adjust to centeredTime?
                if (opendap.timeLongName != null && opendap.timeLongName.equals("End Time")) {
                    GregorianCalendar gc = Calendar2.epochSecondsToGc(thisTime);
                    //fix old-style (pre-Dec 2006) nDay and 1 month end times  so 00:00
                    if (timePeriodNHours > 1 && timePeriodNHours % 24 == 0)
                        gc.add(Calendar2.SECOND, 1);
                    TimePeriods.endCalendarToCenteredTime(timePeriodNHours, gc, errorInMethod);
                    thisTime = Calendar2.gcToEpochSeconds(gc);
                }

                //if gap since data is too long, add mv row
                String thisIsoEndTime = Calendar2.epochSecondsToIsoStringT(thisTime);
                if (timeIndex > 0 && thisTime - lastTime > expectedGapSeconds) {
                    xColumn.addDouble(resultLon);
                    yColumn.addDouble(resultLat);
                    depthColumn.addDouble(depth);  
                    tColumn.add(thisTime - expectedGapSeconds); //crude but sufficient to cause line break
                    idColumn.addString(internalName);
                    dataColumn.addFloat(Float.NaN);
                }

                //if first timeIndex and there is a dataAccessLimit, add row to be a place holder 1 hour back
                if (dataAccessLimit != null) {
                    xColumn.addDouble(resultLon);
                    yColumn.addDouble(resultLat);
                    depthColumn.addDouble(depth); 
                    tColumn.add(thisTime - Calendar2.SECONDS_PER_HOUR); //really crude, but ok: 1 hour back (if there is only 1 real datum, sgtGraph can't handle <1hr on x axis
                    idColumn.addString(internalName);
                    dataColumn.addFloat(Float.NaN);
                    dataAccessLimit = null; //signal that it has been dealt with
                }

                //add the row for this timeIndex's datum
                xColumn.addDouble(resultLon);
                yColumn.addDouble(resultLat);
                depthColumn.addDouble(depth); 
                tColumn.add(thisTime); 
                idColumn.addString(internalName);
                float datum = dataPA.getFloat(timeIndex);
                dataColumn.add(datum == missingValue? Float.NaN : datum);
                lastTime = thisTime;
            }            
        }

        //add metadata
        //for params, if null, change to "" so existing values not removed
        results.setAttributes(0, 1, 2, 3, boldTitle, 
            "",  //data was Grid, now not,   Arrgggh. Just remove it.
            DataHelper.CW_CREATOR_EMAIL, //who is creating this file...
            DataHelper.CW_CREATOR_NAME,
            DataHelper.CW_CREATOR_URL,
            DataHelper.CW_PROJECT,
            FileNameUtility.makeAveragedGridTimeSeriesName(internalName, 
                'S', x, y, isoMinTime, isoMaxTime, timePeriod), //id
            keywordsVocabulary == null? "" : keywordsVocabulary, 
            keywords           == null? "" : keywords, 
            references         == null? "" : references, 
            summary            == null? "" : summary, 
            courtesy           == null? "" : courtesy,  //who is source of data
            "Centered Time" + 
                (TimePeriods.getNHours(timePeriod) > 0? " of " + timePeriod + " Composites" : ""));
        results.columnAttributes(0).remove("coordsys");
        results.columnAttributes(0).remove("point_spacing");
        results.columnAttributes(1).remove("coordsys");
        results.columnAttributes(1).remove("point_spacing");
        Attributes tDataAttributes = results.columnAttributes(5);
        tDataAttributes.set("long_name", boldTitle);
        tDataAttributes.set("units", udUnits);
        tDataAttributes.remove("_coordinateSystem");
        tDataAttributes.remove("coordsys");
        tDataAttributes.remove("numberOfObservations");
        tDataAttributes.remove("percentCoverage");
        //if (verbose) String2.log(results.toString());

        //remove known global attributes no longer appropriate  (mostly CWHDF metadata)
        Attributes tGlobalAttributes = results.globalAttributes();
        tGlobalAttributes.remove("cols");
        tGlobalAttributes.remove("composite");
        tGlobalAttributes.remove("cwhdf_version");
        tGlobalAttributes.remove("et_affine");
        tGlobalAttributes.remove("gctp_datum");
        tGlobalAttributes.remove("gctp_parm");
        tGlobalAttributes.remove("gctp_sys");
        tGlobalAttributes.remove("gctp_zone");
        tGlobalAttributes.remove("geospatial_lat_resolution");
        tGlobalAttributes.remove("geospatial_lon_resolution");
        tGlobalAttributes.remove("pass_date");
        tGlobalAttributes.remove("polygon_latitude");
        tGlobalAttributes.remove("polygon_longitude");
        tGlobalAttributes.remove("processing_level");
        tGlobalAttributes.remove("geographic");
        tGlobalAttributes.remove("projection_type");
        tGlobalAttributes.remove("rows");
        tGlobalAttributes.remove("start_time");

        if (verbose) String2.log("  getTimeSeries " + internalName + 
            " done. nRows=" + results.nRows() + 
            " TIME=" + (System.currentTimeMillis() - time) + "\n");
        return results;

    }

    /* this is just for test purposes */
    private Table getSuperTimeSeries(String newDir, double x, double y,
        String isoMinTime, String isoMaxTime, String timePeriod) throws Exception {
        return super.getTimeSeries(newDir, x, y, isoMinTime, isoMaxTime, timePeriod);
    }

    /**
     * This does a test of getTimeSeries using a data set on West Coast cwexperimental.
     */
    public static void testGetTimeSeries() throws Exception {
        String2.log("\n*** start TestBrowsers.testGetTimeSeries");
        String url = 
            //was "https://oceanwatch.pfeg.noaa.gov/thredds/Satellite/aggregsatGA/ssta/"; //oceanwatch
            "https://thredds1.pfeg.noaa.gov/thredds/catalog/Satellite/aggregsatGA/ssta/"; 

        DataHelper.verbose = true;
        GridDataSetThredds.verbose = true;
        
        String tempDir = SSR.getTempDirectory();
        Table table = null;

        GridDataSetThredds gdst = new GridDataSetThredds(
            new FileNameUtility("gov.noaa.pfel.coastwatch.CWBrowser"), 
            "TGAssta", //String internalName, 
            url,
            "Rainbow", "Linear", "8", "32", -1, "", null, //fgdc,
            null,
            "S", 1.8, 32, "degree_F", 45, 90);  

        //*** test the GridDataSetThredds version of getTimeSeries
        //test individual makeGrid  to get the values tested below in getTimeSeries
        double rLon = -128.975;
        double rLat = 36.025;

        Grid grid = gdst.makeGrid("1 day", 
            "2007-12-01 12:00:00",         
            rLon, rLon, rLat, rLat, 1, 1);
        Test.ensureEqual(grid.lon[0],   rLon,  "");
        Test.ensureEqual(grid.lat[0],   rLat,  "");
        Test.ensureEqual((float)grid.data[0],  16.35f,  "");

        grid = gdst.makeGrid("1 day",
            "2007-12-02 12:00:00",
            rLon, rLon, rLat, rLat, 1, 1);
        Test.ensureEqual(grid.lon[0],   rLon,  "");
        Test.ensureEqual(grid.lat[0],   rLat,  "");
        Test.ensureEqual((float)grid.data[0],  16.05f,  "");

        //first just test if it gets correct answers            
        table = gdst.getTimeSeries(tempDir, rLon, rLat,
            "2007-12-01 12:00:00", "2007-12-20 12:00:00", "1 day");
        //String2.log("timeSeriesTable=" + table);
        Test.ensureEqual(table.nRows(), 20, "");
        Test.ensureEqual(table.nColumns(), 6, "");
        Test.ensureEqual(table.getColumnName(0), "LON", "");
        Test.ensureEqual(table.getColumnName(1), "LAT", "");
        Test.ensureEqual(table.getColumnName(2), "DEPTH", "");
        Test.ensureEqual(table.getColumnName(3), "TIME", "");
        Test.ensureEqual(table.getColumnName(4), "ID", "");
        Test.ensureEqual(table.getColumnName(5), "TGAssta", "");

        Test.ensureEqual(table.getColumn(0).elementTypeString(), "double", "");
        Test.ensureEqual(table.getColumn(1).elementTypeString(), "double", "");
        Test.ensureEqual(table.getColumn(2).elementTypeString(), "double", "");
        Test.ensureEqual(table.getColumn(3).elementTypeString(), "double", "");
        Test.ensureEqual(table.getColumn(4).elementTypeString(), "String", "");
        Test.ensureEqual(table.getColumn(5).elementTypeString(), "float", "");

        Test.ensureEqual(table.getDoubleData(0,0),  rLon,       "");
        Test.ensureEqual(table.getDoubleData(1,0),  rLat,         "");
        Test.ensureEqual(table.getDoubleData(2,0),  0,          "");
        Test.ensureEqual(table.getDoubleData(3,0),  Calendar2.isoStringToEpochSeconds("2007-12-01 12:00:00"), "");
        Test.ensureEqual(table.getStringData(4,0), "TGAssta",   "");
        Test.ensureEqual(table.getFloatData(5,0),  16.35f,     "");

        Test.ensureEqual(table.getDoubleData(0,1),  rLon,       "");
        Test.ensureEqual(table.getDoubleData(1,1),  rLat,         "");
        Test.ensureEqual(table.getDoubleData(2,1),  0,          "");
        Test.ensureEqual(table.getDoubleData(3,1),  Calendar2.isoStringToEpochSeconds("2007-12-02 12:00:00"), "");
        Test.ensureEqual(table.getStringData(4,1),  "TGAssta",  "");
        Test.ensureEqual(table.getFloatData( 5,1),  16.05f,       "");


        //HARDER TEST: data for all time    does it crash?      35s first run,  then 12s
        //for (int i = 30; i <= 36; i++)
        //    table = gdst.getTimeSeries(tempDir, rLon, i,
        //        "1980-01-01", "2099-12-31", gdst.activeTimePeriodOptions[0]);


        //*** test the GridDataSet (superclass) version of getTimeSeries
        //this is the only test of superclass' getTimeSeries
        table = null;
        table = gdst.getSuperTimeSeries(tempDir, rLon, rLat,
            "2007-12-01 12:00:00", "2007-12-20 12:00:00", "1 day");
        //String2.log("super timeSeriesTable=" + table);
        Test.ensureEqual(table.nRows(), 20, "");
        Test.ensureEqual(table.nColumns(), 6, "");
        Test.ensureEqual(table.getColumnName(0), "LON", "");
        Test.ensureEqual(table.getColumnName(1), "LAT", "");
        Test.ensureEqual(table.getColumnName(2), "DEPTH", "");
        Test.ensureEqual(table.getColumnName(3), "TIME", "");
        Test.ensureEqual(table.getColumnName(4), "ID", "");
        Test.ensureEqual(table.getColumnName(5), "TGAssta", "");

        Test.ensureEqual(table.getDoubleData(0,0),  rLon,       "");
        Test.ensureEqual(table.getDoubleData(1,0),  rLat,         "");
        Test.ensureEqual(table.getDoubleData(2,0),  0,          "");
        Test.ensureEqual(table.getDoubleData(3,0),  Calendar2.isoStringToEpochSeconds("2007-12-01 12:00:00"), "");
        Test.ensureEqual(table.getStringData(4,0), "TGAssta",   "");
        Test.ensureEqual(table.getFloatData(5,0),  16.35f,        "");

        Test.ensureEqual(table.getDoubleData(0,1),  rLon,       "");
        Test.ensureEqual(table.getDoubleData(1,1),  rLat,         "");
        Test.ensureEqual(table.getDoubleData(2,1),  0,          "");
        Test.ensureEqual(table.getDoubleData(3,1),  Calendar2.isoStringToEpochSeconds("2007-12-02 12:00:00"), "");
        Test.ensureEqual(table.getStringData(4,1),  "TGAssta",  "");
        Test.ensureEqual(table.getFloatData(5,1),   16.05f,       "");

    
        //*** test of centering time periods
        //test individual makeGrid  to get the values tested below in getTimeSeries
        grid = gdst.makeGrid("3 day", "2006-08-13 12:00:00", //centered time
            -130.02, -130.02, 36.02, 36.02,  //close, not exact
            1, 1);
        Test.ensureEqual(grid.lon[0],   -130.025,  ""); //3 day grid is offset by .025!!
        Test.ensureEqual(grid.lat[0],   36.025,  "");
        Test.ensureEqual((float)grid.data[0],  19.8f,  "");

        grid = gdst.makeGrid("3 day", "2006-08-08 12:00:00", //centered time
            -130.02, -130.02, 36.02, 36.02, 
            1, 1);
        Test.ensureEqual(grid.lon[0],   -130.025,  "");
        Test.ensureEqual(grid.lat[0],   36.025,  "");
        Test.ensureEqual((float)grid.data[0],  18.75f,  "");

        //first just test if it gets correct answers            
        table = gdst.getTimeSeries(tempDir, -130.02, 36.02,
            "2006-08-08", "2006-08-14", //begin and end centered times
            "3 day");
        //String2.log("timeSeriesTable=" + table);
        PrimitiveArray timePA = table.getColumn(3);
        PrimitiveArray dataPA = table.getColumn(5);

        //find the row corresponding to the desired time
        double seconds = Calendar2.isoStringToEpochSeconds("2006-08-13 12:00:00"); //centered time
        int row = timePA.binaryFindClosest(seconds);
        Test.ensureEqual(timePA.getDouble(row), seconds, ""); //ensure exact match   2006-08-13T23:59:59
        Test.ensureEqual(dataPA.getFloat(row), 19.8f, "");

        seconds = Calendar2.isoStringToEpochSeconds("2006-08-08 12:00:00"); //centered time
        row = timePA.binaryFindClosest(seconds);
        Test.ensureEqual(timePA.getDouble(row), seconds, ""); //ensure exact match
        Test.ensureEqual(dataPA.getFloat(row), 18.75f, "");
    }


   /**
    * This performs a simple test of this class.
    */
   public static void basicTest() throws Exception {
        FileNameUtility.verbose = true;
        FileNameUtility fnu = new FileNameUtility("gov.noaa.pfel.coastwatch.CWBrowser");

//       String baseUrl = "https://oceanwatch.pfeg.noaa.gov/thredds/Satellite/aggregsatMO/k490/";
//       String2.log("getUrlString for " + baseUrl + "catalog.xml");
//       String2.log(SSR.getURLResponseStringUnchanged(baseUrl + "catalog.xml"));

//       Opendap opendap = new Opendap(
//           //"https://oceanwatch.pfeg.noaa.gov/thredds/Satellite/aggregsatMO/k490/hday", 
//           //"https://oceanwatch.pfeg.noaa.gov/thredds/Satellite/aggregsatMO/k490/hday", 
//           "https://oceanwatch.pfeg.noaa.gov/thredds/dodsC/satellite/MO/k490/hday.das",
//           acceptDeflate); //throws Exception if trouble

//    public GridDataSetThredds(FileNameUtility fileNameUtility, String internalName, String baseUrl, 
//        String tPalette, String tPaletteScale, String tPaletteMin, String tPaletteMax, 
//        int tDaysTillDataAccessAllowed, String tAnomalyDataSet, String fgdc,
//        String flagDirectory,
//        String tDefaultUnits, double tAltScaleFactor, double tAltOffset,
//        String tAltUnits, double tAltMin, double tAltMax) throws Exception {
       
        String2.log("\n*** GridDataSetThredds.basicTest");
        GridDataSetThredds gridDataSet;

        //variables
        String internalName, sixName;
        GridDataSetThredds.verbose = true;
        Opendap.verbose = true;
        String dir = File2.getClassPath() + //with / separator and / at the end
            "gov/noaa/pfel/coastwatch/griddata/";

        //****************************
        //ensure J1ugeo loads  (it has 1 level: <dataset>)
        /* //Comment out this line with /* to comment out this test. 
        //J1 datasets are no longer available as of March 2009
        internalName = "TJ1ugeo";
        sixName = internalName.substring(1);
        gridDataSet = new GridDataSetThredds(fnu, internalName,
            //was "https://oceanwatch.pfeg.noaa.gov/thredds/Satellite/aggregsatJ1/ugeo/", //was :8081
            "https://thredds1.pfeg.noaa.gov/thredds/Satellite/aggregsatJ1/ugeo/", 
            "BlueWhiteRed", "Linear", "-10", "10", -1, "", null, null,
            "S", 1, 0, "", 1, 1);
        String2.log("TJ1ugeo activeTimePeriodOptions.length=" + gridDataSet.activeTimePeriodOptions.length);
        Test.ensureNotEqual(gridDataSet.activeTimePeriodOptions.length, 0, 
            "TJ1ugeo activeTimePeriodOptions.length");
        /* */

        //*********************************************************************
        //large test of TQSux10  (it has 2 levels: <dataset><dataset>)
        //Comment out this line with /* to comment out this test. 
        internalName = "TQSux10";
        sixName = internalName.substring(1);
        gridDataSet = new GridDataSetThredds(fnu, internalName,
            "https://thredds1.pfeg.noaa.gov/thredds/catalog/Satellite/aggregsatQS/ux10/",
            "BlueWhiteRed", "Linear", "-10", "10", -1, "", null, null, "S", 1, 0, "", 1, 1);

        Grid grid = gridDataSet.makeGrid(
            "1 day", "2006-06-10 12:00:00", -135, -105, 22, 50, 300, 300);

        //set attributes
        String fileName = "TQSux10S1day_20060610_x-135_X-105_y22_Y50";
        gridDataSet.setAttributes(grid, fileName);
        grid.setStatsAttributes(false);  //false -> floats

        //see if it has the expected values
        int nLat = grid.lat.length;
        int nLon = grid.lon.length;
        Test.ensureEqual(grid.globalAttributes().get("Conventions"),                new StringArray(new String[]{"COARDS, CF-1.6, ACDD-1.3, CWHDF"}), "Conventions");
        Test.ensureEqual(grid.globalAttributes().get("title"),                      new StringArray(new String[]{"Wind, QuikSCAT SeaWinds, 0.25 degrees, Global, Science Quality, Zonal"}), "title");
        Test.ensureEqual(grid.globalAttributes().get("summary"),                    new StringArray(new String[]{"Remote Sensing Inc. distributes science quality wind velocity data from the SeaWinds instrument onboard NASA's QuikSCAT satellite.  SeaWinds is a microwave scatterometer designed to measure surface winds over the global ocean.  Wind velocity fields are provided in zonal, meridional, and modulus sets. The reference height for all wind velocities is 10 meters."}), "summary");  
        Test.ensureEqual(grid.globalAttributes().get("keywords"),                   new StringArray(new String[]{"EARTH SCIENCE > Oceans > Ocean Winds > Surface Winds"}), "keywords");
        //!!!! thredds id differs from opendap at first letter
        Test.ensureEqual(grid.globalAttributes().get("id"),                         new StringArray(new String[]{"TQSux10S1day"}), "id");
        Test.ensureEqual(grid.globalAttributes().get("naming_authority"),           new StringArray(new String[]{"gov.noaa.pfeg.coastwatch"}), "naming_authority");
        Test.ensureEqual(grid.globalAttributes().get("keywords_vocabulary"),        new StringArray(new String[]{"GCMD Science Keywords"}), "keywords_vocabulary");
        Test.ensureEqual(grid.globalAttributes().get("cdm_data_type"),              new StringArray(new String[]{"Grid"}), "cdm_data_typ");
        Test.ensureTrue(grid.globalAttributes().getString("history").startsWith("Remote Sensing Systems, Inc."), "history=" + grid.globalAttributes().getString("history"));
        Test.ensureEqual(grid.globalAttributes().get("date_created"),               new StringArray(new String[]{Calendar2.formatAsISODate(Calendar2.newGCalendarZulu())}), "date_created");
        Test.ensureEqual(grid.globalAttributes().get("creator_name"),               new StringArray(new String[]{"NOAA CoastWatch, West Coast Node"}), "creator_name");
        Test.ensureEqual(grid.globalAttributes().get("creator_url"),                new StringArray(new String[]{"http://coastwatch.pfel.noaa.gov"}), "creator_url");
        Test.ensureEqual(grid.globalAttributes().get("creator_email"),              new StringArray(new String[]{"dave.foley@noaa.gov"}), "creator_email");
        Test.ensureEqual(grid.globalAttributes().getString("institution"), "NOAA CoastWatch, West Coast Node", "institution=" + grid.globalAttributes().getString("institution")); 
        Test.ensureEqual(grid.globalAttributes().get("project"),                    new StringArray(new String[]{"CoastWatch (http://coastwatch.noaa.gov/)"}), "project");
        Test.ensureEqual(grid.globalAttributes().get("processing_level"),           new StringArray(new String[]{"3"}), "processing_level");
        Test.ensureEqual(grid.globalAttributes().get("acknowledgement"),            new StringArray(new String[]{"NOAA NESDIS COASTWATCH, NOAA SWFSC ERD"}), "acknowledgement"); 
        Test.ensureEqual(grid.globalAttributes().get("geospatial_lat_min"),         new DoubleArray(new double[]{22}), "geospatial_lat_min");
        Test.ensureEqual(grid.globalAttributes().get("geospatial_lat_max"),         new DoubleArray(new double[]{50}), "geospatial_lat_max");
        Test.ensureEqual(grid.globalAttributes().get("geospatial_lon_min"),         new DoubleArray(new double[]{-135}), "geospatial_lon_min");
        Test.ensureEqual(grid.globalAttributes().get("geospatial_lon_max"),         new DoubleArray(new double[]{-105}), "geospatial_lon_max");
        Test.ensureEqual(grid.globalAttributes().get("geospatial_lat_units"),       new StringArray(new String[]{"degrees_north"}), "geospatial_lat_units");
        Test.ensureEqual(grid.globalAttributes().get("geospatial_lat_resolution"),  new DoubleArray(new double[]{0.125}), "geospatial_lat_resolution");
        Test.ensureEqual(grid.globalAttributes().get("geospatial_lon_units"),       new StringArray(new String[]{"degrees_east"}), "geospatial_lon_units");
        Test.ensureEqual(grid.globalAttributes().get("geospatial_lon_resolution"),  new DoubleArray(new double[]{0.125}), "geospatial_lon_resolution");
        Test.ensureEqual(grid.globalAttributes().get("time_coverage_start"),        new StringArray(new String[]{"2006-06-10T00:00:00Z"}), "time_coverage_start");
        Test.ensureEqual(grid.globalAttributes().get("time_coverage_end"),          new StringArray(new String[]{"2006-06-11T00:00:00Z"}), "time_coverage_end");
        //Test.ensureEqual(grid.globalAttributes().get("time_coverage_resolution", new StringArray(new String[]{""}), "time_coverage_resolution");
        Test.ensureEqual(grid.globalAttributes().get("standard_name_vocabulary"),   new StringArray(new String[]{"CF Standard Name Table v70"}), "standard_name_vocabulary");
        Test.ensureEqual(grid.globalAttributes().get("license"),                    new StringArray(new String[]{"The data may be used and redistributed for free but is not intended for legal use, since it may contain inaccuracies. Neither the data Contributor, CoastWatch, NOAA, nor the United States Government, nor any of their employees or contractors, makes any warranty, express or implied, including warranties of merchantability and fitness for a particular purpose, or assumes any legal liability for the accuracy, completeness, or usefulness, of this information."}), "license");
        Test.ensureEqual(grid.globalAttributes().get("contributor_name"),           new StringArray(new String[]{"Remote Sensing Systems, Inc."}), "contributor_name");
        Test.ensureEqual(grid.globalAttributes().get("contributor_role"),           new StringArray(new String[]{"Source of level 2 data."}), "contributor_role");
        Test.ensureEqual(grid.globalAttributes().get("date_issued"),                new StringArray(new String[]{Calendar2.formatAsISODate(Calendar2.newGCalendarZulu())}), "date_issued");
        Test.ensureEqual(grid.globalAttributes().get("references"),                 new StringArray(new String[]{"RSS Inc. Winds: http://www.remss.com/ ."}), "references");
        Test.ensureEqual(grid.globalAttributes().get("source"),                     new StringArray(new String[]{"satellite observation: QuikSCAT, SeaWinds"}), "source");
        //Google Earth
        Test.ensureEqual(grid.globalAttributes().get("Southernmost_Northing"),      new DoubleArray(new double[]{22}), "southernmost");
        Test.ensureEqual(grid.globalAttributes().get("Northernmost_Northing"),      new DoubleArray(new double[]{50}), "northernmost");
        Test.ensureEqual(grid.globalAttributes().get("Westernmost_Easting"),        new DoubleArray(new double[]{-135}), "westernmost");
        Test.ensureEqual(grid.globalAttributes().get("Easternmost_Easting"),        new DoubleArray(new double[]{-105}), "easternmost");

        //cwhdf attributes
        Test.ensureEqual(grid.globalAttributes().get("cwhdf_version"),              new StringArray(new String[]{"3.4"}), "cwhdf_version"); //string
        Test.ensureEqual(grid.globalAttributes().get("satellite"),                  new StringArray(new String[]{"QuikSCAT"}), "satellite"); //string
        Test.ensureEqual(grid.globalAttributes().get("sensor"),                     new StringArray(new String[]{"SeaWinds"}), "sensor"); //string
        Test.ensureEqual(grid.globalAttributes().get("composite"),                  new StringArray(new String[]{"true"}), "composite"); //string
 
        Test.ensureEqual(grid.globalAttributes().get("pass_date"),                  new IntArray(new int[]{13309}), "pass_date"); //int32[nDays] 
        Test.ensureEqual(grid.globalAttributes().get("start_time"),                 new DoubleArray(new double[]{0}), "start_time"); //float64[nDays] 
        Test.ensureEqual(grid.globalAttributes().get("origin"),                     new StringArray(new String[]{"Remote Sensing Systems, Inc."}), "origin"); //string
        //Test.ensureEqual(grid.globalAttributes().get("history"),                  new StringArray(new String[]{"unknown"}), "history"); //string

        Test.ensureEqual(grid.globalAttributes().get("projection_type"),            new StringArray(new String[]{"mapped"}), "projection_type"); //string
        Test.ensureEqual(grid.globalAttributes().get("projection"),                 new StringArray(new String[]{"geographic"}), "projection"); //string
        Test.ensureEqual(grid.globalAttributes().get("gctp_sys"),                   new IntArray(new int[]{0}), "gctp_sys"); //int32
        Test.ensureEqual(grid.globalAttributes().get("gctp_zone"),                  new IntArray(new int[]{0}), "gctp_zone"); //int32
        Test.ensureEqual(grid.globalAttributes().get("gctp_parm"),                  new DoubleArray(new double[15]), "gctp_parm"); //float64[15 0's]
        Test.ensureEqual(grid.globalAttributes().get("gctp_datum"),                 new IntArray(new int[]{12}), "gctp_datum");//int32 12=WGS84

        double matrix[] = {0, -grid.latSpacing, grid.lonSpacing, 0, grid.lon[0], grid.lat[nLat-1]}; //up side down
        Test.ensureEqual(grid.globalAttributes().get("et_affine"),                  new DoubleArray(matrix), "et_affine"); //right side up

        Test.ensureEqual(grid.globalAttributes().get("rows"),                       new IntArray(new int[]{grid.lat.length}), "rows");//int32 number of rows
        Test.ensureEqual(grid.globalAttributes().get("cols"),                       new IntArray(new int[]{grid.lon.length}), "cols");//int32 number of columns
        Test.ensureEqual(grid.globalAttributes().get("polygon_latitude"),           new DoubleArray(new double[]{
            grid.lat[0], grid.lat[nLat - 1], grid.lat[nLat - 1], grid.lat[0], grid.lat[0]}), "polygon_latitude");
        Test.ensureEqual(grid.globalAttributes().get("polygon_longitude"),          new DoubleArray(new double[]{
            grid.lon[0], grid.lon[0], grid.lon[nLon - 1], grid.lon[nLon - 1], grid.lon[0]}), "polygon_longitude");

        //lat attributes
        Test.ensureEqual(grid.latAttributes().get("long_name"),                     new StringArray(new String[]{"Latitude"}), "lat long_name");
        Test.ensureEqual(grid.latAttributes().get("standard_name"),                 new StringArray(new String[]{"latitude"}), "lat standard_name");
        Test.ensureEqual(grid.latAttributes().get("units"),                         new StringArray(new String[]{"degrees_north"}), "lat units");
        Test.ensureEqual(grid.latAttributes().get("point_spacing"),                 new StringArray(new String[]{"even"}), "lat point_spacing");
        Test.ensureEqual(grid.latAttributes().get("actual_range"),                  new DoubleArray(new double[]{22, 50}), "lat actual_range");

        //CWHDF metadata/attributes for Latitude
        Test.ensureEqual(grid.latAttributes().get("coordsys"),                      new StringArray(new String[]{"geographic"}), "coordsys");//string
        Test.ensureEqual(grid.latAttributes().get("fraction_digits"),               new IntArray(new int[]{2}), "fraction_digits"); //int32

        
        //lon attributes
        Test.ensureEqual(grid.lonAttributes().get("long_name"),                     new StringArray(new String[]{"Longitude"}), "lon long_name");
        Test.ensureEqual(grid.lonAttributes().get("standard_name"),                 new StringArray(new String[]{"longitude"}), "lon standard_name");
        Test.ensureEqual(grid.lonAttributes().get("units"),                         new StringArray(new String[]{"degrees_east"}), "lon units");
        Test.ensureEqual(grid.lonAttributes().get("point_spacing"),                 new StringArray(new String[]{"even"}), "lon point_spacing");
        Test.ensureEqual(grid.lonAttributes().get("actual_range"),                  new DoubleArray(new double[]{-135, -105}), "lon actual_range");     
        
        //CWHDF metadata/attributes for Longitude
        Test.ensureEqual(grid.lonAttributes().get("coordsys"),                      new StringArray(new String[]{"geographic"}), "coordsys"); //string
        Test.ensureEqual(grid.lonAttributes().get("fraction_digits"),               new IntArray(new int[]{2}), "fraction_digits"); //int32

        
        //data attributes                                                                                     DataSet.properties has 0.125, but files need to be reprocessed to catch that
        Test.ensureEqual(grid.dataAttributes().get("long_name"),                    new StringArray(new String[]{"Wind, QuikSCAT SeaWinds, 0.25 degrees, Global, Science Quality, Zonal"}), "data long_name");
        Test.ensureEqual(grid.dataAttributes().get("standard_name"),                new StringArray(new String[]{"x_wind"}), "data standard_name");
        Test.ensureEqual(grid.dataAttributes().get("units"),                        new StringArray(new String[]{"m s-1"}), "data units");
        Test.ensureEqual(grid.dataAttributes().get("_FillValue"),                   new FloatArray(new float[]{-9999999}), "data _FillValue");
        Test.ensureEqual(grid.dataAttributes().get("missing_value"),                new FloatArray(new float[]{-9999999}), "data missing_value");
        Test.ensureEqual(grid.dataAttributes().get("numberOfObservations"),         new IntArray(new int[]{25021}), "data numberOfObservations");
        Test.ensureEqual(grid.dataAttributes().get("percentCoverage"),              new DoubleArray(new double[]{0.46142923005993547}), "data percentCoverage");

        //CWHDF metadata/attributes for the data: varName
        Test.ensureEqual(grid.dataAttributes().get("coordsys"),                     new StringArray(new String[]{"geographic"}), "coordsys");    //string
        Test.ensureEqual(grid.dataAttributes().get("fraction_digits"),              new IntArray(new int[]{1}), "fraction_digits"); //int32

        /* */

        //***************************************
        //large test of TMBchla which covers has odd x range ~ 110 to 320
        //actual dataset minX=120.0 maxX=320.0 minY=-45.0 maxY=65.0 xInc=0.025 yInc=0.025
        //These mimic tests in Grid.testReadGrdSubset().
        //Comment out this line with /* to comment out this test. 
        gridDataSet = new GridDataSetThredds(fnu, "TMBchla",
            //was "https://oceanwatch.pfeg.noaa.gov/thredds/Satellite/aggregsatMB/chla/", //was :8081
            "https://thredds1.pfeg.noaa.gov/thredds/catalog/Satellite/aggregsatMB/chla/",
            "Rainbow", "Log", ".001", "30", -1, "", null, null, "S", 1, 0, "", 1, 1);
        fileName = "temp";

        //get 4 points individually
        grid = gridDataSet.makeGrid("8 day", "2007-01-12 00:00:00", 315, 315, 30, 30, 1, 1);
        Test.ensureEqual(grid.lon, new double[]{315}, "");
        Test.ensureEqual(grid.lat, new double[]{30}, "");
        Test.ensureEqual((float)grid.data[0], (float)0.039, "");

        grid = gridDataSet.makeGrid("8 day", "2007-01-12 00:00:00", 135, 135, 30, 30, 1, 1);
        Test.ensureEqual(grid.lon, new double[]{135}, "");
        Test.ensureEqual(grid.lat, new double[]{30}, "");
        Test.ensureEqual((float)grid.data[0], (float)0.200333, "");
        
        grid = gridDataSet.makeGrid("8 day", "2007-01-12 00:00:00", 315, 315, -41, -41, 1, 1);
        Test.ensureEqual(grid.lon, new double[]{315}, "");
        Test.ensureEqual(grid.lat, new double[]{-41}, "");
        Test.ensureEqual((float)grid.data[0], (float)0.968, "");

        grid = gridDataSet.makeGrid("8 day", "2007-01-12 00:00:00", 135, 135, -41, -41, 1, 1);
        Test.ensureEqual(grid.lon, new double[]{135}, "");
        Test.ensureEqual(grid.lat, new double[]{-41}, "");
        Test.ensureEqual((float)grid.data[0], (float) 0.431, "");

        //test 2 wide, 1 high 
        grid = gridDataSet.makeGrid("8 day", "2007-01-12 00:00:00", 135, 315, 30, 30, 2, 1);
        Test.ensureEqual(grid.lon, new double[]{135, 315}, "");
        Test.ensureEqual(grid.lat, new double[]{30}, "");
        Test.ensureEqual((float)grid.data[0], (float)0.200333, "");
        Test.ensureEqual((float)grid.data[1], (float)0.039, "");
       
        //test 37 wide, 1 high     test most of the data rotated around to right
        //This is a little contrived: getting every 5 degrees of lon allows it to 
        //  cleanly align at 0 and still get my two test points exactly.
        //  But that is part of the nature of having to move the data columns around.
        grid = gridDataSet.makeGrid("8 day", "2007-01-12 00:00:00", -45, 135, 30, 30, 37, 1); //180 range / 5 = 36 + 1
        String2.log("lon=" + String2.toCSSVString(grid.lon));
        Test.ensureEqual(grid.lon, DataHelper.getRegularArray(37, -45, 5), "");
        Test.ensureEqual(grid.lat, new double[]{30}, "");
        Test.ensureEqual((float)grid.data[0], (float)0.039, "");
        Test.ensureEqual((float)grid.data[36], (float)0.200333, "");
       
        //test 1 wide, 2 high  x in 0.. 180
        grid = gridDataSet.makeGrid("8 day", "2007-01-12 00:00:00", 135, 135, -41, 30, 1, 2);
        Test.ensureEqual(grid.lon, new double[]{135}, "");
        Test.ensureEqual(grid.lat, new double[]{-41, 30}, "");
        Test.ensureEqual((float)grid.data[0], (float) 0.431, "");
        Test.ensureEqual((float)grid.data[1], (float)0.200333, "");

        //test 1 wide, 2 high     in x>180
        grid = gridDataSet.makeGrid("8 day", "2007-01-12 00:00:00", 315, 315, -41, 30, 1, 2);
        Test.ensureEqual(grid.lon, new double[]{315}, "");
        Test.ensureEqual(grid.lat, new double[]{-41, 30}, "");
        Test.ensureEqual((float)grid.data[0], (float)0.968, "");
        Test.ensureEqual((float)grid.data[1], (float)0.039, "");

        //test 1 wide, 2 high     in x<0
        grid = gridDataSet.makeGrid("8 day", "2007-01-12 00:00:00", 315-360, 315-360, -41, 30, 1, 2);
        Test.ensureEqual(grid.lon, new double[]{315-360}, "");
        Test.ensureEqual(grid.lat, new double[]{-41, 30}, "");
        Test.ensureEqual((float)grid.data[0], (float)0.968, "");
        Test.ensureEqual((float)grid.data[1], (float)0.039, "");

        //test 2 wide, 2 high     in x<0
        grid = gridDataSet.makeGrid("8 day", "2007-01-12 00:00:00", 135, 315, -41, 30, 2, 2);
        Test.ensureEqual(grid.lon, new double[]{135, 315}, "");
        Test.ensureEqual(grid.lat, new double[]{-41, 30}, "");
        Test.ensureEqual((float)grid.data[0], (float) 0.431, "");
        Test.ensureEqual((float)grid.data[1], (float)0.200333, "");
        Test.ensureEqual((float)grid.data[2], (float)0.968, "");
        Test.ensureEqual((float)grid.data[3], (float)0.039, "");

        //test 37 wide, 2 high     in x<0
        grid = gridDataSet.makeGrid("8 day", "2007-01-12 00:00:00", 315-360, 135, -41, 30, 37, 2);
        Test.ensureEqual(grid.lon, DataHelper.getRegularArray(37, -45, 5), "");
        Test.ensureEqual(grid.lat, new double[]{-41, 30}, "");
        Test.ensureEqual((float)grid.data[0], (float)0.968, "");
        Test.ensureEqual((float)grid.data[1], (float)0.039, "");
        Test.ensureEqual((float)grid.data[72], (float)0.431, "");
        Test.ensureEqual((float)grid.data[73], (float)0.200333, "");

        /* */

        /*
        //***************************************
        //one time only: get one of these files for testing readGrd
        //actual dataset minX=120.0 maxX=320.0 minY=-45.0 maxY=65.0 xInc=0.025 yInc=0.025
        gridDataSet = new GridDataSetThredds(fnu, "TMBchla",
            "https://oceanwatch.pfeg.noaa.gov/thredds/Satellite/aggregsatMB/chla/", //was :8081
            "Rainbow", "Log", ".001", "30", -1, "", null, null);
        fileName = "TestReadGrgTMBchla";

        //get 4 points individually
        grid = gridDataSet.makeGrid("8 day", "2007-01-12 00:00:00", 120, 320, -45, 65, 201, 111);  //1 x 1 degree
        String2.log("resultingFileName=" + resultingFileName);
        //result is TestReadGrgTMBchla_x120_X320_y-45_Y65_nx201_ny111.grd
        */
   }

    /**
     * This runs all of the interactive or not interactive tests for this class.
     *
     * @param errorSB all caught exceptions are logged to this.
     * @param interactive  If true, this runs all of the interactive tests; 
     *   otherwise, this runs all of the non-interactive tests.
     * @param doSlowTestsToo If true, this runs the slow tests, too.
     * @param firstTest The first test to be run (0...).  Test numbers may change.
     * @param lastTest The last test to be run, inclusive (0..., or -1 for the last test). 
     *   Test numbers may change.
     */
    public static void test(StringBuilder errorSB, boolean interactive, 
        boolean doSlowTestsToo, int firstTest, int lastTest) {
        if (lastTest < 0)
            lastTest = interactive? -1 : 1;
        String msg = "\n^^^ GridDatasetThredds.test(" + interactive + ") test=";

        for (int test = firstTest; test <= lastTest; test++) {
            try {
                long time = System.currentTimeMillis();
                String2.log(msg + test);
            
                if (interactive) {
                    //if (test ==  0) ...;

                } else {
                    if (test ==  0) basicTest();
                    if (test ==  1) testGetTimeSeries();
                }

                String2.log(msg + test + " finished successfully in " + (System.currentTimeMillis() - time) + " ms.");
            } catch (Throwable testThrowable) {
                String eMsg = msg + test + " caught throwable:\n" + 
                    MustBe.throwableToString(testThrowable);
                errorSB.append(eMsg);
                String2.log(eMsg);
                if (interactive) 
                    String2.pressEnterToContinue("");
            }
        }
    }


   /**
    * This performs a quick test of a dataset (e.g. to see why it isn't loading in 
    * a CWBrowser).
    *
    * @param twoName e.g., GA
    * @param fourName e.g., ssta
    */
   public static void quickTest(String twoName, String fourName) throws Exception {
        FileNameUtility.verbose = true;
        FileNameUtility fnu = new FileNameUtility("gov.noaa.pfel.coastwatch.CWBrowser");

        String sixName = twoName + fourName;
        String internalName = "T" + sixName;
        String2.log("\n*** GridDataSetThredds.quickTest of " + internalName);
        GridDataSetThredds.verbose = true;
        Opendap.verbose = true;
        String dir = File2.getClassPath() + //with / separator and / at the end
            "gov/noaa/pfel/coastwatch/griddata/";
        GridDataSetThredds gridDataSet = new GridDataSetThredds(fnu, 
            internalName,
            //was "https://oceanwatch.pfeg.noaa.gov/thredds/Satellite/aggregsat" + 
            "https://thredds1.pfeg.noaa.gov/thredds/Satellite/aggregsat" +
                twoName + "/" + fourName + "/", 
            "BlueWhiteRed", "Linear", "-10", "10", -1, "", null, null,
            "S", 1, 0, "", 1, 1);
        Test.ensureNotEqual(gridDataSet.activeTimePeriodOptions.length, 0, 
            "activeTimePeriodOptions.length");
        String2.log("  activeTimePeriodOptions=" + 
            String2.toCSSVString(gridDataSet.activeTimePeriodOptions));
        String2.log("  GridDataSetThredds.quickTest finished successfully");
   }
}
