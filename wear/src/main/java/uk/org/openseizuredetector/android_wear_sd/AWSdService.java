package uk.org.openseizuredetector.android_wear_sd;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

public class AWSdService extends Service {
    public AWSdService() {
    }

    @Override
    public IBinder onBind(Intent intent) {
        // TODO: Return the communication channel to the service.
        throw new UnsupportedOperationException("Not yet implemented");
    }
}
