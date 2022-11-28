package uk.org.openseizuredetector;


import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

public class StartUpActivity extends Activity {

    private ServiceConnection mConnection;
    private static final String CHANNEL_DEFAULT_IMPORTANCE = "2";
    private static final String TAG = "StartUpActivity";
    private TextView mTextView;
    private Timer mUiTimer;
    private Timer mOkTimer;
    private ToggleButton toggleButton;
    private TextView mAlarmText;
    private Button mOKButton;
    private Button mHelpButton;
    private static Context mContext = null;
    private static Notification mNotificationBuilder;
    private static final int PERMISSION_REQUEST_BODY_SENSORS = 16;
    private static List<String> channelIDs = new ArrayList<>();
    private AWSdService mAWSdService;
    private Intent notificationIntent = null;

    private static final String returnNewCHANNEL_ID() {
        String currentID = String.valueOf(R.string.app_name) + channelIDs.size();
        channelIDs.add(channelIDs.size(), currentID);
        return currentID;
    }

    private void createNotificationChannel() {
        // Create the NotificationChannel, but only on API 26+ because
        // the NotificationChannel class is new and not in the support library
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CharSequence name = getString(R.string.app_name);
            String description = getString(R.string.app_name);
            int importance = NotificationManager.IMPORTANCE_DEFAULT;
            NotificationChannel channel = new NotificationChannel(returnNewCHANNEL_ID(), name, importance);
            channel.setDescription(description);
            // Register the channel with the system; you can't change the importance
            // or other notification behaviors after this
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
        }
    }

    private void startForegroundServicem(Intent intent) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                mContext.startForegroundService(intent);
            } else { //old pre-O behaviour
                startService(intent);
            }
        } catch (Exception e) {
            Log.e(TAG, "prepareAndStartForeground(): Failed.", e);
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    private void prepareAndStartForeground() {
        try {
            // If the notification supports a direct reply action, use
            // PendingIntent.FLAG_MUTABLE instead.
            mAWSdService = new AWSdService();
            notificationIntent = new Intent(this, AWSdService.class);
            PendingIntent pendingIntent =
                    PendingIntent.getActivity(this, 0, notificationIntent,
                            PendingIntent.FLAG_IMMUTABLE);
            startForegroundServicem(notificationIntent);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                mNotificationBuilder = new Notification.Builder(this, CHANNEL_DEFAULT_IMPORTANCE)
                        .setContentTitle(getText(R.string.app_name))
                        .setContentText(getText(R.string.hello_round))
                        .setSmallIcon(R.drawable.icon_24x24)
                        .setContentIntent(pendingIntent)
                        .setTicker(getText(R.string.hello_round))
                        .build();
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                mAWSdService.startForeground(channelIDs.size(), mNotificationBuilder);

            }
        } catch (Exception e) {
            Log.e(TAG, "prepareAndStartForeground(): Failed.", e);
        }


    }


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_start_up);
        try {
            if (mContext == null) mContext = this;
            if (mAWSdService == null) mAWSdService = new AWSdService();
            if (mConnection == null) mConnection = new Connection();
        } catch (Exception e) {
            Log.v(TAG, "onCreate(): Error in binding Service variable", e);
        }

        if (isSdServiceRunning()) {
            Log.v(TAG, "onCreate(): Service already running - not starting it");
            bindService(notificationIntent, mConnection, Context.BIND_AUTO_CREATE);
        } else {
            Log.v(TAG, "Service not running - starting it");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (checkSelfPermission(Manifest.permission.BODY_SENSORS) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.BODY_SENSORS}, 1);
                    ActivityCompat.requestPermissions(getActivity(this),
                            new String[]{Manifest.permission.BODY_SENSORS},
                            PERMISSION_REQUEST_BODY_SENSORS);

                } else {
                    Log.d(TAG, "ALREADY GRANTED");
                }
            }
            try {


                //if (mTextView != null) mTextView.setText("Service Started");
                //if (mTextView != null) mTextView.setText("onStart");

                createNotificationChannel();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    prepareAndStartForeground();
                }
                // If the notification supports a direct reply action, use
                // PendingIntent.FLAG_MUTABLE instead.

            } catch (Exception e) {
                Log.e(TAG, " OnCreate - Starting Service failed", e);
            }
        }
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
                    mAWSdService.mSdData.alarmState = 6;
                } else {
                    mAWSdService.mSdData.alarmState = 0;
                    //checked is now false, meaning alarms should be on
                }
            }

        });


        // initiate  OK button's on click
        mOKButton.setOnClickListener(new OnClickListener() {

            @Override
            public void onClick(View v) {
                //send OK Message
                mAWSdService.mSdData.alarmState = 10;
                mAWSdService.ClearAlarmCount();
                mOkTimer = new Timer();
                mOkTimer.schedule(new TurnOffOk(), 1000);
                mAWSdService.handleSendingIAmOK();
                //After sending message, Send activity to the background
                moveTaskToBack(true);
            }
        });

        // initiate  Help button's on click
        mHelpButton.setOnClickListener(new OnClickListener() {

            @Override
            public void onClick(View v) {
                //send Help Message
                mAWSdService.mSdData.alarmState = 11;
                mOkTimer = new Timer();
                mOkTimer.schedule(new TurnOffOk(), 1000);
                mAWSdService.handleSendingHelp();
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

        try {
            if (mAWSdService == null) mAWSdService = new AWSdService();
            if (mConnection == null) mConnection = new Connection();
            if (mContext == null) mContext = this;
        } catch (Exception e) {
            Log.v(TAG, "onCreate(): Error in binding Service variable", e);
        }
        if (isSdServiceRunning()) {
            Log.v(TAG, "onCreate(): Service already running - not starting it");
            bindService(notificationIntent, mConnection, Context.BIND_AUTO_CREATE);
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

            //if (mTextView != null) mTextView.setText("Service Started");
            //if (mTextView != null) mTextView.setText("onStart");
            // If the notification supports a direct reply action, use
            // PendingIntent.FLAG_MUTABLE instead.
            createNotificationChannel();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                prepareAndStartForeground();
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
        mUiTimer.schedule(new UpdateUiTask(), 0, 500);
    }

    @Override
    protected void onStop() {
        super.onStop();
        Log.v(TAG, "onStop()");
        // FIXME - THERE IS NO WAY TO STOP THE SERVICE - WE ARE DOING THIS TO STRESS TEST BATTERY CONSUMPTION.
        stopService(new Intent(getBaseContext(), AWSdService.class));
    }

    private class TurnOffOk extends TimerTask {
        @Override
        public void run() {
            if (mAWSdService == null) {
                Log.v(TAG, "Ok Update - service is null");
            } else {
                Log.v(TAG, "Ok Update - back to 0");
                mAWSdService.mSdData.alarmState = 0;
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.i(TAG, "onPause() - unbinding from service");
        unbindService(mConnection);
        mUiTimer.cancel();
    }

    private class UpdateUiTask extends TimerTask {
        @Override
        public void run() {
            runOnUiThread(() -> {
                if (mAWSdService == null) {
                    Log.v(TAG, "UpdateUiTask - service is null");
                    if (mTextView != null) mTextView.setText("NOT CONNECTED");
                } else {
                    Log.v(TAG, "UpdateUiTask() - " + mAWSdService.mNSamp);
                    if (mTextView != null) mTextView.setText("mNsamp=" + mAWSdService.mNSamp);
                    if (mAlarmText != null && mAWSdService.mSdData != null) {
                        if (mAWSdService.mSdData.alarmState == 2 || mAWSdService.mSdData.alarmState == 1) {
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
            mAWSdService = access.getService();
            Log.i(TAG, "onServiceConnected()" + componentName.toShortString());
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            mAWSdService = null;
            Log.i(TAG, "onServiceDisconnected()" + componentName.toShortString());
        }
    }

}
