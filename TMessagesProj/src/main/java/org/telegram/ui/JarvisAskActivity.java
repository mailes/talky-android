package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.text.InputType;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.jarvis.JarvisLocalCredential;
import org.telegram.messenger.jarvis.JarvisLocalKeyStore;
import org.telegram.messenger.jarvis.JarvisLocalModelClient;
import org.telegram.messenger.jarvis.JarvisManageClient;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.LayoutHelper;

/**
 * In-chat local answer. Opened by /ask in a group or channel; it reads the
 * server-scoped recent excerpts for THAT chat, sends them only to the
 * device-local provider, and shows the server-issued source IDs. A citation
 * is clickable only when it is one of those IDs; model-invented IDs stay text.
 */
public class JarvisAskActivity extends BaseFragment {

    private final long dialogId;
    private final long peerId;
    private String question;
    private long userId;
    private String origin;
    private JarvisLocalKeyStore keyStore;
    private ScrollView scrollView;
    private LinearLayout content;
    private boolean loading;
    private boolean cancelled;
    private boolean didRequest;

    public JarvisAskActivity(long dialogId, String question) {
        this.dialogId = dialogId;
        this.peerId = -dialogId;
        this.question = question == null ? "" : question.trim();
    }

    @Override
    public View createView(Context context) {
        setHasOwnBackground(true);

        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle(LocaleController.getString(R.string.JarvisAsk));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        fragmentView = new FrameLayout(context);
        FrameLayout frameLayout = (FrameLayout) fragmentView;
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray, getResourceProvider()));
        scrollView = new ScrollView(context);
        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray, getResourceProvider()));
        scrollView.addView(content, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        frameLayout.addView(scrollView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        userId = getUserConfig().getClientUserId();
        origin = getMessagesController().jarvisManageOrigin;
        try {
            keyStore = new JarvisLocalKeyStore(ApplicationLoader.applicationContext, userId);
        } catch (Exception ignored) {
            keyStore = null;
        }
        if (TextUtils.isEmpty(question)) {
            presentQuestionDialog();
            rebuild();
        } else {
            loadAnswer();
        }
        return fragmentView;
    }

    private String t(String en, String zh) {
        java.util.Locale locale = LocaleController.getInstance().getCurrentLocale();
        return locale != null && locale.getLanguage().startsWith("zh") ? zh : en;
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        // A request may still be in flight: drop the late UI update so it never
        // builds cells against a destroyed parent activity.
        cancelled = true;
        loading = false;
        scrollView = null;
        content = null;
    }

    private boolean stillCurrent() {
        return getUserConfig().getClientUserId() == userId;
    }

    private void runOnUIThreadIfCurrent(Runnable action) {
        AndroidUtilities.runOnUIThread(() -> {
            if (stillCurrent()) {
                action.run();
            }
        });
    }

    private void rebuild() {
        if (content == null || getParentActivity() == null) {
            return;
        }
        content.removeAllViews();
        if (loading) {
            addInfo(t("Reading recent messages from this chat…", "正在读取这个会话的最近消息…"));
            addAction(t("Cancel", "取消"), false, v -> {
                cancelled = true;
                loading = false;
                rebuild();
            });
            return;
        }
        if (TextUtils.isEmpty(question)) {
            addInfo(t("Ask a question about this chat. Jarvis will use the chat's recent excerpts as evidence.", "就这个会话提问。Jarvis 会把最近的片段作为依据。"));
            addAction(t("Ask a question", "提问"), false, v -> presentQuestionDialog());
            return;
        }
        addHeader(t("Question", "问题"), question);
        addInfo(t("Local mode: the answer runs on this device. Only the server-scoped excerpts are sent to your provider.", "本机模式：答案在这台设备上生成，只把服务端限定范围内的片段发往你的供应商。"));
        addAction(t("Ask another question", "换一个问题"), false, v -> presentQuestionDialog());
    }

    private void loadAnswer() {
        if (didRequest) {
            return;
        }
        didRequest = true;
        cancelled = false;
        loading = true;
        rebuild();
        if (TextUtils.isEmpty(origin)) {
            fail(t("Jarvis management is not configured on this server.", "此服务器没有配置 Jarvis 管理地址。"), false);
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            JarvisLocalCredential credential;
            try {
                credential = keyStore != null ? keyStore.load() : null;
            } catch (Exception ignored) {
                credential = null;
            }
            if (credential == null) {
                runOnUIThreadIfCurrent(() -> fail(t("This device has no local key. Add one in Jarvis settings, then ask again.", "这台设备没有本机 Key。请先在 Jarvis 设置中添加，然后再提问。"), true));
                return;
            }
            final JarvisLocalCredential finalCredential = credential;
            getConnectionsManager().requestJarvisSessionTicket(ticket -> {
                if (ticket == null || ticket.isEmpty()) {
                    runOnUIThreadIfCurrent(() -> fail(t("Your session expired. Sign in again.", "登录状态已失效，请重新登录。"), false));
                    return;
                }
                Utilities.globalQueue.postRunnable(() -> {
                    try {
                        JarvisManageClient client = new JarvisManageClient(origin);
                        JSONObject context = client.context(ticket, peerId, "");
                        JSONArray sources = context.optJSONArray("sources");
                        if (sources == null || sources.length() == 0) {
                            runOnUIThreadIfCurrent(() -> fail(t("No readable messages were found in this chat. Jarvis only reads chats you authorize.", "这个会话里没有可读消息。Jarvis 只读取你授权的会话。"), true));
                            return;
                        }
                        StringBuilder prompt = new StringBuilder();
                        for (int i = 0; i < sources.length(); i++) {
                            JSONObject source = sources.optJSONObject(i);
                            if (source == null) {
                                continue;
                            }
                            String sourceId = source.optString("source_id", "");
                            long sourcePeer = source.optLong("peer_id", 0);
                            long messageId = source.optLong("message_id", 0);
                            if (sourcePeer != peerId || messageId <= 0 || !sourceId.equals("channel:" + peerId + ":" + messageId)) {
                                continue;
                            }
                            prompt.append("[").append(sourceId).append("]");
                            if (source.optBoolean("truncated", false)) {
                                prompt.append(" (excerpt)");
                            }
                            prompt.append(" ").append(source.optString("text", "")).append("\n");
                        }
                        if (prompt.length() == 0) {
                            runOnUIThreadIfCurrent(() -> fail(t("No readable messages were found in this chat.", "这个会话里没有可读消息。"), true));
                            return;
                        }
                        String answer = new JarvisLocalModelClient().generate(finalCredential, prompt.toString(), question);
                        runOnUIThreadIfCurrent(() -> showAnswer(answer, context));
                    } catch (JarvisManageClient.ManageException error) {
                        runOnUIThreadIfCurrent(() -> {
                            if (error.failure == JarvisManageClient.Failure.FORBIDDEN) {
                                fail(t("This chat is not authorized. Open Jarvis settings to authorize it, then ask again.", "这个会话还没有授权。请打开 Jarvis 设置完成授权后再提问。"), true);
                            } else {
                                fail(message(error.failure), false);
                            }
                        });
                    } catch (JarvisLocalModelClient.ModelException error) {
                        runOnUIThreadIfCurrent(() -> fail(localMessage(error.failure), false));
                    } catch (Exception ignored) {
                        runOnUIThreadIfCurrent(() -> fail(t("The answer could not be generated. Check your connection and try again.", "无法生成答案，请检查网络后重试。"), false));
                    }
                });
            });
        });
    }

    private void showAnswer(String answer, JSONObject context) {
        if (cancelled || content == null || getParentActivity() == null) {
            return;
        }
        loading = false;
        content.removeAllViews();
        addHeader(t("Answer", "答案"), null);
        TextView answerView = new TextView(getParentActivity());
        answerView.setText(answer);
        answerView.setTextSize(16);
        answerView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, getResourceProvider()));
        answerView.setPadding(dp(21), dp(8), dp(21), dp(12));
        answerView.setTextIsSelectable(true);
        answerView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite, getResourceProvider()));
        content.addView(answerView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        JSONArray sources = context.optJSONArray("sources");
        if (sources != null && sources.length() > 0) {
            addHeader(t("Sources", "来源"), null);
            for (int i = 0; i < sources.length(); i++) {
                JSONObject source = sources.optJSONObject(i);
                if (source == null) {
                    continue;
                }
                long messageId = source.optLong("message_id", 0);
                String sourceId = source.optString("source_id", "");
                String snippet = source.optString("text", "").replace('\n', ' ').trim();
                if (snippet.length() > 90) {
                    snippet = snippet.substring(0, 90) + "…";
                }
                TextSettingsCell cell = addValue(snippet, sourceId, i != sources.length() - 1, v -> {
                    if (messageId > 0) {
                        presentFragment(ChatActivity.of(dialogId, (int) messageId));
                    }
                });
                cell.getTextView().setSingleLine(true);
            }
            addInfo(t("Only the server-issued source IDs above are clickable; a citation the model invents is not.", "只有上面由服务端签发的来源 ID 可以点击；模型自己编造的引用不会变成链接。"));
        }
        addAction(t("Ask another question", "换一个问题"), false, v -> presentQuestionDialog());
    }

    private void fail(String message, boolean showSettings) {
        if (cancelled || content == null || getParentActivity() == null) {
            return;
        }
        loading = false;
        content.removeAllViews();
        addInfo(message);
        if (showSettings) {
            addAction(t("Open Jarvis settings", "打开 Jarvis 设置"), false, v -> presentFragment(new JarvisSettingsActivity(true)));
        }
        addAction(t("Try again", "重试"), false, v -> {
            didRequest = false;
            loadAnswer();
        });
    }

    private String message(JarvisManageClient.Failure failure) {
        switch (failure) {
            case AUTHENTICATION_REQUIRED:
                return t("Your session expired. Sign in again.", "登录状态已失效，请重新登录。");
            case FORBIDDEN:
                return t("This chat is not authorized for Jarvis.", "这个会话没有授权给 Jarvis。");
            case HOSTED_KEY_REQUIRED:
                return t("Add a hosted key in Jarvis settings first.", "请先在 Jarvis 设置中添加托管 Key。");
            case OPERATION_FAILED:
                return t("The provider rejected the request.", "服务商拒绝了请求。");
            case INVALID_CONFIGURATION:
                return t("Jarvis settings are not configured correctly.", "Jarvis 设置配置有误。");
            case INCOMPATIBLE_RESPONSE:
                return t("This server returned an unsupported response.", "服务器返回了不兼容的结果。");
            case UNAVAILABLE:
            default:
                return t("Jarvis is unavailable right now.", "Jarvis 暂时不可用。");
        }
    }

    private String localMessage(JarvisLocalModelClient.Failure failure) {
        switch (failure) {
            case PROVIDER_REJECTED:
                return t("The provider rejected the local key. Check it in Jarvis settings.", "供应商拒绝了本机 Key，请在 Jarvis 设置中检查。");
            case RATE_LIMITED:
                return t("The provider is rate-limiting this key. Try again later.", "供应商对该 Key 限流，请稍后再试。");
            case INVALID_CONFIGURATION:
                return t("The local key configuration is invalid.", "本机 Key 配置无效。");
            case INVALID_RESPONSE:
            case UNAVAILABLE:
            default:
                return t("The answer could not be generated. Check your connection and try again.", "无法生成答案，请检查网络后重试。");
        }
    }

    private void presentQuestionDialog() {
        if (getParentActivity() == null) {
            return;
        }
        Context context = getParentActivity();
        EditText input = new EditText(context);
        input.setHint(t("Ask about this chat", "就这个会提问"));
        input.setText(question);
        input.setSingleLine(false);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        int pad = dp(24);
        FrameLayout wrapper = new FrameLayout(context);
        wrapper.setPadding(pad, dp(8), pad, 0);
        wrapper.addView(input, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(t("Ask Jarvis", "问 Jarvis"));
        builder.setView(wrapper);
        builder.setPositiveButton(t("Ask", "提问"), (dialog, which) -> {
            String next = input.getText().toString().trim();
            if (TextUtils.isEmpty(next)) {
                return;
            }
            question = next.length() > 1000 ? next.substring(0, 1000) : next;
            didRequest = false;
            loadAnswer();
        });
        builder.setNegativeButton(t("Cancel", "取消"), null);
        showDialog(builder.create());
    }

    private HeaderCell addHeader(String text, String value) {
        HeaderCell header = new HeaderCell(getParentActivity(), getResourceProvider());
        header.setText(value == null ? text : text + "\n" + value);
        header.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray, getResourceProvider()));
        content.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        return header;
    }

    private TextSettingsCell addValue(String title, String value, boolean divider, View.OnClickListener listener) {
        TextSettingsCell cell = new TextSettingsCell(getParentActivity(), getResourceProvider());
        cell.setTextAndValue(title, value, divider);
        cell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite, getResourceProvider()));
        cell.setOnClickListener(listener);
        content.addView(cell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        return cell;
    }

    private TextSettingsCell addAction(String title, boolean destructive, View.OnClickListener listener) {
        TextSettingsCell cell = new TextSettingsCell(getParentActivity(), getResourceProvider());
        cell.setText(title, true);
        cell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite, getResourceProvider()));
        if (destructive) {
            cell.setTextColor(Theme.getColor(Theme.key_text_RedRegular, getResourceProvider()));
        }
        cell.setOnClickListener(listener);
        content.addView(cell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        return cell;
    }

    private TextInfoPrivacyCell addInfo(String text) {
        TextInfoPrivacyCell cell = new TextInfoPrivacyCell(getParentActivity(), getResourceProvider());
        cell.setText(text);
        cell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray, getResourceProvider()));
        content.addView(cell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        return cell;
    }
}
