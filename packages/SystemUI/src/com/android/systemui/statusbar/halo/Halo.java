/*
 * Copyright (C) 2013 ParanoidAndroid.
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

package com.android.systemui.statusbar.halo;

import android.app.Activity;
import android.app.ActivityManagerNative;
import android.app.KeyguardManager;
import android.app.PendingIntent;
import android.app.Notification;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.animation.ValueAnimator.AnimatorUpdateListener;
import android.content.Context;
import android.content.ContentResolver;
import android.content.res.Configuration;
import android.graphics.Color;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.Paint;
import android.graphics.Paint.Style;
import android.graphics.Point;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuff.Mode;
import android.graphics.PorterDuffXfermode;
import android.graphics.Matrix;
import android.os.Handler;
import android.os.RemoteException;
import android.os.Vibrator;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.animation.Animation;
import android.view.animation.Animation.AnimationListener;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.AlphaAnimation;
import android.view.animation.TranslateAnimation;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Gravity;
import android.view.GestureDetector;
import android.view.ViewGroup;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.view.View.OnTouchListener;
import android.view.ViewTreeObserver;
import android.view.ViewTreeObserver.OnGlobalLayoutListener;
import android.view.SoundEffectConstants;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ImageView.ScaleType;
import android.widget.TextView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;

import com.android.systemui.R;
import com.android.systemui.statusbar.BaseStatusBar;
import com.android.systemui.statusbar.BaseStatusBar.NotificationClicker;
import com.android.systemui.statusbar.phone.Ticker;

public class Halo extends RelativeLayout implements Ticker.TickerCallback {

	private int id;
    private String appName;

    private Context mContext;
	private LayoutInflater mLayoutInflater;
    private ImageView mIcon;
    private ImageView mFrame;
    private ImageView mBackdrop;
    private TextView mNumber;
    private PackageManager mPm ;
    private Handler mHandler;
    private BaseStatusBar mBar;
    private Notification curNotif;
    private WindowManager mWindowManager;
    private View mRoot;
    private int mIconSize;
    private WindowManager.LayoutParams mTickerPos;
    private boolean isBeingDragged = false;
    private boolean mHapticFeedback;
    private Vibrator mVibrator;
    private LayoutInflater mInflater;
    private HaloEffect mHaloEffect;
    private Display mDisplay;
    private View mContent, mHaloContent;
    private NotificationClicker mContentIntent = null;

    private Paint mPaintHoloBlue = new Paint();
    private Paint mPaintHoloRed = new Paint();

    public static final String TAG = "HaloLauncher";
    private static final boolean DEBUG = true;
    private static final int TICKER_HIDE_TIME = 2000;
    private static final int SLEEP_DELAY_YAWNING = 6000;
    private static final int SLEEP_DELAY_DAYDREAMING = 6000;

	public boolean mExpanded = false;
    public boolean mSnapped = true;
    public boolean mHidden = false;
    public boolean mFirstStart = true;
    private boolean mInitialized = false;
    private boolean mTickerLeft = true;
    private boolean mNumberLeft = true;

    private int mScreenMin, mScreenMax;
    private int mScreenWidth, mScreenHeight;
    private Rect mPopUpRect;

    private Bitmap mXActive, mXNormal;
    private int mKillX, mKillY;

    private ValueAnimator mSleepYawning = ValueAnimator.ofInt(0, 1);
    private ValueAnimator mSleepDaydreaming = ValueAnimator.ofInt(0, 1);
    private AlphaAnimation mSleepNap = new AlphaAnimation(1, 0.5f);
    private int mAnimationFromX;
    private int mAnimationToX;

    public Halo(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public Halo(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        mContext = context;
        mPm = mContext.getPackageManager();
        mWindowManager = (WindowManager)mContext.getSystemService(Context.WINDOW_SERVICE);
        mInflater = (LayoutInflater)mContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE); 
        mHapticFeedback = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0;
        mVibrator = (Vibrator) mContext.getSystemService(Context.VIBRATOR_SERVICE);
        mDisplay = mWindowManager.getDefaultDisplay();
        mHandler = new Handler();
        mRoot = this;

        // Init variables
        mIconSize = mContext.getResources().getDimensionPixelSize(R.dimen.halo_icon_size)
                + mContext.getResources().getDimensionPixelSize(R.dimen.halo_icon_margin) * 2;
        mTickerPos = getWMParams();

        // Init colors
        mPaintHoloBlue.setAntiAlias(true);
        mPaintHoloBlue.setColor(0xff33b5e5);
        mPaintHoloRed.setAntiAlias(true);
        mPaintHoloRed.setColor(0xffcc0000);

        // Bitmaps
        mXActive = BitmapFactory.decodeResource(mContext.getResources(),
                    R.drawable.ic_launcher_clear_active_holo);

        mXNormal = BitmapFactory.decodeResource(mContext.getResources(),
                    R.drawable.ic_launcher_clear_normal_holo);

        // Animations
        mSleepNap.setInterpolator(new DecelerateInterpolator());
        mSleepNap.setFillAfter(true);
        mSleepNap.setDuration(10000);

        mSleepYawning.setDuration(2000);
        mSleepYawning.setInterpolator(new DecelerateInterpolator());
        mSleepYawning.addUpdateListener(new AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                mTickerPos.x = (int)(mAnimationFromX + mAnimationToX * animation.getAnimatedFraction());
                updatePosition();
            }});
        mSleepYawning.addListener(new Animator.AnimatorListener() {
            @Override public void onAnimationCancel(Animator animation) {}
            @Override public void onAnimationEnd(Animator animation) {}
            @Override public void onAnimationRepeat(Animator animation) {}
            @Override public void onAnimationStart(Animator animation) 
            {
                mContent.startAnimation(mSleepNap);
                mAnimationFromX = mTickerPos.x;
                final int setBack = (int)(mIconSize * 0.3f);
                mAnimationToX = (mAnimationFromX < mScreenWidth / 2) ? -setBack : setBack;
            }
        });

        mSleepDaydreaming.setDuration(2000);
        mSleepDaydreaming.setInterpolator(new AccelerateInterpolator());
        mSleepDaydreaming.addUpdateListener(new AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                mTickerPos.x = (int)(mAnimationFromX + mAnimationToX * animation.getAnimatedFraction());
                updatePosition();
            }});
        mSleepDaydreaming.addListener(new Animator.AnimatorListener() {
            @Override public void onAnimationCancel(Animator animation) {}
            @Override public void onAnimationEnd(Animator animation) { }
            @Override public void onAnimationRepeat(Animator animation) {}
            @Override public void onAnimationStart(Animator animation) 
            {
                mAnimationFromX = mTickerPos.x;
                final int setBack = (int)(mIconSize * 0.2f);
                mAnimationToX = (mAnimationFromX < mScreenWidth / 2) ? -setBack : setBack;
            }});

        // Create effect layer
        mHaloEffect = new HaloEffect(mContext);
        mHaloEffect.pingMinRadius = mIconSize / 2;
        mHaloEffect.pingMaxRadius = (int)(mIconSize * 1.5f);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                      WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                      | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                      | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                      | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH
                      | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
              PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.LEFT|Gravity.TOP;
        mWindowManager.addView(mHaloEffect, lp);
    }

    private void initControl() {
        if (mInitialized) return;

        mInitialized = true;

        // Get actual screen size
        mScreenWidth = mHaloEffect.getWidth();
        mScreenHeight = mHaloEffect.getHeight();

        mScreenMin = Math.min(mHaloEffect.getWidth(), mHaloEffect.getHeight());
        mScreenMax = Math.max(mHaloEffect.getWidth(), mHaloEffect.getHeight());

        if (mFirstStart) {
            // let's put halo in sight
            mTickerPos.x = mScreenWidth / 2 - mIconSize / 2;
            mTickerPos.y = mScreenHeight / 2 - mIconSize / 2;

            // pop a hello ping
            mHandler.postDelayed(new Runnable() {
                public void run() {
                    mHaloEffect.causePing(mPaintHoloRed);
                }}, 200);

            // and disappear if empty
            mHandler.postDelayed(new Runnable() {
                public void run() {
                    if (curNotif == null) {
                        AlphaAnimation alphaDown = new AlphaAnimation(1, 0);
                        alphaDown.setFillAfter(true);
                        alphaDown.setDuration(1000);
                        alphaDown.setAnimationListener(new Animation.AnimationListener() {
                            @Override public void onAnimationEnd(Animation animation)
                            {
                                // We hide it at startup, unless the user really wants it.
                                // This state can only by unlocked by an incomming notification
                                if (!isBeingDragged) {
                                    mContent.setVisibility(View.GONE);
                                    mHidden = true;
                                }
                            }
                            @Override public void onAnimationRepeat(Animation animation) {}
                            @Override public void onAnimationStart(Animation animation) {}
                        });
                        mContent.startAnimation(alphaDown);
                    }
                }}, 1000);
        }

        // Update dimensions
        updateConstraints();

        if (!mFirstStart) {
            snapToSide(true);
        } else {
            // Do the startup animations only once
            mFirstStart = false;
        }
    }

    private void wakeUp(boolean pop) {
        // If HALO is hidden, do nothing
        if (mHidden) return;

        unscheduleSleep();

        mContent.setVisibility(View.VISIBLE);

        // First things first, make the bubble visible
        AlphaAnimation alphaUp = new AlphaAnimation(mContent.getAlpha(), 1);
        alphaUp.setFillAfter(true);
        alphaUp.setDuration(250);
        mContent.startAnimation(alphaUp);

        // Then pop
        if (pop) {
            mHaloEffect.causePing(mPaintHoloBlue);
        }
    }

    private void unscheduleSleep() {
        mSleepYawning.cancel();
        mSleepDaydreaming.cancel();
        mSleepNap.cancel();
    }

    private void scheduleSleep(int yawning, int daydreaming) {
        unscheduleSleep();

        mSleepYawning.setStartDelay(yawning);
        mSleepDaydreaming.setStartDelay(daydreaming);

        AnimatorSet set = new AnimatorSet();
        set.play(mSleepYawning).before(mSleepDaydreaming);
        set.start();
    }

    private void snapToSide(boolean sleep) {
        snapToSide(sleep, SLEEP_DELAY_YAWNING, SLEEP_DELAY_DAYDREAMING);
    }

    private void snapToSide(boolean sleep, int yawning, int daydreaming) {
        final int fromX = mTickerPos.x;
        final boolean left = fromX < mScreenWidth / 2;
        final int toX = left ? -fromX : mScreenWidth-fromX-mIconSize;

        ValueAnimator topAnimation = ValueAnimator.ofInt(0, 1);
        topAnimation.addUpdateListener(new AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                mTickerPos.x = (int)(fromX + toX * animation.getAnimatedFraction());
                updatePosition();
            }
        });
        topAnimation.setDuration(150);
        topAnimation.setInterpolator(new AccelerateInterpolator());
        topAnimation.start();

        // Make it fall asleep soon
        if (sleep) scheduleSleep(yawning, yawning);
    }

    private void updatePosition() {
        try {
            mTickerLeft = (mTickerPos.x + mIconSize / 2 < mScreenWidth / 2);
            if (mNumberLeft != mTickerLeft) {
                mNumberLeft = mTickerLeft;
                RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams
                        (RelativeLayout.LayoutParams.WRAP_CONTENT, RelativeLayout.LayoutParams.WRAP_CONTENT );
                params.addRule(mNumberLeft ? RelativeLayout.ALIGN_RIGHT : RelativeLayout.ALIGN_LEFT, mHaloContent.getId());
                mNumber.setLayoutParams(params);
                mHaloEffect.createBubble();
            }

            mWindowManager.updateViewLayout(mRoot, mTickerPos);
            mHaloEffect.invalidate();
        } catch(Exception e) {
            // Probably some animation still looking to move stuff around
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfiguration) {
        super.onConfigurationChanged(newConfiguration);
        mScreenWidth = newConfiguration.orientation == Configuration.ORIENTATION_PORTRAIT
                ? mScreenMin : mScreenMax;
        mScreenHeight = newConfiguration.orientation == Configuration.ORIENTATION_PORTRAIT
                ? mScreenMax : mScreenMin;
        updateConstraints();
        wakeUp(false);
    }

    private void updateConstraints() {
        mKillX = mScreenWidth / 2;
        mKillY = mIconSize / 2;

        final int popupWidth;
        final int popupHeight;

        DisplayMetrics metrics = new DisplayMetrics();
        mDisplay.getMetrics(metrics);

        if (metrics.heightPixels > metrics.widthPixels) {
                popupWidth = (int)(metrics.widthPixels * 0.9f);
                popupHeight = (int)(metrics.heightPixels * 0.7f);
            } else {
                popupWidth = (int)(metrics.widthPixels * 0.7f);
                popupHeight = (int)(metrics.heightPixels * 0.8f);
        }

        mPopUpRect = new Rect(
            (mScreenWidth - popupWidth) / 2,
            (mScreenHeight - popupHeight) / 2,
            mScreenWidth - (mScreenWidth - popupWidth) / 2,
            mScreenHeight - (mScreenHeight - popupHeight) / 2);

        if (mTickerPos.x < 0) mTickerPos.x = 0;
        if (mTickerPos.y < 0) mTickerPos.y = 0;
        if (mTickerPos.x > mScreenWidth-mIconSize) mTickerPos.x = mScreenWidth-mIconSize;
        if (mTickerPos.y > mScreenHeight-mIconSize) mTickerPos.y = mScreenHeight-mIconSize;
        mWindowManager.updateViewLayout(mRoot, mTickerPos);
    }

    public void setStatusBar(BaseStatusBar bar) {
        mBar = bar;
        if (mBar.getTicker() != null) mBar.getTicker().setUpdateEvent(this);
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();

        // Content
        mContent = (View) findViewById(R.id.content);
        mHaloContent = (View) findViewById(R.id.halo_content);
        
        // Icon
		mIcon = (ImageView) findViewById(R.id.app_icon);
        mIcon.setOnClickListener(mIconClicker);
        mIcon.setOnTouchListener(mIconTouchListener);

        // Frame
        mFrame = (ImageView) findViewById(R.id.frame);
        Bitmap frame = Bitmap.createBitmap(mIconSize, mIconSize, Bitmap.Config.ARGB_8888);
        Canvas frameCanvas = new Canvas(frame);
        frameCanvas.drawCircle(mIconSize / 2, mIconSize / 2, (int)mIconSize / 2, mPaintHoloBlue);
        Bitmap hole = Bitmap.createBitmap(mIconSize, mIconSize, Bitmap.Config.ARGB_8888);
        Canvas holeCanvas = new Canvas(hole);
        holeCanvas.drawARGB(0, 0, 0, 0);
        Paint holePaint = new Paint();
        holePaint.setAntiAlias(true);        
        holeCanvas.drawCircle(mIconSize / 2, mIconSize / 2, (int)((mIconSize / 2) * 0.9f), holePaint);
        holePaint.setXfermode(new PorterDuffXfermode(Mode.SRC_OUT));
        final Rect rect = new Rect(0, 0, mIconSize, mIconSize);
        holeCanvas.drawBitmap(frame, null, rect, holePaint);
        mFrame.setImageDrawable(new BitmapDrawable(mContext.getResources(), hole));

        // Background
        mBackdrop = (ImageView) findViewById(R.id.backdrop);
        Bitmap backOutput = Bitmap.createBitmap(mIconSize, mIconSize, Bitmap.Config.ARGB_8888);
        Canvas backCanvas = new Canvas(backOutput);
        final Paint backPaint = new Paint();
        backPaint.setAntiAlias(true);
        backPaint.setColor(0x88000000);
        backCanvas.drawCircle(mIconSize / 2, mIconSize / 2, (int)mIconSize / 2.1f, backPaint);
        mBackdrop.setImageDrawable(new BitmapDrawable(mContext.getResources(), backOutput));

        // Number
        mNumber = (TextView) findViewById(R.id.number);
        mNumber.setVisibility(View.GONE);
    }

    OnClickListener mIconClicker = new OnClickListener() {
        @Override
		public void onClick(View v) {
            if (!isBeingDragged) {
                snapToSide(true, 200, 0);
                playSoundEffect(SoundEffectConstants.CLICK);
                if (mHapticFeedback) mVibrator.vibrate(2);
                try {
                    ActivityManagerNative.getDefault().resumeAppSwitches();
                    ActivityManagerNative.getDefault().dismissKeyguardOnNextActivity();
                } catch (RemoteException e) {
                    // ...
                }

                if (mContentIntent!= null) {
                    mContentIntent.onClick(mRoot);
                }
            }
        }
    };

    OnTouchListener mIconTouchListener = new OnTouchListener() {
        private float initialX = 0;
        private float initialY = 0;
        private boolean overX = false;

		@Override
		public boolean onTouch(View v, MotionEvent event) {
            final int action = event.getAction();
            switch(action) {
                case MotionEvent.ACTION_DOWN:
                    isBeingDragged = false;
                    overX = false;
                    initialX = event.getRawX();
                    initialY = event.getRawY();
                    wakeUp(false);
                    break;
                case MotionEvent.ACTION_UP:
                    boolean oldState = isBeingDragged;
                    isBeingDragged = false;

                    // Do we erase ourselves?
                    if (overX) {
                        Settings.System.putInt(mContext.getContentResolver(),
                                Settings.System.HALO_ACTIVE, 0);
                        return true;
                    }
                    snapToSide(true);
                    return oldState;
                case MotionEvent.ACTION_MOVE:
                    float mX = event.getRawX();
                    float mY = event.getRawY();
                    float distanceX = mKillX-mX;
                    float distanceY = mKillY-mY;
                    float distance = (float)Math.sqrt(Math.pow(distanceX, 2) + Math.pow(distanceY, 2));
                    if (distance < mIconSize) {
                        if (!overX) {
                            if (mHapticFeedback) mVibrator.vibrate(25);
                            mHaloEffect.causePing(mPaintHoloRed);
                            overX = true;
                        }
                    } else {
                            overX = false;
                    }

                    distanceX = initialX-mX;
                    distanceY = initialY-mY;
                    if (!isBeingDragged) {
                        distance = (float)Math.sqrt(Math.pow(distanceX, 2) + Math.pow(distanceY, 2));
                        if (distance > mIconSize * 0.7f) {
                            isBeingDragged = true;
                            if (mHapticFeedback) mVibrator.vibrate(25);
                        }
                    } else {
                        mTickerPos.x = (int)mX - mIconSize / 2;
                        mTickerPos.y = (int)mY - mIconSize / 2;

                    if (mTickerPos.x < 0) mTickerPos.x = 0;
                    if (mTickerPos.y < 0) mTickerPos.y = 0;
                    if (mTickerPos.x > mScreenWidth-mIconSize) mTickerPos.x = mScreenWidth-mIconSize;
                    if (mTickerPos.y > mScreenHeight-mIconSize) mTickerPos.y = mScreenHeight-mIconSize;

                    updatePosition();
                    return false;
                }
                break;
            }
            return false;
        }
    };

    public void cleanUp() {
        // Remove pending tasks, if we can
        unscheduleSleep();
        mHandler.removeCallbacksAndMessages(null);
        // Kill callback
        mBar.getTicker().setUpdateEvent(null);
        // Kill the effect layer
        if (mHaloEffect != null) mWindowManager.removeView(mHaloEffect);
    }

    class HaloEffect extends FrameLayout {
        private Context mContext;
        private Paint mPingPaint;
        private int pingAlpha = 0;
        private int pingRadius = 0;
        protected int pingMinRadius = 0;
        protected int pingMaxRadius = 0;
        private int mContentAlpha = 0;
        private View mContentView;
        private RelativeLayout mTickerContent;
        private TextView mTextViewR, mTextViewL;
        private boolean mPingAllowed = true;

        private Paint mBitmapPaint = new Paint();
        private Bitmap mTickerBitmap;

        private ValueAnimator tickerUp = ValueAnimator.ofInt(0, 1);
        private ValueAnimator tickerDown = ValueAnimator.ofInt(0, 1);
        private ValueAnimator pingAnim = ValueAnimator.ofInt(0, 1);

        public HaloEffect(Context context) {
            super(context);

            mContext = context;
            setWillNotDraw(false);
            setDrawingCacheEnabled(false);

            LayoutInflater inflater = (LayoutInflater)mContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE); 
            mContentView = inflater.inflate(R.layout.halo_content, null);
            mTickerContent = (RelativeLayout) mContentView.findViewById(R.id.ticker);
            mTextViewR = (TextView) mTickerContent.findViewById(R.id.bubble_r);
            mTextViewL = (TextView) mTickerContent.findViewById(R.id.bubble_l);


            tickerUp.setDuration(1000);
            tickerUp.setInterpolator(new DecelerateInterpolator());
            tickerUp.addUpdateListener(new AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                    mContentAlpha = (int)(255 * animation.getAnimatedFraction());
                    invalidate();
                }
            });

            tickerDown.setStartDelay(TICKER_HIDE_TIME);
            tickerDown.setDuration(1000);
            tickerDown.setInterpolator(new DecelerateInterpolator());
            tickerDown.addUpdateListener(new AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                    mContentAlpha = (int)(255 * (1-animation.getAnimatedFraction()));
                    invalidate();
                }
            });

            pingAnim.setDuration(1500);
            pingAnim.setInterpolator(new DecelerateInterpolator());
            pingAnim.addUpdateListener(new AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                    pingAlpha = (int)(200 * (1-animation.getAnimatedFraction()));
                    pingRadius = (int)((pingMaxRadius - pingMinRadius) *
                            animation.getAnimatedFraction()) + pingMinRadius;
                    invalidate();
                }
            });
        }

        @Override
        public void onConfigurationChanged(Configuration newConfiguration) {
            // This will reset the initialization flag
            mInitialized = false;
        }

        @Override
        protected void onLayout (boolean changed, int left, int top, int right, int bottom) {
            super.onLayout (changed, left, top, right, bottom);
            // We have our effect-layer, now let's kickstart HALO
            initControl();
        }

        public void createBubble() {
            mTextViewL.setVisibility(mTickerLeft ? View.VISIBLE : View.GONE);
            mTextViewR.setVisibility(mTickerLeft ? View.GONE : View.VISIBLE);

            mContentView.measure(MeasureSpec.getSize(mContentView.getMeasuredWidth()), MeasureSpec.getSize(mContentView.getMeasuredHeight()));
            mContentView.layout(400, 400, 400, 400);

            mTickerBitmap = Bitmap.createBitmap(mTickerContent.getMeasuredWidth(),
                    mTickerContent.getMeasuredHeight(), Bitmap.Config.ARGB_8888);               
            Canvas frameCanvas = new Canvas(mTickerBitmap);
            mContentView.draw(frameCanvas);
        }

        public void ticker(String tickerText) {
            mTextViewR.setText(tickerText);
            mTextViewL.setText(tickerText);
            createBubble();

            tickerDown.cancel();
            tickerUp.cancel();

            AnimatorSet set = new AnimatorSet();
            set.play(tickerUp).before(tickerDown);
            set.start();
        }

        public void causePing(Paint paint) {
            if (!mPingAllowed && paint != mPaintHoloRed) return;

            mPingAllowed = false;

            mPingPaint = paint;
            pingAnim.cancel();
            pingAnim.start();

            // prevent ping spam            
            mHandler.postDelayed(new Runnable() {
                public void run() {
                    mPingAllowed = true;
                }}, 3000);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int state;

            if (pingAlpha > 0) {
                mPingPaint.setAlpha(pingAlpha);
                canvas.drawCircle(mTickerPos.x + mIconSize / 2, mTickerPos.y + mIconSize / 2, pingRadius, mPingPaint);
            }

            if (mTickerBitmap!= null && mContentAlpha > 0) {
                state = canvas.save();

                int y = mTickerPos.y - (int)(mIconSize * 0.25);
                if (y < 0) y = 0;

                int x = mTickerPos.x + (int)(mIconSize * 1.05f);
                int c = mContentView.getMeasuredWidth();
                if (x > mScreenWidth - c) {
                    x = mScreenWidth - c;
                    if (mTickerPos.x > mScreenWidth - (int)(mIconSize * 1.5f) ) {
                        x = mTickerPos.x - c - (int)(mIconSize*0.05f);
                    }
                }

                mBitmapPaint.setAlpha(mContentAlpha);
                canvas.drawBitmap(mTickerBitmap, x, y, mBitmapPaint);
            }

            if (isBeingDragged) {
                canvas.drawBitmap(mXNormal, mKillX - mXNormal.getWidth() / 2, mKillY, null);
            }
        }
    }

    public WindowManager.LayoutParams getWMParams() {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.LEFT|Gravity.TOP;
        return lp;
    }
    
    public void updateTicker(Ticker.Segment segment) {
        mContentIntent = null;

        final PendingIntent contentIntent = segment.notification.notification.contentIntent;
        if (contentIntent == null) return;

        mContentIntent = mBar.makeClicker(contentIntent,
                segment.notification.pkg, segment.notification.tag, segment.notification.id);
        mContentIntent.makeFloating(true);

        curNotif = segment.notification.notification;
        appName = segment.notification.pkg;

        Bitmap output = Bitmap.createBitmap(mIconSize, mIconSize, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);

        final Paint paint = new Paint();        
        paint.setAntiAlias(true);
        canvas.drawARGB(0, 0, 0, 0);
        Bitmap input = null;
       
        if (curNotif.largeIcon != null) {
            input = curNotif.largeIcon;
            canvas.drawCircle(mIconSize / 2, mIconSize / 2, mIconSize / 2.1f, paint);
            paint.setXfermode(new PorterDuffXfermode(Mode.SRC_IN));
            Rect rect = new Rect(0, 0, mIconSize, mIconSize);
            canvas.drawBitmap(input, null, rect, paint);
        } else {
            try {
                Drawable icon = segment.icon;
                if (icon == null) icon = mPm.getApplicationIcon(appName);
                icon.setBounds((int)(mIconSize * 0.25f), (int)(mIconSize * 0.25f),
                        (int)(mIconSize * 0.75f), (int)(mIconSize * 0.75f));            
                icon.draw(canvas);
            } catch (Exception e) {
                // NameNotFoundException
            }
        }
        mIcon.setImageDrawable(new BitmapDrawable(mContext.getResources(), output));

        if (curNotif.number > 0) {
            mNumber.setVisibility(View.VISIBLE);
            mNumber.setText((curNotif.number < 100) ? String.valueOf(curNotif.number) : "99+");
        } else {
            mNumber.setVisibility(View.GONE);
        }

        mHidden = false;
        wakeUp(true);
        if (!isBeingDragged) snapToSide(true);

        if (segment != null && segment.getText() != null) {
            mHaloEffect.ticker(segment.getText().toString());
        }
    }
}
