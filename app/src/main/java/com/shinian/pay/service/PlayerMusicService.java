package com.shinian.pay.service;

import android.app.Service;
import android.content.Intent;
import android.media.MediaPlayer;
import android.os.IBinder;
import androidx.annotation.Nullable;
import android.util.Log;

import com.shinian.pay.R;
import com.shinian.pay.manager.AppConstants;

/**循环播放一段无声音频，以提升进程优先级
 *
 * Created by jianddongguo on 2017/7/11.
 * http://blog.csdn.net/andrexpert
 */

public class PlayerMusicService extends Service {
    private final static String TAG = "PlayerMusicService";
    private MediaPlayer mMediaPlayer;

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if(AppConstants.DEBUG)
            Log.d(TAG,TAG+"---->onCreate,启动服务");
        mMediaPlayer = MediaPlayer.create(getApplicationContext(), R.raw.service);
        mMediaPlayer.setLooping(true);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                startPlayMusic();
            }
        }).start();
        return START_STICKY;
    }

    private void startPlayMusic(){
        if(mMediaPlayer != null){
            if(AppConstants.DEBUG)
                Log.d(TAG,"启动后台播放音乐");
            mMediaPlayer.start();
        }
    }

    private void stopPlayMusic(){
        if(mMediaPlayer != null){
            if(AppConstants.DEBUG)
                Log.d(TAG,"关闭后台播放音乐");
            try {
                mMediaPlayer.stop();
            } catch (IllegalStateException ignored) {
            }
            // 释放底层资源（旧实现只 stop 不 release，MediaPlayer 及其音频资源泄漏）
            mMediaPlayer.release();
            mMediaPlayer = null;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopPlayMusic();
        if(AppConstants.DEBUG)
            Log.d(TAG,TAG+"---->onDestroy,停止服务");
        // 修复：用户主动退出时不自我复活（与 DaemonService 同样的退出死循环问题）
        if (!AppConstants.IS_USER_EXIT) {
            Intent intent = new Intent(getApplicationContext(),PlayerMusicService.class);
            startService(intent);
        }
    }
}
