package com.example.lehuy.project2;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import com.google.android.things.pio.PeripheralManager;
import com.google.android.things.pio.UartDevice;
import com.google.android.things.pio.UartDeviceCallback;
import com.google.android.things.pio.Gpio;
import com.google.android.things.pio.GpioCallback;
import com.google.android.things.pio.Pwm;


import java.io.IOException;


/**
 * Skeleton of an Android Things activity.
 * <p>
 * Android Things peripheral APIs are accessible through the class
 * PeripheralManagerService. For example, the snippet below will open a GPIO pin and
 * set it to HIGH:
 * <p>
 * <pre>{@code
 * PeripheralManagerService service = new PeripheralManagerService();
 * mLedGpio = service.openGpio("BCM6");
 * mLedGpio.setDirection(Gpio.DIRECTION_OUT_INITIALLY_LOW);
 * mLedGpio.setValue(true);
 * }</pre>
 * <p>
 * For more complex peripherals, look for an existing user-space driver, or implement one if none
 * is available.
 *
 * @see <a href="https://github.com/androidthings/contrib-drivers#readme">https://github.com/androidthings/contrib-drivers#readme</a>
 */
public class MainActivity extends Activity {
    private static final String TAG = MainActivity.class.getSimpleName();

    // UART Configuration Parameters
    private static final int BAUD_RATE = 115200;
    private static final int DATA_BITS = 8;
    private static final int STOP_BITS = 1;

    private static final int CHUNK_SIZE = 512;

    private HandlerThread mInputThread;
    private Handler mHandler;

    private UartDevice mLoopbackDevice;

    //FSM variable
    private static int state = 0;

    private Runnable mRunnable = new Runnable() {
        @Override
        public void run() {

           transferUartData();

        }
    };

    //GPIO pin name
    private static final String ledRGB = "PWM1";//BoardDefaults.getPWMPort();
    private static final String ledR = "BCM6";
    private static final String ledG = "BCM19";
    private static final String ledB = "BCM26";
    private static final String button = "BCM5";

    //Variables for controlling
    private static int hz = 120;
    private static int intervalBetweenBlinks = 2000;
    private static int buttonStatus = 0;
    private static boolean buttonLock = true;
    private static int ledColor = 1;
    private static double brightness = 100;

    private long nextButtonClickAvailable = 0;
    private long nextPostTime = 0;
    private int RGBTurnCounting = 0;
    private int triggerCount = 0;


