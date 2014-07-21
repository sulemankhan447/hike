package com.bsb.hike.modules.contactmgr;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import android.database.DatabaseUtils;
import android.util.Pair;

import com.bsb.hike.db.HikeConversationsDatabase;
import com.bsb.hike.models.ContactInfo;
import com.bsb.hike.utils.Logger;
import com.bsb.hike.utils.Utils;

class PersistenceCache extends ContactsCache
{
	private HikeUserDatabase hDb;

	// Memory persistence for all one to one conversation contacts that should always be loaded
	private Map<String, ContactInfo> convsContactsPersistence;

	// Memory persistence for all group conversation last messaged contacts with reference count that should always be loaded
	private Map<String, ContactTuple> groupContactsPersistence;

	// Memory persistence for all group names and list of msisdns(last message in group) that should always be loaded
	private Map<String, GroupDetails> groupPersistence;

	private final ReentrantReadWriteLock readWriteLock = new ReentrantReadWriteLock(true);

	private final Lock readLock = readWriteLock.readLock();

	private final Lock writeLock = readWriteLock.writeLock();

	/**
	 * Initializes all the maps and calls {@link #loadMemory} which fills all the map when HikeService is started
	 */
	PersistenceCache(HikeUserDatabase db)
	{
		hDb = db;
		convsContactsPersistence = new HashMap<String, ContactInfo>();
		groupContactsPersistence = new HashMap<String, ContactTuple>();
		groupPersistence = new HashMap<String, GroupDetails>();
		loadMemory();
	}

	/**
	 * get contact info from memory. Returns null if not found in memory. The implementation is thread safe.
	 * 
	 * @param key
	 * @return
	 */
	ContactInfo getContact(String key)
	{
		readLock.lock();
		try
		{
			ContactInfo c = null;
			c = convsContactsPersistence.get(key);
			if (null == c) // contact not found in persistence cache
			{
				ContactTuple tuple = groupContactsPersistence.get(key);
				if (null != tuple)
				{
					c = tuple.getContact();
				}
			}
			return c;
		}
		finally
		{
			readLock.unlock();
		}
	}

	/**
	 * Thread safe method that inserts contact in {@link #convsContactsPersistence}.
	 * 
	 * @param contact
	 */
	void insertContact(ContactInfo contact)
	{
		insertContact(contact, true);
	}

	/**
	 * Inserts contact in {@link #groupContactsPersistence}.
	 * <p>
	 * Make sure that it is not already in memory , we are setting reference count to one here.This implementation is thread safe.
	 * </p>
	 * 
	 * @param contact
	 * @param name
	 *            name if contact is not saved in address book otherwise null
	 */
	void insertContact(ContactInfo contact, boolean ifOneToOneConversation)
	{
		writeLock.lock();
		try
		{
			if (ifOneToOneConversation)
			{
				convsContactsPersistence.put(contact.getMsisdn(), contact);
			}
			else
			{
				ContactTuple tuple = new ContactTuple(1, contact);
				groupContactsPersistence.put(contact.getMsisdn(), tuple);
			}
		}
		finally
		{
			writeLock.unlock();
		}
	}

	/**
	 * Removes the contact from {@link #convsContactsPersistence}.
	 * 
	 * @param contact
	 */
	void removeContact(String msisdn)
	{
		removeContact(msisdn, true);
	}

	/**
	 * Removes the contact the {@link #convsContactsPersistence} OR decrements the reference count in {@link #groupContactsPersistence} , if reference count becomes 0 then removes
	 * from this map Depending on whether it is one to one conversation or group conversation . This method is thread safe.
	 * 
	 * @param contact
	 * @param ifOneToOneConversation
	 *            true if it is one to one conversation contact otherwise false
	 */
	void removeContact(String msisdn, boolean ifOneToOneConversation)
	{
		writeLock.lock();
		try
		{
			removeFromCache(msisdn, ifOneToOneConversation);
		}
		finally
		{
			writeLock.unlock();
		}
	}

	/**
	 * This method is not Thread safe , removes the contact for a particular msisdn from the cache - if it is one to one conversation removes from {@link #convsContactsPersistence}
	 * otherwise decrements the reference count by one , if reference count becomes zero then remove it completely from {@link #groupContactsPersistence}
	 * 
	 * @param msisdn
	 * @param ifOneToOneConversation
	 *            used to determine whether to remove from conversation contacts map or group map
	 */
	private void removeFromCache(String msisdn, boolean ifOneToOneConversation)
	{
		if (ifOneToOneConversation)
		{
			convsContactsPersistence.remove(msisdn);
		}
		else
		{
			ContactTuple tuple = groupContactsPersistence.get(msisdn);
			if (null != tuple)
			{
				tuple.setReferenceCount(tuple.getReferenceCount() - 1);
				if (tuple.getReferenceCount() == 0)
				{
					groupContactsPersistence.remove(msisdn);
				}
			}
		}
	}

