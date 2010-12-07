package edu.ucla.cens.accelservice;

interface IAccelServiceControl
{

	/**
	 * Sets the current operational regime.
	 * REGIME_RELAXED (0x00000000) is the default regime where the service can
	 * take suggestions from its clients.
	 * Other integer values indicate next levels of power consumption limitations
	 *
	 * @param		regime		new power consumption regime
	 */ 
	void setOperationRegime(int regime);
	
	
	/**
	 * Increases the sleep interval between accelerometer sampling events.
	 *
	 * @return 					the new interval after the increase in milliseconds
	 *
	 */
	long increaseInterval();
	
	/**
	 * Decreases the sleep interval between accelerometer sampling events.
	 * 
	 * @return 					the new interval after the decrease in milliseconds
	 */	
	long decreaseInterval();

	/**
	 * Sets the sleep interval
	 * 
	 * @param		interval	the value to be set	in milliseconds
	 * @return 					the new interval after the change in milliseconds
	 */	
	long setInterval(long interval);

	/**
	 * Returns the current sleep interval.
	 * 
	 * @return 					the new interval after the change in milliseconds
	 */	
	long getInterval();
	
	/**
	 * Increases the accelerometer sampling rate.
	 * Possible values are:
	 * SENSOR_DELAY_FASTEST, SENSOR_DELAY_GAME, SENSOR_DELAY_NORMA, SENSOR_DELAY_UI
	 *
	 * @return 					the actual rate after the increase
	 *
	 */	
	int increaseRate();
	
	/**
	 * Decrease the accelerometer sampling rate.
	 * Possible values are:
	 * SENSOR_DELAY_FASTEST, SENSOR_DELAY_GAME, SENSOR_DELAY_NORMA, SENSOR_DELAY_UI
	 *
	 * @return 					the actual rate after the decrease
	 *
	 */	
	int decreaseRate();
	
	/**
	 * Set the rate of accelerometer sampling. 
	 * Possible values are:
	 * SENSOR_DELAY_FASTEST, SENSOR_DELAY_GAME, SENSOR_DELAY_NORMA, SENSOR_DELAY_UI
	 * 
	 * @param 		rate		rate of sensor reading
	 * @return 					the actual rate that was set 
	 * 
	 */
	int setRate(int rate);
	
	/**
	 * Returns the current acceleromter sampling rate.
	 * 
	 * @param 		rate		rate of sensor reading
	 */
	int getRate(); 
	
	
	/**
	 * Sets the power consumption.
	 * 
	 * @param		power		the power consumption level
	 */
	void setPower(int power);
	
	/**
	 * Returns the current power consumption level.
	 * 
	 * @return					the new power consumption level
	 */	
	int getPower();
}
