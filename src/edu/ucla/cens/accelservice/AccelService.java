package edu.ucla.cens.accelservice;

import edu.ucla.cens.systemlog.ISystemLog;
import edu.ucla.cens.systemlog.Log;
import edu.ucla.cens.systemsens.IPowerMonitor;
import edu.ucla.cens.systemsens.IAdaptiveApplication;

import android.app.Service;
import android.app.PendingIntent;
import android.app.AlarmManager;
import android.content.Context;
import android.content.Intent;
import android.content.ComponentName;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.SystemClock;
import android.os.RemoteException;
import android.os.PowerManager;
import android.hardware.SensorManager;
import android.hardware.SensorEventListener;
import android.hardware.SensorEvent;
import android.hardware.Sensor;

import java.util.List;
import java.util.ArrayList;
import java.util.Hashtable;


public class AccelService extends Service
{
	/** TAG string used for logging */
	private static final String TAG = "AccelService";

    private static final String APP_NAME = "AccelService";

    private static final String ACCEL_UNIT_NAME = "accel";
	
	/** Timer message types */
	private static final int SLEEP_TIMER_MSG = 1;
	private static final int READ_TIMER_MSG = 2;
    //private static final int WARMUP_TIMER_MSG = 3;

    /** Alarm intent action */
    private static final String ACCEL_ALARM_ACTION = "accel_alarm";
	
	/** Constant values used for easy time specification */
	private static final int ONE_SECOND = 1000;
	private static final int ONE_MINUTE = 60 * ONE_SECOND;

    /** Default operation values */
    private static final long DEFAULT_WARMUP_INTERVAL = ONE_SECOND;
    private static final long DEFAULT_READ_INTERVAL = ONE_SECOND;
    private static final long DEFAULT_SLEEP_INTERVAL = ONE_MINUTE;
    private static final int DEFAULT_RATE = 
        SensorManager.SENSOR_DELAY_GAME;
    private static final int DEFAULT_POWERCYCLE_HORIZON = 5 * ONE_MINUTE;


	
	/** Operation power consumption regimes */
	public static final int REGIME_RELAXED = 0;
	public static final int REGIME_CONTROLLED = 1;

	
	/** Sensor reading rate. Default rate is set to GAME */
	private int mRate = DEFAULT_RATE;
	
	/** Sleep interval value. By default set to one minutes */
	private long mSleepInterval = DEFAULT_SLEEP_INTERVAL;

    /** Sensor warmup interval */
    private long mWarmupInterval = DEFAULT_WARMUP_INTERVAL;

	/** Reading interval value. By default set to one second */
	private long mReadInterval = DEFAULT_READ_INTERVAL;


	
	/** Boolean variable used to re-initialize the recorded Lists */
	private boolean mJustStarted = true;
	
	/** Boolean variable used to duty-cycle the sensor */
	//private boolean mSensorRunning = false;

    /** Boolean variable set to read the sensor after warm-up */
    private boolean mRecordSensor = false;
	
	/** Latest recorded time-stamp */
	private long mLastTS;


    /** Set if the service is running */
    private boolean mIsRunning = false;

    /** Table of client names and correspondig information */
    private Hashtable<String, ClientInfo> mClientsMap;
	
	
	/** List of recorded force values */
	private List<Double> mLastForceList;
	private List<Double> mTempForceList;
	
	/** List of recorded sensor values */
	private List<Double> mLastListX;
	private List<Double> mTempListX;

	private List<Double> mLastListY;
	private List<Double> mTempListY;

	private List<Double> mLastListZ;
	private List<Double> mTempListZ;

    private AccelCounter mAccelCounter;
	

    /** The alarm manager object */
    private AlarmManager mAlarmManager;

    private PendingIntent mAccelSender;

    /** The partial wakelock object */
    private PowerManager.WakeLock mCpuLock;
	 
	/** The SensorManager object */
	private SensorManager mSensorManager;
	
	
    /** Power monitor stuff */
    private IPowerMonitor mPowerMonitor;
    private boolean mPowerMonitorConnected = false;


