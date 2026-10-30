package me.eternal.purrfect.bridge;


interface AccountStorage {
    Map<String, String> getAccounts(); // userId -> username
    void addAccount(String userId, String username, in ParcelFileDescriptor data);
    void removeAccount(String userId);
    boolean isAccountExists(String userId);
    @nullable ParcelFileDescriptor getAccountData(String userId);
}