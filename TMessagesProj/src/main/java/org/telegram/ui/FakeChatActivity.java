package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FakeChats;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.ChatActionCell;
import org.telegram.ui.Cells.ChatMessageCell;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.SizeNotifierFrameLayout;

import java.util.ArrayList;
import java.util.Calendar;

// this fork: one of the FakeChats, drawn with the real message cells on the chat wallpaper, the way the theme preview
// draws its sample messages. In a private chat, messages can be "sent" (they only go into memory); a channel has
// the mute button instead.
public class FakeChatActivity extends BaseFragment {

    private static final int TYPE_MESSAGE = 0, TYPE_DATE = 1;
    // view counts for a channel's posts, on top of a share of its subscribers
    private static final int[] CHANNEL_VIEWS = {12400, 8700, 15300, 9900, 21100, 7400, 11800};

    private final int chatId;
    private FakeChats.Chat chat;
    // newest first for the bottom-up list, a day's date after its messages
    private final ArrayList<MessageObject> messageObjects = new ArrayList<>();
    private RecyclerListView listView;
    private MessagesAdapter adapter;
    private EditTextBoldCursor messageEditText;

    public FakeChatActivity(int chatId) {
        this.chatId = chatId;
    }

    @Override
    public boolean onFragmentCreate() {
        chat = FakeChats.getChat(chatId);
        return chat != null && super.onFragmentCreate();
    }

