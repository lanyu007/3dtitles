package com.example.myapplication.shpload;

import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reader for ESRI Projection (.prj) files - WKT (Well-Known Text) format
 *
 * Parses projection information from .prj files and identifies common
 * coordinate reference systems (CRS) such as WGS84, UTM, Web Mercator, etc.
 *
 * 读取并解析 .prj 投影文件，识别常见的坐标参考系统
 */
public class ProjectionReader {

    private static final String TAG = "ProjectionReader";

    // WKT content
    private String wkt;

    // Parsed information
    private String epsgCode;
    private String projectionName;
    private boolean isGeographic;
    private boolean needsTransformation;

    /**
     * Read and parse a .prj file from input stream
     * 从输入流读取并解析 .prj 文件
     *
     * @param inputStream Input stream of .prj file
     * @throws IOException If reading fails
     */
    public void read(InputStream inputStream) throws IOException {
        // Read WKT content
        StringBuilder wktBuilder = new StringBuilder();
        BufferedReader reader = new BufferedReader(
            new InputStreamReader(inputStream, StandardCharsets.UTF_8)
        );

        String line;
        while ((line = reader.readLine()) != null) {
            wktBuilder.append(line.trim()).append(" ");
        }

        wkt = wktBuilder.toString().trim();
        Log.d(TAG, "WKT loaded: " + wkt.substring(0, Math.min(200, wkt.length())) + "...");

        // Parse projection information
        parseProjection();

        reader.close();
    }

    /**
     * Parse WKT content to extract projection information
     * 解析 WKT 内容提取投影信息
     */
    private void parseProjection() {
        if (wkt == null || wkt.isEmpty()) {
            projectionName = "Unknown";
            epsgCode = "Unknown";
            isGeographic = true;
            needsTransformation = false;
            return;
        }

        // Determine if geographic or projected
        isGeographic = wkt.startsWith("GEOGCS[") || wkt.startsWith("GEOGCRS[");

        // Extract projection name from first quoted string
        Pattern namePattern = Pattern.compile("^(?:GEOGCS|PROJCS|GEOGCRS|PROJCRS)\\[\"([^\"]+)\"");
        Matcher nameMatcher = namePattern.matcher(wkt);
        if (nameMatcher.find()) {
            projectionName = nameMatcher.group(1);
        } else {
            projectionName = "Unknown";
        }

        // Try to extract EPSG code from AUTHORITY section
        Pattern epsgPattern = Pattern.compile("AUTHORITY\\[\"EPSG\",\"(\\d+)\"\\]");
        Matcher epsgMatcher = epsgPattern.matcher(wkt);
        if (epsgMatcher.find()) {
            epsgCode = "EPSG:" + epsgMatcher.group(1);
        } else {
            // Try to identify common projections by name
            epsgCode = identifyEPSGByName();
        }

        // Determine if coordinate transformation is needed
        determineTransformationNeeds();

        Log.d(TAG, "=== Projection Parsed ===");
        Log.d(TAG, "Name: " + projectionName);
        Log.d(TAG, "EPSG: " + epsgCode);
        Log.d(TAG, "Type: " + (isGeographic ? "Geographic" : "Projected"));
        Log.d(TAG, "Needs Transformation: " + needsTransformation);
    }

