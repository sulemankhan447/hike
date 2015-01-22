package com.bsb.hike.modules.httpmgr.engine;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.PriorityQueue;

import android.util.Pair;

import com.bsb.hike.modules.httpmgr.request.Request;
import com.bsb.hike.modules.httpmgr.request.RequestCall;
import com.bsb.hike.utils.Logger;
import static com.bsb.hike.modules.httpmgr.engine.HttpEngineConstants.CORE_POOL_SIZE;

public class HttpQueue
{

	private static final long serialVersionUID = 1L;

	private static String TAG = "HTTP_QUEUE";

	private PriorityQueue<RequestCall> shortQueue;

	private PriorityQueue<RequestCall> longQueue;

	private Deque<RequestCall> longRunningQueue;

	private Deque<RequestCall> shortRunningQueue;

	private short MAX_QUEUE_SIZE = CORE_POOL_SIZE;

	HttpQueue()
	{
		// Priority Queue used for storing short requests submitted to the engine
		shortQueue = new PriorityQueue<RequestCall>();

		// Priority Queue used for storing long requests submitted to engine
		longQueue = new PriorityQueue<RequestCall>();

		// queue that contains short running requests
		longRunningQueue = new ArrayDeque<RequestCall>(MAX_QUEUE_SIZE);

		// queue that contains long running requests
		shortRunningQueue = new ArrayDeque<RequestCall>(MAX_QUEUE_SIZE);
	}

	/**
	 * add the request to short or long queues
	 * 
	 * @param request
	 */
	void add(RequestCall request)
	{
		if (request.getRequestType() == Request.REQUEST_TYPE_LONG)
		{
			longQueue.add(request);
		}
		else
		{
			shortQueue.add(request);
		}
	}

	/**
	 * add the call to long or short running queues
	 * 
	 * @param call
	 *            request call to add
	 * @param executer
	 *            executerType
	 */
	void addToRunningQueue(RequestCall call, short executer)
	{
		if (executer == HttpEngine.LONG_EXECUTER)
		{
			longRunningQueue.add(call);
		}
		else
		{
			shortRunningQueue.add(call);
		}
	}

	/**
	 * removes call from long or short running queues
	 * 
	 * @param call
	 *            request call to add
	 * @param executer
	 *            executerType
	 */
	void removeFromRunningQueue(RequestCall call, short executer)
	{
		if (executer == HttpEngine.LONG_EXECUTER)
		{
			longRunningQueue.add(call);
		}
		else
		{
			shortRunningQueue.add(call);
		}
	}

	/**
	 * <p>
	 * Gets the next call for httpEngine
	 * </p>
	 * 
	 * <p>
	 * It works on principle that short tasks can be executed on both short and long executers if there is space Long tasks can be executed only on long executer
	 * </p>
	 * 
	 * @param executerType
	 * @return pair of next call and type of executer on which request will be executed
	 */
	synchronized Pair<RequestCall, Short> getNextCall(int executerType)
	{
		RequestCall call = null;
		RequestCall nextCall = null;
		short executer = HttpEngine.LONG_EXECUTER;

		while (null == nextCall)
		{

			if (executerType == HttpEngine.SHORT_EXECUTER)
			{
				// short queue has some tasks, fetch and return it to engine to be executed on short executer
				if (shortQueue.size() > 0)
				{
					call = shortQueue.poll();
					if (!call.isCancelled())
					{
						nextCall = call;
						executer = HttpEngine.SHORT_EXECUTER;
					}

				}
				else
				{
					// no tasks to execute
					Logger.i(TAG, "no tasks to execute");
					break;
				}
			}
			else
			{
				// long queue has some tasks, fetch and return it to engine to be executed on long executer
				if (longQueue.size() > 0)
				{
					call = longQueue.poll();
					if (!call.isCancelled())
					{
						nextCall = call;
						executer = HttpEngine.LONG_EXECUTER;
					}
				}
				else
				{
					// long queue is empty. poll short queue
					// short queue has some tasks, fetch and return it to engine to be executed on long executer
					if (shortQueue.size() > 0)
					{
						call = shortQueue.poll();
						if (!call.isCancelled())
						{
							nextCall = call;
							executer = HttpEngine.LONG_EXECUTER;
						}
					}
					else
					{
						// no tasks to execute
						Logger.i(TAG, "no tasks to execute");
						break;
					}
				}
			}
		}
		return new Pair<RequestCall, Short>(nextCall, executer);
	}

	/**
	 * Checks if there is space available in long or short executer by checking size of long or short running queues
	 * 
	 * @param requestType
	 *            type of request (long or short)
	 * @return
	 */
	boolean spaceAvailable(int requestType)
	{
		if (requestType == Request.REQUEST_TYPE_LONG)
		{
			if (longRunningQueue.size() < MAX_QUEUE_SIZE)
			{
				return true;
			}
		}
		else
		{
			if (shortRunningQueue.size() < MAX_QUEUE_SIZE)
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * clear queue and remove references
	 */
	void shutdown()
	{
		longQueue.clear();
		shortQueue.clear();
		longRunningQueue.clear();
		shortRunningQueue.clear();

		longQueue = null;
		shortQueue = null;
		longRunningQueue = null;
		shortRunningQueue = null;
	}

}
