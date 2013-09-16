/*
 * Copyright (C) 2013 The ChameleonOS Project
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
package com.android.systemui.statusbar.policy.porn;

import android.animation.ObjectAnimator;
import android.app.ActivityManagerNative;
import android.app.AlarmManager;
import android.app.INotificationManager;
import android.app.KeyguardManager;
import android.app.KeyguardManager.KeyguardLock;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.PendingIntent.CanceledException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.notification.INotificationListener;
import android.service.notification.StatusBarNotification;
import android.telephony.TelephonyManager;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RemoteViews;

import com.android.internal.widget.multiwaveview.GlowPadView;
import com.android.internal.widget.multiwaveview.GlowPadView.OnTriggerListener;
import com.android.internal.widget.multiwaveview.TargetDrawable;

import com.android.systemui.R;
import com.android.systemui.statusbar.BaseStatusBar;
import com.android.systemui.statusbar.phone.PhoneStatusBar;

import java.util.ArrayList;
import java.util.Calendar;

public class PornView extends FrameLayout {
    private static final boolean DEBUG = false;
    private static final String TAG = "PornView";

    // the following is used for testing purposes
    private static final String ACTION_FORCE_DISPLAY
            = "com.android.systemui.FORCE_DISPLAY";

    private static final String ACTION_REDISPLAY_NOTIFICATION
            = "com.android.systemui.REDISPLAY_NOTIFICATION";

    private static final String ACTION_DISPLAY_TIMEOUT
            = "com.android.systemui.DISPLAY_TIMEOUT";

    private static final int MAX_OVERFLOW_ICONS = 8;

    private static final long DISPLAY_TIMEOUT = 10000L;

    // Targets
    private static final int UNLOCK_TARGET = 0;
    private static final int OPEN_APP_TARGET = 4;
    private static final int DISMISS_TARGET = 6;

    // messages sent to the handler for processing
    private static final int MSG_SHOW_NOTIFICATION_VIEW = 1000;
    private static final int MSG_HIDE_NOTIFICATION_VIEW = 1001;
    private static final int MSG_SHOW_NOTIFICATION      = 1002;
    private static final int MSG_DISMISS_NOTIFICATION   = 1003;

    private BaseStatusBar mBar;
    private GlowPadView mGlowPadView;
    private View mVeil;
    private View mRemoteView;
    private View mClock;
    private FrameLayout mContent;
    private ObjectAnimator mAnim;
    private Drawable mEmptyHandleDrawable;
    private Drawable mNotificationDrawable;
    private int mCreationOrientation;
    private SettingsObserver mSettingsObserver;
    private PowerManager mPM;
    private INotificationManager mNM;
    private INotificationListenerWrapper mNotificationListener;
    private StatusBarNotification mNotification;
    private SensorManager mSensorManager;
    private Sensor mLightSensor;
    private Sensor mProximitySensor;
    private boolean mProximityIsFar = true;
    private LinearLayout mOverflowNotifications;
    private LayoutParams mRemoteViewLayoutParams;
    private int mIconSize;
    private int mIconMargin;
    private int mIconPadding;
    private LinearLayout.LayoutParams mOverflowLayoutParams;
    private KeyguardManager mKeyguardManager;
    private KeyguardLock mKeyguardLock;

    // user customizable settings
    private boolean mDisplayNotifications = false;
    private boolean mDisplayNotificationText = false;
    private boolean mShowAllNotifications = false;
    private boolean mPocketModeEnabled = false;
    private long mRedisplayTimeout = 0;
    private float mInitialBrightness = 1f;

    private class INotificationListenerWrapper extends INotificationListener.Stub {
        @Override
        public void onNotificationPosted(final StatusBarNotification sbn) {
            if (shouldShowNotification() && isValidNotification(sbn)) {
                // need to make sure either the screen is off or the user is currently
                // viewing the notifications
                if (PornView.this.getVisibility() == View.VISIBLE
                        || !mPM.isScreenOn())
                    showNotification(sbn, true);
            }
        }
        @Override
        public void onNotificationRemoved(final StatusBarNotification sbn) {
            if (mNotification != null && sbn.getPackageName().equals(mNotification.getPackageName())) {
                if (getVisibility() == View.VISIBLE) {
                    mNotification = getNextAvailableNotification();
                    if (mNotification != null) {
                        setActiveNotification(mNotification, true);
                        userActivity();
                        return;
                    }
                } else {
                    mNotification = null;
                }
            }
        }
    }

    private OnTriggerListener mOnTriggerListener = new OnTriggerListener() {

        public void onTrigger(final View v, final int target) {
            if (target == UNLOCK_TARGET) {
                mNotification = null;
                hideNotificationView();
                if (!mKeyguardManager.isKeyguardSecure()) {
                    // This is a BUTT ugly hack to allow dismissing the slide lock
                    Intent intent = new Intent(mContext, DummyActivity.class);
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    try {
                        // Dismiss the lock screen when Settings starts.
                        ActivityManagerNative.getDefault().dismissKeyguardOnNextActivity();
                        mContext.startActivityAsUser(intent, new UserHandle(UserHandle.USER_CURRENT));
                    } catch (RemoteException e) {
                    }
                }
            } else if (target == OPEN_APP_TARGET) {
                hideNotificationView();
                if (!mKeyguardManager.isKeyguardSecure()) {
                    try {
                        // Dismiss the lock screen when Settings starts.
                        ActivityManagerNative.getDefault().dismissKeyguardOnNextActivity();
                    } catch (RemoteException e) {
                    }
                }
                launchNotificationPendingIntent();
            } else if (target == DISMISS_TARGET) {
                dismissNotification();
            }
        }

        public void onReleased(final View v, final int handle) {
            doTransition(mOverflowNotifications, 1.0f, 0);
            if (mRemoteView != null) {
                ObjectAnimator.ofFloat(mRemoteView, "alpha", 0f).start();
                ObjectAnimator.ofFloat(mClock, "alpha", 1f).start();
            }
            // user stopped interacting so kick off the timeout timer
            updateTimeoutTimer();
        }

        public void onGrabbed(final View v, final int handle) {
            // prevent the PornView from turning off while user is interacting with it
            cancelTimeoutTimer();
            mVeil.setAlpha(0f);
            doTransition(mOverflowNotifications, 0.0f, 0);
            if (mRemoteView != null) {
                ObjectAnimator.ofFloat(mRemoteView, "alpha", 1f).start();
                ObjectAnimator.ofFloat(mClock, "alpha", 0f).start();
            }
        }

        public void onGrabbedStateChange(final View v, final int handle) {

        }

        public void onFinishFinalAnimation() {

        }

        public void onTargetChange(View v, int target) {

	}

    };

    class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            ContentResolver resolver =
                    PornView.this.mContext.getContentResolver();
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.ENABLE_PORN), false, this);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.PORN_TEXT), false, this);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.PORN_ALL_NOTIFICATIONS), false, this);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.PORN_POCKET_MODE), false, this);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.PORN_REDISPLAY), false, this);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.PORN_BRIGHTNESS), false, this);
            update();
        }

        void unobserve() {
            PornView.this.mContext.getContentResolver()
                    .unregisterContentObserver(this);
        }

        @Override
        public void onChange(boolean selfChange) {
            update();
        }

        public void update() {
            ContentResolver resolver =
                    PornView.this.mContext.getContentResolver();

            mDisplayNotifications = Settings.System.getInt(
                    resolver, Settings.System.ENABLE_PORN, 0) == 1;
            mDisplayNotificationText = Settings.System.getInt(
                    resolver, Settings.System.PORN_TEXT, 0) == 1;
            mShowAllNotifications = Settings.System.getInt(
                    resolver, Settings.System.PORN_ALL_NOTIFICATIONS, 0) == 1;
            mPocketModeEnabled = Settings.System.getInt(
                    resolver, Settings.System.PORN_POCKET_MODE, 0) == 1;
            mRedisplayTimeout = Settings.System.getLong(
                    resolver, Settings.System.PORN_REDISPLAY, 0L);
            mInitialBrightness = Settings.System.getInt(
                    resolver, Settings.System.PORN_BRIGHTNESS, 100) / 100f;

            if (!mDisplayNotifications || mRedisplayTimeout <= 0) {
                cancelRedisplayTimer();
            }
        }
    }

    private Handler mHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(final Message msg) {
            switch (msg.what) {
                case MSG_SHOW_NOTIFICATION_VIEW:
                    handleShowNotificationView();
                    break;
                case MSG_HIDE_NOTIFICATION_VIEW:
                    handleHideNotificationView();
                    break;
                case MSG_SHOW_NOTIFICATION:
                    boolean ping = msg.arg1 == 1;
                    handleShowNotification(ping);
                    break;
                case MSG_DISMISS_NOTIFICATION:
                    handleDismissNotification();
                    break;
                default:
                    break;
            }
        }
    };

    public PornView(Context context) {
        this(context, null);
    }

    public PornView(Context context, AttributeSet attrs) {
        super(context, attrs);

        mSensorManager = (SensorManager) mContext.getSystemService(Context.SENSOR_SERVICE);
        mProximitySensor = mSensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
        // uncomment once we figure out if and when we are going to use the light sensor
        //mLightSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);

        mPM = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
        mNM = INotificationManager.Stub.asInterface(
                ServiceManager.getService(Context.NOTIFICATION_SERVICE));
        mNotificationListener = new INotificationListenerWrapper();

        mIconSize = getResources().getDimensionPixelSize(R.dimen.overflow_icon_size);
        mIconMargin = getResources().getDimensionPixelSize(R.dimen.porn_notification_margin);
        mIconPadding = getResources().getDimensionPixelSize(R.dimen.overflow_icon_padding);

        mKeyguardManager = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);

        mSettingsObserver = new SettingsObserver(new Handler());
        mCreationOrientation = Resources.getSystem().getConfiguration().orientation;
    }

    public void setStatusBar(BaseStatusBar bar) {
        mBar = bar;
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mGlowPadView = (GlowPadView) findViewById(R.id.glow_pad_view);
        mGlowPadView.setOnTriggerListener(mOnTriggerListener);
        mGlowPadView.setDrawOuterRing(false);
        mContent = (FrameLayout) findViewById(R.id.content);
        mVeil = findViewById(R.id.veil);
        mClock = findViewById(R.id.clock_view);

        mOverflowNotifications = (LinearLayout) findViewById(R.id.keyguard_other_notifications);
        mOverflowNotifications.setOnTouchListener(mOverflowTouchListener);

        mEmptyHandleDrawable = getResources().getDrawable(R.drawable.ic_handle_notification_normal);
        mRemoteViewLayoutParams = getRemoteViewLayoutParams(mCreationOrientation);
        mOverflowLayoutParams = getOverflowLayoutParams();
        updateTargets();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        registerNotificationListener();
        registerSensorListener();
        registerBroadcastReceiver();
        mSettingsObserver.observe();
        if (mRedisplayTimeout > 0 && !mPM.isScreenOn()) updateRedisplayTimer();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        unregisterSensorListener();
        unregisterNotificationListener();
        unregisterBroadcastReceiver();
        mSettingsObserver.unobserve();
    }

    private FrameLayout.LayoutParams getRemoteViewLayoutParams(int orientation) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                getResources().getDimensionPixelSize(R.dimen.notification_min_height),
                orientation == Configuration.ORIENTATION_LANDSCAPE ? Gravity.CENTER : Gravity.TOP);
        return lp;
    }

    private LinearLayout.LayoutParams getOverflowLayoutParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                mIconSize,
                mIconSize);
        lp.setMargins(mIconMargin, 0, mIconMargin, 0);
        return lp;
    }

    private StateListDrawable getLayeredDrawable(Drawable back, Drawable front, int inset, boolean frontBlank) {
        Resources res = getResources();
        InsetDrawable[] inactivelayer = new InsetDrawable[2];
        InsetDrawable[] activelayer = new InsetDrawable[2];
        inactivelayer[0] = new InsetDrawable(
                res.getDrawable(com.android.internal.R.drawable.ic_lockscreen_lock_pressed), 0, 0, 0, 0);
        inactivelayer[1] = new InsetDrawable(front, inset, inset, inset, inset);
        activelayer[0] = new InsetDrawable(back, 0, 0, 0, 0);
        activelayer[1] = new InsetDrawable(
                frontBlank ? res.getDrawable(android.R.color.transparent) : front, inset, inset, inset, inset);
        StateListDrawable states = new StateListDrawable();
        LayerDrawable inactiveLayerDrawable = new LayerDrawable(inactivelayer);
        inactiveLayerDrawable.setId(0, 0);
        inactiveLayerDrawable.setId(1, 1);
        LayerDrawable activeLayerDrawable = new LayerDrawable(activelayer);
        activeLayerDrawable.setId(0, 0);
        activeLayerDrawable.setId(1, 1);
        states.addState(TargetDrawable.STATE_INACTIVE, inactiveLayerDrawable);
        states.addState(TargetDrawable.STATE_ACTIVE, activeLayerDrawable);
        states.addState(TargetDrawable.STATE_FOCUSED, activeLayerDrawable);
        return states;
    }

    private void updateTargets() {
        updateResources();
    }

    public void updateResources() {
        ArrayList<TargetDrawable> storedDraw = new ArrayList<TargetDrawable>();
        final Resources res = getResources();
        final int targetInset = res.getDimensionPixelSize(com.android.internal.R.dimen.lockscreen_target_inset);
        final boolean isLandscape = mCreationOrientation == Configuration.ORIENTATION_LANDSCAPE;
        final Drawable blankActiveDrawable =
                res.getDrawable(com.android.internal.R.drawable.ic_lockscreen_target_activated);
        final InsetDrawable activeBack = new InsetDrawable(blankActiveDrawable, 0, 0, 0, 0);

        // Add unlock target
        storedDraw.add(new TargetDrawable(res, res.getDrawable(com.android.internal.R.drawable.ic_lockscreen_unlock)));
        if (mNotificationDrawable != null) {
            storedDraw.add(new TargetDrawable(res, null));
            storedDraw.add(new TargetDrawable(res, null));
            storedDraw.add(new TargetDrawable(res, null));
            storedDraw.add(new TargetDrawable(res, getLayeredDrawable(activeBack,
                    mNotificationDrawable, targetInset, true)));
            storedDraw.add(new TargetDrawable(res, null));
            storedDraw.add(new TargetDrawable(res, res.getDrawable(R.drawable.ic_pornview_dismiss_notification)));
        }
        storedDraw.add(new TargetDrawable(res, null));
        mGlowPadView.setTargetResources(storedDraw);
    }

    void doTransition(View view, float to, long duration) {
        if (mAnim != null) {
            mAnim.cancel();
        }
        mAnim = ObjectAnimator.ofFloat(view, "alpha", to);
        if (duration > 0) mAnim.setDuration(duration);
        mAnim.start();
    }

    public void launchNotificationPendingIntent() {
        if (mNotification != null) {
            PendingIntent contentIntent = mNotification.getNotification().contentIntent;
            if (contentIntent != null) {
                try {
                    contentIntent.send();
                } catch (CanceledException e) {
                }
            }
            mNotification = null;
        }
    }

    public boolean hasPendingNotification() {
        return mDisplayNotifications && mNotification != null;
    }

    private void showNotificationView() {
        mHandler.removeMessages(MSG_SHOW_NOTIFICATION_VIEW);
        mHandler.sendEmptyMessage(MSG_SHOW_NOTIFICATION_VIEW);
    }

    private void hideNotificationView() {
        mHandler.removeMessages(MSG_HIDE_NOTIFICATION_VIEW);
        mHandler.sendEmptyMessage(MSG_HIDE_NOTIFICATION_VIEW);
    }

    private void showNotification(StatusBarNotification sbn, boolean ping) {
        mNotification = sbn;
        Message msg = new Message();
        msg.what = MSG_SHOW_NOTIFICATION;
        msg.arg1 = ping ? 1 : 0;
        mHandler.removeMessages(MSG_SHOW_NOTIFICATION);
        mHandler.sendMessage(msg);
    }

    private void dismissNotification() {
        mHandler.removeMessages(MSG_DISMISS_NOTIFICATION);
        mHandler.sendEmptyMessage(MSG_DISMISS_NOTIFICATION);
    }

    private void handleShowNotificationView() {
        if (mKeyguardLock == null) {
            mKeyguardLock = mKeyguardManager.newKeyguardLock("porn");
            mKeyguardLock.disableKeyguard();
        }
        setVisibility(View.VISIBLE);
        if (mBar instanceof PhoneStatusBar) {
            ((PhoneStatusBar) mBar).setNavigationBarLightsOn(false);
        } else {
            mBar.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LOW_PROFILE, View.SYSTEM_UI_FLAG_LOW_PROFILE);
        }
    }

    private void handleHideNotificationView() {
        if (mKeyguardLock != null) {
            mKeyguardLock.reenableKeyguard();
            mKeyguardLock = null;
        }
        setVisibility(View.GONE);
        mVeil.setAlpha(1f);
        if (mBar instanceof PhoneStatusBar) {
            ((PhoneStatusBar) mBar).setNavigationBarLightsOn(true);
        } else {
            mBar.setSystemUiVisibility(0, View.SYSTEM_UI_FLAG_LOW_PROFILE);
        }
        cancelTimeoutTimer();
    }

    private void handleShowNotification(boolean ping) {
        if (!mDisplayNotifications) return;
        handleShowNotificationView();
        setActiveNotification(mNotification, true);
        inflateRemoteView(mNotification);
        if (!mPM.isScreenOn()) {
            // to avoid flicker and showing any other screen than the PornView
            // we use a runnable posted with a 250ms delay to turn wake the device
            mHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    wakeDevice();
                    doTransition(mVeil, 1f - mInitialBrightness, 1000);
                }
            }, 250);
        }
        if (ping) mGlowPadView.ping();
    }

    private void handleDismissNotification() {
        try {
            mNM.cancelNotificationFromListener(mNotificationListener,
                    mNotification.getPackageName(), mNotification.getTag(),
                    mNotification.getId());

        } catch (RemoteException e) {
        }
        mNotification = getNextAvailableNotification();
        if (mNotification != null) {
            setActiveNotification(mNotification, true);
            userActivity();
            return;
        }

        // no other notifications to display so turn screen off
        turnScreenOff();
    }

    private void onScreenTurnedOn() {
        cancelRedisplayTimer();
    }

    private void onScreenTurnedOff() {
        hideNotificationView();
        cancelTimeoutTimer();
        if (mRedisplayTimeout > 0) updateRedisplayTimer();
    }

    private void turnScreenOff() {
        mPM.goToSleep(SystemClock.uptimeMillis());
    }

    private void userActivity() {
        mVeil.setAlpha(0f);
        updateTimeoutTimer();
    }

    private void registerBroadcastReceiver() {
        IntentFilter filter = new IntentFilter(ACTION_REDISPLAY_NOTIFICATION);
        filter.addAction(ACTION_DISPLAY_TIMEOUT);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        // uncomment the line below for testing
        filter.addAction(ACTION_FORCE_DISPLAY);
        mContext.registerReceiver(mBroadcastReceiver, filter);
    }

    private void unregisterBroadcastReceiver() {
        mContext.unregisterReceiver(mBroadcastReceiver);
    }

    private void registerNotificationListener() {
        ComponentName cn = new ComponentName("android", "");
        try {
            mNM.registerListener(mNotificationListener, cn, UserHandle.USER_ALL);
        } catch (RemoteException e) {
            Log.e(TAG, "registerNotificationListener()", e);
        }
    }

    private void unregisterNotificationListener() {
        if (mNotificationListener !=  null) {
            try {
                mNM.unregisterListener(mNotificationListener, UserHandle.USER_ALL);
            } catch (RemoteException e) {
                Log.e(TAG, "registerNotificationListener()", e);
            }
        }
    }

    private void registerSensorListener() {
        if (mProximitySensor != null)
            mSensorManager.registerListener(mSensorListener, mProximitySensor, SensorManager.SENSOR_DELAY_UI);
        if (mLightSensor != null)
            mSensorManager.registerListener(mSensorListener, mLightSensor, SensorManager.SENSOR_DELAY_UI);
    }

    private void unregisterSensorListener() {
        if (mProximitySensor != null)
            mSensorManager.unregisterListener(mSensorListener, mProximitySensor);
        if (mLightSensor != null)
            mSensorManager.unregisterListener(mSensorListener, mLightSensor);
    }

    private StatusBarNotification getNextAvailableNotification() {
        try {
            // check if other clearable notifications exist and if so display the next one
            StatusBarNotification[] sbns = mNM
                    .getActiveNotificationsFromListener(mNotificationListener);
            if (sbns == null) return null;
            for (int i = sbns.length - 1; i >= 0; i--) {
                if (shouldShowNotification() && isValidNotification(sbns[i])) {
                    return sbns[i];
                }
            }
        } catch (RemoteException e) {
        }

        return null;
    }

    private void updateOtherNotifications() {
        mOverflowNotifications.post(new Runnable() {
            @Override
            public void run() {
                try {
                    // check if other clearable notifications exist and if so display the next one
                    StatusBarNotification[] sbns = mNM
                            .getActiveNotificationsFromListener(mNotificationListener);
                    mOverflowNotifications.removeAllViews();
                    for (int i = sbns.length - 1; i >= 0; i--) {
                        if (isValidNotification(sbns[i])
                                && mOverflowNotifications.getChildCount() < MAX_OVERFLOW_ICONS) {
                            ImageView iv = new ImageView(mContext);
                            if (mOverflowNotifications.getChildCount() < (MAX_OVERFLOW_ICONS - 1)) {
                                Context pkgContext = mContext.createPackageContext(
                                        sbns[i].getPackageName(), Context.CONTEXT_RESTRICTED);
                                iv.setImageDrawable(pkgContext.getResources()
                                        .getDrawable(sbns[i].getNotification().icon));
                                iv.setTag(sbns[i]);
                                if (sbns[i].getId() == mNotification.getId()) {
                                    iv.setBackgroundResource(R.drawable.porn_active_notification_background);
                                } else {
                                    iv.setBackgroundResource(0);
                                }
                            } else {
                                iv.setImageResource(R.drawable.ic_pornview_morenotifications_holo_dark);
                            }
                            iv.setPadding(mIconPadding, mIconPadding, mIconPadding, mIconPadding);
                            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                            mOverflowNotifications.addView(iv, mOverflowLayoutParams);
                        }
                    }
                } catch (RemoteException re) {
                } catch (NameNotFoundException nnfe) {
                }
            }
        });
    }

    private OnTouchListener mOverflowTouchListener = new OnTouchListener() {
        int mLastChildPosition = -1;
        @Override
        public boolean onTouch(View v, MotionEvent event) {
            int action = event.getActionMasked();
            switch (action) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_MOVE:
                    float x = event.getX();
                    float y = event.getY();
                    final int childCount = mOverflowNotifications.getChildCount();
                    Rect hitRect = new Rect();
                    for (int i = 0; i < childCount; i++) {
                        final ImageView iv = (ImageView) mOverflowNotifications.getChildAt(i);
                        final StatusBarNotification sbn = (StatusBarNotification) iv.getTag();
                        iv.getHitRect(hitRect);
                        if (i != mLastChildPosition ) {
                            if (hitRect.contains((int)x, (int)y)) {
                                mLastChildPosition = i;
                                if (sbn != null && sbn.getId() != mNotification.getId()) {
                                    swapNotification(sbn);
                                    iv.setBackgroundResource(R.drawable.porn_active_notification_background);
                                }
                            } else {
                                iv.setBackgroundResource(0);
                            }
                        } else {
                            iv.setBackgroundResource(R.drawable.porn_active_notification_background);
                        }
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    mLastChildPosition = -1;
                    inflateRemoteView(mNotification);
                    break;
            }
            userActivity();
            return true;
        }
    };

    private void swapNotification(StatusBarNotification sbn) {
        mNotification = sbn;
        setActiveNotification(sbn, false);
    }

    /**
     * Determine if a given notification should be used.
     * @param sbn StatusBarNotification to check.
     * @return True if it should be used, false otherwise.
     */
    private boolean isValidNotification(StatusBarNotification sbn) {
        return (sbn.isClearable() || mShowAllNotifications);
    }

    /**
     * Determine if we should show notifications or not.
     */
    private boolean shouldShowNotification() {
        return mProximityIsFar;
    }

    private void wakeDevice() {
        mPM.wakeUp(SystemClock.uptimeMillis());
        userActivity();
    }

    private void setActiveNotification(final StatusBarNotification sbn, final boolean updateOthers) {
        try {
            Context pkgContext = mContext.createPackageContext(sbn.getPackageName(), Context.CONTEXT_RESTRICTED);
            mNotificationDrawable = pkgContext.getResources().getDrawable(sbn.getNotification().icon);
            TargetDrawable nDrawable = new TargetDrawable(getResources(),
                    createLockHandle(mNotificationDrawable));
            mGlowPadView.setHandleDrawable(nDrawable);
            setHandleText(sbn);
            mGlowPadView.post(new Runnable() {
                @Override
                public void run() {
                    updateResources();
                    mGlowPadView.invalidate();
                    if (updateOthers) updateOtherNotifications();
                }
            });
        } catch (NameNotFoundException e) {
        }
    }

    private void inflateRemoteView(StatusBarNotification sbn) {
        final Notification notification = sbn.getNotification();
        boolean useBigContent = notification.bigContentView != null;
        RemoteViews rv = useBigContent ? notification.bigContentView : notification.contentView;
        if (rv != null) {
            if (mRemoteView != null) mContent.removeView(mRemoteView);
            if (useBigContent)  {
                rv.removeAllViews(com.android.internal.R.id.actions);
                rv.setViewVisibility(com.android.internal.R.id.action_divider, View.GONE);
                mRemoteViewLayoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            } else {
                mRemoteViewLayoutParams.height = getResources().getDimensionPixelSize(R.dimen.notification_min_height);
            }
            mRemoteView = rv.apply(mContext, null);
            mRemoteView.setAlpha(0f);
            mContent.addView(mRemoteView, mRemoteViewLayoutParams);
        }
    }

    private void setHandleText(StatusBarNotification sbn) {
        final Notification notificiation = sbn.getNotification();
        CharSequence tickerText = mDisplayNotificationText ? notificiation.tickerText
                : "";
        if (tickerText == null) {
            Bundle extras = notificiation.extras;
            if (extras != null)
                tickerText = extras.getCharSequence(Notification.EXTRA_TITLE, null);
        }
        mGlowPadView.setHandleText(tickerText != null ? tickerText.toString() : "");
    }

    private Drawable createNotificationLockHandle(Drawable notification) {
        int inset = (mEmptyHandleDrawable.getIntrinsicWidth() - notification.getIntrinsicWidth()) / 2;
        LayerDrawable layers = new LayerDrawable(new Drawable[] {mEmptyHandleDrawable, notification});
        layers.setLayerInset(1,
                inset,
                inset,
                inset,
                inset);
        return layers;
    }

    private Drawable createLockHandle(Drawable notification) {
        Drawable normal = createNotificationLockHandle(notification);
        StateListDrawable stateListDrawable = new StateListDrawable();
        stateListDrawable.addState(TargetDrawable.STATE_INACTIVE, normal);
        stateListDrawable.addState(TargetDrawable.STATE_ACTIVE, normal);
        stateListDrawable.addState(TargetDrawable.STATE_FOCUSED, normal);
        return stateListDrawable;
    }

    private SensorEventListener mSensorListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            float value = event.values[0];
            if (event.sensor.equals(mProximitySensor)) {
                if (value >= mProximitySensor.getMaximumRange()) {
                    mProximityIsFar = true;
                    if (!mPM.isScreenOn() && mPocketModeEnabled && !isOnCall()) {
                        mNotification = getNextAvailableNotification();
                        if (mNotification != null) showNotification(mNotification, true);
                    }
                } else {
                    mProximityIsFar = false;
                }
            } else if (event.sensor.equals(mLightSensor)) {
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
        }
    };

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_REDISPLAY_NOTIFICATION.equals(action)) {
                mNotification = getNextAvailableNotification();
                if (mNotification != null) showNotification(mNotification, true);
            } else if (ACTION_DISPLAY_TIMEOUT.equals(action)) {
                turnScreenOff();
            } else if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                onScreenTurnedOff();
            } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                onScreenTurnedOn();
            } else if (ACTION_FORCE_DISPLAY.equals(action)) {
                mNotification = getNextAvailableNotification();
                if (mNotification != null) showNotification(mNotification, true);
                mVeil.setAlpha(0f);
            }
        }
    };

    private boolean isOnCall() {
        TelephonyManager tm = (TelephonyManager) mContext.getSystemService(Context.TELEPHONY_SERVICE);
        return tm.getCallState() != TelephonyManager.CALL_STATE_IDLE;
    }

    public void updateRedisplayTimer() {
        AlarmManager am = (AlarmManager)mContext.getSystemService(Context.ALARM_SERVICE);
        Intent i = new Intent(ACTION_REDISPLAY_NOTIFICATION);

        PendingIntent pi = PendingIntent.getBroadcast(mContext, 0, i, PendingIntent.FLAG_UPDATE_CURRENT);
        try {
            am.cancel(pi);
        } catch (Exception e) {
        }
        Calendar time = Calendar.getInstance();
        time.setTimeInMillis(System.currentTimeMillis() + mRedisplayTimeout);
        am.set(AlarmManager.RTC, time.getTimeInMillis(), pi);
    }

    public void cancelRedisplayTimer() {
        AlarmManager am = (AlarmManager)mContext.getSystemService(Context.ALARM_SERVICE);
        Intent i = new Intent(ACTION_REDISPLAY_NOTIFICATION);

        PendingIntent pi = PendingIntent.getBroadcast(mContext, 0, i, PendingIntent.FLAG_UPDATE_CURRENT);
        try {
            am.cancel(pi);
        } catch (Exception e) {
        }
    }

    public void updateTimeoutTimer() {
        AlarmManager am = (AlarmManager)mContext.getSystemService(Context.ALARM_SERVICE);
        Intent i = new Intent(ACTION_DISPLAY_TIMEOUT);

        PendingIntent pi = PendingIntent.getBroadcast(mContext, 0, i, PendingIntent.FLAG_UPDATE_CURRENT);
        try {
            am.cancel(pi);
        } catch (Exception e) {
        }
        Calendar time = Calendar.getInstance();
        time.setTimeInMillis(System.currentTimeMillis() + DISPLAY_TIMEOUT);
        am.set(AlarmManager.RTC, time.getTimeInMillis(), pi);
    }

    public void cancelTimeoutTimer() {
        AlarmManager am = (AlarmManager)mContext.getSystemService(Context.ALARM_SERVICE);
        Intent i = new Intent(ACTION_DISPLAY_TIMEOUT);

        PendingIntent pi = PendingIntent.getBroadcast(mContext, 0, i, PendingIntent.FLAG_UPDATE_CURRENT);
        try {
            am.cancel(pi);
        } catch (Exception e) {
        }
    }
}
