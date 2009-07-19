
package com.android.bluetooth.pbap;

import com.android.bluetooth.R;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothError;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothIntent;
// TODO: have dependency on framework/base
//import android.bluetooth.BluetoothPbap;
import android.bluetooth.BluetoothSocket;
import android.bluetooth.BluetoothServerSocket;
// TODO: have dependency on framework/base
//import android.bluetooth.IBluetoothPbap;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.PowerManager;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.widget.Toast;

import java.io.IOException;
import java.util.ArrayList;

import javax.obex.ServerSession;

public class BluetoothPbapService extends Service {

    private static final String TAG = "BluetoothPbapService";

    public static final boolean DBG = true;

    /**
     * Intent indicating incoming connection request which is sent to
     * BluetoothPbapActivity
     */
    public static final String ACCESS_REQUEST = "com.android.bluetooth.pbap.accessrequest";

    /**
     * Intent indicating incoming connection request accepted by user which is
     * sent from BluetoothPbapActivity
     */
    public static final String ACCESS_ALLOWED = "com.android.bluetooth.pbap.accessallowed";

    /**
     * Intent indicating incoming connection request denied by user which is
     * sent from BluetoothPbapActivity
     */
    public static final String ACCESS_DISALLOWED = "com.android.bluetooth.pbap.accessdisallowed";

    /**
     * Intent indicating incoming obex authentication request which is from
     * PCE(Carkit)
     */
    public static final String AUTH_CHALL = "com.android.bluetooth.pbap.authchall";

    /**
     * Intent indicating obex session key input complete by user which is sent
     * from BluetoothPbapActivity
     */
    public static final String AUTH_RESPONSE = "com.android.bluetooth.pbap.authresponse";

    /**
     * Intent indicating user canceled obex authentication session key input
     * which is sent from BluetoothPbapActivity
     */
    public static final String AUTH_CANCELLED = "com.android.bluetooth.pbap.authcancelled";

    /**
     * Intent indicating user confirmation timeout which is sent to
     * BluetoothPbapActivity
     */
    public static final String USER_CONFIRM_TIMEOUT = "com.android.bluetooth.pbap.userconfirmtimeout";

    /**
     * Intent Extra name indicating always allowed which is sent from
     * BluetoothPbapActivity
     */
    public static final String ALWAYS_ALLOWED = "com.android.bluetooth.pbap.alwaysallowed";

    /**
     * Intent Extra name indicating session key which is sent from
     * BluetoothPbapActivity
     */
    public static final String SESSION_KEY = "com.android.bluetooth.pbap.sessionkey";

    public static final String THIS_PACKAGE_NAME = "com.android.bluetooth";

    public static final int MSG_SERVERSESSION_CLOSE = 5000;

    public static final int MSG_SESSION_ESTABLISHED = 5001;

    public static final int MSG_SESSION_DISCONNECTED = 5002;

    public static final int MSG_OBEX_AUTH_CHALL = 5003;

    private static final String BLUETOOTH_PERM = android.Manifest.permission.BLUETOOTH;

    private static final String BLUETOOTH_ADMIN_PERM = android.Manifest.permission.BLUETOOTH_ADMIN;

    private static final int START_LISTENER = 1;

    private static final int USER_TIMEOUT = 2;

    private static final int AUTH_TIMEOUT = 3;

    private PowerManager.WakeLock mWakeLock = null;

    private BluetoothDevice mBluetooth;

    private SocketAcceptThread mAcceptThread = null;

    private BluetoothPbapObexServer mPbapServer;

    private ServerSession mServerSession = null;

    private BluetoothServerSocket mServerSocket = null;

    private BluetoothSocket mConnSocket = null;

    private String mDeviceAddr = null;

    private static String sLocalPhoneNum = null;

    private static String sLocalPhoneName = null;

    private static String sRemoteDeviceName = null;

    private String mRemoteAddr = null;

    private boolean mHasStarted = false;

    private int mState;

    private int mStartId = -1;

    private static final int PORT_NUM = 19;

