package com.example.android.sunshine.app;

import java.util.Calendar;

/**
 * Created by maulin on 10/10/16.
 */
public class Utility {
    private final static String mAmString="AM";
    private final static String mPmString="PM";

    static String getAmPmString(int amPm) {
        return amPm == Calendar.AM ? mAmString : mPmString;
    }

    static String formatTwoDigitNumber(int hour) {
        return String.format("%02d", hour);
    }
}
