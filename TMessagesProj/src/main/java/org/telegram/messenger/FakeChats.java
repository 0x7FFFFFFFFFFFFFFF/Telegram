package org.telegram.messenger;

import java.util.ArrayList;
import java.util.Collections;

// this fork: the made-up chats and channels shown after the fake passcode (FakeChatsActivity, FakeChatActivity).
// Nothing here touches the account: no server ids, no storage. Times are relative to when the chats are first
// shown, and messages "sent" in fake mode only live in memory.
public class FakeChats {

    public static class Message {
        public final String text;
        public final boolean out;
        public final int date;

        Message(String text, boolean out, int date) {
            this.text = text;
            this.out = out;
            this.date = date;
        }
    }

    public static class Chat {
        public final int id;
        public final String name;
        public final boolean channel;
        public final int subscribers;
        public boolean muted;
        public int unreadCount;
        // oldest first
        public final ArrayList<Message> messages = new ArrayList<>();

        Chat(int id, String name, boolean channel, int subscribers, boolean muted, int unreadCount) {
            this.id = id;
            this.name = name;
            this.channel = channel;
            this.subscribers = subscribers;
            this.muted = muted;
            this.unreadCount = unreadCount;
        }

        private Chat in(int minutesAgo, String text) {
            messages.add(new Message(text, false, now - minutesAgo * 60));
            return this;
        }

        private Chat out(int minutesAgo, String text) {
            messages.add(new Message(text, true, now - minutesAgo * 60));
            return this;
        }

        public Message getLastMessage() {
            return messages.get(messages.size() - 1);
        }
    }

    private static int now;
    private static ArrayList<Chat> chats;
    // FakeChatsActivity, to show what was sent from FakeChatActivity
    public static Runnable onChanged;

    private static final int HOUR = 60, DAY = 24 * 60;

    // newest last message first, like the chat list
    public static ArrayList<Chat> getChats() {
        if (chats == null) {
            chats = createChats();
        }
        final ArrayList<Chat> sorted = new ArrayList<>(chats);
        Collections.sort(sorted, (a, b) -> Integer.compare(b.getLastMessage().date, a.getLastMessage().date));
        return sorted;
    }

    public static Chat getChat(int id) {
        for (Chat chat : getChats()) {
            if (chat.id == id) {
                return chat;
            }
        }
        return null;
    }

    public static void send(Chat chat, String text) {
        chat.messages.add(new Message(text, true, (int) (System.currentTimeMillis() / 1000)));
        if (onChanged != null) {
            onChanged.run();
        }
    }

