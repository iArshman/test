package me.eternal.purrfect.bridge;

interface AutoOpenInterface {
    int getProcessedCount();
    List<String> getQueueItems(); // returns JSON serialized SnapQueueItem list
    void reset();
}
