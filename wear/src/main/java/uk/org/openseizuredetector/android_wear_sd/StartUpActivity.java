package uk.org.openseizuredetector.android_wear_sd;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.IBinder;
import android.support.wearable.view.WatchViewStub;
import android.util.Log;
import android.widget.TextView;

import java.util.Timer;
import java.util.TimerTask;

public class StartUpActivity extends Activity {
    private ServiceConnection mConnection;
    private AWSdService mAWSdServce;
    private static final String TAG = "StartUpActivity";
    private TextView mTextView;
    private Timer mUiTimer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_start_up);
        final WatchViewStub stub = (WatchViewStub) findViewById(R.id.watch_view_stub);
        stub.setOnLayoutInflatedListener(new WatchViewStub.OnLayoutInflatedListener() {
            @Override
            public void onLayoutInflated(WatchViewStub stub) {
                mTextView = (TextView) stub.findViewById(R.id.startUpStatusTv);
            }
        });

    }

    @Override
    protected void onStart() {
        super.onStart();
        if (mTextView != null) mTextView.setText("onStart");
        startService(new Intent(getBaseContext(), AWSdService.class));
        if (mTextView != null) mTextView.setText("Service Started");

        mUiTimer = new Timer();

    }

    private class UpdateUiTask extends TimerTask {
        @Override
        public void run() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (mAWSdServce==null) {
                        Log.v(TAG, "UpdateUiTask - service is null");
                    } else {
                        Log.v(TAG, "UpdateUiTask() - " + mAWSdServce.mNSamp);
                        mTextView.setText("mNsamp="+mAWSdServce.mNSamp);
                    }
                }
            });
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.i(TAG, "onResume() - binding to Service");
        bindService(
                new Intent(this, AWSdService.class),
                mConnection = new Connection(),
                Context.BIND_AUTO_CREATE);
        mUiTimer.schedule(new UpdateUiTask(),0,1000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.i(TAG, "onPause() - unbinding from service");
        unbindService(mConnection);
        mUiTimer.cancel();
    }

    @Override
    protected void onStop() {
        super.onStop();
        Log.v(TAG, "onStop()");
        // FIXME - THERE IS NO WAY TO STOP THE SERVICE - WE ARE DOING THIS TO STRESS TEST BATTERY CONSUMPTION.
        //stopService(new Intent(getBaseContext(), AWSdService.class));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }


    private void updateUI() {
        Log.v(TAG,"updateUI");
    }

    private class Connection implements ServiceConnection {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
            AWSdService.Access access = ((AWSdService.Access) iBinder);
            mAWSdServce = access.getService();
            Log.i(TAG, "onServiceConnected()" + componentName.toShortString());
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            mAWSdServce = null;
            Log.i(TAG, "onServiceDisconnected()" + componentName.toShortString());
        }
    }

}
