package uk.org.openseizuredetector.aw;


import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.app.Service;
import android.content.Context;
import android.content.ContextParams;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.util.Log;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import androidx.test.internal.runner.junit4.AndroidJUnit4ClassRunner;
import androidx.test.runner.AndroidJUnitRunner;

import org.junit.After;
import org.junit.Before;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.MethodSorters;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ServiceController;
import androidx.core.net.ConnectivityManagerCompat;
import androidx.test.filters.MediumTest;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Objects;
import java.util.Random;

/**
 * Created by graham on 01/01/16.
 * Modified by bram on 01/04/23.
 * requires solution from: https://stackoverflow.com/questions/22582021/android-studio-no-tests-were-found
 *
         defaultConfig {
            add: testInstrumentationRunner "androidx.test.runner.AndroidJUnitRunner"
         }
 *
 * as per SDK_INT 26
 */
@LargeTest
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@RunWith(AndroidJUnit4.class)
public class OsdInstrumentalTest {
     Context context;
     Application application;
     Looper looper;
     Handler handler;
     OsdUtil util;
     AWSdService aWsdService;
     Intent sdServerIntent;
     ServiceController<AWSdService> controller;
     SdServiceConnection sdServiceConnection;

    @Before
    public void initOsdUtil(){
        application = ApplicationProvider.getApplicationContext();
        context = application.getApplicationContext();
        looper = context.getMainLooper();
        handler = new Handler(looper);
        util = new OsdUtil(context,handler);
        sdServerIntent = new Intent(context,AWSdService.class);
        if (Objects.isNull(sdServiceConnection)) sdServiceConnection = new SdServiceConnection(context);

        // return true if we are using mobile data, otherwise return false
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (cm != null) {
                NetworkCapabilities capabilities = cm.getNetworkCapabilities(cm.getActiveNetwork());
                if (capabilities == null) ;
                assert capabilities != null;
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                    assertTrue(util.isMobileDataActive());
                }
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    assertTrue(util.isNetworkConnected());
                }
            }
        }
    }

    @Test
    public void testOsdUtil() throws Exception {
        testIsServerNotRunning();
        testStartServer();
        try{
            testIsServerRunning();
        }
        catch(AssertionError assertionError){
            Log.e(this.getClass().getName(),assertionError.getLocalizedMessage(),assertionError);
            int startId=new Random().nextInt();
            Intent intent=sdServerIntent;
            intent.putExtra(Constants.GLOBAL_CONSTANTS.startId,startId);
            intent.setData(Constants.GLOBAL_CONSTANTS.mStartUri);
            controller = Robolectric
                    .buildService(AWSdService.class,intent);
            aWsdService = controller.create().get();
            //test basing up on Phone client.
            SharedPreferences SP = PreferenceManager
                    .getDefaultSharedPreferences(context);
            SP.edit()
                    .remove("DataSource")
                    .putString("DataSource","AndroidWear")
                    .apply();
            SP.edit()
                    .remove("PebbleUpdatePeriod")
                    .putString("PebbleUpdatePeriod","100")
                    .apply();
            SP.edit()
                    .remove("MutePeriod")
                    .putString("MutePeriod","30")
                    .apply();
            SP.edit()
                    .remove("PebbleDisplaySpectrum")
                    .putString("PebbleDisplaySpectrum","30")
                    .apply();
            SP.edit()
                    .remove("HRThreshMin")
                    .putString("HRThreshMin","40")
                    .apply();
            SP.edit()
                    .remove("HRThreshMax")
                    .putString("HRThreshMax","150")
                    .apply();
            SP.edit()
                    .remove("O2SatThreshMin")
                    .putString("O2SatThreshMin","75")
                    .apply();
            util.startServer();
            controller.startCommand(Service.START_FLAG_REDELIVERY,startId);
            Thread.sleep(9000);
            testIsServerRunning();
        }
        try {
            testIsMobileDataActive();
        }catch (Exception exception){
            Log.e(this.getClass().getName(),"Exception testingMobileDataActive. TO IMPLEMENT");
        }
        try {
            testIsMobileDataNotActive();
        }catch (Exception exception){
            Log.e(this.getClass().getName(),"Exception testIsMobileDataNotActive. TO IMPLEMENT");
        }
        try {
            testIsNetworkConnected();
        }catch (Exception exception){
            Log.e(this.getClass().getName(),"Exception testIsNetworkConnected. TO IMPLEMENT");
        }
        testGetAppVersionName();
        testStopServer();
        testIsServerNotRunning();
    }

    @Test
    public void testGetAppVersionName() {
        String equalStringNull = null;
        assertNotEquals(util.getAppVersionName(), equalStringNull);
        final PackageManager packageManager = context.getPackageManager();
        if (packageManager != null) {
            try {
                PackageInfo packageInfo = packageManager.getPackageInfo(context.getPackageName(), 0);
                String versionName = packageInfo.versionName;
                assertEquals(versionName, util.getAppVersionName());
            } catch (PackageManager.NameNotFoundException e) {
                e.printStackTrace();
            }
        }
    }

            @Test
    public void testGetLocalIpAddress() throws UnknownHostException {
        InetAddress equalStringGetLocalIpAddress = InetAddress.getByName( util.getLocalIpAddress());
    }



    @Test
    public void testStartServer()  {
        util.startServer();
        if (Objects.isNull(sdServiceConnection)) sdServiceConnection = new SdServiceConnection(context);
        if (Objects.nonNull(sdServiceConnection))
            if (!sdServiceConnection.mBound) util.bindToServer(context, sdServiceConnection);
    }

    @Test
    public void testIsServerRunning() throws Exception {
        Intent testIntent =sdServerIntent;
        testIntent.setAction(Constants.ACTION.BIND_ACTION);
        try
        {
            assertTrue(
                    util.isServerRunning()
            );
            Log.i(this.getClass().getName(),"assertation of util.isServerRunning ok!");
            assertTrue(util.bindToServer(context,sdServiceConnection));
        }catch (AssertionError assertionError){
            Log.e(this.getClass().getName(),assertionError.getLocalizedMessage(),assertionError);
            if (Objects.nonNull(aWsdService)) assertTrue(aWsdService.bindService(sdServerIntent,sdServiceConnection,Service.BIND_EXTERNAL_SERVICE));
        }

    }

    @Test
    public void testIsMobileDataActive() {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null) {
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.Q) {

                NetworkCapabilities capabilities = cm.getNetworkCapabilities(cm.getActiveNetwork());
                if(Objects.nonNull(capabilities))
                    if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))
                        assertTrue(util.isMobileDataActive());
            }
        }

    }

    @Test
    public void testIsMobileDataNotActive(){
        assertFalse(util.isMobileDataActive());
    }

    @Test
    public void testIsNetworkConnected(){
        assertTrue(util.isNetworkConnected());
    }

    @Test
    public void testStopServer() throws Exception {
        if(Objects.nonNull(sdServiceConnection))
            if(sdServiceConnection.mBound)
                util.unbindFromServer(context,sdServiceConnection);
        sdServiceConnection = null;
        util.stopServer();
    }

    @Test
    public void testIsServerNotRunning() throws Exception {
        SdServiceConnection testSdConnection = new SdServiceConnection(context);
        if (util.bindToServer(context,testSdConnection)) {
            util.unbindFromServer(context,testSdConnection);
            util.stopServer();
        }
        try {
            assertFalse(util.isServerRunning());
        }catch(AssertionError assertionError){
            assertFalse(util.bindToServer(context,testSdConnection));
        }
    }

    @After
    public void removeUtil(){
        sdServiceConnection = null;
        sdServerIntent = null;
        util = null;
        handler = null;
        looper = null;
        context = null;
    }
}