    /**
     * Identify EPSG code by analyzing projection name and WKT content
     * 通过分析投影名称和 WKT 内容识别 EPSG 代码
     *
     * @return EPSG code string or "Unknown"
     */
    private String identifyEPSGByName() {
        String upperName = projectionName.toUpperCase();
        String upperWkt = wkt.toUpperCase();

        // WGS84 Geographic (most common)
        if (upperName.contains("WGS") && upperName.contains("84") && isGeographic) {
            return "EPSG:4326";
        }
        if (upperName.equals("GCS_WGS_1984")) {
            return "EPSG:4326";
        }

        // China Geodetic Coordinate System 2000 (中国2000国家大地坐标系)
        if (upperName.contains("CGCS2000") || upperName.contains("CGCS_2000")) {
            return "EPSG:4490";
        }
        if (upperName.contains("CHINA") && upperName.contains("2000") && isGeographic) {
            return "EPSG:4490";
        }

        // Web Mercator (used by Google Maps, OpenStreetMap)
        if (upperName.contains("WEB") && upperName.contains("MERCATOR")) {
            return "EPSG:3857";
        }
        if (upperName.contains("POPULAR") && upperName.contains("VISUALISATION")) {
            return "EPSG:3857";
        }

        // UTM Zones (WGS84)
        Pattern utmPattern = Pattern.compile("UTM.*ZONE[\\s_]*(\\d+)([NS])?", Pattern.CASE_INSENSITIVE);
        Matcher utmMatcher = utmPattern.matcher(upperName);
        if (utmMatcher.find()) {
            String zoneStr = utmMatcher.group(1);
            String hemisphere = utmMatcher.group(2);

            try {
                int zone = Integer.parseInt(zoneStr);
                if (zone >= 1 && zone <= 60) {
                    // Determine hemisphere
                    boolean isNorth = true;
                    if (hemisphere != null) {
                        isNorth = hemisphere.equalsIgnoreCase("N");
                    } else if (upperWkt.contains("SOUTH")) {
                        isNorth = false;
                    }

                    // UTM North: EPSG:32601-32660, UTM South: EPSG:32701-32760
                    int epsgNum = isNorth ? (32600 + zone) : (32700 + zone);
                    return "EPSG:" + epsgNum;
                }
            } catch (NumberFormatException e) {
                Log.w(TAG, "Invalid UTM zone number: " + zoneStr);
            }
        }

        // NAD83 (North American Datum 1983)
        if (upperName.contains("NAD83") || upperName.contains("NAD_1983")) {
            return "EPSG:4269";
        }

        // NAD27 (North American Datum 1927)
        if (upperName.contains("NAD27") || upperName.contains("NAD_1927")) {
            return "EPSG:4267";
        }

        // GCJ-02 (Chinese coordinate system used by Baidu, Amap)
        // Note: GCJ-02 is not in EPSG registry, it's a Chinese encryption offset
        if (upperName.contains("GCJ") || upperName.contains("GCJ-02") || upperName.contains("GCJ02")) {
            return "GCJ-02 (非EPSG标准)";
        }

        return "Unknown";
    }

    /**
     * Determine if coordinate transformation is needed to WGS84
     * 判断是否需要坐标转换到 WGS84
     */
    private void determineTransformationNeeds() {
        // WGS84 geographic (EPSG:4326) needs no transformation
        if (epsgCode.equals("EPSG:4326")) {
            needsTransformation = false;
            return;
        }

        // All projected coordinate systems need transformation
        if (!isGeographic) {
            needsTransformation = true;
            return;
        }

        // CGCS2000 is very similar to WGS84, transformation may not be critical
        // but technically they are different
        if (epsgCode.equals("EPSG:4490")) {
            needsTransformation = true;
            Log.d(TAG, "CGCS2000 detected - transformation recommended but differences are minimal");
            return;
        }

        // Other geographic systems need transformation
        if (epsgCode.startsWith("EPSG:") && !epsgCode.equals("EPSG:4326")) {
            needsTransformation = true;
            return;
        }

        // Unknown systems - assume transformation needed for safety
        if (epsgCode.equals("Unknown")) {
            needsTransformation = false; // Be optimistic for unknown systems
            Log.w(TAG, "Unknown projection - assuming WGS84 compatible");
            return;
        }

        // GCJ-02 and other non-EPSG systems
        if (!epsgCode.startsWith("EPSG:")) {
            needsTransformation = true;
            return;
        }

        needsTransformation = false;
    }

    /**
     * Get a human-readable description of the projection
     * 获取投影的人类可读描述
     *
     * @return Description string
     */
    public String getDescription() {
        StringBuilder desc = new StringBuilder();
        desc.append(projectionName);

        if (!epsgCode.equals("Unknown")) {
            desc.append(" (").append(epsgCode).append(")");
        }

        if (isGeographic) {
            desc.append(" - Geographic");
        } else {
            desc.append(" - Projected");
        }

        return desc.toString();
    }

    // Getters

    /**
     * Get the complete WKT string
     * 获取完整的 WKT 字符串
     */
    public String getWKT() {
        return wkt;
    }

    /**
     * Get the EPSG code (e.g., "EPSG:4326")
     * 获取 EPSG 代码（例如 "EPSG:4326"）
     */
    public String getEPSGCode() {
        return epsgCode != null ? epsgCode : "Unknown";
    }

    /**
     * Get the projection name
     * 获取投影名称
     */
    public String getProjectionName() {
        return projectionName != null ? projectionName : "Unknown";
    }

    /**
     * Check if this is a geographic coordinate system
     * 检查是否为地理坐标系统
     *
     * @return true if geographic (lat/lon), false if projected
     */
    public boolean isGeographic() {
        return isGeographic;
    }

    /**
     * Check if coordinate transformation to WGS84 is needed
     * 检查是否需要转换坐标到 WGS84
     *
     * @return true if transformation recommended
     */
    public boolean needsTransformation() {
        return needsTransformation;
    }
}