    @Override
    public View createView(Context context) {
        Theme.createChatResources(context, false);
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });
        actionBar.addView(createHeader(context), 0, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP | Gravity.LEFT, 56, 0, 40, 0));

        SizeNotifierFrameLayout contentView = new SizeNotifierFrameLayout(context);
        contentView.setBackgroundImage(Theme.getCachedWallpaper(), Theme.isWallpaperMotion());
        fragmentView = contentView;

        LinearLayout linearLayout = new LinearLayout(context);
        linearLayout.setOrientation(LinearLayout.VERTICAL);
        contentView.addView(linearLayout, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, true));
        listView.setClipToPadding(false);
        listView.setPadding(0, dp(4), 0, dp(4));
        listView.setAdapter(adapter = new MessagesAdapter(context));
        linearLayout.addView(listView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));
        linearLayout.addView(chat.channel ? createMuteBar(context) : createInputBar(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        updateMessages();
        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        AndroidUtilities.requestAdjustResize(getParentActivity(), classGuid);
    }

    @Override
    public void onPause() {
        super.onPause();
        if (messageEditText != null) {
            AndroidUtilities.hideKeyboard(messageEditText);
        }
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        AndroidUtilities.removeAdjustResize(getParentActivity(), classGuid);
    }

    // avatar, name and "last seen" or subscribers, like a chat's header
    private View createHeader(Context context) {
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        BackupImageView avatarView = new BackupImageView(context);
        avatarView.setRoundRadius(dp(21));
        AvatarDrawable avatarDrawable = new AvatarDrawable();
        avatarDrawable.setInfo(chat.id, chat.name, null);
        avatarView.setImageDrawable(avatarDrawable);
        header.addView(avatarView, LayoutHelper.createLinear(42, 42, Gravity.CENTER_VERTICAL, 0, 0, 10, 0));

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        header.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));

        TextView titleView = new TextView(context);
        titleView.setTextColor(Theme.getColor(Theme.key_actionBarDefaultTitle));
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titleView.setText(chat.name);
        texts.addView(titleView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

        TextView subtitleView = new TextView(context);
        subtitleView.setTextColor(Theme.getColor(Theme.key_actionBarDefaultSubtitle));
        subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        subtitleView.setSingleLine(true);
        subtitleView.setEllipsize(TextUtils.TruncateAt.END);
        subtitleView.setText(chat.channel ? LocaleController.formatPluralStringComma("Subscribers", chat.subscribers) : getString(R.string.Lately));
        texts.addView(subtitleView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
        return header;
    }

    private View createInputBar(Context context) {
        FrameLayout bar = new FrameLayout(context);
        bar.setBackgroundColor(Theme.getColor(Theme.key_chat_messagePanelBackground));

        messageEditText = new EditTextBoldCursor(context);
        messageEditText.setHint(getString(R.string.TypeMessage));
        messageEditText.setHintTextColor(Theme.getColor(Theme.key_chat_messagePanelHint));
        messageEditText.setTextColor(Theme.getColor(Theme.key_chat_messagePanelText));
        messageEditText.setCursorColor(Theme.getColor(Theme.key_chat_messagePanelText));
        messageEditText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        messageEditText.setBackground(null);
        messageEditText.setMaxLines(6);
        messageEditText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        messageEditText.setPadding(dp(16), dp(12), dp(8), dp(12));
        bar.addView(messageEditText, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM, 0, 0, 48, 0));

        ImageView sendButton = new ImageView(context);
        sendButton.setImageResource(R.drawable.ic_send);
        sendButton.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_chat_messagePanelSend), PorterDuff.Mode.MULTIPLY));
        sendButton.setScaleType(ImageView.ScaleType.CENTER);
        sendButton.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 1));
        sendButton.setContentDescription(getString(R.string.Send));
        sendButton.setOnClickListener(v -> send());
        bar.addView(sendButton, LayoutHelper.createFrame(48, 48, Gravity.BOTTOM | Gravity.RIGHT));
        return bar;
    }

    private View createMuteBar(Context context) {
        TextView muteButton = new TextView(context);
        muteButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        muteButton.setTypeface(AndroidUtilities.bold());
        muteButton.setTextColor(Theme.getColor(Theme.key_chat_fieldOverlayText));
        muteButton.setGravity(Gravity.CENTER);
        muteButton.setBackground(Theme.createSelectorWithBackgroundDrawable(Theme.getColor(Theme.key_chat_messagePanelBackground), Theme.getColor(Theme.key_listSelector)));
        muteButton.setMinHeight(dp(48));
        muteButton.setText(getString(chat.muted ? R.string.ChannelUnmute : R.string.ChannelMute));
        muteButton.setOnClickListener(v -> {
            chat.muted = !chat.muted;
            muteButton.setText(getString(chat.muted ? R.string.ChannelUnmute : R.string.ChannelMute));
        });
        return muteButton;
    }

    private void send() {
        final String text = messageEditText.getText().toString().trim();
        if (text.isEmpty()) {
            return;
        }
        messageEditText.setText("");
        FakeChats.send(chat, text);
        updateMessages();
        listView.scrollToPosition(0);
    }

    private void updateMessages() {
        messageObjects.clear();
        final ArrayList<FakeChats.Message> messages = chat.messages;
        for (int i = messages.size() - 1; i >= 0; i--) {
            final FakeChats.Message message = messages.get(i);
            messageObjects.add(createMessageObject(message, i));
            if (i == 0 || !isSameDay(messages.get(i - 1).date, message.date)) {
                messageObjects.add(createDateObject(message.date));
            }
        }
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    private MessageObject createMessageObject(FakeChats.Message fakeMessage, int index) {
        final TLRPC.TL_message message = new TLRPC.TL_message();
        message.message = fakeMessage.text;
        message.date = fakeMessage.date;
        message.dialog_id = 1;
        message.flags = 259;
        message.id = index + 1;
        message.media = new TLRPC.TL_messageMediaEmpty();
        message.out = fakeMessage.out;
        message.from_id = new TLRPC.TL_peerUser();
        message.peer_id = new TLRPC.TL_peerUser();
        if (fakeMessage.out) {
            message.from_id.user_id = getUserConfig().getClientUserId();
        } else {
            message.peer_id.user_id = getUserConfig().getClientUserId();
        }
        if (chat.channel) {
            message.flags |= TLRPC.MESSAGE_FLAG_HAS_VIEWS;
            message.views = CHANNEL_VIEWS[index % CHANNEL_VIEWS.length] + chat.subscribers / 40;
        }
        return new MessageObject(currentAccount, message, true, false);
    }

    // a day's date over its messages, as in a chat
    private MessageObject createDateObject(int date) {
        final TLRPC.TL_message message = new TLRPC.TL_message();
        message.message = LocaleController.formatDateChat(date);
        message.id = 0;
        message.date = date;
        final MessageObject dateObject = new MessageObject(currentAccount, message, false, false);
        dateObject.type = 10;
        dateObject.contentType = 1;
        dateObject.isDateObject = true;
        return dateObject;
    }

    private static boolean isSameDay(int date1, int date2) {
        final Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(date1 * 1000L);
        final int year = calendar.get(Calendar.YEAR), day = calendar.get(Calendar.DAY_OF_YEAR);
        calendar.setTimeInMillis(date2 * 1000L);
        return year == calendar.get(Calendar.YEAR) && day == calendar.get(Calendar.DAY_OF_YEAR);
    }

    private class MessagesAdapter extends RecyclerListView.SelectionAdapter {

        private final Context context;

        MessagesAdapter(Context context) {
            this.context = context;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return false;
        }

        @Override
        public int getItemCount() {
            return messageObjects.size();
        }

        @Override
        public int getItemViewType(int position) {
            return messageObjects.get(position).isDateObject ? TYPE_DATE : TYPE_MESSAGE;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view;
            if (viewType == TYPE_DATE) {
                view = new ChatActionCell(context);
            } else {
                ChatMessageCell cell = new ChatMessageCell(context, currentAccount);
                cell.setDelegate(new ChatMessageCell.ChatMessageCellDelegate() {});
                view = cell;
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            final MessageObject messageObject = messageObjects.get(position);
            if (holder.itemView instanceof ChatActionCell) {
                ((ChatActionCell) holder.itemView).setMessageObject(messageObject);
                return;
            }
            // bubbles of one sender within five minutes are drawn joined; position - 1 is newer, position + 1 older
            final MessageObject newer = position > 0 ? messageObjects.get(position - 1) : null;
            final MessageObject older = position + 1 < messageObjects.size() ? messageObjects.get(position + 1) : null;
            final ChatMessageCell cell = (ChatMessageCell) holder.itemView;
            cell.isChat = false;
            cell.setFullyDraw(true);
            cell.setMessageObject(messageObject, null, isJoined(messageObject, newer), isJoined(messageObject, older), false);
        }

        private boolean isJoined(MessageObject messageObject, MessageObject other) {
            return other != null && !other.isDateObject && other.isOutOwner() == messageObject.isOutOwner() && Math.abs(other.messageOwner.date - messageObject.messageOwner.date) <= 5 * 60;
        }
    }
}
