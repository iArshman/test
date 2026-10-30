package me.eternal.purrfect.bridge.call;

interface CallDownloadSession {
    ParcelFileDescriptor createStream(long startTimestampMillis, int channels, int sampleRate, int encoding);
    oneway void end();
}
