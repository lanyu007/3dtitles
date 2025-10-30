package com.example.myapplication.loadkml;

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

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
 * Activity for loading and displaying KML files using WorldWindow
 * 使用 WorldWindow 加载和显示 KML 文件的 Activity
 *
 * This activity loads buildings.kml from raw resources and displays it on a 3D globe.
 * 此 Activity 从资源文件中加载 buildings.kml 并在 3D 地球上显示。
 *
 * Key differences from LoadKMZActivity:
 * 与 LoadKMZActivity 的主要区别：
 * - Directly reads KML file (no ZIP extraction needed)
 * - 直接读取 KML 文件（无需 ZIP 解压）
 * - Handles GB2312 encoding for Chinese characters
 * - 处理 GB2312 编码以支持中文字符
 */
public class LoadKMLActivity extends AppCompatActivity {

    public static void start(Context context) {
        Intent intent = new Intent(context, LoadKMLActivity.class);
        context.startActivity(intent);
    }

    private static final String TAG = "LoadKMLActivity";

    // WorldWindow instance for displaying the globe
    // WorldWindow 实例用于显示地球
    protected WorldWindow wwd;

    // Status text view for displaying loading progress
    // 状态文本视图用于显示加载进度
    protected TextView statusText;

    // Layer for storing KML renderables
    // 用于存储 KML 可渲染对象的图层
    protected RenderableLayer kmlLayer;

    // Executor service for background tasks
    // 用于后台任务的执行器服务
    private ExecutorService executorService;

    // Handler for UI thread operations
    // 用于 UI 线程操作的处理器
    private Handler mainHandler;

