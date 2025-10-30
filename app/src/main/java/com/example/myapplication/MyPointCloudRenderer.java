package com.example.myapplication;

import android.content.Context;
import android.opengl.GLES30;
import android.util.Log;

import gov.nasa.worldwind.geom.Position;
import gov.nasa.worldwind.geom.Vec3;
import gov.nasa.worldwind.globe.Globe;
import gov.nasa.worldwind.layer.RenderableLayer;
import gov.nasa.worldwind.render.RenderContext;

/**
 * WorldWind 点云渲染层
 * 将独立的点云渲染器集成到 WorldWind 地图系统中
 *
 * 核心功能:
 * 1. 在 WorldWind 渲染循环中渲染点云数据
 * 2. 同步 WorldWind 相机与点云渲染器相机
 * 3. 处理 OpenGL 上下文切换和状态管理
 * 4. 支持地理坐标与 ECEF 坐标转换
 */
public class MyPointCloudRenderer extends RenderableLayer {
    private static final String TAG = "MyPointCloudRenderer";

    // 核心组件
    private Context context;
    private PointCloudRenderer pointCloudRenderer;
    private Camera camera;
    private TilesetLoader tilesetLoader;

    // WorldWind 集成状态
    private boolean isInitialized = false;
    private boolean tilesetLoaded = false;

    // 长沙地理坐标 (从 tileset.json 获取)
    private static final double CHANGSHA_LONGITUDE = 112.938;
    private static final double CHANGSHA_LATITUDE = 28.194;
    private static final double CHANGSHA_ALTITUDE = 0.0;

    // OpenGL 状态保存
    private int[] savedViewport = new int[4];
    private boolean[] savedDepthTest = new boolean[1];
    private boolean[] savedBlend = new boolean[1];
    private int[] savedBlendSrc = new int[1];
    private int[] savedBlendDst = new int[1];

    // 渲染统计
    private int frameCount = 0;
    private long lastLogTime = System.currentTimeMillis();

    /**
     * 构造函数
     * @param context Android 上下文
     * @param assetBasePath 点云数据资源路径
     */
    public MyPointCloudRenderer(Context context, String assetBasePath) {
        super();
        this.context = context;

        Log.d(TAG, "初始化 MyPointCloudRenderer, 资源路径: " + assetBasePath);

        // 初始化核心组件
        camera = new Camera();
        tilesetLoader = new TilesetLoader(context, assetBasePath);
        pointCloudRenderer = new PointCloudRenderer(context, camera, tilesetLoader);

        // 设置图层属性
        setDisplayName("3D Point Cloud - Changsha");
        setPickEnabled(false); // 点云不支持拾取

        // 异步加载 tileset
        loadTilesetAsync();
    }

    /**
     * 异步加载 tileset.json
     */
    private void loadTilesetAsync() {
        new Thread(() -> {
            try {
                Log.d(TAG, "开始加载 tileset.json...");
                boolean success = tilesetLoader.loadTileset();

                if (success) {
                    int tileCount = tilesetLoader.getAllTiles().size();
                    tilesetLoaded = true;
                    Log.d(TAG, "Tileset 加载成功! 总瓦片数: " + tileCount);
                } else {
                    Log.e(TAG, "Tileset 加载失败");
                }
            } catch (Exception e) {
                Log.e(TAG, "加载 tileset 时出错", e);
            }
        }).start();
    }

    /**
     * WorldWind 渲染循环入口
     * 这是集成的核心方法
     */
    @Override
    public void render(RenderContext rc) {
        if (!isEnabled() || !tilesetLoaded) {
            return;
        }

        try {
            // 首次渲染时初始化 OpenGL 资源
            if (!isInitialized) {
                initializeOpenGL(rc);
                isInitialized = true;
            }

            // 保存 WorldWind 的 OpenGL 状态
            saveOpenGLState();

            // 同步相机
            syncCameraWithWorldWind(rc);

            // 设置点云渲染所需的 OpenGL 状态
            setupPointCloudRenderState(rc);

            // 渲染点云
            renderPointCloud(rc);

            // 恢复 WorldWind 的 OpenGL 状态
            restoreOpenGLState();

            // 定期输出渲染统计
            logRenderingStats();

        } catch (Exception e) {
            Log.e(TAG, "渲染点云时出错", e);
        }
    }

