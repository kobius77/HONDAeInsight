package de.danielh.hondae_insight;

import android.app.Application;
import android.text.TextUtils;
import android.widget.Toast;

import androidx.annotation.StringRes;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.harrysoft.androidbluetoothserial.BluetoothManager;
import com.harrysoft.androidbluetoothserial.SimpleBluetoothDeviceInterface;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.schedulers.Schedulers;

public class CommunicateViewModel extends AndroidViewModel {

    private final CompositeDisposable _compositeDisposable = new CompositeDisposable();
    private BluetoothManager _bluetoothManager;

    @Nullable
    private SimpleBluetoothDeviceInterface _deviceInterface;

    private final MutableLiveData<ConnectionStatus> _connectionStatusData = new MutableLiveData<>();
    private final MutableLiveData<String> _deviceNameData = new MutableLiveData<>();

    private String _mac;
    private boolean _connectionAttemptedOrMade = false;
    private boolean _viewModelSetup = false;
    private boolean _newMessage = false;

    private final Object _newMessageParsed = new Object();
    private String _message = "";
    private String _messageID = "";
    private boolean _retry = true;

    public CommunicateViewModel(@NotNull Application application) {
        super(application);
    }

    public boolean setupViewModel(String deviceName, String mac) {
        if (!_viewModelSetup) {
            _viewModelSetup = true;
            _bluetoothManager = BluetoothManager.getInstance();
            if (_bluetoothManager == null) {
                toast("Bluetooth unavailable");
                return false;
            }

            _mac = mac;
            _deviceNameData.postValue(deviceName);
            _connectionStatusData.postValue(ConnectionStatus.DISCONNECTED);
        }
        return true;
    }

    public void connect() {
        if (_mac == null || _bluetoothManager == null) {
            toast("Error: Missing Device MAC");
            return;
        }

        if (!_connectionAttemptedOrMade) {
            _connectionStatusData.postValue(ConnectionStatus.CONNECTING);
            _connectionAttemptedOrMade = true;

            // Connect asynchronously
            _compositeDisposable.add(_bluetoothManager.openSerialDevice(_mac)
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    // Removed the invalid onErrorResumeNext block
                    .subscribe(
                            device -> onConnected(device.toSimpleDeviceInterface()),
                            t -> {
                                // SHOW THE REAL ERROR TOAST
                                // This is critical for debugging why it fails
                                String errorMsg = t.getMessage() != null ? t.getMessage() : "Unknown Error";
                                toast("Connect Error: " + errorMsg);

                                _connectionAttemptedOrMade = false;
                                _connectionStatusData.postValue(ConnectionStatus.RETRY);
                            }
                    ));
        }
    }

    public void disconnect() {
        if (_connectionAttemptedOrMade && _deviceInterface != null) {
            _connectionAttemptedOrMade = false;
            _bluetoothManager.closeDevice(_deviceInterface);
            _deviceInterface = null;
        }
        _connectionStatusData.postValue(ConnectionStatus.DISCONNECTED);
    }

    private void onConnected(SimpleBluetoothDeviceInterface deviceInterface) {
        this._deviceInterface = deviceInterface;
        if (this._deviceInterface != null) {
            _connectionStatusData.postValue(ConnectionStatus.CONNECTED);
            this._deviceInterface.setListeners(this::onMessageReceived, this::onMessageSent, t -> toast("Send Error: " + t.getMessage()));
            toast(R.string.connected);
        } else {
            toast(R.string.connection_failed);
            _connectionStatusData.postValue(ConnectionStatus.DISCONNECTED);
        }
    }

    private void onMessageSent(String message) { }

    private void onMessageReceived(String message) {
        if (!TextUtils.isEmpty(message)) {
            synchronized (_newMessageParsed) {
                if (message.startsWith(">")) {
                    _message = trySubstring(message, 11);
                    _messageID = trySubstring(message, 11, 19);
                } else {
                    _message += trySubstring(message, 10);
                }
                if (message.contains("0000555555") || message.contains("OK") || message.contains("ELM327") || message.matches(">\\d+\\.\\dV")) {
                    _newMessage = true;
                    _newMessageParsed.notify();
                }
            }
        }
    }

    private String trySubstring(String message, int beginIndex) {
        try {
            return message.substring(beginIndex);
        } catch (IndexOutOfBoundsException e) {
            return message.substring(1);
        }
    }

    private String trySubstring(String message, int beginIndex, int endIndex) {
        try {
            return message.substring(beginIndex, endIndex);
        } catch (IndexOutOfBoundsException e) {
            return message;
        }
    }

    public void sendMessage(String message) {
        if (_deviceInterface != null && !TextUtils.isEmpty(message)) {
            _deviceInterface.sendMessage(message);
        }
    }

    @Override
    protected void onCleared() {
        _compositeDisposable.dispose();
        // Do NOT close _bluetoothManager here.
    }

    // Updated toast to handle Strings directly
    private void toast(String message) {
        Toast.makeText(getApplication(), message, Toast.LENGTH_LONG).show();
    }

    private void toast(@StringRes int messageResource) {
        Toast.makeText(getApplication(), messageResource, Toast.LENGTH_LONG).show();
        if (messageResource == R.string.message_send_error) {
            disconnect();
        }
    }

    public LiveData<ConnectionStatus> getConnectionStatus() { return _connectionStatusData; }
    public LiveData<String> getDeviceName() { return _deviceNameData; }
    public String getMessage() { return _message; }
    public String getMessageID() { return _messageID; }
    public boolean isNewMessage() { return _newMessage; }
    public Object getNewMessageParsed() { return _newMessageParsed; }
    public void setNewMessageProcessed() { _newMessage = false; }
    public boolean isRetry() { return _retry; }
    public void setRetry(boolean _retry) { this._retry = _retry; }

    enum ConnectionStatus {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        RETRY
    }
}