    private static ArrayList<Chat> createChats() {
        now = (int) (System.currentTimeMillis() / 1000);
        final ArrayList<Chat> list = new ArrayList<>();

        list.add(new Chat(1, "Tech News Daily", true, 248512, false, 3)
            .in(3 * DAY + 2 * HOUR, "Weekend read: how data centers are experimenting with liquid cooling to save energy.")
            .in(2 * DAY + 5 * HOUR, "A new generation of USB-C chargers promises faster charging with less heat.")
            .in(DAY + 7 * HOUR, "Several browsers are rolling out stricter protection against tracking cookies this month.")
            .in(DAY + 2 * HOUR, "Smartwatch makers are focusing on battery life and sleep tracking in their latest updates.")
            .in(5 * HOUR, "Morning briefing: chipmakers keep investing in new factories as demand for AI hardware stays high.")
            .in(2 * HOUR, "Phone makers are promising longer software support, with some models now getting seven years of updates.")
            .in(40, "Reminder: the big developer conference starts tomorrow. We'll post highlights as they happen."));

        list.add(new Chat(2, "Alex Chen", false, 0, false, 1)
            .in(2 * DAY + 3 * HOUR, "Did you end up getting the new phone?")
            .out(2 * DAY + 3 * HOUR - 4, "Not yet, waiting for the reviews to come out")
            .in(2 * DAY + 3 * HOUR - 6, "Makes sense. The battery tests should be out this week")
            .in(DAY + HOUR, "Here's one: battery life is apparently a lot better than last year")
            .out(DAY + HOUR - 12, "Nice, that's what I wanted to hear 👍")
            .in(65, "Let me know if you want to borrow my case, I have a spare"));

        list.add(new Chat(3, "Android Weekly", true, 91204, true, 0)
            .in(5 * DAY, "Poll results: 62% of you use gesture navigation, 38% still prefer the buttons.")
            .in(3 * DAY + 4 * HOUR, "Developers: the latest Android Studio update brings faster builds and better previews.")
            .in(2 * DAY, "Tip: you can limit background battery use for each app in Settings → Apps.")
            .in(3 * HOUR, "This week's security patches are rolling out. Check Settings → System → Software update."));

        list.add(new Chat(4, "Maria Lopez", false, 0, false, 0)
            .in(3 * DAY + 6 * HOUR, "Are we still on for lunch on Thursday?")
            .out(3 * DAY + 6 * HOUR - 15, "Yes! 12:30 at the usual place?")
            .in(3 * DAY + 6 * HOUR - 18, "Perfect, see you there")
            .out(DAY + 4 * HOUR, "Running 10 minutes late, sorry")
            .in(DAY + 4 * HOUR - 2, "No worries, I'll grab a table")
            .out(6 * HOUR, "Thanks for lunch, it was great to catch up"));

        list.add(new Chat(5, "AI Digest", true, 130877, false, 1)
            .in(4 * DAY, "Reading list: three beginner-friendly courses on machine learning basics.")
            .in(2 * DAY + 8 * HOUR, "Explainer: what a \"context window\" means for large language models.")
            .in(DAY + 3 * HOUR, "Researchers released an open dataset for training speech recognition in more languages.")
            .in(9 * HOUR, "More apps are adding on-device AI features that work without an internet connection."));

        list.add(new Chat(6, "Sam Patel", false, 0, false, 0)
            .in(4 * DAY + 2 * HOUR, "Have you tried the new update for the code editor?")
            .out(4 * DAY + 2 * HOUR - 30, "Yeah, the new search is much faster")
            .in(4 * DAY + 2 * HOUR - 33, "Agreed. Still getting used to the new sidebar though")
            .out(4 * DAY + HOUR, "Same here 😅")
            .in(DAY + 9 * HOUR, "Are you going to the meetup next Wednesday?")
            .out(DAY + 8 * HOUR, "Probably, if I finish work on time"));

        list.add(new Chat(7, "Gadget Reviews", true, 57390, true, 0)
            .in(6 * DAY, "Coming up: we compare five mechanical keyboards for home offices.")
            .in(4 * DAY + 5 * HOUR, "Our take on e-readers: color screens are getting better but still drain more battery.")
            .in(2 * DAY + 2 * HOUR, "Hands-on: a 14-inch laptop that weighs under 1 kg and lasts a full workday.")
            .in(DAY + 11 * HOUR, "Review: these budget earbuds have surprisingly good noise cancelling for the price."));

        list.add(new Chat(8, "Jordan Lee", false, 0, false, 0)
            .out(5 * DAY + 3 * HOUR, "Thanks for the article you sent, really interesting")
            .in(5 * DAY + 2 * HOUR, "Glad you liked it! The part about home battery storage was new to me too")
            .in(5 * DAY + 2 * HOUR - 1, "There's a follow-up talk next week if you're interested")
            .out(2 * DAY + 10 * HOUR, "Sure, send me the link when it's up")
            .in(2 * DAY + 9 * HOUR, "Will do 👍"));

        list.add(new Chat(9, "Open Source Weekly", true, 42118, false, 0)
            .in(7 * DAY, "Reminder: keep your dependencies up to date and turn on automatic security alerts.")
            .in(5 * DAY + 6 * HOUR, "Project spotlight: a lightweight note-taking app that syncs through your own server.")
            .in(3 * DAY + 2 * HOUR, "Maintainers of several popular projects are looking for volunteers to translate documentation.")
            .in(2 * DAY + 5 * HOUR, "A popular Linux desktop released a big update with smoother animations and better touchpad gestures."));
        return list;
    }
}
