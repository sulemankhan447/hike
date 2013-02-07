package com.bsb.hike.ui;

import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.DialogInterface.OnCancelListener;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.provider.ContactsContract.Intents.Insert;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;
import android.view.Gravity;
import android.view.View;
import android.view.View.OnLongClickListener;
import android.view.ViewGroup;
import android.view.animation.AlphaAnimation;
import android.view.animation.AnimationSet;
import android.view.animation.ScaleAnimation;
import android.view.animation.TranslateAnimation;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.AdapterView.OnItemLongClickListener;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.LinearLayout.LayoutParams;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.bsb.hike.HikeConstants;
import com.bsb.hike.HikeMessengerApp;
import com.bsb.hike.HikePubSub;
import com.bsb.hike.HikePubSub.Listener;
import com.bsb.hike.R;
import com.bsb.hike.adapters.ProfileAdapter;
import com.bsb.hike.db.HikeConversationsDatabase;
import com.bsb.hike.db.HikeUserDatabase;
import com.bsb.hike.http.HikeHttpRequest;
import com.bsb.hike.models.ContactInfo;
import com.bsb.hike.models.ContactInfo.FavoriteType;
import com.bsb.hike.models.GroupConversation;
import com.bsb.hike.models.GroupParticipant;
import com.bsb.hike.models.HikeFile.HikeFileType;
import com.bsb.hike.models.ProfileItem;
import com.bsb.hike.models.StatusMessage;
import com.bsb.hike.models.utils.IconCacheManager;
import com.bsb.hike.tasks.DownloadImageTask;
import com.bsb.hike.tasks.DownloadImageTask.ImageDownloadResult;
import com.bsb.hike.tasks.DownloadProfileImageTask;
import com.bsb.hike.tasks.FinishableEvent;
import com.bsb.hike.tasks.HikeHTTPTask;
import com.bsb.hike.utils.DrawerBaseActivity;
import com.bsb.hike.utils.Utils;
import com.bsb.hike.utils.Utils.ExternalStorageState;
import com.fiksu.asotracking.FiksuTrackingManager;

