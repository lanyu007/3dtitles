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
import com.example.myapplication.shpload.ProjectionReader;
import com.example.myapplication.shpload.ShapefileIndexReader;
import com.example.myapplication.shpload.ShapefileReader;

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
 * 使用 WorldWindow 加载和显示 ESRI Shapefile 的 Activity
 *
 * This activity demonstrates loading Shapefile data from assets/data directory
 * 此 Activity 演示从 assets/data 目录加载 Shapefile 数据
 *
 * Features:
 * 功能：
 * - Loads Shapefile geometry (.shp) and attributes (.dbf)
 * - 加载 Shapefile 几何体（.shp）和属性（.dbf）
 * - Creates 3D building representations with height
 * - 创建带高度的 3D 建筑表示
 * - Automatic camera positioning
 * - 自动相机定位
 * - Asynchronous loading with progress feedback
 * - 异步加载并显示进度反馈
 */
public class LoadShapeActivity extends AppCompatActivity {

    public static void start(Context context) {
        Intent intent = new Intent(context, LoadShapeActivity.class);
        context.startActivity(intent);
    }

    private static final String TAG = "LoadShapeActivity";

    // Shapefile paths in assets
    // assets 中的 Shapefile 路径
    private static final String SHAPEFILE_PATH = "data/buildings.shp";
    private static final String DBF_PATH = "data/buildings.dbf";
    private static final String PRJ_PATH = "data/buildings.prj";
    private static final String SHX_PATH = "data/buildings.shx";

    // WorldWind components
    // WorldWind 组件
    protected WorldWindow wwd;
    protected TextView statusText;
    protected RenderableLayer shapefileLayer;
    private ExecutorService executorService;
    private Handler mainHandler;

    // Shapefile readers
    // Shapefile 读取器
    private ProjectionReader projectionReader;
    private ShapefileIndexReader indexReader;

    // Track bounding box of all loaded geometry
    // 跟踪所有加载几何体的边界框
    private double minLat = Double.MAX_VALUE;
    private double maxLat = -Double.MAX_VALUE;
    private double minLon = Double.MAX_VALUE;
    private double maxLon = -Double.MAX_VALUE;

    // Statistics
    // 统计信息
    private int totalPolygons = 0;
    private int loadedPolygons = 0;

