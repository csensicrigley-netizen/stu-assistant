package com.stuassistant.app;

/** UI event boundary; the Activity owns the device engine and its lifecycle. */
public interface NativeFeedbackSink {
    void play(String kind, int strength);
    void cancel();
}
