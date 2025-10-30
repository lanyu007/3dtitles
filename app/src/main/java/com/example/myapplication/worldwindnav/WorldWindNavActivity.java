package com.example.myapplication.worldwindnav;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.myapplication.R;
import com.example.myapplication.exampledemo.worldwindx.BasicPerformanceBenchmarkActivity;
import com.example.myapplication.exampledemo.worldwindx.BasicStressTestActivity;
import com.example.myapplication.exampledemo.worldwindx.DayNightCycleActivity;
import com.example.myapplication.exampledemo.worldwindx.GeneralGlobeActivity;
import com.example.myapplication.exampledemo.worldwindx.MultiGlobeActivity;
import com.example.myapplication.exampledemo.worldwindx.OmnidirectionalSightlineActivity;
import com.example.myapplication.exampledemo.worldwindx.PathsExampleActivity;
import com.example.myapplication.exampledemo.worldwindx.PathsPolygonsLabelsActivity;
import com.example.myapplication.exampledemo.worldwindx.PlacemarksDemoActivity;
import com.example.myapplication.exampledemo.worldwindx.PlacemarksMilStd2525Activity;
import com.example.myapplication.exampledemo.worldwindx.PlacemarksMilStd2525DemoActivity;
import com.example.myapplication.exampledemo.worldwindx.PlacemarksMilStd2525StressActivity;
import com.example.myapplication.exampledemo.worldwindx.PlacemarksSelectDragActivity;
import com.example.myapplication.exampledemo.worldwindx.PlacemarksStressTestActivity;
import com.example.myapplication.threedimentionmap.WorldWindOfflineMapActivity;

import java.util.ArrayList;
import java.util.List;

public class WorldWindNavActivity extends AppCompatActivity {

    public static void start(Context context) {
        Intent intent = new Intent(context, WorldWindNavActivity.class);
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_world_wind_nav);

        List<String> list = new ArrayList<>();
        list.add("R.id.nav_basic_performance_benchmark_activity");
        list.add("R.id.nav_basic_stress_test_activity");
        list.add("R.id.nav_day_night_cycle_activity");
        list.add("R.id.nav_general_globe_activity");
        list.add("R.id.nav_multi_globe_activity");
        list.add("R.id.nav_omnidirectional_sightline_activity");
        list.add("R.id.nav_paths_example");
        list.add("R.id.nav_paths_and_polygons_activity");
        list.add("R.id.nav_placemarks_demo_activity");
        list.add("R.id.nav_placemarks_milstd2525_activity");
        list.add("R.id.nav_placemarks_milstd2525_demo_activity");
        list.add("R.id.nav_placemarks_milstd2525_stress_activity");
        list.add("R.id.nav_placemarks_select_drag_activity");
        list.add("R.id.nav_placemarks_stress_activity");
        list.add("R.id.nav_texture_stress_test_activity");

        RecyclerView rv_item = findViewById(R.id.rv_item);
        rv_item.setLayoutManager(new LinearLayoutManager(this));
        NavAdapter adapter = new NavAdapter(this,list);
        rv_item.setAdapter(adapter);
    }
}