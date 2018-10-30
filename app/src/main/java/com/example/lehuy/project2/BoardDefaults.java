package com.example.lehuy.project2;

import android.os.Build;
@SuppressWarnings("WeakerAccess")
public class BoardDefaults {
    private static final String DEVICE_RPI3 = "rpi3";

    public static String getUartName(){
        switch(Build.DEVICE){
            case DEVICE_RPI3:
                return "UART0";
            default:
                throw new IllegalStateException("Unknown Build.DEVICE" + Build.DEVICE);
        }
    }

    public static String getPWMPort() {
        switch (Build.DEVICE) {
            case DEVICE_RPI3:
                return "PWM0";
            default:
                throw new IllegalStateException("Unknown Build.DEVICE " + Build.DEVICE);
        }
    }
}