	/**
	 * This method is thread safe and removes the mapping for particular grpId (if group is deleted) and their corresponding last msisnds are removed from the group contacts map
	 * 
	 * @param grpId
	 */
	void removeGroup(String grpId)
	{
		writeLock.lock();
		try
		{
			GroupDetails pp = groupPersistence.get(grpId);
			ConcurrentLinkedQueue<pair> lastMsisdns = pp.getLastMsisdns();
			for (pair msPair : lastMsisdns)
			{
				removeFromCache(msPair.first, false);
			}
			groupPersistence.remove(grpId);
		}
		finally
		{
			writeLock.unlock();
		}
	}

	/**
	 * Thread safe method that updates the contact in memory.
	 * 
	 * @param contact
	 */
	void updateContact(ContactInfo contact)
	{
		writeLock.lock();
		try
		{
			if (convsContactsPersistence.containsKey(contact.getMsisdn()))
			{
				convsContactsPersistence.put(contact.getMsisdn(), contact);
			}
			else
			{
				ContactTuple tuple = groupContactsPersistence.get(contact.getMsisdn());
				if (null != tuple)
				{
					tuple.setContact(contact);
				}
			}
		}
		finally
		{
			writeLock.unlock();
		}
	}

	/**
	 * Returns name of group if msisdn is group ID else contact name - if contact is unsaved then this method returns null as for unsaved contact we need groupId to get name. This
	 * implementation is thread safe.
	 * 
	 * @param msisdn
	 * @return
	 */
	String getName(String msisdn)
	{
		/**
		 * Always try to take locks when and where required. Here we are separating out locking into different zones so that lock acquired should be for minimum time possible.
		 */
		if (Utils.isGroupConversation(msisdn))
		{
			readLock.lock();
			try
			{
				GroupDetails grpDetails = groupPersistence.get(msisdn);
				if (null == grpDetails)
					return null;
				return grpDetails.getGroupName();
			}
			finally
			{
				readLock.unlock();
			}
		}
		readLock.lock();
		try
		{
			ContactInfo c = null;
			c = convsContactsPersistence.get(msisdn);
			if (null == c)
			{
				ContactTuple tuple = groupContactsPersistence.get(msisdn);
				if (null != tuple)
				{
					c = tuple.getContact();
				}
			}

			if (null == c)
				return null;

			return c.getName();
		}
		finally
		{
			readLock.unlock();
		}
	}

	/**
	 * Returns name of an unsaved contact using groupId as unsaved contacts name is different in different groups
	 * 
	 * @param groupId
	 * @param msisdn
	 * @return
	 */
	String getName(String groupId, String msisdn)
	{
		GroupDetails grpDetails = groupPersistence.get(groupId);
		if (null != grpDetails)
			return grpDetails.getName(msisdn);
		return null;
	}

	/**
	 * This function sets name in {@link #groupContactsPersistence} cache if the contact is not saved.
	 * <p>
	 * Suppose some unsaved contact is already in memory and it is added in a group then we set the name of contact using group table.
	 * </p>
	 * 
	 * @param msisdn
	 * @param name
	 */
	void setUnknownContactName(String grpId, String msisdn, String name)
	{
		writeLock.lock();
		try
		{
			GroupDetails grpDet = groupPersistence.get(grpId);
			grpDet.setName(msisdn, name);
		}
		finally
		{
			writeLock.unlock();
		}
	}

