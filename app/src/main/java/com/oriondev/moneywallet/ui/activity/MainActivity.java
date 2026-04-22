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

package com.oriondev.moneywallet.ui.activity;

import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.annotation.StringRes;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.loader.app.LoaderManager;
import androidx.loader.content.CursorLoader;
import androidx.loader.content.Loader;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.recyclerview.widget.RecyclerView;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import com.oriondev.moneywallet.utils.CurrencyManager;
import com.oriondev.moneywallet.utils.MoneyFormatter;
import com.oriondev.moneywallet.utils.Utils;

import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import com.google.android.material.navigation.NavigationView;
import com.afollestad.materialdialogs.MaterialDialog;
import com.oriondev.moneywallet.R;
import com.oriondev.moneywallet.broadcast.LocalAction;
import com.oriondev.moneywallet.model.ColorIcon;
import com.oriondev.moneywallet.model.Money;
import com.oriondev.moneywallet.model.WalletAccount;
import com.oriondev.moneywallet.worker.BackupWorker;
import com.oriondev.moneywallet.storage.database.Contract;
import com.oriondev.moneywallet.storage.database.DataContentProvider;
import com.oriondev.moneywallet.storage.preference.PreferenceManager;
import com.oriondev.moneywallet.ui.activity.base.BaseActivity;
import com.oriondev.moneywallet.ui.fragment.base.NavigableFragment;
import com.oriondev.moneywallet.ui.fragment.multipanel.BudgetMultiPanelViewPagerFragment;
import com.oriondev.moneywallet.ui.fragment.multipanel.CategoryMultiPanelViewPagerFragment;
import com.oriondev.moneywallet.ui.fragment.multipanel.DebtMultiPanelViewPagerFragment;
import com.oriondev.moneywallet.ui.fragment.multipanel.EventMultiPanelViewPagerFragment;
import com.oriondev.moneywallet.ui.fragment.multipanel.ModelMultiPanelViewPagerFragment;
import com.oriondev.moneywallet.ui.fragment.multipanel.PersonMultiPanelFragment;
import com.oriondev.moneywallet.ui.fragment.multipanel.PlaceMultiPanelFragment;
import com.oriondev.moneywallet.ui.fragment.multipanel.RecurrenceMultiPanelViewPagerFragment;
import com.oriondev.moneywallet.ui.fragment.multipanel.SavingMultiPanelViewPagerFragment;
import com.oriondev.moneywallet.ui.fragment.multipanel.SettingMultiPanelFragment;
import com.oriondev.moneywallet.ui.fragment.multipanel.TransactionMultiPanelViewPagerFragment;
import com.oriondev.moneywallet.ui.fragment.singlepanel.OverviewSinglePanelFragment;
import com.oriondev.moneywallet.ui.view.theme.ITheme;
import com.oriondev.moneywallet.ui.view.theme.ThemeEngine;
import com.oriondev.moneywallet.ui.view.theme.ThemedDialog;
import com.oriondev.moneywallet.ui.view.theme.ThemedRecyclerView;
import com.oriondev.moneywallet.utils.IconLoader;

import java.util.Locale;

public class MainActivity extends BaseActivity implements DrawerController, ToolbarController, NavigationView.OnNavigationItemSelectedListener, LoaderManager.LoaderCallbacks<Cursor>  {

    private static final String SAVED_SELECTION = "MainActivity::current_selection";

    private static final int LOADER_WALLETS = 1;

    private static final int ID_SECTION_TRANSACTIONS = 0;
    private static final int ID_SECTION_CATEGORIES = 1;
    private static final int ID_SECTION_OVERVIEW = 2;
    private static final int ID_SECTION_DEBTS = 3;
    private static final int ID_SECTION_BUDGETS = 4;
    private static final int ID_SECTION_SAVINGS = 5;
    private static final int ID_SECTION_EVENTS = 6;
    private static final int ID_SECTION_RECURRENCES = 7;
    private static final int ID_SECTION_MODELS = 8;
    private static final int ID_SECTION_PLACES = 9;
    private static final int ID_SECTION_PEOPLE = 10;
    private static final int ID_SECTION_CALCULATOR = 11;
    private static final int ID_SECTION_CONVERTER = 12;
    private static final int ID_SECTION_ATM = 13;
    private static final int ID_SECTION_BANK = 14;
    private static final int ID_SECTION_SETTING = 15;
    private static final int ID_SECTION_SUPPORT_DEVELOPER = 16;
    private static final int ID_SECTION_ABOUT = 17;

