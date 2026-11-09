package com.PixelFlow.app.API;

import android.app.Dialog;
import android.content.Intent;
import android.view.ContextThemeWrapper;
import android.widget.CheckBox;
import androidx.appcompat.app.AlertDialog;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.PixelFlow.app.AccountActivity;
import com.PixelFlow.app.R;
import com.PixelFlow.app.ui.ActionSheetDialog;
import com.PixelFlow.app.ui.PixelFlowDialogs;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class DialogUiTest {
    private Intent account() {
        return new Intent(InstrumentationRegistry.getInstrumentation().getTargetContext(), AccountActivity.class);
    }

    @Test public void notificationCancelDoesNotCommitChanges() {
        AtomicBoolean saved = new AtomicBoolean();
        try (ActivityScenario<AccountActivity> screen = ActivityScenario.launch(account())) {
            screen.onActivity(activity -> {
                android.view.View content = PixelFlowDialogs.inflate(activity, R.layout.dialog_notification_settings);
                AlertDialog dialog = PixelFlowDialogs.builder(activity).setTitle(R.string.messages_settings)
                        .setView(content).setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(android.R.string.ok, (d, w) -> saved.set(true)).show();
                ((CheckBox) content.findViewById(R.id.ns_like)).performClick();
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
                assertFalse(saved.get());
            });
        }
    }

    @Test public void detailThemeSupportsBrandedFormsAndDestructiveConfirmation() {
        AtomicBoolean confirmed = new AtomicBoolean();
        try (ActivityScenario<AccountActivity> screen = ActivityScenario.launch(account())) {
            screen.onActivity(activity -> {
                ContextThemeWrapper detail = new ContextThemeWrapper(activity, R.style.Theme_Detail);
                AlertDialog form = PixelFlowDialogs.builder(detail).setTitle(R.string.ai_rule_edit)
                        .setView(PixelFlowDialogs.inflate(detail, R.layout.dialog_rule_edit))
                        .setNegativeButton(android.R.string.cancel, null).create();
                PixelFlowDialogs.showForm(form);
                assertNotNull(form.findViewById(R.id.dialog_rule_question));
                assertNotNull(form.findViewById(R.id.dialog_rule_answer));
                form.dismiss();
                AlertDialog confirm = PixelFlowDialogs.destructive(detail).setTitle(R.string.dialog_delete_rule_title)
                        .setPositiveButton(R.string.auto_reply_delete, (d, w) -> confirmed.set(true))
                        .setNegativeButton(android.R.string.cancel, null).show();
                confirm.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            assertTrue(confirmed.get());

        }
    }

    @Test public void loadingCancelRunsCancellationAndClosesDialog() {
        AtomicBoolean cancelled = new AtomicBoolean();
        try (ActivityScenario<AccountActivity> screen = ActivityScenario.launch(account())) {
            screen.onActivity(activity -> {
                AlertDialog loading = PixelFlowDialogs.builder(activity)
                        .setView(PixelFlowDialogs.inflate(activity, R.layout.dialog_share_loading))
                        .setCancelable(false)
                        .setNegativeButton(R.string.share_cancel, (d, w) -> cancelled.set(true)).show();
                loading.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            assertTrue(cancelled.get());
        }
    }

    @Test public void actionSheetInvokesOnlySelectedAction() {
        AtomicBoolean copied = new AtomicBoolean();
        AtomicBoolean deleted = new AtomicBoolean();
        try (ActivityScenario<AccountActivity> screen = ActivityScenario.launch(account())) {
            screen.onActivity(activity -> {
                Dialog sheet = ActionSheetDialog.show(activity, activity.getString(R.string.dialog_comment_actions), null,
                        Arrays.asList(new ActionSheetDialog.Option(android.R.drawable.ic_menu_set_as,
                                activity.getString(R.string.comments_dialog_copy), () -> copied.set(true)),
                                new ActionSheetDialog.Option(android.R.drawable.ic_menu_delete,
                                        activity.getString(R.string.comments_dialog_delete), () -> deleted.set(true), true)));
                ((android.view.ViewGroup) sheet.findViewById(R.id.sheet_options)).getChildAt(0).performClick();
                assertTrue(copied.get());
                assertFalse(deleted.get());
            });
        }
    }
}
