/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.example.android.sunshine.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.v4.content.ContextCompat;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.util.Log;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class MyWatchFace extends CanvasWatchFaceService {
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;
    private static final String TAG = "myWatchFace";

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<MyWatchFace.Engine> mWeakReference;

        public EngineHandler(MyWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            MyWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine {
        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        Paint mBackgroundPaint;
        boolean mAmbient;
        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                initFormats();
            }
        };
        int mTapCount;

        float mXOffset;
        float mClockOffset;
        float mDateOffset;
        private float mTempPadding;
        float mWeatherBitmapWidth;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;
        private Calendar mCalendar;
        private Date mDate;
        private SimpleDateFormat mDateFormat;
        private Paint mMinutePaint;
        private Paint mHourPaint;
        private Paint mColonPaint;
        private float mColonWidth;
        private String mColonString=":";
        private Paint mDatePaint;
        private Paint mSepratorPaint;
        private Bitmap mWheatherBitmap=null;
        private Paint mBoldTempPaint;
        private Paint mTempPaint;
        private String maxTemp="";
        private String minTemp="";
        private GoogleApiClient mGoogleApiClient;
        private static final String TEMP_ICON_KEY = "com.example.android.sunshine.key.icon";
        private static final String TEMP_MIN_KEY = "com.example.android.sunshine.key.temp.min";
        private static final String TEMP_MAX_KEY = "com.example.android.sunshine.key.temp.max";


        DataApi.DataListener dataListener = new DataApi.DataListener() {
            @Override
            public void onDataChanged(DataEventBuffer dataEvents) {
                Log.d(TAG,"called...");
                for (DataEvent event : dataEvents) {
                    if (event.getType() == DataEvent.TYPE_CHANGED) {
                        // DataItem changed
                        DataItem item = event.getDataItem();
                        if (item.getUri().getPath().compareTo("/weather") == 0) {
                            DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();
                            minTemp = Math.round(dataMap.getDouble(TEMP_MIN_KEY)) + "°";
                            maxTemp = Math.round(dataMap.getDouble(TEMP_MAX_KEY)) + "°";
                            Asset profileAsset = dataMap.getAsset(TEMP_ICON_KEY);
                            //load bitmap in another thread
                            new AsyncTask<Asset,Void,Bitmap>() {

                                @Override
                                protected Bitmap doInBackground(Asset... profileAsset) {
                                    mWheatherBitmap = loadBitmapFromAsset(profileAsset[0]);
                                    return mWheatherBitmap;
                                }

                                @Override
                                protected void onPostExecute(Bitmap bitmap) {
                                    super.onPostExecute(bitmap);
                                    invalidate();
                                }
                            }.execute(profileAsset);
                        }
                    } else if (event.getType() == DataEvent.TYPE_DELETED) {
                        // DataItem deleted
                    }
                }
            }
        };

        public Bitmap loadBitmapFromAsset(Asset asset) {
            if (asset == null) {
                throw new IllegalArgumentException("Asset must be non-null");
            }

            // convert asset into a file descriptor and block until it's ready
            InputStream assetInputStream = Wearable.DataApi.getFdForAsset(
                    mGoogleApiClient, asset).await().getInputStream();

            if (assetInputStream == null) {
                return null;
            }
            // decode the stream into a bitmap
             return BitmapFactory.decodeStream(assetInputStream);
        }

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(MyWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setHotwordIndicatorGravity(Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());

            final Resources resources = MyWatchFace.this.getResources();
            mClockOffset = resources.getDimension(R.dimen.digital_clock_offset);
            mDateOffset = resources.getDimension(R.dimen.digital_date_offset);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(ContextCompat.getColor(MyWatchFace.this,R.color.primary));

            mDatePaint = new Paint();
            mDatePaint = createTextPaint(resources.getColor(R.color.digital_text));
            mDatePaint.setTextAlign(Paint.Align.CENTER);

            mHourPaint = new Paint();
            mHourPaint = createTextPaint(resources.getColor(R.color.digital_text));
            mHourPaint.setTextAlign(Paint.Align.RIGHT);

            mMinutePaint = new Paint();
            mMinutePaint = createTextPaint(resources.getColor(R.color.digital_text));
            mMinutePaint.setTypeface(Typeface.create(Typeface.DEFAULT,Typeface.NORMAL));

            mBoldTempPaint = new Paint();
            mBoldTempPaint = createTextPaint(resources.getColor(R.color.digital_text));
            mBoldTempPaint.setTypeface(Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD));

            mTempPaint = new Paint();
            mTempPaint = createTextPaint(resources.getColor(R.color.digital_text));

            mSepratorPaint = new Paint();
            mSepratorPaint = createTextPaint(resources.getColor(R.color.digital_text));
            mSepratorPaint.setStrokeWidth(0.5f);

            mColonPaint = new Paint();
            mColonPaint = createTextPaint(resources.getColor(R.color.digital_text));
            mColonPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.NORMAL));
            mColonPaint.setTextAlign(Paint.Align.CENTER);

            mCalendar = Calendar.getInstance();
            mDate = new Date();
            initFormats();

            mGoogleApiClient = new GoogleApiClient.Builder(getApplicationContext())
                    .addConnectionCallbacks(new GoogleApiClient.ConnectionCallbacks() {
                        @Override
                        public void onConnected(Bundle connectionHint) {
                            Log.d(TAG,"connected");
                            Wearable.DataApi.addListener(mGoogleApiClient, dataListener);
                        }

                        @Override
                        public void onConnectionSuspended(int cause) {
                            Log.d(TAG,"connection suspended");
                        }
                    })
                    .addOnConnectionFailedListener(new GoogleApiClient.OnConnectionFailedListener() {
                        @Override
                        public void onConnectionFailed(ConnectionResult result) {
                            Log.d(TAG,result.getErrorCode()+"");
                        }
                    })
                    // Request access only to the Wearable API
                    .addApi(Wearable.API)
                    .build();

            mGoogleApiClient.connect();
        }

        private void initFormats() {
            mDateFormat = new SimpleDateFormat("EEE, MMM dd yyyy", Locale.getDefault());
            mDateFormat.setCalendar(mCalendar);
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mCalendar.setTimeZone(TimeZone.getDefault());
            } else {
                unregisterReceiver();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            MyWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            MyWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = MyWatchFace.this.getResources();
            boolean isRound = insets.isRound();
            mXOffset = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);
            float textSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);
            float tempSize=resources.getDimension(R.dimen.temp_size);
            mTempPadding=resources.getDimensionPixelOffset(R.dimen.temp_padding);
            mWeatherBitmapWidth =resources.getDimension(R.dimen.whether_bmp_width)+mTempPadding;
            mDatePaint.setTextSize(resources.getDimension(R.dimen.digital_date_text_size));
            mHourPaint.setTextSize(textSize);
            mMinutePaint.setTextSize(textSize);
            mColonPaint.setTextSize(textSize);
            mBoldTempPaint.setTextSize(tempSize);
            mTempPaint.setTextSize(tempSize);

            mColonWidth= mColonPaint.measureText(mColonString);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mMinutePaint.setAntiAlias(!inAmbientMode);
                    mHourPaint.setAntiAlias(!inAmbientMode);
                    mBackgroundPaint.setAntiAlias(!inAmbientMode);
                    mBoldTempPaint.setAntiAlias(!inAmbientMode);
                    mTempPaint.setAntiAlias(!inAmbientMode);
                    mColonPaint.setAntiAlias(!inAmbientMode);
                }
                if(mAmbient) {
                    mBackgroundPaint.setColor(Color.BLACK);
                } else {
                    mBackgroundPaint.setColor(ContextCompat.getColor(MyWatchFace.this,R.color.primary));
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        /**
         * Captures tap event (and tap type) and toggles the background color if the user finishes
         * a tap.
         */
        @Override
        public void onTapCommand(int tapType, int x, int y, long eventTime) {
//            Resources resources = MyWatchFace.this.getResources();
//            switch (tapType) {
//                case TAP_TYPE_TOUCH:
//                    // The user has started touching the screen.
//                    break;
//                case TAP_TYPE_TOUCH_CANCEL:
//                    // The user has started a different gesture or otherwise cancelled the tap.
//                    break;
//                case TAP_TYPE_TAP:
//                    // The user has completed the tap gesture.
//                    mTapCount++;
//                    mBackgroundPaint.setColor(resources.getColor(mTapCount % 2 == 0 ?
//                            R.color.background : R.color.background2));
//                    break;
//            }
//            invalidate();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);
            mDate.setTime(now);
            //boolean is24Hour = DateFormat.is24HourFormat(MyWatchFace.this);

            // Draw the background.
            canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);

            float centerX = bounds.centerX();
            float centerY = bounds.centerY();
            String hourString="";
          //  if (is24Hour) {
                hourString = Utility.formatTwoDigitNumber(mCalendar.get(Calendar.HOUR_OF_DAY));
          //  }
            /**
             * not required might use full taken from sample app

             else {
                    int hour = mCalendar.get(Calendar.HOUR);
                    if (hour == 0) {
                        hour = 12;
                    }
                }

             */

            // Draw the hours.
            canvas.drawText(hourString, centerX-mColonWidth, centerY-mClockOffset, mHourPaint);

            //draw colon
            canvas.drawText(mColonString, centerX, centerY-mClockOffset, mColonPaint);

            // Draw the minutes.
            String minuteString = Utility.formatTwoDigitNumber(mCalendar.get(Calendar.MINUTE));
            canvas.drawText(minuteString, centerX+mColonWidth, centerY- mClockOffset, mMinutePaint);

            //draw date
            canvas.drawText(mDateFormat.format(mDate),centerX,centerY- mDateOffset,mDatePaint);

            //draw separator
            float separatorSize=bounds.width()/10;
            canvas.drawLine(centerX-separatorSize,centerY,centerX+separatorSize,centerY,mSepratorPaint);

            float maxTempWidth=mBoldTempPaint.measureText(maxTemp);

            //draw bitmap
            if(mWheatherBitmap!=null && !mAmbient) {
                canvas.drawBitmap(mWheatherBitmap, null, new Rect(
                        (int) (centerX-mWeatherBitmapWidth),
                        (int) (centerY+mDateOffset),
                        (int) (centerX),
                        (int) (centerY+mDateOffset+mWeatherBitmapWidth)
                ), null);
            }

            //draw temp
            canvas.drawText(maxTemp,centerX+mTempPadding,centerY+mClockOffset,mBoldTempPaint);
            canvas.drawText(minTemp,centerX+maxTempWidth+(mTempPadding*2),centerY+mClockOffset,mTempPaint);
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }
    }
}