	/**
	 * This will load all persistence contacts in memory. This method is called when HikeService is started and fills all the persistence cache at that time only. This method is
	 * not thread safe therefore should be called before mqtt thread is started.
	 */
	void loadMemory()
	{
		Pair<List<String>, Map<String, List<String>>> allmsisdns = HikeConversationsDatabase.getInstance().getConversationMsisdns();
		List<String> oneToOneMsisdns = allmsisdns.first;
		Map<String, List<String>> groupLastMsisdnsMap = allmsisdns.second;

		Map<String, String> groupNamesMap = HikeConversationsDatabase.getInstance().getGroupNames();

		List<String> grouplastMsisdns = new ArrayList<String>();

		for (Entry<String, String> mapEntry : groupNamesMap.entrySet())
		{
			String grpId = mapEntry.getKey();
			String name = mapEntry.getValue();
			List<String> lastMsisdns = groupLastMsisdnsMap.get(grpId);
			ConcurrentLinkedQueue<pair> lastMsisdnsConcurrentLinkedQueue = new ConcurrentLinkedQueue<pair>();
			if (null != lastMsisdns)
			{
				grouplastMsisdns.addAll(lastMsisdns);
				for (String ms : lastMsisdns)
				{
					lastMsisdnsConcurrentLinkedQueue.add(new pair(ms, null));
				}
			}
			GroupDetails grpDetails = new GroupDetails(name, lastMsisdnsConcurrentLinkedQueue);
			groupPersistence.put(grpId, grpDetails);
		}

		List<String> msisdnsToGetContactInfo = new ArrayList<String>();
		msisdnsToGetContactInfo.addAll(oneToOneMsisdns);
		msisdnsToGetContactInfo.addAll(grouplastMsisdns);

		Map<String, ContactInfo> contactsMap = new HashMap<String, ContactInfo>();
		if (msisdnsToGetContactInfo.size() > 0)
		{
			contactsMap = hDb.getContactInfoFromMsisdns(msisdnsToGetContactInfo, true);
		}

		// grouplastMsisdns list convert it to map
		Map<String, Boolean> temp = new HashMap<String, Boolean>();
		for (String ms : grouplastMsisdns)
		{
			temp.put(ms, true);
		}

		// traverse through oneToOneMsisdns and get from contactsMap and put in convsContactsPersistence , remove from contactsMap if not present in grouplastMsisdns map
		for (String ms : oneToOneMsisdns)
		{
			ContactInfo contact = contactsMap.get(ms);
			convsContactsPersistence.put(ms, contact);
			if (!temp.containsKey(ms))
			{
				contactsMap.remove(ms);
			}
		}

		// traverse through contactsMap which is left put in groupContactsPersistence if contactInfo name is null we have to get names for that
		StringBuilder unknownGroupMsisdns = new StringBuilder("(");
		for (Entry<String, ContactInfo> mapEntry : contactsMap.entrySet())
		{
			String msisdn = mapEntry.getKey();
			ContactInfo contact = mapEntry.getValue();
			ContactTuple tuple = new ContactTuple(1, contact);
			groupContactsPersistence.put(msisdn, tuple);

			if (null == contact.getName())
			{
				unknownGroupMsisdns.append(DatabaseUtils.sqlEscapeString(msisdn) + ",");
			}
		}

		// get names of unknown group contacts from group members table
		Map<String, Map<String, String>> unknownGroupMsisdnsName = new HashMap<String, Map<String, String>>();

		int idx = unknownGroupMsisdns.lastIndexOf(",");
		if (idx >= 0)
		{
			unknownGroupMsisdns.replace(idx, unknownGroupMsisdns.length(), ")");
			unknownGroupMsisdnsName = HikeConversationsDatabase.getInstance().getGroupMembersName(unknownGroupMsisdns.toString());
		}

		// set names for unknown group contacts
		for (Entry<String, GroupDetails> mapEntry : groupPersistence.entrySet())
		{
			String groupId = mapEntry.getKey();
			GroupDetails grpDetails = mapEntry.getValue();
			ConcurrentLinkedQueue<pair> list = grpDetails.getLastMsisdns();
			Map<String, String> map = unknownGroupMsisdnsName.get(groupId);
			if (null != map)
			{
				for (pair msPair : list)
				{
					msPair.second = map.get(msPair.first);
				}
			}
		}
	}

	/**
	 * This method makes a database query to load the contact for a msisdn in persistence convs map and returns the same
	 * 
	 * @param msisdn
	 * @param ifNotFoundReturnNull
	 *            if true returns null if contact is not saved in address book
	 */
	ContactInfo putInCache(String msisdn, boolean ifNotFoundReturnNull)
	{
		return putInCache(msisdn, ifNotFoundReturnNull, true);
	}

	/**
	 * This method makes a database query to load the contact info for a msisdn in persistence memory and returns the same
	 * 
	 * @param msisdn
	 * @param ifNotFoundReturnNull
	 *            if true returns null if contact is not saved
	 * @param ifOneToOneConversation
	 *            used to determine whether to put in {@link #convsContactsPersistence} or {@link #groupContactsPersistence}.
	 * @return Returns contact info object
	 */
	ContactInfo putInCache(String msisdn, boolean ifNotFoundReturnNull, boolean ifOneToOneConversation)
	{
		ContactInfo contact = hDb.getContactInfoFromMSISDN(msisdn, ifNotFoundReturnNull);

		insertContact(contact, ifOneToOneConversation);

		return contact;
	}

	/**
	 * This method makes a database query to load the contactInfo of msisdns in {@link #convsContactsPersistence} and returns the list of same
	 * 
	 * @param msisdns
	 */
	List<ContactInfo> putInCache(List<String> msisdns)
	{
		return putInCache(msisdns, true);
	}

