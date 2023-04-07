/*
  Pebble_sd - a simple accelerometer based seizure detector that runs on a
  Pebble smart watch (http://getpebble.com).

  See http://openseizuredetector.org for more information.

  Copyright Graham Jones, 2015.

  This file is part of pebble_sd.

  Pebble_sd is free software: you can redistribute it and/or modify
  it under the terms of the GNU General Public License as published by
  the Free Software Foundation, either version 3 of the License, or
  (at your option) any later version.

  Pebble_sd is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
  GNU General Public License for more details.

  You should have received a copy of the GNU General Public License
  along with pebble_sd.  If not, see <http://www.gnu.org/licenses/>.

*/
package uk.org.openseizuredetector.aw;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Enumeration;

/**
 * OsdUtil - OpenSeizureDetector Utilities
 * Deals with starting and stopping the background service and binding to it to receive data.
 */
public class OsdUtil {
    public final static String PRIVACY_POLICY_URL = "https://www.openseizuredetector.org.uk/?page_id=1415";
    public final static String DATA_SHARING_URL = "https://www.openseizuredetector.org.uk/?page_id=1818";
    private static final String mSysLogTableName = "SysLog";
    private final static Long mMinPruneInterval = (long) (5 * 60 * 1000); // minimum time between syslog pruning is 5 minutes
    /**
     * Based on http://stackoverflow.com/questions/7440473/android-how-to-check-if-the-intent-service-is-still-running-or-has-stopped-running
     */
    private static Context mContext;
    private static String TAG = "OsdUtil";
    //private LogManager mLm;
    static private SQLiteDatabase mSysLogDb = null;   // SQLite Database for data and log entries.
    private static Long mLastPruneMillis = 0L;   // Record of the last time we pruned the syslog db.
    private static int mNbound = 0;
    public final int ALARM_STATUS_WARNING = 1;
    public final int ALARM_STATUS_ALARM = 2;
    public final int ALARM_STATUS_FALL = 3;
    public final int ALARM_STATUS_MANUAL = 5;
    private final String SYSLOG = "SysLog";
    private Handler mHandler;
    private boolean mLogAlarms = true;
    private boolean mLogData = true;
    private boolean mPermissionsRequested = false;
    private boolean mSMSPermissionsRequested = false;

    public OsdUtil(Context context, Handler handler) {
        mContext = context;
        mHandler = handler;
        //Log.i(TAG,"Creating Log Manager instance");
        //mLm = new LogManager(mContext,false,false,null,0,0,false,0);

    }

    /**
     * used to make sure timers etc. run on UI thread
     */
    public void runOnUiThread(Runnable runnable) {
        mHandler.post(runnable);
    }

