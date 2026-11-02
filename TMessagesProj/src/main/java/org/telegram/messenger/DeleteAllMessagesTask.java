package org.telegram.messenger;

import static org.telegram.messenger.LocaleController.formatPluralString;
import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.Bulletin;
import org.telegram.ui.Components.BulletinFactory;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;

// Deletes, for everyone, every message one user sent in a group. The messages are found with messages.search
// (from that user, newest first, a page at a time) and deleted 100 at a time; ConnectionsManager waits out
// flood waits by itself, so a long history only takes longer. For a channel this works on its discussion
// group, which holds the channel's comments, and a group upgraded from a basic group also gets the old
// group's messages deleted. An admin deleting another member's messages in a supergroup uses
// channels.deleteParticipantHistory instead, which the server runs in bulk.
// The "everywhere" task does this for my messages in every group I'm in and under every channel I follow: it
// lists my chats with messages.getAllChats (plus the loaded chat list), looks up the channels' discussion
// groups, and then works through them one by one; a chat that fails is counted and skipped.
public class DeleteAllMessagesTask {

    private static final int PAGE_SIZE = 100;
    // messages.getAllChats pages by leaving out the chats it already sent; this caps a slice that never ends
    private static final int MAX_CHAT_LIST_REQUESTS = 20;

    // by account, chat and author, so tapping the menu item again shows the running task instead of starting another
    private static final HashMap<String, DeleteAllMessagesTask> tasks = new HashMap<>();

    private final int currentAccount;
    private final String key;
    private final TLRPC.Chat chat; // where the task was started; null for the everywhere task
    private final TLRPC.User fromUser; // null for my own messages
    private final boolean everywhere;

    // where to delete, in order
    private final ArrayList<Long> dialogIds = new ArrayList<>();
    private final HashSet<Long> dialogIdsSet = new HashSet<>();

    // the everywhere task first lists my chats, then looks up the discussion groups of the channels among them
    private boolean listingChats;
    private int chatListRequests;
    private final LinkedHashMap<Long, TLRPC.Chat> listedChats = new LinkedHashMap<>();
    private final ArrayDeque<TLRPC.Chat> channelsToResolve = new ArrayDeque<>();

    private int dialogIndex;
    private int offsetId;
    // pages of ids found in dialogIds[dialogIndex] and not deleted yet
    private final ArrayDeque<ArrayList<Integer>> batches = new ArrayDeque<>();
    private int total, deleted, skipped;
    private int deletedInDialog, chatsCleaned, chatsFailed;
    private String lastChatError;
    private boolean participantCacheCleared;
    private int reqId;
    private boolean deleting;
    private boolean cancelled, finished;

    private Bulletin bulletin;
    private Bulletin.ProgressLayout bulletinLayout;
    private WeakReference<BaseFragment> fragmentRef;

    // groups: my messages; a channel: my comments, if it has a discussion group
    public static boolean canDeleteMine(TLRPC.Chat chat, TLRPC.ChatFull chatFull) {
        if (chat == null || ChatObject.isMonoForum(chat)) {
            return false;
        }
        if (ChatObject.isChannelAndNotMegaGroup(chat)) {
            return chatFull != null && chatFull.linked_chat_id != 0;
        }
        return true;
    }

    // another member's messages: admins who may delete messages of others
    public static boolean canDeleteFrom(TLRPC.Chat chat) {
        return chat != null && !ChatObject.isChannelAndNotMegaGroup(chat) && !ChatObject.isMonoForum(chat) && ChatObject.canUserDoAdminAction(chat, ChatObject.ACTION_DELETE_MESSAGES);
    }

    public static void deleteMine(BaseFragment fragment, TLRPC.Chat chat, TLRPC.ChatFull chatFull) {
        start(fragment, chat, chatFull, null);
    }

    public static void deleteFrom(BaseFragment fragment, TLRPC.Chat chat, TLRPC.User user) {
        if (user != null) {
            start(fragment, chat, null, user);
        }
    }

