package com.example.myapplication.kmzload;

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
import com.example.myapplication.threedimentionmap.WorldWindOfflineMapActivity;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import gov.nasa.worldwind.WorldWindow;
import gov.nasa.worldwind.geom.Position;
import gov.nasa.worldwind.globe.BasicElevationCoverage;
import gov.nasa.worldwind.layer.BackgroundLayer;
import gov.nasa.worldwind.layer.BlueMarbleLandsatLayer;
import gov.nasa.worldwind.layer.RenderableLayer;
import gov.nasa.worldwind.render.Color;
import gov.nasa.worldwind.shape.Path;
import gov.nasa.worldwind.shape.Placemark;
import gov.nasa.worldwind.shape.PlacemarkAttributes;
import gov.nasa.worldwind.shape.Polygon;
import gov.nasa.worldwind.shape.ShapeAttributes;

/**
 * Activity for loading and displaying KMZ files using WorldWindow
 */
public class LoadKMZActivity extends AppCompatActivity {

    public static void start(Context context) {
        Intent intent = new Intent(context, LoadKMZActivity.class);
        context.startActivity(intent);
    }

    private static final String TAG = "LoadKMZActivity";

    protected WorldWindow wwd;
    protected TextView statusText;
    protected RenderableLayer kmlLayer;
    private ExecutorService executorService;
    private Handler mainHandler;

