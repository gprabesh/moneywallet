package com.oriondev.moneywallet.worker;

import android.app.Notification;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.work.Data;
import androidx.work.ForegroundInfo;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.oriondev.moneywallet.R;
import com.oriondev.moneywallet.broadcast.LocalAction;
import com.oriondev.moneywallet.model.CurrencyUnit;
import com.oriondev.moneywallet.model.DataFormat;
import com.oriondev.moneywallet.model.Wallet;
import com.oriondev.moneywallet.storage.database.Contract;
import com.oriondev.moneywallet.storage.database.DataContentProvider;
import com.oriondev.moneywallet.storage.database.data.AbstractDataExporter;
import com.oriondev.moneywallet.storage.database.data.AbstractDataImporter;
import com.oriondev.moneywallet.storage.database.data.csv.CSVDataExporter;
import com.oriondev.moneywallet.storage.database.data.csv.CSVDataImporter;
import com.oriondev.moneywallet.storage.database.data.pdf.PDFDataExporter;
import com.oriondev.moneywallet.storage.database.data.xls.XLSDataExporter;
import com.oriondev.moneywallet.ui.notification.NotificationContract;
import com.oriondev.moneywallet.utils.CurrencyManager;
import com.oriondev.moneywallet.utils.DateUtils;
import com.oriondev.moneywallet.utils.IconLoader;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class ImportExportWorker extends Worker {

    public static final String MODE = "ImportExportWorker::Arguments::Mode";
    public static final String FORMAT = "ImportExportWorker::Arguments::Format";
    public static final String START_DATE = "ImportExportWorker::Arguments::StartDate";
    public static final String END_DATE = "ImportExportWorker::Arguments::EndDate";
    public static final String WALLET_IDS = "ImportExportWorker::Arguments::Wallets";
    public static final String FOLDER_URI = "ImportExportWorker::Arguments::FolderUri";
    public static final String FILE_URI = "ImportExportWorker::Arguments::FileUri";
    public static final String UNIQUE_WALLET = "ImportExportWorker::Arguments::UniqueWallet";
    public static final String OPTIONAL_COLUMNS = "ImportExportWorker::Arguments::OptionalColumns";

    public static final String RESULT_FILE_URI = "ImportExportWorker::Results::FileUri";
    public static final String RESULT_FILE_TYPE = "ImportExportWorker::Results::FileType";
    public static final String EXCEPTION = "ImportExportWorker::Results::Exception";

    public static final int MODE_EXPORT = 0;
    public static final int MODE_IMPORT = 1;

    private LocalBroadcastManager mBroadcastManager;

    public ImportExportWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        mBroadcastManager = LocalBroadcastManager.getInstance(getApplicationContext());
        Data inputData = getInputData();
        int mode = inputData.getInt(MODE, MODE_EXPORT);

        switch (mode) {
            case MODE_EXPORT:
                setForegroundAsync(createForegroundInfo(R.string.title_data_exporting));
                handleExport(inputData);
                break;
            case MODE_IMPORT:
                setForegroundAsync(createForegroundInfo(R.string.title_data_importing));
                handleImport(inputData);
                break;
        }
        return Result.success();
    }

    private ForegroundInfo createForegroundInfo(int titleRes) {
        Notification notification = new NotificationCompat.Builder(getApplicationContext(), NotificationContract.NOTIFICATION_CHANNEL_BACKUP)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getApplicationContext().getString(titleRes))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return new ForegroundInfo(NotificationContract.NOTIFICATION_ID_BACKUP_PROGRESS, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            return new ForegroundInfo(NotificationContract.NOTIFICATION_ID_BACKUP_PROGRESS, notification);
        }
    }

    private void handleImport(Data inputData) {
        notifyTaskStarted(LocalAction.ACTION_IMPORT_SERVICE_STARTED);
        try {
            String formatName = inputData.getString(FORMAT);
            DataFormat dataFormat = DataFormat.valueOf(formatName);
            Uri fileUri = Uri.parse(inputData.getString(FILE_URI));

            AbstractDataImporter dataImporter = getDataImporter(dataFormat, fileUri);
            try {
                dataImporter.importData();
            } finally {
                dataImporter.close();
            }
            notifyTaskFinished(LocalAction.ACTION_IMPORT_SERVICE_FINISHED);
        } catch (Exception e) {
            notifyTaskFailed(LocalAction.ACTION_IMPORT_SERVICE_FAILED, e);
        }
    }

    private void handleExport(Data inputData) {
        notifyTaskStarted(LocalAction.ACTION_EXPORT_SERVICE_STARTED);
        try {
            String formatName = inputData.getString(FORMAT);
            DataFormat dataFormat = DataFormat.valueOf(formatName);
            long startDateTime = inputData.getLong(START_DATE, -1);
            Date startDate = startDateTime == -1 ? null : new Date(startDateTime);
            long endDateTime = inputData.getLong(END_DATE, -1);
            Date endDate = endDateTime == -1 ? null : new Date(endDateTime);
            long[] walletIds = inputData.getLongArray(WALLET_IDS);
            Wallet[] wallets = fetchWallets(walletIds);
            Uri folderUri = Uri.parse(inputData.getString(FOLDER_URI));
            boolean uniqueWallet = inputData.getBoolean(UNIQUE_WALLET, false);
            String[] optionalColumns = inputData.getStringArray(OPTIONAL_COLUMNS);

            if (wallets == null || wallets.length == 0) {
                throw new IllegalArgumentException("parameter is null or empty [WALLETS]");
            }

            AbstractDataExporter dataExporter = getDataExporter(dataFormat, folderUri);
            ContentResolver contentResolver = getApplicationContext().getContentResolver();
            Uri uri = DataContentProvider.CONTENT_TRANSACTIONS;

            StringBuilder selectionBuilder = new StringBuilder();
            List<String> selectionArguments = new ArrayList<>();
            selectionBuilder.append("DATE (" + Contract.Transaction.DATE + ") <= DATE(?)");
            selectionArguments.add(DateUtils.getSQLDateString(getFixedEndDate(endDate)));

            if (startDate != null) {
                selectionBuilder.append(" AND DATE (" + Contract.Transaction.DATE + ") >= DATE(?)");
                selectionArguments.add(DateUtils.getSQLDateString(startDate));
            }

            String sortOrder = Contract.Transaction.DATE + " DESC";
            boolean multiWallet = wallets.length > 1 && dataExporter.isMultiWalletSupported() && !uniqueWallet;
            String[] columns = dataExporter.getColumns(!multiWallet, optionalColumns);

            if (dataExporter.shouldLoadPeople()) {
                Cursor cursor = contentResolver.query(DataContentProvider.CONTENT_PEOPLE, null, null, null, null);
                if (cursor != null) {
                    dataExporter.cachePeople(cursor);
                    cursor.close();
                }
            }

            if (multiWallet) {
                for (Wallet wallet : wallets) {
                    String selection = selectionBuilder + " AND " + Contract.Transaction.WALLET_ID + " = ?";
                    String[] arguments = selectionArguments.toArray(new String[selectionArguments.size() + 1]);
                    arguments[arguments.length - 1] = String.valueOf(wallet.getId());
                    Cursor cursor = contentResolver.query(uri, null, selection, arguments, sortOrder);
                    if (cursor != null) {
                        dataExporter.exportData(cursor, columns, wallet);
                        cursor.close();
                    }
                }
            } else {
                selectionBuilder.append(" AND (");
                for (int i = 0; i < wallets.length; i++) {
                    if (i != 0) {
                        selectionBuilder.append(" OR ");
                    }
                    selectionBuilder.append(Contract.Transaction.WALLET_ID + " = ?");
                    selectionArguments.add(String.valueOf(wallets[i].getId()));
                }
                selectionBuilder.append(")");
                Cursor cursor = contentResolver.query(uri, null, selectionBuilder.toString(), selectionArguments.toArray(new String[selectionArguments.size()]), sortOrder);
                if (cursor != null) {
                    dataExporter.exportData(cursor, columns, wallets);
                    cursor.close();
                }
            }

            dataExporter.close();
            Uri resultUri = dataExporter.getOutputFile();
            String resultType = dataExporter.getResultType();
            notifyTaskFinished(LocalAction.ACTION_EXPORT_SERVICE_FINISHED, resultUri, resultType);
        } catch (Exception e) {
            e.printStackTrace();
            notifyTaskFailed(LocalAction.ACTION_EXPORT_SERVICE_FAILED, e);
        }
    }

    private Wallet[] fetchWallets(long[] walletIds) {
        if (walletIds == null || walletIds.length == 0) return new Wallet[0];
        List<Wallet> wallets = new ArrayList<>();
        ContentResolver contentResolver = getApplicationContext().getContentResolver();
        
        StringBuilder selection = new StringBuilder(Contract.Wallet.ID + " IN (");
        for (int i = 0; i < walletIds.length; i++) {
            if (i != 0) selection.append(",");
            selection.append(walletIds[i]);
        }
        selection.append(")");

        Cursor cursor = contentResolver.query(
                DataContentProvider.CONTENT_WALLETS,
                null,
                selection.toString(),
                null,
                null
        );

        if (cursor != null) {
            int mIndexId = cursor.getColumnIndex(Contract.Wallet.ID);
            int mIndexName = cursor.getColumnIndex(Contract.Wallet.NAME);
            int mIndexIcon = cursor.getColumnIndex(Contract.Wallet.ICON);
            int mIndexCurrency = cursor.getColumnIndex(Contract.Wallet.CURRENCY);
            int mIndexStartMoney = cursor.getColumnIndex(Contract.Wallet.START_MONEY);
            int mIndexTotalMoney = cursor.getColumnIndex(Contract.Wallet.TOTAL_MONEY);

            while (cursor.moveToNext()) {
                wallets.add(new Wallet(
                        cursor.getLong(mIndexId),
                        cursor.getString(mIndexName),
                        IconLoader.parse(cursor.getString(mIndexIcon)),
                        CurrencyManager.getCurrency(cursor.getString(mIndexCurrency)),
                        cursor.getLong(mIndexStartMoney),
                        cursor.getLong(mIndexTotalMoney)
                ));
            }
            cursor.close();
        }
        return wallets.toArray(new Wallet[0]);
    }

    private void notifyTaskStarted(String action) {
        Intent intent = new Intent(action);
        mBroadcastManager.sendBroadcast(intent);
    }

    private void notifyTaskFinished(String action) {
        Intent intent = new Intent(action);
        mBroadcastManager.sendBroadcast(intent);
    }

    private void notifyTaskFinished(String action, Uri resultUri, String resultType) {
        Intent intent = new Intent(action);
        intent.putExtra(RESULT_FILE_URI, resultUri);
        intent.putExtra(RESULT_FILE_TYPE, resultType);
        mBroadcastManager.sendBroadcast(intent);
    }

    private void notifyTaskFailed(String action, Exception exception) {
        Intent intent = new Intent(action);
        intent.putExtra(EXCEPTION, exception);
        mBroadcastManager.sendBroadcast(intent);
    }

    private AbstractDataImporter getDataImporter(DataFormat dataFormat, Uri fileUri) throws IOException {
        switch (dataFormat) {
            case CSV:
                return new CSVDataImporter(getApplicationContext(), fileUri);
            default:
                throw new RuntimeException("DataFormat not supported");
        }
    }

    private AbstractDataExporter getDataExporter(DataFormat dataFormat, Uri folderUri) throws IOException {
        switch (dataFormat) {
            case CSV:
                return new CSVDataExporter(getApplicationContext(), folderUri);
            case XLS:
                return new XLSDataExporter(getApplicationContext(), folderUri);
            case PDF:
                return new PDFDataExporter(getApplicationContext(), folderUri);
            default:
                throw new RuntimeException("DataFormat not supported");
        }
    }

    private Date getFixedEndDate(Date endDate) {
        Date now = new Date();
        if (endDate != null) {
            long minMillis = Math.min(now.getTime(), endDate.getTime());
            return new Date(minMillis);
        }
        return now;
    }
}
