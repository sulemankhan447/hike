package com.bsb.hike.voip;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.math.BigInteger;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.ByteOrder;
import java.util.Enumeration;
import java.util.Random;

import org.json.JSONException;
import org.json.JSONObject;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.MediaRecorder;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.support.v4.app.NotificationCompat;
import android.telephony.TelephonyManager;

import com.bsb.hike.HikeConstants;
import com.bsb.hike.HikeMessengerApp;
import com.bsb.hike.HikePubSub;
import com.bsb.hike.R;
import com.bsb.hike.db.HikeConversationsDatabase;
import com.bsb.hike.models.ConvMessage;
import com.bsb.hike.models.Conversation;
import com.bsb.hike.notifications.HikeNotification;
import com.bsb.hike.service.HikeMqttManagerNew;
import com.bsb.hike.utils.HikeSharedPreferenceUtil;
import com.bsb.hike.utils.IntentManager;
import com.bsb.hike.utils.Logger;
import com.bsb.hike.utils.Utils;

public class VoIPUtils {

	private static boolean notificationDisplayed = false; 

	public static enum ConnectionClass {
		TwoG,
		ThreeG,
		FourG,
		WiFi,
		Unknown
	}

	public static enum CallSource
	{
		CHAT_THREAD, PROFILE_ACTIVITY, MISSED_CALL_NOTIF, CALL_FAILED_FRAG
	}
	
    public static boolean isWifiConnected(Context context) {
    	ConnectivityManager connManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
    	NetworkInfo mWifi = connManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);

