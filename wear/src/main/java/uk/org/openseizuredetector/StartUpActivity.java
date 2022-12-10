package uk.org.openseizuredetector;


import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.Notification;
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

    private ServiceConnection mConnection;
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
    private AWSdService mAWSdService;
    private Intent mServiceIntent;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_start_up);
        try {
            if (mContext == null) mContext = this;
            if (mAWSdService == null) mAWSdService = new AWSdService();
            if (mConnection == null) mConnection = new Connection(mContext);
            if (mServiceIntent == null) mServiceIntent = new Intent(mContext, AWSdService.class);

        } catch (Exception e) {
            Log.v(TAG, "onCreate(): Error in binding Service variable", e);
        }

        if (isSdServiceRunning()) {
            Log.v(TAG, "onCreate(): Service already running - not starting it");
            bindService(mServiceIntent, mConnection, Context.BIND_AUTO_CREATE);
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
            Object result = bindService(mServiceIntent, mConnection, Context.BIND_AUTO_CREATE);
            Log.v(TAG, "OnCreate(): result of result(bindService) " + result);
            Log.v(TAG, "OnCreate(): result of mAWSdService: mSdData: " + mAWSdService.mSdData);

        }
        // final WatchViewStub stub = (WatchViewStub) findViewById(R.id.watch_view_stub);
        // stub.setOnLayoutInflatedListener(new WatchViewStub.OnLayoutInflatedListener() {
        //     @Override
        //    public void onLayoutInflated(WatchViewStub stub) {
        //        mTextView = (TextView) stub.findViewById(R.id.startUpStatusTv);
        //    }
        // });
        mTextView = (TextView) findViewById(R.id.text);
        toggleButton = (ToggleButton) findViewById(R.id.toggleButton1);
        mAlarmText = (TextView) findViewById(R.id.text1);
        mOKButton = (Button) findViewById(R.id.button);
        mHelpButton = (Button) findViewById(R.id.button1);


        // initiate toggle button's on click
        toggleButton.setOnClickListener(new OnClickListener() {

            @Override
            public void onClick(View v) {
                try {
                    if (mAWSdService.mSdData != null) {
                        if (toggleButton.isChecked()) {
                            //checked is now true, meaning alarms should be off
                            mAWSdService.mSdData.alarmState = 6;
                        } else {
                            mAWSdService.mSdData.alarmState = 0;
                            //checked is now false, meaning alarms should be on
                        }
                    } else {
                        Log.e(TAG, "OnClick() illegal access of mAWSdService.mSdData", new Throwable());
                    }
                } catch (Exception e) {
                    Log.e(TAG, "OnClick() illegal access of mAWSdService.mSdData", e);
                }
            }

        });


        // initiate  OK button's on click
        mOKButton.setOnClickListener(new OnClickListener() {

            @Override
            public void onClick(View v) {

                try {
                    if (mAWSdService.mSdData != null) {
                        //send OK Message
                        mAWSdService.mSdData.alarmState = 10;
                        mAWSdService.ClearAlarmCount();
                        mOkTimer = new Timer();
                        mOkTimer.schedule(new TurnOffOk(), 1000);
                        mAWSdService.handleSendingIAmOK();
                        //After sending message, Send activity to the background
                        moveTaskToBack(true);

                    } else {
                        Log.e(TAG, "OnClick() illegal access of mAWSdService.mSdData", new Throwable());
                    }
                } catch (Exception e) {
                    Log.e(TAG, "OnClick() illegal access of mAWSdService.mSdData", e);
                }
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
            if (mContext == null) mContext = this;
            if (mAWSdService == null) mAWSdService = new AWSdService();
            if (mConnection == null) mConnection = new Connection(mContext);
            if (mServiceIntent == null) mServiceIntent = new Intent(mContext, AWSdService.class);

        } catch (Exception e) {
            Log.v(TAG, "onCreate(): Error in binding Service variable", e);
        }
        if (isSdServiceRunning()) {
            Log.v(TAG, "onCreate(): Service already running - not starting it");
            bindService(mServiceIntent, mConnection, Context.BIND_AUTO_CREATE);
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

            if (mTextView != null)
                mTextView.setText(new StringBuilder().append(getResources().getString(R.string.hello_round)).append(": Service Started").toString());
            if (mTextView != null)
                mTextView.setText(new StringBuilder().append(getResources().getString(R.string.hello_round)).append(": onStart").toString());
            try {
                bindService(mServiceIntent, mConnection, Context.BIND_AUTO_CREATE);
                if (mTextView != null)
                    mTextView.setText(new StringBuilder().append(getResources().getString(R.string.hello_round)).append(": Bound to AWSdService").toString());
            } catch (Exception e) {
                Log.e(TAG, "onStart(): Failed to bind to service: ", e);
                if (mTextView != null)
                    mTextView.setText(new StringBuilder().append(getResources().getString(R.string.hello_round)).append(": Failed to bind to AWSdService").toString());
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.i(TAG, "onResume() - binding to Service");
        bindService(
                new Intent(this, AWSdService.class),
                mConnection = new Connection(mContext),
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

    }

    @Override
    protected void onDestroy() {
        //stopService(new Intent(getBaseContext(), AWSdService.class));
        super.onDestroy();
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.i(TAG, "onPause() - unbinding from service");
        unbindService(mConnection);
        mUiTimer.cancel();
    }

    private boolean isSdServiceRunning() {
        ActivityManager manager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            //Log.v(TAG,"isSdServiceRunning() - "+service.service.getClassName());
            if ("uk.org.openseizuredetector.AWSdService".equals(service.service.getClassName())) {
                Log.v(TAG, "isSdServiceRunning() - returning true");
                return true;
            }
        }
        Log.v(TAG, "isSdServiceRunning() - returning false");
        return false;
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

    private class UpdateUiTask extends TimerTask {
        @Override
        public void run() {
            runOnUiThread(() -> {
                try {

                    if (mAWSdService == null) {
                        Log.v(TAG, "UpdateUiTask - service is null");
                        if (mTextView != null)
                            mTextView.setText(new StringBuilder().append(getResources().getString(R.string.hello_round)).append(": mAWSdService not created").toString());
                    } else {
                        if (mAWSdService.mSdData == null)
                            mTextView.setText(new StringBuilder().append(getResources().getString(R.string.hello_round)).append(": mAWSdService created, but mSdData NOT").toString());
                        else {
                            //Log.v(TAG, "UpdateUiTask() - " + mAWSdService.mNSamp);
                            if (mTextView != null)
                                mTextView.setText(new StringBuilder().append(getResources().getString(R.string.hello_round))
                                        .append(": mNsamp=")
                                        .append(mAWSdService.mNSamp)
                                        .append(" Status of server: ")
                                        .append(mAWSdService.mSdData.serverOK).toString());

                            if (mAlarmText != null && mAWSdService.mSdData != null) {
                                if (mAWSdService.mSdData.alarmState == 2 || mAWSdService.mSdData.alarmState == 1) {
                                    mAlarmText.setVisibility(View.VISIBLE);
                                } else {
                                    mAlarmText.setVisibility(View.INVISIBLE);
                                }
                            }
                        }
                    }


                } catch (Exception e) {
                    Log.e(TAG, "UpdateUiTask() - runOnUiThread(): ", e);
                }

            });
        }
    }

    private class Connection implements ServiceConnection {
        public Connection(Context context) {
            mContext = context;
        }

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