    private static final int USER_CONFIRM_TIMEOUT_VALUE = 30000;

    private static final int SOCKET_ACCEPT_TIMEOUT_VALUE = 5000;

    private static final int TIME_TO_WAIT_VALUE = 6000;

    private static BluetoothPbapVcardManager sVcardManager;

    private CharSequence mTmpTxt;

    private BluetoothPbapAuthenticator mAuth = null;

    public BluetoothPbapService() {
        // TODO: have dependency on framework/base
        // mState = BluetoothPbap.STATE_DISCONNECTED;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mBluetooth = (BluetoothDevice)getSystemService(Context.BLUETOOTH_SERVICE);
        if (mBluetooth != null) {
            mDeviceAddr = mBluetooth.getAddress();
        }
        sVcardManager = new BluetoothPbapVcardManager(BluetoothPbapService.this);
        if (!mHasStarted && mDeviceAddr != null) {
            mHasStarted = true;
            Log.i(TAG, "Starting PBAP service");
            int state = mBluetooth.getBluetoothState();
            if (state == BluetoothDevice.BLUETOOTH_STATE_ON) {
                mSessionStatusHandler.sendMessageDelayed(mSessionStatusHandler
                        .obtainMessage(START_LISTENER), TIME_TO_WAIT_VALUE);
            }
        }
    }

    @Override
    public void onStart(Intent intent, int startId) {
        mStartId = startId;
        if (mBluetooth == null || mDeviceAddr == null) {
            Log.w(TAG, "Stopping BluetoothHeadsetService: "
                    + "device does not have BT or device is not ready");
            closeService(); // release all resources
        } else {
            parseIntent(intent);
        }
    }