    // Building height statistics
    // 建筑高度统计
    private int buildingsWithHeight = 0;
    private double minHeight = Double.MAX_VALUE;
    private double maxHeight = 0.0;
    private double totalHeight = 0.0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_load_shape);

        // Initialize executor service and handler
        // 初始化执行器服务和处理器
        executorService = Executors.newSingleThreadExecutor();
        mainHandler = new Handler(Looper.getMainLooper());

        // Get status text view
        // 获取状态文本视图
        statusText = findViewById(R.id.status_text);

        // Create the WorldWindow (a GLSurfaceView) which displays the globe
        // 创建 WorldWindow（GLSurfaceView）用于显示地球
        wwd = new WorldWindow(this);

        // Add the WorldWindow view object to the layout
        // 将 WorldWindow 视图对象添加到布局中
        FrameLayout globeLayout = findViewById(R.id.globe);
        globeLayout.addView(wwd);

        // Setup the WorldWindow's layers
        // 设置 WorldWindow 的图层
        wwd.getLayers().addLayer(new BackgroundLayer());
        wwd.getLayers().addLayer(new BlueMarbleLandsatLayer());
        wwd.getLayers().addLayer(new AtmosphereLayer());

        // Setup the WorldWindow's elevation coverages
        // 设置 WorldWindow 的高程覆盖
        wwd.getGlobe().getElevationModel().addCoverage(new BasicElevationCoverage());

        // Create layer for Shapefile content
        // 为 Shapefile 内容创建图层
        shapefileLayer = new RenderableLayer("Shapefile Layer");
        wwd.getLayers().addLayer(shapefileLayer);

        // Load Shapefile
        // 加载 Shapefile
        loadShapefile();
    }

    /**
     * Load and parse the Shapefile data
     * 加载和解析 Shapefile 数据
     */
    private void loadShapefile() {
        updateStatus("Loading Shapefile...");
        Log.d(TAG, "Starting Shapefile load from: " + SHAPEFILE_PATH);

        executorService.execute(() -> {
            try {
                // Step 1: Load optional projection info
                // 步骤 1：加载可选的投影信息
                try {
                    InputStream prjStream = getAssets().open(PRJ_PATH);
                    projectionReader = new ProjectionReader();
                    projectionReader.read(prjStream);
                    prjStream.close();
//                    Log.d(TAG, "Loaded projection: " + projectionReader.getProjectionInfo());
                } catch (Exception e) {
                    Log.w(TAG, "No projection file found or error reading it: " + e.getMessage());
                }

                // Step 2: Load optional shapefile index
                // 步骤 2：加载可选的 shapefile 索引
                try {
                    InputStream shxStream = getAssets().open(SHX_PATH);
                    indexReader = new ShapefileIndexReader();
                    indexReader.read(shxStream);
                    shxStream.close();
                    Log.d(TAG, "Loaded index with " + indexReader.getRecordCount() + " records");
                } catch (Exception e) {
                    Log.w(TAG, "No index file found or error reading it: " + e.getMessage());
                }

                // Step 3: Load main geometry file (.shp)
                // 步骤 3：加载主几何体文件（.shp）
                updateStatus("Reading geometry...");
                InputStream shpStream = getAssets().open(SHAPEFILE_PATH);
                ShapefileReader shpReader = new ShapefileReader();
                shpReader.read(shpStream);
                shpStream.close();

                totalPolygons = shpReader.getRecordCount();
//                Log.d(TAG, "Loaded " + totalPolygons + " polygons from shapefile");

                // Step 4: Load attribute data (.dbf)
                // 步骤 4：加载属性数据（.dbf）
                DBFReader dbfReader = null;
                try {
                    InputStream dbfStream = getAssets().open(DBF_PATH);
                    dbfReader = new DBFReader();
                    dbfReader.read(dbfStream);
                    dbfStream.close();
                    Log.d(TAG, "Loaded " + dbfReader.getRecordCount() + " attribute records");
                } catch (Exception e) {
                    Log.w(TAG, "No DBF file found or error reading it: " + e.getMessage());
                }

                // Step 5: Create 3D buildings from shapefile data
                // 步骤 5：从 shapefile 数据创建 3D 建筑
                updateStatus("Creating 3D buildings...");
                createBuildings(shpReader, dbfReader);

                // Step 6: Position camera
                // 步骤 6：定位相机
                mainHandler.post(() -> {
                    positionCamera();
                    updateStatus(String.format("Loaded %d buildings", loadedPolygons));
                    wwd.requestRedraw();
                });

            } catch (Exception e) {
                Log.e(TAG, "Error loading shapefile", e);
                mainHandler.post(() -> {
                    updateStatus("Error: " + e.getMessage());
                    Toast.makeText(this, "Failed to load shapefile: " + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    /**
     * Create 3D building representations from shapefile data
     * 从 shapefile 数据创建 3D 建筑表示
     */
    private void createBuildings(ShapefileReader shpReader, DBFReader dbfReader) {
        List<ShapefileReader.PolygonRecord> polygons = shpReader.getPolygonRecords();

        for (int i = 0; i < polygons.size(); i++) {
            ShapefileReader.PolygonRecord record = polygons.get(i);

            // Get attributes for this record
            // 获取此记录的属性
            Map<String, Object> attributes = null;
            if (dbfReader != null && i < dbfReader.getRecordCount()) {
                attributes = dbfReader.getRecord(i);
            }

            // Create 3D building
            // 创建 3D 建筑
            // 使用方案B（简化拉伸）渲染3D建筑，性能更优
            List<Polygon> buildingPolygons = createBuilding3D(record, attributes);
//            List<Polygon> buildingPolygons = createOtherBuilding3D(record, attributes);

            // Add to layer on UI thread
            // 在 UI 线程上添加到图层
            final int polygonIndex = i;
            mainHandler.post(() -> {
                for (Polygon polygon : buildingPolygons) {
                    shapefileLayer.addRenderable(polygon);
                }
                loadedPolygons++;

                // Update status periodically
                // 定期更新状态
                if (polygonIndex % 10 == 0) {
                    updateStatus(String.format("Loading: %d/%d buildings", loadedPolygons, totalPolygons));
                    wwd.requestRedraw();
                }
            });
        }
    }

    /**
     * Create a 3D building from a Shapefile polygon record
     * 从 Shapefile 多边形记录创建 3D 建筑
     */
    private List<Polygon> createBuilding3D(ShapefileReader.PolygonRecord record,
                                           Map<String, Object> attributes) {
        List<Polygon> buildingPolygons = new ArrayList<>();

        try {
            // Use the first part (outer ring)
            // 使用第一部分（外环）
            List<ShapefileReader.Point> ring = record.parts.get(0);

            // Extract building height
            // 提取建筑高度
            double buildingHeight = extractHeight(attributes);

            // Get base color for this building based on height
            // 根据高度获取此建筑的基础颜色
            Color baseColor = getColorByHeight(buildingHeight);

            // Create shared Position lists (baseRing and topRing)
            // 创建共享的 Position 列表（baseRing 和 topRing）
            List<Position> baseRing = new ArrayList<>();
            List<Position> topRing = new ArrayList<>();

            for (ShapefileReader.Point point : ring) {
                baseRing.add(Position.fromDegrees(point.y, point.x, 0));
                topRing.add(Position.fromDegrees(point.y, point.x, buildingHeight));
                updateBoundingBox(point.y, point.x);
            }

            // Ensure both rings are closed
            // 确保两个环都是闭合的
            if (baseRing.size() > 0) {
                Position first = baseRing.get(0);
                Position last = baseRing.get(baseRing.size() - 1);
                if (first.latitude != last.latitude || first.longitude != last.longitude) {
                    baseRing.add(first);
                    topRing.add(topRing.get(0));
                }
            }

            // Create bottom polygon
            // 创建底面多边形
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
            // 创建顶面多边形
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
            // 创建侧面多边形
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
            // 用水平切片填充建筑内部
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
            // 更新高度统计
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
     * Create a 3D building using Simplified Approach A (Top + Side Faces Only)
     * 使用简化方案 A 创建 3D 建筑（仅顶面 + 侧面）
     *
     * IMPORTANT: WorldWind Android SDK does not support setExtrusionHeight(),
     * setEnableCap(), or setEnableSides() methods. These are only available
     * in WorldWind Java Desktop SDK.
     *
     * 重要提示：WorldWind Android SDK 不支持 setExtrusionHeight()、
     * setEnableCap() 或 setEnableSides() 方法。这些方法仅在 WorldWind Java
     * 桌面版 SDK 中可用。
     *
     * This implementation manually constructs 3D buildings by creating separate
     * polygons for the top face and each side face. This is a simplified version
     * of full Approach A - it omits the bottom face and interior slices to improve
     * performance while maintaining a good visual effect.
     *
     * 本实现通过手动为顶面和每个侧面创建独立的多边形来构建 3D 建筑。这是完整方案 A
     * 的简化版本 - 省略了底面和内部切片以提高性能，同时保持良好的视觉效果。
     *
     * Optimization vs Full Approach A:
     * 相比完整方案 A 的优化：
     * - NO bottom face (not visible from above, saves polygons)
     * - 无底面（从上方不可见，节省多边形）
     * - NO interior slices (reduces polygon count significantly)
     * - 无内部切片（显著减少多边形数量）
     * - ONLY top face + side faces (essential for 3D appearance)
     * - 仅顶面 + 侧面（3D 外观的基本要素）
     *
     * @param record Shapefile polygon record containing geometry
     * @param attributes Attribute map containing building properties (e.g., height)
     * @return List of polygons representing the 3D building
     */
    private List<Polygon> createOtherBuilding3D(ShapefileReader.PolygonRecord record,
                                                 Map<String, Object> attributes) {
        List<Polygon> buildingPolygons = new ArrayList<>();

        try {
            // Use the first part (outer ring)
            // 使用第一部分（外环）
            List<ShapefileReader.Point> ring = record.parts.get(0);

            // Extract building height from attributes
            // 从属性中提取建筑高度
            double buildingHeight = extractHeight(attributes);

            // Get base color for this building based on height
            // 根据高度获取此建筑的基础颜色
            Color baseColor = getColorByHeight(buildingHeight);

            // Create shared Position lists (baseRing at Z=0 and topRing at Z=buildingHeight)
            // 创建共享的 Position 列表（baseRing 在 Z=0，topRing 在 Z=buildingHeight）
            List<Position> baseRing = new ArrayList<>();
            List<Position> topRing = new ArrayList<>();

            for (ShapefileReader.Point point : ring) {
                baseRing.add(Position.fromDegrees(point.y, point.x, 0));
                topRing.add(Position.fromDegrees(point.y, point.x, buildingHeight));
                updateBoundingBox(point.y, point.x);
            }

            // Ensure both rings are closed
            // 确保两个环都闭合
            if (baseRing.size() > 0) {
                Position first = baseRing.get(0);
                Position last = baseRing.get(baseRing.size() - 1);
                if (first.latitude != last.latitude || first.longitude != last.longitude) {
                    baseRing.add(first);
                    topRing.add(topRing.get(0));
                }
            }

            // Create top polygon (roof)
            // 创建顶面多边形（屋顶）
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

            // Create side face polygons (walls)
            // 创建侧面多边形（墙壁）
            // Each edge becomes a vertical rectangular face
            // 每条边变成一个垂直的矩形面
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

                // Create a quadrilateral for each side face
                // 为每个侧面创建一个四边形
                List<Position> sideFacePositions = new ArrayList<>();
                sideFacePositions.add(bottomLeft);
                sideFacePositions.add(bottomRight);
                sideFacePositions.add(topRight);
                sideFacePositions.add(topLeft);
                sideFacePositions.add(bottomLeft); // Close the face

                ShapeAttributes sideFaceAttrs = new ShapeAttributes();
                sideFaceAttrs.setInteriorColor(sideColor);
                sideFaceAttrs.setOutlineColor(new Color(
                        baseColor.red * 0.5f,
                        baseColor.green * 0.5f,
                        baseColor.blue * 0.5f,
                        0.8f
                ));
                sideFaceAttrs.setOutlineWidth(1f);
                sideFaceAttrs.setDrawInterior(true);
                sideFaceAttrs.setDrawOutline(true);

                Polygon sideFacePolygon = new Polygon(sideFacePositions, sideFaceAttrs);
                sideFacePolygon.setAltitudeMode(gov.nasa.worldwind.WorldWind.RELATIVE_TO_GROUND);
                sideFacePolygon.setFollowTerrain(false);
                buildingPolygons.add(sideFacePolygon);
            }

            // Update height statistics
            // 更新高度统计
            if (buildingHeight > 0) {
                buildingsWithHeight++;
                minHeight = Math.min(minHeight, buildingHeight);
                maxHeight = Math.max(maxHeight, buildingHeight);
                totalHeight += buildingHeight;
            }

        } catch (Exception e) {
            Log.w(TAG, "Error creating 3D building with simplified approach A: " + e.getMessage());
        }

        return buildingPolygons;
    }

    /**
     * Extract building height from attributes
     * 从属性中提取建筑高度
     */
    private double extractHeight(Map<String, Object> attributes) {
        if (attributes == null) return 0.0;

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

        return 0.0;
    }

    /**
     * Get color based on building height
     * 根据建筑高度获取颜色
     */
    private Color getColorByHeight(double height) {
        if (height < 10) return new Color(0.5f, 0.7f, 1.0f, 0.6f);      // Blue
        else if (height < 30) return new Color(0.3f, 0.8f, 0.3f, 0.6f); // Green
        else if (height < 60) return new Color(1.0f, 0.9f, 0.3f, 0.6f); // Yellow
        else if (height < 100) return new Color(1.0f, 0.6f, 0.2f, 0.6f);// Orange
        else return new Color(1.0f, 0.3f, 0.2f, 0.6f);                  // Red
    }

    /**
     * Update the bounding box with a new coordinate
     * 使用新坐标更新边界框
     */
    private void updateBoundingBox(double latitude, double longitude) {
        minLat = Math.min(minLat, latitude);
        maxLat = Math.max(maxLat, latitude);
        minLon = Math.min(minLon, longitude);
        maxLon = Math.max(maxLon, longitude);
    }

    /**
     * Position the camera to view all loaded buildings
     * 定位相机以查看所有加载的建筑
     */
    private void positionCamera() {
        gov.nasa.worldwind.geom.LookAt lookAt = new gov.nasa.worldwind.geom.LookAt();

        if (minLat != Double.MAX_VALUE && maxLat != -Double.MAX_VALUE) {
            lookAt.latitude = (minLat + maxLat) / 2.0;
            lookAt.longitude = (minLon + maxLon) / 2.0;
            lookAt.altitude = 0;

            double latDiff = maxLat - minLat;
            double lonDiff = maxLon - minLon;
            double maxDiff = Math.max(latDiff, lonDiff);

            lookAt.range = maxDiff * 111000 * 1.5;
            lookAt.range = Math.max(5000, Math.min(lookAt.range, 1000000));

            Log.d(TAG, "Camera positioned to bounding box: lat=" + lookAt.latitude +
                    ", lon=" + lookAt.longitude + ", range=" + lookAt.range);
        } else {
            lookAt.latitude = 30.0;
            lookAt.longitude = 115.0;
            lookAt.altitude = 0;
            lookAt.range = 50000;
            Log.d(TAG, "Camera positioned to default location");
        }

        wwd.getNavigator().setAsLookAt(wwd.getGlobe(), lookAt);
    }

    /**
     * Update status text on UI thread
     * 在 UI 线程上更新状态文本
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