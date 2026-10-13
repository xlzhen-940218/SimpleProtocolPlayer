package com.xlzhen.simpleprotocolplayer;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Handles recording PCM audio streams asynchronously and saving them as standard WAV files
 * in the public internal storage Music directory (/Music/).
 */
public class PcmAudioRecorder {
  private static final String TAG = "PcmAudioRecorder";

  private final Context context;
  private volatile boolean isRecording = false;
  private File tempPcmFile;
  private FileOutputStream tempPcmOut;
  private int sampleRate = 44100;
  private int channels = 2;
  private long totalPcmBytes = 0;

  private final BlockingQueue<byte[]> dataQueue = new LinkedBlockingQueue<>();
  private Thread writerThread;

  public PcmAudioRecorder(Context context) {
    this.context = context.getApplicationContext();
  }

  public synchronized boolean isRecording() {
    return isRecording;
  }

  public synchronized boolean start(int sampleRate, int channels) {
    if (isRecording) {
      Log.w(TAG, "Already recording");
      return false;
    }

    this.sampleRate = sampleRate > 0 ? sampleRate : 44100;
    this.channels = channels > 0 ? channels : 2;
    this.totalPcmBytes = 0;
    this.dataQueue.clear();

    try {
      File cacheDir = context.getExternalCacheDir() != null
          ? context.getExternalCacheDir()
          : context.getCacheDir();
      tempPcmFile = new File(cacheDir, "temp_stream_" + System.currentTimeMillis() + ".pcm");
      tempPcmOut = new FileOutputStream(tempPcmFile);
    } catch (IOException e) {
      Log.e(TAG, "Failed to create temp PCM file", e);
      return false;
    }

    isRecording = true;

    writerThread = new Thread(() -> {
      while (isRecording || !dataQueue.isEmpty()) {
        try {
          byte[] chunk = dataQueue.poll(100, TimeUnit.MILLISECONDS);
          if (chunk != null && tempPcmOut != null) {
            tempPcmOut.write(chunk);
            totalPcmBytes += chunk.length;
          }
        } catch (InterruptedException ignored) {
        } catch (IOException e) {
          Log.e(TAG, "Error writing PCM chunk", e);
        }
      }
      try {
        if (tempPcmOut != null) {
          tempPcmOut.flush();
          tempPcmOut.close();
          tempPcmOut = null;
        }
      } catch (IOException e) {
        Log.e(TAG, "Error closing temp PCM stream", e);
      }
    }, "PcmWriterThread");
    writerThread.start();

    Log.i(TAG, "Started recording: " + sampleRate + "Hz, channels=" + channels);
    return true;
  }

  public void write(byte[] data, int offset, int length) {
    if (!isRecording || data == null || length <= 0) {
      return;
    }
    byte[] chunk = new byte[length];
    System.arraycopy(data, offset, chunk, 0, length);
    dataQueue.offer(chunk);
  }

