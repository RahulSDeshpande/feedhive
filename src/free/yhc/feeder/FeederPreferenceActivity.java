/*****************************************************************************
 *    Copyright (C) 2012 Younghyung Cho. <yhcting77@gmail.com>
 *
 *    This file is part of Feeder.
 *
 *    This program is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU Lesser General Public License as
 *    published by the Free Software Foundation either version 3 of the
 *    License, or (at your option) any later version.
 *
 *    This program is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU Lesser General Public License
 *    (<http://www.gnu.org/licenses/lgpl.html>) for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *****************************************************************************/

package free.yhc.feeder;

import android.os.Bundle;
import android.preference.PreferenceActivity;
import free.yhc.feeder.model.UnexpectedExceptionHandler;

public class FeederPreferenceActivity extends PreferenceActivity implements
UnexpectedExceptionHandler.TrackedModule {
    @Override
    public String
    dump(UnexpectedExceptionHandler.DumpLevel lv) {
        return "[ ChannelSettingActivity ]";
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        UnexpectedExceptionHandler.S().registerModule(this);
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.preference);
    }

    @Override
    protected void
    onDestroy() {
        super.onDestroy();
        UnexpectedExceptionHandler.S().unregisterModule(this);
    }
}