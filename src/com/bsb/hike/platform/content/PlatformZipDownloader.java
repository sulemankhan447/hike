package com.bsb.hike.platform.content;

import java.io.File;

import java.util.Observable;
import java.util.Observer;

import com.bsb.hike.HikeMessengerApp;
import com.bsb.hike.modules.httpmgr.exception.HttpException;
import com.bsb.hike.modules.httpmgr.hikehttp.HttpRequests;
import com.bsb.hike.modules.httpmgr.request.listener.IRequestListener;
import com.bsb.hike.modules.httpmgr.response.Response;
import com.bsb.hike.platform.content.PlatformContent.EventCode;
import com.bsb.hike.utils.Utils;

/**
 * Download and store template. First
 *
 * @author Atul M
 */
public class PlatformZipDownloader
{
	private PlatformContentRequest mRequest;

	private boolean isPriorDownload;

	private File zipFile;

	/**
	 * Instantiates a new platform template download task.
	 *
	 * @param argRequest: request
	 * @param  ignorePlatformQueue: whether the app data is downloaded prior to showing cards or not.
	 */
	public PlatformZipDownloader(PlatformContentRequest argRequest, boolean ignorePlatformQueue)
	{
		// Get ID from content and call http
		mRequest = argRequest;
		isPriorDownload = ignorePlatformQueue;
		zipFile = new File(PlatformContentConstants.PLATFORM_CONTENT_DIR + PlatformContentConstants.TEMP_DIR_NAME, mRequest.getContentData().getId() + ".zip");
	}

	/**
	 * Calling this function will download and unzip the micro app. Download will be terminated if the folder already exists and then will try to
	 * get the folder from assets and then will download it from web.
	 */
	public void downloadAndUnzip()
	{
		if (zipFile.exists())
		{
			return;
		}
		if ((Utils.getExternalStorageState() == Utils.ExternalStorageState.NONE))
		{
			return;
		}

		// Create temp folder
		File tempFolder = new File(PlatformContentConstants.PLATFORM_CONTENT_DIR + PlatformContentConstants.TEMP_DIR_NAME);

		tempFolder.mkdirs();

		PlatformContentConstants.PLATFORM_CONTENT_DIR = HikeMessengerApp.getInstance().getApplicationContext().getFilesDir() + File.separator
				+ PlatformContentConstants.CONTENT_DIR_NAME + File.separator;
		//Check if the zip is present in hike app package
		AssetsZipMoveTask.AssetZipMovedCallbackCallback mCallback = new AssetsZipMoveTask.AssetZipMovedCallbackCallback()
		{

			@Override
			public void assetZipMoved(boolean hasMoved)
			{
				if (hasMoved)
				{
					unzipMicroApp();
				}
				else
				{
					getZipFromWeb();
				}
			}
		};

		Utils.executeBoolResultAsyncTask(new AssetsZipMoveTask(zipFile, mRequest, mCallback, isPriorDownload));

	}

	/**
	 * download the zip from web using 3 retries. On success, will unzip the folder.
	 */
	private void getZipFromWeb()
	{
		HttpRequests.platformZipDownloadRequest(mRequest.getContentData().getLayout_url(), new IRequestListener()
		{
			@Override
			public void onRequestFailure(HttpException httpException)
			{
				PlatformRequestManager.failure(mRequest, EventCode.LOW_CONNECTIVITY, isPriorDownload);
			}

			@Override
			public void onRequestSuccess(Response result) 
			{
				unzipMicroApp();
			}

			@Override
			public void onRequestProgressUpdate(float progress)
			{
				//do nothing
			}
		});
	}

	/**
	 * calling this function will unzip the microApp.
	 */
	private void unzipMicroApp()
	{
		try
		{
			unzipWebFile(zipFile.getAbsolutePath(), PlatformContentConstants.PLATFORM_CONTENT_DIR, new Observer()
			{
				@Override
				public void update(Observable observable, Object data)
				{
					// delete temp folder
					File tempFolder = new File(PlatformContentConstants.PLATFORM_CONTENT_DIR + PlatformContentConstants.TEMP_DIR_NAME);
					PlatformContentUtils.deleteDirectory(tempFolder);
					if (isPriorDownload)
					{
						mRequest.getListener().onComplete(mRequest.getContentData());
					}
					else
					{
						PlatformRequestManager.setReadyState(mRequest);
					}
				}
			});
		}
		catch (IllegalStateException ise)
		{
			ise.printStackTrace();
			PlatformRequestManager.failure(mRequest,EventCode.UNKNOWN, isPriorDownload);
		}
	}


	private void unzipWebFile(String zipFilePath, String unzipLocation, Observer observer)
	{
		HikeUnzipTask unzipper = new HikeUnzipTask(zipFilePath, unzipLocation);
		unzipper.addObserver(observer);
		unzipper.unzip();
	}

}
