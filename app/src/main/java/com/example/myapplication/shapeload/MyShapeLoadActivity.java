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
 * 用于加载和显示带有3D建筑物表示的Polygon shapefile的Activity
 *
 * 此Activity演示了:
 * - 从assets加载Polygon shapefile(建筑物)
 * - 自动将Web Mercator(EPSG:3857)转换为WGS84
 * - 从多边形轮廓创建3D建筑物
 * - 从DBF属性提取建筑物高度
 * - 基于建筑物高度的颜色映射(蓝色到红色渐变)
 * - 大数据集的高效批处理
 * - 带进度更新的异步加载
 */
public class MyShapeLoadActivity extends AppCompatActivity {

    public static void start(Context context) {
        Intent intent = new Intent(context, MyShapeLoadActivity.class);
        context.startActivity(intent);
    }

    private static final String TAG = "MyShapeLoadActivity";

    // assets中的Shapefile路径
    private static final String SHAPEFILE_PATH = "shp/cs.shp";
    private static final String DBF_PATH = "shp/cs.dbf";
    private static final String PRJ_PATH = "shp/cs.prj";
    private static final String CPG_PATH = "shp/cs.cpg";

    // 性能批处理配置
    private static final int BATCH_SIZE = 1000;  // 每批处理1000个建筑物
    private static final int PROGRESS_UPDATE_INTERVAL = 100;  // 每100个建筑物更新一次

    // WorldWind组件
    protected WorldWindow wwd;
    protected TextView statusText;
    protected TextView projectionText;
    protected RenderableLayer shapefileLayer;
    private ExecutorService executorService;
    private Handler mainHandler;

    // 跟踪所有已加载几何体的边界框
    private double minLat = Double.MAX_VALUE;
    private double maxLat = -Double.MAX_VALUE;
    private double minLon = Double.MAX_VALUE;
    private double maxLon = -Double.MAX_VALUE;

    // 统计信息
    private int totalPolygons = 0;
    private int loadedPolygons = 0;

    // 建筑物高度统计
    private int buildingsWithHeight = 0;
    private double minHeight = Double.MAX_VALUE;
    private double maxHeight = 0.0;
    private double totalHeight = 0.0;

    // 网格统计
    private int totalGrids = 0;
    private int loadedGrids = 0;

    // 点云统计
    private int totalPoints = 0;
    private int loadedPoints = 0;

    // PointZ数据的Z值统计
    private double globalMinZ = 0.0;
    private double globalMaxZ = 0.0;

    // 用于动态颜色映射的高度百分位数
    private double heightP25 = 0.0;
    private double heightP50 = 0.0;
    private double heightP75 = 0.0;
    private double heightP95 = 0.0;

    // 最优网格大小(动态计算)
    private double optimalGridSize = 0.0001;  // 默认0.0001°(约11米)

