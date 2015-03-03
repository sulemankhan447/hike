package com.bsb.hike.offline;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

import com.bsb.hike.HikeMessengerApp;
import com.bsb.hike.R;
import com.bsb.hike.utils.Logger;

import android.app.ProgressDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.p2p.WifiP2pManager.ActionListener;
import android.net.wifi.p2p.WifiP2pManager.Channel;
import android.net.wifi.p2p.WifiP2pManager.ChannelListener;
import android.net.wifi.p2p.WifiP2pManager.GroupInfoListener;
import android.net.wifi.p2p.WifiP2pManager.PeerListListener;
import android.util.Log;
import android.widget.Toast;

// Singleton class
public class WifiP2pConnectionManager implements ChannelListener
{
	private final String TAG = "WifiP2pConnectionManager";
	private Context context;
	private WifiP2pManager manager;
	private WifiManager wifiManager;
	private Channel channel;
    private BroadcastReceiver receiver = null;
    private SharedPreferences settings;
    private String myMsisdn;
    private boolean retryChannel = false;
    private boolean isWifiP2pEnabled = false;
    private PeerListListener peerListListener;
    private GroupInfoListener groupInfoListener;
    private WifiP2pConnectionManagerListener wifiP2pConnectionManagerListener; 
    private volatile boolean isOfflineFileTransferOn = false;
    
	public WifiP2pConnectionManager(Context context, WifiP2pConnectionManagerListener wListener, PeerListListener pListener, GroupInfoListener gListener)
	{
		this.context = context;
		this.peerListListener = pListener;
		this.groupInfoListener = gListener;
		this.wifiP2pConnectionManagerListener = wListener;
		initialise(context);
	}
	
	public void setIsWifiP2pEnabled(boolean isWifiP2pEnabled) {
        this.isWifiP2pEnabled = isWifiP2pEnabled;
    }
	
	private void initialise(Context context)
	{
        isOfflineFileTransferOn = true;
        manager = (WifiP2pManager) context.getSystemService(Context.WIFI_P2P_SERVICE);
        channel = manager.initialize(context, context.getMainLooper(), this);
        wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        if(wifiManager.isWifiEnabled() == false)
        	wifiManager.setWifiEnabled(true);
        
        setDeviceNameAsMsisdn();   
	}
	
	private void setDeviceNameAsMsisdn() {
		/*
         * Setting device Name to msisdn
         */
        try {
        	settings = context.getSharedPreferences(HikeMessengerApp.ACCOUNT_SETTINGS, 0);
            myMsisdn = settings.getString(HikeMessengerApp.MSISDN_SETTING, null);
			Method m = manager.getClass().getMethod("setDeviceName", new Class[]{channel.getClass(), String.class,
						WifiP2pManager.ActionListener.class});
			m.invoke(manager, channel, myMsisdn, new WifiP2pManager.ActionListener() {
				
				@Override
				public void onSuccess() {
					Log.d(TAG, "Device Name changed to " + myMsisdn);
				}
				@Override
				public void onFailure(int reason) {
					Logger.e(TAG, "Unable to set device name as msisdn");
				}
			});
		} catch (NoSuchMethodException e) {
			e.printStackTrace();
		} catch (IllegalAccessException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (IllegalArgumentException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (InvocationTargetException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	public boolean getIsOfflineFileTransferOn()
	{
		return isOfflineFileTransferOn;
	}
	
	public void setIsOfflineFileTransferOn(boolean isOfflineFileTransferOn)
	{
		synchronized(WifiP2pConnectionManager.class)
		{
			this.isOfflineFileTransferOn = isOfflineFileTransferOn;
		}
	}
	
	public void connect(WifiP2pConfig config)
	{
		manager.connect(channel, config, new ActionListener() {
            @Override
            public void onSuccess() {
                // WiFiDirectBroadcastReceiver will notify us. Ignore for now.
            }

			@Override
            public void onFailure(int reason) {
               wifiP2pConnectionManagerListener.connectFailure(reason);
            }
        });
	}
	
	public void disconnect()
	{
		manager.removeGroup(channel, new ActionListener() {
            @Override
            public void onFailure(int reasonCode) {
                Log.d(TAG, "Disconnect failed. Reason :" + reasonCode);

            }
            
            @Override
            public void onSuccess() {
                Log.d(TAG, "Disconnect successful.");
            }

        });
	}
	
	public void cancelConnect()
	{
		manager.cancelConnect(channel, new ActionListener() {
        	
            @Override
            public void onSuccess() {
                wifiP2pConnectionManagerListener.cancelConnectSuccess();
            }

            @Override
            public void onFailure(int reasonCode) {
                wifiP2pConnectionManagerListener.cancelConnectFailure(reasonCode);
            }
        });
	}
	
	public void enableDiscovery()
	{
		if (!wifiManager.isWifiEnabled()) {
            wifiP2pConnectionManagerListener.notifyWifiNotEnabled();
        }
		manager.discoverPeers(channel, new WifiP2pManager.ActionListener() {
        	
            @Override
            public void onSuccess() {
            	wifiP2pConnectionManagerListener.discoverySuccess();
            }

            @Override
            public void onFailure(int reasonCode) {
            	wifiP2pConnectionManagerListener.discoveryFailure(reasonCode);
            }
		});
	}
	public void resetData()
	{
		wifiP2pConnectionManagerListener.resetData();
	}
	
	public void updateDevice(WifiP2pDevice device)
	{
		wifiP2pConnectionManagerListener.updateMyDevice(device);
	}
	public boolean checkChannel()
	{
		return (channel != null);
	}
	
	public boolean checkManager()
	{
		return (manager != null);
	}
	
	public void requestGroupInfo()
	{
		manager.requestGroupInfo(channel, groupInfoListener);
	}
	
	public void requestPeers()
	{
		if (manager != null) {
            manager.requestPeers(channel, peerListListener);
		}
	}
	
	@Override
	public void onChannelDisconnected() {
		if (manager != null && !retryChannel) {
            retryChannel = true;
            manager.initialize(context, context.getMainLooper(), this);
        } else {
        	wifiP2pConnectionManagerListener.channelDisconnectedFailure();
        }
		
	}
}

interface WifiP2pConnectionManagerListener
{
	void channelDisconnectedFailure();
	
	void updateMyDevice(WifiP2pDevice device);

	void resetData();
	
	void notifyWifiNotEnabled();
	
	void connectSuccess();
	
	void connectFailure(int reasonCode);
	
	void cancelConnectSuccess();
	
	void cancelConnectFailure(int reasonCode);
	
	void discoverySuccess();
	
	void discoveryFailure(int reasonCode);
}