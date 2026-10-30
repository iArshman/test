package me.eternal.purrfect.bridge.storage;

interface FileHandle {
    boolean exists();
    boolean create();
    boolean delete();

    @nullable ParcelFileDescriptor open(int mode);
}