    private final IAdaptiveApplication mAdaptiveControl
        = new IAdaptiveApplication.Stub()
    {

        public String getName()
        {
            return APP_NAME;
        }

        public List<String> identifyList()
        {
            ArrayList<String> unitNames = new ArrayList<String>(1);
            unitNames.add(ACCEL_UNIT_NAME);

            return unitNames;
        }

        public List<Double> getWork()
        {
            ArrayList<Double> totalWork = new ArrayList<Double>(1);

            totalWork.add(mAccelCounter.getCount());

            Log.i(TAG, "Returning " + totalWork + " in getWork()");

            return totalWork;
        }

        public void setWorkLimit(List workLimit)
        {
            double accelLimit = (Double) workLimit.get(0);

            mAccelCounter.setLimit(accelLimit);
        }


    };

    private ServiceConnection mPowerMonitorConnection
        = new ServiceConnection()
    {
        public void onServiceConnected(ComponentName className,
                IBinder service)
        {
            mPowerMonitor = IPowerMonitor.Stub.asInterface(service);
            try
            {
                mPowerMonitor.register(mAdaptiveControl,
                        DEFAULT_POWERCYCLE_HORIZON);
            }
            catch (RemoteException re)
            {
                Log.e(TAG, "Could not register AdaptivePower object",
                        re);
            }
            mPowerMonitorConnected = true;
        }

        public void onServiceDisconnected(ComponentName className)
        {
            try
            {
                mPowerMonitor.unregister(mAdaptiveControl);
            }
            catch (RemoteException re)
            {
                Log.e(TAG, "Could not unregister AdaptivePower object",
                        re);
            }
            mPowerMonitor = null;
            mPowerMonitorConnected = false;
        }

    };



	
	
