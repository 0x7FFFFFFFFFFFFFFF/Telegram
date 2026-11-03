package org.telegram.messenger;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_account;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;

// this fork: the quiet account wipe behind the wipe passcode (SharedConfig.wipePasscodeHash). It runs with no UI
// while the fake chats are shown, and for every logged-in account it, in order:
//   - resets the profile: clears the last name, bio, username and all profile photos (the first name is left,
//     since Telegram won't accept an empty one),
//   - deletes every contact,
//   - lists every chat (messages.getAllChats) and every private chat (messages.getDialogs, paged like
//     DeleteAllMessagesTask), then leaves every group and channel and deletes every chat's history for both sides.
// Everything is best-effort and silent: a request that fails is passed over. SharedConfig.wipePending keeps it
// going across restarts; when the last account's pass finishes, the flag is cleared. There is no cancel: once the
// wipe passcode is entered the wipe runs to the end.
public class WipeAccountTask {

    private static final int PAGE_SIZE = 100;
    // messages.getAllChats pages by leaving out the chats it already sent; this caps a slice that never ends
    private static final int MAX_CHAT_LIST_REQUESTS = 20;
    // pages of the private-chat list (100 each) looked through
    private static final int MAX_DIALOG_LIST_REQUESTS = 200;

    private static final HashMap<Integer, WipeAccountTask> tasks = new HashMap<>();
    private static int running;