    /**
     * 初始化 OpenGL 资源
     * 相当于 GLSurfaceView.Renderer 的 onSurfaceCreated
     */
    private void initializeOpenGL(RenderContext rc) {
        Log.d(TAG, "初始化 OpenGL 资源...");

        // 调用 PointCloudRenderer 的 onSurfaceCreated
        // 注意: 这里传入 null, 因为我们不需要 GL10 接口
        pointCloudRenderer.onSurfaceCreated(null, null);

        // 设置视口尺寸
        int width = rc.viewport.width;
        int height = rc.viewport.height;
        pointCloudRenderer.onSurfaceChanged(null, width, height);

        Log.d(TAG, "OpenGL 资源初始化完成, 视口: " + width + "x" + height);
    }

    /**
     * 保存 WorldWind 的 OpenGL 状态
     * 确保点云渲染不会影响 WorldWind 的渲染
     */
    private void saveOpenGLState() {
        // 保存视口
        GLES30.glGetIntegerv(GLES30.GL_VIEWPORT, savedViewport, 0);

        // 保存深度测试状态
        savedDepthTest[0] = GLES30.glIsEnabled(GLES30.GL_DEPTH_TEST);

        // 保存混合状态
        savedBlend[0] = GLES30.glIsEnabled(GLES30.GL_BLEND);
        GLES30.glGetIntegerv(GLES30.GL_BLEND_SRC_ALPHA, savedBlendSrc, 0);
        GLES30.glGetIntegerv(GLES30.GL_BLEND_DST_ALPHA, savedBlendDst, 0);
    }

