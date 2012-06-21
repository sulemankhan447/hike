package com.bsb.hike.ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.AdapterView;
import android.widget.AdapterView.AdapterContextMenuInfo;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ListView;
import android.widget.TextView;

import com.bsb.hike.HikeConstants;
import com.bsb.hike.HikeMessengerApp;
import com.bsb.hike.HikePubSub;
import com.bsb.hike.NetworkManager;
import com.bsb.hike.R;
import com.bsb.hike.adapters.ConversationsAdapter;
import com.bsb.hike.adapters.HikeInviteAdapter;
import com.bsb.hike.db.HikeConversationsDatabase;
import com.bsb.hike.models.ConvMessage;
import com.bsb.hike.models.ConvMessage.ParticipantInfoState;
import com.bsb.hike.models.Conversation;
import com.bsb.hike.models.GroupConversation;
import com.bsb.hike.models.utils.IconCacheManager;
import com.bsb.hike.utils.UpdateAppBaseActivity;
import com.bsb.hike.utils.Utils;

public class MessagesList extends UpdateAppBaseActivity implements OnClickListener, OnItemClickListener, HikePubSub.Listener, android.content.DialogInterface.OnClickListener, Runnable
{
	private static final int INVITE_PICKER_RESULT = 1001;

	public static final Object COMPOSE = "compose";

	private ListView mConversationsView;

	private View mSearchIconView;

	private View mEditMessageIconView;

	private ConversationsAdapter mAdapter;

	private View mEmptyView;

	private Comparator<? super Conversation> mConversationsComparator;

	private Map<String, Conversation> mConversationsByMSISDN;

	private Set<String> mConversationsAdded;

	private View mInviteToolTip;

	private SharedPreferences accountPrefs;

	@Override
	protected void onPause()
	{
		super.onPause();
		Log.d("MESSAGE LIST", "Currently in pause state. .......");
		HikeMessengerApp.getPubSub().publish(HikePubSub.NEW_ACTIVITY, null);
	}

	@Override
	protected void onResume()
	{
		super.onResume();
		HikeMessengerApp.getPubSub().publish(HikePubSub.NEW_ACTIVITY, this);
	}
	
	private class DeleteConversationsAsyncTask extends AsyncTask<Conversation, Void, Conversation[]>
	{

		@Override
		protected Conversation[] doInBackground(Conversation... convs)
		{
			HikeConversationsDatabase db = null;
			ArrayList<Long> ids = new ArrayList<Long>(convs.length);
			ArrayList<String> msisdns =new ArrayList<String>(convs.length);
			for (Conversation conv : convs)
			{
				ids.add(conv.getConvId());
				msisdns.add(conv.getMsisdn());
			}

			try
			{
				db = new HikeConversationsDatabase(MessagesList.this);
				db.deleteConversation(ids.toArray(new Long[] {}), msisdns);
			}
			finally
			{
				if (db != null)
				{
					db.close();
				}
			}
			return convs;
		}

		@Override
		protected void onPostExecute(Conversation[] deleted)
		{
			NotificationManager mgr = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
			for (Conversation conversation : deleted)
			{
				mgr.cancel((int) conversation.getConvId());
				mAdapter.remove(conversation);
				mConversationsByMSISDN.remove(conversation.getMsisdn());
				mConversationsAdded.remove(conversation.getMsisdn());
			}

			mAdapter.notifyDataSetChanged();
			mAdapter.setNotifyOnChange(false);
		}
	}

	@Override
	protected void onNewIntent(Intent intent) {
		Utils.requireAuth(this);
	}

