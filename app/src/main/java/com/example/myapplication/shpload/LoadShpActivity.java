package com.example.myapplication.shpload;

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

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import gov.nasa.worldwind.WorldWindow;
import gov.nasa.worldwind.geom.Position;
import gov.nasa.worldwind.globe.BasicElevationCoverage;
import gov.nasa.worldwind.layer.BackgroundLayer;
import gov.nasa.worldwind.layer.BlueMarbleLandsatLayer;
import gov.nasa.worldwind.layer.RenderableLayer;
import gov.nasa.worldwind.render.Color;
import gov.nasa.worldwind.shape.Polygon;
import gov.nasa.worldwind.shape.ShapeAttributes;

/**
 * Activity for loading and displaying ESRI Shapefiles using WorldWindow
 *
 * This activity demonstrates:
 * - Loading Shapefile geometry (.shp) and attributes (.dbf) from assets
 * - Parsing binary Shapefile format
 * - Creating polygon renderables from Shapefile data
 * - Intelligent camera positioning based on data bounds
 * - Asynchronous loading with progress updates
 */
public class LoadShpActivity extends AppCompatActivity {

    public static void start(Context context) {
        Intent intent = new Intent(context, LoadShpActivity.class);
        context.startActivity(intent);
    }

    private static final String TAG = "LoadShpActivity";

    // Shapefile paths in assets
    private static final String SHAPEFILE_PATH = "data/buildings.shp";
    private static final String DBF_PATH = "data/buildings.dbf";

    // WorldWind components
    protected WorldWindow wwd;
    protected TextView statusText;
    protected RenderableLayer shapefileLayer;
    private ExecutorService executorService;
    private Handler mainHandler;

    // Track bounding box of all loaded geometry
    private double minLat = Double.MAX_VALUE;
    private double maxLat = -Double.MAX_VALUE;
    private double minLon = Double.MAX_VALUE;
    private double maxLon = -Double.MAX_VALUE;

