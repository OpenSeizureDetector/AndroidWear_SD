package uk.org.openseizuredetector;


import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.TextView;
import android.widget.ToggleButton;

import androidx.annotation.RequiresApi;
import androidx.core.app.ActivityCompat;

import java.util.Timer;
import java.util.TimerTask;

public class StartUpActivity extends Activity {
    private static final String CHANNEL_DEFAULT_IMPORTANCE = "2";
    private ServiceConnection mConnection;
    private AWSdService mAWSdServce;
    private static final String TAG = "StartUpActivity";
    private TextView mTextView;
    private Timer mUiTimer;
    private Timer mOkTimer;
    private ToggleButton toggleButton;
    private TextView mAlarmText;
    private Button mOKButton;
    private Button mHelpButton;

    private static final int PERMISSION_REQUEST_BODY_SENSORS = 16;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_start_up);
        // final WatchViewStub stub = (WatchViewStub) findViewById(R.id.watch_view_stub);
        // stub.setOnLayoutInflatedListener(new WatchViewStub.OnLayoutInflatedListener() {
        //     @Override
        //    public void onLayoutInflated(WatchViewStub stub) {
        //        mTextView = (TextView) stub.findViewById(R.id.startUpStatusTv);
        //    }
        // });
        toggleButton = (ToggleButton) findViewById(R.id.toggleButton1);
        mAlarmText = (TextView) findViewById(R.id.text1);
        mOKButton = (Button) findViewById(R.id.button);
        mHelpButton = (Button) findViewById(R.id.button1);


        // initiate toggle button's on click
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


        // initiate  OK button's on click
        mOKButton.setOnClickListener(new OnClickListener() {

            @Override
            public void onClick(View v) {
                //send OK Message
                mAWSdServce.mSdData.alarmState = 10;
                mAWSdServce.ClearAlarmCount();
                mOkTimer = new Timer();
                mOkTimer.schedule(new TurnOffOk(), 1000);
                mAWSdServce.handleSendingIAmOK();
                //After sending message, Send activity to the background
                moveTaskToBack(true);
            }
        });

        // initiate  Help button's on click
        mHelpButton.setOnClickListener(new OnClickListener() {

            @Override
            public void onClick(View v) {
                //send Help Message
                mAWSdServce.mSdData.alarmState = 11;
                mOkTimer = new Timer();
                mOkTimer.schedule(new TurnOffOk(), 1000);
                mAWSdServce.handleSendingHelp();
                //After sending message, Send activity to the background
                moveTaskToBack(true);
            }
        });


    }

    public Activity getActivity(Context context) {
        if (context == null) {
            return null;
        } else if (context instanceof ContextWrapper) {
            if (context instanceof Activity) {
                return (Activity) context;
            } else {
                return getActivity(((ContextWrapper) context).getBaseContext());
            }
        }

        return null;
    }

    public void addListenerOnButton() {


    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    @Override
    protected void onStart() {
        super.onStart();


        if (isSdServiceRunning()) {
            Log.v(TAG, "Service already running - not starting it");
        } else {
            Log.v(TAG, "Service not running - starting it");
            if (checkSelfPermission(Manifest.permission.BODY_SENSORS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.BODY_SENSORS}, 1);
                ActivityCompat.requestPermissions(getActivity(this),
                        new String[]{Manifest.permission.BODY_SENSORS},
                        PERMISSION_REQUEST_BODY_SENSORS);

            } else {
                Log.d(TAG, "ALREADY GRANTED");
            }
            Context context = getApplicationContext();
            Intent intent = new Intent(context, AWSdService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
            //if (mTextView != null) mTextView.setText("Service Started");
            //if (mTextView != null) mTextView.setText("onStart");
            // If the notification supports a direct reply action, use
            // PendingIntent.FLAG_MUTABLE instead.
            Intent notificationIntent = new Intent(this, AWSdService.class);
            PendingIntent pendingIntent =
                    PendingIntent.getActivity(this, 0, notificationIntent,
                            PendingIntent.FLAG_IMMUTABLE);

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                Notification notification =
                        new Notification.Builder(this, CHANNEL_DEFAULT_IMPORTANCE)
                                .setContentTitle(getText(R.string.app_name))
                                .setContentText(getText(R.string.hello_round))
                                .setSmallIcon(R.drawable.icon_24x24)
                                .setContentIntent(pendingIntent)
                                .setTicker(getText(R.string.hello_round))
                                .build();


                // Notification ID cannot be 0.

                context.startForegroundService(notificationIntent);
            }
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
        //TODO: disable update after test
        //mUiTimer.schedule(new UpdateUiTask(),0,500);
    }

    private class TurnOffOk extends TimerTask {
        @Override
        public void run() {
            if (mAWSdServce == null) {
                Log.v(TAG, "Ok Update - service is null");
            } else {
                Log.v(TAG, "Ok Update - back to 0");
                mAWSdServce.mSdData.alarmState = 0;
            }
        }
    }

    private class UpdateUiTask extends TimerTask {
        @Override
        public void run() {
            runOnUiThread(() -> {
                if (mAWSdServce == null) {
                    Log.v(TAG, "UpdateUiTask - service is null");
                    if (mTextView != null) mTextView.setText("NOT CONNECTED");
                } else {
                    Log.v(TAG, "UpdateUiTask() - " + mAWSdServce.mNSamp);
                    if (mTextView != null) mTextView.setText("mNsamp=" + mAWSdServce.mNSamp);
                    if (mAlarmText != null && mAWSdServce.mSdData != null) {
                        if (mAWSdServce.mSdData.alarmState == 2 || mAWSdServce.mSdData.alarmState == 1) {
                            mAlarmText.setVisibility(View.VISIBLE);
                        } else {
                            mAlarmText.setVisibility(View.INVISIBLE);
                        }
                    }

                }
            });
        }
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
