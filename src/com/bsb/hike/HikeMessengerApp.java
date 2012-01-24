package com.bsb.hike;

import android.app.Application;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;

import com.bsb.hike.service.HikeService;
import com.bsb.hike.service.HikeServiceConnection;
import com.bsb.hike.utils.AccountUtils;
import com.bsb.hike.utils.DbConversationListener;
import com.bsb.hike.utils.SmileyParser;
import com.bsb.hike.utils.ToastListener;

public class HikeMessengerApp extends Application
{
	public static final String ACCOUNT_SETTINGS = "accountsettings";

	public static final String MSISDN_SETTING = "msisdn";

	public static final String CARRIER_SETTING = "carrier";

	public static final String NAME_SETTING = "name";

	public static final String TOKEN_SETTING = "token";

	public static final String MESSAGES_SETTING = "messageid";

	public static final String UID_SETTING = "uid";

	public static final String ADDRESS_BOOK_SCANNED = "abscanned";

	public static final String CONTACT_LIST_EMPTY = "contactlistempty";

	public static final String SMS_SETTING = "smscredits";

	private static HikePubSub mPubSubInstance;

	private static Messenger mMessenger;

	private NetworkManager mNetworkManager;

	private Messenger mService;

	private HikeServiceConnection mServiceConnection;

	private boolean mInitialized;

	static class IncomingHandler extends Handler
	{
		@Override
		public void handleMessage(Message msg)
		{
			Log.d("HikeMessengerApp", "In handleMessage " + msg.what);
			switch (msg.what)
			{
				case HikeService.MSG_APP_PUBLISH:
					Log.d("HikeMessengerApp", "received message " );
					Bundle bundle = msg.getData();
					String message = bundle.getString("msg");
					Log.d("HikeMessengerApp", "received message " + message);
					mPubSubInstance.publish(HikePubSub.WS_RECEIVED, message);
			}
		}
	}

	static
	{
		mPubSubInstance = new HikePubSub();
	}

	public void sendToService(Message message)
	{
		try
		{
			mService.send(message);
		}
		catch (RemoteException e)
		{
			Log.e("HikeMessengerApp", "Unable to connect to service", e);
		}
	}

	public void connectToService()
	{
		if (!mInitialized)
		{
			synchronized(HikeMessengerApp.class)
			{
				if (!mInitialized)
				{
					mInitialized = true;
					mNetworkManager.startWebSocket();
					mServiceConnection = HikeServiceConnection.createConnection(this, mMessenger);
				}
			}
		}
	}

	public void onCreate()
	{
		super.onCreate();

		SmileyParser.init(this);
		/* add the db write listener */
		new DbConversationListener(getApplicationContext());

		/* add the generic websocket listener. This will turn strings into objects and re-broadcast them */
		mNetworkManager = NetworkManager.getInstance(getApplicationContext());

		/* add a handler to handle toasts. The object initializes itself it it's constructor */
		new ToastListener(getApplicationContext());

		mMessenger = new Messenger(new IncomingHandler());

		SharedPreferences settings = getSharedPreferences(HikeMessengerApp.ACCOUNT_SETTINGS, 0);
		String token = settings.getString(HikeMessengerApp.TOKEN_SETTING, null);
		if (token != null)
		{
			AccountUtils.setToken(token);
		}
	}

	public static HikePubSub getPubSub()
	{
		return mPubSubInstance;
	}

	public void setService(Messenger service)
	{
		this.mService = service;
	}
}