    private Pwm mLedRGB;
    private Gpio mLedR;
    private Gpio mLedG;
    private Gpio mLedB;
    private Gpio mButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "Activity Created");

        // Create a background looper thread for I/O
        mInputThread = new HandlerThread("InputThread");
        mInputThread.start();
        mHandler = new Handler(mInputThread.getLooper());


        try {
            //Attempt to access the UART device
            Log.i(TAG, "Configuring UART port");
            openUart(BoardDefaults.getUartName(), BAUD_RATE);
            // Read any initially buffered data
            mHandler.post(mRunnable);

            //Attempt to access PWM pins
            Log.i(TAG, "Configuring GPIO");
            mLedRGB = PeripheralManager.getInstance().openPwm(ledRGB);
            initializePwm(mLedRGB);

            //Attempt to access GPIO pins
            mLedR = PeripheralManager.getInstance().openGpio(ledR);
            mLedR.setDirection(Gpio.DIRECTION_OUT_INITIALLY_LOW);
            mLedR.setActiveType(Gpio.ACTIVE_HIGH);

            mLedG = PeripheralManager.getInstance().openGpio(ledG);
            mLedG.setDirection(Gpio.DIRECTION_OUT_INITIALLY_LOW);
            mLedG.setActiveType(Gpio.ACTIVE_HIGH);

            mLedB = PeripheralManager.getInstance().openGpio(ledB);
            mLedB.setDirection(Gpio.DIRECTION_OUT_INITIALLY_LOW);
            mLedB.setActiveType(Gpio.ACTIVE_HIGH);

            mButton = PeripheralManager.getInstance().openGpio(button);
            configureButton(mButton);


            mLedR.setValue(true);
            mLedG.setValue(true);
            mLedB.setValue(true);

        } catch (IOException e) {
            Log.e(TAG, "Unable to initialize", e);
        }
    }

    @Override
    protected void onDestroy(){
        super.onDestroy();
        Log.d(TAG, "Activity Destroyed");

        // Terminate the worker thread
        if (mInputThread != null) {
            mInputThread.quitSafely();
        }

        try {
            //Attempt to unregister Gpio interrupt
            mButton.unregisterGpioCallback(mGpioCallback);

            //Attempt to close UART port
            Log.i(TAG,"Closing UART port");
            closeUart();

            //Close PWM port
            Log.i(TAG,"Closing PWM");
            if (mLedRGB != null){
                mLedRGB.setPwmDutyCycle(0);
                mLedRGB.close();
                mLedRGB = null;
            }

            //Close GPIO port
            Log.i(TAG,"Closing GPIO");
            if (mLedR != null){
                mLedR.close();
                mLedR = null;
            }
            if (mLedG != null){
                mLedG.close();
                mLedG = null;
            }
            if (mLedB != null){
                mLedB.close();
                mLedB = null;
            }
        } catch (IOException e){
                Log.e(TAG, "Unable to close", e);
            }

    }

    /**
     * Callback invoked when UART receives new incoming data.
     */
    private UartDeviceCallback mCallback = new UartDeviceCallback() {
        @Override
        public boolean onUartDeviceDataAvailable(UartDevice uart) {
            // Queue up a data transfer
            transferUartData();
            //Continue listening for more interrupts
            return true;
        }

        @Override
        public void onUartDeviceError(UartDevice uart, int error) {
            Log.w(TAG, uart + ": Error event " + error);
        }
    };

    /* Private Helper Methods */

    /**
     * Access and configure the requested UART device for 8N1.
     *
     * @param name Name of the UART peripheral device to open.
     * @param baudRate Data transfer rate. Should be a standard UART baud,
     *                 such as 9600, 19200, 38400, 57600, 115200, etc.
     *
     * @throws IOException if an error occurs opening the UART port.
     */
    private void openUart(String name, int baudRate) throws IOException {
        mLoopbackDevice = PeripheralManager.getInstance().openUartDevice(name);
        // Configure the UART
        mLoopbackDevice.setBaudrate(baudRate);
        mLoopbackDevice.setDataSize(DATA_BITS);
        mLoopbackDevice.setParity(UartDevice.PARITY_NONE);
        mLoopbackDevice.setStopBits(STOP_BITS);

        mLoopbackDevice.registerUartDeviceCallback(mHandler, mCallback);
    }

    /**
     * Close the UART device connection, if it exists
     */
    private void closeUart() throws IOException {
        if (mLoopbackDevice != null) {
            mLoopbackDevice.unregisterUartDeviceCallback(mCallback);
            try {
                mLoopbackDevice.close();
            } finally {
                mLoopbackDevice = null;
            }
        }
    }

    /**
     * Loop over the contents of the UART RX buffer, transferring each
     * one back to the TX buffer to create a loopback service.
     *
     * Potentially long-running operation. Call from a worker thread.
     */
    private void transferUartData() {
        if (mLoopbackDevice != null) {
            // Loop until there is no more data in the RX buffer.
            try {
                byte[] buffer = new byte[CHUNK_SIZE];
                int read;
                int write;
                while ((read = mLoopbackDevice.read(buffer, buffer.length)) > 0) {
                    write = mLoopbackDevice.write(buffer, read);

                        if (state == 0 && buffer[0] == '0') {
                            state = 1;
                            Log.i(TAG, "Ready to receive UART command");
                        } else if (state >= 1) {
                            switch (buffer[0]) {
                                case '1':
                                    if (state != 2){
                                        nextPostTime = System.currentTimeMillis();
                                    }
                                    state = 2;
                                    Log.i(TAG, "state = 2");
                                    break;
                                case '2':
                                    if (state != 3){
                                        nextPostTime = System.currentTimeMillis();
                                    }
                                    state = 3;
                                    toggleButtonLock();
                                    Log.i(TAG, "state = 3");
                                    break;
                                case '3':
                                    if (state != 4){
                                        nextPostTime = System.currentTimeMillis();
                                    }
                                    state = 4;
                                    Log.i(TAG, "state = 4");
                                    break;
                                case '4':
                                    break;
                                case '5':
                                    if (state != 6){
                                        nextPostTime = System.currentTimeMillis();
                                    }
                                    state = 6;
                                    Log.i(TAG, "state = 6");
                                    break;
                                case 'F':
                                    state = 10;
                                    Log.i(TAG, "state = 10");
                                    break;
                            }
                        }

                }
            } catch (IOException e) {
                Log.w(TAG, "Unable to transfer data over UART", e);
            }
        }
        try {
            stateOperating();
        } catch (IOException e){
            Log.i(TAG, "Just an error of stateOperating()");
        }
    }

    public void initializePwm(Pwm pwm) throws IOException {
        pwm.setPwmFrequencyHz(hz);
        pwm.setPwmDutyCycle(100);

        // Enable the PWM signal
        pwm.setEnabled(true);
    }

    public void configureButton(Gpio gpio) throws IOException{
        // Initialize the pin as an input
        gpio.setDirection(Gpio.DIRECTION_IN);
        gpio.setActiveType(Gpio.ACTIVE_HIGH);


        // Register for all state changes
        gpio.setEdgeTriggerType(Gpio.EDGE_RISING);
        gpio.registerGpioCallback(mGpioCallback);
    }

    private GpioCallback mGpioCallback = new GpioCallback() {
        @Override
        public boolean onGpioEdge(Gpio gpio) {
            if (!buttonLock) {
                if (nextButtonClickAvailable < System.currentTimeMillis()) {
                    if (buttonStatus < 4) {
                        buttonStatus = buttonStatus + 1;
                        nextButtonClickAvailable = System.currentTimeMillis() + 1500;
                        triggerCount = triggerCount + 1;
                        Log.i(TAG, "Trigger count: " + triggerCount);
                        Log.i(TAG, "Button status: " + buttonStatus);
                    }
                    if (buttonStatus == 4) {
                        buttonStatus = 0;
                    }
               }
            }

            // Continue listening for more interrupts
            return true;
        }

        @Override
        public void onGpioError(Gpio gpio, int error) {
            Log.w(TAG, gpio + ": Error event " + error);
        }
    };

    private void stateOperating() throws IOException{
        if (!buttonLock){
            if (state != 3){
                buttonLock = true;
                buttonStatus = 0;
                Log.i(TAG,"Button Lock: "+ buttonLock);
            }
        }
        if (System.currentTimeMillis() >= nextPostTime) {
            //Calling function base on state
            switch (state - 1) {
                case 1:
                    Log.i(TAG, "Case 1");
                    displayRGBColor();
                    changeColor();
                    mHandler.postDelayed(mRunnable, 1500);
                    nextPostTime = System.currentTimeMillis() + 1500;
                    break;
                case 2:
                    Log.i(TAG, "Case 2");
                    displayRGBColor();
                    changeColor();
                    updateIntervalBetweenBlinks();
                    mHandler.postDelayed(mRunnable, intervalBetweenBlinks);
                    nextPostTime = System.currentTimeMillis() + intervalBetweenBlinks;
                    break;
                case 3://change RGB LED's brightness every 2 second
                    Log.i(TAG, "Case 3");
                    changeBrightness();
                    mHandler.postDelayed(mRunnable, 2000);
                    nextPostTime = System.currentTimeMillis() + 2000;
                    break;
                case 4:
                    break;
                case 5:
                    blinkEachLed();
                    break;
                case 9:
                    state = 0;
                    System.exit(0);
                    break;
            }

        }
    }

    /*Displaying color of RGB LED
    RGB
    000 = 0
    001 = 1
    010 = 2
    011 = 3
    100 = 4
    101 = 5
    110 = 6
    111 = 7*/
    private void displayRGBColor() throws IOException {
        mLedRGB.setPwmDutyCycle(20);//Maximum brightness
        Log.i(TAG, "changing RGB color " + ledColor);
        if (ledColor == 8){
            ledColor = 0;
        }
        switch(ledColor){
            case 1:
                mLedR.setValue(true);
                mLedG.setValue(true);
                mLedB.setValue(false);
                break;
            case 2:
                mLedR.setValue(true);
                mLedG.setValue(false);
                mLedB.setValue(true);
                break;
            case 3:
                mLedR.setValue(true);
                mLedG.setValue(false);
                mLedB.setValue(false);
                break;
            case 4:
                mLedR.setValue(false);
                mLedG.setValue(true);
                mLedB.setValue(true);
                break;
            case 5:
                mLedR.setValue(false);
                mLedG.setValue(true);
                mLedB.setValue(false);
                break;
            case 6:
                mLedR.setValue(false);
                mLedG.setValue(false);
                mLedB.setValue(true);
                break;
            case 7:
                mLedR.setValue(false);
                mLedG.setValue(false);
                mLedB.setValue(false);
                break;
            default:
                mLedR.setValue(true);
                mLedG.setValue(true);
                mLedB.setValue(true);
                break;
        }

    }

    //Changing RGB LED color
    private void changeColor(){
        ledColor = ledColor + 1;
    }

    //Turning ON/OFF button lock
    private void  toggleButtonLock() {
        if (buttonLock){
            buttonLock = false;
        } else {
            buttonLock = true;
            buttonStatus = 0;
        }
        Log.i(TAG , "ButtonLock: "+ buttonLock);
    }

    //Changing blink time due to button status
    private void updateIntervalBetweenBlinks(){
        switch (buttonStatus){
            case 1:
                Log.i(TAG,"Period 1s");
                intervalBetweenBlinks = 1000;
                break;
            case 2:
                Log.i(TAG,"Period 0,5s");
                intervalBetweenBlinks = 500;
                break;
            case 3:
                Log.i(TAG,"Period 0,2s");
                intervalBetweenBlinks = 200;
                break;
            case 0:
                Log.i(TAG,"Period 2s");
                intervalBetweenBlinks = 2000;
            break;
        }
    }

    //Set color to yellow & Changing the brightness of RGB's common pin
    private void changeBrightness() throws IOException{

        mLedR.setValue(false);
        mLedG.setValue(false);
        mLedB.setValue(true);
        ledColor = 6;

        if (brightness < 0) {
            brightness = 100;
        }
        mLedRGB.setPwmDutyCycle(brightness);
        brightness = brightness - 20;
    }

    //Blinking each LED with different paces

    private void blinkEachLed() throws IOException {

        long intervalRedTime = 500;
        long intervalGreenTime = 2000;
        long intervalBlueTime = 3000;


        mLedRGB.setPwmDutyCycle(100);
        if (RGBTurnCounting == 0){
            mLedR.setValue(true);
            mLedG.setValue(true);
            mLedB.setValue(true);
            RGBTurnCounting = RGBTurnCounting + 1;
            mHandler.post(mRunnable);
        } else if (RGBTurnCounting <= 10){
            mLedR.setValue(!mLedR.getValue());
            RGBTurnCounting = RGBTurnCounting + 1;
            mHandler.postDelayed(mRunnable, intervalRedTime);
            nextPostTime = System.currentTimeMillis() + intervalRedTime;
        } else if (RGBTurnCounting <= 20){
                mLedG.setValue(!mLedG.getValue());
                RGBTurnCounting = RGBTurnCounting + 1;
            mHandler.postDelayed(mRunnable, intervalGreenTime);
            nextPostTime = System.currentTimeMillis() + intervalGreenTime;
        } else if (RGBTurnCounting <= 30){
                mLedB.setValue(!mLedB.getValue());
                RGBTurnCounting = RGBTurnCounting + 1;
            mHandler.postDelayed(mRunnable, intervalBlueTime);
            nextPostTime = System.currentTimeMillis() + intervalBlueTime;
        } else {RGBTurnCounting = 0;}

        /*

            if (expectRTime <= System.currentTimeMillis()) {
                expectRTime = System.currentTimeMillis() + intervalRedTime;
                mLedR.setValue(!mLedR.getValue());
            }

            if (expectGTime <= System.currentTimeMillis()) {
                expectGTime = System.currentTimeMillis() + intervalGreenTime;
                mLedG.setValue(!mLedG.getValue());

            if (expectBTime <= System.currentTimeMillis()) {
                expectBTime = System.currentTimeMillis() + intervalBlueTime;
                mLedB.setValue(!mLedB.getValue());
            }

         */

    }
}
