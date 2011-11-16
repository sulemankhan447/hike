package com.bsb.im.service;

import com.bsb.im.R;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.provider.ContactsContract;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.widget.Toast;

public class ContactMap
{
	private Activity mActivity;

	public ContactMap(Activity act)
	{
		mActivity = act;
	}

	public String getContact()
	{
		String addressbook = "";
		ContentResolver content = mActivity.getContentResolver();
		// è·å¾æ?çèç³»äºº
		Cursor cur = content.query(ContactsContract.Contacts.CONTENT_URI, null,
				null, null, null);
		// å¾ªç¯éå
		if (cur.moveToFirst())
		{
			int idColumn = cur.getColumnIndex(ContactsContract.Contacts._ID);

			int displayNameColumn = cur
					.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME);
			do
			{
				// è·å¾èç³»äººçIDå?
				String contactId = cur.getString(idColumn);
				// è·å¾èç³»äººå§å?
				String disPlayName = cur.getString(displayNameColumn);
				
				// æ¥çè¯¥èç³»äººæå¤å°ä¸ªçµè¯å·ç ãå¦ææ²¡æè¿è¿åå¼ä¸º0
				int phoneCount = cur.getInt(cur.getColumnIndex(ContactsContract.Contacts.HAS_PHONE_NUMBER));
				if (phoneCount > 0)
				{
					// è·å¾èç³»äººççµè¯å·ç 
					// Cursor phones =
					// getContentResolver().query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,null,ContactsContract.CommonDataKinds.Phone.CONTACT_ID+
					// " = " + contactId, null, null);

					// è·å¾èç³»äººçææºå·ç 
					Cursor phones = content.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,null,ContactsContract.CommonDataKinds.Phone.CONTACT_ID+"="+contactId+"and"+ContactsContract.CommonDataKinds.Phone.TYPE+"="+ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE,null, null);
					if (phones.moveToFirst())
					{
						do
						{
							// éåæ?ççµè¯å·ç ?
							String phoneNumber = phones
									.getString(phones
											.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER));
							if(addressbook.equals(""))
							{
								addressbook = disPlayName + ":" + phoneNumber;
							}
							else
							{
								addressbook += "," + disPlayName + ":" + phoneNumber;
							}
						}
						while (phones.moveToNext());
					}
				}

			}
			while (cur.moveToNext());
		}
		return addressbook;
	}
	
	public String getPhoneNumber(){  
	    TelephonyManager mTelephonyMgr;  
	    mTelephonyMgr = (TelephonyManager) mActivity.getSystemService(Context.TELEPHONY_SERVICE);   
	    return mTelephonyMgr.getLine1Number();  
	}   
}