    	if (mWifi.isConnected()) {
    		return true;
    	}    	
    	else
    		return false;
    }	
	
    public static String getLocalIpAddress(Context c) {
    	
    	if (isWifiConnected(c)) {
    		WifiManager wifiMgr = (WifiManager) c.getSystemService(Context.WIFI_SERVICE);
    		WifiInfo wifiInfo = wifiMgr.getConnectionInfo();
    		int ipAddress = wifiInfo.getIpAddress();
    		
    	    // Convert little-endian to big-endianif needed
    	    if (ByteOrder.nativeOrder().equals(ByteOrder.LITTLE_ENDIAN)) {
    	        ipAddress = Integer.reverseBytes(ipAddress);
    	    }

    	    byte[] ipByteArray = BigInteger.valueOf(ipAddress).toByteArray();

    	    String ipAddressString;
    	    try {
    	        ipAddressString = InetAddress.getByAddress(ipByteArray).getHostAddress();
    	    } catch (UnknownHostException ex) {
    	        Logger.e(VoIPConstants.TAG, "Unable to get host address.");
    	        ipAddressString = null;
    	    }

    	    return ipAddressString;    		
    	} else {
	        try {
	            for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements();) {
	                NetworkInterface intf = en.nextElement();
	                for (Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses(); enumIpAddr.hasMoreElements();) {
	                    InetAddress inetAddress = enumIpAddr.nextElement();
	                    if (!inetAddress.isLoopbackAddress() && inetAddress instanceof Inet4Address) {
	                        return inetAddress.getHostAddress();
	                    }
	                }
	            }
	        } catch (NullPointerException ex) {
	            ex.printStackTrace();
	        } catch (SocketException ex) {
	            ex.printStackTrace();
	        }
        return null;
    	}
    }	

    /**
     * Used to communicate between two VoIP clients, via the server
     * @param msisdn The client to which the message is being sent.
     * @param type Message type (v0, v1 etc). 
     * @param subtype Message sub-type. This usually decides what the recipient does with 
     * the message.
     * @throws JSONException
     */
    public static void sendMessage(String msisdn, String type, String subtype) throws JSONException {
    	
		JSONObject data = new JSONObject();
		data.put(HikeConstants.MESSAGE_ID, new Random().nextInt(10000));
		data.put(HikeConstants.TIMESTAMP, System.currentTimeMillis() / 1000); 

		JSONObject message = new JSONObject();
		message.put(HikeConstants.TO, msisdn);
		message.put(HikeConstants.TYPE, type);
		message.put(HikeConstants.SUB_TYPE, subtype);
		message.put(HikeConstants.DATA, data);
		
		HikeMqttManagerNew.getInstance().sendMessage(message, HikeMqttManagerNew.MQTT_QOS_ONE);
    }

    /**
     * Add a VoIP related message to the chat thread.
     * @param context
     * @param clientPartner
     * @param messageType
     * @param duration
     */
    public static void addMessageToChatThread(Context context, VoIPClient clientPartner, String messageType, int duration, long timeStamp, boolean shouldShowPush) 
    {

    	if (notificationDisplayed) {
    		Logger.w(VoIPConstants.TAG, "Notification already displayed.");
    		return;
    	} else
    		notificationDisplayed = true;
    		
    	Logger.d(VoIPConstants.TAG, "Adding message to chat thread. Message: " + messageType + ", Duration: " + duration);
    	HikeConversationsDatabase mConversationDb = HikeConversationsDatabase.getInstance();
    	Conversation mConversation = mConversationDb.getConversation(clientPartner.getPhoneNumber(), HikeConstants.MAX_MESSAGES_TO_LOAD_INITIALLY, Utils.isGroupConversation(clientPartner.getPhoneNumber()));	
    	long timestamp = System.currentTimeMillis() / 1000;
    	if (timeStamp > 0)
    	{
    		timestamp = timeStamp;
    	}
    	
		JSONObject jsonObject = new JSONObject();
		JSONObject data = new JSONObject();
		
		if (duration == 0 && messageType == HikeConstants.MqttMessageTypes.VOIP_MSG_TYPE_CALL_SUMMARY) {
			if (clientPartner.isInitiator())
				messageType = HikeConstants.MqttMessageTypes.VOIP_MSG_TYPE_MISSED_CALL_INCOMING;
			else
				messageType = HikeConstants.MqttMessageTypes.VOIP_MSG_TYPE_MISSED_CALL_OUTGOING;
		}

		boolean selfGenerated = true;
		if(messageType.equals(HikeConstants.MqttMessageTypes.VOIP_MSG_TYPE_MISSED_CALL_INCOMING))
		{
			selfGenerated = false;
		}

		try
		{
			Logger.d(VoIPConstants.TAG, "Adding message of type: " + messageType + " to chat thread.");
			data.put(HikeConstants.MESSAGE_ID, Long.toString(timestamp));
			data.put(HikeConstants.VOIP_CALL_DURATION, duration);
			data.put(HikeConstants.VOIP_CALL_INITIATOR, !clientPartner.isInitiator());
			data.put(HikeConstants.TIMESTAMP, timestamp);

			jsonObject.put(HikeConstants.DATA, data);
			jsonObject.put(HikeConstants.TYPE, messageType);
			jsonObject.put(HikeConstants.TO, clientPartner.getPhoneNumber());
			
			ConvMessage convMessage = new ConvMessage(jsonObject, mConversation, context, selfGenerated);
			convMessage.setShouldShowPush(shouldShowPush);
			mConversationDb.addConversationMessages(convMessage);
			HikeMessengerApp.getPubSub().publish(HikePubSub.MESSAGE_RECEIVED, convMessage);
		}
		catch (JSONException e)
		{
			Logger.w(VoIPConstants.TAG, "addMessageToChatThread() JSONException: " + e.toString());
		}    	
    }

	public static void sendMissedCallNotificationToPartner(VoIPClient clientPartner) {

		try {
			JSONObject socketData = new JSONObject();
			socketData.put("time", System.currentTimeMillis());
			
			JSONObject data = new JSONObject();
			data.put(HikeConstants.MESSAGE_ID, new Random().nextInt(10000));
			data.put(HikeConstants.TIMESTAMP, System.currentTimeMillis() / 1000); 
			data.put(HikeConstants.METADATA, socketData);

			JSONObject message = new JSONObject();
			message.put(HikeConstants.TO, clientPartner.getPhoneNumber());
			message.put(HikeConstants.TYPE, HikeConstants.MqttMessageTypes.MESSAGE_VOIP_1);
			message.put(HikeConstants.SUB_TYPE, HikeConstants.MqttMessageTypes.VOIP_MSG_TYPE_MISSED_CALL_INCOMING);
			message.put(HikeConstants.DATA, data);
			
			HikeMqttManagerNew.getInstance().sendMessage(message, HikeMqttManagerNew.MQTT_QOS_ONE);
			Logger.d(VoIPConstants.TAG, "Sent missed call notifier to partner.");
			
		} catch (JSONException e) {
			e.printStackTrace();
		} 
		
	}

	/**
	 * Tells you how you are connected to the Internet. 
	 * 2G / 3G / WiFi etc.
	 * @param context
	 * @return ConnectionClass 2G / 3G / 4G / WiFi
	 */
	public static ConnectionClass getConnectionClass(Context context) {
		ConnectionClass connection = null;
		
		ConnectivityManager connManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
		NetworkInfo mWifi = connManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);

		if (mWifi != null && mWifi.isConnected()) {
		    connection = ConnectionClass.WiFi;
		} else {
		    TelephonyManager mTelephonyManager = (TelephonyManager)
		            context.getSystemService(Context.TELEPHONY_SERVICE);
		    int networkType = mTelephonyManager.getNetworkType();
		    switch (networkType) {
		        case TelephonyManager.NETWORK_TYPE_GPRS:
		        case TelephonyManager.NETWORK_TYPE_EDGE:
		        case TelephonyManager.NETWORK_TYPE_CDMA:
		        case TelephonyManager.NETWORK_TYPE_1xRTT:
		        case TelephonyManager.NETWORK_TYPE_IDEN:
		            connection = ConnectionClass.TwoG;
		            break;
		        case TelephonyManager.NETWORK_TYPE_UMTS:
		        case TelephonyManager.NETWORK_TYPE_EVDO_0:
		        case TelephonyManager.NETWORK_TYPE_EVDO_A:
		        case TelephonyManager.NETWORK_TYPE_HSDPA:
		        case TelephonyManager.NETWORK_TYPE_HSUPA:
		        case TelephonyManager.NETWORK_TYPE_HSPA:
		        case TelephonyManager.NETWORK_TYPE_EVDO_B:
		        case TelephonyManager.NETWORK_TYPE_EHRPD:
		        case TelephonyManager.NETWORK_TYPE_HSPAP:
		            connection = ConnectionClass.ThreeG;
		            break;
		        case TelephonyManager.NETWORK_TYPE_LTE:
		            connection = ConnectionClass.FourG;
		            break;
		        default:
		            connection = ConnectionClass.Unknown;
		            break;
		    }
		}
