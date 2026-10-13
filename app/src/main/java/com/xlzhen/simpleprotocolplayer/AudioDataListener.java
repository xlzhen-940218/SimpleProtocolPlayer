package com.xlzhen.simpleprotocolplayer;

public interface AudioDataListener {
  void onAudioData(byte[] buffer, int offset, int length);
}
