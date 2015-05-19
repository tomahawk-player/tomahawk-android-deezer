/* == This file is part of Tomahawk Player - <http://tomahawk-player.org> ===
 *
 *   Copyright 2015, Enno Gottschalk <mrmaffen@googlemail.com>
 *
 *   Tomahawk is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   Tomahawk is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with Tomahawk. If not, see <http://www.gnu.org/licenses/>.
 */
package org.tomahawk.deezerplugin;

import com.deezer.sdk.network.connect.DeezerConnect;
import com.deezer.sdk.network.request.event.DeezerError;
import com.deezer.sdk.player.TrackPlayer;
import com.deezer.sdk.player.event.OnBufferErrorListener;
import com.deezer.sdk.player.event.OnPlayerErrorListener;
import com.deezer.sdk.player.event.OnPlayerStateChangeListener;
import com.deezer.sdk.player.event.PlayerState;
import com.deezer.sdk.player.exception.TooManyPlayersExceptions;
import com.deezer.sdk.player.networkcheck.WifiAndMobileNetworkStateChecker;

import org.tomahawk.aidl.IPluginService;
import org.tomahawk.aidl.IPluginServiceCallback;

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.util.Log;

import java.util.concurrent.RejectedExecutionException;

public class DeezerService extends Service {

    // Used for debug logging
    private static final String TAG = DeezerService.class.getSimpleName();

    public final static String APP_ID = "";

    private TrackPlayer mPlayer;

    /**
     * This is a list of callbacks that have been registered with the service.  Note that this is
     * package scoped (instead of private) so that it can be accessed more efficiently from inner
     * classes.
     */
    final RemoteCallbackList<IPluginServiceCallback> mCallbacks = new RemoteCallbackList<>();

    /**
     * The IRemoteInterface is defined through IDL
     */
    private final IPluginService.Stub mBinder = new IPluginService.Stub() {

        @Override
        public void registerCallback(IPluginServiceCallback cb) {
            if (cb != null) {
                mCallbacks.register(cb);
            }
        }

        @Override
        public void unregisterCallback(IPluginServiceCallback cb) {
            if (cb != null) {
                mCallbacks.unregister(cb);
            }
        }

        @Override
        public void prepare(String uri, String accessToken, String accessTokenSecret,
                long accessTokenExpires) throws RemoteException {
            DeezerConnect deezerConnect = new DeezerConnect(APP_ID);
            deezerConnect.setAccessToken(getApplicationContext(), accessToken);
            deezerConnect.setAccessExpires(accessTokenExpires);
            try {
                if (mPlayer == null
                        || mPlayer.getPlayerState() == PlayerState.RELEASED) {
                    mPlayer = new TrackPlayer(getApplication(), deezerConnect,
                            new WifiAndMobileNetworkStateChecker());
                    mPlayer.addOnBufferErrorListener(mPlayerHandler);
                    mPlayer.addOnPlayerErrorListener(mPlayerHandler);
                    mPlayer.addOnPlayerStateChangeListener(mPlayerHandler);
                }
            } catch (TooManyPlayersExceptions | DeezerError e) {
                Log.e(TAG, "<init>: " + e.getClass() + ": " + e
                        .getLocalizedMessage());
            }
            synchronized (this) {
                mPlayer.playTrack(Long.valueOf(uri));
            }
        }

        @Override
        public void play() throws RemoteException {
            if (mPlayer != null && mPlayer.getPlayerState() != PlayerState.RELEASED) {
                synchronized (this) {
                    if (mPlayer.getPlayerState() != PlayerState.WAITING_FOR_DATA
                            && mPlayer.getPlayerState() != PlayerState.STOPPED) {
                        mPlayer.play();
                    }
                }
            }
        }

        @Override
        public void pause() throws RemoteException {
            if (mPlayer != null && mPlayer.getPlayerState() != PlayerState.RELEASED) {
                synchronized (this) {
                    if (mPlayer.getPlayerState() != PlayerState.WAITING_FOR_DATA
                            && mPlayer.getPlayerState() != PlayerState.STOPPED) {
                        mPlayer.pause();
                    }
                }
            }
        }

        @Override
        public void seek(final int ms) throws RemoteException {
            if (mPlayer != null && mPlayer.getPlayerState() != PlayerState.RELEASED) {
                try {
                    synchronized (this) {
                        try {
                            mPlayer.seek(ms);
                        } catch (Exception e) {
                            Log.e(TAG, "seekTo: " + e.getClass() + ": " + e.getLocalizedMessage());
                            broadcastToAll(new BroadcastRunnable() {
                                @Override
                                public void broadcast(IPluginServiceCallback callback)
                                        throws RemoteException {
                                    callback.onError("Error while seeking");
                                }
                            });
                        }
                    }

                    broadcastToAll(new BroadcastRunnable() {
                        @Override
                        public void broadcast(IPluginServiceCallback callback)
                                throws RemoteException {
                            callback.onPlayerPositionChanged(ms, System.currentTimeMillis());
                        }
                    });
                } catch (RejectedExecutionException e) {
                    Log.e(TAG, "seek - " + e.getLocalizedMessage());
                }
            }
        }

        @Override
        public void setBitRate(int mode) throws RemoteException {
        }
    };