    /**
     * 恢复 WorldWind 的 OpenGL 状态
     */
    private void restoreOpenGLState() {
        // 恢复视口
        GLES30.glViewport(savedViewport[0], savedViewport[1], savedViewport[2], savedViewport[3]);

        // 恢复深度测试
        if (savedDepthTest[0]) {
            GLES30.glEnable(GLES30.GL_DEPTH_TEST);
        } else {
            GLES30.glDisable(GLES30.GL_DEPTH_TEST);
        }

        // 恢复混合
        if (savedBlend[0]) {
            GLES30.glEnable(GLES30.GL_BLEND);
        } else {
            GLES30.glDisable(GLES30.GL_BLEND);
        }
        GLES30.glBlendFunc(savedBlendSrc[0], savedBlendDst[0]);

        // 解绑缓冲区
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0);
        GLES30.glUseProgram(0);
    }

    /**
     * 设置点云渲染所需的 OpenGL 状态
     */
    private void setupPointCloudRenderState(RenderContext rc) {
        // 启用深度测试
        GLES30.glEnable(GLES30.GL_DEPTH_TEST);
        GLES30.glDepthFunc(GLES30.GL_LESS);

        // 启用混合以支持透明度
        GLES30.glEnable(GLES30.GL_BLEND);
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA);

        // 设置视口 (使用 WorldWind 的视口)
        int width = rc.viewport.width;
        int height = rc.viewport.height;
        GLES30.glViewport(0, 0, width, height);
    }

    /**
     * 同步 WorldWind 的相机到点云渲染器的相机
     * 这是最关键的方法,确保点云与地图坐标对齐
     */
    private void syncCameraWithWorldWind(RenderContext rc) {
        Globe globe = rc.globe;

        // 直接从 Camera 获取地理坐标
        Vec3 cameraPoint = rc.cameraPoint;
        double eyeLat = cameraPoint.x;
        double eyeLon = cameraPoint.y;
        double eyeAlt = cameraPoint.z;

        // 转换为笛卡尔坐标 (ECEF 地心坐标系)
        Vec3 eyeCartesian = globe.geographicToCartesian(eyeLat, eyeLon, eyeAlt, new Vec3());

        // 获取 WorldWind 的观察点位置 (通常是地球中心或目标点)
        // 这里我们使用长沙的位置作为观察中心
        Vec3 targetCartesian = globe.geographicToCartesian(
            CHANGSHA_LATITUDE, CHANGSHA_LONGITUDE, CHANGSHA_ALTITUDE, new Vec3());

        // 更新点云相机的位置
        camera.setPosition((float)eyeCartesian.x, (float)eyeCartesian.y, (float)eyeCartesian.z);
        camera.setTarget((float)targetCartesian.x, (float)targetCartesian.y, (float)targetCartesian.z);

        // 计算距离
        double dx = eyeCartesian.x - targetCartesian.x;
        double dy = eyeCartesian.y - targetCartesian.y;
        double dz = eyeCartesian.z - targetCartesian.z;
        float distance = (float)Math.sqrt(dx * dx + dy * dy + dz * dz);
        camera.setDistance(distance);

        // 同步视角参数
        // 注意: WorldWind 使用不同的视角系统,这里做简化处理
        // 实际项目中可能需要更精确的矩阵转换

        // 更新宽高比
        int width = rc.viewport.width;
        int height = rc.viewport.height;
        if (height > 0) {
            camera.setAspectRatio((float)width / (float)height);
        }
    }

    /**
     * 渲染点云
     */
    private void renderPointCloud(RenderContext rc) {
        // 不需要清除缓冲区,因为 WorldWind 已经做了
        // 直接调用点云渲染器的绘制方法
        pointCloudRenderer.onDrawFrame(null);

        frameCount++;
    }

    /**
     * 输出渲染统计信息
     */
    private void logRenderingStats() {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastLogTime > 5000) { // 每5秒输出一次
            int pointCount = pointCloudRenderer.getTotalPointsRendered();
            int vboCount = pointCloudRenderer.getTileVBOCount();
            float pointSize = pointCloudRenderer.getPointSize();

            Log.d(TAG, String.format(
                "渲染统计 - 帧数: %d | 点数: %,d | VBO: %d | 点大小: %.1f",
                frameCount, pointCount, vboCount, pointSize
            ));

            lastLogTime = currentTime;
            frameCount = 0;
        }
    }

    /**
     * 清理资源
     * 必须在 OpenGL 线程中调用
     */
    public void cleanup() {
        Log.d(TAG, "清理点云渲染器资源");

        if (pointCloudRenderer != null) {
            pointCloudRenderer.cleanup();
        }

        isInitialized = false;
        tilesetLoaded = false;
    }

    // ==================== 公共 API ====================

    /**
     * 设置点的大小
     * @param size 点大小 (1.0 - 20.0)
     */
    public void setPointSize(float size) {
        if (pointCloudRenderer != null) {
            pointCloudRenderer.setPointSize(size);
        }
    }

    /**
     * 获取点的大小
     */
    public float getPointSize() {
        if (pointCloudRenderer != null) {
            return pointCloudRenderer.getPointSize();
        }
        return 3.0f;
    }

    /**
     * 获取渲染的点数
     */
    public int getTotalPointsRendered() {
        if (pointCloudRenderer != null) {
            return pointCloudRenderer.getTotalPointsRendered();
        }
        return 0;
    }

    /**
     * 获取已加载的瓦片数量
     */
    public int getTileVBOCount() {
        if (pointCloudRenderer != null) {
            return pointCloudRenderer.getTileVBOCount();
        }
        return 0;
    }

    /**
     * 检查是否已初始化
     */
    public boolean isInitialized() {
        return isInitialized;
    }

    /**
     * 检查 tileset 是否已加载
     */
    public boolean isTilesetLoaded() {
        return tilesetLoaded;
    }

    /**
     * 获取相机对象 (用于外部控制)
     */
    public Camera getCamera() {
        return camera;
    }

    /**
     * 获取 tileset 加载器 (用于外部查询)
     */
    public TilesetLoader getTilesetLoader() {
        return tilesetLoader;
    }
}
