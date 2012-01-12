package com.bsb.hike;

import java.net.URISyntaxException;

import org.fusesource.mqtt.client.BlockingConnection;
import org.fusesource.mqtt.client.MQTT;
import org.fusesource.mqtt.client.Message;
import org.fusesource.mqtt.client.QoS;
import org.fusesource.mqtt.client.Topic;
import org.json.JSONException;
import org.json.JSONObject;

import android.content.Context;
import android.util.Log;

import com.bsb.hike.models.ContactInfo;
import com.bsb.hike.models.ConvMessage;
import com.bsb.hike.utils.AccountUtils;
import com.bsb.hike.utils.HikeConversationsDatabase;
import com.bsb.hike.utils.HikeToast;
import com.bsb.hike.utils.HikeUserDatabase;

public class PushManager extends Thread
{

	private MQTT mqtt;

	private volatile boolean finished;

	private String password;

	private String topic;

	private HikeToast toaster;

	private HikeUserDatabase db;

	private HikeConversationsDatabase convDb;

	public PushManager(Context context, String uid, String password) throws URISyntaxException
	{
		mqtt = new MQTT();
		this.toaster = new HikeToast(context);
		mqtt.setHost(AccountUtils.HOST, 1883);
		mqtt.setKeepAlive((short) (60 * 5));
		mqtt.setWillTopic("connection_terminated");
		mqtt.setPassword(password);
		this.password = password;
		this.topic = uid;
		this.db = new HikeUserDatabase(context);
		this.convDb = new HikeConversationsDatabase(context);
	}

	private BlockingConnection connect()
	{
		BlockingConnection connection;
		while (true)
		{
			try
			{
				Log.d("PushManager", "creating blockingConnection");
				connection = mqtt.blockingConnection();
				Log.d("PushManager", "connecting");
				connection.connect();
				Log.d("PushManager", "subscribing to topic: " + this.topic);
				Topic[] topics = { new Topic(this.topic, QoS.AT_LEAST_ONCE) };
				byte[] qos = connection.subscribe(topics);
				Log.d("PushManager", "subscribed");
				return connection;
			}
			catch (Exception e)
			{
				Log.e("PushManager", "trying to connect", e);
				try
				{
					Thread.sleep(2000);
				}
				catch (InterruptedException e1)
				{
					e1.printStackTrace();
				}
			}
		}
	}

	@Override
	public void run()
	{
		Log.d("PushManager", "Making connection");
		BlockingConnection connection = connect();
		Log.d("PushManager", "Connection made");
		while (!finished)
		{
			Message message;
			try
			{
				Log.d("PushManager", "receiving message");
				message = connection.receive();
				Log.d("PushManager", "message received");
			}
			catch (Exception e)
			{
				connection = connect();
				continue;
			}

			byte[] payload = message.getPayload();
			String str = new String(payload);
			try
			{
				JSONObject obj = new JSONObject(str);
				String msg = obj.optString("message");
				String msisdn = obj.optString("msisdn");
				long timestamp = obj.optLong("timestamp");
				ContactInfo contactInfo = this.db.getContactInfoFromMSISDN(msisdn);
				this.toaster.toast(contactInfo, msisdn, msg, timestamp);
				ConvMessage convMessage = new ConvMessage(msg, msisdn, contactInfo.id, timestamp, ConvMessage.State.RECEIVED_UNREAD);
				this.convDb.addConversationMessages(convMessage);
			}
			catch (JSONException e)
			{
				Log.e("PushManager", "invalid JSON Message", e);
			}
			message.ack();
		}
	}

}
