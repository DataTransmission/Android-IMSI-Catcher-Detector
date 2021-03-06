package com.SecUpwN.AIMSICD.service;

import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.location.Location;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.support.v4.app.NotificationCompat;
import android.telephony.CellInfo;
import android.telephony.CellLocation;
import android.telephony.NeighboringCellInfo;
import android.telephony.PhoneStateListener;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.telephony.TelephonyManager;
import android.telephony.cdma.CdmaCellLocation;
import android.telephony.gsm.GsmCellLocation;
import android.util.Log;
import android.view.WindowManager;

import com.SecUpwN.AIMSICD.AIMSICD;
import com.SecUpwN.AIMSICD.BuildConfig;
import com.SecUpwN.AIMSICD.R;
import com.SecUpwN.AIMSICD.adapters.AIMSICDDbAdapter;
import com.SecUpwN.AIMSICD.utils.Cell;
import com.SecUpwN.AIMSICD.utils.Device;
import com.SecUpwN.AIMSICD.utils.DeviceApi17;
import com.SecUpwN.AIMSICD.utils.Helpers;
import com.SecUpwN.AIMSICD.utils.Icon;
import com.SecUpwN.AIMSICD.utils.Status;
import com.SecUpwN.AIMSICD.utils.TinyDB;
//import com.SecUpwN.AIMSICD.service.AimsicdService;

import org.apache.http.HttpResponse;
import org.apache.http.StatusLine;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 *  Class to handle tracking of cell information
 *
 *  Description:  TODO: add more info
 *
 *  Note:       The refresh rate is set in two different places:
 *                  onSharedPreferenceChanged()
 *                  loadPreferences()
 *
 *              For proper TinyDB implementation use something like:
 *              https://github.com/kcochibili/TinyDB--Android-Shared-Preferences-Turbo/issues/6
 *
 *
 *
 *  ToDo:       Currently the automatic refresh rate is hard-coded to 15 seconds,
 *              in 2 different places above. We may consider have this more transparent
 *              in a static variable. It is also used in timerRunnable where it
 *              defaults to 25 seconds.
 *
 *              [x] Use TinyDB.java to simplify Shared Preferences usage
 *
 *
 *  ChangeLog
 *
 *  2015-03-02  kairenken   removed OCID_UPLOAD_PREF. (Upload is done manually.)
 *  2015-03-02  E:V:A       Added TinyDB import for SharedPreferences alternative
 *  2015-03-03  E:V:A       Replaced getSystemProp with TinyDB Boolean "ocid_downloaded" in Runnable()
 *
 *
 *
 */
public class CellTracker implements SharedPreferences.OnSharedPreferenceChangeListener {

    private final String TAG = "AIMSICD";
    private final String mTAG = "CellTracker";

    public static String OCID_API_KEY = null;   // see getOcidKey()
    private final int NOTIFICATION_ID = 1;      // ?

    private static TelephonyManager tm;
    private final SignalStrengthTracker signalStrengthTracker;
    private PhoneStateListener mPhoneStateListener;
    private SharedPreferences prefs;
    // We can also use this to simplify SharedPreferences usage above.
    //TinyDB tinydb = new TinyDB(context);

    public static int PHONE_TYPE;               //
    public static long REFRESH_RATE;            // [s] The DeviceInfo refresh rate (arrays.xml)
    public static int LAST_DB_BACKUP_VERSION;   //
    public static final String SILENT_SMS = "SILENT_SMS_INTERCEPTED";

    private boolean CELL_TABLE_CLEANSED;
    private final Device mDevice = new Device();

    /*
     * Tracking and Alert Declarations
     */
    private boolean mMonitoringCell;
    private boolean mTrackingCell;
    private boolean mTrackingFemtocell;
    private boolean mFemtoDetected;
    private boolean mChangedLAC;
    private boolean mCellIdNotInOpenDb;
    private Cell mMonitorCell;
    private boolean mTypeZeroSmsDetected;
    private LinkedBlockingQueue<NeighboringCellInfo> neighboringCellBlockingQueue;

    private final AIMSICDDbAdapter dbHelper;
    // TEST to fix toast in OCID api key was:
    // private Context context;
    private static Context context;
    private final Handler timerHandler = new Handler();

    public CellTracker(Context context, SignalStrengthTracker sst) {
        this.context = context;
        this.signalStrengthTracker = sst;

        // TelephonyManager provides system details
        tm = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        prefs = context.getSharedPreferences(AimsicdService.SHARED_PREFERENCES_BASENAME, 0);
        prefs.registerOnSharedPreferenceChangeListener(this);
        loadPreferences();
        setNotification();

        PHONE_TYPE = tm.getPhoneType();  //PHONE_TYPE_GSM /CDMA /SIP /NONE

        dbHelper = new AIMSICDDbAdapter(context);
        if (!CELL_TABLE_CLEANSED) {
            dbHelper.open();
            dbHelper.cleanseCellTable();
            dbHelper.close();
            SharedPreferences.Editor prefsEditor;
            prefsEditor = prefs.edit();
            prefsEditor.putBoolean(context.getString(R.string.pref_cell_table_cleansed), true);
            prefsEditor.apply();
        }

        mDevice.refreshDeviceInfo(tm, context); //Telephony Manager
        mMonitorCell = new Cell();

        //Register receiver for Silent SMS Interception Notification
        context.registerReceiver(mMessageReceiver, new IntentFilter(SILENT_SMS));
    }

    public boolean isTrackingCell() {
        return mTrackingCell;
    }

    public boolean isMonitoringCell() {
        return mMonitoringCell;
    }

    /**
     * Cell Information Monitoring
     * TODO: What exactly are we monitoring here??
     *
     *
     * @param monitor Enable/Disable monitoring
     */
    public void setCellMonitoring(boolean monitor) {
        if (monitor) {
            timerHandler.postDelayed(timerRunnable, 0);
            mMonitoringCell = true;
            Helpers.msgShort(context, "Monitoring Cell Information.");
        } else {
            timerHandler.removeCallbacks(timerRunnable);
            mMonitoringCell = false;
            Helpers.msgShort(context, "Stopped monitoring Cell Information.");
        }
        setNotification();
    }

    public Device getDevice() {
        return mDevice;
    }

    /**
     * Tracking Femotcell Connections
     *
     * @return boolean indicating Femtocell Connection Tracking State
     */
    public boolean isTrackingFemtocell() {
        return mTrackingFemtocell;
    }