  public synchronized String stop() {
    if (!isRecording) {
      return null;
    }
    isRecording = false;

    if (writerThread != null) {
      try {
        writerThread.join(2000);
      } catch (InterruptedException ignored) {
      }
      writerThread = null;
    }

    if (tempPcmFile == null || !tempPcmFile.exists() || totalPcmBytes <= 0) {
      Log.w(TAG, "No PCM audio was recorded or temp file missing");
      if (tempPcmFile != null) {
        tempPcmFile.delete();
      }
      return null;
    }

    String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
    String displayName = "SPP_Record_" + timestamp + ".wav";
    String savedLocation = null;

    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        savedLocation = saveWithMediaStore(displayName);
      }
      if (savedLocation == null) {
        savedLocation = saveWithDirectFile(displayName);
      }
    } catch (Exception e) {
      Log.e(TAG, "Error finalizing WAV file: " + e, e);
    } finally {
      if (tempPcmFile != null) {
        tempPcmFile.delete();
      }
    }

    Log.i(TAG, "Recording saved successfully to: " + savedLocation);
    return savedLocation;
  }

  private String saveWithMediaStore(String displayName) {
    ContentResolver resolver = context.getContentResolver();
    ContentValues values = new ContentValues();
    values.put(MediaStore.Audio.Media.DISPLAY_NAME, displayName);
    values.put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav");
    values.put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC);
    values.put(MediaStore.Audio.Media.IS_PENDING, 1);

    Uri uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values);
    if (uri == null) {
      return null;
    }

    try (OutputStream out = resolver.openOutputStream(uri);
         InputStream in = new FileInputStream(tempPcmFile)) {
      if (out == null) {
        return null;
      }
      writeWavHeader(out, totalPcmBytes, sampleRate, channels, 16);
      byte[] buffer = new byte[8192];
      int read;
      while ((read = in.read(buffer)) != -1) {
        out.write(buffer, 0, read);
      }
      out.flush();

      values.clear();
      values.put(MediaStore.Audio.Media.IS_PENDING, 0);
      resolver.update(uri, values, null, null);

      return Environment.DIRECTORY_MUSIC + "/" + displayName;
    } catch (IOException e) {
      Log.e(TAG, "MediaStore save failed: " + e, e);
      resolver.delete(uri, null, null);
      return null;
    }
  }

  private String saveWithDirectFile(String displayName) {
    File musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC);
    if (!musicDir.exists()) {
      musicDir.mkdirs();
    }
    File targetFile = new File(musicDir, displayName);

    try (OutputStream out = new FileOutputStream(targetFile);
         InputStream in = new FileInputStream(tempPcmFile)) {
      writeWavHeader(out, totalPcmBytes, sampleRate, channels, 16);
      byte[] buffer = new byte[8192];
      int read;
      while ((read = in.read(buffer)) != -1) {
        out.write(buffer, 0, read);
      }
      out.flush();

      MediaScannerConnection.scanFile(
          context,
          new String[]{targetFile.getAbsolutePath()},
          new String[]{"audio/wav"},
          null);

      return targetFile.getAbsolutePath();
    } catch (IOException e) {
      Log.e(TAG, "Direct file save failed: " + e, e);
      return null;
    }
  }

  private void writeWavHeader(
      OutputStream out,
      long totalAudioLen,
      long longSampleRate,
      int channels,
      int bitsPerSample)
      throws IOException {

    long totalDataLen = totalAudioLen + 36;
    long byteRate = longSampleRate * channels * bitsPerSample / 8;
    int blockAlign = channels * bitsPerSample / 8;

    byte[] header = new byte[44];
    // RIFF/WAVE header
    header[0] = 'R';
    header[1] = 'I';
    header[2] = 'F';
    header[3] = 'F';
    header[4] = (byte) (totalDataLen & 0xff);
    header[5] = (byte) ((totalDataLen >> 8) & 0xff);
    header[6] = (byte) ((totalDataLen >> 16) & 0xff);
    header[7] = (byte) ((totalDataLen >> 24) & 0xff);
    header[8] = 'W';
    header[9] = 'A';
    header[10] = 'V';
    header[11] = 'E';
    // 'fmt ' chunk
    header[12] = 'f';
    header[13] = 'm';
    header[14] = 't';
    header[15] = ' ';
    header[16] = 16; // 4 bytes: size of 'fmt ' chunk
    header[17] = 0;
    header[18] = 0;
    header[19] = 0;
    header[20] = 1; // format = 1 (PCM)
    header[21] = 0;
    header[22] = (byte) channels;
    header[23] = 0;
    header[24] = (byte) (longSampleRate & 0xff);
    header[25] = (byte) ((longSampleRate >> 8) & 0xff);
    header[26] = (byte) ((longSampleRate >> 16) & 0xff);
    header[27] = (byte) ((longSampleRate >> 24) & 0xff);
    header[28] = (byte) (byteRate & 0xff);
    header[29] = (byte) ((byteRate >> 8) & 0xff);
    header[30] = (byte) ((byteRate >> 16) & 0xff);
    header[31] = (byte) ((byteRate >> 24) & 0xff);
    header[32] = (byte) blockAlign;
    header[33] = 0;
    header[34] = (byte) bitsPerSample;
    header[35] = 0;
    // 'data' chunk
    header[36] = 'd';
    header[37] = 'a';
    header[38] = 't';
    header[39] = 'a';
    header[40] = (byte) (totalAudioLen & 0xff);
    header[41] = (byte) ((totalAudioLen >> 8) & 0xff);
    header[42] = (byte) ((totalAudioLen >> 16) & 0xff);
    header[43] = (byte) ((totalAudioLen >> 24) & 0xff);

    out.write(header, 0, 44);
  }
}
