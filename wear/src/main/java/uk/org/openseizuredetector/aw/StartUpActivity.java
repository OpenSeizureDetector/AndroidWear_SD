package uk.org.openseizuredetector.aw;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.Notification;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.preference.PreferenceManager;
import androidx.wear.ambient.AmbientModeSupport;
import androidx.wear.remote.interactions.RemoteActivityHelper;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import java.util.Calendar;
import java.util.List;
import java.util.Objects;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;
import java.util.concurrent.Executors;

import io.reactivex.rxjava3.disposables.Disposable;
import uk.org.openseizuredetector.SdData;

/**
 * with modifications of : https://developer.android.com/guide/background/persistent/how-to/long-running
 */
public class StartUpActivity extends AppCompatActivity
        implements AmbientModeSupport.AmbientCallbackProvider {

    private static final String TAG = "StartUpActivity";
    private static final int PERMISSION_REQUEST_BODY_SENSORS = 16;
    private static Context mContext = null;
    private static Notification mNotificationBuilder;
    private SdServiceConnection mConnection;
    private TextView mTextView;
    private Timer mUiTimer;
    private Timer mOkTimer;
    private ToggleButton toggleButton;
    private TextView mAlarmText;
    private Button mOKButton;
    private Button mHelpButton;
    private AWSdService mAWSdService;
    private Service aWSdService;
    private Intent mServiceIntent;
    private StringBuilder textViewBuilder;
    List<WorkInfo> runningWorkers = null;
    private OsdUtil mUtil;
    private SharedPreferences SP = null;
    private boolean activateStopByBack = false;
    long lastPress;
    Toast backpressToast;
    private WorkManager workManager;
    private SdData mSdData = null;
    private OneTimeWorkRequest workRequest = null;
    private String mMobileNodeUri = null;
    private Handler mHandler = new Handler(Looper.getMainLooper());
    private UUID currentUuID;
    private Constraints defaultConstraints = null;
    private Disposable disposable;

    public static <T extends Parcelable> T unmarshall(byte[] bytes, Parcelable.Creator<T> creator) {
        Parcel parcel = unmarshall(bytes);
        return creator.createFromParcel(parcel);
    }

    public static Parcel unmarshall(byte[] bytes) {
        Parcel parcel = Parcel.obtain();
        parcel.unmarshall(bytes, 0, bytes.length);
        parcel.setDataPosition(0); // this is extremely important!
        return parcel;
    }

    public Activity mGetActivity(Context context) {
        if (context == null) {
            return null;
        } else if (context instanceof ContextWrapper) {
            if (context instanceof Activity) {
                return (Activity) context;
            } else {
                return mGetActivity(((ContextWrapper) context).getBaseContext());
            }
        }

        return null;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_start_up);
        try {
            if (mContext == null) mContext = this;
            if (mUtil == null) mUtil = new OsdUtil(this, mHandler);
            //if (mConnection.mAWSdService == null) mConnection.mAWSdService = new AWSdService();
            //if (mServiceIntent == null) mServiceIntent = new Intent(mContext, AWSdService.class);

        } catch (Exception e) {
            Log.v(TAG, "onCreate(): Error in binding Service variable", e);
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
                    if (mConnection.mAWSdService.mSdData != null) {
                        if (toggleButton.isChecked()) {
                            //checked is now true, meaning alarms should be off
                            mConnection.mAWSdService.mSdData.alarmState = Constants.GLOBAL_CONSTANTS.ALARMS_OFF;
                        } else {
                            mConnection.mAWSdService.mSdData.alarmState = Constants.GLOBAL_CONSTANTS.ALARMS_ON;
                            //checked is now false, meaning alarms should be on
                        }
                    } else {
                        Log.e(TAG, "OnClick() illegal access ofmConnection.mAWSdService.mSdData", new Throwable());
                    }
                } catch (Exception e) {
                    Log.e(TAG, "OnClick() illegal access ofmConnection.mAWSdService.mSdData", e);
                }
            }

        });


        // initiate  OK button's on click
        mOKButton.setOnClickListener(new OnClickListener() {

            @Override
            public void onClick(View v) {

                try {
                    if (mConnection.mAWSdService.mSdData != null) {
                        //send OK Message
                        mConnection.mAWSdService.mSdData.alarmState = 10;
                        mConnection.mAWSdService.ClearAlarmCount();
                        mOkTimer = new Timer();
                        mOkTimer.schedule(new TurnOffOk(), 1000);
                        mConnection.mAWSdService.handleSendingIAmOK();
                        //After sending message, Send activity to the background
                        moveTaskToBack(true);

                    } else {
                        Log.e(TAG, "OnClick() illegal access ofmConnection.mAWSdService.mSdData", new Throwable());
                    }
                } catch (Exception e) {
                    Log.e(TAG, "OnClick() illegal access ofmConnection.mAWSdService.mSdData", e);
                }
            }
        });

        // initiate  Help button's on click
        mHelpButton.setOnClickListener(new OnClickListener() {

            @Override
            public void onClick(View v) {
                //send Help Message
                mConnection.mAWSdService.mSdData.alarmState = 11;
                mOkTimer = new Timer();
                mOkTimer.schedule(new TurnOffOk(), 1000);
                mConnection.mAWSdService.handleSendingHelp();
                //After sending message, Send activity to the background
                moveTaskToBack(true);
            }
        });

        /*
         * Declare an ambient mode controller, which will be used by
         * the activity to determine if the current mode is ambient.
         */
        AmbientModeSupport.AmbientController ambientController = AmbientModeSupport.attach(this);


        if (mConnection == null) mConnection = new SdServiceConnection(this);
    }

    private void onChangedObserver(@Nullable WorkInfo workInfo) {
        if (workInfo != null) {
            Log.d("oneTimeWorkRequest", "Status changed to : " + workInfo.getState());
            Data progress = workInfo.getProgress();

            if (Objects.nonNull(progress)) {
                byte[] bytes = progress.getByteArray(Constants.GLOBAL_CONSTANTS.mSdDataPath);
                if (Objects.nonNull(bytes)) {
                    Parcel parcel = Parcel.obtain();
                    parcel.unmarshall(bytes, 0, bytes.length);
                    parcel.setDataPosition(0);
                    mSdData = unmarshall(bytes, SdData.CREATOR);
                    runOnUiThread(() -> {
                        try {
                            textViewBuilder = new StringBuilder();

                            if (mConnection.mAWSdService == null) {
                                Log.v(TAG, "updateUiTask - service is null");
                                if (mTextView != null)
                                    mTextView.setText(textViewBuilder.append(getResources().getString(R.string.hello_round)).append(": mAWSdService not created").toString());
                            } else {
                                if (mSdData == null)
                                    mTextView.setText(textViewBuilder.append(getResources().getString(R.string.hello_round)).append(": mAWSdService created, but mSdData NOT").toString());
                                else {
                                    //Log.v(TAG, "updateUiTask() - " +mConnection.mAWSdService.mNSamp);
                                    if (mTextView != null)
                                        mTextView.setText(textViewBuilder
                                                .append(getResources().getString(R.string.hello_round))
                                                .append(" Connected: ")
                                                .append(mSdData.serverOK)
                                                .append(" time: ")
                                                .append(Calendar.getInstance().getTime())
                                                .append(" ❤️ ")
                                                .append((short) mSdData.mHR)
                                                .append(" \uD83D\uDD0B% ")
                                                .append(mSdData.batteryPc)
                                                .toString());

                                    if (mAlarmText != null && mSdData != null) {
                                        if (mSdData.alarmState == 2 || mSdData.alarmState == 1) {
                                            mAlarmText.setVisibility(View.VISIBLE);
                                        } else {
                                            mAlarmText.setVisibility(View.INVISIBLE);
                                        }
                                    }
                                }
                            }


                        } catch (Exception e) {
                            Log.e(TAG, "updateUiTask() - runOnUiThread(): ", e);
                        }

                    });

                }

            }
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    @Override
    protected void onStart() {
        super.onStart();

        try {

            if (checkSelfPermission(Manifest.permission.BODY_SENSORS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.BODY_SENSORS}, 1);
                ActivityCompat.requestPermissions(mGetActivity(this),
                        new String[]{Manifest.permission.BODY_SENSORS},
                        PERMISSION_REQUEST_BODY_SENSORS);

            } else {
                Log.d(TAG, "ALREADY GRANTED");
            }

            if (!mUtil.isServerRunning()) {
                Log.i(TAG, "onStart() - server not running - isServerRunning=" + mUtil.isServerRunning());
                // Wait 0.1 second to give the server chance to shutdown in case we have just shut it down below, then start it

                Log.i(TAG, "onStart() - starting server after delay -isServerRunning=" + mUtil.isServerRunning());
                mUtil.startServer();
                // Bind to the service.
                Log.i(TAG, "onStart() - binding to server");
                RemoteActivityHelper remoteActivityHelper = new RemoteActivityHelper(this, Executors.newSingleThreadExecutor());
                remoteActivityHelper.startRemoteActivity(new Intent(Constants.GLOBAL_CONSTANTS.mAppPackageName), mMobileNodeUri);
                if (mTextView != null)
                    mTextView.setText(new StringBuilder().append(getResources().getString(R.string.hello_round)).append(": Service Started").toString());
                if (mTextView != null)
                    mTextView.setText(new StringBuilder().append(getResources().getString(R.string.hello_round)).append(": onStart").toString());
            }

            try {
                // Start the server
                Log.d(TAG, "OsdUtil.startServer()");
                    /*mServiceIntent = new Intent(mContext, AWSdService.class);
                    mServiceIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
                    mServiceIntent.setData(Uri.parse("Start"));
                    mServiceIntent.setAction(Constants.ACTION.STARTFOREGROUND_ACTION);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        Log.i(TAG, "Starting Foreground Service (Android 8 and above)");
                        mContext.startForegroundService(mServiceIntent);
                    } else {
                        Log.i(TAG, "Starting Normal Service (Pre-Android 8)");
                        mContext.startService(mServiceIntent);
                    }*/


                if (Objects.isNull(mConnection)) mConnection = new SdServiceConnection(this);

                if (Objects.nonNull(mConnection))
                    if (!mConnection.mBound) mUtil.bindToServer(this, mConnection);


                if (mTextView != null)
                    mTextView.setText(new StringBuilder().append(getResources().getString(R.string.hello_round)).append(": Bound to AWSdService").toString());
            } catch (Exception e) {
                Log.e(TAG, "onStart(): Failed to bind to service: ", e);
                if (mTextView != null)
                    mTextView.setText(new StringBuilder().append(getResources().getString(R.string.hello_round)).append(": Failed to bind to AWSdService").toString());
            }
            if (mConnection.mAWSdService != null) {
                if (mConnection.mAWSdService.mSdData != null) {

                    if (!mConnection.mAWSdService.mSdData.serverOK) {
                        Log.e(TAG, "onStart(): no initialised server");
                        mConnection.mAWSdService.requestCreateNewChannelAndInit = true;
                    } else {
                        Log.d(TAG, "onStart(): initialised server");
                        mConnection.mAWSdService.requestCreateNewChannelAndInit = true;
                        return;
                    }
                }
            }
            mHandler.postDelayed(() -> {
                Log.d(TAG, "running bindRetry in postDelayed");
                bindRetry();
            }, 100);
            //if (mContext == null) mContext = this;
            //if (mConnection == null) mConnection = new SdServiceConnection(mContext);
            //if (mConnection.mAWSdService == null) mConnection.mAWSdService = new AWSdService();


        } catch (Exception e) {
            Log.v(TAG, "onCreate(): Error in binding Service variable", e);
        }


    }

    /**
     * onChangedObserver is responsible for handling LiveData changed event
     * (this.postValue(mSdData)
     * result here is (SdData) from Object o.
     * Source event line: AWSdService:ServiceLiveData:signalChangedData()
     */
    private void onChangedObserver(Object o) {
        try {
            mSdData = (SdData) o;
            updateUiTask();
        } catch (Exception e) {
            Log.e(getClass().getName(), "onChangedObserver: error: ", e);
        }
    }


    private void setPreferredNodeRunner() {
        SP = PreferenceManager
                .getDefaultSharedPreferences(mContext);
        mConnection.mAWSdService.mMobileNodeUri = SP.getString(Constants.GLOBAL_CONSTANTS.intentAction, "");
        if (mConnection.mAWSdService.mMobileNodeUri.equalsIgnoreCase("")) {
            mHandler.postDelayed(() -> {
                mConnection.mAWSdService.setReceiverAndUpdateMobileUri();
            }, 300);

        }

    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.i(TAG, "onResume() - recreating defaults if needed");

        if (!mUtil.isServerRunning())
            mUtil.startServer();

        if (mConnection == null) mConnection = new SdServiceConnection(this);

        if (!mConnection.mBound) mUtil.bindToServer(this, mConnection);
        mHandler.postDelayed(this::bindRetry, 100);
        //mUiTimer = new Timer();
        //TODO: disable update after test
        //mUiTimer.schedule(new updateUiTask(), 0, 500);


    }


    @Override
    public void onBackPressed() {
        try {
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastPress > 5000) {
                backpressToast = Toast.makeText(getBaseContext(), "Press back again to exit", Toast.LENGTH_LONG);
                backpressToast.show();
                lastPress = currentTime;
            } else {
                Log.d(TAG, "onBackPressed: initiating shutdown");
                if (backpressToast != null) backpressToast.cancel();
                activateStopByBack = true;
                if (Objects.nonNull(mConnection))
                    if (mConnection.mBound)
                        mUtil.unbindFromServer(this, mConnection);
                mUtil.stopServer();
                finishAffinity();
                super.onBackPressed();
            }
        } catch (Exception e) {
            Log.e(TAG, "onBackPressed() Error thrown while processing.");
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        Log.v(TAG, "onStop()");
        if (mConnection != null) {
            if (mConnection.mAWSdService != null) {
                if (mConnection.mBound) {
                    if (mConnection.mAWSdService.serviceLiveData.hasActiveObservers()) {
                        mConnection.mAWSdService.serviceLiveData.removeObserver(this::onChangedObserver);
                    }
                    mUtil.unbindFromServer(this, mConnection);

                }


                //mUiTimer.cancel();
                // FIXME - THERE IS NO WAY TO STOP THE SERVICE - WE ARE DOING THIS TO STRESS TEST BATTERY CONSUMPTION.

            }
            if (Objects.nonNull(mConnection.mAWSdService)) {
                if (Objects.nonNull(mConnection.mAWSdService.mSdData)) {
                    if (Constants.ACTION.STOP_WEAR_SD_ACTION.equals(mConnection.mAWSdService.mSdData.mDataType) || activateStopByBack) {
                        mUtil.stopServer();
                    }
                }
            }

        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mConnection != null)
            if (mConnection.mAWSdService != null)
                if (mConnection.mBound) {
                    if (mConnection.mAWSdService.serviceLiveData.hasActiveObservers())
                        mConnection.mAWSdService.serviceLiveData.removeObserver(this::onChangedObserver);
                    mUtil.unbindFromServer(this, mConnection);
                    if (Constants.ACTION.STOP_WEAR_SD_ACTION.equals(mConnection.mAWSdService.mSdData.mDataType) || activateStopByBack) {
                        mUtil.stopServer();
                        activateStopByBack = false;
                    }
                }

        if (Objects.nonNull(mUiTimer)) mUiTimer.cancel();
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.i(TAG, "onPause() - unbinding from service");
        if (Objects.nonNull(mConnection)) {
            if (Objects.nonNull(mConnection.mAWSdService)) {
                if (mConnection.mBound)
                    if (mConnection.mAWSdService.mBound) {
                        mConnection.mAWSdService.parentContext = null;
                        if (Objects.nonNull(mConnection.mAWSdService.mMobileNodeUri)) {
                            if (Objects.isNull(SP))
                                SP = PreferenceManager
                                        .getDefaultSharedPreferences(this);

                            SharedPreferences.Editor editor = SP.edit();
                            editor.putString(Constants.GLOBAL_CONSTANTS.intentReceiver, mConnection.mAWSdService.mMobileNodeUri);
                            editor.apply();
                        }
                    }
                if (Objects.nonNull(mConnection.mAWSdService.serviceLiveData))
                    if (mConnection.mAWSdService.serviceLiveData.hasActiveObservers())
                        mConnection.mAWSdService.serviceLiveData.removeObserver(this::onChangedObserver);
            }
        }
        mUtil.unbindFromServer(this, mConnection);
        mConnection = null;

        if (Objects.nonNull(mUiTimer)) mUiTimer.cancel();
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

    private void bindRetry() {
        if (Objects.nonNull(mConnection))
            if (Objects.nonNull(mConnection.mAWSdService))
                if (mConnection.mBound) {
                    mConnection.mAWSdService.parentContext = mContext;
                    if (!mConnection.mAWSdService.serviceLiveData.hasActiveObservers())
                        try {
                            mConnection.mAWSdService.serviceLiveData.observe(this, this::onChangedObserver);
                        } catch (IllegalArgumentException illegalArgumentException) {
                            Log.e(TAG, "bindRetry() error: ", illegalArgumentException);
                        }
                    return;
                }
        if (!(isFinishing()
                && isDestroyed()))
            mHandler.postDelayed(
                    () -> bindRetry(), 100);
    }

    /**
     * @return the {@link AmbientModeSupport.AmbientCallback} to be used by this class to communicate with the
     * entity interested in ambient events.
     */
    @Override
    public AmbientModeSupport.AmbientCallback getAmbientCallback() {
        return new MyAmbientCallback();
    }

    private static class MyAmbientCallback extends AmbientModeSupport.AmbientCallback {
        @Override
        public void onEnterAmbient(Bundle ambientDetails) {
            // Handle entering ambient mode
            Log.d(TAG, "onEnterAmbient();");
        }

        @Override
        public void onExitAmbient() {
            // Handle exiting ambient mode
            Log.d(TAG, "onExitAmbient(): Exiting ambient mode");
        }

        @Override
        public void onUpdateAmbient() {
            // Update the content
            Log.d(TAG, "onUpdateAmbient(): Updating ambient");
        }
    }

    private class TurnOffOk extends TimerTask {
        @Override
        public void run() {
            if (mAWSdService == null) {
                Log.v(TAG, "Ok Update - service is null");
            } else {
                Log.v(TAG, "Ok Update - back to 0");
                mConnection.mAWSdService.mSdData.alarmState = 0;
            }
        }
    }

    public void updateUiTask() {
        Log.d(TAG, "firing runOnUiThread");
        runOnUiThread(() -> {
            try {
                textViewBuilder = new StringBuilder();

                if (mConnection.mAWSdService == null) {
                    Log.v(TAG, "updateUiTask - service is null");
                    if (mTextView != null)
                        mTextView.setText(textViewBuilder.append(getResources().getString(R.string.hello_round)).append(": mAWSdService not created").toString());
                } else {
                    if (mConnection.mAWSdService.mSdData == null)
                        mTextView.setText(textViewBuilder.append(getResources().getString(R.string.hello_round)).append(": mAWSdService created, but mSdData NOT").toString());
                    else {
                        //Log.v(TAG, "updateUiTask() - " +mConnection.mAWSdService.mNSamp);
                        if (mTextView != null)
                            mTextView.setText(textViewBuilder.append(getResources().getString(R.string.hello_round))
                                    .append(" Connected: ")
                                    .append(mConnection.mAWSdService.mSdData.serverOK)
                                    .append(" time: ")
                                    .append(Calendar.getInstance().getTime())
                                    .append(" ❤️ ")
                                    .append((short) mConnection.mAWSdService.mSdData.mHR)
                                    .append(" \uD83D\uDD0B% ")
                                    .append(mConnection.mAWSdService.mSdData.batteryPc)
                                    .toString());

                        if (mAlarmText != null && mConnection.mAWSdService.mSdData != null) {
                            if (mConnection.mAWSdService.mSdData.alarmState == 2 || mConnection.mAWSdService.mSdData.alarmState == 1) {
                                mAlarmText.setVisibility(View.VISIBLE);
                            } else {
                                mAlarmText.setVisibility(View.INVISIBLE);
                            }
                        }
                    }
                }


            } catch (Exception e) {
                Log.e(TAG, "updateUiTask() - runOnUiThread(): ", e);
            }

        });

    }

    class Connection implements ServiceConnection {
        public Connection(Context context) {
            mContext = context;
        }

        @Override
        public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
            AWSdService.Access access = ((AWSdService.Access) iBinder);
            mConnection.mAWSdService.parentConnection = new Connection(mContext);
            aWSdService = access.getService();
            Log.d(TAG, "mAWSdService= : " + mConnection.mAWSdService + " aWSdService = : " + aWSdService +
                    " result in compare: " + Objects.equals(aWSdService, mConnection.mAWSdService));
            Log.i(TAG, "onServiceConnected()" + componentName.toShortString());
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            mConnection.mAWSdService.onUnbind(mServiceIntent);

            if (!isSdServiceRunning()) {
                //mAWSdService = null;
            }
            Log.i(TAG, "onServiceDisconnected()" + componentName.toShortString());
        }
    }


}
