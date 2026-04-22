package com.oriondev.moneywallet.worker;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.work.Data;
import androidx.work.ForegroundInfo;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.oriondev.moneywallet.R;
import com.oriondev.moneywallet.api.BackendException;
import com.oriondev.moneywallet.api.BackendServiceFactory;
import com.oriondev.moneywallet.api.IBackendServiceAPI;
import com.oriondev.moneywallet.broadcast.AutoBackupBroadcastReceiver;
import com.oriondev.moneywallet.broadcast.LocalAction;
import com.oriondev.moneywallet.broadcast.NotificationBroadcastReceiver;
import com.oriondev.moneywallet.broadcast.RecurrenceBroadcastReceiver;
import com.oriondev.moneywallet.model.IFile;
import com.oriondev.moneywallet.storage.database.DataContentProvider;
import com.oriondev.moneywallet.storage.database.ExportException;
import com.oriondev.moneywallet.storage.database.ImportException;
import com.oriondev.moneywallet.storage.database.SQLDatabaseImporter;
import com.oriondev.moneywallet.storage.database.backup.AbstractBackupExporter;
import com.oriondev.moneywallet.storage.database.backup.AbstractBackupImporter;
import com.oriondev.moneywallet.storage.database.backup.BackupManager;
import com.oriondev.moneywallet.storage.database.backup.DefaultBackupExporter;
import com.oriondev.moneywallet.storage.database.backup.DefaultBackupImporter;
import com.oriondev.moneywallet.storage.database.backup.LegacyBackupImporter;
import com.oriondev.moneywallet.storage.preference.BackendManager;
import com.oriondev.moneywallet.storage.preference.PreferenceManager;
import com.oriondev.moneywallet.ui.notification.NotificationContract;
import com.oriondev.moneywallet.utils.CurrencyManager;
import com.oriondev.moneywallet.utils.DateUtils;
import com.oriondev.moneywallet.utils.ProgressInputStream;
import com.oriondev.moneywallet.utils.ProgressOutputStream;
import com.oriondev.moneywallet.utils.Utils;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class BackupWorker extends Worker {

    public static final String ACTION = "BackupWorker::Argument::Action";
    public static final String BACKEND_ID = "BackupWorker::Argument::BackendId";
    public static final String AUTO_BACKUP = "BackupWorker::Argument::AutoBackup";
    public static final String ONLY_ON_WIFI = "BackupWorker::Argument::OnlyOnWifi";
    public static final String RUN_FOREGROUND = "BackupWorker::Argument::RunForeground";
    public static final String BACKUP_FILE = "BackupWorker::Argument::BackupFile";
    public static final String EXCEPTION = "BackupWorker::Argument::Exception";
    public static final String FOLDER_CONTENT = "BackupWorker::Argument::FolderContent";
    public static final String PARENT_FOLDER = "BackupWorker::Argument::ParentFolder";
    public static final String PASSWORD = "BackupWorker::Argument::Password";
    public static final String PROGRESS_STATUS = "BackupWorker::Argument::ProgressStatus";
    public static final String PROGRESS_VALUE = "BackupWorker::Argument::ProgressValue";
    public static final String CALLER_ID = "BackupWorker::Argument::CallerId";

    private static final String ATTACHMENT_FOLDER = "attachments";
    private static final String BACKUP_CACHE_FOLDER = "backups";
    private static final String TEMP_FOLDER = "temp";
    private static final String FILE_DATETIME_PATTERN = "yyyy-MM-dd_HH-mm-ss";
    private static final String OUTPUT_FILE = "backup_%s%s";

    public static final int ACTION_NONE = 0;
    public static final int ACTION_LIST = 1;
    public static final int ACTION_BACKUP = 2;
    public static final int ACTION_RESTORE = 3;

    public static final int STATUS_BACKUP_CREATION = 1;
    public static final int STATUS_BACKUP_UPLOADING = 2;
    public static final int STATUS_BACKUP_DOWNLOADING = 3;
    public static final int STATUS_BACKUP_RESTORING = 4;

    private static final boolean DEFAULT_AUTO_BACKUP = false;
    private static final boolean DEFAULT_ONLY_ON_WIFI = false;
    private static final boolean DEFAULT_RUN_FOREGROUND = false;

    private boolean mAutoBackup;
    private IBackendServiceAPI mBackendServiceAPI;
    private String mCallerId;
    private LocalBroadcastManager mBroadcastManager;
    private NotificationCompat.Builder mNotificationBuilder;
    private String mBackendId;

    public BackupWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        Data inputData = getInputData();
        int action = inputData.getInt(ACTION, ACTION_NONE);
        mBackendId = inputData.getString(BACKEND_ID);
        mAutoBackup = inputData.getBoolean(AUTO_BACKUP, DEFAULT_AUTO_BACKUP);
        boolean onlyOnWiFi = inputData.getBoolean(ONLY_ON_WIFI, DEFAULT_ONLY_ON_WIFI);
        boolean runForeground = inputData.getBoolean(RUN_FOREGROUND, DEFAULT_RUN_FOREGROUND);
        mCallerId = inputData.getString(CALLER_ID);

        mBroadcastManager = LocalBroadcastManager.getInstance(getApplicationContext());

        try {
            if (runForeground && (action == ACTION_BACKUP || action == ACTION_RESTORE)) {
                mNotificationBuilder = getBaseNotificationBuilder(NotificationContract.NOTIFICATION_CHANNEL_BACKUP)
                        .setProgress(0, 0, true)
                        .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                        .setContentTitle(getNotificationContentTitle(action, false));
                
                ForegroundInfo foregroundInfo;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    foregroundInfo = new ForegroundInfo(NotificationContract.NOTIFICATION_ID_BACKUP_PROGRESS, mNotificationBuilder.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
                } else {
                    foregroundInfo = new ForegroundInfo(NotificationContract.NOTIFICATION_ID_BACKUP_PROGRESS, mNotificationBuilder.build());
                }
                setForegroundAsync(foregroundInfo);
            }

            notifyTaskStarted(action);
            mBackendServiceAPI = BackendServiceFactory.getServiceAPIById(getApplicationContext(), mBackendId);

            if (onlyOnWiFi && (action == ACTION_BACKUP || action == ACTION_RESTORE)) {
                ConnectivityManager connectivityManager = (ConnectivityManager) getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
                if (connectivityManager != null) {
                    NetworkInfo networkInfo = connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
                    if (networkInfo == null || !networkInfo.isConnected()) {
                        throw new WiFiNotConnectedException();
                    }
                }
            }

            switch (action) {
                case ACTION_LIST:
                    onActionList(inputData);
                    break;
                case ACTION_BACKUP:
                    onActionBackup(inputData);
                    break;
                case ACTION_RESTORE:
                    onActionRestore(inputData);
                    break;
            }
            return Result.success();
        } catch (Exception e) {
            if (e instanceof BackendException) {
                if (!((BackendException) e).isRecoverable()) {
                    BackendManager.setAutoBackupEnabled(mBackendId, false);
                    AutoBackupBroadcastReceiver.scheduleAutoBackupTask(getApplicationContext());
                }
            }
            notifyTaskFailure(action, e);
            return Result.failure();
        }
    }

    private void onActionList(Data inputData) throws BackendException {
        String encodedFolder = inputData.getString(PARENT_FOLDER);
        IFile remoteFolder = BackendServiceFactory.getFile(mBackendId, encodedFolder);
        List<IFile> fileList = mBackendServiceAPI.getFolderContent(remoteFolder);
        notifyListTaskFinished(fileList);
    }

    private void onActionBackup(Data inputData) throws ExportException, BackendException, IOException {
        String encodedFolder = inputData.getString(PARENT_FOLDER);
        IFile remoteFolder = BackendServiceFactory.getFile(mBackendId, encodedFolder);
        File folder = getApplicationContext().getExternalFilesDir(null);
        File cache = new File(folder, BACKUP_CACHE_FOLDER);
        File revision = new File(cache, UUID.randomUUID().toString());
        try {
            FileUtils.forceMkdir(revision);
            String password = inputData.getString(PASSWORD);
            notifyTaskProgress(ACTION_BACKUP, STATUS_BACKUP_CREATION, 0);
            File backup = prepareLocalBackupFile(revision, password);
            notifyTaskProgress(ACTION_BACKUP, STATUS_BACKUP_UPLOADING, 30);
            IFile uploaded = mBackendServiceAPI.uploadFile(remoteFolder, backup, new ProgressInputStream.UploadProgressListener() {
                @Override
                public void onUploadProgressUpdate(int percentage) {
                    int realProgress = 30 + (percentage * 70 / 100);
                    notifyTaskProgress(ACTION_BACKUP, STATUS_BACKUP_UPLOADING, realProgress);
                }
            });
            notifyTaskProgress(ACTION_BACKUP, STATUS_BACKUP_UPLOADING, 100);
            notifyUploadTaskFinished(uploaded);
        } finally {
            FileUtils.deleteQuietly(revision);
        }
    }

    private File prepareLocalBackupFile(@NonNull File folder, @Nullable String password) throws ExportException, IOException {
        File backupFile = createBackupFile(folder, BackupManager.getExtension(!TextUtils.isEmpty(password)));
        AbstractBackupExporter exporter = new DefaultBackupExporter(getApplicationContext().getContentResolver(), backupFile, password);
        exporter.exportDatabase(getApplicationContext().getFilesDir());
        exporter.exportAttachments(getAttachmentFolder());
        return backupFile;
    }

    private File createBackupFile(@NonNull File folder, @NonNull String extension) {
        String datetime = DateUtils.getDateTimeString(new Date(), FILE_DATETIME_PATTERN);
        String name = String.format(Locale.ENGLISH, OUTPUT_FILE, datetime, extension);
        return new File(folder, name);
    }

    private File getAttachmentFolder() throws IOException {
        File root = getApplicationContext().getExternalFilesDir(null);
        File folder = new File(root, ATTACHMENT_FOLDER);
        FileUtils.forceMkdir(folder);
        return folder;
    }

    private void onActionRestore(Data inputData) throws ImportException, BackendException, IOException {
        String encodedFile = inputData.getString(BACKUP_FILE);
        IFile remoteFile = BackendServiceFactory.getFile(mBackendId, encodedFile);
        if (remoteFile != null) {
            File folder = getApplicationContext().getExternalFilesDir(null);
            File cache = new File(folder, BACKUP_CACHE_FOLDER);
            File revision = new File(cache, UUID.randomUUID().toString());
            try {
                FileUtils.forceMkdir(revision);
                notifyTaskProgress(ACTION_RESTORE, STATUS_BACKUP_DOWNLOADING, 0);
                File backup = mBackendServiceAPI.downloadFile(revision, remoteFile, new ProgressOutputStream.DownloadProgressListener() {
                    @Override
                    public void onDownloadProgressUpdate(int percentage) {
                        int realProgress = (percentage * 70 / 100);
                        notifyTaskProgress(ACTION_RESTORE, STATUS_BACKUP_DOWNLOADING, realProgress);
                    }
                });
                notifyTaskProgress(ACTION_RESTORE, STATUS_BACKUP_RESTORING, 75);
                String password = inputData.getString(PASSWORD);
                restoreLocalBackupFile(backup, password);
                notifyTaskProgress(ACTION_RESTORE, STATUS_BACKUP_RESTORING, 100);
                DataContentProvider.notifyDatabaseIsChanged(getApplicationContext());
                PreferenceManager.setLastTimeDataIsChanged(0L);
                CurrencyManager.invalidateCache(getApplicationContext());
                RecurrenceBroadcastReceiver.scheduleRecurrenceTask(getApplicationContext());
                AutoBackupBroadcastReceiver.scheduleAutoBackupTask(getApplicationContext());
                notifyTaskFinished(ACTION_RESTORE);
            } finally {
                FileUtils.deleteQuietly(revision);
            }
        } else {
            throw new RuntimeException("Backup file to restore not specified");
        }
    }

    private void restoreLocalBackupFile(@NonNull File backup, @Nullable String password) throws ImportException, IOException {
        AbstractBackupImporter importer;
        String fileName = backup.getName();
        if (fileName.endsWith(BackupManager.BACKUP_EXTENSION_LEGACY)) {
            importer = new LegacyBackupImporter(getApplicationContext(), backup);
        } else {
            importer = new DefaultBackupImporter(getApplicationContext(), backup, password);
        }
        File temporaryFolder = new File(getApplicationContext().getExternalFilesDir(null), TEMP_FOLDER);
        FileUtils.forceMkdir(temporaryFolder);
        try {
            File databaseFile = getApplicationContext().getDatabasePath(SQLDatabaseImporter.DATABASE_NAME);
            importer.importDatabase(temporaryFolder, databaseFile.getParentFile());
            importer.importAttachments(getAttachmentFolder());
        } finally {
            FileUtils.cleanDirectory(temporaryFolder);
        }
    }

    private String getNotificationContentTitle(int action, boolean error) {
        if (action == ACTION_BACKUP) {
            return getApplicationContext().getString(error ? R.string.notification_title_backup_creation_failed : R.string.notification_title_backup_creation);
        } else if (action == ACTION_RESTORE) {
            return getApplicationContext().getString(error ? R.string.notification_title_backup_restoring_failed : R.string.notification_title_backup_restoring);
        }
        return null;
    }

    private NotificationCompat.Builder getBaseNotificationBuilder(String channelId) {
        return new NotificationCompat.Builder(getApplicationContext(), channelId)
                .setSmallIcon(Utils.isAtLeastLollipop() ? R.drawable.ic_notification : R.mipmap.ic_launcher);
    }

    private String getNotificationContentText(int status) {
        switch (status) {
            case STATUS_BACKUP_CREATION:
                return getApplicationContext().getString(R.string.notification_content_backup_file_creation);
            case STATUS_BACKUP_UPLOADING:
                return getApplicationContext().getString(R.string.notification_content_backup_file_uploading);
            case STATUS_BACKUP_DOWNLOADING:
                return getApplicationContext().getString(R.string.notification_content_backup_file_downloading);
            case STATUS_BACKUP_RESTORING:
                return getApplicationContext().getString(R.string.notification_content_backup_file_restoring);
        }
        return null;
    }

    private void notifyTaskStarted(int action) {
        Intent intent = new Intent(LocalAction.ACTION_BACKUP_SERVICE_STARTED);
        intent.putExtra(ACTION, action);
        intent.putExtra(CALLER_ID, mCallerId);
        mBroadcastManager.sendBroadcast(intent);
    }

    private void notifyTaskProgress(int action, int status, int progress) {
        Intent intent = new Intent(LocalAction.ACTION_BACKUP_SERVICE_RUNNING);
        intent.putExtra(ACTION, action);
        intent.putExtra(PROGRESS_STATUS, status);
        intent.putExtra(PROGRESS_VALUE, progress);
        intent.putExtra(CALLER_ID, mCallerId);
        mBroadcastManager.sendBroadcast(intent);

        if (mNotificationBuilder != null) {
            mNotificationBuilder.setContentText(getNotificationContentText(status));
            mNotificationBuilder.setProgress(100, progress, false);
            ForegroundInfo foregroundInfo;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                foregroundInfo = new ForegroundInfo(NotificationContract.NOTIFICATION_ID_BACKUP_PROGRESS, mNotificationBuilder.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                foregroundInfo = new ForegroundInfo(NotificationContract.NOTIFICATION_ID_BACKUP_PROGRESS, mNotificationBuilder.build());
            }
            setForegroundAsync(foregroundInfo);
        }
    }

    private void notifyTaskFinished(int action) {
        Intent intent = new Intent(LocalAction.ACTION_BACKUP_SERVICE_FINISHED);
        intent.putExtra(ACTION, action);
        intent.putExtra(CALLER_ID, mCallerId);
        mBroadcastManager.sendBroadcast(intent);
    }

    private void notifyListTaskFinished(List<IFile> files) {
        Intent intent = new Intent(LocalAction.ACTION_BACKUP_SERVICE_FINISHED);
        intent.putExtra(ACTION, ACTION_LIST);
        intent.putParcelableArrayListExtra(FOLDER_CONTENT, Utils.wrapAsArrayList(files));
        intent.putExtra(CALLER_ID, mCallerId);
        mBroadcastManager.sendBroadcast(intent);
    }

    private void notifyUploadTaskFinished(IFile file) {
        Intent intent = new Intent(LocalAction.ACTION_BACKUP_SERVICE_FINISHED);
        intent.putExtra(ACTION, ACTION_BACKUP);
        intent.putExtra(BACKUP_FILE, file);
        intent.putExtra(CALLER_ID, mCallerId);
        mBroadcastManager.sendBroadcast(intent);
    }

    private void notifyTaskFailure(int action, Exception exception) {
        Intent intent = new Intent(LocalAction.ACTION_BACKUP_SERVICE_FAILED);
        intent.putExtra(ACTION, action);
        intent.putExtra(EXCEPTION, exception);
        intent.putExtra(CALLER_ID, mCallerId);
        mBroadcastManager.sendBroadcast(intent);

        if (mNotificationBuilder != null || mAutoBackup) {
            mNotificationBuilder = getBaseNotificationBuilder(NotificationContract.NOTIFICATION_CHANNEL_ERROR)
                    .setContentTitle(getNotificationContentTitle(action, true))
                    .setCategory(NotificationCompat.CATEGORY_ERROR);
            if (exception instanceof WiFiNotConnectedException || (exception instanceof BackendException && ((BackendException) exception).isRecoverable())) {
                Bundle intentArguments = new Bundle();
                intentArguments.putInt(ACTION, action);
                intentArguments.putString(BACKEND_ID, mBackendId);
                intentArguments.putBoolean(AUTO_BACKUP, mAutoBackup);
                intentArguments.putString(CALLER_ID, mCallerId);
                
                Data inputData = getInputData();
                intentArguments.putBoolean(ONLY_ON_WIFI, inputData.getBoolean(ONLY_ON_WIFI, DEFAULT_ONLY_ON_WIFI));
                intentArguments.putBoolean(RUN_FOREGROUND, inputData.getBoolean(RUN_FOREGROUND, DEFAULT_RUN_FOREGROUND));
                intentArguments.putString(PASSWORD, inputData.getString(PASSWORD));
                intentArguments.putString(PARENT_FOLDER, inputData.getString(PARENT_FOLDER));
                intentArguments.putString(BACKUP_FILE, inputData.getString(BACKUP_FILE));

                Intent retryIntent = new Intent(getApplicationContext(), NotificationBroadcastReceiver.class);
                retryIntent.setAction(NotificationBroadcastReceiver.ACTION_RETRY_BACKUP_CREATION);
                retryIntent.putExtra(NotificationBroadcastReceiver.ACTION_INTENT_ARGUMENTS, intentArguments);
                PendingIntent pendingIntent = PendingIntent.getBroadcast(getApplicationContext(), 0, retryIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                
                if (exception instanceof WiFiNotConnectedException) {
                    mNotificationBuilder.setContentText(getApplicationContext().getString(R.string.notification_content_backup_error_wifi_network));
                    mNotificationBuilder.setStyle(new NotificationCompat.BigTextStyle().bigText(getApplicationContext().getString(R.string.notification_content_backup_error_wifi_network)));
                } else {
                    mNotificationBuilder.setContentText(getApplicationContext().getString(R.string.notification_content_backup_error_backend_recoverable));
                    mNotificationBuilder.setStyle(new NotificationCompat.BigTextStyle().bigText(getApplicationContext().getString(R.string.notification_content_backup_error_backend_recoverable)));
                }
                mNotificationBuilder.addAction(R.drawable.ic_refresh_black_24dp, getApplicationContext().getString(R.string.notification_action_retry), pendingIntent);
            } else if (exception instanceof BackendException) {
                mNotificationBuilder.setContentText(getApplicationContext().getString(R.string.notification_content_backup_error_backend));
                mNotificationBuilder.setStyle(new NotificationCompat.BigTextStyle().bigText(getApplicationContext().getString(R.string.notification_content_backup_error_backend)));
            } else {
                String message = getApplicationContext().getString(R.string.notification_content_backup_error_internal, exception.getMessage());
                mNotificationBuilder.setContentText(message);
                mNotificationBuilder.setStyle(new NotificationCompat.BigTextStyle().bigText(message));
            }
            NotificationManagerCompat notificationManager = NotificationManagerCompat.from(getApplicationContext());
            notificationManager.notify(NotificationContract.NOTIFICATION_ID_BACKUP_ERROR, mNotificationBuilder.build());
        }
    }

    private static class WiFiNotConnectedException extends Exception {
        private WiFiNotConnectedException() {
            super("the device is not connected to a WiFi network");
        }
    }
}
