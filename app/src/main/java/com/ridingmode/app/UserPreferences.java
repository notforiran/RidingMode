package com.ridingmode.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class UserPreferences {
    private static final String PREFS_NAME = "riding_mode_prefs";
    private static final String KEY_NOTIFICATIONS_ENABLED = "notifications_enabled";
    private static final String KEY_MUTED = "muted";
    private static final String KEY_CUSTOM_CONTACTS = "custom_contacts";
    private static final String KEY_IS_RIDING = "is_riding";

    private UserPreferences() { }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static boolean areNotificationsEnabled(Context context) {
        return prefs(context).getBoolean(KEY_NOTIFICATIONS_ENABLED, true);
    }

    public static void setNotificationsEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_NOTIFICATIONS_ENABLED, enabled).apply();
    }

    
    public static void setRiding(Context context, boolean riding) {
        prefs(context).edit().putBoolean(KEY_IS_RIDING, riding).apply();
    }

    
    public static boolean wasRiding(Context context) {
        return prefs(context).getBoolean(KEY_IS_RIDING, false);
    }

    public static boolean isMuted(Context context) {
        return prefs(context).getBoolean(KEY_MUTED, false);
    }

    public static void setMuted(Context context, boolean muted) {
        prefs(context).edit().putBoolean(KEY_MUTED, muted).apply();
    }

    public static List<CustomContact> getCustomContacts(Context context) {
        List<CustomContact> output = new ArrayList<>();
        String raw = prefs(context).getString(KEY_CUSTOM_CONTACTS, "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                String name = item.optString("name", "").trim();
                String number = item.optString("number", "").trim();
                if (name.length() == 0 || number.length() == 0) continue;
                output.add(new CustomContact(name, number));
            }
        } catch (Exception ignored) { }
        return output;
    }

    public static void saveCustomContacts(Context context, List<CustomContact> contacts) {
        JSONArray array = new JSONArray();
        for (CustomContact contact : contacts) {
            if (contact == null || contact.name == null || contact.number == null) continue;
            if (contact.name.trim().length() == 0 || contact.number.trim().length() == 0) continue;
            try {
                JSONObject object = new JSONObject();
                object.put("name", contact.name.trim());
                object.put("number", contact.number.trim());
                array.put(object);
            } catch (Exception ignored) { }
        }
        prefs(context).edit().putString(KEY_CUSTOM_CONTACTS, array.toString()).apply();
    }

    public static void upsertCustomContact(Context context, String name, String number) {
        List<CustomContact> contacts = getCustomContacts(context);
        String normalizedName = normalizeName(name);
        boolean replaced = false;
        for (int i = 0; i < contacts.size(); i++) {
            CustomContact current = contacts.get(i);
            if (normalizeName(current.name).equals(normalizedName)) {
                contacts.set(i, new CustomContact(name.trim(), number.trim()));
                replaced = true;
                break;
            }
        }
        if (!replaced) contacts.add(0, new CustomContact(name.trim(), number.trim()));
        saveCustomContacts(context, contacts);
    }

    public static void removeCustomContact(Context context, int index) {
        List<CustomContact> contacts = getCustomContacts(context);
        if (index >= 0 && index < contacts.size()) {
            contacts.remove(index);
            saveCustomContacts(context, contacts);
        }
    }

    public static String normalizeName(String value) {
        if (value == null) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .toLowerCase(Locale.US)
                .replaceAll("[^a-z0-9+ ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    public static final class CustomContact {
        public final String name;
        public final String number;
        public final String normalizedName;

        public CustomContact(String name, String number) {
            this.name = name == null ? "" : name.trim();
            this.number = number == null ? "" : number.trim();
            this.normalizedName = normalizeName(this.name);
        }
    }
}
