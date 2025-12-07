package com.example.myapplication.lasload;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.myapplication.R;
import com.example.myapplication.exampledemo.worldwindx.experimental.AtmosphereLayer;
import com.example.myapplication.shpload.DBFReader;
import com.example.myapplication.shpload.ShapefileReader;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import gov.nasa.worldwind.WorldWind;
import gov.nasa.worldwind.WorldWindow;
import gov.nasa.worldwind.geom.Position;
import gov.nasa.worldwind.globe.BasicElevationCoverage;
import gov.nasa.worldwind.layer.BackgroundLayer;
import gov.nasa.worldwind.layer.BlueMarbleLandsatLayer;
import gov.nasa.worldwind.layer.RenderableLayer;
import gov.nasa.worldwind.shape.Placemark;
import gov.nasa.worldwind.shape.PlacemarkAttributes;

/**
 * Activity for loading and displaying PointZ point cloud data from Shapefile
 * Visualizes each point as a small 3D sphere similar to building visualization
 */
public class LoadLASActivity extends AppCompatActivity {

    public static void start(Context context) {
        Intent intent = new Intent(context, LoadLASActivity.class);
        context.startActivity(intent);
    }

    private static final String TAG = "LoadLASActivity";

    // File paths - adjust these to your actual PointZ shapefile
    private static final String SHAPEFILE_PATH = "shp/cs.shp";
    private static final String DBF_PATH = "shp/cs.dbf";

    private WorldWindow wwd;
    private RenderableLayer pointCloudLayer;
    private TextView statusText;
    private TextView pointInfoText;
    private Handler mainHandler;
    private ExecutorService executorService;

    // Point cloud statistics
    private int totalPoints = 0;
    private double minZ = Double.MAX_VALUE;
    private double maxZ = Double.MIN_VALUE;

