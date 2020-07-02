package com.nexenio.bleindoorpositioningdemo.recording;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.snackbar.BaseTransientBottomBar;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.ResultReceiver;
import android.text.Editable;
import android.text.InputFilter;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import com.nexenio.bleindoorpositioningdemo.ExternalStorageUtils;
import com.nexenio.bleindoorpositioningdemo.R;
import com.nexenio.bleindoorpositioningdemo.bluetooth.BluetoothClient;
import com.nexenio.bleindoorpositioningdemo.location.AndroidLocationProvider;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class OverallRecordingActivity extends AppCompatActivity {

    // TODO: block start if fields are missing (app is crashing)
    // TODO: find out why app is crashing for that

    private static final String TAG = OverallRecordingActivity.class.getSimpleName();
    // This must be configured for the file provider; adjust file_paths.xml
    public static final String RECORDING_DIRECTORY_NAME = "Indoor Positioning Recording";
    public static final String ACTION_RECORDING_START = "Start_Recording";
    public static final String ACTION_RECORDING_STOP = "Stop_Recording";
    public static final int REQUEST_CODE_STORAGE_PERMISSIONS = 0; // changed from 1 to 0 on 25.05

    public static final int RESULT_CODE_ERROR = 0;
    public static final int RECORDING_FINISHED = 1;
    public static final int RECORDING_STARTED = 2;

    // TODO: 24.05.20 change recording uuid to mp-project-specific-value
    public final static UUID RECORDING_UUID = UUID.fromString("61a0523a-a733-4789-ae8f-4f55fcff64f2");

    private TextInputEditText recordingUuidCopyEditText;
    private TextInputEditText durationEditText;
    private TextInputEditText offsetEditText;
    private TextInputEditText recordingCommentEditText;
    private MaterialButton recordButton;

    private boolean isRecording;

    @Nullable
    protected Snackbar errorSnackBar;
    protected CoordinatorLayout coordinatorLayout;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_overall_recording);
        initializeLayout();

        // setup location
        AndroidLocationProvider.initialize(this);

        // setup bluetooth
        initializeBluetoothScanning();

        // setup storage
        if (!hasStoragePermission()) {
            requestStoragePermission();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        switch (requestCode) {
            case REQUEST_CODE_STORAGE_PERMISSIONS: {
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Log.d(TAG, "Storage permission granted");
                } else {
                    showStoragePermissionMissingError();
                }
                break;
            }
            case AndroidLocationProvider.REQUEST_CODE_LOCATION_PERMISSIONS: {
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Log.d(TAG, "Location permission granted");
                    AndroidLocationProvider.startRequestingLocationUpdates();
                } else {
                    requestLocationServices();
                    Log.d(TAG, "Location permission not granted. Wut?");
                }
            }
        }
    }

    private void initializeLayout() {
        coordinatorLayout = getWindow().getDecorView().findViewById(R.id.coordinatorLayout);

        recordButton = findViewById(R.id.recordButton);

        recordingUuidCopyEditText = findViewById(R.id.recordingUuidCopyEditText);
        recordingCommentEditText = findViewById(R.id.recordingCommentEditText);
        durationEditText = findViewById(R.id.recordingDurationEditText);
        offsetEditText = findViewById(R.id.offsetEditText);

        setupUuidEditText();

        // Limit numbers to avoid overflow
        InputFilter[] FilterArray = new InputFilter[1];
        FilterArray[0] = new InputFilter.LengthFilter(6);
    }

    private void initializeBluetoothScanning() {
        Log.d(TAG, "Initializing Bluetooth scanning");
        BluetoothClient.initialize(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
    }

    private boolean hasAllPermissionsAndSettingsEnabled() {
        boolean everythingAvailable = true;
        // observe location
        if (!AndroidLocationProvider.hasLocationPermission(this)) {
            AndroidLocationProvider.requestLocationPermission(this);
            everythingAvailable = false;
        } else if (!AndroidLocationProvider.isLocationEnabled(this)) {
            requestLocationServices();
            everythingAvailable = false;
        }
        // observe bluetooth
        if (!BluetoothClient.isBluetoothEnabled()) {
            requestBluetooth();
            everythingAvailable = false;
        }
        // setup storage
        if (!hasStoragePermission()) {
            requestStoragePermission();
            everythingAvailable = false;
        }
        return everythingAvailable;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == BluetoothClient.REQUEST_CODE_ENABLE_BLUETOOTH) {
            if (resultCode == RESULT_OK) {
                Log.d(TAG, "Bluetooth enabled, starting to scan");
                BluetoothClient.startScanning();
            } else {
                Log.d(TAG, "Bluetooth not enabled, invoking new request");
                BluetoothClient.requestBluetoothEnabling(this);
            }
        }
    }

    public void onRecordButtonClicked(View view) {
        if (isRecording) {
            stopRecording();
        } else {
            startRecording();
        }
    }

    private void startRecording() {
        if (!hasAllPermissionsAndSettingsEnabled()) {
            return;
        }

        if (showInvalidFields()) {
            Toast.makeText(this, "Not all information were provided", Toast.LENGTH_LONG).show();
            return;
        }

        Intent intent = new Intent(this, RecordingService.class);
        String comment = recordingCommentEditText.getText() == null ? "test" : recordingCommentEditText.getText().toString();
        long duration = getLongFromEditText(durationEditText);
        long offset = getLongFromEditText(offsetEditText);

        intent.putExtra("comment", comment);
        intent.putExtra("duration", duration);
        intent.putExtra("offset", offset);
        intent.putExtra("receiverTag", new RecorderResultReceiver(new Handler()));

        intent.setAction(ACTION_RECORDING_START);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        isRecording = true;
        recordButton.setText(getString(R.string.action_stop_recording));
    }

    private long getLongFromEditText(TextInputEditText editText) {
        Editable text = editText.getText();
        if (text == null) {
            return 0;
        } else {
            return Long.parseLong(text.toString());
        }
    }

    private void stopRecording() {
        stopRecording(false);
    }

    private void stopRecording(boolean serviceStopped) {
        if (!isRecording) {
            return;
        }
        Log.d(TAG, "Stopping recording");

        if (!serviceStopped) {
            Intent intent = new Intent(this, RecordingService.class);
            intent.setAction(ACTION_RECORDING_STOP);
            startService(intent);
        }

        isRecording = false;
        recordButton.setText(getString(R.string.action_start_recording));
    }

    /**
     * Convenience method for getting the recording uuid which the beacons need to send.
     */
    private void setupUuidEditText() {
        recordingUuidCopyEditText.setText(RECORDING_UUID.toString());
        recordingUuidCopyEditText.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    ClipData clip = ClipData.newPlainText("Recording Uuid", ((TextInputEditText) v).getText());
                    clipboard.setPrimaryClip(clip);
                    Toast.makeText(OverallRecordingActivity.this, "Copied to Clipboard", Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    /*
     * Storage Permission
     */
    private boolean hasStoragePermission() {
        int permissionResult = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE);
        return permissionResult == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressLint("CheckResult")
    private void requestStoragePermission() {
        Log.d(TAG, "Requesting storage permission");
        ActivityCompat.requestPermissions(this, new String[]{
                Manifest.permission.WRITE_EXTERNAL_STORAGE
        }, REQUEST_CODE_STORAGE_PERMISSIONS);
    }

    private void showStoragePermissionMissingError() {
        // find a container for the snackbar
        if (errorSnackBar != null) {
            errorSnackBar.dismiss();
            errorSnackBar = null;
        }

        View container = coordinatorLayout;
        if (container == null) {
            container = getWindow().getDecorView();
        }
        // create the snackbar
        errorSnackBar = Snackbar.make(container, "Permission not granted", Snackbar.LENGTH_LONG);

        errorSnackBar.setDuration(BaseTransientBottomBar.LENGTH_INDEFINITE)
                .setAction(getString(R.string.record_retry), new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        requestStoragePermission();
                    }
                }).show();
    }

    /*
     * Storage & Export
     */

    public void onExportAllRecordingsButtonClicked(View view) {
        exportAllRecordings();
    }

    private void exportAllRecordings() {
        File documentsDirectory = ExternalStorageUtils.getDocumentsDirectory(RECORDING_DIRECTORY_NAME);
        List<File> jsonFiles = ExternalStorageUtils.getJsonFilesInDirectory(documentsDirectory);

        String fileName = "measurements.zip";
        File zipFile = new File(documentsDirectory, fileName);

        if (zipFile.exists()) {
            zipFile.delete();
        }

        try {
            ExternalStorageUtils.zip(jsonFiles, zipFile.getAbsolutePath());
        } catch (IOException e) {
            Toast.makeText(this, "Unable to export files", Toast.LENGTH_LONG).show();
            Log.e(TAG, "Unable to export measurements", e);
            return;
        }
        ExternalStorageUtils.shareFile(zipFile, this);
    }

    /**
     * Indicate text edit fields with missing text.
     *
     * @return False if no invalid field was found, else true
     */
    private boolean showInvalidFields() {
        List<TextInputEditText> textInputEditTexts = getNecessaryTextInputEditTexts();
        boolean invalidTextField = false;
        for (TextInputEditText textInputEditText : textInputEditTexts) {
            if (textInputEditText.getText() != null && textInputEditText.getText().toString().equals("")) {
                String errorString = "This field cannot be empty";
                textInputEditText.setError(errorString);
                invalidTextField = true;
            }
        }
        return invalidTextField;
    }

    private List<TextInputEditText> getNecessaryTextInputEditTexts() {
        return Arrays.asList(
                durationEditText,
                offsetEditText,
                recordingCommentEditText
        );
    }

    /*
     * Permissions
     */

    private void requestLocationServices() {
        Snackbar snackbar = Snackbar.make(
                coordinatorLayout,
                R.string.error_location_disabled,
                Snackbar.LENGTH_INDEFINITE
        );
        snackbar.setAction(R.string.action_enable, new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                AndroidLocationProvider.requestLocationEnabling(OverallRecordingActivity.this);
            }
        });
        snackbar.show();
    }

    private void requestBluetooth() {
        Snackbar snackbar = Snackbar.make(
                coordinatorLayout,
                R.string.error_bluetooth_disabled,
                Snackbar.LENGTH_INDEFINITE
        );
        snackbar.setAction(R.string.action_enable, new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                BluetoothClient.requestBluetoothEnabling(OverallRecordingActivity.this);
            }
        });
        snackbar.show();
    }

    private class RecorderResultReceiver extends ResultReceiver {

        /**
         * Create a new ResultReceive to receive results.  Your {@link #onReceiveResult} method will
         * be called from the thread running
         * <var>handler</var> if given, or from an arbitrary thread if null.
         */
        RecorderResultReceiver(Handler handler) {
            super(handler);
        }

        @Override
        protected void onReceiveResult(int resultCode, @Nullable Bundle resultData) {
            switch (resultCode) {
                case RECORDING_FINISHED:
                    stopRecording(true);
                    // try exporting new file
                    /*
                    if (resultData != null) {
                        String fileName = resultData.getString("fileName");
                        File documentsDirectory = ExternalStorageUtils.getDocumentsDirectory(RECORDING_DIRECTORY_NAME);
                        File file = new File(documentsDirectory, fileName);
                        ExternalStorageUtils.shareFile(file, OverallRecordingActivity.this);
                    }
                    */
                    break;
                case RECORDING_STARTED:
                    // TODO: show toast about recording started
                    // TODO: needs to be send by service
                    break;
                case RESULT_CODE_ERROR:
                    // display error message
                    break;
            }

            super.onReceiveResult(resultCode, resultData);
        }

    }

}