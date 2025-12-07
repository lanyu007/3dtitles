package com.example.myapplication.shapeload;

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
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
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
 * Activity for loading and displaying Polygon shapefile with 3D building representations
 *
 * This activity demonstrates:
 * - Loading Polygon shapefile from assets (buildings)
 * - Converting Web Mercator (EPSG:3857) to WGS84 automatically
 * - Creating 3D buildings from polygon footprints
 * - Extracting building heights from DBF attributes
 * - Color mapping based on building height (blue to red gradient)
 * - Efficient batch processing for large datasets
 * - Asynchronous loading with progress updates
 */
public class MyShapeLoadActivity extends AppCompatActivity {

    public static void start(Context context) {
        Intent intent = new Intent(context, MyShapeLoadActivity.class);
        context.startActivity(intent);
    }

    private static final String TAG = "MyShapeLoadActivity";

    // Shapefile paths in assets
    private static final String SHAPEFILE_PATH = "shp/cs.shp";
    private static final String DBF_PATH = "shp/cs.dbf";
    private static final String PRJ_PATH = "shp/cs.prj";
    private static final String CPG_PATH = "shp/cs.cpg";

    // Batch processing configuration for performance
    private static final int BATCH_SIZE = 1000;  // Process 1000 buildings per batch
    private static final int PROGRESS_UPDATE_INTERVAL = 100;  // Update every 100 buildings

    // WorldWind components
    protected WorldWindow wwd;
    protected TextView statusText;
    protected TextView projectionText;
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

    // Building height statistics
    private int buildingsWithHeight = 0;
    private double minHeight = Double.MAX_VALUE;
    private double maxHeight = 0.0;
    private double totalHeight = 0.0;

    // Grid statistics
    private int totalGrids = 0;
    private int loadedGrids = 0;

    // Point cloud statistics
    private int totalPoints = 0;
    private int loadedPoints = 0;

    // Z-value statistics for PointZ data
    private double globalMinZ = 0.0;
    private double globalMaxZ = 0.0;

    // Height percentiles for dynamic color mapping
    private double heightP25 = 0.0;
    private double heightP50 = 0.0;
    private double heightP75 = 0.0;
    private double heightP95 = 0.0;

    // Optimal grid size (calculated dynamically)
    private double optimalGridSize = 0.0001;  // Default 0.0001° (~11m)

