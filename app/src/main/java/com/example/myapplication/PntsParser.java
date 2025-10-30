package com.example.myapplication;

import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Parser for Cesium 3D Tiles Point Cloud (.pnts) binary format
 * Specification: https://github.com/CesiumGS/3d-tiles/tree/main/specification/TileFormats/PointCloud
 *
 * Format structure:
 * - 28 byte header
 * - Feature Table (JSON + binary data)
 * - Batch Table (optional)
 */
public class PntsParser {
    private static final String TAG = "PntsParser";

    // Magic number for PNTS files: "pnts"
    private static final int PNTS_MAGIC = 0x73746e70;

    // Point cloud data
    private float[] positions;      // XYZ positions
    private byte[] colors;          // RGB or RGBA colors
    private float[] normals;        // Normal vectors (optional)
    private int pointCount;

    /**
     * Parse a .pnts file from an InputStream
     * @param inputStream The input stream containing the .pnts file data
     * @return true if parsing was successful
     */
    public boolean parse(InputStream inputStream) {
        try {
            // Read entire file into byte array
            byte[] data = readAllBytes(inputStream);
            ByteBuffer buffer = ByteBuffer.wrap(data);
            buffer.order(ByteOrder.LITTLE_ENDIAN);

            // Parse header (28 bytes)
            int magic = buffer.getInt();
            if (magic != PNTS_MAGIC) {
                Log.e(TAG, "Invalid PNTS magic number: " + Integer.toHexString(magic));
                return false;
            }

            int version = buffer.getInt();
            int byteLength = buffer.getInt();
            int featureTableJsonByteLength = buffer.getInt();
            int featureTableBinaryByteLength = buffer.getInt();
            int batchTableJsonByteLength = buffer.getInt();
            int batchTableBinaryByteLength = buffer.getInt();

            Log.d(TAG, "PNTS version: " + version);
            Log.d(TAG, "Total byte length: " + byteLength);
            Log.d(TAG, "Feature table JSON length: " + featureTableJsonByteLength);
            Log.d(TAG, "Feature table binary length: " + featureTableBinaryByteLength);

            // Parse Feature Table JSON
            byte[] featureTableJsonBytes = new byte[featureTableJsonByteLength];
            buffer.get(featureTableJsonBytes);
            String featureTableJson = new String(featureTableJsonBytes).trim();
            Log.d(TAG, "Feature Table JSON: " + featureTableJson);

            // Parse feature table to get metadata
            parseFeatureTableJson(featureTableJson);

            // The binary data starts after JSON
            int binaryStartPosition = buffer.position();

            // Parse positions (required)
            parsePositions(buffer, binaryStartPosition, featureTableJson);

            // Parse colors (optional but common)
            parseColors(buffer, binaryStartPosition, featureTableJson);

            // Parse normals (optional)
            parseNormals(buffer, binaryStartPosition, featureTableJson);

            Log.d(TAG, "Successfully parsed " + pointCount + " points");
            return true;

        } catch (Exception e) {
            Log.e(TAG, "Error parsing PNTS file", e);
            return false;
        }
    }

    /**
     * Parse feature table JSON to extract point count and metadata
     */
    private void parseFeatureTableJson(String json) {
        // Simple JSON parsing for POINTS_LENGTH
        // Format: {"POINTS_LENGTH":12345,...}
        int pointsLengthIndex = json.indexOf("\"POINTS_LENGTH\"");
        if (pointsLengthIndex != -1) {
            int colonIndex = json.indexOf(":", pointsLengthIndex);
            int commaIndex = json.indexOf(",", colonIndex);
            int braceIndex = json.indexOf("}", colonIndex);
            int endIndex = (commaIndex != -1 && commaIndex < braceIndex) ? commaIndex : braceIndex;

            String pointCountStr = json.substring(colonIndex + 1, endIndex).trim();
            pointCount = Integer.parseInt(pointCountStr);
            Log.d(TAG, "Point count: " + pointCount);
        }
    }