    public void stop() {
        if(isMonitoringCell()) {
            setCellMonitoring(false);
        }
        if(isTrackingCell()){
            setCellTracking(false);
        }
        if(isTrackingFemtocell()){
            stopTrackingFemto();
        }
        cancelNotification();
        tm.listen(mCellSignalListener, PhoneStateListener.LISTEN_NONE);
        prefs.unregisterOnSharedPreferenceChangeListener(this);

        context.unregisterReceiver(mMessageReceiver);
    }

    /**
     * Cell Information Tracking and database logging
     *
     *  Description:
     *
     *          If the "tracking" option is enabled (as it is by default) then we are keeping
     *          a record (tracking) of the device location "gpsd_lat/lon", the connection
     *          signal strength (rx_signal) and data activity (?) and data connection state (?).
     *          The items included in these are stored in the "cellinfo" table.
     *
     *          DATA_ACTIVITY:
     *          DATA_CONNECTION_STATE:
     *
     *  TODO:   We also need to listen and log for:
     *
     *      [ ]     LISTEN_CALL_STATE:
     *                  CALL_STATE_IDLE
     *                  CALL_STATE_OFFHOOK
     *                  CALL_STATE_RINGING
     *
     *      [ ]     LISTEN_SERVICE_STATE:
     *                  STATE_EMERGENCY_ONLY
     *                  STATE_IN_SERVICE
     *                  STATE_OUT_OF_SERVICE
     *                  STATE_POWER_OFF
     *
     * @param track Enable/Disable tracking
     */
    public void setCellTracking(boolean track) {
        if (track) {
            tm.listen(mCellSignalListener,
                    PhoneStateListener.LISTEN_CELL_LOCATION |           // gpsd_lat/lon ?
                            PhoneStateListener.LISTEN_SIGNAL_STRENGTHS |        // rx_signal
                            PhoneStateListener.LISTEN_DATA_ACTIVITY |           // No,In,Ou,IO,Do
                            PhoneStateListener.LISTEN_DATA_CONNECTION_STATE     // Di,Ct,Cd,Su
                    // PhoneStateListener.LISTEN_CALL_STATE ?
                    // PhoneStateListener.LISTEN_SERVICE_STATE ?
            );
            mTrackingCell = true;
            Helpers.msgShort(context, "Tracking Cell Information.");
        } else {
            tm.listen(mCellSignalListener, PhoneStateListener.LISTEN_NONE);
            mDevice.mCell.setLon(0.0);
            mDevice.mCell.setLat(0.0);
            mDevice.setCellInfo("[0,0]|nn|nn|"); //default entries into "locationinfo"::Connection
            mTrackingCell = false;
            Helpers.msgShort(context, "Stopped tracking Cell Information.");
        }
        setNotification();
    }

