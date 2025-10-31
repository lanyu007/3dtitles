package com.example.myapplication.geoload;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
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

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import gov.nasa.worldwind.WorldWindow;
import gov.nasa.worldwind.geom.LookAt;
import gov.nasa.worldwind.geom.Sector;
import gov.nasa.worldwind.globe.BasicElevationCoverage;
import gov.nasa.worldwind.layer.BackgroundLayer;
import gov.nasa.worldwind.layer.BlueMarbleLandsatLayer;
import gov.nasa.worldwind.layer.RenderableLayer;
import gov.nasa.worldwind.render.Color;
import gov.nasa.worldwind.render.ImageSource;
import gov.nasa.worldwind.shape.SurfaceImage;

/**
 * Activity for loading and displaying GeoTIFF files using WorldWindow
 *
 * GeoTIFF is a raster image format that includes geographic metadata, allowing
 * images to be positioned and displayed on geographic coordinates. This activity
 * demonstrates loading a GeoTIFF file along with its World File (.tfw) which
 * contains the geographic transformation parameters.
 *
 * 用于使用 WorldWindow 加载和显示 GeoTIFF 文件的 Activity
 *
 * GeoTIFF 是包含地理元数据的栅格图像格式，允许图像定位和显示在地理坐标上。
 * 此 Activity 演示加载 GeoTIFF 文件及其包含地理转换参数的世界文件（.tfw）。
 *
 * Architecture Overview / 架构概述:
 * 1. WorldWindow initialization with base layers / WorldWindow 初始化及基础图层
 * 2. Asynchronous loading of GeoTIFF data / 异步加载 GeoTIFF 数据
 * 3. World File (.tfw) parsing for geographic parameters / 世界文件解析获取地理参数
 * 4. TIFF image reading (currently using placeholder) / TIFF 图像读取（当前使用占位符）
 * 5. SurfaceImage creation and display / SurfaceImage 创建和显示
 * 6. Automatic camera positioning / 自动相机定位
 *
 * @author WorldWind Team
 * @version 1.0
 */
public class LoadGEOActivity extends AppCompatActivity {

    public static void start(Context context) {
        Intent intent = new Intent(context, LoadGEOActivity.class);
        context.startActivity(intent);
    }

    // Constants / 常量
    private static final String TAG = "LoadGEOActivity";

    // GeoTIFF file paths in assets directory / assets 目录中的 GeoTIFF 文件路径
    private static final String GEOTIFF_PATH = "geodata/dem.tif";
    private static final String TFW_PATH = "geodata/dem.tfw";

    // WorldWind components / WorldWind 组件
    protected WorldWindow wwd;
    protected TextView statusText;
    protected RenderableLayer geoLayer;
    private ExecutorService executorService;
    private Handler mainHandler;

    // GeoTIFF metadata from World File / 从世界文件获取的 GeoTIFF 元数据
    // World File format (.tfw) contains 6 lines / 世界文件格式（.tfw）包含 6 行：
    // Line 1: X pixel width (map units per pixel) / X 方向像素宽度（地图单位/像素）
    // Line 2: Y-axis rotation (usually 0) / Y 轴旋转（通常为 0）
    // Line 3: X-axis rotation (usually 0) / X 轴旋转（通常为 0）
    // Line 4: Y pixel height (negative, map units per pixel) / Y 方向像素高度（负值，地图单位/像素）
    // Line 5: Top-left corner X coordinate / 左上角 X 坐标
    // Line 6: Top-left corner Y coordinate / 左上角 Y 坐标
    private double pixelWidth = 0.01;      // Default from dem.tfw / dem.tfw 中的默认值
    private double rotationY = 0.0;        // Y-axis rotation / Y 轴旋转
    private double rotationX = 0.0;        // X-axis rotation / X 轴旋转
    private double pixelHeight = -0.01;    // Negative for Y direction / Y 方向为负值
    private double topLeftX = 625.0;       // Left-top corner X / 左上角 X 坐标
    private double topLeftY = 4702.0;      // Left-top corner Y / 左上角 Y 坐标

