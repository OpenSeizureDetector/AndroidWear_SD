package uk.org.openseizuredetector;


import android.app.Activity;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.TextView;
import android.widget.ToggleButton;

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
    private static final int PERMISSION_REQUEST_BODY_SENSORS = 16;
    private static Context mContext = null;
    private static Notification mNotificationBuilder;
    private AWSdService mAWSdService;
    private Intent mServiceIntent;


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

    @Override
    protected void onStart() {
        super.onStart();
        //if (mTextView != null) mTextView.setText("onStart");
        if (isSdServiceRunning()) {
            Log.v(TAG, "onCreate(): Service already running - not starting it");
            bindService(mServiceIntent, mConnection, Context.BIND_AUTO_CREATE);
        } else {
            Log.v(TAG, "Service not running - starting it");
            startService(new Intent(getBaseContext(), AWSdService.class));
            //if (mTextView != null) mTextView.setText("Service Started");
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
        stopService(new Intent(getBaseContext(), AWSdService.class));
        super.onDestroy();
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
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
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
                }
            });
        }
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
                            mTextView.setText(new StringBuilder().append(R.string.hello_round).append(": NOT CONNECTED").toString());
                    } else {
                        if (mAWSdService.mSdData == null)
                            mTextView.setText(new StringBuilder().append(R.string.hello_round).append(": NOT CONNECTED").toString());
                        else {
                            //Log.v(TAG, "UpdateUiTask() - " + mAWSdService.mNSamp);
                            if (mTextView != null)
                                mTextView.setText(new StringBuilder().append(R.string.hello_round).append(": mNsamp=").append(mAWSdService.mNSamp).toString());
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
