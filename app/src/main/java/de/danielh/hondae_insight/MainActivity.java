package de.danielh.hondae_insight;

import android.Manifest;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    private MainActivityViewModel _viewModel;
    private SharedPreferences _preferences;
    private final static String DEVICE_NAME = "device_name";
    private final static String DEVICE_MAC = "device_mac";
    
    // UPDATED: Launcher for Multiple Permissions (Connect + Scan)
    private ActivityResultLauncher<String[]> requestPermissionLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 1. Initialize the Permission Launcher
        requestPermissionLauncher = registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
            // Check if all requested permissions were granted
            boolean allGranted = true;
            for (Map.Entry<String, Boolean> entry : result.entrySet()) {
                if (!entry.getValue()) allGranted = false;
            }

            if (allGranted) {
                runBluetoothStartup(savedInstanceState);
            } else {
                Toast.makeText(this, "Bluetooth permissions are required.", Toast.LENGTH_LONG).show();
                finish();
            }
        });

        _viewModel = new ViewModelProvider(this).get(MainActivityViewModel.class);
        
        // Setup Observers
        _viewModel.getToastMessage().observe(this, messageResId -> {
            if (messageResId != null) Toast.makeText(this, messageResId, Toast.LENGTH_LONG).show();
        });
        _viewModel.getCloseActivity().observe(this, shouldClose -> {
            if (shouldClose != null && shouldClose) finish();
        });

        if (!_viewModel.setupViewModel()) {
            return;
        }

        _preferences = getPreferences(MODE_PRIVATE);

        RecyclerView deviceList = findViewById(R.id.main_devices);
        SwipeRefreshLayout swipeRefreshLayout = findViewById(R.id.main_swiperefresh);

        deviceList.setLayoutManager(new LinearLayoutManager(this));
        DeviceAdapter adapter = new DeviceAdapter();
        deviceList.setAdapter(adapter);

        _viewModel.getPairedDeviceList().observe(this, adapter::updateList);

        swipeRefreshLayout.setOnRefreshListener(() -> {
            if (hasBluetoothPermissions()) {
                _viewModel.refreshPairedDevices();
            } else {
                requestPermissions();
            }
            swipeRefreshLayout.setRefreshing(false);
        });

        // 2. Start the Logic Flow
        if (hasBluetoothPermissions()) {
            runBluetoothStartup(savedInstanceState);
        } else {
            requestPermissions();
        }
    }

    private void runBluetoothStartup(Bundle savedInstanceState) {
        _viewModel.refreshPairedDevices();
        if (savedInstanceState == null) {
            checkLastConnectedDevice();
        }
    }

    // UPDATED: Check for BOTH Connect and Scan
    private boolean hasBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                   ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
        }
        return true; 
    }

    // UPDATED: Request BOTH Connect and Scan
    private void requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestPermissionLauncher.launch(new String[]{
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            });
        }
    }

    private void checkLastConnectedDevice() {
        String deviceName = _preferences.getString(DEVICE_NAME, null);
        String deviceMac = _preferences.getString(DEVICE_MAC, null);

        if (deviceName != null && deviceMac != null) {
            new Handler(Looper.getMainLooper()).post(() -> 
                openCommunicationsActivity(deviceName, deviceMac)
            );
        }
    }

    public void openCommunicationsActivity(String deviceName, String macAddress) {
        if (!hasBluetoothPermissions()) return;

        final SharedPreferences.Editor editor = _preferences.edit();
        editor.putString(DEVICE_NAME, deviceName);
        editor.putString(DEVICE_MAC, macAddress);
        editor.apply();
        
        Intent intent = new Intent(this, CommunicateActivity.class);
        intent.putExtra("device_name", deviceName);
        intent.putExtra("device_mac", macAddress);
        startActivity(intent);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onBackPressed() {
        finish();
    }

    // --- ViewHolder and Adapter ---
    private class DeviceViewHolder extends RecyclerView.ViewHolder {
        private final RelativeLayout _layout;
        private final TextView _text1;
        private final TextView _text2;

        DeviceViewHolder(View view) {
            super(view);
            _layout = view.findViewById(R.id.list_item);
            _text1 = view.findViewById(R.id.list_item_text1);
            _text2 = view.findViewById(R.id.list_item_text2);
        }

        void setupView(BluetoothDevice device) {
            try {
                if (!hasBluetoothPermissions()) {
                     _text1.setText("Permission Missing");
                     return;
                }
                String name = device.getName() != null ? device.getName() : "Unknown Device";
                _text1.setText(name);
                _text2.setText(device.getAddress());
                _layout.setOnClickListener(view -> openCommunicationsActivity(name, device.getAddress()));
            } catch (SecurityException e) {
                _text1.setText("Error");
            }
        }
    }

    class DeviceAdapter extends RecyclerView.Adapter<DeviceViewHolder> {
        private BluetoothDevice[] _deviceList = new BluetoothDevice[0];

        @NotNull
        @Override
        public DeviceViewHolder onCreateViewHolder(@NotNull ViewGroup parent, int viewType) {
            return new DeviceViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.list_item, parent, false));
        }

        @Override
        public void onBindViewHolder(@NotNull DeviceViewHolder holder, int position) {
            holder.setupView(_deviceList[position]);
        }

        @Override
        public int getItemCount() {
            return _deviceList.length;
        }

        void updateList(Collection<BluetoothDevice> deviceList) {
            this._deviceList = deviceList.toArray(new BluetoothDevice[0]);
            notifyDataSetChanged();
        }
    }
}
