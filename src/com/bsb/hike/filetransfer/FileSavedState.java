package com.bsb.hike.filetransfer;

import java.io.Serializable;

import org.json.JSONException;
import org.json.JSONObject;

import com.bsb.hike.filetransfer.FileTransferBase.FTState;

public class FileSavedState implements Serializable
{
	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;

	private FTState _currentState;

	private int _totalSize; // (in bytes)

	private int _transferredSize;

	private String _sessionId;

	private String _responseJson;
	
	private String _fileKey;

	public FileSavedState(FTState state, int totalSize, int transferredSize)
	{
		_currentState = state;
		_totalSize = totalSize;
		_transferredSize = transferredSize;
		_sessionId = null;
		_responseJson = null;
	}

	public FileSavedState(FTState state, int totalSize, int transferredSize, String sId, JSONObject response)
	{
		_currentState = state;
		_totalSize = totalSize;
		_transferredSize = transferredSize;
		_sessionId = sId;
		if (response != null)
		{
			_responseJson = response.toString();
		}
		else
		{
			_responseJson = null;
		}
	}

	public FileSavedState()
	{
		_currentState = FTState.NOT_STARTED;
	}
	
	public FileSavedState(FTState state, String mFileKey)
	{
		_currentState = state;
		_fileKey = mFileKey;
	}

	public int getTotalSize()
	{
		return _totalSize;
	}

	public int getTransferredSize()
	{
		return _transferredSize;
	}

	public FTState getFTState()
	{
		return _currentState;
	}

	public String getSessionId()
	{
		return _sessionId;
	}
	
	public String getFileKey()
	{
		return _fileKey;
	}

	public JSONObject getResponseJson()
	{
		try
		{
			if (_responseJson != null)
			{
				return (new JSONObject(_responseJson));
			}
			return null;
		}
		catch (JSONException e)
		{
			// TODO Auto-generated catch block
			e.printStackTrace();
			return null;
		}
	}

	@Override
	public String toString()
	{
		// TODO Auto-generated method stub
		String s = super.toString() + " " + _currentState + " " + _transferredSize + "/" + _totalSize;
		return s;
	}
	
}
