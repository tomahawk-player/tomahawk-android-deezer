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

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;

import java.util.ArrayList;
import java.util.concurrent.RejectedExecutionException;

public class DeezerService extends Service {

    // Used for debug logging
    private static final String TAG = DeezerService.class.getSimpleName();

    /**
     * Command to the service to register a client, receiving callbacks from the service. The
     * Message's replyTo field must be a Messenger of the client where callbacks should be sent.
     */
    static final int MSG_REGISTER_CLIENT = 1;

    /**
     * Command to the service to unregister a client, ot stop receiving callbacks from the service.
     * The Message's replyTo field must be a Messenger of the client as previously given with
     * MSG_REGISTER_CLIENT.
     */
    static final int MSG_UNREGISTER_CLIENT = 2;

    /**
     * Commands to the service
     */
    private static final int MSG_PREPARE = 100;

    private static final String MSG_PREPARE_ARG_URI = "uri";

    private static final String MSG_PREPARE_ARG_ACCESSTOKEN = "accessToken";

    private static final String MSG_PREPARE_ARG_ACCESSTOKENEXPIRES = "accessTokenExpires";

    private static final int MSG_PLAY = 101;

    private static final int MSG_PAUSE = 102;

    private static final int MSG_SEEK = 103;

    private static final String MSG_SEEK_ARG_MS = "ms";

    private static final int MSG_SETBITRATE = 104;

    private static final String MSG_SETBITRATE_ARG_MODE = "mode";

    /**
     * Commands to the client
     */
    private static final int MSG_ONPAUSE = 200;

    private static final int MSG_ONPLAY = 201;

    private static final int MSG_ONPREPARED = 202;

    protected static final String MSG_ONPREPARED_ARG_URI = "uri";

    private static final int MSG_ONPLAYERENDOFTRACK = 203;

    private static final int MSG_ONPLAYERPOSITIONCHANGED = 204;

    private static final String MSG_ONPLAYERPOSITIONCHANGED_ARG_POSITION = "position";

    private static final String MSG_ONPLAYERPOSITIONCHANGED_ARG_TIMESTAMP = "timestamp";

    private static final int MSG_ONERROR = 205;

    private static final String MSG_ONERROR_ARG_MESSAGE = "message";

    public final static String APP_ID = "";

    private TrackPlayer mPlayer;

    private String mPreparedUri;

    /**
     * Keeps track of all current registered clients.
     */
    ArrayList<Messenger> mClients = new ArrayList<>();

    /**
     * Target we publish for clients to send messages to IncomingHandler.
     */
    final Messenger mMessenger = new Messenger(new IncomingHandler(this));

    /**
     * Handler of incoming messages from clients.
     */
    private static class IncomingHandler extends WeakReferenceHandler<DeezerService> {

        public IncomingHandler(DeezerService referencedObject) {
            super(referencedObject);
        }