    // process the intent from receiver
    private void parseIntent(final Intent intent) {
        String action = intent.getExtras().getString("action");
        int state = intent.getIntExtra(BluetoothIntent.BLUETOOTH_STATE, BluetoothError.ERROR);
        if (action.equals(BluetoothIntent.BLUETOOTH_STATE_CHANGED_ACTION)) {
            if (state == BluetoothDevice.BLUETOOTH_STATE_OFF) {
                closeService(); // release all resources
            }
        }

        if (action.equals(ACCESS_ALLOWED)) {
            mSessionStatusHandler.removeMessages(USER_TIMEOUT);
            if (intent.getBooleanExtra(ALWAYS_ALLOWED, false)) {
                // TODO: have dependency on device trust feature implementation
                // mBluetooth.setTrust(mRemoteAddr, true);
            }
            try {
                // In case carkit time out and try to use HFP for phonebook
                // access ,while UI still there for user to comfirm
                if (mConnSocket != null) {
                    startObexServerSession();
                } else {
                    obexServerSessionClose();
                }
            } catch (IOException ex) {
                Log.e(TAG, "Caught the error: " + ex.toString());
            }
        }

        if (action.equals(ACCESS_DISALLOWED)) {
            mSessionStatusHandler.removeMessages(USER_TIMEOUT);
            obexServerSessionClose();
        }

        if (action.equals(AUTH_RESPONSE)) {
            mSessionStatusHandler.removeMessages(USER_TIMEOUT);
            String sessionkey = intent.getStringExtra(SESSION_KEY);
            notifyAuthKeyInput(sessionkey);
        }

        if (action.equals(AUTH_CANCELLED)) {
            mSessionStatusHandler.removeMessages(USER_TIMEOUT);
            notifyAuthCancelled();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // TODO: have dependency on framework/base
        // setState(BluetoothPbap.STATE_DISCONNECTED,
        // BluetoothPbap.RESULT_CANCELED);
    }

    @Override
    public IBinder onBind(Intent intent) {
        // TODO: have dependency on framework/base
        // return mBinder;
        return null;
    }

    public static int getPhonebookSize(final int type) {
        if (DBG) {
            Log.d(TAG, "getPhonebookSzie type=" + type);
        }
        switch (type) {
            case BluetoothPbapObexServer.NEED_PHONEBOOK:
                return sVcardManager.getPhonebookSize();
            default:
                return sVcardManager.getCallHistorySize(type);
        }
    }

    public static String getPhonebook(final int type, final int pos, final boolean vcardType) {
        if (DBG) {
            Log.d(TAG, "getPhonebook type=" + type + " pos=" + pos + " vcardType=" + vcardType);
        }
        switch (type) {
            case BluetoothPbapObexServer.NEED_PHONEBOOK:
                return sVcardManager.getPhonebook(pos, vcardType);
            default:
                return sVcardManager.getCallHistory(pos, type, vcardType);
        }
    }

    public static String getLocalPhoneNum() {
        return sLocalPhoneNum;
    }

    public static String getLocalPhoneName() {
        return sLocalPhoneName;
    }

    public static String getRemoteDeviceName() {
        return sRemoteDeviceName;
    }

    // Used for phone book listing by name
    public static ArrayList<String> getPhonebookNameList() {
        return sVcardManager.loadNameList();
    }

    // Used for phone book listing by number
    public static ArrayList<String> getPhonebookNumberList() {
        return sVcardManager.loadNumberList();
    }

    // Used for call history listing
    public static ArrayList<String> getCallLogList(final int type) {
        return sVcardManager.loadCallHistoryList(type);
    }

    private void notifyAuthKeyInput(final String key) {
        synchronized (mAuth) {
            mAuth.setSessionKey(key);
            mAuth.setChallenged(true);
            mAuth.notify();
        }
    }

    private void notifyAuthCancelled() {
        synchronized (mAuth) {
            mAuth.setCancelled(true);
            mAuth.notify();
        }
    }

    private final boolean initSocket() {
        try {
            // It is mandatory for PSE to support initiation of bonding and
            // encryption.
            mServerSocket = BluetoothServerSocket.listenUsingRfcommOn(PORT_NUM);
        } catch (IOException ex) {
            Log.e(TAG, "initSocket " + ex.toString());
            return false;
        }
        return true;
    }

    private final void startObexServerSession() throws IOException {
        // acquire the wakeLock before start Obex transaction thread
        if (mWakeLock == null) {
            PowerManager pm = (PowerManager)getSystemService(Context.POWER_SERVICE);
            mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    "StartingObexPbapTransaction");
            mWakeLock.setReferenceCounted(false);
            mWakeLock.acquire();
        }
        TelephonyManager tm = (TelephonyManager)getSystemService(Context.TELEPHONY_SERVICE);
        if (tm != null) {
            sLocalPhoneNum = tm.getLine1Number();
            sLocalPhoneName = tm.getLine1AlphaTag();
        }
        mPbapServer = new BluetoothPbapObexServer(mSessionStatusHandler);
        mAuth = new BluetoothPbapAuthenticator(mSessionStatusHandler);
        synchronized (mAuth) {
            mAuth.setChallenged(false);
            mAuth.setCancelled(false);
        }
        BluetoothPbapRfcommTransport transport = new BluetoothPbapRfcommTransport(mConnSocket);
        mServerSession = new ServerSession(transport, mPbapServer, mAuth);
        // TODO: have dependency on framework/base
        // setState(BluetoothPbap.STATE_CONNECTED);
    }

    private final void closeSocket(boolean server, boolean accept) throws IOException {
        if (server == true) {
            if (mServerSocket != null) {
                mServerSocket.close();
            }
            mServerSocket = null;
        }

        if (accept == true) {
            if (mConnSocket != null) {
                mConnSocket.close();
            }
            mConnSocket = null;
        }
    }

    private final void closeService() {
        if (mAcceptThread != null) {
            try {
                mAcceptThread.shutdown();
                mAcceptThread.join();
                mAcceptThread = null;
            } catch (InterruptedException ex) {
                Log.w(TAG, "mAcceptThread close error" + ex);
            }
        }
        if (mServerSession != null) {
            mServerSession.close();
            mServerSession = null;
        }
        try {
            closeSocket(true, true);
        } catch (IOException ex) {
            Log.e(TAG, "Caught the error: " + ex);
        }
        mHasStarted = false;
        BluetoothPbapReceiver.finishStartingService(BluetoothPbapService.this, mStartId);
    }