    private final static int ID_ACTION_NEW_WALLET = 1;
    private final static int ID_ACTION_MANAGE_WALLET = 2;

    private DrawerLayout mDrawerLayout;
    private NavigationView mNavigationView;
    private ActionBarDrawerToggle mDrawerToggle;

    private long mCurrentSelection;
    private Fragment mCurrentFragment;

    private Cursor mCursor;
    private boolean mShowingWallets = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        initializeUi();
        loadUi(savedInstanceState);
        registerReceiver();
    }

    /**
     * Initialize all the ui elements of the activity.
     */
    private void initializeUi() {
        setContentView(R.layout.activity_root_container);
        initializeNavigationDrawer();
    }

    /**
     * This method must be called during the initialization of the activity in order to
     * setup the account header and the navigation drawer.
     */
    private void initializeNavigationDrawer() {
        mDrawerLayout = findViewById(R.id.drawer_layout);
        mNavigationView = findViewById(R.id.navigation_view);
        mNavigationView.setNavigationItemSelectedListener(this);

        View headerView = mNavigationView.getHeaderView(0);
        if (headerView != null) {
            headerView.setOnClickListener(v -> {
                mShowingWallets = !mShowingWallets;
                updateMenuVisibility();
                ImageView toggle = headerView.findViewById(R.id.header_toggle);
                if (toggle != null) {
                    toggle.animate().rotation(mShowingWallets ? 180 : 0).setDuration(200).start();
                }
            });
        }
    }

    private void updateMenuVisibility() {
        Menu menu = mNavigationView.getMenu();
        if (menu != null) {
            menu.setGroupVisible(R.id.id_section_wallets, mShowingWallets);
            menu.setGroupVisible(R.id.id_section_main, !mShowingWallets);

            MenuItem utils = menu.findItem(R.id.id_section_utilities_parent);
            if (utils != null) utils.setVisible(!mShowingWallets);

            MenuItem more = menu.findItem(R.id.id_section_more_parent);
            if (more != null) more.setVisible(!mShowingWallets);
        }
    }

    @Override
    public void setToolbar(Toolbar toolbar) {
        setSupportActionBar(toolbar);

        mDrawerToggle = new ActionBarDrawerToggle(this, mDrawerLayout, toolbar, R.string.menu_transaction, R.string.menu_transaction);
        mDrawerLayout.addDrawerListener(mDrawerToggle);
        mDrawerToggle.syncState();
    }

    @Override
    public boolean onNavigationItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        long selection = -1;

        if (id == R.id.id_section_transactions) selection = ID_SECTION_TRANSACTIONS;
        else if (id == R.id.id_section_categories) selection = ID_SECTION_CATEGORIES;
        else if (id == R.id.id_section_overview) selection = ID_SECTION_OVERVIEW;
        else if (id == R.id.id_section_debts) selection = ID_SECTION_DEBTS;
        else if (id == R.id.id_section_budgets) selection = ID_SECTION_BUDGETS;
        else if (id == R.id.id_section_savings) selection = ID_SECTION_SAVINGS;
        else if (id == R.id.id_section_events) selection = ID_SECTION_EVENTS;
        else if (id == R.id.id_section_recurrences) selection = ID_SECTION_RECURRENCES;
        else if (id == R.id.id_section_models) selection = ID_SECTION_MODELS;
        else if (id == R.id.id_section_places) selection = ID_SECTION_PLACES;
        else if (id == R.id.id_section_people) selection = ID_SECTION_PEOPLE;
        else if (id == R.id.id_section_calculator) selection = ID_SECTION_CALCULATOR;
        else if (id == R.id.id_section_converter) selection = ID_SECTION_CONVERTER;
        else if (id == R.id.id_section_atm) selection = ID_SECTION_ATM;
        else if (id == R.id.id_section_bank) selection = ID_SECTION_BANK;
        else if (id == R.id.id_section_setting) selection = ID_SECTION_SETTING;
        else if (id == R.id.id_section_support_developer) selection = ID_SECTION_SUPPORT_DEVELOPER;
        else if (id == R.id.id_section_about) selection = ID_SECTION_ABOUT;

        if (selection != -1) {
            handleSelection(selection);
            mDrawerLayout.closeDrawer(GravityCompat.START);
            return true;
        } else if (id >= 1000) {
            // This is a dynamic wallet selection
            long walletId = id - 1000;
            PreferenceManager.setCurrentWallet(this, walletId);
            getSupportLoaderManager().restartLoader(LOADER_WALLETS, null, this);
            mShowingWallets = false;
            updateMenuVisibility();
            View headerView = mNavigationView.getHeaderView(0);
            if (headerView != null) {
                ImageView toggle = headerView.findViewById(R.id.header_toggle);
                if (toggle != null) {
                    toggle.animate().rotation(0).setDuration(200).start();
                }
            }
            mDrawerLayout.closeDrawer(GravityCompat.START);
            return true;
        }
        return false;
    }

    private void loadUi(Bundle savedInstanceState) {
        if (savedInstanceState != null) {
            mCurrentSelection = savedInstanceState.getLong(SAVED_SELECTION);
        } else {
            mCurrentSelection = ID_SECTION_TRANSACTIONS;
        }
        updateNavigationViewSelection(mCurrentSelection);
        handleSelection(mCurrentSelection);
        getSupportLoaderManager().restartLoader(LOADER_WALLETS, null, this);
    }

    private void updateNavigationViewSelection(long selection) {
        int id = -1;
        if (selection == ID_SECTION_TRANSACTIONS) id = R.id.id_section_transactions;
        else if (selection == ID_SECTION_CATEGORIES) id = R.id.id_section_categories;
        else if (selection == ID_SECTION_OVERVIEW) id = R.id.id_section_overview;
        else if (selection == ID_SECTION_DEBTS) id = R.id.id_section_debts;
        else if (selection == ID_SECTION_BUDGETS) id = R.id.id_section_budgets;
        else if (selection == ID_SECTION_SAVINGS) id = R.id.id_section_savings;
        else if (selection == ID_SECTION_EVENTS) id = R.id.id_section_events;
        else if (selection == ID_SECTION_RECURRENCES) id = R.id.id_section_recurrences;
        else if (selection == ID_SECTION_MODELS) id = R.id.id_section_models;
        else if (selection == ID_SECTION_PLACES) id = R.id.id_section_places;
        else if (selection == ID_SECTION_PEOPLE) id = R.id.id_section_people;
        else if (selection == ID_SECTION_CALCULATOR) id = R.id.id_section_calculator;
        else if (selection == ID_SECTION_CONVERTER) id = R.id.id_section_converter;
        else if (selection == ID_SECTION_ATM) id = R.id.id_section_atm;
        else if (selection == ID_SECTION_BANK) id = R.id.id_section_bank;
        else if (selection == ID_SECTION_SETTING) id = R.id.id_section_setting;
        else if (selection == ID_SECTION_SUPPORT_DEVELOPER) id = R.id.id_section_support_developer;
        else if (selection == ID_SECTION_ABOUT) id = R.id.id_section_about;

        if (id != -1) {
            mNavigationView.setCheckedItem(id);
        }
    }

    private void registerReceiver() {
        IntentFilter filter = new IntentFilter(LocalAction.ACTION_BACKUP_SERVICE_FINISHED);
        LocalBroadcastManager.getInstance(this).registerReceiver(mBroadcastReceiver, filter);
    }

    /**
     * Store all the instance information in order to restore them if the activity is recreated.
     * The only information to store here is the current section loaded. The fragment will manage
     * the lifecycle internally, no need to save his state here.
     * @param savedState of the current instance of the activity.
     */
    @Override
    protected void onSaveInstanceState(Bundle savedState) {
        super.onSaveInstanceState(savedState);
        savedState.putLong(SAVED_SELECTION, mCurrentSelection);
    }

    /**
     * Override this method to check when the back button is pressed if the drawer is open.
     * If open it will be closed.
     */
    @Override
    public void onBackPressed() {
        if (mDrawerLayout != null && mDrawerLayout.isDrawerOpen(GravityCompat.START)) {
            mDrawerLayout.closeDrawer(GravityCompat.START);
        } else if (mCurrentFragment instanceof NavigableFragment) {
            if (!((NavigableFragment) mCurrentFragment).navigateBack()) {
                super.onBackPressed();
            }
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mBroadcastReceiver);
    }



    /**
     * Set the lock mode for the activity drawer.
     * @param lockMode to set to the navigation drawer.
     */
    @Override
    public void setDrawerLockMode(int lockMode) {
        mDrawerLayout.setDrawerLockMode(lockMode);
    }

    private void handleSelection(long selection) {
        mCurrentSelection = selection;
        switch ((int) selection) {
            case ID_SECTION_CALCULATOR:
                    startActivity(new Intent(this, CalculatorActivity.class));
                    break;
                case ID_SECTION_CONVERTER:
                    startActivity(new Intent(this, CurrencyConverterActivity.class));
                    break;
                case ID_SECTION_ATM:
                    showAtmSearchDialog();
                    break;
                case ID_SECTION_BANK:
                    showBankSearchDialog();
                    break;
                case ID_SECTION_SUPPORT_DEVELOPER:
                    startActivity(new Intent(this, DonationActivity.class));
                    break;
                case ID_SECTION_ABOUT:
                    startActivity(new Intent(this, AboutActivity.class));
                    break;
                case ID_SECTION_SETTING:
                    loadSection(ID_SECTION_SETTING);
                    break;
                default:
                    loadSection((int) selection);
                    break;
            }
    }

    private void showAtmSearchDialog() {
        ThemedDialog.buildMaterialDialog(this)
                .title(R.string.title_atm_search)
                .input(R.string.hint_atm_name, 0, false, new MaterialDialog.InputCallback() {

                    @Override
                    public void onInput(@NonNull MaterialDialog dialog, CharSequence input) {
                        Uri mapUri = Uri.parse("geo:0,0?q=atm " + input);
                        Intent mapIntent = new Intent(Intent.ACTION_VIEW, mapUri);
                        try {
                            startActivity(mapIntent);
                        } catch (ActivityNotFoundException ignore) {
                            showActivityNotFoundDialog();
                        }
                    }

                }).show();
    }

    private void showBankSearchDialog() {
        ThemedDialog.buildMaterialDialog(this)
                .title(R.string.title_bank_search)
                .input(R.string.hint_bank_name, 0, false, new MaterialDialog.InputCallback() {

                    @Override
                    public void onInput(@NonNull MaterialDialog dialog, CharSequence input) {
                        Uri mapUri = Uri.parse("geo:0,0?q=bank " + input);
                        Intent mapIntent = new Intent(Intent.ACTION_VIEW, mapUri);
                        try {
                            startActivity(mapIntent);
                        } catch (ActivityNotFoundException ignore) {
                            showActivityNotFoundDialog();
                        }
                    }

                }).show();
    }

    private void showActivityNotFoundDialog() {
        ThemedDialog.buildMaterialDialog(this)
                .title(R.string.title_error)
                .content(R.string.message_error_activity_not_found)
                .positiveText(android.R.string.ok)
                .show();
    }

    /**
     * Load the fragment of the specified section inside the frame of the activity.
     * If the fragment is already in the stack of the fragment manager this method will
     * reuse it without spending time in recreating a new one.
     * @param identifier of the section.
     */
    private void loadSection(int identifier) {
        FragmentManager manager = getSupportFragmentManager();
        String tag = getTagById(identifier);
        mCurrentFragment = manager.findFragmentByTag(tag);
        if (mCurrentFragment == null) {
            mCurrentFragment = buildFragmentById(identifier);
        }
        manager.beginTransaction().replace(R.id.fragment_container, mCurrentFragment, tag).commit();
    }

    /**
     * Generate a unique string as tag to identify every fragment into the fragment manager.
     * @param identifier of the drawer item.
     * @return a unique tag.
     */
    private String getTagById(int identifier) {
        return String.format(Locale.ENGLISH, "MainActivity::drawer::%d", identifier);
    }

    /**
     * This method creates a new fragment of the specified section.
     * @param identifier of the section.
     * @return the new created fragment.
     * @throws IllegalArgumentException if the provided id is not a
     * valid section identifier.
     */
    private Fragment buildFragmentById(int identifier) {
        switch (identifier) {
            case ID_SECTION_TRANSACTIONS:
                return new TransactionMultiPanelViewPagerFragment();
            case ID_SECTION_CATEGORIES:
                return new CategoryMultiPanelViewPagerFragment();
            case ID_SECTION_OVERVIEW:
                return new OverviewSinglePanelFragment();
            case ID_SECTION_DEBTS:
                return new DebtMultiPanelViewPagerFragment();
            case ID_SECTION_BUDGETS:
                return new BudgetMultiPanelViewPagerFragment();
            case ID_SECTION_SAVINGS:
                return new SavingMultiPanelViewPagerFragment();
            case ID_SECTION_EVENTS:
                return new EventMultiPanelViewPagerFragment();
            case ID_SECTION_RECURRENCES:
                return new RecurrenceMultiPanelViewPagerFragment();
            case ID_SECTION_MODELS:
                return new ModelMultiPanelViewPagerFragment();
            case ID_SECTION_PLACES:
                return new PlaceMultiPanelFragment();
            case ID_SECTION_PEOPLE:
                return new PersonMultiPanelFragment();
            case ID_SECTION_SETTING:
                return new SettingMultiPanelFragment();
            default:
                throw new IllegalArgumentException("Invalid section id: " + identifier);
        }
    }

    /**
     * Query content resolver to retrieve all wallets from the database.
     * @param id of the loader.
     * @param args bundle of arguments.
     * @return the cursor loader that will retrieve the content from the database.
     */
    @NonNull
    @Override
    public Loader<Cursor> onCreateLoader(int id, Bundle args) {
        String[] projection = new String[] {
                Contract.Wallet.ID,
                Contract.Wallet.NAME,
                Contract.Wallet.ICON,
                Contract.Wallet.COUNT_IN_TOTAL,
                Contract.Wallet.CURRENCY,
                Contract.Wallet.START_MONEY,
                Contract.Wallet.TOTAL_MONEY,
                Contract.Wallet.ARCHIVED
        };
        Uri uri = DataContentProvider.CONTENT_WALLETS;
        String sortOrder = Contract.Wallet.INDEX + " ASC, " + Contract.Wallet.NAME + " ASC";
        return new CursorLoader(this, uri, projection, null, null, sortOrder);
    }

    @Override
    public void onLoadFinished(@NonNull Loader<Cursor> loader, Cursor cursor) {
        mCursor = cursor;
        if (mCursor != null && mCursor.getCount() > 0) {
            long currentWalletId = PreferenceManager.getCurrentWallet();
            if (currentWalletId == PreferenceManager.NO_CURRENT_WALLET) {
                mCursor.moveToFirst();
                PreferenceManager.setCurrentWallet(this, mCursor.getLong(mCursor.getColumnIndex(Contract.Wallet.ID)));
            }
            updateWalletMenu(mCursor);
        }
    }

    private void updateWalletMenu(Cursor cursor) {
        Menu menu = mNavigationView.getMenu();

        // Cleanup previous dynamic items
        menu.removeGroup(R.id.id_section_wallets);

        // Add "Total" wallet
        WalletAccount totalWallet = createTotalWalletAccount(cursor);
        if (totalWallet != null) {
            MenuItem item = menu.add(R.id.id_section_wallets, 1000 + (int)totalWallet.getIdentifier(), 1, totalWallet.getName());
            item.setIcon(totalWallet.getIconRes());
            item.setCheckable(true);
            item.setChecked(PreferenceManager.getCurrentWallet() == totalWallet.getIdentifier());
        }

        // Add individual wallets
        int indexName = cursor.getColumnIndex(Contract.Wallet.NAME);
        int indexId = cursor.getColumnIndex(Contract.Wallet.ID);
        int indexTotal = cursor.getColumnIndex(Contract.Wallet.TOTAL_MONEY);
        int indexInitial = cursor.getColumnIndex(Contract.Wallet.START_MONEY);
        int indexCurrency = cursor.getColumnIndex(Contract.Wallet.CURRENCY);

        for (int i = 0; i < cursor.getCount(); i++) {
            cursor.moveToPosition(i);
            long id = cursor.getLong(indexId);
            String name = cursor.getString(indexName);
            long amount = cursor.getLong(indexTotal) + cursor.getLong(indexInitial);
            String currency = cursor.getString(indexCurrency);

            String formattedAmount = MoneyFormatter.getInstance().getNotTintedString(CurrencyManager.getCurrency(currency), amount);
            String title = name + " (" + formattedAmount + ")";
            MenuItem item = menu.add(R.id.id_section_wallets, 1000 + (int)id, i + 2, title);
            item.setIcon(R.drawable.ic_account_balance_24dp);
            item.setCheckable(true);
            
            if (PreferenceManager.getCurrentWallet() == id) {
                item.setChecked(true);
                updateHeaderSubtitle(title);
            }
        }

        if (totalWallet != null && PreferenceManager.getCurrentWallet() == totalWallet.getIdentifier()) {
            updateHeaderSubtitle(totalWallet.getName());
        }

        updateMenuVisibility();
    }

    private void updateHeaderSubtitle(String subtitleText) {
        View headerView = mNavigationView.getHeaderView(0);
        if (headerView != null) {
            TextView subtitle = headerView.findViewById(R.id.header_subtitle);
            if (subtitle != null) {
                subtitle.setText(subtitleText);
            }
        }
    }


    /**
     * Create a total wallet profile from returned cursor.
     * @param cursor not null that contains all available wallets.
     * @return the total wallet profile if it can be created, null otherwise.
     */
    private WalletAccount createTotalWalletAccount(@NonNull Cursor cursor) {
        Money money = new Money();
        int indexCurrency = mCursor.getColumnIndex(Contract.Wallet.CURRENCY);
        int indexInTotal = mCursor.getColumnIndex(Contract.Wallet.COUNT_IN_TOTAL);
        int indexWalletInitial = mCursor.getColumnIndex(Contract.Wallet.START_MONEY);
        int indexWalletTotal = mCursor.getColumnIndex(Contract.Wallet.TOTAL_MONEY);
        for (int i = 0; i < cursor.getCount(); i++) {
            cursor.moveToPosition(i);
            if (cursor.getInt(indexInTotal) == 1) {
                String currency = cursor.getString(indexCurrency);
                long amount = cursor.getLong(indexWalletInitial) + cursor.getLong(indexWalletTotal);
                money.addMoney(currency, amount);
            }
        }
        if (money.getNumberOfCurrencies() > 0) {
            String name = getString(R.string.total_wallet_name);
            return new WalletAccount()
                    .withIdentifier(0)
                    .withName(name)
                    .withMoney(money)
                    .withIcon(R.drawable.ic_account_balance_24dp);
        }
        return null;
    }

    @Override
    public void onLoaderReset(@NonNull Loader<Cursor> loader) {
        if (mCursor != null) {
            if (!mCursor.isClosed()) {
                mCursor.close();
            }
            mCursor = null;
        }
    }

    @Override
    protected void onThemeSetup(ITheme theme) {
        super.onThemeSetup(theme);
        // Handled by Material 3 theme in XML
    }


    private BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent != null) {
                int action = intent.getIntExtra(BackupWorker.ACTION, 0);
                if (action == BackupWorker.ACTION_RESTORE) {
                    getSupportLoaderManager().restartLoader(LOADER_WALLETS, null, MainActivity.this);
                }
            }
        }

    };
}