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

package com.oriondev.moneywallet.model;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;

import com.oriondev.moneywallet.utils.IconLoader;
import com.oriondev.moneywallet.utils.MoneyFormatter;

/**
 * Created by andrea on 23/01/18.
 */
public class WalletAccount {

    private MoneyFormatter mMoneyFormatter = MoneyFormatter.getInstance();

    private long mIdentifier;
    private String mName;
    private long mId;
    private Money mMoney;
    private Drawable mIconDrawable;
    private int mIconRes = -1;

    public WalletAccount withIdentifier(long identifier) {
        mIdentifier = identifier;
        return this;
    }

    public long getIdentifier() {
        return mIdentifier;
    }

    public WalletAccount withName(String name) {
        mName = name;
        return this;
    }

    public String getName() {
        return mName;
    }

    public String getEmail() {
        return mMoneyFormatter.getNotTintedString(mMoney);
    }

    public WalletAccount withIcon(Context context, Icon icon) {
        Icon safeIcon = icon != null ? icon : IconLoader.UNKNOWN;
        if (safeIcon instanceof VectorIcon) {
            this.mIconRes = ((VectorIcon) safeIcon).getResource(context);
        } else if (safeIcon instanceof ColorIcon) {
            this.mIconDrawable = ((ColorIcon) safeIcon).getDrawable();
        }
        return this;
    }

    public WalletAccount withIcon(Drawable icon) {
        this.mIconDrawable = icon;
        return this;
    }

    public WalletAccount withIcon(@DrawableRes int iconRes) {
        this.mIconRes = iconRes;
        return this;
    }

    public WalletAccount withId(long id) {
        mId = id;
        return this;
    }

    public long getId() {
        return mId;
    }

    public WalletAccount withMoney(String currency, long money) {
        mMoney = new Money(currency, money);
        return this;
    }

    public WalletAccount withMoney(Money money) {
        mMoney = money;
        return this;
    }

    public Money getMoney() {
        return mMoney;
    }

    public int getIconRes() {
        return mIconRes;
    }

    public Drawable getIconDrawable() {
        return mIconDrawable;
    }
}