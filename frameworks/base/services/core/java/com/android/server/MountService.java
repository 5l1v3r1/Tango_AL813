/*
* Copyright (C) 2014 MediaTek Inc.
* Modification based on code covered by the mentioned copyright
* and/or permission notice(s).
*/
/*
 * Copyright (C) 2007 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server;

import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import android.app.ActivityManager.RunningAppProcessInfo;
import android.app.AppOpsManager;
import android.Manifest;
import android.app.ActivityManagerNative;
import android.app.AppOpsManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.content.res.Configuration;
import android.content.res.ObbInfo;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Environment;
import android.os.Environment.UserEnvironment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.storage.IMountService;
import android.os.storage.IMountServiceListener;
import android.os.storage.IMountShutdownObserver;
import android.os.storage.IObbActionListener;
import android.os.storage.OnObbStateChangeListener;
import android.os.storage.StorageManager;
import android.os.storage.StorageResultCode;
import android.os.storage.StorageVolume;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Slog;
import android.util.Xml;
import android.util.Log;
import android.widget.Toast;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.IMediaContainerService;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.Preconditions;
import com.android.internal.util.XmlUtils;
import com.android.server.NativeDaemonConnector.Command;
import com.android.server.NativeDaemonConnector.SensitiveArg;
import com.android.server.am.ActivityManagerService;
import com.android.server.pm.PackageManagerService;
import com.android.server.pm.UserManagerService;
import com.google.android.collect.Lists;
import com.google.android.collect.Maps;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.codec.DecoderException;
import org.xmlpull.v1.XmlPullParserException;
import com.mediatek.storage.StorageManagerEx;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.InvalidParameterException;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.KeySpec;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.Set;

import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

//added for factory kit mode. 2013.08.13.yjf.
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
//added ends here.
import android.app.ActivityManager;//add chenwenshuai
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.app.AlertDialog.Builder;
import android.view.WindowManager;
//add chenwenshuai for low speed sdcard notification
import android.app.Notification;
import android.app.NotificationManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
/**
 * MountService implements back-end services for platform storage
 * management.
 * @hide - Applications should use android.os.storage.StorageManager
 * to access the MountService.
 */
