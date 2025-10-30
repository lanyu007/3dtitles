package com.example.myapplication;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Loader for Cesium 3D Tiles tileset.json configuration
 * Manages tile hierarchy and LOD (Level of Detail) selection
 */
public class TilesetLoader {
    private static final String TAG = "TilesetLoader";

    private Context context;
    private String assetBasePath;
    private TileNode rootTile;
    private List<TileNode> allTiles = new ArrayList<>();

    /**
     * Represents a single tile in the 3D Tiles hierarchy
     */
    public static class TileNode {
        public String contentUri;           // Path to .pnts file
        public BoundingVolume boundingVolume;
        public double geometricError;       // Error metric for LOD
        public List<TileNode> children;
        public boolean isLoaded = false;
        public PointCloudData pointCloudData;

        // Computed properties
        public double distanceToCamera = Double.MAX_VALUE;
        public boolean shouldRender = false;
    }

    /**
     * Bounding volume for a tile (box or region)
     */
    public static class BoundingVolume {
        public double[] box;      // [centerX, centerY, centerZ, halfAxisX1, halfAxisX2, halfAxisX3, ...]
        public double[] region;   // [west, south, east, north, minHeight, maxHeight]
        public double[] sphere;   // [centerX, centerY, centerZ, radius]

        public double[] getCenter() {
            if (box != null && box.length >= 3) {
                return new double[]{box[0], box[1], box[2]};
            } else if (sphere != null && sphere.length >= 3) {
                return new double[]{sphere[0], sphere[1], sphere[2]};
            } else if (region != null && region.length >= 6) {
                // Approximate center for region
                double centerLon = (region[0] + region[2]) / 2.0;
                double centerLat = (region[1] + region[3]) / 2.0;
                double centerHeight = (region[4] + region[5]) / 2.0;
                return new double[]{centerLon, centerLat, centerHeight};
            }
            return new double[]{0, 0, 0};
        }

        public double getRadius() {
            if (sphere != null && sphere.length >= 4) {
                return sphere[3];
            } else if (box != null && box.length >= 12) {
                // Approximate radius for box
                double dx = Math.abs(box[3]) + Math.abs(box[6]) + Math.abs(box[9]);
                double dy = Math.abs(box[4]) + Math.abs(box[7]) + Math.abs(box[10]);
                double dz = Math.abs(box[5]) + Math.abs(box[8]) + Math.abs(box[11]);
                return Math.sqrt(dx * dx + dy * dy + dz * dz);
            }
            return 100.0; // Default radius
        }
    }

    /**
     * Loaded point cloud data
     */
    public static class PointCloudData {
        public float[] positions;
        public byte[] colors;
        public int pointCount;
    }

    public TilesetLoader(Context context, String assetBasePath) {
        this.context = context;
        this.assetBasePath = assetBasePath;
    }

    /**
     * Load and parse tileset.json
     * @return true if successful
     */
    public boolean loadTileset() {
        try {
            AssetManager assetManager = context.getAssets();
            String tilesetPath = assetBasePath + "/tileset.json";

            Log.d(TAG, "Loading tileset from: " + tilesetPath);

            InputStream inputStream = assetManager.open(tilesetPath);
            InputStreamReader reader = new InputStreamReader(inputStream);

            Gson gson = new Gson();
            JsonObject tileset = gson.fromJson(reader, JsonObject.class);

            reader.close();

            // Parse root tile
            if (tileset.has("root")) {
                JsonObject rootJson = tileset.getAsJsonObject("root");
                rootTile = parseTileNode(rootJson);
                collectAllTiles(rootTile);

                Log.d(TAG, "Tileset loaded successfully. Total tiles: " + allTiles.size());
                return true;
            } else {
                Log.e(TAG, "No root tile found in tileset.json");
                return false;
            }

        } catch (Exception e) {
            Log.e(TAG, "Error loading tileset", e);
            return false;
        }
    }

    /**
     * Parse a single tile node from JSON
     */
    private TileNode parseTileNode(JsonObject tileJson) {
        TileNode tile = new TileNode();

        // Parse bounding volume
        if (tileJson.has("boundingVolume")) {
            tile.boundingVolume = parseBoundingVolume(tileJson.getAsJsonObject("boundingVolume"));
        }

        // Parse geometric error
        if (tileJson.has("geometricError")) {
            tile.geometricError = tileJson.get("geometricError").getAsDouble();
        }

        // Parse content URI
        if (tileJson.has("content")) {
            JsonObject content = tileJson.getAsJsonObject("content");
            if (content.has("uri") || content.has("url")) {
                tile.contentUri = content.has("uri") ?
                        content.get("uri").getAsString() :
                        content.get("url").getAsString();
            }
        }

        // Parse children
        if (tileJson.has("children")) {
            JsonArray childrenArray = tileJson.getAsJsonArray("children");
            tile.children = new ArrayList<>();

            for (JsonElement childElement : childrenArray) {
                JsonObject childJson = childElement.getAsJsonObject();
                TileNode childTile = parseTileNode(childJson);
                tile.children.add(childTile);
            }
        }

        return tile;
    }

