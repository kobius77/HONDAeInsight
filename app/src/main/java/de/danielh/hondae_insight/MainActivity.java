package de.danielh.hondae_insight;

import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast; // Added Import

import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import org.jetbrains.annotations.NotNull;

import java.util.Collection;

public class MainActivity extends AppCompatActivity {

    private MainActivityViewModel _viewModel;
    private SharedPreferences _preferences;
    private final static String DEVICE_NAME = "device_name";
    private final static String DEVICE_MAC = "device_mac";
    
    // Flag to prevent auto-connecting if the user just pressed "Back"
    private boolean shouldAutoConnect = true; 

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Updated ViewModel initialization
        _viewModel = new ViewModelProvider(this).get(MainActivityViewModel.class);

        // --- NEW CODE START: Listen for errors and close requests from ViewModel ---
        
        // 1. Listen for Toast messages (errors like "No Bluetooth")
        _viewModel.getToastMessage().observe(this, messageResId -> {
            if (messageResId != null) {
                Toast.makeText(this, messageResId, Toast.LENGTH_LONG).show();
            }
        });

        // 2. Listen for the close signal (if setup failed)
        _viewModel.getCloseActivity().observe(this, shouldClose -> {
            if (shouldClose != null && shouldClose) {
                finish();
            }
        });
        
        // --- NEW CODE END ---

        if (!_viewModel.setupViewModel()) {
            // We can return here, but the observer above will handle the finish()
            return;
        }

        _preferences = getPreferences(MODE_PRIVATE);

        RecyclerView deviceList = findViewById(R.id.main_devices);
        SwipeRefreshLayout swipeRefreshLayout = findViewById(R.id.main_swiperefresh);

        deviceList.setLayoutManager(new LinearLayoutManager(this));
        DeviceAdapter adapter = new DeviceAdapter();
        deviceList.setAdapter(adapter);

        swipeRefreshLayout.setOnRefreshListener(() -> {
            _viewModel.refreshPairedDevices();
            swipeRefreshLayout.setRefreshing(false);
        });

        _viewModel.getPairedDeviceList().observe(this, adapter::updateList);
        _viewModel.refreshPairedDevices();

        // Only auto-connect if this is a fresh start (savedInstanceState is null)
        if (savedInstanceState == null) {
            checkLastConnectedDevice();
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
            String name = device.getName() != null ? device.getName() : "Unknown Device";
            _text1.setText(name);
            _text2.setText(device.getAddress());
            _layout.setOnClickListener(view -> openCommunicationsActivity(name, device.getAddress()));
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