    // Projection information
    private String projectionInfo = "Unknown";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_load_shape);

        // Initialize executor service and handler
        executorService = Executors.newSingleThreadExecutor();
        mainHandler = new Handler(Looper.getMainLooper());

        // Get UI components
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
        shapefileLayer = new RenderableLayer("Building Layer");
        wwd.getLayers().addLayer(shapefileLayer);

        Log.d(TAG, "WorldWindow initialized");
    }

    /**
     * Load character encoding from .cpg file
     * 从 .cpg 文件加载字符编码
     *
     * @return The character encoding name (e.g., "UTF-8", "GBK"), or null if not found
     */
    private String loadCharacterEncoding() {
        try {
            InputStream cpgStream = getAssets().open(CPG_PATH);
            byte[] buffer = new byte[1024];
            int bytesRead = cpgStream.read(buffer);
            cpgStream.close();

            if (bytesRead > 0) {
                // Read the encoding name and trim whitespace
                String encoding = new String(buffer, 0, bytesRead, "UTF-8").trim();

                // Handle common encoding aliases
                if (encoding.equalsIgnoreCase("UTF8")) {
                    encoding = "UTF-8";
                } else if (encoding.equalsIgnoreCase("GBK") || encoding.equalsIgnoreCase("GB2312")) {
                    encoding = "GBK"; // GBK is a superset of GB2312
                }

                Log.d(TAG, "=== Character Encoding ===");
                Log.d(TAG, "Encoding from .cpg: " + encoding);
                return encoding;
            } else {
                Log.w(TAG, ".cpg file is empty, using default UTF-8 encoding");
                return null;
            }
        } catch (Exception e) {
            Log.w(TAG, ".cpg file not found, using default UTF-8 encoding: " + e.getMessage());
            return null;
        }
    }

    /**
     * Load projection information from .prj file
     */
    private void loadProjectionInfo() {
        try {
            InputStream prjStream = getAssets().open(PRJ_PATH);
            byte[] buffer = new byte[4096];
            int bytesRead = prjStream.read(buffer);
            prjStream.close();

            if (bytesRead > 0) {
                String prjContent = new String(buffer, 0, bytesRead, "UTF-8").trim();

                // Parse projection name from WKT format
                if (prjContent.contains("WGS_1984_Web_Mercator")) {
                    projectionInfo = "WGS 1984 Web Mercator (EPSG:3857)";
                } else if (prjContent.contains("WGS_84") || prjContent.contains("WGS84")) {
                    projectionInfo = "WGS 84 (EPSG:4326)";
                } else {
                    projectionInfo = "Custom Projection";
                }

                Log.d(TAG, "Projection info: " + projectionInfo);
            }
        } catch (Exception e) {
            Log.w(TAG, ".prj file not found, assuming WGS84: " + e.getMessage());
            projectionInfo = "WGS 84 (assumed)";
        }
    }

    /**
     * Calculate optimal grid size based on point density and geographic bounds
     *
     * Strategy:
     * - High density (>500k points): finer grid (0.00003° ~ 3.3m)
     * - Medium density (100k-500k): medium grid (0.00005° ~ 5.5m)
     * - Low density (<100k): coarser grid (0.0001° ~ 11m)
     *
     * Also considers geographic bounds to ensure reasonable grid count
     *
     * FIXED: Integer overflow issue - now uses long for grid count calculations
     *
     * @param pointCount Total number of points in the dataset
     * @param bbox Bounding box [minX, minY, maxX, maxY]
     * @return Optimal grid size in degrees
     */
    private double calculateOptimalGridSize(int pointCount, double[] bbox) {
        // Grid count constraints to prevent overflow and ensure reasonable performance
        final long MAX_GRIDS = 500000;  // Maximum 500k grids (~710 points/grid for 1M points)
        final long MIN_GRIDS = 1000;    // Minimum 1000 grids for reasonable detail

        // Calculate geographic area
        double latRange = bbox[3] - bbox[1];
        double lonRange = bbox[2] - bbox[0];
        double area = latRange * lonRange;  // in square degrees

        // Calculate point density (points per square degree)
        double density = pointCount / area;

        Log.d(TAG, "=== Grid Size Calculation ===");
        Log.d(TAG, "Total points: " + pointCount);
        Log.d(TAG, "Geographic area: " + String.format("%.6f", area) + " sq degrees");
        Log.d(TAG, "Latitude range: " + String.format("%.6f", latRange) + "° (~" +
                   String.format("%.1f", latRange * 111000) + " m)");
        Log.d(TAG, "Longitude range: " + String.format("%.6f", lonRange) + "° (~" +
                   String.format("%.1f", lonRange * 111000) + " m)");
        Log.d(TAG, "Point density: " + String.format("%.0f", density) + " points/sq degree");

        double gridSize;

        // Determine initial grid size based on point count and density
        if (pointCount > 500000) {
            // Very high density: use finer grid (3-5 meters)
            gridSize = 0.00003;  // ~3.3 meters
            Log.d(TAG, "High density detected: using fine grid (0.00003° ~ 3.3m)");
        } else if (pointCount > 100000) {
            // Medium-high density: use medium-fine grid (5-7 meters)
            gridSize = 0.00005;  // ~5.5 meters
            Log.d(TAG, "Medium-high density detected: using medium-fine grid (0.00005° ~ 5.5m)");
        } else if (pointCount > 50000) {
            // Medium density: use medium grid (7-11 meters)
            gridSize = 0.00007;  // ~7.7 meters
            Log.d(TAG, "Medium density detected: using medium grid (0.00007° ~ 7.7m)");
        } else {
            // Lower density: use default grid (11 meters)
            gridSize = 0.0001;  // ~11 meters
            Log.d(TAG, "Lower density detected: using default grid (0.0001° ~ 11m)");
        }

        // FIXED: Use long to prevent integer overflow
        // Calculate number of grids in each dimension separately to avoid overflow
        long latGrids = (long) Math.ceil(latRange / gridSize);
        long lonGrids = (long) Math.ceil(lonRange / gridSize);

        // Check for potential overflow before multiplication
        if (latGrids > 0 && lonGrids > Long.MAX_VALUE / latGrids) {
            Log.e(TAG, "ERROR: Grid calculation would overflow! latGrids=" + latGrids +
                       ", lonGrids=" + lonGrids);
            // Use a very large grid size as fallback
            gridSize = Math.sqrt(area / MAX_GRIDS);
            latGrids = (long) Math.ceil(latRange / gridSize);
            lonGrids = (long) Math.ceil(lonRange / gridSize);
        }

        long estimatedGrids = latGrids * lonGrids;

        Log.d(TAG, "Initial grid estimation:");
        Log.d(TAG, "  Grid size: " + String.format("%.6f", gridSize) + "°");
        Log.d(TAG, "  Latitude grids: " + latGrids);
        Log.d(TAG, "  Longitude grids: " + lonGrids);
        Log.d(TAG, "  Estimated total grids: " + estimatedGrids);
        Log.d(TAG, "  Points per grid: " + (estimatedGrids > 0 ? (pointCount / estimatedGrids) : 0));

        // Iterative adjustment: If grid count is too high, increase grid size
        int adjustmentIterations = 0;
        while (estimatedGrids > MAX_GRIDS && adjustmentIterations < 10) {
            adjustmentIterations++;
            // Increase grid size by factor to reduce grid count
            double adjustmentFactor = Math.sqrt((double) estimatedGrids / MAX_GRIDS);
            gridSize *= adjustmentFactor;

            latGrids = (long) Math.ceil(latRange / gridSize);
            lonGrids = (long) Math.ceil(lonRange / gridSize);
            estimatedGrids = latGrids * lonGrids;

            Log.d(TAG, "Adjustment iteration " + adjustmentIterations + " (too many grids):");
            Log.d(TAG, "  New grid size: " + String.format("%.6f", gridSize) + "° (~" +
                       String.format("%.1f", gridSize * 111000) + "m)");
            Log.d(TAG, "  Estimated grids: " + estimatedGrids);
            Log.d(TAG, "  Points per grid: " + (pointCount / estimatedGrids));
        }

        // Iterative adjustment: If grid count is too low, decrease grid size
        adjustmentIterations = 0;
        while (estimatedGrids < MIN_GRIDS && pointCount > 10000 && adjustmentIterations < 10) {
            adjustmentIterations++;
            // Decrease grid size by factor to increase grid count
            double adjustmentFactor = Math.sqrt((double) MIN_GRIDS / estimatedGrids);
            gridSize /= adjustmentFactor;

            // Don't make grid size too small
            if (gridSize < 0.00001) {  // ~1.1 meters minimum
                Log.d(TAG, "Grid size reached minimum threshold (0.00001°), stopping adjustment");
                gridSize = 0.00001;
                break;
            }

            latGrids = (long) Math.ceil(latRange / gridSize);
            lonGrids = (long) Math.ceil(lonRange / gridSize);
            estimatedGrids = latGrids * lonGrids;

            Log.d(TAG, "Adjustment iteration " + adjustmentIterations + " (too few grids):");
            Log.d(TAG, "  New grid size: " + String.format("%.6f", gridSize) + "° (~" +
                       String.format("%.1f", gridSize * 111000) + "m)");
            Log.d(TAG, "  Estimated grids: " + estimatedGrids);
            Log.d(TAG, "  Points per grid: " + (pointCount / estimatedGrids));
        }

        // Final calculation and logging
        latGrids = (long) Math.ceil(latRange / gridSize);
        lonGrids = (long) Math.ceil(lonRange / gridSize);
        estimatedGrids = latGrids * lonGrids;

        Log.d(TAG, "=== Final Grid Configuration ===");
        Log.d(TAG, "Grid size: " + String.format("%.6f", gridSize) + "° (~" +
                   String.format("%.1f", gridSize * 111000) + "m)");
        Log.d(TAG, "Latitude grids: " + latGrids);
        Log.d(TAG, "Longitude grids: " + lonGrids);
        Log.d(TAG, "Total estimated grids: " + estimatedGrids);
        Log.d(TAG, "Points per grid (average): " + (estimatedGrids > 0 ? (pointCount / estimatedGrids) : 0));
        Log.d(TAG, "Grid coverage: " + String.format("%.1f", gridSize * 111000) + "m × " +
                   String.format("%.1f", gridSize * 111000) + "m");
        Log.d(TAG, "================================");

        return gridSize;
    }

    /**
     * Calculate height percentiles from normalized Z values
     * Used for dynamic color mapping based on actual data distribution
     *
     * @param zValues List of all normalized Z values
     */
    private void calculateHeightPercentiles(List<Double> zValues) {
        if (zValues.isEmpty()) {
            Log.w(TAG, "No Z values available for percentile calculation");
            return;
        }

        // Sort the Z values
        List<Double> sortedZ = new ArrayList<>(zValues);
        Collections.sort(sortedZ);

        int n = sortedZ.size();

        // Calculate percentiles
        heightP25 = sortedZ.get((int) (n * 0.25));
        heightP50 = sortedZ.get((int) (n * 0.50));  // Median
        heightP75 = sortedZ.get((int) (n * 0.75));
        heightP95 = sortedZ.get((int) (n * 0.95));

        Log.d(TAG, "=== Height Percentile Analysis ===");
        Log.d(TAG, "Total data points: " + n);
        Log.d(TAG, "Min height: " + String.format("%.2f", sortedZ.get(0)) + "m");
        Log.d(TAG, "P25 (25th percentile): " + String.format("%.2f", heightP25) + "m");
        Log.d(TAG, "P50 (Median): " + String.format("%.2f", heightP50) + "m");
        Log.d(TAG, "P75 (75th percentile): " + String.format("%.2f", heightP75) + "m");
        Log.d(TAG, "P95 (95th percentile): " + String.format("%.2f", heightP95) + "m");
        Log.d(TAG, "Max height: " + String.format("%.2f", sortedZ.get(n - 1)) + "m");
        Log.d(TAG, "==================================");
    }

    /**
     * Load and parse the Shapefile asynchronously
     */
    private void loadShapefile() {
        Log.d(TAG, "=== Starting Shapefile Load ===");
        Log.d(TAG, "Shapefile path: " + SHAPEFILE_PATH);
        updateStatus("Loading Building Shapefile...");

        executorService.execute(() -> {
            ShapefileReader shpReader = new ShapefileReader();
            DBFReader dbfReader = null;

            try {
                // Step 1: Load projection and encoding information
                loadProjectionInfo();
                String characterEncoding = loadCharacterEncoding();
                updateProjectionDisplay();

                // Step 2: Load and parse .shp file
                updateStatus("Reading geometry file (.shp)...");
                InputStream shpStream = getAssets().open(SHAPEFILE_PATH);
                shpReader.read(shpStream);
                shpStream.close();

                final int shapeType = shpReader.getShapeType();
                double[] bbox = shpReader.getBoundingBox();
                Log.d(TAG, "Shape type: " + shapeType);
                Log.d(TAG, "Bounding box: [" + bbox[0] + ", " + bbox[1] + ", " + bbox[2] + ", " + bbox[3] + "]");
                Log.d(TAG, "Detected shape type: " + shapeType + " (" + getShapeTypeName(shapeType) + ")");

                // Check shape type and process accordingly
                if (shapeType == 11) {
                    // PointZ data - use grid conversion
                    Log.d(TAG, "=== PointZ Processing Branch ===");
                    List<ShapefileReader.PointZRecord> pointZRecords = shpReader.getPointZRecords();
                    Log.d(TAG, "Total PointZ records: " + pointZRecords.size());
                    if (pointZRecords.isEmpty()) {
                        Log.e(TAG, "ERROR: No PointZ records found!");
                        updateStatus("Error: No PointZ data in file");
                        return;
                    }
                    totalPoints = pointZRecords.size();
                    Log.d(TAG, "Shapefile loaded: " + totalPoints + " PointZ records");

                    // First pass: Find Z value range and collect all Z values
                    List<Double> allZValues = new ArrayList<>();
                    double globalMinZ = Double.MAX_VALUE;
                    double globalMaxZ = -Double.MAX_VALUE;
                    for (ShapefileReader.PointZRecord point : pointZRecords) {
                        globalMinZ = Math.min(globalMinZ, point.z);
                        globalMaxZ = Math.max(globalMaxZ, point.z);
                        allZValues.add(point.z);
                    }

                    Log.d(TAG, "Global Z range: min=" + globalMinZ + ", max=" + globalMaxZ + ", range=" + (globalMaxZ - globalMinZ));

                    // Calculate height percentiles for dynamic color mapping
                    updateStatus("Calculating height distribution...");
                    List<Double> normalizedZValues = new ArrayList<>();
                    for (ShapefileReader.PointZRecord point : pointZRecords) {
                        normalizedZValues.add(point.z - globalMinZ);
                    }
                    calculateHeightPercentiles(normalizedZValues);

                    // Save global Z values for metadata logging
                    this.globalMinZ = globalMinZ;
                    this.globalMaxZ = globalMaxZ;

                    // Step 3: 计算最优网格大小
                    updateStatus("分析点云密度...");
                    final double gridSize = calculateOptimalGridSize(totalPoints, bbox);
                    optimalGridSize = gridSize;  // 保存以供后续引用

                    // Step 4: 创建 PointCloudTo3DConverter
                    updateStatus("将点云转换为3D网格...");
                    PointCloudTo3DConverter converter = new PointCloudTo3DConverter(gridSize);
                    Log.d(TAG, "创建 PointCloudTo3DConverter，网格大小=" + gridSize);

                    // 第二遍扫描：添加点到网格并归一化Z值
                    updateStatus("归一化高度...");
                    final double zOffset = globalMinZ;  // 保存为final供lambda使用
                    for (ShapefileReader.PointZRecord point : pointZRecords) {
                        // 归一化Z值：减去最小值得到相对高度
                        double normalizedZ = point.z - zOffset;
                        converter.addPoint(point.y, point.x, normalizedZ);
                        normalizedZValues.add(normalizedZ);
                    }

                    Log.d(TAG, "Z值已归一化: offset=" + zOffset + ", 新范围=[0, " +
                          (globalMaxZ - globalMinZ) + "]");

                    // 计算高度百分位数用于动态颜色映射
                    updateStatus("计算高度分布...");
                    calculateHeightPercentiles(normalizedZValues);

                    // 保存全局Z值供元数据日志使用
                    this.globalMinZ = globalMinZ;
                    this.globalMaxZ = globalMaxZ;

                    // 获取所有网格单元
                    updateStatus("生成3D网格建筑...");
                    Collection<GridCell> gridCells = converter.getAllGridCells();
                    totalGrids = gridCells.size();

                    Log.d(TAG, "网格统计:");
                    Log.d(TAG, "  总网格数: " + totalGrids);
                    Log.d(TAG, "  网格大小: " + gridSize + " 度 (~" +
                          String.format("%.1f", gridSize * 111000) + " 米)");

                    // 批量处理网格单元
                    List<GridCell> cellList = new ArrayList<>(gridCells);
                    for (int i = 0; i < cellList.size(); i += BATCH_SIZE) {
                        int endIndex = Math.min(i + BATCH_SIZE, cellList.size());
                        List<Polygon> batchPolygons = new ArrayList<>();

                        // 为批次中的每个网格单元创建3D建筑
                        for (int j = i; j < endIndex; j++) {
                            GridCell cell = cellList.get(j);
                            // 计算高度使用加权平均（70%平均 + 30%最大）
                            double height = cell.getAvgZ() * 0.7 + cell.getMaxZ() * 0.3;
                            List<Polygon> columnPolygons = createGridBuilding3D(cell, gridSize, height);
                            batchPolygons.addAll(columnPolygons);
                            loadedGrids++;
                        }

                        // 在UI线程上添加批次到图层
                        final List<Polygon> finalBatch = new ArrayList<>(batchPolygons);
                        final int currentProgress = loadedGrids;
                        mainHandler.post(() -> {
                            for (Polygon polygon : finalBatch) {
                                shapefileLayer.addRenderable(polygon);
                            }
                            wwd.requestRedraw();
                        });

                        // 每100个网格更新一次进度
                        if (loadedGrids % PROGRESS_UPDATE_INTERVAL == 0) {
                            updateStatus("已加载 " + loadedGrids + "/" + totalGrids + " 个网格");
                        }
                    }

                    Log.d(TAG, "3D网格建筑创建完成: " + loadedGrids + " 个网格");
                    updateStatus("已加载 " + loadedGrids + " 个3D网格建筑");

                } else {
                    // Polygon data - use original logic
                    List<ShapefileReader.PolygonRecord> polygons = shpReader.getPolygonRecords();
                    totalPolygons = polygons.size();
                    Log.d(TAG, "Shapefile loaded: " + totalPolygons + " Polygon records");

                    // Step 3: Load and parse .dbf file (attributes)
                    updateStatus("Reading attributes file (.dbf)...");
                    try {
                        InputStream dbfStream = getAssets().open(DBF_PATH);
                        dbfReader = new DBFReader();
                        dbfReader.read(dbfStream, characterEncoding);
                        dbfStream.close();
                        Log.d(TAG, "DBF loaded: " + dbfReader.getRecordCount() + " records");
                    } catch (Exception e) {
                        Log.w(TAG, "Could not load DBF file (attributes will not be available): " + e.getMessage());
                    }

                    // Step 4: Create 3D buildings from polygon records
                    updateStatus("Creating 3D buildings (0/" + totalPolygons + ")...");

                    final DBFReader finalDbfReader = dbfReader;

                    // Process polygons in batches for performance
                    for (int i = 0; i < polygons.size(); i += BATCH_SIZE) {
                        int endIndex = Math.min(i + BATCH_SIZE, polygons.size());
                        List<Polygon> batchPolygons = new ArrayList<>();

                        // Create batch of 3D buildings
                        for (int j = i; j < endIndex; j++) {
                            ShapefileReader.PolygonRecord record = polygons.get(j);

                            // Get attributes if available
                            Map<String, Object> attributes = null;
                            if (finalDbfReader != null && j < finalDbfReader.getRecordCount()) {
                                attributes = finalDbfReader.getRecord(j);
                            }

                            // Create 3D building from polygon record
                            List<Polygon> buildingPolygons = createBuilding3D(record, attributes);
                            batchPolygons.addAll(buildingPolygons);
                            loadedPolygons++;
                        }

                        // Add batch to layer on main thread
                        final int currentCount = loadedPolygons;
                        mainHandler.post(() -> {
                            for (Polygon polygon : batchPolygons) {
                                shapefileLayer.addRenderable(polygon);
                            }

                            // Update progress periodically
                            if (currentCount % PROGRESS_UPDATE_INTERVAL == 0 || currentCount == totalPolygons) {
                                updateStatus("Loading buildings (" + currentCount + "/" + totalPolygons + ")...");
                            }

                            wwd.requestRedraw();
                        });

                        // Brief pause to allow UI updates
                        Thread.sleep(10);
                    }
                }

                // Step 5: Position camera and finalize
                mainHandler.post(() -> {
                    positionCamera();
                    Log.d(TAG, "Camera positioning complete");
                    Log.d(TAG, "  Bounds: lat[" + minLat + ", " + maxLat + "], lon[" + minLon + ", " + maxLon + "]");
                    logShapefileMetadata();

                    String statusMsg;
                    if (shapeType == 11) {
                        statusMsg = "已加载 " + loadedGrids + " 个3D网格建筑";
                    } else {
                        statusMsg = "Loaded " + loadedPolygons + " buildings with 3D effects";
                    }
                    updateStatus(statusMsg);
                    wwd.requestRedraw();

                    Log.d(TAG, "Shapefile loading complete");
                });

            } catch (Exception e) {
                Log.e(TAG, "Error loading shapefile", e);
                e.printStackTrace();  // 打印完整堆栈跟踪
                mainHandler.post(() -> {
                    updateStatus("Error loading Shapefile: " + e.getMessage());
                    Toast.makeText(this, "Failed to load Shapefile: " + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    /**
     * Create a 3D building from a Shapefile polygon record
     *
     * Features:
     * - Creates bottom face, top face, side faces, and interior slices
     * - Extracts building height from DBF attributes
     * - Color mapping based on height (blue->green->yellow->orange->red)
     * - RELATIVE_TO_GROUND altitude mode for 3D effect
     *
     * @param record Polygon record containing footprint coordinates
     * @param attributes Optional attribute data from DBF file (including height)
     * @return List of Polygon renderables forming the 3D building
     */
    private List<Polygon> createBuilding3D(ShapefileReader.PolygonRecord record,
                                           Map<String, Object> attributes) {
        List<Polygon> buildingPolygons = new ArrayList<>();

        try {
            // Use the first part (outer ring)
            List<ShapefileReader.Point> ring = record.parts.get(0);

            // Extract building height
            double buildingHeight = extractHeight(attributes);

            // Get base color for this building based on height
            Color baseColor = getColorByHeight(buildingHeight);

            // Create shared Position lists (baseRing and topRing)
            List<Position> baseRing = new ArrayList<>();
            List<Position> topRing = new ArrayList<>();

            for (ShapefileReader.Point point : ring) {
                baseRing.add(Position.fromDegrees(point.y, point.x, 0));
                topRing.add(Position.fromDegrees(point.y, point.x, buildingHeight));
                updateBoundingBox(point.y, point.x);
            }

            // Ensure both rings are closed
            if (baseRing.size() > 0) {
                Position first = baseRing.get(0);
                Position last = baseRing.get(baseRing.size() - 1);
                if (first.latitude != last.latitude || first.longitude != last.longitude) {
                    baseRing.add(first);
                    topRing.add(topRing.get(0));
                }
            }

            // Create bottom polygon
            if (baseRing.size() >= 3) {
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

                Polygon bottomPolygon = new Polygon(baseRing, bottomAttrs);
                bottomPolygon.setAltitudeMode(gov.nasa.worldwind.WorldWind.RELATIVE_TO_GROUND);
                bottomPolygon.setFollowTerrain(false);
                buildingPolygons.add(bottomPolygon);
            }

            // Create top polygon
            if (topRing.size() >= 3) {
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

                Polygon topPolygon = new Polygon(topRing, topAttrs);
                topPolygon.setAltitudeMode(gov.nasa.worldwind.WorldWind.RELATIVE_TO_GROUND);
                topPolygon.setFollowTerrain(false);
                buildingPolygons.add(topPolygon);
            }

            // Create side face polygons
            Color sideColor = new Color(
                    baseColor.red * 0.65f,
                    baseColor.green * 0.65f,
                    baseColor.blue * 0.65f,
                    0.9f
            );

            for (int i = 0; i < baseRing.size() - 1; i++) {
                Position bottomLeft = baseRing.get(i);
                Position bottomRight = baseRing.get(i + 1);
                Position topLeft = topRing.get(i);
                Position topRight = topRing.get(i + 1);

                List<Position> sideFacePositions = new ArrayList<>();
                sideFacePositions.add(bottomLeft);
                sideFacePositions.add(bottomRight);
                sideFacePositions.add(topRight);
                sideFacePositions.add(topLeft);
                sideFacePositions.add(bottomLeft);

                ShapeAttributes sideFaceAttrs = new ShapeAttributes();
                sideFaceAttrs.setInteriorColor(sideColor);
                sideFaceAttrs.setOutlineColor(new Color(
                        sideColor.red * 0.8f,
                        sideColor.green * 0.8f,
                        sideColor.blue * 0.8f,
                        0.4f
                ));
                sideFaceAttrs.setOutlineWidth(0.5f);
                sideFaceAttrs.setDrawInterior(true);
                sideFaceAttrs.setDrawOutline(true);

                Polygon sideFacePolygon = new Polygon(sideFacePositions, sideFaceAttrs);
                sideFacePolygon.setAltitudeMode(gov.nasa.worldwind.WorldWind.RELATIVE_TO_GROUND);
                sideFacePolygon.setFollowTerrain(false);
                buildingPolygons.add(sideFacePolygon);
            }

            // Fill building interior with horizontal slices
            if (buildingHeight > 0 && baseRing.size() >= 3) {
                int numInteriorLayers = Math.max(1, (int) (buildingHeight / 10.0));
                numInteriorLayers = Math.min(numInteriorLayers, 10);

                Color interiorColor = new Color(
                        baseColor.red * 0.7f,
                        baseColor.green * 0.7f,
                        baseColor.blue * 0.7f,
                        0.85f
                );

                ShapeAttributes interiorAttrs = new ShapeAttributes();
                interiorAttrs.setInteriorColor(interiorColor);
                interiorAttrs.setOutlineColor(new Color(0, 0, 0, 0));
                interiorAttrs.setOutlineWidth(0f);
                interiorAttrs.setDrawInterior(true);
                interiorAttrs.setDrawOutline(false);

                for (int layer = 1; layer <= numInteriorLayers; layer++) {
                    double layerHeight = (buildingHeight * layer) / (numInteriorLayers + 1);

                    List<Position> slicePositions = new ArrayList<>();
                    for (ShapefileReader.Point point : ring) {
                        slicePositions.add(Position.fromDegrees(point.y, point.x, layerHeight));
                    }

                    if (slicePositions.size() > 0) {
                        Position first = slicePositions.get(0);
                        Position last = slicePositions.get(slicePositions.size() - 1);
                        if (first.latitude != last.latitude || first.longitude != last.longitude) {
                            slicePositions.add(first);
                        }
                    }

                    Polygon slicePolygon = new Polygon(slicePositions, interiorAttrs);
                    slicePolygon.setAltitudeMode(gov.nasa.worldwind.WorldWind.RELATIVE_TO_GROUND);
                    slicePolygon.setFollowTerrain(false);
                    buildingPolygons.add(slicePolygon);
                }
            }

            // Update height statistics
            if (buildingHeight > 0) {
                buildingsWithHeight++;
                minHeight = Math.min(minHeight, buildingHeight);
                maxHeight = Math.max(maxHeight, buildingHeight);
                totalHeight += buildingHeight;
            }

        } catch (Exception e) {
            Log.w(TAG, "Error creating 3D building: " + e.getMessage());
        }

        return buildingPolygons;
    }


    /**
     * Create a 3D building from a GridCell with complete building structure
     *
     * Creates a 3D building representation with:
     * - Bottom face at ground level (Z=0)
     * - Top face at specified height
     * - 4 vertical side faces connecting bottom and top
     * - Interior horizontal slices for solid appearance
     * - Color based on height using percentile distribution
     * - ABSOLUTE altitude mode (PointZ data already has absolute heights)
     *
     * This method mirrors the implementation in LoadShapeActivity.createBuilding3D
     * but adapted for grid-based rectangular buildings instead of polygon footprints
     *
     * @param cell GridCell containing center coordinates
     * @param gridSize Size of the grid cell in degrees
     * @param height Building height in meters
     * @return List of Polygon renderables forming the 3D building
     */
    private List<Polygon> createGridBuilding3D(GridCell cell, double gridSize, double height) {
        List<Polygon> buildingPolygons = new ArrayList<>();

        try {
            // Calculate grid rectangle's four corner points
            double halfSize = gridSize / 2.0;
            double lat1 = cell.centerLat - halfSize;  // South
            double lat2 = cell.centerLat + halfSize;  // North
            double lon1 = cell.centerLon - halfSize;  // West
            double lon2 = cell.centerLon + halfSize;  // East

            // Get base color for this building based on height
            Color baseColor = getColorByHeight(height);

            // Create baseRing (bottom face, Z=0) - clockwise from bottom-left
            List<Position> baseRing = new ArrayList<>();
            baseRing.add(Position.fromDegrees(lat1, lon1, 0));  // Bottom-left
            baseRing.add(Position.fromDegrees(lat1, lon2, 0));  // Bottom-right
            baseRing.add(Position.fromDegrees(lat2, lon2, 0));  // Top-right
            baseRing.add(Position.fromDegrees(lat2, lon1, 0));  // Top-left
            baseRing.add(Position.fromDegrees(lat1, lon1, 0));  // Close the ring

            // Create topRing (top face, Z=height)
            List<Position> topRing = new ArrayList<>();
            topRing.add(Position.fromDegrees(lat1, lon1, height));  // Bottom-left
            topRing.add(Position.fromDegrees(lat1, lon2, height));  // Bottom-right
            topRing.add(Position.fromDegrees(lat2, lon2, height));  // Top-right
            topRing.add(Position.fromDegrees(lat2, lon1, height));  // Top-left
            topRing.add(Position.fromDegrees(lat1, lon1, height));  // Close the ring

            // Create bottom polygon (dark, semi-transparent)
            if (baseRing.size() >= 3) {
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

                Polygon bottomPolygon = new Polygon(baseRing, bottomAttrs);
                bottomPolygon.setAltitudeMode(gov.nasa.worldwind.WorldWind.ABSOLUTE);
                bottomPolygon.setFollowTerrain(false);
                buildingPolygons.add(bottomPolygon);
            }

            // Create top polygon (full baseColor with 0.6f alpha matching getColorByHeight)
            if (topRing.size() >= 3) {
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

                Polygon topPolygon = new Polygon(topRing, topAttrs);
                topPolygon.setAltitudeMode(gov.nasa.worldwind.WorldWind.ABSOLUTE);
                topPolygon.setFollowTerrain(false);
                buildingPolygons.add(topPolygon);
            }

            // Create 4 side face polygons
            Color sideColor = new Color(
                    baseColor.red * 0.65f,
                    baseColor.green * 0.65f,
                    baseColor.blue * 0.65f,
                    0.9f
            );

            for (int i = 0; i < 4; i++) {
                Position bottomLeft = baseRing.get(i);
                Position bottomRight = baseRing.get(i + 1);
                Position topLeft = topRing.get(i);
                Position topRight = topRing.get(i + 1);

                List<Position> sideFacePositions = new ArrayList<>();
                sideFacePositions.add(bottomLeft);
                sideFacePositions.add(bottomRight);
                sideFacePositions.add(topRight);
                sideFacePositions.add(topLeft);
                sideFacePositions.add(bottomLeft);  // Close the face

                ShapeAttributes sideFaceAttrs = new ShapeAttributes();
                sideFaceAttrs.setInteriorColor(sideColor);
                sideFaceAttrs.setOutlineColor(new Color(
                        sideColor.red * 0.8f,
                        sideColor.green * 0.8f,
                        sideColor.blue * 0.8f,
                        0.4f
                ));
                sideFaceAttrs.setOutlineWidth(0.5f);
                sideFaceAttrs.setDrawInterior(true);
                sideFaceAttrs.setDrawOutline(true);

                Polygon sideFacePolygon = new Polygon(sideFacePositions, sideFaceAttrs);
                sideFacePolygon.setAltitudeMode(gov.nasa.worldwind.WorldWind.ABSOLUTE);
                sideFacePolygon.setFollowTerrain(false);
                buildingPolygons.add(sideFacePolygon);
            }

            // Fill building interior with horizontal slices (1-10 layers based on height)
            if (height > 0) {
                int numInteriorLayers = Math.max(1, (int) (height / 10.0));
                numInteriorLayers = Math.min(numInteriorLayers, 10);

                Color interiorColor = new Color(
                        baseColor.red * 0.7f,
                        baseColor.green * 0.7f,
                        baseColor.blue * 0.7f,
                        0.85f
                );

                ShapeAttributes interiorAttrs = new ShapeAttributes();
                interiorAttrs.setInteriorColor(interiorColor);
                interiorAttrs.setOutlineColor(new Color(0, 0, 0, 0));
                interiorAttrs.setOutlineWidth(0f);
                interiorAttrs.setDrawInterior(true);
                interiorAttrs.setDrawOutline(false);

                for (int layer = 1; layer <= numInteriorLayers; layer++) {
                    double layerHeight = (height * layer) / (numInteriorLayers + 1);

                    List<Position> slicePositions = new ArrayList<>();
                    slicePositions.add(Position.fromDegrees(lat1, lon1, layerHeight));
                    slicePositions.add(Position.fromDegrees(lat1, lon2, layerHeight));
                    slicePositions.add(Position.fromDegrees(lat2, lon2, layerHeight));
                    slicePositions.add(Position.fromDegrees(lat2, lon1, layerHeight));
                    slicePositions.add(Position.fromDegrees(lat1, lon1, layerHeight));  // Close

                    Polygon slicePolygon = new Polygon(slicePositions, interiorAttrs);
                    slicePolygon.setAltitudeMode(gov.nasa.worldwind.WorldWind.ABSOLUTE);
                    slicePolygon.setFollowTerrain(false);
                    buildingPolygons.add(slicePolygon);
                }
            }

            // Update bounding box
            updateBoundingBox(lat1, lon1);
            updateBoundingBox(lat2, lon2);

        } catch (Exception e) {
            Log.w(TAG, "Error creating 3D grid building: " + e.getMessage());
        }

        return buildingPolygons;
    }



    /**
     * Get shape type name from shape type code
     *
     * @param shapeType Shape type code
     * @return Human-readable shape type name
     */
    private String getShapeTypeName(int shapeType) {
        switch (shapeType) {
            case 0: return "Null";
            case 1: return "Point";
            case 3: return "PolyLine";
            case 5: return "Polygon";
            case 8: return "MultiPoint";
            case 11: return "PointZ";
            case 13: return "PolyLineZ";
            case 15: return "PolygonZ";
            default: return "Unknown(" + shapeType + ")";
        }
    }

    /**
     * Extract building height from attributes
     *
     * @param attributes DBF attributes map
     * @return Building height in meters (0 if not found or invalid)
     */
    private double extractHeight(Map<String, Object> attributes) {
        if (attributes == null) return 50.0;

        String[] heightFields = {"height", "HEIGHT", "elevation", "ELEVATION", "elev", "ELEV", "alt", "ALT"};

        for (String field : heightFields) {
            if (attributes.containsKey(field)) {
                Object heightObj = attributes.get(field);
                if (heightObj instanceof Number) {
                    double height = ((Number) heightObj).doubleValue();
                    if (height >= 0 && height <= 10000) {
                        return height;
                    }
                }
            }
        }

        return 50.0;  // Default height if no valid height field found
    }

    /**
     * Get color based on building height (heatmap effect)
     *
     * Uses dynamic percentile-based color mapping for better adaptation to actual data:
     * - < P25: Blue (lowest 25%)
     * - P25-P50: Cyan-Green (25%-50%)
     * - P50-P75: Yellow (50%-75%)
     * - P75-P95: Orange (75%-95%)
     * - > P95: Red (highest 5%)
     *
     * Falls back to fixed thresholds if percentiles are not available
     *
     * @param height Building height in meters
     * @return Corresponding color
     */
    private Color getColorByHeight(double height) {
        // Use dynamic percentile-based thresholds if available
        if (heightP95 > 0) {
            // Dynamic mapping based on actual data distribution
            if (height < heightP25) {
                return new Color(0.3f, 0.5f, 1.0f, 0.9f);       // Blue - lowest 25%
            } else if (height < heightP50) {
                return new Color(0.2f, 0.8f, 0.6f, 0.9f);       // Cyan-Green - 25%-50%
            } else if (height < heightP75) {
                return new Color(1.0f, 0.9f, 0.2f, 0.9f);       // Yellow - 50%-75%
            } else if (height < heightP95) {
                return new Color(1.0f, 0.6f, 0.1f, 0.9f);       // Orange - 75%-95%
            } else {
                return new Color(1.0f, 0.2f, 0.1f, 0.9f);       // Red - highest 5%
            }
        } else {
            // Fallback to fixed thresholds for polygon data
            if (height < 10) return new Color(0.3f, 0.5f, 1.0f, 0.9f);       // Blue - low buildings
            else if (height < 30) return new Color(0.2f, 0.8f, 0.6f, 0.9f);  // Cyan-Green - low-medium
            else if (height < 60) return new Color(1.0f, 0.9f, 0.2f, 0.9f);  // Yellow - medium
            else if (height < 100) return new Color(1.0f, 0.6f, 0.1f, 0.9f); // Orange - medium-high
            else return new Color(1.0f, 0.2f, 0.1f, 0.9f);                   // Red - high buildings
        }
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
     *
     * Camera positioning strategy (OPTIMIZED for point cloud visualization):
     * 1. Calculate data span in degrees and meters
     * 2. Base camera distance = 2x data span (dynamic multiplier)
     * 3. Adjust for building height (minimum 3x max height)
     * 4. Dynamic tilt angle based on height-to-span ratio
     * 5. Range limits: 500m (close-up) to 50km (wide view)
     *
     * For typical 400m × 400m point cloud data:
     * - Data span: ~0.017° (~400m)
     * - Camera range: ~800-1000m
     * - Tilt: 45-75° (optimized for 3D perspective)
     */
    private void positionCamera() {
        gov.nasa.worldwind.geom.LookAt lookAt = new gov.nasa.worldwind.geom.LookAt();

        // Validate coordinates before positioning
        if (minLat == Double.MAX_VALUE) {
            Log.w(TAG, "Using default camera position (no valid coordinates)");
            lookAt.latitude = 28.2;
            lookAt.longitude = 113.0;
            lookAt.altitude = 0;
            lookAt.range = 50000;
            lookAt.heading = 0;
            lookAt.tilt = 60;
            wwd.getNavigator().setAsLookAt(wwd.getGlobe(), lookAt);
            return;
        }

        // Calculate center of bounding box
        lookAt.latitude = (minLat + maxLat) / 2.0;
        lookAt.longitude = (minLon + maxLon) / 2.0;
        lookAt.altitude = 0;

        // Calculate data span in degrees
        double latSpan = maxLat - minLat;
        double lonSpan = maxLon - minLon;
        double dataSpan = Math.max(latSpan, lonSpan);  // Use larger dimension

        // Convert to meters (1 degree ≈ 111km at equator)
        double dataSpanMeters = dataSpan * 111000;

        Log.d(TAG, "=== Camera Positioning ===");
        Log.d(TAG, "Data span: " + String.format("%.6f", dataSpan) + "° (" +
                   String.format("%.1f", dataSpanMeters) + " m)");
        Log.d(TAG, "Latitude span: " + String.format("%.6f", latSpan) + "° (" +
                   String.format("%.1f", latSpan * 111000) + " m)");
        Log.d(TAG, "Longitude span: " + String.format("%.6f", lonSpan) + "° (" +
                   String.format("%.1f", lonSpan * 111000) + " m)");

        // Calculate base camera range: 2x data span for good overview
        double cameraRange = dataSpanMeters * 2.0;

        // Get building/terrain height information
        double maxHeight = globalMaxZ - globalMinZ;
        if (maxHeight <= 0) {
            // For polygon buildings, check if we have height data
            if (buildingsWithHeight > 0 && this.maxHeight > 0) {
                maxHeight = this.maxHeight;
            }
        }

        Log.d(TAG, "Max height: " + String.format("%.2f", maxHeight) + " m");

        // Adjust camera range for building height
        if (maxHeight > 0) {
            // Ensure camera is at least 3x the maximum building height
            double minHeightBasedRange = maxHeight * 3.0;
            double oldRange = cameraRange;
            cameraRange = Math.max(cameraRange, minHeightBasedRange);

            if (cameraRange != oldRange) {
                Log.d(TAG, "Camera range adjusted for building height:");
                Log.d(TAG, "  Original range: " + String.format("%.1f", oldRange) + " m");
                Log.d(TAG, "  Height-based minimum: " + String.format("%.1f", minHeightBasedRange) + " m");
                Log.d(TAG, "  Adjusted range: " + String.format("%.1f", cameraRange) + " m");
            }
        }

        // Apply range limits: 500m minimum (close-up detail) to 50km maximum (wide area)
        double rawRange = cameraRange;
        cameraRange = Math.max(500, Math.min(50000, cameraRange));

        if (cameraRange != rawRange) {
            Log.d(TAG, "Camera range clamped: " + String.format("%.1f", rawRange) +
                       " m -> " + String.format("%.1f", cameraRange) + " m");
        }

        // Calculate dynamic tilt angle based on height-to-span ratio
        // For tall buildings relative to area: use steeper tilt (better side view)
        // For flat/wide areas: use shallower tilt (better overview)
        double heightToSpanRatio = (dataSpanMeters > 0 && maxHeight > 0) ?
                                   (maxHeight / dataSpanMeters) : 0.0;

        // Tilt calculation: 45° base + up to 30° adjustment based on height ratio
        // Result: 45°-75° range
        // - Low buildings (ratio < 0.2): ~45-50° (overview)
        // - Medium buildings (ratio 0.2-0.5): ~50-60° (balanced)
        // - High buildings (ratio > 0.5): ~60-75° (emphasize height)
        double tilt = 45.0 + heightToSpanRatio * 40.0;
        tilt = Math.min(75, Math.max(45, tilt));  // Clamp to 45-75° range

        Log.d(TAG, "Height/Span ratio: " + String.format("%.3f", heightToSpanRatio));
        Log.d(TAG, "Camera tilt: " + String.format("%.1f", tilt) + "°");
        Log.d(TAG, "Camera range: " + String.format("%.1f", cameraRange) + " m");
        Log.d(TAG, "Camera position: (" + String.format("%.6f", lookAt.latitude) +
                   ", " + String.format("%.6f", lookAt.longitude) + ")");

        // Set final camera parameters
        lookAt.range = cameraRange;
        lookAt.heading = 0;  // Face north
        lookAt.tilt = tilt;  // Dynamic tilt angle

        Log.d(TAG, "=== Camera Configuration ===");
        Log.d(TAG, "  Center: (" + String.format("%.6f", lookAt.latitude) + ", " +
              String.format("%.6f", lookAt.longitude) + ")");
        Log.d(TAG, "  Range: " + String.format("%.0f", lookAt.range) + " meters");
        Log.d(TAG, "  Tilt: " + String.format("%.1f", lookAt.tilt) + " degrees");
        Log.d(TAG, "  Heading: " + lookAt.heading + " degrees");
        Log.d(TAG, "===========================");

        wwd.getNavigator().setAsLookAt(wwd.getGlobe(), lookAt);
    }

    /**
     * Log complete shapefile metadata report
     */
    private void logShapefileMetadata() {
        Log.d(TAG, "");
        Log.d(TAG, "========================================");

        if (totalPoints > 0) {
            // PointZ data report
            Log.d(TAG, "    PointZ Shapefile Metadata Report");
            Log.d(TAG, "========================================");
            Log.d(TAG, "File: " + SHAPEFILE_PATH);
            Log.d(TAG, "");
            Log.d(TAG, "Projection:");
            Log.d(TAG, "  - Name: " + projectionInfo);
            Log.d(TAG, "  - Note: Coordinates automatically converted to WGS84");
            Log.d(TAG, "");
            Log.d(TAG, "Geometry:");
            Log.d(TAG, "  - Type: PointZ (3D Point Cloud)");
            Log.d(TAG, "  - Total Points: " + totalPoints);
            Log.d(TAG, "  - Loaded Points: " + loadedPoints);
            Log.d(TAG, "  - Bounding Box:");
            Log.d(TAG, "      Lat: [" + String.format("%.6f", minLat) + ", " + String.format("%.6f", maxLat) + "]");
            Log.d(TAG, "      Lon: [" + String.format("%.6f", minLon) + ", " + String.format("%.6f", maxLon) + "]");
            Log.d(TAG, "");
            Log.d(TAG, "Height Statistics (Absolute):");
            Log.d(TAG, "  - Global min Z: " + String.format("%.2f", globalMinZ) + " meters");
            Log.d(TAG, "  - Global max Z: " + String.format("%.2f", globalMaxZ) + " meters");
            Log.d(TAG, "  - Z range: " + String.format("%.2f", globalMaxZ - globalMinZ) + " meters");
            Log.d(TAG, "  - Normalized to: [0, " + String.format("%.2f", globalMaxZ - globalMinZ) + "]");
            Log.d(TAG, "");
            Log.d(TAG, "Height Percentiles (Relative):");
            Log.d(TAG, "  - P25 (25th percentile): " + String.format("%.2f", heightP25) + " meters");
            Log.d(TAG, "  - P50 (Median): " + String.format("%.2f", heightP50) + " meters");
            Log.d(TAG, "  - P75 (75th percentile): " + String.format("%.2f", heightP75) + " meters");
            Log.d(TAG, "  - P95 (95th percentile): " + String.format("%.2f", heightP95) + " meters");
            Log.d(TAG, "");
            Log.d(TAG, "Point Cloud Processing:");
            Log.d(TAG, "  - Algorithm: Grid aggregation + 3D buildings");
            Log.d(TAG, "  - Total points: " + totalPoints);
            Log.d(TAG, "  - Total grids: " + totalGrids);
            Log.d(TAG, "  - Loaded grids: " + loadedGrids);
            Log.d(TAG, "  - Grid size: " + String.format("%.6f", optimalGridSize) + "° (~" +
                  String.format("%.1f", optimalGridSize * 111000) + "m)");
            Log.d(TAG, "  - Points per grid (avg): " + (totalGrids > 0 ? (totalPoints / totalGrids) : 0));
            Log.d(TAG, "  - Height calculation: 70% avg + 30% max per grid");
            Log.d(TAG, "");
            Log.d(TAG, "Rendering:");
            Log.d(TAG, "  - Total polygons: " + shapefileLayer.count());
            Log.d(TAG, "  - Altitude Mode: ABSOLUTE");
            Log.d(TAG, "  - Color Mapping: Percentile-based (Blue<P25<Cyan<P50<Yellow<P75<Orange<P95<Red)");
            Log.d(TAG, "  - 3D Structure: Bottom + Top + 4 Side Faces + Interior Slices");
            Log.d(TAG, "  - Visualization Quality: Grid-based 3D buildings");
        } else {
            // Polygon data report
            Log.d(TAG, "    Polygon Shapefile Metadata Report");
            Log.d(TAG, "========================================");
            Log.d(TAG, "File: " + SHAPEFILE_PATH);
            Log.d(TAG, "");
            Log.d(TAG, "Projection:");
            Log.d(TAG, "  - Name: " + projectionInfo);
            Log.d(TAG, "  - Note: Coordinates automatically converted to WGS84");
            Log.d(TAG, "");
            Log.d(TAG, "Geometry:");
            Log.d(TAG, "  - Type: Polygon");
            Log.d(TAG, "  - Records: " + loadedPolygons);
            Log.d(TAG, "  - Bounding Box:");
            Log.d(TAG, "      Lat: [" + String.format("%.6f", minLat) + ", " + String.format("%.6f", maxLat) + "]");
            Log.d(TAG, "      Lon: [" + String.format("%.6f", minLon) + ", " + String.format("%.6f", maxLon) + "]");
            Log.d(TAG, "");
            Log.d(TAG, "Building Heights:");
            if (buildingsWithHeight > 0) {
                Log.d(TAG, "  - Buildings with height: " + buildingsWithHeight);
                Log.d(TAG, "  - Height range: [" + String.format("%.2f", minHeight) + ", " + String.format("%.2f", maxHeight) + "] meters");
                Log.d(TAG, "  - Average height: " + String.format("%.2f", totalHeight / buildingsWithHeight) + " meters");
            } else {
                Log.d(TAG, "  - No height data available (using default heights)");
            }
            Log.d(TAG, "");
            Log.d(TAG, "Rendering:");
            Log.d(TAG, "  - Total polygons: " + shapefileLayer.count());
            Log.d(TAG, "  - Altitude Mode: RELATIVE_TO_GROUND");
            Log.d(TAG, "  - Color Mapping: Blue->Green->Yellow->Orange->Red (by height)");
            Log.d(TAG, "  - 3D Structure: Bottom + Top + Side Faces + Interior Slices");
        }

        Log.d(TAG, "========================================");
        Log.d(TAG, "");
    }

    /**
     * Update projection display on UI
     */
    private void updateProjectionDisplay() {
        mainHandler.post(() -> {
            if (projectionText != null) {
                projectionText.setText(projectionInfo);
                Log.d(TAG, "Updated projection display: " + projectionInfo);
            }
        });
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

    /**
     * 网格坐标类
     * 用于标识唯一的网格单元
     */
    private static class GridCoord {
        final int gridX;
        final int gridY;

        GridCoord(int gridX, int gridY) {
            this.gridX = gridX;
            this.gridY = gridY;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            GridCoord that = (GridCoord) o;
            return gridX == that.gridX && gridY == that.gridY;
        }

        @Override
        public int hashCode() {
            return 31 * gridX + gridY;
        }

        @Override
        public String toString() {
            return "Grid(" + gridX + "," + gridY + ")";
        }
    }

    /**
     * 网格单元类
     * 存储网格范围和内部所有点的Z值
     */
    private static class GridCell {
        final double centerLat;
        final double centerLon;
        final List<Double> zValues = new ArrayList<>();

        GridCell(double centerLat, double centerLon) {
            this.centerLat = centerLat;
            this.centerLon = centerLon;
        }

        void addZValue(double z) {
            zValues.add(z);
        }

        double getMinZ() {
            return zValues.isEmpty() ? 0.0 : Collections.min(zValues);
        }

        double getMaxZ() {
            return zValues.isEmpty() ? 0.0 : Collections.max(zValues);
        }

        double getAvgZ() {
            return zValues.isEmpty() ? 0.0 :
                    zValues.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        }

        int getPointCount() {
            return zValues.size();
        }
    }

    /**
     * 点云转3D网格转换器
     * 将点云数据栅格化为规则网格，每个网格单元记录内部点的Z值
     */
    private static class PointCloudTo3DConverter {
        private final double gridSize;  // 网格大小（度）
        private final Map<GridCoord, GridCell> gridMap;
        private double minLat = Double.MAX_VALUE;
        private double maxLat = -Double.MAX_VALUE;
        private double minLon = Double.MAX_VALUE;
        private double maxLon = -Double.MAX_VALUE;
        private int totalPoints = 0;

        /**
         * 构造函数
         * @param gridSize 网格大小（度），例如0.0001度约等于11米
         */
        PointCloudTo3DConverter(double gridSize) {
            this.gridSize = gridSize;
            this.gridMap = new HashMap<>();
        }

        /**
         * 添加点到网格
         * @param lat 纬度（WGS84）
         * @param lon 经度（WGS84）
         * @param z 高程值
         */
        void addPoint(double lat, double lon, double z) {
            // 更新边界
            minLat = Math.min(minLat, lat);
            maxLat = Math.max(maxLat, lat);
            minLon = Math.min(minLon, lon);
            maxLon = Math.max(maxLon, lon);

            // 计算网格坐标
            GridCoord coord = getGridCoord(lat, lon);

            // 获取或创建网格单元
            GridCell cell = gridMap.get(coord);
            if (cell == null) {
                // 计算网格中心坐标
                double centerLat = (coord.gridY + 0.5) * gridSize;
                double centerLon = (coord.gridX + 0.5) * gridSize;
                cell = new GridCell(centerLat, centerLon);
                gridMap.put(coord, cell);
            }

            // 添加Z值
            cell.addZValue(z);
            totalPoints++;
        }

        /**
         * 计算点所属的网格坐标
         */
        private GridCoord getGridCoord(double lat, double lon) {
            int gridX = (int) Math.floor(lon / gridSize);
            int gridY = (int) Math.floor(lat / gridSize);
            return new GridCoord(gridX, gridY);
        }

        /**
         * 获取所有网格单元
         */
        Collection<GridCell> getAllGridCells() {
            return gridMap.values();
        }

        /**
         * 获取总点数
         */
        int getTotalPoints() {
            return totalPoints;
        }

        /**
         * 获取网格数量
         */
        int getGridCount() {
            return gridMap.size();
        }

        /**
         * 获取网格大小
         */
        double getGridSize() {
            return gridSize;
        }

        /**
         * 获取边界框
         */
        double[] getBounds() {
            return new double[]{minLat, maxLat, minLon, maxLon};
        }
    }
}
