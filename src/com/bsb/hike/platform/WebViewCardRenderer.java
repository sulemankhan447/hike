package com.bsb.hike.platform;

import java.util.ArrayList;

import android.app.AlertDialog;
import android.content.Context;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JsResult;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.widget.BaseAdapter;

import com.bsb.hike.R;
import com.bsb.hike.models.ConvMessage;
import com.bsb.hike.platform.content.PlatformContent;
import com.bsb.hike.platform.content.PlatformContentListener;
import com.bsb.hike.platform.content.PlatformContentModel;
import com.bsb.hike.platform.content.PlatformWebClient;
import com.bsb.hike.utils.Logger;

/**
 * Created by shobhitmandloi on 14/01/15.
 */
public class WebViewCardRenderer extends BaseAdapter
{
	
	static final String tag = "webviewcardRenderer";
	
	Context mContext;

	ArrayList<ConvMessage> convMessages;

	BaseAdapter adapter;

	public WebViewCardRenderer(Context context, ArrayList<ConvMessage> convMessages)
	{
		this.mContext = context;
		this.convMessages = convMessages;
	}
	
	public WebViewCardRenderer(Context context, ArrayList<ConvMessage> convMessages,BaseAdapter adapter)
	{
		this.mContext = context;
		this.adapter =  adapter;
		this.convMessages = convMessages;
	}

	private static class WebViewHolder
	{

		WebView myBrowser;
		PlatformJavaScriptBridge platformJavaScriptBridge;
	}

	private WebViewHolder initializaHolder(WebViewHolder holder, View view, ConvMessage convMessage)
	{
		holder.myBrowser = (WebView) view.findViewById(R.id.webcontent);
		holder.myBrowser.setVerticalScrollBarEnabled(false);
		holder.myBrowser.setHorizontalScrollBarEnabled(false);
		holder.platformJavaScriptBridge = new PlatformJavaScriptBridge(mContext, holder.myBrowser, convMessage, this);

		return holder;
	}

	@Override
	public int getCount()
	{
		return 0;
	}

	@Override
	public Object getItem(int position)
	{
		return convMessages.get(position);
	}

	@Override
	public long getItemId(int position)
	{
		return convMessages.get(position).getMsgID();
	}

	@Override
	public View getView(int position, View convertView, ViewGroup parent)
	{
		Logger.i(tag, "get view with called with position "+position);
		WebViewHolder viewHolder = new WebViewHolder();
		View view = convertView;
		final ConvMessage convMessage = (ConvMessage) getItem(position);
		LayoutInflater inflater = (LayoutInflater) mContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
		if (view == null)
		{
			view = inflater.inflate(R.layout.html_item, parent, false);
			initializaHolder(viewHolder, view, convMessage);
			view.setTag(viewHolder);
		}
		else
		{
			viewHolder = (WebViewHolder) view.getTag();
		}

		final WebView web = viewHolder.myBrowser;


		final WebViewHolder finalViewHolder = viewHolder;
		PlatformContent.getContent(convMessage.platformWebMessageMetadata.JSONtoString(), new PlatformContentListener< PlatformContentModel >()
		{
			public void onComplete(PlatformContentModel content)
			{
				web.addJavascriptInterface(finalViewHolder.platformJavaScriptBridge, HikePlatformConstants.PLATFORM_BRIDGE_NAME);
				finalViewHolder.platformJavaScriptBridge.allowUniversalAccess();
				finalViewHolder.platformJavaScriptBridge.allowDebugging();
				web.getSettings().setJavaScriptEnabled(true);
				web.setWebViewClient(new CustomWebViewClient(convMessage));
				web.loadDataWithBaseURL("", content.getFormedData(), "text/html", "UTF-8", "");
			}});
		
		return view;

	}

	private class CustomWebViewClient extends PlatformWebClient
	{

		ConvMessage convMessage;

		public CustomWebViewClient(ConvMessage convMessage)
		{
			this.convMessage = convMessage;
		}

		@Override
		public void onPageFinished(WebView view, String url)
		{
			view.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
			view.requestLayout();
			view.setVisibility(View.VISIBLE);
			Log.d("tag", "Height of webView after loading is " + String.valueOf(view.getMeasuredHeight()) + "px");
			Logger.d(tag, "conv message passed to webview " + convMessage);
			view.loadUrl("javascript:setData(" + "'" + convMessage.getMsgID() + "','" + convMessage.getMsisdn() + "','" + convMessage.platformWebMessageMetadata.getHelperData()
					+ "')");
			String alarmData = convMessage.platformWebMessageMetadata.getAlarmData();
			if (alarmData != null)
			{
				view.loadUrl("javascript:alarmPlayed(" + "'" + alarmData + "')");
			}
			super.onPageFinished(view, url);
		}
	}

}
