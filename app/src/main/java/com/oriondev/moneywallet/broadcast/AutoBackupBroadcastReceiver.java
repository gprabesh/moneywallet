/*
 * Copyright (c) 2018.
 *
 * This file is part of MoneyWallet.
 *
 * MoneyWallet is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * MoneyWallet is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with MoneyWallet.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.oriondev.moneywallet.broadcast;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.work.Data;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import com.oriondev.moneywallet.api.BackendServiceFactory;
import com.oriondev.moneywallet.model.IFile;
import com.oriondev.moneywallet.storage.preference.BackendManager;
import com.oriondev.moneywallet.storage.preference.PreferenceManager;
import com.oriondev.moneywallet.worker.BackupWorker;

import java.util.Set;

/**
 * Created by andrea on 27/11/18.
 */
public class AutoBackupBroadcastReceiver extends BroadcastReceiver {

    private static final int MILLIS_IN_HOUR = 1000 * 60 * 60;

    public static void scheduleAutoBackupTask(Context context) {
        cancelPendingIntent(context);
        Set<String> backendIdSet = BackendManager.getAutoBackupEnabledServices();
        if (backendIdSet != null && !backendIdSet.isEmpty()) {
            Long nextTimestamp = null;
            for (String backendId : backendIdSet) {
                long lastTimestamp = BackendManager.getAutoBackupLastTime(backendId);
                int hourOffset = BackendManager.getAutoBackupHoursOffset(backendId);
                long nextOccurrence = lastTimestamp + (hourOffset * MILLIS_IN_HOUR);
                if (nextTimestamp == null || nextOccurrence < nextTimestamp) {
                    nextTimestamp = nextOccurrence;
                }
            }
            if (nextTimestamp != null) {
                if (nextTimestamp <= System.currentTimeMillis()) {
                    startBackgroundTask(context);
                } else {
                    schedulePendingIntent(context, nextTimestamp);
                }
            }
        }
    }

    private static void schedulePendingIntent(Context context, long timestamp) {
        PendingIntent pendingIntent = createPendingIntent(context);
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Service.ALARM_SERVICE);
        if (alarmManager != null) {
            alarmManager.set(AlarmManager.RTC_WAKEUP, timestamp, pendingIntent);
            System.out.println("[ALARM] AutoBackupTask scheduled at: " + timestamp);
        }
    }

    private static void cancelPendingIntent(Context context) {
        PendingIntent pendingIntent = createPendingIntent(context);
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Service.ALARM_SERVICE);
        if (alarmManager != null) {
            alarmManager.cancel(pendingIntent);
        }
    }

    private static PendingIntent createPendingIntent(Context context) {
        Intent intent = new Intent(context, AutoBackupBroadcastReceiver.class);
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        startBackgroundTask(context);
    }

    private static void startBackgroundTask(Context context) {
        System.out.println("[ALARM] AutoBackupTask fired now");
        Set<String> backendIdSet = BackendManager.getAutoBackupEnabledServices();
        if (backendIdSet != null && !backendIdSet.isEmpty()) {
            for (String backendId : backendIdSet) {
                long lastTimestamp = BackendManager.getAutoBackupLastTime(backendId);
                int hourOffset = BackendManager.getAutoBackupHoursOffset(backendId);
                long nextOccurrence = lastTimestamp + (hourOffset * MILLIS_IN_HOUR);
                if (nextOccurrence <= System.currentTimeMillis()) {
                    if (!BackendManager.isAutoBackupWhenDataIsChangedOnly(backendId) || PreferenceManager.getLastTimeDataIsChanged() > lastTimestamp) {
                        boolean onlyOnWiFi = BackendManager.isAutoBackupOnWiFiOnly(backendId);
                        IFile folder = BackendServiceFactory.getFile(backendId, BackendManager.getAutoBackupFolder(backendId));
                        String password = BackendManager.getAutoBackupPassword(backendId);
                        // build the work request
                        Data inputData = new Data.Builder()
                                .putInt(BackupWorker.ACTION, BackupWorker.ACTION_BACKUP)
                                .putString(BackupWorker.BACKEND_ID, backendId)
                                .putBoolean(BackupWorker.AUTO_BACKUP, true)
                                .putBoolean(BackupWorker.ONLY_ON_WIFI, onlyOnWiFi)
                                .putString(BackupWorker.PARENT_FOLDER, folder.encodeToString())
                                .putString(BackupWorker.PASSWORD, password)
                                .build();

                        OneTimeWorkRequest backupRequest = new OneTimeWorkRequest.Builder(BackupWorker.class)
                                .setInputData(inputData)
                                .build();

                        WorkManager.getInstance(context).enqueue(backupRequest);
                    }
                    // register the next occurrence as the last time the auto backup
                    // for this specific backend has been executed
                    BackendManager.setAutoBackupLastTime(backendId, nextOccurrence);
                }
            }
        }
        // reschedule the auto backup immediately
        AutoBackupBroadcastReceiver.scheduleAutoBackupTask(context);
    }
}