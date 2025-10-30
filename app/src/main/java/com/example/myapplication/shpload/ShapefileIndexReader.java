package com.example.myapplication.shpload;

import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Reader for ESRI Shapefile Index (.shx) format
 *
 * The .shx file provides an index for fast random access to records in the .shp file.
 * Each index record contains the offset and length of the corresponding .shp record.
 *
 * .shx 文件提供了快速随机访问 .shp 文件中记录的索引
 * 每个索引记录包含对应 .shp 记录的偏移量和长度
 */
public class ShapefileIndexReader {

    private static final String TAG = "ShapefileIndexReader";

    // Shapefile constants (same as .shp file)
    private static final int FILE_CODE = 9994;
    private static final int VERSION = 1000;
    private static final int HEADER_SIZE = 100;

    // Parsed data
    private int shapeType;
    private double[] boundingBox = new double[4]; // minX, minY, maxX, maxY
    private List<IndexRecord> indexRecords = new ArrayList<>();

    /**
     * Index record containing offset and length of a shape record
     * 索引记录，包含形状记录的偏移量和长度
     */
    public static class IndexRecord {
        /**
         * Offset of the record in .shp file (in 16-bit words)
         * 记录在 .shp 文件中的偏移量（以 16 位字为单位）
         */
        public int offset;

        /**
         * Length of the record content (in 16-bit words)
         * 记录内容的长度（以 16 位字为单位）
         */
        public int contentLength;

        public IndexRecord(int offset, int contentLength) {
            this.offset = offset;
            this.contentLength = contentLength;
        }

        /**
         * Get offset in bytes
         * 获取字节偏移量
         */
        public int getOffsetBytes() {
            return offset * 2;
        }

        /**
         * Get content length in bytes
         * 获取内容长度（字节）
         */
        public int getContentLengthBytes() {
            return contentLength * 2;
        }

        @Override
        public String toString() {
            return "IndexRecord{offset=" + offset + " words (" + getOffsetBytes() +
                   " bytes), length=" + contentLength + " words (" +
                   getContentLengthBytes() + " bytes)}";
        }
    }

    /**
     * Read and parse a .shx file from input stream
     * 从输入流读取并解析 .shx 文件
     *
     * @param inputStream Input stream of .shx file
     * @throws IOException If reading fails
     */
    public void read(InputStream inputStream) throws IOException {
        // Read entire file into byte array for easier parsing
        byte[] fileData = readAllBytes(inputStream);
        ByteBuffer buffer = ByteBuffer.wrap(fileData);

        // Read file header (100 bytes, same format as .shp)
        readHeader(buffer);

        Log.d(TAG, "Shapefile index header parsed:");
        Log.d(TAG, "  Shape Type: " + getShapeTypeName(shapeType));
        Log.d(TAG, "  Bounding Box: [" + boundingBox[0] + ", " + boundingBox[1] +
                ", " + boundingBox[2] + ", " + boundingBox[3] + "]");

        // Read index records (each record is 8 bytes)
        readIndexRecords(buffer);

        Log.d(TAG, "Loaded " + indexRecords.size() + " index records");

        // Verify index integrity
        verifyIndex();
    }