    /**
     * Parse position data from feature table
     */
    private void parsePositions(ByteBuffer buffer, int binaryStart, String json) {
        // Look for POSITION or POSITION_QUANTIZED in JSON
        int positionOffset = extractByteOffset(json, "POSITION");

        if (positionOffset >= 0) {
            positions = new float[pointCount * 3];
            buffer.position(binaryStart + positionOffset);

            for (int i = 0; i < pointCount * 3; i++) {
                positions[i] = buffer.getFloat();
            }
            Log.d(TAG, "Parsed " + pointCount + " positions");
        } else {
            Log.w(TAG, "No POSITION data found in feature table");
        }
    }

    /**
     * Parse color data from feature table
     */
    private void parseColors(ByteBuffer buffer, int binaryStart, String json) {
        // Look for RGB or RGBA
        int rgbOffset = extractByteOffset(json, "RGB");
        int rgbaOffset = extractByteOffset(json, "RGBA");

        if (rgbaOffset >= 0) {
            // RGBA format (4 bytes per point)
            colors = new byte[pointCount * 4];
            buffer.position(binaryStart + rgbaOffset);
            buffer.get(colors);
            Log.d(TAG, "Parsed RGBA colors");
        } else if (rgbOffset >= 0) {
            // RGB format (3 bytes per point)
            byte[] rgbColors = new byte[pointCount * 3];
            buffer.position(binaryStart + rgbOffset);
            buffer.get(rgbColors);

            // Convert RGB to RGBA
            colors = new byte[pointCount * 4];
            for (int i = 0; i < pointCount; i++) {
                colors[i * 4] = rgbColors[i * 3];         // R
                colors[i * 4 + 1] = rgbColors[i * 3 + 1]; // G
                colors[i * 4 + 2] = rgbColors[i * 3 + 2]; // B
                colors[i * 4 + 3] = (byte) 255;           // A
            }
            Log.d(TAG, "Parsed RGB colors");
        } else {
            // Default white color if no color data
            colors = new byte[pointCount * 4];
            for (int i = 0; i < pointCount * 4; i += 4) {
                colors[i] = (byte) 255;     // R
                colors[i + 1] = (byte) 255; // G
                colors[i + 2] = (byte) 255; // B
                colors[i + 3] = (byte) 255; // A
            }
            Log.d(TAG, "No color data, using default white");
        }
    }

    /**
     * Parse normal data from feature table
     */
    private void parseNormals(ByteBuffer buffer, int binaryStart, String json) {
        int normalOffset = extractByteOffset(json, "NORMAL");

        if (normalOffset >= 0) {
            normals = new float[pointCount * 3];
            buffer.position(binaryStart + normalOffset);

            for (int i = 0; i < pointCount * 3; i++) {
                normals[i] = buffer.getFloat();
            }
            Log.d(TAG, "Parsed normals");
        }
    }

    /**
     * Extract byte offset from JSON for a given property
     * Returns -1 if not found
     */
    private int extractByteOffset(String json, String propertyName) {
        String searchKey = "\"" + propertyName + "\"";
        int index = json.indexOf(searchKey);

        if (index == -1) {
            return -1;
        }

        // Find the byteOffset value
        int byteOffsetIndex = json.indexOf("\"byteOffset\"", index);
        if (byteOffsetIndex == -1 || byteOffsetIndex > json.indexOf("}", index)) {
            // If no byteOffset specified, it starts at offset 0
            return 0;
        }

        int colonIndex = json.indexOf(":", byteOffsetIndex);
        int commaIndex = json.indexOf(",", colonIndex);
        int braceIndex = json.indexOf("}", colonIndex);
        int endIndex = (commaIndex != -1 && commaIndex < braceIndex) ? commaIndex : braceIndex;

        String offsetStr = json.substring(colonIndex + 1, endIndex).trim();
        return Integer.parseInt(offsetStr);
    }

    /**
     * Read all bytes from an InputStream
     */
    private byte[] readAllBytes(InputStream inputStream) throws IOException {
        byte[] buffer = new byte[8192];
        int bytesRead;
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();

        while ((bytesRead = inputStream.read(buffer)) != -1) {
            output.write(buffer, 0, bytesRead);
        }

        return output.toByteArray();
    }

    // Getters
    public float[] getPositions() {
        return positions;
    }

    public byte[] getColors() {
        return colors;
    }

    public float[] getNormals() {
        return normals;
    }

    public int getPointCount() {
        return pointCount;
    }
}