    // starts (or resumes) the wipe on every logged-in account; clears the flag if there's nothing to wipe
    public static void runIfPending() {
        if (!SharedConfig.wipePending) {
            return;
        }
        boolean any = false;
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.getInstance(a).isClientActivated()) {
                any = true;
                if (!tasks.containsKey(a)) {
                    final WipeAccountTask task = new WipeAccountTask(a);
                    tasks.put(a, task);
                    running++;
                    task.next();
                }
            }
        }
        if (!any) {
            SharedConfig.clearWipePending();
        }
    }

    private final int currentAccount;

    // 0 reset profile, 1 delete contacts, 2 list chats and private chats, 3 leave/clear each of them
    private int step;
    private boolean finished;
    private int reqId;

    // enumeration, the same approach as DeleteAllMessagesTask
    private boolean listingChats = true;
    private int chatListRequests;
    private final LinkedHashMap<Long, TLRPC.Chat> listedChats = new LinkedHashMap<>();
    private boolean listingDialogs = true;
    private int dialogListStep, dialogListRequests;
    private int dialogListOffsetId, dialogListOffsetDate;
    private TLRPC.InputPeer dialogListOffsetPeer;

    // every chat and private chat to clear, as dialog ids (negative for chats, positive for users)
    private final ArrayList<Long> dialogIds = new ArrayList<>();
    private final HashSet<Long> dialogIdsSet = new HashSet<>();
    private int dialogIndex;

    private WipeAccountTask(int currentAccount) {
        this.currentAccount = currentAccount;
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
        if (step == 0) {
            resetProfile();
            return;
        }
        if (step == 1) {
            deleteContacts();
            return;
        }
        if (step == 2) {
            if (listingChats) {
                listChats();
                return;
            }
            if (listingDialogs) {
                listDialogs();
                return;
            }
            step = 3;
        }
        if (dialogIndex >= dialogIds.size()) {
            finish();
            return;
        }
        nukeDialog(dialogIds.get(dialogIndex));
    }

    private void resetProfile() {
        // the first name is left as is: Telegram rejects an empty one. The rest is cleared.
        final TL_account.updateProfile updateProfile = new TL_account.updateProfile();
        updateProfile.flags = 2 | 4; // last_name | about
        updateProfile.last_name = "";
        updateProfile.about = "";
        getConnectionsManager().sendRequest(updateProfile, (response, error) -> {
            if (response instanceof TLRPC.User) {
                AndroidUtilities.runOnUIThread(() -> getMessagesController().putUser((TLRPC.User) response, false));
            }
        });
        final TL_account.updateUsername updateUsername = new TL_account.updateUsername();
        updateUsername.username = "";
        getConnectionsManager().sendRequest(updateUsername, (response, error) -> {});
        deleteProfilePhotos();
    }

    private void deleteProfilePhotos() {
        final TLRPC.TL_photos_getUserPhotos req = new TLRPC.TL_photos_getUserPhotos();
        req.user_id = new TLRPC.TL_inputUserSelf();
        req.offset = 0;
        req.max_id = 0;
        req.limit = PAGE_SIZE;
        reqId = getConnectionsManager().sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            reqId = 0;
            if (finished) {
                return;
            }
            final ArrayList<TLRPC.InputPhoto> inputPhotos = new ArrayList<>();
            if (response instanceof TLRPC.photos_Photos) {
                final TLRPC.photos_Photos res = (TLRPC.photos_Photos) response;
                for (int i = 0; i < res.photos.size(); i++) {
                    final TLRPC.Photo photo = res.photos.get(i);
                    if (photo instanceof TLRPC.TL_photo) {
                        final TLRPC.TL_inputPhoto inputPhoto = new TLRPC.TL_inputPhoto();
                        inputPhoto.id = photo.id;
                        inputPhoto.access_hash = photo.access_hash;
                        inputPhoto.file_reference = photo.file_reference != null ? photo.file_reference : new byte[0];
                        inputPhotos.add(inputPhoto);
                    }
                }
            }
            // deleting every profile photo (the current avatar is among them) leaves no picture on the account
            if (!inputPhotos.isEmpty()) {
                final TLRPC.TL_photos_deletePhotos del = new TLRPC.TL_photos_deletePhotos();
                del.id = inputPhotos;
                getConnectionsManager().sendRequest(del, (r2, e2) -> {});
            }
            step = 1;
            next();
        }));
    }

    private void deleteContacts() {
        final TLRPC.TL_contacts_getContacts req = new TLRPC.TL_contacts_getContacts();
        reqId = getConnectionsManager().sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            reqId = 0;
            if (finished) {
                return;
            }
            final ArrayList<TLRPC.InputUser> ids = new ArrayList<>();
            if (response instanceof TLRPC.TL_contacts_contacts) {
                final TLRPC.TL_contacts_contacts res = (TLRPC.TL_contacts_contacts) response;
                getMessagesController().putUsers(res.users, false);
                for (int i = 0; i < res.users.size(); i++) {
                    final TLRPC.User user = res.users.get(i);
                    if (user != null) {
                        final TLRPC.TL_inputUser inputUser = new TLRPC.TL_inputUser();
                        inputUser.user_id = user.id;
                        inputUser.access_hash = user.access_hash;
                        ids.add(inputUser);
                    }
                }
            }
            if (!ids.isEmpty()) {
                final TLRPC.TL_contacts_deleteContacts del = new TLRPC.TL_contacts_deleteContacts();
                del.id = ids;
                getConnectionsManager().sendRequest(del, (r2, e2) -> {});
            }
            // and drop the imported-contacts mapping, so nothing is re-added from the phone book
            getConnectionsManager().sendRequest(new TLRPC.TL_contacts_resetSaved(), (r2, e2) -> {});
            step = 2;
            next();
        }));
    }

    private void listChats() {
        final TLRPC.TL_messages_getAllChats req = new TLRPC.TL_messages_getAllChats();
        req.except_ids.addAll(listedChats.keySet());
        reqId = getConnectionsManager().sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            reqId = 0;
            if (finished) {
                return;
            }
            chatListRequests++;
            boolean more = false;
            if (response instanceof TLRPC.messages_Chats) {
                final TLRPC.messages_Chats res = (TLRPC.messages_Chats) response;
                getMessagesController().putChats(res.chats, false);
                int added = 0;
                for (int i = 0; i < res.chats.size(); i++) {
                    final TLRPC.Chat listed = res.chats.get(i);
                    if (listed != null && listedChats.put(listed.id, listed) == null) {
                        added++;
                    }
                }
                more = res instanceof TLRPC.TL_messages_chatsSlice && added > 0 && listedChats.size() < res.count && chatListRequests < MAX_CHAT_LIST_REQUESTS;
            }
            if (!more) {
                listingChats = false;
                addListedChats();
            }
            next();
        }));
    }

    private void addListedChats() {
        final ArrayList<TLRPC.Dialog> dialogs = getMessagesController().getAllDialogs();
        for (int i = 0; i < dialogs.size(); i++) {
            final long dialogId = dialogs.get(i).id;
            if (DialogObject.isChatDialog(dialogId) && !listedChats.containsKey(-dialogId)) {
                final TLRPC.Chat listed = getMessagesController().getChat(-dialogId);
                if (listed != null) {
                    listedChats.put(listed.id, listed);
                }
            }
        }
        for (TLRPC.Chat listed : listedChats.values()) {
            // chats I've already left or been removed from, and ones out of reach, are nothing to leave
            if (listed instanceof TLRPC.TL_chatEmpty || listed instanceof TLRPC.TL_chatForbidden || listed instanceof TLRPC.TL_channelForbidden ||
                    listed.left || listed.kicked || ChatObject.isMonoForum(listed)) {
                continue;
            }
            addDialog(-listed.id);
        }
    }

    private void listDialogs() {
        final TLObject request;
        if (dialogListStep < 2) {
            final TLRPC.TL_messages_getPinnedDialogs req = new TLRPC.TL_messages_getPinnedDialogs();
            req.folder_id = dialogListStep;
            request = req;
        } else {
            final TLRPC.TL_messages_getDialogs req = new TLRPC.TL_messages_getDialogs();
            req.exclude_pinned = true;
            req.flags |= 2;
            req.folder_id = dialogListStep - 2;
            req.offset_id = dialogListOffsetId;
            req.offset_date = dialogListOffsetDate;
            req.offset_peer = dialogListOffsetPeer != null ? dialogListOffsetPeer : new TLRPC.TL_inputPeerEmpty();
            req.limit = PAGE_SIZE;
            request = req;
        }
        reqId = getConnectionsManager().sendRequest(request, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            reqId = 0;
            if (finished) {
                return;
            }
            dialogListRequests++;
            ArrayList<TLRPC.Dialog> dialogs = null;
            ArrayList<TLRPC.Message> messages = null;
            boolean slice = false;
            if (response instanceof TLRPC.messages_Dialogs) {
                final TLRPC.messages_Dialogs res = (TLRPC.messages_Dialogs) response;
                getMessagesController().putUsers(res.users, false);
                getMessagesController().putChats(res.chats, false);
                dialogs = res.dialogs;
                messages = res.messages;
                slice = res instanceof TLRPC.TL_messages_dialogsSlice;
            } else if (response instanceof TLRPC.TL_messages_peerDialogs) {
                final TLRPC.TL_messages_peerDialogs res = (TLRPC.TL_messages_peerDialogs) response;
                getMessagesController().putUsers(res.users, false);
                getMessagesController().putChats(res.chats, false);
                dialogs = res.dialogs;
            }
            if (dialogs != null) {
                for (int i = 0; i < dialogs.size(); i++) {
                    final TLRPC.Dialog dialog = dialogs.get(i);
                    if (!(dialog instanceof TLRPC.TL_dialogFolder) && dialog.peer != null && dialog.peer.user_id != 0) {
                        addDialog(dialog.peer.user_id);
                    }
                }
            }
            TLRPC.Message oldest = null;
            if (messages != null) {
                for (int i = 0; i < messages.size(); i++) {
                    final TLRPC.Message message = messages.get(i);
                    if (message != null && message.date != 0 && (oldest == null || message.date < oldest.date)) {
                        oldest = message;
                    }
                }
            }
            if (slice && oldest != null && (oldest.id != dialogListOffsetId || oldest.date != dialogListOffsetDate) && dialogListRequests < MAX_DIALOG_LIST_REQUESTS) {
                dialogListOffsetId = oldest.id;
                dialogListOffsetDate = oldest.date;
                dialogListOffsetPeer = getMessagesController().getInputPeer(MessageObject.getDialogId(oldest));
            } else {
                dialogListStep++;
                dialogListOffsetId = 0;
                dialogListOffsetDate = 0;
                dialogListOffsetPeer = null;
                if (dialogListStep > 3) {
                    listingDialogs = false;
                    final ArrayList<TLRPC.Dialog> loaded = getMessagesController().getAllDialogs();
                    for (int i = 0; i < loaded.size(); i++) {
                        if (DialogObject.isUserDialog(loaded.get(i).id)) {
                            addDialog(loaded.get(i).id);
                        }
                    }
                }
            }
            next();
        }));
    }

    private void nukeDialog(long dialogId) {
        if (DialogObject.isUserDialog(dialogId)) {
            deleteUserHistory(dialogId);
        } else {
            leaveChat(dialogId);
        }
    }

    private void deleteUserHistory(long dialogId) {
        final TLRPC.TL_messages_deleteHistory req = new TLRPC.TL_messages_deleteHistory();
        req.peer = getMessagesController().getInputPeer(dialogId);
        req.max_id = 0;
        // delete for the other side too, except Saved Messages (myself), where revoke doesn't apply
        req.revoke = dialogId != UserConfig.getInstance(currentAccount).getClientUserId();
        reqId = getConnectionsManager().sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            reqId = 0;
            if (finished) {
                return;
            }
            if (response instanceof TLRPC.TL_messages_affectedHistory) {
                final TLRPC.TL_messages_affectedHistory res = (TLRPC.TL_messages_affectedHistory) response;
                getMessagesController().processNewDifferenceParams(-1, res.pts, -1, res.pts_count);
                if (res.offset > 0) {
                    // the server deletes a slice at a time; keep going until this chat is empty
                    deleteUserHistory(dialogId);
                    return;
                }
            }
            nextDialog();
            next();
        }));
    }

    private void leaveChat(long dialogId) {
        final TLRPC.Chat chat = getMessagesController().getChat(-dialogId);
        if (chat == null) {
            nextDialog();
            next();
            return;
        }
        final TLObject request;
        if (ChatObject.isChannel(chat)) {
            final TLRPC.TL_channels_leaveChannel req = new TLRPC.TL_channels_leaveChannel();
            req.channel = MessagesController.getInputChannel(chat);
            request = req;
        } else {
            final TLRPC.TL_messages_deleteChatUser req = new TLRPC.TL_messages_deleteChatUser();
            req.chat_id = chat.id;
            req.user_id = new TLRPC.TL_inputUserSelf();
            req.revoke_history = true;
            request = req;
        }
        reqId = getConnectionsManager().sendRequest(request, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            reqId = 0;
            if (finished) {
                return;
            }
            if (response instanceof TLRPC.Updates) {
                getMessagesController().processUpdates((TLRPC.Updates) response, false);
            }
            nextDialog();
            next();
        }));
    }

    private void nextDialog() {
        dialogIndex++;
    }

    private void finish() {
        if (finished) {
            return;
        }
        finished = true;
        tasks.remove(currentAccount);
        running--;
        if (running <= 0) {
            running = 0;
            SharedConfig.clearWipePending();
        }
    }
}
