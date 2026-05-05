package com.ridingmode.app;

import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.SpeechRecognizer;
import java.util.ArrayList;

public class RiderRecognitionListener implements RecognitionListener {
    private final RidingForegroundService service;

    public RiderRecognitionListener(RidingForegroundService service) {
        this.service = service;
    }

    @Override public void onReadyForSpeech(Bundle params) { }
    @Override public void onBeginningOfSpeech() { }
    @Override public void onRmsChanged(float rmsdB) { }
    @Override public void onBufferReceived(byte[] buffer) { }
    @Override public void onEndOfSpeech() { }
    @Override public void onError(int error) { service.restartListening(); }
    @Override public void onEvent(int eventType, Bundle params) { }
    @Override public void onPartialResults(Bundle partialResults) { }

    @Override
    public void onResults(Bundle results) {
        if (results != null) {
            ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (matches != null && !matches.isEmpty()) service.handleCommand(matches.get(0));
        }
        service.restartListening();
    }
}
