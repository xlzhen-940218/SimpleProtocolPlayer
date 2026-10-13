package com.kaytat.simpleprotocolplayer;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import java.util.ArrayList;

/**
 * Foreground Service that handles audio streaming (both Speaker receiver mode and Microphone transmitter mode).
 * Keeps app alive in background, manages wake/wifi locks, and provides notification controls.
 */
public class MusicService extends Service
    implements MusicFocusable, WorkerThreadPair.StopPlaybackCallback {

  static final String TAG = "MusicService";

  public static final int DEFAULT_AUDIO_PORT = 12345;
  public static final int DEFAULT_SAMPLE_RATE = 44100;
  public static final boolean DEFAULT_STEREO = true;
  public static final int DEFAULT_BUFFER_MS = 50;
  public static final boolean DEFAULT_RETRY = false;
  public static final boolean DEFAULT_USE_PERFORMANCE_MODE = false;
  public static final boolean DEFAULT_USE_MIN_BUFFER = false;

  public static final String ACTION_PLAY = "com.kaytat.simpleprotocolplayer.action.PLAY";
  public static final String ACTION_STOP = "com.kaytat.simpleprotocolplayer.action.STOP";
  public static final String ACTION_STATUS_CHANGED =
      "com.kaytat.simpleprotocolplayer.action.STATUS_CHANGED";

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

  private final ArrayList<WorkerThreadPair> workers = new ArrayList<>();
  private MicWorkerThread micWorker = null;

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

  @Override
  public void onCreate() {
    super.onCreate();
    Log.i(TAG, "Creating MusicService");

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
    int sampleRate = i.getIntExtra(DATA_SAMPLE_RATE, DEFAULT_SAMPLE_RATE);
    boolean stereo = i.getBooleanExtra(DATA_STEREO, DEFAULT_STEREO);
    int bufferMs = i.getIntExtra(DATA_BUFFER_MS, DEFAULT_BUFFER_MS);
    boolean retry = i.getBooleanExtra(DATA_RETRY, DEFAULT_RETRY);
    boolean usePerformanceMode = i.getBooleanExtra(DATA_USE_PERFORMANCE_MODE, DEFAULT_USE_PERFORMANCE_MODE);
    boolean useMinBuffer = i.getBooleanExtra(DATA_USE_MIN_BUFFER, DEFAULT_USE_MIN_BUFFER);

    // Stop existing streaming first to avoid dangling sockets
    stopActiveWorkers();

    streamStartTime = System.currentTimeMillis();

    if (currentMode == MODE_MIC) {
      // Microphone mode
      startMicStream(currentHost, currentPort, sampleRate, stereo, bufferMs, retry);
    } else {
      // Speaker mode
      tryToGetAudioFocus();
      playStream(currentHost, currentPort, sampleRate, stereo, bufferMs, retry, usePerformanceMode, useMinBuffer);
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

  void processStopRequest() {
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

    mNotification =
        new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_playing)
            .setOngoing(true)
            .setContentTitle(title)
            .setContentText(content)
            .setContentIntent(contentIntent)
            .addAction(stopAction)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build();

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      startForeground(NOTIFICATION_ID, mNotification, foregroundServiceType);
    } else {
      startForeground(NOTIFICATION_ID, mNotification);
    }
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
  public IBinder onBind(Intent arg0) {
    return null;
  }

  @Override
  public void stopPlayback() {
    processStopRequest();
  }
}
