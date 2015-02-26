package com.bsb.hike.productpopup;

import java.util.ArrayList;
import java.util.Collections;

import org.json.JSONObject;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.util.SparseArray;

import com.bsb.hike.HikeMessengerApp;
import com.bsb.hike.db.HikeContentDatabase;
import com.bsb.hike.models.HikeAlarmManager;
import com.bsb.hike.models.HikeHandlerUtil;
import com.bsb.hike.notifications.HikeNotification;
import com.bsb.hike.platform.content.PlatformContent;
import com.bsb.hike.platform.content.PlatformContent.EventCode;
import com.bsb.hike.platform.content.PlatformContentModel;
import com.bsb.hike.productpopup.ProductPopupsConstants.PopupStateEnum;
import com.bsb.hike.productpopup.ProductPopupsConstants.PopupTriggerPoints;
import com.bsb.hike.utils.Logger;
import com.bsb.hike.utils.Utils;

/**
 * 
 * @author himanshu
 * 
 *         The Manager to ProductPopus.This call handles when the popups need to be shown ,all interactions with the ProductPopup DB.
 */
public class ProductInfoManager
{
	// Handler that is running on the backend thread.

	private final Handler handler = new Handler(HikeHandlerUtil.getInstance().getLooper());

	private static final ProductInfoManager mmProductInfoMgr = new ProductInfoManager();

	// <TRIGGER_POINT,ARRAYLIST<POPUPS>
	private SparseArray<ArrayList<ProductContentModel>> mmSparseArray = new SparseArray<ArrayList<ProductContentModel>>();

	Context context = null;

	// Singleton class
	private ProductInfoManager()
	{
		context = HikeMessengerApp.getInstance().getApplicationContext();

	}

	// To be called once in on create of HikeMessangerApp.
	public void init()
	{
		handler.post(new Runnable()
		{

			@Override
			public void run()
			{
				mmSparseArray = HikeContentDatabase.getInstance(context).getAllPopup();
			}
		});

	}

	public static ProductInfoManager getInstance()
	{
		return mmProductInfoMgr;
	}

	/**
	 * 
	 * @param val
	 * @param iShowPopup
	 *            -Listener to return the result on the Calling Activity
	 * 
	 *            Logic :this method check taht if there is any popup on a particular trigger point ...
	 * 
	 *            We get the Arraylist containing of all the popups for the corresponding trigger point.We sort it according to the start time.Then we check the popup with the
	 *            highest start time whose endtime is also valid ..And we delete all the popups with the lower start time ..and we do nothing for the popups whose start time is
	 *            greater that the Present start time(Future Popus)
	 * 
	 * 
	 */
	public void isThereAnyPopup(Integer val, IActivityPopup iShowPopup)
	{
		ProductContentModel mmModel = null;
		long presentTime = System.currentTimeMillis();
		ArrayList<ProductContentModel> popUpToBeDeleted=null;
		if (mmSparseArray.get(val) != null && !mmSparseArray.get(val).isEmpty())
		{
			popUpToBeDeleted = new ArrayList<ProductContentModel>();
			ArrayList<ProductContentModel> mmArray = mmSparseArray.get(val);
			Collections.sort(mmArray, ProductContentModel.ProductContentStartTimeComp);
			for (ProductContentModel productContentModel : mmArray)
			{
				if (productContentModel.getStarttime() <= presentTime)
				{
					if (productContentModel.getEndtime() >= presentTime && mmModel == null)
					{
						mmModel = productContentModel;
						Logger.d("ProductPopup", productContentModel.getTriggerpoint() + ">>>>>" + productContentModel.getStarttime() + ">>>>>>" + productContentModel.getEndtime());
					}
					else
					{
						popUpToBeDeleted.add(productContentModel);
					}
				}
			}
		}

		deletePopups(popUpToBeDeleted);

		if (mmModel != null)
		{
			parseAndShowPopup(mmModel, iShowPopup);
		}
		else
		{
			iShowPopup.onFailure();
		}
	}

	/**
	 * 
	 * @param mmArrayList
	 * 
	 *            Deleting all the Popups from the Database and form the memory
	 */
	public void deletePopups(final ArrayList<ProductContentModel> mmArrayList)
	{

		// Deleting all the things on Backend thread;
		handler.post(new Runnable()
		{

			@Override
			public void run()
			{
				if (mmArrayList != null && !mmArrayList.isEmpty())
				{
					
					String[] hashCode = new String[mmArrayList.size()];
					int length = 0;
					for (ProductContentModel a : mmArrayList)
					{
						hashCode[length++] = a.hashCode() + "";

					}
					Logger.d("ProductPopup",hashCode.toString());
					HikeContentDatabase.getInstance().deletePopup(hashCode);
					int triggerPoint = (mmArrayList.get(0).getTriggerpoint());
					Logger.d("ProductPopup", "start deleting" + mmArrayList.get(0).getTriggerpoint() + "<<<<<");

					if (mmSparseArray.get(triggerPoint) != null)
						mmSparseArray.get(triggerPoint).removeAll(mmArrayList);

					Logger.d("ProductPopup", "End deleting" + mmArrayList.toString());
				}
			}
		});

	}

