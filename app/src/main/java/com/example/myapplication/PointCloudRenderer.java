package com.example.myapplication;

import android.content.Context;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * 点云数据的 OpenGL ES 3.0 渲染器
 * 管理着色器程序、顶点缓冲对象(VBO)和渲染管线
 */
public class PointCloudRenderer implements GLSurfaceView.Renderer {
    private static final String TAG = "PointCloudRenderer";

    private Context context;
    private Camera camera;
    private TilesetLoader tilesetLoader;

    // 着色器程序
    private int shaderProgram;
    private int positionHandle;
    private int colorHandle;
    private int mvpMatrixHandle;
    private int pointSizeHandle;

    // 渲染状态
    private float pointSize = 3.0f;
    private int totalPointsRendered = 0;
    private boolean isInitialized = false;

    // 已加载瓦片的VBO缓存
    private List<TileVBO> tileVBOs = new ArrayList<>();

    /**
     * 单个瓦片的VBO数据
     */
    private static class TileVBO {
        int positionVBO;
        int colorVBO;
        int pointCount;
        TilesetLoader.TileNode tile;
    }

    public PointCloudRenderer(Context context, Camera camera, TilesetLoader tilesetLoader) {
        this.context = context;
        this.camera = camera;
        this.tilesetLoader = tilesetLoader;
    }

    @Override
    public void onSurfaceCreated(GL10 gl10, EGLConfig eglConfig) {
        Log.d(TAG, "onSurfaceCreated");

        // 设置清除颜色(深灰色)
        GLES30.glClearColor(0.1f, 0.1f, 0.1f, 1.0f);

        // 启用深度测试
        GLES30.glEnable(GLES30.GL_DEPTH_TEST);
        GLES30.glDepthFunc(GLES30.GL_LESS);

        // 启用混合以支持透明度
        GLES30.glEnable(GLES30.GL_BLEND);
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA);

        // 加载并编译着色器
        int vertexShader = loadShader(GLES30.GL_VERTEX_SHADER, R.raw.vertex_shader);
        int fragmentShader = loadShader(GLES30.GL_FRAGMENT_SHADER, R.raw.fragment_shader);

        // 创建着色器程序
        shaderProgram = GLES30.glCreateProgram();
        GLES30.glAttachShader(shaderProgram, vertexShader);
        GLES30.glAttachShader(shaderProgram, fragmentShader);
        GLES30.glLinkProgram(shaderProgram);

        // 检查链接状态
        int[] linkStatus = new int[1];
        GLES30.glGetProgramiv(shaderProgram, GLES30.GL_LINK_STATUS, linkStatus, 0);
        if (linkStatus[0] == 0) {
            Log.e(TAG, "Error linking program: " + GLES30.glGetProgramInfoLog(shaderProgram));
            GLES30.glDeleteProgram(shaderProgram);
            return;
        }

        // 获取属性和统一变量位置
        positionHandle = GLES30.glGetAttribLocation(shaderProgram, "a_Position");
        colorHandle = GLES30.glGetAttribLocation(shaderProgram, "a_Color");
        mvpMatrixHandle = GLES30.glGetUniformLocation(shaderProgram, "u_MVPMatrix");
        pointSizeHandle = GLES30.glGetUniformLocation(shaderProgram, "u_PointSize");

        Log.d(TAG, "Shader program created successfully");
        Log.d(TAG, "Position handle: " + positionHandle);
        Log.d(TAG, "Color handle: " + colorHandle);
        Log.d(TAG, "MVP matrix handle: " + mvpMatrixHandle);
        Log.d(TAG, "Point size handle: " + pointSizeHandle);

