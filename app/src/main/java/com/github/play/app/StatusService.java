/*
 * Copyright 2012 Kevin Sawicki <kevinsawicki@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.play.app;

import static android.app.Notification.FLAG_ONGOING_EVENT;
import static android.app.PendingIntent.FLAG_UPDATE_CURRENT;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.text.TextUtils;
import android.util.Log;

import com.emorym.android_pusher.Pusher;
import com.emorym.android_pusher.PusherCallback;
import com.github.play.R.drawable;
import com.github.play.R.string;
import com.github.play.core.Song;
import com.github.play.core.SongPusher;
import com.github.play.core.StatusUpdate;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Service to receive push notifications about the currently playing song and
 * queued songs
 */
public class StatusService extends Service {

	/**
	 * Action to use for broadcasting updates
	 */
	public static final String UPDATE = "com.github.play.action.STATUS_UPDATE";

	/**
	 * Intent extra key to a {@link StatusUpdate} handle
	 */
	public static final String EXTRA_UPDATE = "update";

	/**
	 * Start service with application key
	 *
	 * @param context
	 * @param applicationKey
	 */
	public static void start(final Context context, final String applicationKey) {
		Intent intent = new Intent(ACTION);
		intent.putExtra(EXTRA_KEY, applicationKey);
		context.startService(intent);
	}

	/**
	 * Start service with application key
	 *
	 * @param context
	 */
	public static void stop(final Context context) {
		context.stopService(new Intent(ACTION));
	}

	/**
	 * Action to use for intents
	 */
	private static final String ACTION = "com.github.play.action.STATUS";

	private static final String EXTRA_KEY = "applicationKey";

	private static final String TAG = "StatusService";

	private static Song parseSong(final JSONObject object) {
		String id = object.optString("id");
		if (id == null)
			id = "";

		String artist = object.optString("artist");
		if (artist == null)
			artist = "";

		String album = object.optString("album");
		if (album == null)
			album = "";

		String name = object.optString("name");
		if (name == null)
			name = "";

		return new Song(id, name, artist, album, object.optBoolean("starred"));
	}

	private final Executor backgroundThread = Executors.newFixedThreadPool(1);

	private Notification notification;

	private final PusherCallback callback = new PusherCallback() {

		public void onEvent(JSONObject eventData) {
			JSONObject nowPlaying = eventData.optJSONObject("now_playing");
			if (nowPlaying == null)
				return;

			JSONArray upcomingSongs = eventData.optJSONArray("songs");
			if (upcomingSongs == null)
				return;

			playing = parseSong(nowPlaying);

			List<Song> parsedSongs = new ArrayList<Song>(upcomingSongs.length());
			for (int i = 0; i < upcomingSongs.length(); i++) {
				JSONObject song = upcomingSongs.optJSONObject(i);
				if (song == null)
					continue;
				parsedSongs.add(parseSong(song));
			}
			queued = parsedSongs.toArray(new Song[parsedSongs.size()]);

			Intent intent = new Intent(UPDATE);
			intent.putExtra(EXTRA_UPDATE, new StatusUpdate(playing, queued));
			sendBroadcast(intent);

			updateNotification();
		}
	};

	private Pusher pusher;

	private String applicationKey;

	private Song playing;

	private Song[] queued = new Song[0];

	@Override
	public IBinder onBind(final Intent intent) {
		return null;
	}

	@Override
	public void onDestroy() {
		super.onDestroy();

		Log.d(TAG, "Destroying status service");

		destroyPusher(pusher);
		stopForeground(true);
	}

	@Override
	public void onCreate() {
		super.onCreate();

		Log.d(TAG, "Creating status service");
	}

	@Override
	public int onStartCommand(final Intent intent, final int flags,
			final int startId) {

		if (intent != null) {
			String intentKey = intent.getStringExtra(EXTRA_KEY);
			if (!TextUtils.isEmpty(intentKey)
					&& !intentKey.equals(applicationKey)) {
				destroyPusher(pusher);
				createPusher(intentKey);
			}
		}

		return super.onStartCommand(intent, flags, startId);
	}

	private void destroyPusher(final Pusher pusher) {
		if (pusher != null)
			backgroundThread.execute(new Runnable() {

				public void run() {
					pusher.disconnect();
				}
			});
	}

	private void createPusher(String applicationKey) {
		this.applicationKey = applicationKey;

		final Pusher pusher = new SongPusher(applicationKey);
		backgroundThread.execute(new Runnable() {

			public void run() {
				pusher.subscribe("now_playing_updates").bind(
						"update_now_playing", callback);
			}
		});
		this.pusher = pusher;
	}

	@SuppressWarnings("deprecation")
	private void updateNotification() {
		Context context = getApplicationContext();
		PendingIntent intent = PendingIntent.getActivity(context, 0,
				new Intent(context, PlayActivity.class), FLAG_UPDATE_CURRENT);

		boolean startForeground = false;
		if (notification == null) {
			notification = new Notification();
			notification.icon = drawable.notification;
			notification.flags |= FLAG_ONGOING_EVENT;
			startForeground = true;
		}

		notification.tickerText = MessageFormat.format(
				getString(string.notification_ticker), playing.name,
				playing.artist);
		notification.setLatestEventInfo(context, playing.name, MessageFormat
				.format(getString(string.notification_content), playing.artist,
						playing.album), intent);

		if (startForeground)
			startForeground(1, notification);
		else
			((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
					.notify(1, notification);
	}
}
