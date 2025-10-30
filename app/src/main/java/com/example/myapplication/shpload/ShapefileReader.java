package com.example.myapplication.shpload;

import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Reader for ESRI Shapefile (.shp) format
 * Supports reading polygon geometries from binary shapefile format
 */
public class ShapefileReader {

    private static final String TAG = "ShapefileReader";

    // Shapefile constants
    private static final int FILE_CODE = 9994;
    private static final int VERSION = 1000;

    // Shape types
    private static final int SHAPE_NULL = 0;
    private static final int SHAPE_POINT = 1;
    private static final int SHAPE_POLYLINE = 3;
    private static final int SHAPE_POLYGON = 5;
    private static final int SHAPE_MULTIPOINT = 8;

    // File header size
    private static final int HEADER_SIZE = 100;

    private int shapeType;
    private double[] boundingBox = new double[4]; // minX, minY, maxX, maxY
    private List<PolygonRecord> polygonRecords = new ArrayList<>();

    /**
     * Polygon record containing geometry data
     */
    public static class PolygonRecord {
        public double minX, minY, maxX, maxY;
        public List<List<Point>> parts; // List of rings (outer + holes)

        public PolygonRecord() {
            parts = new ArrayList<>();
        }
    }

    /**
     * Simple point class for coordinates
     */
    public static class Point {
        public double x; // Longitude
        public double y; // Latitude

        public Point(double x, double y) {
            this.x = x;
            this.y = y;
        }
    }

    /**
     * Read and parse a shapefile from input stream
     */
    public void read(InputStream inputStream) throws IOException {
        // Read entire file into byte array for easier parsing
        byte[] fileData = readAllBytes(inputStream);
        ByteBuffer buffer = ByteBuffer.wrap(fileData);

        // Read file header
        readHeader(buffer);

        Log.d(TAG, "Shapefile header parsed:");
        Log.d(TAG, "  Shape Type: " + getShapeTypeName(shapeType));
        Log.d(TAG, "  Bounding Box: [" + boundingBox[0] + ", " + boundingBox[1] +
                ", " + boundingBox[2] + ", " + boundingBox[3] + "]");

        // Read all records
        int recordCount = 0;
        while (buffer.remaining() > 8) { // Need at least record header
            try {
                readRecord(buffer);
                recordCount++;
            } catch (Exception e) {
                Log.w(TAG, "Error reading record " + recordCount + ": " + e.getMessage());
                break;
            }
        }

        Log.d(TAG, "Loaded " + polygonRecords.size() + " polygon records");
    }

    /**
     * Read the 100-byte file header
     */
    private void readHeader(ByteBuffer buffer) throws IOException {
        // File code (Big Endian)
        buffer.order(ByteOrder.BIG_ENDIAN);
        int fileCode = buffer.getInt();
        if (fileCode != FILE_CODE) {
            throw new IOException("Invalid shapefile: wrong file code " + fileCode);
        }

        // Skip unused bytes (5 integers)
        buffer.position(buffer.position() + 20);

        // File length in 16-bit words (Big Endian)
        int fileLength = buffer.getInt();
        Log.d(TAG, "File length: " + fileLength + " words (" + (fileLength * 2) + " bytes)");

        // Version (Little Endian)
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        int version = buffer.getInt();
        if (version != VERSION) {
            Log.w(TAG, "Unexpected version: " + version);
        }

        // Shape type
        shapeType = buffer.getInt();

        // Bounding box (8 doubles, Little Endian)
        boundingBox[0] = buffer.getDouble(); // minX
        boundingBox[1] = buffer.getDouble(); // minY
        boundingBox[2] = buffer.getDouble(); // maxX
        boundingBox[3] = buffer.getDouble(); // maxY

        // Skip Z and M ranges (4 doubles)
        buffer.position(HEADER_SIZE);
    }

