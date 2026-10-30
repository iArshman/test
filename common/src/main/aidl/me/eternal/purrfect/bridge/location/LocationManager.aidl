package me.eternal.purrfect.bridge.location;

import me.eternal.purrfect.bridge.location.FriendLocation;

interface LocationManager {
    void provideFriendsLocation(in List<FriendLocation> friendsLocation);
}