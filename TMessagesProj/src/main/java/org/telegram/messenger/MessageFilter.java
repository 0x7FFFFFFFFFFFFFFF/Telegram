package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;

// this fork: a global list of words to filter out. Any message, channel post, chat-list entry or person whose
// visible text contains one of these words (case-insensitive substring) is hidden from the UI entirely. The list
// is managed from Settings > Privacy and Security > Filtered Words, or by long-pressing a message or a chat and
// choosing Block (which prefills the word with the message text or the name, to edit before saving).
public class MessageFilter {

    private static final String PREFS_NAME = "filtered_words";
    private static final String KEY_WORDS = "words";

    private static ArrayList<String> words;    // as entered, kept sorted, for display
    private static ArrayList<String> needles;  // lowercased, for matching

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static void ensureLoaded() {
        if (words != null) {
            return;
        }
        words = new ArrayList<>();
        needles = new ArrayList<>();
        final ArrayList<String> stored = new ArrayList<>(prefs().getStringSet(KEY_WORDS, new LinkedHashSet<>()));
        Collections.sort(stored, String.CASE_INSENSITIVE_ORDER);
        for (int i = 0; i < stored.size(); i++) {
            final String w = stored.get(i);
            if (w != null && w.trim().length() > 0) {
                words.add(w);
                needles.add(w.toLowerCase(Locale.getDefault()));
            }
        }
    }

    public static ArrayList<String> getWords() {
        ensureLoaded();
        return new ArrayList<>(words);
    }

    public static boolean isEmpty() {
        ensureLoaded();
        return needles.isEmpty();
    }

    public static boolean contains(String word) {
        ensureLoaded();
        if (word == null) {
            return false;
        }
        final String t = word.trim();
        for (int i = 0; i < words.size(); i++) {
            if (words.get(i).equalsIgnoreCase(t)) {
                return true;
            }
        }
        return false;
    }

    public static void add(String word) {
        ensureLoaded();
        if (word == null) {
            return;
        }
        final String t = word.trim();
        if (t.length() == 0 || contains(t)) {
            return;
        }
        words.add(t);
        Collections.sort(words, String.CASE_INSENSITIVE_ORDER);
        rebuildNeedles();
        save();
    }

    public static void remove(String word) {
        ensureLoaded();
        if (word == null) {
            return;
        }
        boolean changed = false;
        for (int i = words.size() - 1; i >= 0; i--) {
            if (words.get(i).equalsIgnoreCase(word.trim())) {
                words.remove(i);
                changed = true;
            }
        }
        if (changed) {
            rebuildNeedles();
            save();
        }
    }

    private static void rebuildNeedles() {
        needles.clear();
        for (int i = 0; i < words.size(); i++) {
            needles.add(words.get(i).toLowerCase(Locale.getDefault()));
        }
    }

    private static void save() {
        prefs().edit().putStringSet(KEY_WORDS, new LinkedHashSet<>(words)).apply();
        AndroidUtilities.runOnUIThread(() ->
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.filteredWordsChanged));
    }

    // --- matching ---

    public static boolean matches(CharSequence text) {
        if (TextUtils.isEmpty(text)) {
            return false;
        }
        ensureLoaded();
        if (needles.isEmpty()) {
            return false;
        }
        final String s = text.toString().toLowerCase(Locale.getDefault());
        for (int i = 0; i < needles.size(); i++) {
            if (s.contains(needles.get(i))) {
                return true;
            }
        }
        return false;
    }

    public static boolean matchesUser(TLRPC.User user) {
        if (user == null) {
            return false;
        }
        ensureLoaded();
        if (needles.isEmpty()) {
            return false;
        }
        if (matches(UserObject.getUserName(user))) {
            return true;
        }
        return !TextUtils.isEmpty(user.username) && matches(user.username);
    }

    public static boolean matchesChat(TLRPC.Chat chat) {
        if (chat == null) {
            return false;
        }
        ensureLoaded();
        if (needles.isEmpty()) {
            return false;
        }
        if (matches(chat.title)) {
            return true;
        }
        return !TextUtils.isEmpty(chat.username) && matches(chat.username);
    }

    public static boolean matchesMessage(MessageObject message) {
        if (message == null) {
            return false;
        }
        ensureLoaded();
        if (needles.isEmpty()) {
            return false;
        }
        if (matches(message.messageText) || matches(message.caption)) {
            return true;
        }
        return message.messageOwner != null && matches(message.messageOwner.message);
    }

    // a dialog is hidden when its peer (a user or a chat) matches by name/title/username
    public static boolean matchesDialog(int currentAccount, long dialogId) {
        ensureLoaded();
        if (needles.isEmpty()) {
            return false;
        }
        final MessagesController controller = MessagesController.getInstance(currentAccount);
        if (DialogObject.isUserDialog(dialogId)) {
            if (matchesUser(controller.getUser(dialogId))) {
                return true;
            }
            final TLRPC.UserFull full = controller.getUserFull(dialogId); // bio, if it has been loaded
            return full != null && matches(full.about);
        }
        if (DialogObject.isChatDialog(dialogId)) {
            if (matchesChat(controller.getChat(-dialogId))) {
                return true;
            }
            final TLRPC.ChatFull full = controller.getChatFull(-dialogId); // description, if it has been loaded
            return full != null && matches(full.about);
        }
        return false;
    }
}