	/**
	 * SensorEventListener object is passed to the SensorManager instance.
	 * Every time the onSensorChanged() method is called values 
     * are recorded 
	 * in the lists.
	 */
	private final SensorEventListener mSensorListener = 
        new SensorEventListener() {
	
		/*
		 * Called when a new sensor reading is available.
		 * 
		 * If this is the first value after a sleep interval 
         * (mJustStarted is true)
		 * the list objects are re-initialized. 
		 * 
		 * @see android.hardware.SensorEventListener#onSensorChanged(
         * android.hardware.SensorEvent)
		 */
		public void onSensorChanged(SensorEvent se) 
		{
            mAccelCounter.count();

            if (mRecordSensor)
            {
                if (mJustStarted)
                {
                    // Just started receiving sensor updates. 
                    // Start a new list
                    mTempForceList = new ArrayList<Double>();
                    mTempListX = new ArrayList<Double>();
                    mTempListY = new ArrayList<Double>();
                    mTempListZ = new ArrayList<Double>();


                    mHandler.sendMessageAtTime(
                            mHandler.obtainMessage(SLEEP_TIMER_MSG),
                            SystemClock.uptimeMillis() + mReadInterval);


                    mJustStarted = false;
                }
                double x = se.values[0];
                double y = se.values[1];
                double z = se.values[2];
                double totalForce = 0.0;
                double grav = SensorManager.GRAVITY_EARTH;
                
                totalForce += Math.pow(x/grav, 2.0);
                totalForce += Math.pow(y/grav, 2.0);
                totalForce += Math.pow(z/grav, 2.0);
                totalForce = Math.sqrt(totalForce);
                
                mLastTS = System.currentTimeMillis();
                
                mTempForceList.add(totalForce);
                mTempListX.add(x);
                mTempListY.add(y);
                mTempListZ.add(z);

            }
			
		}
		
		/*
		 * Called when the sensor accuracy changes.
		 * I do not handle this event.
		 * @see android.hardware.SensorEventListener#onAccuracyChanged(
         * android.hardware.Sensor, int)
		 */
		public void onAccuracyChanged(Sensor sensor, int accuracy) 
		{
			String accuracyStr = "Unkown";
			
			switch (accuracy)
			{
			case SensorManager.SENSOR_STATUS_ACCURACY_HIGH:
				accuracyStr = "high";
				break;
			case SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM:
				accuracyStr = "medim";
				break;
			case SensorManager.SENSOR_STATUS_ACCURACY_LOW:
				accuracyStr = "medim";
				break;			
			case 0:
				accuracyStr = "Zero";
				break;
			}
			Log.i(TAG, "Accuracy changed to " + 
                accuracyStr + " (" + accuracy + ")");
		}
	};
	

	
	/*
	 * Binder object for the service. 
	 */
	private final IAccelService.Stub mBinder = new IAccelService.Stub()
	{

		/**
		 * Set the rate of accelerometer sampling. This is only a 
		 * suggestion and the service may choose a lower rate 
         * to save power.
		 * Possible values are:
		 * SENSOR_DELAY_FASTEST, SENSOR_DELAY_GAME, 
         * SENSOR_DELAY_NORMA, SENSOR_DELAY_UI
		 * 
		 * @param 	rate	rate of sensor reading
		 * @return 			the actual rate that was set
		 * 
		 */
		public int suggestRate(String callerName, int rate)
		{
            if (callerName == null)
                return -1;

            if (mClientsMap.containsKey(callerName))
            {
                mClientsMap.get(callerName).setRate(rate);
                Log.v(TAG, "Got rate suggestion of " + rate
                        + " from " + callerName);
            }
            else
            {
                Log.i(TAG, "Client " + callerName + 
                        " not recognized. Adding it.");
                mClientsMap.put(callerName, new ClientInfo());
                mClientsMap.get(callerName).setRate(rate);
            }

            return adjustRate();
		}
		
		/**
		 * Set the length of the interval that accelerometer is
         * recorded before it is turned of (for duty-cycling).
		 *
		 * @param 	length		length of the interval for sensor 
         *                      reading in milli-seconds
		 */
		public long setReadingLength(String callerName, long length)
		{

            if (callerName == null)
                return -1;

            if (mClientsMap.containsKey(callerName))
            {
                mClientsMap.get(callerName).setReadInterval(length);
                Log.v(TAG, "Read length set to " 
                        + length + " by " + callerName);
            }
            else
            {
                Log.i(TAG, "Client " + callerName + 
                        " not recognized. Adding it.");
                mClientsMap.put(callerName, new ClientInfo());
                mClientsMap.get(callerName).setReadInterval(length);
            }

            return adjustReadInterval();
		}


        /**
         * Set the length of the warm-up interval before the actual
         * reading interval begins.
         *
         * @param   length      length of the warm-up interval for
         *                      preparing the accelerometer
         */
        public long setWarmupLength(String callerName, long length)
        {

            if (callerName == null)
                return -1;

            if (mClientsMap.containsKey(callerName))
            {
                mClientsMap.get(callerName).setWarmupInterval(length);
                Log.v(TAG, "Warmup interval set to " 
                        + length + " by " + callerName);

            }
            else
            {
                Log.i(TAG, "Client " + callerName + 
                        " not recognized. Adding it.");
                mClientsMap.put(callerName, new ClientInfo());
                mClientsMap.get(callerName).setWarmupInterval(length);
            }



            return adjustWarmupInterval();
        }
		
		/**
		 * Suggest length of the duty-cycling interval. The
         * accelerometer sensor will be turned off for some time
         * between readings.  This is only a suggestion and the
         * service may choose a longer interval to save power
         *
		 * 
		 * @param	interval	suggested length of off interval 
                                in milli-seconds
		 */
		public long suggestInterval(String callerName, long interval)
		{
            if (callerName == null)
                return -1;

            if (mClientsMap.containsKey(callerName))
            {
                mClientsMap.get(callerName).setSleepInterval(interval);
                Log.v(TAG, "Got interval suggestion of " 
                        + interval + " from " + callerName);

            }
            else
            {

                Log.i(TAG, "Client " + callerName + 
                        " not recognized. Adding it.");
                mClientsMap.put(callerName, new ClientInfo());
                mClientsMap.get(callerName).setSleepInterval(interval);
            }


            return adjustSleepInterval();
		}
		
		/**
		 * Returns the current sleeping interval.
		 * 
		 * @return				current sleep interval used by the service
		 */
		public long getInterval()
		{
		    return mSleepInterval;
		}

		/**
		 * Returns the current rate.
		 * 
		 * @return				current rate
		 */
		public int getRate()
		{
		    return mRate;
		}


		/**
		 * Returns the current reading length
		 * 
		 * @return				current reading length 
		 */
		public long getReadingLength()
		{
		    return mReadInterval;
		}

		/**
		 * Returns the length of current warmup interval
		 * 
		 * @return				current warmup interval length 
		 */
		public long getWarmupLength()
		{
            return mWarmupInterval;
		}


		
		/**
		 * Returns the latest recorded force vector.
		 * 
		 * @return				latest recorded force vector
		 */
		public List<Double> getLastForce()
		{
            synchronized(mLastListX)
            {
		        return mLastForceList; 
            }
		}
		 
		 /**
		  * Returns the list of latest recorded X values.
		  * Each element of the list contains an array of values.
		  *
		  * @return				latest recorded values
		  */
		 public List<Double> getLastXValues()
		 {

            synchronized(mLastListX)
            {
                return mLastListX;
            }
		 }


		 /**
		  * Returns the list of latest recorded Y values.
		  * Each element of the list contains an array of values.
		  *
		  * @return				latest recorded values
		  */
		 public List<Double> getLastYValues()
		 {
            synchronized(mLastListX)
            {
		        return mLastListY;
            }
		 }

		 /**
		  * Returns the list of latest recorded Z values.
		  * Each element of the list contains an array of values.
		  *
		  * @return				latest recorded values
		  */
		 public List<Double> getLastZValues()
		 { 
             synchronized(mLastListX)
             {
		        return mLastListZ;
             }
		 }


		  
		  /**
		   * Returns the time-stamp of the last recorded value.
		   * This method can be used to verify the freshness of the values.
		   *
		   * @return 			time-stamp of the latest recorded
           *                    sensor value
		   */
		  public long getLastTimeStamp()
		  {
             synchronized(mLastListX)
             {
		        return mLastTS;
             }
		  }

          /**
           * Returns true if the service is running.
           *
           * @return             running state of the service
           */
          public boolean isRunning()
          {
              return mIsRunning;
          }
		 
          /**
           * Starts the accelerometer service.
           */
          public void start(String callerName)
          {
              
              if (callerName == null)
                  return;

              Log.i(TAG, "Received start() from " 
                      + callerName);

              if (mClientsMap != null)
                  Log.i(TAG, "Current clients are: " +
                          mClientsMap.keySet());
              else
                  Log.i(TAG, "ClientsMap is null");




              if (!mClientsMap.containsKey(callerName))
                  mClientsMap.put(callerName, new ClientInfo());



              if (mClientsMap != null)
                  Log.i(TAG, "New clients are: " +
                          mClientsMap.keySet());
              else
                  Log.i(TAG, "ClientsMap is null");



              int clientCount = mClientsMap.size();


              if ((clientCount == 1) && (!mIsRunning))
              //if (!mIsRunning)
              {
                  Log.i(TAG, "Starting the service");
                  mAlarmManager.cancel(mAccelSender);

                  mAlarmManager.setRepeating(
                          AlarmManager.ELAPSED_REALTIME_WAKEUP,
                          SystemClock.elapsedRealtime(),
                          mSleepInterval, 
                          mAccelSender);
                  mIsRunning = true;
                  //mSensorRunning = false;
              }
              else
              {
                  Log.i(TAG, "Already running");
              }
          }
          
          /**
          * Stops the accelerometer service to save maximum power.
          */
          public void stop(String callerName)
          {

              if (callerName == null)
                  return;

              Log.i(TAG, "Received stop() from " 
                      + callerName);


              if (mClientsMap.containsKey(callerName))
                  mClientsMap.remove(callerName);
              else
                  return;

              int clientCount = mClientsMap.size();

              Log.i(TAG, "Client count is " + clientCount);

              if ((clientCount == 0) && (mIsRunning))
              {
                  Log.i(TAG, "Stopping the service");
                  mIsRunning = false;
                  mAlarmManager.cancel(mAccelSender);
                  mHandler.removeMessages(SLEEP_TIMER_MSG);
                  mHandler.removeMessages(READ_TIMER_MSG);

                  mSensorManager.unregisterListener(mSensorListener, 
                     mSensorManager.getDefaultSensor(
                         Sensor.TYPE_ACCELEROMETER));
                 
                  
                 //mSensorRunning = false;
                 mIsRunning = false;
              }
              else
              {
                  Log.i(TAG, "Still need to continue running.");
                  adjustRate();
                  adjustSleepInterval();
                  adjustWarmupInterval();
                  adjustReadInterval();
              }

          }
 
	};
	