        isInitialized = true;
    }

    @Override
    public void onSurfaceChanged(GL10 gl10, int width, int height) {
        Log.d(TAG, "onSurfaceChanged: " + width + "x" + height);

        // 设置视口
        GLES30.glViewport(0, 0, width, height);

        // 更新相机宽高比
        float aspectRatio = (float) width / (float) height;
        camera.setAspectRatio(aspectRatio);
    }

    @Override
    public void onDrawFrame(GL10 gl10) {
        if (!isInitialized) {
            return;
        }

        // 清除缓冲区
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT | GLES30.GL_DEPTH_BUFFER_BIT);

        // 使用着色器程序
        GLES30.glUseProgram(shaderProgram);

        // 设置点大小
        GLES30.glUniform1f(pointSizeHandle, pointSize);

        // 设置MVP矩阵(模型视图投影矩阵)
        GLES30.glUniformMatrix4fv(mvpMatrixHandle, 1, false, camera.getViewProjectionMatrix(), 0);

        // 根据LOD(细节层级)选择要渲染的瓦片
        List<TilesetLoader.TileNode> tilesToRender = tilesetLoader.selectTilesToRender(
                camera.getPosition(), 16.0);

        totalPointsRendered = 0;

        // 加载并渲染瓦片
        for (TilesetLoader.TileNode tile : tilesToRender) {
            // 如果尚未加载,则加载瓦片内容
            if (!tile.isLoaded) {
                tilesetLoader.loadTileContent(tile);
            }

            // 渲染瓦片
            if (tile.isLoaded && tile.pointCloudData != null) {
                renderTile(tile);
                totalPointsRendered += tile.pointCloudData.pointCount;
            }
        }

        // 检查OpenGL错误
        int error = GLES30.glGetError();
        if (error != GLES30.GL_NO_ERROR) {
            Log.e(TAG, "OpenGL error: " + error);
        }
    }

    /**
     * 渲染单个瓦片
     */
    private void renderTile(TilesetLoader.TileNode tile) {
        TilesetLoader.PointCloudData data = tile.pointCloudData;

        if (data.positions == null || data.colors == null) {
            return;
        }

        // 查找或创建此瓦片的VBO
        TileVBO vbo = findOrCreateVBO(tile);

        if (vbo == null) {
            return;
        }

        // 绑定位置VBO
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo.positionVBO);
        GLES30.glEnableVertexAttribArray(positionHandle);
        GLES30.glVertexAttribPointer(positionHandle, 3, GLES30.GL_FLOAT, false, 0, 0);

        // 绑定颜色VBO
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo.colorVBO);
        GLES30.glEnableVertexAttribArray(colorHandle);
        GLES30.glVertexAttribPointer(colorHandle, 4, GLES30.GL_UNSIGNED_BYTE, true, 0, 0);

        // 绘制点
        GLES30.glDrawArrays(GLES30.GL_POINTS, 0, vbo.pointCount);

        // 解绑
        GLES30.glDisableVertexAttribArray(positionHandle);
        GLES30.glDisableVertexAttribArray(colorHandle);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0);
    }

    /**
     * 查找现有VBO或为瓦片创建新VBO
     */
    private TileVBO findOrCreateVBO(TilesetLoader.TileNode tile) {
        // 检查VBO是否已存在
        for (TileVBO vbo : tileVBOs) {
            if (vbo.tile == tile) {
                return vbo;
            }
        }

        // 创建新VBO
        TileVBO vbo = new TileVBO();
        vbo.tile = tile;
        vbo.pointCount = tile.pointCloudData.pointCount;

        // 生成VBO
        int[] vbos = new int[2];
        GLES30.glGenBuffers(2, vbos, 0);

        vbo.positionVBO = vbos[0];
        vbo.colorVBO = vbos[1];

        // 上传位置数据
        FloatBuffer positionBuffer = ByteBuffer.allocateDirect(tile.pointCloudData.positions.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        positionBuffer.put(tile.pointCloudData.positions);
        positionBuffer.position(0);

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo.positionVBO);
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER,
                tile.pointCloudData.positions.length * 4,
                positionBuffer,
                GLES30.GL_STATIC_DRAW);

        // 上传颜色数据
        ByteBuffer colorBuffer = ByteBuffer.allocateDirect(tile.pointCloudData.colors.length)
                .order(ByteOrder.nativeOrder());
        colorBuffer.put(tile.pointCloudData.colors);
        colorBuffer.position(0);

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo.colorVBO);
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER,
                tile.pointCloudData.colors.length,
                colorBuffer,
                GLES30.GL_STATIC_DRAW);

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0);

        // 缓存VBO
        tileVBOs.add(vbo);

        Log.d(TAG, "Created VBO for tile: " + tile.contentUri + " (" + vbo.pointCount + " points)");

        return vbo;
    }

    /**
     * 从资源加载并编译着色器
     */
    private int loadShader(int type, int resourceId) {
        String shaderCode = loadShaderSource(resourceId);

        int shader = GLES30.glCreateShader(type);
        GLES30.glShaderSource(shader, shaderCode);
        GLES30.glCompileShader(shader);

        // 检查编译状态
        int[] compileStatus = new int[1];
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compileStatus, 0);

        if (compileStatus[0] == 0) {
            Log.e(TAG, "Error compiling shader: " + GLES30.glGetShaderInfoLog(shader));
            GLES30.glDeleteShader(shader);
            return 0;
        }

        return shader;
    }

    /**
     * 从资源文件加载着色器源代码
     */
    private String loadShaderSource(int resourceId) {
        StringBuilder shaderSource = new StringBuilder();

        try {
            InputStream inputStream = context.getResources().openRawResource(resourceId);
            BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));

            String line;
            while ((line = reader.readLine()) != null) {
                shaderSource.append(line).append("\n");
            }

            reader.close();
        } catch (Exception e) {
            Log.e(TAG, "Error loading shader source", e);
        }

        return shaderSource.toString();
    }

    /**
     * 清理OpenGL资源
     */
    public void cleanup() {
        // 删除VBO
        for (TileVBO vbo : tileVBOs) {
            int[] vbos = new int[]{vbo.positionVBO, vbo.colorVBO};
            GLES30.glDeleteBuffers(2, vbos, 0);
        }
        tileVBOs.clear();

        // 删除着色器程序
        if (shaderProgram != 0) {
            GLES30.glDeleteProgram(shaderProgram);
        }
    }

    // Getter和Setter方法

    public void setPointSize(float size) {
        this.pointSize = Math.max(1.0f, Math.min(20.0f, size));
    }

    public float getPointSize() {
        return pointSize;
    }

    public int getTotalPointsRendered() {
        return totalPointsRendered;
    }

    public int getTileVBOCount() {
        return tileVBOs.size();
    }
}