    private final PlayerHandler mPlayerHandler = new PlayerHandler();

    private class PlayerHandler implements OnPlayerStateChangeListener, OnPlayerErrorListener,
            OnBufferErrorListener {

        @Override
        public void onBufferError(final Exception ex, double percent) {
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    Log.d(TAG, "onBufferError: ", ex);
                    broadcastToAll(new BroadcastRunnable() {
                        @Override
                        public void broadcast(IPluginServiceCallback callback)
                                throws RemoteException {
                            callback.onError("Error while buffering");
                        }
                    });
                }
            });
        }

        @Override
        public void onPlayerError(final Exception ex, long timePosition) {
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    Log.d(TAG, "onPlayerError: ", ex);
                    broadcastToAll(new BroadcastRunnable() {
                        @Override
                        public void broadcast(IPluginServiceCallback callback)
                                throws RemoteException {
                            callback.onError("PlayerError: " + ex.getLocalizedMessage());
                        }
                    });
                }
            });
        }

        @Override
        public void onPlayerStateChange(final PlayerState state, long timePosition) {
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    broadcastToAll(new BroadcastRunnable() {
                        @Override
                        public void broadcast(IPluginServiceCallback callback)
                                throws RemoteException {
                            if (state == PlayerState.READY) {
                                callback.onPrepared();
                            } else if (state == PlayerState.PLAYBACK_COMPLETED) {
                                callback.onPlayerEndOfTrack();
                            } else if (state == PlayerState.PAUSED) {
                                callback.onPause();
                            } else if (state == PlayerState.STARTED) {
                                callback.onPlay();
                            }
                        }
                    });
                }
            });
        }
    }

    interface BroadcastRunnable {

        void broadcast(IPluginServiceCallback callback) throws RemoteException;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        Log.d(TAG, "DeezerService has been created");
    }

    @Override
    public void onDestroy() {
        mCallbacks.kill();
        if (mPlayer != null) {
            mPlayer.release();
        }
        Log.d(TAG, "DeezerService has been destroyed");

        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.d(TAG, "Client has been bound to DeezerService");
        if (IPluginService.class.getName().equals(intent.getAction())) {
            return mBinder;
        }
        return null;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Log.d(TAG, "Client has been unbound from DeezerService");
        stopSelf();
        return false;
    }

    private void broadcastToAll(BroadcastRunnable runnable) {
        // Broadcast to all clients
        final int N = mCallbacks.beginBroadcast();
        for (int i = 0; i < N; i++) {
            try {
                runnable.broadcast(mCallbacks.getBroadcastItem(i));
            } catch (RemoteException e) {
                // The RemoteCallbackList will take care of removing
                // the dead object for us.
            }
        }
        mCallbacks.finishBroadcast();
    }
}
