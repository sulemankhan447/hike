package com.bsb.hike.ui.fragments;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;

import org.json.JSONException;

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.NotificationManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.os.AsyncTask;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.provider.ContactsContract.Intents.Insert;
import android.util.Pair;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemLongClickListener;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.actionbarsherlock.app.SherlockListFragment;
import com.bsb.hike.HikeConstants;
import com.bsb.hike.HikeMessengerApp;
import com.bsb.hike.HikePubSub;
import com.bsb.hike.HikePubSub.Listener;
import com.bsb.hike.R;
import com.bsb.hike.adapters.ConversationsAdapter;
import com.bsb.hike.adapters.EmptyConversationsAdapter;
import com.bsb.hike.db.HikeConversationsDatabase;
import com.bsb.hike.db.HikeUserDatabase;
import com.bsb.hike.models.ContactInfo;
import com.bsb.hike.models.ConvMessage;
import com.bsb.hike.models.ConvMessage.ParticipantInfoState;
import com.bsb.hike.models.ConvMessage.State;
import com.bsb.hike.models.Conversation;
import com.bsb.hike.models.ConversationTip;
import com.bsb.hike.models.EmptyConversationItem;
import com.bsb.hike.models.GroupConversation;
import com.bsb.hike.models.TypingNotification;
import com.bsb.hike.smartImageLoader.IconLoader;
import com.bsb.hike.tasks.EmailConversationsAsyncTask;
import com.bsb.hike.ui.HikeDialog;
import com.bsb.hike.ui.HomeActivity;
import com.bsb.hike.ui.ProfileActivity;
import com.bsb.hike.utils.CustomAlertDialog;
import com.bsb.hike.utils.HikeAnalyticsEvent;
import com.bsb.hike.utils.HikeSharedPreferenceUtil;
import com.bsb.hike.utils.Logger;
import com.bsb.hike.utils.Utils;

public class ConversationFragment extends SherlockListFragment implements OnItemLongClickListener, Listener
{

	private class DeleteConversationsAsyncTask extends AsyncTask<Conversation, Void, Conversation[]>
	{

		Context context;
		boolean publishStealthEvent;

		public DeleteConversationsAsyncTask(Context context)
		{
			this(context, true);
		}

		public DeleteConversationsAsyncTask(Context context, boolean publishStealthEvent)
		{
			/*
			 * Using application context since that will never be null while the task is running.
			 */
			this.context = context.getApplicationContext();
			this.publishStealthEvent = publishStealthEvent;
		}

		@Override
		protected Conversation[] doInBackground(Conversation... convs)
		{
			HikeConversationsDatabase db = null;
			ArrayList<Long> ids = new ArrayList<Long>(convs.length);
			ArrayList<String> msisdns = new ArrayList<String>(convs.length);
			Editor editor = context.getSharedPreferences(HikeConstants.DRAFT_SETTING, Context.MODE_PRIVATE).edit();
			for (Conversation conv : convs)
			{
				/*
				 * Added to check for the Conversation tip item we add for the group chat tip and other.
				 */
				if (conv instanceof ConversationTip)
				{
					continue;
				}
				ids.add(conv.getConvId());
				msisdns.add(conv.getMsisdn());
				editor.remove(conv.getMsisdn());
			}
			editor.commit();

			db = HikeConversationsDatabase.getInstance();
			db.deleteConversation(ids.toArray(new Long[] {}), msisdns);

			return convs;
		}

		@Override
		protected void onPostExecute(Conversation[] deleted)
		{
			if (!isAdded())
			{
				return;
			}
			NotificationManager mgr = (NotificationManager) getActivity().getSystemService(Context.NOTIFICATION_SERVICE);
			for (Conversation conversation : deleted)
			{
				/*
				 * Added to check for the Conversation tip item we add for the group chat tip and other.
				 */
				if (conversation instanceof ConversationTip)
				{
					continue;
				}
				mgr.cancel((int) conversation.getConvId());
				mAdapter.remove(conversation);
				mConversationsByMSISDN.remove(conversation.getMsisdn());
				mConversationsAdded.remove(conversation.getMsisdn());

				HikeMessengerApp.removeStealthMsisdn(conversation.getMsisdn(), publishStealthEvent);
				stealthConversations.remove(conversation);
			}

			notifyDataSetChanged();

			if (mAdapter.getCount() == 0)
			{
				setEmptyState();
			}
		}
	}

	private String[] pubSubListeners = { HikePubSub.MESSAGE_RECEIVED, HikePubSub.SERVER_RECEIVED_MSG, HikePubSub.MESSAGE_DELIVERED_READ, HikePubSub.MESSAGE_DELIVERED,
			HikePubSub.NEW_CONVERSATION, HikePubSub.MESSAGE_SENT, HikePubSub.MSG_READ, HikePubSub.ICON_CHANGED, HikePubSub.GROUP_NAME_CHANGED, HikePubSub.CONTACT_ADDED,
			HikePubSub.LAST_MESSAGE_DELETED, HikePubSub.TYPING_CONVERSATION, HikePubSub.END_TYPING_CONVERSATION, HikePubSub.RESET_UNREAD_COUNT, HikePubSub.GROUP_LEFT,
			HikePubSub.FTUE_LIST_FETCHED_OR_UPDATED, HikePubSub.CLEAR_CONVERSATION, HikePubSub.CONVERSATION_CLEARED_BY_DELETING_LAST_MESSAGE, 
			HikePubSub.DISMISS_STEALTH_FTUE_CONV_TIP, HikePubSub.SHOW_STEALTH_FTUE_CONV_TIP, HikePubSub.STEALTH_MODE_TOGGLED, HikePubSub.CLEAR_FTUE_STEALTH_CONV,
			HikePubSub.RESET_STEALTH_INITIATED, HikePubSub.RESET_STEALTH_CANCELLED, HikePubSub.REMOVE_WELCOME_HIKE_TIP };

	private ConversationsAdapter mAdapter;

	private HashMap<String, Conversation> mConversationsByMSISDN;

	private HashSet<String> mConversationsAdded;

	private Comparator<? super Conversation> mConversationsComparator;

	private View emptyView;

	private Set<Conversation> stealthConversations;

	private List<Conversation> displayedConversations;

	private boolean showingStealthFtueConvTip = false;
	
	private boolean showingWelcomeHikeConvTip = false;

