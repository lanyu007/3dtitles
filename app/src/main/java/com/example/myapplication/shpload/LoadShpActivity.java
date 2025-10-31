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
import java.util.Collections;
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
    private static final String PRJ_PATH = "data/buildings.prj";
    private static final String SHX_PATH = "data/buildings.shx";

    // WorldWind components
    protected WorldWindow wwd;
    protected TextView statusText;
    protected TextView projectionText;
    protected RenderableLayer shapefileLayer;
    private ExecutorService executorService;
    private Handler mainHandler;

    // Shapefile readers
    private ProjectionReader projectionReader;
    private ShapefileIndexReader indexReader;
    private String projectionInfo = "Unknown";

    // Track bounding box of all loaded geometry
    private double minLat = Double.MAX_VALUE;
    private double maxLat = -Double.MAX_VALUE;
    private double minLon = Double.MAX_VALUE;
    private double maxLon = -Double.MAX_VALUE;

    // Statistics
    private int totalPolygons = 0;
    private int loadedPolygons = 0;

    // Building height statistics
    private int buildingsWithHeight = 0;
    private double minHeight = Double.MAX_VALUE;
    private double maxHeight = 0.0;
    private double totalHeight = 0.0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_load_shp);

        // Initialize executor service and handler
        executorService = Executors.newSingleThreadExecutor();
        mainHandler = new Handler(Looper.getMainLooper());

        // Get status text view
        statusText = findViewById(R.id.status_text);
        projectionText = findViewById(R.id.projection_text);

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
     * Load projection information from .prj file
     * 从 .prj 文件加载投影信息
     */
    private void loadProjectionInfo() {
        try {
            InputStream prjStream = getAssets().open(PRJ_PATH);
            projectionReader = new ProjectionReader();
            projectionReader.read(prjStream);
            prjStream.close();

            projectionInfo = projectionReader.getProjectionName();

            Log.d(TAG, "=== Projection Information ===");
            Log.d(TAG, "Name: " + projectionReader.getProjectionName());
            Log.d(TAG, "EPSG: " + projectionReader.getEPSGCode());
            Log.d(TAG, "Type: " + (projectionReader.isGeographic() ? "Geographic" : "Projected"));
            Log.d(TAG, "Needs Transformation: " + projectionReader.needsTransformation());

            if (projectionReader.needsTransformation()) {
                Log.w(TAG, "⚠️ Data requires coordinate transformation!");
                updateStatus("Warning: Coordinate transformation needed");
            }

            // Update UI with projection info
            updateProjectionDisplay();

        } catch (Exception e) {
            Log.w(TAG, ".prj file not found or invalid, assuming WGS84: " + e.getMessage());
            projectionInfo = "WGS84 (assumed)";
        }
    }

    /**
     * Load shapefile index from .shx file
     * 从 .shx 文件加载 shapefile 索引
     */
    private void loadShapefileIndex() {
        try {
            InputStream shxStream = getAssets().open(SHX_PATH);
            indexReader = new ShapefileIndexReader();
            indexReader.read(shxStream);
            shxStream.close();

            Log.d(TAG, "=== Shapefile Index ===");
            Log.d(TAG, "Record count: " + indexReader.getRecordCount());

            // Index will be used for validation after loading

        } catch (Exception e) {
            Log.w(TAG, ".shx file not found or invalid, will use sequential reading: " + e.getMessage());
            indexReader = null;
        }
    }

    /**
     * Validate coordinate ranges
     * 验证坐标范围
     *
     * @return true if coordinates appear valid for WGS84
     */
    private boolean validateCoordinates() {
        if (minLat == Double.MAX_VALUE) {
            Log.e(TAG, "❌ No valid coordinates loaded!");
            return false;
        }

        // Check geographic coordinate ranges
        if (minLat < -90 || maxLat > 90) {
            Log.w(TAG, "⚠️ Invalid latitude range: [" + minLat + ", " + maxLat + "]");
            Log.w(TAG, "⚠️ Data may not be in geographic coordinates (WGS84)");
            updateStatus("Warning: Invalid latitude detected");
            return false;
        }

        if (minLon < -180 || maxLon > 180) {
            Log.w(TAG, "⚠️ Invalid longitude range: [" + minLon + ", " + maxLon + "]");
            Log.w(TAG, "⚠️ Data may not be in geographic coordinates (WGS84)");
            updateStatus("Warning: Invalid longitude detected");
            return false;
        }

        // Check data range reasonableness
        double latDiff = maxLat - minLat;
        double lonDiff = maxLon - minLon;
        double maxDiff = Math.max(latDiff, lonDiff);

        if (maxDiff > 180) {
            Log.w(TAG, "⚠️ Data span > 180 degrees, likely projected coordinates");
            updateStatus("Warning: Large coordinate span detected");
            return false;
        }

        Log.d(TAG, "✓ Coordinates valid");
        Log.d(TAG, "   Latitude: [" + minLat + ", " + maxLat + "]");
        Log.d(TAG, "   Longitude: [" + minLon + ", " + maxLon + "]");
        return true;
    }

    /**
     * Log complete shapefile metadata report
     * 记录完整的 shapefile 元数据报告
     */
    private void logShapefileMetadata() {
        Log.d(TAG, "");
        Log.d(TAG, "========================================");
        Log.d(TAG, "    Shapefile Metadata Report");
        Log.d(TAG, "========================================");
        Log.d(TAG, "File: " + SHAPEFILE_PATH);
        Log.d(TAG, "");
        Log.d(TAG, "Projection:");
        Log.d(TAG, "  - Name: " + projectionInfo);
        if (projectionReader != null) {
            Log.d(TAG, "  - EPSG: " + projectionReader.getEPSGCode());
            Log.d(TAG, "  - Type: " + (projectionReader.isGeographic() ? "Geographic" : "Projected"));
        }
        Log.d(TAG, "");
        Log.d(TAG, "Geometry:");
        Log.d(TAG, "  - Type: Polygon");
        Log.d(TAG, "  - Records: " + (indexReader != null ? indexReader.getRecordCount() : loadedPolygons));
        Log.d(TAG, "  - Bounding Box:");
        if (minLat != Double.MAX_VALUE) {
            Log.d(TAG, "      Lat: [" + String.format("%.6f", minLat) + ", " + String.format("%.6f", maxLat) + "]");
            Log.d(TAG, "      Lon: [" + String.format("%.6f", minLon) + ", " + String.format("%.6f", maxLon) + "]");
        } else {
            Log.d(TAG, "      (No coordinates loaded)");
        }
        Log.d(TAG, "");
        Log.d(TAG, "Rendering:");
        Log.d(TAG, "  - Polygons: " + shapefileLayer.count());

        if (minLat != Double.MAX_VALUE) {
            double centerLat = (minLat + maxLat) / 2.0;
            double centerLon = (minLon + maxLon) / 2.0;
            double latDiff = maxLat - minLat;
            double lonDiff = maxLon - minLon;
            double maxDiff = Math.max(latDiff, lonDiff);
            double range = Math.max(1000, Math.min(maxDiff * 111000 * 1.5, 500000));

            Log.d(TAG, "  - Camera: (" + String.format("%.6f", centerLat) + ", " +
                  String.format("%.6f", centerLon) + ")");
            Log.d(TAG, "  - Range: " + String.format("%.0f", range) + " meters");
        }
        Log.d(TAG, "");
        Log.d(TAG, "Building Heights:");
        if (buildingsWithHeight > 0) {
            Log.d(TAG, "  - Buildings with height: " + buildingsWithHeight);
            Log.d(TAG, "  - Min height: " + String.format("%.2f", minHeight) + " meters");
            Log.d(TAG, "  - Max height: " + String.format("%.2f", maxHeight) + " meters");
            Log.d(TAG, "  - Avg height: " + String.format("%.2f", (totalHeight / buildingsWithHeight)) + " meters");
        } else {
            Log.d(TAG, "  - No height data available (all buildings are 2D)");
        }
        Log.d(TAG, "========================================");
        Log.d(TAG, "");
    }

    /**
     * Update projection display on UI
     * 更新 UI 上的投影显示
     */
    private void updateProjectionDisplay() {
        mainHandler.post(() -> {
            if (projectionText == null) {
                Log.w(TAG, "Projection text view not found");
                return;
            }

            String displayText;
            if (projectionReader != null) {
                String epsg = projectionReader.getEPSGCode();
                String name = projectionReader.getProjectionName();

                // 简洁的显示格式
                if (epsg != null && !epsg.isEmpty()) {
                    displayText = name + " (" + epsg + ")";
                } else {
                    displayText = name;
                }

                // 如果需要坐标转换，添加警告标记
                if (projectionReader.needsTransformation()) {
                    displayText += " ⚠️";
                }
            } else {
                displayText = "Projection: Unknown";
            }

            projectionText.setText(displayText);
            Log.d(TAG, "Updated projection display: " + displayText);
        });
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
                // === Step 0: Load projection and index information ===
                loadProjectionInfo();
                loadShapefileIndex();

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
                            List<Polygon> buildingPolygons = createBuilding3D(record, attributes);
                            if (!buildingPolygons.isEmpty()) {
                                batchPolygons.addAll(buildingPolygons);
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

                // Step 4: Validate coordinates
                if (!validateCoordinates()) {
                    Log.w(TAG, "Coordinate validation failed, but continuing...");
                }

                // Validate index if available
                if (indexReader != null && indexReader.getRecordCount() != loadedPolygons) {
                    Log.w(TAG, "⚠️ Index record count (" + indexReader.getRecordCount() +
                          ") does not match loaded polygons (" + loadedPolygons + ")");
                }

                // Step 5: Position camera and display metadata
                mainHandler.post(() -> {
                    positionCamera();
                    updateProjectionDisplay();  // 确保投影信息显示 / Ensure projection info is displayed
                    logShapefileMetadata();
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
     * 从属性数据中提取建筑高度
     * Extract building height from attribute data
     *
     * 尝试从以下字段读取（按优先级）：
     * Tries to read from the following fields (by priority):
     * 1. height / HEIGHT
     * 2. elevation / ELEVATION
     * 3. floors / FLOORS（楼层数 × 3米 / floor count × 3 meters）
     * 4. 默认 0 / default 0
     *
     * @param attributes DBF 属性数据 / DBF attribute data
     * @return 建筑高度（米），范围 0-10000 / Building height (meters), range 0-10000
     */
    private double extractHeight(Map<String, Object> attributes) {
        if (attributes == null) {
            return 0.0;
        }

        // 按优先级尝试不同的高度字段
        // Try different height fields by priority
        String[] heightFields = {"height", "HEIGHT", "elevation", "ELEVATION",
                                "elev", "ELEV", "alt", "ALT"};

        for (String field : heightFields) {
            if (attributes.containsKey(field)) {
                Object heightObj = attributes.get(field);

                // 处理不同的数据类型
                // Handle different data types
                if (heightObj instanceof Number) {
                    double height = ((Number) heightObj).doubleValue();

                    // 验证高度合理性（0-10000 米）
                    // Validate height reasonableness (0-10000 meters)
                    if (height >= 0 && height <= 10000) {
                        Log.d(TAG, "Extracted height: " + height + " from field: " + field);
                        return height;
                    } else {
                        Log.w(TAG, "Invalid height value: " + height + " from field: " + field);
                    }
                } else if (heightObj instanceof String) {
                    try {
                        double height = Double.parseDouble((String) heightObj);
                        if (height >= 0 && height <= 10000) {
                            return height;
                        }
                    } catch (NumberFormatException e) {
                        Log.w(TAG, "Failed to parse height: " + heightObj);
                    }
                }
            }
        }

        // 尝试从楼层数计算高度（假设每层 3 米）
        // Try to calculate height from floor count (assume 3 meters per floor)
        String[] floorFields = {"floors", "FLOORS", "floor", "FLOOR", "storeys", "STOREYS"};
        for (String field : floorFields) {
            if (attributes.containsKey(field)) {
                Object floorsObj = attributes.get(field);
                if (floorsObj instanceof Number) {
                    int floors = ((Number) floorsObj).intValue();
                    if (floors > 0 && floors <= 200) {
                        double calculatedHeight = floors * 3.0;
                        Log.d(TAG, "Calculated height from floors: " + calculatedHeight);
                        return calculatedHeight;
                    }
                }
            }
        }

        return 0.0;  // 默认高度 / Default height
    }

    /**
     * 根据建筑高度生成颜色（热力图效果）
     * Generate color based on building height (heatmap effect)
     *
     * 颜色映射 / Color mapping:
     * - 0-10m: 浅蓝色（低矮建筑）/ Light blue (low buildings)
     * - 10-30m: 绿色（中等建筑）/ Green (medium buildings)
     * - 30-60m: 黄色（较高建筑）/ Yellow (tall buildings)
     * - 60-100m: 橙色（高层建筑）/ Orange (high-rise buildings)
     * - 100m+: 红色（超高层建筑）/ Red (skyscrapers)
     *
     * @param height 建筑高度（米）/ Building height (meters)
     * @return 对应的颜色 / Corresponding color
     */
    private Color getColorByHeight(double height) {
        if (height < 10) {
            // 浅蓝色 / Light blue
            return new Color(0.5f, 0.7f, 1.0f, 0.6f);
        } else if (height < 30) {
            // 绿色 / Green
            return new Color(0.3f, 0.8f, 0.3f, 0.6f);
        } else if (height < 60) {
            // 黄色 / Yellow
            return new Color(1.0f, 0.9f, 0.3f, 0.6f);
        } else if (height < 100) {
            // 橙色 / Orange
            return new Color(1.0f, 0.6f, 0.2f, 0.6f);
        } else {
            // 红色（超高层）/ Red (skyscrapers)
            return new Color(1.0f, 0.3f, 0.2f, 0.6f);
        }
    }

    /**
     * Create a 3D building from a Shapefile polygon record
     * Creates bottom, top, and side polygons to form a pseudo-3D building effect
     *
     * @param record Shapefile polygon record containing geometry
     * @param attributes Optional attribute data from DBF file
     * @return List of Polygon renderables (bottom, top, and sides), or empty list if creation fails
     */
    private List<Polygon> createBuilding3D(ShapefileReader.PolygonRecord record,
                                           Map<String, Object> attributes) {
        List<Polygon> buildingPolygons = new ArrayList<>();

        try {
            // Use the first part (outer ring)
            List<ShapefileReader.Point> ring = record.parts.get(0);

            // 提取建筑高度（如果有属性数据）
            // Extract building height (if attribute data is available)
            double buildingHeight = extractHeight(attributes);

            // Update bounding box for all points
            for (ShapefileReader.Point point : ring) {
                updateBoundingBox(point.y, point.x);
            }

            // 更新高度统计信息
            // Update height statistics
            if (buildingHeight > 0) {
                buildingsWithHeight++;
                minHeight = Math.min(minHeight, buildingHeight);
                maxHeight = Math.max(maxHeight, buildingHeight);
                totalHeight += buildingHeight;
                Log.d(TAG, "Creating 3D building with height: " + buildingHeight + " meters");
            }

            // Get base color for this building based on height
            Color baseColor = getColorByHeight(buildingHeight);

            // === 1. Create bottom polygon (height = 0) ===
            List<Position> basePositions = new ArrayList<>();
            for (ShapefileReader.Point point : ring) {
                basePositions.add(Position.fromDegrees(point.y, point.x, 0));
            }

            // Ensure closed
            if (basePositions.size() > 0) {
                Position first = basePositions.get(0);
                Position last = basePositions.get(basePositions.size() - 1);
                if (first.latitude != last.latitude || first.longitude != last.longitude) {
                    basePositions.add(first);
                }
            }

            // Bottom polygon attributes - darker color, semi-transparent
            ShapeAttributes bottomAttrs = new ShapeAttributes();
            Color bottomColor = new Color(
                baseColor.red * 0.5f,
                baseColor.green * 0.5f,
                baseColor.blue * 0.5f,
                0.3f
            );
            bottomAttrs.setInteriorColor(bottomColor);
            bottomAttrs.setOutlineColor(new Color(0.3f, 0.3f, 0.3f, 0.5f));
            bottomAttrs.setOutlineWidth(1f);
            bottomAttrs.setDrawInterior(true);
            bottomAttrs.setDrawOutline(true);

            Polygon bottomPolygon = new Polygon(basePositions, bottomAttrs);
            bottomPolygon.setAltitudeMode(gov.nasa.worldwind.WorldWind.RELATIVE_TO_GROUND);
            bottomPolygon.setFollowTerrain(false);
            buildingPolygons.add(bottomPolygon);

            // === 2. Create top polygon (height = buildingHeight) ===
            List<Position> topPositions = new ArrayList<>();
            for (ShapefileReader.Point point : ring) {
                topPositions.add(Position.fromDegrees(point.y, point.x, buildingHeight));
            }

            // Ensure closed
            if (topPositions.size() > 0) {
                Position first = topPositions.get(0);
                Position last = topPositions.get(topPositions.size() - 1);
                if (first.latitude != last.latitude || first.longitude != last.longitude) {
                    topPositions.add(first);
                }
            }

            // Reverse order for correct normal direction (outward facing)
            Collections.reverse(topPositions);

            // Top polygon attributes - full color
            ShapeAttributes topAttrs = new ShapeAttributes();
            topAttrs.setInteriorColor(baseColor);
            topAttrs.setOutlineColor(new Color(
                baseColor.red * 0.7f,
                baseColor.green * 0.7f,
                baseColor.blue * 0.7f,
                1.0f
            ));
            topAttrs.setOutlineWidth(2f);
            topAttrs.setDrawInterior(true);
            topAttrs.setDrawOutline(true);

            Polygon topPolygon = new Polygon(topPositions, topAttrs);
            topPolygon.setAltitudeMode(gov.nasa.worldwind.WorldWind.RELATIVE_TO_GROUND);
            topPolygon.setFollowTerrain(false);
            buildingPolygons.add(topPolygon);

            // === 3. Create side polygons (connecting bottom and top) ===
            Color sideColor = new Color(
                baseColor.red * 0.6f,
                baseColor.green * 0.6f,
                baseColor.blue * 0.6f,
                0.7f
            );

            // Get positions without the closing point for side generation
            List<Position> baseRing = new ArrayList<>();
            List<Position> topRing = new ArrayList<>();
            for (ShapefileReader.Point point : ring) {
                baseRing.add(Position.fromDegrees(point.y, point.x, 0));
                topRing.add(Position.fromDegrees(point.y, point.x, buildingHeight));
            }

            // Reverse top ring to match bottom orientation
            Collections.reverse(topRing);

            // Create a side polygon for each edge
            for (int i = 0; i < baseRing.size() - 1; i++) {
                List<Position> sidePositions = new ArrayList<>();

                // Create trapezoid: basePt1 -> basePt2 -> topPt2 -> topPt1 -> basePt1 (closed)
                Position basePt1 = baseRing.get(i);
                Position basePt2 = baseRing.get(i + 1);
                Position topPt1 = topRing.get(topRing.size() - 1 - i);
                Position topPt2 = topRing.get(topRing.size() - 2 - i);

                sidePositions.add(basePt1);
                sidePositions.add(basePt2);
                sidePositions.add(topPt2);
                sidePositions.add(topPt1);
                sidePositions.add(basePt1); // Close the polygon

                ShapeAttributes sideAttrs = new ShapeAttributes();
                sideAttrs.setInteriorColor(sideColor);
                sideAttrs.setOutlineColor(new Color(
                    sideColor.red * 0.8f,
                    sideColor.green * 0.8f,
                    sideColor.blue * 0.8f,
                    0.8f
                ));
                sideAttrs.setOutlineWidth(1f);
                sideAttrs.setDrawInterior(true);
                sideAttrs.setDrawOutline(true);

                Polygon sidePolygon = new Polygon(sidePositions, sideAttrs);
                sidePolygon.setAltitudeMode(gov.nasa.worldwind.WorldWind.RELATIVE_TO_GROUND);
                sidePolygon.setFollowTerrain(false);
                buildingPolygons.add(sidePolygon);
            }

            // Set display name from attributes if available
            if (attributes != null) {
                String name = getDisplayName(attributes);
                if (name != null) {
                    for (Polygon polygon : buildingPolygons) {
                        polygon.setDisplayName(name);
                    }
                }
            }

            Log.d(TAG, "Created " + buildingPolygons.size() + " polygons for 3D building (height: " + buildingHeight + "m)");
            return buildingPolygons;

        } catch (Exception e) {
            Log.w(TAG, "Error creating 3D building: " + e.getMessage());
            return buildingPolygons; // Return whatever was created (may be empty)
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
     * 定位相机以查看加载的 Shapefile 内容，基于数据范围智能定位
     */
    private void positionCamera() {
        gov.nasa.worldwind.geom.LookAt lookAt = new gov.nasa.worldwind.geom.LookAt();

        // Validate coordinates before positioning
        if (minLat == Double.MAX_VALUE) {
            // No coordinates loaded - use default position
            Log.w(TAG, "Using default camera position (no valid coordinates)");
            lookAt.latitude = 28.2;
            lookAt.longitude = 113.0;
            lookAt.altitude = 0;
            lookAt.range = 50000;
            wwd.getNavigator().setAsLookAt(wwd.getGlobe(), lookAt);
            return;
        }

        // Check for invalid coordinate ranges
        if (minLat < -90 || maxLat > 90 || minLon < -180 || maxLon > 180) {
            Log.w(TAG, "Invalid coordinate ranges detected, using default camera position");
            lookAt.latitude = 28.2;
            lookAt.longitude = 113.0;
            lookAt.altitude = 0;
            lookAt.range = 50000;
            wwd.getNavigator().setAsLookAt(wwd.getGlobe(), lookAt);
            return;
        }

        // Calculate center of bounding box
        lookAt.latitude = (minLat + maxLat) / 2.0;
        lookAt.longitude = (minLon + maxLon) / 2.0;
        lookAt.altitude = 0;

        // Calculate range based on bounding box size
        double latDiff = maxLat - minLat;
        double lonDiff = maxLon - minLon;
        double maxDiff = Math.max(latDiff, lonDiff);

        // Check for unreasonable data span
        if (maxDiff > 180) {
            Log.w(TAG, "⚠️ Data range > 180°, using default camera distance");
            lookAt.range = 50000;
        } else {
            // Set range to view entire content (rough estimate: 1 degree ≈ 111km)
            // Add 50% margin for better viewing
            lookAt.range = maxDiff * 111000 * 1.5;

            // Ensure minimum and maximum range: 1km - 500km
            lookAt.range = Math.max(1000, Math.min(lookAt.range, 500000));
        }

        Log.d(TAG, "Camera positioned:");
        Log.d(TAG, "  - Center: (" + String.format("%.6f", lookAt.latitude) + ", " +
              String.format("%.6f", lookAt.longitude) + ")");
        Log.d(TAG, "  - Range: " + String.format("%.0f", lookAt.range) + " meters");
        Log.d(TAG, "  - Bounds: [" + String.format("%.6f", minLat) + ", " +
              String.format("%.6f", minLon) + ", " +
              String.format("%.6f", maxLat) + ", " +
              String.format("%.6f", maxLon) + "]");

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