	/**
	 * 
	 * @param productContentModel
	 * @param iShowPopup
	 *            -Listener to return the result to the calling activity
	 * 
	 *            This method is responsible for downloading the zip if not present and then mustaching the template,validating the data
	 */
	private void parseAndShowPopup(final ProductContentModel productContentModel, final IActivityPopup iShowPopup)
	{

		PlatformContent.getContent(productContentModel.toJSONString(), new PopupContentListener(productContentModel, iShowPopup)
		{
			ProductContentModel productContentModel = null;

			@Override
			public void onEventOccured(EventCode event)
			{
				productContentModel = getProductContentModel();

				switch (event)
				{
				case LOW_CONNECTIVITY:
					HikeContentDatabase.getInstance(context).updatePopupStatus(productContentModel.hashCode(), PopupStateEnum.NOT_DOWNLOADED.ordinal());
					if (getListener() != null)
					{
						getListener().onFailure();
					}
					break;
				case INVALID_DATA:

				case STORAGE_FULL:

					ArrayList<ProductContentModel> mmArrayList = new ArrayList<ProductContentModel>();
					mmArrayList.add(productContentModel);
					deletePopups(mmArrayList);
					if (getListener() != null)
					{
						getListener().onFailure();
					}
					break;
				default:

					break;
				}
			}

			@Override
			public void onComplete(PlatformContentModel content)
			{
				productContentModel = getProductContentModel();

				if (getListener() != null)
				{
					productContentModel.setFormedData(content.getFormedData());
					getListener().onSuccess(productContentModel);
					//
					// // deleting the popup from the Memory
					// ArrayList<ProductContentModel> mmArrayList = mmSparseArray.get(productContentModel.getTriggerpoint());
					// if (mmArrayList != null)
					// {
					// mmArrayList.remove(productContentModel);
					// }
					// mmArrayList = new ArrayList<ProductContentModel>();
					//
					// mmArrayList.add(productContentModel);
					//
					// // deleting the popup from the DataBase
					// deletePopups(mmArrayList);
				}
				else
				{
					handlePushScenarios(productContentModel);
					if (mmSparseArray.get(productContentModel.getTriggerpoint()) != null)
					{
						ArrayList<ProductContentModel> mmArrayList = mmSparseArray.get(productContentModel.getTriggerpoint());
						mmArrayList.add(productContentModel);
					}
					else
					{
						ArrayList<ProductContentModel> mmArrayList = new ArrayList<ProductContentModel>();
						mmArrayList.add(productContentModel);
						mmSparseArray.put(productContentModel.getTriggerpoint(), mmArrayList);
					}
					HikeContentDatabase.getInstance(context).updatePopupStatus(productContentModel.hashCode(), PopupStateEnum.DOWNLOADED.ordinal());
				}
			}

		});

	}

	private void handlePushScenarios(ProductContentModel productContentModel)
	{
		if (productContentModel.isPushReceived())
		{

			Intent intent = new Intent();
			intent.putExtra(ProductPopupsConstants.NOTIFICATION_TEXT, productContentModel.getNotifText());
			intent.putExtra(ProductPopupsConstants.NOTIFICATION_TITLE, productContentModel.getNotifTitle());
			intent.putExtra(ProductPopupsConstants.PUSH_SOUND, productContentModel.shouldPlaySound());
			intent.putExtra(ProductPopupsConstants.TRIGGER_POINT, productContentModel.getTriggerpoint());
			if (productContentModel.isPushFuture())
			{
				
				Logger.d("ProductPopup","Future Push Received"+productContentModel.getPushTime()+"......."+ System.currentTimeMillis());
				HikeAlarmManager.setAlarmWithIntent(HikeMessengerApp.getInstance().getApplicationContext(),productContentModel.getPushTime(),
						HikeAlarmManager.REQUESTCODE_PRODUCT_POPUP, true, intent);
			}
			else
			{	
				NotificationContentModel mmNotificationContentModel=new NotificationContentModel(productContentModel.getNotifTitle(), productContentModel.getUser(), productContentModel.shouldPlaySound(), productContentModel.getTriggerpoint());
				ProductInfoManager.getInstance().notifyUser(mmNotificationContentModel);
			}
		}
	}

	/**
	 * 
	 * @param metaData
	 *            of the Json
	 * 
	 *            Saving the popup.
	 */
	public void parsePopupPacket(JSONObject metaData)
	{

		Logger.d("ProductPopup", "Popup received Going to inserti in DB");

		// saving the Popup in DataBase:
		ProductContentModel productContentModel = ProductContentModel.makeProductContentModel(metaData);

		HikeContentDatabase.getInstance(context).savePopup(productContentModel, PopupStateEnum.NOT_DOWNLOADED.ordinal());

		parseAndShowPopup(productContentModel, null);

	}

	/**
	 * 
	 * @param intent
	 * 
	 *            Send a push to the user
	 */
	public void notifyUser(NotificationContentModel notificationContentModel)
	{
		String title = notificationContentModel.getTitle();
		String user = notificationContentModel.getUser();
		boolean shouldPlaySound = notificationContentModel.isShouldPlaySound();
		int triggerpoint = notificationContentModel.getTriggerpoint();

		if (triggerpoint == PopupTriggerPoints.CHAT_SCR.ordinal())
		{
			HikeNotification.getInstance(context).notifyStringMessage(text, title, !shouldPlaySound);

		}
		else
		{
			HikeNotification.getInstance(context).notifyUserAndOpenHomeActivity(user, title, !shouldPlaySound);
		}
	}

	/**
	 * 
	 * @param mmModel
	 * @return
	 * 
	 *         Creating a Dialog Pojo ...Utility method
	 */
	public DialogPojo getDialogPojo(ProductContentModel mmModel)
	{
		DialogPojo mmPojo = new DialogPojo(mmModel.isFullScreen(), mmModel.getHeight(), mmModel.getFormedData());
		mmPojo.setData(mmModel);
		return mmPojo;
	}

}
