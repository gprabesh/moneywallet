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

package com.oriondev.moneywallet.ui.fragment.secondary;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import androidx.annotation.MenuRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.work.Data;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import androidx.fragment.app.Fragment;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.appcompat.widget.Toolbar;
import android.text.InputType;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import com.afollestad.materialdialogs.DialogAction;
import com.afollestad.materialdialogs.MaterialDialog;
import com.oriondev.moneywallet.R;
import com.oriondev.moneywallet.api.BackendException;
import com.oriondev.moneywallet.api.AbstractBackendServiceDelegate;
import com.oriondev.moneywallet.api.BackendServiceFactory;
import com.oriondev.moneywallet.broadcast.LocalAction;
import com.oriondev.moneywallet.model.IFile;
import com.oriondev.moneywallet.worker.BackupWorker;
import com.oriondev.moneywallet.storage.database.backup.BackupManager;
import com.oriondev.moneywallet.ui.adapter.recycler.BackupFileAdapter;
import com.oriondev.moneywallet.ui.fragment.base.MultiPanelFragment;
import com.oriondev.moneywallet.ui.fragment.base.NavigableFragment;
import com.oriondev.moneywallet.ui.fragment.dialog.AutoBackupSettingDialog;
import com.oriondev.moneywallet.ui.view.AdvancedRecyclerView;
import com.oriondev.moneywallet.ui.view.theme.ThemedDialog;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by andrea on 21/11/18.
 */
public class BackupHandlerFragment extends Fragment implements BackupFileAdapter.Controller, SwipeRefreshLayout.OnRefreshListener, Toolbar.OnMenuItemClickListener, AbstractBackendServiceDelegate.BackendServiceStatusListener {

    private static final String ARG_ALLOW_BACKUP = "BackupHandlerFragment::Arguments::AllowBackup";
    private static final String ARG_ALLOW_RESTORE = "BackupHandlerFragment::Arguments::AllowRestore";
    private static final String ARG_BACKEND_ID = "BackupHandlerFragment::Arguments::BackendId";

    public static final String BACKUP_SERVICE_CALLER_ID = "BackupHandlerFragment";

    private static final IFile ROOT_FOLDER = null;

    private boolean mAllowBackup;
    private boolean mAllowRestore;
    private AbstractBackendServiceDelegate mBackendService;
    private List<IFile> mFileStack;

    private AdvancedRecyclerView mAdvancedRecyclerView;
    private BackupFileAdapter mBackupAdapter;

    private View mCoverLayout;
    private View mPrimaryLayout;

    private Toolbar mToolbar;
    private Toolbar mCoverToolbar;

    private AutoBackupSettingDialog mAutoBackupSettingDialog;

    private LocalBroadcastManager mLocalBroadcastManager;

