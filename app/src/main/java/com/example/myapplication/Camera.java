package com.example.myapplication;

import android.opengl.Matrix;

/**
 * 3D Camera system with support for rotation, translation, and zoom
 * Uses matrix transformations for view and projection
 */
public class Camera {
    private static final String TAG = "Camera";

    // Camera position
    private float[] position = new float[]{0f, 0f, 500f};

    // Camera target (look-at point)
    private float[] target = new float[]{0f, 0f, 0f};

    // Camera up vector
    private float[] up = new float[]{0f, 1f, 0f};

    // Rotation angles (in degrees)
    private float rotationX = 0f;  // Pitch
    private float rotationY = 0f;  // Yaw
    private float rotationZ = 0f;  // Roll

    // Distance from target
    private float distance = 500f;

    // Matrices
    private float[] viewMatrix = new float[16];
    private float[] projectionMatrix = new float[16];
    private float[] viewProjectionMatrix = new float[16];

    // Projection parameters
    private float fov = 45f;
    private float aspectRatio = 1f;
    private float nearPlane = 1f;
    private float farPlane = 10000f;

    public Camera() {
        updateViewMatrix();
        updateProjectionMatrix();
    }

    /**
     * Set camera position
     */
    public void setPosition(float x, float y, float z) {
        position[0] = x;
        position[1] = y;
        position[2] = z;
        updateViewMatrix();
    }

    /**
     * Set camera target (look-at point)
     */
    public void setTarget(float x, float y, float z) {
        target[0] = x;
        target[1] = y;
        target[2] = z;
        updateViewMatrix();
    }

    /**
     * Set camera distance from target
     */
    public void setDistance(float distance) {
        this.distance = Math.max(10f, Math.min(5000f, distance));
        updateCameraPosition();
        updateViewMatrix();
    }

    /**
     * Zoom in/out by changing distance
     */
    public void zoom(float delta) {
        distance *= (1f - delta);
        distance = Math.max(10f, Math.min(5000f, distance));
        updateCameraPosition();
        updateViewMatrix();
    }

    /**
     * Rotate camera around target
     * @param deltaX Horizontal rotation (yaw)
     * @param deltaY Vertical rotation (pitch)
     */
    public void rotate(float deltaX, float deltaY) {
        rotationY += deltaX;
        rotationX += deltaY;

        // Clamp pitch to prevent gimbal lock
        rotationX = Math.max(-89f, Math.min(89f, rotationX));

        // Normalize yaw to 0-360
        rotationY = rotationY % 360f;

        updateCameraPosition();
        updateViewMatrix();
    }

    /**
     * Pan camera (translate target point)
     * @param deltaX Horizontal pan
     * @param deltaY Vertical pan
     */
    public void pan(float deltaX, float deltaY) {
        // Calculate right and up vectors
        float[] forward = new float[3];
        forward[0] = target[0] - position[0];
        forward[1] = target[1] - position[1];
        forward[2] = target[2] - position[2];
        normalize(forward);

        float[] right = new float[3];
        cross(forward, up, right);
        normalize(right);

        float[] realUp = new float[3];
        cross(right, forward, realUp);
        normalize(realUp);

        // Scale pan speed by distance
        float panSpeed = distance * 0.001f;

        // Move target
        target[0] += right[0] * deltaX * panSpeed - realUp[0] * deltaY * panSpeed;
        target[1] += right[1] * deltaX * panSpeed - realUp[1] * deltaY * panSpeed;
        target[2] += right[2] * deltaX * panSpeed - realUp[2] * deltaY * panSpeed;

        updateCameraPosition();
        updateViewMatrix();
    }

    /**
     * Update camera position based on rotation and distance
     */
    private void updateCameraPosition() {
        // Convert rotation to radians
        float pitchRad = (float) Math.toRadians(rotationX);
        float yawRad = (float) Math.toRadians(rotationY);

        // Calculate position using spherical coordinates
        float x = distance * (float) (Math.cos(pitchRad) * Math.sin(yawRad));
        float y = distance * (float) Math.sin(pitchRad);
        float z = distance * (float) (Math.cos(pitchRad) * Math.cos(yawRad));

        position[0] = target[0] + x;
        position[1] = target[1] + y;
        position[2] = target[2] + z;
    }

    /**
     * Update view matrix
     */
    private void updateViewMatrix() {
        Matrix.setLookAtM(viewMatrix, 0,
                position[0], position[1], position[2],  // Eye position
                target[0], target[1], target[2],        // Look at point
                up[0], up[1], up[2]);                   // Up vector

        updateViewProjectionMatrix();
    }

    /**
     * Update projection matrix
     */
    private void updateProjectionMatrix() {
        Matrix.perspectiveM(projectionMatrix, 0, fov, aspectRatio, nearPlane, farPlane);
        updateViewProjectionMatrix();
    }

    /**
     * Update combined view-projection matrix
     */
    private void updateViewProjectionMatrix() {
        Matrix.multiplyMM(viewProjectionMatrix, 0, projectionMatrix, 0, viewMatrix, 0);
    }

    /**
     * Set viewport aspect ratio
     */
    public void setAspectRatio(float aspectRatio) {
        this.aspectRatio = aspectRatio;
        updateProjectionMatrix();
    }

    /**
     * Set field of view
     */
    public void setFov(float fov) {
        this.fov = fov;
        updateProjectionMatrix();
    }

    /**
     * Reset camera to default position
     */
    public void reset() {
        position[0] = 0f;
        position[1] = 0f;
        position[2] = 500f;

        target[0] = 0f;
        target[1] = 0f;
        target[2] = 0f;

        rotationX = 0f;
        rotationY = 0f;
        rotationZ = 0f;

        distance = 500f;

        updateViewMatrix();
    }

    // Helper methods

    /**
     * Normalize a 3D vector
     */
    private void normalize(float[] v) {
        float length = (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        if (length > 0) {
            v[0] /= length;
            v[1] /= length;
            v[2] /= length;
        }
    }

    /**
     * Cross product of two 3D vectors
     */
    private void cross(float[] a, float[] b, float[] result) {
        result[0] = a[1] * b[2] - a[2] * b[1];
        result[1] = a[2] * b[0] - a[0] * b[2];
        result[2] = a[0] * b[1] - a[1] * b[0];
    }

    // Getters

    public float[] getPosition() {
        return position;
    }

    public float[] getTarget() {
        return target;
    }

    public float[] getViewMatrix() {
        return viewMatrix;
    }

    public float[] getProjectionMatrix() {
        return projectionMatrix;
    }

    public float[] getViewProjectionMatrix() {
        return viewProjectionMatrix;
    }

    public float getDistance() {
        return distance;
    }

    public float getRotationX() {
        return rotationX;
    }

    public float getRotationY() {
        return rotationY;
    }
}