    // 投影信息
    private String projectionInfo = "Unknown";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_load_shape);

        // 初始化执行器服务和处理器
        executorService = Executors.newSingleThreadExecutor();
        mainHandler = new Handler(Looper.getMainLooper());

        // 获取UI组件
        statusText = findViewById(R.id.status_text);
        projectionText = findViewById(R.id.projection_text);

        // 创建WorldWindow(GLSurfaceView)，用于显示地球
        wwd = new WorldWindow(this);

        // 将WorldWindow视图对象添加到布局
        FrameLayout globeLayout = findViewById(R.id.globe);
        globeLayout.addView(wwd);

        // 设置WorldWindow的图层
        initializeWorldWindow();

        // 加载Shapefile
        loadShapefile();
    }

    /**
     * 使用基础图层和Shapefile图层初始化WorldWindow
     */
    private void initializeWorldWindow() {
        Log.d(TAG, "初始化WorldWindow");

        // 添加基础图层
        wwd.getLayers().addLayer(new BackgroundLayer());
        wwd.getLayers().addLayer(new BlueMarbleLandsatLayer());
        wwd.getLayers().addLayer(new AtmosphereLayer());

        // 设置高程模型
        wwd.getGlobe().getElevationModel().addCoverage(new BasicElevationCoverage());

        // 创建Shapefile内容图层
        shapefileLayer = new RenderableLayer("Building Layer");
        wwd.getLayers().addLayer(shapefileLayer);

        Log.d(TAG, "WorldWindow已初始化");
    }

    /**
     * 从.cpg文件加载字符编码
     *
     * @return 字符编码名称(例如"UTF-8"、"GBK")，如果未找到则返回null
     */
    private String loadCharacterEncoding() {
        try {
            InputStream cpgStream = getAssets().open(CPG_PATH);
            byte[] buffer = new byte[1024];
            int bytesRead = cpgStream.read(buffer);
            cpgStream.close();

            if (bytesRead > 0) {
                // 读取编码名称并修剪空白
                String encoding = new String(buffer, 0, bytesRead, "UTF-8").trim();

                // 处理常见编码别名
                if (encoding.equalsIgnoreCase("UTF8")) {
                    encoding = "UTF-8";
                } else if (encoding.equalsIgnoreCase("GBK") || encoding.equalsIgnoreCase("GB2312")) {
                    encoding = "GBK"; // GBK是GB2312的超集
                }

                Log.d(TAG, "=== 字符编码 ===");
                Log.d(TAG, "从.cpg读取的编码: " + encoding);
                return encoding;
            } else {
                Log.w(TAG, ".cpg文件为空，使用默认UTF-8编码");
                return null;
            }
        } catch (Exception e) {
            Log.w(TAG, "未找到.cpg文件，使用默认UTF-8编码: " + e.getMessage());
            return null;
        }
    }

    /**
     * 从.prj文件加载投影信息
     */
    private void loadProjectionInfo() {
        try {
            InputStream prjStream = getAssets().open(PRJ_PATH);
            byte[] buffer = new byte[4096];
            int bytesRead = prjStream.read(buffer);
            prjStream.close();

            if (bytesRead > 0) {
                String prjContent = new String(buffer, 0, bytesRead, "UTF-8").trim();

                // 从WKT格式解析投影名称
                if (prjContent.contains("WGS_1984_Web_Mercator")) {
                    projectionInfo = "WGS 1984 Web Mercator (EPSG:3857)";
                } else if (prjContent.contains("WGS_84") || prjContent.contains("WGS84")) {
                    projectionInfo = "WGS 84 (EPSG:4326)";
                } else {
                    projectionInfo = "自定义投影";
                }

                Log.d(TAG, "投影信息: " + projectionInfo);
            }
        } catch (Exception e) {
            Log.w(TAG, "未找到.prj文件，假定为WGS84: " + e.getMessage());
            projectionInfo = "WGS 84 (假定)";
        }
    }

    /**
     * 基于点密度和地理边界计算最优网格大小
     *
     * 策略:
     * - 高密度(>500k个点): 更细网格(0.00003°约3.3米)
     * - 中密度(100k-500k): 中等网格(0.00005°约5.5米)
     * - 低密度(<100k): 较粗网格(0.0001°约11米)
     *
     * 还考虑地理边界以确保合理的网格数量
     *
     * 已修复: 整数溢出问题 - 现在使用long进行网格计数计算
     *
     * @param pointCount 数据集中的总点数
     * @param bbox 边界框[minX, minY, maxX, maxY]
     * @return 最优网格大小(度)
     */
    private double calculateOptimalGridSize(int pointCount, double[] bbox) {
        // 网格数量约束，防止溢出并确保合理性能
        final long MAX_GRIDS = 500000;  // 最大500k个网格(对于100万个点约710点/网格)
        final long MIN_GRIDS = 1000;    // 最小1000个网格以保证合理细节

        // 计算地理区域
        double latRange = bbox[3] - bbox[1];
        double lonRange = bbox[2] - bbox[0];
        double area = latRange * lonRange;  // 平方度

        // 计算点密度(点数/平方度)
        double density = pointCount / area;

        Log.d(TAG, "=== 网格大小计算 ===");
        Log.d(TAG, "总点数: " + pointCount);
        Log.d(TAG, "地理区域: " + String.format("%.6f", area) + " 平方度");
        Log.d(TAG, "纬度范围: " + String.format("%.6f", latRange) + "° (约" +
                   String.format("%.1f", latRange * 111000) + " 米)");
        Log.d(TAG, "经度范围: " + String.format("%.6f", lonRange) + "° (约" +
                   String.format("%.1f", lonRange * 111000) + " 米)");
        Log.d(TAG, "点密度: " + String.format("%.0f", density) + " 点/平方度");

        double gridSize;

        // 根据点数和密度确定初始网格大小
        if (pointCount > 500000) {
            // 非常高密度: 使用更细网格(3-5米)
            gridSize = 0.00003;  // 约3.3米
            Log.d(TAG, "检测到高密度: 使用细网格(0.00003° 约3.3米)");
        } else if (pointCount > 100000) {
            // 中高密度: 使用中细网格(5-7米)
            gridSize = 0.00005;  // 约5.5米
            Log.d(TAG, "检测到中高密度: 使用中细网格(0.00005° 约5.5米)");
        } else if (pointCount > 50000) {
            // 中等密度: 使用中等网格(7-11米)
            gridSize = 0.00007;  // 约7.7米
            Log.d(TAG, "检测到中等密度: 使用中等网格(0.00007° 约7.7米)");
        } else {
            // 较低密度: 使用默认网格(11米)
            gridSize = 0.0001;  // 约11米
            Log.d(TAG, "检测到较低密度: 使用默认网格(0.0001° 约11米)");
        }

        // 已修复: 使用long防止整数溢出
        // 分别计算每个维度的网格数以避免溢出
        long latGrids = (long) Math.ceil(latRange / gridSize);
        long lonGrids = (long) Math.ceil(lonRange / gridSize);

        // 在乘法之前检查潜在溢出
        if (latGrids > 0 && lonGrids > Long.MAX_VALUE / latGrids) {
            Log.e(TAG, "错误: 网格计算将溢出! latGrids=" + latGrids +
                       ", lonGrids=" + lonGrids);
            // 使用非常大的网格大小作为后备
            gridSize = Math.sqrt(area / MAX_GRIDS);
            latGrids = (long) Math.ceil(latRange / gridSize);
            lonGrids = (long) Math.ceil(lonRange / gridSize);
        }

        long estimatedGrids = latGrids * lonGrids;

        Log.d(TAG, "初始网格估算:");
        Log.d(TAG, "  网格大小: " + String.format("%.6f", gridSize) + "°");
        Log.d(TAG, "  纬度网格数: " + latGrids);
        Log.d(TAG, "  经度网格数: " + lonGrids);
        Log.d(TAG, "  估计总网格数: " + estimatedGrids);
        Log.d(TAG, "  每网格点数: " + (estimatedGrids > 0 ? (pointCount / estimatedGrids) : 0));

        // 迭代调整: 如果网格数太多，增加网格大小
        int adjustmentIterations = 0;
        while (estimatedGrids > MAX_GRIDS && adjustmentIterations < 10) {
            adjustmentIterations++;
            // 通过系数增加网格大小以减少网格数
            double adjustmentFactor = Math.sqrt((double) estimatedGrids / MAX_GRIDS);
            gridSize *= adjustmentFactor;

            latGrids = (long) Math.ceil(latRange / gridSize);
            lonGrids = (long) Math.ceil(lonRange / gridSize);
            estimatedGrids = latGrids * lonGrids;

            Log.d(TAG, "调整迭代 " + adjustmentIterations + " (网格太多):");
            Log.d(TAG, "  新网格大小: " + String.format("%.6f", gridSize) + "° (约" +
                       String.format("%.1f", gridSize * 111000) + "米)");
            Log.d(TAG, "  估计网格数: " + estimatedGrids);
            Log.d(TAG, "  每网格点数: " + (pointCount / estimatedGrids));
        }

        // 迭代调整: 如果网格数太少，减小网格大小
        adjustmentIterations = 0;
        while (estimatedGrids < MIN_GRIDS && pointCount > 10000 && adjustmentIterations < 10) {
            adjustmentIterations++;
            // 通过系数减小网格大小以增加网格数
            double adjustmentFactor = Math.sqrt((double) MIN_GRIDS / estimatedGrids);
            gridSize /= adjustmentFactor;

            // 不要让网格大小太小
            if (gridSize < 0.00001) {  // 最小约1.1米
                Log.d(TAG, "网格大小达到最小阈值(0.00001°)，停止调整");
                gridSize = 0.00001;
                break;
            }

            latGrids = (long) Math.ceil(latRange / gridSize);
            lonGrids = (long) Math.ceil(lonRange / gridSize);
            estimatedGrids = latGrids * lonGrids;

            Log.d(TAG, "调整迭代 " + adjustmentIterations + " (网格太少):");
            Log.d(TAG, "  新网格大小: " + String.format("%.6f", gridSize) + "° (约" +
                       String.format("%.1f", gridSize * 111000) + "米)");
            Log.d(TAG, "  估计网格数: " + estimatedGrids);
            Log.d(TAG, "  每网格点数: " + (pointCount / estimatedGrids));
        }

        // 最终计算和日志记录
        latGrids = (long) Math.ceil(latRange / gridSize);
        lonGrids = (long) Math.ceil(lonRange / gridSize);
        estimatedGrids = latGrids * lonGrids;

        Log.d(TAG, "=== 最终网格配置 ===");
        Log.d(TAG, "网格大小: " + String.format("%.6f", gridSize) + "° (约" +
                   String.format("%.1f", gridSize * 111000) + "米)");
        Log.d(TAG, "纬度网格数: " + latGrids);
        Log.d(TAG, "经度网格数: " + lonGrids);
        Log.d(TAG, "总估计网格数: " + estimatedGrids);
        Log.d(TAG, "每网格点数(平均): " + (estimatedGrids > 0 ? (pointCount / estimatedGrids) : 0));
        Log.d(TAG, "网格覆盖范围: " + String.format("%.1f", gridSize * 111000) + "米 × " +
                   String.format("%.1f", gridSize * 111000) + "米");
        Log.d(TAG, "================================");

        return gridSize;
    }

    /**
     * 从归一化Z值计算高度百分位数
     * 用于基于实际数据分布的动态颜色映射
     *
     * @param zValues 所有归一化Z值的列表
     */
    private void calculateHeightPercentiles(List<Double> zValues) {
        if (zValues.isEmpty()) {
            Log.w(TAG, "无可用Z值用于百分位数计算");
            return;
        }

        // 对Z值排序
        List<Double> sortedZ = new ArrayList<>(zValues);
        Collections.sort(sortedZ);

        int n = sortedZ.size();

        // 计算百分位数
        heightP25 = sortedZ.get((int) (n * 0.25));
        heightP50 = sortedZ.get((int) (n * 0.50));  // 中位数
        heightP75 = sortedZ.get((int) (n * 0.75));
        heightP95 = sortedZ.get((int) (n * 0.95));

        Log.d(TAG, "=== 高度百分位数分析 ===");
        Log.d(TAG, "总数据点数: " + n);
        Log.d(TAG, "最小高度: " + String.format("%.2f", sortedZ.get(0)) + "米");
        Log.d(TAG, "P25(第25百分位数): " + String.format("%.2f", heightP25) + "米");
        Log.d(TAG, "P50(中位数): " + String.format("%.2f", heightP50) + "米");
        Log.d(TAG, "P75(第75百分位数): " + String.format("%.2f", heightP75) + "米");
        Log.d(TAG, "P95(第95百分位数): " + String.format("%.2f", heightP95) + "米");
        Log.d(TAG, "最大高度: " + String.format("%.2f", sortedZ.get(n - 1)) + "米");
        Log.d(TAG, "==================================");
    }

    /**
     * 异步加载和解析Shapefile
     */
    private void loadShapefile() {
        Log.d(TAG, "=== 开始加载Shapefile ===");
        Log.d(TAG, "Shapefile路径: " + SHAPEFILE_PATH);
        updateStatus("正在加载建筑物Shapefile...");

        executorService.execute(() -> {
            ShapefileReader shpReader = new ShapefileReader();
            DBFReader dbfReader = null;

            try {
                // 步骤1: 加载投影和编码信息
                loadProjectionInfo();
                String characterEncoding = loadCharacterEncoding();
                updateProjectionDisplay();

                // 步骤2: 加载并解析.shp文件
                updateStatus("正在读取几何文件(.shp)...");
                InputStream shpStream = getAssets().open(SHAPEFILE_PATH);
                shpReader.read(shpStream);
                shpStream.close();

                final int shapeType = shpReader.getShapeType();
                double[] bbox = shpReader.getBoundingBox();
                Log.d(TAG, "形状类型: " + shapeType);
                Log.d(TAG, "边界框: [" + bbox[0] + ", " + bbox[1] + ", " + bbox[2] + ", " + bbox[3] + "]");
                Log.d(TAG, "检测到形状类型: " + shapeType + " (" + getShapeTypeName(shapeType) + ")");

                // 检查形状类型并相应处理
                if (shapeType == 11) {
                    // PointZ数据 - 使用网格转换
                    Log.d(TAG, "=== PointZ处理分支 ===");
                    List<ShapefileReader.PointZRecord> pointZRecords = shpReader.getPointZRecords();
                    Log.d(TAG, "总PointZ记录数: " + pointZRecords.size());
                    if (pointZRecords.isEmpty()) {
                        Log.e(TAG, "错误: 未找到PointZ记录!");
                        updateStatus("错误: 文件中无PointZ数据");
                        return;
                    }
                    totalPoints = pointZRecords.size();
                    Log.d(TAG, "已加载Shapefile: " + totalPoints + " 条PointZ记录");

                    // 第一遍扫描: 查找Z值范围并收集所有Z值
                    List<Double> allZValues = new ArrayList<>();
                    double globalMinZ = Double.MAX_VALUE;
                    double globalMaxZ = -Double.MAX_VALUE;
                    for (ShapefileReader.PointZRecord point : pointZRecords) {
                        globalMinZ = Math.min(globalMinZ, point.z);
                        globalMaxZ = Math.max(globalMaxZ, point.z);
                        allZValues.add(point.z);
                    }

                    Log.d(TAG, "全局Z范围: 最小=" + globalMinZ + ", 最大=" + globalMaxZ + ", 范围=" + (globalMaxZ - globalMinZ));

                    // 计算高度百分位数用于动态颜色映射
                    updateStatus("正在计算高度分布...");
                    List<Double> normalizedZValues = new ArrayList<>();
                    for (ShapefileReader.PointZRecord point : pointZRecords) {
                        normalizedZValues.add(point.z - globalMinZ);
                    }
                    calculateHeightPercentiles(normalizedZValues);

                    // 保存全局Z值用于元数据日志
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
                    // Polygon数据 - 使用原始逻辑
                    List<ShapefileReader.PolygonRecord> polygons = shpReader.getPolygonRecords();
                    totalPolygons = polygons.size();
                    Log.d(TAG, "已加载Shapefile: " + totalPolygons + " 条Polygon记录");

                    // 步骤3: 加载并解析.dbf文件(属性)
                    updateStatus("正在读取属性文件(.dbf)...");
                    try {
                        InputStream dbfStream = getAssets().open(DBF_PATH);
                        dbfReader = new DBFReader();
                        dbfReader.read(dbfStream, characterEncoding);
                        dbfStream.close();
                        Log.d(TAG, "已加载DBF: " + dbfReader.getRecordCount() + " 条记录");
                    } catch (Exception e) {
                        Log.w(TAG, "无法加载DBF文件(属性将不可用): " + e.getMessage());
                    }

                    // 步骤4: 从多边形记录创建3D建筑物
                    updateStatus("正在创建3D建筑物(0/" + totalPolygons + ")...");

                    final DBFReader finalDbfReader = dbfReader;

                    // 批量处理多边形以提高性能
                    for (int i = 0; i < polygons.size(); i += BATCH_SIZE) {
                        int endIndex = Math.min(i + BATCH_SIZE, polygons.size());
                        List<Polygon> batchPolygons = new ArrayList<>();

                        // 创建3D建筑物批次
                        for (int j = i; j < endIndex; j++) {
                            ShapefileReader.PolygonRecord record = polygons.get(j);

                            // 如果可用则获取属性
                            Map<String, Object> attributes = null;
                            if (finalDbfReader != null && j < finalDbfReader.getRecordCount()) {
                                attributes = finalDbfReader.getRecord(j);
                            }

                            // 从多边形记录创建3D建筑物
                            List<Polygon> buildingPolygons = createBuilding3D(record, attributes);
                            batchPolygons.addAll(buildingPolygons);
                            loadedPolygons++;
                        }

                        // 在主线程上将批次添加到图层
                        final int currentCount = loadedPolygons;
                        mainHandler.post(() -> {
                            for (Polygon polygon : batchPolygons) {
                                shapefileLayer.addRenderable(polygon);
                            }

                            // 定期更新进度
                            if (currentCount % PROGRESS_UPDATE_INTERVAL == 0 || currentCount == totalPolygons) {
                                updateStatus("正在加载建筑物(" + currentCount + "/" + totalPolygons + ")...");
                            }

                            wwd.requestRedraw();
                        });

                        // 短暂暂停以允许UI更新
                        Thread.sleep(10);
                    }
                }

                // 步骤5: 定位相机并完成
                mainHandler.post(() -> {
                    positionCamera();
                    Log.d(TAG, "相机定位完成");
                    Log.d(TAG, "  边界: 纬度[" + minLat + ", " + maxLat + "], 经度[" + minLon + ", " + maxLon + "]");
                    logShapefileMetadata();

                    String statusMsg;
                    if (shapeType == 11) {
                        statusMsg = "已加载 " + loadedGrids + " 个3D网格建筑";
                    } else {
                        statusMsg = "已加载 " + loadedPolygons + " 个3D效果建筑物";
                    }
                    updateStatus(statusMsg);
                    wwd.requestRedraw();

                    Log.d(TAG, "Shapefile加载完成");
                });

            } catch (Exception e) {
                Log.e(TAG, "加载shapefile时出错", e);
                e.printStackTrace();  // 打印完整堆栈跟踪
                mainHandler.post(() -> {
                    updateStatus("加载Shapefile错误: " + e.getMessage());
                    Toast.makeText(this, "加载Shapefile失败: " + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    /**
     * 从Shapefile多边形记录创建3D建筑物
     *
     * 特性:
     * - 创建底面、顶面、侧面和内部切片
     * - 从DBF属性提取建筑物高度
     * - 基于高度的颜色映射(蓝色->绿色->黄色->橙色->红色)
     * - RELATIVE_TO_GROUND高度模式用于3D效果
     *
     * @param record 包含轮廓坐标的多边形记录
     * @param attributes 来自DBF文件的可选属性数据(包括高度)
     * @return 组成3D建筑物的多边形可渲染对象列表
     */
    private List<Polygon> createBuilding3D(ShapefileReader.PolygonRecord record,
                                           Map<String, Object> attributes) {
        List<Polygon> buildingPolygons = new ArrayList<>();

        try {
            // 使用第一部分(外环)
            List<ShapefileReader.Point> ring = record.parts.get(0);

            // 提取建筑物高度
            double buildingHeight = extractHeight(attributes);

            // 基于高度获取此建筑物的基础颜色
            Color baseColor = getColorByHeight(buildingHeight);

            // 创建共享的Position列表(baseRing和topRing)
            List<Position> baseRing = new ArrayList<>();
            List<Position> topRing = new ArrayList<>();

            for (ShapefileReader.Point point : ring) {
                baseRing.add(Position.fromDegrees(point.y, point.x, 0));
                topRing.add(Position.fromDegrees(point.y, point.x, buildingHeight));
                updateBoundingBox(point.y, point.x);
            }

            // 确保两个环都是闭合的
            if (baseRing.size() > 0) {
                Position first = baseRing.get(0);
                Position last = baseRing.get(baseRing.size() - 1);
                if (first.latitude != last.latitude || first.longitude != last.longitude) {
                    baseRing.add(first);
                    topRing.add(topRing.get(0));
                }
            }

            // 创建底部多边形
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

            // 创建顶部多边形
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

            // 用水平切片填充建筑物内部
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

            // 更新高度统计
            if (buildingHeight > 0) {
                buildingsWithHeight++;
                minHeight = Math.min(minHeight, buildingHeight);
                maxHeight = Math.max(maxHeight, buildingHeight);
                totalHeight += buildingHeight;
            }

        } catch (Exception e) {
            Log.w(TAG, "创建3D建筑物时出错: " + e.getMessage());
        }

        return buildingPolygons;
    }


    /**
     * 从GridCell创建完整建筑结构的3D建筑物
     *
     * 创建3D建筑物表示，包含:
     * - 地面层底面(Z=0)
     * - 指定高度的顶面
     * - 连接底部和顶部的4个垂直侧面
     * - 用于实体外观的内部水平切片
     * - 基于百分位分布的高度颜色
     * - ABSOLUTE高度模式(PointZ数据已具有绝对高度)
     *
     * 此方法镜像了LoadShapeActivity.createBuilding3D中的实现
     * 但适配为基于网格的矩形建筑而不是多边形轮廓
     *
     * @param cell 包含中心坐标的GridCell
     * @param gridSize 网格单元大小(度)
     * @param height 建筑物高度(米)
     * @return 组成3D建筑物的多边形可渲染对象列表
     */
    private List<Polygon> createGridBuilding3D(GridCell cell, double gridSize, double height) {
        List<Polygon> buildingPolygons = new ArrayList<>();

        try {
            // 计算网格矩形的四个角点
            double halfSize = gridSize / 2.0;
            double lat1 = cell.centerLat - halfSize;  // 南
            double lat2 = cell.centerLat + halfSize;  // 北
            double lon1 = cell.centerLon - halfSize;  // 西
            double lon2 = cell.centerLon + halfSize;  // 东

            // 基于高度获取此建筑物的基础颜色
            Color baseColor = getColorByHeight(height);

            // 创建baseRing(底面, Z=0) - 从左下角顺时针
            List<Position> baseRing = new ArrayList<>();
            baseRing.add(Position.fromDegrees(lat1, lon1, 0));  // 左下
            baseRing.add(Position.fromDegrees(lat1, lon2, 0));  // 右下
            baseRing.add(Position.fromDegrees(lat2, lon2, 0));  // 右上
            baseRing.add(Position.fromDegrees(lat2, lon1, 0));  // 左上
            baseRing.add(Position.fromDegrees(lat1, lon1, 0));  // 闭合环

            // 创建topRing(顶面, Z=height)
            List<Position> topRing = new ArrayList<>();
            topRing.add(Position.fromDegrees(lat1, lon1, height));  // 左下
            topRing.add(Position.fromDegrees(lat1, lon2, height));  // 右下
            topRing.add(Position.fromDegrees(lat2, lon2, height));  // 右上
            topRing.add(Position.fromDegrees(lat2, lon1, height));  // 左上
            topRing.add(Position.fromDegrees(lat1, lon1, height));  // 闭合环

            // 创建底部多边形(深色，半透明)
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

            // 创建顶部多边形(完整baseColor，0.6f alpha匹配getColorByHeight)
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

            // 创建4个侧面多边形
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
                sideFacePositions.add(bottomLeft);  // 闭合面

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

            // 用水平切片填充建筑物内部(基于高度的1-10层)
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
                    slicePositions.add(Position.fromDegrees(lat1, lon1, layerHeight));  // 闭合

                    Polygon slicePolygon = new Polygon(slicePositions, interiorAttrs);
                    slicePolygon.setAltitudeMode(gov.nasa.worldwind.WorldWind.ABSOLUTE);
                    slicePolygon.setFollowTerrain(false);
                    buildingPolygons.add(slicePolygon);
                }
            }

            // 更新边界框
            updateBoundingBox(lat1, lon1);
            updateBoundingBox(lat2, lon2);

        } catch (Exception e) {
            Log.w(TAG, "创建3D网格建筑物时出错: " + e.getMessage());
        }

        return buildingPolygons;
    }



    /**
     * 从形状类型代码获取形状类型名称
     *
     * @param shapeType 形状类型代码
     * @return 人类可读的形状类型名称
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
     * 从属性中提取建筑物高度
     *
     * @param attributes DBF属性映射
     * @return 建筑物高度(米)(如果未找到或无效则为0)
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

        return 50.0;  // 如果没有找到有效的高度字段，则使用默认高度
    }

    /**
     * 基于建筑物高度获取颜色(热力图效果)
     *
     * 使用基于百分位数的动态颜色映射以更好地适应实际数据:
     * - < P25: 蓝色(最低25%)
     * - P25-P50: 青绿色(25%-50%)
     * - P50-P75: 黄色(50%-75%)
     * - P75-P95: 橙色(75%-95%)
     * - > P95: 红色(最高5%)
     *
     * 如果百分位数不可用，则回退到固定阈值
     *
     * @param height 建筑物高度(米)
     * @return 对应的颜色
     */
    private Color getColorByHeight(double height) {
        // 如果可用，使用基于百分位数的动态阈值
        if (heightP95 > 0) {
            // 基于实际数据分布的动态映射
            if (height < heightP25) {
                return new Color(0.3f, 0.5f, 1.0f, 0.9f);       // 蓝色 - 最低25%
            } else if (height < heightP50) {
                return new Color(0.2f, 0.8f, 0.6f, 0.9f);       // 青绿色 - 25%-50%
            } else if (height < heightP75) {
                return new Color(1.0f, 0.9f, 0.2f, 0.9f);       // 黄色 - 50%-75%
            } else if (height < heightP95) {
                return new Color(1.0f, 0.6f, 0.1f, 0.9f);       // 橙色 - 75%-95%
            } else {
                return new Color(1.0f, 0.2f, 0.1f, 0.9f);       // 红色 - 最高5%
            }
        } else {
            // 对于多边形数据回退到固定阈值
            if (height < 10) return new Color(0.3f, 0.5f, 1.0f, 0.9f);       // 蓝色 - 低建筑
            else if (height < 30) return new Color(0.2f, 0.8f, 0.6f, 0.9f);  // 青绿色 - 中低
            else if (height < 60) return new Color(1.0f, 0.9f, 0.2f, 0.9f);  // 黄色 - 中等
            else if (height < 100) return new Color(1.0f, 0.6f, 0.1f, 0.9f); // 橙色 - 中高
            else return new Color(1.0f, 0.2f, 0.1f, 0.9f);                   // 红色 - 高建筑
        }
    }

    /**
     * 用新坐标更新边界框
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