    // Track bounding box of all loaded geometry
    private double minLat = Double.MAX_VALUE;
    private double maxLat = -Double.MAX_VALUE;
    private double minLon = Double.MAX_VALUE;
    private double maxLon = -Double.MAX_VALUE;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_load_kmzactivity);

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
        wwd.getLayers().addLayer(new BackgroundLayer());
        wwd.getLayers().addLayer(new BlueMarbleLandsatLayer());
        wwd.getLayers().addLayer(new AtmosphereLayer());

        // Setup the WorldWindow's elevation coverages
        wwd.getGlobe().getElevationModel().addCoverage(new BasicElevationCoverage());

        // Create layer for KML content
        kmlLayer = new RenderableLayer("KML Layer");
        wwd.getLayers().addLayer(kmlLayer);

        // Load KMZ file
        loadKMZFile();
    }

    /**
     * Load and parse the KMZ file
     */
    private void loadKMZFile() {
        updateStatus("Loading KMZ file...");

        executorService.execute(() -> {
            try {
                // Open the KMZ file from raw resources
                InputStream kmzInputStream = getResources().openRawResource(R.raw.city);

                updateStatus("Extracting KMZ archive...");

                // KMZ is a ZIP file containing KML
                ZipInputStream zipInputStream = new ZipInputStream(kmzInputStream);
                ZipEntry entry;

                while ((entry = zipInputStream.getNextEntry()) != null) {
                    String entryName = entry.getName();
                    Log.d(TAG, "Found entry in KMZ: " + entryName);

                    // Look for KML files (usually doc.kml or *.kml)
                    if (entryName.toLowerCase().endsWith(".kml")) {
                        updateStatus("Parsing KML: " + entryName);
                        parseKML(zipInputStream);
                        break;
                    }
                }

                zipInputStream.close();

                // Position camera to view the loaded content
                mainHandler.post(() -> {
                    positionCamera();
                    updateStatus("KMZ loaded successfully");
                    wwd.requestRedraw();
                });

            } catch (Exception e) {
                Log.e(TAG, "Error loading KMZ file", e);
                mainHandler.post(() -> {
                    updateStatus("Error loading KMZ: " + e.getMessage());
                    Toast.makeText(this, "Failed to load KMZ: " + e.getMessage(),
                                 Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    /**
     * Parse KML content from the input stream
     */
    private void parseKML(InputStream inputStream) throws Exception {
        XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
        factory.setNamespaceAware(true);
        XmlPullParser parser = factory.newPullParser();
        parser.setInput(new InputStreamReader(inputStream));

        int eventType = parser.getEventType();
        String currentTag = null;
        StringBuilder coordinates = new StringBuilder();
        String placemarkName = null;
        String geometryType = null;

        while (eventType != XmlPullParser.END_DOCUMENT) {
            switch (eventType) {
                case XmlPullParser.START_TAG:
                    currentTag = parser.getName();

                    if ("Placemark".equals(currentTag)) {
                        placemarkName = null;
                        coordinates.setLength(0);
                        geometryType = null;
                    } else if ("Point".equals(currentTag)) {
                        geometryType = "Point";
                    } else if ("LineString".equals(currentTag)) {
                        geometryType = "LineString";
                    } else if ("Polygon".equals(currentTag)) {
                        geometryType = "Polygon";
                    }
                    break;

                case XmlPullParser.TEXT:
                    String text = parser.getText().trim();
                    if (!text.isEmpty()) {
                        if ("name".equals(currentTag)) {
                            placemarkName = text;
                        } else if ("coordinates".equals(currentTag)) {
                            coordinates.append(text);
                        }
                    }
                    break;

                case XmlPullParser.END_TAG:
                    String endTag = parser.getName();

                    if ("Placemark".equals(endTag)) {
                        // Create renderable based on geometry type
                        if (coordinates.length() > 0 && geometryType != null) {
                            createRenderable(geometryType, coordinates.toString(), placemarkName);
                        }
                    }
                    break;
            }
            eventType = parser.next();
        }
    }

    /**
     * Create a renderable object from KML geometry
     */
    private void createRenderable(String geometryType, String coordinatesStr, String name) {
        try {
            List<Position> positions = parseCoordinates(coordinatesStr);

            if (positions.isEmpty()) {
                Log.w(TAG, "No valid positions found for " + name);
                return;
            }

            mainHandler.post(() -> {
                try {
                    switch (geometryType) {
                        case "Point":
                            createPlacemark(positions.get(0), name);
                            break;
                        case "LineString":
                            createPath(positions, name);
                            break;
                        case "Polygon":
                            createPolygon(positions, name);
                            break;
                    }
                    updateStatus("Added " + geometryType + ": " + name);
                } catch (Exception e) {
                    Log.e(TAG, "Error creating renderable", e);
                }
            });

        } catch (Exception e) {
            Log.e(TAG, "Error parsing coordinates for " + name, e);
        }
    }

    /**
     * Parse KML coordinates string into Position list
     * Format: "lon,lat,alt lon,lat,alt ..."
     */
    private List<Position> parseCoordinates(String coordinatesStr) {
        List<Position> positions = new ArrayList<>();

        // KML coordinates can be separated by whitespace or newlines
        String[] tuples = coordinatesStr.trim().split("\\s+");

        for (String tuple : tuples) {
            if (tuple.isEmpty()) continue;

            String[] parts = tuple.split(",");
            if (parts.length >= 2) {
                try {
                    double longitude = Double.parseDouble(parts[0].trim());
                    double latitude = Double.parseDouble(parts[1].trim());
                    double altitude = parts.length > 2 ? Double.parseDouble(parts[2].trim()) : 0;

                    positions.add(Position.fromDegrees(latitude, longitude, altitude));

                    // Update bounding box
                    updateBoundingBox(latitude, longitude);

                } catch (NumberFormatException e) {
                    Log.w(TAG, "Invalid coordinate: " + tuple);
                }
            }
        }

        return positions;
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
     * Create a Placemark for Point geometry
     */
    private void createPlacemark(Position position, String name) {
        PlacemarkAttributes attrs = new PlacemarkAttributes();
        attrs.setImageColor(new Color(1f, 0f, 0f, 1f)); // Red
        attrs.setImageScale(2.0);

        Placemark placemark = new Placemark(position, attrs, name);
        placemark.setDisplayName(name != null ? name : "Point");

        kmlLayer.addRenderable(placemark);
        Log.d(TAG, "Created placemark: " + name + " at " + position);
    }

    /**
     * Create a Path for LineString geometry
     */
    private void createPath(List<Position> positions, String name) {
        ShapeAttributes attrs = new ShapeAttributes();
        attrs.setOutlineColor(new Color(1f, 1f, 0f, 1f)); // Yellow
        attrs.setOutlineWidth(3f);

        Path path = new Path(positions, attrs);
        path.setFollowTerrain(true);
        path.setDisplayName(name != null ? name : "Path");

        kmlLayer.addRenderable(path);
        Log.d(TAG, "Created path: " + name + " with " + positions.size() + " points");
    }

    /**
     * Create a Polygon for Polygon geometry
     */
    private void createPolygon(List<Position> positions, String name) {
        ShapeAttributes attrs = new ShapeAttributes();
        attrs.setInteriorColor(new Color(1f, 0.5f, 0f, 0.5f)); // Semi-transparent orange
        attrs.setOutlineColor(new Color(1f, 0.5f, 0f, 1f)); // Orange
        attrs.setOutlineWidth(2f);

        Polygon polygon = new Polygon(positions, attrs);
        polygon.setFollowTerrain(true);
        polygon.setDisplayName(name != null ? name : "Polygon");

        kmlLayer.addRenderable(polygon);
        Log.d(TAG, "Created polygon: " + name + " with " + positions.size() + " points");
    }

    /**
     * Position the camera to view the loaded KML content
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
            lookAt.range = Math.max(5000, Math.min(lookAt.range, 1000000));

            Log.d(TAG, "Camera positioned to bounding box: lat=" + lookAt.latitude +
                    ", lon=" + lookAt.longitude + ", range=" + lookAt.range);
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