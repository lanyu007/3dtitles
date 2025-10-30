package com.example.myapplication.shpload;

import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reader for dBASE (.dbf) format - Shapefile attribute table
 * Supports reading field definitions and record data
 */
public class DBFReader {

    private static final String TAG = "DBFReader";

    // DBF constants
    private static final byte DBF_TERMINATOR = 0x0D;
    private static final byte FIELD_TERMINATOR = 0x0D;

    // Field types
    private static final char TYPE_CHARACTER = 'C';
    private static final char TYPE_NUMBER = 'N';
    private static final char TYPE_LOGICAL = 'L';
    private static final char TYPE_DATE = 'D';
    private static final char TYPE_FLOAT = 'F';

    private int recordCount;
    private int headerLength;
    private int recordLength;
    private List<FieldDescriptor> fields = new ArrayList<>();
    private List<Map<String, Object>> records = new ArrayList<>();

    /**
     * Field descriptor containing field metadata
     */
    public static class FieldDescriptor {
        public String name;
        public char type;
        public int length;
        public int decimalCount;

        public FieldDescriptor(String name, char type, int length, int decimalCount) {
            this.name = name;
            this.type = type;
            this.length = length;
            this.decimalCount = decimalCount;
        }

        @Override
        public String toString() {
            return "Field{" + name + " (" + type + "), length=" + length + "}";
        }
    }

    /**
     * Read and parse a DBF file from input stream
     */
    public void read(InputStream inputStream) throws IOException {
        // Read entire file into byte array
        byte[] fileData = readAllBytes(inputStream);
        ByteBuffer buffer = ByteBuffer.wrap(fileData);
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        // Read file header
        readHeader(buffer);

        Log.d(TAG, "DBF header parsed:");
        Log.d(TAG, "  Record count: " + recordCount);
        Log.d(TAG, "  Header length: " + headerLength);
        Log.d(TAG, "  Record length: " + recordLength);
        Log.d(TAG, "  Fields: " + fields.size());

        // Read field descriptors
        readFields(buffer);

        // Read all records
        readRecords(buffer);

        Log.d(TAG, "Loaded " + records.size() + " records");
    }

    /**
     * Read the 32-byte file header
     */
    private void readHeader(ByteBuffer buffer) throws IOException {
        // Version
        byte version = buffer.get();
        Log.d(TAG, "DBF Version: " + (version & 0xFF));

        // Last update date (YY, MM, DD)
        byte year = buffer.get();
        byte month = buffer.get();
        byte day = buffer.get();
        Log.d(TAG, "Last update: " + (1900 + year) + "-" + month + "-" + day);

        // Number of records
        recordCount = buffer.getInt();

        // Header length
        headerLength = buffer.getShort() & 0xFFFF;

        // Record length
        recordLength = buffer.getShort() & 0xFFFF;

        // Skip reserved bytes (20 bytes)
        buffer.position(32);
    }

    /**
     * Read field descriptors from header
     */
    private void readFields(ByteBuffer buffer) throws IOException {
        // Field descriptors start at byte 32 and end with 0x0D
        while (buffer.position() < headerLength - 1) {
            byte firstByte = buffer.get(buffer.position());

            // Check for field terminator
            if (firstByte == FIELD_TERMINATOR) {
                buffer.get(); // Consume terminator
                break;
            }

            // Read field descriptor (32 bytes)
            FieldDescriptor field = readFieldDescriptor(buffer);
            fields.add(field);
            Log.d(TAG, "  Field: " + field);
        }

        // Ensure we're positioned at the start of records
        buffer.position(headerLength);
    }

    /**
     * Read a single field descriptor (32 bytes)
     */
    private FieldDescriptor readFieldDescriptor(ByteBuffer buffer) throws IOException {
        // Field name (11 bytes, null-terminated)
        byte[] nameBytes = new byte[11];
        buffer.get(nameBytes);
        String name = new String(nameBytes, StandardCharsets.US_ASCII).trim();
        // Remove null terminators
        int nullIndex = name.indexOf('\0');
        if (nullIndex >= 0) {
            name = name.substring(0, nullIndex);
        }

        // Field type (1 byte)
        char type = (char) buffer.get();

        // Skip field data address (4 bytes)
        buffer.position(buffer.position() + 4);

        // Field length (1 byte)
        int length = buffer.get() & 0xFF;

        // Decimal count (1 byte)
        int decimalCount = buffer.get() & 0xFF;

        // Skip reserved bytes (14 bytes)
        buffer.position(buffer.position() + 14);

        return new FieldDescriptor(name, type, length, decimalCount);
    }

    /**
     * Read all data records
     */
    private void readRecords(ByteBuffer buffer) throws IOException {
        for (int i = 0; i < recordCount; i++) {
            // Check for end of file marker
            if (!buffer.hasRemaining()) {
                Log.w(TAG, "Unexpected end of file at record " + i);
                break;
            }

            // Deletion flag (1 byte)
            byte deletionFlag = buffer.get();
            boolean isDeleted = (deletionFlag == '*');

            // Read field values
            Map<String, Object> record = new HashMap<>();
            for (FieldDescriptor field : fields) {
                if (!buffer.hasRemaining()) {
                    Log.w(TAG, "Buffer exhausted reading field " + field.name + " in record " + i);
                    break;
                }

                byte[] valueBytes = new byte[field.length];
                buffer.get(valueBytes);
                Object value = parseFieldValue(valueBytes, field);
                record.put(field.name, value);
            }

            // Only add non-deleted records
            if (!isDeleted && !record.isEmpty()) {
                records.add(record);
            }
        }
    }

    /**
     * Parse a field value based on its type
     */
    private Object parseFieldValue(byte[] valueBytes, FieldDescriptor field) {
        String valueStr = new String(valueBytes, StandardCharsets.US_ASCII).trim();

        // Handle empty values
        if (valueStr.isEmpty()) {
            return null;
        }

        try {
            switch (field.type) {
                case TYPE_CHARACTER:
                    return valueStr;

                case TYPE_NUMBER:
                case TYPE_FLOAT:
                    if (field.decimalCount > 0) {
                        return Double.parseDouble(valueStr);
                    } else {
                        return Long.parseLong(valueStr);
                    }

                case TYPE_LOGICAL:
                    char firstChar = valueStr.charAt(0);
                    return firstChar == 'T' || firstChar == 't' || firstChar == 'Y' || firstChar == 'y';

                case TYPE_DATE:
                    // Format: YYYYMMDD
                    if (valueStr.length() == 8) {
                        return valueStr.substring(0, 4) + "-" +
                               valueStr.substring(4, 6) + "-" +
                               valueStr.substring(6, 8);
                    }
                    return valueStr;

                default:
                    return valueStr;
            }
        } catch (Exception e) {
            Log.w(TAG, "Error parsing field " + field.name + ": " + e.getMessage());
            return valueStr;
        }
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

    // Getters

    public int getRecordCount() {
        return recordCount;
    }

    public List<FieldDescriptor> getFields() {
        return fields;
    }

    public List<Map<String, Object>> getRecords() {
        return records;
    }

    public Map<String, Object> getRecord(int index) {
        if (index >= 0 && index < records.size()) {
            return records.get(index);
        }
        return null;
    }
}
