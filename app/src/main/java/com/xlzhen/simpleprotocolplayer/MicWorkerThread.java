package com.xlzhen.simpleprotocolplayer;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.util.Log;
import android.widget.Toast;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;

/**
 * Worker thread that records PCM audio from the Android microphone and streams it
 * to a PulseAudio / PipeWire simple protocol TCP server (satisfying Issue #13 and Issue #43).
 */
public class MicWorkerThread extends ThreadStoppable {
  private static final String TAG = "MicWorkerThread";

  static final int[][] RETRY_PARAMS = new int[][] {{5, 12}, {20, 6}, {60, 2}};
  static final int SOCKET_TIMEOUT = 5 * 1000;

  private final Context context;
  private final WorkerThreadPair.StopPlaybackCallback stopCallback;
  private final String ipAddr;
  private final int port;
  private final int sampleRate;
  private final boolean stereo;
  private final int bufferMs;
  private final boolean attemptRetry;
  private final AudioDataListener audioDataListener;

  private volatile Socket socket = null;
  private AudioRecord audioRecord = null;

  public MicWorkerThread(
      Context context,
      WorkerThreadPair.StopPlaybackCallback stopCallback,
      AudioDataListener audioDataListener,
      String ipAddr,
      int port,
      int sampleRate,
      boolean stereo,
      int bufferMs,
      boolean attemptRetry) {
    this.context = context;
    this.stopCallback = stopCallback;
    this.audioDataListener = audioDataListener;
    this.ipAddr = ipAddr;
    this.port = port;
    this.sampleRate = sampleRate > 0 ? sampleRate : MusicService.DEFAULT_SAMPLE_RATE;
    this.stereo = stereo;
    this.bufferMs = bufferMs > 5 ? bufferMs : MusicService.DEFAULT_BUFFER_MS;
    this.attemptRetry = attemptRetry;
    setName("mic:" + ipAddr + ":" + port);
  }

  @Override
  public void customStop() {
    super.customStop();
    closeSocket();
    releaseAudioRecord();
  }

  private synchronized void closeSocket() {
    if (socket != null) {
      try {
        if (!socket.isClosed()) {
          socket.close();
        }
      } catch (IOException e) {
        Log.i(TAG, "Error closing mic socket: " + e);
      } finally {
        socket = null;
      }
    }
  }

  private synchronized void releaseAudioRecord() {
    if (audioRecord != null) {
      try {
        if (audioRecord.getState() == AudioRecord.STATE_INITIALIZED) {
          if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
            audioRecord.stop();
          }
        }
        audioRecord.release();
      } catch (Exception e) {
        Log.e(TAG, "Error releasing AudioRecord: " + e);
      } finally {
        audioRecord = null;
      }
    }
  }

  @Override
  public void run() {
    Log.i(TAG, "MicWorkerThread started");
    boolean connectionMade;
    int retryCount = 0;
    int retryParamIndex = 0;

    while (running) {
      connectionMade = runImpl();

      if (!running) {
        Log.i(TAG, "MicWorkerThread stopped");
        break;
      }
      if (!attemptRetry) {
        Log.i(TAG, "No retry for mic");
        break;
      }

      if (connectionMade) {
        retryCount = retryParamIndex = 0;
        continue;
      }

      if (retryCount >= RETRY_PARAMS[retryParamIndex][1]) {
        retryCount = 0;
        retryParamIndex++;
        if (retryParamIndex >= RETRY_PARAMS.length) {
          Log.i(TAG, "Mic retry limit reached");
          break;
        }
      }

      try {
        //noinspection BusyWait
        Thread.sleep((long) RETRY_PARAMS[retryParamIndex][0] * 1000);
      } catch (Exception ignored) {
      }
      retryCount++;
    }

    if (running) {
      brokenShutdown();
    }
    Log.i(TAG, "MicWorkerThread finished");
  }

  @SuppressLint("MissingPermission")
  private boolean runImpl() {
    boolean connectionMade = false;
    int channelConfig = stereo ? AudioFormat.CHANNEL_IN_STEREO : AudioFormat.CHANNEL_IN_MONO;
    int audioFormat = AudioFormat.ENCODING_PCM_16BIT;

    int minBufSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat);
    if (minBufSize <= 0) {
      minBufSize = 4096;
    }

    int bytesPerSec = sampleRate * 2 * (stereo ? 2 : 1);
    int packetSize = (bytesPerSec * bufferMs) / 1000;
    packetSize = Math.max(packetSize, minBufSize);
    if (stereo) {
      packetSize = (packetSize + 3) & ~0x3;
    } else {
      packetSize = (packetSize + 1) & ~0x1;
    }

    byte[] buffer = new byte[packetSize];

    try {
      audioRecord =
          new AudioRecord(
              MediaRecorder.AudioSource.MIC,
              sampleRate,
              channelConfig,
              audioFormat,
              packetSize * 2);

      if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
        Log.e(TAG, "AudioRecord initialization failed");
        return false;
      }

      socket = new Socket(ipAddr, port);
      socket.setTcpNoDelay(true);
      socket.setSoTimeout(SOCKET_TIMEOUT);
      OutputStream os = socket.getOutputStream();

      audioRecord.startRecording();
      Log.i(TAG, "AudioRecord started and connected to " + ipAddr + ":" + port);

      while (running) {
        int bytesRead = audioRecord.read(buffer, 0, buffer.length);
        if (bytesRead > 0) {
          connectionMade = true;
          os.write(buffer, 0, bytesRead);
          if (audioDataListener != null) {
            audioDataListener.onAudioData(buffer, 0, bytesRead);
          }
        } else if (bytesRead < 0) {
          Log.e(TAG, "AudioRecord read error: " + bytesRead);
          break;
        }
      }
    } catch (Exception e) {
      Log.i(TAG, "runImpl mic exception: " + e);
    } finally {
      closeSocket();
      releaseAudioRecord();
    }

    return connectionMade;
  }

  private void brokenShutdown() {
    Handler h = new Handler(context.getMainLooper());
    h.post(
        () -> {
          Toast.makeText(
                  context.getApplicationContext(),
                  "Microphone stream disconnected",
                  Toast.LENGTH_SHORT)
              .show();
          if (stopCallback != null) {
            stopCallback.stopPlayback();
          }
        });
  }
}