    // my messages in every group I'm in, and my comments under every channel I follow
    public static void deleteMineEverywhere(BaseFragment fragment) {
        if (fragment == null) {
            return;
        }
        final int currentAccount = fragment.getCurrentAccount();
        final String key = currentAccount + "_everywhere";
        DeleteAllMessagesTask task = tasks.get(key);
        if (task == null) {
            task = new DeleteAllMessagesTask(currentAccount, key, null, null, true);
            task.listingChats = true;
            tasks.put(key, task);
            task.next();
        }
        task.showBulletin(fragment);
    }

    private static void start(BaseFragment fragment, TLRPC.Chat chat, TLRPC.ChatFull chatFull, TLRPC.User fromUser) {
        if (fragment == null || chat == null) {
            return;
        }
        final int currentAccount = fragment.getCurrentAccount();
        final MessagesController messagesController = MessagesController.getInstance(currentAccount);
        if (chatFull == null) {
            chatFull = messagesController.getChatFull(chat.id);
        }
        TLRPC.Chat group = chat;
        if (ChatObject.isChannelAndNotMegaGroup(chat)) {
            group = chatFull != null && chatFull.linked_chat_id != 0 ? messagesController.getChat(chatFull.linked_chat_id) : null;
            if (group == null) {
                BulletinFactory.of(fragment).createErrorBulletin(getString(R.string.DeleteAllNoComments)).show();
                return;
            }
            chatFull = messagesController.getChatFull(group.id);
        }

        final String key = currentAccount + "_" + group.id + "_" + (fromUser != null ? fromUser.id : 0);
        DeleteAllMessagesTask task = tasks.get(key);
        if (task == null) {
            task = new DeleteAllMessagesTask(currentAccount, key, chat, fromUser, false);
            task.addDialog(-group.id);
            // my messages also go from the basic group this group was upgraded from;
            // another member's can't be deleted for everyone there anymore
            if (fromUser == null && chatFull != null) {
                task.addDialog(-chatFull.migrated_from_chat_id);
            }
            tasks.put(key, task);
            task.next();
        }
        task.showBulletin(fragment);
    }

    private DeleteAllMessagesTask(int currentAccount, String key, TLRPC.Chat chat, TLRPC.User fromUser, boolean everywhere) {
        this.currentAccount = currentAccount;
        this.key = key;
        this.chat = chat;
        this.fromUser = fromUser;
        this.everywhere = everywhere;
    }

    private MessagesController getMessagesController() {
        return MessagesController.getInstance(currentAccount);
    }

    private ConnectionsManager getConnectionsManager() {
        return ConnectionsManager.getInstance(currentAccount);
    }

    private void addDialog(long dialogId) {
        if (dialogId != 0 && dialogIdsSet.add(dialogId)) {
            dialogIds.add(dialogId);
        }
    }

    private void next() {
        if (finished) {
            return;
        }
        if (cancelled) {
            finish(null);
            return;
        }
        if (listingChats) {
            listChats();
            return;
        }
        while (!channelsToResolve.isEmpty()) {
            final TLRPC.Chat channel = channelsToResolve.peek();
            // the discussion group is known if the channel's full info is cached, along with that group
            final TLRPC.ChatFull channelFull = getMessagesController().getChatFull(channel.id);
            if (channelFull == null || channelFull.linked_chat_id != 0 && getMessagesController().getChat(channelFull.linked_chat_id) == null) {
                loadDiscussionGroup(channel);
                return;
            }
            channelsToResolve.poll();
            addDialog(-channelFull.linked_chat_id);
        }
        if (!batches.isEmpty()) {
            deleteBatch(batches.poll());
            return;
        }
        if (dialogIndex >= dialogIds.size()) {
            finish(null);
            return;
        }
        final long dialogId = dialogIds.get(dialogIndex);
        final TLRPC.Chat dialogChat = getMessagesController().getChat(-dialogId);
        if (fromUser != null && ChatObject.isChannel(dialogChat)) {
            deleteParticipantHistory(dialogChat);
        } else {
            searchPage(dialogId, dialogChat);
        }
    }