	/*
	 * Returns the binder object.
	 * 
	 * @see android.app.Service#onBind(android.content.Intent)
	 */
	@Override
	public IBinder onBind(Intent intent)
	{
        if (IAccelService.class.getName().equals(intent.getAction())) 
        {
            return mBinder;
        }
        
		return null;
	}
	
	/*
	 * Handler object to manage self messages.
	 * There are only two types of messages: SLEEP_TIMER_MSG 
     * and READ_TIMER_MSG.
	 */
    private final Handler mHandler = new Handler()
    {
        @Override
        public void handleMessage(Message msg)
        {
            // Discard the message if the service is not 
            // supposed to be running.
            if (!mIsRunning)
            {
                Log.w(TAG, "Discarding internal message.");
                //mAlarmManager.cancel(mAccelSender);
                return;
            }

            if (msg.what == SLEEP_TIMER_MSG)
            {
            	//if (mSensorRunning)
            	//{
                Log.v(TAG, "Turning off the sensor");
            
                mSensorManager.unregisterListener(mSensorListener, 
                        mSensorManager.getDefaultSensor(
                            Sensor.TYPE_ACCELEROMETER));
                
                //mSensorRunning = false;
                mRecordSensor = false;
                
                // Time to copy temp lists to last lists
                synchronized (mLastListX)
                {
                    mLastForceList = mTempForceList;
                    mLastListX = mTempListX;
                    mLastListY = mTempListY;
                    mLastListZ = mTempListZ;
                }


            	//}
                //else
                //{
                //    Log.v(TAG, "Time to turn off sensor,"
                //            + " but running flag is not set.");
                //}

                if (mCpuLock.isHeld())
                    mCpuLock.release();

            }
            else if (msg.what == READ_TIMER_MSG)
            {             
                // Debug
                Log.v(TAG, "Recording the sensor for "
                        + mReadInterval + " milliseconds");
            		
                mJustStarted = true; 
                mRecordSensor = true;

            }

        }

    };


