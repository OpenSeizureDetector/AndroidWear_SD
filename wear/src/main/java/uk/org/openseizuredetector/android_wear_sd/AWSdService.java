package uk.org.openseizuredetector.android_wear_sd;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

public class AWSdService extends Service {
    private final static String TAG="AWSdService";

    public AWSdService() {
        Log.v(TAG,"AWSdService Constructor()");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.v(TAG,"onStartCommand()");
        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.v(TAG,"onBind()");
        // TODO: Return the communication channel to the service.
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Log.v(TAG,"onUnbind()");
        return super.onUnbind(intent);
    }


    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.v(TAG,"onDestroy()");
    }

    @Override
    public void onRebind(Intent intent) {
        super.onRebind(intent);
        Log.v(TAG,"onRebind()");
    }
}
