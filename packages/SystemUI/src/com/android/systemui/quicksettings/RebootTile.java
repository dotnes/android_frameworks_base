/*
 * Copyright (C) 2012 Slimroms
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

package com.android.systemui.quicksettings;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.os.Handler;
import android.os.PowerManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnLongClickListener;

import java.io.DataOutputStream;
import java.io.IOException;

import com.android.systemui.R;
import com.android.systemui.statusbar.phone.QuickSettingsController;
import com.android.systemui.statusbar.phone.QuickSettingsContainerView;

public class RebootTile extends QuickSettingsTile {

    final String[] reboot = {"reboot"};
    final String[] rebootr = {"reboot recovery"};

    public RebootTile(Context context, LayoutInflater inflater,
            QuickSettingsContainerView container,
            final QuickSettingsController qsc, Handler handler) {
        super(context, inflater, container, qsc);

        mLabel = mContext.getString(R.string.quick_settings_reboot);
        mDrawable = R.drawable.ic_qs_reboot;

        mOnClick = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
		try {
			RunAsRoot(reboot);
		} catch (IOException e) {
                        e.printStackTrace();
                }
            }
        };
        mOnLongClick = new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                try {
                        RunAsRoot(rebootr);
                } catch (IOException e) {
                        e.printStackTrace();
                }
                return true;
            }
        };
    }

    public void RunAsRoot(String[] cmds) throws IOException {
        Process p = Runtime.getRuntime().exec("su");
        DataOutputStream os = new DataOutputStream(p.getOutputStream());
        for (String tmpCmd : cmds) {
            os.writeBytes(tmpCmd + "\n");
        }
        os.writeBytes("exit\n");
        os.flush();
    }

}