    /**
      * Triggers the sensor reading cycle.
      * Starts the sensor and also sends a message for the
      * warmup interval.
      */
    private void sensorCycle()
    {

        //if (!mSensorRunning)
        //{
        Log.v(TAG, "Starting to warm up the sensor for "
                + mWarmupInterval
                + " milliseconds");

        if (mAccelCounter.hasBudget())
        {
            mSensorManager.registerListener(mSensorListener, 
                    mSensorManager.getDefaultSensor(
                        Sensor.TYPE_ACCELEROMETER), 
                    mRate);

            mHandler.sendMessageAtTime(
                    mHandler.obtainMessage(READ_TIMER_MSG),
                    SystemClock.uptimeMillis() + mWarmupInterval);

            //mSensorRunning = true;
            mRecordSensor = false;

        }
        else
        {
            Log.i(TAG, "Ran out of budget. Did not turn " +
                    "on the sensor.");
        }

        //}
        //else
        //{
        //    Log.i(TAG, "Sensor is already recording.");
        //}


    }


    @Override
    public void onStart(Intent intent, int startId)
    {
        //Log.i(TAG, "Received onStart() call");
        if (!mPowerMonitorConnected)
        {
            Log.i(TAG, "Rebinding to PowerMonitor");
            bindService(new Intent(IPowerMonitor.class.getName()),
                    mPowerMonitorConnection, Context.BIND_AUTO_CREATE);
     
        }

        if (!Log.isConnected())
        {
            bindService(new Intent(ISystemLog.class.getName()),
                    Log.SystemLogConnection, Context.BIND_AUTO_CREATE);
        }

        if (intent != null)
        {
            String action = intent.getAction();
            //Log.i(TAG, "Intent is not null.");

            if (action != null)
            {
                //Log.i(TAG, "Action is not null.");
                if (action.equals(ACCEL_ALARM_ACTION))
                {
                    //Log.i(TAG, "ACCEL_ALARM_ACTION it is.");
                    if (!mCpuLock.isHeld())
                        mCpuLock.acquire(); // Released after sensor
                                            // reading is over

                    sensorCycle();
                }
            }
        }
        super.onStart(intent, startId);
    }
	