public class ProfileActivity extends DrawerBaseActivity implements
		FinishableEvent, android.content.DialogInterface.OnClickListener,
		Listener, OnLongClickListener, OnItemClickListener,
		OnItemLongClickListener {

	private EditText mNameEdit;

	private View currentSelection;

	private Dialog mDialog;
	private String mLocalMSISDN = null;

	private ActivityState mActivityState; /* config state of this activity */
	private String nameTxt;
	private boolean isBackPressed = false;
	private EditText mEmailEdit;
	private String emailTxt;
	private Map<String, GroupParticipant> participantMap;

	private ProfileType profileType;
	private String httpRequestURL;
	private String groupOwner;

	private TextView mNameDisplay;
	private int lastSavedGender;

	private SharedPreferences preferences;

	private String[] groupInfoPubSubListeners = { HikePubSub.ICON_CHANGED,
			HikePubSub.GROUP_NAME_CHANGED, HikePubSub.GROUP_END,
			HikePubSub.PARTICIPANT_JOINED_GROUP,
			HikePubSub.PARTICIPANT_LEFT_GROUP,
			HikePubSub.PROFILE_IMAGE_DOWNLOADED,
			HikePubSub.PROFILE_IMAGE_NOT_DOWNLOADED };

	private String[] contactInfoPubSubListeners = { HikePubSub.ICON_CHANGED,
			HikePubSub.CONTACT_ADDED, HikePubSub.USER_JOINED,
			HikePubSub.USER_LEFT, HikePubSub.PROFILE_IMAGE_DOWNLOADED,
			HikePubSub.PROFILE_IMAGE_NOT_DOWNLOADED,
			HikePubSub.STATUS_MESSAGE_RECEIVED, HikePubSub.FAVORITE_TOGGLED };

	private String[] profilePubSubListeners = {
			HikePubSub.PROFILE_IMAGE_DOWNLOADED,
			HikePubSub.PROFILE_IMAGE_NOT_DOWNLOADED,
			HikePubSub.STATUS_MESSAGE_RECEIVED };

	private GroupConversation groupConversation;
	private ImageButton topBarBtn;
	private ContactInfo contactInfo;
	private boolean isBlocked;
	private boolean showMessageBtn = true;

	private static enum ProfileType {
		USER_PROFILE, // The user profile screen
		USER_PROFILE_EDIT, // The user profile edit screen
		GROUP_INFO, // The group info screen
		CONTACT_INFO // Contact info screen
	};

	private class ActivityState {
		public HikeHTTPTask task; /* the task to update the global profile */
		public DownloadImageTask downloadPicasaImageTask; /*
														 * the task to download
														 * the picasa image
														 */
		public DownloadProfileImageTask downloadProfileImageTask;

		public String destFilePath = null; /*
											 * the bitmap before the user saves
											 * it
											 */
		public int genderType;
		public boolean viewingProfileImage = false;
		public boolean animatedProfileImage = false;
		public int imageViewId = -1;
		public String id = null;
	}

	public File selectedFileIcon;
	private ListView profileContent;
	private ProfileAdapter profileAdapter;

	private List<StatusMessage> profileItems;

	private List<GroupParticipant> participantsList;

	private boolean isGroupOwner;

	/* store the task so we can keep keep the progress dialog going */
	@Override
	public Object onRetainNonConfigurationInstance() {
		Log.d("ProfileActivity", "onRetainNonConfigurationinstance");
		return mActivityState;
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		if (mDialog != null) {
			mDialog.dismiss();
		}
		if ((mActivityState != null) && (mActivityState.task != null)) {
			mActivityState.task.setActivity(null);
		}
		if (profileType == ProfileType.GROUP_INFO) {
			HikeMessengerApp.getPubSub().removeListeners(this,
					groupInfoPubSubListeners);
		} else if (profileType == ProfileType.CONTACT_INFO) {
			HikeMessengerApp.getPubSub().removeListeners(this,
					contactInfoPubSubListeners);
		} else if (profileType == ProfileType.USER_PROFILE) {
			HikeMessengerApp.getPubSub().removeListeners(this,
					profilePubSubListeners);
		}
		mActivityState = null;
	}

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		if (Utils.requireAuth(this)) {
			return;
		}

		preferences = getSharedPreferences(HikeMessengerApp.ACCOUNT_SETTINGS,
				MODE_PRIVATE);

		Object o = getLastNonConfigurationInstance();
		if (o instanceof ActivityState) {
			mActivityState = (ActivityState) o;
			if (mActivityState.task != null) {
				/* we're currently executing a task, so show the progress dialog */
				mActivityState.task.setActivity(this);
				mDialog = ProgressDialog.show(this, null, getResources()
						.getString(R.string.updating_profile));
			} else if (mActivityState.downloadPicasaImageTask != null
					|| mActivityState.downloadProfileImageTask != null) {
				mDialog = ProgressDialog.show(this, null, getResources()
						.getString(R.string.downloading_image));
				if (mActivityState.downloadProfileImageTask != null) {
					mDialog.setCancelable(true);
					setDialogOnCancelListener();
				}
			}
		} else {
			mActivityState = new ActivityState();
		}

		if (getIntent().hasExtra(HikeConstants.Extras.EXISTING_GROUP_CHAT)) {
			this.profileType = ProfileType.GROUP_INFO;
			HikeMessengerApp.getPubSub().addListeners(this,
					groupInfoPubSubListeners);
			setupGroupProfileScreen();
		} else if (getIntent().hasExtra(HikeConstants.Extras.CONTACT_INFO)) {
			this.profileType = ProfileType.CONTACT_INFO;
			this.showMessageBtn = getIntent().getBooleanExtra(
					HikeConstants.Extras.FROM_CENTRAL_TIMELINE, false);
			HikeMessengerApp.getPubSub().addListeners(this,
					contactInfoPubSubListeners);
			setupContactProfileScreen();
		} else {
			httpRequestURL = "/account";
			fetchPersistentData();

			if (getIntent().getBooleanExtra(HikeConstants.Extras.EDIT_PROFILE,
					false)) {
				this.profileType = ProfileType.USER_PROFILE_EDIT;
				setupEditScreen();
			} else {
				this.profileType = ProfileType.USER_PROFILE;
				HikeMessengerApp.getPubSub().addListeners(this,
						profilePubSubListeners);
				setupProfileScreen(savedInstanceState);
			}
		}
		if (mActivityState.viewingProfileImage) {
			downloadOrShowProfileImage(false, false, mActivityState.imageViewId);
		}
	}

	private void setupContactProfileScreen() {
		setContentView(R.layout.profile);

		TextView mTitleView = (TextView) findViewById(R.id.title);
		mTitleView.setText(R.string.user_info);

		boolean canCall = getPackageManager().hasSystemFeature(
				PackageManager.FEATURE_TELEPHONY);

		this.mLocalMSISDN = getIntent().getStringExtra(
				HikeConstants.Extras.CONTACT_INFO);

		contactInfo = HikeUserDatabase.getInstance().getContactInfoFromMSISDN(
				mLocalMSISDN, false);

		if (!contactInfo.isOnhike()) {
			contactInfo.setOnhike(getIntent().getBooleanExtra(
					HikeConstants.Extras.ON_HIKE, false));
		}

		profileItems = new ArrayList<StatusMessage>();
		setupContactProfileList();

		profileAdapter = new ProfileAdapter(this, profileItems, null,
				contactInfo, false);
		profileContent = (ListView) findViewById(R.id.profile_content);
		profileContent.setAdapter(profileAdapter);

		findViewById(R.id.button_bar).setVisibility(View.VISIBLE);
		topBarBtn = (ImageButton) findViewById(R.id.title_image_btn);
		topBarBtn.setVisibility(View.VISIBLE);
		/*
		 * This would only happen for unknown contacts.
		 */
		if (contactInfo.getMsisdn().equals(contactInfo.getId())) {
			topBarBtn.setImageResource(R.drawable.ic_add_to_contacts_top);
		} else {
			topBarBtn.setImageResource(R.drawable.ic_call_top);
		}
	}

	private void setupContactProfileList() {
		profileItems.clear();
		// Adding an item for the header
		profileItems.add(new StatusMessage(ProfileAdapter.PROFILE_HEADER_ID,
				null, null, null, null, null, 0));
		if (showMessageBtn) {
			// Adding an item for the button
			profileItems.add(new StatusMessage(
					ProfileAdapter.PROFILE_BUTTON_ID, null, null, null, null,
					null, 0));
		}
		if ((contactInfo.getFavoriteType() != FavoriteType.NOT_FAVORITE)
				&& (contactInfo.getFavoriteType() != FavoriteType.PENDING)
				&& (contactInfo.isOnhike())) {
			profileItems.addAll(HikeConversationsDatabase.getInstance()
					.getStatusMessages(mLocalMSISDN));
		} else {
			// Adding an item for the empty status
			profileItems.add(new StatusMessage(ProfileAdapter.PROFILE_EMPTY_ID,
					null, null, null, null, null, 0));
		}
	}

	private void setupGroupProfileScreen() {
		setContentView(R.layout.profile);

		TextView mTitleView = (TextView) findViewById(R.id.title);
		mTitleView.setText(R.string.group_info);

		this.mLocalMSISDN = getIntent().getStringExtra(
				HikeConstants.Extras.EXISTING_GROUP_CHAT);

		HikeConversationsDatabase hCDB = HikeConversationsDatabase
				.getInstance();
		groupConversation = (GroupConversation) hCDB.getConversation(
				mLocalMSISDN, 0);

		participantMap = groupConversation.getGroupParticipantList();
		List<String> inactiveMsisdns = new ArrayList<String>();
		/*
		 * Removing inactive participants
		 */
		for (Entry<String, GroupParticipant> participantEntry : participantMap
				.entrySet()) {
			GroupParticipant groupParticipant = participantEntry.getValue();
			if (groupParticipant.hasLeft()) {
				inactiveMsisdns.add(participantEntry.getKey());
			}
		}
		for (String msisdn : inactiveMsisdns) {
			participantMap.remove(msisdn);
		}

		httpRequestURL = "/group/" + groupConversation.getMsisdn();

		participantsList = new ArrayList<GroupParticipant>();
		profileAdapter = new ProfileAdapter(this, participantsList,
				groupConversation, null, false);

		setupGroupProfileList();

		profileContent = (ListView) findViewById(R.id.profile_content);
		profileContent.setAdapter(profileAdapter);
		profileContent.setOnItemLongClickListener(this);
		profileContent.setOnItemClickListener(this);
	}

	private void setupGroupProfileList() {
		GroupParticipant userInfo = new GroupParticipant(
				Utils.getUserContactInfo(preferences, true));

		participantsList.clear();
		// Adding an item for the header
		participantsList.add(new GroupParticipant(new ContactInfo(
				ProfileAdapter.GROUP_HEADER_ID, null, null, null)));

		List<GroupParticipant> participants = new ArrayList<GroupParticipant>(
				participantMap.values());
		participants.add(userInfo);
		Collections.sort(participants);

		boolean hasSmsUser = false;

		for (GroupParticipant participant : participants) {
			if (!participant.getContactInfo().isOnhike()) {
				hasSmsUser = true;
				break;
			}
		}

		participantsList.addAll(participants);
		// Adding an item for the button
		participantsList.add(new GroupParticipant(new ContactInfo(
				ProfileAdapter.GROUP_BUTTON_ID, null, null, null)));

		isGroupOwner = userInfo.getContactInfo().getMsisdn()
				.equals(groupConversation.getGroupOwner());

		profileAdapter.setNumParticipants(participants.size());
		profileAdapter.setHasSmsUser(hasSmsUser);
		profileAdapter.notifyDataSetChanged();
	}

	public void onTitleIconClick(View v) {
		if (profileType == ProfileType.USER_PROFILE) {
			super.onTitleIconClick(v);
			return;
		}
		if (v.getId() == R.id.title_image_btn2) {
			if (profileType == ProfileType.GROUP_INFO) {
				groupConversation.setIsMuted(!groupConversation.isMuted());
				topBarBtn
						.setImageResource(groupConversation.isMuted() ? R.drawable.ic_group_muted
								: R.drawable.ic_group_not_muted);

				HikeMessengerApp.getPubSub().publish(
						HikePubSub.MUTE_CONVERSATION_TOGGLED,
						new Pair<String, Boolean>(
								groupConversation.getMsisdn(),
								groupConversation.isMuted()));
			} else if (profileType == ProfileType.CONTACT_INFO) {
				contactInfo
						.setFavoriteType(contactInfo.getFavoriteType() == FavoriteType.FAVORITE ? FavoriteType.NOT_FAVORITE
								: FavoriteType.FAVORITE);

				((ImageView) v)
						.setImageResource(contactInfo.getFavoriteType() == FavoriteType.FAVORITE ? R.drawable.ic_favorite
								: R.drawable.ic_not_favorite);
				Pair<ContactInfo, FavoriteType> favoriteToggle = new Pair<ContactInfo, FavoriteType>(
						contactInfo, contactInfo.getFavoriteType());
				HikeMessengerApp.getPubSub().publish(
						HikePubSub.FAVORITE_TOGGLED, favoriteToggle);
			}
		} else if (v.getId() == R.id.title_image_btn) {
			if (profileType == ProfileType.CONTACT_INFO) {
				if (contactInfo.getMsisdn().equals(contactInfo.getId())) {
					onAddToContactClicked(null);
				} else {
					onCallClicked(null);
				}
			}
		}
	}

	private void setupEditScreen() {
		setContentView(R.layout.profile_edit);

		TextView mTitleView = (TextView) findViewById(R.id.title);

		ViewGroup name = (ViewGroup) findViewById(R.id.name);
		ViewGroup phone = (ViewGroup) findViewById(R.id.phone);
		ViewGroup email = (ViewGroup) findViewById(R.id.email);
		ViewGroup gender = (ViewGroup) findViewById(R.id.gender);
		ViewGroup picture = (ViewGroup) findViewById(R.id.photo);

		mNameEdit = (EditText) name.findViewById(R.id.name_input);
		mEmailEdit = (EditText) email.findViewById(R.id.email_input);

		((TextView) name.findViewById(R.id.name_edit_field))
				.setText(R.string.name);
		((TextView) phone.findViewById(R.id.phone_edit_field))
				.setText(R.string.phone_num);
		((TextView) email.findViewById(R.id.email_edit_field))
				.setText(R.string.email);
		((TextView) gender.findViewById(R.id.gender_edit_field))
				.setText(R.string.gender);
		((TextView) picture.findViewById(R.id.photo_edit_field))
				.setText(R.string.edit_picture);

		picture.setBackgroundResource(R.drawable.profile_bottom_item_selector);
		picture.setFocusable(true);

		mTitleView.setText(getResources().getString(R.string.edit_profile));
		((EditText) phone.findViewById(R.id.phone_input)).setText(mLocalMSISDN);
		((EditText) phone.findViewById(R.id.phone_input)).setEnabled(false);

		// Make sure that the name text does not exceed the permitted length
		int maxLength = getResources().getInteger(R.integer.max_length_name);
		if (nameTxt.length() > maxLength) {
			nameTxt = nameTxt.substring(0, maxLength);
		}

		mNameEdit.setText(nameTxt);
		mEmailEdit.setText(emailTxt);

		mNameEdit.setSelection(nameTxt.length());
		mEmailEdit.setSelection(emailTxt.length());

		onEmoticonClick(mActivityState.genderType == 0 ? null
				: mActivityState.genderType == 1 ? gender
						.findViewById(R.id.guy) : gender
						.findViewById(R.id.girl));

		// Hide the cursor initially
		Utils.hideCursor(mNameEdit, getResources());
	}

	private void setupProfileScreen(Bundle savedInstanceState) {
		setContentView(R.layout.profile);
		afterSetContentView(savedInstanceState);

		ContactInfo myInfo = Utils.getUserContactInfo(preferences);

		profileItems = new ArrayList<StatusMessage>();
		// Adding an item for the header
		profileItems.add(new StatusMessage(ProfileAdapter.PROFILE_HEADER_ID,
				null, myInfo.getMsisdn(), myInfo.getName(), null, null, 0));
		// Adding an item for the button
		profileItems.add(new StatusMessage(ProfileAdapter.PROFILE_BUTTON_ID,
				null, null, null, null, null, 0));
		profileItems.addAll(HikeConversationsDatabase.getInstance()
				.getStatusMessages(mLocalMSISDN));

		profileAdapter = new ProfileAdapter(this, profileItems, null, myInfo,
				true);

		profileContent = (ListView) findViewById(R.id.profile_content);
		profileContent.setAdapter(profileAdapter);

		TextView mTitleView = (TextView) findViewById(R.id.title_centered);
		mTitleView.setText(getResources().getString(R.string.profile_title));
	}

	private void fetchPersistentData() {
		nameTxt = preferences.getString(HikeMessengerApp.NAME, "Set a name!");
		mLocalMSISDN = preferences.getString(HikeMessengerApp.MSISDN_SETTING,
				null);
		emailTxt = preferences.getString(HikeConstants.Extras.EMAIL, "");
		lastSavedGender = preferences.getInt(HikeConstants.Extras.GENDER, 0);
		mActivityState.genderType = mActivityState.genderType == 0 ? lastSavedGender
				: mActivityState.genderType;
	}

	public void onBackPressed() {
		if (mActivityState.viewingProfileImage) {
			View profileContainer = findViewById(R.id.profile_image_container);

			animateProfileImage(false, mActivityState.imageViewId);
			profileContainer.setVisibility(View.GONE);

			mActivityState.viewingProfileImage = false;
			mActivityState.animatedProfileImage = false;
			expandedWithoutAnimation = false;
			return;
		}
		if (this.profileType == ProfileType.USER_PROFILE_EDIT
				|| this.profileType == ProfileType.GROUP_INFO) {
			isBackPressed = true;
			saveChanges();
			overridePendingTransition(R.anim.slide_in_left_noalpha,
					R.anim.slide_out_right_noalpha);
		} else {
			if (this.profileType == ProfileType.USER_PROFILE) {
				super.onBackPressed();
			} else {
				finish();
			}
		}
	}

	public void onProfileItemClick(View v) {
		ProfileItem item = (ProfileItem) v.getTag(R.id.profile);
		Intent intent = item.getIntent(ProfileActivity.this);
		if (intent != null) {
			startActivity(intent);
		}
	}

	public void saveChanges() {
		ArrayList<HikeHttpRequest> requests = new ArrayList<HikeHttpRequest>();

		if (this.profileType == ProfileType.USER_PROFILE_EDIT
				&& !TextUtils.isEmpty(mEmailEdit.getText())) {
			if (!Utils.isValidEmail(mEmailEdit.getText())) {
				Toast.makeText(this,
						getResources().getString(R.string.invalid_email),
						Toast.LENGTH_LONG).show();
				return;
			}
		}

		if (mNameEdit != null) {
			final String newName = mNameEdit.getText().toString().trim();
			if (!TextUtils.isEmpty(newName) && !nameTxt.equals(newName)) {
				/* user edited the text, so update the profile */
				HikeHttpRequest request = new HikeHttpRequest(httpRequestURL
						+ "/name", new HikeHttpRequest.HikeHttpCallback() {
					public void onFailure() {
						if (isBackPressed) {
							finishEditing();
						}
					}

					public void onSuccess(JSONObject response) {
						if (ProfileActivity.this.profileType != ProfileType.GROUP_INFO) {
							/*
							 * if the request was successful, update the shared
							 * preferences and the UI
							 */
							String name = newName;
							Editor editor = preferences.edit();
							editor.putString(HikeMessengerApp.NAME_SETTING,
									name);
							editor.commit();
							HikeMessengerApp.getPubSub().publish(
									HikePubSub.PROFILE_NAME_CHANGED, null);
						} else {
							HikeConversationsDatabase hCDB = HikeConversationsDatabase
									.getInstance();
							hCDB.setGroupName(
									ProfileActivity.this.mLocalMSISDN, newName);
						}
						if (isBackPressed) {
							finishEditing();
						}
					}
				});

				JSONObject json = new JSONObject();
				try {
					json.put("name", newName);
					request.setJSONData(json);
				} catch (JSONException e) {
					Log.e("ProfileActivity", "Could not set name", e);
				}
				requests.add(request);
			}
		}

		if (mActivityState.destFilePath != null) {
			/* the server only needs a smaller version */
			final Bitmap smallerBitmap = Utils.scaleDownImage(
					mActivityState.destFilePath,
					HikeConstants.PROFILE_IMAGE_DIMENSIONS, true);

			final byte[] bytes = Utils.bitmapToBytes(smallerBitmap,
					Bitmap.CompressFormat.JPEG, 100);

			profileAdapter.setProfilePreview(smallerBitmap);

			HikeHttpRequest request = new HikeHttpRequest(httpRequestURL
					+ "/avatar", new HikeHttpRequest.HikeHttpCallback() {
				public void onFailure() {
					Log.d("ProfileActivity", "resetting image");
					Utils.removeTempProfileImage(mLocalMSISDN);
					mActivityState.destFilePath = null;
					profileAdapter.notifyDataSetChanged();
					if (isBackPressed) {
						finishEditing();
					}
				}

				public void onSuccess(JSONObject response) {
					HikeUserDatabase db = HikeUserDatabase.getInstance();
					db.setIcon(mLocalMSISDN, bytes, false);

					Utils.renameTempProfileImage(mLocalMSISDN);

					if (profileType == ProfileType.USER_PROFILE
							|| profileType == ProfileType.USER_PROFILE_EDIT) {
						HikeMessengerApp.getPubSub().publish(
								HikePubSub.PROFILE_PIC_CHANGED, null);
					}
					if (isBackPressed) {
						finishEditing();
					}
				}
			});

			request.setFilePath(mActivityState.destFilePath);
			requests.add(request);
		}

		if (this.profileType == ProfileType.USER_PROFILE_EDIT
				&& ((!emailTxt.equals(mEmailEdit.getText().toString())) || ((mActivityState.genderType != lastSavedGender)))) {
			HikeHttpRequest request = new HikeHttpRequest(httpRequestURL
					+ "/profile", new HikeHttpRequest.HikeHttpCallback() {
				public void onFailure() {
					if (isBackPressed) {
						finishEditing();
					}
				}

				public void onSuccess(JSONObject response) {
					Editor editor = preferences.edit();
					if (Utils.isValidEmail(mEmailEdit.getText())) {
						editor.putString(HikeConstants.Extras.EMAIL, mEmailEdit
								.getText().toString());
					}
					editor.putInt(
							HikeConstants.Extras.GENDER,
							currentSelection != null ? (currentSelection
									.getId() == R.id.guy ? 1 : 2) : 0);
					editor.commit();
					if (isBackPressed) {
						finishEditing();
					}
				}
			});
			JSONObject obj = new JSONObject();
			try {
				Log.d(getClass().getSimpleName(), "Profile details Email: "
						+ mEmailEdit.getText() + " Gender: "
						+ mActivityState.genderType);
				if (!emailTxt.equals(mEmailEdit.getText().toString())) {
					obj.put(HikeConstants.EMAIL, mEmailEdit.getText());
				}
				if (mActivityState.genderType != lastSavedGender) {
					obj.put(HikeConstants.GENDER,
							mActivityState.genderType == 1 ? "m"
									: mActivityState.genderType == 2 ? "f" : "");
				}
				Log.d(getClass().getSimpleName(),
						"JSON to be sent is: " + obj.toString());
				request.setJSONData(obj);
			} catch (JSONException e) {
				Log.e("ProfileActivity", "Could not set email or gender", e);
			}
			requests.add(request);
		}

		if (!requests.isEmpty()) {
			mDialog = ProgressDialog.show(this, null,
					getResources().getString(R.string.updating_profile));
			mActivityState.task = new HikeHTTPTask(this,
					R.string.update_profile_failed);
			HikeHttpRequest[] r = new HikeHttpRequest[requests.size()];
			requests.toArray(r);
			mActivityState.task.execute(r);
		} else if (isBackPressed) {
			finishEditing();
		}
	}

	private void finishEditing() {
		if (this.profileType != ProfileType.GROUP_INFO) {
			Intent i = new Intent(this, ProfileActivity.class);
			i.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
			startActivity(i);
		}
		finish();
	}

	protected String getLargerIconId() {
		return mLocalMSISDN + "::large";
	}

	@Override
	public void onFinish(boolean success) {
		if (mDialog != null) {
			mDialog.dismiss();
			mDialog = null;
		}

		mActivityState = new ActivityState();
	}

	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		String path = null;
		if (resultCode != RESULT_OK) {
			return;
		}

		String directory = HikeConstants.HIKE_MEDIA_DIRECTORY_ROOT
				+ HikeConstants.PROFILE_ROOT;
		String fileName = Utils.getTempProfileImageFileName(mLocalMSISDN);
		final String destFilePath = directory + "/" + fileName;

		switch (requestCode) {
		case HikeConstants.CAMERA_RESULT:
			/* fall-through on purpose */
		case HikeConstants.GALLERY_RESULT:
			Log.d("ProfileActivity", "The activity is " + this);
			if (requestCode == HikeConstants.CAMERA_RESULT) {
				String filePath = preferences.getString(
						HikeMessengerApp.FILE_PATH, "");
				selectedFileIcon = new File(filePath);

				/*
				 * Removing this key. We no longer need this.
				 */
				Editor editor = preferences.edit();
				editor.remove(HikeMessengerApp.FILE_PATH);
				editor.commit();
			}
			if (requestCode == HikeConstants.CAMERA_RESULT
					&& !selectedFileIcon.exists()) {
				Toast.makeText(getApplicationContext(), R.string.error_capture,
						Toast.LENGTH_SHORT).show();
				return;
			}
			boolean isPicasaImage = false;
			Uri selectedFileUri = null;
			if (requestCode == HikeConstants.CAMERA_RESULT) {
				path = selectedFileIcon.getAbsolutePath();
			} else {
				selectedFileUri = data.getData();
				if (Utils.isPicasaUri(selectedFileUri.toString())) {
					isPicasaImage = true;
					path = Utils.getOutputMediaFile(HikeFileType.PROFILE, null,
							null).getAbsolutePath();
				} else {
					String fileUriStart = "file://";
					String fileUriString = selectedFileUri.toString();
					if (fileUriString.startsWith(fileUriStart)) {
						selectedFileIcon = new File(URI.create(fileUriString));
						/*
						 * Done to fix the issue in a few Sony devices.
						 */
						path = selectedFileIcon.getAbsolutePath();
					} else {
						path = Utils.getRealPathFromUri(selectedFileUri, this);
					}
				}
			}
			if (TextUtils.isEmpty(path)) {
				Toast.makeText(getApplicationContext(), R.string.error_capture,
						Toast.LENGTH_SHORT).show();
				return;
			}
			if (!isPicasaImage) {
				Utils.startCropActivity(this, path, destFilePath);
			} else {
				final File destFile = new File(path);
				mActivityState.downloadPicasaImageTask = new DownloadImageTask(
						getApplicationContext(), destFile, selectedFileUri,
						new ImageDownloadResult() {

							@Override
							public void downloadFinished(boolean result) {
								if (mDialog != null) {
									mDialog.dismiss();
									mDialog = null;
								}
								mActivityState = new ActivityState();
								if (!result) {
									Toast.makeText(getApplicationContext(),
											R.string.error_download,
											Toast.LENGTH_SHORT).show();
								} else {
									Utils.startCropActivity(
											ProfileActivity.this,
											destFile.getAbsolutePath(),
											destFilePath);
								}
							}
						});
				mActivityState.downloadPicasaImageTask.execute();
				mDialog = ProgressDialog.show(this, null, getResources()
						.getString(R.string.downloading_image));
			}
			break;
		case HikeConstants.CROP_RESULT:
			mActivityState.destFilePath = data
					.getStringExtra(MediaStore.EXTRA_OUTPUT);
			if ((this.profileType == ProfileType.USER_PROFILE)
					|| (this.profileType == ProfileType.GROUP_INFO)) {
				saveChanges();
			}
			break;
		}
	}

	@Override
	public void onClick(DialogInterface dialog, int item) {
		Intent intent = null;
		switch (item) {
		case HikeConstants.PROFILE_PICTURE_FROM_CAMERA:
			if (Utils.getExternalStorageState() != ExternalStorageState.WRITEABLE) {
				Toast.makeText(getApplicationContext(),
						R.string.no_external_storage, Toast.LENGTH_SHORT)
						.show();
				return;
			}
			intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
			selectedFileIcon = Utils.getOutputMediaFile(HikeFileType.PROFILE,
					null, null); // create a file to save the image
			if (selectedFileIcon != null) {
				intent.putExtra(MediaStore.EXTRA_OUTPUT,
						Uri.fromFile(selectedFileIcon));

				/*
				 * Saving the file path. Will use this to get the file once the
				 * image has been captured.
				 */
				Editor editor = preferences.edit();
				editor.putString(HikeMessengerApp.FILE_PATH,
						selectedFileIcon.getAbsolutePath());
				editor.commit();

				startActivityForResult(intent, HikeConstants.CAMERA_RESULT);
				overridePendingTransition(R.anim.slide_in_right_noalpha,
						R.anim.slide_out_left_noalpha);
			} else {
				Toast.makeText(this, getString(R.string.no_sd_card),
						Toast.LENGTH_LONG).show();
			}
			break;
		case HikeConstants.PROFILE_PICTURE_FROM_GALLERY:
			if (Utils.getExternalStorageState() == ExternalStorageState.NONE) {
				Toast.makeText(getApplicationContext(),
						R.string.no_external_storage, Toast.LENGTH_SHORT)
						.show();
				return;
			}
			intent = new Intent(Intent.ACTION_PICK);
			intent.setType("image/*");
			startActivityForResult(intent, HikeConstants.GALLERY_RESULT);
			overridePendingTransition(R.anim.slide_in_right_noalpha,
					R.anim.slide_out_left_noalpha);
			break;
		}
	}

	public void onEmoticonClick(View v) {
		if (v != null) {
			if (currentSelection != null) {
				currentSelection.setSelected(false);
			}
			v.setSelected(currentSelection != v);
			currentSelection = v == currentSelection ? null : v;
			if (currentSelection != null) {
				mActivityState.genderType = currentSelection.getId() == R.id.guy ? 1
						: 2;
				return;
			}
			mActivityState.genderType = 0;
		}
	}

	public void onViewImageClicked(View v) {
		StatusMessage statusMessage = (StatusMessage) v.getTag();
		mActivityState.imageViewId = v.getId();
		mActivityState.id = (statusMessage == null || statusMessage
				.getMappedId() == null) ? mLocalMSISDN : statusMessage
				.getMappedId();
		downloadOrShowProfileImage(true, false, v.getId());
	}

	private void downloadOrShowProfileImage(boolean startNewDownload,
			boolean justDownloaded, int viewId) {
		if (Utils.getExternalStorageState() == ExternalStorageState.NONE) {
			Toast.makeText(getApplicationContext(),
					R.string.no_external_storage, Toast.LENGTH_SHORT).show();
			return;
		}

		StatusMessage statusMessage = (StatusMessage) findViewById(viewId)
				.getTag();

		boolean statusImage = false;
		String id = mLocalMSISDN;
		if (profileType != ProfileType.GROUP_INFO
				&& statusMessage.getMappedId() != null) {
			id = statusMessage.getMappedId();
			statusImage = true;
		}

		String basePath = HikeConstants.HIKE_MEDIA_DIRECTORY_ROOT
				+ HikeConstants.PROFILE_ROOT;

		boolean hasCustomImage = HikeUserDatabase.getInstance().hasIcon(id);

		String fileName = hasCustomImage ? Utils.getProfileImageFileName(id)
				: Utils.getDefaultAvatarServerName(this, id);

		File file = new File(basePath, fileName);

		if (file.exists()) {
			showLargerImage(
					BitmapDrawable.createFromPath(basePath + "/" + fileName),
					justDownloaded, viewId);
		} else {
			showLargerImage(
					IconCacheManager.getInstance().getIconForMSISDN(
							mLocalMSISDN), justDownloaded, viewId);
			if (startNewDownload) {
				mActivityState.downloadProfileImageTask = new DownloadProfileImageTask(
						getApplicationContext(), id, fileName, hasCustomImage,
						statusImage);
				mActivityState.downloadProfileImageTask.execute();

				mDialog = ProgressDialog.show(this, null, getResources()
						.getString(R.string.downloading_image));
				mDialog.setCancelable(true);
				setDialogOnCancelListener();
			}
		}
	}

	private void setDialogOnCancelListener() {
		mDialog.setOnCancelListener(new OnCancelListener() {

			@Override
			public void onCancel(DialogInterface dialog) {
				if (mActivityState.downloadProfileImageTask != null) {
					mActivityState.downloadProfileImageTask.cancel(true);
				}
				mActivityState = new ActivityState();
			}
		});
	}

	public void hideLargerImage(View v) {
		onBackPressed();
	}

	private boolean expandedWithoutAnimation = false;

	private void showLargerImage(Drawable image, boolean justDownloaded,
			int viewId) {
		mActivityState.viewingProfileImage = true;

		ViewGroup profileImageContainer = (ViewGroup) findViewById(R.id.profile_image_container);
		profileImageContainer.setVisibility(View.VISIBLE);

		ImageView profileImageLarge = (ImageView) findViewById(R.id.profile_image_large);

		if (!justDownloaded) {
			if (!mActivityState.animatedProfileImage) {
				mActivityState.animatedProfileImage = true;
				animateProfileImage(true, viewId);
			} else {
				expandedWithoutAnimation = true;
				((LinearLayout) profileImageContainer)
						.setGravity(Gravity.CENTER);
				LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT,
						LayoutParams.MATCH_PARENT);
				profileImageLarge.setLayoutParams(lp);
			}
		}

		((ImageView) findViewById(R.id.profile_image_large))
				.setImageDrawable(image);
	}

	private void animateProfileImage(boolean expand, int viewId) {
		ViewGroup profileImageContainer = (ViewGroup) findViewById(R.id.profile_image_container);
		((LinearLayout) profileImageContainer).setGravity(Gravity.NO_GRAVITY);
		ImageView profileImageLarge = (ImageView) findViewById(R.id.profile_image_large);
		ImageView profileImageSmall = (ImageView) findViewById(viewId);

		if (profileImageSmall != null) {
			int maxWidth = (expand || !expandedWithoutAnimation) ? getResources()
					.getDisplayMetrics().widthPixels : profileImageSmall
					.getMeasuredHeight();
			int maxHeight = (expand || !expandedWithoutAnimation) ? getResources()
					.getDisplayMetrics().heightPixels : profileImageSmall
					.getMeasuredHeight();

			int screenHeight = getResources().getDisplayMetrics().heightPixels;

			int startWidth = (expand || !expandedWithoutAnimation) ? profileImageSmall
					.getWidth() : profileImageLarge.getWidth();
			int startHeight = (expand || !expandedWithoutAnimation) ? profileImageSmall
					.getHeight() : profileImageLarge.getHeight();

			int[] startLocations = new int[2];
			if (expand || mActivityState.animatedProfileImage) {
				profileImageSmall.getLocationOnScreen(startLocations);
			} else {
				profileImageLarge.getLocationOnScreen(startLocations);
			}

			int statusBarHeight = screenHeight
					- profileImageContainer.getHeight();

			int startLocX = startLocations[0];
			int startLocY = startLocations[1] - statusBarHeight;

			LayoutParams startLp = new LayoutParams(startWidth, startHeight);
			startLp.setMargins(startLocX, startLocY, 0, 0);

			profileImageLarge.setLayoutParams(startLp);

			float multiplier;
			if (maxWidth > maxHeight) {
				multiplier = maxHeight / startHeight;
			} else {
				multiplier = maxWidth / startWidth;
			}

			ScaleAnimation scaleAnimation = (expand || expandedWithoutAnimation) ? new ScaleAnimation(
					1.0f, multiplier, 1.0f, multiplier) : new ScaleAnimation(
					multiplier, 1.0f, multiplier, 1.0f);

			int xDest;
			int yDest;
			if (expand || !expandedWithoutAnimation) {
				xDest = maxWidth / 2;
				xDest -= (((int) (startWidth * multiplier)) / 2) + startLocX;
				yDest = maxHeight / 2;
				yDest -= (((int) (startHeight * multiplier)) / 2) + startLocY;
			} else {
				int[] endLocations = new int[2];
				profileImageSmall.getLocationInWindow(endLocations);
				xDest = endLocations[0];
				yDest = endLocations[1];
			}

			TranslateAnimation translateAnimation = (expand || expandedWithoutAnimation) ? new TranslateAnimation(
					0, xDest, 0, yDest) : new TranslateAnimation(xDest, 0,
					yDest, 0);

			AnimationSet animationSet = new AnimationSet(true);
			animationSet.addAnimation(scaleAnimation);
			animationSet.addAnimation(translateAnimation);
			animationSet.setFillAfter(true);
			animationSet.setDuration(350);
			animationSet.setStartOffset(expand ? 150 : 0);

			profileImageLarge.startAnimation(animationSet);
		}
		AlphaAnimation alphaAnimation = expand ? new AlphaAnimation(0.0f, 1.0f)
				: new AlphaAnimation(1.0f, 0.0f);
		alphaAnimation.setDuration(200);
		alphaAnimation.setStartOffset(expand ? 0 : 200);
		profileImageContainer.startAnimation(alphaAnimation);
	}

	public void onChangeImageClicked(View v) {
		/*
		 * The wants to change their profile picture. Open a dialog to allow
		 * them pick Camera or Gallery
		 */
		final CharSequence[] items = getResources().getStringArray(
				R.array.profile_pic_dialog);
		AlertDialog.Builder builder = new AlertDialog.Builder(this);
		builder.setTitle(R.string.choose_picture);
		builder.setItems(items, this);
		mDialog = builder.show();
	}

	public void onEditProfileClicked(View v) {
		Utils.logEvent(ProfileActivity.this,
				HikeConstants.LogEvent.EDIT_PROFILE);
		Intent i = new Intent(ProfileActivity.this, ProfileActivity.class);
		i.putExtra(HikeConstants.Extras.EDIT_PROFILE, true);
		startActivity(i);
		finish();
	}

	public void onProfileLargeBtnClick(View v) {
		switch (profileType) {
		case GROUP_INFO:
			for (Entry<String, GroupParticipant> participant : participantMap
					.entrySet()) {
				if (!participant.getValue().getContactInfo().isOnhike()
						&& !participant.getValue().hasLeft()) {
					HikeMessengerApp.getPubSub().publish(
							HikePubSub.MQTT_PUBLISH,
							Utils.makeHike2SMSInviteMessage(
									participant.getKey(), this).serialize());
				}
			}
			Toast toast = Toast.makeText(ProfileActivity.this,
					R.string.invite_sent, Toast.LENGTH_SHORT);
			toast.setGravity(Gravity.BOTTOM, 0, 0);
			toast.show();
			break;
		case CONTACT_INFO:
			openChatThread(contactInfo);
			break;
		case USER_PROFILE:
			showStatusDialog(false);
			break;
		}
	}

	private void openChatThread(ContactInfo contactInfo) {
		Intent intent = Utils.createIntentFromContactInfo(contactInfo);
		intent.setClass(this, ChatThread.class);
		startActivity(intent);
	}

	public void onProfileSmallLeftBtnClick(View v) {
		Utils.logEvent(ProfileActivity.this,
				HikeConstants.LogEvent.ADD_PARTICIPANT);

		Intent intent = new Intent(ProfileActivity.this, ChatThread.class);
		intent.putExtra(HikeConstants.Extras.GROUP_CHAT, true);
		intent.putExtra(HikeConstants.Extras.EXISTING_GROUP_CHAT, mLocalMSISDN);
		startActivity(intent);

		overridePendingTransition(R.anim.slide_in_right_noalpha,
				R.anim.slide_out_left_noalpha);
	}

	public void onProfileSmallRightBtnClick(View v) {
		AlertDialog.Builder builder = new AlertDialog.Builder(this);
		builder.setMessage(R.string.leave_group_confirm);
		builder.setPositiveButton(R.string.yes,
				new DialogInterface.OnClickListener() {

					@Override
					public void onClick(DialogInterface dialog, int which) {
						Intent intent = new Intent(ProfileActivity.this,
								MessagesList.class);
						intent.putExtra(HikeConstants.Extras.GROUP_LEFT,
								mLocalMSISDN);
						intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
						startActivity(intent);
						finish();
						overridePendingTransition(R.anim.slide_in_left_noalpha,
								R.anim.slide_out_right_noalpha);
					}
				});
		builder.setNegativeButton(R.string.no,
				new DialogInterface.OnClickListener() {

					@Override
					public void onClick(DialogInterface dialog, int which) {
					}
				});
		builder.setCancelable(true);
		AlertDialog alertDialog = builder.create();
		alertDialog.show();
	}

	public void onProfileNegativeBtnClick(View v) {
		if (contactInfo.isOnhike()) {
			contactInfo.setFavoriteType(FavoriteType.PENDING);

			Pair<ContactInfo, FavoriteType> favoriteToggle = new Pair<ContactInfo, FavoriteType>(
					contactInfo, contactInfo.getFavoriteType());
			HikeMessengerApp.getPubSub().publish(HikePubSub.FAVORITE_TOGGLED,
					favoriteToggle);
			Toast.makeText(getApplicationContext(),
					"Waiting for your friend to accept the request",
					Toast.LENGTH_SHORT).show();
		} else {
			inviteToHike(contactInfo.getMsisdn());
		}
	}

	public void onBlockGroupOwnerClicked(View v) {
		Button blockBtn = (Button) v;
		HikeMessengerApp.getPubSub().publish(
				isBlocked ? HikePubSub.UNBLOCK_USER : HikePubSub.BLOCK_USER,
				this.groupOwner);
		isBlocked = !isBlocked;
		blockBtn.setText(!isBlocked ? R.string.block_owner
				: R.string.unblock_owner);
	}

	public void onEditGroupNameClicked(View v) {
		InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
		imm.toggleSoftInput(InputMethodManager.SHOW_FORCED,
				InputMethodManager.HIDE_IMPLICIT_ONLY);
		Log.d(getClass().getSimpleName(), "ONSHOWN");
		mNameDisplay.setVisibility(View.GONE);
		mNameEdit.setVisibility(View.VISIBLE);
		mNameEdit.setSelection(mNameEdit.length());
		mNameEdit.requestFocus();
	}

	public void onAddToContactClicked(View v) {
		Utils.logEvent(this, HikeConstants.LogEvent.MENU_ADD_TO_CONTACTS);
		addToContacts(mLocalMSISDN);
	}

	private void addToContacts(String msisdn) {
		Intent i = new Intent(Intent.ACTION_INSERT_OR_EDIT);
		i.setType(ContactsContract.Contacts.CONTENT_ITEM_TYPE);
		i.putExtra(Insert.PHONE, msisdn);
		startActivity(i);
	}

	public void onInviteToHikeClicked(View v) {
		inviteToHike(contactInfo.getMsisdn());
	}

	private void inviteToHike(String msisdn) {
		FiksuTrackingManager.uploadPurchaseEvent(this, HikeConstants.INVITE,
				HikeConstants.INVITE_SENT, HikeConstants.CURRENCY);
		HikeMessengerApp.getPubSub().publish(HikePubSub.MQTT_PUBLISH,
				Utils.makeHike2SMSInviteMessage(msisdn, this).serialize());

		Toast toast = Toast.makeText(ProfileActivity.this,
				R.string.invite_sent, Toast.LENGTH_SHORT);
		toast.setGravity(Gravity.BOTTOM, 0, 0);
		toast.show();
	}

	public void onCallClicked(View v) {
		Utils.logEvent(this, HikeConstants.LogEvent.MENU_CALL);
		Intent callIntent = new Intent(Intent.ACTION_CALL);
		callIntent.setData(Uri.parse("tel:" + mLocalMSISDN));
		startActivity(callIntent);
	}

	public void onBlockUserClicked(View v) {
		Button blockBtn = (Button) v;
		HikeMessengerApp.getPubSub().publish(
				isBlocked ? HikePubSub.UNBLOCK_USER : HikePubSub.BLOCK_USER,
				this.mLocalMSISDN);
		isBlocked = !isBlocked;
		blockBtn.setText(!isBlocked ? R.string.block_user
				: R.string.unblock_user);
	}

	@Override
	public void onEventReceived(final String type, Object object) {
		// Only execute the super class method if we are in a drawer activity
		if (profileType == ProfileType.USER_PROFILE) {
			super.onEventReceived(type, object);
		}
		if (mLocalMSISDN == null) {
			Log.w(getClass().getSimpleName(),
					"The msisdn is null, we are doing something wrong.."
							+ object);
			return;
		}
		if (HikePubSub.GROUP_NAME_CHANGED.equals(type)) {
			if (mLocalMSISDN.equals((String) object)) {
				HikeConversationsDatabase db = HikeConversationsDatabase
						.getInstance();
				nameTxt = db.getGroupName(mLocalMSISDN);
				groupConversation.setContactName(nameTxt);

				runOnUiThread(new Runnable() {
					@Override
					public void run() {
						profileAdapter
								.updateGroupConversation(groupConversation);
					}
				});
			}
		} else if (HikePubSub.ICON_CHANGED.equals(type)) {
			if (mLocalMSISDN.equals((String) object)) {
				runOnUiThread(new Runnable() {
					@Override
					public void run() {
						profileAdapter.notifyDataSetChanged();
					}
				});
			}
		}

		if (HikePubSub.PARTICIPANT_LEFT_GROUP.equals(type)) {
			if (mLocalMSISDN.equals(((JSONObject) object)
					.optString(HikeConstants.TO))) {
				String msisdn = ((JSONObject) object)
						.optString(HikeConstants.DATA);
				this.participantMap.remove(msisdn);
				runOnUiThread(new Runnable() {
					@Override
					public void run() {
						setupGroupProfileList();
					}
				});
			}
		} else if (HikePubSub.PARTICIPANT_JOINED_GROUP.equals(type)) {
			if (mLocalMSISDN.equals(((JSONObject) object)
					.optString(HikeConstants.TO))) {
				final JSONObject obj = (JSONObject) object;
				final JSONArray participants = obj
						.optJSONArray(HikeConstants.DATA);
				for (int i = 0; i < participants.length(); i++) {
					String msisdn = participants.optJSONObject(i).optString(
							HikeConstants.MSISDN);

					HikeUserDatabase hUDB = HikeUserDatabase.getInstance();
					ContactInfo participant = hUDB.getContactInfoFromMSISDN(
							msisdn, false);

					if (TextUtils.isEmpty(participant.getName())) {
						HikeConversationsDatabase hCDB = HikeConversationsDatabase
								.getInstance();
						participant.setName(hCDB.getParticipantName(
								mLocalMSISDN, msisdn));
					}

					participantMap.put(msisdn,
							new GroupParticipant(participant));
				}
				runOnUiThread(new Runnable() {
					@Override
					public void run() {
						setupGroupProfileList();
					}
				});
			}
		} else if (HikePubSub.GROUP_END.equals(type)) {
			mLocalMSISDN.equals(((JSONObject) object)
					.optString(HikeConstants.TO));
			{
				runOnUiThread(new Runnable() {
					@Override
					public void run() {
						ProfileActivity.this.finish();
					}
				});
			}
		} else if (HikePubSub.CONTACT_ADDED.equals(type)) {
			final ContactInfo contact = (ContactInfo) object;
			if (!this.mLocalMSISDN.equals(contact.getMsisdn())) {
				return;
			}
			this.contactInfo = contact;
			runOnUiThread(new Runnable() {
				@Override
				public void run() {
					profileAdapter.updateContactInfo(contactInfo);
					if (topBarBtn != null) {
						topBarBtn.setImageResource(R.drawable.ic_call_top);
					}
				}
			});
		} else if (HikePubSub.USER_JOINED.equals(type)
				|| HikePubSub.USER_LEFT.equals(type)) {
			if (!mLocalMSISDN.equals((String) object)) {
				return;
			}
			runOnUiThread(new Runnable() {
				@Override
				public void run() {
					profileAdapter.notifyDataSetChanged();
				}
			});
		} else if (HikePubSub.PROFILE_IMAGE_DOWNLOADED.equals(type)
				|| HikePubSub.PROFILE_IMAGE_NOT_DOWNLOADED.equals(type)) {
			String msisdn = (String) object;
			if (!msisdn.equals(mActivityState.id)) {
				return;
			}
			runOnUiThread(new Runnable() {
				@Override
				public void run() {
					mActivityState = new ActivityState();
					mActivityState.animatedProfileImage = true;
					downloadOrShowProfileImage(false, true,
							mActivityState.imageViewId);

					if (mDialog != null) {
						mDialog.dismiss();
						mDialog = null;
					}
				}
			});
		} else if (HikePubSub.STATUS_MESSAGE_RECEIVED.equals(type)) {
			StatusMessage statusMessage = (StatusMessage) object;
			if (!mLocalMSISDN.equals(statusMessage.getMsisdn())) {
				return;
			}
			profileItems.add(showMessageBtn ? 2 : 1, statusMessage);
			runOnUiThread(new Runnable() {

				@Override
				public void run() {
					profileAdapter.notifyDataSetChanged();
				}
			});
		} else if (HikePubSub.FAVORITE_TOGGLED.equals(type)) {
			final Pair<ContactInfo, FavoriteType> favoriteToggle = (Pair<ContactInfo, FavoriteType>) object;

			ContactInfo contactInfo = favoriteToggle.first;
			FavoriteType favoriteType = favoriteToggle.second;

			if (!mLocalMSISDN.equals(contactInfo.getMsisdn())) {
				return;
			}
			this.contactInfo.setFavoriteType(favoriteType);
			setupContactProfileList();
			runOnUiThread(new Runnable() {

				@Override
				public void run() {
					profileAdapter.notifyDataSetChanged();
				}
			});
		}
	}

	@Override
	public boolean onLongClick(View view) {

		final ContactInfo contactInfo = (ContactInfo) view.getTag();

		AlertDialog.Builder builder = new AlertDialog.Builder(this);

		ArrayList<String> itemList = new ArrayList<String>(2);

		String myMsisdn = Utils.getUserContactInfo(preferences).getMsisdn();

		/*
		 * If the user long pressed his/her own contact simply return.
		 */
		if (myMsisdn.equals(contactInfo.getMsisdn())) {
			return false;
		}
		/*
		 * This should only be true in case of unknown contacts.
		 */
		if (contactInfo.getMsisdn().equals(contactInfo.getId())) {
			itemList.add(getString(R.string.add_to_contacts));
		}

		if (myMsisdn.equals(groupConversation.getGroupOwner())) {
			itemList.add(getString(R.string.remove_from_group));
		}

		/*
		 * If no item could be added we should just return.
		 */
		if (itemList.isEmpty()) {
			return false;
		}

		final String[] items = new String[itemList.size()];
		itemList.toArray(items);

		builder.setItems(items, new DialogInterface.OnClickListener() {

			@Override
			public void onClick(DialogInterface arg0, int position) {
				if (getString(R.string.add_to_contacts).equals(items[position])) {
					addToContacts(contactInfo.getMsisdn());
				} else if (getString(R.string.remove_from_group).equals(
						items[position])) {
					removeFromGroup(contactInfo);
				}
			}
		});

		AlertDialog dialog = builder.create();
		dialog.show();
		return true;
	}

	private void removeFromGroup(final ContactInfo contactInfo) {
		AlertDialog.Builder builder = new AlertDialog.Builder(
				ProfileActivity.this);

		String message = getString(R.string.remove_confirm,
				contactInfo.getFirstName());

		builder.setMessage(message);
		builder.setPositiveButton(R.string.yes,
				new DialogInterface.OnClickListener() {

					@Override
					public void onClick(DialogInterface dialog, int which) {
						JSONObject object = new JSONObject();
						try {
							object.put(HikeConstants.TO,
									groupConversation.getMsisdn());
							object.put(
									HikeConstants.TYPE,
									HikeConstants.MqttMessageTypes.GROUP_CHAT_KICK);

							JSONObject data = new JSONObject();

							JSONArray msisdns = new JSONArray();
							msisdns.put(contactInfo.getMsisdn());

							data.put(HikeConstants.MSISDNS, msisdns);

							object.put(HikeConstants.DATA, data);
						} catch (JSONException e) {
							Log.e(getClass().getSimpleName(), "Invalid JSON", e);
						}
						HikeMessengerApp.getPubSub().publish(
								HikePubSub.MQTT_PUBLISH, object);
					}
				});
		builder.setNegativeButton(R.string.no,
				new DialogInterface.OnClickListener() {

					@Override
					public void onClick(DialogInterface dialog, int which) {
					}
				});
		builder.setCancelable(true);
		AlertDialog alertDialog = builder.create();
		alertDialog.show();
	}

	@Override
	public void onItemClick(AdapterView<?> adapterView, View view,
			int position, long id) {
		GroupParticipant groupParticipant = (GroupParticipant) profileAdapter
				.getItem(position);
		if (groupParticipant == null) {
			return;
		}

		ContactInfo contactInfo = groupParticipant.getContactInfo();

		String myMsisdn = preferences.getString(
				HikeMessengerApp.MSISDN_SETTING, "");

		Intent intent = new Intent(this, ProfileActivity.class);

		intent.putExtra(HikeConstants.Extras.FROM_CENTRAL_TIMELINE, true);
		if (myMsisdn.equals(contactInfo.getMsisdn())) {
			startActivity(intent);
			return;
		}

		intent.setClass(this, ProfileActivity.class);
		intent.putExtra(HikeConstants.Extras.CONTACT_INFO,
				contactInfo.getMsisdn());
		startActivity(intent);
	}

	@Override
	public boolean onItemLongClick(AdapterView<?> adapterView, View view,
			int position, long id) {
		GroupParticipant groupParticipant = (GroupParticipant) profileAdapter
				.getItem(position);
		if (groupParticipant == null) {
			return false;
		}

		String myMsisdn = preferences.getString(
				HikeMessengerApp.MSISDN_SETTING, "");

		final ContactInfo contactInfo = groupParticipant.getContactInfo();
		if (myMsisdn.equals(contactInfo.getMsisdn())) {
			return false;
		}

		ArrayList<String> optionsList = new ArrayList<String>();
		ArrayList<Integer> optionImagesList = new ArrayList<Integer>();

		optionsList.add(getString(R.string.send_message));
		optionImagesList.add(R.drawable.ic_send_message);
		if (!contactInfo.isOnhike()) {
			optionsList.add(getString(R.string.invite_to_hike));
			optionImagesList.add(R.drawable.ic_invite_single);
		}
		if (contactInfo.getMsisdn().equals(contactInfo.getId())) {
			optionsList.add(getString(R.string.add_to_contacts));
			optionImagesList.add(R.drawable.ic_add_to_contacts);
		}
		if (isGroupOwner) {
			optionsList.add(getString(R.string.remove_from_group));
			optionImagesList.add(R.drawable.ic_remove_from_group);
		}

		final String[] options = new String[optionsList.size()];
		optionsList.toArray(options);

		final Integer[] optionIcons = new Integer[optionImagesList.size()];
		optionImagesList.toArray(optionIcons);

		AlertDialog.Builder builder = new AlertDialog.Builder(this);

		ListAdapter dialogAdapter = new ArrayAdapter<CharSequence>(this,
				R.layout.alert_item, R.id.item, options) {

			@Override
			public View getView(int position, View convertView, ViewGroup parent) {
				View v = super.getView(position, convertView, parent);
				TextView tv = (TextView) v.findViewById(R.id.item);
				tv.setCompoundDrawablesWithIntrinsicBounds(
						optionIcons[position], 0, 0, 0);
				return v;
			}

		};

		builder.setAdapter(dialogAdapter,
				new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) {
						String option = options[which];
						if (getString(R.string.send_message).equals(option)) {
							openChatThread(contactInfo);
						} else if (getString(R.string.invite_to_hike).equals(
								option)) {
							inviteToHike(contactInfo.getMsisdn());
						} else if (getString(R.string.add_to_contacts).equals(
								option)) {
							addToContacts(contactInfo.getMsisdn());
						} else if (getString(R.string.remove_from_group)
								.equals(option)) {
							removeFromGroup(contactInfo);
						}
					}
				});

		AlertDialog alertDialog = builder.show();
		alertDialog.getListView().setDivider(
				getResources()
						.getDrawable(R.drawable.ic_thread_divider_profile));
		return true;
	}
}
