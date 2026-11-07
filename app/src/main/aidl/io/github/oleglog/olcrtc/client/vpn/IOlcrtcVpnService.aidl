package io.github.oleglog.olcrtc.client.vpn;

import io.github.oleglog.olcrtc.client.vpn.IVpnStateCallback;
import android.os.Bundle;

interface IOlcrtcVpnService {
    void start(long profileId);
    void startSubscriptionProfile(String profileId);
    void stop();
    void reconnect();
    int[] refreshSubscription(long subscriptionId);
    long testConnectionLatency();
    long[] getTrafficSnapshot();
    String getActiveProfileReference();
    String getPendingCaptchaUrl();
    int getState();
    void registerCallback(IVpnStateCallback callback);
    void unregisterCallback(IVpnStateCallback callback);
    Bundle checkForUpdate(String currentVersion);
    int getActiveSocksPort();
}