//		Logger.w(VoIPConstants.TAG, "Our connection class: " + connection.name());
		return connection;
	}
	
	/**
	 * Is the user currently in a call?
	 * @param context
	 * @return
	 */
	public static boolean isUserInCall(Context context) {
		boolean callActive = false;
		TelephonyManager telephonyManager = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
		if (telephonyManager.getCallState() != TelephonyManager.CALL_STATE_IDLE)
			callActive = true;
		
		return callActive;
	}
	
	public static int getAudioSource() {
		int source = MediaRecorder.AudioSource.MIC;
		String model = android.os.Build.MODEL;
		
		if (android.os.Build.VERSION.SDK_INT >= 11)
			source = MediaRecorder.AudioSource.MIC;
		
		Logger.d(VoIPConstants.TAG, "Phone model: " + model);
		
//		if (model.contains("Nexus 5") || 
//				model.contains("Nexus 4"))
//			source = MediaRecorder.AudioSource.VOICE_RECOGNITION;
		
		return source;
	}
	
	/**
	 * Call this method when VoIP service is created. 
	 */
	public static void resetNotificationStatus() 
	{
		notificationDisplayed = false;
	}

	public static boolean shouldShowCallRatePopupNow()
	{
		return HikeSharedPreferenceUtil.getInstance().getData(HikeMessengerApp.SHOW_VOIP_CALL_RATE_POPUP, false);
	}
	
	public static void setupCallRatePopupNextTime()
	{
		HikeSharedPreferenceUtil sharedPref = HikeSharedPreferenceUtil.getInstance();
		int callsCount = sharedPref.getData(HikeMessengerApp.VOIP_ACTIVE_CALLS_COUNT, 0);
		sharedPref.saveData(HikeMessengerApp.VOIP_ACTIVE_CALLS_COUNT, ++callsCount);

		int frequency = sharedPref.getData(HikeMessengerApp.VOIP_CALL_RATE_POPUP_FREQUENCY, -1);
		boolean shownAlready = sharedPref.getData(HikeMessengerApp.SHOW_VOIP_CALL_RATE_POPUP, false);

		if(callsCount == frequency)
		{
			// Show popup next time
			sharedPref.saveData(HikeMessengerApp.SHOW_VOIP_CALL_RATE_POPUP, true);
			sharedPref.saveData(HikeMessengerApp.VOIP_ACTIVE_CALLS_COUNT, 0);
		}
		else if(shownAlready)
		{
			// Shown for the first time, dont show later
			sharedPref.saveData(HikeMessengerApp.SHOW_VOIP_CALL_RATE_POPUP, false);
		}
	}
	
	/**
	 * Returns the relay port that should be used. 
	 * This can be set by the server, and otherwise defaults to VoIPConstants.ICEServerPort
	 * @return
	 */
	public static int getRelayPort(Context context) {
		
		SharedPreferences prefs = context.getSharedPreferences(HikeMessengerApp.ACCOUNT_SETTINGS, 0);
		int port = prefs.getInt(HikeConstants.VOIP_RELAY_SERVER_PORT, VoIPConstants.ICEServerPort);
		return port;
	}

	public static void cancelMissedCallNotification(Context context)
	{
		HikeMessengerApp.getPubSub().publish(HikePubSub.CANCEL_ALL_NOTIFICATIONS, null);
		Intent it = new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
		context.sendBroadcast(it);
	}

	public static NotificationCompat.Action[] getMissedCallNotifActions(Context context, String msisdn)
	{
		Intent callIntent = IntentManager.getVoipCallIntent(context, msisdn, CallSource.MISSED_CALL_NOTIF);
		PendingIntent callPendingIntent = PendingIntent.getService(context, 0, callIntent, PendingIntent.FLAG_UPDATE_CURRENT);

		Intent messageIntent = IntentManager.getChatThreadIntent(context, msisdn);
		messageIntent.putExtra(HikeConstants.Extras.SHOW_KEYBOARD, true);
		PendingIntent messagePendingIntent = PendingIntent.getActivity(context, 0, messageIntent, PendingIntent.FLAG_UPDATE_CURRENT);

		NotificationCompat.Action actions[] = new NotificationCompat.Action[2];
		actions[0] = new NotificationCompat.Action(R.drawable.ic_action_call, context.getString(R.string.voip_missed_call_action), callPendingIntent);
		actions[1] = new NotificationCompat.Action(R.drawable.ic_action_message, context.getString(R.string.voip_missed_call_message), messagePendingIntent);

		return actions;
	}
	
	public static int getQualityTestAcceptablePacketLoss(Context context) {
		
		SharedPreferences prefs = context.getSharedPreferences(HikeMessengerApp.ACCOUNT_SETTINGS, 0);
		int apl = prefs.getInt(HikeConstants.VOIP_QUALITY_TEST_ACCEPTABLE_PACKET_LOSS, 20);
		return apl;
	}
	
	public static int getQualityTestSimulatedCallDuration(Context context) {
		
		SharedPreferences prefs = context.getSharedPreferences(HikeMessengerApp.ACCOUNT_SETTINGS, 0);
		int scd = prefs.getInt(HikeConstants.VOIP_QUALITY_TEST_SIMULATED_CALL_DURATION, 2);
		return scd;
	}
	
	public static String getCPUInfo() {
	    StringBuffer sb = new StringBuffer();
	    sb.append("abi: ").append(Build.CPU_ABI).append("\n");
	    if (new File("/proc/cpuinfo").exists()) {
	        try {
	            BufferedReader br = new BufferedReader(new FileReader(new File("/proc/cpuinfo")));
	            String aLine;
	            while ((aLine = br.readLine()) != null) {
	                sb.append(aLine + "\n");
	            }
	            if (br != null) {
	                br.close();
	            }
	        } catch (IOException e) {
	            e.printStackTrace();
	        } 
	    }
	    return sb.toString();
	}	
	
	public static boolean useAEC(Context context) {
		boolean useAEC = true;
		
		useAEC = HikeSharedPreferenceUtil.getInstance().getData(HikeConstants.VOIP_AEC_ENABLED, true);
		
		// Disable AEC on <= 2.3 devices
		if (!Utils.isHoneycombOrHigher())
			useAEC = false;
		
		return useAEC;
	}
}
