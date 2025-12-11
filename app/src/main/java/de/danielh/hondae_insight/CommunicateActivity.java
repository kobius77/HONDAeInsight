package de.danielh.hondae_insight;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Environment;
import android.text.TextUtils;
import android.view.MenuItem;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.Checkable;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.ImageButton;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProviders;

import java.io.File;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.KeyEvent;

// MQTT Imports
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

public class CommunicateActivity extends AppCompatActivity implements LocationListener {

    public static final int CAN_BUS_SCAN_INTERVALL = 30000;
    public static final int WAIT_FOR_NEW_MESSAGE_TIMEOUT = 1000;
    public static final int WAIT_TIME_BETWEEN_COMMAND_SENDS_MS = 50;

    // CAN Command IDs
    public static final String VIN_ID = "1862F190";
    public static final String AMBIENT_ID = "39627028";
    public static final String SOH_ID = "F6622021";
    public static final String SOC_ID = "F6622029";
    public static final String BATTEMP_ID = "F662202A";
    public static final String ODO_ID = "39627022";

    public static final int RANGE_ESTIMATE_WINDOW_5KM = 5;

    // PREFERENCES KEYS (Renamed Constants, but keeping VALUES same to preserve user data)
    private static final String PREFS_KEY_MQTT_URL = "abrp_user_token";
    private static final String PREFS_KEY_MQTT_SWITCH = "iternioSendToAPISwitch";
    
    private static final int MAX_RETRY = 5; // Used for Bluetooth logic

    private static final String NOTIFICATION_CHANNEL_ID = "SoC";
    private static final int NOTIFICATION_ID = 23;

    // Bluetooth Commands
    private final ArrayList<String> _connectionCommands = new ArrayList<>(Arrays.asList(
            "ATWS", "ATE0", "ATSP7", "ATAT1", "ATH1", "ATL0", "ATS0", "ATRV",
            "ATAL", "ATCAF1", "ATSHDA01F1", "ATFCSH18DA01F1", "ATFCSD300000",
            "ATFCSM1", "ATCFC1", "ATCP18", "ATSHDA07F1", "ATFCSH18DA07F1",
            "ATCRA18DAF107", "22F190" //VIN
    ));

    private final ArrayList<String> _loopCommands = new ArrayList<>(Arrays.asList(
            "ATSHDA60F1", "ATFCSH18DA60F1", "ATCRA18DAF160",
            "227028", //AMBIENT
            "2270229", //ODO
            "ATSHDA15F1", "ATFCSH18DA15F1", "ATCRA18DAF115",
            "222021", //SOH VOLT AMP
            "222029", //SOC
            "ATSHDA01F1", "ATFCSH18DA01F1", "ATCRA18DAF101",
            "22202A", // BATTTEMP
            "ATRV" // AUX BAT
    ));

    private final String LOG_FILE_HEADER = "sysTimeMs,ODO,SoC (dash),SoC (min),SoC (max),SoH,Battemp,Ambienttemp,kW,Amp,Volt,AuxBat,Connection,Charging,Speed,Lat,Lon";
    
    // UI Elements
    private TextView _connectionText, _vinText, _messageText, _socMinText, _socMaxText, _socDeltaText,
            _socDashText, _batTempText, _batTempDeltaText, _ambientTempText, _sohText, _kwText, _ampText, _voltText, _auxBatText, _odoText,
            _rangeText, _chargingText, _speedText, _gpsStatusText, _apiStatusText;
    
    // MQTT UI Elements (Renamed)
    private EditText _mqttUrlText;
    private Switch _mqttSwitch;
    
    private CheckBox _isChargingCheckBox;
    private Button _connectButton;

    // Data Variables
    private double _soc, _socMin, _socMax, _socDelta, _soh, _speed, _power, _batTemp, _amp, _volt, _auxBat;
    private byte _ambientTemp;
    private final double[] _socHistory = new double[RANGE_ESTIMATE_WINDOW_5KM + 1];
    private final double[] _socMinHistory = new double[RANGE_ESTIMATE_WINDOW_5KM + 1];
    private final double[] _socMaxHistory = new double[RANGE_ESTIMATE_WINDOW_5KM + 1];
    private final double[] _batTempHistory = new double[RANGE_ESTIMATE_WINDOW_5KM + 1];
    private int _socHistoryPosition = 0;
    private int _lastOdo = Integer.MIN_VALUE, _odo;
    