	@Override
	public void onCreate(Bundle savedInstanceState)
	{
		super.onCreate(savedInstanceState);
		
		if (Utils.requireAuth(this))
		{
			return;
		}

		SharedPreferences settings = getSharedPreferences(HikeMessengerApp.ACCOUNT_SETTINGS, 0);
		String token = settings.getString(HikeMessengerApp.TOKEN_SETTING, null);
		// TODO this is being called everytime this activity is created. Way too often
		HikeMessengerApp app = (HikeMessengerApp) getApplicationContext();
		app.connectToService();

		setContentView(R.layout.main);

		Utils.setDensityMultiplier(MessagesList.this);
		//TODO Remove this from here. Analytics - For testing purposes only
		HikeMessengerApp.getPubSub().publish(HikePubSub.MQTT_PUBLISH, Utils.getDeviceDetails(MessagesList.this));
		HikeMessengerApp.getPubSub().publish(HikePubSub.MQTT_PUBLISH, Utils.getDeviceStats(MessagesList.this));
		//Analytics - For testing purposes only

		mConversationsView = (ListView) findViewById(R.id.conversations);

		View view = findViewById(R.id.title_hikeicon);
		view.setVisibility(View.VISIBLE);

		/*
		 * mSearchIconView = findViewById(R.id.search); mSearchIconView.setOnClickListener(this);
		 */

		mEditMessageIconView = findViewById(R.id.edit_message);
		mEditMessageIconView.setOnClickListener(this);

		/* set the empty view layout for the list */
		mEmptyView = findViewById(R.id.empty_view);
		mEmptyView.setOnClickListener(this);

		mConversationsView.setEmptyView(mEmptyView);
		mConversationsView.setOnItemClickListener(this);

		accountPrefs = getSharedPreferences(HikeMessengerApp.ACCOUNT_SETTINGS, 0);

		if (!accountPrefs.getBoolean(HikeMessengerApp.MESSAGES_LIST_TOOLTIP_DISMISSED, false) 
				&& Utils.wasScreenOpenedNNumberOfTimes(accountPrefs, HikeMessengerApp.NUM_TIMES_HOME_SCREEN))
		{
			mInviteToolTip = mEmptyView.findViewById(R.id.credits_help_layout);
			mInviteToolTip.setBackgroundResource(R.drawable.home_credits_tool_tip_bg);

			TextView text = (TextView) mEmptyView.findViewById(R.id.tool_tip);
			text.setText(getString(R.string.earn_200_sms_tap_here));

			if (savedInstanceState == null || !savedInstanceState.getBoolean(HikeConstants.Extras.TOOLTIP_SHOWING)) 
			{
				Animation alphaIn = AnimationUtils.loadAnimation(
						MessagesList.this, android.R.anim.fade_in);
				alphaIn.setStartOffset(1000);
				mInviteToolTip.setAnimation(alphaIn);
			}
			mInviteToolTip.setVisibility(View.VISIBLE);
		}

		HikeConversationsDatabase db = new HikeConversationsDatabase(this);
		List<Conversation> conversations = db.getConversations();
		db.close();

		mConversationsByMSISDN = new HashMap<String, Conversation>(conversations.size());
		mConversationsAdded = new HashSet<String>();

		/*
		 * Use an iterator so we can remove conversations w/ no messages from our list
		 */
		for (Iterator<Conversation> iter = conversations.iterator(); iter.hasNext();)
		{
			Conversation conv = (Conversation) iter.next();
			mConversationsByMSISDN.put(conv.getMsisdn(), conv);
			if (conv.getMessages().isEmpty() && !(conv instanceof GroupConversation))
			{
				iter.remove();
			}
			else
			{
				mConversationsAdded.add(conv.getMsisdn());
			}
		}

		mAdapter = new ConversationsAdapter(this, R.layout.conversation_item, conversations);
		/* we need this object every time a message comes in, seems simplest to just create it once */
		mConversationsComparator = new Conversation.ConversationComparator();

		/*
		 * because notifyOnChange gets re-enabled whenever we call notifyDataSetChanged it's simpler to assume it's set to false and always notifyOnChange by hand
		 */
		mAdapter.setNotifyOnChange(false);
		mConversationsView.setAdapter(mAdapter);

		HikeMessengerApp.getPubSub().addListener(HikePubSub.MESSAGE_RECEIVED, this);
		HikeMessengerApp.getPubSub().addListener(HikePubSub.SERVER_RECEIVED_MSG, this);
		HikeMessengerApp.getPubSub().addListener(HikePubSub.MESSAGE_DELIVERED_READ, this);
		HikeMessengerApp.getPubSub().addListener(HikePubSub.MESSAGE_DELIVERED, this);
		HikeMessengerApp.getPubSub().addListener(HikePubSub.MESSAGE_FAILED, this);

		HikeMessengerApp.getPubSub().addListener(HikePubSub.NEW_CONVERSATION, this);
		HikeMessengerApp.getPubSub().addListener(HikePubSub.MESSAGE_SENT, this);
		HikeMessengerApp.getPubSub().addListener(HikePubSub.MSG_READ, this);

		HikeMessengerApp.getPubSub().addListener(HikePubSub.ICON_CHANGED, this);
		HikeMessengerApp.getPubSub().addListener(HikePubSub.GROUP_LEFT, this);
		HikeMessengerApp.getPubSub().addListener(HikePubSub.GROUP_NAME_CHANGED, this);
		/* register for long-press's */
		registerForContextMenu(mConversationsView);
	}