    public boolean isServerRunning() {
        int nServers = 0;
        /* Log.v(TAG,"isServerRunning()...."); */
        ActivityManager manager =
                (ActivityManager) mContext.getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningServiceInfo service :
                manager.getRunningServices(Integer.MAX_VALUE)) {
            //Log.v(TAG,"Service: "+service.service.getClassName());
            if (Constants.GLOBAL_CONSTANTS.mServiceWearSdName
                    .equals(service.service.getClassName())) {
                nServers = nServers + 1;
            }
        }
        //Log.v(TAG, "isServerRunning() - " + nServers + " instances are running");
        return nServers != 0;
    }

    /**
     * Start the SdServer service
     */
    public void startServer() {
        // Start the server
        Log.d(TAG, "OsdUtil.startServer()");
        Intent mServiceIntent;
        mServiceIntent = new Intent(mContext, AWSdService.class);
        mServiceIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        mServiceIntent.setData(Uri.parse("Start"));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Log.i(TAG, "Starting Foreground Service (Android 8 and above)");
            mContext.startForegroundService(mServiceIntent);
        } else {
            Log.i(TAG, "Starting Normal Service (Pre-Android 8)");
            mContext.startService(mServiceIntent);
        }
    }

    /**
     * Stop the SdServer service
     */
    public void stopServer() {
        Log.i(TAG, "OsdUtil.stopServer() - stopping Server... - mNbound=" + mNbound);

        // then send an Intent to stop the service.
        Intent mServiceIntent;
        mServiceIntent = new Intent(mContext, AWSdService.class);
        mServiceIntent.setData(Uri.parse("Stop"));
        mServiceIntent.setAction(Constants.ACTION.STOPFOREGROUND_ACTION);
        mContext.stopService(mServiceIntent);
    }

    /**
     * bind an activity to to an already running server.
     */
    public void bindToServer(Context activity, SdServiceConnection sdServiceConnection) {
        Log.i(TAG, "OsdUtil.bindToServer() - binding to SdServer");
        Intent intent = new Intent(mContext, AWSdService.class);
        intent.setAction(Constants.ACTION.BIND_ACTION);
        activity.bindService(intent, sdServiceConnection, Context.BIND_AUTO_CREATE);
        mNbound = mNbound + 1;
        Log.i(TAG, "OsdUtil.bindToServer() - mNbound = " + mNbound);
    }

    /**
     * unbind an activity from server
     */
    public void unbindFromServer(Context activity, SdServiceConnection sdServiceConnection) {
        // unbind this activity from the service if it is bound.
        if (sdServiceConnection.mBound) {
            Log.i(TAG, "unbindFromServer() - unbinding");
            try {
                activity.unbindService(sdServiceConnection);
                sdServiceConnection.mBound = false;
                mNbound = mNbound - 1;
                Log.i(TAG, "OsdUtil.unBindFromServer() - mNbound = " + mNbound);
            } catch (Exception ex) {
                Log.e(TAG, "unbindFromServer() - error unbinding service - " + ex.toString(), ex);
                Log.i(TAG, "OsdUtil.unBindFromServer() - mNbound = " + mNbound);
            }
        } else {
            Log.i(TAG, "unbindFromServer() - not bound to server - ignoring");
            Log.i(TAG, "OsdUtil.unBindFromServer() - mNbound = " + mNbound);
        }
    }

    public String getAppVersionName() {
        String versionName = "unknown";
        // From http://stackoverflow.com/questions/4471025/
        //         how-can-you-get-the-manifest-version-number-
        //         from-the-apps-layout-xml-variable
        final PackageManager packageManager = mContext.getPackageManager();
        if (packageManager != null) {
            try {
                PackageInfo packageInfo = packageManager.getPackageInfo(mContext.getPackageName(), 0);
                versionName = packageInfo.versionName;
            } catch (PackageManager.NameNotFoundException e) {
                Log.v(TAG, "failed to find versionName");
                versionName = null;
            }
        }
        return versionName;
    }

    /**
     * get the ip address of the phone.
     * Based on http://stackoverflow.com/questions/11015912/how-do-i-get-ip-address-in-ipv4-format
     */
    public String getLocalIpAddress() {
        try {
            for (Enumeration<NetworkInterface> en = NetworkInterface
                    .getNetworkInterfaces(); en.hasMoreElements(); ) {
                NetworkInterface intf = en.nextElement();
                for (Enumeration<InetAddress> enumIpAddr = intf
                        .getInetAddresses(); enumIpAddr.hasMoreElements(); ) {
                    InetAddress inetAddress = enumIpAddr.nextElement();
                    //Log.v(TAG,"ip1--:" + inetAddress);
                    //Log.v(TAG,"ip2--:" + inetAddress.getHostAddress());

                    // for getting IPV4 format
                    if (!inetAddress.isLoopbackAddress()
                            && inetAddress instanceof Inet4Address
                    ) {

                        String ip = inetAddress.getHostAddress();
                        //Log.v(TAG,"ip---::" + ip);
                        return ip;
                    }
                }
            }
        } catch (Exception ex) {
            Log.e("IP Address", ex.toString());
        }
        return null;
    }

    /**
     * Display a Toast message on screen.
     *
     * @param msg - message to display.
     */
    public void showToast(final String msg) {
        runOnUiThread(() -> Toast.makeText(mContext, msg,
                Toast.LENGTH_LONG).show());
    }

    public File[] getDataFilesList() {
        File[] files = getDataStorageDir().listFiles();
        Log.d("Files", "Size: " + files.length);
        for (int i = 0; i < files.length; i++) {
            Log.d("Files", "FileName:" + files[i].getName());
        }
        return (files);
    }

    /* Checks if external storage is available for read and write */
    public boolean isExternalStorageWritable() {
        String state = Environment.getExternalStorageState();
        if (Environment.MEDIA_MOUNTED.equals(state)) {
            return true;
        }
        return false;
    }

    public File getDataStorageDir() {
        // Get the directory for the user's public directory.
        File file = mContext.getExternalFilesDir(null);
        return file;
    }

    public String getPreferredPebbleAppPackageName() {
        // returns the package name of the preferred Android Pebble App.
        return "com.getpebble.android.basalt";
    }

    public String isPebbleAppInstalled() {
        // Returns the package name of the installed pebble App or null if it is not installed
        String pkgName;
        pkgName = "com.getpebble.android";
        if (isPackageInstalled(pkgName)) return pkgName;
        pkgName = "com.getpebble.android.basalt";
        if (isPackageInstalled(pkgName)) return pkgName;
        return null;
    }

    public boolean isPackageInstalled(String packagename) {
        PackageManager pm = mContext.getPackageManager();
        try {
            pm.getPackageInfo(packagename, PackageManager.GET_ACTIVITIES);
            //showToast("found "+packagename);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            //showToast(packagename + " not found");
            return false;
        }
    }

    /**
     * string2date - returns a Date object represented by string dateStr
     * It first attempts to parse it as a long integer, in which case it is assumed to
     * be a unix timestamp.
     * If that fails it attempts to parse it as yyyy-MM-dd'T'HH:mm:ss'Z' format.
     *
     * @param dateStr String reprenting a date
     * @return Date object or null if parsing fails.
     */
    public Date string2date(String dateStr) {
        Date dataTime = null;
        try {
            long tstamp = Long.parseLong(dateStr);
            dataTime = new Date(tstamp);
        } catch (NumberFormatException e) {
            Log.v(TAG, "remoteEventsAdapter.getView: Error Parsing dataDate as Long: " + e.getLocalizedMessage() + " trying as string");
            try {
                SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
                dataTime = dateFormat.parse(dateStr);
            } catch (ParseException e2) {
                Log.e(TAG, "remoteEventsAdapter.getView: Error Parsing dataDate " + e2.getLocalizedMessage());
                dataTime = null;
            }
        }
        return (dataTime);
    }

    public String alarmStatusToString(int eventAlarmStatus) {
        String retVal = "Unknown";
        switch (eventAlarmStatus) {
            case ALARM_STATUS_WARNING: // Warning
                retVal = "WARNING";
                break;
            case ALARM_STATUS_ALARM: // alarm
                retVal = "ALARM";
                break;
            case ALARM_STATUS_FALL: // fall
                retVal = "FALL";
                break;
            case ALARM_STATUS_MANUAL: // Manual alarm
                retVal = "MANUAL ALARM";
                break;

        }
        return (retVal);
    }


}
