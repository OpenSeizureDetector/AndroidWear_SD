package uk.org.openseizuredetector;

import android.app.Activity;
import android.app.ActivityManager;
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
import android.view.MotionEvent;
import android.widget.TextView;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Toast;
import android.widget.ToggleButton;


import java.util.Timer;
import java.util.TimerTask;

public class StartUpActivity extends Activity {
    private ServiceConnection mConnection;
    private AWSdService mAWSdServce;
    private static final String TAG = "StartUpActivity";
    private TextView mTextView;
    private Timer mUiTimer;
    private ToggleButton toggleButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_start_up);
        //final WatchViewStub stub = (WatchViewStub) findViewById(R.id.watch_view_stub);
        //stub.setOnLayoutInflatedListener(new WatchViewStub.OnLayoutInflatedListener() {
        //    @Override
        //    public void onLayoutInflated(WatchViewStub stub) {
        //        mTextView = (TextView) stub.findViewById(R.id.startUpStatusTv);
        //    }
        //});
        toggleButton = (ToggleButton) findViewById(R.id.toggleButton1);

        // initiate toggle button's
        toggleButton.setOnClickListener(new OnClickListener() {

            @Override
            public void onClick(View v) {
                if (toggleButton.isChecked()) {
                    //checked is now true, meaning alarms should be off
                    mAWSdServce.mSdData.alarmState = 6;
                } else {
                    mAWSdServce.mSdData.alarmState = 0;
                    //checked is now false, meaning alarms should be on
                }
            }
        });

    }

    public void addListenerOnButton() {



    }
    @Override
    protected void onStart() {
        super.onStart();
        //if (mTextView != null) mTextView.setText("onStart");
        if (isSdServiceRunning()) {
            Log.v(TAG,"Service already running - not starting it");
        } else {
            Log.v(TAG,"Service not running - starting it");
            startService(new Intent(getBaseContext(), AWSdService.class));
            //if (mTextView != null) mTextView.setText("Service Started");
        }
    }

    private class UpdateUiTask extends TimerTask {
        @Override
        public void run() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (mAWSdServce==null) {
                        Log.v(TAG, "UpdateUiTask - service is null");
                        if (mTextView != null) mTextView.setText("NOT CONNECTED");
                    } else {
                        Log.v(TAG, "UpdateUiTask() - " + mAWSdServce.mNSamp);
                        if (mTextView != null) mTextView.setText("mNsamp="+mAWSdServce.mNSamp);
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
        mUiTimer = new Timer();
        mUiTimer.schedule(new UpdateUiTask(),0,1000);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        Log.v(TAG,"dispatch touch event");
        mAWSdServce.mSdData.alarmState = 0;
        mAWSdServce.ClearAlarmCount();
        return  super.dispatchTouchEvent(ev);
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


    private boolean isSdServiceRunning() {
        ActivityManager manager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            //Log.v(TAG,"isSdServiceRunning() - "+service.service.getClassName());
            if ("uk.org.openseizuredetector.android_wear_sd.AWSdService".equals(service.service.getClassName())) {
                Log.v(TAG,"isSdServiceRunning() - returning true");
                return true;
            }
        }
        Log.v(TAG,"isSdServiceRunning() - returning false");
        return false;
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