	@Override
	protected void onSaveInstanceState(Bundle outState) {
		outState.putBoolean(HikeConstants.Extras.TOOLTIP_SHOWING, mInviteToolTip != null && mInviteToolTip.getVisibility() == View.VISIBLE);
		super.onSaveInstanceState(outState);
	}

	@Override
	public void onBackPressed()
	{
		Utils.incrementNumTimesScreenOpen(accountPrefs, HikeMessengerApp.NUM_TIMES_HOME_SCREEN);
		super.onBackPressed();
	}

	private Intent createIntentForConversation(Conversation conversation)
	{
		Intent intent = new Intent(MessagesList.this, ChatThread.class);
		if (conversation.getContactName() != null)
		{
			intent.putExtra(HikeConstants.Extras.NAME, conversation.getContactName());
		}
		if (conversation.getContactId() != null)
		{
			intent.putExtra(HikeConstants.Extras.ID, conversation.getContactId());
		}
		intent.putExtra(HikeConstants.Extras.MSISDN, conversation.getMsisdn());
		return intent;
	}

	@Override
	public boolean onContextItemSelected(MenuItem item)
	{
		AdapterContextMenuInfo info = (AdapterContextMenuInfo) item.getMenuInfo();
		Conversation conv = mAdapter.getItem((int) info.id);
		switch (item.getItemId())
		{
		case R.id.shortcut:
			Utils.logEvent(MessagesList.this, HikeConstants.LogEvent.ADD_SHORTCUT);
			Intent shortcutIntent = createIntentForConversation(conv);
			Intent intent = new Intent();
			Log.i("CreateShortcut", "Creating intent for broadcasting");
			intent.putExtra(Intent.EXTRA_SHORTCUT_INTENT, shortcutIntent);
			intent.putExtra(Intent.EXTRA_SHORTCUT_NAME, conv.getLabel());
			Drawable d = IconCacheManager.getInstance().getIconForMSISDN(conv.getMsisdn());
			Bitmap bitmap = ((BitmapDrawable) d).getBitmap();
			Bitmap scaled = Bitmap.createScaledBitmap(bitmap, 60, 60, false);
			bitmap = null;
			intent.putExtra(Intent.EXTRA_SHORTCUT_ICON, scaled);
			intent.setAction("com.android.launcher.action.INSTALL_SHORTCUT");
			sendBroadcast(intent);
			return true;
		case R.id.delete:
			Utils.logEvent(MessagesList.this, HikeConstants.LogEvent.DELETE_CONVERSATION);
			if(conv instanceof GroupConversation)
			{
				leaveGroup(conv);
			}
			else
			{
				DeleteConversationsAsyncTask task = new DeleteConversationsAsyncTask();
				task.execute(conv);
			}
			return true;
		default:
			return super.onContextItemSelected(item);
		}
	}