    public static BackupHandlerFragment newInstance(String backendId, boolean allowBackup, boolean allowRestore) {
        BackupHandlerFragment fragment = new BackupHandlerFragment();
        Bundle arguments = new Bundle();
        arguments.putBoolean(ARG_ALLOW_BACKUP, allowBackup);
        arguments.putBoolean(ARG_ALLOW_RESTORE, allowRestore);
        arguments.putString(ARG_BACKEND_ID, backendId);
        fragment.setArguments(arguments);
        return fragment;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Bundle arguments = getArguments();
        if (arguments != null) {
            mAllowBackup = arguments.getBoolean(ARG_ALLOW_BACKUP, true);
            mAllowRestore = arguments.getBoolean(ARG_ALLOW_RESTORE, true);
            String backendId = arguments.getString(ARG_BACKEND_ID, null);
            mBackendService = BackendServiceFactory.getServiceById(backendId, this);
            mFileStack = new ArrayList<>();
        } else {
            throw new IllegalStateException("Arguments bundle is null, please instantiate the fragment using the newInstance() method instead.");
        }
        // initialize the dialog fragment used to schedule auto-backups
        mAutoBackupSettingDialog = new AutoBackupSettingDialog();
        // bind to local broadcast manager to get notified of background operations
        Activity activity = getActivity();
        if (activity != null) {
            if (mBackendService != null) {
                mBackendService.register(this, activity);
            }
            IntentFilter intentFilter = new IntentFilter();
            intentFilter.addAction(LocalAction.ACTION_BACKUP_SERVICE_STARTED);
            intentFilter.addAction(LocalAction.ACTION_BACKUP_SERVICE_FINISHED);
            intentFilter.addAction(LocalAction.ACTION_BACKUP_SERVICE_FAILED);
            mLocalBroadcastManager = LocalBroadcastManager.getInstance(activity);
            mLocalBroadcastManager.registerReceiver(mLocalBroadcastReceiver, intentFilter);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mBackendService != null) {
            if (mBackendService.isServiceEnabled(getActivity())) {
                hideCoverView();
                loadCurrentFolder();
            } else {
                showCoverView();
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mLocalBroadcastManager != null) {
            mLocalBroadcastManager.unregisterReceiver(mLocalBroadcastReceiver);
        }
    }

    @Nullable
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        ViewGroup view = (ViewGroup) inflater.inflate(R.layout.fragment_secondary_panel_backup_list, container, false);
        mPrimaryLayout = view.findViewById(R.id.primary_layout);
        mToolbar = view.findViewById(R.id.secondary_toolbar);
        mCoverLayout = view.findViewById(R.id.cover_layout);
        mCoverToolbar = view.findViewById(R.id.cover_toolbar);
        mToolbar.setTitle(getTitle());
        Fragment parent = getParentFragment();
        if (parent instanceof MultiPanelFragment && !((MultiPanelFragment) parent).isExtendedLayout()) {
            mToolbar.setNavigationIcon(R.drawable.ic_arrow_back_black_24dp);
            mToolbar.setNavigationOnClickListener(new View.OnClickListener() {

                @Override
                public void onClick(View v) {
                    Fragment parent = getParentFragment();
                    if (parent instanceof NavigableFragment) {
                        ((NavigableFragment) parent).navigateBack();
                    }
                }

            });
            mToolbar.inflateMenu(R.menu.menu_backup_service_remote);
            mToolbar.setOnMenuItemClickListener(this);
            if (mCoverToolbar != null) {
                mCoverToolbar.setNavigationIcon(R.drawable.ic_arrow_back_black_24dp);
                mCoverToolbar.setNavigationOnClickListener(new View.OnClickListener() {

                    @Override
                    public void onClick(View v) {
                        Fragment parent = getParentFragment();
                        if (parent instanceof NavigableFragment) {
                            ((NavigableFragment) parent).navigateBack();
                        }
                    }

                });
            }
        }
        mAdvancedRecyclerView = view.findViewById(R.id.advanced_recycler_view);
        FloatingActionButton floatingActionButton = view.findViewById(R.id.floating_action_button);
        mAdvancedRecyclerView.setLayoutManager(new LinearLayoutManager(getActivity()));
        mBackupAdapter = new BackupFileAdapter(this);
        mAdvancedRecyclerView.setEmptyText(R.string.message_no_file_found);
        mAdvancedRecyclerView.setAdapter(mBackupAdapter);
        mAdvancedRecyclerView.setOnRefreshListener(this);
        if (mAllowBackup) {
            floatingActionButton.setOnClickListener(new View.OnClickListener() {

                @Override
                public void onClick(View v) {
                    ThemedDialog.buildMaterialDialog(v.getContext())
                            .title(R.string.title_backup_create)
                            .content(R.string.message_backup_create)
                            .negativeText(android.R.string.cancel)
                            .inputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD)
                            .input(R.string.hint_password, 0, new MaterialDialog.InputCallback() {

                                @Override
                                public void onInput(@NonNull MaterialDialog dialog, CharSequence input) {
                                    Activity activity = getActivity();
                                    if (activity != null) {
                                        IFile folder = mFileStack.isEmpty() ? ROOT_FOLDER : mFileStack.get(mFileStack.size() - 1);
                                        Data.Builder inputData = new Data.Builder()
                                                .putString(BackupWorker.BACKEND_ID, mBackendService.getId())
                                                .putInt(BackupWorker.ACTION, BackupWorker.ACTION_BACKUP)
                                                .putBoolean(BackupWorker.RUN_FOREGROUND, true)
                                                .putString(BackupWorker.CALLER_ID, BACKUP_SERVICE_CALLER_ID);

                                        if (folder != null) {
                                            inputData.putString(BackupWorker.PARENT_FOLDER, folder.encodeToString());
                                        }

                                        if (input.length() > 0) {
                                            inputData.putString(BackupWorker.PASSWORD, input.toString());
                                        }

                                        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(BackupWorker.class)
                                                .setInputData(inputData.build())
                                                .build();
                                        WorkManager.getInstance(activity).enqueue(request);
                                    }
                                }

                            })
                            .show();
                }

            });
        } else {
            floatingActionButton.setVisibility(View.GONE);
        }
        TextView coverTextView = view.findViewById(R.id.cover_text_view);
        Button coverActionButton = view.findViewById(R.id.cover_action_button);
        if (mBackendService != null) {
            coverTextView.setText(mBackendService.getBackupCoverMessage());
            coverActionButton.setText(mBackendService.getBackupCoverAction());
            coverActionButton.setOnClickListener(new View.OnClickListener() {

                @Override
                public void onClick(View v) {
                    try {
                        mBackendService.setup(getActivity());
                    } catch (BackendException e) {
                        e.printStackTrace();
                    }
                }

            });
        }
        return view;
    }

    protected void showCoverView() {
        if (mCoverLayout != null) {
            mPrimaryLayout.setVisibility(View.GONE);
            mCoverLayout.setVisibility(View.VISIBLE);
        }
    }

    protected void hideCoverView() {
        if (mCoverLayout != null) {
            // setup menu item visibility
            setMenuItemVisibility(R.id.action_disconnect, BackendServiceFactory.SERVICE_ID_SAF.equals(mBackendService.getId()));
            // setup layout visibility
            mCoverLayout.setVisibility(View.GONE);
            mPrimaryLayout.setVisibility(View.VISIBLE);
        }
    }

    protected int getTitle() {
        return mBackendService != null ? mBackendService.getName() : 0;
    }

    @MenuRes
    protected int onInflateMenu() {
        return R.menu.menu_backup_service_remote;
    }

    protected void setMenuItemVisibility(int id, boolean visible) {
        Menu menu = mToolbar.getMenu();
        if (menu != null) {
            MenuItem menuItem = menu.findItem(id);
            if (menuItem != null) {
                menuItem.setVisible(visible);
            }
        }
    }

    @Override
    public boolean onMenuItemClick(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_disconnect:
                try {
                    mBackendService.teardown(getActivity());
                } catch (BackendException e) {
                    e.printStackTrace();
                }
                break;
            case R.id.action_auto_backup:
                String tag = getTag() + "::AutoBackupSettingDialog";
                mAutoBackupSettingDialog.show(getChildFragmentManager(), tag, mBackendService.getId());
                break;
        }
        return false;
    }

    protected boolean isStackEmpty() {
        return mFileStack.isEmpty();
    }

    public void loadCurrentFolder() {
        loadFolder(mFileStack.isEmpty() ? ROOT_FOLDER : mFileStack.get(mFileStack.size() - 1));
    }

    protected void loadFolder(IFile folder) {
        Activity activity = getActivity();
        if (activity != null) {
            Data.Builder inputData = new Data.Builder()
                    .putString(BackupWorker.BACKEND_ID, mBackendService.getId())
                    .putInt(BackupWorker.ACTION, BackupWorker.ACTION_LIST)
                    .putString(BackupWorker.CALLER_ID, BACKUP_SERVICE_CALLER_ID);

            if (folder != null) {
                                inputData.putString(BackupWorker.PARENT_FOLDER, folder.encodeToString());
                            }

            OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(BackupWorker.class)
                    .setInputData(inputData.build())
                    .build();
            WorkManager.getInstance(activity).enqueue(request);
        }
    }

    @Override
    public void navigateBack() {
        int stackSize = mFileStack.size();
        if (stackSize > 0) {
            mFileStack.remove(stackSize - 1);
            mAdvancedRecyclerView.setState(AdvancedRecyclerView.State.LOADING);
            loadCurrentFolder();
        }
    }

    @Override
    public void onFileClick(final IFile file) {
        if (file.isDirectory()) {
            mFileStack.add(file);
            mAdvancedRecyclerView.setState(AdvancedRecyclerView.State.LOADING);
            loadFolder(file);
        } else {
            Activity activity = getActivity();
            if (mAllowRestore && activity != null) {
                if (file.getName().endsWith(BackupManager.BACKUP_EXTENSION_STANDARD)) {
                    ThemedDialog.buildMaterialDialog(activity)
                            .title(R.string.title_backup_restore)
                            .content(R.string.message_backup_restore_standard)
                            .positiveText(android.R.string.ok)
                            .negativeText(android.R.string.cancel)
                            .onPositive(new MaterialDialog.SingleButtonCallback() {

                                @Override
                                public void onClick(@NonNull MaterialDialog dialog, @NonNull DialogAction which) {
                                    Data inputData = new Data.Builder()
                                            .putString(BackupWorker.BACKEND_ID, mBackendService.getId())
                                            .putInt(BackupWorker.ACTION, BackupWorker.ACTION_RESTORE)
                                            .putString(BackupWorker.BACKUP_FILE, file.encodeToString())
                                            .putBoolean(BackupWorker.RUN_FOREGROUND, true)
                                            .putString(BackupWorker.CALLER_ID, BACKUP_SERVICE_CALLER_ID)
                                            .build();

                                    OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(BackupWorker.class)
                                            .setInputData(inputData)
                                            .build();
                                    WorkManager.getInstance(getActivity()).enqueue(request);
                                }

                            })
                            .show();
                } else if (file.getName().endsWith(BackupManager.BACKUP_EXTENSION_PROTECTED)) {
                    ThemedDialog.buildMaterialDialog(activity)
                            .title(R.string.title_backup_restore)
                            .content(R.string.message_backup_restore_protected)
                            .positiveText(android.R.string.ok)
                            .inputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD)
                            .input(R.string.hint_password, 0, false, new MaterialDialog.InputCallback() {

                                @Override
                                public void onInput(@NonNull MaterialDialog dialog, CharSequence input) {
                                    Data inputData = new Data.Builder()
                                            .putString(BackupWorker.BACKEND_ID, mBackendService.getId())
                                            .putInt(BackupWorker.ACTION, BackupWorker.ACTION_RESTORE)
                                            .putString(BackupWorker.BACKUP_FILE, file.encodeToString())
                                            .putString(BackupWorker.PASSWORD, input.toString())
                                            .putBoolean(BackupWorker.RUN_FOREGROUND, true)
                                            .putString(BackupWorker.CALLER_ID, BACKUP_SERVICE_CALLER_ID)
                                            .build();

                                    OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(BackupWorker.class)
                                            .setInputData(inputData)
                                            .build();
                                    WorkManager.getInstance(getActivity()).enqueue(request);
                                }

                            })
                            .show();
                } else if (file.getName().endsWith(BackupManager.BACKUP_EXTENSION_LEGACY)) {
                    ThemedDialog.buildMaterialDialog(activity)
                            .title(R.string.title_backup_restore)
                            .content(R.string.message_backup_restore_legacy)
                            .positiveText(android.R.string.ok)
                            .negativeText(android.R.string.cancel)
                            .onPositive(new MaterialDialog.SingleButtonCallback() {

                                @Override
                                public void onClick(@NonNull MaterialDialog dialog, @NonNull DialogAction which) {
                                    Data inputData = new Data.Builder()
                                            .putString(BackupWorker.BACKEND_ID, mBackendService.getId())
                                            .putInt(BackupWorker.ACTION, BackupWorker.ACTION_RESTORE)
                                            .putString(BackupWorker.BACKUP_FILE, file.encodeToString())
                                            .putBoolean(BackupWorker.RUN_FOREGROUND, true)
                                            .putString(BackupWorker.CALLER_ID, BACKUP_SERVICE_CALLER_ID)
                                            .build();

                                    OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(BackupWorker.class)
                                            .setInputData(inputData)
                                            .build();
                                    WorkManager.getInstance(getActivity()).enqueue(request);
                                }

                            })
                            .show();
                }
            }
        }
    }

    @Override
    public void onRefresh() {
        loadCurrentFolder();
    }

    @Override
    public void onBackendStatusChange(boolean enabled) {
        if (enabled) {
            hideCoverView();
            if (isStackEmpty()) {
                loadCurrentFolder();
            }
        } else {
            showCoverView();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String permissions[], @NonNull int[] grantResults) {
        if (!mBackendService.handlePermissionsResult(getActivity(), requestCode, permissions, grantResults)) {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (!mBackendService.handleActivityResult(getActivity(), requestCode, resultCode, data)) {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    private BroadcastReceiver mLocalBroadcastReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            String callerId = intent.getStringExtra(BackupWorker.CALLER_ID);
            if (!BACKUP_SERVICE_CALLER_ID.equals(callerId)) {
                return;
            }
            if (TextUtils.equals(action, LocalAction.ACTION_BACKUP_SERVICE_STARTED)) {
                int operation = intent.getIntExtra(BackupWorker.ACTION, 0);
                if (operation == BackupWorker.ACTION_LIST) {
                    mAdvancedRecyclerView.setState(AdvancedRecyclerView.State.LOADING);
                }
            } else if (TextUtils.equals(action, LocalAction.ACTION_BACKUP_SERVICE_FINISHED)) {
                int operation = intent.getIntExtra(BackupWorker.ACTION, 0);
                if (operation == BackupWorker.ACTION_LIST) {
                    List<IFile> files = intent.getParcelableArrayListExtra(BackupWorker.FOLDER_CONTENT);
                    mBackupAdapter.setFileList(files, mFileStack.size() > 0);
                    if (mBackupAdapter.getItemCount() == 0) {
                        mAdvancedRecyclerView.setState(AdvancedRecyclerView.State.EMPTY);
                    } else {
                        mAdvancedRecyclerView.setState(AdvancedRecyclerView.State.READY);
                    }
                } else if (operation == BackupWorker.ACTION_BACKUP) {
                    IFile backup = intent.getParcelableExtra(BackupWorker.BACKUP_FILE);
                    mBackupAdapter.addFileToList(backup);
                }
            } else if (TextUtils.equals(action, LocalAction.ACTION_BACKUP_SERVICE_FAILED)) {
                int operation = intent.getIntExtra(BackupWorker.ACTION, 0);
                if (operation == BackupWorker.ACTION_LIST) {
                    Exception exception = (Exception) intent.getSerializableExtra(BackupWorker.EXCEPTION);
                    if (exception instanceof BackendException && ((BackendException) exception).isRecoverable()) {
                        mBackupAdapter.setFileList(null, false);
                        mAdvancedRecyclerView.setErrorText(R.string.message_error_backend_recoverable);
                        mAdvancedRecyclerView.setState(AdvancedRecyclerView.State.ERROR);
                    } else {
                        try {
                            mBackendService.teardown(getActivity());
                        } catch (BackendException e) {
                            e.printStackTrace();
                        }
                    }
                }
            }
        }

    };
}