	private enum hikeBotConvStat
	{
		NOTVIEWED, VIEWED, DELETED
	};

	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState)
	{
		View parent = inflater.inflate(R.layout.conversations, null);

		return parent;
	}

	private void setEmptyState()
	{
		if (emptyView == null)
		{
			ViewGroup emptyHolder = (ViewGroup) getView().findViewById(R.id.emptyViewHolder);
			emptyView = LayoutInflater.from(getActivity()).inflate(R.layout.conversation_empty_view, emptyHolder);
			// emptyHolder.addView(emptyView);
			ListView friendsList = (ListView) getView().findViewById(android.R.id.list);
			setupEmptyView();
			friendsList.setEmptyView(emptyView);
		}
	}

	private void setupEmptyView()
	{

		if (emptyView == null || !isAdded())
		{
			return;
		}

		View ftueNotEmptyView = emptyView.findViewById(R.id.ftue_not_empty);

		if (HomeActivity.ftueContactsData.isEmpty())
		{
			ftueNotEmptyView.setVisibility(View.GONE);
		}
		else
		{
			ftueNotEmptyView.setVisibility(View.VISIBLE);

			ListView ftueListView = (ListView) emptyView.findViewById(R.id.ftue_list);
			List<EmptyConversationItem> ftueListItems= new ArrayList<EmptyConversationItem>();
			
			int hikeContactCount = -1; // TODO need to fetch actual hike contacts count here
			EmptyConversationItem hikeContactsItem = new EmptyConversationItem(HomeActivity.ftueContactsData.getCompleteList(), getResources().getString(R.string.ftue_hike_contact_card_header, hikeContactCount), EmptyConversationItem.HIKE_CONTACTS);
			ftueListItems.add(hikeContactsItem);
			ftueListView.setAdapter(new EmptyConversationsAdapter(getActivity(), -1, ftueListItems));
		}
	}

	@Override
	public void onActivityCreated(Bundle savedInstanceState)
	{
		super.onActivityCreated(savedInstanceState);

		mConversationsComparator = new Conversation.ConversationComparator();
		fetchConversations();

		for (TypingNotification typingNotification : HikeMessengerApp.getTypingNotificationSet().values())
		{
			toggleTypingNotification(true, typingNotification);
		}
	}

	@Override
	public void onDestroy()
	{
		HikeMessengerApp.getPubSub().removeListeners(this, pubSubListeners);

		if (!getActivity().getSharedPreferences(HikeMessengerApp.ACCOUNT_SETTINGS, 0).getBoolean(HikeMessengerApp.STEALTH_MODE_SETUP_DONE, false))
		{
			// if stealth setup is not done and user has marked some chats as stealth unmark all of them
			for (Conversation conv : stealthConversations)
			{
				conv.setIsStealth(false);
				HikeConversationsDatabase.getInstance().toggleStealth(conv.getMsisdn(), false);
			}

			HikeMessengerApp.clearStealthMsisdn();
			HikeSharedPreferenceUtil.getInstance(getActivity()).removeData(HikeMessengerApp.SHOWING_STEALTH_FTUE_CONV_TIP);
		}

		super.onDestroy();
	}

	@Override
	public void onStop()
	{
		// TODO Auto-generated method stub
		super.onStop();
		if (showingStealthFtueConvTip)
		{
			HikeSharedPreferenceUtil.getInstance(getActivity()).saveData(HikeMessengerApp.STEALTH_MODE, HikeConstants.STEALTH_OFF);
			Conversation convTip = displayedConversations.get(0);
			removeStealthConvTip(convTip);
		}
		
		if (showingWelcomeHikeConvTip)
		{
			HikeSharedPreferenceUtil.getInstance(getActivity()).saveData(HikeMessengerApp.STEALTH_MODE, HikeConstants.STEALTH_OFF);
			removeWelcomeHikeTipIfExists();
		}

	}

	@Override
	public void onListItemClick(ListView l, View v, int position, long id)
	{
		Conversation conv = (Conversation) mAdapter.getItem(position);

		/*
		 * The item will be instance ConversationTip only for tips we show on ConversationFragment.
		 */
		if (conv instanceof ConversationTip)
		{
			switch (((ConversationTip) conv).getTipType())
			{
			case ConversationTip.STEALTH_FTUE_TIP:
				break;
			case ConversationTip.RESET_STEALTH_TIP:
				resetStealthTipClicked();
				break;
			}
			return;
		}

		Intent intent = Utils.createIntentForConversation(getSherlockActivity(), conv);
		startActivity(intent);

		SharedPreferences prefs = getActivity().getSharedPreferences(HikeMessengerApp.ACCOUNT_SETTINGS, 0);
		if (conv.getMsisdn().equals(HikeConstants.FTUE_HIKEBOT_MSISDN) && prefs.getInt(HikeConstants.HIKEBOT_CONV_STATE, 0) == hikeBotConvStat.NOTVIEWED.ordinal())
		{
			Editor editor = prefs.edit();
			editor.putInt(HikeConstants.HIKEBOT_CONV_STATE, hikeBotConvStat.VIEWED.ordinal());
			editor.commit();
		}

	}

	private void resetStealthTipClicked()
	{
		long remainingTime = System.currentTimeMillis() - HikeSharedPreferenceUtil.getInstance(getActivity()).getData(HikeMessengerApp.RESET_COMPLETE_STEALTH_START_TIME, 0l);

		if (remainingTime > HikeConstants.RESET_COMPLETE_STEALTH_TIME_MS)
		{
			Object[] dialogStrings = new Object[4];
			dialogStrings[0] = getString(R.string.reset_complete_stealth_header);
			dialogStrings[1] = getString(R.string.reset_stealth_confirmation);
			dialogStrings[2] = getString(R.string.confirm);
			dialogStrings[3] = getString(R.string.cancel);

			HikeDialog.showDialog(getActivity(), HikeDialog.RESET_STEALTH_DIALOG, new HikeDialog.HikeDialogListener()
			{

				@Override
				public void positiveClicked(Dialog dialog)
				{
					HikeAnalyticsEvent.sendStealthReset();
					resetStealthMode();
					dialog.dismiss();
				}

				@Override
				public void neutralClicked(Dialog dialog)
				{

				}

				@Override
				public void negativeClicked(Dialog dialog)
				{
					removeResetStealthTipIfExists();

					Utils.cancelScheduledStealthReset(getActivity());

					dialog.dismiss();
					
					Utils.sendUILogEvent(HikeConstants.LogEvent.RESET_STEALTH_CANCEL);
				}
			}, dialogStrings);
		}
	}

	private void resetStealthMode()
	{
		removeResetStealthTipIfExists();

		int prevStealthValue = HikeSharedPreferenceUtil.getInstance(getActivity()).getData(HikeMessengerApp.STEALTH_MODE, HikeConstants.STEALTH_OFF);

		resetStealthPreferences();
		stealthConversations.clear();

		/*
		 * If previously the stealth mode was off, we should publish an event telling the friends fragment to refresh its list.
		 */
		if (prevStealthValue == HikeConstants.STEALTH_OFF)
		{
			HikeMessengerApp.getPubSub().publish(HikePubSub.STEALTH_MODE_RESET_COMPLETE, null);
		}

		/*
		 * Calling the delete conversation task in the end to ensure that we first publish the reset event. If the delete task was published at first, it was causing a threading
		 * issue where the contacts in the friends fragment were getting removed and not added again.
		 */
		DeleteConversationsAsyncTask task = new DeleteConversationsAsyncTask(getActivity(), false);
		task.execute(stealthConversations.toArray(new Conversation[0]));

		HikeMessengerApp.clearStealthMsisdn();
	}

	private void resetStealthPreferences()
	{
		HikeSharedPreferenceUtil prefUtil = HikeSharedPreferenceUtil.getInstance(getActivity());

		prefUtil.removeData(HikeMessengerApp.STEALTH_ENCRYPTED_PATTERN);
		prefUtil.removeData(HikeMessengerApp.STEALTH_MODE);
		prefUtil.removeData(HikeMessengerApp.STEALTH_MODE_SETUP_DONE);
		prefUtil.removeData(HikeMessengerApp.SHOWING_STEALTH_FTUE_CONV_TIP);
		prefUtil.removeData(HikeMessengerApp.RESET_COMPLETE_STEALTH_START_TIME);
		prefUtil.removeData(HikeMessengerApp.SHOWN_FIRST_UNMARK_STEALTH_TOAST);
	}

	@Override
	public boolean onItemLongClick(AdapterView<?> adapterView, View view, int position, long id)
	{
		if (position >= mAdapter.getCount())
		{
			return false;
		}
		ArrayList<String> optionsList = new ArrayList<String>();

		final Conversation conv = (Conversation) mAdapter.getItem(position);

		if (conv instanceof ConversationTip)
		{
			return false;
		}

		/*
		 * Switch to stealth mode if we are in ftue.
		 */
		if (!getActivity().getSharedPreferences(HikeMessengerApp.ACCOUNT_SETTINGS, 0).getBoolean(HikeMessengerApp.STEALTH_MODE_SETUP_DONE, false))
		{
			for (int i = 0; i < mAdapter.getCount(); i++)
			{
				Conversation convTip = mAdapter.getItem(i);
				if (convTip instanceof ConversationTip && ((ConversationTip) convTip).isStealthFtueTip())
				{
					HikeSharedPreferenceUtil.getInstance(getActivity()).saveData(HikeMessengerApp.STEALTH_MODE, HikeConstants.STEALTH_ON);
					break;
				}
			}
		}

		final int stealthType = HikeSharedPreferenceUtil.getInstance(getActivity()).getData(HikeMessengerApp.STEALTH_MODE, HikeConstants.STEALTH_OFF);

		if (stealthType == HikeConstants.STEALTH_ON || stealthType == HikeConstants.STEALTH_ON_FAKE)
		{
			optionsList.add(getString(conv.isStealth() ? R.string.unmark_stealth : R.string.mark_stealth));
		}
		if (!(conv instanceof GroupConversation) && conv.getContactName() == null)
		{
			optionsList.add(getString(R.string.add_to_contacts));
			optionsList.add(getString(R.string.add_to_contacts_existing));
		}
		if (!(conv instanceof GroupConversation))
		{
			if (conv.getContactName() != null)
			{
				optionsList.add(getString(R.string.viewcontact));
			}
		}
		else
		{
			optionsList.add(getString(R.string.group_info));
		}
		if (conv.getContactName() != null)
		{
			optionsList.add(getString(R.string.shortcut));

		}

		if (!(conv instanceof GroupConversation) && conv.getContactName() == null)
		{
			optionsList.add(HikeUserDatabase.getInstance().isBlocked(conv.getMsisdn())?getString(R.string.unblock_title):getString(R.string.block_title));
		}
		if (conv instanceof GroupConversation)
		{
			optionsList.add(getString(R.string.delete_leave));
		}
		else
		{
			optionsList.add(getString(R.string.delete_chat));
		}
		if (conv instanceof GroupConversation)
		{	
			optionsList.add(getString(R.string.clear_whole_conversation));
		}
		optionsList.add(getString(R.string.email_conversations));
		

		final String[] options = new String[optionsList.size()];
		optionsList.toArray(options);

		AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());

		ListAdapter dialogAdapter = new MenuArrayAdapter(getActivity(), R.layout.alert_item, R.id.item, options);

		builder.setAdapter(dialogAdapter, new DialogInterface.OnClickListener()
		{
			@Override
			public void onClick(DialogInterface dialog, int which)
			{
				String option = options[which];
				if (getString(R.string.shortcut).equals(option))
				{
					Utils.logEvent(getActivity(), HikeConstants.LogEvent.ADD_SHORTCUT);
					Utils.createShortcut(getSherlockActivity(), conv);
				}
				else if (getString(R.string.delete_chat).equals(option))
				{
					final CustomAlertDialog deleteConfirmDialog = new CustomAlertDialog(getActivity());
					deleteConfirmDialog.setHeader(R.string.delete);
					deleteConfirmDialog.setBody(getString(R.string.confirm_delete_chat_msg, conv.getLabel()));
					
					View.OnClickListener dialogOkClickListener = new View.OnClickListener()
					{

						@Override
						public void onClick(View v)
						{
							Utils.logEvent(getActivity(), HikeConstants.LogEvent.DELETE_CONVERSATION);
							DeleteConversationsAsyncTask task = new DeleteConversationsAsyncTask(getActivity());
							Utils.executeConvAsyncTask(task, conv);
							deleteConfirmDialog.dismiss();
						}
					};

					deleteConfirmDialog.setOkButton(R.string.yes, dialogOkClickListener);
					deleteConfirmDialog.setCancelButton(R.string.no);
					deleteConfirmDialog.show();
				}
				else if (getString(R.string.delete_leave).equals(option))
				{
					final CustomAlertDialog deleteConfirmDialog = new CustomAlertDialog(getActivity());
					deleteConfirmDialog.setHeader(R.string.delete);
					deleteConfirmDialog.setBody(getString(R.string.confirm_delete_group_msg, conv.getLabel()));
					
					View.OnClickListener dialogOkClickListener = new View.OnClickListener()
					{

						@Override
						public void onClick(View v)
						{
							Utils.logEvent(getActivity(), HikeConstants.LogEvent.DELETE_CONVERSATION);
							leaveGroup(conv);
							deleteConfirmDialog.dismiss();
						}
					};

					deleteConfirmDialog.setOkButton(android.R.string.ok, dialogOkClickListener);
					deleteConfirmDialog.setCancelButton(R.string.cancel);
					deleteConfirmDialog.show();
				}
				else if (getString(R.string.email_conversations).equals(option))
				{
					EmailConversationsAsyncTask task = new EmailConversationsAsyncTask(getSherlockActivity(), ConversationFragment.this);
					Utils.executeConvAsyncTask(task, conv);
				}
				else if (getString(R.string.deleteconversations).equals(option))
				{
					Utils.logEvent(getActivity(), HikeConstants.LogEvent.DELETE_ALL_CONVERSATIONS_MENU);
					DeleteAllConversations();
				}
				else if (getString(R.string.viewcontact).equals(option))
				{
					viewContacts(conv);
				}
				else if (getString(R.string.clear_whole_conversation).equals(option))
				{
					clearConversation(conv);
				}
				else if (getString(R.string.add_to_contacts).equals(option))
				{
					addToContacts(conv.getMsisdn());
				}
				else if (getString(R.string.add_to_contacts_existing).equals(option))
				{
					addToContactsExisting(conv.getMsisdn());
				}

				else if (getString(R.string.group_info).equals(option))
				{
					if (!((GroupConversation) conv).getIsGroupAlive())
					{
						return;
					}
					viewGroupInfo(conv);
				}
				else if (getString(R.string.mark_stealth).equals(option) || getString(R.string.unmark_stealth).equals(option))
				{
					boolean newStealthValue = !conv.isStealth();
					/*
					 * If stealth ftue conv tap tip is visible than remove it
					 */
					if (!getActivity().getSharedPreferences(HikeMessengerApp.ACCOUNT_SETTINGS, 0).getBoolean(HikeMessengerApp.STEALTH_MODE_SETUP_DONE, false))
					{
						for (int i = 0; i < mAdapter.getCount(); i++)
						{
							Conversation convTip = mAdapter.getItem(i);
							if (convTip instanceof ConversationTip && ((ConversationTip) convTip).isStealthFtueTip())
							{
								removeStealthConvTip(convTip);
								break;
							}
						}
					}
					else
					{
						// We don't show this toast during stealth ftue setup.
						if(newStealthValue)
						{
							Toast.makeText(getActivity(), R.string.chat_marked_stealth, Toast.LENGTH_SHORT).show();
						}
						else if(!HikeSharedPreferenceUtil.getInstance(getActivity()).getData(HikeMessengerApp.SHOWN_FIRST_UNMARK_STEALTH_TOAST, false))
						{
							Toast.makeText(getActivity(), R.string.chat_unmarked_stealth_first, Toast.LENGTH_LONG).show();
							HikeSharedPreferenceUtil.getInstance(getActivity()).saveData(HikeMessengerApp.SHOWN_FIRST_UNMARK_STEALTH_TOAST, true);
						}
						else
						{
							Toast.makeText(getActivity(), R.string.chat_unmarked_stealth, Toast.LENGTH_SHORT).show();
						}
					}

					if (stealthType == HikeConstants.STEALTH_ON_FAKE)
					{
						/*
						 * We don't need to do anything here if the device is on fake stealth mode.
						 */
						return;
					}
					if (getString(R.string.mark_stealth).equals(option))
					{
						Set<String> enabledConvs = new HashSet<String>();
						enabledConvs.add(conv.getMsisdn());
						HikeAnalyticsEvent.sendStealthMsisdns(enabledConvs, new HashSet<String>());

						stealthConversations.add(conv);
						HikeMessengerApp.addNewStealthMsisdn(conv.getMsisdn());
					}
					else
					{
						Set<String> disabledConvs = new HashSet<String>();
						disabledConvs.add(conv.getMsisdn());
						HikeAnalyticsEvent.sendStealthMsisdns(new HashSet<String>(), disabledConvs);

						stealthConversations.remove(conv);
						HikeMessengerApp.removeStealthMsisdn(conv.getMsisdn());
					}

					conv.setIsStealth(newStealthValue);

					HikeConversationsDatabase.getInstance().toggleStealth(conv.getMsisdn(), newStealthValue);

					notifyDataSetChanged();

					if (!HikeSharedPreferenceUtil.getInstance(getActivity()).getData(HikeMessengerApp.STEALTH_MODE_SETUP_DONE, false))
					{
						HikeSharedPreferenceUtil.getInstance(getActivity()).saveData(HikeMessengerApp.STEALTH_MODE, HikeConstants.STEALTH_OFF);
						HikeMessengerApp.getPubSub().publish(HikePubSub.STEALTH_MODE_TOGGLED, true);
						HikeMessengerApp.getPubSub().publish(HikePubSub.SHOW_STEALTH_FTUE_SET_PASS_TIP, null);
					}
				}
				else if (getString(R.string.block_title).equals(option))
				{
					HikeMessengerApp.getPubSub().publish(HikePubSub.BLOCK_USER, conv.getMsisdn());
				}
				else if (getString(R.string.unblock_title).equals(option))
				{
					HikeMessengerApp.getPubSub().publish(HikePubSub.UNBLOCK_USER, conv.getMsisdn());
				}
			}
		});

		AlertDialog alertDialog = builder.show();
		alertDialog.getListView().setDivider(getResources().getDrawable(R.drawable.ic_thread_divider_profile));
		return true;
	}


	protected void clearConversation(final Conversation conv) {
		final CustomAlertDialog clearConfirmDialog = new CustomAlertDialog(this.getActivity());
		clearConfirmDialog.setHeader(R.string.clear_conversation);
		clearConfirmDialog.setBody(R.string.confirm_clear_conversation);
		View.OnClickListener dialogOkClickListener = new View.OnClickListener()
		{

			@Override
			public void onClick(View v)
			{
				HikeMessengerApp.getPubSub().publish(HikePubSub.CLEAR_CONVERSATION, new Pair<String, Long>(conv.getMsisdn(), conv.getConvId()));
				clearConfirmDialog.dismiss();
			}
		};

		clearConfirmDialog.setOkButton(R.string.ok, dialogOkClickListener);
		clearConfirmDialog.setCancelButton(R.string.cancel);
		clearConfirmDialog.show();
		
	}

	private void fetchConversations()
	{
		HikeConversationsDatabase db = HikeConversationsDatabase.getInstance();
		displayedConversations = new ArrayList<Conversation>();
		List<Conversation> conversationList = db.getConversations();

		stealthConversations = new HashSet<Conversation>();

		ShowTipIfNeeded();

		displayedConversations.addAll(conversationList);

		mConversationsByMSISDN = new HashMap<String, Conversation>(displayedConversations.size());
		mConversationsAdded = new HashSet<String>();

		setupConversationLists();

		if (mAdapter != null)
		{
			mAdapter.clear();
		}

		mAdapter = new ConversationsAdapter(getActivity(), displayedConversations);

		setListAdapter(mAdapter);

		getListView().setOnItemLongClickListener(this);

		HikeMessengerApp.getPubSub().addListeners(this, pubSubListeners);
		if (displayedConversations.isEmpty())
		{
			setEmptyState();
		}
	}

	private void ShowTipIfNeeded()
	{
		if (HikeSharedPreferenceUtil.getInstance(getActivity()).getData(HikeMessengerApp.RESET_COMPLETE_STEALTH_START_TIME, 0l) > 0)
		{
			displayedConversations.add(new ConversationTip(ConversationTip.RESET_STEALTH_TIP));
		}
		else if (!HikeSharedPreferenceUtil.getInstance(getActivity()).getData(HikeMessengerApp.SHOWN_WELCOME_HIKE_TIP, false))
		{
			showingWelcomeHikeConvTip = true;
			displayedConversations.add(new ConversationTip(ConversationTip.WELCOME_HIKE_TIP));
		}
		
	}

	private void setupConversationLists()
	{
		int stealthValue = HikeSharedPreferenceUtil.getInstance(getActivity()).getData(HikeMessengerApp.STEALTH_MODE, HikeConstants.STEALTH_OFF);

		/*
		 * Use an iterator so we can remove conversations w/ no messages from our list
		 */
		for (Iterator<Conversation> iter = displayedConversations.iterator(); iter.hasNext();)
		{
			Object object = iter.next();
			Conversation conv = (Conversation) object;
			if (conv instanceof ConversationTip)
			{
				continue;
			}

			mConversationsByMSISDN.put(conv.getMsisdn(), conv);
			if (conv.isStealth())
			{
				stealthConversations.add(conv);
				HikeMessengerApp.addStealthMsisdnToMap(conv.getMsisdn());
			}

			if (conv.getMessages().isEmpty() && !(conv instanceof GroupConversation))
			{
				iter.remove();
			}
			else if ((stealthValue == HikeConstants.STEALTH_OFF || stealthValue == HikeConstants.STEALTH_ON_FAKE) && conv.isStealth())
			{
				mConversationsAdded.add(conv.getMsisdn());
				iter.remove();
			}
			else
			{
				mConversationsAdded.add(conv.getMsisdn());
			}
		}

	}

	private void changeConversationsVisibility()
	{
		int stealthValue = HikeSharedPreferenceUtil.getInstance(getActivity()).getData(HikeMessengerApp.STEALTH_MODE, HikeConstants.STEALTH_OFF);

		if (stealthValue == HikeConstants.STEALTH_OFF || stealthValue == HikeConstants.STEALTH_ON_FAKE)
		{
			for (Iterator<Conversation> iter = displayedConversations.iterator(); iter.hasNext();)
			{
				Object object = iter.next();
				if (object == null)
				{
					continue;
				}
				Conversation conv = (Conversation) object;
				if (conv.isStealth())
				{
					iter.remove();
				}
			}

			if (mAdapter.getCount() == 0)
			{
				setEmptyState();
			}
		}
		else
		{
			mAdapter.addItemsToAnimat(stealthConversations);
			displayedConversations.addAll(stealthConversations);
		}

		Collections.sort(displayedConversations, mConversationsComparator);
		notifyDataSetChanged();
	}

	private void leaveGroup(Conversation conv)
	{
		if (conv == null)
		{
			Logger.d(getClass().getSimpleName(), "Invalid conversation");
			return;
		}
		HikeMessengerApp.getPubSub().publish(HikePubSub.MQTT_PUBLISH, conv.serialize(HikeConstants.MqttMessageTypes.GROUP_CHAT_LEAVE));
		deleteConversation(conv);
	}

	private void deleteConversation(Conversation conv)
	{
		DeleteConversationsAsyncTask task = new DeleteConversationsAsyncTask(getActivity());
		Utils.executeConvAsyncTask(task, conv);
	}

	private void toggleTypingNotification(boolean isTyping, TypingNotification typingNotification)
	{
		if (mConversationsByMSISDN == null)
		{
			return;
		}
		String msisdn = typingNotification.getId();
		Conversation conversation = mConversationsByMSISDN.get(msisdn);
		if (conversation == null)
		{
			Logger.d(getClass().getSimpleName(), "Conversation Does not exist");
			return;
		}
		List<ConvMessage> messageList = conversation.getMessages();
		if (messageList.isEmpty())
		{
			Logger.d(getClass().getSimpleName(), "Conversation is empty");
			return;
		}
		ConvMessage message;

		boolean shouldUpdateUI = false;

		if (isTyping)
		{
			message = messageList.get(messageList.size() - 1);
			if (message.getTypingNotification() == null)
			{
				ConvMessage convMessage = new ConvMessage(typingNotification);
				convMessage.setTimestamp(message.getTimestamp());
				convMessage.setMessage(HikeConstants.IS_TYPING);
				convMessage.setState(State.RECEIVED_UNREAD);
				messageList.add(convMessage);

				shouldUpdateUI = true;
			}
		}
		else
		{
			message = messageList.get(messageList.size() - 1);
			if (message.getTypingNotification() != null)
			{
				messageList.remove(message);
				shouldUpdateUI = true;
			}
		}

		if (shouldUpdateUI)
		{
			if (messageList.isEmpty())
			{
				return;
			}

			/*
			 * Getting the current last message in the conversation
			 */
			ConvMessage convMessage = messageList.get(messageList.size() - 1);

			View parentView = getParenViewForConversation(conversation);

			if (parentView == null || convMessage == null)
			{
				if(parentView == null)
				{
					notifyDataSetChanged();
				}
				return;
			}
			
			mAdapter.updateViewsRelatedToLastMessage(parentView, convMessage, conversation);
		}
	}

	public void notifyDataSetChanged()
	{
		if (mAdapter == null)
		{
			return;
		}
		mAdapter.notifyDataSetChanged();
	}

	@SuppressWarnings("unchecked")
	@Override
	public void onEventReceived(String type, Object object)
	{

		if (!isAdded())
		{
			return;
		}
		Logger.d(getClass().getSimpleName(), "Event received: " + type);
		if ((HikePubSub.MESSAGE_RECEIVED.equals(type)) || (HikePubSub.MESSAGE_SENT.equals(type)))
		{
			Logger.d(getClass().getSimpleName(), "New msg event sent or received.");
			ConvMessage message = (ConvMessage) object;
			/* find the conversation corresponding to this message */
			String msisdn = message.getMsisdn();
			final Conversation conv = mConversationsByMSISDN.get(msisdn);

			if (conv == null)
			{
				// When a message gets sent from a user we don't have a
				// conversation for, the message gets
				// broadcasted first then the conversation gets created. It's
				// okay that we don't add it now, because
				// when the conversation is broadcasted it will contain the
				// messages
				return;
			}

			if (Utils.shouldIncrementCounter(message))
			{
				conv.setUnreadCount(conv.getUnreadCount() + 1);
			}

			if (message.getParticipantInfoState() == ParticipantInfoState.STATUS_MESSAGE)
			{
				if (!conv.getMessages().isEmpty())
				{
					ConvMessage prevMessage = conv.getMessages().get(conv.getMessages().size() - 1);
					String metadata = message.getMetadata().serialize();
					message = new ConvMessage(message.getMessage(), message.getMsisdn(), prevMessage.getTimestamp(), prevMessage.getState(), prevMessage.getMsgID(),
							prevMessage.getMappedMsgID(), message.getGroupParticipantMsisdn());
					try
					{
						message.setMetadata(metadata);
					}
					catch (JSONException e)
					{
						e.printStackTrace();
					}
				}
			}
			// For updating the group name if some participant has joined or
			// left the group
			else if ((conv instanceof GroupConversation) && message.getParticipantInfoState() != ParticipantInfoState.NO_INFO)
			{
				HikeConversationsDatabase hCDB = HikeConversationsDatabase.getInstance();
				((GroupConversation) conv).setGroupParticipantList(hCDB.getGroupParticipants(conv.getMsisdn(), false, false));
			}

			final ConvMessage finalMessage = message;

			if (conv.getMessages().size() > 0)
			{
				if (finalMessage.getMsgID() < conv.getMessages().get(conv.getMessages().size() - 1).getMsgID())
				{
					return;
				}
			}
			if (getActivity() == null)
			{
				return;
			}
			getActivity().runOnUiThread(new Runnable()
			{
				@Override
				public void run()
				{
					addMessage(conv, finalMessage);
				}
			});

		}
		else if (HikePubSub.LAST_MESSAGE_DELETED.equals(type))
		{
			Pair<ConvMessage, String> messageMsisdnPair = (Pair<ConvMessage, String>) object;

			final ConvMessage message = messageMsisdnPair.first;
			final String msisdn = messageMsisdnPair.second;

			final boolean conversationEmpty = message == null;

			final Conversation conversation = mConversationsByMSISDN.get(msisdn);

			final List<ConvMessage> messageList = new ArrayList<ConvMessage>(1);

			if (!conversationEmpty)
			{
				if (conversation == null)
				{
					return;
				}
				messageList.add(message);
			}
			if (getActivity() == null)
			{
				return;
			}
			getActivity().runOnUiThread(new Runnable()
			{
				@Override
				public void run()
				{
					if (conversationEmpty)
					{
						mConversationsByMSISDN.remove(msisdn);
						mConversationsAdded.remove(msisdn);
						mAdapter.remove(conversation);
						notifyDataSetChanged();
					}
					else
					{
						conversation.setMessages(messageList);
						sortAndUpdateTheView(conversation, messageList.get(messageList.size() - 1), false);
					}
				}
			});
		}
		else if (HikePubSub.NEW_CONVERSATION.equals(type))
		{
			final Conversation conversation = (Conversation) object;
			if (HikeMessengerApp.hikeBotNamesMap.containsKey(conversation.getMsisdn()))
			{
				conversation.setContactName(HikeMessengerApp.hikeBotNamesMap.get(conversation.getMsisdn()));
			}
			Logger.d(getClass().getSimpleName(), "New Conversation. Group Conversation? " + (conversation instanceof GroupConversation));
			mConversationsByMSISDN.put(conversation.getMsisdn(), conversation);
			if (conversation.getMessages().isEmpty() && !(conversation instanceof GroupConversation))
			{
				return;
			}

			mConversationsAdded.add(conversation.getMsisdn());

			if (getActivity() == null)
			{
				return;
			}
			getActivity().runOnUiThread(new Runnable()
			{
				public void run()
				{
					displayedConversations.add(conversation);

					notifyDataSetChanged();
				}
			});
		}
		else if (HikePubSub.MSG_READ.equals(type))
		{
			String msisdn = (String) object;
			final Conversation conv = mConversationsByMSISDN.get(msisdn);
			if (conv == null)
			{
				/*
				 * We don't really need to do anything if the conversation does not exist.
				 */
				return;
			}

			ConvMessage lastConvMessage = null;

			/*
			 * look for the latest received messages and set them to read. Exit when we've found some read messages
			 */
			List<ConvMessage> messages = conv.getMessages();
			for (int i = messages.size() - 1; i >= 0; --i)
			{
				ConvMessage msg = messages.get(i);
				if (Utils.shouldChangeMessageState(msg, ConvMessage.State.RECEIVED_READ.ordinal()))
				{
					ConvMessage.State currentState = msg.getState();
					if (currentState == ConvMessage.State.RECEIVED_READ)
					{
						break;
					}

					/*
					 * We are only interested with the last convMessage object for updating the UI.
					 */
					if (i == messages.size() - 1)
					{
						lastConvMessage = msg;
					}
					msg.setState(ConvMessage.State.RECEIVED_READ);
				}
			}

			/*
			 * We should only update the view if the last message's state was changed.
			 */
			if (getActivity() == null || lastConvMessage == null)
			{
				return;
			}

			final ConvMessage message = lastConvMessage;
			getActivity().runOnUiThread(new Runnable()
			{
				
				@Override
				public void run()
				{
					updateViewForMessageStateChange(conv, message);
				}
			});
		}
		else if (HikePubSub.SERVER_RECEIVED_MSG.equals(type))
		{
			long msgId = ((Long) object).longValue();
			final ConvMessage msg = findMessageById(msgId);
			if (Utils.shouldChangeMessageState(msg, ConvMessage.State.SENT_CONFIRMED.ordinal()))
			{
				msg.setState(ConvMessage.State.SENT_CONFIRMED);

				if (getActivity() == null)
				{
					return;
				}
				getActivity().runOnUiThread(new Runnable()
				{
					
					@Override
					public void run()
					{
						Conversation conversation = mConversationsByMSISDN.get(msg.getMsisdn());
						
						updateViewForMessageStateChange(conversation, msg);
					}
				});
			}
		}
		else if (HikePubSub.MESSAGE_DELIVERED_READ.equals(type))
		{
			Pair<String, long[]> pair = (Pair<String, long[]>) object;

			final String msisdn = pair.first;

			long[] ids = (long[]) pair.second;

			ConvMessage lastConvMessage = null;

			// TODO we could keep a map of msgId -> conversation objects
			// somewhere to make this faster
			for (int i = 0; i < ids.length; i++)
			{
				ConvMessage msg = findMessageById(ids[i]);
				if (Utils.shouldChangeMessageState(msg, ConvMessage.State.SENT_DELIVERED_READ.ordinal()))
				{
					// If the msisdn don't match we simply return
					if (!msg.getMsisdn().equals(msisdn))
					{
						return;
					}
					lastConvMessage = msg;

					msg.setState(ConvMessage.State.SENT_DELIVERED_READ);

					/*
					 * Since we have updated the last message of the conversation, we don't need to iterate through the array anymore.
					 */
					break;
				}
			}

			if (getActivity() == null || lastConvMessage == null)
			{
				return;
			}

			final ConvMessage message = lastConvMessage;
			getActivity().runOnUiThread(new Runnable()
			{
				
				@Override
				public void run()
				{
					Conversation conversation = mConversationsByMSISDN.get(msisdn);
					
					updateViewForMessageStateChange(conversation, message);
				}
			});
		}
		else if (HikePubSub.MESSAGE_DELIVERED.equals(type))
		{
			Pair<String, Long> pair = (Pair<String, Long>) object;

			final String msisdn = pair.first;
			long msgId = pair.second;
			final ConvMessage msg = findMessageById(msgId);
			if (Utils.shouldChangeMessageState(msg, ConvMessage.State.SENT_DELIVERED.ordinal()))
			{
				// If the msisdn don't match we simply return
				if (!msg.getMsisdn().equals(msisdn))
				{
					return;
				}
				msg.setState(ConvMessage.State.SENT_DELIVERED);

				if (getActivity() == null)
				{
					return;
				}
				getActivity().runOnUiThread(new Runnable()
				{
					
					@Override
					public void run()
					{
						Conversation conversation = mConversationsByMSISDN.get(msisdn);
						
						updateViewForMessageStateChange(conversation, msg);
					}
				});
			}
		}
		else if (HikePubSub.ICON_CHANGED.equals(type))
		{
			if (getActivity() == null)
			{
				return;
			}

			final String msisdn = (String) object;

			/* an icon changed, so update the view */
			getActivity().runOnUiThread(new Runnable()
			{
				
				@Override
				public void run()
				{
					Conversation conversation = mConversationsByMSISDN.get(msisdn);

					View parentView = getParenViewForConversation(conversation);

					if (parentView == null)
					{
						notifyDataSetChanged();
						return;
					}

					mAdapter.updateViewsRelatedToAvatar(parentView, conversation);
				}
			});
		}
		else if (HikePubSub.GROUP_NAME_CHANGED.equals(type))
		{
			String groupId = (String) object;
			HikeConversationsDatabase db = HikeConversationsDatabase.getInstance();
			final String groupName = db.getGroupName(groupId);

			final Conversation conv = mConversationsByMSISDN.get(groupId);
			if (conv == null)
			{
				return;
			}
			conv.setContactName(groupName);

			if (getActivity() == null)
			{
				return;
			}
			getActivity().runOnUiThread(new Runnable()
			{
				
				@Override
				public void run()
				{
					updateViewForNameChange(conv);
				}
			});
		}
		else if (HikePubSub.CONTACT_ADDED.equals(type))
		{
			ContactInfo contactInfo = (ContactInfo) object;

			if (contactInfo == null)
			{
				return;
			}

			final Conversation conversation = this.mConversationsByMSISDN.get(contactInfo.getMsisdn());
			if (conversation != null)
			{
				conversation.setContactName(contactInfo.getName());

				if (getActivity() == null)
				{
					return;
				}
				getActivity().runOnUiThread(new Runnable()
				{
					
					@Override
					public void run()
					{
						updateViewForNameChange(conversation);
					}
				});
			}
		}
		else if (HikePubSub.TYPING_CONVERSATION.equals(type) || HikePubSub.END_TYPING_CONVERSATION.equals(type))
		{
			if (object == null)
			{
				return;
			}

			final boolean isTyping = HikePubSub.TYPING_CONVERSATION.equals(type);
			final TypingNotification typingNotification = (TypingNotification) object;

			getActivity().runOnUiThread(new Runnable()
			{

				@Override
				public void run()
				{
					toggleTypingNotification(isTyping, typingNotification);
				}
			});
		}
		else if (HikePubSub.RESET_UNREAD_COUNT.equals(type))
		{
			String msisdn = (String) object;
			final Conversation conv = mConversationsByMSISDN.get(msisdn);
			if (conv == null)
			{
				return;
			}
			conv.setUnreadCount(0);

			if (getActivity() == null)
			{
				return;
			}
			getActivity().runOnUiThread(new Runnable()
			{
				
				@Override
				public void run()
				{
					List<ConvMessage> messages = conv.getMessages();

					if (messages.isEmpty())
					{
						return;
					}

					ConvMessage lastConvMessage = messages.get(messages.size() - 1);
					updateViewForMessageStateChange(conv, lastConvMessage);
				}
			});
		}
		else if (HikePubSub.GROUP_LEFT.equals(type))
		{
			String groupId = (String) object;
			final Conversation conversation = mConversationsByMSISDN.get(groupId);
			if (conversation == null)
			{
				return;
			}

			if (getActivity() == null)
			{
				return;
			}
			getActivity().runOnUiThread(new Runnable()
			{
				@Override
				public void run()
				{
					deleteConversation(conversation);
				}
			});
		}
		else if (HikePubSub.FTUE_LIST_FETCHED_OR_UPDATED.equals(type))
		{
			if (getActivity() == null)
			{
				return;
			}
			getActivity().runOnUiThread(new Runnable()
			{

				@Override
				public void run()
				{
					setupEmptyView();
				}
			});
		}
		else if (HikePubSub.CLEAR_CONVERSATION.equals(type))
		{
			Pair<String, Long> values = (Pair<String, Long>) object;

			String msisdn = values.first;
			clearConversation(msisdn);
		}
		else if (HikePubSub.CONVERSATION_CLEARED_BY_DELETING_LAST_MESSAGE.equals(type))
		{
			String msisdn = (String) object;
			clearConversation(msisdn);
		}
		else if (HikePubSub.DISMISS_STEALTH_FTUE_CONV_TIP.equals(type))
		{
			if (mAdapter == null || mAdapter.isEmpty())
			{
				return;
			}
			int position = (Integer) object;
			final Conversation conversation = mAdapter.getItem(position);
			if (!(conversation instanceof ConversationTip && ((ConversationTip) conversation).isStealthFtueTip()))
			{
				return;
			}

			if (getActivity() == null)
			{
				return;
			}
			getActivity().runOnUiThread(new Runnable()
			{

				@Override
				public void run()
				{
					removeStealthConvTip(conversation);
				}
			});
		}
		else if (HikePubSub.SHOW_STEALTH_FTUE_CONV_TIP.equals(type))
		{
			if (getActivity() == null)
			{
				return;
			}
			getActivity().runOnUiThread(new Runnable()
			{

				@Override
				public void run()
				{
					showStealthConvTip();
				}
			});

		}
		else if (HikePubSub.STEALTH_MODE_TOGGLED.equals(type))
		{
			boolean changeItemsVisibility = (Boolean) object;

			if (!changeItemsVisibility)
			{
				return;
			}

			getActivity().runOnUiThread(new Runnable()
			{
				@Override
				public void run()
				{
					changeConversationsVisibility();
				}
			});
		}
		else if (HikePubSub.CLEAR_FTUE_STEALTH_CONV.equals(type))
		{
			getActivity().runOnUiThread(new Runnable()
			{
				@Override
				public void run()
				{
					// if stealth setup is not done and user has marked some chats as stealth unmark all of them
					for (Conversation conv : stealthConversations)
					{
						conv.setIsStealth(false);
						HikeConversationsDatabase.getInstance().toggleStealth(conv.getMsisdn(), false);
						HikeMessengerApp.removeStealthMsisdn(conv.getMsisdn());
					}
					displayedConversations.addAll(stealthConversations);
					stealthConversations.clear();
					Collections.sort(displayedConversations, mConversationsComparator);
					notifyDataSetChanged();
				}
			});
		}
		else if (HikePubSub.RESET_STEALTH_INITIATED.equals(type))
		{
			if (!isAdded())
			{
				return;
			}
			getActivity().runOnUiThread(new Runnable()
			{

				@Override
				public void run()
				{
					getFirstConversation();

					displayedConversations.add(0, new ConversationTip(ConversationTip.RESET_STEALTH_TIP));

					notifyDataSetChanged();
				}
			});
		}
		else if (HikePubSub.RESET_STEALTH_CANCELLED.equals(type))
		{
			if (!isAdded())
			{
				return;
			}
			getActivity().runOnUiThread(new Runnable()
			{

				@Override
				public void run()
				{
					removeResetStealthTipIfExists();
				}
			});
		}
		else if (HikePubSub.REMOVE_WELCOME_HIKE_TIP.equals(type))
		{
			if (!isAdded())
			{
				return;
			}
			getActivity().runOnUiThread(new Runnable()
			{

				@Override
				public void run()
				{
					removeWelcomeHikeTipIfExists();
				}
			});
		}
	}

	private void removeResetStealthTipIfExists()
	{
		if (mAdapter.isEmpty())
		{
			return;
		}

		Conversation conversation = mAdapter.getItem(0);

		if (conversation instanceof ConversationTip && ((ConversationTip) conversation).isResetStealthTip())
		{
			mAdapter.remove(conversation);
			mAdapter.resetCountDownSetter();

			notifyDataSetChanged();
		}
	}
	
	private void removeWelcomeHikeTipIfExists()
	{
		if (mAdapter.isEmpty())
		{
			return;
		}
		
		showingWelcomeHikeConvTip = false;
		Conversation conversation = mAdapter.getItem(0);
		
		if (conversation instanceof ConversationTip && ((ConversationTip) conversation).isWelcomeHikeTip())
		{
			mAdapter.remove(conversation);
			notifyDataSetChanged();
		}
		HikeSharedPreferenceUtil.getInstance(getActivity()).saveData(HikeMessengerApp.SHOWN_WELCOME_HIKE_TIP, true);
	}

	private Conversation getFirstConversation()
	{
		Conversation conv = null;
		if (!displayedConversations.isEmpty())
		{
			conv = displayedConversations.get(0);
		}
		return conv;
	}

	/*
	 * Add item for stealth ftue conv tap tip.
	 */
	protected void showStealthConvTip()
	{
		displayedConversations.add(0, new ConversationTip(ConversationTip.STEALTH_FTUE_TIP));
		notifyDataSetChanged();
		HikeSharedPreferenceUtil.getInstance(getActivity()).saveData(HikeMessengerApp.SHOWING_STEALTH_FTUE_CONV_TIP, true);
		showingStealthFtueConvTip = true;
	}

	protected void removeStealthConvTip(Conversation conversation)
	{
		HikeSharedPreferenceUtil.getInstance(getActivity()).removeData(HikeMessengerApp.SHOWING_STEALTH_FTUE_CONV_TIP);
		showingStealthFtueConvTip = false;
		mAdapter.remove(conversation);
		notifyDataSetChanged();
	}

	private void clearConversation(String msisdn)
	{
		final Conversation conversation = mConversationsByMSISDN.get(msisdn);

		if (conversation == null)
		{
			return;
		}

		/*
		 * Clearing all current messages.
		 */
		List<ConvMessage> messages = conversation.getMessages();

		ConvMessage convMessage = null;
		if (!messages.isEmpty())
		{
			convMessage = messages.get(0);
		}

		messages.clear();

		/*
		 * Adding a blank message
		 */
		final ConvMessage newMessage = new ConvMessage("", msisdn, convMessage != null ? convMessage.getTimestamp() : 0, State.RECEIVED_READ);
		messages.add(newMessage);

		if (getActivity() == null)
		{
			return;
		}

		getActivity().runOnUiThread(new Runnable()
		{
			
			@Override
			public void run()
			{
				View parentView = getParenViewForConversation(conversation);

				if (parentView == null)
				{
					notifyDataSetChanged();
					return;
				}
				
				mAdapter.updateViewsRelatedToLastMessage(parentView, newMessage, conversation);
			}
		});
	}

	private ConvMessage findMessageById(long msgId)
	{
		for (Entry<String, Conversation> conversationEntry : mConversationsByMSISDN.entrySet())
		{
			Conversation conversation = conversationEntry.getValue();
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

	private View getParenViewForConversation(Conversation conversation)
	{
		int index = displayedConversations.indexOf(conversation);

		if (index == -1)
		{
			return null;
		}

		return getListView().getChildAt(index);
	}

	private void updateViewForNameChange(Conversation conversation)
	{
		View parentView = getParenViewForConversation(conversation);

		if (parentView == null)
		{
			notifyDataSetChanged();
			return;
		}

		mAdapter.updateViewsRelatedToName(parentView, conversation);
	}

	private void updateViewForMessageStateChange(Conversation conversation, ConvMessage convMessage)
	{
		View parentView = getParenViewForConversation(conversation);

		if (parentView == null)
		{
			notifyDataSetChanged();
			return;
		}

		mAdapter.updateViewsRelatedToMessageState(parentView, convMessage, conversation);
	}

	private void addMessage(Conversation conv, ConvMessage convMessage)
	{
		boolean newConversationAdded = false;

		if (!mConversationsAdded.contains(conv.getMsisdn()))
		{
			mConversationsAdded.add(conv.getMsisdn());
			displayedConversations.add(conv);

			newConversationAdded = true;
		}
		conv.addMessage(convMessage);
		Logger.d(getClass().getSimpleName(), "new message is " + convMessage);

		sortAndUpdateTheView(conv, convMessage, newConversationAdded);
	}

	private void sortAndUpdateTheView(Conversation conversation, ConvMessage convMessage, boolean newConversationAdded)
	{
		int prevIndex = displayedConversations.indexOf(conversation);

		Collections.sort(displayedConversations, mConversationsComparator);

		int newIndex = displayedConversations.indexOf(conversation);

		/*
		 * Here we check if the index of the item remained the same after sorting. If it did, we just need to update that item's view. If not, we need to call notifyDataSetChanged.
		 * OR if a new conversation was added, in that case we simply call notify.
		 */
		if (newConversationAdded || newIndex != prevIndex)
		{
			notifyDataSetChanged();
		}
		else
		{
			View parentView = getListView().getChildAt(newIndex);

			if (parentView == null)
			{
				notifyDataSetChanged();
				return;
			}

			mAdapter.updateViewsRelatedToLastMessage(parentView, convMessage, conversation);
		}
	}

	public void DeleteAllConversations()
	{
		if (!mAdapter.isEmpty())
		{
			Utils.logEvent(getActivity(), HikeConstants.LogEvent.DELETE_ALL_CONVERSATIONS_MENU);
			final CustomAlertDialog deleteDialog = new CustomAlertDialog(getActivity());
			deleteDialog.setHeader(R.string.deleteconversations);
			deleteDialog.setBody(R.string.delete_all_question);
			OnClickListener deleteAllOkClickListener = new OnClickListener()
			{

				@Override
				public void onClick(View v)
				{
					Conversation[] convs = new Conversation[mAdapter.getCount()];
					for (int i = 0; i < convs.length; i++)
					{
						convs[i] = mAdapter.getItem(i);
						if ((convs[i] instanceof GroupConversation))
						{
							HikeMessengerApp.getPubSub().publish(HikePubSub.MQTT_PUBLISH, convs[i].serialize(HikeConstants.MqttMessageTypes.GROUP_CHAT_LEAVE));
						}
					}
					DeleteConversationsAsyncTask task = new DeleteConversationsAsyncTask(getActivity());
					task.execute(convs);
					deleteDialog.dismiss();
				}
			};

			deleteDialog.setOkButton(R.string.delete, deleteAllOkClickListener);
			deleteDialog.setCancelButton(R.string.cancel);

			deleteDialog.show();
		}
	}

	@Override
	public void onResume()
	{
		SharedPreferences prefs = getActivity().getSharedPreferences(HikeMessengerApp.ACCOUNT_SETTINGS, 0);
		if (getActivity() == null && prefs.getInt(HikeConstants.HIKEBOT_CONV_STATE, 0) == hikeBotConvStat.VIEWED.ordinal())
		{
			/*
			 * if there is a HikeBotConversation in Conversation list also it is Viewed by user then delete this.
			 */
			Conversation conv = null;
			conv = mConversationsByMSISDN.get(HikeConstants.FTUE_HIKEBOT_MSISDN);
			if (conv != null)
			{
				Editor editor = prefs.edit();
				editor.putInt(HikeConstants.HIKEBOT_CONV_STATE, hikeBotConvStat.DELETED.ordinal());
				editor.commit();
				Utils.logEvent(getActivity(), HikeConstants.LogEvent.DELETE_CONVERSATION);
				DeleteConversationsAsyncTask task = new DeleteConversationsAsyncTask(getActivity());
				Utils.executeConvAsyncTask(task, conv);
			}
		}
		super.onResume();
	}

	public boolean hasNoConversation()
	{
		/*
		 * if group chat tip is showing we should remove this first and than add stealth ftue conversation tip
		 */
		Conversation conv = getFirstConversation();

		/*
		 * if conv not null this implies, We certainly have some conversations on the screen other than group chat tip
		 */
		return conv == null;
	}
	
	private class MenuArrayAdapter extends ArrayAdapter<CharSequence>
	{
		private boolean stealthFtueDone = true;
		private int stealthType;
		
		public MenuArrayAdapter(Context context, int resource, int textViewResourceId, String[] options)
		{
			super(context, resource, textViewResourceId, options);
			stealthFtueDone = HikeSharedPreferenceUtil.getInstance(getActivity()).getData(HikeMessengerApp.STEALTH_MODE_SETUP_DONE, false);
			stealthType = HikeSharedPreferenceUtil.getInstance(getActivity()).getData(HikeMessengerApp.STEALTH_MODE, HikeConstants.STEALTH_OFF);
			// TODO Auto-generated constructor stub
		}
		
		@Override
		public View getView(int position, View convertView, ViewGroup parent)
		{
			View v = super.getView(position, convertView, parent);
			
			if(!stealthFtueDone && stealthType == HikeConstants.STEALTH_ON && position == 0)
			{
				v.findViewById(R.id.intro_img).setVisibility(View.VISIBLE);
			}
			else
			{
				v.findViewById(R.id.intro_img).setVisibility(View.GONE);
			}
			// TODO Auto-generated method stub
			return v;
		}
		
	}
	
	protected void viewContacts(Conversation conv) 
	{
		Intent intent = new Intent(getActivity(), ProfileActivity.class);
		intent.putExtra(HikeConstants.Extras.CONTACT_INFO, conv.getMsisdn());
		intent.putExtra(HikeConstants.Extras.ON_HIKE, conv.isOnhike());
		startActivity(intent);
	}
	protected void viewGroupInfo(Conversation conv) {
		Intent intent = new Intent(getActivity(), ProfileActivity.class);
		intent.putExtra(HikeConstants.Extras.GROUP_CHAT, true);
		intent.putExtra(HikeConstants.Extras.EXISTING_GROUP_CHAT, conv.getMsisdn());
		startActivity(intent);
	}

	private void addToContacts(String msisdn)
	{
		Intent i = new Intent(Intent.ACTION_INSERT);
		i.setType(ContactsContract.RawContacts.CONTENT_TYPE);
		i.putExtra(Insert.PHONE, msisdn);
		startActivity(i);
	}

	private void addToContactsExisting(String msisdn)
	{
		Intent i = new Intent(Intent.ACTION_INSERT_OR_EDIT);
		i.setType(ContactsContract.Contacts.CONTENT_ITEM_TYPE);
		i.putExtra(Insert.PHONE, msisdn);
		startActivity(i);
	}

}