    // Statistics
    private int totalPolygons = 0;
    private int loadedPolygons = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_load_shp);

        // Initialize executor service and handler
        executorService = Executors.newSingleThreadExecutor();
        mainHandler = new Handler(Looper.getMainLooper());

        // Get status text view
        statusText = findViewById(R.id.status_text);

        // Create the WorldWindow (a GLSurfaceView) which displays the globe
        wwd = new WorldWindow(this);

        // Add the WorldWindow view object to the layout
        FrameLayout globeLayout = findViewById(R.id.globe);
        globeLayout.addView(wwd);

        // Setup the WorldWindow's layers
        initializeWorldWindow();

        // Load Shapefile
        loadShapefile();
    }

    /**
     * Initialize WorldWindow with base layers and Shapefile layer
     */
    private void initializeWorldWindow() {
        Log.d(TAG, "Initializing WorldWindow");

        // Add base layers
        wwd.getLayers().addLayer(new BackgroundLayer());
        wwd.getLayers().addLayer(new BlueMarbleLandsatLayer());
        wwd.getLayers().addLayer(new AtmosphereLayer());

        // Setup elevation model
        wwd.getGlobe().getElevationModel().addCoverage(new BasicElevationCoverage());

        // Create layer for Shapefile content
        shapefileLayer = new RenderableLayer("Shapefile Layer");
        wwd.getLayers().addLayer(shapefileLayer);

        Log.d(TAG, "WorldWindow initialized");
    }

    /**
     * Load and parse the Shapefile asynchronously
     */
    private void loadShapefile() {
        updateStatus("Loading Shapefile...");

        executorService.execute(() -> {
            ShapefileReader shpReader = new ShapefileReader();
            DBFReader dbfReader = null;

            try {
                // Step 1: Load and parse .shp file
                updateStatus("Reading geometry file (.shp)...");
                InputStream shpStream = getAssets().open(SHAPEFILE_PATH);
                shpReader.read(shpStream);
                shpStream.close();

                totalPolygons = shpReader.getRecordCount();
                Log.d(TAG, "Shapefile loaded: " + totalPolygons + " polygons");
                Log.d(TAG, "Shape type: " + shpReader.getShapeType());
                double[] bbox = shpReader.getBoundingBox();
                Log.d(TAG, "Bounding box: [" + bbox[0] + ", " + bbox[1] +
                        ", " + bbox[2] + ", " + bbox[3] + "]");

                // Step 2: Load and parse .dbf file (attributes)
                updateStatus("Reading attributes file (.dbf)...");
                try {
                    InputStream dbfStream = getAssets().open(DBF_PATH);
                    dbfReader = new DBFReader();
                    dbfReader.read(dbfStream);
                    dbfStream.close();
                    Log.d(TAG, "DBF loaded: " + dbfReader.getRecordCount() + " records");
                } catch (Exception e) {
                    Log.w(TAG, "Could not load DBF file (attributes will not be available): " +
                            e.getMessage());
                }

                // Step 3: Create polygon renderables
                updateStatus("Creating polygon objects (0/" + totalPolygons + ")...");
                List<ShapefileReader.PolygonRecord> polygons = shpReader.getPolygonRecords();
                final DBFReader finalDbfReader = dbfReader;

                // Process polygons in batches for better performance
                int batchSize = 50;
                for (int i = 0; i < polygons.size(); i += batchSize) {
                    int endIndex = Math.min(i + batchSize, polygons.size());
                    List<Polygon> batchPolygons = new ArrayList<>();

                    // Create batch of polygons
                    for (int j = i; j < endIndex; j++) {
                        ShapefileReader.PolygonRecord record = polygons.get(j);

                        // Get attributes if available
                        Map<String, Object> attributes = null;
                        if (finalDbfReader != null && j < finalDbfReader.getRecordCount()) {
                            attributes = finalDbfReader.getRecord(j);
                        }

                        // Create polygon with first part (outer ring)
                        // Note: Additional parts would be holes, which can be handled separately
                        if (!record.parts.isEmpty()) {
                            Polygon polygon = createPolygonFromShapefile(record, attributes);
                            if (polygon != null) {
                                batchPolygons.add(polygon);
                                loadedPolygons++;
                            }
                        }
                    }

                    // Add batch to layer on main thread
                    final int currentCount = loadedPolygons;
                    mainHandler.post(() -> {
                        for (Polygon polygon : batchPolygons) {
                            shapefileLayer.addRenderable(polygon);
                        }
                        updateStatus("Loading polygons (" + currentCount + "/" + totalPolygons + ")...");
                        wwd.requestRedraw();
                    });

                    // Brief pause to allow UI updates
                    Thread.sleep(10);
                }

                // Step 4: Position camera to view the data
                mainHandler.post(() -> {
                    positionCamera();
                    updateStatus("Shapefile loaded: " + loadedPolygons + " buildings");
                    wwd.requestRedraw();
                    Log.d(TAG, "Shapefile loading complete");
                });

            } catch (Exception e) {
                Log.e(TAG, "Error loading Shapefile", e);
                mainHandler.post(() -> {
                    updateStatus("Error loading Shapefile: " + e.getMessage());
                    Toast.makeText(this, "Failed to load Shapefile: " + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    /**
     * Create a WorldWind Polygon from a Shapefile polygon record
     *
     * @param record Shapefile polygon record containing geometry
     * @param attributes Optional attribute data from DBF file
     * @return Polygon renderable object, or null if creation fails
     */
    private Polygon createPolygonFromShapefile(ShapefileReader.PolygonRecord record,
                                               Map<String, Object> attributes) {
        try {
            // Use the first part (outer ring)
            List<ShapefileReader.Point> ring = record.parts.get(0);

            // Convert Shapefile points to WorldWind positions
            List<Position> positions = new ArrayList<>();
            for (ShapefileReader.Point point : ring) {
                // Shapefile coordinates are (longitude, latitude)
                positions.add(Position.fromDegrees(point.y, point.x, 0));

                // Update bounding box
                updateBoundingBox(point.y, point.x);
            }

            // Ensure polygon is closed
            if (positions.size() > 0) {
                Position first = positions.get(0);
                Position last = positions.get(positions.size() - 1);
                if (first.latitude != last.latitude || first.longitude != last.longitude) {
                    positions.add(first);
                }
            }

            // Create polygon with building-appropriate styling
            ShapeAttributes attrs = new ShapeAttributes();
            attrs.setInteriorColor(new Color(0.2f, 0.5f, 0.8f, 0.6f)); // Semi-transparent blue
            attrs.setOutlineColor(new Color(0.1f, 0.3f, 0.6f, 1.0f)); // Dark blue
            attrs.setOutlineWidth(2f);
            attrs.setDrawInterior(true);
            attrs.setDrawOutline(true);

            Polygon polygon = new Polygon(positions, attrs);
            polygon.setFollowTerrain(true);
            polygon.setAltitudeMode(gov.nasa.worldwind.WorldWind.CLAMP_TO_GROUND);

            // Set display name from attributes if available
            if (attributes != null) {
                String name = getDisplayName(attributes);
                if (name != null) {
                    polygon.setDisplayName(name);
                }
            }

            return polygon;

        } catch (Exception e) {
            Log.w(TAG, "Error creating polygon: " + e.getMessage());
            return null;
        }
    }

    /**
     * Extract a display name from DBF attributes
     * Tries common field names like NAME, BUILDINGNA, etc.
     */
    private String getDisplayName(Map<String, Object> attributes) {
        // Try common name fields
        String[] nameFields = {"NAME", "BUILDINGNA", "BUILDING", "LABEL", "ID"};

        for (String field : nameFields) {
            Object value = attributes.get(field);
            if (value != null) {
                String str = value.toString().trim();
                if (!str.isEmpty()) {
                    return str;
                }
            }
        }

        return null;
    }

    /**
     * Update the bounding box with a new coordinate
     */
    private void updateBoundingBox(double latitude, double longitude) {
        minLat = Math.min(minLat, latitude);
        maxLat = Math.max(maxLat, latitude);
        minLon = Math.min(minLon, longitude);
        maxLon = Math.max(maxLon, longitude);
    }

    /**
     * Position the camera to view the loaded Shapefile content
     * Uses intelligent positioning based on data extent
     */
    private void positionCamera() {
        gov.nasa.worldwind.geom.LookAt lookAt = new gov.nasa.worldwind.geom.LookAt();

        // If we have valid bounding box, use it
        if (minLat != Double.MAX_VALUE && maxLat != -Double.MAX_VALUE) {
            // Center of bounding box
            lookAt.latitude = (minLat + maxLat) / 2.0;
            lookAt.longitude = (minLon + maxLon) / 2.0;
            lookAt.altitude = 0;

            // Calculate range based on bounding box size
            double latDiff = maxLat - minLat;
            double lonDiff = maxLon - minLon;
            double maxDiff = Math.max(latDiff, lonDiff);

            // Set range to view entire content (rough estimate: 1 degree ≈ 111km)
            // Add 50% margin for better viewing
            lookAt.range = maxDiff * 111000 * 1.5;

            // Ensure minimum and maximum range
            lookAt.range = Math.max(1000, Math.min(lookAt.range, 500000));

            Log.d(TAG, "Camera positioned to bounding box:");
            Log.d(TAG, "  Center: (" + lookAt.latitude + ", " + lookAt.longitude + ")");
            Log.d(TAG, "  Range: " + lookAt.range + " meters");
            Log.d(TAG, "  Bounds: [" + minLat + ", " + minLon + ", " + maxLat + ", " + maxLon + "]");
        } else {
            // Fallback to default position if no geometry was loaded
            lookAt.latitude = 28.2;
            lookAt.longitude = 113.0;
            lookAt.altitude = 0;
            lookAt.range = 50000; // 50km view range

            Log.d(TAG, "Camera positioned to default location (no geometry found)");
        }

        wwd.getNavigator().setAsLookAt(wwd.getGlobe(), lookAt);
    }

    /**
     * Update status text on UI thread
     */
    private void updateStatus(String message) {
        Log.d(TAG, message);
        mainHandler.post(() -> {
            if (statusText != null) {
                statusText.setText(message);
            }
        });
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
        if (executorService != null) {
            executorService.shutdown();
        }
    }
}