	/**
	 * This method makes a database query to load the contactInfo of msisdns in persistence memory and returns the list of same
	 * 
	 * @param msisdns
	 * @param ifOneToOneConversation
	 *            used to determine whether to put in {@link #convsContactsPersistence} or {@link #groupContactsPersistence}.
	 * @return
	 */
	List<ContactInfo> putInCache(List<String> msisdns, boolean ifOneToOneConversation)
	{
		if (msisdns.size() > 0)
		{
			Map<String, ContactInfo> map = hDb.getContactInfoFromMsisdns(msisdns, true);

			for (Entry<String, ContactInfo> mapEntry : map.entrySet())
			{
				ContactInfo contact = mapEntry.getValue();

				insertContact(contact, ifOneToOneConversation);

			}

			return new ArrayList<ContactInfo>(map.values());
		}

		return null;
	}

	/**
	 * This method Updates the contact by setting the name to null for the contact that is deleted from address book
	 * 
	 * @param contact
	 */
	void contactDeleted(ContactInfo contact)
	{
		ContactInfo updatedContact = new ContactInfo(contact);
		updatedContact.setName(null);
		updateContact(updatedContact);
	}

	/**
	 * This method is used for removing msisdns from the group persistence cache when last message in group is changed and their reference count is decremented in group contacts
	 * map by one which is done by {@link #removeFromCache} method
	 * 
	 * @param groupId
	 * @param currentGroupMsisdns
	 */
	List<String> removeOlderLastGroupMsisdn(String groupId, List<String> currentGroupMsisdns)
	{
		if (groupPersistence != null)
		{
			GroupDetails nameAndLastMsisdns;
			readLock.lock();
			try
			{
				nameAndLastMsisdns = groupPersistence.get(groupId);
			}
			finally
			{
				readLock.unlock();
			}

			List<String> msisdns = new ArrayList<String>();
			if (null != nameAndLastMsisdns)
			{
				ConcurrentLinkedQueue<pair> grpMsisdns = nameAndLastMsisdns.getLastMsisdns();
				if (null != grpMsisdns)
				{
					boolean flag;
					writeLock.lock();
					try
					{
						for (pair msisdnPair : grpMsisdns)
						{
							flag = false;
							for (String ms : currentGroupMsisdns)
							{
								if (ms.equals(msisdnPair.first))
								{
									flag = true;
									break;
								}
							}
							if (!flag)
							{
								removeFromCache(msisdnPair.first, false);
							}
							else
							{
								// if contact is in group map then increase ref count by 1
								ContactTuple tuple = groupContactsPersistence.get(msisdnPair.first);
								if (null != tuple)
								{
									tuple.setReferenceCount(tuple.getReferenceCount() + 1);
								}
								else
								{
									// if contact is in convsMap then insert in groupMap with ref count 1
									ContactInfo contact = convsContactsPersistence.get(msisdnPair.first);
									if (null == contact)
									{
										// get contact from db
										// add to a list
										msisdns.add(msisdnPair.first);
									}
									else
									{
										insertContact(contact, false);
									}
								}
							}
						}
					}
					finally
					{
						writeLock.unlock();
					}
				}

				// lock is not needed here because grpMsisdns is concurrentLinkedQueue
				grpMsisdns.clear();

				for (String ms : currentGroupMsisdns)
				{
					pair msisdnNamePair = new pair(ms, null); // TODO name for unsaved contact
					grpMsisdns.add(msisdnNamePair);
				}
			}
			// returning msisdns which are not found in persistence cache because before making new objects we should also check transient cache
			return msisdns;
		}
		return null;
	}

	/**
	 * clears the persistence memory
	 */
	void clearMemory()
	{
		writeLock.lock();
		try
		{
			if (null != convsContactsPersistence)
			{
				convsContactsPersistence.clear();
			}

			if (null != groupContactsPersistence)
			{
				groupContactsPersistence.clear();
			}

			if (null != groupPersistence)
			{
				groupPersistence.clear();
			}
		}
		finally
		{
			writeLock.unlock();
		}
	}

	/**
	 * If group conversation, check in groupPersistence else check in convPersistance
	 * 
	 * @param id
	 * @return
	 */
	boolean convExist(String id)
	{
		readLock.lock();
		try
		{
			if (Utils.isGroupConversation(id))
				return groupPersistence.containsKey(id);
			else
				return convsContactsPersistence.containsKey(id);
		}
		finally
		{
			readLock.unlock();
		}
	}

	public boolean isGroupExists(String groupId)
	{
		readLock.lock();
		try
		{
			return groupPersistence.containsKey(groupId);
		}
		finally
		{
			readLock.unlock();
		}
	}

	public void insertGroup(String grpId, String groupName)
	{
		writeLock.lock();
		try
		{
			ConcurrentLinkedQueue<pair> clq = new ConcurrentLinkedQueue<pair>();
			GroupDetails grpDetails = new GroupDetails(groupName, clq);
			groupPersistence.put(grpId, grpDetails);
		}
		finally
		{
			writeLock.unlock();
		}
	}
}