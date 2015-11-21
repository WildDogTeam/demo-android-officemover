package com.wilddog.officemover;

import com.wilddog.client.Wilddog;

/**
 * @author Jeen
 * @since 11/20/15
 *
 * Initialize Wilddog with the application context. This must happen before the client is used.
 */
public class OfficeMoverApplication extends android.app.Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Wilddog.setAndroidContext(this);
    }
}