    /**
     * Parse bounding volume from JSON
     */
    private BoundingVolume parseBoundingVolume(JsonObject volumeJson) {
        BoundingVolume volume = new BoundingVolume();

        if (volumeJson.has("box")) {
            JsonArray boxArray = volumeJson.getAsJsonArray("box");
            volume.box = new double[boxArray.size()];
            for (int i = 0; i < boxArray.size(); i++) {
                volume.box[i] = boxArray.get(i).getAsDouble();
            }
        }

        if (volumeJson.has("region")) {
            JsonArray regionArray = volumeJson.getAsJsonArray("region");
            volume.region = new double[regionArray.size()];
            for (int i = 0; i < regionArray.size(); i++) {
                volume.region[i] = regionArray.get(i).getAsDouble();
            }
        }

        if (volumeJson.has("sphere")) {
            JsonArray sphereArray = volumeJson.getAsJsonArray("sphere");
            volume.sphere = new double[sphereArray.size()];
            for (int i = 0; i < sphereArray.size(); i++) {
                volume.sphere[i] = sphereArray.get(i).getAsDouble();
            }
        }

        return volume;
    }

    /**
     * Collect all tiles in the hierarchy into a flat list
     */
    private void collectAllTiles(TileNode tile) {
        if (tile == null) return;

        allTiles.add(tile);

        if (tile.children != null) {
            for (TileNode child : tile.children) {
                collectAllTiles(child);
            }
        }
    }

    /**
     * Select tiles to render based on camera position and LOD
     * @param cameraPos Camera position [x, y, z]
     * @param screenSpaceError Maximum screen space error threshold
     * @return List of tiles that should be rendered
     */
    public List<TileNode> selectTilesToRender(float[] cameraPos, double screenSpaceError) {
        List<TileNode> tilesToRender = new ArrayList<>();

        if (rootTile != null) {
            selectTilesRecursive(rootTile, cameraPos, screenSpaceError, tilesToRender);
        }

        Log.d(TAG, "Selected " + tilesToRender.size() + " tiles to render");
        return tilesToRender;
    }

    /**
     * Recursive LOD selection
     */
    private void selectTilesRecursive(TileNode tile, float[] cameraPos, double screenSpaceError,
                                      List<TileNode> result) {
        if (tile.boundingVolume == null) {
            return;
        }

        // Calculate distance from camera to tile center
        double[] center = tile.boundingVolume.getCenter();
        double dx = center[0] - cameraPos[0];
        double dy = center[1] - cameraPos[1];
        double dz = center[2] - cameraPos[2];
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);

        tile.distanceToCamera = distance;

        // Calculate screen space error
        double radius = tile.boundingVolume.getRadius();
        double projectedError = (tile.geometricError * 1000.0) / Math.max(distance, 1.0);

        // Decide whether to render this tile or its children
        if (projectedError <= screenSpaceError || tile.children == null || tile.children.isEmpty()) {
            // Render this tile
            if (tile.contentUri != null) {
                tile.shouldRender = true;
                result.add(tile);
            }
        } else {
            // Traverse children for higher detail
            for (TileNode child : tile.children) {
                selectTilesRecursive(child, cameraPos, screenSpaceError, result);
            }
        }
    }

    /**
     * Load point cloud data for a specific tile
     */
    public boolean loadTileContent(TileNode tile) {
        if (tile.isLoaded || tile.contentUri == null) {
            return tile.isLoaded;
        }

        try {
            String contentPath = assetBasePath + "/" + tile.contentUri;
            Log.d(TAG, "Loading tile content: " + contentPath);

            AssetManager assetManager = context.getAssets();
            InputStream inputStream = assetManager.open(contentPath);

            PntsParser parser = new PntsParser();
            if (parser.parse(inputStream)) {
                PointCloudData data = new PointCloudData();
                data.positions = parser.getPositions();
                data.colors = parser.getColors();
                data.pointCount = parser.getPointCount();

                tile.pointCloudData = data;
                tile.isLoaded = true;

                Log.d(TAG, "Loaded " + data.pointCount + " points from " + tile.contentUri);
                return true;
            }

            inputStream.close();

        } catch (Exception e) {
            Log.e(TAG, "Error loading tile content: " + tile.contentUri, e);
        }

        return false;
    }

    // Getters
    public TileNode getRootTile() {
        return rootTile;
    }

    public List<TileNode> getAllTiles() {
        return allTiles;
    }
}