package com.example.myapplication;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.myapplication.exampledemo.worldwindx.GeneralGlobeActivity;
import com.example.myapplication.geoload.LoadGEOActivity;
import com.example.myapplication.kmzload.LoadKMZActivity;
import com.example.myapplication.loadkml.LoadKMLActivity;
import com.example.myapplication.shpload.LoadShpActivity;
import com.example.myapplication.threedimentionmap.WorldWindOfflineMapActivity;
import com.example.myapplication.worldwindnav.WorldWindNavActivity;

/**
 * 3D Tiles 点云查看器的主活动
 * 从 assets/dy3d 目录加载并渲染点云数据
 */
public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";

    // 点云数据的资源路径
    private static final String ASSET_BASE_PATH = "dy3d";

    // UI组件
    private PointCloudGLSurfaceView glSurfaceView;
    private TextView statusText;
    private Button resetButton;
    private Button increasePointSizeButton;
    private Button decreasePointSizeButton;

    // 核心组件
    private Camera camera;
    private TilesetLoader tilesetLoader;
    private PointCloudRenderer renderer;

    // 状态更新处理器
    private Handler mainHandler;
    private Runnable statusUpdateRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Log.d(TAG, "onCreate");

        // 初始化UI组件
        glSurfaceView = findViewById(R.id.glSurfaceView);
        statusText = findViewById(R.id.statusText);
        resetButton = findViewById(R.id.resetButton);
        increasePointSizeButton = findViewById(R.id.increasePointSizeButton);
        decreasePointSizeButton = findViewById(R.id.decreasePointSizeButton);

        // 初始化核心组件
        camera = new Camera();
        tilesetLoader = new TilesetLoader(this, ASSET_BASE_PATH);
        renderer = new PointCloudRenderer(this, camera, tilesetLoader);

        // 设置GLSurfaceView
        glSurfaceView.setRendererAndCamera(renderer, camera);

        // 设置按钮
        setupButtons();

        // 在后台加载瓦片集
        loadTilesetAsync();

        // 设置状态更新定时器
        setupStatusUpdater();

    }

    /**
     * 设置按钮点击监听器
     */
    private void setupButtons() {
        // 重置视图按钮
        resetButton.setOnClickListener(v -> {
            camera.reset();
            Toast.makeText(this, "View reset", Toast.LENGTH_SHORT).show();

//            WorldWindOfflineMapActivity.start(this);
//            GeneralGlobeActivity.start(this);
//            WorldWindNavActivity.start(this);
//            LoadKMZActivity.start(this);
            LoadShpActivity.start(this);
//            LoadKMLActivity.start(this);
//            LoadGEOActivity.start(this);
        });

        // 增加点大小按钮
        increasePointSizeButton.setOnClickListener(v -> {
            float currentSize = renderer.getPointSize();
            renderer.setPointSize(currentSize + 1f);
            updateStatus();
        });

        // 减小点大小按钮
        decreasePointSizeButton.setOnClickListener(v -> {
            float currentSize = renderer.getPointSize();
            renderer.setPointSize(currentSize - 1f);
            updateStatus();
        });
    }

    /**
     * 异步加载 tileset.json
     */
    private void loadTilesetAsync() {
        new Thread(() -> {
            try {
                Log.d(TAG, "Loading tileset...");
                statusText.post(() -> statusText.setText("Loading tileset.json..."));

                boolean success = tilesetLoader.loadTileset();

                if (success) {
                    Log.d(TAG, "Tileset loaded successfully");
                    int tileCount = tilesetLoader.getAllTiles().size();

                    statusText.post(() -> {
                        statusText.setText("Tileset loaded: " + tileCount + " tiles");
                        Toast.makeText(MainActivity.this,
                                "准备就绪！使用手势探索：\n" +
                                        "- 单指拖动：平移地图\n" +
                                        "- 双指拖动：旋转视角\n" +
                                        "- 双指捏合：缩放",
                                Toast.LENGTH_LONG).show();
                    });
                } else {
                    Log.e(TAG, "Failed to load tileset");
                    statusText.post(() -> {
                        statusText.setText("Error: Failed to load tileset.json");
                        Toast.makeText(MainActivity.this,
                                "Failed to load tileset.json. Check asset path.",
                                Toast.LENGTH_LONG).show();
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "Error loading tileset", e);
                statusText.post(() -> {
                    statusText.setText("Error: " + e.getMessage());
                    Toast.makeText(MainActivity.this,
                            "Error: " + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    /**
     * 设置定期状态更新
     */
    private void setupStatusUpdater() {
        mainHandler = new Handler(Looper.getMainLooper());
        statusUpdateRunnable = new Runnable() {
            @Override
            public void run() {
                updateStatus();
                mainHandler.postDelayed(this, 1000); // 每秒更新一次
            }
        };
        mainHandler.postDelayed(statusUpdateRunnable, 1000);
    }

    /**
     * 使用当前渲染信息更新状态文本
     */
    private void updateStatus() {
        if (renderer != null && camera != null) {
            int pointCount = renderer.getTotalPointsRendered();
            int vboCount = renderer.getTileVBOCount();
            float pointSize = renderer.getPointSize();
            float distance = camera.getDistance();

            String status = String.format(
                    "Points: %,d | VBOs: %d | Size: %.1f | Distance: %.0f",
                    pointCount, vboCount, pointSize, distance
            );

            statusText.setText(status);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        glSurfaceView.onResume();
        Log.d(TAG, "onResume");
    }

    @Override
    protected void onPause() {
        super.onPause();
        glSurfaceView.onPause();
        Log.d(TAG, "onPause");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        // 停止状态更新
        if (mainHandler != null && statusUpdateRunnable != null) {
            mainHandler.removeCallbacks(statusUpdateRunnable);
        }

        // 清理渲染器资源
        if (renderer != null) {
            glSurfaceView.queueEvent(() -> renderer.cleanup());
        }

        Log.d(TAG, "onDestroy");
    }
}