    /*
     * Create and initialize the service object.
     * 
     * We first bind to SystemLog to send all logging messages 
     * through that.
     * After initializing the SensorManager object as self-message is sent
     * to get things started.
     * @see android.app.Service#onCreate()
     */
    @Override
    public void onCreate() {
        super.onCreate();

        Log.setAppName(APP_NAME);
        bindService(new Intent(ISystemLog.class.getName()),
                Log.SystemLogConnection, Context.BIND_AUTO_CREATE);
     
        bindService(new Intent(IPowerMonitor.class.getName()),
                mPowerMonitorConnection, Context.BIND_AUTO_CREATE);
 

        Log.i(TAG, "onCreate");

        //mSensorRunning = false;

        mClientsMap = new Hashtable<String, ClientInfo>();
     
        resetToDefault();
        mSensorManager = (SensorManager) getSystemService(
                Context.SENSOR_SERVICE);
        
		
        mAccelCounter = new AccelCounter();

        PowerManager pm = (PowerManager) getSystemService(
                Context.POWER_SERVICE);
        mCpuLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                APP_NAME);
        mCpuLock.setReferenceCounted(false);


        mAlarmManager = (AlarmManager) getSystemService(ALARM_SERVICE);

        // Repeating alarm for Accel dutycycling
        Intent accelAlarmIntent = new Intent(AccelService.this,
                AccelService.class);
        accelAlarmIntent.setAction(ACCEL_ALARM_ACTION);
        mAccelSender = PendingIntent.getService(AccelService.this, 0,
                accelAlarmIntent, 0);

        

    }
    
    /*
     * Called to clean up.
     * If the sensor is running we stop it.
     * @see android.app.Service#onDestroy()
     */
    @Override
    public void onDestroy()
    {
    	Log.i(TAG, "onDestroy");

        Log.i(TAG, "Stopping the service");
        mAlarmManager.cancel(mAccelSender);
        mHandler.removeMessages(SLEEP_TIMER_MSG);
        mHandler.removeMessages(READ_TIMER_MSG);
        
        mSensorManager.unregisterListener(mSensorListener, 
           mSensorManager.getDefaultSensor(
               Sensor.TYPE_ACCELEROMETER));
        
        
        //mSensorRunning = false;


    	super.onDestroy();

        unbindService(Log.SystemLogConnection);
    	
    	//if (mSensorRunning)
        mSensorManager.unregisterListener(mSensorListener);
    	
    }
    
    /*
     * Sets all the running parameters to default values
     */
    private void resetToDefault()
    {
        Log.i(TAG, "Resetting variables to default");
    	/** Sensor reading rate. Default rate is set to GAME */
    	mRate = DEFAULT_RATE;
    	
    	/** Sleep interval value. By default set to one minutes */
    	mSleepInterval = DEFAULT_SLEEP_INTERVAL;
    	
    	/** Reading interval value. By default set to one second */
    	mReadInterval = DEFAULT_READ_INTERVAL;

        mWarmupInterval = DEFAULT_WARMUP_INTERVAL;
            

        mLastForceList = new ArrayList<Double>();
        mLastListX = new ArrayList<Double>();
        mLastListY = new ArrayList<Double>();
        mLastListZ = new ArrayList<Double>();


        /*
        synchronized(mLastListX)
        {
            mLastForceList = null;
            mLastListX = null;
            mLastListY = null;
            mLastListZ = null;
        }
        */


    	
    }


    /*
     * Returns the greates common devisor.
     */
    private long gcd(long a, long b) 
    {
        if (b==0) 
            return a;
        else
            return gcd(b, a % b);
    }

  
    /*
     * Used internally to modify sleep interval.
     * For now does nothing but applying the change. But in future will 
     * implement the power adaptation logic.
     * 
     * @param 		interval 		suggested interval in miliseconds
     * @return						final applied value
     * 
     */
    private long adjustSleepInterval()
    {
        long interval, curInterval = mSleepInterval;

        for (ClientInfo client : mClientsMap.values())
        {
            interval = client.sleepInterval;
            curInterval = gcd(curInterval, interval);
        }

        if (mSleepInterval != curInterval)
        {
            mSleepInterval = curInterval;
            Log.i(TAG, "Sleeping interval changed to " + mSleepInterval);
        }

        if (mIsRunning)
        {
            mAlarmManager.cancel(mAccelSender);
            mAlarmManager.setRepeating(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime(),
                mSleepInterval, 
                mAccelSender);
        }

    	
    	return mSleepInterval;
    }


    private long adjustWarmupInterval()
    {

        long interval, curInterval = -1;

        for (ClientInfo client : mClientsMap.values())
        {
            interval = client.warmupInterval;
            if (curInterval < interval)
                curInterval = interval;
        }

        if (mWarmupInterval != curInterval)
        {
            mWarmupInterval = curInterval;
            Log.v(TAG, "Warmup length set to " + mWarmupInterval);
        }

        return mWarmupInterval;
    }

    private long adjustReadInterval()
    {
        long interval, curInterval = -1;

        for (ClientInfo client : mClientsMap.values())
        {
            interval = client.readInterval;
            if (curInterval < interval)
                curInterval = interval;
        }

        if (mReadInterval != curInterval)
        {
            mReadInterval = curInterval;
            Log.i(TAG, "Read interval changed to " + mReadInterval);
        }
        return mReadInterval;
    }
    
    /*
     * Used internally to modify the sampling rate.
     * For now does nothing but applying the change. But in future will 
     * implement the power adaptation logic.
     * 
     * @param 		interval 		suggested interval in miliseconds
     * @return						final applied value
     * 
     */
    private int adjustRate()
    {
        int rate, curRate = Integer.MAX_VALUE;

        for (ClientInfo client : mClientsMap.values())
        {
            rate = client.rate;
            if (curRate > rate)
                curRate = rate;
        }

        if (mRate != curRate)
        {
            mRate = curRate;
            Log.i(TAG, "Rate set to " + mRate);
        }
		return mRate;
    }


    class ClientInfo
    {
        public int rate;
        public long sleepInterval;
        public long warmupInterval;
        public long readInterval;


        ClientInfo()
        {
            this.rate = DEFAULT_RATE;
            this.sleepInterval = DEFAULT_SLEEP_INTERVAL;
            this.readInterval = DEFAULT_READ_INTERVAL;
            this.warmupInterval = DEFAULT_WARMUP_INTERVAL;
        }


        public void setReadInterval(long length)
        {
            if (length > 0)
                readInterval = length;
            else
                warmupInterval = DEFAULT_WARMUP_INTERVAL;
        }


        public void setWarmupInterval(long length)
        {
            if (length >= 0)
                warmupInterval = length;
            else
                warmupInterval = DEFAULT_WARMUP_INTERVAL;
        }


        public void setRate(int newrate)
        {
            switch (newrate)
            {
            case SensorManager.SENSOR_DELAY_FASTEST:
                mRate = SensorManager.SENSOR_DELAY_FASTEST;
                break;
            case SensorManager.SENSOR_DELAY_GAME:
                mRate = SensorManager.SENSOR_DELAY_GAME;
                break;
            case SensorManager.SENSOR_DELAY_NORMAL:
                mRate = SensorManager.SENSOR_DELAY_NORMAL;
                break;
            case SensorManager.SENSOR_DELAY_UI:
                mRate = SensorManager.SENSOR_DELAY_UI;
                break;
            default:
                mRate = DEFAULT_RATE;
                break;
            }
        }

        public void setSleepInterval(long interval)
        {
            if (interval < ONE_SECOND)
            {
                this.sleepInterval =  DEFAULT_SLEEP_INTERVAL;
            }
            else
            {
                mSleepInterval = interval;
            }
        }

    }




    class AccelCounter
    {
        private double mTotal;
        private double mCurTotal;
        private double mLimit;

        public AccelCounter()
        {
            mTotal = mCurTotal = 0.0;
            mLimit = Double.NaN;
        }

        public boolean hasBudget()
        {
            if ( Double.isNaN(mLimit) || (mCurTotal < mLimit))
            {
                return true;
            }
            else if (!Double.isNaN(mLimit) && (mCurTotal >= mLimit))
            {
                return false;
            }
            return true;
        }

        public boolean count()
        {
            if (hasBudget())
            {
                mTotal += 1;
                mCurTotal += 1;
                return true;
            }
            else
                return false;
        }

        public void setLimit(double workLimit)
        {
            mLimit = workLimit;
            mCurTotal = 0.0;
        }

        public double getCount()
        {
            return mTotal;
        }

    }

    
}
