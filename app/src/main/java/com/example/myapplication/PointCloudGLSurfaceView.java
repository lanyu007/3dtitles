package com.example.myapplication;

import android.content.Context;
import android.opengl.GLSurfaceView;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;

/**
 * 用于点云渲染的自定义GLSurfaceView
 * 处理触摸手势：旋转、缩放和平移
 */
public class PointCloudGLSurfaceView extends GLSurfaceView {
    private static final String TAG = "PointCloudGLSurfaceView";

    private PointCloudRenderer renderer;
    private Camera camera;

    // 手势检测
    private ScaleGestureDetector scaleGestureDetector;
    private float previousX;
    private float previousY;
    private int activePointerId = -1;

    // 多点触控跟踪
    private float lastTouchDistance = 0f;
    private boolean isScaling = false;
    private boolean isPanning = false;

    public PointCloudGLSurfaceView(Context context) {
        super(context);
        init(context);
    }

    public PointCloudGLSurfaceView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context context) {
        // 设置OpenGL ES 3.0上下文
        setEGLContextClientVersion(3);

        // 初始化捏合缩放的手势检测器
        scaleGestureDetector = new ScaleGestureDetector(context, new ScaleListener());

        // 暂停时保留EGL上下文(可选,但有助于性能)
        setPreserveEGLContextOnPause(true);
    }

    /**
     * 设置渲染器和相机
     */
    public void setRendererAndCamera(PointCloudRenderer renderer, Camera camera) {
        this.renderer = renderer;
        this.camera = camera;
        setRenderer(renderer);

        // 持续渲染以实现流畅交互
        setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // 处理缩放手势
        scaleGestureDetector.onTouchEvent(event);

        int action = event.getActionMasked();
        int pointerCount = event.getPointerCount();

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                // 单指按下 - 开始平移
                activePointerId = event.getPointerId(0);
                previousX = event.getX();
                previousY = event.getY();
                isScaling = false;
                isPanning = false;
                break;

            case MotionEvent.ACTION_POINTER_DOWN:
                // 第二个手指按下
                if (pointerCount == 2) {
                    lastTouchDistance = spacing(event);
                    isScaling = false;
                    isPanning = true; // 双指 = 旋转模式

                    // 记录中点用于旋转
                    previousX = (event.getX(0) + event.getX(1)) / 2f;
                    previousY = (event.getY(0) + event.getY(1)) / 2f;
                }
                break;

            case MotionEvent.ACTION_MOVE:
                if (isScaling) {
                    // 缩放由ScaleGestureDetector处理
                    return true;
                }

                if (pointerCount == 1 && !isPanning) {
                    // 单指 - 平移地图
                    int pointerIndex = event.findPointerIndex(activePointerId);
                    if (pointerIndex >= 0) {
                        float x = event.getX(pointerIndex);
                        float y = event.getY(pointerIndex);

                        float deltaX = x - previousX;
                        float deltaY = y - previousY;

                        // 平移相机
                        if (camera != null) {
                            queueEvent(() -> camera.pan(-deltaX, deltaY));
                        }

                        previousX = x;
                        previousY = y;
                    }
                } else if (pointerCount == 2 && isPanning) {
                    // 双指 - 旋转相机
                    float currentX = (event.getX(0) + event.getX(1)) / 2f;
                    float currentY = (event.getY(0) + event.getY(1)) / 2f;

                    float deltaX = currentX - previousX;
                    float deltaY = currentY - previousY;

                    // 旋转相机
                    if (camera != null) {
                        queueEvent(() -> camera.rotate(deltaX * 0.2f, deltaY * 0.2f));
                    }

                    previousX = currentX;
                    previousY = currentY;

                    // 检查捏合缩放
                    float currentDistance = spacing(event);
                    if (Math.abs(currentDistance - lastTouchDistance) > 10f) {
                        float scaleFactor = currentDistance / lastTouchDistance;
                        if (camera != null) {
                            queueEvent(() -> camera.zoom((1f - scaleFactor) * 0.5f));
                        }
                        lastTouchDistance = currentDistance;
                    }
                }
                break;

            case MotionEvent.ACTION_POINTER_UP:
                // 一个手指释放
                int pointerIndex = event.getActionIndex();
                int pointerId = event.getPointerId(pointerIndex);

                if (pointerId == activePointerId) {
                    // 活动指针释放,切换到另一个指针
                    int newPointerIndex = pointerIndex == 0 ? 1 : 0;
                    if (newPointerIndex < pointerCount) {
                        previousX = event.getX(newPointerIndex);
                        previousY = event.getY(newPointerIndex);
                        activePointerId = event.getPointerId(newPointerIndex);
                    }
                }
                isPanning = false;
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                // 所有手指释放
                activePointerId = -1;
                isScaling = false;
                isPanning = false;
                break;
        }

        return true;
    }

    /**
     * 计算两个触摸点之间的距离
     */
    private float spacing(MotionEvent event) {
        if (event.getPointerCount() < 2) {
            return 0f;
        }

        float x = event.getX(0) - event.getX(1);
        float y = event.getY(0) - event.getY(1);
        return (float) Math.sqrt(x * x + y * y);
    }

    /**
     * 捏合缩放的缩放手势监听器
     */
    private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScaleBegin(ScaleGestureDetector detector) {
            isScaling = true;
            return true;
        }

        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            float scaleFactor = detector.getScaleFactor();

            // 缩放相机
            if (camera != null) {
                queueEvent(() -> camera.zoom((1f - scaleFactor) * 0.5f));
            }

            return true;
        }

        @Override
        public void onScaleEnd(ScaleGestureDetector detector) {
            isScaling = false;
        }
    }

    /**
     * 获取渲染器
     */
    public PointCloudRenderer getPointCloudRenderer() {
        return renderer;
    }

    /**
     * 获取相机
     */
    public Camera getCamera() {
        return camera;
    }
}