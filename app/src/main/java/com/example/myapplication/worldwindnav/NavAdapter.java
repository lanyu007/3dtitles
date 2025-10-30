package com.example.myapplication.worldwindnav;

import static androidx.core.content.ContextCompat.startActivity;

import android.content.Context;
import android.content.Intent;
import android.view.View;


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
import com.example.myapplication.exampledemo.worldwindx.TextureStressTestActivity;
import com.example.myapplication.recyclerview.CommonAdapter;
import com.example.myapplication.recyclerview.base.ViewHolder;

import java.util.List;

public class NavAdapter extends CommonAdapter<String> {
    private Context context;

    public NavAdapter(Context context, List<String> datas) {
        super(context, R.layout.item_nav_layout, datas);
        this.context = context;
    }

    @Override
    protected void convert(ViewHolder holder, String s, int position) {
        holder.setText(R.id.tv_activity,"to "+s);

        holder.getConvertView().setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                switch (s) {
                    case "R.id.nav_basic_performance_benchmark_activity":
                        context.startActivity(new Intent(context, BasicPerformanceBenchmarkActivity.class));
                        break;
                    case "R.id.nav_basic_stress_test_activity":
                        context.startActivity(new Intent(context, BasicStressTestActivity.class));
                        break;
                    case "R.id.nav_day_night_cycle_activity":
                        context.startActivity(new Intent(context, DayNightCycleActivity.class));
                        break;
                    case "R.id.nav_general_globe_activity":
                        context.startActivity(new Intent(context, GeneralGlobeActivity.class));
                        break;
                    case "R.id.nav_multi_globe_activity":
                        context.startActivity(new Intent(context, MultiGlobeActivity.class));
                        break;
                    case "R.id.nav_omnidirectional_sightline_activity":
                        context.startActivity(new Intent(context, OmnidirectionalSightlineActivity.class));
                        break;
                    case "R.id.nav_paths_example":
                        context.startActivity(new Intent(context, PathsExampleActivity.class));
                        break;
                    case "R.id.nav_paths_and_polygons_activity":
                        context.startActivity(new Intent(context, PathsPolygonsLabelsActivity.class));
                        break;
                    case "R.id.nav_placemarks_demo_activity":
                        context.startActivity(new Intent(context, PlacemarksDemoActivity.class));
                        break;
                    case "R.id.nav_placemarks_milstd2525_activity":
                        context.startActivity(new Intent(context, PlacemarksMilStd2525Activity.class));
                        break;
                    case "R.id.nav_placemarks_milstd2525_demo_activity":
                        context.startActivity(new Intent(context, PlacemarksMilStd2525DemoActivity.class));
                        break;
                    case "R.id.nav_placemarks_milstd2525_stress_activity":
                        context.startActivity(new Intent(context, PlacemarksMilStd2525StressActivity.class));
                        break;
                    case "R.id.nav_placemarks_select_drag_activity":
                        context.startActivity(new Intent(context, PlacemarksSelectDragActivity.class));
                        break;
                    case "R.id.nav_placemarks_stress_activity":
                        context.startActivity(new Intent(context, PlacemarksStressTestActivity.class));
                        break;
                    case "R.id.nav_texture_stress_test_activity":
                        context.startActivity(new Intent(context, TextureStressTestActivity.class));
                        break;
                }
            }
        });

    }
}