    private void listChats() {
        final TLRPC.TL_messages_getAllChats req = new TLRPC.TL_messages_getAllChats();
        req.except_ids.addAll(listedChats.keySet());
        deleting = false;
        reqId = getConnectionsManager().sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            reqId = 0;
            if (finished) {
                return;
            }
            chatListRequests++;
            boolean more = false;
            // without an answer the task goes on with the chats in the loaded chat list
            if (response instanceof TLRPC.messages_Chats) {
                final TLRPC.messages_Chats res = (TLRPC.messages_Chats) response;
                getMessagesController().putChats(res.chats, false);
                int added = 0;
                for (int i = 0; i < res.chats.size(); ++i) {
                    final TLRPC.Chat listed = res.chats.get(i);
                    if (listed != null && listedChats.put(listed.id, listed) == null) {
                        added++;
                    }
                }
                // a slice has more: ask again without the chats listed so far
                more = res instanceof TLRPC.TL_messages_chatsSlice && added > 0 && listedChats.size() < res.count && chatListRequests < MAX_CHAT_LIST_REQUESTS;
            }
            if (!more) {
                listingChats = false;
                addListedChats();
            }
            updateBulletin();
            next();
        }));
    }

    private void addListedChats() {
        final ArrayList<TLRPC.Dialog> dialogs = getMessagesController().getAllDialogs();
        for (int i = 0; i < dialogs.size(); ++i) {
            final long dialogId = dialogs.get(i).id;
            if (DialogObject.isChatDialog(dialogId) && !listedChats.containsKey(-dialogId)) {
                final TLRPC.Chat listed = getMessagesController().getChat(-dialogId);
                if (listed != null) {
                    listedChats.put(listed.id, listed);
                }
            }
        }
        for (TLRPC.Chat listed : listedChats.values()) {
            // groups I left or was removed from are out of reach; a basic group upgraded to a supergroup
            // ("deactivated") still has my old messages
            if (listed instanceof TLRPC.TL_chatEmpty || listed instanceof TLRPC.TL_chatForbidden || listed instanceof TLRPC.TL_channelForbidden ||
                    listed.left || listed.kicked || ChatObject.isMonoForum(listed)) {
                continue;
            }
            final TLRPC.ChatFull listedFull = getMessagesController().getChatFull(listed.id);
            if (ChatObject.isChannelAndNotMegaGroup(listed)) {
                if (listed.has_link || listedFull != null && listedFull.linked_chat_id != 0) {
                    channelsToResolve.add(listed);
                }
                continue;
            }
            addDialog(-listed.id);
            if (listedFull != null) {
                addDialog(-listedFull.migrated_from_chat_id);
            }
        }
    }

    private void loadDiscussionGroup(TLRPC.Chat channel) {
        final TLRPC.TL_channels_getFullChannel req = new TLRPC.TL_channels_getFullChannel();
        req.channel = MessagesController.getInputChannel(channel);
        deleting = false;
        reqId = getConnectionsManager().sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            reqId = 0;
            if (finished) {
                return;
            }
            channelsToResolve.poll();
            // a channel that can't be read is passed over: its comments are all that's missed
            if (response instanceof TLRPC.TL_messages_chatFull) {
                final TLRPC.TL_messages_chatFull res = (TLRPC.TL_messages_chatFull) response;
                getMessagesController().putUsers(res.users, false);
                getMessagesController().putChats(res.chats, false);
                if (res.full_chat != null && getMessagesController().getChat(res.full_chat.linked_chat_id) != null) {
                    addDialog(-res.full_chat.linked_chat_id);
                }
            }
            updateBulletin();
            next();
        }));
    }

    private void searchPage(long dialogId, TLRPC.Chat dialogChat) {
        final TLRPC.TL_messages_search req = new TLRPC.TL_messages_search();
        req.peer = getMessagesController().getInputPeer(dialogId);
        req.q = "";
        req.from_id = fromUser != null ? MessagesController.getInputPeer(fromUser) : new TLRPC.TL_inputPeerSelf();
        req.flags |= 1;
        req.filter = new TLRPC.TL_inputMessagesFilterEmpty();
        req.offset_id = offsetId;
        req.limit = PAGE_SIZE;
        deleting = false;
        reqId = getConnectionsManager().sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            reqId = 0;
            if (finished) {
                return;
            }
            if (!(response instanceof TLRPC.messages_Messages)) {
                chatFailed(error != null ? error.text : "NO_RESPONSE");
                return;
            }
            final TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
            if (offsetId == 0) {
                total += Math.max(res.count, res.messages.size());
            }
            final ArrayList<Integer> ids = new ArrayList<>();
            final ArrayList<Integer> serviceIds = new ArrayList<>();
            int minId = Integer.MAX_VALUE;
            for (int i = 0; i < res.messages.size(); ++i) {
                final TLRPC.Message message = res.messages.get(i);
                if (message == null || message.id <= 0 || message instanceof TLRPC.TL_messageEmpty) {
                    continue;
                }
                minId = Math.min(minId, message.id);
                if (!MessageObject.canDeleteMessage(currentAccount, false, message, dialogChat)) {
                    skipped++;
                } else if (message instanceof TLRPC.TL_messageService) {
                    serviceIds.add(message.id);
                } else {
                    ids.add(message.id);
                }
            }
            if (minId == Integer.MAX_VALUE) {
                // an empty page: nothing older is left in this chat
                nextDialog();
            } else {
                // the next page starts below this one, so messages that weren't deleted aren't found again
                offsetId = minId;
                // service messages go in their own batch: one that can't be deleted would fail the whole request
                if (!ids.isEmpty()) {
                    batches.add(ids);
                }
                if (!serviceIds.isEmpty()) {
                    batches.add(serviceIds);
                }
            }
            updateBulletin();
            next();
        }));
    }

    private void deleteBatch(ArrayList<Integer> ids) {
        final long dialogId = dialogIds.get(dialogIndex);
        final TLRPC.Chat dialogChat = getMessagesController().getChat(-dialogId);
        final boolean channel = ChatObject.isChannel(dialogChat);
        final TLObject request;
        if (channel) {
            final TLRPC.TL_channels_deleteMessages req = new TLRPC.TL_channels_deleteMessages();
            req.channel = MessagesController.getInputChannel(dialogChat);
            req.id = ids;
            request = req;
        } else {
            final TLRPC.TL_messages_deleteMessages req = new TLRPC.TL_messages_deleteMessages();
            req.revoke = true;
            req.id = ids;
            request = req;
        }
        deleting = true;
        reqId = getConnectionsManager().sendRequest(request, (response, error) -> {
            if (response instanceof TLRPC.TL_messages_affectedMessages) {
                final TLRPC.TL_messages_affectedMessages res = (TLRPC.TL_messages_affectedMessages) response;
                if (channel) {
                    getMessagesController().processNewChannelDifferenceParams(res.pts, res.pts_count, dialogChat.id);
                } else {
                    getMessagesController().processNewDifferenceParams(-1, res.pts, -1, res.pts_count);
                }
            }
            AndroidUtilities.runOnUIThread(() -> {
                reqId = 0;
                if (error == null) {
                    deleted += ids.size();
                    deletedInDialog += ids.size();
                    // gone on the server; now remove them from the cache and from an open chat
                    getMessagesController().deleteMessages(ids, null, null, dialogId, 0, true, ChatActivity.MODE_DEFAULT, true);
                } else if (isPerMessageError(error)) {
                    if (ids.size() > 1) {
                        // some message in the batch can't be deleted: halve the batch until that one is alone
                        final int half = ids.size() / 2;
                        batches.addFirst(new ArrayList<>(ids.subList(half, ids.size())));
                        batches.addFirst(new ArrayList<>(ids.subList(0, half)));
                    } else {
                        skipped++;
                    }
                } else {
                    chatFailed(error.text);
                    return;
                }
                updateBulletin();
                next();
            });
        });
    }

    private static boolean isPerMessageError(TLRPC.TL_error error) {
        return error.text != null && (error.text.startsWith("MESSAGE_DELETE_FORBIDDEN") || error.text.startsWith("MESSAGE_ID_INVALID"));
    }

    private void deleteParticipantHistory(TLRPC.Chat dialogChat) {
        final TLRPC.TL_channels_deleteParticipantHistory req = new TLRPC.TL_channels_deleteParticipantHistory();
        req.channel = MessagesController.getInputChannel(dialogChat);
        req.participant = MessagesController.getInputPeer(fromUser);
        deleting = true;
        reqId = getConnectionsManager().sendRequest(req, (response, error) -> {
            if (response instanceof TLRPC.TL_messages_affectedHistory) {
                final TLRPC.TL_messages_affectedHistory res = (TLRPC.TL_messages_affectedHistory) response;
                getMessagesController().processNewChannelDifferenceParams(res.pts, res.pts_count, dialogChat.id);
            }
            AndroidUtilities.runOnUIThread(() -> {
                reqId = 0;
                if (!(response instanceof TLRPC.TL_messages_affectedHistory)) {
                    chatFailed(error != null ? error.text : "NO_RESPONSE");
                    return;
                }
                final TLRPC.TL_messages_affectedHistory res = (TLRPC.TL_messages_affectedHistory) response;
                deleted += res.pts_count;
                deletedInDialog += res.pts_count;
                if (!participantCacheCleared) {
                    // the server has started deleting; drop that member's messages from the cache and an open chat
                    participantCacheCleared = true;
                    MessagesStorage.getInstance(currentAccount).deleteUserChatHistory(-dialogChat.id, fromUser.id);
                }
                if (res.offset <= 0) {
                    nextDialog();
                }
                updateBulletin();
                next();
            });
        });
    }

    private void nextDialog() {
        if (deletedInDialog > 0) {
            chatsCleaned++;
        }
        deletedInDialog = 0;
        dialogIndex++;
        offsetId = 0;
        batches.clear();
        participantCacheCleared = false;
    }

    // a failed chat ends a one-chat task; the everywhere task notes it and goes on with the next chat
    private void chatFailed(String error) {
        if (!everywhere) {
            finish(error);
            return;
        }
        chatsFailed++;
        lastChatError = error;
        nextDialog();
        updateBulletin();
        next();
    }

    private void cancel() {
        if (finished || cancelled) {
            return;
        }
        cancelled = true;
        // a running delete is left to finish, so its messages also leave the cache; the task then stops in next()
        if (reqId != 0 && !deleting) {
            getConnectionsManager().cancelRequest(reqId, true);
            reqId = 0;
            finish(null);
        } else if (reqId == 0) {
            finish(null);
        }
    }

    private void finish(String error) {
        if (finished) {
            return;
        }
        finished = true;
        tasks.remove(key);
        if (deletedInDialog > 0) {
            chatsCleaned++;
            deletedInDialog = 0;
        }
        if (bulletin != null) {
            bulletin.hide();
            bulletin = null;
            bulletinLayout = null;
        }
        // the result goes where the progress was if that screen is still in front (on a tablet the chat is
        // in the right pane, which global() doesn't look at), otherwise on whatever screen is
        final BaseFragment fragment = fragmentRef != null ? fragmentRef.get() : null;
        final boolean sameScreen = fragment != null && !fragment.isPaused() && fragment.isLastFragment() && BulletinFactory.canShowBulletin(fragment);
        final BulletinFactory factory = sameScreen ? BulletinFactory.of(fragment) : BulletinFactory.global();
        final Bulletin result;
        if (error != null) {
            String text = formatString(R.string.DeleteAllMessagesFailed, error);
            if (deleted > 0) {
                text = formatString(R.string.DeleteAllMessagesFailedAfter, formatPluralString("DeleteAllMessagesDone", deleted), error);
            }
            result = factory.createErrorBulletin(text);
        } else {
            String text;
            if (deleted == 0 && skipped == 0 && chatsFailed == 0) {
                text = getString(cancelled ? R.string.DeleteAllMessagesStoppedNone : R.string.DeleteAllMessagesNone);
            } else {
                text = formatPluralString(cancelled ? "DeleteAllMessagesStopped" : "DeleteAllMessagesDone", deleted);
                if (everywhere && chatsCleaned > 0) {
                    text = formatString(R.string.DeleteAllEverywhereInChats, text, formatPluralString("Chats", chatsCleaned));
                }
                if (skipped > 0) {
                    text = formatString(R.string.DeleteAllMessagesSkipped, text, skipped);
                }
                if (chatsFailed > 0) {
                    text = formatString(R.string.DeleteAllEverywhereFailedChats, text, chatsFailed, lastChatError);
                }
            }
            result = factory.createSimpleBulletinDetail(R.raw.ic_delete, text);
        }
        result.setDuration(Bulletin.DURATION_PROLONG).show();
    }

    private void showBulletin(BaseFragment fragment) {
        if (finished || !BulletinFactory.canShowBulletin(fragment)) {
            return;
        }
        if (bulletin != null) {
            bulletin.hide(false, 0);
        }
        fragmentRef = new WeakReference<>(fragment);
        final Context context = fragment.getParentActivity();
        final Theme.ResourcesProvider resourcesProvider = fragment.getResourceProvider();
        bulletinLayout = new Bulletin.ProgressLayout(context, resourcesProvider);
        final TLObject avatarObject = fromUser != null ? fromUser : chat != null ? chat : UserConfig.getInstance(currentAccount).getCurrentUser();
        final AvatarDrawable avatarDrawable = new AvatarDrawable();
        avatarDrawable.setInfo(currentAccount, avatarObject);
        bulletinLayout.imageView.setForUserOrChat(avatarObject, avatarDrawable);
        bulletinLayout.setButton(new Bulletin.UndoButton(context, true, resourcesProvider).setText(getString(R.string.Stop)).setUndoAction(this::cancel));
        bulletinLayout.textView.setText(getProgressText(), false);
        bulletinLayout.setProgress(getProgress());
        // stays until the task ends or Stop is tapped; leaving the screen only hides it, the task goes on
        bulletin = BulletinFactory.of(fragment).create(bulletinLayout, -1);
        bulletin.hideAfterBottomSheet = false;
        bulletin.show();
    }

    private void updateBulletin() {
        if (bulletin == null || !bulletin.isShowing()) {
            return;
        }
        bulletinLayout.textView.setText(getProgressText(), true);
        bulletinLayout.setProgress(getProgress());
    }

    private boolean isFindingChats() {
        return listingChats || !channelsToResolve.isEmpty();
    }

    private CharSequence getProgressText() {
        if (everywhere) {
            if (isFindingChats()) {
                return getString(R.string.DeleteAllEverywhereFinding);
            }
            return formatString(R.string.DeleteAllEverywhereProgress, deleted, Math.min(dialogIndex + 1, dialogIds.size()), dialogIds.size());
        }
        if (total > 0) {
            return formatString(R.string.DeleteAllMessagesProgress, Math.min(deleted, total), total);
        }
        return getString(R.string.DeleteAllMessagesWorking);
    }

    private float getProgress() {
        // 1 would show the ring as done
        if (everywhere) {
            return isFindingChats() || dialogIds.isEmpty() ? 0 : Math.min(0.99f, dialogIndex / (float) dialogIds.size());
        }
        return total > 0 ? Math.min(0.99f, (deleted + skipped) / (float) total) : 0;
    }
}
