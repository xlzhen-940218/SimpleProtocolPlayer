package com.kaytat.simpleprotocolplayer;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Filter;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.chip.Chip;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.common.base.Strings;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.apache.commons.validator.routines.DomainValidator;
import org.apache.commons.validator.routines.InetAddressValidator;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";

    static final String IP_PREF = "IP_PREF";
    static final String PORT_PREF = "PORT_PREF";
    static final String IP_JSON_PREF = "IP_JSON_PREF";
    static final String PORT_JSON_PREF = "PORT_JSON_PREF";
    static final String RATE_PREF = "RATE";
    static final String STEREO_PREF = "STEREO";
    static final String BUFFER_MS_PREF = "BUFFER_MS";
    static final String RETRY_PREF = "RETRY";
    static final String USE_PERFORMANCE_MODE_PREF = "USE_PERFORMANCE_MODE";
    static final String USE_MIN_BUFFER_PREF = "USE_MIN_BUFFER";
    static final String USE_MEDIA3_PREF = "USE_MEDIA3";
    static final String MODE_PREF = "STREAM_MODE";

    AutoCompleteTextView ipAddrText;
    ArrayList<String> ipAddrList;
    ArrayAdapter<String> ipAddrAdapter;

    AutoCompleteTextView audioPortText;
    ArrayList<String> audioPortList;
    ArrayAdapter<String> audioPortAdapter;

    AutoCompleteTextView sampleRateDropdown;
    AutoCompleteTextView stereoDropdown;
    TextInputEditText bufferSizeText;

    MaterialCheckBox checkRetry;
    MaterialCheckBox checkUsePerformanceMode;
    MaterialCheckBox checkUseMinBuffer;
    MaterialCheckBox checkUseMedia3;

    MaterialButtonToggleGroup toggleModeGroup;
    TextView textModeDescription;

    ImageView imageStatusDot;
    TextView textStatusTitle;
    TextView textStatusSubtitle;
    TextView textStreamTimer;

    MaterialButton playButton;
    MaterialButton stopButton;

    int sampleRate;
    boolean stereo;
    int bufferMs;
    boolean retry;
    boolean usePerformanceMode;
    boolean useMinBuffer;
    boolean useMedia3;
    int currentMode = MusicService.MODE_SPEAKER;

    private final Handler timerHandler = new Handler(Looper.getMainLooper());
    private final Runnable timerRunnable =
            new Runnable() {
                @Override
                public void run() {
                    if (MusicService.currentStatus == MusicService.StreamingStatus.STREAMING_PLAYER
                            || MusicService.currentStatus == MusicService.StreamingStatus.STREAMING_MIC) {
                        long elapsed = (System.currentTimeMillis() - MusicService.streamStartTime) / 1000;
                        long hours = elapsed / 3600;
                        long minutes = (elapsed % 3600) / 60;
                        long seconds = elapsed % 60;
                        textStreamTimer.setText(
                                String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds));
                        timerHandler.postDelayed(this, 1000);
                    }
                }
            };

    private final ActivityResultLauncher<String> requestPermissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestPermission(),
                    isGranted -> {
                        if (!isGranted) {
                            Toast.makeText(
                                            MainActivity.this,
                                            R.string.permission_mic_required,
                                            Toast.LENGTH_LONG)
                                    .show();
                        }
                    });

    private final ActivityResultLauncher<String> notificationPermissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestPermission(),
                    isGranted -> {
                        if (!isGranted) {
                            Log.i(TAG, "Notification permission denied");
                        }
                    });

    private final BroadcastReceiver statusReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (MusicService.ACTION_STATUS_CHANGED.equals(intent.getAction())) {
                        updateUiState();
                    }
                }
            };

    private enum NetworkConnection {
        NOT_CONNECTED,
        WIFI_CONNECTED,
        NON_WIFI_CONNECTED
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        MaterialToolbar toolbar = findViewById(R.id.topAppBar);
        toolbar.setOnMenuItemClickListener(this::onToolbarMenuItemClick);

        ipAddrText = findViewById(R.id.editTextIpAddr);
        audioPortText = findViewById(R.id.editTextAudioPort);
        sampleRateDropdown = findViewById(R.id.autoCompleteSampleRate);
        stereoDropdown = findViewById(R.id.autoCompleteStereo);
        bufferSizeText = findViewById(R.id.editTextBufferSize);

        checkRetry = findViewById(R.id.checkBoxRetry);
        checkUsePerformanceMode = findViewById(R.id.checkBoxUsePerformanceMode);
        checkUseMinBuffer = findViewById(R.id.checkBoxUseMinBuffer);
        checkUseMedia3 = findViewById(R.id.checkBoxUseMedia3);

        toggleModeGroup = findViewById(R.id.toggleModeGroup);
        textModeDescription = findViewById(R.id.textModeDescription);

        imageStatusDot = findViewById(R.id.imageStatusDot);
        textStatusTitle = findViewById(R.id.textStatusTitle);
        textStatusSubtitle = findViewById(R.id.textStatusSubtitle);
        textStreamTimer = findViewById(R.id.textStreamTimer);

        playButton = findViewById(R.id.playButton);
        stopButton = findViewById(R.id.stopButton);

        playButton.setOnClickListener(v -> onPlayClicked());
        stopButton.setOnClickListener(v -> onStopClicked());

        setupModeToggle();
        setupBufferPresets();
        setupDropdowns();

        // Setup AutoComplete suggestions
        ipAddrText.setOnTouchListener(
                (v, event) -> {
                    ipAddrText.showDropDown();
                    return false;
                });
        ipAddrText.setOnFocusChangeListener(
                (v, hasFocus) -> {
                    if (hasFocus && ipAddrText.getAdapter() != null) {
                        ipAddrText.showDropDown();
                    }
                });

        audioPortText.setOnTouchListener(
                (v, event) -> {
                    audioPortText.showDropDown();
                    return false;
                });
        audioPortText.setOnFocusChangeListener(
                (v, hasFocus) -> {
                    if (hasFocus && audioPortText.getAdapter() != null) {
                        audioPortText.showDropDown();
                    }
                });

        initBackgroundMusicService();
        requestNotificationPermissionIfNecessary();
    }

    private void requestNotificationPermissionIfNecessary() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            }
        }
    }

    private void setupModeToggle() {
        toggleModeGroup.addOnButtonCheckedListener(
                (group, checkedId, isChecked) -> {
                    if (isChecked) {
                        if (checkedId == R.id.btnModeMic) {
                            currentMode = MusicService.MODE_MIC;
                            textModeDescription.setText(R.string.mode_mic_desc);
                            checkMicPermission();
                        } else {
                            currentMode = MusicService.MODE_SPEAKER;
                            textModeDescription.setText(R.string.mode_speaker_desc);
                        }
                        savePrefs();
                    }
                });
    }

    private void checkMicPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
        }
    }

    private void setupBufferPresets() {
        Chip chip20 = findViewById(R.id.chipBuffer20);
        Chip chip50 = findViewById(R.id.chipBuffer50);
        Chip chip100 = findViewById(R.id.chipBuffer100);

        chip20.setOnClickListener(v -> bufferSizeText.setText("20"));
        chip50.setOnClickListener(v -> bufferSizeText.setText("50"));
        chip100.setOnClickListener(v -> bufferSizeText.setText("100"));
    }

    private void setupDropdowns() {
        String[] rates = getResources().getStringArray(R.array.sampleRates);
        ArrayAdapter<String> rateAdapter =
                new ArrayAdapter<>(this, android.R.layout.simple_dropdown_item_1line, rates);
        sampleRateDropdown.setAdapter(rateAdapter);

        String[] stereoOptions = getResources().getStringArray(R.array.stereo);
        ArrayAdapter<String> stereoAdapter =
                new ArrayAdapter<>(this, android.R.layout.simple_dropdown_item_1line, stereoOptions);
        stereoDropdown.setAdapter(stereoAdapter);
    }

    private boolean onToolbarMenuItemClick(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.linux_guide_item) {
            showLinuxGuideDialog();
            return true;
        } else if (id == R.id.notice_item) {
            startActivity(new Intent(this, NoticeActivity.class));
            return true;
        }
        return false;
    }

    private void showLinuxGuideDialog() {
        String message =
                "1. Speaker Mode (Android plays PC sound):\n"
                        + "On your Linux PC, run:\n\n"
                        + "pactl load-module module-simple-protocol-tcp rate=44100 format=s16le channels=2 source=@DEFAULT_SINK@.monitor record=true port=12345\n\n"
                        + "2. Microphone Mode (Android mic sent to PC):\n"
                        + "On your Linux PC, run:\n\n"
                        + "pactl load-module module-simple-protocol-tcp rate=44100 format=s16le channels=1 sink=virtual_mic playback=true port=12345\n\n"
                        + "(Or PipeWire simple protocol source module)";

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.linux_guide_item)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override
    public void onResume() {
        super.onResume();

        IntentFilter filter = new IntentFilter(MusicService.ACTION_STATUS_CHANGED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(statusReceiver, filter);
        }

        loadPrefs();
        updateUiState();
    }

    @Override
    public void onPause() {
        super.onPause();
        try {
            unregisterReceiver(statusReceiver);
        } catch (Exception ignored) {
        }
        timerHandler.removeCallbacks(timerRunnable);
    }

    @Override
    public void onStop() {
        super.onStop();
        if (controllerFuture != null) {
            MediaController.releaseFuture(controllerFuture);
        }
    }

    private void updateUiState() {
        MusicService.StreamingStatus status = MusicService.currentStatus;

        switch (status) {
            case STREAMING_PLAYER:
                imageStatusDot.setColorFilter(ContextCompat.getColor(this, R.color.status_streaming));
                textStatusTitle.setText(R.string.status_streaming_speaker);
                textStatusSubtitle.setText(
                        String.format(Locale.getDefault(), "Connected to %s:%d", MusicService.currentHost, MusicService.currentPort));
                textStreamTimer.setVisibility(View.VISIBLE);
                timerHandler.removeCallbacks(timerRunnable);
                timerHandler.post(timerRunnable);
                setStreamingUiLock(true);
                break;

            case STREAMING_MIC:
                imageStatusDot.setColorFilter(ContextCompat.getColor(this, R.color.status_streaming));
                textStatusTitle.setText(R.string.status_streaming_mic);
                textStatusSubtitle.setText(
                        String.format(Locale.getDefault(), "Streaming mic to %s:%d", MusicService.currentHost, MusicService.currentPort));
                textStreamTimer.setVisibility(View.VISIBLE);
                timerHandler.removeCallbacks(timerRunnable);
                timerHandler.post(timerRunnable);
                setStreamingUiLock(true);
                break;

            case CONNECTING:
                imageStatusDot.setColorFilter(ContextCompat.getColor(this, R.color.status_connecting));
                textStatusTitle.setText(R.string.status_connecting);
                textStatusSubtitle.setText("Establishing socket connection…");
                textStreamTimer.setVisibility(View.GONE);
                timerHandler.removeCallbacks(timerRunnable);
                setStreamingUiLock(true);
                break;

            case ERROR:
                imageStatusDot.setColorFilter(ContextCompat.getColor(this, R.color.status_error));
                textStatusTitle.setText("Connection failed");
                textStatusSubtitle.setText("Check network, Linux server IP and port.");
                textStreamTimer.setVisibility(View.GONE);
                timerHandler.removeCallbacks(timerRunnable);
                setStreamingUiLock(false);
                break;

            case IDLE:
            default:
                imageStatusDot.setColorFilter(ContextCompat.getColor(this, R.color.status_idle));
                textStatusTitle.setText(R.string.status_ready);
                textStatusSubtitle.setText("Not connected");
                textStreamTimer.setVisibility(View.GONE);
                timerHandler.removeCallbacks(timerRunnable);
                setStreamingUiLock(false);
                break;
        }
    }

    private void setStreamingUiLock(boolean isStreaming) {
        // Prevent duplicate button clicks while streaming
        playButton.setEnabled(!isStreaming);
        stopButton.setEnabled(isStreaming);

        ipAddrText.setEnabled(!isStreaming);
        audioPortText.setEnabled(!isStreaming);
        sampleRateDropdown.setEnabled(!isStreaming);
        stereoDropdown.setEnabled(!isStreaming);
        bufferSizeText.setEnabled(!isStreaming);
        toggleModeGroup.setEnabled(!isStreaming);
        checkRetry.setEnabled(!isStreaming);
        checkUsePerformanceMode.setEnabled(!isStreaming);
        checkUseMinBuffer.setEnabled(!isStreaming);
        checkUseMedia3.setEnabled(!isStreaming);
    }

    private void onPlayClicked() {
        // Immediately disable play button to prevent spam clicks
        playButton.setEnabled(false);

        if (currentMode == MusicService.MODE_MIC) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
                playButton.setEnabled(true);
                return;
            }
        }

        switch (getNetworkConnection()) {
            case NOT_CONNECTED:
                Toast.makeText(getApplicationContext(), "No network connectivity.", Toast.LENGTH_SHORT).show();
                playButton.setEnabled(true);
                return;
            case NON_WIFI_CONNECTED:
                Toast.makeText(getApplicationContext(), "WARNING: wifi not connected.", Toast.LENGTH_SHORT).show();
                break;
            default:
                break;
        }

        hideKb();

        String ipAddr = ipAddrText.getText().toString().trim();
        String portStr = audioPortText.getText().toString().trim();

        DomainValidator domainValidator = DomainValidator.getInstance();
        InetAddressValidator inetAddressValidator = InetAddressValidator.getInstance();
        if (!domainValidator.isValid(ipAddr)
                && !inetAddressValidator.isValidInet4Address(ipAddr)
                && !inetAddressValidator.isValidInet6Address(ipAddr)) {
            Toast.makeText(getApplicationContext(), "Invalid address", Toast.LENGTH_SHORT).show();
            playButton.setEnabled(true);
            return;
        }

        int audioPort;
        try {
            audioPort = Integer.parseInt(portStr);
        } catch (NumberFormatException nfe) {
            Toast.makeText(getApplicationContext(), "Invalid port", Toast.LENGTH_SHORT).show();
            playButton.setEnabled(true);
            return;
        }

        // Extract sample rate
        String rateStr = sampleRateDropdown.getText().toString();
        String[] rateSplit = rateStr.split(" ");
        try {
            sampleRate = Integer.parseInt(rateSplit[0]);
        } catch (Exception nfe) {
            sampleRate = MusicService.DEFAULT_SAMPLE_RATE;
        }

        // Extract stereo/mono setting
        String stereoStr = stereoDropdown.getText().toString();
        String stereoKey = getString(R.string.stereoKey);
        stereo = stereoStr.contains(stereoKey);

        // Buffer ms
        try {
            bufferMs = Integer.parseInt(bufferSizeText.getText().toString().trim());
        } catch (Exception e) {
            bufferMs = MusicService.DEFAULT_BUFFER_MS;
        }

        retry = checkRetry.isChecked();
        usePerformanceMode = checkUsePerformanceMode.isChecked();
        useMinBuffer = checkUseMinBuffer.isChecked();
        useMedia3 = checkUseMedia3.isChecked();

        savePrefs();

        Bundle bundle = new Bundle();
        bundle.putInt(MusicService.DATA_MODE, currentMode);
        bundle.putString(MusicService.DATA_IP_ADDRESS, ipAddr);
        bundle.putInt(MusicService.DATA_AUDIO_PORT, audioPort);
        bundle.putInt(MusicService.DATA_SAMPLE_RATE, sampleRate);
        bundle.putBoolean(MusicService.DATA_STEREO, stereo);
        bundle.putInt(MusicService.DATA_BUFFER_MS, bufferMs);
        bundle.putBoolean(MusicService.DATA_RETRY, retry);
        bundle.putBoolean(MusicService.DATA_USE_PERFORMANCE_MODE, usePerformanceMode);
        bundle.putBoolean(MusicService.DATA_USE_MIN_BUFFER, useMinBuffer);

        startStreamingService(useMedia3, bundle);
    }

    private void onStopClicked() {
        stopButton.setEnabled(false);
        hideKb();
        stopStreamingService(useMedia3);
    }

    private void startStreamingService(boolean useMedia3Active, Bundle bundle) {
        if (useMedia3Active && currentMode == MusicService.MODE_SPEAKER) {
            try {
                MediaItem mediaItem =
                        new MediaItem.Builder()
                                .setUri(
                                        new Uri.Builder()
                                                .encodedAuthority(
                                                        bundle.getString(MusicService.DATA_IP_ADDRESS)
                                                                + ":"
                                                                + bundle.getInt(MusicService.DATA_AUDIO_PORT))
                                                .build())
                                .setMediaMetadata(
                                        new MediaMetadata.Builder()
                                                .setTitle("Streaming from " + bundle.getString(MusicService.DATA_IP_ADDRESS))
                                                .setArtist("Simple Protocol Player")
                                                .setExtras(bundle)
                                                .build())
                                .build();
                if (controllerFuture != null) {
                    controllerFuture.get().setMediaItem(mediaItem);
                    controllerFuture.get().prepare();
                    controllerFuture.get().play();
                }
            } catch (Exception e) {
                Log.e(TAG, "startMusicService:media3 exception", e);
            }
        } else {
            Intent intent = new Intent(this, MusicService.class);
            intent.setAction(MusicService.ACTION_PLAY);
            intent.putExtras(bundle);
            ContextCompat.startForegroundService(this, intent);
        }
    }

    private void stopStreamingService(boolean useMedia3Active) {
        if (useMedia3Active && currentMode == MusicService.MODE_SPEAKER) {
            try {
                if (controllerFuture != null) {
                    controllerFuture.get().stop();
                }
            } catch (Exception e) {
                Log.e(TAG, "stopMusicService:media3 exception", e);
            }
        } else {
            Intent intent = new Intent(this, MusicService.class);
            intent.setAction(MusicService.ACTION_STOP);
            ContextCompat.startForegroundService(this, intent);
        }
    }

    private void loadPrefs() {
        SharedPreferences myPrefs = getSharedPreferences("myPrefs", MODE_PRIVATE);

        ipAddrList = getListFromPrefs(myPrefs, IP_JSON_PREF, IP_PREF);
        ipAddrAdapter =
                new NoFilterArrayAdapter<>(this, android.R.layout.simple_list_item_1, ipAddrList);
        ipAddrText.setAdapter(ipAddrAdapter);
        ipAddrText.setThreshold(1);
        if (!ipAddrList.isEmpty()) {
            ipAddrText.setText(ipAddrList.get(0));
        }

        if (!isEmpty(ipAddrText)) {
            getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        }

        audioPortList = getListFromPrefs(myPrefs, PORT_JSON_PREF, PORT_PREF);
        audioPortAdapter =
                new NoFilterArrayAdapter<>(this, android.R.layout.simple_list_item_1, audioPortList);
        audioPortText.setAdapter(audioPortAdapter);
        audioPortText.setThreshold(1);
        if (!audioPortList.isEmpty()) {
            audioPortText.setText(audioPortList.get(0));
        } else {
            audioPortText.setText(String.valueOf(MusicService.DEFAULT_AUDIO_PORT));
        }

        Resources res = getResources();
        sampleRate = myPrefs.getInt(RATE_PREF, MusicService.DEFAULT_SAMPLE_RATE);
        String rateString = Integer.toString(sampleRate);
        String[] sampleRateStrings = res.getStringArray(R.array.sampleRates);
        boolean sampleRateFound = false;
        for (String s : sampleRateStrings) {
            if (s.contains(rateString)) {
                sampleRateDropdown.setText(s, false);
                sampleRateFound = true;
                break;
            }
        }
        if (!sampleRateFound && sampleRateStrings.length > 0) {
            sampleRateDropdown.setText(sampleRateStrings[4], false);
        }

        stereo = myPrefs.getBoolean(STEREO_PREF, MusicService.DEFAULT_STEREO);
        String[] stereoStrings = res.getStringArray(R.array.stereo);
        String stereoKey = getString(R.string.stereoKey);
        if (stereoStrings[0].contains(stereoKey) == stereo) {
            stereoDropdown.setText(stereoStrings[0], false);
        } else {
            stereoDropdown.setText(stereoStrings[1], false);
        }

        bufferMs = myPrefs.getInt(BUFFER_MS_PREF, MusicService.DEFAULT_BUFFER_MS);
        bufferSizeText.setText(String.format(Locale.getDefault(), "%d", bufferMs));

        retry = myPrefs.getBoolean(RETRY_PREF, MusicService.DEFAULT_RETRY);
        checkRetry.setChecked(retry);

        usePerformanceMode =
                myPrefs.getBoolean(USE_PERFORMANCE_MODE_PREF, MusicService.DEFAULT_USE_PERFORMANCE_MODE);
        checkUsePerformanceMode.setChecked(usePerformanceMode);

        useMinBuffer = myPrefs.getBoolean(USE_MIN_BUFFER_PREF, MusicService.DEFAULT_USE_MIN_BUFFER);
        checkUseMinBuffer.setChecked(useMinBuffer);

        useMedia3 = myPrefs.getBoolean(USE_MEDIA3_PREF, false);
        checkUseMedia3.setChecked(useMedia3);

        currentMode = myPrefs.getInt(MODE_PREF, MusicService.MODE_SPEAKER);
        if (currentMode == MusicService.MODE_MIC) {
            toggleModeGroup.check(R.id.btnModeMic);
            textModeDescription.setText(R.string.mode_mic_desc);
        } else {
            toggleModeGroup.check(R.id.btnModeSpeaker);
            textModeDescription.setText(R.string.mode_speaker_desc);
        }
    }

    private void savePrefs() {
        SharedPreferences myPrefs = getSharedPreferences("myPrefs", MODE_PRIVATE);
        SharedPreferences.Editor prefsEditor = myPrefs.edit();

        ipAddrList = getUpdatedArrayList(myPrefs, ipAddrText, IP_JSON_PREF, IP_PREF);
        audioPortList = getUpdatedArrayList(myPrefs, audioPortText, PORT_JSON_PREF, PORT_PREF);

        prefsEditor.putString(IP_JSON_PREF, getJson(ipAddrList).toString());
        prefsEditor.putString(PORT_JSON_PREF, getJson(audioPortList).toString());

        prefsEditor.putBoolean(STEREO_PREF, stereo);
        prefsEditor.putInt(RATE_PREF, sampleRate);
        prefsEditor.putInt(BUFFER_MS_PREF, bufferMs);
        prefsEditor.putBoolean(RETRY_PREF, retry);
        prefsEditor.putBoolean(USE_PERFORMANCE_MODE_PREF, usePerformanceMode);
        prefsEditor.putBoolean(USE_MIN_BUFFER_PREF, useMinBuffer);
        prefsEditor.putBoolean(USE_MEDIA3_PREF, useMedia3);
        prefsEditor.putInt(MODE_PREF, currentMode);
        prefsEditor.apply();

        if (ipAddrAdapter != null) {
            ipAddrAdapter.clear();
            ipAddrAdapter.addAll(ipAddrList);
            ipAddrAdapter.notifyDataSetChanged();
        }
        if (audioPortAdapter != null) {
            audioPortAdapter.clear();
            audioPortAdapter.addAll(audioPortList);
            audioPortAdapter.notifyDataSetChanged();
        }
    }

    private ArrayList<String> getListFromPrefs(SharedPreferences prefs, String keyJson, String keySingle) {
        String jsonString = prefs.getString(keyJson, null);
        ArrayList<String> arrayList = new ArrayList<>();
        if (Strings.isNullOrEmpty(jsonString)) {
            String single = prefs.getString(keySingle, null);
            if (!Strings.isNullOrEmpty(single)) {
                arrayList.add(single);
            }
        } else {
            try {
                JSONObject jsonObject = new JSONObject(jsonString);
                JSONArray jsonArray = jsonObject.getJSONArray("list");
                for (int i = 0; i < jsonArray.length(); i++) {
                    String s = (String) jsonArray.get(i);
                    if (!Strings.isNullOrEmpty(s)) {
                        arrayList.add(s);
                    }
                }
            } catch (JSONException e) {
                Log.i(TAG, e.toString());
            }
        }
        return arrayList;
    }

    private ArrayList<String> getUpdatedArrayList(
            SharedPreferences prefs, AutoCompleteTextView view, String keyJson, String keySingle) {
        ArrayList<String> arrayList = getListFromPrefs(prefs, keyJson, keySingle);
        String currentText = view.getText().toString().trim();
        if (!currentText.isEmpty()) {
            arrayList.remove(currentText);
            arrayList.add(0, currentText);
            if (arrayList.size() >= 5) {
                arrayList.subList(5, arrayList.size()).clear();
            }
        }
        return arrayList;
    }

    private JSONObject getJson(ArrayList<String> arrayList) {
        JSONArray jsonArray = new JSONArray(arrayList);
        JSONObject jsonObject = new JSONObject();
        try {
            jsonObject.put("list", jsonArray);
        } catch (JSONException jsonException) {
            Log.i(TAG, jsonException.toString());
        }
        return jsonObject;
    }

    private void hideKb() {
        InputMethodManager inputManager =
                (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        View v = getCurrentFocus();
        if (v != null && inputManager != null) {
            inputManager.hideSoftInputFromWindow(v.getWindowToken(), InputMethodManager.HIDE_NOT_ALWAYS);
        }
    }

    private boolean isEmpty(AutoCompleteTextView etText) {
        return Strings.isNullOrEmpty(etText.getText().toString().trim());
    }

    private NetworkConnection getNetworkConnection() {
        ConnectivityManager connectivityManager =
                (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivityManager == null) {
            return NetworkConnection.NOT_CONNECTED;
        }
        NetworkCapabilities capabilities =
                connectivityManager.getNetworkCapabilities(connectivityManager.getActiveNetwork());
        if (capabilities == null) {
            return NetworkConnection.NOT_CONNECTED;
        }
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            return NetworkConnection.NOT_CONNECTED;
        }
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return NetworkConnection.WIFI_CONNECTED;
        } else {
            return NetworkConnection.NON_WIFI_CONNECTED;
        }
    }

    ListenableFuture<MediaController> controllerFuture;

    private void initBackgroundMusicService() {
        try {
            SessionToken sessionToken =
                    new SessionToken(this, new ComponentName(this, BackgroundMusicService.class));
            controllerFuture = new MediaController.Builder(this, sessionToken).buildAsync();
            controllerFuture.addListener(() -> {
            }, MoreExecutors.directExecutor());
        } catch (Exception e) {
            Log.e(TAG, "initBackgroundMusicService error: " + e);
        }
    }

    private static class NoFilterArrayAdapter<T> extends ArrayAdapter<T> {
        private final Filter filter = new NoFilter();
        public final List<T> items;

        @Override
        @NonNull
        public Filter getFilter() {
            return filter;
        }

        public NoFilterArrayAdapter(Context context, int textViewResourceId, List<T> objects) {
            super(context, textViewResourceId, objects);
            items = objects;
        }

        private class NoFilter extends Filter {
            @Override
            protected Filter.FilterResults performFiltering(CharSequence arg0) {
                Filter.FilterResults result = new Filter.FilterResults();
                result.values = items;
                result.count = items.size();
                return result;
            }

            @Override
            protected void publishResults(CharSequence arg0, Filter.FilterResults arg1) {
                notifyDataSetChanged();
            }
        }
    }
}