    // Image dimensions / 图像尺寸
    private int imageWidth = 4600;         // TIFF image width in pixels / TIFF 图像宽度（像素）
    private int imageHeight = 4200;        // TIFF image height in pixels / TIFF 图像高度（像素）

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_load_geoactivity);

        Log.d(TAG, "=== LoadGEOActivity started ===");

        // Initialize executor service and handler for async operations
        // 初始化执行器服务和处理器用于异步操作
        executorService = Executors.newSingleThreadExecutor();
        mainHandler = new Handler(Looper.getMainLooper());

        // Get status text view for displaying loading progress
        // 获取状态文本视图用于显示加载进度
        statusText = findViewById(R.id.status_text);

        // Initialize WorldWindow
        // 初始化 WorldWindow
        initializeWorldWindow();

        // Start loading GeoTIFF file
        // 开始加载 GeoTIFF 文件
        loadGeoTIFF();
    }

    /**
     * Initialize WorldWindow with base layers and elevation coverage
     *
     * Sets up the 3D globe view with:
     * - Background layer (stars)
     * - Blue Marble + Landsat imagery
     * - Atmosphere layer (for realistic sky rendering)
     * - Basic elevation coverage (for terrain)
     * - Renderable layer for GeoTIFF content
     *
     * 初始化 WorldWindow 并添加基础图层和高程覆盖
     *
     * 设置 3D 地球视图包括：
     * - 背景图层（星空）
     * - Blue Marble + Landsat 影像
     * - 大气层（用于真实的天空渲染）
     * - 基础高程覆盖（用于地形）
     * - 用于 GeoTIFF 内容的可渲染图层
     */
    private void initializeWorldWindow() {
        Log.d(TAG, "Initializing WorldWindow...");

        // Create the WorldWindow (a GLSurfaceView) which displays the globe
        // 创建 WorldWindow（GLSurfaceView）用于显示地球
        wwd = new WorldWindow(this);

        // Add the WorldWindow view object to the layout
        // 将 WorldWindow 视图对象添加到布局
        FrameLayout globeLayout = findViewById(R.id.globe);
        globeLayout.addView(wwd);

        // Setup the WorldWindow's layers
        // 设置 WorldWindow 的图层
        wwd.getLayers().addLayer(new BackgroundLayer());
        wwd.getLayers().addLayer(new BlueMarbleLandsatLayer());
        wwd.getLayers().addLayer(new AtmosphereLayer());

        // Setup the WorldWindow's elevation coverages for terrain
        // 设置 WorldWindow 的高程覆盖用于地形
        wwd.getGlobe().getElevationModel().addCoverage(new BasicElevationCoverage());

        // Create layer for GeoTIFF content
        // 创建用于 GeoTIFF 内容的图层
        geoLayer = new RenderableLayer("GeoTIFF Layer");
        wwd.getLayers().addLayer(geoLayer);

        Log.d(TAG, "WorldWindow initialized with base layers");
    }

    /**
     * Load and display the GeoTIFF file
     *
     * This method runs asynchronously to avoid blocking the UI thread.
     * The loading process involves:
     * 1. Reading and parsing the World File (.tfw) for geographic parameters
     * 2. Reading the TIFF image data (currently using placeholder bitmap)
     * 3. Creating a SurfaceImage to display the raster on the globe
     * 4. Positioning the camera to view the loaded data
     *
     * 加载并显示 GeoTIFF 文件
     *
     * 此方法异步运行以避免阻塞 UI 线程。
     * 加载过程包括：
     * 1. 读取和解析世界文件（.tfw）以获取地理参数
     * 2. 读取 TIFF 图像数据（当前使用占位符位图）
     * 3. 创建 SurfaceImage 以在地球上显示栅格
     * 4. 定位相机以查看加载的数据
     */
    private void loadGeoTIFF() {
        updateStatus("Loading GeoTIFF file...");
        Log.d(TAG, "Starting GeoTIFF loading process");
        Log.d(TAG, "TIFF file path: " + GEOTIFF_PATH);
        Log.d(TAG, "World file path: " + TFW_PATH);
        Log.d(TAG, "Expected TIFF file size: 73.7 MB");
        Log.d(TAG, "Expected image dimensions: " + imageWidth + " x " + imageHeight + " pixels");

        executorService.execute(() -> {
            try {
                // Step 1: Read and parse World File for geographic transformation
                // 步骤 1：读取并解析世界文件以获取地理变换参数
                updateStatus("Reading World File (.tfw)...");
                readWorldFile();

                // Log the parsed World File parameters
                // 记录解析的世界文件参数
                Log.d(TAG, "=== World File Parameters ===");
                Log.d(TAG, "Pixel width (X): " + pixelWidth + " map units/pixel");
                Log.d(TAG, "Y-axis rotation: " + rotationY);
                Log.d(TAG, "X-axis rotation: " + rotationX);
                Log.d(TAG, "Pixel height (Y): " + pixelHeight + " map units/pixel");
                Log.d(TAG, "Top-left X coordinate: " + topLeftX + " map units");
                Log.d(TAG, "Top-left Y coordinate: " + topLeftY + " map units");

                // Calculate and log the geographic extent
                // 计算并记录地理范围
                double bottomRightX = topLeftX + (imageWidth * Math.abs(pixelWidth));
                double bottomRightY = topLeftY + (imageHeight * pixelHeight); // pixelHeight is negative
                Log.d(TAG, "=== Calculated Geographic Extent ===");
                Log.d(TAG, "Top-left corner: (" + topLeftX + ", " + topLeftY + ")");
                Log.d(TAG, "Bottom-right corner: (" + bottomRightX + ", " + bottomRightY + ")");
                Log.d(TAG, "Width: " + (bottomRightX - topLeftX) + " map units");
                Log.d(TAG, "Height: " + (topLeftY - bottomRightY) + " map units");

                // Step 2: Read TIFF image
                // 步骤 2：读取 TIFF 图像
                updateStatus("Reading TIFF image...");
                Bitmap bitmap = readTIFFImage();
                Log.d(TAG, "TIFF image loaded: " + bitmap.getWidth() + " x " +
                      bitmap.getHeight() + " pixels");

                // Step 3: Display the GeoTIFF on the globe
                // 步骤 3：在地球上显示 GeoTIFF
                updateStatus("Creating surface image...");
                displayGeoTIFF(bitmap);

                // Step 4: Position camera to view the GeoTIFF
                // 步骤 4：定位相机以查看 GeoTIFF
                mainHandler.post(() -> {
                    positionCamera();
                    updateStatus("GeoTIFF loaded successfully");
                    wwd.requestRedraw();
                    Log.d(TAG, "=== GeoTIFF loading complete ===");
                });

            } catch (Exception e) {
                Log.e(TAG, "Error loading GeoTIFF", e);
                mainHandler.post(() -> {
                    updateStatus("Error: " + e.getMessage());
                    Toast.makeText(this, "Failed to load GeoTIFF: " + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    /**
     * Read and parse the World File (.tfw)
     *
     * World files are plain text files with 6 lines containing affine transformation
     * parameters that define how image pixel coordinates map to real-world coordinates.
     *
     * Format:
     * Line 1: A - Pixel width in X direction (map units per pixel)
     * Line 2: D - Rotation about Y axis (usually 0 for north-up images)
     * Line 3: B - Rotation about X axis (usually 0 for north-up images)
     * Line 4: E - Pixel height in Y direction (negative for standard maps)
     * Line 5: C - X coordinate of upper-left pixel center
     * Line 6: F - Y coordinate of upper-left pixel center
     *
     * The transformation from pixel (row, col) to map (x, y) is:
     * x = A*col + B*row + C
     * y = D*col + E*row + F
     *
     * 读取并解析世界文件（.tfw）
     *
     * 世界文件是纯文本文件，包含 6 行仿射变换参数，定义图像像素坐标如何映射到真实世界坐标。
     *
     * 格式：
     * 第 1 行：A - X 方向像素宽度（地图单位/像素）
     * 第 2 行：D - 绕 Y 轴旋转（北向图像通常为 0）
     * 第 3 行：B - 绕 X 轴旋转（北向图像通常为 0）
     * 第 4 行：E - Y 方向像素高度（标准地图为负值）
     * 第 5 行：C - 左上角像素中心的 X 坐标
     * 第 6 行：F - 左上角像素中心的 Y 坐标
     *
     * 从像素 (行, 列) 到地图 (x, y) 的转换公式：
     * x = A*col + B*row + C
     * y = D*col + E*row + F
     *
     * @throws Exception if the file cannot be read or parsed
     */
    private void readWorldFile() throws Exception {
        InputStream inputStream = getAssets().open(TFW_PATH);
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));

        try {
            // Read the 6 lines of the World File
            // 读取世界文件的 6 行
            String line1 = reader.readLine(); // Pixel width (X)
            String line2 = reader.readLine(); // Rotation Y
            String line3 = reader.readLine(); // Rotation X
            String line4 = reader.readLine(); // Pixel height (Y, negative)
            String line5 = reader.readLine(); // Top-left X
            String line6 = reader.readLine(); // Top-left Y

            // Parse the values, handling scientific notation
            // 解析值，处理科学计数法
            if (line1 != null) pixelWidth = Double.parseDouble(line1.trim());
            if (line2 != null) rotationY = Double.parseDouble(line2.trim());
            if (line3 != null) rotationX = Double.parseDouble(line3.trim());
            if (line4 != null) pixelHeight = Double.parseDouble(line4.trim());
            if (line5 != null) topLeftX = Double.parseDouble(line5.trim());
            if (line6 != null) topLeftY = Double.parseDouble(line6.trim());

            Log.d(TAG, "World File parsed successfully");

            // Validate the parameters
            // 验证参数
            if (Math.abs(rotationY) > 0.001 || Math.abs(rotationX) > 0.001) {
                Log.w(TAG, "Warning: Image has rotation (not north-up). " +
                          "Rotations are not fully supported in this implementation.");
            }

        } finally {
            reader.close();
            inputStream.close();
        }
    }

    /**
     * Read the TIFF image data
     *
     * IMPORTANT: Android does not natively support TIFF format.
     * This is a PLACEHOLDER IMPLEMENTATION that creates a colored bitmap
     * for demonstration purposes.
     *
     * For production use, you need to add a TIFF decoding library:
     *
     * Option 1: Use imageio-ext (recommended for GeoTIFF)
     * Add to build.gradle:
     *   implementation 'org.geosolutionsext.imageio-ext:imageio-ext-tiff:1.3.5'
     *
     * Option 2: Use Android-compatible TIFF library
     *   implementation 'com.github.beyka:androidtiffbitmapfactory:0.9.3.3'
     *
     * Option 3: Pre-convert TIFF to PNG/JPEG on server side
     *
     * 读取 TIFF 图像数据
     *
     * 重要：Android 原生不支持 TIFF 格式。
     * 这是一个占位符实现，创建彩色位图用于演示目的。
     *
     * 对于生产使用，您需要添加 TIFF 解码库：
     *
     * 选项 1：使用 imageio-ext（推荐用于 GeoTIFF）
     * 添加到 build.gradle：
     *   implementation 'org.geosolutionsext.imageio-ext:imageio-ext-tiff:1.3.5'
     *
     * 选项 2：使用 Android 兼容的 TIFF 库
     *   implementation 'com.github.beyka:androidtiffbitmapfactory:0.9.3.3'
     *
     * 选项 3：在服务器端预先将 TIFF 转换为 PNG/JPEG
     *
     * @return Placeholder bitmap representing the TIFF image / 代表 TIFF 图像的占位符位图
     * @throws Exception if image cannot be created / 如果无法创建图像
     */
    private Bitmap readTIFFImage() throws Exception {
        Log.d(TAG, "Creating placeholder bitmap (TIFF decoding not implemented)");
        Log.w(TAG, "TODO: Add TIFF decoding library for production use");

        // Check available memory before creating large bitmap
        // 在创建大位图之前检查可用内存
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        long usedMemory = runtime.totalMemory() - runtime.freeMemory();
        long availableMemory = maxMemory - usedMemory;

        Log.d(TAG, "Memory status:");
        Log.d(TAG, "  Max memory: " + (maxMemory / 1048576) + " MB");
        Log.d(TAG, "  Used memory: " + (usedMemory / 1048576) + " MB");
        Log.d(TAG, "  Available memory: " + (availableMemory / 1048576) + " MB");

        // Calculate estimated bitmap size
        // 计算估计的位图大小
        long estimatedSize = (long) imageWidth * imageHeight * 4; // ARGB_8888 = 4 bytes per pixel
        Log.d(TAG, "Estimated bitmap size: " + (estimatedSize / 1048576) + " MB");

        if (availableMemory < estimatedSize * 2) {
            Log.w(TAG, "Warning: Low memory. Consider downsampling the image.");
            // For this demo, we'll create a smaller bitmap
            // 对于此演示，我们将创建一个较小的位图
            imageWidth = Math.min(imageWidth, 1024);
            imageHeight = Math.min(imageHeight, 1024);
            Log.d(TAG, "Reduced bitmap size to: " + imageWidth + " x " + imageHeight);
        }

        // Create a placeholder bitmap with gradient color
        // 创建带有渐变色的占位符位图
        Bitmap bitmap = Bitmap.createBitmap(imageWidth, imageHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        // Fill with semi-transparent blue-green color to represent DEM data
        // 填充半透明蓝绿色以表示 DEM 数据
        // In a real implementation, this would be elevation data with color mapping
        // 在实际实现中，这将是带有颜色映射的高程数据
        int color = android.graphics.Color.argb(128, 100, 150, 200);
        canvas.drawColor(color);

        // Draw a border to make the extent visible
        // 绘制边框以使范围可见
        android.graphics.Paint paint = new android.graphics.Paint();
        paint.setColor(android.graphics.Color.argb(255, 255, 0, 0)); // Red border
        paint.setStyle(android.graphics.Paint.Style.STROKE);
        paint.setStrokeWidth(5);
        canvas.drawRect(0, 0, imageWidth, imageHeight, paint);

        // Draw some diagonal lines to show it's a placeholder
        // 绘制一些对角线以显示这是占位符
        paint.setStrokeWidth(2);
        canvas.drawLine(0, 0, imageWidth, imageHeight, paint);
        canvas.drawLine(imageWidth, 0, 0, imageHeight, paint);

        Log.d(TAG, "Placeholder bitmap created successfully");

        return bitmap;
    }

    /**
     * Display the GeoTIFF on the globe using SurfaceImage
     *
     * Creates a SurfaceImage renderable that drapes the raster image over the globe's surface.
     * The image is positioned according to the geographic extent calculated from the World File.
     *
     * COORDINATE SYSTEM WARNING:
     * The coordinates from the World File (625-671, 4660-4702) are likely in a projected
     * coordinate system (e.g., UTM), NOT latitude/longitude (WGS84).
     *
     * For proper display, you should:
     * 1. Identify the projection (from .prj file or metadata)
     * 2. Use a coordinate transformation library (e.g., Proj4j, GeoTools)
     * 3. Convert to WGS84 latitude/longitude
     * 4. Then create the Sector
     *
     * Current implementation uses coordinates directly for demonstration purposes.
     *
     * 使用 SurfaceImage 在地球上显示 GeoTIFF
     *
     * 创建 SurfaceImage 可渲染对象，将栅格图像覆盖在地球表面上。
     * 图像根据从世界文件计算的地理范围进行定位。
     *
     * 坐标系警告：
     * 世界文件中的坐标（625-671, 4660-4702）可能是投影坐标系（如 UTM），
     * 而不是经纬度（WGS84）。
     *
     * 为了正确显示，您应该：
     * 1. 识别投影（从 .prj 文件或元数据）
     * 2. 使用坐标转换库（如 Proj4j、GeoTools）
     * 3. 转换为 WGS84 经纬度
     * 4. 然后创建 Sector
     *
     * 当前实现直接使用坐标用于演示目的。
     *
     * @param bitmap The image bitmap to display / 要显示的图像位图
     */
    private void displayGeoTIFF(Bitmap bitmap) {
        Log.d(TAG, "Creating SurfaceImage for GeoTIFF display");

        // Calculate geographic extent from World File parameters
        // 从世界文件参数计算地理范围
        double bottomRightX = topLeftX + (imageWidth * Math.abs(pixelWidth));
        double bottomRightY = topLeftY + (imageHeight * pixelHeight); // pixelHeight is negative

        Log.d(TAG, "=== Geographic Extent Calculation ===");
        Log.d(TAG, "Formula: bottomRightX = topLeftX + (imageWidth * pixelWidth)");
        Log.d(TAG, "         " + bottomRightX + " = " + topLeftX + " + (" +
              imageWidth + " * " + Math.abs(pixelWidth) + ")");
        Log.d(TAG, "Formula: bottomRightY = topLeftY + (imageHeight * pixelHeight)");
        Log.d(TAG, "         " + bottomRightY + " = " + topLeftY + " + (" +
              imageHeight + " * " + pixelHeight + ")");

        // WARNING: These coordinates are likely projected coordinates, not lat/lon
        // For demonstration, we'll treat them as decimal degrees
        // TODO: Add proper coordinate transformation for production use
        // 警告：这些坐标可能是投影坐标，而不是经纬度
        // 为了演示，我们将它们视为十进制度数
        // TODO：为生产使用添加适当的坐标转换

        // Create Sector (geographic bounding box)
        // Note: Sector expects (minLat, minLon, latSpan, lonSpan)
        // 创建 Sector（地理边界框）
        // 注意：Sector 期望 (minLat, minLon, latSpan, lonSpan)
        double minLatitude = Math.min(bottomRightY, topLeftY);
        double maxLatitude = Math.max(bottomRightY, topLeftY);
        double minLongitude = Math.min(topLeftX, bottomRightX);
        double maxLongitude = Math.max(topLeftX, bottomRightX);

        double latitudeSpan = maxLatitude - minLatitude;
        double longitudeSpan = maxLongitude - minLongitude;

        Sector sector = new Sector(minLatitude, minLongitude, latitudeSpan, longitudeSpan);

        Log.d(TAG, "=== Sector Details ===");
        Log.d(TAG, "Min Latitude: " + minLatitude);
        Log.d(TAG, "Max Latitude: " + maxLatitude);
        Log.d(TAG, "Min Longitude: " + minLongitude);
        Log.d(TAG, "Max Longitude: " + maxLongitude);
        Log.d(TAG, "Latitude Span: " + latitudeSpan);
        Log.d(TAG, "Longitude Span: " + longitudeSpan);
        Log.d(TAG, "Sector: " + sector);

        // Create ImageSource from the bitmap
        // 从位图创建 ImageSource
        ImageSource imageSource = ImageSource.fromBitmap(bitmap);

        // Create SurfaceImage renderable
        // SurfaceImage drapes the image over the terrain
        // 创建 SurfaceImage 可渲染对象
        // SurfaceImage 将图像覆盖在地形上
        SurfaceImage surfaceImage = new SurfaceImage(sector, imageSource);

        // Optional: Set opacity for the surface image
        // 可选：设置表面图像的不透明度
        // surfaceImage.setImageColor(new Color(1f, 1f, 1f, 0.8f)); // 80% opacity

        // Add to the renderable layer
        // 添加到可渲染图层
        mainHandler.post(() -> {
            geoLayer.addRenderable(surfaceImage);
            Log.d(TAG, "SurfaceImage added to layer");
            updateStatus("GeoTIFF image displayed on globe");
        });
    }

    /**
     * Position the camera to view the loaded GeoTIFF
     *
     * Calculates the center point and appropriate viewing distance based on the
     * geographic extent of the GeoTIFF data. The camera is positioned to show
     * the entire data extent with a comfortable margin.
     *
     * 定位相机以查看加载的 GeoTIFF
     *
     * 根据 GeoTIFF 数据的地理范围计算中心点和适当的观看距离。
     * 相机定位以显示整个数据范围并留有舒适的边距。
     */
    private void positionCamera() {
        Log.d(TAG, "Positioning camera to view GeoTIFF");

        LookAt lookAt = new LookAt();

        // Calculate the center point of the GeoTIFF extent
        // 计算 GeoTIFF 范围的中心点
        double bottomRightX = topLeftX + (imageWidth * Math.abs(pixelWidth));
        double bottomRightY = topLeftY + (imageHeight * pixelHeight);

        double centerLat = (topLeftY + bottomRightY) / 2.0;
        double centerLon = (topLeftX + bottomRightX) / 2.0;

        lookAt.latitude = centerLat;
        lookAt.longitude = centerLon;
        lookAt.altitude = 0;

        // Calculate appropriate viewing distance
        // 计算适当的观看距离
        double rangeX = Math.abs(bottomRightX - topLeftX);
        double rangeY = Math.abs(topLeftY - bottomRightY);
        double maxRange = Math.max(rangeX, rangeY);

        // Convert to meters for camera range
        // Assuming coordinates are in degrees, 1 degree ≈ 111 km
        // If coordinates are in a projected system (meters), adjust accordingly
        // 转换为米用于相机范围
        // 假设坐标为度数，1 度 ≈ 111 公里
        // 如果坐标在投影系统（米）中，请相应调整

        // For this demo, we assume small coordinate values indicate a projected system
        // with units in kilometers, so multiply by 1000 to get meters
        // 对于此演示，我们假设小坐标值表示投影系统，单位为公里，因此乘以 1000 得到米
        if (maxRange < 1) {
            // Likely in degrees / 可能是度数
            lookAt.range = maxRange * 111000 * 1.5;
        } else if (maxRange < 100) {
            // Likely in kilometers / 可能是公里
            lookAt.range = maxRange * 1000 * 1.5;
        } else {
            // Likely in meters / 可能是米
            lookAt.range = maxRange * 1.5;
        }

        // Add 50% margin for better viewing
        // 添加 50% 边距以获得更好的视图

        // Ensure range is within reasonable bounds
        // 确保范围在合理范围内
        lookAt.range = Math.max(5000, Math.min(lookAt.range, 10000000));

        Log.d(TAG, "=== Camera Position ===");
        Log.d(TAG, "Center Latitude: " + lookAt.latitude);
        Log.d(TAG, "Center Longitude: " + lookAt.longitude);
        Log.d(TAG, "Altitude: " + lookAt.altitude);
        Log.d(TAG, "Range: " + lookAt.range + " meters");
        Log.d(TAG, "Max extent dimension: " + maxRange + " units");

        // Set the camera position
        // 设置相机位置
        wwd.getNavigator().setAsLookAt(wwd.getGlobe(), lookAt);

        Log.d(TAG, "Camera positioned successfully");
    }

    /**
     * Update status text on UI thread
     *
     * Safely updates the status TextView from any thread by posting to the main handler.
     * Also logs the status message for debugging purposes.
     *
     * 在 UI 线程上更新状态文本
     *
     * 通过发布到主处理器，从任何线程安全地更新状态 TextView。
     * 还记录状态消息用于调试目的。
     *
     * @param message Status message to display / 要显示的状态消息
     */
    private void updateStatus(String message) {
        Log.d(TAG, "Status: " + message);
        mainHandler.post(() -> {
            if (statusText != null) {
                statusText.setText(message);
            }
        });
    }

    /**
     * Called when the activity is paused
     * Pauses the WorldWindow to save resources
     *
     * 当 Activity 暂停时调用
     * 暂停 WorldWindow 以节省资源
     */
    @Override
    protected void onPause() {
        super.onPause();
        if (wwd != null) {
            wwd.onPause();
            Log.d(TAG, "WorldWindow paused");
        }
    }

    /**
     * Called when the activity is resumed
     * Resumes the WorldWindow rendering
     *
     * 当 Activity 恢复时调用
     * 恢复 WorldWindow 渲染
     */
    @Override
    protected void onResume() {
        super.onResume();
        if (wwd != null) {
            wwd.onResume();
            Log.d(TAG, "WorldWindow resumed");
        }
    }

    /**
     * Called when the activity is destroyed
     * Shuts down the executor service to release resources
     *
     * 当 Activity 销毁时调用
     * 关闭执行器服务以释放资源
     */
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (executorService != null) {
            executorService.shutdown();
            Log.d(TAG, "ExecutorService shut down");
        }
        Log.d(TAG, "=== LoadGEOActivity destroyed ===");
    }
}