    private void obexServerSessionClose() {
        // Release the wake lock if obex transaction is over
        if (mWakeLock != null) {
            mWakeLock.release();
            mWakeLock = null;
        }
        mServerSession = null;
        mAcceptThread = null;
        try {
            closeSocket(false, true);
        } catch (IOException e) {
            Log.e(TAG, "Caught the error: " + e.toString());
        }
        // Last obex transaction is finished,we start to listen for incoming
        // connection again
        if (mBluetooth.isEnabled()) {
            startRfcommSocketListener();
        }
        // TODO: have dependency on framework/base
        // setState(BluetoothPbap.STATE_DISCONNECTED);
    }

    /**
     * A thread that runs in the background waiting for remote rfcomm
     * connect.Once a remote socket connected, this thread shall be
     * shutdown.When the remote disconnect,this thread shall run again waiting
     * for next request.
     */
    private class SocketAcceptThread extends Thread {

        private boolean stopped = false;

        private boolean trust;

        @Override
        public void run() {
            while (!stopped) {
                try {
                    mConnSocket = mServerSocket.accept(SOCKET_ACCEPT_TIMEOUT_VALUE);
                } catch (IOException ex) {
                    if (stopped) {
                        break;
                    }
                    if (DBG) {
                        Log.v(TAG, "Caught the error in socketAcceptThread: " + ex);
                    }
                }
                if (mConnSocket != null) {
                    mRemoteAddr = mConnSocket.getAddress();
                    if (mRemoteAddr != null) {
                        sRemoteDeviceName = mBluetooth.getRemoteName(mRemoteAddr);
                        // In case getRemoteName failed and return null
                        if (sRemoteDeviceName == null) {
                            sRemoteDeviceName = getString(R.string.defaultname);
                        }
                    }
                    // TODO: have dependency on device trust feature
                    // implementation
                    // trust = mBluetooth.getTrustState(mRemoteAddr);
                    if (trust) {
                        try {
                            startObexServerSession();
                        } catch (IOException ex) {
                            Log.e(TAG, "catch exception starting obex server session"
                                    + ex.toString());
                        }
                    } else {
                        BluetoothPbapReceiver.makeNewPbapNotification(getApplicationContext(),
                                ACCESS_REQUEST);
                        Log.i(TAG, "incomming connection accepted from" + sRemoteDeviceName);
                        mSessionStatusHandler.sendMessageDelayed(mSessionStatusHandler
                                .obtainMessage(USER_TIMEOUT), USER_CONFIRM_TIMEOUT_VALUE);
                    }
                    stopped = true; // job done ,close this thread;
                }
            }
        }

        void shutdown() {
            stopped = true;
            interrupt();
        }
    }

    private void startRfcommSocketListener() {
        if (mServerSocket == null) {
            if (!initSocket()) {
                closeService();
                return;
            }
        }
        if (mAcceptThread == null) {
            mAcceptThread = new SocketAcceptThread();
            mAcceptThread.setName("BluetoothPbapAcceptThread");
            mAcceptThread.start();
        }
    }

    private void setState(int state) {
        // TODO: have dependency on framework/base
        // setState(state, BluetoothHeadset.RESULT_SUCCESS);
    }

    // TODO: have dependency on framework/base
    /*
     * private synchronized void setState(int state, int result) { if (state !=
     * mState) { if (DBG) Log.d(TAG, "Pbap state " + mState + " -> " + state +
     * ", result = " + result); Intent intent = new
     * Intent(BluetoothPbap.PBAP_STATE_CHANGED_ACTION);
     * intent.putExtra(BluetoothPbap.PBAP_PREVIOUS_STATE, mState); mState =
     * state; intent.putExtra(BluetoothPbap.PBAP_STATE, mState);
     * intent.putExtra(BluetoothIntent.ADDRESS, mRemoteAddr);
     * sendBroadcast(intent, BLUETOOTH_PERM); } }
     */

