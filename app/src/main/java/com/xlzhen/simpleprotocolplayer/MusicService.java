package com.xlzhen.simpleprotocolplayer;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import android.widget.Toast;
import androidx.core.app.NotificationCompat;
import java.util.ArrayList;

/**
 * Foreground Service that handles audio streaming (both Speaker receiver mode and Microphone transmitter mode).
 * Keeps app alive in background, manages wake/wifi locks, supports real-time recording, and provides notification controls.
 */
public class MusicService extends Service
    implements MusicFocusable, WorkerThreadPair.StopPlaybackCallback, AudioDataListener {

  static final String TAG = "MusicService";

  public static final int DEFAULT_AUDIO_PORT = 12345;
  public static final int DEFAULT_SAMPLE_RATE = 44100;
  public static final boolean DEFAULT_STEREO = true;
  public static final int DEFAULT_BUFFER_MS = 50;
  public static final boolean DEFAULT_RETRY = false;
  public static final boolean DEFAULT_USE_PERFORMANCE_MODE = false;
  public static final boolean DEFAULT_USE_MIN_BUFFER = false;

  public static final String ACTION_PLAY = "com.xlzhen.simpleprotocolplayer.action.PLAY";
  public static final String ACTION_STOP = "com.xlzhen.simpleprotocolplayer.action.STOP";
  public static final String ACTION_STATUS_CHANGED =
      "com.xlzhen.simpleprotocolplayer.action.STATUS_CHANGED";

  public static final String ACTION_START_RECORD =
      "com.xlzhen.simpleprotocolplayer.action.START_RECORD";
  public static final String ACTION_STOP_RECORD =
      "com.xlzhen.simpleprotocolplayer.action.STOP_RECORD";
  public static final String ACTION_RECORDING_STATE_CHANGED =
      "com.xlzhen.simpleprotocolplayer.action.RECORDING_STATE_CHANGED";
  public static final String EXTRA_IS_RECORDING = "extra_is_recording";
  public static final String EXTRA_RECORDING_PATH = "extra_recording_path";

  public static final String DATA_MODE = "mode";
  public static final int MODE_SPEAKER = 0;
  public static final int MODE_MIC = 1;

  public static final String DATA_IP_ADDRESS = "ip_addr";
  public static final String DATA_AUDIO_PORT = "audio_port";
  public static final String DATA_SAMPLE_RATE = "sample_rate";
  public static final String DATA_STEREO = "stereo";
  public static final String DATA_BUFFER_MS = "buffer_ms";
  public static final String DATA_RETRY = "retry";
  public static final String DATA_USE_PERFORMANCE_MODE = "use_performance_mode";
  public static final String DATA_USE_MIN_BUFFER = "use_min_buffer";

  public static final String EXTRA_STATUS = "extra_status";
  public static final String EXTRA_MODE = "extra_mode";
  public static final String EXTRA_HOST = "extra_host";
  public static final String EXTRA_PORT = "extra_port";
  public static final String EXTRA_MESSAGE = "extra_message";

  public static final float DUCK_VOLUME = 0.1f;

  public enum StreamingStatus {
    IDLE,
    CONNECTING,
    STREAMING_PLAYER,
    STREAMING_MIC,
    ERROR
  }

  // Observable static state
  public static volatile StreamingStatus currentStatus = StreamingStatus.IDLE;
  public static volatile int currentMode = MODE_SPEAKER;
  public static volatile String currentHost = "";
  public static volatile int currentPort = DEFAULT_AUDIO_PORT;
  public static volatile long streamStartTime = 0;
  public static volatile boolean isRecording = false;
  public static volatile String lastSavedRecordingPath = "";

  private final ArrayList<WorkerThreadPair> workers = new ArrayList<>();
  private MicWorkerThread micWorker = null;
  private StandaloneMicThread standaloneRecordThread = null;
  private PcmAudioRecorder recorder = null;

  private int currentSampleRate = DEFAULT_SAMPLE_RATE;
  private boolean currentStereo = DEFAULT_STEREO;

  AudioFocusHelper mAudioFocusHelper = null;

  enum AudioFocus {
    NoFocusNoDuck,
    NoFocusCanDuck,
    Focused
  }

  AudioFocus mAudioFocus = AudioFocus.NoFocusNoDuck;
  WifiLockManager wifiLockManager;
  private PowerManager.WakeLock wakeLock = null;

  final int NOTIFICATION_ID = 1001;
  final String NOTIFICATION_CHANNEL_ID = "simple_protocol_service_channel";
  Notification mNotification = null;

  private String currentNotificationTitle = "";
  private String currentNotificationContent = "";
  private int currentForegroundServiceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK;

  @Override
  public void onCreate() {
    super.onCreate();
    Log.i(TAG, "Creating MusicService");

    recorder = new PcmAudioRecorder(this);
    wifiLockManager = new WifiLockManager(this);
    wifiLockManager.setEnabled(true);

    PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
    if (pm != null) {
      wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SimpleProtocolPlayer:WakeLock");
      wakeLock.setReferenceCounted(false);
    }

    mAudioFocusHelper = new AudioFocusHelper(getApplicationContext(), this);
    createNotificationChannel();
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    if (intent != null) {
      String action = intent.getAction();
      if (action != null) {
        if (action.equals(ACTION_PLAY)) {
          processPlayRequest(intent);
        } else if (action.equals(ACTION_STOP)) {
          processStopRequest();
        } else if (action.equals(ACTION_START_RECORD)) {
          processStartRecordRequest();
        } else if (action.equals(ACTION_STOP_RECORD)) {
          processStopRecordRequest();
        }
      }
    }
    return START_NOT_STICKY;
  }

  private void updateStatus(StreamingStatus status, String message) {
    currentStatus = status;
    Intent broadcast = new Intent(ACTION_STATUS_CHANGED);
    broadcast.setPackage(getPackageName());
    broadcast.putExtra(EXTRA_STATUS, status.name());
    broadcast.putExtra(EXTRA_MODE, currentMode);
    broadcast.putExtra(EXTRA_HOST, currentHost);
    broadcast.putExtra(EXTRA_PORT, currentPort);
    if (message != null) {
      broadcast.putExtra(EXTRA_MESSAGE, message);
    }
    sendBroadcast(broadcast);
  }

  void processPlayRequest(Intent i) {
    currentMode = i.getIntExtra(DATA_MODE, MODE_SPEAKER);
    currentHost = i.getStringExtra(DATA_IP_ADDRESS);
    currentPort = i.getIntExtra(DATA_AUDIO_PORT, DEFAULT_AUDIO_PORT);
    currentSampleRate = i.getIntExtra(DATA_SAMPLE_RATE, DEFAULT_SAMPLE_RATE);
    currentStereo = i.getBooleanExtra(DATA_STEREO, DEFAULT_STEREO);
    int bufferMs = i.getIntExtra(DATA_BUFFER_MS, DEFAULT_BUFFER_MS);
    boolean retry = i.getBooleanExtra(DATA_RETRY, DEFAULT_RETRY);
    boolean usePerformanceMode = i.getBooleanExtra(DATA_USE_PERFORMANCE_MODE, DEFAULT_USE_PERFORMANCE_MODE);
    boolean useMinBuffer = i.getBooleanExtra(DATA_USE_MIN_BUFFER, DEFAULT_USE_MIN_BUFFER);

    // Stop existing streaming first to avoid dangling sockets
    stopActiveWorkers();

    streamStartTime = System.currentTimeMillis();

    if (currentMode == MODE_MIC) {
      startMicStream(currentHost, currentPort, currentSampleRate, currentStereo, bufferMs, retry);
    } else {
      tryToGetAudioFocus();
      playStream(currentHost, currentPort, currentSampleRate, currentStereo, bufferMs, retry, usePerformanceMode, useMinBuffer);
    }
  }

  private void acquireLocks() {
    wifiLockManager.setStayAwake(true);
    if (wakeLock != null && !wakeLock.isHeld()) {
      try {
        wakeLock.acquire(12 * 60 * 60 * 1000L); // 12 hours max
      } catch (Exception e) {
        Log.e(TAG, "Error acquiring wake lock: " + e);
      }
    }
  }

  private void releaseLocks() {
    wifiLockManager.setStayAwake(false);
    if (wakeLock != null && wakeLock.isHeld()) {
      try {
        wakeLock.release();
      } catch (Exception e) {
        Log.e(TAG, "Error releasing wake lock: " + e);
      }
    }
  }

  private void playStream(
      String serverAddr,
      int serverPort,
      int sample_rate,
      boolean stereo,
      int buffer_ms,
      boolean retry,
      boolean usePerformanceMode,
      boolean useMinBuffer) {

    workers.add(
        new WorkerThreadPair(
            this,
            this,
            this,
            serverAddr,
            serverPort,
            sample_rate,
            stereo,
            buffer_ms,
            retry,
            usePerformanceMode,
            useMinBuffer));

    acquireLocks();
    configVolume();

    String title = "PulseAudio Speaker";
    String content = "Receiving from " + serverAddr + ":" + serverPort;
    setUpAsForeground(title, content, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
    updateStatus(StreamingStatus.STREAMING_PLAYER, "Connected to " + serverAddr);
  }

  private void startMicStream(
      String serverAddr,
      int serverPort,
      int sampleRate,
      boolean stereo,
      int bufferMs,
      boolean retry) {

    micWorker =
        new MicWorkerThread(
            this,
            this,
            this,
            serverAddr,
            serverPort,
            sampleRate,
            stereo,
            bufferMs,
            retry);
    micWorker.start();

    acquireLocks();

    String title = "PulseAudio Microphone";
    String content = "Streaming mic to " + serverAddr + ":" + serverPort;
    int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        : ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK;
    setUpAsForeground(title, content, type);
    updateStatus(StreamingStatus.STREAMING_MIC, "Sending mic to " + serverAddr);
  }

  @Override
  public void onAudioData(byte[] buffer, int offset, int length) {
    if (recorder != null && isRecording) {
      recorder.write(buffer, offset, length);
    }
  }

  private void processStartRecordRequest() {
    if (isRecording) {
      return;
    }

    if (currentStatus == StreamingStatus.IDLE) {
      startStandaloneMicRecording();
    } else {
      int channels = currentStereo ? 2 : 1;
      if (recorder.start(currentSampleRate, channels)) {
        isRecording = true;
        broadcastRecordingState(true, null);
        updateNotification();
      }
    }
  }

  private void processStopRecordRequest() {
    if (!isRecording && standaloneRecordThread == null) {
      return;
    }

    stopStandaloneMicRecording();

    String savedPath = null;
    if (recorder != null && recorder.isRecording()) {
      savedPath = recorder.stop();
    }
    isRecording = false;
    lastSavedRecordingPath = savedPath != null ? savedPath : "";
    broadcastRecordingState(false, savedPath);

    if (savedPath != null) {
      showRecordingSavedToast(savedPath);
    }

    if (currentStatus == StreamingStatus.IDLE) {
      relaxResources();
      stopSelf();
    } else {
      updateNotification();
    }
  }

  private void startStandaloneMicRecording() {
    acquireLocks();
    currentSampleRate = 44100;
    currentStereo = false;
    if (recorder.start(44100, 1)) {
      isRecording = true;
      standaloneRecordThread = new StandaloneMicThread();
      standaloneRecordThread.start();

      String title = getString(R.string.recording_title_standalone);
      String content = getString(R.string.recording_desc_standalone);
      int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
          ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
          : ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK;
      setUpAsForeground(title, content, type);
      broadcastRecordingState(true, null);
    }
  }

  private void stopStandaloneMicRecording() {
    if (standaloneRecordThread != null) {
      standaloneRecordThread.cancel();
      standaloneRecordThread = null;
    }
  }

  private void showRecordingSavedToast(String savedPath) {
    Handler h = new Handler(Looper.getMainLooper());
    h.post(() -> {
      String msg = String.format(getString(R.string.recording_saved_toast), savedPath);
      Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_LONG).show();
    });
  }

  private void broadcastRecordingState(boolean recording, String savedPath) {
    Intent intent = new Intent(ACTION_RECORDING_STATE_CHANGED);
    intent.setPackage(getPackageName());
    intent.putExtra(EXTRA_IS_RECORDING, recording);
    if (savedPath != null) {
      intent.putExtra(EXTRA_RECORDING_PATH, savedPath);
    }
    sendBroadcast(intent);
  }

  void processStopRequest() {
    if (isRecording) {
      processStopRecordRequest();
    }
    relaxResources();
    giveUpAudioFocus();
    updateStatus(StreamingStatus.IDLE, "Stopped");
    stopSelf();
  }

  void relaxResources() {
    try {
      stopForeground(STOP_FOREGROUND_REMOVE);
    } catch (Exception e) {
      Log.e(TAG, "stopForeground error: " + e);
    }
    releaseLocks();
    stopActiveWorkers();
  }

  void stopActiveWorkers() {
    for (WorkerThreadPair worker : workers) {
      worker.stopAndInterrupt();
    }
    workers.clear();

    if (micWorker != null) {
      micWorker.customStop();
      micWorker = null;
    }
    stopStandaloneMicRecording();
  }

  void tryToGetAudioFocus() {
    if (mAudioFocus != AudioFocus.Focused
        && mAudioFocusHelper != null
        && mAudioFocusHelper.requestFocus()) {
      mAudioFocus = AudioFocus.Focused;
    }
  }

  void giveUpAudioFocus() {
    if (mAudioFocus == AudioFocus.Focused
        && mAudioFocusHelper != null
        && mAudioFocusHelper.abandonFocus()) {
      mAudioFocus = AudioFocus.NoFocusNoDuck;
    }
  }

  void configVolume() {
    if (mAudioFocus == AudioFocus.NoFocusNoDuck) {
      if (currentStatus == StreamingStatus.STREAMING_PLAYER) {
        processStopRequest();
      }
      return;
    }

    for (WorkerThreadPair it : workers) {
      if (it.audioTrack != null) {
        if (mAudioFocus == AudioFocus.NoFocusCanDuck) {
          it.audioTrack.setVolume(DUCK_VOLUME);
        } else {
          it.audioTrack.setVolume(1.0f);
        }
      }
    }
  }

  @SuppressLint("InlinedApi")
  void setUpAsForeground(String title, String content, int foregroundServiceType) {
    currentNotificationTitle = title;
    currentNotificationContent = content;
    currentForegroundServiceType = foregroundServiceType;

    mNotification = buildNotification(title, content);

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      startForeground(NOTIFICATION_ID, mNotification, foregroundServiceType);
    } else {
      startForeground(NOTIFICATION_ID, mNotification);
    }
  }

  private void updateNotification() {
    NotificationManager nm = getSystemService(NotificationManager.class);
    if (nm != null) {
      mNotification = buildNotification(currentNotificationTitle, currentNotificationContent);
      nm.notify(NOTIFICATION_ID, mNotification);
    }
  }

  private Notification buildNotification(String baseTitle, String baseContent) {
    PendingIntent contentIntent =
        PendingIntent.getActivity(
            getApplicationContext(),
            0,
            new Intent(getApplicationContext(), MainActivity.class),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

    Intent stopIntent = new Intent(this, MusicService.class);
    stopIntent.setAction(ACTION_STOP);
    PendingIntent stopPendingIntent =
        PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

    NotificationCompat.Action stopAction =
        new NotificationCompat.Action.Builder(
                R.drawable.ic_stop,
                getString(R.string.notification_action_stop),
                stopPendingIntent)
            .build();

    NotificationCompat.Action recordAction;
    if (isRecording) {
      Intent stopRecordIntent = new Intent(this, MusicService.class);
      stopRecordIntent.setAction(ACTION_STOP_RECORD);
      PendingIntent stopRecordPi =
          PendingIntent.getService(
              this,
              2,
              stopRecordIntent,
              PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
      recordAction =
          new NotificationCompat.Action.Builder(
                  R.drawable.ic_stop_record,
                  getString(R.string.notification_action_stop_record),
                  stopRecordPi)
              .build();
    } else {
      Intent startRecordIntent = new Intent(this, MusicService.class);
      startRecordIntent.setAction(ACTION_START_RECORD);
      PendingIntent startRecordPi =
          PendingIntent.getService(
              this,
              3,
              startRecordIntent,
              PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
      recordAction =
          new NotificationCompat.Action.Builder(
                  R.drawable.ic_record,
                  getString(R.string.notification_action_record),
                  startRecordPi)
              .build();
    }

    String displayTitle = isRecording ? "🔴 [REC] " + baseTitle : baseTitle;
    String displayContent =
        isRecording
            ? baseContent + " • " + getString(R.string.recording_active_badge)
            : baseContent;

    return new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_stat_playing)
        .setOngoing(true)
        .setContentTitle(displayTitle)
        .setContentText(displayContent)
        .setContentIntent(contentIntent)
        .addAction(stopAction)
        .addAction(recordAction)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .build();
  }

  private void createNotificationChannel() {
    NotificationChannel channel =
        new NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Simple Protocol Audio Service",
            NotificationManager.IMPORTANCE_LOW);
    channel.setDescription("Background audio streaming for Simple Protocol Player");
    channel.setSound(null, null);
    channel.enableVibration(false);
    NotificationManager notificationManager = getSystemService(NotificationManager.class);
    if (notificationManager != null) {
      notificationManager.createNotificationChannel(channel);
    }
  }

  @Override
  public void onGainedAudioFocus() {
    mAudioFocus = AudioFocus.Focused;
    if (currentStatus == StreamingStatus.STREAMING_PLAYER) {
      configVolume();
    }
  }

  @Override
  public void onLostAudioFocus(boolean canDuck) {
    mAudioFocus = canDuck ? AudioFocus.NoFocusCanDuck : AudioFocus.NoFocusNoDuck;
    if (currentStatus == StreamingStatus.STREAMING_PLAYER) {
      configVolume();
    }
  }

  @Override
  public void onDestroy() {
    processStopRequest();
    super.onDestroy();
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }

  @Override
  public void stopPlayback() {
    processStopRequest();
  }

  private class StandaloneMicThread extends Thread {
    private volatile boolean running = true;
    private AudioRecord audioRecord;

    @SuppressLint("MissingPermission")
    @Override
    public void run() {
      int channelConfig = AudioFormat.CHANNEL_IN_MONO;
      int audioFormat = AudioFormat.ENCODING_PCM_16BIT;
      int minBufSize = AudioRecord.getMinBufferSize(44100, channelConfig, audioFormat);
      if (minBufSize <= 0) minBufSize = 4096;
      byte[] buffer = new byte[minBufSize];

      try {
        audioRecord =
            new AudioRecord(
                MediaRecorder.AudioSource.MIC,
                44100,
                channelConfig,
                audioFormat,
                minBufSize * 2);

        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
          Log.e(TAG, "Standalone AudioRecord failed to initialize");
          return;
        }

        audioRecord.startRecording();
        while (running) {
          int read = audioRecord.read(buffer, 0, buffer.length);
          if (read > 0) {
            onAudioData(buffer, 0, read);
          } else if (read < 0) {
            break;
          }
        }
      } catch (Exception e) {
        Log.e(TAG, "Standalone mic error: " + e);
      } finally {
        if (audioRecord != null) {
          try {
            if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
              audioRecord.stop();
            }
            audioRecord.release();
          } catch (Exception ignored) {
          }
          audioRecord = null;
        }
      }
    }

    public void cancel() {
      running = false;
      interrupt();
    }
  }
}
