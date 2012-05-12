package com.bsb.hike.adapters;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import android.app.Activity;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import com.bsb.hike.HikeMessengerApp;
import com.bsb.hike.HikePubSub;
import com.bsb.hike.R;
import com.bsb.hike.db.HikeUserDatabase;
import com.bsb.hike.models.ContactInfo;
import com.bsb.hike.models.utils.IconCacheManager;
import com.bsb.hike.utils.Utils;

public class HikeBlockedUserAdapter extends HikeArrayAdapter implements OnClickListener
{

	private Set<String> blockedUsers;
	private Activity context;

	private static List<ContactInfo> getItems(Activity activity)
	{
		HikeUserDatabase db = new HikeUserDatabase(activity);
		List<ContactInfo> contacts = db.getContacts();
		db.close();
		Collections.sort(contacts);
		return contacts;
	}

	public HikeBlockedUserAdapter(Activity activity, int viewItemId)
	{
		super(activity, viewItemId, getItems(activity));
		this.context = activity;
		HikeUserDatabase db = new HikeUserDatabase(activity);
		this.blockedUsers = db.getBlockedUsers();
		db.close();
	}

	@Override
	protected View getItemView(int position, View convertView, ViewGroup parent)
	{
		ContactInfo contactInfo = (ContactInfo) getItem(position);
		LayoutInflater inflater = (LayoutInflater) activity.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
		View v = convertView;
		if (v == null)
		{
			v = inflater.inflate(R.layout.contact_item, parent, false);
		}

		TextView textView = (TextView) v.findViewById(R.id.name);
		textView.setText(contactInfo.getName());
		
		TextView numView = (TextView) v.findViewById(R.id.number);
		numView.setText(contactInfo.getPhoneNum());
		
		ImageView imageView = (ImageView) v.findViewById(R.id.contact_image);
		imageView.setPadding(8, 8, 18, 8);
		
		if(contactInfo.hasCustomPhoto())
		{
			imageView.setImageDrawable(IconCacheManager.getInstance().getIconForMSISDN(contactInfo.getMsisdn()));
		}
		else
		{
			imageView.setImageDrawable(Utils.getDefaultIconForUser(context, contactInfo.getMsisdn()));
		}
		
		Button button = (Button) v.findViewById(R.id.contact_button);
		button.setSelected(blockedUsers.contains(contactInfo.getMsisdn()));
		
		v.setTag(contactInfo);
		v.setOnClickListener(this);

		return v;
	}

	@Override
	public void onClick(View view)
	{
		ContactInfo contactInfo = (ContactInfo) view.getTag();
		String msisdn = (String) contactInfo.getMsisdn();
		boolean block = !blockedUsers.contains(msisdn);
		boolean b = (block) ? blockedUsers.add(msisdn) : blockedUsers.remove(msisdn);
		this.notifyDataSetChanged();
		HikeMessengerApp.getPubSub().publish(block ? HikePubSub.BLOCK_USER : HikePubSub.UNBLOCK_USER, msisdn);
	}

	@Override
	public String getTitle()
	{
		return context.getResources().getString(R.string.block_users);
	}
}
