package com.andhradroid.android.uploadservice.service;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.util.Log;

import com.andhradroid.android.uploadservice.R;
import com.andhradroid.android.uploadservice.app.MyApp;
import com.andhradroid.android.uploadservice.demo.MainActivity;
import com.andhradroid.android.uploadservice.events.UploadStateChangedEvent;
import com.andhradroid.android.uploadservice.events.UploadingPausedStateChangedEvent;
import com.andhradroid.android.uploadservice.model.PhotoUpload;
import com.andhradroid.android.uploadservice.model.PhotoUploadController;

import de.greenrobot.event.EventBus;

public class UploadService extends Service {
    //
    private boolean mCurrentlyUploading;
    private ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private PhotoUploadController mController;
    private int mNumberUploaded = 0;
    private Future<?> mCurrentUploadRunnable;
    private EventBus bus = EventBus.getDefault();
    private android.support.v4.app.NotificationCompat.Builder mNotificationBuilder;
    private NotificationManager mNotificationMgr;

    private class UpdateRunnable extends PhotupThreadRunnable {

        private final PhotoUpload mSelection;

        public UpdateRunnable(PhotoUpload selection) {
            mSelection = selection;
        }

        public void runImpl() {
            try {
                if (mSelection.getUploadState() == PhotoUpload.STATE_UPLOAD_WAITING) {
                    mSelection.setUploadState(PhotoUpload.STATE_UPLOAD_IN_PROGRESS);
                    Thread.sleep(3000);
                    Log.e("UploadService", "Finished " + mSelection.getName());
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            if (!isConnected(UploadService.this)) {
                mSelection.setUploadState(PhotoUpload.STATE_UPLOAD_WAITING);
            } else {
                mSelection.setUploadState(PhotoUpload.STATE_UPLOAD_COMPLETED);
            }
            bus.post(new UploadingPausedStateChangedEvent());
        }

    }

    @Override
    public void onCreate() {
        super.onCreate();
        bus.register(this);
        mController = ((MyApp) getApplication()).getPhotoUploadController();
        mNotificationMgr = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        bus.unregister(this);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (null == intent || "INTENT_SERVICE_UPLOAD_ALL".equals(intent.getAction())) {
            if (uploadAll()) {
                return START_STICKY;
            }
        }
        return START_NOT_STICKY;
    }

    public void onEventMainThread(UploadStateChangedEvent event) {
        PhotoUpload upload = event.getUpload();

        switch (upload.getUploadState()) {
            case PhotoUpload.STATE_UPLOAD_IN_PROGRESS:
                updateNotification(upload);
                break;

            case PhotoUpload.STATE_UPLOAD_COMPLETED:
                mNumberUploaded++;
                // Fall through...

            case PhotoUpload.STATE_UPLOAD_ERROR:
                startNextUploadOrFinish();
                // Fall through...

            case PhotoUpload.STATE_UPLOAD_WAITING:
                break;
        }
    }

    void startNextUploadOrFinish() {
        PhotoUpload nextUpload = mController.getNextUpload();
        if (null != nextUpload && canUpload()) {
            startUpload(nextUpload);
        } else {
            mCurrentlyUploading = false;
            stopSelf();
        }
    }

    private boolean canUpload() {
        return !isUploadingPaused(this) && isConnected(this);
        // return true;
    }

    private void startUpload(PhotoUpload upload) {
        // updateNotification(upload);
        mCurrentUploadRunnable = mExecutor.submit(new UpdateRunnable(upload));
        mCurrentlyUploading = true;
    }

    private boolean uploadAll() {
        // If we're currently uploading, ignore call
        if (mCurrentlyUploading) {
            return true;
        }

        if (canUpload()) {
            PhotoUpload nextUpload = mController.getNextUpload();
            if (null != nextUpload) {
                startForeground();
                startUpload(nextUpload);
                return true;
            }
        }

        // If we reach here, there's no need to keep us running
        mCurrentlyUploading = false;
        stopSelf();

        return false;
    }

    void stopUploading() {
        if (null != mCurrentUploadRunnable) {
            mCurrentUploadRunnable.cancel(true);
        }
        mCurrentlyUploading = false;
        stopSelf();
    }

    public void onEvent(UploadingPausedStateChangedEvent event) {
        if (isUploadingPaused(this)) {
            stopUploading();
        } else {
            startNextUploadOrFinish();
        }
    }

    private boolean isUploadingPaused(UploadService uploadService) {
        // TODO:
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        return prefs.getBoolean("isPaused", false);
    }

    private void startForeground() {
        if (null == mNotificationBuilder) {
            mNotificationBuilder = new android.support.v4.app.NotificationCompat.Builder(this);
            mNotificationBuilder.setSmallIcon(R.drawable.ic_launcher);
            mNotificationBuilder.setContentTitle(getString(R.string.app_name));
            mNotificationBuilder.setOngoing(true);
            mNotificationBuilder.setWhen(System.currentTimeMillis());

            PendingIntent intent = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), 0);
            mNotificationBuilder.setContentIntent(intent);
        }
        startForeground(12, mNotificationBuilder.build());
    }

    void updateNotification(final PhotoUpload upload) {
        String text;

        switch (upload.getUploadState()) {
            case PhotoUpload.STATE_UPLOAD_WAITING:
                text = "uploading " + (mNumberUploaded + 1);
                mNotificationBuilder.setContentTitle(text);
                mNotificationBuilder.setTicker(text);
                mNotificationBuilder.setProgress(0, 0, true);
                mNotificationBuilder.setWhen(System.currentTimeMillis());
                break;

            case PhotoUpload.STATE_UPLOAD_IN_PROGRESS:
                //
                text = "uploading " + (mNumberUploaded + 1);
                mNotificationBuilder.setContentTitle(text);
                mNotificationBuilder.setTicker(text);
                mNotificationBuilder.setProgress(0, 0, true);
                mNotificationBuilder.setWhen(System.currentTimeMillis());

                break;
        }

        mNotificationMgr.notify(12, mNotificationBuilder.build());
    }

    public static boolean isConnected(Context context) {
        ConnectivityManager mgr = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        final NetworkInfo info = mgr.getActiveNetworkInfo();
        return null != info && info.isConnectedOrConnecting();
    }
}
