/*
 * Created by wangzhuozhou on 2017/4/12.
 * Copyright 2015－2019 Sensors Data Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.sensorsdata.analytics.android.sdk;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.ContentObserver;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.widget.Toast;

import com.sensorsdata.analytics.android.sdk.data.DbAdapter;
import com.sensorsdata.analytics.android.sdk.data.DbParams;
import com.sensorsdata.analytics.android.sdk.data.persistent.PersistentFirstDay;
import com.sensorsdata.analytics.android.sdk.data.persistent.PersistentFirstStart;
import com.sensorsdata.analytics.android.sdk.util.AopUtil;
import com.sensorsdata.analytics.android.sdk.util.DateFormatUtils;
import com.sensorsdata.analytics.android.sdk.util.NetworkUtils;
import com.sensorsdata.analytics.android.sdk.util.SensorsDataTimer;
import com.sensorsdata.analytics.android.sdk.util.SensorsDataUtils;

import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;

import static com.sensorsdata.analytics.android.sdk.util.Base64Coder.CHARSET_UTF8;


@TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH)
class SensorsDataActivityLifecycleCallbacks implements Application.ActivityLifecycleCallbacks {
    private static final String TAG = "SA.LifecycleCallbacks";
    private static final String EVENT_TIMER = "event_timer";
    private final SensorsDataAPI mSensorsDataInstance;
    private final PersistentFirstStart mFirstStart;
    private final PersistentFirstDay mFirstDay;
    private Context mContext;
    private boolean resumeFromBackground = false;
    private CountDownTimer mCountDownTimer;
    private DbAdapter mDbAdapter;
    private JSONObject activityProperty = new JSONObject();
    private JSONObject endDataProperty = new JSONObject();
    private boolean isAutoTrackEnabled;
    private boolean isAutoTrackAppEnd;
    private boolean isPaused = false;
    /**
     * 打点时间间隔：2000 毫秒
     */
    private static final int TIME_INTERVAL = 2000;
    private Runnable timer = new Runnable() {
        @Override
        public void run() {
            if (isAutoTrackAppEnd && !isPaused) {
                generateAppEndData();
            }
        }
    };

    SensorsDataActivityLifecycleCallbacks(SensorsDataAPI instance, PersistentFirstStart firstStart,
                                          PersistentFirstDay firstDay, Context context) {
        this.mSensorsDataInstance = instance;
        this.mFirstStart = firstStart;
        this.mFirstDay = firstDay;
        this.mContext = context;
        this.mDbAdapter = DbAdapter.getInstance();
        if (Looper.myLooper() == null) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    Looper.prepare();
                    initTimerAndObserver();
                    Looper.loop();
                }
            }).start();
        } else {
            initTimerAndObserver();
        }
    }

    @Override
    public void onActivityCreated(Activity activity, Bundle bundle) {
        try {
            Uri uri = null;
            if (activity != null) {
                Intent intent = activity.getIntent();
                if (intent != null) {
                    uri = intent.getData();
                }
            }
            if (uri != null) {
                String host = uri.getHost();
                if ("heatmap".equals(host)) {
                    String featureCode = uri.getQueryParameter("feature_code");
                    String postUrl = uri.getQueryParameter("url");
                    showOpenHeatMapDialog(activity, featureCode, postUrl);
                } else if ("debugmode".equals(host)) {
                    String infoId = uri.getQueryParameter("info_id");
                    showDebugModeSelectDialog(activity, infoId);
                } else if ("visualized".equals(host)) {
                    String featureCode = uri.getQueryParameter("feature_code");
                    String postUrl = uri.getQueryParameter("url");
                    showOpenVisualizedAutoTrackDialog(activity, featureCode, postUrl);
                }
            }
        } catch (Exception e) {
            com.sensorsdata.analytics.android.sdk.SALog.printStackTrace(e);
        }
    }

    @Override
    public void onActivityStarted(Activity activity) {
        try {
            isAutoTrackEnabled = mSensorsDataInstance.isAutoTrackEnabled();
            if (!isAutoTrackEnabled) {
                checkFirstDay();
                //先从缓存中读取 SDKConfig
                mSensorsDataInstance.applySDKConfigFromCache();
                //每次启动 App，重新拉取最新的配置信息
                mSensorsDataInstance.pullSDKConfigFromServer();
                return;
            }

            isPaused = false;
            isAutoTrackAppEnd = !mSensorsDataInstance.isAutoTrackEventTypeIgnored(SensorsDataAPI.AutoTrackEventType.APP_END);
            activityProperty = AopUtil.buildTitleAndScreenName(activity);
            SensorsDataUtils.mergeJSONObject(activityProperty, endDataProperty);
            boolean sessionTimeOut = isSessionTimeOut();
            if (sessionTimeOut && !mDbAdapter.getAppEndState()) {
                trackAppEnd(TIME_INTERVAL);
            }

            if (sessionTimeOut || mDbAdapter.getAppEndState()) {
                mDbAdapter.commitAppEndState(false);
                checkFirstDay();
                // XXX: 注意内部执行顺序
                boolean firstStart = mFirstStart.get();

                try {
                    mSensorsDataInstance.appBecomeActive();
                } catch (Exception e) {
                    com.sensorsdata.analytics.android.sdk.SALog.printStackTrace(e);
                }

                //从后台恢复，从缓存中读取 SDK 控制配置信息
                if (resumeFromBackground) {
                    //先从缓存中读取 SDKConfig
                    mSensorsDataInstance.applySDKConfigFromCache();
                    mSensorsDataInstance.resumeTrackScreenOrientation();
//                    mSensorsDataInstance.resumeTrackTaskThread();
                    isAutoTrackEnabled = mSensorsDataInstance.isAutoTrackEnabled();
                }
                //每次启动 App，重新拉取最新的配置信息
                mSensorsDataInstance.pullSDKConfigFromServer();

                try {
                    if (!mSensorsDataInstance.isAutoTrackEventTypeIgnored(SensorsDataAPI.AutoTrackEventType.APP_START)) {
                        if (firstStart) {
                            mFirstStart.commit(false);
                        }
                        JSONObject properties = new JSONObject();
                        properties.put("$resume_from_background", resumeFromBackground);
                        properties.put("$is_first_time", firstStart);
                        SensorsDataUtils.mergeJSONObject(activityProperty, properties);
                        mSensorsDataInstance.track("$AppStart", properties);
                    }

                    if (!mSensorsDataInstance.isAutoTrackEventTypeIgnored(SensorsDataAPI.AutoTrackEventType.APP_END)) {
                        mDbAdapter.commitAppStartTime(SystemClock.elapsedRealtime());
                        TrackTaskManager.getInstance().addTrackEventTask(new Runnable() {
                            @Override
                            public void run() {
                                generateAppEndData();
                            }
                        });
                    }
                } catch (Exception e) {
                    SALog.i(TAG, e);
                }

                if (resumeFromBackground) {
                    try {
                        HeatMapService.getInstance().resume();
                        VisualizedAutoTrackService.getInstance().resume();
                    } catch (Exception e) {
                        com.sensorsdata.analytics.android.sdk.SALog.printStackTrace(e);
                    }
                }

                // 下次启动时，从后台恢复
                resumeFromBackground = true;

            }
        } catch (Exception e) {
            com.sensorsdata.analytics.android.sdk.SALog.printStackTrace(e);
        }
    }

    @Override
    public void onActivityResumed(final Activity activity) {
        try {
            mDbAdapter.commitAppStart(true);

            if (isAutoTrackEnabled && !mSensorsDataInstance.isActivityAutoTrackAppViewScreenIgnored(activity.getClass())
                    && !mSensorsDataInstance.isAutoTrackEventTypeIgnored(SensorsDataAPI.AutoTrackEventType.APP_VIEW_SCREEN)) {
                try {
                    JSONObject properties = new JSONObject();
                    SensorsDataUtils.mergeJSONObject(activityProperty, properties);
                    if (activity instanceof ScreenAutoTracker) {
                        ScreenAutoTracker screenAutoTracker = (ScreenAutoTracker) activity;
                        String screenUrl = screenAutoTracker.getScreenUrl();
                        JSONObject otherProperties = screenAutoTracker.getTrackProperties();
                        if (otherProperties != null) {
                            SensorsDataUtils.mergeJSONObject(otherProperties, properties);
                        }

                        mSensorsDataInstance.trackViewScreen(screenUrl, properties);
                    } else {
                        SensorsDataAutoTrackAppViewScreenUrl autoTrackAppViewScreenUrl = activity.getClass().getAnnotation(SensorsDataAutoTrackAppViewScreenUrl.class);
                        if (autoTrackAppViewScreenUrl != null) {
                            String screenUrl = autoTrackAppViewScreenUrl.url();
                            if (TextUtils.isEmpty(screenUrl)) {
                                screenUrl = activity.getClass().getCanonicalName();
                            }
                            mSensorsDataInstance.trackViewScreen(screenUrl, properties);
                        } else {
                            mSensorsDataInstance.track("$AppViewScreen", properties);
                        }
                    }
                } catch (Exception e) {
                    SALog.i(TAG, e);
                }
            }
            SensorsDataTimer.getInstance().timer(timer, 0, TIME_INTERVAL);
        } catch (Exception e) {
            com.sensorsdata.analytics.android.sdk.SALog.printStackTrace(e);
        }
    }

    @Override
    public void onActivityPaused(Activity activity) {
        if (!isAutoTrackEnabled) {
            return;
        }
        isPaused = true;
        try {
            mCountDownTimer.start();
            mDbAdapter.commitAppStart(false);
            generateAppEndData();
        } catch (Exception e) {
            com.sensorsdata.analytics.android.sdk.SALog.printStackTrace(e);
        }
    }

    @Override
    public void onActivityStopped(Activity activity) {
    }

    @Override
    public void onActivitySaveInstanceState(Activity activity, Bundle bundle) {
    }

    @Override
    public void onActivityDestroyed(Activity activity) {
    }

    /**
     * 发送 $AppEnd 事件
     *
     * @param timeInterval 为了防止在打点的时间间隔时出现 Crash 导致事件序列不对，所以
     * 补发的 $AppEnd 事件的结束时间增加 timeInterval（默认 2s）
     */
    private void trackAppEnd(int timeInterval) {
        if (mDbAdapter.getAppEndState()) {
            return;
        }
        try {
            mSensorsDataInstance.stopTrackScreenOrientation();
            mSensorsDataInstance.resetPullSDKConfigTimer();
            HeatMapService.getInstance().stop();
            VisualizedAutoTrackService.getInstance().stop();
            mSensorsDataInstance.appEnterBackground();
        } catch (Exception e) {
            com.sensorsdata.analytics.android.sdk.SALog.printStackTrace(e);
        }

        if (isAutoTrackAppEnd) {
            try {
                String jsonEndData = mDbAdapter.getAppEndData();
                JSONObject endDataJsonObject = null;
                if (!TextUtils.isEmpty(jsonEndData)) {
                    endDataJsonObject = new JSONObject(jsonEndData);
                    if (endDataJsonObject.has(EVENT_TIMER)) {
                        long startTime = mDbAdapter.getAppStartTime();
                        long endTime = endDataJsonObject.getLong(EVENT_TIMER);
                        EventTimer eventTimer = new EventTimer(TimeUnit.SECONDS, startTime, endTime);
                        SALog.d(TAG, "startTime:" + startTime + "--endTime:" + endTime + "--event_duration:" + eventTimer.duration());
                        mSensorsDataInstance.trackTimer("$AppEnd", eventTimer);
                        endDataJsonObject.remove(EVENT_TIMER);
                    }
                }
                JSONObject properties = new JSONObject();
                if (endDataJsonObject != null) {
                    properties = new JSONObject(endDataJsonObject.toString());
                }

                mSensorsDataInstance.clearLastScreenUrl();
                properties.put("event_time", mDbAdapter.getAppPausedTime() + timeInterval);
                mSensorsDataInstance.track("$AppEnd", properties);
            } catch (Exception e) {
                SALog.i(TAG, e);
            }
        }
        try {
            mDbAdapter.commitAppEndState(true);
        } catch (Exception e) {
            com.sensorsdata.analytics.android.sdk.SALog.printStackTrace(e);
        }
    }

    /**
     * 存储当前的 AppEnd 事件关键信息
     */
    private void generateAppEndData() {
        try {
            endDataProperty.put(EVENT_TIMER, SystemClock.elapsedRealtime());
            mDbAdapter.commitAppEndData(endDataProperty.toString());
            mDbAdapter.commitAppPausedTime(System.currentTimeMillis());
        } catch (Exception e) {
            com.sensorsdata.analytics.android.sdk.SALog.printStackTrace(e);
        }
    }

    /**
     * 判断是否超出 Session 时间间隔
     *
     * @return true 超时，false 未超时
     */
    private boolean isSessionTimeOut() {
        long currentTime = System.currentTimeMillis() > 946656000000L ? System.currentTimeMillis() : 946656000000L;
        boolean sessionTimeOut = Math.abs(currentTime - mDbAdapter.getAppPausedTime()) > mDbAdapter.getSessionIntervalTime();
        SALog.d(TAG, "SessionTimeOut:" + sessionTimeOut);
        return sessionTimeOut;
    }

    private void initTimerAndObserver() {
        initCountDownTimer();
        registerObserver();
    }

    private void initCountDownTimer() {
        mCountDownTimer = new CountDownTimer(mDbAdapter.getSessionIntervalTime(), 10 * 1000) {
            @Override
            public void onTick(long l) {
                SALog.d(TAG, "time:" + l);
            }

            @Override
            public void onFinish() {
                SALog.d(TAG, "timeFinish");
                trackAppEnd(0);
                resumeFromBackground = true;
//                mSensorsDataInstance.stopTrackTaskThread();
                SensorsDataTimer.getInstance().shutdownTimerTask();
            }
        };
    }

    /**
     * 检查 DateFormat 是否为空，如果为空则进行初始化
     */
    private void checkFirstDay() {
        if (mFirstDay.get() == null) {
            mFirstDay.commit(DateFormatUtils.formatTime(System.currentTimeMillis(), DateFormatUtils.YYYY_MM_DD));
        }
    }

    private void registerObserver() {
        final SensorsActivityStateObserver activityStateObserver = new SensorsActivityStateObserver(new Handler(Looper.myLooper()));
        mContext.getContentResolver().registerContentObserver(DbParams.getInstance().getAppStartUri(), false, activityStateObserver);
        mContext.getContentResolver().registerContentObserver(DbParams.getInstance().getSessionTimeUri(), false, activityStateObserver);
    }

    private void showDebugModeSelectDialog(final Activity activity, final String infoId) {
        try {
            DebugModeSelectDialog dialog = new DebugModeSelectDialog(activity, mSensorsDataInstance.getDebugMode());
            dialog.setCanceledOnTouchOutside(false);
            dialog.setOnDebugModeDialogClickListener(new DebugModeSelectDialog.OnDebugModeViewClickListener() {
                @Override
                public void onCancel(Dialog dialog) {
                    dialog.cancel();
                }

                @Override
                public void setDebugMode(Dialog dialog, SensorsDataAPI.DebugMode debugMode) {
                    mSensorsDataInstance.setDebugMode(debugMode);
                    dialog.cancel();
                }
            });
            dialog.setOnCancelListener(new DialogInterface.OnCancelListener() {
                @Override
                public void onCancel(DialogInterface dialog) {
                    //如果当前的调试模式不是 DebugOff ,则发送匿名或登录 ID 给服务端
                    String serverUrl = mSensorsDataInstance.getServerUrl();
                    SensorsDataAPI.DebugMode mCurrentDebugMode = mSensorsDataInstance.getDebugMode();
                    if (mSensorsDataInstance.isNetworkRequestEnable() && !TextUtils.isEmpty(serverUrl) && !TextUtils.isEmpty(infoId) && mCurrentDebugMode != SensorsDataAPI.DebugMode.DEBUG_OFF) {
                        new SendDebugIdThread(serverUrl, mSensorsDataInstance.getDistinctId(), infoId).start();
                    }
                    String currentDebugToastMsg = "";
                    if (mCurrentDebugMode == SensorsDataAPI.DebugMode.DEBUG_OFF) {
                        currentDebugToastMsg = "已关闭调试模式，请重新扫描二维码进行开启";
                    } else if (mCurrentDebugMode == SensorsDataAPI.DebugMode.DEBUG_ONLY) {
                        currentDebugToastMsg = "开启调试模式，校验数据，但不进行数据导入；关闭 App 进程后，将自动关闭调试模式";
                    } else if (mCurrentDebugMode == SensorsDataAPI.DebugMode.DEBUG_AND_TRACK) {
                        currentDebugToastMsg = "开启调试模式，校验数据，并将数据导入到神策分析中；关闭 App 进程后，将自动关闭调试模式";
                    }
                    Toast.makeText(activity, currentDebugToastMsg, Toast.LENGTH_LONG).show();
                    SALog.info(TAG, "您当前的调试模式是：" + mCurrentDebugMode, null);
                }
            });
            dialog.show();
        } catch (Exception e) {
            SALog.printStackTrace(e);
        }
    }

    private void showOpenHeatMapDialog(final Activity context, final String featureCode, final String postUrl) {
        try {
            if (!SensorsDataAPI.sharedInstance().isNetworkRequestEnable()) {
                showNotRequestNetworkDialog(context, "已关闭网络请求（NetworkRequest），无法使用 App 点击分析，请开启后再试！");
                return;
            }
            if (!SensorsDataAPI.sharedInstance().isAppHeatMapConfirmDialogEnabled()) {
                HeatMapService.getInstance().start(context, featureCode, postUrl);
                return;
            }

            boolean isWifi = false;
            try {
                String networkType = NetworkUtils.networkType(context);
                if ("WIFI".equals(networkType)) {
                    isWifi = true;
                }
            } catch (Exception e) {
                SALog.printStackTrace(e);
            }

            AlertDialog.Builder builder = new AlertDialog.Builder(context);
            builder.setTitle("提示");
            if (isWifi) {
                builder.setMessage("正在连接 App 点击分析");
            } else {
                builder.setMessage("正在连接 App 点击分析，建议在 WiFi 环境下使用");
            }
            builder.setCancelable(false);
            builder.setNegativeButton("取消", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {

                }
            });
            builder.setPositiveButton("继续", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    HeatMapService.getInstance().start(context, featureCode, postUrl);
                }
            });
            AlertDialog dialog = builder.create();
            dialog.show();

            try {
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(Color.BLACK);
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Color.RED);
            } catch (Exception e) {
                com.sensorsdata.analytics.android.sdk.SALog.printStackTrace(e);
            }
        } catch (Exception e) {
            SALog.printStackTrace(e);
        }
    }

    private void showOpenVisualizedAutoTrackDialog(final Activity context, final String featureCode, final String postUrl) {
        try {
            if (!SensorsDataAPI.sharedInstance().isNetworkRequestEnable()) {
                showNotRequestNetworkDialog(context, "已关闭网络请求（NetworkRequest），无法使用 App 可视化全埋点，请开启后再试！");
                return;
            }
            if (!SensorsDataAPI.sharedInstance().isVisualizedAutoTrackConfirmDialogEnabled()) {
                VisualizedAutoTrackService.getInstance().start(context, featureCode, postUrl);
                return;
            }

            boolean isWifi = false;
            try {
                String networkType = NetworkUtils.networkType(context);
                if ("WIFI".equals(networkType)) {
                    isWifi = true;
                }
            } catch (Exception e) {
                // ignore
            }

            AlertDialog.Builder builder = new AlertDialog.Builder(context);
            builder.setTitle("提示");
            if (isWifi) {
                builder.setMessage("正在连接 App 可视化全埋点");
            } else {
                builder.setMessage("正在连接 App 可视化全埋点，建议在 WiFi 环境下使用");
            }
            builder.setCancelable(false);
            builder.setNegativeButton("取消", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {

                }
            });
            builder.setPositiveButton("继续", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    VisualizedAutoTrackService.getInstance().start(context, featureCode, postUrl);
                }
            });
            AlertDialog dialog = builder.create();
            dialog.show();

            try {
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(Color.BLACK);
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Color.RED);
            } catch (Exception e) {
                SALog.printStackTrace(e);
            }
        } catch (Exception e) {
            SALog.printStackTrace(e);
        }
    }

    private void showNotRequestNetworkDialog(Context context, String message) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("提示")
                .setMessage(message)
                .setPositiveButton("确定", null).show();
    }

    private class SensorsActivityStateObserver extends ContentObserver {

        /**
         * Creates a content observer.
         *
         * @param handler The handler to run {@link #onChange} on, or null if none.
         */
        SensorsActivityStateObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            super.onChange(selfChange, uri);
            try {
                if (DbParams.getInstance().getAppStartUri().equals(uri)) {
                    if (mCountDownTimer != null) {
                        mCountDownTimer.cancel();
                    }
                } else if (DbParams.getInstance().getSessionTimeUri().equals(uri)) {
                    initCountDownTimer();
                }
            } catch (Exception e) {
                SALog.printStackTrace(e);
            }
        }
    }

    class SendDebugIdThread extends Thread {
        private String distinctId;
        private String infoId;
        private String serverUrl;

        SendDebugIdThread(String serverUrl, String distinctId, String infoId) {
            this.distinctId = distinctId;
            this.infoId = infoId;
            this.serverUrl = serverUrl;
        }

        @Override
        public void run() {
            super.run();
            sendHttpRequest(serverUrl, false);
        }

        private void sendHttpRequest(String serverUrl, boolean isRedirects) {
            ByteArrayOutputStream out = null;
            OutputStream out2 = null;
            BufferedOutputStream bout = null;
            HttpURLConnection connection = null;
            try {
                URL url = new URL(String.format(serverUrl + "&info_id=%s", infoId));
                SALog.info(TAG, String.format("DebugMode URL:%s", url), null);
                connection = (HttpURLConnection) url.openConnection();
                if (connection == null) {
                    SALog.info(TAG, String.format("can not connect %s,shouldn't happen", url.toString()), null);
                    return;
                }
                SSLSocketFactory sf = SensorsDataAPI.sharedInstance().getSSLSocketFactory();
                if (sf != null && connection instanceof HttpsURLConnection) {
                    ((HttpsURLConnection) connection).setSSLSocketFactory(sf);
                }
                connection.setInstanceFollowRedirects(false);
                out = new ByteArrayOutputStream();
                OutputStreamWriter writer = new OutputStreamWriter(out);
                String requestBody = "{\"distinct_id\": \"" + distinctId + "\"}";
                writer.write(requestBody);
                writer.flush();
                SALog.info(TAG, String.format("DebugMode request body : %s", requestBody), null);
                connection.setDoOutput(true);
                connection.setUseCaches(false);
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-type", "text/plain");

                out2 = connection.getOutputStream();
                bout = new BufferedOutputStream(out2);
                bout.write(out.toString().getBytes(CHARSET_UTF8));
                bout.flush();
                out.close();
                int responseCode = connection.getResponseCode();
                SALog.info(TAG, String.format(Locale.CHINA, "DebugMode 后端的响应码是:%d", responseCode), null);
                if (!isRedirects && SensorsDataHttpURLConnectionHelper.needRedirects(responseCode)) {
                    String location = SensorsDataHttpURLConnectionHelper.getLocation(connection, serverUrl);
                    if (!TextUtils.isEmpty(location)) {
                        closeStream(out, out2, bout, connection);
                        sendHttpRequest(location, true);
                    }
                }
            } catch (Exception e) {
                SALog.printStackTrace(e);
            } finally {
                closeStream(out, out2, bout, connection);
            }
        }

        private void closeStream(ByteArrayOutputStream out, OutputStream out2, BufferedOutputStream bout, HttpURLConnection connection) {
            if (out != null) {
                try {
                    out.close();
                } catch (Exception e) {
                    SALog.printStackTrace(e);
                }
            }
            if (out2 != null) {
                try {
                    out2.close();
                } catch (Exception e) {
                    SALog.printStackTrace(e);
                }
            }
            if (bout != null) {
                try {
                    bout.close();
                } catch (Exception e) {
                    SALog.printStackTrace(e);
                }
            }
            if (connection != null) {
                try {
                    connection.disconnect();
                } catch (Exception e) {
                    SALog.printStackTrace(e);
                }
            }
        }
    }
}