    /**
     * Read the 100-byte file header
     * 读取 100 字节的文件头
     *
     * @param buffer Byte buffer containing file data
     * @throws IOException If header is invalid
     */
    private void readHeader(ByteBuffer buffer) throws IOException {
        // File code (Big Endian)
        buffer.order(ByteOrder.BIG_ENDIAN);
        int fileCode = buffer.getInt();
        if (fileCode != FILE_CODE) {
            throw new IOException("Invalid shapefile index: wrong file code " + fileCode);
        }

        // Skip unused bytes (5 integers)
        buffer.position(buffer.position() + 20);

        // File length in 16-bit words (Big Endian)
        int fileLength = buffer.getInt();
        Log.d(TAG, "Index file length: " + fileLength + " words (" + (fileLength * 2) + " bytes)");

        // Calculate expected number of records
        // File length includes header (100 bytes = 50 words)
        int dataWords = fileLength - 50;
        int expectedRecords = dataWords / 4; // Each record is 8 bytes = 4 words
        Log.d(TAG, "Expected records: " + expectedRecords);

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
     * Read all index records from the buffer
     * 从缓冲区读取所有索引记录
     *
     * @param buffer Byte buffer positioned after header
     * @throws IOException If reading fails
     */
    private void readIndexRecords(ByteBuffer buffer) throws IOException {
        buffer.order(ByteOrder.BIG_ENDIAN);

        int recordNum = 0;
        while (buffer.remaining() >= 8) { // Each index record is 8 bytes
            // Offset (4 bytes, Big Endian, in 16-bit words)
            int offset = buffer.getInt();

            // Content length (4 bytes, Big Endian, in 16-bit words)
            int contentLength = buffer.getInt();

            // Validate record
            if (offset < 0 || contentLength < 0) {
                Log.w(TAG, "Invalid index record " + recordNum +
                      ": offset=" + offset + ", length=" + contentLength);
                break;
            }

            IndexRecord record = new IndexRecord(offset, contentLength);
            indexRecords.add(record);

            recordNum++;
        }

        if (buffer.remaining() > 0) {
            Log.w(TAG, "Warning: " + buffer.remaining() + " bytes remaining after reading index records");
        }
    }

    /**
     * Verify index integrity by checking for overlaps and gaps
     * 验证索引完整性，检查重叠和间隙
     */
    private void verifyIndex() {
        if (indexRecords.isEmpty()) {
            Log.w(TAG, "Index verification: No records found");
            return;
        }

        Log.d(TAG, "Verifying index integrity...");

        // Check first record starts after header
        IndexRecord firstRecord = indexRecords.get(0);
        if (firstRecord.offset < 50) { // Header is 50 words
            Log.w(TAG, "⚠ First record offset (" + firstRecord.offset +
                  " words) is before end of header (50 words)");
        }

        // Check for overlaps and gaps
        int issueCount = 0;
        for (int i = 0; i < indexRecords.size() - 1; i++) {
            IndexRecord current = indexRecords.get(i);
            IndexRecord next = indexRecords.get(i + 1);

            // Calculate where current record ends (including 8-byte record header)
            // Record header: 4 bytes record number + 4 bytes content length = 4 words
            int currentEnd = current.offset + 4 + current.contentLength;

            if (next.offset < currentEnd) {
                // Overlap detected
                Log.w(TAG, "⚠ Record " + i + " overlaps with record " + (i + 1) +
                      ": current ends at " + currentEnd + ", next starts at " + next.offset);
                issueCount++;
            } else if (next.offset > currentEnd) {
                // Gap detected (may be normal for deleted records)
                int gapWords = next.offset - currentEnd;
                if (gapWords > 1000) { // Only log large gaps
                    Log.d(TAG, "Gap of " + gapWords + " words between records " + i + " and " + (i + 1));
                }
            }
        }

        if (issueCount == 0) {
            Log.d(TAG, "✓ Index verification passed: no overlaps detected");
        } else {
            Log.w(TAG, "⚠ Index verification found " + issueCount + " issues");
        }

        // Log sample records
        Log.d(TAG, "Sample index records:");
        for (int i = 0; i < Math.min(3, indexRecords.size()); i++) {
            Log.d(TAG, "  Record " + i + ": " + indexRecords.get(i));
        }
        if (indexRecords.size() > 3) {
            Log.d(TAG, "  ...");
            int lastIdx = indexRecords.size() - 1;
            Log.d(TAG, "  Record " + lastIdx + ": " + indexRecords.get(lastIdx));
        }
    }

    /**
     * Read all bytes from input stream
     * 从输入流读取所有字节
     *
     * @param inputStream Input stream
     * @return Byte array containing all data
     * @throws IOException If reading fails
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
     * 获取人类可读的形状类型名称
     */
    private String getShapeTypeName(int type) {
        switch (type) {
            case 0: return "Null";
            case 1: return "Point";
            case 3: return "Polyline";
            case 5: return "Polygon";
            case 8: return "Multipoint";
            case 11: return "PointZ";
            case 13: return "PolylineZ";
            case 15: return "PolygonZ";
            case 18: return "MultipointZ";
            case 21: return "PointM";
            case 23: return "PolylineM";
            case 25: return "PolygonM";
            case 28: return "MultipointM";
            case 31: return "MultiPatch";
            default: return "Unknown (" + type + ")";
        }
    }

    // Getters

    /**
     * Get total number of records in the index
     * 获取索引中的记录总数
     */
    public int getRecordCount() {
        return indexRecords.size();
    }

    /**
     * Get a specific index record by index
     * 通过索引获取特定的索引记录
     *
     * @param index Record index (0-based)
     * @return Index record, or null if index is out of bounds
     */
    public IndexRecord getRecord(int index) {
        if (index >= 0 && index < indexRecords.size()) {
            return indexRecords.get(index);
        }
        return null;
    }

    /**
     * Get all index records
     * 获取所有索引记录
     */
    public List<IndexRecord> getAllRecords() {
        return new ArrayList<>(indexRecords);
    }

    /**
     * Get shape type from index file
     * 从索引文件获取形状类型
     */
    public int getShapeType() {
        return shapeType;
    }

    /**
     * Get bounding box from index file
     * 从索引文件获取边界框
     */
    public double[] getBoundingBox() {
        return boundingBox;
    }
}