class MountService extends IMountService.Stub
        implements INativeDaemonConnectorCallbacks, Watchdog.Monitor {

    // Static direct instance pointer for the tightly-coupled idle service to use
    static MountService sSelf = null;

    public static final String ACTION_ENCRYPTION_TYPE_CHANGED = "com.mediatek.intent.extra.ACTION_ENCRYPTION_TYPE_CHANGED";
    private int mOldEncryptionType = -1;

    // TODO: listen for user creation/deletion

    private static final boolean LOCAL_LOGD = true;
    private static final boolean DEBUG_UNMOUNT = true;
    private static final boolean DEBUG_EVENTS = true;
    private static final boolean DEBUG_OBB = false;

    // Disable this since it messes up long-running cryptfs operations.
    private static final boolean WATCHDOG_ENABLE = false;

    // Change it to config format wipe or not, default set as true
    private static final boolean FORMAT_WIPE = true;

    private static final String TAG = "MountService";

    private static final String VOLD_TAG = "VoldConnector";
    ///usbotg:
    private static final String LABLE_OTG = "usbotg";
    private static final String LABLE_SD = "sdcard";
    private static final int OTG_EVENT605_LENGTH = 13;
    private static final int OTG_EVENT632_LENGTH = 7;

    /** Maximum number of ASEC containers allowed to be mounted. */
    private static final int MAX_CONTAINERS = 250;

	private boolean isBadRemove = false;//chenwenshuai add for Multiple plug reboot
	private int appMoveRef = 0; //chenwenshuai
	private boolean mLowSpeedSDCardFlag = false;
	private static final int LOW_SPEED_SDCARD_NOTIFICATION_ID = 2;//chenwenshuai added for low speed sdcard notify.

    /// M: javaopt_removal @{
    private static final String PROP_SHARED_SDCARD = "ro.mtk_shared_sdcard";
    private static final String PROP_2SDCARD_SWAP = "ro.mtk_2sdcard_swap";
    private static final String PROP_OWNER_SDCARD_SUPPORT = "ro.mtk_owner_sdcard_support";
    private static final String PROP_MULTI_PARTITION = "ro.mtk_multi_patition";
    private static final String PROP_DM_APP = "ro.mtk_dm_app";
    private static final String PROP_VOLD_DECRYPT = "vold.decrypt";    
    /// @}

    /*
     * Internal vold volume state constants
     */
    class VolumeState {
        public static final int Init       = -1;
        public static final int NoMedia    = 0;
        public static final int Idle       = 1;
        public static final int Pending    = 2;
        public static final int Checking   = 3;
        public static final int Mounted    = 4;
        public static final int Unmounting = 5;
        public static final int Formatting = 6;
        public static final int Shared     = 7;
        public static final int SharedMnt  = 8;
    }

    /*
     * BICR
     * Internal CD Rom volume state constants
     */
    class CDRomState {
        public static final int Unknown    = -1;
        public static final int Shared     = 0;
        public static final int Unshared   = 1;
        public static final int Sharing    = 2;
        public static final int Unsharing  = 3;
        public static final int Not_Exist  = 4;
    }

    /*
     * Internal vold response code constants
     */
    class VoldResponseCode {
        /*
         * 100 series - Requestion action was initiated; expect another reply
         *              before proceeding with a new command.
         */
        public static final int VolumeListResult               = 110;
        public static final int AsecListResult                 = 111;
        public static final int StorageUsersListResult         = 112;
        public static final int CryptfsGetfieldResult          = 113;

        /*
         * 200 series - Requestion action has been successfully completed.
         */
        public static final int ShareStatusResult              = 210;
        public static final int AsecPathResult                 = 211;
        public static final int ShareEnabledResult             = 212;
        /// M: BICR @{
        public static final int CdromStatusResult              = 214;
        /// @}

        /*
         * 400 series - Command was accepted, but the requested action
         *              did not take place.
         */
        public static final int OpFailedNoMedia                = 401;
        public static final int OpFailedMediaBlank             = 402;
        public static final int OpFailedMediaCorrupt           = 403;
        public static final int OpFailedVolNotMounted          = 404;
        public static final int OpFailedStorageBusy            = 405;
        public static final int OpFailedStorageNotFound        = 406;

        /*
         * 600 series - Unsolicited broadcasts.
         */
        public static final int VolumeStateChange              = 605;
        public static final int VolumeMountFailedBlank         = 610;
        public static final int VolumeUuidChange               = 613;
        public static final int VolumeUserLabelChange          = 614;
        public static final int VolumeDiskInserted             = 630;
        public static final int VolumeDiskRemoved              = 631;
        public static final int VolumeBadRemoval               = 632;

        // M : ALPS00446260
        public static final int VolumeEjectBeforeSwap          = 633 ;
        // M : ALPS00446260
        public static final int VolumeUnmountable          = 634 ;

        /*
         * 700 series - fstrim
         */
        public static final int FstrimCompleted                = 700;
    }

    /** List of crypto types.
      * These must match CRYPT_TYPE_XXX in cryptfs.h AND their
      * corresponding commands in CommandListener.cpp */
    public static final String[] CRYPTO_TYPES
        = { "password", "default", "pattern", "pin" };

    private final Context mContext;
    private final NativeDaemonConnector mConnector;

    private final Object mVolumesLock = new Object();

    /** When defined, base template for user-specific {@link StorageVolume}. */
    private StorageVolume mEmulatedTemplate;

    // TODO: separate storage volumes on per-user basis

    @GuardedBy("mVolumesLock")
    private final ArrayList<StorageVolume> mVolumes = Lists.newArrayList();
    /** Map from path to {@link StorageVolume} */
    @GuardedBy("mVolumesLock")
    private final HashMap<String, StorageVolume> mVolumesByPath = Maps.newHashMap();
    /** Map from path to state */
    @GuardedBy("mVolumesLock")
    private final HashMap<String, String> mVolumeStates = Maps.newHashMap();

    private volatile boolean mSystemReady = false;

    private PackageManagerService                 mPms;
    private boolean                               mUmsEnabling;
    private boolean                               mUmsAvailable = false;
    // Used as a lock for methods that register/unregister listeners.
    final private ArrayList<MountServiceBinderListener> mListeners =
            new ArrayList<MountServiceBinderListener>();
    private final CountDownLatch mConnectedSignal = new CountDownLatch(1);
    private final CountDownLatch mAsecsScanned = new CountDownLatch(1);
    private boolean                               mSendUmsConnectedOnBoot = false;
    /// M: Add  some objects  for new feature or bug fix @{
    private static final String                   EXTERNAL_SD1 = (SystemProperties.get(PROP_SHARED_SDCARD).equals("1") && !SystemProperties.get(PROP_2SDCARD_SWAP).equals("1")) ? "/storage/emulated/0" : "/storage/sdcard0";
    private static final String                   EXTERNAL_SD2 = "/storage/sdcard1";
    private static final String                   EXTERNAL_OTG = Environment.DIRECTORY_USBOTG;
    private static final String                   LABEL_SD1 = "sdcard0"; //internal storage
    private static final String                   LABEL_SD2 = "sdcard1"; //external storage
    // by MTP request
    private static final int                      MTP_RESERVE_SPACE = 10;
    private static final long                     MAX_FILE_SIZE     = (((long) 4) * 1024 * 1024 * 1024 - 1);
    // before unmount, send MEDIA_EJECT and wait some time to let APP release their fd in sd card
    private static final int                      MEDIA_EJECT_TIME = 1500;
    private static final int                      MEDIA_EJECT_SHUTDOWN_TIME = 500;
    private boolean                               mIsAnyAllowUMS = false;
    private int                                   mShutdownCount = 0;
    private static final Object                   TURNONUSB_SYNC_LOCK = new Object();
    private static final String                   BOOT_IPO = "android.intent.action.ACTION_BOOT_IPO";
    private static final String                   MOUNT_UNMOUNT_ALL = "mount_unmount_all";
    private static final String                   FIRST_BOOT_MOUNTED = "first_boot_mounted";
    private static final String                   INSERT_OTG = "insert_otg";
    private boolean                               mBootCompleted = false;
    private boolean                               mShutdownSD = false;
    private int                                   mShutdownRet = StorageResultCode.OperationSucceeded;
    private boolean                               mSD1BootMounted = false;
    private boolean                               mSD2BootMounted = false;
    // this will set to true when user is turning on UMS or turning off UMS
    private boolean                               mIsTurnOnOffUsb = false;
    private boolean                               mIsUsbConnected = true;
    private int                                   mUMSCount = 0;
    private boolean                               mSetDefaultEnable = false;
    private boolean                               mMountAll = false;
    private boolean                               mUnmountPrimary = false;
    // SD swap
    private static final String                   PROP_SD_SWAP = "vold.swap.state";
    private static final String                   PROP_SD_SWAP_TRUE = "1";
    private static final String                   PROP_SD_SWAP_FALSE = "0";
    private static final String                   PROP_SWAPPING = "sys.sd.swapping";
    private static final String                   PROP_UNMOUNTING = "sys.sd.unmounting";
    private StorageVolume                         mVolumePrimary;
    private StorageVolume                         mVolumeSecondary;
    private boolean                               mMountSwap = false;
    private boolean                               mUnmountSwap = false;
    private static final String                   INTENT_SD_SWAP = "com.mediatek.SD_SWAP";
    private static final String                   SD_EXIST       = "SD_EXIST";
    private boolean                               mFirstTimeSDSwapIntent = true;
    private boolean                               mSwapStateForSDSwapIntent = false;
    private boolean                               mSwapStateForSDSwapMountPoint = false;
    private boolean                               mFirstTime_SwapStateForSDSwapMountPoint = true; // force to swap mount point when boot/ipo start
    // OMA DM support
    private static final String OMADM_USB_ENABLE =  "com.mediatek.dm.LAWMO_UNLOCK";
    private static final String OMADM_USB_DISABLE = "com.mediatek.dm.LAWMO_LOCK";
    private static final String OMADM_SD_FORMAT =   "com.mediatek.dm.LAWMO_WIPE";
    private static final Object OMADM_SYNC_LOCK = new Object();
    /// @}

    // Privacy Protection
    private static final String PRIVACY_PROTECTION_LOCK =   "com.mediatek.ppl.NOTIFY_LOCK";
    private static final String PRIVACY_PROTECTION_UNLOCK = "com.mediatek.ppl.NOTIFY_UNLOCK";
    private static final String PRIVACY_PROTECTION_WIPE =   "com.mediatek.ppl.NOTIFY_MOUNT_SERVICE_WIPE";
    private static final String PRIVACY_PROTECTION_WIPE_DONE =   "com.mediatek.ppl.MOUNT_SERVICE_WIPE_RESPONSE";

    /// M: sdcard&otg only for owner @{
    private int mOldUserId = -1;
    private int mUserId = -1;
    private boolean mUmountSdByUserSwitch;
    /// @}

    /**
     * Private hash of currently mounted secure containers.
     * Used as a lock in methods to manipulate secure containers.
     */
    final private HashSet<String> mAsecMountSet = new HashSet<String>();

    /**
     * The size of the crypto algorithm key in bits for OBB files. Currently
     * Twofish is used which takes 128-bit keys.
     */
    private static final int CRYPTO_ALGORITHM_KEY_SIZE = 128;

    /**
     * The number of times to run SHA1 in the PBKDF2 function for OBB files.
     * 1024 is reasonably secure and not too slow.
     */
    private static final int PBKDF2_HASH_ROUNDS = 1024;

    /**
     * Mounted OBB tracking information. Used to track the current state of all
     * OBBs.
     */
    final private Map<IBinder, List<ObbState>> mObbMounts = new HashMap<IBinder, List<ObbState>>();

    /** Map from raw paths to {@link ObbState}. */
    final private Map<String, ObbState> mObbPathToStateMap = new HashMap<String, ObbState>();

    class ObbState implements IBinder.DeathRecipient {
        public ObbState(String rawPath, String canonicalPath, int callingUid,
                IObbActionListener token, int nonce) {
            this.rawPath = rawPath;
            this.canonicalPath = canonicalPath.toString();

            final int userId = UserHandle.getUserId(callingUid);
            this.ownerPath = buildObbPath(canonicalPath, userId, false);
            this.voldPath = buildObbPath(canonicalPath, userId, true);

            this.ownerGid = UserHandle.getSharedAppGid(callingUid);
            this.token = token;
            this.nonce = nonce;
        }

        final String rawPath;
        final String canonicalPath;
        final String ownerPath;
        final String voldPath;

        final int ownerGid;

        // Token of remote Binder caller
        final IObbActionListener token;

        // Identifier to pass back to the token
        final int nonce;

        public IBinder getBinder() {
            return token.asBinder();
        }

        @Override
        public void binderDied() {
            ObbAction action = new UnmountObbAction(this, true);
            mObbActionHandler.sendMessage(mObbActionHandler.obtainMessage(OBB_RUN_ACTION, action));
        }

        public void link() throws RemoteException {
            getBinder().linkToDeath(this, 0);
        }

        public void unlink() {
            getBinder().unlinkToDeath(this, 0);
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("ObbState{");
            sb.append("rawPath=").append(rawPath);
            sb.append(",canonicalPath=").append(canonicalPath);
            sb.append(",ownerPath=").append(ownerPath);
            sb.append(",voldPath=").append(voldPath);
            sb.append(",ownerGid=").append(ownerGid);
            sb.append(",token=").append(token);
            sb.append(",binder=").append(getBinder());
            sb.append('}');
            return sb.toString();
        }
    }

    // OBB Action Handler
    final private ObbActionHandler mObbActionHandler;

    // OBB action handler messages
    private static final int OBB_RUN_ACTION = 1;
    private static final int OBB_MCS_BOUND = 2;
    private static final int OBB_MCS_UNBIND = 3;
    private static final int OBB_MCS_RECONNECT = 4;
    private static final int OBB_FLUSH_MOUNT_STATE = 5;

    /*
     * Default Container Service information
     */
    static final ComponentName DEFAULT_CONTAINER_COMPONENT = new ComponentName(
            "com.android.defcontainer", "com.android.defcontainer.DefaultContainerService");

    final private DefaultContainerConnection mDefContainerConn = new DefaultContainerConnection();

    class DefaultContainerConnection implements ServiceConnection {
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (DEBUG_OBB)
                Slog.i(TAG, "onServiceConnected");
            IMediaContainerService imcs = IMediaContainerService.Stub.asInterface(service);
            mObbActionHandler.sendMessage(mObbActionHandler.obtainMessage(OBB_MCS_BOUND, imcs));
        }

        public void onServiceDisconnected(ComponentName name) {
            if (DEBUG_OBB)
                Slog.i(TAG, "onServiceDisconnected");
        }
    };

    // Used in the ObbActionHandler
    private IMediaContainerService mContainerService = null;

    // Last fstrim operation tracking
    private static final String LAST_FSTRIM_FILE = "last-fstrim";
    private final File mLastMaintenanceFile;
    private long mLastMaintenance;

    // Handler messages
    private static final int H_UNMOUNT_PM_UPDATE = 1;
    private static final int H_UNMOUNT_PM_DONE = 2;
    private static final int H_UNMOUNT_MS = 3;
    private static final int H_SYSTEM_READY = 4;
    private static final int H_FSTRIM = 5;
	//chenwenshuai add
    private static final int H_UPDATE_APP_START = 7;
    private static final int H_UPDATE_APP_END = 8;
    private static final int H_UNLOAD_APP_END = 9;
	private static final int H_UNMOUNT_CLEAR_PM = 10;

    private static final int RETRY_UNMOUNT_DELAY = 30; // in ms
    private static final int MAX_UNMOUNT_RETRIES = 4;

    class UnmountCallBack {
        final String path;
        final boolean force;
        final boolean removeEncryption;
        final boolean byUserSwitch;
        int retries;

        UnmountCallBack(String path, boolean force, boolean removeEncryption) {
            retries = 0;
            this.path = path;
            this.force = force;
            this.removeEncryption = removeEncryption;
            this.byUserSwitch = false;
        }

        UnmountCallBack(String path, boolean force, boolean removeEncryption, boolean byUserSwitch) {
            retries = 0;
            this.path = path;
            this.force = force;
            this.removeEncryption = removeEncryption;
            this.byUserSwitch = byUserSwitch;
        }
        void handleFinished() {
            if (DEBUG_UNMOUNT) Slog.i(TAG, "Unmounting " + path);
            /// M: sdcard&otg only for owner @{
            if (SystemProperties.get(PROP_OWNER_SDCARD_SUPPORT).equals("1") && byUserSwitch) {
                doUnmountVolumeForUserSwitch(path, true, removeEncryption);
            } else {
            /// @}
                doUnmountVolume(path, true, removeEncryption);
            /// M: sdcard&otg only for owner @{
            }
            /// @}
            if (SystemProperties.get(PROP_2SDCARD_SWAP).equals("1") && mUnmountSwap) {
                try {
                    SystemProperties.set(PROP_SWAPPING, "0");
                } catch (IllegalArgumentException e) {
                    Slog.e(TAG, "IllegalArgumentException when set default path:", e);
                }
                doSDSwapVolumeUpdate();
                mUnmountSwap = false;
                updateDefaultpath();
                sendSDSwapIntent();
            }
        }
    }

    class UmsEnableCallBack extends UnmountCallBack {
        final String method;

        UmsEnableCallBack(String path, String method, boolean force) {
            super(path, force, false);
            this.method = method;
        }

        @Override
        void handleFinished() {
            if (!mIsUsbConnected) {
                StorageVolume volume = null;
                synchronized (mVolumesLock) {
                    volume = mVolumesByPath.get(this.path);
                }
                updatePublicVolumeState(volume, Environment.MEDIA_CHECKING);
                sendStorageIntent(Intent.ACTION_MEDIA_CHECKING, volume, UserHandle.ALL);
                updatePublicVolumeState(volume, Environment.MEDIA_MOUNTED);
                sendStorageIntent(Intent.ACTION_MEDIA_MOUNTED, volume, UserHandle.ALL);
            } else {
                synchronized (TURNONUSB_SYNC_LOCK) {
                    boolean unmountSwap = mUnmountSwap;
                    super.handleFinished();

                    // share SD & Turn off UMS case, external sd will shift to storage/sdcard1
                    if (unmountSwap) {
                        doShareUnshareVolume(EXTERNAL_SD2 , method, true);
                    } else {
                        doShareUnshareVolume(path, method, true);
                    }
                }
            }
            mUMSCount--;
            if (mUMSCount <= 0) {
                new Thread() {
                    public void run() {
                        SystemClock.sleep(300);
                        mIsTurnOnOffUsb = false;
                    }
                } .start();
                mUnmountPrimary = false;
            }
        }
    }

    class ShutdownCallBack extends UnmountCallBack {
        MountShutdownLatch mMountShutdownLatch;
        ShutdownCallBack(String path, final MountShutdownLatch mountShutdownLatch) {
            super(path, true, false);
            mMountShutdownLatch = mountShutdownLatch;
        }

        @Override
        void handleFinished() {
            int ret = doUnmountVolume(path, true, removeEncryption);
            Slog.i(TAG, "Unmount completed: " + path + ", result code: " + ret);
            mMountShutdownLatch.countDown();

            mShutdownRet = mShutdownRet | ret;
            mShutdownCount--;
            if (mShutdownCount <= 0) {
                mShutdownSD = false;
                mUnmountPrimary = false;
                mShutdownRet = StorageResultCode.OperationSucceeded;
            }
        }
    }

    static class MountShutdownLatch {
        private IMountShutdownObserver mObserver;
        private AtomicInteger mCount;

        MountShutdownLatch(final IMountShutdownObserver observer, int count) {
            mObserver = observer;
            mCount = new AtomicInteger(count);
        }

        void countDown() {
            boolean sendShutdown = false;
            if (mCount.decrementAndGet() == 0) {
                sendShutdown = true;
            }
            if (sendShutdown && mObserver != null) {
                try {
                    mObserver.onShutDownComplete(StorageResultCode.OperationSucceeded);
                } catch (RemoteException e) {
                    Slog.w(TAG, "RemoteException when shutting down");
                }
            }
        }
    }

    class MountServiceHandler extends Handler {
        ArrayList<UnmountCallBack> mForceUnmounts = new ArrayList<UnmountCallBack>();
        boolean mUpdatingStatus = false;

        MountServiceHandler(Looper l) {
            super(l);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case H_UNMOUNT_PM_UPDATE: {
                    if (DEBUG_UNMOUNT) Slog.i(TAG, "H_UNMOUNT_PM_UPDATE");
                    UnmountCallBack ucb = (UnmountCallBack) msg.obj;
                    StorageVolume volume = null;
                    synchronized (mVolumesLock) {
                        volume = mVolumesByPath.get(ucb.path);
                    }
                    if (volume == null) {
                        Slog.e(TAG, "H_UNMOUNT_PM_UPDATE volume is not exist!");
                        break;
                    }
                    /// M: sdcard&otg only for owner @{
                    if (SystemProperties.get(PROP_OWNER_SDCARD_SUPPORT).equals("1") &&
                        ucb.byUserSwitch &&
                        mUserId == UserHandle.USER_OWNER) {
                        Slog.i(TAG, "H_UNMOUNT_PM_UPDATE stop unmount because of user changed to owner");

                        updatePublicVolumeState(volume, Environment.MEDIA_MOUNTED);
                        if (mSetDefaultEnable && !mIsTurnOnOffUsb && !mMountSwap && !mUnmountSwap) {
                            Slog.i(TAG, "updateDefaultpath Environment.MEDIA_MOUNTED");
                            updateDefaultpath();
                        }
                        sendStorageIntent(Intent.ACTION_MEDIA_MOUNTED, volume, UserHandle.ALL);
                        break;
                    }
                    /// @}
                    sendStorageIntent(Intent.ACTION_MEDIA_EJECT, volume, UserHandle.ALL);
                    if (SystemProperties.get(PROP_2SDCARD_SWAP).equals("1") && mUnmountSwap && !ucb.path.equals(EXTERNAL_SD2)) {
                        StorageVolume swapVolume = null;
                        synchronized (mVolumesLock) {
                            swapVolume = mVolumesByPath.get(EXTERNAL_SD2);
                        }
                        sendStorageIntent(Intent.ACTION_MEDIA_EJECT, swapVolume, UserHandle.ALL);
                    }
                    if (mShutdownCount > 0) {
                        SystemClock.sleep(MEDIA_EJECT_SHUTDOWN_TIME);
                    } else {
                        SystemClock.sleep(MEDIA_EJECT_TIME);
                    }

                    mForceUnmounts.add(ucb);
                    if (DEBUG_UNMOUNT) Slog.i(TAG, " registered = " + mUpdatingStatus);
                    // Register only if needed.
                    if (!mUpdatingStatus) {
                        if (DEBUG_UNMOUNT) Slog.i(TAG, "Updating external media status on PackageManager");
                        if (ucb.path.equals(EXTERNAL_SD1)) {
                            mUpdatingStatus = true;
                            mPms.updateExternalMediaStatus(false, true);
                        } else if (!mUnmountPrimary) {
                            //if shutdown or turn on UMS, and mnt/sdcard need unmount
                            //do not send message until PMS done
                            finishMediaUpdate();
                        }
                    }
                    break;
                }
                case H_UNMOUNT_PM_DONE: {
                    if (DEBUG_UNMOUNT) Slog.i(TAG, "H_UNMOUNT_PM_DONE");
                    if (DEBUG_UNMOUNT) Slog.i(TAG, "Updated status. Processing requests");
                    mUpdatingStatus = false;
                    int size = mForceUnmounts.size();
                    int sizeArr[] = new int[size];
                    int sizeArrN = 0;
                    // Kill processes holding references first
                    ActivityManagerService ams = (ActivityManagerService)
                    ServiceManager.getService("activity");
                    for (int i = 0; i < size; i++) {
                        UnmountCallBack ucb = mForceUnmounts.get(i);
                        /// M: sdcard&otg only for owner @{
                        StorageVolume volume = null;
                        synchronized (mVolumesLock) {
                            volume = mVolumesByPath.get(ucb.path);
                        }
                        if (volume == null) {
                            Slog.e(TAG, "H_UNMOUNT_PM_DONE volume is not exist!");
                            sizeArr[sizeArrN++] = i;
                            continue;
                        }
                        if (SystemProperties.get(PROP_OWNER_SDCARD_SUPPORT).equals("1") &&
                            ucb.byUserSwitch &&
                            mUserId == UserHandle.USER_OWNER) {
                            sizeArr[sizeArrN++] = i;
                            Slog.i(TAG, "H_UNMOUNT_PM_DONE stop unmount because of user changed to owner");

                            updatePublicVolumeState(volume, Environment.MEDIA_MOUNTED);
                            if (mSetDefaultEnable && !mIsTurnOnOffUsb && !mMountSwap && !mUnmountSwap) {
                                Slog.i(TAG, "updateDefaultpath Environment.MEDIA_MOUNTED");
                                updateDefaultpath();
                            }
                            sendStorageIntent(Intent.ACTION_MEDIA_MOUNTED, volume, UserHandle.ALL);
                            continue;
                        }
                        /// @}
                        String path = ucb.path;
                        boolean needKill = false;
                        boolean done = false;
                        if (!ucb.force) {
                            done = true;
                        } else {
                            int pids[] = getStorageUsers(path);
                            if (pids == null || pids.length == 0) {
                                done = true;
                            } else if (ams != null) {
                                List<RunningAppProcessInfo> runningList = ams.getRunningAppProcesses();
                                if (runningList != null) {
                                    int len = pids.length;
                                    for (int k = 0; k < len; k++) {
                                        if (needKill) {
                                            break;
                                        } else {
                                            for (RunningAppProcessInfo p : runningList) {
                                                if (p.pid == pids[k]) {
                                                    needKill = true;
                                                    Slog.i(TAG, "java process, need kill!");
                                                    break;
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    Slog.i(TAG, "runningList from AMS is null!");
                                }

                                if (needKill) {
                                    // Eliminate system process here?
                                    ams.killPids(pids, "unmount media", true);
                                    // Confirm if file references have been freed.
                                    pids = getStorageUsers(path);
                                    if (pids == null || pids.length == 0) {
                                        done = true;
                                    }
                                } else {
                                    Slog.i(TAG, "all native process, don't need kill!");
                                    done = true;
                                }
                            } else {
                                Slog.e(TAG, "Fail to get AMS while unmount!");
                            }
                        }
                        if (!done && (ucb.retries < MAX_UNMOUNT_RETRIES)) {
                            // Retry again
                            Slog.i(TAG, "Retrying to kill storage users again");
                            mHandler.sendMessageDelayed(
                                    mHandler.obtainMessage(H_UNMOUNT_PM_DONE,
                                            ucb.retries++),
                                    RETRY_UNMOUNT_DELAY);
                        } else {
                            if (ucb.retries >= MAX_UNMOUNT_RETRIES) {
                                Slog.i(TAG, "Failed to unmount media inspite of " +
                                        MAX_UNMOUNT_RETRIES + " retries. Forcibly killing processes now");
                            }
                            sizeArr[sizeArrN++] = i;
                            //mHandler.sendMessage(mHandler.obtainMessage(H_UNMOUNT_MS,
                            //        ucb));
                            /*chenwenshuai modify */
                            mHandler.sendMessage(mHandler.obtainMessage(H_UNMOUNT_CLEAR_PM,
                                    ucb));
                        }
                    }
                    // Remove already processed elements from list.
                    for (int i = (sizeArrN-1); i >= 0; i--) {
                        mForceUnmounts.remove(sizeArr[i]);
                    }
                    break;
                }
				/*chenwenshuai modify for HQ01039542 */
				case H_UNMOUNT_CLEAR_PM:
					UnmountCallBack ucb1 = (UnmountCallBack) msg.obj;
					if (ucb1.path.equals(EXTERNAL_SD2)) {
            			mPms.updateExternalMediaStatus(false, false);
        			}
					mHandler.sendMessage(mHandler.obtainMessage(H_UNMOUNT_MS,
                                    ucb1));
				
                case H_UNMOUNT_MS: {
                    if (DEBUG_UNMOUNT) Slog.i(TAG, "H_UNMOUNT_MS");
                    UnmountCallBack ucb = (UnmountCallBack) msg.obj;
                    /// M: sdcard&otg only for owner @{
                    StorageVolume volume = null;
                    synchronized (mVolumesLock) {
                        volume = mVolumesByPath.get(ucb.path);
                    }
                    if (volume == null) {
                        Slog.e(TAG, "H_UNMOUNT_MS volume is not exist!");
                        break;
                    }
                    if (SystemProperties.get(PROP_OWNER_SDCARD_SUPPORT).equals("1") &&
                        ucb.byUserSwitch &&
                        mUserId == UserHandle.USER_OWNER) {
                        Slog.i(TAG, "H_UNMOUNT_MS stop unmount because of user changed to owner");

                        updatePublicVolumeState(volume, Environment.MEDIA_MOUNTED);
                        if (mSetDefaultEnable && !mIsTurnOnOffUsb && !mMountSwap && !mUnmountSwap) {
                            Slog.i(TAG, "updateDefaultpath Environment.MEDIA_MOUNTED");
                            updateDefaultpath();
                        }
                        sendStorageIntent(Intent.ACTION_MEDIA_MOUNTED, volume, UserHandle.ALL);
                        break;
                    }
                    /// @}
                    ucb.handleFinished();
                    break;
                }
                case H_SYSTEM_READY: {
                    try {
                        handleSystemReady();
                    } catch (Exception ex) {
                        Slog.e(TAG, "Boot-time mount exception", ex);
                    }
                    break;
                }
                case H_FSTRIM: {
                    waitForReady();
                    Slog.i(TAG, "Running fstrim idle maintenance");

                    // Remember when we kicked it off
                    try {
                        mLastMaintenance = System.currentTimeMillis();
                        mLastMaintenanceFile.setLastModified(mLastMaintenance);
                    } catch (Exception e) {
                        Slog.e(TAG, "Unable to record last fstrim!");
                    }

                    try {
                        // This method must be run on the main (handler) thread,
                        // so it is safe to directly call into vold.
                        mConnector.execute("fstrim", "dotrim");
                        EventLogTags.writeFstrimStart(SystemClock.elapsedRealtime());
                    } catch (NativeDaemonConnectorException ndce) {
                        Slog.e(TAG, "Failed to run fstrim!");
                    }

                    // invoke the completion callback, if any
                    Runnable callback = (Runnable) msg.obj;
                    if (callback != null) {
                        callback.run();
                    }
                    break;
                }
				/*chenwenshuai add for HQ00884925 begin*/
		       case H_UPDATE_APP_START:{
	           			handlStartUpdateApp();
						break;
				}
			 	case H_UPDATE_APP_END:{
						handlEndUpdateApp();
					    break;
				}
				case H_UNLOAD_APP_END:{
					    handlEndUnloadApp();
						break;
				}
				/*chenwenshuai add end*/
            }
        }
    };

    private final Handler mHandler;

    void waitForAsecScan() {
        waitForLatch(mAsecsScanned);
    }

    private void waitForReady() {
        waitForLatch(mConnectedSignal);
    }

    private void waitForLatch(CountDownLatch latch) {
        for (;;) {
            try {
                if (latch.await(5000, TimeUnit.MILLISECONDS)) {
                    return;
                } else {
                    Slog.w(TAG, "Thread " + Thread.currentThread().getName()
                            + " still waiting for MountService ready...");
                }
            } catch (InterruptedException e) {
                Slog.w(TAG, "Interrupt while waiting for MountService to be ready.");
            }
        }
    }

    private boolean isReady() {
        try {
            return mConnectedSignal.await(0, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            return false;
        }
    }

    private void handleSystemReady() {
        // Snapshot current volume states since it's not safe to call into vold
        // while holding locks.
        Slog.i(TAG, "handleSystemReady");
        final HashMap<String, String> snapshot;
        synchronized (mVolumesLock) {
            snapshot = new HashMap<String, String>(mVolumeStates);
        }
        mMountAll = true;

        // Damage external sd card may cost long time to do mount
        // this may block intermal sd card mount time
        // so we force to mount internal sd card first, also shoud consider swap case.
        String[] paths;
        String[] rawPaths;
        String[] states;
        int count;
        Set<String> keys = snapshot.keySet();
        count = keys.size();
        rawPaths = keys.toArray(new String[count]); //[storage/sdcard1, storage/sdcard0]
        paths = new String[count];
        states = new String[count];
        if (SystemProperties.get(PROP_2SDCARD_SWAP).equals("1")) {
            paths = rawPaths;
            for (int i = 0; i < count; i++) {
                states[i] = snapshot.get(paths[i]);
            }
        } else {
            for (int i = 0; i < count; i++) {
                paths[i] = rawPaths[count - 1 - i];
                states[i] = snapshot.get(paths[i]);
            }
        }

        //for (Map.Entry<String, String> entry : snapshot.entrySet()) {
        for (int i = 0; i < count; i++) {
            final String path = paths[i];
            final String state = states[i];

            if (state.equals(Environment.MEDIA_UNMOUNTED)) {
                /*
                 * if platform support multi partition,
                 * volume may become null after otg plug out
                 */                
                try {
                    if (path.equals(EXTERNAL_SD1)) {
                        mSD1BootMounted = true;
                        Slog.i(TAG, "mSD1BootMounted = true");
                    } else if (path.equals(EXTERNAL_SD2)) {
                        mSD2BootMounted = true;
                        Slog.i(TAG, "mSD2BootMounted = true");
                    }
                    int rc = doMountVolume(path);
                    if (rc != StorageResultCode.OperationSucceeded) {
                        Slog.e(TAG, String.format("Boot-time mount failed (%d)",
                                rc));
                    }
                    doSDSwapVolumeUpdate();
                    //updateDefaultpath();
                    sendSDSwapIntent();
                } catch (Exception ex) {
                    Slog.w(TAG, "Failed to mount media on boot", ex);
                }
            } else if (state.equals(Environment.MEDIA_SHARED)) {
                /*
                 * Bootstrap UMS enabled state since vold indicates
                 * the volume is shared (runtime restart while ums enabled)
                 */
                notifyVolumeStateChange(null, path, VolumeState.NoMedia,
                        VolumeState.Shared);
            }
        }

        // Push mounted state for all emulated storage
        synchronized (mVolumesLock) {
            for (StorageVolume volume : mVolumes) {
                if (volume.isEmulated()) {
                    updatePublicVolumeState(volume, Environment.MEDIA_MOUNTED);
                }
            }
        }

        /*
         * If UMS was connected on boot, send the connected event
         * now that we're up.
         */
        if (mSendUmsConnectedOnBoot) {
            sendUmsIntent(true);
            mSendUmsConnectedOnBoot = false;
        }

        // Because timing issue, onEvent may come late than doMountVolume return
        // so this can cause defaultPath change, we force updateDefaultpath() do later..
        new Thread() {
            public void run() {
                SystemClock.sleep(2000);
                updateDefaultpath();
            }
        } .start();
        mSetDefaultEnable = true;
        mMountAll = false;
        mUnmountPrimary = false;

        /*
         * Start scheduling nominally-daily fstrim operations
         */
        MountServiceIdler.scheduleIdlePass(mContext);
    }

    private final BroadcastReceiver mUserReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, -1);
            if (userId == -1) return;
            final UserHandle user = new UserHandle(userId);

            final String action = intent.getAction();
            if (Intent.ACTION_USER_ADDED.equals(action)) {
                synchronized (mVolumesLock) {
                    createEmulatedVolumeForUserLocked(user);
                }

            } else if (Intent.ACTION_USER_REMOVED.equals(action)) {
                synchronized (mVolumesLock) {
                    final List<StorageVolume> toRemove = Lists.newArrayList();
                    for (StorageVolume volume : mVolumes) {
                        if (user.equals(volume.getOwner())) {
                            toRemove.add(volume);
                        }
                    }
                    for (StorageVolume volume : toRemove) {
                        removeVolumeLocked(volume);
                    }
                }
            /// M: multiple user @{
            } else if (Intent.ACTION_USER_SWITCHED.equals(action)) {
                /// M: sdcard&otg only for owner @{
                mOldUserId = mUserId;
                mUserId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, UserHandle.USER_OWNER);
                if (SystemProperties.get(PROP_OWNER_SDCARD_SUPPORT).equals("1")) {
                    handleUserSwitch();
                }
                /// @}
            }
            /// @}
        }
    };

    private final BroadcastReceiver mUsbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            boolean available = (intent.getBooleanExtra(UsbManager.USB_CONNECTED, false) &&
                    intent.getBooleanExtra(UsbManager.USB_FUNCTION_MASS_STORAGE, false) &&
                    !intent.getBooleanExtra("SettingUsbCharging", false));
            mIsUsbConnected = available;
            notifyShareAvailabilityChange(available);
        }
    };

    // M: add for OMA DM
    private final BroadcastReceiver mDMReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(OMADM_USB_ENABLE)) {
                Slog.e(TAG, "USB enable");
                new Thread() {
                    public void run() {
                        synchronized (OMADM_SYNC_LOCK) {
                            enableUSBFuction(true);
                        }
                    }
                } .start();
            } else if (action.equals(OMADM_USB_DISABLE)) {
                Slog.e(TAG, "USB disable");
                new Thread() {
                    public void run() {
                        synchronized (OMADM_SYNC_LOCK) {
                           enableUSBFuction(false);
                        }
                    }
                } .start();
            } else if (action.equals(OMADM_SD_FORMAT)) {
                Slog.e(TAG, "format SD");
                new Thread() {
                    public void run() {
                        synchronized (OMADM_SYNC_LOCK) {
                            int length = mVolumes.size();
                            String path = null;
                            for (int i = 0; i < length; i++) {
                                path = mVolumes.get(i).getPath();
                                try {
                                    String mCurState = getVolumeState(path);
                                    Slog.e(TAG, mCurState);
                                    if (mCurState.equals(Environment.MEDIA_MOUNTED)) {
                                        if (SystemProperties.get(PROP_2SDCARD_SWAP).equals("1")) {
                                            unmountVolumeNotSwap(path, true, true);
                                        } else {
                                            unmountVolume(path, true, true);
                                        }
                                            //wait for unmount succeed...
                                        for (int j = 0; j < 20; j++) {
                                            sleep(1000);
                                            mCurState = getVolumeState(path);
                                            if (mCurState.equals(Environment.MEDIA_UNMOUNTED)) {
                                                Slog.e(TAG, "Unmount Succeeded!");
                                                break;
                                            }
                                        }
                                    }
                                    else if (mCurState.equals(Environment.MEDIA_SHARED)) {
                                        doShareUnshareVolume(path, "ums", false);
                                    }

                                    int ret = formatVolume(path);
                                    if (StorageResultCode.OperationSucceeded == ret) {
                                        Slog.e(TAG, "SD format Succeed!");
                                    } else {
                                        Slog.e(TAG, "SD format Failed!");
                                    }
                                } catch (InterruptedException e) {
                                    Slog.e(TAG, "SD format exception", e);
                                } catch (IllegalArgumentException x) {
                                    Slog.e(TAG, "SD format exception", x);
                                } catch (SecurityException x) {
                                    Slog.e(TAG, "SD format exception", x);
                                } catch (NullPointerException e) {
                                    Slog.e(TAG, "SD format exception", e);
                                }
                            }
                        }
                    }
                } .start();
            }
        }
    };

    // M: Privacy Protection
    private final BroadcastReceiver mPrivacyProtectionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(PRIVACY_PROTECTION_UNLOCK)) {
                Slog.i(TAG, "Privacy Protection unlock!");
                new Thread() {
                    public void run() {
                        enableUSBFuction(true);
                    }
                } .start();
            } else if (action.equals(PRIVACY_PROTECTION_LOCK)) {
                Slog.i(TAG, "Privacy Protection lock!");
                new Thread() {
                    public void run() {
                        enableUSBFuction(false);
                    }
                } .start();
            } else if (action.equals(PRIVACY_PROTECTION_WIPE)) {
                Slog.i(TAG, "Privacy Protection wipe!");
                new Thread() {
                    public void run() {
                        ArrayList<StorageVolume> tempVolumes = Lists.newArrayList();
                        synchronized (mVolumesLock) {
                            for (StorageVolume volume : mVolumes) {
                                if (!volume.getPath().equals(EXTERNAL_OTG) && !volume.isEmulated()) {
                                    tempVolumes.add(volume);
                                }
                            }
                        }

                        String state = null;
                        String path = null;
                        for (StorageVolume volume : tempVolumes) {
                            path = volume.getPath();
                            state = getVolumeState(path);
                            Slog.i(TAG, "Privacy Protection wipe: path " + path + "is " + state);
                            // first need to wait leave checking state if needed
                            if (state.equals(Environment.MEDIA_CHECKING)) {
                                Slog.i(TAG, "Privacy Protection wipe: path " + path + "is checking, wait..");
                                for (int i = 0; i < 30; i++) {
                                    try {
                                        sleep(1000);
                                    } catch (InterruptedException ex) {
                                        Slog.e(TAG, "Exception when wait!", ex);
                                    }
                                    state = getVolumeState(path);
                                    if (!state.equals(Environment.MEDIA_CHECKING)) {
                                        Slog.i(TAG, "Privacy Protection wipe: wait checking done!");
                                        break;
                                    }
                                }
                            }
                            // then unmount if needed
                            if (state.equals(Environment.MEDIA_MOUNTED)) {
                                Slog.i(TAG, "Privacy Protection wipe: path " + path + "is mounted, wait..");
                                unmountVolumeNotSwap(path, true, false);
                                for (int i = 0; i < 30; i++) {
                                    try {
                                        sleep(1000);
                                    } catch (InterruptedException ex) {
                                        Slog.e(TAG, "Exception when wait!", ex);
                                    }
                                    state = getVolumeState(path);
                                    if (state.equals(Environment.MEDIA_UNMOUNTED)) {
                                        Slog.i(TAG, "Privacy Protection wipe: wait unmount done!");
                                        break;
                                    }
                                }
                            }
                            // then unshare if needed
                            if (state.equals(Environment.MEDIA_SHARED)) {
                                Slog.i(TAG, "Privacy Protection wipe: path " + path + "is shared, wait..");
                                doShareUnshareVolume(path, "ums", false);
                                for (int i = 0; i < 30; i++) {
                                    try {
                                        sleep(1000);
                                    } catch (InterruptedException ex) {
                                        Slog.e(TAG, "Exception when wait!", ex);
                                    }
                                    state = getVolumeState(path);
                                    if (state.equals(Environment.MEDIA_UNMOUNTED)) {
                                        Slog.i(TAG, "Privacy Protection wipe: wait unshare done!");
                                        break;
                                    }
                                }
                            }
                            // we can format it now
                            if (state.equals(Environment.MEDIA_UNMOUNTED)) {
                                Slog.i(TAG, "Privacy Protection wipe: format " + path);
                                doFormatVolume(path);
                                for (int i = 0; i < 30; i++) {
                                    try {
                                        sleep(1000);
                                    } catch (InterruptedException ex) {
                                        Slog.e(TAG, "Exception when wait!", ex);
                                    }
                                    state = getVolumeState(path);
                                    if (state.equals(Environment.MEDIA_UNMOUNTED)) {
                                        Slog.i(TAG, "Privacy Protection wipe: format done!");
                                        break;
                                    }
                                }
                            }
                        }
                        // notify Privacy Protection that format done
                        Intent intent = new Intent(PRIVACY_PROTECTION_WIPE_DONE);
                        mContext.sendBroadcast(intent);
                        Slog.d(TAG, "Privacy Protection wipe: send " + intent);
                    }
                } .start();
            }
        }
    };

    // M: add for BOOT IPO
    private final BroadcastReceiver mBootIPOReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // set mBootCompleted when receive BOOT_COMPLETE or BOOT_IPO
            // then just return while BOOT_COMPLETE
            String action = intent.getAction();
            if (action.equals(Intent.ACTION_BOOT_COMPLETED)) {
                Slog.d(TAG, "MountService BOOT_COMPLETED!");
                /*HQ_liugang add for HQ01307332*/
                if(mPms.isFirstBoot()){
					String productName = SystemProperties.get("ro.product.name", "");
					Uri filePath = Uri.parse("file://sdcard/");
                    Slog.d(TAG, "LG send ACTION_MEDIA_MOUNTED");
		/*HQ_caoxuhao add for HQ01398425 start*/
					if(productName != null){
		Log.i(TAG,"send ACTION_MEDIA_MOUNTED");
		/*HQ_caoxuhao add for HQ01398425 end*/
	                    Intent fIntent = new Intent(Intent.ACTION_MEDIA_MOUNTED);
						fIntent.setData(filePath);
	                    context.sendBroadcastAsUser(fIntent, UserHandle.ALL);
					}
                    Slog.d(TAG, "LG send ACTION_MEDIA_MOUNTED end");
                }
                /*HQ_liugang add end*/ 				
                mBootCompleted = true;
                return;
            }

            new Thread() {
                public void run() {
                    Slog.d(TAG, "MountService BOOT_IPO!");
                    try {
                        Slog.d(TAG, "Notify VOLD IPO startup");
                        mConnector.execute("volume", "ipo", "startup");
                    } catch (NativeDaemonConnectorException e) {
                        Slog.e(TAG, "Error reinit SD card while IPO", e);
                    }

                    if (SystemProperties.get(PROP_2SDCARD_SWAP).equals("1")) {
                        mFirstTime_SwapStateForSDSwapMountPoint = true;
                        doSDSwapVolumeUpdate() ;
                    }
                    try {
                        final String[] vols = NativeDaemonEvent.filterMessageList(
                                mConnector.executeForList("volume", "list"),
                                VoldResponseCode.VolumeListResult);
                        for (String volstr : vols) {
                            String[] tok = volstr.split(" ");
                            // FMT: <label> <mountpoint> <state>
                            String path = tok[1];
                            String state = Environment.MEDIA_REMOVED;

                            final StorageVolume volume;
                            synchronized (mVolumesLock) {
                                volume = mVolumesByPath.get(path);
                            }
                            if (volume == null) {
                                    Slog.e(TAG, "Error processing initial volume state:  volume == null");
                                    continue;
                                }

                            int st = Integer.parseInt(tok[2]);
                            if (st == VolumeState.NoMedia) {
                                state = Environment.MEDIA_REMOVED;
                            } else if (st == VolumeState.Idle) {
                                state = Environment.MEDIA_UNMOUNTED;
                            } else if (st == VolumeState.Mounted) {
                                state = Environment.MEDIA_MOUNTED;
                                Slog.i(TAG, "Media already mounted on daemon connection");
                            } else if (st == VolumeState.Shared) {
                                state = Environment.MEDIA_SHARED;
                                Slog.i(TAG, "Media shared on daemon connection");
                            } else {
                                throw new InvalidParameterException(String.format("Unexpected state %d", st));
                            }

                            if (state != null) {
                                if (DEBUG_EVENTS) Slog.i(TAG, "Updating valid state " + state);
                                if (path.equals(EXTERNAL_OTG) && state.equals(Environment.MEDIA_REMOVED)) {
                                    Slog.i(TAG, "do not update /storage/usbotg MEDIA_REMOVED state in IPO");
                                } else {
                                    updatePublicVolumeState(volume, state);
                                }
                            }
                        }
                    } catch (NativeDaemonConnectorException e) {
                        Slog.e(TAG, "Error processing initial volume state", e);
                    } catch (InvalidParameterException e) {
                        Slog.e(TAG, "Error processing initial volume state", e);
                    }
                    handleSystemReady();
                }
            } .start();

        }
    };

    private final class MountServiceBinderListener implements IBinder.DeathRecipient {
        final IMountServiceListener mListener;

        MountServiceBinderListener(IMountServiceListener listener) {
            mListener = listener;

        }

        public void binderDied() {
            if (LOCAL_LOGD) Slog.d(TAG, "An IMountServiceListener has died!");
            synchronized (mListeners) {
                mListeners.remove(this);
                mListener.asBinder().unlinkToDeath(this, 0);
            }
        }
    }

    void runIdleMaintenance(Runnable callback) {
        mHandler.sendMessage(mHandler.obtainMessage(H_FSTRIM, callback));
    }

    // Binder entry point for kicking off an immediate fstrim
    @Override
    public void runMaintenance() {
        validatePermission(android.Manifest.permission.MOUNT_UNMOUNT_FILESYSTEMS);
        runIdleMaintenance(null);
    }

    @Override
    public long lastMaintenance() {
        return mLastMaintenance;
    }

    private void doShareUnshareVolume(String path, String method, boolean enable) {
        // TODO: Add support for multiple share methods
        if (!method.equals("ums")) {
            throw new IllegalArgumentException(String.format("Method %s not supported", method));
        }

        if (path.contains("emulated")) {
            Slog.i(TAG, "emulated storage cannot be share/unshare");
            return;
        }

        try {
            mConnector.execute("volume", enable ? "share" : "unshare", path, method);
        } catch (NativeDaemonConnectorException e) {
            Slog.e(TAG, "Failed to share/unshare", e);
        }
    }

    private void updatePublicVolumeState(StorageVolume volume, String state) {
        updatePublicVolumeState(volume, state, null);
    }

    private void updatePublicVolumeState(StorageVolume volume, String state, String label) {
        if (SystemProperties.get(PROP_MULTI_PARTITION).equals("1") && volume == null) {
            Slog.i(TAG, "updatePublicVolumeState: volume had been deleted,just return!");
            return;
        }
        final String path = volume.getPath();
        final String oldState;
        synchronized (mVolumesLock) {
            oldState = mVolumeStates.put(path, state);
            volume.setState(state);
        }

        if (state.equals(oldState)) {
            Slog.w(TAG, String.format("Duplicate state transition (%s -> %s) for %s",
                    state, state, path));
            return;
        }

        Slog.d(TAG, "volume state changed for " + path + " (" + oldState + " -> " + state + ")");
        Slog.d(TAG, "state=" + state + ", mSetDefaultEnable=" + mSetDefaultEnable + ", mIsTurnOnOffUsb=" + mIsTurnOnOffUsb + ", mMountSwap=" + mMountSwap + ", mUnmountSwap=" + mUnmountSwap);
        // update default path for MEDIA_UNMOUNTED
        if (Environment.MEDIA_UNMOUNTED.equals(state)
                && mSetDefaultEnable
                && ((!mIsTurnOnOffUsb && !SystemProperties.get(PROP_SHARED_SDCARD).equals("1")) || SystemProperties.get(PROP_SHARED_SDCARD).equals("1"))
                && !mMountSwap
                && !mUnmountSwap) {
            Slog.i(TAG, "updateDefaultpath MEDIA_UNMOUNTED");
            updateDefaultpath();
        }

        // Tell PackageManager about changes to primary volume state, but only
        // when not emulated.

        /*
         * MountService need and must call updateExternalMediaStatus()
         * when storage mount/unmount which may contains app.
         * There are 4 cases:
         * 1. phone storage + sd (no swap) : app 2 phone storage
         * 3. share sd           + sd (no swap): not allowed
         * 2. phone storage + sd (swap)      : app 2 sd (swap only allowed app install to external sd)
         * 4. share sd           + sd (swap)     : app 2 sd
         *
         * So, MountService should notify PMS in the following cases:
         * must primary storage, when swap enable, must be the external sd, swap disable, the phone storage.
         */
        if (path.equals(EXTERNAL_SD1)) {
            if ((!SystemProperties.get(PROP_2SDCARD_SWAP).equals("1") && !SystemProperties.get(PROP_SHARED_SDCARD).equals("1")) ||
                    (SystemProperties.get(PROP_2SDCARD_SWAP).equals("1") && LABEL_SD2.equals(label))) {
                if (Environment.MEDIA_UNMOUNTED.equals(state)) {
                    mPms.updateExternalMediaStatus(false, false);

                    /*
                     * Some OBBs might have been unmounted when this volume was
                     * unmounted, so send a message to the handler to let it know to
                     * remove those from the list of mounted OBBS.
                     */
                    mObbActionHandler.sendMessage(mObbActionHandler.obtainMessage(
                            OBB_FLUSH_MOUNT_STATE, path));
                } else if (Environment.MEDIA_MOUNTED.equals(state)) {
                    mPms.updateExternalMediaStatus(true, false);
                }
            }
        }

		//added for install app sd.chenwenshuai.
        if(path.equals(EXTERNAL_SD2))
        {
            if (Environment.MEDIA_MOUNTED.equals(state))
            {
                Slog.d(TAG, "updatePublicVolumeStae:"+path+",mounted");
                mPms.updateExternalMediaStatus(true,false);
            }
            else if(Environment.MEDIA_UNMOUNTED.equals(state))
            {
                Slog.d(TAG, "updatePublicVolumeState:"+path+",unmounted");
                mPms.updateExternalMediaStatus(false,false);
            }
        }
        //added ends here.
		
        if (SystemProperties.get(PROP_OWNER_SDCARD_SUPPORT).equals("1") &&
            mUserId != UserHandle.USER_OWNER &&
            (volume.getPath().equals(EXTERNAL_SD2) ||
            volume.getPath().startsWith(EXTERNAL_OTG)) &&
            !Environment.MEDIA_UNMOUNTED.equals(state))
            {
                Slog.d(TAG, "Do not CALLBACK EXTERNAL_SD2 and EXTERNAL_OTG removed msg when user_id = " + mUserId);
                return;
            }
        synchronized (mListeners) {
            for (int i = mListeners.size() -1; i >= 0; i--) {
                MountServiceBinderListener bl = mListeners.get(i);
                try {
                    bl.mListener.onStorageStateChanged(path, oldState, state);
                } catch (RemoteException rex) {
                    Slog.e(TAG, "Listener dead");
                    mListeners.remove(i);
                } catch (Exception ex) {
                    Slog.e(TAG, "Listener failed", ex);
                }
            }
        }
    }

    /**
     * Callback from NativeDaemonConnector
     */
    public void onDaemonConnected() {
        /*
         * Since we'll be calling back into the NativeDaemonConnector,
         * we need to do our work in a new thread.
         */
        new Thread("MountService#onDaemonConnected") {
            @Override
            public void run() {
                /**
                 * Determine media state and UMS detection status
                 */
                final String encryptProgress = SystemProperties.get("vold.encrypt_progress");
                final String decrypt = SystemProperties.get("vold.decrypt");
                Slog.e(TAG, "encryptProgress(" + encryptProgress + "), decrypt(" + decrypt + ")");
                if (!encryptProgress.equals("") && "trigger_restart_min_framework".equals(decrypt)) {
                    Slog.e(TAG, "encryptProgress(" + encryptProgress + "), skip the command to vold.");
                    StorageVolume primaryVolume = getPrimaryPhysicalVolume();
                    if (primaryVolume != null) {
                        updatePublicVolumeState(primaryVolume, Environment.MEDIA_REMOVED);
                    }
                    /*
                      * Now that we've done our initialization, release
                      * the hounds!
                      */
                    // M: socket may disconnect, so when onDaemonConnected called
                    // the object may already null
                    if (mConnectedSignal != null) {
                        mConnectedSignal.countDown();
                    }

                    // Notify people waiting for ASECs to be scanned that it's done.
                    // M: socket may disconnect, so when onDaemonConnected called
                    // the object may already null
                    if (mAsecsScanned != null) {
                        mAsecsScanned.countDown();
                    }

                    return;
                }

                try {
                    final String[] vols = NativeDaemonEvent.filterMessageList(
                            mConnector.executeForList("volume", "list", "broadcast"),
                            VoldResponseCode.VolumeListResult);
                    for (String volstr : vols) {
                        String[] tok = volstr.split(" ");
                        // FMT: <label> <mountpoint> <state>
                        String path = tok[1];
                        String state = Environment.MEDIA_REMOVED;

                        final StorageVolume volume;
                        synchronized (mVolumesLock) {
                            volume = mVolumesByPath.get(path);
                        }
                        if (volume == null &&
                            !(SystemProperties.get(PROP_MULTI_PARTITION).equals("1") && path.contains(LABLE_OTG))) {
                            Slog.e(TAG, "Error processing initial volume state:  volume == null");
                            continue;
                        }

                        int st = Integer.parseInt(tok[2]);
                        if (st == VolumeState.NoMedia) {
                            state = Environment.MEDIA_REMOVED;
                        } else if (st == VolumeState.Idle) {
                            state = Environment.MEDIA_UNMOUNTED;
                        } else if (st == VolumeState.Mounted) {
                            state = Environment.MEDIA_MOUNTED;
                            Slog.i(TAG, "Media already mounted on daemon connection");
                        } else if (st == VolumeState.Shared) {
                            state = Environment.MEDIA_SHARED;
                            Slog.i(TAG, "Media shared on daemon connection");
                        } else {
                            throw new Exception(String.format("Unexpected state %d", st));
                        }

                        if (state != null) {
                            if (DEBUG_EVENTS) Slog.i(TAG, "Updating valid state " + state);
                            if (SystemProperties.get(PROP_MULTI_PARTITION).equals("1")) {
                                //usbotg
                                if (null == volume && path.contains(LABLE_OTG)) {
                                    StorageVolume usbotgVolume;
                                    synchronized (mVolumes) {
                                        int size = mVolumes.size();
                                        Slog.d(TAG, "usbotg: mountservice onDaemonConnected:before mVolumes size is " + size);
                                        usbotgVolume =  new StorageVolume(new File(path), -1,  false, true, false, 0, false, 0L, null);
                                        boolean bPathIncluded = isVolumeRegistered(usbotgVolume.getPath());
                                        Slog.d(TAG, "usbotg: mountservice onDaemonConnected:otg path is registered? " + bPathIncluded);
                                        if (!bPathIncluded) {
                                            if (DEBUG_EVENTS) Slog.d(TAG, "usbotg: mountservice notifyVolumeChange:add volume " + size);
                                            mVolumes.add(usbotgVolume);
                                            mVolumesByPath.put(path, usbotgVolume);
                                        }
                                        Slog.d(TAG, "usbotg: mountservice onDaemonConnected:after mVolumes size is " + mVolumes.size());
                                        for (int i = 0; i < mVolumes.size(); i++) {
                                            mVolumes.get(i).setStorageId(i);
                                        }
                                    }
                                    updatePublicVolumeState(usbotgVolume, state);
                                } else {
                                    updatePublicVolumeState(volume, state);
                                }
                            } else {
                                updatePublicVolumeState(volume, state);
                            }
                        }
                    }
                } catch (Exception e) {
                    Slog.e(TAG, "Error processing initial volume state", e);
                    //final StorageVolume primary = getPrimaryPhysicalVolume();
                    //if (primary != null) {
                    //    updatePublicVolumeState(primary, Environment.MEDIA_REMOVED);
                    //}
                }
                if (SystemProperties.get(PROP_2SDCARD_SWAP).equals("1")) {
                    doSDSwapVolumeUpdate();
                }

                /*
                 * Now that we've done our initialization, release
                 * the hounds!
                 */
                // M: socket may disconnect, so when onDaemonConnected called
                // the object may already null
                if (mConnectedSignal != null) {
                    mConnectedSignal.countDown();
                }

                // On an encrypted device we can't see system properties yet, so pull
                // the system locale out of the mount service.
                if ("".equals(SystemProperties.get("vold.encrypt_progress"))) {
                    copyLocaleFromMountService();
                }

                // Let package manager load internal ASECs.
                //mPms.scanAvailableAsecs();

                // Notify people waiting for ASECs to be scanned that it's done.
                // M: socket may disconnect, so when onDaemonConnected called
                // the object may already null
                if (mAsecsScanned != null) {
                    mAsecsScanned.countDown();
                }
            }
        }.start();
    }

    private void copyLocaleFromMountService() {
        String systemLocale;
        try {
            systemLocale = getField(StorageManager.SYSTEM_LOCALE_KEY);
        } catch (RemoteException e) {
            return;
        }
        if (TextUtils.isEmpty(systemLocale)) {
            return;
        }

        Slog.d(TAG, "Got locale " + systemLocale + " from mount service");
        Locale locale = Locale.forLanguageTag(systemLocale);
        Configuration config = new Configuration();
        config.setLocale(locale);
        try {
            ActivityManagerNative.getDefault().updateConfiguration(config);
        } catch (RemoteException e) {
            Slog.e(TAG, "Error setting system locale from mount service", e);
        }

        // Temporary workaround for http://b/17945169.
        Slog.d(TAG, "Setting system properties to " + systemLocale + " from mount service");
        SystemProperties.set("persist.sys.language", locale.getLanguage());
        SystemProperties.set("persist.sys.country", locale.getCountry());
    }

    /**
     * Callback from NativeDaemonConnector
     */
    public boolean onCheckHoldWakeLock(int code) {
        return false;
    }

    /**
     * Callback from NativeDaemonConnector
     */
    public boolean onEvent(int code, String raw, String[] cooked) {
        if (DEBUG_EVENTS) {
            StringBuilder builder = new StringBuilder();
            builder.append("onEvent::");
            builder.append(" raw= " + raw);
            if (cooked != null) {
                builder.append(" cooked = " );
                for (String str : cooked) {
                    builder.append(" " + str);
                }
            }
            Slog.i(TAG, builder.toString());
        }
        if (code == VoldResponseCode.VolumeStateChange) {
            /*
             * One of the volumes we're managing has changed state.
             * Format: "NNN Volume <label> <path> state changed
             * from <old_#> (<old_str>) to <new_#> (<new_str>)"
             */
            if (SystemProperties.get(PROP_MULTI_PARTITION).equals("1")) {
                ///usbotg:
                if ((LABLE_OTG.equals(cooked[2])) && (cooked.length == OTG_EVENT605_LENGTH)) {
                    notifyVolumeChange(
                            cooked[2], cooked[3], Integer.parseInt(cooked[7]),
                                    Integer.parseInt(cooked[10]));
                } else {
                    boolean bValue = false;
                    if (cooked[12].equals("1"))
                        bValue = true;

                    notifyVolumeStateChange(
                        cooked[2], cooked[3], Integer.parseInt(cooked[7]),
                                Integer.parseInt(cooked[10]), bValue);
                }
            } else {
                boolean bValue = false;
                if (cooked[12].equals("1"))
                    bValue = true;

                notifyVolumeStateChange(
                    cooked[2], cooked[3], Integer.parseInt(cooked[7]),
                            Integer.parseInt(cooked[10]), bValue);
            }
        } else if (code == VoldResponseCode.VolumeUuidChange) {
            // Format: nnn <label> <path> <uuid>
            final String path = cooked[2];
            final String uuid = (cooked.length > 3) ? cooked[3] : null;

            final StorageVolume vol = mVolumesByPath.get(path);
            if (vol != null) {
                vol.setUuid(uuid);
            }
        } else if (code == VoldResponseCode.VolumeUserLabelChange) {
            // Format: nnn <label> <path> <label>
            final String path = cooked[2];
            final String userLabel = (cooked.length > 3) ? cooked[3] : null;

            final StorageVolume vol = mVolumesByPath.get(path);
            if (vol != null) {
                vol.setUserLabel(userLabel);
            }
        } else if ((code == VoldResponseCode.VolumeDiskInserted) ||
                   (code == VoldResponseCode.VolumeDiskRemoved) ||
                   (code == VoldResponseCode.VolumeBadRemoval)) {
            // FMT: NNN Volume <label> <mountpoint> disk inserted (<major>:<minor>)
            // FMT: NNN Volume <label> <mountpoint> disk removed (<major>:<minor>)
            // FMT: NNN Volume <label> <mountpoint> bad removal (<major>:<minor>)
            String action = null;
            final String label = cooked[2];
            final String path = cooked[3];
            int major = -1;
            int minor = -1;

            try {
                String devComp = cooked[6].substring(1, cooked[6].length() -1);
                String[] devTok = devComp.split(":");
                major = Integer.parseInt(devTok[0]);
                minor = Integer.parseInt(devTok[1]);
            } catch (Exception ex) {
                Slog.e(TAG, "Failed to parse major/minor", ex);
            }

            final StorageVolume volume;
            final String state;
            synchronized (mVolumesLock) {
                volume = mVolumesByPath.get(path);
                state = mVolumeStates.get(path);
            }

            if (code == VoldResponseCode.VolumeDiskInserted) {
                if (SystemProperties.get(PROP_OWNER_SDCARD_SUPPORT).equals("1") &&
                    mUserId != UserHandle.USER_OWNER &&
                    (volume.getPath().equals(EXTERNAL_SD2) ||
                    volume.getPath().startsWith(EXTERNAL_OTG))) {
                    Slog.d(TAG, "Do not mount EXTERNAL_SD2 and EXTERNAL_OTG when user_id = " + mUserId);
                    return true;
                }
                if (SystemProperties.get(PROP_2SDCARD_SWAP).equals("1") && !path.startsWith(EXTERNAL_OTG)) {
                    // Swap plug out, need to send eject for /storage/sdcard1 also.
                    StorageVolume primaryVolume;
                    synchronized (mVolumesLock) {
                        primaryVolume = mVolumesByPath.get(EXTERNAL_SD1);
                    }
                    sendStorageIntent(Intent.ACTION_MEDIA_EJECT, primaryVolume, UserHandle.ALL);
                    doSDSwapVolumeUpdate();
                    updateDefaultpath();
                    sendSDSwapIntent();
                }
                new Thread("MountService#VolumeDiskInserted") {
                    @Override
                    public void run() {
                        Slog.d(TAG, "onEvent: VolumeDiskInserted, start to mount " + path);
                        try {
                            if (SystemProperties.get(PROP_MULTI_PARTITION).equals("1") &&
                                path.startsWith(EXTERNAL_OTG)) {
                                int rc;
                                waitForReady();
                                if ((rc = doMountVolume(path)) != StorageResultCode.OperationSucceeded) {
                                    Slog.w(TAG, String.format("Insertion mount failed (%d)", rc));
                                }
                                if (!path.startsWith(EXTERNAL_OTG)) {
                                    doSDSwapVolumeUpdate();
                                    updateDefaultpath();
                                    sendSDSwapIntent();
                                }

                                try{
                                    Thread.sleep(500);                                   
                                } catch (InterruptedException e) {
                                    e.printStackTrace();
                                }  

                                if (rc == StorageResultCode.OperationSucceeded && enableDefaultPathDialog() && 1 != getFactoryKitMode()) {//modify for factory test
                                    Intent intent = new Intent("com.mediatek.storage.StorageDefaultPathDialog");
                                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                    if (path.contains(EXTERNAL_OTG)) {
                                        intent.putExtra(INSERT_OTG, true);
                                    }
                                    //mContext.startActivity(intent);
                                } else {
                                    Slog.w(TAG, String.format("Insertion mount failed (%d)", rc));
                                }                                
                            } else {
                                if (isUsbMassStorageEnabled()) {
                                    // just share to pc
                                    doShareUnshareVolume(path, "ums", true);
                                } else {
                                    int rc;
                                    if ((rc = doMountVolume(path)) != StorageResultCode.OperationSucceeded) {
                                        Slog.w(TAG, String.format("Insertion mount failed (%d)", rc));
                                    }
                                    if (!path.startsWith(EXTERNAL_OTG)) {
                                        doSDSwapVolumeUpdate();
                                        updateDefaultpath();
                                        sendSDSwapIntent();
                                    }
                                    
                                    try{
                                        Thread.sleep(500);                                   
                                    } catch (InterruptedException e) {
                                        e.printStackTrace();
                                    }  

                                    if (rc == StorageResultCode.OperationSucceeded && enableDefaultPathDialog()) {
                                        Intent intent = new Intent("com.mediatek.storage.StorageDefaultPathDialog");
                                        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                        if (path.equals(EXTERNAL_OTG)) {
                                            intent.putExtra(INSERT_OTG, true);
                                        }
                                        //mContext.startActivity(intent);
                                    } else {
                                        Slog.w(TAG, String.format("Insertion mount failed (%d)", rc));
                                    }
                                }
                            }

                        } catch (Exception ex) {
                            Slog.w(TAG, "Failed to mount media on insertion", ex);
                        }
                    }
                }.start();
                action = Intent.ACTION_SD_INSERTED;
            } else if (code == VoldResponseCode.VolumeDiskRemoved) {
                if (SystemProperties.get(PROP_2SDCARD_SWAP).equals("1") && !path.startsWith(EXTERNAL_OTG)) {
                    Slog.i(TAG, "[SWAP] removed MTK_2SDCARD_SWAP");
                    mUnmountSwap = true;
                    doSDSwapVolumeUpdate();
                    updateDefaultpath();
                    sendSDSwapIntent();
                    mUnmountSwap = false;
                }
                /*
                 * This event gets trumped if we're already in BAD_REMOVAL state
                 */
                if (getVolumeState(path) != null && getVolumeState(path).equals(Environment.MEDIA_BAD_REMOVAL)) {
                    return true;
                }
                if (DEBUG_EVENTS) Slog.i(TAG, "Sending eject event first");
                sendStorageIntent(Intent.ACTION_MEDIA_EJECT, volume, UserHandle.ALL);
                /* Send the media unmounted event first */
                if (DEBUG_EVENTS) Slog.i(TAG, "Sending unmounted event first");

                updatePublicVolumeState(volume, Environment.MEDIA_UNMOUNTED, label);
                sendStorageIntent(Intent.ACTION_MEDIA_UNMOUNTED, volume, UserHandle.ALL);

                if (DEBUG_EVENTS) Slog.i(TAG, "Sending media removed");
                updatePublicVolumeState(volume, Environment.MEDIA_REMOVED);
                action = Intent.ACTION_MEDIA_REMOVED;
                if (SystemProperties.get(PROP_MULTI_PARTITION).equals("1")) {
                    ///usbotg:
                    if (LABLE_OTG.equals(cooked[2])) {
                        if (!isVolumeRegistered(path)) {
                            return true;
                        }
                        sendStorageIntent(action, volume, UserHandle.ALL);
                        notifyVolumeChange(label, path);
                    }
                }
            } else if (code == VoldResponseCode.VolumeBadRemoval) {
            	Slog.d(TAG, "mountservice onevent1 isBadRemove ="+isBadRemove ); 
                isBadRemove = true;//chenwenshuai add for Multiple plug reboot
		String preDefaultPath = this.getDefaultPath();//chiguoqing add for HQ01518567
                if (SystemProperties.get(PROP_MULTI_PARTITION).equals("1")) {
                    ///usbotg:
                    if (LABLE_OTG.equals(cooked[2])) {
                        if (!isVolumeRegistered(path)) {
                            return true;
                        }
                        //notifyVolumeChange(label,path);
                    }
                }
                if (DEBUG_EVENTS) Slog.i(TAG, "Sending eject event first");
                sendStorageIntent(Intent.ACTION_MEDIA_EJECT, volume, UserHandle.ALL);
                // Swap plug out, need to send eject for /storage/sdcard1 also.
                if (SystemProperties.get(PROP_2SDCARD_SWAP).equals("1") && !path.startsWith(EXTERNAL_OTG)) {
                    StorageVolume secondaryVolume;
                    synchronized (mVolumesLock) {
                        secondaryVolume = mVolumesByPath.get(EXTERNAL_SD2);
                    }
                    sendStorageIntent(Intent.ACTION_MEDIA_EJECT, secondaryVolume, UserHandle.ALL);
                }
                if (DEBUG_EVENTS) Slog.i(TAG, "Sending unmounted event first");
                /* Send the media unmounted event first */
                updatePublicVolumeState(volume, Environment.MEDIA_UNMOUNTED, label);
                sendStorageIntent(Intent.ACTION_MEDIA_UNMOUNTED, volume, UserHandle.ALL);

                if (DEBUG_EVENTS) Slog.i(TAG, "Sending media bad removal");
                updatePublicVolumeState(volume, Environment.MEDIA_BAD_REMOVAL);
                action = Intent.ACTION_MEDIA_BAD_REMOVAL;
                if (SystemProperties.get(PROP_MULTI_PARTITION).equals("1")) {
                    ///usbotg:
                    if ((LABLE_OTG.equals(cooked[2])) && (cooked.length == OTG_EVENT632_LENGTH)) {
                        if (!isVolumeRegistered(path)) {
                            return true;
                        }
                        notifyVolumeChange(label, path);
                    }
                }
                /// M: ALPS00773050
                String defaultPath = this.getDefaultPath();
                updateDefaultpath();
                /// M: ALPS00773050

				//chenwenshuai add for bad remove system reboot begin
				if(preDefaultPath.equals("/storage/sdcard1")){  //chiguoqing add for HQ01518567
					int themeID = mContext.getResources().getIdentifier("androidhwext:style/Theme.Emui.Dialog.Alert", null, null);
					Dialog dialog = new AlertDialog.Builder(mContext, themeID).setTitle(com.hq.resource.internal.R.string.dlg_sd_attention_title)
                    .setMessage(com.hq.resource.internal.R.string.storage_reset_back_message)
                    .setPositiveButton(com.hq.resource.internal.R.string.dlg_ok, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(final DialogInterface arg0, final int arg1) {
                        	SystemProperties.set("persist.sys.primarysd", "0");
			    			Intent intentReboot = new Intent(Intent.ACTION_REBOOT);
                            intentReboot.putExtra("nowait", 1);
                            intentReboot.putExtra("interval", 1);
                            intentReboot.putExtra("window", 0);
                            mContext.sendBroadcast(intentReboot);
                        }
                    }).setCancelable(false).create();
					dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
					dialog.getWindow().getAttributes().privateFlags |=
                        WindowManager.LayoutParams.PRIVATE_FLAG_SHOW_FOR_ALL_USERS;
					dialog.show();
				}
				//chenwenshuai add for bad remove system reboot end
				
                if (enableDefaultToast() && (path.equals(defaultPath))) {
                    Toast.makeText(mContext, mContext.getString(com.mediatek.internal.R.string.sdcard_default_path_change), Toast.LENGTH_LONG).show();
                }
            } else if (code == VoldResponseCode.FstrimCompleted) {
                EventLogTags.writeFstrimFinish(SystemClock.elapsedRealtime());
            } else {
                Slog.e(TAG, String.format("Unknown code {%d}", code));
            }

            if (action != null) {
                sendStorageIntent(action, volume, UserHandle.ALL);
            }
        } else if (code == VoldResponseCode.VolumeEjectBeforeSwap) {
            // M : ALPS00446260
            String action = null;
            final String label = cooked[2];
            final String path = cooked[3];

            final StorageVolume volume;
            synchronized (mVolumesLock) {
                volume = mVolumesByPath.get(path);
            }
            Slog.i(TAG, "VoldResponseCode.VolumeEjectBeforeSwap: send eject to volume :"  + volume.toString());
            sendStorageIntent(Intent.ACTION_MEDIA_EJECT, volume, UserHandle.ALL);
            // M : ALPS00446260
        } else if (code == VoldResponseCode.VolumeUnmountable) {
            String action = null;
            final String label = cooked[2];
            final String path = cooked[3];

            final StorageVolume volume;
            synchronized (mVolumesLock) {
                volume = mVolumesByPath.get(path);
            }
            Slog.i(TAG, "VoldResponseCode.VolumeUnmountable: send MEDIA_UNMOUNTABLE to volume :"  + volume.toString());
            updatePublicVolumeState(volume, Environment.MEDIA_UNMOUNTABLE);
            sendStorageIntent(Intent.ACTION_MEDIA_UNMOUNTABLE, volume, UserHandle.ALL);
            updateDefaultpath();
            doSDSwapVolumeUpdate();
            // force setting UI to refresh default path
            Intent intent = new Intent(INTENT_SD_SWAP);
            intent.putExtra(SD_EXIST, getSwapState());
            Slog.d(TAG, "sendSDSwapIntent " + intent);
            mContext.sendBroadcast(intent);
        } else if (code == VoldResponseCode.VolumeMountFailedBlank) {
            String action = null;
            final String label = cooked[2];
            final String path = cooked[3];

            final StorageVolume volume;
            synchronized (mVolumesLock) {
                volume = mVolumesByPath.get(path);
            }
            Slog.i(TAG, "VoldResponseCode.VolumeMountFailedBlank: send MEDIA_NOFS to volume :"  + volume.toString());
            updatePublicVolumeState(volume, Environment.MEDIA_NOFS);
            sendStorageIntent(Intent.ACTION_MEDIA_NOFS, volume, UserHandle.ALL);
            updateDefaultpath();
            doSDSwapVolumeUpdate();
            // force setting UI to refresh default path
            Intent intent = new Intent(INTENT_SD_SWAP);
            intent.putExtra(SD_EXIST, getSwapState());
            Slog.d(TAG, "sendSDSwapIntent " + intent);
            mContext.sendBroadcast(intent);
        } else {
            return false;
        }

        return true;
    }

    private boolean isVolumeRegistered(String path) {
        boolean isVolumeRegistered = false;
        for (int i = 0; i < mVolumes.size(); i++) {
            if (mVolumes.get(i).getPath().equals(path)) {
                isVolumeRegistered = true;
                break;
            }
        }
        return isVolumeRegistered;
    }

    ///usbotg:
    private void notifyVolumeChange(String label, String path, int oldState, int newState) {
        if (DEBUG_EVENTS) Slog.i(TAG, "otg patition state change");
        if ((label == null) || (path == null)) {
            Slog.e(TAG, "usbotg: mountservice notifyVolumeChange:invalid lable or invalid path");
            return;
        }

        if (oldState == newState) {
            if (DEBUG_EVENTS) Slog.e(TAG, "usbotg: mountservice notifyVolumeChange:oldState=newState,don't care");
            return;
        } else {
            synchronized (mVolumes) {
                if (mVolumes == null) {
                    if (DEBUG_EVENTS) Slog.e(TAG, "usbotg: mountservice notifyVolumeChange: volumes is null... ");
                    return;
                }
                int size = mVolumes.size();
                Slog.d(TAG, "usbotg: mountservice notifyVolumeChange:before mVolumes size is " + size);
                StorageVolume volume =  new StorageVolume(new File(path), -1,  false, true, false, 0, false, 0L, null);
                boolean bPathIncluded = isVolumeRegistered(volume.getPath());
                Slog.d(TAG, "usbotg: mountservice notifyVolumeChange:otg path is registered? " + bPathIncluded);
                if ((oldState == VolumeState.NoMedia) && (newState == VolumeState.Idle)) {
                    if (!bPathIncluded) {
                        if (DEBUG_EVENTS) Slog.d(TAG, "usbotg: mountservice notifyVolumeChange:add volume " + size);
                        //mVolumes.add(volume);
                        //mVolumesByPath.put(path, volume);
                        addVolumeLocked(volume);
                        mVolumeStates.put(volume.getPath(), Environment.MEDIA_REMOVED);
                    }
                    Slog.d(TAG, "usbotg: mountservice notifyVolumeChange,Volume state is: " + newState);
                    notifyVolumeStateChange(label, path, oldState, newState);
                } else if ((oldState == VolumeState.Idle) && (newState == VolumeState.Checking)) {
                    Slog.d(TAG, "usbotg: mountservice notifyVolumeChange,Volume state is: " + newState);
                    notifyVolumeStateChange(label, path, oldState, newState);
                } else if (newState == VolumeState.Mounted) {
                    Slog.d(TAG, "usbotg: mountservice notifyVolumeChange,Volume state is: " + newState);
                    notifyVolumeStateChange(label, path, oldState, newState);
                } else if ((oldState == VolumeState.Unmounting) && (newState == VolumeState.Idle)) {
                    notifyVolumeStateChange(label, path, oldState,newState);
                }else if ((oldState == VolumeState.Checking)&&(newState == VolumeState.Idle)) {
		    Slog.d(TAG, "usbotg: mountservice notifyVolumeChange,Volume state is: " + newState);
                    notifyVolumeStateChange(label, path, oldState,newState);
                } else {
                    return;
                }
                Slog.d(TAG, "usbotg: mountservice notifyVolumeChange:after mVolumes size is " + mVolumes.size());
                for (int i = 0; i < mVolumes.size(); i++) {
                    mVolumes.get(i).setStorageId(i);
                }
            }
        }
    }

    ///usbotg:
    private void notifyVolumeChange(String label, final String path) {
        if (DEBUG_EVENTS) Slog.i(TAG, "usbotg: mountservice notifyVolumeChange:disk bad removal");
        if ((label == null) || (path == null)) {
            Slog.e(TAG, "usbotg: mountservice notifyVolumeChange:invalid lable or invalid path");
            return;
        }
        synchronized (mVolumes) {
            if (mVolumes == null) {
                if (DEBUG_EVENTS) Slog.e(TAG, "usbotg: mountservice notifyVolumeChange:volumes is null... ");
                return;
            }
            int size = mVolumes.size();
            int idx_incd = 0;
            Slog.d(TAG, "usbotg: mountservice notifyVolumeChange:before mVolumes size is " + size);
            StorageVolume volume =  new StorageVolume(new File(path), -1,  false, true, false, 0, false, 0L, null);
            idx_incd = mVolumes.indexOf(volume);
            if (idx_incd != -1) {
                if (DEBUG_EVENTS) Slog.d(TAG, "usbotg: mountservice notifyVolumeChange:remove volume " + idx_incd);
                removeVolumeLocked(volume);
            } else {
                return;
            }
            Slog.d(TAG, "usbotg: mountservice notifyVolumeChange:after mVolumes size is " + mVolumes.size());
            for (int i = 0; i < mVolumes.size(); i++) {
                mVolumes.get(i).setStorageId(i);
            }
        }
    }

    private void notifyVolumeStateChange(String label, String path, int oldState, int newState) {
          notifyVolumeStateChange(label, path, oldState, newState, false);
    }

    private void notifyVolumeStateChange(String label, String path, int oldState, int newState, boolean bValue) {
        final StorageVolume volume;
        String state;
        synchronized (mVolumesLock) {
            volume = mVolumesByPath.get(path);
            if (SystemProperties.get(PROP_MULTI_PARTITION).equals("1") && volume == null) {
                Slog.i(TAG, "notifyVolumeStateChange: volume had been deleted,just return!");
                return;
            }
            state = getVolumeState(path);
        }

        if (DEBUG_EVENTS) Slog.i(TAG, "notifyVolumeStateChange::" + state + " bValue::" + bValue);

		/* HQ_ChenWenshuai 2015-09-14 modified for low speed sdcard notify begin */
		if (newState == VolumeState.Mounted) {
			File file = new File("/proc/speed_class");
			if(file.exists() && !mLowSpeedSDCardFlag){
				sendLowSpeedSDCardNotification();
				mLowSpeedSDCardFlag = true;
			}
		}else if(newState == VolumeState.Unmounting){
			if(mLowSpeedSDCardFlag){
				cancelLowSpeedSDCardNotification();
				mLowSpeedSDCardFlag = false;
			}
		}
		/*HQ_ChenWenshuai 2015-09-14 modified end */
			
        String action = null;

        if (oldState == VolumeState.Shared && newState != oldState) {
            if (LOCAL_LOGD) Slog.d(TAG, "Sending ACTION_MEDIA_UNSHARED intent");
            sendStorageIntent(Intent.ACTION_MEDIA_UNSHARED, volume, UserHandle.ALL);
        }

        if (newState == VolumeState.Init) {
        } else if (newState == VolumeState.NoMedia) {
            // NoMedia is handled via Disk Remove events
        } else if (newState == VolumeState.Idle) {

            // set prop unmounting
            try {
                Slog.i(TAG, "set prop sys.sd.unmounting = 0");
                SystemProperties.set(PROP_UNMOUNTING, "0");
            } catch (IllegalArgumentException e) {
                Slog.e(TAG, "IllegalArgumentException when set sys.sd.unmounting:", e);
            }

            /*
             * Don't notify if we're in BAD_REMOVAL, NOFS, UNMOUNTABLE, or
             * if we're in the process of enabling UMS
             */
            Slog.i(TAG, "get state again, before get state=" + state);
            state = getVolumeState(path);

            Slog.i(TAG, "get state again, after get state=" + state);
            if (!state.equals(
                    Environment.MEDIA_BAD_REMOVAL) && !state.equals(
                            Environment.MEDIA_NOFS) && !state.equals(
                                    Environment.MEDIA_UNMOUNTABLE) && !getUmsEnabling()) {
                if (DEBUG_EVENTS) Slog.i(TAG, "updating volume state for media bad removal nofs and unmountable");
                updatePublicVolumeState(volume, Environment.MEDIA_UNMOUNTED, label);
                action = Intent.ACTION_MEDIA_UNMOUNTED;
                // for fix time issue,must broadcast now
                sendStorageIntent(action, volume, UserHandle.ALL);
                return;
            }            
        } else if (newState == VolumeState.Pending) {
        } else if (newState == VolumeState.Checking) {
            if (DEBUG_EVENTS) Slog.i(TAG, "updating volume state checking");
            updatePublicVolumeState(volume, Environment.MEDIA_CHECKING);
            action = Intent.ACTION_MEDIA_CHECKING;
        } else if (newState == VolumeState.Mounted) {
            if (DEBUG_EVENTS) Slog.i(TAG, "updating volume state mounted");
            if (bValue) {
                Slog.i(TAG, "the filesystem of the mounted storage is FAT32. update maxFileSize to 4G");
                volume.setMaxFileSize(MAX_FILE_SIZE);
            } else {
                Slog.i(TAG, "the filesystem of the mounted storage is not FAT32. update maxFileSize to 0");
                volume.setMaxFileSize(0);
            }
            updatePublicVolumeState(volume, Environment.MEDIA_MOUNTED, label);
            if (mSetDefaultEnable && !mIsTurnOnOffUsb && !mMountSwap && !mUnmountSwap) {
                Slog.i(TAG, "updateDefaultpath VolumeState.Mounted");
                updateDefaultpath();
            }
            action = Intent.ACTION_MEDIA_MOUNTED;
        } else if (newState == VolumeState.Unmounting) {
            //action = Intent.ACTION_MEDIA_EJECT;
        } else if (newState == VolumeState.Formatting) {
        } else if (newState == VolumeState.Shared) {
            if (DEBUG_EVENTS) Slog.i(TAG, "Updating volume state media mounted");
            /* Send the media unmounted event first */
            updatePublicVolumeState(volume, Environment.MEDIA_UNMOUNTED, label);
            sendStorageIntent(Intent.ACTION_MEDIA_UNMOUNTED, volume, UserHandle.ALL);

            if (DEBUG_EVENTS) Slog.i(TAG, "Updating media shared");
            updatePublicVolumeState(volume, Environment.MEDIA_SHARED);
            action = Intent.ACTION_MEDIA_SHARED;
            if (LOCAL_LOGD) Slog.d(TAG, "Sending ACTION_MEDIA_SHARED intent");
        } else if (newState == VolumeState.SharedMnt) {
            Slog.e(TAG, "Live shared mounts not supported yet!");
            return;
        } else {
            Slog.e(TAG, "Unhandled VolumeState {" + newState + "}");
        }

        if (action != null) {
            sendStorageIntent(action, volume, UserHandle.ALL);
        }
    }

    private int doMountVolume(String path) {
        int rc = StorageResultCode.OperationSucceeded;

        final StorageVolume volume;
        synchronized (mVolumesLock) {
            volume = mVolumesByPath.get(path);
        }

        if (!volume.isEmulated() && hasUserRestriction(UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA)) {
            Slog.w(TAG, "User has restriction DISALLOW_MOUNT_PHYSICAL_MEDIA; cannot mount volume.");
            return StorageResultCode.OperationFailedInternalError;
        }

        if (DEBUG_EVENTS) Slog.i(TAG, "doMountVolume: Mouting " + path);
        try {
            final Command cmd = new Command("volume", "mount", path);
            if (SystemProperties.get(PROP_2SDCARD_SWAP).equals("1") && mMountSwap) {
                cmd.appendArg("swap");
            }
            Slog.d(TAG, "doMountVolume  cmd:" + cmd);
            mConnector.execute(cmd);
        } catch (NativeDaemonConnectorException e) {
            /*
             * Mount failed for some reason
             */
            String action = null;
            int code = e.getCode();
            if (code == VoldResponseCode.OpFailedNoMedia) {
                /*
                 * Attempt to mount but no media inserted
                 */
                rc = StorageResultCode.OperationFailedNoMedia;
            } else if (code == VoldResponseCode.OpFailedMediaBlank) {
                if (DEBUG_EVENTS) Slog.i(TAG, " updating volume state :: media nofs");
                /*
                 * Media is blank or does not contain a supported filesystem
                 */
                updatePublicVolumeState(volume, Environment.MEDIA_NOFS);
                action = Intent.ACTION_MEDIA_NOFS;
                rc = StorageResultCode.OperationFailedMediaBlank;
            } else if (code == VoldResponseCode.OpFailedMediaCorrupt) {
                if (DEBUG_EVENTS) Slog.i(TAG, "updating volume state media corrupt");
                /*
                 * Volume consistency check failed
                 */
                updatePublicVolumeState(volume, Environment.MEDIA_UNMOUNTABLE);
                action = Intent.ACTION_MEDIA_UNMOUNTABLE;
                rc = StorageResultCode.OperationFailedMediaCorrupt;
            } else {
                rc = StorageResultCode.OperationFailedInternalError;
            }

            /*
             * Send broadcast intent (if required for the failure)
             */
            if (action != null) {
               if(SystemProperties.get(PROP_OWNER_SDCARD_SUPPORT).equals("1") 
                  && (mOldUserId != mUserId)) {
                   /*
                    * we have to send the intent in another thread to avoid dead lock as
                    * AMS will call #getVolumeList() to acquire the mVolumesLock.
                    */
                    final String tempAction = action;
                    final StorageVolume tempV = volume;
                    new Thread() {
                        public void run() {                        
                             sendStorageIntent(tempAction, tempV, UserHandle.ALL);
                        }
                    }.start();
               } else {
                   sendStorageIntent(action, volume, UserHandle.ALL);
               }              
            }
        }

        return rc;
    }

    /*
     * If force is not set, we do not unmount if there are
     * processes holding references to the volume about to be unmounted.
     * If force is set, all the processes holding references need to be
     * killed via the ActivityManager before actually unmounting the volume.
     * This might even take a while and might be retried after timed delays
     * to make sure we dont end up in an instable state and kill some core
     * processes.
     * If removeEncryption is set, force is implied, and the system will remove any encryption
     * mapping set on the volume when unmounting.
     */
    private int doUnmountVolume(String path, boolean force, boolean removeEncryption) {
        if (!getVolumeState(path).equals(Environment.MEDIA_MOUNTED)) {
            return VoldResponseCode.OpFailedVolNotMounted;
        }

        /*
         * Force a GC to make sure AssetManagers in other threads of the
         * system_server are cleaned up. We have to do this since AssetManager
         * instances are kept as a WeakReference and it's possible we have files
         * open on the external storage.
         */
        Runtime.getRuntime().gc();
        System.runFinalization();
		//added by chenwenshuai
        String args[] = getExternalAsecFromMntasec();
        forcestopProcess(args);
        //added ends

        // Redundant probably. But no harm in updating state again.

		//chenwenshuai for HQ01039542
        //if (path.equals(EXTERNAL_SD1)) {
           // mPms.updateExternalMediaStatus(false, false);
        //}

        try {
            final Command cmd = new Command("volume", "unmount", path);
            if (removeEncryption) {
                if (SystemProperties.get(PROP_2SDCARD_SWAP).equals("1") && mUnmountSwap) {
                    cmd.appendArg("force_and_revert_and_swap");
                } else {
                    cmd.appendArg("force_and_revert");
                }
            } else if (force) {
                if (SystemProperties.get(PROP_2SDCARD_SWAP).equals("1") && mUnmountSwap) {
                    cmd.appendArg("force_and_swap");
                } else {
                    cmd.appendArg("force");
                }
            } else {
                cmd.appendArg("swap");
            }
            mConnector.execute(cmd);
            // We unmounted the volume. None of the asec containers are available now.
            synchronized (mAsecMountSet) {
                mAsecMountSet.clear();
            }
            return StorageResultCode.OperationSucceeded;
        } catch (NativeDaemonConnectorException e) {
            // Don't worry about mismatch in PackageManager since the
            // call back will handle the status changes any way.
            int code = e.getCode();
            if (code == VoldResponseCode.OpFailedVolNotMounted) {
                return StorageResultCode.OperationFailedStorageNotMounted;
            } else if (code == VoldResponseCode.OpFailedStorageBusy) {
                return StorageResultCode.OperationFailedStorageBusy;
            } else {
                return StorageResultCode.OperationFailedInternalError;
            }
        }
    }

	//added for install app sd.chenwenshuai.
		private String[] getExternalAsecFromMntasec()
		{
			ArrayList<String> arrFile = new ArrayList<String>();  
			File file = new File("/mnt/asec");	
			File[] subFile = file.listFiles();	
	  
			for (int iFileLength = 0; iFileLength < subFile.length; iFileLength++) {  
				if (subFile[iFileLength].isDirectory() ) {	
					
					String dirName = subFile[iFileLength].getName();  
					Slog.d(TAG, "getExternalAsecFromMntasec():dirName:" + dirName);
					
					int idx = dirName.lastIndexOf("-");
					if (idx == -1) {
						continue;
					}
	
					String asec = dirName.substring(0, idx);
					Slog.d(TAG, "getExternalAsecFromMntasec():asec:" + asec);
					arrFile.add(asec);	
				}  
			}  
			int size = arrFile.size();
			if(size > 0)
			{
				return (String[])arrFile.toArray(new String[size]);
			}
			else
			{
				return null;
			}
		}  
		private void forcestopProcess(String []args)
		{
			if(args == null)
			{
				return;
			}
			
			ActivityManager mAm = (ActivityManager)mContext.getSystemService(Context.ACTIVITY_SERVICE);
			for(int index = 0; index < args.length; index++)
			{
				if(args[index] != null)
				{
					Slog.d(TAG, "forcestopProcess:"+args[index]);
					
					mAm.forceStopPackage(args[index]);
				}
			}
		}
		//added ends

    private int doUnmountVolumeForUserSwitch(String path, boolean force, boolean removeEncryption) {

        /*
         * Force a GC to make sure AssetManagers in other threads of the
         * system_server are cleaned up. We have to do this since AssetManager
         * instances are kept as a WeakReference and it's possible we have files
         * open on the external storage.
         */
        Runtime.getRuntime().gc();
        System.runFinalization();

        // Redundant probably. But no harm in updating state again.

        if (path.equals(EXTERNAL_SD1)) {
            mPms.updateExternalMediaStatus(false, false);
        }

        try {
            final Command cmd = new Command("volume", "unmount", path);
            if (removeEncryption) {
                if (SystemProperties.get(PROP_2SDCARD_SWAP).equals("1") && mUnmountSwap) {
                    cmd.appendArg("force_and_revert_and_swap");
                } else {
                    cmd.appendArg("force_and_revert");
                }
            } else if (force) {
                if (SystemProperties.get(PROP_2SDCARD_SWAP).equals("1") && mUnmountSwap) {
                    cmd.appendArg("force_and_swap");
                } else {
                    cmd.appendArg("force");
                }
            } else {
                cmd.appendArg("swap");
            }
            mConnector.execute(cmd);
            // We unmounted the volume. None of the asec containers are available now.
            synchronized (mAsecMountSet) {
                mAsecMountSet.clear();
            }
            return StorageResultCode.OperationSucceeded;
        } catch (NativeDaemonConnectorException e) {
            // Don't worry about mismatch in PackageManager since the
            // call back will handle the status changes any way.
            int code = e.getCode();
            if (code == VoldResponseCode.OpFailedVolNotMounted) {
                return StorageResultCode.OperationFailedStorageNotMounted;
            } else if (code == VoldResponseCode.OpFailedStorageBusy) {
                return StorageResultCode.OperationFailedStorageBusy;
            } else {
                return StorageResultCode.OperationFailedInternalError;
            }
        }
    }
    private int doFormatVolume(String path) {
        try {
            if (FORMAT_WIPE) {
                mConnector.execute("volume", "format", path, "wipe");
            } else {
                mConnector.execute("volume", "format", path);
            }
            return StorageResultCode.OperationSucceeded;
        } catch (NativeDaemonConnectorException e) {
            int code = e.getCode();
            if (code == VoldResponseCode.OpFailedNoMedia) {
                return StorageResultCode.OperationFailedNoMedia;
            } else if (code == VoldResponseCode.OpFailedMediaCorrupt) {
                return StorageResultCode.OperationFailedMediaCorrupt;
            } else {
                return StorageResultCode.OperationFailedInternalError;
            }
        }
    }

    private boolean doGetVolumeShared(String path, String method) {
        if (path.contains("emulated")) {
            return false;
        }

        final NativeDaemonEvent event;
        try {
            event = mConnector.execute("volume", "shared", path, method);
        } catch (NativeDaemonConnectorException ex) {
            Slog.e(TAG, "Failed to read response to volume shared " + path + " " + method);
            return false;
        }

        if (event.getCode() == VoldResponseCode.ShareEnabledResult) {
            return event.getMessage().endsWith("enabled");
        } else {
            return false;
        }
    }

    private void notifyShareAvailabilityChange(final boolean avail) {
        synchronized (mListeners) {
            mUmsAvailable = avail;
            for (int i = mListeners.size() -1; i >= 0; i--) {
                MountServiceBinderListener bl = mListeners.get(i);
                try {
                    bl.mListener.onUsbMassStorageConnectionChanged(avail);
                } catch (RemoteException rex) {
                    Slog.e(TAG, "Listener dead");
                    mListeners.remove(i);
                } catch (Exception ex) {
                    Slog.e(TAG, "Listener failed", ex);
                }
            }
        }

        if (mSystemReady == true) {
            sendUmsIntent(avail);
        } else {
            mSendUmsConnectedOnBoot = avail;
        }

        if (!avail) {
            // M: two case need turn off
            // 1. is turning on UMS
            // 2. there is any storage at SHARED status
            boolean needTurnOff = false;
            if (mIsTurnOnOffUsb) {
                needTurnOff = true;
            } else {
                synchronized (mVolumes) {
                    int size = mVolumes.size();
                    for (int i = 0; i < size; i++) {
                        if (getVolumeState(mVolumes.get(i).getPath()).equals(Environment.MEDIA_SHARED)) {
                            needTurnOff = true;
                            break;
                        }
                    }
                }
            }
            /*
             * USB mass storage disconnected while enabled
             */
            if (needTurnOff) {
                new Thread("MountService#AvailabilityChange") {
                    @Override
                    public void run() {
                        synchronized (TURNONUSB_SYNC_LOCK) {
                            setUsbMassStorageEnabled(false);
                        }
                    }
                } .start();
            }
        }
    }

    private void sendStorageIntent(String action, StorageVolume volume, UserHandle user) {
        if (SystemProperties.get(PROP_MULTI_PARTITION).equals("1") && volume == null) {
            Slog.i(TAG, "sendStorageIntent: volume had been deleted,just return!");
            return;
        }
        final Intent intent = new Intent(action, Uri.parse("file://" + volume.getPath()));
        // M: tell MediaScanner all sd card will be mount unmount together
        if (((action.equals(Intent.ACTION_MEDIA_EJECT) && (mShutdownSD == true || mUMSCount > 0)) ||
                (action.equals(Intent.ACTION_MEDIA_MOUNTED) && mMountAll == true) ||
                (action.equals(Intent.ACTION_MEDIA_UNMOUNTED) && mIsTurnOnOffUsb == true)) &&
                !SystemProperties.get(PROP_SHARED_SDCARD).equals("1")) {
            intent.putExtra(MOUNT_UNMOUNT_ALL, true);
        }

        if ((mSD1BootMounted || mSD2BootMounted) && action.equals(Intent.ACTION_MEDIA_MOUNTED)) {
            if (mSD1BootMounted && volume.getPath().equals(EXTERNAL_SD1)) {
                mSD1BootMounted = false;
                if (!mBootCompleted) {
                    intent.putExtra(FIRST_BOOT_MOUNTED, true);
                    Slog.i(TAG, "sendStorageIntent mSD1BootMounted");
                }
            } else if (mSD2BootMounted && volume.getPath().equals(EXTERNAL_SD2)) {
                mSD2BootMounted = false;
                if (!mBootCompleted) {
                    intent.putExtra(FIRST_BOOT_MOUNTED, true);
                    Slog.i(TAG, "sendStorageIntent mSD2BootMounted");
                }
            }
        }
        intent.putExtra(StorageVolume.EXTRA_STORAGE_VOLUME, volume);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        Slog.d(TAG, "sendStorageIntent " + intent + " to " + user + ", " + intent.getExtras());
        mContext.sendBroadcastAsUser(intent, user);
    }

    private void sendUmsIntent(boolean c) {
        mContext.sendBroadcastAsUser(
                new Intent((c ? Intent.ACTION_UMS_CONNECTED : Intent.ACTION_UMS_DISCONNECTED)),
                UserHandle.ALL);
    }

    private void sendEncryptionTypeIntent() {
        mContext.sendBroadcastAsUser(new Intent(ACTION_ENCRYPTION_TYPE_CHANGED), UserHandle.ALL);
    }

    private void validatePermission(String perm) {
        if (mContext.checkCallingOrSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException(String.format("Requires %s permission", perm));
        }
    }

    private boolean hasUserRestriction(String restriction) {
        UserManager um = (UserManager) mContext.getSystemService(Context.USER_SERVICE);
        return um.hasUserRestriction(restriction, Binder.getCallingUserHandle());
    }

    private void validateUserRestriction(String restriction) {
        if (hasUserRestriction(restriction)) {
            throw new SecurityException("User has restriction " + restriction);
        }
    }

    // Storage list XML tags
    private static final String TAG_STORAGE_LIST = "StorageList";
    private static final String TAG_STORAGE = "storage";

    private void readStorageListLocked() {
        mVolumes.clear();
        // remove this for IPO, it will cause error notication
        //mVolumeStates.clear();

        Resources resources = mContext.getResources();

        int id = com.android.internal.R.xml.storage_list;
        XmlResourceParser parser = resources.getXml(id);
        AttributeSet attrs = Xml.asAttributeSet(parser);

        try {
            XmlUtils.beginDocument(parser, TAG_STORAGE_LIST);
            while (true) {
                XmlUtils.nextElement(parser);

                String element = parser.getName();
                if (element == null) break;

                if (TAG_STORAGE.equals(element)) {
                    TypedArray a = resources.obtainAttributes(attrs,
                            com.android.internal.R.styleable.Storage);

                    String path = a.getString(
                            com.android.internal.R.styleable.Storage_mountPoint);
                    int descriptionId = a.getResourceId(
                            com.android.internal.R.styleable.Storage_storageDescription, -1);
                    CharSequence description = a.getText(
                            com.android.internal.R.styleable.Storage_storageDescription);
                    boolean primary = a.getBoolean(
                            com.android.internal.R.styleable.Storage_primary, false);
                    boolean removable = a.getBoolean(
                            com.android.internal.R.styleable.Storage_removable, false);
                    boolean emulated = a.getBoolean(
                            com.android.internal.R.styleable.Storage_emulated, false);

                    // M: Only SHARED_SDCARD can use mtpReserve, set it to 0 first.
                    //int mtpReserve = a.getInt(
                    //        com.android.internal.R.styleable.Storage_mtpReserve, 0);
                    int mtpReserve = 0;
                    boolean allowMassStorage = a.getBoolean(
                            com.android.internal.R.styleable.Storage_allowMassStorage, false);
                    // resource parser does not support longs, so XML value is in megabytes
                    long maxFileSize = a.getInt(
                            com.android.internal.R.styleable.Storage_maxFileSize, 0) * 1024L * 1024L;

                    // M: Don't use storage_list to set up emulated sd, handle in code instead
                    // When share sd enabled, "/storage/sdcard0" will be init as the emulated
                    if (SystemProperties.get(PROP_SHARED_SDCARD).equals("1") && primary) {
                        path = null;
                        //descriptionId = com.android.internal.R.string.storage_phone;
                        description = mContext.getString(descriptionId);
                        primary = true;
                        removable = false;
                        emulated = true;
                        mtpReserve = MTP_RESERVE_SPACE;
                    }

                    Slog.d(TAG, "got storage path: " + path + " description: " + description +
                            " primary: " + primary + " removable: " + removable +
                            " emulated: " + emulated +  " mtpReserve: " + mtpReserve +
                            " allowMassStorage: " + allowMassStorage +
                            " maxFileSize: " + maxFileSize);

                    if (SystemProperties.get(PROP_MULTI_PARTITION).equals("1") 
                           && path!=null && path.startsWith(EXTERNAL_OTG)) {  
                        Slog.d(TAG, "with multi partition, ignore storage : " + path);  
                        continue;
                    }                    

                    if (emulated) {
                        // For devices with emulated storage, we create separate
                        // volumes for each known user.
                        mEmulatedTemplate = new StorageVolume(null, descriptionId, true, false,
                                true, mtpReserve, false, maxFileSize, null);

                        final UserManagerService userManager = UserManagerService.getInstance();
                        for (UserInfo user : userManager.getUsers(false)) {
                            createEmulatedVolumeForUserLocked(user.getUserHandle());
                        }

                        if (SystemProperties.get(PROP_SHARED_SDCARD).equals("1") && mVolumePrimary == null) {
                            for (StorageVolume shareVolume : mVolumes) {
                                if (shareVolume.getPath().equals(EXTERNAL_SD1)) {
                                    mVolumePrimary = shareVolume;
                                }
                            }
                        }

                    } else {
                        if (path == null || description == null) {
                            Slog.e(TAG, "Missing storage path or description in readStorageList");
                        } else {
                            final StorageVolume volume = new StorageVolume(new File(path),
                                    descriptionId, primary, removable, emulated, mtpReserve,
                                    allowMassStorage, maxFileSize, null);
                            addVolumeLocked(volume);

                            // Until we hear otherwise, treat as unmounted
                            mVolumeStates.put(volume.getPath(), Environment.MEDIA_UNMOUNTED);
                            volume.setState(Environment.MEDIA_UNMOUNTED);

                            if (allowMassStorage) {
                                mIsAnyAllowUMS = true;
                            }

                            if (mVolumePrimary == null && path.equals(EXTERNAL_SD1)) {
                                mVolumePrimary = volume;
                            } else if (mVolumeSecondary == null && path.equals(EXTERNAL_SD2)) {
                                mVolumeSecondary = volume;
                            }
                        }
                    }

                    a.recycle();
                }
            }
        } catch (XmlPullParserException e) {
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            // Compute storage ID for each physical volume; emulated storage is
            // always 0 when defined.
            int index = isExternalStorageEmulated() ? 1 : 0;
            for (StorageVolume volume : mVolumes) {
                if (!volume.isEmulated()) {
                    volume.setStorageId(index++);
                }
            }
            parser.close();
        }
    }

    /**
     * Create and add new {@link StorageVolume} for given {@link UserHandle}
     * using {@link #mEmulatedTemplate} as template.
     */
    private void createEmulatedVolumeForUserLocked(UserHandle user) {
        if (mEmulatedTemplate == null) {
            throw new IllegalStateException("Missing emulated volume multi-user template");
        }

        final UserEnvironment userEnv = new UserEnvironment(user.getIdentifier());
        final File path = userEnv.getExternalStorageDirectory();
        final StorageVolume volume = StorageVolume.fromTemplate(mEmulatedTemplate, path, user);
        volume.setStorageId(0);
        addVolumeLocked(volume);

        if (mSystemReady) {
            updatePublicVolumeState(volume, Environment.MEDIA_MOUNTED);
        } else {
            // Place stub status for early callers to find
            mVolumeStates.put(volume.getPath(), Environment.MEDIA_MOUNTED);
            volume.setState(Environment.MEDIA_MOUNTED);
        }
    }

    private void addVolumeLocked(StorageVolume volume) {
        Slog.d(TAG, "addVolumeLocked() " + volume);
        mVolumes.add(volume);
        final StorageVolume existing = mVolumesByPath.put(volume.getPath(), volume);
        if (existing != null) {
            Slog.d(TAG, "Volume at " + volume.getPath() + " already exists: " + existing);
            //throw new IllegalStateException(
            //        "Volume at " + volume.getPath() + " already exists: " + existing);
        }
    }

    private void removeVolumeLocked(StorageVolume volume) {
        Slog.d(TAG, "removeVolumeLocked() " + volume);
        mVolumes.remove(volume);
        mVolumesByPath.remove(volume.getPath());
        mVolumeStates.remove(volume.getPath());
    }

    private StorageVolume getPrimaryPhysicalVolume() {
        synchronized (mVolumesLock) {
            for (StorageVolume volume : mVolumes) {
                if (volume.isPrimary() && !volume.isEmulated()) {
                    return volume;
                }
            }
        }
        return null;
    }

    /**
     * Constructs a new MountService instance
     *
     * @param context  Binder context for this service
     */
    public MountService(Context context) {
        sSelf = this;

        mContext = context;

        synchronized (mVolumesLock) {
            readStorageListLocked();
        }

        // XXX: This will go away soon in favor of IMountServiceObserver
        mPms = (PackageManagerService) ServiceManager.getService("package");

        HandlerThread hthread = new HandlerThread(TAG);
        hthread.start();
        mHandler = new MountServiceHandler(hthread.getLooper());

        // Watch for user changes
        final IntentFilter userFilter = new IntentFilter();
        userFilter.addAction(Intent.ACTION_USER_ADDED);
        userFilter.addAction(Intent.ACTION_USER_REMOVED);

        /// M: sdcard&otg only for owner
        userFilter.addAction(Intent.ACTION_USER_SWITCHED);
        mContext.registerReceiver(mUserReceiver, userFilter, null, mHandler);

        // Watch for USB changes on primary volume
        // Because share sd also can support UMS, so we check each volume can support UMS
        synchronized (mVolumesLock) {
            for (StorageVolume volume : mVolumes) {
                if (volume.allowMassStorage()) {
                    mContext.registerReceiver(
                            mUsbReceiver, new IntentFilter(UsbManager.ACTION_USB_STATE), null, mHandler);
                    break;
                }
            }
        }

        // M: OMA DM
        if (SystemProperties.get(PROP_DM_APP).equals("1")) {
            final IntentFilter DMFilter = new IntentFilter();
            DMFilter.addAction(OMADM_USB_ENABLE);
            DMFilter.addAction(OMADM_USB_DISABLE);
            DMFilter.addAction(OMADM_SD_FORMAT);
            mContext.registerReceiver(mDMReceiver, DMFilter, null, mHandler);
        }

        final IntentFilter privacyProtectionFilter = new IntentFilter();
        privacyProtectionFilter.addAction(PRIVACY_PROTECTION_LOCK);
        privacyProtectionFilter.addAction(PRIVACY_PROTECTION_UNLOCK);
        privacyProtectionFilter.addAction(PRIVACY_PROTECTION_WIPE);
        mContext.registerReceiver(mPrivacyProtectionReceiver, privacyProtectionFilter, null, mHandler);

        // M: Boot IPO receiver
        final IntentFilter bootIPOFilter = new IntentFilter();
        bootIPOFilter.addAction(BOOT_IPO);
        bootIPOFilter.addAction(Intent.ACTION_BOOT_COMPLETED); // for MediaScanner
        mContext.registerReceiver(mBootIPOReceiver, bootIPOFilter, null, mHandler);

        // Add OBB Action Handler to MountService thread.
        mObbActionHandler = new ObbActionHandler(IoThread.get().getLooper());

        // Initialize the last-fstrim tracking if necessary
        File dataDir = Environment.getDataDirectory();
        File systemDir = new File(dataDir, "system");
        mLastMaintenanceFile = new File(systemDir, LAST_FSTRIM_FILE);
        if (!mLastMaintenanceFile.exists()) {
            // Not setting mLastMaintenance here means that we will force an
            // fstrim during reboot following the OTA that installs this code.
            try {
                (new FileOutputStream(mLastMaintenanceFile)).close();
            } catch (IOException e) {
                Slog.e(TAG, "Unable to create fstrim record " + mLastMaintenanceFile.getPath());
            }
        } else {
            mLastMaintenance = mLastMaintenanceFile.lastModified();
        }

        /*
         * Create the connection to vold with a maximum queue of twice the
         * amount of containers we'd ever expect to have. This keeps an
         * "asec list" from blocking a thread repeatedly.
         */
        mConnector = new NativeDaemonConnector(this, "vold", MAX_CONTAINERS * 2, VOLD_TAG, 25,
                null);

        Thread thread = new Thread(mConnector, VOLD_TAG);
        thread.start();

        // Add ourself to the Watchdog monitors if enabled.
        if (WATCHDOG_ENABLE) {
            Watchdog.getInstance().addMonitor(this);
        }
    }

    public void systemReady() {
        mSystemReady = true;
        mHandler.obtainMessage(H_SYSTEM_READY).sendToTarget();
    }

    /**
     * Exposed API calls below here
     */

    public void registerListener(IMountServiceListener listener) {
        synchronized (mListeners) {
            MountServiceBinderListener bl = new MountServiceBinderListener(listener);
            try {
                listener.asBinder().linkToDeath(bl, 0);
                mListeners.add(bl);
            } catch (RemoteException rex) {
                Slog.e(TAG, "Failed to link to listener death");
            }
        }
    }

    public void unregisterListener(IMountServiceListener listener) {
        synchronized (mListeners) {
            for(MountServiceBinderListener bl : mListeners) {
                if (bl.mListener.asBinder() == listener.asBinder()) {
                    mListeners.remove(mListeners.indexOf(bl));
                    listener.asBinder().unlinkToDeath(bl, 0);
                    return;
                }
            }
        }
    }

    public void shutdown(final IMountShutdownObserver observer) {
        validatePermission(android.Manifest.permission.SHUTDOWN);

        Slog.i(TAG, "Shutting down");
        mShutdownCount = 0;
        mSetDefaultEnable = false;
        mBootCompleted = false;
        final HashMap<String, String> snapshot;
        synchronized (mVolumesLock) {
            snapshot = new HashMap<String, String>(mVolumeStates);
        }
        //if /storage/sdcard0 is mounted while shutdown
        //need to wait PMS unmount apk finish then can real unmount all sd card
        String mState = getVolumeState(EXTERNAL_SD1);
        if (mState.equals(Environment.MEDIA_MOUNTED)) {
            mShutdownSD = true;
            mUnmountPrimary = true;
        } else if (mState.equals(Environment.MEDIA_SHARED)) {
            mShutdownSD = true;
        }
        MountShutdownLatch mountShutdownLatch = new MountShutdownLatch(observer,
                                                        snapshot.size());

        for (String path : snapshot.keySet()) {
            String state = snapshot.get(path);

            if (state.equals(Environment.MEDIA_SHARED)) {
                /*
                 * If the media is currently shared, unshare it.
                 * XXX: This is still dangerous!. We should not
                 * be rebooting at *all* if UMS is enabled, since
                 * the UMS host could have dirty FAT cache entries
                 * yet to flush.
                 */
                //M: Shutdown case, just unshare volume, no need to mount it.
                //setUsbMassStorageEnabled(false);
                doShareUnshareVolume(path, "ums", false);
            } else if (state.equals(Environment.MEDIA_CHECKING)) {
                /*
                 * If the media is being checked, then we need to wait for
                 * it to complete before being able to proceed.
                 */
                // XXX: @hackbod - Should we disable the ANR timer here?
                int retries = 30;
                while (state.equals(Environment.MEDIA_CHECKING) && (retries-- >= 0)) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException iex) {
                        Slog.e(TAG, "Interrupted while waiting for media", iex);
                        break;
                    }
                    state = getVolumeState(path);
                }
                if (retries == 0) {
                    Slog.e(TAG, "Timed out waiting for media to check");
                }
            }

            if (state.equals(Environment.MEDIA_MOUNTED)) {
                // because unmount will fail when emulated path is "storage/emulated/..."
                // this will cause IPO power off fail, so just skip to do unmount when primary is emulated
                //if (path.contains("/storage/emulated/") && SystemProperties.get(PROP_SHARED_SDCARD).equals("1") && !SystemProperties.get(PROP_2SDCARD_SWAP).equals("1")) {
                //    mUnmountPrimary = false;
                //    continue;
                //}
                StorageVolume volume;
                synchronized (mVolumesLock) {
                    volume = mVolumesByPath.get(path);
                }
                if (volume.isEmulated()) {
                    if (volume.isPrimary()) {
                        mUnmountPrimary = false;
                    }
                    mountShutdownLatch.countDown();
                    Slog.i(TAG, "path=" + path + " isEmulated, so countDown mountShutdownLatch");
                    continue;
                }

                mShutdownCount++;
                // Post a unmount message.
                ShutdownCallBack ucb = new ShutdownCallBack(path, mountShutdownLatch);
                mHandler.sendMessage(mHandler.obtainMessage(H_UNMOUNT_PM_UPDATE, ucb));
            } else if (observer != null) {
                    /*
                     * Count down, since nothing will be done. The observer will be
                     * notified when we are done so shutdown sequence can continue.
                     */
                    mountShutdownLatch.countDown();
                    Slog.i(TAG, "Unmount completed: " + path +
                        ", result code: " + StorageResultCode.OperationSucceeded);
            }
        }

        if (SystemProperties.get(PROP_2SDCARD_SWAP).equals("1")) {
            new Thread() {
                @Override
                public void run() {
                    try {
                        Slog.d(TAG, "Notify VOLD IPO shutdown");
                        mConnector.execute("volume", "ipo", "shutdown");
                    } catch (NativeDaemonConnectorException e) {
                        Slog.e(TAG, "Error to notify VOLD IPO shutdown", e);
                    }
                }
            } .start();
        }
    }

    private boolean getUmsEnabling() {
        synchronized (mListeners) {
            return mUmsEnabling;
        }
    }

    private void setUmsEnabling(boolean enable) {
        synchronized (mListeners) {
            mUmsEnabling = enable;
        }
    }

    public boolean isUsbMassStorageConnected() {
        waitForReady();

        if (getUmsEnabling()) {
            return true;
        }
        synchronized (mListeners) {
            return mUmsAvailable;
        }
    }

    public void setUsbMassStorageEnabled(boolean enable) {
        waitForReady();
        validatePermission(android.Manifest.permission.MOUNT_UNMOUNT_FILESYSTEMS);
        validateUserRestriction(UserManager.DISALLOW_USB_FILE_TRANSFER);

        // M: erternal sd card can also share to pc when the primary is emulated.
        //final StorageVolume primary = getPrimaryPhysicalVolume();
        //if (primary == null) return;

        // TODO: Add support for multiple share methods

        /*
         * If the volume is mounted and we're enabling then unmount it
         */
        String[] paths;
        String[] states;
        int count;
        String mState = Environment.getExternalStorageState();
        if (enable && mState.equals(Environment.MEDIA_MOUNTED) && !SystemProperties.get(PROP_SHARED_SDCARD).equals("1")) {
            mUnmountPrimary = true;
        }

        synchronized (mVolumeStates) {
            Set<String> keys = mVolumeStates.keySet();
            count = keys.size();
            paths = keys.toArray(new String[count]);
            states = new String[count];
            for (int i = 0; i < count; i++) {
                states[i] = mVolumeStates.get(paths[i]);
            }
        }

        mMountAll = true;
        for (int i = count - 1; i >= 0; i--) {
            String path = paths[i];
            String state = states[i];
            String method = "ums";

            synchronized (mVolumesLock) {
                StorageVolume volume = mVolumesByPath.get(path);
                if (volume.isEmulated()) {
                    Slog.d(TAG, "Emulated volume: " + path + ", no need share!");
                    continue;
                }
                //ICUSB
                if (volume.getPath().equals("/mnt/udisk/folder1")) {
                    Slog.d(TAG, "ICUSB: " + path + ", no need share!");
                    continue;
                }
            }

            if (enable && state.equals(Environment.MEDIA_MOUNTED)) {
                mIsTurnOnOffUsb = true;
                mUMSCount++;
                // Override for isUsbMassStorageEnabled()

                // For share SD feature, emulated sd can't mount to pc
                // so it will swap to storage/sdcard0 when turn on UMS
                if (SystemProperties.get(PROP_SHARED_SDCARD).equals("1") && SystemProperties.get(PROP_2SDCARD_SWAP).equals("1") &&
                        isExternalStorage(path) && path.equals(EXTERNAL_SD1)) {
                    mUnmountSwap = true;
                    updateDefaultPathForSwap(false, path);
                    Slog.d(TAG, "MTK_SHARED_SDCARD is enabled, need to swap sd when UMS is enabled: mUnmountSwap: " + mUnmountSwap);
                 }

                setUmsEnabling(enable);
                UmsEnableCallBack umscb = new UmsEnableCallBack(path, method, true);
                mHandler.sendMessage(mHandler.obtainMessage(H_UNMOUNT_PM_UPDATE, umscb));
                // Clear override
                setUmsEnabling(false);
            } else if (enable && state.equals(Environment.MEDIA_UNMOUNTED)) {
                doShareUnshareVolume(path, method, enable);
            }
            /*
                     * If we disabled UMS then mount the volume
                     */
            if (!enable && !state.equals(Environment.MEDIA_REMOVED) && !state.equals(Environment.MEDIA_BAD_REMOVAL)
                  && !state.equals(Environment.MEDIA_NOFS)) {
                // wait all UMS callback has done..
                int retry = 15;
                while (mUMSCount > 0 && retry > 0) {
                    SystemClock.sleep(1000);
                    retry-- ;
                    Slog.d(TAG, "Turn off UMS, wait for turn on UMS done!");
                }
                mIsTurnOnOffUsb = true;
                doShareUnshareVolume(path, method, enable);
                if (mountVolume(path) != StorageResultCode.OperationSucceeded) {
                    Slog.e(TAG, "Failed to remount " + path +
                           " after disabling share method " + method);
                          /*
                            * Even though the mount failed, the unshare didn't so don't indicate an error.
                            * The mountVolume() call will have set the storage state and sent the necessary
                            * broadcasts.
                            */
                }
                mIsTurnOnOffUsb = false;
            }
        }
        mMountAll = false;
    }

    public boolean isUsbMassStorageEnabled() {
        waitForReady();
        // M: Due to multi storage, we should check all of storage
        // if there is any one storage in share status, return value should be true
        // otherwise will return false.
        String[] paths;
        int count;
        synchronized (mVolumes) {
            count = mVolumes.size();
            paths = new String[count];
            for (int i = 0; i < count; i++) {
                paths[i] = mVolumes.get(i).getPath();
            }
        }

        for (int i = 0; i < count; i++) {
            if (doGetVolumeShared(paths[i], "ums")) {
                Slog.i(TAG, "isUsbMassStorageEnabled: " + paths[i] + " true");
                return true;
            }
        }

        Slog.i(TAG, "isUsbMassStorageEnabled: false");
        return false;
    }

    /**
     * @return state of the volume at the specified mount point
     */
    public String getVolumeState(String mountPoint) {
        synchronized (mVolumesLock) {
            String state = mVolumeStates.get(mountPoint);
            if (state == null) {
                Slog.w(TAG, "getVolumeState(" + mountPoint + "): Unknown volume");
                if (SystemProperties.get("vold.encrypt_progress").length() != 0) {
                    state = Environment.MEDIA_REMOVED;
                } else {
                    //throw new IllegalArgumentException();
                    Slog.e(TAG, "getVolumeState(" + mountPoint + "): ERROR!");
                    state = Environment.MEDIA_UNKNOWN;
                }
            }

            return state;
        }
    }

    @Override
    public boolean isExternalStorageEmulated() {
        return mEmulatedTemplate != null;
    }

    public int mountVolume(String path) {
        validatePermission(android.Manifest.permission.MOUNT_UNMOUNT_FILESYSTEMS);
        boolean isExternal = isExternalStorage(path);
        if (SystemProperties.get(PROP_2SDCARD_SWAP).equals("1") && isExternal && !path.equals(EXTERNAL_SD1)) {
            mMountSwap = true;
            StorageVolume volume;
            synchronized (mVolumesLock) {
                volume = mVolumesByPath.get(EXTERNAL_SD1);
            } 

            if(SystemProperties.get(PROP_OWNER_SDCARD_SUPPORT).equals("1") 
               && (mOldUserId != mUserId)){
                /*
                 * we have to send the intent in another thread to avoid dead lock as
                 * AMS will call #getVolumeList() to acquire the mVolumesLock.
                 */
                 final StorageVolume tempV = volume;
                 new Thread() {
                     public void run() {                        
                          sendStorageIntent(Intent.ACTION_MEDIA_EJECT, tempV, UserHandle.ALL);
                     }
                 }.start();
            } else {
                sendStorageIntent(Intent.ACTION_MEDIA_EJECT, volume, UserHandle.ALL);
            }           
                        
            SystemClock.sleep(MEDIA_EJECT_TIME);
            updateDefaultPathForSwap(true, path);
        }

        waitForReady();
        int ret = doMountVolume(path);

        if (SystemProperties.get(PROP_2SDCARD_SWAP).equals("1")) {
            if (mMountSwap && StorageResultCode.OperationSucceeded == ret) {
                //swap mount Succeed, do swap
                mMountSwap = false;
                doSDSwapVolumeUpdate();
                updateDefaultpath();
                if(SystemProperties.get(PROP_OWNER_SDCARD_SUPPORT).equals("1") 
                   && (mOldUserId != mUserId)){
                    /*
                     * we have to send the intent in another thread to avoid dead lock as
                     * AMS will call #getVolumeList() to acquire the mVolumesLock.
                     */
                     new Thread() {
                         public void run() {                        
                              sendSDSwapIntent();
                         }
                     }.start();
                } else {
                    sendSDSwapIntent();
                }   
            } else if (StorageResultCode.OperationSucceeded != ret && isExternal) {
                //normal mount external sd fail, do swap
                doSDSwapVolumeUpdate();
                updateDefaultpath();
                if(SystemProperties.get(PROP_OWNER_SDCARD_SUPPORT).equals("1") 
                   && (mOldUserId != mUserId)){
                    /*
                     * we have to send the intent in another thread to avoid dead lock as
                     * AMS will call #getVolumeList() to acquire the mVolumesLock.
                     */
                     new Thread() {
                         public void run() {                        
                              sendSDSwapIntent();
                         }
                     }.start();
                } else {
                    sendSDSwapIntent();
                }           
            }
        }

        return ret;
    }

    public void unmountVolume(String path, boolean force, boolean removeEncryption) {
        validatePermission(android.Manifest.permission.MOUNT_UNMOUNT_FILESYSTEMS);
        waitForReady();

        String volState = getVolumeState(path);
        if (DEBUG_UNMOUNT) {
            Slog.i(TAG, "Unmounting " + path
                    + " force = " + force
                    + " removeEncryption = " + removeEncryption);
        }
        if (SystemProperties.get(PROP_2SDCARD_SWAP).equals("1") && isExternalStorage(path) &&
                    path.equals(EXTERNAL_SD1)) {
            mUnmountSwap = true;
            updateDefaultPathForSwap(false, path);
            try {
                Slog.i(TAG, "set prop sys.sd.swapping=1");
                SystemProperties.set(PROP_SWAPPING, "1");
            } catch (IllegalArgumentException e) {
                Slog.e(TAG, "IllegalArgumentException when set sys.sd.swapping:", e);
            }
        }
        if (Environment.MEDIA_UNMOUNTED.equals(volState) ||
                Environment.MEDIA_REMOVED.equals(volState) ||
                Environment.MEDIA_SHARED.equals(volState) ||
                Environment.MEDIA_UNMOUNTABLE.equals(volState)) {
            // Media already unmounted or cannot be unmounted.
            // TODO return valid return code when adding observer call back.
            return;
        }

        // set prop unmounting
        try {
            Slog.i(TAG, "set prop sys.sd.unmounting = " + path);
            SystemProperties.set(PROP_UNMOUNTING, path);
        } catch (IllegalArgumentException e) {
            Slog.e(TAG, "IllegalArgumentException when set sys.sd.unmounting:", e);
        }

        UnmountCallBack ucb = new UnmountCallBack(path, force, removeEncryption);
        mHandler.sendMessage(mHandler.obtainMessage(H_UNMOUNT_PM_UPDATE, ucb));
    }

    public void unmountVolumeForUserSwitch(String path, boolean force, boolean removeEncryption) {
        validatePermission(android.Manifest.permission.MOUNT_UNMOUNT_FILESYSTEMS);
        waitForReady();

        String volState = getVolumeState(path);
        if (DEBUG_UNMOUNT) {
            Slog.i(TAG, "User Switch Unmounting " + path
                    + " force = " + force
                    + " removeEncryption = " + removeEncryption);
        }
        if (SystemProperties.get(PROP_2SDCARD_SWAP).equals("1") && isExternalStorage(path) &&
                    path.equals(EXTERNAL_SD1)) {
            mUnmountSwap = true;
            updateDefaultPathForSwap(false, path);
        }
        if (Environment.MEDIA_UNMOUNTED.equals(volState) ||
                Environment.MEDIA_REMOVED.equals(volState) ||
                Environment.MEDIA_SHARED.equals(volState)) {
            // Media already unmounted or cannot be unmounted.
            // TODO return valid return code when adding observer call back.
            return;
        }

        StorageVolume volume;
        synchronized (mVolumesLock) {
            volume = mVolumesByPath.get(path);
        }
        updatePublicVolumeState(volume, Environment.MEDIA_UNMOUNTED);
        if (mSetDefaultEnable && !mIsTurnOnOffUsb && !mMountSwap && !mUnmountSwap) {
            Slog.i(TAG, "updateDefaultpath Environment.MEDIA_UNMOUNTED");
            updateDefaultpath();
        }

        final StorageVolume tempV = volume;
        /*
         * we have to send the intent in another thread to avoid dead lock as
         * AMS will call #getVolumeList() to acquire the mVolumesLock.
         */
        new Thread() {
            public void run() {
                sendStorageIntent(Intent.ACTION_MEDIA_UNMOUNTED, tempV, UserHandle.ALL);
            }
        }.start();

        // set prop unmounting
        try {
            Slog.i(TAG, "set prop sys.sd.unmounting = " + path);
            SystemProperties.set(PROP_UNMOUNTING, path);
        } catch (IllegalArgumentException e) {
            Slog.e(TAG, "IllegalArgumentException when set sys.sd.unmounting:", e);
        }

        UnmountCallBack ucb = new UnmountCallBack(path, force, removeEncryption, true);
        mHandler.sendMessage(mHandler.obtainMessage(H_UNMOUNT_PM_UPDATE, ucb));
    }

    public int formatVolume(String path) {
        validatePermission(android.Manifest.permission.MOUNT_FORMAT_FILESYSTEMS);
        waitForReady();

        return doFormatVolume(path);
    }

    public int[] getStorageUsers(String path) {
        validatePermission(android.Manifest.permission.MOUNT_UNMOUNT_FILESYSTEMS);
        waitForReady();
        try {
            final String[] r = NativeDaemonEvent.filterMessageList(
                    mConnector.executeForList("storage", "users", path),
                    VoldResponseCode.StorageUsersListResult);

            // FMT: <pid> <process name>
            int[] data = new int[r.length];
            for (int i = 0; i < r.length; i++) {
                String[] tok = r[i].split(" ");
                try {
                    data[i] = Integer.parseInt(tok[0]);
                } catch (NumberFormatException nfe) {
                    Slog.e(TAG, String.format("Error parsing pid %s", tok[0]));
                    return new int[0];
                }
            }
            return data;
        } catch (NativeDaemonConnectorException e) {
            Slog.e(TAG, "Failed to retrieve storage users list", e);
            return new int[0];
        }
    }

    private void warnOnNotMounted() {
        final StorageVolume primary = getPrimaryPhysicalVolume();
        if (primary != null) {
            boolean mounted = false;
            try {
                mounted = Environment.MEDIA_MOUNTED.equals(getVolumeState(primary.getPath()));
            } catch (IllegalArgumentException e) {
            }

            if (!mounted) {
                Slog.w(TAG, "getSecureContainerList() called when storage not mounted");
            }
        }
    }

    public String[] getSecureContainerList() {
        validatePermission(android.Manifest.permission.ASEC_ACCESS);
        waitForReady();
        warnOnNotMounted();

        try {
            return NativeDaemonEvent.filterMessageList(
                    mConnector.executeForList("asec", "list"), VoldResponseCode.AsecListResult);
        } catch (NativeDaemonConnectorException e) {
            return new String[0];
        }
    }

    public int createSecureContainer(String id, int sizeMb, String fstype, String key,
            int ownerUid, boolean external) {
        if (SystemProperties.get(PROP_2SDCARD_SWAP).equals("1") &&
            getVolumeState(EXTERNAL_SD1).equals(Environment.MEDIA_MOUNTED)) {
            if (external && !getSwapState()) {
                Slog.e(TAG, "External SD not exist, and PMS want to create ASEC in SD (APP2SD). For SWAP feature, make createSecureContainer() fail!");
                return StorageResultCode.OperationFailedInternalError;
            }
        }

		Slog.d(TAG, "createSecureContainer:id:" + id +", external:" + external); //added for debug.chenwenshuai.

        validatePermission(android.Manifest.permission.ASEC_CREATE);
        waitForReady();
        warnOnNotMounted();

        int rc = StorageResultCode.OperationSucceeded;
        try {
            mConnector.execute("asec", "create", id, sizeMb, fstype, new SensitiveArg(key),
                    ownerUid, external ? "1" : "0");
        } catch (NativeDaemonConnectorException e) {
            rc = StorageResultCode.OperationFailedInternalError;
        }

        if (rc == StorageResultCode.OperationSucceeded) {
            synchronized (mAsecMountSet) {
                mAsecMountSet.add(id);
            }
        }
        return rc;
    }

    @Override
    public int resizeSecureContainer(String id, int sizeMb, String key) {
        validatePermission(android.Manifest.permission.ASEC_CREATE);
        waitForReady();
        warnOnNotMounted();

        int rc = StorageResultCode.OperationSucceeded;
        try {
            mConnector.execute("asec", "resize", id, sizeMb, new SensitiveArg(key));
        } catch (NativeDaemonConnectorException e) {
            rc = StorageResultCode.OperationFailedInternalError;
        }
        return rc;
    }

    public int finalizeSecureContainer(String id) {
        validatePermission(android.Manifest.permission.ASEC_CREATE);
        warnOnNotMounted();

        int rc = StorageResultCode.OperationSucceeded;
        try {
            mConnector.execute("asec", "finalize", id);
            /*
             * Finalization does a remount, so no need
             * to update mAsecMountSet
             */
        } catch (NativeDaemonConnectorException e) {
            rc = StorageResultCode.OperationFailedInternalError;
        }
        return rc;
    }

    public int fixPermissionsSecureContainer(String id, int gid, String filename) {
        validatePermission(android.Manifest.permission.ASEC_CREATE);
        warnOnNotMounted();

        int rc = StorageResultCode.OperationSucceeded;
        try {
            mConnector.execute("asec", "fixperms", id, gid, filename);
            /*
             * Fix permissions does a remount, so no need to update
             * mAsecMountSet
             */
        } catch (NativeDaemonConnectorException e) {
            rc = StorageResultCode.OperationFailedInternalError;
        }
        return rc;
    }

    public int destroySecureContainer(String id, boolean force) {
        validatePermission(android.Manifest.permission.ASEC_DESTROY);
        waitForReady();
        warnOnNotMounted();

        /*
         * Force a GC to make sure AssetManagers in other threads of the
         * system_server are cleaned up. We have to do this since AssetManager
         * instances are kept as a WeakReference and it's possible we have files
         * open on the external storage.
         */
        Runtime.getRuntime().gc();
        System.runFinalization();

        int rc = StorageResultCode.OperationSucceeded;
        try {
            final Command cmd = new Command("asec", "destroy", id);
            if (force) {
                cmd.appendArg("force");
            }
            mConnector.execute(cmd);
        } catch (NativeDaemonConnectorException e) {
            int code = e.getCode();
            if (code == VoldResponseCode.OpFailedStorageBusy) {
                rc = StorageResultCode.OperationFailedStorageBusy;
            } else {
                rc = StorageResultCode.OperationFailedInternalError;
            }
        }

        if (rc == StorageResultCode.OperationSucceeded) {
            synchronized (mAsecMountSet) {
                if (mAsecMountSet.contains(id)) {
                    mAsecMountSet.remove(id);
                }
            }
        }

        return rc;
    }

    public int mountSecureContainer(String id, String key, int ownerUid, boolean readOnly) {
        validatePermission(android.Manifest.permission.ASEC_MOUNT_UNMOUNT);
        waitForReady();
        warnOnNotMounted();

		Slog.d(TAG, "mountSecureContainer:id" + id);//add app install sd debug chenwenshuai

        synchronized (mAsecMountSet) {
            if (mAsecMountSet.contains(id)) {
                return StorageResultCode.OperationFailedStorageMounted;
            }
        }

        int rc = StorageResultCode.OperationSucceeded;
        try {
            mConnector.execute("asec", "mount", id, new SensitiveArg(key), ownerUid,
                    readOnly ? "ro" : "rw");
        } catch (NativeDaemonConnectorException e) {
            int code = e.getCode();
			Slog.d(TAG, "mountSecureContainer:mount result:" + code);//add app install sd debug chenwenshuai
            if (code != VoldResponseCode.OpFailedStorageBusy) {
                rc = StorageResultCode.OperationFailedInternalError;
            }
        }

        if (rc == StorageResultCode.OperationSucceeded) {
            synchronized (mAsecMountSet) {
                mAsecMountSet.add(id);
            }
        }
        return rc;
    }

    public int unmountSecureContainer(String id, boolean force) {
        validatePermission(android.Manifest.permission.ASEC_MOUNT_UNMOUNT);
        waitForReady();
        warnOnNotMounted();

		Slog.d(TAG,"unmountSecureContainer: id: " + id);//add app install sd debug chenwenshuai

        synchronized (mAsecMountSet) {
            if (!mAsecMountSet.contains(id)) {
                return StorageResultCode.OperationFailedStorageNotMounted;
            }
         }

        /*
         * Force a GC to make sure AssetManagers in other threads of the
         * system_server are cleaned up. We have to do this since AssetManager
         * instances are kept as a WeakReference and it's possible we have files
         * open on the external storage.
         */
        Runtime.getRuntime().gc();
        System.runFinalization();

		Slog.d(TAG,"unmountSecureContainer:begin to umount asec" );//add app install sd debug chenwenshuai

        int rc = StorageResultCode.OperationSucceeded;
        try {
            final Command cmd = new Command("asec", "unmount", id);
            if (force) {
                cmd.appendArg("force");
            }
            mConnector.execute(cmd);
        } catch (NativeDaemonConnectorException e) {
            int code = e.getCode();
            if (code == VoldResponseCode.OpFailedStorageBusy) {
                rc = StorageResultCode.OperationFailedStorageBusy;
            } else {
                rc = StorageResultCode.OperationFailedInternalError;
            }
        }

		Slog.d(TAG,"unmountSecureContainer:begin to umount asec" );//add app install sd debug chenwenshuai
				
	   rc = StorageResultCode.OperationSucceeded;

        if (rc == StorageResultCode.OperationSucceeded) {
            synchronized (mAsecMountSet) {
                mAsecMountSet.remove(id);
            }
        }
        return rc;
    }

    public boolean isSecureContainerMounted(String id) {
        validatePermission(android.Manifest.permission.ASEC_ACCESS);
        waitForReady();
        warnOnNotMounted();

        synchronized (mAsecMountSet) {
			Slog.d(TAG,"isSecureContainerMounted:mAsecMountSet" + mAsecMountSet + ",id" + id);//add app install sd debug chenwenshuai
            return mAsecMountSet.contains(id);
        }
    }

    public int renameSecureContainer(String oldId, String newId) {
        validatePermission(android.Manifest.permission.ASEC_RENAME);
        waitForReady();
        warnOnNotMounted();

        synchronized (mAsecMountSet) {
            /*
             * Because a mounted container has active internal state which cannot be
             * changed while active, we must ensure both ids are not currently mounted.
             */
            if (mAsecMountSet.contains(oldId) || mAsecMountSet.contains(newId)) {
                return StorageResultCode.OperationFailedStorageMounted;
            }
        }

        int rc = StorageResultCode.OperationSucceeded;
        try {
            mConnector.execute("asec", "rename", oldId, newId);
        } catch (NativeDaemonConnectorException e) {
            rc = StorageResultCode.OperationFailedInternalError;
        }

        return rc;
    }

    public String getSecureContainerPath(String id) {
        validatePermission(android.Manifest.permission.ASEC_ACCESS);
        waitForReady();
        warnOnNotMounted();

        final NativeDaemonEvent event;
        try {
            event = mConnector.execute("asec", "path", id);
            event.checkCode(VoldResponseCode.AsecPathResult);
            return event.getMessage();
        } catch (NativeDaemonConnectorException e) {
            int code = e.getCode();
            if (code == VoldResponseCode.OpFailedStorageNotFound) {
                Slog.i(TAG, String.format("Container '%s' not found", id));
                return null;
            } else {
                throw new IllegalStateException(String.format("Unexpected response code %d", code));
            }
        }
    }

    public String getSecureContainerFilesystemPath(String id) {
        validatePermission(android.Manifest.permission.ASEC_ACCESS);
        waitForReady();
        warnOnNotMounted();

        final NativeDaemonEvent event;
        try {
            event = mConnector.execute("asec", "fspath", id);
            event.checkCode(VoldResponseCode.AsecPathResult);
            return event.getMessage();
        } catch (NativeDaemonConnectorException e) {
            int code = e.getCode();
            if (code == VoldResponseCode.OpFailedStorageNotFound) {
                Slog.i(TAG, String.format("Container '%s' not found", id));
                return null;
            } else {
                throw new IllegalStateException(String.format("Unexpected response code %d", code));
            }
        }
    }

    public void finishMediaUpdate() {
        mHandler.sendEmptyMessage(H_UNMOUNT_PM_DONE);
    }

	 /*chenwenshuai add for HQ00884925 begin*/
    public void startUpdateApp() {
       Slog.d(TAG, "startUpdateApp" );
        mHandler.sendEmptyMessage(H_UPDATE_APP_START);
    }

    public void endUpdateApp() {
        mHandler.sendEmptyMessage(H_UPDATE_APP_END);
    } 
    /*chenwenshuai add for Multiple plug reboot 2015.1.24*/
    public void endUnloadApp(){
        Slog.d(TAG, "mountservice endUnloadApp isBadRemove ="+isBadRemove ); 
        if(isBadRemove ){
			/* HQ_ChenWenshuai 2015-10-05 modified for HQ01419244 begin */
			synchronized (mAsecMountSet) {
                mAsecMountSet.clear();
            }
			/*HQ_ChenWenshuai 2015-10-05 modified end */
            mHandler.sendEmptyMessageDelayed(H_UNLOAD_APP_END,3000);
            isBadRemove = false;
        }
    }
    /* Multiple plug reboot */
    AlertDialog dialogStart = null;
    AlertDialog dialogEnd = null;
    AlertDialog dialogUnloadStart = null;

    
    public void handlStartUpdateApp(){
	if(dialogEnd != null){
		dialogEnd.cancel();
	}
	 dialogStart = new AlertDialog.Builder(mContext)  
	                .setMessage(com.mediatek.internal.R.string.app_move_start_msg)
	                .setTitle(com.mediatek.internal.R.string.space_low_warning)
	                .setPositiveButton(com.mediatek.internal.R.string.app_move_msg_confirm, new DialogInterface.OnClickListener() {
					public void onClick(DialogInterface dialog, int which) {
	                        dialog.cancel();
	                    }
	                }).create();
	dialogStart.getWindow().setType((WindowManager.LayoutParams.TYPE_SYSTEM_ALERT));
	dialogStart.show();
     }

    public void handlEndUpdateApp(){		
	if ( dialogStart != null ) { 
		dialogStart.cancel();
	 }
	dialogEnd =	new AlertDialog.Builder(mContext)  
                .setMessage(com.mediatek.internal.R.string.app_move_end_msg)
                .setTitle(com.mediatek.internal.R.string.space_low_warning)
                .setPositiveButton(com.mediatek.internal.R.string.app_move_msg_confirm, new DialogInterface.OnClickListener() {
				public void onClick(DialogInterface dialog, int which) {
                        dialog.cancel();
                    }
                }).create();  
		dialogEnd.getWindow().setType((WindowManager.LayoutParams.TYPE_SYSTEM_ALERT));
		dialogEnd.show();
    }
    
    /*chenwenshuai add for Multiple plug reboot 2015.1.24*/
    public void  handlEndUnloadApp(){
            dialogUnloadStart = new AlertDialog.Builder(mContext)  
                .setMessage(com.mediatek.internal.R.string.app_unload_start_msg)
                .setTitle(com.mediatek.internal.R.string.space_low_warning)
                .setPositiveButton(com.mediatek.internal.R.string.app_reboot_btn_now, new DialogInterface.OnClickListener() {
				public void onClick(DialogInterface dialog, int which) {
                                    Intent intent = new Intent(Intent.ACTION_REBOOT);
                                    intent.putExtra("nowait", 1);
                                    intent.putExtra("interval", 1);
                                    intent.putExtra("window", 0);
                                    mContext.sendBroadcast(intent);
                    }
                })
                .setNegativeButton(com.mediatek.internal.R.string.app_reboot_btn_late, new DialogInterface.OnClickListener() {
				public void onClick(DialogInterface dialog, int which) {
                                   dialog.cancel();
                    }
                }).create();  
		dialogUnloadStart.getWindow().setType((WindowManager.LayoutParams.TYPE_SYSTEM_ALERT));
                dialogUnloadStart. setCanceledOnTouchOutside(false);
		dialogUnloadStart.show();
    }
    /*chenwenshuai add end*/
	
	/*chenwenshuai add for HQ01040648 2015.4.17*/
	public void addMoveAppRef() {
		appMoveRef++;
	}

	public void deleteMoveAppRef() {
		appMoveRef--;	
	}

	public int getMoveAppRef() {
		return appMoveRef;	
	}
	/*chenwenshuai add end*/

    private boolean isUidOwnerOfPackageOrSystem(String packageName, int callerUid) {
        if (callerUid == android.os.Process.SYSTEM_UID) {
            return true;
        }

        if (packageName == null) {
            return false;
        }

        final int packageUid = mPms.getPackageUid(packageName, UserHandle.getUserId(callerUid));

        if (DEBUG_OBB) {
            Slog.d(TAG, "packageName = " + packageName + ", packageUid = " +
                    packageUid + ", callerUid = " + callerUid);
        }

        return callerUid == packageUid;
    }

    public String getMountedObbPath(String rawPath) {
        Preconditions.checkNotNull(rawPath, "rawPath cannot be null");

        waitForReady();
        warnOnNotMounted();

        final ObbState state;
        synchronized (mObbPathToStateMap) {
            state = mObbPathToStateMap.get(rawPath);
        }
        if (state == null) {
            Slog.w(TAG, "Failed to find OBB mounted at " + rawPath);
            return null;
        }

        final NativeDaemonEvent event;
        try {
            event = mConnector.execute("obb", "path", state.voldPath);
            event.checkCode(VoldResponseCode.AsecPathResult);
            return event.getMessage();
        } catch (NativeDaemonConnectorException e) {
            int code = e.getCode();
            if (code == VoldResponseCode.OpFailedStorageNotFound) {
                return null;
            } else {
                throw new IllegalStateException(String.format("Unexpected response code %d", code));
            }
        }
    }

    @Override
    public boolean isObbMounted(String rawPath) {
        Preconditions.checkNotNull(rawPath, "rawPath cannot be null");
        synchronized (mObbMounts) {
            return mObbPathToStateMap.containsKey(rawPath);
        }
    }

    @Override
    public void mountObb(
            String rawPath, String canonicalPath, String key, IObbActionListener token, int nonce) {
        Preconditions.checkNotNull(rawPath, "rawPath cannot be null");
        Preconditions.checkNotNull(canonicalPath, "canonicalPath cannot be null");
        Preconditions.checkNotNull(token, "token cannot be null");

        final int callingUid = Binder.getCallingUid();
        final ObbState obbState = new ObbState(rawPath, canonicalPath, callingUid, token, nonce);
        final ObbAction action = new MountObbAction(obbState, key, callingUid);
        mObbActionHandler.sendMessage(mObbActionHandler.obtainMessage(OBB_RUN_ACTION, action));

        if (DEBUG_OBB)
            Slog.i(TAG, "Send to OBB handler: " + action.toString());
    }

    @Override
    public void unmountObb(String rawPath, boolean force, IObbActionListener token, int nonce) {
        Preconditions.checkNotNull(rawPath, "rawPath cannot be null");

        final ObbState existingState;
        synchronized (mObbPathToStateMap) {
            existingState = mObbPathToStateMap.get(rawPath);
        }

        if (existingState != null) {
            // TODO: separate state object from request data
            final int callingUid = Binder.getCallingUid();
            final ObbState newState = new ObbState(
                    rawPath, existingState.canonicalPath, callingUid, token, nonce);
            final ObbAction action = new UnmountObbAction(newState, force);
            mObbActionHandler.sendMessage(mObbActionHandler.obtainMessage(OBB_RUN_ACTION, action));

            if (DEBUG_OBB)
                Slog.i(TAG, "Send to OBB handler: " + action.toString());
        } else {
            Slog.w(TAG, "Unknown OBB mount at " + rawPath);
        }
    }

    @Override
    public int getEncryptionState() {
        mContext.enforceCallingOrSelfPermission(Manifest.permission.CRYPT_KEEPER,
                "no permission to access the crypt keeper");

        waitForReady();

        final NativeDaemonEvent event;
        try {
            event = mConnector.execute("cryptfs", "cryptocomplete");
            return Integer.parseInt(event.getMessage());
        } catch (NumberFormatException e) {
            // Bad result - unexpected.
            Slog.w(TAG, "Unable to parse result from cryptfs cryptocomplete");
            return ENCRYPTION_STATE_ERROR_UNKNOWN;
        } catch (NativeDaemonConnectorException e) {
            // Something bad happened.
            Slog.w(TAG, "Error in communicating with cryptfs in validating");
            return ENCRYPTION_STATE_ERROR_UNKNOWN;
        }
    }

    private String toHex(String password) {
        if (password == null) {
            return new String();
        }
        byte[] bytes = password.getBytes(StandardCharsets.UTF_8);
        return new String(Hex.encodeHex(bytes));
    }

    private String fromHex(String hexPassword) {
        if (hexPassword == null) {
            return null;
        }

        try {
            byte[] bytes = Hex.decodeHex(hexPassword.toCharArray());
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (DecoderException e) {
            return null;
        }
    }

    @Override
    public int decryptStorage(String password) {
        if (TextUtils.isEmpty(password)) {
            throw new IllegalArgumentException("password cannot be empty");
        }

        mContext.enforceCallingOrSelfPermission(Manifest.permission.CRYPT_KEEPER,
                "no permission to access the crypt keeper");

        waitForReady();

        if (DEBUG_EVENTS) {
            Slog.i(TAG, "decrypting storage...");
        }

        final NativeDaemonEvent event;
        try {
            event = mConnector.execute("cryptfs", "checkpw", new SensitiveArg(toHex(password)));

            final int code = Integer.parseInt(event.getMessage());
            if (code == 0) {
                // Decrypt was successful. Post a delayed message before restarting in order
                // to let the UI to clear itself
                mHandler.postDelayed(new Runnable() {
                    public void run() {
                        try {
                            mConnector.execute("cryptfs", "restart");
                        } catch (NativeDaemonConnectorException e) {
                            Slog.e(TAG, "problem executing in background", e);
                        }
                    }
                }, 3000); // 1 second
            }

            return code;
        } catch (NativeDaemonConnectorException e) {
            // Decryption failed
            return e.getCode();
        }
    }

    public int encryptStorage(int type, String password) {
        if (TextUtils.isEmpty(password) && type != StorageManager.CRYPT_TYPE_DEFAULT) {
            throw new IllegalArgumentException("password cannot be empty");
        }

        mContext.enforceCallingOrSelfPermission(Manifest.permission.CRYPT_KEEPER,
            "no permission to access the crypt keeper");

        waitForReady();

        if (DEBUG_EVENTS) {
            Slog.i(TAG, "encrypting storage...");
        }

        try {
            mConnector.execute("cryptfs", "enablecrypto", "inplace", CRYPTO_TYPES[type],
                               new SensitiveArg(toHex(password)));
        } catch (NativeDaemonConnectorException e) {
            // Encryption failed
            return e.getCode();
        }

        return 0;
    }

    /** Set the password for encrypting the master key.
     *  @param type One of the CRYPTO_TYPE_XXX consts defined in StorageManager.
     *  @param password The password to set.
     */
    public int changeEncryptionPassword(int type, String password) {
        mContext.enforceCallingOrSelfPermission(Manifest.permission.CRYPT_KEEPER,
            "no permission to access the crypt keeper");

        waitForReady();

        if (DEBUG_EVENTS) {
            Slog.i(TAG, "changing encryption password...");
        }

        if (mOldEncryptionType == -1) {
            mOldEncryptionType = getPasswordType();
        }

        try {
            NativeDaemonEvent event = mConnector.execute("cryptfs", "changepw", CRYPTO_TYPES[type],
                        new SensitiveArg(toHex(password)));

            if (type != mOldEncryptionType) {
                Slog.i(TAG, "Encryption type changed from " + mOldEncryptionType + " to " + type);
                mOldEncryptionType = type;
                sendEncryptionTypeIntent();
            }

            return Integer.parseInt(event.getMessage());
        } catch (NativeDaemonConnectorException e) {
            // Encryption failed
            return e.getCode();
        }
    }

    /**
     * Validate a user-supplied password string with cryptfs
     */
    @Override
    public int verifyEncryptionPassword(String password) throws RemoteException {
        // Only the system process is permitted to validate passwords
        if (Binder.getCallingUid() != android.os.Process.SYSTEM_UID) {
            throw new SecurityException("no permission to access the crypt keeper");
        }

        mContext.enforceCallingOrSelfPermission(Manifest.permission.CRYPT_KEEPER,
            "no permission to access the crypt keeper");

        if (TextUtils.isEmpty(password)) {
            throw new IllegalArgumentException("password cannot be empty");
        }

        waitForReady();

        if (DEBUG_EVENTS) {
            Slog.i(TAG, "validating encryption password...");
        }

        final NativeDaemonEvent event;
        try {
            event = mConnector.execute("cryptfs", "verifypw", new SensitiveArg(toHex(password)));
            Slog.i(TAG, "cryptfs verifypw => " + event.getMessage());
            return Integer.parseInt(event.getMessage());
        } catch (NativeDaemonConnectorException e) {
            // Encryption failed
            return e.getCode();
        }
    }

    /**
     * Get the type of encryption used to encrypt the master key.
     * @return The type, one of the CRYPT_TYPE_XXX consts from StorageManager.
     */
    @Override
    public int getPasswordType() {

        waitForReady();

        final NativeDaemonEvent event;
        try {
            event = mConnector.execute("cryptfs", "getpwtype");
            for (int i = 0; i < CRYPTO_TYPES.length; ++i) {
                if (CRYPTO_TYPES[i].equals(event.getMessage()))
                    return i;
            }

            throw new IllegalStateException("unexpected return from cryptfs");
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        }
    }

    /**
     * Set a field in the crypto header.
     * @param field field to set
     * @param contents contents to set in field
     */
    @Override
    public void setField(String field, String contents) throws RemoteException {

        waitForReady();

        final NativeDaemonEvent event;
        try {
            event = mConnector.execute("cryptfs", "setfield", field, contents);
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        }
    }

    /**
     * Gets a field from the crypto header.
     * @param field field to get
     * @return contents of field
     */
    @Override
    public String getField(String field) throws RemoteException {

        waitForReady();

        final NativeDaemonEvent event;
        try {
            final String[] contents = NativeDaemonEvent.filterMessageList(
                    mConnector.executeForList("cryptfs", "getfield", field),
                    VoldResponseCode.CryptfsGetfieldResult);
            String result = new String();
            for (String content : contents) {
                result += content;
            }
            return result;
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        }
    }

    @Override
    public String getPassword() throws RemoteException {
        if (!isReady()) {
            return new String();
        }

        final NativeDaemonEvent event;
        try {
            event = mConnector.execute("cryptfs", "getpw");
            return fromHex(event.getMessage());
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        }
    }

    @Override
    public void clearPassword() throws RemoteException {
        if (!isReady()) {
            return;
        }

        final NativeDaemonEvent event;
        try {
            event = mConnector.execute("cryptfs", "clearpw");
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        }
    }

    @Override
    public int mkdirs(String callingPkg, String appPath) {
        final int userId = UserHandle.getUserId(Binder.getCallingUid());
        final UserEnvironment userEnv = new UserEnvironment(userId);

        // Validate that reported package name belongs to caller
        final AppOpsManager appOps = (AppOpsManager) mContext.getSystemService(
                Context.APP_OPS_SERVICE);
        appOps.checkPackage(Binder.getCallingUid(), callingPkg);

        try {
            appPath = new File(appPath).getCanonicalPath();
        } catch (IOException e) {
            Slog.e(TAG, "Failed to resolve " + appPath + ": " + e);
            return -1;
        }

        if (!appPath.endsWith("/")) {
            appPath = appPath + "/";
        }

        // Try translating the app path into a vold path, but require that it
        // belong to the calling package.
        String voldPath = maybeTranslatePathForVold(appPath,
                userEnv.buildExternalStorageAppDataDirs(callingPkg),
                userEnv.buildExternalStorageAppDataDirsForVold(callingPkg));
        if (voldPath != null) {
            try {
                mConnector.execute("volume", "mkdirs", voldPath);
                return 0;
            } catch (NativeDaemonConnectorException e) {
                return e.getCode();
            }
        }

        voldPath = maybeTranslatePathForVold(appPath,
                userEnv.buildExternalStorageAppObbDirs(callingPkg),
                userEnv.buildExternalStorageAppObbDirsForVold(callingPkg));
        if (voldPath != null) {
            try {
                mConnector.execute("volume", "mkdirs", voldPath);
                return 0;
            } catch (NativeDaemonConnectorException e) {
                return e.getCode();
            }
        }

        voldPath = maybeTranslatePathForVold(appPath,
                userEnv.buildExternalStorageAppMediaDirs(callingPkg),
                userEnv.buildExternalStorageAppMediaDirsForVold(callingPkg));
        if (voldPath != null) {
            try {
                mConnector.execute("volume", "mkdirs", voldPath);
                return 0;
            } catch (NativeDaemonConnectorException e) {
                return e.getCode();
            }
        }

        throw new SecurityException("Invalid mkdirs path: " + appPath);
    }

    /**
     * Translate the given path from an app-visible path to a vold-visible path,
     * but only if it's under the given whitelisted paths.
     *
     * @param path a canonicalized app-visible path.
     * @param appPaths list of app-visible paths that are allowed.
     * @param voldPaths list of vold-visible paths directly corresponding to the
     *            allowed app-visible paths argument.
     * @return a vold-visible path representing the original path, or
     *         {@code null} if the given path didn't have an app-to-vold
     *         mapping.
     */
    @VisibleForTesting
    public static String maybeTranslatePathForVold(
            String path, File[] appPaths, File[] voldPaths) {
        if (appPaths.length != voldPaths.length) {
            throw new IllegalStateException("Paths must be 1:1 mapping");
        }

        for (int i = 0; i < appPaths.length; i++) {
            final String appPath = appPaths[i].getAbsolutePath() + "/";
            if (path.startsWith(appPath)) {
                path = new File(voldPaths[i], path.substring(appPath.length()))
                        .getAbsolutePath();
                if (!path.endsWith("/")) {
                    path = path + "/";
                }
                return path;
            }
        }
        return null;
    }

    @Override
    public StorageVolume[] getVolumeList() {
        final int callingUserId = UserHandle.getCallingUserId();
        final boolean accessAll = (mContext.checkPermission(
                android.Manifest.permission.ACCESS_ALL_EXTERNAL_STORAGE,
                Binder.getCallingPid(), Binder.getCallingUid()) == PERMISSION_GRANTED);

        synchronized (mVolumesLock) {
            final ArrayList<StorageVolume> filtered = Lists.newArrayList();
            for (StorageVolume volume : mVolumes) {
                final UserHandle owner = volume.getOwner();
                final boolean ownerMatch = owner == null || owner.getIdentifier() == callingUserId;
                if (accessAll || ownerMatch) {
                    filtered.add(volume);
                }
            }

            return filtered.toArray(new StorageVolume[filtered.size()]);
        }
    }

    private void addObbStateLocked(ObbState obbState) throws RemoteException {
        final IBinder binder = obbState.getBinder();
        List<ObbState> obbStates = mObbMounts.get(binder);

        if (obbStates == null) {
            obbStates = new ArrayList<ObbState>();
            mObbMounts.put(binder, obbStates);
        } else {
            for (final ObbState o : obbStates) {
                if (o.rawPath.equals(obbState.rawPath)) {
                    throw new IllegalStateException("Attempt to add ObbState twice. "
                            + "This indicates an error in the MountService logic.");
                }
            }
        }

        obbStates.add(obbState);
        try {
            obbState.link();
        } catch (RemoteException e) {
            /*
             * The binder died before we could link it, so clean up our state
             * and return failure.
             */
            obbStates.remove(obbState);
            if (obbStates.isEmpty()) {
                mObbMounts.remove(binder);
            }

            // Rethrow the error so mountObb can get it
            throw e;
        }

        mObbPathToStateMap.put(obbState.rawPath, obbState);
    }

    private void removeObbStateLocked(ObbState obbState) {
        final IBinder binder = obbState.getBinder();
        final List<ObbState> obbStates = mObbMounts.get(binder);
        if (obbStates != null) {
            if (obbStates.remove(obbState)) {
                obbState.unlink();
            }
            if (obbStates.isEmpty()) {
                mObbMounts.remove(binder);
            }
        }

        mObbPathToStateMap.remove(obbState.rawPath);
    }

    private class ObbActionHandler extends Handler {
        private boolean mBound = false;
        private final List<ObbAction> mActions = new LinkedList<ObbAction>();

        ObbActionHandler(Looper l) {
            super(l);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case OBB_RUN_ACTION: {
                    final ObbAction action = (ObbAction) msg.obj;

                    if (DEBUG_OBB)
                        Slog.i(TAG, "OBB_RUN_ACTION: " + action.toString());

                    // If a bind was already initiated we don't really
                    // need to do anything. The pending install
                    // will be processed later on.
                    if (!mBound) {
                        // If this is the only one pending we might
                        // have to bind to the service again.
                        if (!connectToService()) {
                            Slog.e(TAG, "Failed to bind to media container service");
                            action.handleError();
                            return;
                        }
                    }

                    mActions.add(action);
                    break;
                }
                case OBB_MCS_BOUND: {
                    if (DEBUG_OBB)
                        Slog.i(TAG, "OBB_MCS_BOUND");
                    if (msg.obj != null) {
                        mContainerService = (IMediaContainerService) msg.obj;
                    }
                    if (mContainerService == null) {
                        // Something seriously wrong. Bail out
                        Slog.e(TAG, "Cannot bind to media container service");
                        for (ObbAction action : mActions) {
                            // Indicate service bind error
                            action.handleError();
                        }
                        mActions.clear();
                    } else if (mActions.size() > 0) {
                        final ObbAction action = mActions.get(0);
                        if (action != null) {
                            action.execute(this);
                        }
                    } else {
                        // Should never happen ideally.
                        Slog.w(TAG, "Empty queue");
                    }
                    break;
                }
                case OBB_MCS_RECONNECT: {
                    if (DEBUG_OBB)
                        Slog.i(TAG, "OBB_MCS_RECONNECT");
                    if (mActions.size() > 0) {
                        if (mBound) {
                            disconnectService();
                        }
                        if (!connectToService()) {
                            Slog.e(TAG, "Failed to bind to media container service");
                            for (ObbAction action : mActions) {
                                // Indicate service bind error
                                action.handleError();
                            }
                            mActions.clear();
                        }
                    }
                    break;
                }
                case OBB_MCS_UNBIND: {
                    if (DEBUG_OBB)
                        Slog.i(TAG, "OBB_MCS_UNBIND");

                    // Delete pending install
                    if (mActions.size() > 0) {
                        mActions.remove(0);
                    }
                    if (mActions.size() == 0) {
                        if (mBound) {
                            disconnectService();
                        }
                    } else {
                        // There are more pending requests in queue.
                        // Just post MCS_BOUND message to trigger processing
                        // of next pending install.
                        mObbActionHandler.sendEmptyMessage(OBB_MCS_BOUND);
                    }
                    break;
                }
                case OBB_FLUSH_MOUNT_STATE: {
                    final String path = (String) msg.obj;

                    if (DEBUG_OBB)
                        Slog.i(TAG, "Flushing all OBB state for path " + path);

                    synchronized (mObbMounts) {
                        final List<ObbState> obbStatesToRemove = new LinkedList<ObbState>();

                        final Iterator<ObbState> i = mObbPathToStateMap.values().iterator();
                        while (i.hasNext()) {
                            final ObbState state = i.next();

                            /*
                             * If this entry's source file is in the volume path
                             * that got unmounted, remove it because it's no
                             * longer valid.
                             */
                            if (state.canonicalPath.startsWith(path)) {
                                obbStatesToRemove.add(state);
                            }
                        }

                        for (final ObbState obbState : obbStatesToRemove) {
                            if (DEBUG_OBB)
                                Slog.i(TAG, "Removing state for " + obbState.rawPath);

                            removeObbStateLocked(obbState);

                            try {
                                obbState.token.onObbResult(obbState.rawPath, obbState.nonce,
                                        OnObbStateChangeListener.UNMOUNTED);
                            } catch (RemoteException e) {
                                Slog.i(TAG, "Couldn't send unmount notification for  OBB: "
                                        + obbState.rawPath);
                            }
                        }
                    }
                    break;
                }
            }
        }

        private boolean connectToService() {
            if (DEBUG_OBB)
                Slog.i(TAG, "Trying to bind to DefaultContainerService");

            Intent service = new Intent().setComponent(DEFAULT_CONTAINER_COMPONENT);
            if (mContext.bindService(service, mDefContainerConn, Context.BIND_AUTO_CREATE)) {
                mBound = true;
                return true;
            }
            return false;
        }

        private void disconnectService() {
            mContainerService = null;
            mBound = false;
            mContext.unbindService(mDefContainerConn);
        }
    }

    abstract class ObbAction {
        private static final int MAX_RETRIES = 3;
        private int mRetries;

        ObbState mObbState;

        ObbAction(ObbState obbState) {
            mObbState = obbState;
        }

        public void execute(ObbActionHandler handler) {
            try {
                if (DEBUG_OBB)
                    Slog.i(TAG, "Starting to execute action: " + toString());
                mRetries++;
                if (mRetries > MAX_RETRIES) {
                    Slog.w(TAG, "Failed to invoke remote methods on default container service. Giving up");
                    mObbActionHandler.sendEmptyMessage(OBB_MCS_UNBIND);
                    handleError();
                    return;
                } else {
                    handleExecute();
                    if (DEBUG_OBB)
                        Slog.i(TAG, "Posting install MCS_UNBIND");
                    mObbActionHandler.sendEmptyMessage(OBB_MCS_UNBIND);
                }
            } catch (RemoteException e) {
                if (DEBUG_OBB)
                    Slog.i(TAG, "Posting install MCS_RECONNECT");
                mObbActionHandler.sendEmptyMessage(OBB_MCS_RECONNECT);
            } catch (Exception e) {
                if (DEBUG_OBB)
                    Slog.d(TAG, "Error handling OBB action", e);
                handleError();
                mObbActionHandler.sendEmptyMessage(OBB_MCS_UNBIND);
            }
        }

        abstract void handleExecute() throws RemoteException, IOException;
        abstract void handleError();

        protected ObbInfo getObbInfo() throws IOException {
            ObbInfo obbInfo;
            try {
                obbInfo = mContainerService.getObbInfo(mObbState.ownerPath);
            } catch (RemoteException e) {
                Slog.d(TAG, "Couldn't call DefaultContainerService to fetch OBB info for "
                        + mObbState.ownerPath);
                obbInfo = null;
            }
            if (obbInfo == null) {
                throw new IOException("Couldn't read OBB file: " + mObbState.ownerPath);
            }
            return obbInfo;
        }

        protected void sendNewStatusOrIgnore(int status) {
            if (mObbState == null || mObbState.token == null) {
                return;
            }

            try {
                mObbState.token.onObbResult(mObbState.rawPath, mObbState.nonce, status);
            } catch (RemoteException e) {
                Slog.w(TAG, "MountServiceListener went away while calling onObbStateChanged");
            }
        }
    }

    class MountObbAction extends ObbAction {
        private final String mKey;
        private final int mCallingUid;

        MountObbAction(ObbState obbState, String key, int callingUid) {
            super(obbState);
            mKey = key;
            mCallingUid = callingUid;
        }

        @Override
        public void handleExecute() throws IOException, RemoteException {
            waitForReady();
            warnOnNotMounted();

            final ObbInfo obbInfo = getObbInfo();

            if (!isUidOwnerOfPackageOrSystem(obbInfo.packageName, mCallingUid)) {
                Slog.w(TAG, "Denied attempt to mount OBB " + obbInfo.filename
                        + " which is owned by " + obbInfo.packageName);
                sendNewStatusOrIgnore(OnObbStateChangeListener.ERROR_PERMISSION_DENIED);
                return;
            }

            final boolean isMounted;
            synchronized (mObbMounts) {
                isMounted = mObbPathToStateMap.containsKey(mObbState.rawPath);
            }
            if (isMounted) {
                Slog.w(TAG, "Attempt to mount OBB which is already mounted: " + obbInfo.filename);
                sendNewStatusOrIgnore(OnObbStateChangeListener.ERROR_ALREADY_MOUNTED);
                return;
            }

            final String hashedKey;
            if (mKey == null) {
                hashedKey = "none";
            } else {
                try {
                    SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");

                    KeySpec ks = new PBEKeySpec(mKey.toCharArray(), obbInfo.salt,
                            PBKDF2_HASH_ROUNDS, CRYPTO_ALGORITHM_KEY_SIZE);
                    SecretKey key = factory.generateSecret(ks);
                    BigInteger bi = new BigInteger(key.getEncoded());
                    hashedKey = bi.toString(16);
                } catch (NoSuchAlgorithmException e) {
                    Slog.e(TAG, "Could not load PBKDF2 algorithm", e);
                    sendNewStatusOrIgnore(OnObbStateChangeListener.ERROR_INTERNAL);
                    return;
                } catch (InvalidKeySpecException e) {
                    Slog.e(TAG, "Invalid key spec when loading PBKDF2 algorithm", e);
                    sendNewStatusOrIgnore(OnObbStateChangeListener.ERROR_INTERNAL);
                    return;
                }
            }

            int rc = StorageResultCode.OperationSucceeded;
            try {
                mConnector.execute("obb", "mount", mObbState.voldPath, new SensitiveArg(hashedKey),
                        mObbState.ownerGid);
            } catch (NativeDaemonConnectorException e) {
                int code = e.getCode();
                if (code != VoldResponseCode.OpFailedStorageBusy) {
                    rc = StorageResultCode.OperationFailedInternalError;
                }
            }

            if (rc == StorageResultCode.OperationSucceeded) {
                if (DEBUG_OBB)
                    Slog.d(TAG, "Successfully mounted OBB " + mObbState.voldPath);

                synchronized (mObbMounts) {
                    addObbStateLocked(mObbState);
                }

                sendNewStatusOrIgnore(OnObbStateChangeListener.MOUNTED);
            } else {
                Slog.e(TAG, "Couldn't mount OBB file: " + rc);

                sendNewStatusOrIgnore(OnObbStateChangeListener.ERROR_COULD_NOT_MOUNT);
            }
        }

        @Override
        public void handleError() {
            sendNewStatusOrIgnore(OnObbStateChangeListener.ERROR_INTERNAL);
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("MountObbAction{");
            sb.append(mObbState);
            sb.append('}');
            return sb.toString();
        }
    }

    class UnmountObbAction extends ObbAction {
        private final boolean mForceUnmount;

        UnmountObbAction(ObbState obbState, boolean force) {
            super(obbState);
            mForceUnmount = force;
        }

        @Override
        public void handleExecute() throws IOException {
            waitForReady();
            warnOnNotMounted();

            final ObbInfo obbInfo = getObbInfo();

            final ObbState existingState;
            synchronized (mObbMounts) {
                existingState = mObbPathToStateMap.get(mObbState.rawPath);
            }

            if (existingState == null) {
                sendNewStatusOrIgnore(OnObbStateChangeListener.ERROR_NOT_MOUNTED);
                return;
            }

            if (existingState.ownerGid != mObbState.ownerGid) {
                Slog.w(TAG, "Permission denied attempting to unmount OBB " + existingState.rawPath
                        + " (owned by GID " + existingState.ownerGid + ")");
                sendNewStatusOrIgnore(OnObbStateChangeListener.ERROR_PERMISSION_DENIED);
                return;
            }

            int rc = StorageResultCode.OperationSucceeded;
            try {
                final Command cmd = new Command("obb", "unmount", mObbState.voldPath);
                if (mForceUnmount) {
                    cmd.appendArg("force");
                }
                mConnector.execute(cmd);
            } catch (NativeDaemonConnectorException e) {
                int code = e.getCode();
                if (code == VoldResponseCode.OpFailedStorageBusy) {
                    rc = StorageResultCode.OperationFailedStorageBusy;
                } else if (code == VoldResponseCode.OpFailedStorageNotFound) {
                    // If it's not mounted then we've already won.
                    rc = StorageResultCode.OperationSucceeded;
                } else {
                    rc = StorageResultCode.OperationFailedInternalError;
                }
            }

            if (rc == StorageResultCode.OperationSucceeded) {
                synchronized (mObbMounts) {
                    removeObbStateLocked(existingState);
                }

                sendNewStatusOrIgnore(OnObbStateChangeListener.UNMOUNTED);
            } else {
                Slog.w(TAG, "Could not unmount OBB: " + existingState);
                sendNewStatusOrIgnore(OnObbStateChangeListener.ERROR_COULD_NOT_UNMOUNT);
            }
        }

        @Override
        public void handleError() {
            sendNewStatusOrIgnore(OnObbStateChangeListener.ERROR_INTERNAL);
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("UnmountObbAction{");
            sb.append(mObbState);
            sb.append(",force=");
            sb.append(mForceUnmount);
            sb.append('}');
            return sb.toString();
        }
    }

    @VisibleForTesting
    public static String buildObbPath(final String canonicalPath, int userId, boolean forVold) {
        // TODO: allow caller to provide Environment for full testing
        // TODO: extend to support OBB mounts on secondary external storage

        // Only adjust paths when storage is emulated
        if (!Environment.isExternalStorageEmulated() || (SystemProperties.get(PROP_SHARED_SDCARD).equals("1") && SystemProperties.get(PROP_2SDCARD_SWAP).equals("1"))) {
            return canonicalPath;
        }

        String path = canonicalPath.toString();

        // First trim off any external storage prefix
        final UserEnvironment userEnv = new UserEnvironment(userId);

        // /storage/emulated/0
        final String externalPath = userEnv.getExternalStorageDirectory().getAbsolutePath();
        // /storage/emulated_legacy
        final String legacyExternalPath = Environment.getLegacyExternalStorageDirectory()
                .getAbsolutePath();

        if (path.startsWith(externalPath)) {
            path = path.substring(externalPath.length() + 1);
        } else if (path.startsWith(legacyExternalPath)) {
            path = path.substring(legacyExternalPath.length() + 1);
        } else {
            return canonicalPath;
        }

        // Handle special OBB paths on emulated storage
        final String obbPath = "Android/obb";
        if (path.startsWith(obbPath)) {
            path = path.substring(obbPath.length() + 1);

            if (forVold) {
                return new File(Environment.getEmulatedStorageObbSource(), path).getAbsolutePath();
            } else {
                final UserEnvironment ownerEnv = new UserEnvironment(UserHandle.USER_OWNER);
                return new File(ownerEnv.buildExternalStorageAndroidObbDirs()[0], path)
                        .getAbsolutePath();
            }
        }

        // Handle normal external storage paths
        if (forVold) {
            return new File(Environment.getEmulatedStorageSource(userId), path).getAbsolutePath();
        } else {
            return new File(userEnv.getExternalDirsForApp()[0], path).getAbsolutePath();
        }
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.DUMP, TAG);

        final IndentingPrintWriter pw = new IndentingPrintWriter(writer, "  ", 160);

        synchronized (mObbMounts) {
            pw.println("mObbMounts:");
            pw.increaseIndent();
            final Iterator<Entry<IBinder, List<ObbState>>> binders = mObbMounts.entrySet()
                    .iterator();
            while (binders.hasNext()) {
                Entry<IBinder, List<ObbState>> e = binders.next();
                pw.println(e.getKey() + ":");
                pw.increaseIndent();
                final List<ObbState> obbStates = e.getValue();
                for (final ObbState obbState : obbStates) {
                    pw.println(obbState);
                }
                pw.decreaseIndent();
            }
            pw.decreaseIndent();

            pw.println();
            pw.println("mObbPathToStateMap:");
            pw.increaseIndent();
            final Iterator<Entry<String, ObbState>> maps = mObbPathToStateMap.entrySet().iterator();
            while (maps.hasNext()) {
                final Entry<String, ObbState> e = maps.next();
                pw.print(e.getKey());
                pw.print(" -> ");
                pw.println(e.getValue());
            }
            pw.decreaseIndent();
        }

        synchronized (mVolumesLock) {
            pw.println();
            pw.println("mVolumes:");
            pw.increaseIndent();
            for (StorageVolume volume : mVolumes) {
                pw.println(volume);
                pw.increaseIndent();
                pw.println("Current state: " + mVolumeStates.get(volume.getPath()));
                pw.decreaseIndent();
            }
            pw.decreaseIndent();
        }

        pw.println();
        pw.println("mConnection:");
        pw.increaseIndent();
        mConnector.dump(fd, pw, args);
        pw.decreaseIndent();

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

        pw.println();
        pw.print("Last maintenance: ");
        pw.println(sdf.format(new Date(mLastMaintenance)));
    }

    /** {@inheritDoc} */
    public void monitor() {
        if (mConnector != null) {
            mConnector.monitor();
        }
    }

    /// M: New private APIs for new feature or bug fix @{
    private void updateDefaultpath() {
        String defaultPath = getDefaultPath();
        String path = defaultPath;
        boolean needChange = false;
        if (!Environment.MEDIA_MOUNTED.equals(getVolumeState(defaultPath))) {
            synchronized (mVolumes) {
                int length = mVolumes.size();
                for (int i = 0; i < length; i++) {
                    path = mVolumes.get(i).getPath();
                    if (Environment.MEDIA_MOUNTED.equals(getVolumeState(path))) {
                        needChange = true;
                        break;
                    }
                }
            }
            if (needChange && (!path.equals(defaultPath))) {
                Slog.i(TAG, "setDefaultPath: " + path);
                setDefaultPath(path);
            }
        }
    }
    /**
     * OMA DM
     */
    private void enableUSBFuction(boolean enable) {
        waitForReady();
        try {
            mConnector.execute("USB", enable ? "enable" : "disable");
        } catch (NativeDaemonConnectorException e) {
            Slog.e(TAG, "enableUSBFunction failed, ", e);
        }
    }

    /**
     * BICR
     */
    private void doShareUnshareCDRom(boolean share) {
        Slog.d(TAG, "doShareUnshareCDRom" + share);
        try {
            mConnector.execute("cd-rom", share ? "share" : "unshare");
        } catch (NativeDaemonConnectorException e) {
            Slog.e(TAG, "Failed to share/unshare CD Rom", e);
        }
    }

    /**
     * BICR
     */
    private int doGetCDRomState() {
        NativeDaemonEvent event;
        try {
            event = mConnector.execute("cd-rom", "status");
        } catch (NativeDaemonConnectorException ex) {
            Slog.e(TAG, "Failed to get CD rom ststus!");
            return CDRomState.Unknown;
        }

        String rawEvent = event.getRawEvent();
        String[] tok = rawEvent.split(" ");
        if (tok.length < 2) {
            Slog.e(TAG, "Malformed response get CD rom ststus");
            return CDRomState.Unknown;
        }

        int code;
        try {
            code = Integer.parseInt(tok[0]);
        } catch (NumberFormatException nfe) {
            Slog.e(TAG, String.format("Error parsing code %s", tok[0]));
            return CDRomState.Unknown;
        }
        if (code == VoldResponseCode.CdromStatusResult) {
            if ("Shared".equals(tok[1])) {
                return CDRomState.Shared;
            } else if ("Unshared".equals(tok[1])) {
                return CDRomState.Unshared;
            } else if ("Sharing".equals(tok[1])) {
                return CDRomState.Sharing;
            } else if ("Unsharing".equals(tok[1])) {
                return CDRomState.Unsharing;
            } else if ("Not_Exist".equals(tok[1])) {
                return CDRomState.Not_Exist;
            }
        } else {
            Slog.e(TAG, String.format("Unexpected response code %d", code));
            return CDRomState.Unknown;
        }
        Slog.e(TAG, "Got an empty response");
        return CDRomState.Unknown;
    }

    /**
     * Shared SD card
     * update the Storage Volume info for Shared SD
     * this should be done after
     */
    private void doShareSDVolumeUpdate() {
        /*if (SystemProperties.get(PROP_SHARED_SDCARD).equals("1")) {
            mPrimaryVolume = new StorageVolume(
                mPrimaryVolume.getPath(),
                R.string.storage_internal,//descriptionId
                //"internal",
                mPrimaryVolume.isRemovable(),
                true,//emulated
                MTP_RESERVE_SPACE,
                mPrimaryVolume.allowMassStorage(),
                mPrimaryVolume.getMaxFileSize());

            synchronized (mVolumes) {
                int length = mVolumes.size();
                if (length > 0) {
                    mVolumes.set(0, mPrimaryVolume);
                }
            }
            synchronized (mVolumeMap) {
                mVolumeMap.put(mPrimaryVolume.getPath(), mPrimaryVolume);
            }
            // set the storage id
            synchronized (mVolumes) {
                int length = mVolumes.size();
                for (int i = 0; i < length; i++) {
                    mVolumes.get(i).setStorageId(i);
                }
            }
        }*/
    }

    /**
     * Shared SD card
     * return the volume path that can be share to pc.
     */
    private String getUMSPath() {
        if (SystemProperties.get(PROP_SHARED_SDCARD).equals("1") && !SystemProperties.get(PROP_2SDCARD_SWAP).equals("1")) {
            return EXTERNAL_SD2;
        } else {
            return EXTERNAL_SD1;
        }
    }

    /**
     * SD swap
     */
    private void doSDSwapVolumeUpdate() {
        Slog.i(TAG, "doSDSwapVolumeUpdate");
        if (!SystemProperties.get(PROP_2SDCARD_SWAP).equals("1")) {
            Slog.i(TAG, "MTK_2SDCARD_SWAP not enable!");
            return;
        }
        //for sd swap featuer
        //if SD exist, need to change info of primary volume, such as description, removable

        // NOT just "swap" volumes in the volume list
        // this can keep mVolumes always be updated
        if (mVolumePrimary == null || mVolumeSecondary == null) {
            Slog.e(TAG, "doSDSwapVolumeUpdate: mVolumePrimary == null || mVolumeSecondary == null, return!");
            return;
        }


        boolean  sdSwapStatus = getSwapState();
        //if(sdSwapStatus != mSwapStateForSDSwapMountPoint) {
        if ((mFirstTime_SwapStateForSDSwapMountPoint) || (sdSwapStatus != mSwapStateForSDSwapMountPoint)) {
            mSwapStateForSDSwapMountPoint = sdSwapStatus;
            mFirstTime_SwapStateForSDSwapMountPoint = false ;
        }
        else {
            Slog.i(TAG, "doSDSwapVolumeUpdate: already update the mount point path, Just skip this. status:" + sdSwapStatus);
            return;
        }

        StorageVolume tVolume;
        StorageVolume pVolume;
        StorageVolume tempVolume;
        if (sdSwapStatus) {
            //SD swap
            Slog.i(TAG, "doSDSwapVolumeUpdate: SD swap!");
            synchronized (mVolumesLock) {
                pVolume = new StorageVolume(
                    mVolumePrimary.getPathFile(),
                    mVolumeSecondary.getDescriptionId(), //volume.getDescription(mContext),
                    true, //primary
                    mVolumeSecondary.isRemovable(),
                    mVolumeSecondary.isEmulated(),
                    mVolumeSecondary.getMtpReserveSpace(),
                    mVolumeSecondary.allowMassStorage(),
                    mVolumeSecondary.getMaxFileSize(),
                    mVolumeSecondary.getOwner());
                // For KK.MR1 add item in StorageVolume, need set here
                tempVolume = mVolumesByPath.get(mVolumePrimary.getPath());
                if (tempVolume != null) {
                    pVolume.setUuid(tempVolume.getUuid());
                    pVolume.setUserLabel(tempVolume.getUserLabel());
                    pVolume.setState(tempVolume.getState());
                }

                tVolume = new StorageVolume(
                    mVolumeSecondary.getPathFile(),
                    mVolumePrimary.getDescriptionId(), //mPrimaryVolume.getDescription(mContext),
                    false, //primary
                    mVolumePrimary.isRemovable(),
                    mVolumePrimary.isEmulated(),
                    mVolumePrimary.getMtpReserveSpace(),
                    mVolumePrimary.allowMassStorage(),
                    mVolumePrimary.getMaxFileSize(),
                    mVolumePrimary.getOwner());
                // For KK.MR1 add item in StorageVolume, need set here
                tempVolume = mVolumesByPath.get(mVolumeSecondary.getPath());
                if (tempVolume != null) {
                    tVolume.setUuid(tempVolume.getUuid());
                    tVolume.setUserLabel(tempVolume.getUserLabel());
                    tVolume.setState(tempVolume.getState());
                }

                mVolumesByPath.put(EXTERNAL_SD1, pVolume);
                mVolumesByPath.put(EXTERNAL_SD2, tVolume);

                int length = mVolumes.size();
                if (length > 0) {
                    mVolumes.set(0, pVolume);
                    for (int i = 0; i < length; i++) {
                        if (mVolumes.get(i).getPath().equals(EXTERNAL_SD2)) {
                            mVolumes.set(i, tVolume);
                            break;
                        }
                    }
                }
            }
        } else {
            //SD NOT swap
            Slog.i(TAG, "doSDSwapVolumeUpdate: SD NOT swap!");
            synchronized (mVolumesLock) {
                // For KK.MR1 add item in StorageVolume, need set here
                tempVolume = mVolumesByPath.get(mVolumePrimary.getPath());
                if (tempVolume != null) {
                    mVolumePrimary.setUuid(tempVolume.getUuid());
                    mVolumePrimary.setUserLabel(tempVolume.getUserLabel());
                    mVolumePrimary.setState(tempVolume.getState());
                }
                tempVolume = mVolumesByPath.get(mVolumeSecondary.getPath());
                if (tempVolume != null) {
                    mVolumeSecondary.setUuid(tempVolume.getUuid());
                    mVolumeSecondary.setUserLabel(tempVolume.getUserLabel());
                    mVolumeSecondary.setState(tempVolume.getState());
                }
                mVolumesByPath.put(EXTERNAL_SD1, mVolumePrimary);
                mVolumesByPath.put(EXTERNAL_SD2, mVolumeSecondary);

                int length = mVolumes.size();
                if (length > 0) {
                    mVolumes.set(0, mVolumePrimary);
                    for (int i = 0; i < length; i++) {
                        if (mVolumes.get(i).getPath().equals(EXTERNAL_SD2)) {
                            mVolumes.set(i, mVolumeSecondary);
                            break;
                        }
                    }
                }
            }
        }
        //set the storage id
        synchronized (mVolumesLock) {
            int length = mVolumes.size();
            StorageVolume volume = null;
            for (int i = 0; i < length; i++) {
                volume = mVolumes.get(i);
                volume.setStorageId(i);
                Slog.d(TAG, "doSDSwapVolumeUpdate " +
                            " path: " + volume.getPath() +
                            " description: " + volume.getDescription(mContext) +
                            " removable: " + volume.isRemovable() +
                            " emulated: " + volume.isEmulated() +
                            " mtpReserve: " + volume.getMtpReserveSpace() +
                            " allowMassStorage: " + volume.allowMassStorage() +
                            " maxFileSize: " + volume.getMaxFileSize());
            }
        }
    }

    /**
     * SD swap
     */
    private void sendSDSwapIntent() {
        boolean  sdSwapStatus = getSwapState();
        if (mFirstTimeSDSwapIntent || sdSwapStatus != mSwapStateForSDSwapIntent) {
            mSwapStateForSDSwapIntent = sdSwapStatus;
        }
        else {
            Slog.i(TAG, "sendSDSwapIntent: already sent INTENT_SD_SWAP, Just skip this. status:" + sdSwapStatus);
            return;
        }

        Intent intent = new Intent(INTENT_SD_SWAP);
        intent.putExtra(SD_EXIST, sdSwapStatus);
        Slog.d(TAG, "sendSDSwapIntent " + intent);
        mContext.sendBroadcast(intent);
        mFirstTimeSDSwapIntent = false;
    }

    /**
     * SD swap
     */
    private boolean isExternalStorage(String path) {
        StorageVolume volume;
        synchronized (mVolumesLock) {
            volume = mVolumesByPath.get(path);
        }
        if (null == volume) {
            Slog.e(TAG, "isExternalStorage error, invalid path!");
            return false;
        }
        return volume.isRemovable();
    }
    /// @}

    /// M: New public APIs for new feature or bug fix @{
    /**
     * BICR
     */
    public void shareCDRom(boolean share) {
        Slog.i(TAG, "shareCDRom " + share);
        final boolean doShare = share;
        new Thread() {
            public void run() {
                if ("yes".equals(SystemProperties.get("sys.usb.mtk_bicr_support"))
                    || "yes_hide".equals(SystemProperties.get("sys.usb.mtk_bicr_support"))) {
                    waitForReady();
                    int state = doGetCDRomState();
                    Slog.i(TAG, "CDRom status=" + state);
                    if ((state == CDRomState.Shared && doShare == false) ||
                        (state == CDRomState.Unshared && doShare == true)) {
                        doShareUnshareCDRom(doShare);
                    }
                } else {
                    Slog.i(TAG, "CD rom feature not enable!");
                }
            }
        } .start();
    }

    /**
     * SD swap
     */
    public void unmountVolumeNotSwap(String path, boolean force, boolean removeEncryption) {
        validatePermission(android.Manifest.permission.MOUNT_UNMOUNT_FILESYSTEMS);
        waitForReady();

        String volState = getVolumeState(path);
        if (DEBUG_UNMOUNT) {
            Slog.i(TAG, "Unmounting " + path
                    + " force = " + force
                    + " removeEncryption = " + removeEncryption);
        }

        if (Environment.MEDIA_UNMOUNTED.equals(volState) ||
                Environment.MEDIA_REMOVED.equals(volState) ||
                Environment.MEDIA_SHARED.equals(volState) ||
                Environment.MEDIA_UNMOUNTABLE.equals(volState)) {
            // Media already unmounted or cannot be unmounted.
            // TODO return valid return code when adding observer call back.
            return;
        }

        // set prop unmounting
        try {
            Slog.i(TAG, "set prop sys.sd.unmounting = " + path);
            SystemProperties.set(PROP_UNMOUNTING, path);
        } catch (IllegalArgumentException e) {
            Slog.e(TAG, "IllegalArgumentException when set sys.sd.unmounting:", e);
        }

        UnmountCallBack ucb = new UnmountCallBack(path, force, removeEncryption);
        mHandler.sendMessage(mHandler.obtainMessage(H_UNMOUNT_PM_UPDATE, ucb));
    }

    /**
     * SD swap
     */
    public int mountVolumeNotSwap(String path) {
        validatePermission(android.Manifest.permission.MOUNT_UNMOUNT_FILESYSTEMS);

        waitForReady();
        return doMountVolume(path);
    }

    /**
     * SD swap
     * if sd swap happen, the real sd/EMMC may shift to other path
     * to keep the real default path same, need to change default path here
     */
    private void updateDefaultPathForSwap(boolean mount, String path) {
        if (!SystemProperties.get(PROP_2SDCARD_SWAP).equals("1")) {
            return;
        }

        if (mount && EXTERNAL_SD2.equals(path)) {

            //Only one case if swap enable when mount
            //before mount:  sd1=phone storage; default=phone storage
            //after mount:     sd1=sd card; sd2=phone storage; default=phone storage
            //so we change default path to sd2 to make sure that phone storage is also default

            setDefaultPath(EXTERNAL_SD2);
        } else if (!mount) {
            //Two case if swap enable when unmount
            String defaultPath = getDefaultPath();
            if (EXTERNAL_SD1.equals(defaultPath)) {
                //case1
                //before unmount:  sd1=sd card; sd2=phone storage; default=sd card
                //after unmount:     sd1=phone storage; default=phone storage
                //default path change from sd card to phone storage, but path not change
                //So we do nothing here

            } else if (EXTERNAL_SD2.equals(defaultPath)) {
                //case2
                //before unmount:  sd1=sd card; sd2=phone storage; default=phone storage
                //after unmount:     sd1=phone storage; default=phone storage
                //default path change from sd2 to sd1

                setDefaultPath(EXTERNAL_SD1);
            }
        }
    }

    private String getDefaultPath() {
        StorageManagerEx sm = new StorageManagerEx();
        return sm.getDefaultPath();
    }

    /**
     * set default path for APP to storage data.
     */
    public void setDefaultPath(String path) {
        Slog.i(TAG, "setDefaultPath path=" + path);
        if (path == null) {
            Slog.e(TAG, "setDefaultPath error! path=null");
            return;
        }
        try {
            SystemProperties.set("persist.sys.sd.defaultpath", path);
			//chenwenshuai add for the default path flag change with the path begin
			if (path.equals("/storage/sdcard1")){
                SystemProperties.set("persist.sys.primarysd", "1");
            } else {
                SystemProperties.set("persist.sys.primarysd", "0");
            }
			//chenwenshuai add end
        } catch (IllegalArgumentException e) {
            Slog.e(TAG, "IllegalArgumentException when set default path:", e);
        }
    }

    private boolean getSwapState() {
        StorageManagerEx sm = new StorageManagerEx();
        return sm.getSdSwapState();
    }

    /**
     * check all storage state
     * if there is more than one storage in MOUNTED state
     * we should show the dialog after insert and mount succeed
     */
    private boolean enableDefaultPathDialog() {
        int mountCount = 0;
        if(SystemProperties.get(PROP_VOLD_DECRYPT).equals("trigger_restart_min_framework"))
            return false;
        
        synchronized (mVolumesLock) {
            Iterator iter = mVolumeStates.entrySet().iterator();
            while (iter.hasNext()) {
                Map.Entry entry = (Map.Entry) iter.next();
                String state = (String) entry.getValue();
                Slog.i(TAG, "enableDefaultPathDialog state=" + state);
                if (Environment.MEDIA_MOUNTED.equals(state)) {
                    mountCount++;
                }
            }
        }
        return (mountCount > 1);
    }

    /**
     * check all storage, if there is one internal storage
     * we should show the toast when plug out sd card
     */
    private boolean enableDefaultToast() {
        boolean internal = false;
        synchronized (mVolumesLock) {
            for (StorageVolume volume : mVolumes) {
                if (!volume.isRemovable()) {
                    internal = true;
                    break;
                }
            }
        }
        return internal;
    }
    /// @}

    /// M: multiple user @{
    private void handleUserSwitch() {
        /// M: sdcard&otg only for owner @{
        if (SystemProperties.get(PROP_OWNER_SDCARD_SUPPORT).equals("1")) {
            if (mOldUserId != UserHandle.USER_OWNER && mUserId != UserHandle.USER_OWNER) {
                Slog.d(TAG, "Switch among normal users,do nothing!");
                return;
            }
            Slog.d(TAG, "MTK_OWNER_SDCARD_ONLY_SUPPORT now handleUserSwitch.");
            if (mUserId != UserHandle.USER_OWNER) {
                synchronized (mVolumesLock) {
                    for (StorageVolume volume : mVolumes) {
                        Slog.d(TAG, "MTK_OWNER_SDCARD_ONLY_SUPPORT now unmountVolume: " + volume.getPath() + ": " + getVolumeState(volume.getPath()));
                        if ((Environment.MEDIA_MOUNTED.equals(getVolumeState(volume.getPath())) ||
                            Environment.MEDIA_NOFS.equals(getVolumeState(volume.getPath())) ||
                            Environment.MEDIA_UNMOUNTABLE.equals(getVolumeState(volume.getPath()))) &&
                            (volume.getPath().equals(EXTERNAL_SD2) ||
                            volume.getPath().startsWith(EXTERNAL_OTG))) {

                            Slog.d(TAG, "MTK_OWNER_SDCARD_ONLY_SUPPORT now unmountVolume");
                            unmountVolumeForUserSwitch(volume.getPath(), true, false);
                        }
                    }
                }

           } else {
               if (mOldUserId != -1) {
                    synchronized (mVolumesLock) {
                        for (StorageVolume volume : mVolumes) {
                            Slog.d(TAG, "MTK_OWNER_SDCARD_ONLY_SUPPORT now mountVolume: " + volume.getPath() + ": " + getVolumeState(volume.getPath()));
                            if (!Environment.MEDIA_MOUNTED.equals(getVolumeState(volume.getPath())) &&
                                !Environment.MEDIA_REMOVED.equals(getVolumeState(volume.getPath())) &&
                                (volume.getPath().equals(EXTERNAL_SD2) ||
                                volume.getPath().startsWith(EXTERNAL_OTG))) {

                                Slog.d(TAG, "MTK_OWNER_SDCARD_ONLY_SUPPORT now mountVolume"); 
                                mountVolume(volume.getPath());
                            }
                        }
                    }
               }
           }
        }
      /// @}
    }
    /// @}   

   //added for factory kit. 2013.08.13.yjf.
    private int getFactoryKitMode()
    {
        //read data from /sys/devices/platform/hq_boot_status/boot_status file, if data is 1, should start factory kit.
        //otherwise, should start home.
        int result = 0;

        File file = new File("/sys/devices/platform/hq_boot_status/boot_status");//liugang modify for HQ00974350
        if(file.exists() == false)
        {
            return 0;
        }
        
        FileInputStream in = null;
        try {
            in = new FileInputStream(file);
            result = in.read();

            in.close();
        } catch (IOException e) {
            Slog.w(TAG, "getFactoryKitMode:Failure reading /sys/devices/platform/hq_boot_status/boot_status:", e);
        }
        
        Slog.w(TAG, "getFactoryKitMode:result:" + result);

        return result;
    }
    //added ends here.
	
	/* HQ_ChenWenshuai 2015-09-14 modified for low speed sdcard notify begin */
	private void sendLowSpeedSDCardNotification(){
		NotificationManager mNotificationMgr =
               (NotificationManager)mContext.getSystemService(
                       Context.NOTIFICATION_SERVICE);
		CharSequence title = mContext.getText(com.hq.resource.internal.R.string.hq_low_speed_sdcard_title); 
        CharSequence details = mContext.getText(com.hq.resource.internal.R.string.hq_low_speed_sdcard_text); 
        Notification notification = new Notification.Builder(mContext)
               .setSmallIcon(com.hq.resource.internal.R.drawable.stat_sys_warning)
               .setTicker(title)
               .setColor(mContext.getResources().getColor(
                   com.android.internal.R.color.system_notification_accent_color))
               .setContentTitle(title)
               .setContentText(details)
               .setStyle(new Notification.BigTextStyle()
                     .bigText(details))
               .setVisibility(Notification.VISIBILITY_PUBLIC)
               .setCategory(Notification.CATEGORY_SYSTEM)
               .build();
		Bitmap bigIcon = BitmapFactory.decodeResource(mContext.getResources(), com.hq.resource.internal.R.drawable.stat_sys_warning);
		notification.largeIcon = bigIcon;
		mNotificationMgr.notify(LOW_SPEED_SDCARD_NOTIFICATION_ID, notification);
	}

	private void cancelLowSpeedSDCardNotification() {
	NotificationManager mNotificationMgr =
                (NotificationManager)mContext.getSystemService(
                        Context.NOTIFICATION_SERVICE);
        mNotificationMgr.cancel(LOW_SPEED_SDCARD_NOTIFICATION_ID);	
	}
	/*HQ_ChenWenshuai 2015-09-14 modified end */
}

