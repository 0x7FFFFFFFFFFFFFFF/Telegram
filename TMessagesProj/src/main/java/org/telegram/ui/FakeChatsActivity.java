package org.telegram.ui;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.FakeChats;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.INavigationLayout;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.DialogCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;

// this fork: the chat list after the fake passcode (SharedConfig.fakeModeActive). LaunchActivity shows it instead of
// every real screen; it lists only FakeChats. The lock button locks the app, and the real passcode brings the real
// chats back.
public class FakeChatsActivity extends BaseFragment {

    private static final int LOCK = 1;

    private ArrayList<FakeChats.Chat> chats = new ArrayList<>();
    private ListAdapter adapter;
    private ActionBarMenuItem lockItem;
    private final Runnable onChatsChanged = this::updateChats;

    @Override
    public boolean onFragmentCreate() {
        chats = FakeChats.getChats();
        FakeChats.onChanged = onChatsChanged;
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        if (FakeChats.onChanged == onChatsChanged) {
            FakeChats.onChanged = null;
        }
        super.onFragmentDestroy();
    }

    @Override
    public View createView(Context context) {
        actionBar.setTitle(getString(R.string.AppName));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == LOCK) {
                    lock();
                }
            }
        });
        lockItem = actionBar.createMenu().addItem(LOCK, R.drawable.outline_header_lock_24);
        lockItem.setContentDescription(getString(R.string.AccDescrPasscodeLock));

        FrameLayout frameLayout = new FrameLayout(context);
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        RecyclerListView listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setAdapter(adapter = new ListAdapter(context));
        listView.setOnItemClickListener((view, position) -> {
            if (position >= 0 && position < chats.size()) {
                openChat(chats.get(position));
            }
        });
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        return fragmentView = frameLayout;
    }

    @Override
    public void onResume() {
        super.onResume();
        updateChats();
    }

    private void updateChats() {
        chats = FakeChats.getChats();
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    private void openChat(FakeChats.Chat chat) {
        chat.unreadCount = 0;
        updateChats();
        final FakeChatActivity fragment = new FakeChatActivity(chat.id);
        if (getParentActivity() instanceof LaunchActivity) {
            final LaunchActivity launchActivity = (LaunchActivity) getParentActivity();
            if (launchActivity.isTabletRightPanel(launchActivity.getRightActionBarLayout())) {
                // in the right panel, where real chats open
                launchActivity.presentRightPanelPage(new INavigationLayout.NavigationParams(fragment).setNoAnimation(true).forceRightLayout());
                return;
            }
        }
        presentFragment(fragment);
    }

    // the same as the lock button of the real chat list
    private void lock() {
        if (!(getParentActivity() instanceof LaunchActivity) || SharedConfig.passcodeHash.length() == 0) {
            return;
        }
        SharedConfig.appLocked = true;
        SharedConfig.saveConfig();
        final int[] position = new int[2];
        lockItem.getLocationInWindow(position);
        ((LaunchActivity) getParentActivity()).showPasscodeActivity(false, true, position[0] + lockItem.getMeasuredWidth() / 2, position[1] + lockItem.getMeasuredHeight() / 2, () -> lockItem.setAlpha(1.0f), () -> lockItem.setAlpha(0.0f));
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {

        private final Context context;

        ListAdapter(Context context) {
            this.context = context;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return true;
        }

        @Override
        public int getItemCount() {
            return chats.size();
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            final DialogCell cell = new DialogCell(null, context, false, false);
            cell.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(cell);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            final FakeChats.Chat chat = chats.get(position);
            final FakeChats.Message last = chat.getLastMessage();
            final DialogCell.CustomDialog dialog = new DialogCell.CustomDialog();
            dialog.id = chat.id;
            dialog.name = chat.name;
            dialog.message = last.text;
            dialog.date = last.date;
            dialog.unread_count = chat.unreadCount;
            dialog.muted = chat.muted;
            dialog.sent = last.out ? DialogCell.SENT_STATE_READ : DialogCell.SENT_STATE_NOTHING;
            final DialogCell cell = (DialogCell) holder.itemView;
            cell.useSeparator = position != getItemCount() - 1;
            cell.setDialog(dialog);
        }
    }
}