    private final BroadcastReceiver mMessageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final Bundle bundle = intent.getExtras();
            if (bundle != null) {
                dbHelper.open();
                dbHelper.insertSilentSms(bundle);
                dbHelper.close();
                setSilentSmsStatus(true);
            }
        }
    };


    /**
     *  Description:    This handles the settings/choices and default preferences, when changed.
     *                  From the default file:
     *                          preferences.xml
     *                  And saved in the file:
     *                          /data/data/com.SecUpwN.AIMSICD/shared_prefs/com.SecUpwN.AIMSICD_preferences.xml
     *
     *  NOTE:           - For more code transparency we have added TinyDB.java as a
     *                    wrapper to SharedPreferences usage. Please try to use this instead.
     *
     * @param sharedPreferences
     * @param key
     */
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        final String KEY_UI_ICONS =     context.getString(R.string.pref_ui_icons_key);
        final String FEMTO_DETECTION =  context.getString(R.string.pref_femto_detection_key);
        final String REFRESH =          context.getString(R.string.pref_refresh_key);      // Manual Refresh
        final String DB_VERSION =       context.getString(R.string.pref_last_database_backup_version);
        final String OCID_KEY =         context.getString(R.string.pref_ocid_key);

        if (key.equals(KEY_UI_ICONS)) {
            //Update Notification to display selected icon type
            setNotification();
        } else if (key.equals(FEMTO_DETECTION)) {
            boolean trackFemtoPref = sharedPreferences.getBoolean(FEMTO_DETECTION, false);
            if (trackFemtoPref) {
                startTrackingFemto();
            } else {
                stopTrackingFemto();
            }
        } else if (key.equals(REFRESH)) {
            String refreshRate = sharedPreferences.getString(REFRESH, "1");
            if (refreshRate.isEmpty()) {
                refreshRate = "1"; // Set default refresh rate to automatic "1", manual is "0".
            }


            int rate = Integer.parseInt(refreshRate);
            long t;
            switch (rate) {
                case 1:
                    t = 15L; // Automatic refresh rate is 15 seconds
                    break;
                default:
                    t = (rate * 1L);
                    break;
            }
            REFRESH_RATE = TimeUnit.SECONDS.toMillis(t);
        } else if (key.equals(DB_VERSION)) {
            LAST_DB_BACKUP_VERSION = sharedPreferences.getInt(DB_VERSION, 1);
        } else if (key.equals(OCID_KEY)) {
            getOcidKey();
        }
    }

    public void getOcidKey() {
        final String OCID_KEY = context.getString(R.string.pref_ocid_key);
        OCID_API_KEY = prefs.getString(OCID_KEY, BuildConfig.OPEN_CELLID_API_KEY);
        if (OCID_API_KEY == null) {
            OCID_API_KEY = "NA"; // avoid null api key
        }
    }

    /**
     * Get an API key for Open Cell ID. Do not call this from the UI/Main thread.
     *
     * @return null or newly generated key
     *
     */
    public static String requestNewOCIDKey() throws Exception {
        String responseFromServer = null;
        DefaultHttpClient httpclient = new DefaultHttpClient();
        HttpGet httpGet = new HttpGet("http://opencellid.org/gsmCell/user/generateApiKey");
        HttpResponse result = httpclient.execute(httpGet);
        StatusLine status = result.getStatusLine();

        if (status.getStatusCode() == 200) {
            if (result.getEntity() != null) {
                InputStream is = result.getEntity().getContent();
                ByteArrayOutputStream content = new ByteArrayOutputStream();
                // Read response into a buffered stream
                int readBytes;
                byte[] sBuffer = new byte[4096];
                while ((readBytes = is.read(sBuffer)) != -1) {
                    content.write(sBuffer, 0, readBytes);
                }
                responseFromServer = content.toString("UTF-8");
                result.getEntity().consumeContent();
            }
            Log.d("OCID", responseFromServer);
            return responseFromServer;

        } else if (status.getStatusCode() == 503) {
            // Check for HTTP error code 503 which is returned when user is trying to request
            // a new API key within 24 hours of the last request. (See GH issue #267)
            // Make toast message:  "Only one new API key request per 24 hours. Please try again later."

            Helpers.msgLong(context, "Only one new API key request per 24 hours!\nPlease try again later.");
            if (result.getEntity() != null) {
                InputStream is = result.getEntity().getContent();
                ByteArrayOutputStream content = new ByteArrayOutputStream();
                // Read response into a buffered stream
                int readBytes = 0;
                byte[] sBuffer = new byte[4096];
                while ((readBytes = is.read(sBuffer)) != -1) {
                    content.write(sBuffer, 0, readBytes);
                }
                responseFromServer = content.toString("UTF-8");
                result.getEntity().consumeContent();
            }
            Log.d("AIMSICD", "CellTracker: OCID Reached 24hr API key limit: " + responseFromServer);
            return responseFromServer;

        } else {

            // TODO add code here or elsewhere to check for NO network exceptions...
            // See: https://github.com/SecUpwN/Android-IMSI-Catcher-Detector/issues/293
            httpclient = null;
            httpGet = null;
            result = null;

            Log.d("AIMSICD", "CellTracker: OCID Returned " + status.getStatusCode() + " " + status.getReasonPhrase());
            throw new Exception("OCID Returned " + status.getStatusCode() + " " + status.getReasonPhrase());
        }
    }


    /**
     *  Description:    Updates Neighbouring Cell details
     *
     *  TODO: add more details...
     *
     *
     */
    public List<Cell> updateNeighbouringCells() {

        TinyDB tinydb = new TinyDB(context);

        List<Cell> neighboringCells = new ArrayList<>();
        List<NeighboringCellInfo> neighboringCellInfo;
        neighboringCellInfo = tm.getNeighboringCellInfo();

        if (neighboringCellInfo.size() == 0) {
            // try to poll the neighboring cells for a few seconds
            neighboringCellBlockingQueue = new LinkedBlockingQueue<>(100);
            Log.i(TAG, mTAG + ": neighbouringCellInfo empty - start polling");
            //Log.i(TAG, "signal: " + mDevice.getSignalDBm());

            //LISTEN_CELL_INFO added in API 17
            if (Build.VERSION.SDK_INT > 16) {
                DeviceApi17.startListening(tm, phoneStatelistener);
            } else {
                tm.listen(phoneStatelistener,
                        PhoneStateListener.LISTEN_CELL_LOCATION |
                                PhoneStateListener.LISTEN_DATA_CONNECTION_STATE |
                                PhoneStateListener.LISTEN_SERVICE_STATE |
                                PhoneStateListener.LISTEN_SIGNAL_STRENGTHS);
            }

            for (int i = 0; i < 10 && neighboringCellInfo.size() == 0; i++) {
                try {
                    Log.d(TAG, mTAG + ": neighbouringCellInfo empty: trying " + i);
                    NeighboringCellInfo info = neighboringCellBlockingQueue.poll(1, TimeUnit.SECONDS);
                    if (info == null) {
                        neighboringCellInfo = tm.getNeighboringCellInfo();
                        if (neighboringCellInfo.size() > 0) {
                            // Can we think of a better log message here?
                            Log.d(TAG, mTAG + ": neighbouringCellInfo found on " + i + " try. (time based)");
                            break;
                        } else {
                            continue;
                        }
                    }
                    ArrayList<NeighboringCellInfo> cellInfoList =
                            new ArrayList<>(neighboringCellBlockingQueue.size() + 1);
                    while (info != null) {
                        cellInfoList.add(info);
                        info = neighboringCellBlockingQueue.poll(1, TimeUnit.SECONDS);
                    }
                    neighboringCellInfo = cellInfoList;
                } catch (InterruptedException e) {
                    // Maybe a more valuable message here?
                    // normal
                }
            }
        }

        Log.d(TAG, mTAG + ": neighbouringCellInfo size: " + neighboringCellInfo.size());

        /**
         *  Description:    This snippet sets a global variable (SharedPreference) to indicate
         *                  if Neighboring cells info CAN be obtained or has been obtained
         *                  previously. If it has been and suddenly there are none, we can
         *                  raise a flag of CID being suspicious.
         *
         *                  The logic is:
         *
         *                      IF NC has never been seen on device:
         *                          - NC list is NOT supported on this AOS/HW, so we do nothing.
         *                      IF NC has been seen before,
         *                          - NC list IS supported on this AOS/HW, so we set:
         *                              nc_list_present : "true"
         *                      IF NC list has been seen before AND current CID doesn't provide
         *                      one, we raise an alarm or flag.
         *
         *
         *  Notes:      a)  Not sure where to place this test, but let's try it here..
         *              b)  In TinyDB, the getBoolean() returns "false" by default, if empty.
         *
         *  Issue:
         *          [ ] We need a timer or "something" to reverse a positive detection once
         *              we're out and away from the fake BTS cell.
         *          [ ] We need to add this to EventLog
         *          [ ] We need to add detection tickers etc...
         *          [ ] Attention to the spelling of "neighbor" (USA) Vs. "neighbour" (Eng world)
         *          [x] We need to use a global and persistent variable and not a system property
         *
         */

        Integer ncls = neighboringCellInfo.size(); // NC list size
        Boolean nclp = tinydb.getBoolean("nc_list_present"); // NC list present? (default is false)

        //if ( ncls > 0 && !nclp ) {
        if ( ncls > 0 ) {
            tinydb.putBoolean("nc_list_present", true);
            Log.d(TAG, mTAG + ": neighbouringCellInfo size: " + ncls );
            Log.d(TAG, mTAG + ": Setting nc_list_present to: true" );
        } else if ( ncls == 0 && nclp )  {
            // Detection 7a
            //String ZID = String.valueOf(mDevice.mCell.getCID() );
            Log.i(TAG, mTAG + ": ALERT: No neighboring cells detected for CID: " + mDevice.mCell.getCID() );

            //  TODO: ADD alert to EventLog table HERE !!

        } else  {
            //if ( ncls == 0 && !nclp )
            // Todo: remove cid string when working.
            Log.d(TAG, mTAG + ": NC list not supported by AOS on this device. Nothing to do. CID: " + mDevice.mCell.getCID() );
            Log.d(TAG, mTAG + ": Setting nc_list_present to: false" );  // Maybe not needed...
            tinydb.putBoolean("nc_list_present", false);                // Maybe not needed...
        }
        // END -- NC list check

        // Add NC list to ?? cellinfo ??  --->  DBi_measure:nc_list
        for (NeighboringCellInfo neighbourCell : neighboringCellInfo) {
            Log.i(TAG,
                    mTAG + ": neighbouringCellInfo -" +
                            " LAC:" + neighbourCell.getLac() +
                            " CID:" + neighbourCell.getCid() +
                            " PSC:" + neighbourCell.getPsc() +
                            " RSSI:" + neighbourCell.getRssi() );

            final Cell cell = new Cell(
                    neighbourCell.getCid(),
                    neighbourCell.getLac(),
                    neighbourCell.getRssi(),
                    neighbourCell.getPsc(),
                    neighbourCell.getNetworkType(), false);
            neighboringCells.add(cell);
        }
        return neighboringCells;
    }

    private void handlePhoneStateChange() {
        List<NeighboringCellInfo> neighboringCellInfo;
        neighboringCellInfo = tm.getNeighboringCellInfo();
        if (neighboringCellInfo.size() == 0) {
            return;
        }
        // Does this make sense? Is it empty or not?
        Log.i(TAG, mTAG + ": neighbouringCellInfo empty - event based polling succeeded!");
        tm.listen(phoneStatelistener, PhoneStateListener.LISTEN_NONE);
        neighboringCellBlockingQueue.addAll(neighboringCellInfo);
    }

    public void refreshDevice() {
        mDevice.refreshDeviceInfo(tm, context);
    }

    /**
     * Process User Preferences
     *
     * Description:     This loads the default Settings/Preferences as set in:
     *                      preferences.xml
     *                  and:
     *                      /data/data/com.SecUpwN.AIMSICD/shared_prefs/com.SecUpwN.AIMSICD_preferences.xml
     *
     *  TODO:           Please add more info and corrections
     *
     */
    private void loadPreferences() {
        boolean trackFemtoPref  = prefs.getBoolean( context.getString(R.string.pref_femto_detection_key), false);
        boolean trackCellPref   = prefs.getBoolean( context.getString(R.string.pref_enable_cell_key), true);
        boolean monitorCellPref = prefs.getBoolean( context.getString(R.string.pref_enable_cell_monitoring_key), true);

        LAST_DB_BACKUP_VERSION  = prefs.getInt(     context.getString(R.string.pref_last_database_backup_version), 1);
        CELL_TABLE_CLEANSED     = prefs.getBoolean( context.getString(R.string.pref_cell_table_cleansed), false);

        String refreshRate      = prefs.getString(  context.getString(R.string.pref_refresh_key), "1");
        // Default to Automatic ("1")
        if (refreshRate.isEmpty()) { refreshRate = "1";  }

        int rate = Integer.parseInt(refreshRate);
        long t;
        switch (rate) {
            case 1:
                t = 15L; // Automatic refresh rate is 15 seconds
                break;
            default:
                t = (rate * 1L);
                break;
        }

        REFRESH_RATE = TimeUnit.SECONDS.toMillis(t);
        getOcidKey();

        if (trackFemtoPref) {   startTrackingFemto(); }
        if (trackCellPref) {    setCellTracking(true); }
        if (monitorCellPref) {  setCellMonitoring(true); }
    }


    /**
     * Description:  TODO: add more info
     *
     *    This SEEM TO add entries to the "locationinfo" DB table in the ??
     *
     *    Issues:
     *
     *    [ ] We see that "Connection" items are messed up. What is the purpose of these?
     *
     *    $ sqlite3.exe -header -csv aimsicd.db 'select * from locationinfo;'
     *      _id,Lac,CellID,Net,Lat,Lng,Signal,Connection,Timestamp
     *      1,10401,6828111,10, 54.6787,25.2869, 24, "[10401,6828111,126]No|Di|HSPA|", "2015-01-21 20:45:10"
     *
     *    [ ] TODO: CDMA has to extract the MCC and MNC using something like:
     *
     *    	String mccMnc = phoneMgr.getNetworkOperator();
     *      String cdmaMcc = "";
     *      String cdmaMnc = "";
     *      if (mccMnc != null && mccMnc.length() >= 5) {
     *          cdmaMcc = mccMnc.substring(0, 3);
     *          cdmaMnc = mccMnc.substring(3, 5);
     }      }
     *
     *    ChangeLog:
     *
     *          2015-01-22  E:V:A   Changed what appears to be a typo in the character
     *                              following getNetworkTypeName(), "|" to "]"
     *          2015-01-24  E:V:A   FC WTF!? Changed back ^ to "|". (Where is this info parsed?)
     *
     */
    private final PhoneStateListener mCellSignalListener = new PhoneStateListener() {
        public void onCellLocationChanged(CellLocation location) {
            mDevice.setNetID(tm);           // ??
            mDevice.getNetworkTypeName();   // RAT??

            switch (mDevice.getPhoneID()) {

                case TelephonyManager.PHONE_TYPE_NONE:  // Maybe bad!
                case TelephonyManager.PHONE_TYPE_SIP:   // Maybe bad!
                case TelephonyManager.PHONE_TYPE_GSM:
                    GsmCellLocation gsmCellLocation = (GsmCellLocation) location;
                    if (gsmCellLocation != null) {
                        mDevice.setCellInfo(
                                gsmCellLocation.toString() +                // ??
                                        mDevice.getDataActivityTypeShort() + "|" +  // No,In,Ou,IO,Do
                                        mDevice.getDataStateShort() + "|" +         // Di,Ct,Cd,Su
                                        mDevice.getNetworkTypeName() + "|"          // TODO: Is "|" a typo?
                        );
                        mDevice.mCell.setLAC(gsmCellLocation.getLac());     // LAC
                        mDevice.mCell.setCID(gsmCellLocation.getCid());     // CID
                        if (gsmCellLocation.getPsc() != -1)
                            mDevice.mCell.setPSC(gsmCellLocation.getPsc()); // PSC

                    }
                    break;

                case TelephonyManager.PHONE_TYPE_CDMA:
                    CdmaCellLocation cdmaCellLocation = (CdmaCellLocation) location;
                    if (cdmaCellLocation != null) {
                        mDevice.setCellInfo(
                                cdmaCellLocation.toString() +               // ??
                                        mDevice.getDataActivityTypeShort() + "|" +  // No,In,Ou,IO,Do
                                        mDevice.getDataStateShort() + "|" +         // Di,Ct,Cd,Su
                                        mDevice.getNetworkTypeName() + "|"          // TODO: Is "|" a typo?
                        );
                        mDevice.mCell.setLAC(cdmaCellLocation.getNetworkId());      // NID
                        mDevice.mCell.setCID(cdmaCellLocation.getBaseStationId());  // BID
                        mDevice.mCell.setSID(cdmaCellLocation.getSystemId());       // SID
                        mDevice.mCell.setMNC(cdmaCellLocation.getSystemId());       // <== BUG!? See issue above! // MNC
                        mDevice.setNetworkName(tm.getNetworkOperatorName());        // ??
                    }
            }

        }

        /**
         *  Description:  TODO: add more info
         *
         *  Issues:
         *
         *      [ ]     Getting and comparing signal strengths between different RATs can be very
         *              tricky, since they all return different ranges of values. AOS doesn't
         *              specify very clearly what exactly is returned, even though people have
         *              a good idea, by trial and error.
         *
         *              See note in : SignalStrengthTracker.java
         *
         *  Notes:
         *
         *
         *
         */
        public void onSignalStrengthsChanged(SignalStrength signalStrength) {
            // Update Signal Strength
            if (signalStrength.isGsm()) {
                int dbm;
                if(signalStrength.getGsmSignalStrength() <= 2 ||
                        signalStrength.getGsmSignalStrength() ==  NeighboringCellInfo.UNKNOWN_RSSI)
                {
                    // Unknown signal strength, get it another way
                    String[] bits = signalStrength.toString().split(" ");
                    dbm = Integer.parseInt(bits[9]);
                } else {
                    dbm = signalStrength.getGsmSignalStrength();
                }
                mDevice.setSignalDbm(dbm);
            } else {
                int evdoDbm = signalStrength.getEvdoDbm();
                int cdmaDbm = signalStrength.getCdmaDbm();

                // Use lowest signal to be conservative
                mDevice.setSignalDbm((cdmaDbm < evdoDbm) ? cdmaDbm : evdoDbm);
            }
            // Send it to signal tracker
            signalStrengthTracker.registerSignalStrength(mDevice.mCell.getCID(), mDevice.getSignalDBm());
            //signalStrengthTracker.isMysterious(mDevice.mCell.getCID(), mDevice.getSignalDBm());
        }

        // In DB:   No,In,Ou,IO,Do
        public void onDataActivity(int direction) {
            switch (direction) {
                case TelephonyManager.DATA_ACTIVITY_NONE:
                    mDevice.setDataActivityTypeShort("No");
                    mDevice.setDataActivityType("None");
                    break;
                case TelephonyManager.DATA_ACTIVITY_IN:
                    mDevice.setDataActivityTypeShort("In");
                    mDevice.setDataActivityType("In");
                    break;
                case TelephonyManager.DATA_ACTIVITY_OUT:
                    mDevice.setDataActivityTypeShort("Ou");
                    mDevice.setDataActivityType("Out");
                    break;
                case TelephonyManager.DATA_ACTIVITY_INOUT:
                    mDevice.setDataActivityTypeShort("IO");
                    mDevice.setDataActivityType("In-Out");
                    break;
                case TelephonyManager.DATA_ACTIVITY_DORMANT:
                    mDevice.setDataActivityTypeShort("Do");
                    mDevice.setDataActivityType("Dormant");
                    break;
            }
        }

        // In DB:   Di,Ct,Cd,Su
        public void onDataConnectionStateChanged(int state) {
            switch (state) {
                case TelephonyManager.DATA_DISCONNECTED:
                    mDevice.setDataState("Disconnected");
                    mDevice.setDataStateShort("Di");
                    break;
                case TelephonyManager.DATA_CONNECTING:
                    mDevice.setDataState("Connecting");
                    mDevice.setDataStateShort("Ct");
                    break;
                case TelephonyManager.DATA_CONNECTED:
                    mDevice.setDataState("Connected");
                    mDevice.setDataStateShort("Cd");
                    break;
                case TelephonyManager.DATA_SUSPENDED:
                    mDevice.setDataState("Suspended");
                    mDevice.setDataStateShort("Su");
                    break;
            }
        }

    };

    void setSilentSmsStatus(boolean state) {
        mTypeZeroSmsDetected = state;
        setNotification();
        if (state) {
            final AlertDialog.Builder builder = new AlertDialog.Builder(context);
            builder.setMessage(R.string.sms_message).setTitle(R.string.sms_title);
            AlertDialog alert = builder.create();
            alert.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
            alert.show();
            mTypeZeroSmsDetected = false;
        }
    }

    /**
     *  Description:  TODO: add more info
     *
     *      This SEEM TO add entries to the "locationinfo" DB table ???
     *
     *  From "locationinfo":
     *
     *      $ sqlite3.exe -header aimsicd.db 'select * from locationinfo;'
     *      _id|Lac|CellID|Net|Lat|Lng|Signal|Connection|Timestamp
     *      1|10401|6828xxx|10|54.67874392|25.28693531|24|[10401,6828320,126]No|Di|HSPA||2015-01-21 20:45:10
     *
     *  From "cellinfo":
     *
     *      $ sqlite3.exe -header aimsicd.db 'select * from cellinfo;'
     *      _id|Lac|CellID|Net|Lat|Lng|Signal|Mcc|Mnc|Accuracy|Speed|Direction|NetworkType|MeasurementTaken|OCID_SUBMITTED|Timestamp
     *      1|10401|6828xxx|10|54.67874392|25.28693531|24|246|2|69.0|0.0|0.0|HSPA|82964|0|2015-01-21 20:45:10
     *
     *  Issues:
     *
     */
    public void onLocationChanged(Location loc) {

        if (Build.VERSION.SDK_INT > 16) {
            DeviceApi17.loadCellInfo(tm, mDevice.mCell);
        }

        if (!mDevice.mCell.isValid()) {
            CellLocation cellLocation = tm.getCellLocation();
            if (cellLocation != null) {
                switch (mDevice.getPhoneID()) {

                    case TelephonyManager.PHONE_TYPE_NONE:  // Maybe bad!
                    case TelephonyManager.PHONE_TYPE_SIP:   // Maybe bad!
                    case TelephonyManager.PHONE_TYPE_GSM:
                        GsmCellLocation gsmCellLocation = (GsmCellLocation) cellLocation;
                        mDevice.mCell.setCID(gsmCellLocation.getCid()); // CID
                        mDevice.mCell.setLAC(gsmCellLocation.getLac()); // LAC
                        mDevice.mCell.setPSC(gsmCellLocation.getPsc()); // PSC
                        break;

                    case TelephonyManager.PHONE_TYPE_CDMA:
                        CdmaCellLocation cdmaCellLocation = (CdmaCellLocation) cellLocation;
                        mDevice.mCell.setCID(cdmaCellLocation.getBaseStationId()); // BSID ??
                        mDevice.mCell.setLAC(cdmaCellLocation.getNetworkId());     // NID
                        mDevice.mCell.setSID(cdmaCellLocation.getSystemId());      // SID
                        mDevice.mCell.setMNC(cdmaCellLocation.getSystemId());       // <== BUG!??     // MNC
                }
            }
        }

        if (loc != null && (loc.getLatitude() != 0.0 && loc.getLongitude() != 0.0)) {
            mDevice.mCell.setLon(loc.getLongitude());       // gpsd_lon
            mDevice.mCell.setLat(loc.getLatitude());        // gpsd_lat
            mDevice.mCell.setSpeed(loc.getSpeed());         // speed
            mDevice.mCell.setAccuracy(loc.getAccuracy());   // gpsd_accu
            mDevice.mCell.setBearing(loc.getBearing());     // -- [deg]??
            mDevice.setLastLocation(loc);                   //

            //Store last known location in preference
            SharedPreferences.Editor prefsEditor;
            prefsEditor = prefs.edit();
            prefsEditor.putString(context.getString(R.string.data_last_lat_lon),
                    String.valueOf(loc.getLatitude()) + ":" +
                            String.valueOf(loc.getLongitude()));
            prefsEditor.apply();

            if (mTrackingCell) {
                dbHelper.open();

                // LOCATION_TABLE (locationinfo)    ==>  DBi_measure + DBi_bts
                dbHelper.insertLocation(
                        mDevice.mCell.getLAC(),     // Lac
                        mDevice.mCell.getCID(),     // CellID
                        mDevice.mCell.getNetType(), // Net
                        mDevice.mCell.getLat(),     // Lat
                        mDevice.mCell.getLon(),     // Lng
                        mDevice.mCell.getDBM(),     // Signal
                        mDevice.getCellInfo()       // Connection
                );

                // CELL_TABLE                       (cellinfo)      ==>  DBi_measure + DBi_bts
                dbHelper.insertCell(
                        mDevice.mCell.getLAC(),     // Lac
                        mDevice.mCell.getCID(),     // CellID
                        mDevice.mCell.getNetType(), // Net
                        mDevice.mCell.getLat(),     // Lat
                        mDevice.mCell.getLon(),     // Lng
                        mDevice.mCell.getDBM(),     // Signal
                        mDevice.mCell.getMCC(),     // Mcc
                        mDevice.mCell.getMNC(),     // Mnc
                        mDevice.mCell.getAccuracy(),// Accuracy
                        mDevice.mCell.getSpeed(),   // Speed
                        mDevice.mCell.getBearing(), // Direction
                        mDevice.getNetworkTypeName(),         // NetworkType
                        SystemClock.currentThreadTimeMillis() // MeasurementTaken [ms]
                );
                dbHelper.close();
            }
        }
    }

    /**
     * Cancel and remove the persistent notification
     */
    public void cancelNotification() {
        NotificationManager notificationManager = (NotificationManager) context
                .getSystemService(Context.NOTIFICATION_SERVICE);
        if (notificationManager != null) {
            notificationManager.cancel(NOTIFICATION_ID);
        }
    }

    /**
     * Set or update the Detection/Status Notification
     *
     *  Description:    TODO: Please add details!
     *
     *  Issues:
     *
     *  [ ] TODO: Seem we're missing the other colors here: ORANGE and BLACK (skull)
     *      See:  https://github.com/SecUpwN/Android-IMSI-Catcher-Detector/wiki/Status-Icons
     *      and:  https://github.com/SecUpwN/Android-IMSI-Catcher-Detector/issues/11#issuecomment-44670204
     *
     *      Change names from "IDLE,NORMAL,MEDIUM,ALARM" to:"GRAY,GREEN,YELLOW,ORANGE,RED,BLACK",
     *      to reflect detection Icon colors. They should be based on the detection scores here:
     *      <TBA>
     *
     *  [ ] We need to standardize the "contentText" and "tickerText" format
     *
     *  [ ] From #91: https://github.com/SecUpwN/Android-IMSI-Catcher-Detector/issues/91
     *
     *      Problem:
     *          Having multiple notifications will cause an issue with
     *      notifications themselves AND tickerText.  It seems that the
     *      most recent notification raised would overwrite any previous,
     *      notification or tickerText.  This results in loss of information
     *      for any notification before the last one.
     *
     *      Possible Solution:
     *          Perhaps arranging a queue implementation to deal with text
     *      being passed into tickerText only when any previous text has
     *      been entirely displayed.
     *
     *
     *  Dependencies:    Status.java, CellTracker.java, Icon.java ( + others?)
     *
     *  ChangeLog:
     *
     *     2015-01-22   E:V:A  Added placeholder for "Missing Neighboring Cells Alert"
     *
     *
     */
    void setNotification() {
        String tickerText;
        String contentText = "Phone Type " + mDevice.getPhoneType();

        if (mFemtoDetected || mTypeZeroSmsDetected) {
            Status.setCurrentStatus(Status.Type.ALARM, this.context);
        } else if (mChangedLAC) {
            Status.setCurrentStatus(Status.Type.MEDIUM, this.context);
            contentText = "Hostile Service Area: Changing LAC Detected!";
        } else if(mCellIdNotInOpenDb){
            Status.setCurrentStatus(Status.Type.MEDIUM, this.context);
            contentText = "Cell ID does not exist in OpenCellID Database!";
        } else if (mTrackingFemtocell || mTrackingCell || mMonitoringCell) {
            Status.setCurrentStatus(Status.Type.NORMAL, this.context);
            if (mTrackingFemtocell) {
                contentText = "FemtoCell Detection Active.";
            } else if (mTrackingCell) {
                contentText = "Cell Tracking Active.";
            } else {
                contentText = "Cell Monitoring Active.";
            }
        } else {
            Status.setCurrentStatus(Status.Type.IDLE, this.context);
        }

        switch (Status.getStatus()) {
            case IDLE: // GRAY
                contentText = "Phone Type " + mDevice.getPhoneType();
                tickerText = context.getResources().getString(R.string.app_name_short) + " - Status: Idle.";
                break;

            case NORMAL: // GREEN
                tickerText = context.getResources().getString(R.string.app_name_short) + " - Status: Good. No Threats Detected.";
                break;

            case MEDIUM: // YELLOW
                // Initialize tickerText as the app name string
                // See multiple detection comments above.
                tickerText = context.getResources().getString(R.string.app_name_short);
                if (mChangedLAC) {
                    //Append changing LAC text
                    tickerText += " - Hostile Service Area: Changing LAC Detected!";
                    contentText = "Hostile Service Area: Changing LAC Detected!";
                    // See #264 and ask He3556
                    //} else if (mNoNCList)  {
                    //    tickerText += " - BTS doesn't provide any neighbors!";
                    //    contentText = "CID: " + cellid + " is not providing a neighboring cell list!";

                } else if (mCellIdNotInOpenDb) {
                    //Append Cell ID not existing in external db text
                    tickerText += " - Cell ID does not exist in OpenCellID Database!";
                    contentText = "Cell ID does not exist in OpenCellID Database!";
                }
                break;

            case ALARM: // ORANGE, RED or BLACK ?
                tickerText = context.getResources().getString(R.string.app_name_short) + " - ALERT: Some Threat Detected!"; // Hmm, this is vague!
                if (mFemtoDetected) {
                    contentText = "ALERT: FemtoCell Connection Detected!";
                } else if (mTypeZeroSmsDetected) {
                    contentText = "ALERT: Type-0 Silent SMS Intercepted!";
                }

                break;
            default:
                tickerText = context.getResources().getString(R.string.app_name);
                break;
        }

        // TODO: Explanation (see above)
        Intent notificationIntent = new Intent(context, AIMSICD.class);
        notificationIntent.putExtra("silent_sms", mTypeZeroSmsDetected);
        notificationIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_FROM_BACKGROUND);
        PendingIntent contentIntent = PendingIntent.getActivity(
                context, NOTIFICATION_ID, notificationIntent, PendingIntent.FLAG_CANCEL_CURRENT);
        String iconType = prefs.getString(context.getString(R.string.pref_ui_icons_key), "SENSE").toUpperCase();
        Notification mBuilder =
                new NotificationCompat.Builder(context)
                        .setSmallIcon(Icon.getIcon(Icon.Type.valueOf(iconType)))
                        .setTicker(tickerText)
                        .setContentTitle(context.getResources().getString(R.string.app_name))
                        .setContentText(contentText)
                        .setOngoing(true)
                        .setAutoCancel(false)
                        .setContentIntent(contentIntent)
                        .build();
        NotificationManager mNotificationManager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        mNotificationManager.notify(NOTIFICATION_ID, mBuilder);
    }


    /**
     *  Description:    (From xLaMbChOpSx commit comment)
     *
     *      Initial implementation for detection method 1 to compare the CID & LAC with the Cell
     *      Information Table contents as an initial implementation for detection of a changed LAC,
     *      once OCID issues (API key use etc) have been finalised this detection method can be
     *      extended to include checking of external data.
     *
     *      This initial implementation is activated through the drawer menu item titled
     *      "Monitor Cell Information" which will invoke timed runnable that will poll the Cell
     *      Location details using the selected refresh rate OR default to 25 seconds if
     *      Automatic/Manual has been selected, once testing is complete this can be enabled as a
     *      passive detection method which can could be controlled through a user preference.
     *
     *      As I have no real way of testing this I require testing by other project members who
     *      do have access to equipment or an environment where a changing LAC can be simulated
     *      thus confirming the accuracy of this implementation.
     *
     *      Presently this will only invoke the MEDIUM threat level through the notification and
     *      does not fully implement the capturing and score based method as per the issue details
     *      once further testing is complete the alert and tracking of information can be refined.
     *
     *      See:
     *        https://github.com/xLaMbChOpSx/Android-IMSI-Catcher-Detector/commit/43ae77e2a0cad10dfd50f92da5a998f9ece95b38
     *        https://github.com/SecUpwN/Android-IMSI-Catcher-Detector/issues/91#issuecomment-64391732
     *
     *  Short explanation:
     *
     *                  This is a polling mechanism for getting the LAC/CID and location
     *                  info for the currently connected cell.
     *
     *  Variables:
     *                  There is a "timer" here (REFRESH_RATE), what exactly is it timing?
     *                  "Every REFRESH_RATE seconds, get connected cell details."
     *
     *  Notes:
     *              a) Check if CellID (CID) is in DBe_import (OpenCell) database (issue #91)
     *                 See news in: issue #290 and compare to AIMSICDDbAdapter.java
     *
     *  Issues:     [ ] We shouldn't do any detection here!
     *              [ ] We might wanna use a listener to do this?
     *                  Are there any reasons why not using a listener?
     *
     *  ChangeLog:
     *              2015-03-03  E:V:A   Changed getProp() to use TinyDB (SharedPreferences)
     *
     */
    private final Runnable timerRunnable = new Runnable() {

        @Override
        public void run() {
            TinyDB tinydb = new TinyDB(context);
            switch (mDevice.getPhoneID()) {

                case TelephonyManager.PHONE_TYPE_NONE:  // Maybe bad!
                case TelephonyManager.PHONE_TYPE_SIP:   // Maybe bad!
                case TelephonyManager.PHONE_TYPE_GSM:
                    GsmCellLocation gsmCellLocation = (GsmCellLocation) tm.getCellLocation();
                    if (gsmCellLocation != null) {
                        mMonitorCell.setLAC(gsmCellLocation.getLac());
                        mMonitorCell.setCID(gsmCellLocation.getCid());

                        dbHelper.open();
                        boolean lacOK = dbHelper.checkLAC(mMonitorCell);
                        if (!lacOK) {
                            mChangedLAC = true;
                            setNotification();
                        } else {
                            mChangedLAC = false;
                        }
                        // Check if CellID (CID) is in DBe_import (OpenCell) database (issue #91)
                        if ( tinydb.getBoolean("ocid_downloaded") ) {
                            if (!dbHelper.openCellExists(mMonitorCell.getCID())) {
                                Log.i(TAG, "ALERT: Connected to unknown CID not in DBe_import: " + mMonitorCell.getCID());

                                // Code Place-holder: TODO: Add to EventLog table!!

                                mCellIdNotInOpenDb = true;
                                setNotification();
                            } else {
                                mCellIdNotInOpenDb = false;
                            }
                            dbHelper.close();
                        }
                    }
                    break;

                case TelephonyManager.PHONE_TYPE_CDMA:
                    CdmaCellLocation cdmaCellLocation = (CdmaCellLocation) tm.getCellLocation();
                    if (cdmaCellLocation != null) {
                        mMonitorCell.setLAC(cdmaCellLocation.getNetworkId());
                        mMonitorCell.setCID(cdmaCellLocation.getBaseStationId());

                        dbHelper.open();
                        boolean lacOK = dbHelper.checkLAC(mMonitorCell);
                        if (!lacOK) {
                            mChangedLAC = true;
                            setNotification();
                        } else {
                            mChangedLAC = false;
                        }
                        dbHelper.close();
                    }
            }

            if (REFRESH_RATE != 0) {
                timerHandler.postDelayed(this, REFRESH_RATE);
            } else {
                //Default to 25 seconds refresh rate
                timerHandler.postDelayed(this, TimeUnit.SECONDS.toMillis(25));
            }
        }
    };