    /**
     * Read a single record from the buffer
     */
    private void readRecord(ByteBuffer buffer) throws IOException {
        // Record header (Big Endian)
        buffer.order(ByteOrder.BIG_ENDIAN);
        int recordNumber = buffer.getInt();
        int contentLength = buffer.getInt(); // Length in 16-bit words

        // Record content (Little Endian)
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        int recordShapeType = buffer.getInt();

        // Handle null shapes
        if (recordShapeType == SHAPE_NULL) {
            Log.d(TAG, "Skipping null shape in record " + recordNumber);
            return;
        }

        // Only handle polygon shapes
        if (recordShapeType == SHAPE_POLYGON) {
            PolygonRecord polygon = readPolygon(buffer);
            polygonRecords.add(polygon);
        } else {
            // Skip unsupported geometry types
            Log.w(TAG, "Skipping unsupported shape type " + recordShapeType +
                    " in record " + recordNumber);
            // Skip remaining content
            int bytesToSkip = (contentLength * 2) - 4; // Already read shape type
            buffer.position(buffer.position() + bytesToSkip);
        }
    }

    /**
     * Read a polygon geometry from the buffer
     */
    private PolygonRecord readPolygon(ByteBuffer buffer) throws IOException {
        PolygonRecord polygon = new PolygonRecord();

        // Bounding box
        polygon.minX = buffer.getDouble();
        polygon.minY = buffer.getDouble();
        polygon.maxX = buffer.getDouble();
        polygon.maxY = buffer.getDouble();

        // Number of parts (rings)
        int numParts = buffer.getInt();

        // Number of points
        int numPoints = buffer.getInt();

        // Parts array - starting index of each ring
        int[] parts = new int[numParts];
        for (int i = 0; i < numParts; i++) {
            parts[i] = buffer.getInt();
        }

        // Points array - all points for all rings
        Point[] points = new Point[numPoints];
        for (int i = 0; i < numPoints; i++) {
            double x = buffer.getDouble();
            double y = buffer.getDouble();
            points[i] = new Point(x, y);
        }

        // Split points into parts
        for (int i = 0; i < numParts; i++) {
            int startIndex = parts[i];
            int endIndex = (i < numParts - 1) ? parts[i + 1] : numPoints;

            List<Point> ring = new ArrayList<>();
            for (int j = startIndex; j < endIndex; j++) {
                ring.add(points[j]);
            }
            polygon.parts.add(ring);
        }

        return polygon;
    }

    /**
     * Read all bytes from input stream
     */
    private byte[] readAllBytes(InputStream inputStream) throws IOException {
        List<byte[]> chunks = new ArrayList<>();
        int totalSize = 0;
        byte[] buffer = new byte[8192];
        int bytesRead;

        while ((bytesRead = inputStream.read(buffer)) != -1) {
            byte[] chunk = new byte[bytesRead];
            System.arraycopy(buffer, 0, chunk, 0, bytesRead);
            chunks.add(chunk);
            totalSize += bytesRead;
        }

        // Combine all chunks
        byte[] result = new byte[totalSize];
        int offset = 0;
        for (byte[] chunk : chunks) {
            System.arraycopy(chunk, 0, result, offset, chunk.length);
            offset += chunk.length;
        }

        return result;
    }

    /**
     * Get human-readable shape type name
     */
    private String getShapeTypeName(int type) {
        switch (type) {
            case SHAPE_NULL: return "Null";
            case SHAPE_POINT: return "Point";
            case SHAPE_POLYLINE: return "Polyline";
            case SHAPE_POLYGON: return "Polygon";
            case SHAPE_MULTIPOINT: return "Multipoint";
            default: return "Unknown (" + type + ")";
        }
    }

    // Getters

    public int getShapeType() {
        return shapeType;
    }

    public double[] getBoundingBox() {
        return boundingBox;
    }

    public List<PolygonRecord> getPolygonRecords() {
        return polygonRecords;
    }

    public int getRecordCount() {
        return polygonRecords.size();
    }
}