    private String _vin;
    private String _lat = "0.0", _lon = "0.0"; // Needed for MQTT/Log
    private String _gpsStatus = "No Fix";
    private double _elevation;
    
    private ChargingConnection _chargingConnection;
    private boolean _isCharging;
    
    // System Variables
    private PrintWriter _logFileWriter;
    private SharedPreferences _preferences;
    private long _sysTimeMs;
    private long _epoch, _lastEpoch, _lastEpochNotification, _lastEpochSuccessfulApiSend;
    
    private CommunicateViewModel _viewModel;
    private volatile boolean _loopRunning = false;
    private volatile boolean _mqttRunning = false; // Was _sendDataToIternioRunning
    private volatile int _retries = 0;
    private boolean _carConnected = false;
    private byte _newMessage;

    // MQTT Persistent Client
    private MqttClient _mqttClient;
    private MqttConnectOptions _mqttConnOpts;

    NotificationCompat.Builder _notificationBuilder;
    NotificationManagerCompat _notificationManagerCompat;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_communicate);
        
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        // --- AUTO-CONNECT LOGIC START ---
        String deviceName = getIntent().getStringExtra("device_name");
        String deviceMac = getIntent().getStringExtra("device_mac");
        
        SharedPreferences prefs = getPreferences(MODE_PRIVATE); // Temp local var for clarity

        if (deviceMac != null) {
            prefs.edit().putString("saved_device_name", deviceName)
                        .putString("saved_device_mac", deviceMac)
                        .apply();
        } else {
            deviceName = prefs.getString("saved_device_name", "Unknown Device");
            deviceMac = prefs.getString("saved_device_mac", null);
        }

        _viewModel = ViewModelProviders.of(this).get(CommunicateViewModel.class);
        if (deviceMac == null || !_viewModel.setupViewModel(deviceName, deviceMac)) {
            finish();
            return;
        }
        // --- AUTO-CONNECT LOGIC END ---

        _preferences = getPreferences(MODE_PRIVATE);

        // Notification Setup
        _notificationBuilder = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.mipmap.e_logo)
                .setContentTitle("e Insight")
                .setContentText("Start")
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);
        createNotificationChannel();
        _notificationManagerCompat = NotificationManagerCompat.from(this);

        // UI Setup - Find Views
        _connectionText = findViewById(R.id.communicate_connection_text);
        _messageText = findViewById(R.id.communicate_message);
        _vinText = findViewById(R.id.communicate_vin);
        _speedText = findViewById(R.id.communicate_speed);
        
        // Corrected View Binding
        _gpsStatusText = findViewById(R.id.communicate_gps_status);
        _socMinText = findViewById(R.id.communicate_soc_min);
        _socMaxText = findViewById(R.id.communicate_soc_max);
        _socDashText = findViewById(R.id.communicate_soc_dash);
        _socDeltaText = findViewById(R.id.communicate_soc_delta);
        _chargingText = findViewById(R.id.communicate_charging_connection);
        _isChargingCheckBox = findViewById(R.id.communicate_is_charging);
        _batTempText = findViewById(R.id.communicate_battemp);
        _batTempDeltaText = findViewById(R.id.communicate_battemp_delta);
        _ambientTempText = findViewById(R.id.communicate_ambient_temp);
        _sohText = findViewById(R.id.communicate_soh);
        _kwText = findViewById(R.id.communicate_kw);
        _ampText = findViewById(R.id.communicate_amp);
        _voltText = findViewById(R.id.communicate_volt);
        _auxBatText = findViewById(R.id.communicate_aux_bat);
        _odoText = findViewById(R.id.communicate_odo);
        _rangeText = findViewById(R.id.communicate_range);
        _apiStatusText = findViewById(R.id.communicate_api_status);

        // MQTT UI Setup
        _mqttUrlText.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE ||
                (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN)) {

                // 1. Save the new URL immediately
                SharedPreferences.Editor edit = _preferences.edit();
                edit.putString(PREFS_KEY_MQTT_URL, v.getText().toString());
                edit.apply();

                // 2. Hide Keyboard & Clear Focus
                v.clearFocus();
                hideKeyboard();

                // 3. FORCE Reconnect with new settings
                restartMqtt();
                return true;
            }
            return false;
        });
        }

        _viewModel.getConnectionStatus().observe(this, this::onConnectionStatus);
        _viewModel.getDeviceName().observe(this, name -> setTitle(getString(R.string.device_name_format, name)));

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Location Permissions
        try {
            if (ContextCompat.checkSelfPermission(getApplicationContext(), android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION}, 101);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        LocationManager lm = (LocationManager) this.getSystemService(Context.LOCATION_SERVICE);
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, this);
        } catch (SecurityException e) {
            // Permission not granted
        }

        checkExternalMedia();
        
        // --- NEW: Connect to MQTT immediately on startup ---
        new Thread(this::connectToMqtt).start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Smart Save: Automatically save the MQTT URL whenever the user leaves the app
        if (_preferences != null && _mqttUrlText != null) {
            SharedPreferences.Editor edit = _preferences.edit();
            String currentUrl = _mqttUrlText.getText().toString();
            if (!currentUrl.isEmpty()) {
                edit.putString(PREFS_KEY_MQTT_URL, currentUrl);
                edit.apply();
            }
        }
    }

    @Override
    protected void onDestroy() {
        // Close MQTT connection politely
        try {
            if (_mqttClient != null && _mqttClient.isConnected()) {
                _mqttClient.disconnect();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        super.onDestroy();
    }

    // --- NEW MQTT CONNECTION METHOD ---
    private void connectToMqtt() {
        try {
            // 1. Initialize Client if it doesn't exist yet
            if (_mqttClient == null) {
                String rawInput = _preferences.getString(PREFS_KEY_MQTT_URL, "").trim();
                String brokerUrl = rawInput;
                String username = null;
                String password = null;

                // Parse Credentials (tcp://user:pass@host:port)
                if (rawInput.contains("@") && rawInput.startsWith("tcp://")) {
                    try {
                        String withoutScheme = rawInput.substring(6);
                        int atIndex = withoutScheme.lastIndexOf("@");
                        if (atIndex != -1) {
                            String userPass = withoutScheme.substring(0, atIndex);
                            String hostPort = withoutScheme.substring(atIndex + 1);
                            brokerUrl = "tcp://" + hostPort;
                            int colonIndex = userPass.indexOf(":");
                            if (colonIndex != -1) {
                                username = userPass.substring(0, colonIndex);
                                password = userPass.substring(colonIndex + 1);
                            } else {
                                username = userPass;
                            }
                        }
                    } catch (Exception e) {
                        brokerUrl = rawInput; // Fallback
                    }
                }

                // Setup Options
                _mqttConnOpts = new MqttConnectOptions();
                _mqttConnOpts.setCleanSession(true);
                _mqttConnOpts.setConnectionTimeout(10); 
                _mqttConnOpts.setKeepAliveInterval(60); 
                
                if (username != null && !username.isEmpty()) {
                    _mqttConnOpts.setUserName(username);
                    if (password != null) {
                        _mqttConnOpts.setPassword(password.toCharArray());
                    }
                }

                String clientId = "HondaE_Android_" + System.currentTimeMillis();
                _mqttClient = new MqttClient(brokerUrl, clientId, new MemoryPersistence());
            }

            // 2. Connect if not connected
            if (!_mqttClient.isConnected()) {
                _mqttClient.connect(_mqttConnOpts);
                setText(_apiStatusText, "🔵"); 
            }

        } catch (Exception e) {
            setText(_apiStatusText, "🔴");
        }
    }

    // --- REPURPOSED: PUBLISH ONLY ---
    private void publishMqttMessage() { 
        try {
            // 1. Auto-Reconnect logic
            if (_mqttClient == null || !_mqttClient.isConnected()) {
                connectToMqtt();
            }

            // 2. Publish ONLY if connected
            if (_mqttClient != null && _mqttClient.isConnected()) {
                String topic = "hondae/status";
                
                String payload = "{" +
                        "\"soc\":" + _soc +
                        ",\"soh\":" + _soh +
                        ",\"power\":" + _power +
                        ",\"amp\":" + _amp +
                        ",\"volt\":" + _volt +
                        ",\"batt_temp\":" + _batTemp +
                        ",\"ambient_temp\":" + _ambientTemp +
                        ",\"is_charging\":" + _isCharging +
                        ",\"charging_mode\":\"" + _chargingConnection.getName() + "\"" +
                        ",\"speed\":" + _speed +
                        ",\"odo\":" + _odo +
                        ",\"lat\":" + _lat +
                        ",\"lon\":" + _lon +
                        ",\"elevation\":" + _elevation +
                        ",\"timestamp\":" + _epoch +
                        "}";

                MqttMessage message = new MqttMessage(payload.getBytes());
                message.setQos(0);
                _mqttClient.publish(topic, message);

                _lastEpochSuccessfulApiSend = _epoch;
                setText(_apiStatusText, "🔵"); 
            } else {
                setText(_apiStatusText, "🔴");
            }

        } catch (Exception e) {
            if (_epoch - _lastEpochSuccessfulApiSend > 9) {
                setText(_apiStatusText, "🔴");
            }
        }
    }

    private void createNotificationChannel() {
        CharSequence name = getString(R.string.channel_name);
        String description = getString(R.string.channel_description);
        int importance = NotificationManager.IMPORTANCE_DEFAULT;
        NotificationChannel channel = new NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance);
        channel.setDescription(description);
        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        notificationManager.createNotificationChannel(channel);
    }

    private void connectCAN() { 
        try {
            setText(_apiStatusText, "⚪");
            for (String command : _connectionCommands) {
                synchronized (_viewModel.getNewMessageParsed()) {
                    _viewModel.sendMessage(command + "\n\r");
                    _viewModel.getNewMessageParsed().wait(WAIT_FOR_NEW_MESSAGE_TIMEOUT);
                    if (_viewModel.isNewMessage()) {
                        final String message = _viewModel.getMessage();
                        if (_viewModel.isNewMessage() && _viewModel.getMessageID().equals(VIN_ID)) {
                            parseVIN(message);
                            setText(_vinText, _vin);
                            _carConnected = true;
                            _viewModel.setNewMessageProcessed();
                        } else if (_viewModel.isNewMessage()) {
                            setText(_messageText, message);
                            _viewModel.setNewMessageProcessed();
                        }
                        if (message.matches("\\d+\\.\\dV")) { //Aux Bat Voltage
                            _auxBat = Double.parseDouble(message.substring(0, message.length() - 1));
                            setText(_auxBatText, message);
                        }
                    }
                }
                if (command.length() <= 6) {
                    Thread.sleep(WAIT_TIME_BETWEEN_COMMAND_SENDS_MS);
                }
            }

            if (_carConnected) {
                Thread.sleep(WAIT_FOR_NEW_MESSAGE_TIMEOUT);
                openNewFileForWriting();
                loop();
            } else {
                setText(_messageText, "CAN not responding...");
                _viewModel.disconnect();
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private void loop() { 
        _loopRunning = true;
        
        while (_loopRunning) {
            try {
                _sysTimeMs = System.currentTimeMillis();
                
                loopMessagesToVariables();

                _epoch = _sysTimeMs / 1000;
                setText(_ambientTempText, _ambientTemp + ".0°C");
                setText(_sohText, String.format(Locale.ENGLISH, "%1$05.2f%%", _soh));
                setText(_ampText, String.format(Locale.ENGLISH, "%1$06.2fA", _amp));
                setText(_voltText, String.format(Locale.ENGLISH, "%1$.1f/%2$.2fV", _volt, _volt / 96));
                setText(_kwText, String.format(Locale.ENGLISH, "%1$05.1fkW", _power));
                
                setText(_socMinText, String.format(Locale.ENGLISH, "%1$05.2f%%", _socMin));
                setText(_socMaxText, String.format(Locale.ENGLISH, "%1$05.2f%%", _socMax));
                setText(_socDeltaText, String.format(Locale.ENGLISH, "%1$4.2f%%", _socDelta));
                setText(_socDashText, String.format(Locale.ENGLISH, "%1$05.2f%%", _soc));
                setText(_chargingText, _chargingConnection.getName());
                setChecked(_isChargingCheckBox, _isCharging);
                setText(_batTempText, _batTemp + "°C");
                setText(_odoText, _odo + "km");

                setText(_speedText, _speed + "km/h");
                setText(_gpsStatusText, _gpsStatus);

                if (_newMessage > 4) {
                    setText(_messageText, String.valueOf(_epoch));
                    
                    if (_lastEpochNotification + 10 < _epoch) {
                        _notificationBuilder.setContentText("SoC " + String.valueOf(_soc) + "%");
                        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                            _notificationManagerCompat.notify(NOTIFICATION_ID, _notificationBuilder.build());
                            _lastEpochNotification = _epoch;
                        }
                    }
                    
                    writeLineToLogFile();
                    
                    // MQTT Logic - Renamed Variable
                    if (_mqttRunning && _lastEpoch + 1 < _epoch) {
                        _lastEpoch = _epoch;
                        new Thread(this::publishMqttMessage).start();
                    }
                } else {
                    setText(_messageText, "Incomplete data (" + _newMessage + "), retrying...");
                }
                _newMessage = 0;

                Thread.sleep(CAN_BUS_SCAN_INTERVALL);

            } catch (InterruptedException e) {
                _loopRunning = false;
                
            } catch (Exception e) {
                String errorMsg = e.getMessage() != null ? e.getMessage() : "Unknown Error";
                setText(_messageText, "Error: " + errorMsg + ". Retrying in " + (CAN_BUS_SCAN_INTERVALL/1000) + "s...");

                try {
                    Thread.sleep(CAN_BUS_SCAN_INTERVALL);
                } catch (InterruptedException ie) {
                    _loopRunning = false;
                }
            }
        }
        _carConnected = false;
    }

    private void loopMessagesToVariables() throws InterruptedException {
        for (String command : _loopCommands) {
            synchronized (_viewModel.getNewMessageParsed()) {
                _viewModel.sendMessage(command + "\n\r");
                _viewModel.getNewMessageParsed().wait(WAIT_FOR_NEW_MESSAGE_TIMEOUT);
                
                if (_viewModel.isNewMessage()) {
                    final String message = _viewModel.getMessage();
                    final String messageID = _viewModel.getMessageID();

                    if (messageID.equals(AMBIENT_ID)) {
                        if (message.length() >= 44) {
                            _ambientTemp = Integer.valueOf(message.substring(42, 44), 16).byteValue();
                            _newMessage++;
                        }
                    } else if (messageID.equals(SOH_ID)) {
                        if (message.length() >= 285) {
                            _soh = Integer.parseInt(message.substring(198, 202), 16) / 100.0;
                            _amp = Math.round((Integer.valueOf(message.substring(280, 284), 16).shortValue() / 34.0) * 100.0) / 100.0;
                            _volt = Integer.parseInt(message.substring(76, 80), 16) / 10.0;
                            _power = Math.round(_amp * _volt / 1000.0 * 10.0) / 10.0;
                            _newMessage++;
                        }
                    } else if (messageID.equals(SOC_ID)) {
                        if (message.length() >= 280) {
                            _socMin = Integer.parseInt(message.substring(142, 146), 16) / 100.0;
                            _socMax = Integer.parseInt(message.substring(138, 142), 16) / 100.0;
                            _socDelta = Math.round((_socMax - _socMin) * 100.0) / 100.0;
                            _soc = Integer.parseInt(message.substring(156, 160), 16) / 100.0;
                            
                            if (message.length() > 161) {
                                _isCharging = message.charAt(161) == '1';
                            }
                            
                            String connectionType = message.substring(277, 278);
                            switch (connectionType) {
                                case "2": _chargingConnection = ChargingConnection.AC; break;
                                case "3": _chargingConnection = ChargingConnection.DC; break;
                                default: _chargingConnection = ChargingConnection.NC;
                            }
                            _newMessage++;
                        }
                    } else if (messageID.equals(BATTEMP_ID)) {
                        if (message.length() >= 415) {
                            _batTemp = Integer.valueOf(message.substring(410, 414), 16).shortValue() / 10.0;
                            _newMessage++;
                        }
                    } else if (messageID.equals(ODO_ID)) {
                        if (message.length() >= 26) {
                            _odo = Integer.parseInt(message.substring(18, 26), 16);
                            if (_lastOdo < _odo) {
                                _lastOdo = _odo;
                                _socHistory[_socHistoryPosition] = _soc;
                                _socMinHistory[_socHistoryPosition] = _socMin;
                                _socMaxHistory[_socHistoryPosition] = _socMax;
                                _batTempHistory[_socHistoryPosition] = _batTemp;
                                _socHistoryPosition = (_socHistoryPosition + 1) % (RANGE_ESTIMATE_WINDOW_5KM + 1);
                                
                                double socDelta = _socHistory[(_socHistoryPosition + 1) % (RANGE_ESTIMATE_WINDOW_5KM + 1)] - _soc;
                                double socMinDelta = _socMinHistory[(_socHistoryPosition + 1) % (RANGE_ESTIMATE_WINDOW_5KM + 1)] - _socMin;
                                double socMaxDelta = _socMaxHistory[(_socHistoryPosition + 1) % (RANGE_ESTIMATE_WINDOW_5KM + 1)] - _socMax;
                                double batTempDelta = _batTemp - _batTempHistory[(_socHistoryPosition + 1) % (RANGE_ESTIMATE_WINDOW_5KM + 1)];
                                long socRange = Math.round((_soc / socDelta) * RANGE_ESTIMATE_WINDOW_5KM);
                                long socMinRange = Math.round((_socMin / socMinDelta) * RANGE_ESTIMATE_WINDOW_5KM);
                                long socMaxRange = Math.round((_socMax / socMaxDelta) * RANGE_ESTIMATE_WINDOW_5KM);
                                double batTempChange = batTempDelta / RANGE_ESTIMATE_WINDOW_5KM;
                                if (socRange >= 0 || socMinRange >= 0 || socMaxRange >= 0) {
                                    setText(_rangeText, String.format(Locale.ENGLISH, "%1$03dkm / %2$03dkm / %3$03dkm", socRange, socMinRange, socMaxRange));
                                    setText(_batTempDeltaText, String.format(Locale.ENGLISH, "%1$.2fK/km", batTempChange));
                                } else {
                                    setText(_rangeText, "---km / ---km / ---km");
                                }
                            }
                            _newMessage++;
                        }
                    } else if (message.matches("\\d+\\.\\dV")) { //Aux Bat Voltage
                        _auxBat = Double.parseDouble(message.substring(0, message.length() - 1));
                        setText(_auxBatText, message);
                    }
                    _viewModel.setNewMessageProcessed();
                }
            }
            if (command.length() <= 7) {
                Thread.sleep(WAIT_TIME_BETWEEN_COMMAND_SENDS_MS);
            }
        }
    }

    private void checkExternalMedia() {
        boolean externalStorageWriteable = false;
        String state = Environment.getExternalStorageState();
        if (Environment.MEDIA_MOUNTED.equals(state)) {
            externalStorageWriteable = true;
        } 
        if (!externalStorageWriteable) {
            setText(_messageText, "\n\nExternal Media: writable=" + false);
        }
    }

    private void setText(final TextView text, final String value) {
        runOnUiThread(() -> text.setText(value));
    }

    private void setChecked(final Checkable checkable, final boolean checked) {
        runOnUiThread(() -> checkable.setChecked(checked));
    }

    private void handleMqttSwitch(boolean isChecked) {
        _mqttRunning = isChecked;
        SharedPreferences.Editor edit = _preferences.edit();
        edit.putBoolean(PREFS_KEY_MQTT_SWITCH, isChecked);
        String urlText = _mqttUrlText.getText().toString();
        if (!TextUtils.isEmpty(urlText)) {
            edit.putString(PREFS_KEY_MQTT_URL, urlText);
        }
        edit.apply();
    }

    private void parseVIN(String message) {
        _vin = hexToASCII(message.substring(10, 44));
    }

    private static String hexToASCII(String hexStr) {
        StringBuilder output = new StringBuilder();
        for (int i = 0; i < hexStr.length(); i += 2) {
            String str = hexStr.substring(i, i + 2);
            output.append((char) Integer.parseInt(str, 16));
        }
        return output.toString();
    }

    private void onConnectionStatus(CommunicateViewModel.ConnectionStatus connectionStatus) {
        switch (connectionStatus) {
            case CONNECTED:
                _connectionText.setText(R.string.status_connected);
                _connectButton.setEnabled(true);
                _connectButton.setText(R.string.disconnect);
                _connectButton.setOnClickListener(v -> _viewModel.disconnect());
                new Thread(this::connectCAN).start();
                break;

            case CONNECTING:
                _connectionText.setText(R.string.status_connecting);
                _connectButton.setEnabled(true);
                _connectButton.setText(R.string.stop);
                _viewModel.setRetry(true);
                _connectButton.setOnClickListener(v -> _viewModel.setRetry(false));
                break;

            case DISCONNECTED:
                _loopRunning = false;
                _connectionText.setText(R.string.status_disconnected);
                _connectButton.setEnabled(true);
                _connectButton.setText(R.string.connect);
                _connectButton.setOnClickListener(v -> _viewModel.connect());
                closeLogFile();
                _retries = 0;
                break;

            case RETRY:
                _retries++;
                if (_viewModel.isRetry() && _retries < MAX_RETRY) {
                    _viewModel.connect();
                } else {
                    _viewModel.disconnect();
                }
        }
    }

    private void openNewFileForWriting() {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss");
            Date now = new Date();
            // Using [0] for internal storage
            File logFile = new File(this.getExternalMediaDirs()[0], _vin + "-" + sdf.format(now) + ".csv");
            logFile.createNewFile();
            _logFileWriter = new PrintWriter(logFile);
            _logFileWriter.println(LOG_FILE_HEADER);
        } catch (Exception e) {
             e.printStackTrace();
        }
    }

    private void writeLineToLogFile() {
        String dataLine = _sysTimeMs + "," + _odo + "," + _soc + ","
                + _socMin + "," + _socMax + "," + _soh + "," + _batTemp + ","
                + _ambientTemp + "," + _power + "," + _amp + "," + _volt + ","
                + _auxBat + "," + _chargingConnection.getName() + "," + _isCharging
                + "," + _speed + "," + _lat + "," + _lon;

        String statusMessage = "";

        if (_logFileWriter == null) {
            statusMessage = "LOG FILE MISSING ❌";
        } else {
            _logFileWriter.println(dataLine);
            if (_logFileWriter.checkError()) {
                statusMessage = "WRITE ERROR ❌";
            }
        }

        if (!statusMessage.isEmpty()) {
            setText(_messageText, statusMessage);
        }
    }

    private void closeLogFile() {
        if (_logFileWriter != null) {
            _logFileWriter.flush();
            _logFileWriter.close();
        }
    }

    // force the MQTT client to kill the old connection and build a new one
    private void restartMqtt() {
        new Thread(() -> {
            try {
                // If a client exists, kill it to ensure we don't use old settings
                if (_mqttClient != null) {
                    try {
                        if (_mqttClient.isConnected()) {
                            _mqttClient.disconnect();
                        }
                    } catch (Exception e) { /* ignore cleanup errors */ }
                    
                    try { _mqttClient.close(); } catch (Exception e) {}
                    
                    _mqttClient = null; // Vital: This forces connectToMqtt() to re-read the URL!
                }
                
                // Now connect with the FRESH URL from the text box
                connectToMqtt();
                
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    // utility to close the on-screen keyboard
    private void hideKeyboard() {
        android.view.View view = this.getCurrentFocus();
        if (view != null) {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                onBackPressed();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    public void onBackPressed() {
        moveTaskToBack(true);
    }

    @Override
    public void onLocationChanged(Location location) {
        _speed = Math.round(location.getSpeed() * 36) / 10.0;
        _elevation = Math.round(location.getAltitude() * 10.0) / 10.0;
        
        int accuracy = (int) location.getAccuracy();
        _gpsStatus = "Fix (±" + accuracy + "m)";
        
        _lat = String.valueOf(location.getLatitude());
        _lon = String.valueOf(location.getLongitude());
    }

    enum ChargingConnection {
        NC("NC", 0),
        AC("AC", 0),
        DC("DC", 1);
        private final String _name;
        private final int _dcfc;

        ChargingConnection(String name, int dcfc) {
            _name = name;
            _dcfc = dcfc;
        }

        public String getName() {
            return _name;
        }

        public int getDcfc() {
            return _dcfc;
        }
    }
}
