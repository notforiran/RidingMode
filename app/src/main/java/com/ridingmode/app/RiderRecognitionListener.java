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

    @Override public void onReadyForSpeech(Bundle params) { service.onRecognizerReady(); }
    @Override public void onBeginningOfSpeech() { service.onRecognizerSpeechStarted(); }
    @Override public void onRmsChanged(float rmsdB) { }
    @Override public void onBufferReceived(byte[] buffer) { }
    @Override public void onEndOfSpeech() { service.onRecognizerSpeechEnded(); }
    @Override public void onError(int error) { service.onRecognizerError(error); }
    @Override public void onEvent(int eventType, Bundle params) { }
    @Override public void onPartialResults(Bundle partialResults) { }

    @Override
    public void onResults(Bundle results) {
        ArrayList<String> matches = null;
        if (results != null) {
            matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        }
        service.onRecognizerResults(matches);
    }
}