//=================================================================================================
// The below code section was copied and modified with permission from
// Femtocatcher at:  https://github.com/iSECPartners/femtocatcher
//
// Copyright (C) 2013 iSEC Partners
//=================================================================================================

    /**
     * Start FemtoCell detection tracking (For CDMA Devices ONLY!)
     */
    public void startTrackingFemto() {

        /* Check if it is a CDMA phone */
        if (mDevice.getPhoneID() != TelephonyManager.PHONE_TYPE_CDMA) {
            Helpers.msgShort(context, "AIMSICD can only detect FemtoCell connections on CDMA devices.");
            return;
        }

        mTrackingFemtocell = true;
        mPhoneStateListener = new PhoneStateListener() {
            public void onServiceStateChanged(ServiceState s) {
                Log.d(TAG, mTAG + ": Service State changed!");
                getServiceStateInfo(s);
            }
        };
        tm.listen(mPhoneStateListener, PhoneStateListener.LISTEN_CELL_LOCATION);
        tm.listen(mPhoneStateListener, PhoneStateListener.LISTEN_SERVICE_STATE);
        setNotification();
    }

    /**
     * Stop FemtoCell detection tracking (For CDMA Devices ONLY!)
     */
    public void stopTrackingFemto() {
        if (mPhoneStateListener != null) {
            tm.listen(mPhoneStateListener, PhoneStateListener.LISTEN_NONE);
            mTrackingFemtocell = false;
            setNotification();
            Log.v(TAG, mTAG + ": Stopped tracking FemtoCell connections.");
        }
    }

    private void getServiceStateInfo(ServiceState s) {
        if (s != null) {
            if (IsConnectedToCdmaFemto(s)) {
                Helpers.msgShort(context, "ALERT! FemtoCell Connection Detected.");
                mFemtoDetected = true;
                setNotification();
                //toggleRadio();
            } else {
                mFemtoDetected = false;
                setNotification();
            }
        }
    }

    private boolean IsConnectedToCdmaFemto(ServiceState s) {
        if (s == null) {
            return false;
        }

        /* Get International Roaming indicator
         * if indicator is not 0 return false
         */

        /* Get the radio technology */
        int networkType = mDevice.mCell.getNetType();

        /* Check if it is EvDo network */
        boolean evDoNetwork = isEvDoNetwork(networkType);

        /* If it is not an evDo network check the network ID range.
         * If it is connected to Femtocell, the NID should be between [0xfa, 0xff)
         */
        if (!evDoNetwork) {
            /* get network ID */
            if (tm != null) {
                CdmaCellLocation c = (CdmaCellLocation) tm.getCellLocation();

                if (c != null) {
                    int networkID = c.getNetworkId();
                    int FEMTO_NID_MAX = 0xff;
                    int FEMTO_NID_MIN = 0xfa;
                    return !((networkID < FEMTO_NID_MIN) || (networkID >= FEMTO_NID_MAX));

                } else {
                    Log.v(TAG, mTAG + ": Cell location info is null.");
                    return false;
                }
            } else {
                Log.v(TAG, mTAG + ": Telephony Manager is null.");
                return false;
            }
        }

        /* if it is an evDo network */
        // TODO
        else {
            /* get network ID */
            if (tm != null) {
                CdmaCellLocation c = (CdmaCellLocation) tm.getCellLocation();

                if (c != null) {
                    int networkID = c.getNetworkId();

                    int FEMTO_NID_MAX = 0xff;
                    int FEMTO_NID_MIN = 0xfa;
                    return !((networkID < FEMTO_NID_MIN) || (networkID >= FEMTO_NID_MAX));
                } else {
                    Log.v(TAG, mTAG + ": Cell location info is null.");
                    return false;
                }
            } else {
                Log.v(TAG, mTAG + ": Telephony Manager is null.");
                return false;
            }
        }

    }

    /**
     * Confirmation of connection to an EVDO Network
     *
     * @return EVDO network connection returns TRUE
     */
    private boolean isEvDoNetwork(int networkType) {
        return (networkType == TelephonyManager.NETWORK_TYPE_EVDO_0) ||
                (networkType == TelephonyManager.NETWORK_TYPE_EVDO_A) ||
                (networkType == TelephonyManager.NETWORK_TYPE_EVDO_B) ||
                (networkType == TelephonyManager.NETWORK_TYPE_EHRPD);
    }

//=================================================================================================
// END Femtocatcher code
//=================================================================================================

    /**
     * Description:     TODO:
     *
     *
     */
    final PhoneStateListener phoneStatelistener = new PhoneStateListener() {
        private void handle() {
            handlePhoneStateChange();
        }
        @Override
        public void onServiceStateChanged(ServiceState serviceState) {
            handle();
        }
        @Override
        public void onDataConnectionStateChanged(int state) {
            handle();
        }
        @Override
        public void onDataConnectionStateChanged(int state, int networkType) {
            handle();
        }
        @Override
        public void onSignalStrengthsChanged(SignalStrength signalStrength) {
            handle();
        }
        @Override
        public void onCellInfoChanged(List<CellInfo> cellInfo) {
            handle();
        }
    };

    /**
     * Getter for use in tests only
     * TODO: What tests?
     */
    public Cell getMonitorCell() {
        return mMonitorCell;
    }
}