    /**
     * Handlers for incoming service calls
     */
 // TODO: have dependency on framework/base
    /*
    private final IBluetoothPbap.Stub mBinder = new IBluetoothPbap.Stub() {
        private static final String TAG1 = "IBluetoothPbap.Stub";

        public int getState() {
            if (DBG) {
                Log.d(TAG1, "getState " + mState);
            }
            enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
            return mState;
        }

        public String getPceAddress() {
            if (DBG) {
                Log.d(TAG1, "getPceAddress" + mRemoteAddr);
            }
            enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
            if (mState == BluetoothPbap.STATE_DISCONNECTED) {
                return null;
            }
            return mRemoteAddr;
        }

        public boolean isConnected(String address) {
            enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
            return mState == BluetoothPbap.STATE_CONNECTED && mRemoteAddr.equals(address);
        }

        public boolean connectPce(String address) {
            enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM, "Need BLUETOOTH_ADMIN permission");
            return false;
        }

        public void disconnectPce() {
            if (DBG) {
                Log.d(TAG1, "disconnectPce");
            }
            enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM, "Need BLUETOOTH_ADMIN permission");
            synchronized (BluetoothPbapService.this) {
                switch (mState) {
                    case BluetoothPbap.STATE_CONNECTED:
                        if (mServerSession != null) {
                            mServerSession.close();
                            mServerSession = null;
                        }
                        try {
                            closeSocket(false, true);
                        } catch (IOException ex) {
                            Log.e(TAG1, "Caught the error: " + ex);
                        }
                        setState(BluetoothPbap.STATE_DISCONNECTED, BluetoothPbap.RESULT_CANCELED);
                        break;
                }
            }
        }

        // Not used
        public boolean setPriority(String address, int priority) {
            if (DBG) {
                Log.d(TAG1, "setPriority");
            }
            enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM, "Need BLUETOOTH_ADMIN permission");
            return true;
        }

        // Not used
        public int getPriority(String address) {
            enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
            return 0;
        }
    };
*/
    private final Handler mSessionStatusHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case START_LISTENER:
                    if (mBluetooth.isEnabled()) {
                        startRfcommSocketListener();
                    } else {
                        closeService();// release all resources
                    }
                    break;
                case USER_TIMEOUT:
                    Intent intent = new Intent(USER_CONFIRM_TIMEOUT);
                    sendBroadcast(intent);
                    BluetoothPbapReceiver.removePbapNotification(getApplicationContext(),
                            BluetoothPbapReceiver.NOTIFICATION_ID_ACCESS);
                    obexServerSessionClose();
                    break;
                case AUTH_TIMEOUT:
                    Intent i = new Intent(USER_CONFIRM_TIMEOUT);
                    sendBroadcast(i);
                    BluetoothPbapReceiver.removePbapNotification(getApplicationContext(),
                            BluetoothPbapReceiver.NOTIFICATION_ID_AUTH);
                    notifyAuthCancelled();
                    break;
                case MSG_SERVERSESSION_CLOSE:
                    obexServerSessionClose();
                    mTmpTxt = getString(R.string.toast_disconnected, sRemoteDeviceName);
                    Toast.makeText(BluetoothPbapService.this, mTmpTxt, Toast.LENGTH_SHORT).show();
                    break;
                case MSG_SESSION_ESTABLISHED:
                    mTmpTxt = getString(R.string.toast_connected, sRemoteDeviceName);
                    Toast.makeText(BluetoothPbapService.this, mTmpTxt, Toast.LENGTH_SHORT).show();
                    break;
                case MSG_SESSION_DISCONNECTED:
                    // case MSG_SERVERSESSION_CLOSE will handle ,so just skip
                    break;
                case MSG_OBEX_AUTH_CHALL:
                    BluetoothPbapReceiver.makeNewPbapNotification(getApplicationContext(),
                            AUTH_CHALL);
                    mSessionStatusHandler.sendMessageDelayed(mSessionStatusHandler
                            .obtainMessage(AUTH_TIMEOUT), USER_CONFIRM_TIMEOUT_VALUE);
                    break;
            }
        }
    };

}