    // Bounding box coordinates to track all loaded geometry
    // 边界框坐标用于跟踪所有加载的几何体
    private double minLat = Double.MAX_VALUE;
    private double maxLat = -Double.MAX_VALUE;
    private double minLon = Double.MAX_VALUE;
    private double maxLon = -Double.MAX_VALUE;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_load_kmlactivity);

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

        // Create layer for KML content
        // 为 KML 内容创建图层
        kmlLayer = new RenderableLayer("KML Layer");
        wwd.getLayers().addLayer(kmlLayer);

        // Load KML file
        // 加载 KML 文件
        loadKMLFile();
    }

    /**
     * Load and parse the KML file
     * 加载和解析 KML 文件
     *
     * This method loads buildings.kml from raw resources and parses it in a background thread.
     * 此方法从资源文件中加载 buildings.kml 并在后台线程中解析它。
     */
    private void loadKMLFile() {
        updateStatus("Loading KML file...");
        Log.d(TAG, "Loading KML file...");

        executorService.execute(() -> {
            InputStream kmlInputStream = null;
            try {
                // Open the KML file from raw resources
                // 从资源文件中打开 KML 文件
                kmlInputStream = getResources().openRawResource(R.raw.buildings);

                updateStatus("Parsing KML file...");
                Log.d(TAG, "Parsing KML file...");

                // Parse the KML directly (no ZIP extraction needed)
                // 直接解析 KML（无需 ZIP 解压）
                parseKML(kmlInputStream);

                // Position camera to view the loaded content
                // 定位相机以查看加载的内容
                mainHandler.post(() -> {
                    positionCamera();
                    updateStatus("KML loaded successfully");
                    Log.d(TAG, "KML loaded successfully");
                    wwd.requestRedraw();
                });

            } catch (Exception e) {
                Log.e(TAG, "Error loading KML file", e);
                mainHandler.post(() -> {
                    updateStatus("Error loading KML: " + e.getMessage());
                    Toast.makeText(this, "Failed to load KML: " + e.getMessage(),
                                 Toast.LENGTH_LONG).show();
                });
            } finally {
                // Close the input stream
                // 关闭输入流
                if (kmlInputStream != null) {
                    try {
                        kmlInputStream.close();
                    } catch (Exception e) {
                        Log.e(TAG, "Error closing KML input stream", e);
                    }
                }
            }
        });
    }

    /**
     * Parse KML content from the input stream
     * 从输入流解析 KML 内容
     *
     * Uses XmlPullParser to parse KML elements and extract geometry information.
     * 使用 XmlPullParser 解析 KML 元素并提取几何信息。
     *
     * @param inputStream The input stream containing KML data
     *                    包含 KML 数据的输入流
     * @throws Exception If parsing fails
     *                   如果解析失败
     */
    private void parseKML(InputStream inputStream) throws Exception {
        XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
        factory.setNamespaceAware(true);
        XmlPullParser parser = factory.newPullParser();

        // IMPORTANT: buildings.kml uses GB2312 encoding for Chinese characters
        // 重要：buildings.kml 使用 GB2312 编码处理中文字符
        parser.setInput(new InputStreamReader(inputStream, "GB2312"));

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
                        // Reset state for new placemark
                        // 重置新地标的状态
                        placemarkName = null;
                        coordinates.setLength(0);
                        geometryType = null;
                    } else if ("Point".equals(currentTag)) {
                        geometryType = "Point";
                        Log.d(TAG, "Parsed Point");
                    } else if ("LineString".equals(currentTag)) {
                        geometryType = "LineString";
                        Log.d(TAG, "Parsed LineString");
                    } else if ("Polygon".equals(currentTag)) {
                        geometryType = "Polygon";
                        Log.d(TAG, "Parsed Polygon");
                    }
                    break;

                case XmlPullParser.TEXT:
                    String text = parser.getText().trim();
                    if (!text.isEmpty()) {
                        if ("name".equals(currentTag)) {
                            placemarkName = text;
                            Log.d(TAG, "Placemark name: " + placemarkName);
                        } else if ("coordinates".equals(currentTag)) {
                            coordinates.append(text);
                        }
                    }
                    break;

                case XmlPullParser.END_TAG:
                    String endTag = parser.getName();

                    if ("Placemark".equals(endTag)) {
                        // Create renderable based on geometry type
                        // 根据几何类型创建可渲染对象
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
     * 从 KML 几何体创建可渲染对象
     *
     * @param geometryType Type of geometry (Point, LineString, Polygon)
     *                     几何类型（Point, LineString, Polygon）
     * @param coordinatesStr Coordinate string from KML
     *                       来自 KML 的坐标字符串
     * @param name Name of the placemark
     *             地标名称
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
                            Log.d(TAG, "Created placemark: " + name);
                            break;
                        case "LineString":
                            createPath(positions, name);
                            Log.d(TAG, "Created path: " + name + " with " + positions.size() + " points");
                            break;
                        case "Polygon":
                            createPolygon(positions, name);
                            Log.d(TAG, "Created polygon: " + name + " with " + positions.size() + " points");
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
     * 将 KML 坐标字符串解析为 Position 列表
     *
     * KML format: "lon,lat,alt lon,lat,alt ..."
     * KML 格式："经度,纬度,高度 经度,纬度,高度 ..."
     *
     * @param coordinatesStr Coordinate string from KML
     *                       来自 KML 的坐标字符串
     * @return List of Position objects
     *         Position 对象列表
     */
    private List<Position> parseCoordinates(String coordinatesStr) {
        List<Position> positions = new ArrayList<>();

        // KML coordinates can be separated by whitespace or newlines
        // KML 坐标可以用空格或换行符分隔
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
                    // 更新边界框
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
     * 使用新坐标更新边界框
     *
     * @param latitude Latitude in degrees
     *                 纬度（度）
     * @param longitude Longitude in degrees
     *                  经度（度）
     */
    private void updateBoundingBox(double latitude, double longitude) {
        minLat = Math.min(minLat, latitude);
        maxLat = Math.max(maxLat, latitude);
        minLon = Math.min(minLon, longitude);
        maxLon = Math.max(maxLon, longitude);
    }

    /**
     * Create a Placemark for Point geometry
     * 为 Point 几何体创建 Placemark
     *
     * @param position Position of the point
     *                 点的位置
     * @param name Name of the placemark
     *             地标名称
     */
    private void createPlacemark(Position position, String name) {
        PlacemarkAttributes attrs = new PlacemarkAttributes();
        attrs.setImageColor(new Color(1f, 0f, 0f, 1f)); // Red / 红色
        attrs.setImageScale(2.0);

        Placemark placemark = new Placemark(position, attrs, name);
        placemark.setDisplayName(name != null ? name : "Point");

        kmlLayer.addRenderable(placemark);
        Log.d(TAG, "Created placemark: " + name + " at " + position);
    }

    /**
     * Create a Path for LineString geometry
     * 为 LineString 几何体创建 Path
     *
     * @param positions List of positions defining the path
     *                  定义路径的位置列表
     * @param name Name of the path
     *             路径名称
     */
    private void createPath(List<Position> positions, String name) {
        ShapeAttributes attrs = new ShapeAttributes();
        attrs.setOutlineColor(new Color(1f, 1f, 0f, 1f)); // Yellow / 黄色
        attrs.setOutlineWidth(3f);

        Path path = new Path(positions, attrs);
        path.setFollowTerrain(true);
        path.setDisplayName(name != null ? name : "Path");

        kmlLayer.addRenderable(path);
        Log.d(TAG, "Created path: " + name + " with " + positions.size() + " points");
    }

    /**
     * Create a Polygon for Polygon geometry
     * 为 Polygon 几何体创建 Polygon
     *
     * @param positions List of positions defining the polygon boundary
     *                  定义多边形边界的位置列表
     * @param name Name of the polygon
     *             多边形名称
     */
    private void createPolygon(List<Position> positions, String name) {
        ShapeAttributes attrs = new ShapeAttributes();
        attrs.setInteriorColor(new Color(1f, 0.5f, 0f, 0.5f)); // Semi-transparent orange / 半透明橙色
        attrs.setOutlineColor(new Color(1f, 0.5f, 0f, 1f)); // Orange / 橙色
        attrs.setOutlineWidth(2f);

        Polygon polygon = new Polygon(positions, attrs);
        polygon.setFollowTerrain(true);
        polygon.setDisplayName(name != null ? name : "Polygon");

        kmlLayer.addRenderable(polygon);
        Log.d(TAG, "Created polygon: " + name + " with " + positions.size() + " points");
    }

    /**
     * Position the camera to view the loaded KML content
     * 定位相机以查看加载的 KML 内容
     *
     * Automatically calculates the best camera position based on the bounding box of all loaded geometry.
     * 根据所有加载几何体的边界框自动计算最佳相机位置。
     */
    private void positionCamera() {
        gov.nasa.worldwind.geom.LookAt lookAt = new gov.nasa.worldwind.geom.LookAt();

        // If we have valid bounding box, use it
        // 如果有有效的边界框，使用它
        if (minLat != Double.MAX_VALUE && maxLat != -Double.MAX_VALUE) {
            // Center of bounding box
            // 边界框的中心
            lookAt.latitude = (minLat + maxLat) / 2.0;
            lookAt.longitude = (minLon + maxLon) / 2.0;
            lookAt.altitude = 0;

            // Calculate range based on bounding box size
            // 根据边界框大小计算范围
            double latDiff = maxLat - minLat;
            double lonDiff = maxLon - minLon;
            double maxDiff = Math.max(latDiff, lonDiff);

            // Set range to view entire content (rough estimate: 1 degree ≈ 111km)
            // 设置范围以查看整个内容（粗略估计：1 度 ≈ 111 公里）
            // Add 50% margin for better viewing
            // 添加 50% 边距以获得更好的视角
            lookAt.range = maxDiff * 111000 * 1.5;

            // Ensure minimum and maximum range (5km to 1000km)
            // 确保最小和最大范围（5 公里到 1000 公里）
            lookAt.range = Math.max(5000, Math.min(lookAt.range, 1000000));

            Log.d(TAG, "Camera positioned to bounding box: lat=" + lookAt.latitude +
                    ", lon=" + lookAt.longitude + ", range=" + lookAt.range);
        } else {
            // Fallback to default position if no geometry was loaded
            // 如果没有加载几何体，则回退到默认位置
            lookAt.latitude = 28.2;
            lookAt.longitude = 113.0;
            lookAt.altitude = 0;
            lookAt.range = 50000; // 50km view range / 50 公里视野范围

            Log.d(TAG, "Camera positioned to default location (no geometry found)");
        }

        wwd.getNavigator().setAsLookAt(wwd.getGlobe(), lookAt);
    }

    /**
     * Update status text on UI thread
     * 在 UI 线程上更新状态文本
     *
     * @param message Status message to display
     *                要显示的状态消息
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
        // Pause the WorldWindow's rendering thread
        // 暂停 WorldWindow 的渲染线程
        if (wwd != null) {
            wwd.onPause();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Resume the WorldWindow's rendering thread
        // 恢复 WorldWindow 的渲染线程
        if (wwd != null) {
            wwd.onResume();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Shutdown the executor service
        // 关闭执行器服务
        if (executorService != null) {
            executorService.shutdown();
        }
    }
}
