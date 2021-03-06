package com.madpixels.tgadmintools.services;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.support.annotation.Nullable;

import com.madpixels.apphelpers.MyLog;
import com.madpixels.apphelpers.Utils;
import com.madpixels.tgadmintools.BuildConfig;
import com.madpixels.tgadmintools.helper.TgH;
import com.madpixels.tgadmintools.db.DBHelper;
import com.madpixels.tgadmintools.entities.BanTask;
import com.madpixels.tgadmintools.helper.TgUtils;
import com.madpixels.tgadmintools.utils.LogUtil;

import org.drinkless.td.libcore.telegram.Client;
import org.drinkless.td.libcore.telegram.TdApi;

import java.util.ArrayList;

/**
 * Created by Snake on 26.02.2016.
 */
public class ServiceUnbanTask extends Service {

    ArrayList<BanTask> mListTasks;

    public static void registerTask(Context context) {
        long ts = DBHelper.getInstance().getBanListFirst();
        if (ts == 0) return;
        if (ts < System.currentTimeMillis()) {
           context.startService(new Intent(context, ServiceUnbanTask.class));
        } else {
            Intent myIntent = new Intent(context, ServiceUnbanTask.class);
            PendingIntent pi = PendingIntent.getService(context, 0, myIntent, 0);

            AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            alarmManager.cancel(pi);
            alarmManager.set(AlarmManager.RTC_WAKEUP, ts + 3000, pi);

            String date = Utils.TimestampToDate((ts+3000)/1000);
            MyLog.log("task registered at: "+date);
        }
    }

    public static void unregister(Context c){
        Intent myIntent = new Intent(c, ServiceUnbanTask.class);
        PendingIntent pi = PendingIntent.getService(c, 0, myIntent, 0);
        AlarmManager alarmManager = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        alarmManager.cancel(pi);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        MyLog.log("ServiceUnbanTask started");
        mListTasks = DBHelper.getInstance().getExpairedBanList();
        if(BuildConfig.DEBUG)
            LogUtil.showLogNotification("Service unban started for planned unban");

        if (mListTasks != null) {
            TgH.init(this, new Client.ResultHandler() {
                @Override
                public void onResult(TdApi.TLObject object) {
                    next();
                }
            });
        }

        return START_STICKY;
    }

    private void next(){
        if(mListTasks.isEmpty()){
            registerTask(this); // регистрием на некст
            stopSelf();
            return;
        }
        BanTask banTask = mListTasks.remove(0);
        if(banTask.isReturnOnUnban)
            unbanAndReturnToChat(banTask);
        else{
            removeFromBanList(banTask);
        }
    }

    private void unbanAndReturnToChat(final BanTask ban) {
        if(TgUtils.isGroup(ban.chatType)){
            DBHelper.getInstance().removeUserFromAutoKick(ban.chat_id, ban.user_id);
        }
        final TdApi.TLFunction fReturnUser = new TdApi.AddChatParticipant(ban.chat_id, ban.user_id, 0);
        TgH.TG().send(fReturnUser, new Client.ResultHandler() {
            @Override
            public void onResult(TdApi.TLObject object) {
                MyLog.log(object.toString());
                if(object.getConstructor()== TdApi.Ok.CONSTRUCTOR) {
                    DBHelper.getInstance().removeBanTask(ban);
                    LogUtil.logAutoUnbanAndReturn(ban);
                }else{
                    if(TgUtils.isError(object)){//DONE если удаление не удалось (например error 3 chat not found) то получается вечный loop
                        TdApi.Error e = (TdApi.Error) object;
                        if(e.code==3){// если чат не найден попробуйем загрузить чаты
                            TgH.send(new TdApi.GetChats(Long.MAX_VALUE, 0, 100), new Client.ResultHandler() {
                                @Override
                                public void onResult(TdApi.TLObject object) {
                                    TgH.send(fReturnUser, new Client.ResultHandler() {
                                        @Override
                                        public void onResult(TdApi.TLObject object) {
                                            if(TgUtils.isError(object))
                                                LogUtil.logAutoUnbanAndReturnError(ban, object.toString());
                                        }
                                    });
                                }
                            });
                        }else //code!=3:
                            LogUtil.logAutoUnbanAndReturnError(ban, object.toString());
                    }
                    DBHelper.getInstance().removeBanTask(ban);
                }
                next();
            }
        });
    }

    private void removeFromBanList(final BanTask ban){
        if(TgUtils.isSuperGroup(ban.chatType)) {
            TdApi.TLFunction f = new TdApi.ChangeChatParticipantRole(ban.chat_id, ban.user_id, new TdApi.ChatParticipantRoleLeft());
            TgH.TG().send(f, new Client.ResultHandler() {
                @Override
                public void onResult(TdApi.TLObject object) {
                    if(TgUtils.isOk(object)) {
                        MyLog.log(object.toString());
                        DBHelper.getInstance().removeBannedUser(ban.chat_id, ban.user_id);
                        LogUtil.logAutoRemoveFromBanList(ban);
                        next();
                    }else{
                        // Load banned users for load user
                        TdApi.TLFunction f1 = new TdApi.GetChannelParticipants(ban.from_id, new TdApi.ChannelParticipantsKicked(), 0, 100);
                        TgH.send(f1);

                        TdApi.TLFunction f = new TdApi.GetUser(ban.user_id);
                        TgH.send(f, new Client.ResultHandler() {
                            @Override
                            public void onResult(TdApi.TLObject object) {
                                if(!TgUtils.isOk(object)) {
                                    DBHelper.getInstance().setUnbanError(ban);
                                    LogUtil.logAutoRemoveFromBanListError(ban, object.toString());
                                }
                                next();
                            }
                        });
                    }
                }
            });
        }else{
            DBHelper.getInstance().removeBannedUser(ban.chat_id, ban.user_id);
            DBHelper.getInstance().removeUserFromAutoKick(ban.chat_id, ban.user_id);
            LogUtil.logAutoRemoveFromBanList(ban);
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