    // Bounding box for camera positioning
    private double minLat = Double.MAX_VALUE;
    private double maxLat = Double.MIN_VALUE;
    private double minLon = Double.MAX_VALUE;
    private double maxLon = Double.MIN_VALUE;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_load_lasactivity);

        // Initialize handler for UI updates
        mainHandler = new Handler(Looper.getMainLooper());

        // Initialize executor service for background loading
        executorService = Executors.newSingleThreadExecutor();

        // Get status text views
        statusText = findViewById(R.id.status_text);
        pointInfoText = findViewById(R.id.point_info_text);

        // Create the WorldWindow (a GLSurfaceView) which displays the globe
        wwd = new WorldWindow(this);

        // Add the WorldWindow view object to the layout
        FrameLayout globeLayout = findViewById(R.id.globe);
        globeLayout.addView(wwd);

        // Setup the WorldWindow's layers
        initializeWorldWindow();

        // Load shapefile in background
        loadPointCloudShapefile();
    }

    /**
     * Initialize WorldWindow with base layers and point cloud layer
     */
    private void initializeWorldWindow() {
        Log.d(TAG, "Initializing WorldWindow");

        // Add base layers
        wwd.getLayers().addLayer(new BackgroundLayer());
        wwd.getLayers().addLayer(new BlueMarbleLandsatLayer());
        wwd.getLayers().addLayer(new AtmosphereLayer());

        // Setup elevation model
        wwd.getGlobe().getElevationModel().addCoverage(new BasicElevationCoverage());

        // Create layer for point cloud content
        pointCloudLayer = new RenderableLayer();
        pointCloudLayer.setDisplayName("Point Cloud Layer");
        wwd.getLayers().addLayer(pointCloudLayer);

        Log.d(TAG, "WorldWindow initialized");
    }

    /**
     * Load PointZ shapefile data in background thread
     */
    private void loadPointCloudShapefile() {
        executorService.execute(() -> {
            try {
                Log.d(TAG, "Starting PointZ shapefile loading...");

                // Step 1: Load geometry from .shp file
                List<ShapefileReader.PointZRecord> pointZRecords = loadShapefileGeometry();

                if (pointZRecords == null || pointZRecords.isEmpty()) {
                    showError("No PointZ data found in shapefile");
                    return;
                }

                Log.d(TAG, "Loaded " + pointZRecords.size() + " PointZ records");

                // Step 2: Load attributes from .dbf file (optional)
                Map<Integer, Map<String, Object>> attributeMap = loadDBFAttributes();

                // Step 3: Create 3D point cloud visualization
                createPointCloud(pointZRecords, attributeMap);

                // Step 4: Position camera to view all points
                positionCamera();

                // Step 5: Show completion message
                updateStatusText("Point cloud loaded successfully!");
                updatePointInfoText("Points: " + totalPoints +
                        " | Z range: " + String.format("%.2f", minZ) + " - " + String.format("%.2f", maxZ) + "m");

            } catch (Exception e) {
                Log.e(TAG, "Error loading PointZ shapefile", e);
                updateStatusText("Error: " + e.getMessage());
                showError("Failed to load point cloud: " + e.getMessage());
            }
        });
    }

    /**
     * Load PointZ geometry from .shp file
     */
    private List<ShapefileReader.PointZRecord> loadShapefileGeometry() {
        try {
            InputStream shpStream = getAssets().open(SHAPEFILE_PATH);
            ShapefileReader reader = new ShapefileReader();
            reader.read(shpStream);
            shpStream.close();

            return reader.getPointZRecords();

        } catch (Exception e) {
            Log.e(TAG, "Error loading shapefile geometry", e);
            return null;
        }
    }

    /**
     * Load attributes from .dbf file
     */
    private Map<Integer, Map<String, Object>> loadDBFAttributes() {
        try {
            InputStream dbfStream = getAssets().open(DBF_PATH);
            DBFReader dbfReader = new DBFReader();
            dbfReader.read(dbfStream);
            dbfStream.close();

            // Convert list of records to map indexed by record number
            List<Map<String, Object>> records = dbfReader.getRecords();
            Map<Integer, Map<String, Object>> attributeMap = new HashMap<>();
            for (int i = 0; i < records.size(); i++) {
                attributeMap.put(i, records.get(i));
            }

            Log.d(TAG, "Loaded DBF attributes: " + attributeMap.size() + " records");
            return attributeMap;

        } catch (Exception e) {
            Log.w(TAG, "DBF file not found or error reading, continuing without attributes", e);
            return null;
        }
    }

    /**
     * Create 3D point cloud visualization from PointZ records
     * Each point is rendered as a small placemark with color based on Z value
     */
    private void createPointCloud(List<ShapefileReader.PointZRecord> pointZRecords,
                                   Map<Integer, Map<String, Object>> attributeMap) {

        totalPoints = pointZRecords.size();

        // First pass: find Z range for color mapping
        for (ShapefileReader.PointZRecord point : pointZRecords) {
            if (point.z < minZ) minZ = point.z;
            if (point.z > maxZ) maxZ = point.z;
        }

        Log.d(TAG, "Z value range: " + minZ + " to " + maxZ);

        // Create placemarks for points in batches
        int batchSize = 100;
        List<Placemark> batch = new ArrayList<>();

        for (int i = 0; i < pointZRecords.size(); i++) {
            ShapefileReader.PointZRecord point = pointZRecords.get(i);

            // Update bounding box
            if (point.y < minLat) minLat = point.y;
            if (point.y > maxLat) maxLat = point.y;
            if (point.x < minLon) minLon = point.x;
            if (point.x > maxLon) maxLon = point.x;

            // Create position with Z value as altitude
            Position position = Position.fromDegrees(point.y, point.x, point.z);

            // Get attributes for this point (if available)
            Map<String, Object> attributes = (attributeMap != null) ? attributeMap.get(i) : null;

            // Create placemark with color based on Z value
            Placemark placemark = createPointPlacemark(position, point.z, attributes);
            batch.add(placemark);

            // Add batch to layer when full
            if (batch.size() >= batchSize) {
                addBatchToLayer(batch);
                batch.clear();

                // Show progress every 1000 points
                if ((i + 1) % 1000 == 0) {
                    final int progress = i + 1;
                    updateStatusText("Loading: " + progress + "/" + totalPoints + " points");
                }
            }
        }

        // Add remaining points
        if (!batch.isEmpty()) {
            addBatchToLayer(batch);
        }

        Log.d(TAG, "Created " + totalPoints + " point placemarks");
    }

    /**
     * Create a placemark for a single point with color based on Z value
     */
    private Placemark createPointPlacemark(Position position, double zValue,
                                           Map<String, Object> attributes) {

        // Get color based on Z value (height)
        gov.nasa.worldwind.render.Color color = getColorByHeight(zValue);

        // Create placemark attributes
        PlacemarkAttributes attrs = new PlacemarkAttributes();
        attrs.setImageColor(color);
        attrs.setImageScale(0.3);  // Small points for dense point cloud

        // Create placemark
        String displayName = "Point Z=" + String.format("%.2f", zValue);
        if (attributes != null && attributes.containsKey("CLASS")) {
            displayName += " Class=" + attributes.get("CLASS");
        }

        Placemark placemark = new Placemark(position, attrs, displayName);
        placemark.setAltitudeMode(WorldWind.ABSOLUTE);  // Use absolute altitude

        return placemark;
    }

    /**
     * Get color based on Z value (height)
     * Maps Z values to a color gradient: blue (low) -> green -> yellow -> orange -> red (high)
     */
    private gov.nasa.worldwind.render.Color getColorByHeight(double z) {
        // Normalize Z value to 0-1 range
        double normalizedZ = (z - minZ) / (maxZ - minZ);

        // Clamp to 0-1
        normalizedZ = Math.max(0, Math.min(1, normalizedZ));

        float r, g, b;

        if (normalizedZ < 0.25) {
            // Blue to Cyan (0.0 - 0.25)
            float t = (float)(normalizedZ / 0.25);
            r = 0;
            g = t;
            b = 1;
        } else if (normalizedZ < 0.5) {
            // Cyan to Green (0.25 - 0.5)
            float t = (float)((normalizedZ - 0.25) / 0.25);
            r = 0;
            g = 1;
            b = 1 - t;
        } else if (normalizedZ < 0.75) {
            // Green to Yellow (0.5 - 0.75)
            float t = (float)((normalizedZ - 0.5) / 0.25);
            r = t;
            g = 1;
            b = 0;
        } else {
            // Yellow to Red (0.75 - 1.0)
            float t = (float)((normalizedZ - 0.75) / 0.25);
            r = 1;
            g = 1 - t;
            b = 0;
        }

        return new gov.nasa.worldwind.render.Color(r, g, b, 1.0f);
    }

    /**
     * Add a batch of placemarks to the layer on UI thread
     */
    private void addBatchToLayer(List<Placemark> batch) {
        List<Placemark> batchCopy = new ArrayList<>(batch);
        mainHandler.post(() -> {
            for (Placemark placemark : batchCopy) {
                pointCloudLayer.addRenderable(placemark);
            }
            wwd.requestRedraw();
        });
    }

    /**
     * Position camera to view all points
     */
    private void positionCamera() {
        mainHandler.post(() -> {
            if (minLat == Double.MAX_VALUE || maxLat == Double.MIN_VALUE) {
                Log.w(TAG, "Invalid bounding box, cannot position camera");
                return;
            }

            // Calculate center point
            double centerLat = (minLat + maxLat) / 2.0;
            double centerLon = (minLon + maxLon) / 2.0;
            double centerAlt = (minZ + maxZ) / 2.0;

            // Calculate appropriate altitude for viewing
            double latRange = maxLat - minLat;
            double lonRange = maxLon - minLon;
            double maxRange = Math.max(latRange, lonRange);

            // Set altitude based on point cloud extent
            // Use a multiplier to ensure all points are visible
            double viewAltitude = centerAlt + (maxRange * 111000 * 2);  // 111km per degree, *2 for good view
            viewAltitude = Math.max(viewAltitude, 1000);  // Minimum 1000m altitude

            Position lookAtPosition = Position.fromDegrees(centerLat, centerLon, viewAltitude);

            wwd.getNavigator().setLatitude(lookAtPosition.latitude);
            wwd.getNavigator().setLongitude(lookAtPosition.longitude);
            wwd.getNavigator().setAltitude(lookAtPosition.altitude);

            Log.d(TAG, "Camera positioned at: " + centerLat + ", " + centerLon +
                    " altitude: " + viewAltitude + "m");

            wwd.requestRedraw();
        });
    }

    /**
     * Update status text on UI thread
     */
    private void updateStatusText(String message) {
        mainHandler.post(() -> {
            if (statusText != null) {
                statusText.setText(message);
            }
        });
    }

    /**
     * Update point info text on UI thread
     */
    private void updatePointInfoText(String message) {
        mainHandler.post(() -> {
            if (pointInfoText != null) {
                pointInfoText.setText(message);
            }
        });
    }

    /**
     * Show error message on UI thread
     */
    private void showError(String message) {
        mainHandler.post(() ->
            Toast.makeText(this, "Error: " + message, Toast.LENGTH_LONG).show()
        );
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (wwd != null) {
            wwd.onPause();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (wwd != null) {
            wwd.onResume();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        // Shutdown executor service
        if (executorService != null) {
            executorService.shutdown();
        }

        // Remove handler callbacks
        if (mainHandler != null) {
            mainHandler.removeCallbacksAndMessages(null);
        }
    }
}