	@Override
	public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo)
	{
		super.onCreateContextMenu(menu, v, menuInfo);
		android.view.MenuInflater inflater = getMenuInflater();
		inflater.inflate(R.menu.conversation_menu, menu);
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		MenuInflater inflater = getMenuInflater();
		inflater.inflate(R.menu.main_menu, menu);
		return true;
	}

	private void showCreditsScreen()
	{
		Intent intent = new Intent(this, CreditsActivity.class);
		startActivity(intent);		
	}

	@Override
	public boolean onPrepareOptionsMenu(Menu menu) {
		Utils.logEvent(MessagesList.this, HikeConstants.LogEvent.MENU);
		return super.onPrepareOptionsMenu(menu);
	}

	public boolean onOptionsItemSelected(MenuItem item)
	{
		Intent intent;
		switch (item.getItemId())
		{
		case R.id.invite:
			Utils.logEvent(MessagesList.this, HikeConstants.LogEvent.INVITE_MENU);
			intent = new Intent(this, HikeListActivity.class);
			intent.putExtra(HikeConstants.ADAPTER_NAME, HikeInviteAdapter.class.getName());
			startActivity(intent);
			return true;
		case R.id.deleteconversations:
			if (!mAdapter.isEmpty()) {
				Utils.logEvent(MessagesList.this, HikeConstants.LogEvent.DELETE_ALL_CONVERSATIONS_MENU);
				AlertDialog.Builder builder = new AlertDialog.Builder(this);
				builder.setMessage(R.string.delete_all_question)
						.setPositiveButton("Delete", this)
						.setNegativeButton(R.string.cancel, this).show();
			}
			return true;
		case R.id.profile:
			Utils.logEvent(MessagesList.this, HikeConstants.LogEvent.PROFILE_MENU);
			intent = new Intent(this, ProfileActivity.class);
			startActivity(intent);
			return true;
		case R.id.feedback:
			Utils.logEvent(MessagesList.this, HikeConstants.LogEvent.FEEDBACK_MENU, 0);
			intent = new Intent(this, FeedbackActivity.class);
			startActivity(intent);
			return true;
		default:
			return super.onOptionsItemSelected(item);
		}
	}

	@Override
	protected void onDestroy()
	{
		super.onDestroy();
		Log.d(getClass().getSimpleName(), "onDestroy " + this);
		HikeMessengerApp.getPubSub().removeListener(HikePubSub.MSG_READ, this);
		HikeMessengerApp.getPubSub().removeListener(HikePubSub.MESSAGE_SENT, this);
		HikeMessengerApp.getPubSub().removeListener(HikePubSub.MESSAGE_RECEIVED, this);
		HikeMessengerApp.getPubSub().removeListener(HikePubSub.NEW_CONVERSATION, this);
		HikeMessengerApp.getPubSub().removeListener(HikePubSub.SERVER_RECEIVED_MSG, this);
		HikeMessengerApp.getPubSub().removeListener(HikePubSub.MESSAGE_DELIVERED_READ, this);
		HikeMessengerApp.getPubSub().removeListener(HikePubSub.MESSAGE_DELIVERED, this);
		HikeMessengerApp.getPubSub().removeListener(HikePubSub.MESSAGE_FAILED, this);
		HikeMessengerApp.getPubSub().removeListener(HikePubSub.ICON_CHANGED, this);
		HikeMessengerApp.getPubSub().removeListener(HikePubSub.GROUP_LEFT, this);
		HikeMessengerApp.getPubSub().removeListener(HikePubSub.GROUP_NAME_CHANGED, this);
	}

	@Override
	public void onClick(View v)
	{
		if ((v == mEditMessageIconView))
		{
			Utils.logEvent(MessagesList.this, HikeConstants.LogEvent.COMPOSE_BUTTON);
			Intent intent = new Intent(this, ChatThread.class);
			intent.putExtra(HikeConstants.Extras.EDIT, true);
			startActivity(intent);
			overridePendingTransition(R.anim.slide_up_noalpha, R.anim.no_animation);
		}
	}

	@Override
	public void onEventReceived(String type, Object object)
	{
		if ((HikePubSub.MESSAGE_RECEIVED.equals(type)) || (HikePubSub.MESSAGE_SENT.equals(type)))
		{
			Log.d("MESSAGE LIST", "New msg event sent or received.");
			final ConvMessage message = (ConvMessage) object;
			/* find the conversation corresponding to this message */
			String msisdn = message.getMsisdn();
			final Conversation conv = mConversationsByMSISDN.get(msisdn);
			
			if (conv == null)
			{
				// When a message gets sent from a user we don't have a conversation for, the message gets
				// broadcasted first then the conversation gets created. It's okay that we don't add it now, because
				// when the conversation is broadcasted it will contain the messages
				return;
			}

			// For updating the group name if some participant has joined or left the group
			else if((conv instanceof GroupConversation) && message.getParticipantInfoState() != ParticipantInfoState.NO_INFO)
			{
				HikeConversationsDatabase hCDB = new HikeConversationsDatabase(MessagesList.this);
				((GroupConversation) conv).setGroupParticipantList(hCDB.getGroupParticipants(conv.getMsisdn()));
				hCDB.close();
			}
			runOnUiThread(new Runnable(){
				@Override
				public void run()
				{
					if (!mConversationsAdded.contains(conv.getMsisdn()))
					{
						mConversationsAdded.add(conv.getMsisdn());
						mAdapter.add(conv);
					}

					conv.addMessage(message);
					Log.d("MessagesList", "new message is " + message);
					mAdapter.sort(mConversationsComparator);
					mAdapter.notifyDataSetChanged();
					// notifyDataSetChanged sets notifyonChange to true but we want it to always be false
					mAdapter.setNotifyOnChange(false);
				}
			});
		}
		else if (HikePubSub.NEW_CONVERSATION.equals(type))
		{
			final Conversation conversation = (Conversation) object;
			Log.d(getClass().getSimpleName(), "New Conversation. Group Conversation? " + (conversation instanceof GroupConversation));
			mConversationsByMSISDN.put(conversation.getMsisdn(), conversation);
			if (conversation.getMessages().isEmpty() && !(conversation instanceof GroupConversation))
			{
				return;
			}

			mConversationsAdded.add(conversation.getMsisdn());
			runOnUiThread(new Runnable()
			{
				public void run()
				{
					mAdapter.add(conversation);
					if (conversation instanceof GroupConversation) 
					{
						mAdapter.notifyDataSetChanged();
					}
					mAdapter.setNotifyOnChange(false);
				}
			});
		}
		else if (HikePubSub.MSG_READ.equals(type))
		{
			String msisdn = (String) object;
			Conversation conv = mConversationsByMSISDN.get(msisdn);
			/* look for the latest received messages and set them to read.
			 * Exit when we've found some read messages
			 */
			List<ConvMessage> messages = conv.getMessages();
			for(int i = messages.size() - 1; i >= 0; --i)
			{
				ConvMessage msg = messages.get(i);
				if (!msg.isSent())
				{
					ConvMessage.State currentState = msg.getState();
					msg.setState(ConvMessage.State.RECEIVED_READ);
					if (currentState == ConvMessage.State.RECEIVED_READ)
					{
						break;
					}
				}
			}

			mAdapter.setNotifyOnChange(false);
			runOnUiThread(this);
		}
		else if (HikePubSub.SERVER_RECEIVED_MSG.equals(type))
		{
			long msgId = ((Long) object).longValue();
			ConvMessage msg = findMessageById(msgId);
			if (msg != null)
			{
				msg.setState(ConvMessage.State.SENT_CONFIRMED);
				runOnUiThread(this);
			}
		}
		else if (HikePubSub.MESSAGE_DELIVERED_READ.equals(type))
		{
			long[] ids = (long[]) object;
			// TODO we could keep a map of msgId -> conversation objects somewhere to make this faster
			for (int i = 0; i < ids.length; i++)
			{
				ConvMessage msg = findMessageById(ids[i]);
				if (msg != null)
				{
					msg.setState(ConvMessage.State.SENT_DELIVERED_READ);
				}
			}
			runOnUiThread(this);
		}
		else if (HikePubSub.MESSAGE_DELIVERED.equals(type))
		{
			long msgId = ((Long) object).longValue();
			ConvMessage msg = findMessageById(msgId);
			if (msg != null)
			{
				msg.setState(ConvMessage.State.SENT_DELIVERED);
				runOnUiThread(this);
			}
		}
		else if (HikePubSub.MESSAGE_FAILED.equals(type))
		{
			long msgId = ((Long) object).longValue();
			ConvMessage msg = findMessageById(msgId);
			if (msg != null)
			{
				msg.setState(ConvMessage.State.SENT_FAILED);
				runOnUiThread(this);
			}
		}
		else if (HikePubSub.ICON_CHANGED.equals(type))
		{
			/* an icon changed, so update the view */
			runOnUiThread(this);
		}
		else if (HikePubSub.GROUP_LEFT.equals(type))
		{
			final String groupId = (String) object;
			runOnUiThread(new Runnable() 
			{
				@Override
				public void run() 
				{
					leaveGroup(MessagesList.this.mConversationsByMSISDN.get(groupId));
				}
			});
		}
		else if (HikePubSub.GROUP_NAME_CHANGED.equals(type))
		{
			String groupId = (String) object;
			HikeConversationsDatabase db = new HikeConversationsDatabase(this);
			final String groupName = db.getGroupName(groupId);
			db.close();

			Conversation conv = mConversationsByMSISDN.get(groupId);
			conv.setContactName(groupName);

			runOnUiThread(this);
		}
	}

	ConvMessage findMessageById(long msgId)
	{
		int count = mAdapter.getCount();
		for (int i = 0; i < count; ++i)
		{
			Conversation conversation = mAdapter.getItem(i);
			if (conversation == null)
			{
				continue;
			}
			List<ConvMessage> messages = conversation.getMessages();
			if (messages.isEmpty())
			{
				continue;
			}

			ConvMessage message = messages.get(messages.size() - 1);
			if (message.getMsgID() == msgId)
			{
				return message;
			}
		}

		return null;
	}

	public void run()
	{
		mAdapter.notifyDataSetChanged();
		// notifyDataSetChanged sets notifyonChange to true but we want it to always be false
		mAdapter.setNotifyOnChange(false);
	}

	@Override
	public void onClick(DialogInterface dialog, int which)
	{
		switch (which)
		{
		case DialogInterface.BUTTON_POSITIVE:
			Conversation[] convs = new Conversation[mAdapter.getCount()];
			for (int i = 0; i < convs.length; i++)
			{
				convs[i] = mAdapter.getItem(i);
				if ((convs[i] instanceof GroupConversation)) 
				{
					HikeMessengerApp.getPubSub().publish(HikePubSub.MQTT_PUBLISH, convs[i].serialize(NetworkManager.GROUP_CHAT_LEAVE));
				}
			}
			DeleteConversationsAsyncTask task = new DeleteConversationsAsyncTask();
			task.execute(convs);
			break;
		default:
		}
	}

	@Override
	public void onItemClick(AdapterView<?> adapterView, View view, int position, long id)
	{
		Conversation conv = (Conversation) adapterView.getItemAtPosition(position);
		Intent intent = createIntentForConversation(conv);
		startActivity(intent);
		overridePendingTransition(R.anim.slide_in_right_noalpha, R.anim.slide_out_left_noalpha);
	}

	private void setToolTipDismissed()
	{
		if (mInviteToolTip.getVisibility() == View.VISIBLE) {
			Animation alphaOut = AnimationUtils.loadAnimation(
					MessagesList.this, android.R.anim.fade_out);
			alphaOut.setDuration(200);
			mInviteToolTip.setAnimation(alphaOut);
			mInviteToolTip.setVisibility(View.INVISIBLE);
		}

		Editor editor = getSharedPreferences(HikeMessengerApp.ACCOUNT_SETTINGS, 0).edit();
		editor.putBoolean(HikeMessengerApp.MESSAGES_LIST_TOOLTIP_DISMISSED, true);
		editor.commit();
	}

	public void onToolTipClosed(View v)
	{
		Utils.logEvent(MessagesList.this, HikeConstants.LogEvent.HOME_TOOL_TIP_CLOSED);
		setToolTipDismissed();
	}

	public void onTitleIconClick(View v)
	{
		setToolTipDismissed();
		showCreditsScreen();
	}

	public void onToolTipClicked(View v)
	{
		Utils.logEvent(MessagesList.this, HikeConstants.LogEvent.HOME_TOOL_TIP_CLICKED);
		onTitleIconClick(null);
	}

	private void leaveGroup(Conversation conv)
	{
		HikeMessengerApp.getPubSub().publish(HikePubSub.MQTT_PUBLISH, conv.serialize(NetworkManager.GROUP_CHAT_LEAVE));
		DeleteConversationsAsyncTask task = new DeleteConversationsAsyncTask();
		task.execute(conv);
	}
}
