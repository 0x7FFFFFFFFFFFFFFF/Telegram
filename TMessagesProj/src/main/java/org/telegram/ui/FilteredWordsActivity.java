package org.telegram.ui;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessageFilter;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;

// this fork: Settings > Privacy and Security > Filtered Words. Lists the words MessageFilter hides, lets you add
// one (also reachable by long-pressing a message or a chat and choosing Filter) and edit or delete each. See
// MessageFilter for what the words hide.
public class FilteredWordsActivity extends UniversalFragment implements NotificationCenter.NotificationCenterDelegate {

    private static final int BUTTON_ADD = -1;

    @Override
    public boolean onFragmentCreate() {
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.filteredWordsChanged);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.filteredWordsChanged);
        super.onFragmentDestroy();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.filteredWordsChanged && listView != null) {
            listView.adapter.update(true);
        }
    }

    @Override
    protected CharSequence getTitle() {
        return getString(R.string.FilteredWords);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asButton(BUTTON_ADD, R.drawable.msg_add, getString(R.string.FilteredWordsAdd)));
        final ArrayList<String> words = MessageFilter.getWords();
        if (words.isEmpty()) {
            items.add(UItem.asShadow(getString(R.string.FilteredWordsEmpty)));
            return;
        }
        for (int i = 0; i < words.size(); i++) {
            final UItem item = UItem.asButton(i + 1, words.get(i));
            item.object = words.get(i);
            items.add(item);
        }
        items.add(UItem.asShadow(getString(R.string.FilteredWordsInfo)));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == BUTTON_ADD) {
            showEditDialog(this, null, null);
        } else if (item.object instanceof String) {
            showEditDialog(this, (String) item.object, (String) item.object);
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    // Shown by the Add button, by tapping a word here, and by the Filter action on a message or chat. existing is
    // the word being edited (null when adding a new one); prefill is the text the field starts with.
    public static void showEditDialog(BaseFragment fragment, String existing, String prefill) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        final Context context = fragment.getParentActivity();
        final AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(getString(existing == null ? R.string.FilteredWordAddTitle : R.string.FilteredWordEditTitle));

        final EditTextBoldCursor edit = new EditTextBoldCursor(context);
        edit.setTextSize(18);
        edit.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        edit.setCursorColor(Theme.getColor(Theme.key_dialogTextBlack));
        edit.setCursorSize(AndroidUtilities.dp(20));
        edit.setCursorWidth(1.5f);
        edit.setHintText(getString(R.string.FilteredWordHint));
        edit.setHintColor(Theme.getColor(Theme.key_dialogTextHint));
        edit.setBackground(null);
        edit.setSingleLine(true);
        if (prefill != null) {
            edit.setText(prefill);
            edit.setSelection(edit.getText().length());
        }

        final LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(4), AndroidUtilities.dp(24), 0);
        container.addView(edit, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        builder.setView(container);

        builder.setPositiveButton(getString(R.string.Save), (dialog, which) -> {
            final String text = edit.getText() == null ? "" : edit.getText().toString().trim();
            // editing an existing word replaces it; a blank save just removes the old one
            if (existing != null && !existing.equalsIgnoreCase(text)) {
                MessageFilter.remove(existing);
            }
            if (text.length() > 0) {
                MessageFilter.add(text);
            }
        });
        if (existing != null) {
            builder.setNeutralButton(getString(R.string.Delete), (dialog, which) -> MessageFilter.remove(existing));
        }
        builder.setNegativeButton(getString(R.string.Cancel), null);

        final AlertDialog dialog = builder.create();
        dialog.setOnShowListener(d -> {
            edit.requestFocus();
            AndroidUtilities.showKeyboard(edit);
        });
        dialog.show();
    }
}
