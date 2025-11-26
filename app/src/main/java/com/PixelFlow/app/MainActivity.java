package com.PixelFlow.app;

import android.os.Bundle;
import android.view.View;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

public class MainActivity extends AppCompatActivity {

    private BottomNavigationView bottomNavigationView;
    private FloatingActionButton fabCamera;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initView();
        setupBottomNavigation();
        setupCameraButton();

        // 默认显示首页
        if (savedInstanceState == null) {
//            showFragment(HomeFragment.newInstance());
            bottomNavigationView.setSelectedItemId(R.id.nav_home);
        }
    }

    private void initView() {
        bottomNavigationView = findViewById(R.id.bottom_navigation);
        fabCamera = findViewById(R.id.fab_camera);
    }

    private void setupBottomNavigation() {
        bottomNavigationView.setOnItemSelectedListener(item -> {
            Fragment selectedFragment = null;

            int itemId = item.getItemId();
            if (itemId == R.id.nav_home) {
                selectedFragment = HomeFragment.newInstance();
            }
//            else if (itemId == R.id.nav_friends) {
//                selectedFragment = FriendsFragment.newInstance();
//            } else if (itemId == R.id.nav_messages) {
//                selectedFragment = MessagesFragment.newInstance();
//            } else if (itemId == R.id.nav_profile) {
//                selectedFragment = ProfileFragment.newInstance();
//            }
            // 注意：我们不处理相机占位项的点击

            if (selectedFragment != null) {
                showFragment(selectedFragment);
                return true;
            }

            return false;
        });
    }

    private void setupCameraButton() {
        fabCamera.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 处理相机按钮点击事件
                openCamera();
            }
        });
    }

    private void openCamera() {
        // 这里实现打开相机的逻辑
        // 例如：启动相机Activity、打开相机Fragment、请求权限等

        // 示例：显示一个提示
        // Toast.makeText(this, "打开相机", Toast.LENGTH_SHORT).show();

        // 后续你可以在这里添加真实的相机功能
    }

    private void showFragment(Fragment fragment) {
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.fragment_container, fragment)
                .commit();
    }
}