        @Override
        public void handleMessage(Message msg) {
            final DeezerService s = getReferencedObject();
            switch (msg.what) {
                case MSG_REGISTER_CLIENT:
                    s.mClients.add(msg.replyTo);
                    break;
                case MSG_UNREGISTER_CLIENT:
                    s.mClients.remove(msg.replyTo);
                    break;
                case MSG_PREPARE:
                    String uri = msg.getData().getString(MSG_PREPARE_ARG_URI);
                    String accessToken =
                            msg.getData().getString(MSG_PREPARE_ARG_ACCESSTOKEN);
                    long accessTokenExpires =
                            msg.getData().getLong(MSG_PREPARE_ARG_ACCESSTOKENEXPIRES);

                    s.mPreparedUri = uri;

                    DeezerConnect deezerConnect = new DeezerConnect(APP_ID);
                    deezerConnect.setAccessToken(s.getApplicationContext(), accessToken);
                    deezerConnect.setAccessExpires(accessTokenExpires);
                    try {
                        if (s.mPlayer == null
                                || s.mPlayer.getPlayerState() == PlayerState.RELEASED) {
                            s.mPlayer = new TrackPlayer(s.getApplication(), deezerConnect,
                                    new WifiAndMobileNetworkStateChecker());
                            s.mPlayer.addOnBufferErrorListener(s.mPlayerHandler);
                            s.mPlayer.addOnPlayerErrorListener(s.mPlayerHandler);
                            s.mPlayer.addOnPlayerStateChangeListener(s.mPlayerHandler);
                        }
                    } catch (TooManyPlayersExceptions | DeezerError e) {
                        Log.e(TAG, "<init>: " + e.getClass() + ": " + e
                                .getLocalizedMessage());
                    }
                    synchronized (this) {
                        s.mPlayer.playTrack(Long.valueOf(uri));
                    }
                    break;
                case MSG_PLAY:
                    Log.d(TAG, "play called");
                    if (s.mPlayer != null && s.mPlayer.getPlayerState() != PlayerState.RELEASED) {
                        synchronized (this) {
                            if (s.mPlayer.getPlayerState() != PlayerState.WAITING_FOR_DATA
                                    && s.mPlayer.getPlayerState() != PlayerState.STOPPED) {
                                s.mPlayer.play();
                            }
                        }
                    }
                    break;
                case MSG_PAUSE:
                    Log.d(TAG, "pause called");
                    if (s.mPlayer != null && s.mPlayer.getPlayerState() != PlayerState.RELEASED) {
                        synchronized (this) {
                            if (s.mPlayer.getPlayerState() != PlayerState.WAITING_FOR_DATA
                                    && s.mPlayer.getPlayerState() != PlayerState.STOPPED) {
                                s.mPlayer.pause();
                            }
                        }
                    }
                    break;
                case MSG_SEEK:
                    int ms = msg.getData().getInt(MSG_SEEK_ARG_MS);

                    Log.d(TAG, "seek()");
                    if (s.mPlayer != null && s.mPlayer.getPlayerState() != PlayerState.RELEASED) {
                        try {
                            synchronized (this) {
                                try {
                                    s.mPlayer.seek(ms);
                                } catch (Exception e) {
                                    Log.e(TAG, "seekTo: " + e.getClass() + ": " + e
                                            .getLocalizedMessage());
                                    Bundle args = new Bundle();
                                    args.putString(MSG_ONERROR_ARG_MESSAGE, "Error while seeking");
                                    s.broadcastToAll(MSG_ONERROR, args);
                                }
                            }

                            Bundle args = new Bundle();
                            args.putInt(MSG_ONPLAYERPOSITIONCHANGED_ARG_POSITION, ms);
                            args.putLong(MSG_ONPLAYERPOSITIONCHANGED_ARG_TIMESTAMP,
                                    System.currentTimeMillis());
                            s.broadcastToAll(MSG_ONPLAYERPOSITIONCHANGED, args);
                        } catch (RejectedExecutionException e) {
                            Log.e(TAG, "seek - " + e.getLocalizedMessage());
                        }
                    }
                    break;
                case MSG_SETBITRATE:
                    break;
                default:
                    super.handleMessage(msg);
            }
        }
    }

    private final PlayerHandler mPlayerHandler = new PlayerHandler();

    private class PlayerHandler implements OnPlayerStateChangeListener, OnPlayerErrorListener,
            OnBufferErrorListener {

        @Override
        public void onBufferError(final Exception ex, double percent) {
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    Log.d(TAG, "onBufferError: ", ex);
                    Bundle args = new Bundle();
                    args.putString(MSG_ONERROR_ARG_MESSAGE, "Error while buffering");
                    broadcastToAll(MSG_ONERROR, args);
                }
            });
        }

        @Override
        public void onPlayerError(final Exception ex, long timePosition) {
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    Log.d(TAG, "onPlayerError: ", ex);
                    Bundle args = new Bundle();
                    args.putString(MSG_ONERROR_ARG_MESSAGE,
                            "PlayerError: " + ex.getLocalizedMessage());
                    broadcastToAll(MSG_ONERROR, args);
                }
            });
        }

        @Override
        public void onPlayerStateChange(final PlayerState state, long timePosition) {
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    if (state == PlayerState.READY) {
                        Bundle args = new Bundle();
                        args.putString(MSG_ONPREPARED_ARG_URI, mPreparedUri);
                        broadcastToAll(MSG_ONPREPARED, args);
                    } else if (state == PlayerState.PLAYBACK_COMPLETED) {
                        broadcastToAll(MSG_ONPLAYERENDOFTRACK);
                    } else if (state == PlayerState.PAUSED) {
                        broadcastToAll(MSG_ONPAUSE);
                    } else if (state == PlayerState.STARTED) {
                        broadcastToAll(MSG_ONPLAY);
                    }
                }
            });
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();

        Log.d(TAG, "DeezerService has been created");
    }

    @Override
    public void onDestroy() {
        if (mPlayer != null) {
            mPlayer.release();
        }
        Log.d(TAG, "DeezerService has been destroyed");

        super.onDestroy();
    }

    /**
     * When binding to the service, we return an interface to our messenger for sending messages to
     * the service.
     */
    @Override
    public IBinder onBind(Intent intent) {
        Log.d(TAG, "Client has been bound to DeezerService");
        return mMessenger.getBinder();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Log.d(TAG, "Client has been unbound from DeezerService");
        return false;
    }

    private void broadcastToAll(int what) {
        broadcastToAll(what, null);
    }

    private void broadcastToAll(int what, Bundle bundle) {
        for (int i = mClients.size() - 1; i >= 0; i--) {
            try {
                Message message = Message.obtain(null, what);
                message.setData(bundle);
                mClients.get(i).send(message);
            } catch (RemoteException e) {
                // The client is dead.  Remove it from the list;
                // we are going through the list from back to front
                // so this is safe to do inside the loop.
                mClients.remove(i);
            }
        }
    }
}
