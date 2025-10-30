package com.example.myapplication.threedimentionmap;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.myapplication.MyPointCloudRenderer;
import com.example.myapplication.R;

import gov.nasa.worldwind.WorldWindow;
import gov.nasa.worldwind.geom.LookAt;
import gov.nasa.worldwind.geom.Position;
import gov.nasa.worldwind.layer.BackgroundLayer;
import gov.nasa.worldwind.layer.BlueMarbleLandsatLayer;

/**
 * WorldWind 离线地图活动
 * 集成了 3D 点云渲染功能,显示长沙地区的点云数据
 */
public class WorldWindOfflineMapActivity extends AppCompatActivity {
    private static final String TAG = "WorldWindOfflineMap";

    public static void start(Context context) {
        Intent intent = new Intent(context, WorldWindOfflineMapActivity.class);
        context.startActivity(intent);
    }

    // WorldWind核心视图组件
    private WorldWindow worldWindow;

    // 点云渲染层
    private MyPointCloudRenderer pointCloudLayer;

    // 点云数据的资源路径
    private static final String ASSET_BASE_PATH = "dy3d";

    // 长沙地理坐标
    private static final double CHANGSHA_LONGITUDE = 112.938;
    private static final double CHANGSHA_LATITUDE = 28.194;
    private static final double DEFAULT_ALTITUDE = 5000.0; // 默认高度 5000 米

    // 主线程处理器
    private Handler mainHandler;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_world_wind_offline_map);

        Log.d(TAG, "onCreate - 初始化 WorldWind 离线地图");

        // 初始化主线程处理器
        mainHandler = new Handler(Looper.getMainLooper());

        // 获取 WorldWindow
        worldWindow = findViewById(R.id.worldWindow);
        if (worldWindow == null) {
            throw new IllegalStateException("WorldWindow not found in layout");
        }

        // 初始化点云渲染层
        initializePointCloudLayer();

        // 定位相机到长沙
//        positionCameraToChangsha();
        adjustCameraToPntsPointCloud();

        // 显示欢迎提示
        showWelcomeMessage();

        Log.d(TAG, "WorldWind 地图初始化完成");
    }

    /**
     * 初始化点云渲染层
     */
    private void initializePointCloudLayer() {
        try {

//            // 1. 添加背景图层（纯色背景，防止地球背面透明）
//            BackgroundLayer backgroundLayer = new BackgroundLayer();
//            worldWindow.getLayers().addLayer(backgroundLayer);
//            Log.i(TAG, "Background layer added");
//
//            // 2. 添加Blue Marble + Landsat卫星影像图层（NASA提供的全球影像）
//            // 这是WorldWind的默认高清卫星底图
//            BlueMarbleLandsatLayer imageLayer = new BlueMarbleLandsatLayer();
//            worldWindow.getLayers().addLayer(imageLayer);
//            Log.i(TAG, "Blue Marble Landsat layer added");
            Log.d(TAG, "创建点云渲染层, 资源路径: " + ASSET_BASE_PATH);

            // 创建点云渲染层
            pointCloudLayer = new MyPointCloudRenderer(this, ASSET_BASE_PATH);

            // 添加到 WorldWind 图层列表
            worldWindow.getLayers().addLayer(pointCloudLayer);

            Log.d(TAG, "点云渲染层已添加到 WorldWind");

            // 延迟检查加载状态
            mainHandler.postDelayed(this::checkPointCloudLoadStatus, 2000);

        } catch (Exception e) {
            Log.e(TAG, "初始化点云渲染层失败", e);
            Toast.makeText(this, "Failed to initialize point cloud layer", Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 检查点云加载状态
     */
    private void checkPointCloudLoadStatus() {
        if (pointCloudLayer != null && pointCloudLayer.isTilesetLoaded()) {
            int tileCount = pointCloudLayer.getTilesetLoader().getAllTiles().size();
            Log.d(TAG, "点云数据加载成功! 总瓦片数: " + tileCount);

            Toast.makeText(this,
                "点云数据加载成功!\n" +
                "瓦片数: " + tileCount + "\n" +
                "正在渲染长沙地区3D点云...",
                Toast.LENGTH_LONG).show();
        } else {
            Log.d(TAG, "点云数据仍在加载中...");
            // 继续等待
            mainHandler.postDelayed(this::checkPointCloudLoadStatus, 1000);
        }
    }

    /**
     * 定位相机到长沙
     */
    private void positionCameraToChangsha() {
        try {
            // 创建长沙位置
            Position changsha = Position.fromDegrees(CHANGSHA_LATITUDE, CHANGSHA_LONGITUDE, DEFAULT_ALTITUDE);

            // 设置相机位置
            worldWindow.getNavigator().setLatitude(changsha.latitude);
            worldWindow.getNavigator().setLongitude(changsha.longitude);
            worldWindow.getNavigator().setAltitude(changsha.altitude);

            // 设置视角
            worldWindow.getNavigator().setHeading(0.0);   // 正北方向
            worldWindow.getNavigator().setTilt(45.0);     // 45度倾斜角
            worldWindow.getNavigator().setRoll(0.0);      // 无滚转

            Log.d(TAG, String.format(
                "相机已定位到长沙: 经度=%.3f, 纬度=%.3f, 高度=%.0f米",
                CHANGSHA_LONGITUDE, CHANGSHA_LATITUDE, DEFAULT_ALTITUDE
            ));

        } catch (Exception e) {
            Log.e(TAG, "定位相机到长沙失败", e);
        }
    }

    /**
     * 显示欢迎消息
     */
    private void showWelcomeMessage() {
        Toast.makeText(this,
            "WorldWind 3D点云地图\n" +
            "正在加载长沙地区点云数据...\n\n" +
            "手势操作:\n" +
            "- 单指拖动: 平移地图\n" +
            "- 双指拖动: 旋转视角\n" +
            "- 双指捏合: 缩放地图",
            Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "onResume");

        // WorldWindow 会自动恢复渲染
        if (worldWindow != null) {
            worldWindow.onResume();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.d(TAG, "onPause");

        // WorldWindow 会自动暂停渲染
        if (worldWindow != null) {
            worldWindow.onPause();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy - 清理资源");

        // 停止处理器
        if (mainHandler != null) {
            mainHandler.removeCallbacksAndMessages(null);
        }

        // 清理点云资源
        if (pointCloudLayer != null) {
            // 点云渲染器的清理会在 WorldWind 的渲染线程中自动处理
            // WorldWind 框架会自动管理 GPU 资源
            pointCloudLayer.cleanup();
            Log.d(TAG, "点云资源已清理");
        }
    }

    /**
     * 获取点云渲染层 (用于外部控制)
     */
    public MyPointCloudRenderer getPointCloudLayer() {
        return pointCloudLayer;
    }

    /**
     * 获取 WorldWindow (用于外部控制)
     */
    public WorldWindow getWorldWindow() {
        return worldWindow;
    }

    private void adjustCameraToPntsPointCloud() {
        // PNTS数据位于长沙附近 (~28°N, 113°E)
        LookAt lookAt = new LookAt();
        lookAt.latitude = 28.2282;   // 长沙纬度
        lookAt.longitude = 112.9388; // 长沙经度
        lookAt.altitude = 0;
        lookAt.range = 5000;         // 5km高度以查看点云

        worldWindow.getNavigator().setAsLookAt(worldWindow.getGlobe(), lookAt);

        Log.i(TAG, "Camera adjusted to Changsha region for PNTS point cloud");
    }
}