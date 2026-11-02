package org.telegram.ui;

import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.os.Bundle;
import android.view.View;

import org.telegram.messenger.DeleteAllMessagesTask;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.INavigationLayout;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

// What DeleteAllMessagesTask couldn't delete: the chats it couldn't go through, then the messages, grouped by
// why they stayed. Tapping one opens its chat at that message.
public class DeleteAllFailedActivity extends UniversalFragment {

    private final CharSequence result;
    private final ArrayList<DeleteAllMessagesTask.Failure> failures;

    private final ArrayList<UItem> chatItems = new ArrayList<>();
    // by the server's error, in the order they came up; "" for service messages that weren't sent for deleting
    private final LinkedHashMap<String, ArrayList<MessageObject>> messagesByError = new LinkedHashMap<>();

    // opens over whatever is in front; in tablet two-pane mode as a new right panel page, so a chat opened from
    // the list comes back to it
    public static boolean open(int account, CharSequence result, ArrayList<DeleteAllMessagesTask.Failure> failures) {
        final LaunchActivity launchActivity = LaunchActivity.instance;
        if (launchActivity == null || launchActivity.isFinishing()) {
            return false;
        }
        final DeleteAllFailedActivity fragment = new DeleteAllFailedActivity(result, failures);
        fragment.setCurrentAccount(account);
        if (launchActivity.isTabletRightPanel(launchActivity.getRightActionBarLayout())) {
            return launchActivity.presentRightPanelPage(new INavigationLayout.NavigationParams(fragment).setNoAnimation(true).forceRightLayout());
        }
        final BaseFragment lastFragment = LaunchActivity.getSafeLastFragment();
        return lastFragment != null && lastFragment.presentFragment(fragment);
    }

    private DeleteAllFailedActivity(CharSequence result, ArrayList<DeleteAllMessagesTask.Failure> failures) {
        this.result = result;
        this.failures = failures;
    }

    @Override
    public boolean onFragmentCreate() {
        for (int i = 0; i < failures.size(); ++i) {
            final DeleteAllMessagesTask.Failure failure = failures.get(i);
            if (failure.message == null) {
                final TLObject chat = DialogObject.isUserDialog(failure.dialogId) ? getMessagesController().getUser(failure.dialogId) : getMessagesController().getChat(-failure.dialogId);
                if (chat != null) {
                    final UItem item = UItem.asProfileCell(chat);
                    item.subtext = failure.error;
                    chatItems.add(item);
                }
                continue;
            }
            final String error = failure.error != null ? failure.error : "";
            ArrayList<MessageObject> messages = messagesByError.get(error);
            if (messages == null) {
                messages = new ArrayList<>();
                messagesByError.put(error, messages);
            }
            messages.add(new MessageObject(currentAccount, failure.message, false, false));
        }
        return super.onFragmentCreate();
    }

    @Override
    protected CharSequence getTitle() {
        return getString(R.string.DeleteAllFailedTitle);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asShadow(formatString(R.string.DeleteAllFailedResult, result)));
        if (!chatItems.isEmpty()) {
            items.add(UItem.asHeader(getString(R.string.DeleteAllFailedChats)));
            items.addAll(chatItems);
            items.add(UItem.asShadow(getString(R.string.DeleteAllFailedChatsInfo)));
        }
        for (Map.Entry<String, ArrayList<MessageObject>> entry : messagesByError.entrySet()) {
            final boolean service = entry.getKey().isEmpty();
            items.add(UItem.asHeader(getString(service ? R.string.DeleteAllFailedService : R.string.DeleteAllFailedRefused)));
            for (MessageObject messageObject : entry.getValue()) {
                items.add(UItem.asSearchMessage(messageObject));
            }
            items.add(UItem.asShadow(service ? getString(R.string.DeleteAllFailedServiceInfo) : formatString(R.string.DeleteAllFailedRefusedInfo, entry.getKey())));
        }
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.object instanceof MessageObject) {
            final MessageObject messageObject = (MessageObject) item.object;
            openChat(messageObject.getDialogId(), messageObject.getId());
        } else if (item.object instanceof TLRPC.User) {
            openChat(((TLRPC.User) item.object).id, 0);
        } else if (item.object instanceof TLRPC.Chat) {
            openChat(-((TLRPC.Chat) item.object).id, 0);
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    private void openChat(long dialogId, int messageId) {
        final Bundle args = new Bundle();
        if (DialogObject.isUserDialog(dialogId)) {
            args.putLong("user_id", dialogId);
        } else {
            long did = dialogId;
            final TLRPC.Chat chat = getMessagesController().getChat(-did);
            // a message of a basic group that became a supergroup opens in the supergroup, below its own history
            if (messageId != 0 && chat != null && chat.migrated_to != null) {
                args.putLong("migrated_to", did);
                did = -chat.migrated_to.channel_id;
            }
            args.putLong("chat_id", -did);
        }
        if (messageId != 0) {
            args.putInt("message_id", messageId);
        }
        if (getMessagesController().checkCanOpenChat(args, this)) {
            presentFragment(new ChatActivity(args));
        }
    }
}
