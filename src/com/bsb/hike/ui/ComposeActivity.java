package com.bsb.hike.ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.json.JSONException;
import org.json.JSONObject;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import com.actionbarsherlock.app.ActionBar;
import com.bsb.hike.HikeConstants;
import com.bsb.hike.HikeMessengerApp;
import com.bsb.hike.HikePubSub;
import com.bsb.hike.R;
import com.bsb.hike.adapters.HikeSearchContactAdapter;
import com.bsb.hike.db.HikeConversationsDatabase;
import com.bsb.hike.db.HikeUserDatabase;
import com.bsb.hike.models.ContactInfo;
import com.bsb.hike.models.ConvMessage;
import com.bsb.hike.models.GroupConversation;
import com.bsb.hike.models.GroupParticipant;
import com.bsb.hike.utils.HikeAppStateBaseFragmentActivity;
import com.bsb.hike.utils.Utils;

public class ComposeActivity extends HikeAppStateBaseFragmentActivity implements
		OnItemClickListener {

	private EditText mInputNumberView;
	private ListView mContactList;
	private Set<ContactInfo> selectedContactSet;
	private Button doneBtn;
	private TextView title;
	private ImageView backIcon;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.compose);

		selectedContactSet = new HashSet<ContactInfo>();

		setupActionBar();

		mInputNumberView = (EditText) findViewById(R.id.search_text);
		mContactList = (ListView) findViewById(R.id.compose_list);

		mContactList.setEmptyView(findViewById(android.R.id.empty));

		init();
		new CreateAutoCompleteViewTask().execute();
	}

	private void init() {
		selectedContactSet.clear();
		doneBtn.setVisibility(View.GONE);
		backIcon.setImageResource(R.drawable.ic_back);
		getActionBar().setBackgroundDrawable(
				getResources().getDrawable(R.drawable.bg_header));
	}

	private void setupActionBar() {
		ActionBar actionBar = getSupportActionBar();
		actionBar.setDisplayOptions(ActionBar.DISPLAY_SHOW_CUSTOM);

		View actionBarView = LayoutInflater.from(this).inflate(
				R.layout.compose_action_bar, null);

		View backContainer = actionBarView.findViewById(R.id.back);

		backIcon = (ImageView) actionBarView.findViewById(R.id.abs__up);

		doneBtn = (Button) actionBarView.findViewById(R.id.done_btn);

		title = (TextView) actionBarView.findViewById(R.id.title);
		title.setText(R.string.new_chat);

		backContainer.setOnClickListener(new OnClickListener() {

			@Override
			public void onClick(View v) {
				if (selectedContactSet.isEmpty()) {
					Intent intent = new Intent(ComposeActivity.this,
							HomeActivity.class);
					intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
					startActivity(intent);
				} else {
					init();
					new CreateAutoCompleteViewTask().execute();
				}
			}
		});

		doneBtn.setOnClickListener(new OnClickListener() {

			@Override
			public void onClick(View v) {
				onDoneButtonClick();
			}
		});

		actionBar.setCustomView(actionBarView);

		init();
	}

	private void onDoneButtonClick() {
		Iterator<ContactInfo> iterator = selectedContactSet.iterator();
		ArrayList<ContactInfo> selectedContactList = new ArrayList<ContactInfo>(
				selectedContactSet.size());
		while (iterator.hasNext()) {
			selectedContactList.add(iterator.next());
		}

		ContactInfo conversationContactInfo = null;
		if (selectedContactList.size() == 1) {
			conversationContactInfo = selectedContactList.get(0);
		} else {
			String groupId = getIntent().getStringExtra(
					HikeConstants.Extras.EXISTING_GROUP_CHAT);
			boolean newGroup = false;

			if (TextUtils.isEmpty(groupId)) {
				// Create new group
				String uid = getSharedPreferences(
						HikeMessengerApp.ACCOUNT_SETTINGS, MODE_PRIVATE)
						.getString(HikeMessengerApp.UID_SETTING, "");
				groupId = uid + ":" + System.currentTimeMillis();
				newGroup = true;
			} else {
				// Group alredy exists. Fetch existing participants.
				newGroup = false;
			}
			Map<String, GroupParticipant> participantList = new HashMap<String, GroupParticipant>();

			for (ContactInfo particpant : selectedContactList) {
				GroupParticipant groupParticipant = new GroupParticipant(
						particpant);
				participantList.put(particpant.getMsisdn(), groupParticipant);
			}
			ContactInfo userContactInfo = Utils
					.getUserContactInfo(getSharedPreferences(
							HikeMessengerApp.ACCOUNT_SETTINGS, MODE_PRIVATE));

			GroupConversation groupConversation = new GroupConversation(
					groupId, 0, null, userContactInfo.getMsisdn(), true);
			groupConversation.setGroupParticipantList(participantList);

			Log.d(getClass().getSimpleName(), "Creating group: " + groupId);
			HikeConversationsDatabase mConversationDb = HikeConversationsDatabase
					.getInstance();
			mConversationDb.addGroupParticipants(groupId,
					groupConversation.getGroupParticipantList());
			if (newGroup) {
				mConversationDb.addConversation(groupConversation.getMsisdn(),
						false, "", groupConversation.getGroupOwner());
			}

			try {
				// Adding this boolean value to show a different system message
				// if its a new group
				JSONObject gcjPacket = groupConversation
						.serialize(HikeConstants.MqttMessageTypes.GROUP_CHAT_JOIN);
				gcjPacket.put(HikeConstants.NEW_GROUP, newGroup);

				HikeMessengerApp.getPubSub().publish(
						HikePubSub.MESSAGE_SENT,
						new ConvMessage(gcjPacket, groupConversation, this,
								true));
			} catch (JSONException e) {
				e.printStackTrace();
			}
			HikeMessengerApp
					.getPubSub()
					.publish(
							HikePubSub.MQTT_PUBLISH,
							groupConversation
									.serialize(HikeConstants.MqttMessageTypes.GROUP_CHAT_JOIN));

			conversationContactInfo = new ContactInfo(groupId, groupId,
					groupId, groupId);
		}
		Intent intent = Utils
				.createIntentFromContactInfo(conversationContactInfo);
		intent.setClass(this, ChatThread.class);
		startActivity(intent);
		finish();
	}

	private class CreateAutoCompleteViewTask extends
			AsyncTask<Void, Void, List<Pair<AtomicBoolean, ContactInfo>>> {

		private boolean isGroupChat;
		private boolean isForwardingMessage;
		private boolean isSharingFile;
		private boolean freeSMSOn;
		private boolean nativeSMSOn;
		private String userMsisdn;
		private String existingGroupId;
		boolean loadOnUiThread;

		@Override
		protected void onPreExecute() {
			isGroupChat = getIntent().getBooleanExtra(
					HikeConstants.Extras.GROUP_CHAT, false);
			isForwardingMessage = getIntent().getBooleanExtra(
					HikeConstants.Extras.FORWARD_MESSAGE, false);
			isSharingFile = getIntent().getType() != null;
			// Getting the group id. This will be a valid value if the intent
			// was passed to add group participants.
			existingGroupId = getIntent().getStringExtra(
					HikeConstants.Extras.EXISTING_GROUP_CHAT);

			// if (isSharingFile) {
			// mLabelView.setText(R.string.share_file);
			// } else if (isForwardingMessage) {
			// mLabelView.setText(R.string.forward);
			// } else if (!TextUtils.isEmpty(existingGroupId)) {
			// mLabelView.setText(R.string.add_group);
			// } else if (isGroupChat) {
			// mLabelView.setText(R.string.new_group);
			// } else {
			// mLabelView.setText(R.string.new_message);
			// }

			SharedPreferences appPref = PreferenceManager
					.getDefaultSharedPreferences(getApplicationContext());

			freeSMSOn = appPref.getBoolean(HikeConstants.FREE_SMS_PREF, true);

			nativeSMSOn = appPref
					.getBoolean(HikeConstants.SEND_SMS_PREF, false);

			userMsisdn = getSharedPreferences(
					HikeMessengerApp.ACCOUNT_SETTINGS, MODE_PRIVATE).getString(
					HikeMessengerApp.MSISDN_SETTING, "");

			loadOnUiThread = Utils.loadOnUiThread();
		}

		@Override
		protected List<Pair<AtomicBoolean, ContactInfo>> doInBackground(
				Void... params) {
			if (!loadOnUiThread) {
				return getContactsForComposeScreen(userMsisdn, freeSMSOn,
						isGroupChat, isForwardingMessage, isSharingFile,
						nativeSMSOn);
			} else {
				return null;
			}
		}

		@Override
		protected void onPostExecute(
				List<Pair<AtomicBoolean, ContactInfo>> contactList) {
			if (contactList == null) {
				contactList = getContactsForComposeScreen(userMsisdn,
						freeSMSOn, isGroupChat, isForwardingMessage,
						isSharingFile, nativeSMSOn);
			}

			mInputNumberView.setText("");
			HikeSearchContactAdapter adapter = new HikeSearchContactAdapter(
					ComposeActivity.this, contactList, mInputNumberView,
					isGroupChat, null, existingGroupId, getIntent(), freeSMSOn,
					nativeSMSOn);
			mContactList.setAdapter(adapter);
			mContactList.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
			mContactList.setOnItemClickListener(ComposeActivity.this);
			mInputNumberView.addTextChangedListener(adapter);

			getWindow().setSoftInputMode(
					WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
		}

	}

	private List<Pair<AtomicBoolean, ContactInfo>> getContactsForComposeScreen(
			String userMsisdn, boolean freeSMSOn, boolean isGroupChat,
			boolean isForwardingMessage, boolean isSharingFile,
			boolean nativeSMSOn) {
		List<Pair<AtomicBoolean, ContactInfo>> contactList = HikeUserDatabase
				.getInstance().getContactsForComposeScreen(freeSMSOn,
						(isGroupChat || isForwardingMessage || isSharingFile),
						userMsisdn, nativeSMSOn);

		if (isForwardingMessage || isSharingFile) {
			contactList.addAll(0, HikeConversationsDatabase.getInstance()
					.getGroupNameAndParticipantsAsContacts());
		}
		return contactList;
	}

	@SuppressWarnings("unchecked")
	@Override
	public void onItemClick(AdapterView<?> adapterView, View view,
			int position, long id) {
		Object tag = view.getTag();
		Pair<AtomicBoolean, ContactInfo> pair = (Pair<AtomicBoolean, ContactInfo>) tag;

		pair.first.set(!pair.first.get());
		view.setTag(pair);
		((HikeSearchContactAdapter) adapterView.getAdapter())
				.notifyDataSetChanged();

		ContactInfo contactInfo = pair.second;
		if (selectedContactSet.contains(contactInfo)) {
			selectedContactSet.remove(contactInfo);
		} else {
			selectedContactSet.add(contactInfo);
		}

		if (!selectedContactSet.isEmpty()) {
			doneBtn.setVisibility(View.VISIBLE);
			doneBtn.setText(Integer.toString(selectedContactSet.size()));
			getActionBar().setBackgroundDrawable(
					getResources().getDrawable(R.drawable.bg_header_compose));
			title.setText(selectedContactSet.size() > 1 ? R.string.new_group
					: R.string.new_chat);
			backIcon.setImageResource(R.drawable.ic_cancel);
		} else {
			init();
		}
	}

}