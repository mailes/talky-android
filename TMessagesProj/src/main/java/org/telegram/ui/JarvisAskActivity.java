package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.jarvis.JarvisLocalCredential;
import org.telegram.messenger.jarvis.JarvisLocalKeyStore;
import org.telegram.messenger.jarvis.JarvisLocalModelClient;
import org.telegram.messenger.jarvis.JarvisManageClient;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.ChatMessageCell;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.ChatActivityEnterView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.SizeNotifierFrameLayout;
import org.telegram.ui.Stories.recorder.PreviewView;

import java.util.ArrayList;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * In-chat local answer, presented as a chat transcript. Opened by /ask in a
 * group or channel; it reads the server-scoped recent excerpts for THAT chat,
 * sends them only to the device-local provider, and shows the server-issued
 * source messages as real chat bubbles. A citation is clickable only when it
 * is one of those IDs; model-invented IDs stay text.
 *
 * The page reuses the chat's own wallpaper and the chat's message cells, and
 * a ChatActivityEnterView at the bottom lets the user ask follow-up questions
 * without sending anything to the chat.
 */
public class JarvisAskActivity extends BaseFragment {

    private static final int STATE_LOADING = 0;
    private static final int STATE_ANSWERED = 1;
    private static final int STATE_FAILED = 2;
    private static final int STATE_CANCELLED = 3;

    private static final int MAX_SOURCES = 24;
    private static final int MAX_SOURCE_CHARS = 1200;
    private static final long REQUEST_TIMEOUT_MS = 120_000L;

    private final long dialogId;
    private final long peerId;
    private String pendingQuestion;
    private long userId;
    private String origin;
    private JarvisLocalKeyStore keyStore;

    private SizeNotifierFrameLayout containerView;
    private ScrollView scrollView;
    private LinearLayout content;
    private ChatActivityEnterView enterView;

    private final ArrayList<Turn> turns = new ArrayList<>();
    private int activeRequestId;
    private int fakeMessageId = 1_000_000;
    private int scrollBottomPadding;
    private View latestQuestionView;
    private boolean destroyed;

    private static final class Turn {
        String question;
        int askedAt;
        int answeredAt;
        String answer;
        ArrayList<SourceItem> sources = new ArrayList<>();
        int state = STATE_LOADING;
        String error;
        boolean showSettings;
    }

    private static final class SourceItem {
        long messageId;
        String sourceId;
        String text;
        boolean truncated;
        TLRPC.Message message;
    }

    private static final class MarkdownText {
        String text;
        ArrayList<TLRPC.MessageEntity> entities = new ArrayList<>();
    }

    public JarvisAskActivity(long dialogId, String question) {
        this.dialogId = dialogId;
        this.peerId = -dialogId;
        this.pendingQuestion = question == null ? "" : question.trim();
    }

    private final ChatMessageCell.ChatMessageCellDelegate cellDelegate = new ChatMessageCell.ChatMessageCellDelegate() {
    };

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

        userId = getUserConfig().getClientUserId();
        origin = getMessagesController().jarvisManageOrigin;
        try {
            keyStore = new JarvisLocalKeyStore(ApplicationLoader.applicationContext, userId);
        } catch (Exception ignored) {
            keyStore = null;
        }

        containerView = new SizeNotifierFrameLayout(context);
        fragmentView = containerView;

        Drawable wallpaper = null;
        try {
            wallpaper = PreviewView.getBackgroundDrawable(null, currentAccount, dialogId, Theme.isCurrentThemeDark());
        } catch (Exception ignored) {
        }
        if (wallpaper == null) {
            wallpaper = Theme.getCachedWallpaper();
        }
        if (wallpaper != null) {
            containerView.setBackground(wallpaper);
        }

        scrollView = new ScrollView(context);
        scrollView.setFillViewport(true);
        scrollView.setClipToPadding(false);
        scrollView.setVerticalScrollBarEnabled(false);
        scrollView.setPadding(0, dp(6), 0, dp(64));
        scrollBottomPadding = dp(64);

        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, 0, 0, dp(8));
        scrollView.addView(content, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        containerView.addView(scrollView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        setupComposer();

        if (TextUtils.isEmpty(pendingQuestion)) {
            rebuild();
        } else {
            String question = pendingQuestion;
            pendingQuestion = null;
            ask(question);
        }
        return fragmentView;
    }

    private String t(String en, String zh) {
        Locale locale = LocaleController.getInstance().getCurrentLocale();
        return locale != null && locale.getLanguage().startsWith("zh") ? zh : en;
    }

    // MARK: - Composer

    private void setupComposer() {
        // isChat=false keeps the attach button out of the composer; the forced
        // send button replaces the voice button, so this stays a text field.
        enterView = new ChatActivityEnterView(getParentActivity(), containerView, null, false);
        enterView.setAllowStickersAndGifs(false, false, false);
        enterView.setForceShowSendButton(true, false);
        enterView.setOverrideHint(t("Ask a follow-up…", "继续追问…"));
        enterView.forceSmoothKeyboard(true);
        enterView.setDelegate(new ChatActivityEnterView.ChatActivityEnterViewDelegate() {
            @Override
            public void onMessageSend(CharSequence message, boolean notify, int scheduleDate, int scheduleRepeatPeriod, long payStars) {
                if (message == null) {
                    return;
                }
                String text = message.toString().trim();
                if (!TextUtils.isEmpty(text)) {
                    ask(text);
                }
            }

            @Override
            public void needSendTyping() {
            }

            @Override
            public void onTextChanged(CharSequence text, boolean bigChange, boolean fromDraft) {
            }

            @Override
            public void onTextSelectionChanged(int start, int end) {
            }

            @Override
            public void onTextSpansChanged(CharSequence text) {
            }

            @Override
            public void onAttachButtonHidden() {
            }

            @Override
            public void onAttachButtonShow() {
            }

            @Override
            public void onWindowSizeChanged(int size) {
            }

            @Override
            public void onStickersTab(boolean opened) {
            }

            @Override
            public void onMessageEditEnd(boolean loading) {
            }

            @Override
            public void didPressAttachButton() {
            }

            @Override
            public void needStartRecordVideo(int state, boolean notify, int scheduleDate, int scheduleRepeatPeriod, int ttl, long effectId, long stars) {
            }

            @Override
            public void toggleVideoRecordingPause() {
            }

            @Override
            public boolean isVideoRecordingPaused() {
                return false;
            }

            @Override
            public void needChangeVideoPreviewState(int state, float seekProgress) {
            }

            @Override
            public void onSwitchRecordMode(boolean video) {
            }

            @Override
            public void onPreAudioVideoRecord() {
            }

            @Override
            public void needStartRecordAudio(int state) {
            }

            @Override
            public void needShowMediaBanHint() {
            }

            @Override
            public void onStickersExpandedChange() {
            }

            @Override
            public void onUpdateSlowModeButton(View button, boolean show, CharSequence time) {
            }

            @Override
            public void onSendLongClick() {
            }

            @Override
            public void onAudioVideoInterfaceUpdated() {
            }
        });
        containerView.addView(enterView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.BOTTOM));
        enterView.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (scrollView == null) {
                return;
            }
            int padding = enterView.getMeasuredHeight() + dp(6);
            if (padding != scrollBottomPadding) {
                scrollBottomPadding = padding;
                scrollView.setPadding(0, dp(6), 0, padding);
            }
        });
    }

    private boolean isLoading() {
        for (int i = 0; i < turns.size(); i++) {
            if (turns.get(i).state == STATE_LOADING) {
                return true;
            }
        }
        return false;
    }

    private void setComposerEnabled(boolean enabled) {
        if (enterView != null && enterView.getEditField() != null) {
            enterView.getEditField().setEnabled(enabled);
        }
    }

    // MARK: - Request

    private void ask(String question) {
        if (destroyed || isLoading()) {
            return;
        }
        String text = question.trim();
        if (TextUtils.isEmpty(text)) {
            return;
        }
        if (text.length() > 1000) {
            text = text.substring(0, 1000);
        }
        Turn turn = new Turn();
        turn.question = text;
        turn.askedAt = now();
        turns.add(turn);
        rebuild(scrollView != null);
        setComposerEnabled(false);
        loadAnswer(turn, ++activeRequestId);
    }

    private void loadAnswer(Turn turn, int requestId) {
        if (TextUtils.isEmpty(origin)) {
            fail(turn, requestId, t("Jarvis management is not configured on this server.", "此服务器没有配置 Jarvis 管理地址。"), false);
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
                ui(() -> fail(turn, requestId, t("This device has no local key. Add one in Jarvis settings, then ask again.", "这台设备没有本机 Key。请先在 Jarvis 设置中添加，然后再提问。"), true));
                return;
            }
            scheduleRequestTimeout(turn, requestId);
            requestAnswer(turn, requestId, credential, 0);
        });
    }

    private void scheduleRequestTimeout(Turn turn, int requestId) {
        // Every HTTP call has its own timeout, but a stalled socket, a ticket
        // that never comes back or a long-provider stall would otherwise leave
        // the transcript on "Thinking…" forever.
        Utilities.globalQueue.postRunnable(() -> ui(() -> {
            if (isCurrent(turn, requestId) && turn.state == STATE_LOADING) {
                fail(turn, requestId, t("Jarvis took too long to answer. Check your connection and try again.", "Jarvis 响应超时，请检查网络后重试。"), false);
            }
        }), REQUEST_TIMEOUT_MS);
    }

    private void requestAnswer(Turn turn, int requestId, JarvisLocalCredential credential, int attempt) {
        final long startedAt = SystemClock.elapsedRealtime();
        getConnectionsManager().requestJarvisSessionTicket(ticket -> {
            FileLog.d("JarvisAsk: ticket " + (TextUtils.isEmpty(ticket) ? "empty" : "ok")
                    + " after " + (SystemClock.elapsedRealtime() - startedAt) + "ms");
            if (ticket == null || ticket.isEmpty()) {
                ui(() -> fail(turn, requestId, t("Your session expired. Sign in again.", "登录状态已失效，请重新登录。"), false));
                return;
            }
            Utilities.globalQueue.postRunnable(() -> {
                try {
                    JarvisManageClient client = new JarvisManageClient(origin);
                    JSONObject context = client.context(ticket, peerId, "");
                    FileLog.d("JarvisAsk: context ok after " + (SystemClock.elapsedRealtime() - startedAt) + "ms");
                    ArrayList<SourceItem> sources = readSources(context);
                    if (sources.isEmpty()) {
                        ui(() -> fail(turn, requestId, t("No readable messages were found in this chat. Jarvis only reads chats you authorize.", "这个会话里没有可读消息。Jarvis 只读取你授权的会话。"), true));
                        return;
                    }
                    StringBuilder prompt = new StringBuilder();
                    for (int i = 0; i < sources.size(); i++) {
                        SourceItem source = sources.get(i);
                        prompt.append("[").append(source.sourceId).append("]");
                        if (source.truncated) {
                            prompt.append(" (excerpt)");
                        }
                        prompt.append(" ").append(source.text).append("\n");
                    }
                    String answer = new JarvisLocalModelClient().generate(credential, prompt.toString(), turn.question);
                    FileLog.d("JarvisAsk: generate ok after " + (SystemClock.elapsedRealtime() - startedAt) + "ms");
                    // Look the excerpts up while still on the worker: the
                    // storage read is synchronous and must not run on the UI
                    // thread for every source.
                    for (int i = 0; i < sources.size(); i++) {
                        SourceItem source = sources.get(i);
                        try {
                            source.message = MessagesStorage.getInstance(currentAccount).getMessage(dialogId, source.messageId);
                        } catch (Exception ignored) {
                            source.message = null;
                        }
                    }
                    ui(() -> completeAnswer(turn, requestId, answer, sources));
                } catch (JarvisManageClient.ManageException error) {
                    FileLog.e("JarvisAsk: manage failure " + error.failure
                            + " after " + (SystemClock.elapsedRealtime() - startedAt) + "ms");
                    if (error.failure == JarvisManageClient.Failure.AUTHENTICATION_REQUIRED && attempt == 0) {
                        // The ticket lives for 60 seconds from the moment it is
                        // minted; a slow or just-resumed connection can spend
                        // that budget before the request lands, so mint a
                        // fresh one and try once more instead of failing.
                        requestAnswer(turn, requestId, credential, 1);
                    } else if (error.failure == JarvisManageClient.Failure.FORBIDDEN) {
                        ui(() -> fail(turn, requestId, t("This chat is not authorized. Open Jarvis settings to authorize it, then ask again.", "这个会话还没有授权。请打开 Jarvis 设置完成授权后再提问。"), true));
                    } else {
                        ui(() -> fail(turn, requestId, message(error.failure), false));
                    }
                } catch (JarvisLocalModelClient.ModelException error) {
                    FileLog.e("JarvisAsk: model failure " + error.failure
                            + " after " + (SystemClock.elapsedRealtime() - startedAt) + "ms");
                    ui(() -> fail(turn, requestId, localMessage(error.failure), false));
                } catch (Exception error) {
                    FileLog.e("JarvisAsk: unexpected failure after " + (SystemClock.elapsedRealtime() - startedAt) + "ms", error);
                    ui(() -> fail(turn, requestId, t("The answer could not be generated. Check your connection and try again.", "无法生成答案，请检查网络后重试。"), false));
                }
            });
        });
    }

    private ArrayList<SourceItem> readSources(JSONObject context) {
        ArrayList<SourceItem> result = new ArrayList<>();
        JSONArray sources = context.optJSONArray("sources");
        if (sources == null) {
            return result;
        }
        for (int i = 0; i < sources.length() && result.size() < MAX_SOURCES; i++) {
            JSONObject source = sources.optJSONObject(i);
            if (source == null) {
                continue;
            }
            String sourceId = source.optString("source_id", "");
            long messageId = source.optLong("message_id", 0);
            if (source.optLong("peer_id", 0) != peerId || messageId <= 0
                    || !sourceId.equals("channel:" + peerId + ":" + messageId)) {
                continue;
            }
            SourceItem item = new SourceItem();
            item.messageId = messageId;
            item.sourceId = sourceId;
            item.truncated = source.optBoolean("truncated", false);
            String text = source.optString("text", "");
            if (text.length() > MAX_SOURCE_CHARS) {
                text = text.substring(0, MAX_SOURCE_CHARS);
                item.truncated = true;
            }
            item.text = text;
            result.add(item);
        }
        return result;
    }

    private void completeAnswer(Turn turn, int requestId, String answer, ArrayList<SourceItem> sources) {
        if (!isCurrent(turn, requestId)) {
            return;
        }
        turn.answer = answer;
        turn.sources = sources;
        turn.answeredAt = now();
        turn.state = STATE_ANSWERED;
        setComposerEnabled(true);
        rebuild(true);
    }

    private void fail(Turn turn, int requestId, String message, boolean showSettings) {
        if (!isCurrent(turn, requestId)) {
            return;
        }
        turn.error = message;
        turn.showSettings = showSettings;
        turn.state = STATE_FAILED;
        setComposerEnabled(true);
        rebuild(true);
    }

    private void cancel(Turn turn) {
        FileLog.d("JarvisAsk: cancel tapped state=" + turn.state);
        if (turn.state != STATE_LOADING) {
            return;
        }
        activeRequestId++;
        turn.state = STATE_CANCELLED;
        setComposerEnabled(true);
        rebuild(true);
    }

    private boolean isCurrent(Turn turn, int requestId) {
        return requestId == activeRequestId && turns.contains(turn);
    }

    private void ui(Runnable action) {
        AndroidUtilities.runOnUIThread(() -> {
            if (!destroyed && stillCurrent()) {
                action.run();
            }
        });
    }

    private boolean stillCurrent() {
        return getUserConfig().getClientUserId() == userId;
    }

    private static int now() {
        return (int) (System.currentTimeMillis() / 1000);
    }

    // MARK: - Transcript

    private void rebuild() {
        rebuild(false);
    }

    private void rebuild(boolean scroll) {
        if (content == null || getParentActivity() == null || destroyed) {
            return;
        }
        content.removeAllViews();
        latestQuestionView = null;
        if (turns.isEmpty()) {
            addCaption(t("Ask a question about this chat. Jarvis uses the chat's recent excerpts as evidence.", "就这个会话提问。Jarvis 会把最近的片段作为依据。"));
        }
        for (int i = 0; i < turns.size(); i++) {
            addTurn(turns.get(i));
        }
        if (scroll && scrollView != null) {
            scrollToLatestQuestion();
        }
    }

    private void addTurn(Turn turn) {
        View questionView = addBubble(syntheticMessage(turn.question, turn.askedAt, userPeer(outgoingFromId()), true, null, false), null);
        if (turn == turns.get(turns.size() - 1)) {
            latestQuestionView = questionView;
        }
        switch (turn.state) {
            case STATE_LOADING:
                addLoading(turn);
                break;
            case STATE_ANSWERED:
                MarkdownText markdown = markdown(turn.answer == null ? "" : turn.answer);
                addBubble(syntheticMessage(markdown.text, turn.answeredAt, userPeer(botFromId()), false, markdown.entities, true), null);
                addSources(turn);
                break;
            case STATE_CANCELLED:
                addCaption(t("Cancelled.", "已取消。"));
                break;
            case STATE_FAILED:
            default:
                addCaption(turn.error == null ? t("Jarvis is unavailable right now.", "Jarvis 暂时不可用。") : turn.error);
                if (turn.showSettings) {
                    addLinkRow(t("Open Jarvis settings", "打开 Jarvis 设置"), () -> presentFragment(new JarvisSettingsActivity(true)));
                }
                addLinkRow(t("Try again", "重试"), () -> retry(turn));
                break;
        }
    }

    private void retry(Turn turn) {
        if (isLoading()) {
            return;
        }
        turn.answer = null;
        turn.error = null;
        turn.showSettings = false;
        turn.state = STATE_LOADING;
        rebuild(true);
        setComposerEnabled(false);
        loadAnswer(turn, ++activeRequestId);
    }

    private void addSources(Turn turn) {
        if (turn.sources == null || turn.sources.isEmpty()) {
            return;
        }
        addCaption(t("Sources (" + turn.sources.size() + ")", "来源（" + turn.sources.size() + "）"));
        for (int i = 0; i < turn.sources.size(); i++) {
            SourceItem source = turn.sources.get(i);
            TLRPC.Message owner = source.message;
            boolean hasRealText = owner != null && !TextUtils.isEmpty(owner.message);
            String text = hasRealText ? owner.message : source.text;
            if (text == null) {
                text = "";
            }
            TLRPC.Peer from = owner != null && owner.from_id != null ? owner.from_id : chatPeer();
            int date = owner != null && owner.date > 0 ? owner.date : now();
            ArrayList<TLRPC.MessageEntity> entities = hasRealText ? owner.entities : null;
            final long messageId = source.messageId;
            addBubble(syntheticMessage(text, date, from, false, entities, false), () -> openSource(messageId));
        }
    }

    private void addLoading(Turn turn) {
        LinearLayout row = new LinearLayout(getParentActivity());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setBackground(Theme.createRoundRectDrawable(dp(12), Theme.getColor(Theme.key_chat_serviceBackground, getResourceProvider())));
        row.setPadding(dp(12), dp(6), dp(12), dp(6));

        TextView label = new TextView(getParentActivity());
        label.setTextSize(14);
        label.setText(t("Thinking…", "思考中…"));
        label.setTextColor(Theme.getColor(Theme.key_chat_serviceText, getResourceProvider()));
        row.addView(label, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

        TextView cancel = new TextView(getParentActivity());
        cancel.setTextSize(14);
        cancel.setText(t("Cancel", "取消"));
        cancel.setTextColor(Theme.getColor(Theme.key_chat_serviceLink, getResourceProvider()));
        cancel.setPadding(dp(14), 0, 0, 0);
        cancel.setOnClickListener(v -> cancel(turn));
        row.addView(cancel, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

        LinearLayout.LayoutParams params = LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL);
        params.topMargin = dp(6);
        params.bottomMargin = dp(4);
        content.addView(row, params);
    }

    private void addCaption(String text) {
        TextView label = new TextView(getParentActivity());
        label.setTextSize(13);
        label.setText(text);
        label.setGravity(Gravity.CENTER);
        label.setTextColor(Theme.getColor(Theme.key_chat_serviceText, getResourceProvider()));
        label.setBackground(Theme.createRoundRectDrawable(dp(12), Theme.getColor(Theme.key_chat_serviceBackground, getResourceProvider())));
        label.setPadding(dp(12), dp(5), dp(12), dp(5));
        LinearLayout.LayoutParams params = LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL);
        params.topMargin = dp(6);
        params.bottomMargin = dp(4);
        content.addView(label, params);
    }

    private void addLinkRow(String text, Runnable action) {
        TextView label = new TextView(getParentActivity());
        label.setTextSize(14);
        label.setText(text);
        label.setGravity(Gravity.CENTER);
        label.setTextColor(Theme.getColor(Theme.key_chat_serviceLink, getResourceProvider()));
        label.setPadding(dp(10), dp(5), dp(10), dp(5));
        label.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams params = LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL);
        params.bottomMargin = dp(2);
        content.addView(label, params);
    }

    private View addBubble(MessageObject message, Runnable onTap) {
        if (message == null || getParentActivity() == null) {
            return null;
        }
        FrameLayout wrapper = new FrameLayout(getParentActivity());
        ChatMessageCell cell;
        if (onTap != null) {
            TappableChatMessageCell tappable = new TappableChatMessageCell(getParentActivity(), currentAccount);
            tappable.setOnTap(onTap);
            cell = tappable;
        } else {
            cell = new ChatMessageCell(getParentActivity(), currentAccount);
        }
        cell.setDelegate(cellDelegate);
        cell.isChat = true;
        cell.setFullyDraw(true);
        cell.setMessageObject(message, null, false, false, false);
        wrapper.addView(cell, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        wrapper.setPadding(0, dp(2), 0, dp(2));
        content.addView(wrapper, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        return wrapper;
    }

    private MessageObject syntheticMessage(String text, int date, TLRPC.Peer from, boolean outgoing, ArrayList<TLRPC.MessageEntity> entities, boolean jarvis) {
        TLRPC.TL_message message = new TLRPC.TL_message();
        message.id = ++fakeMessageId;
        message.message = text;
        message.date = date <= 0 ? now() : date;
        message.dialog_id = dialogId;
        message.peer_id = chatPeer();
        message.from_id = from != null ? from : chatPeer();
        message.out = outgoing;
        message.flags = TLRPC.MESSAGE_FLAG_HAS_FROM_ID;
        message.media = new TLRPC.TL_messageMediaEmpty();
        if (entities != null && !entities.isEmpty()) {
            message.entities = entities;
        }
        MessageObject object = new MessageObject(currentAccount, message, true, false);
        if (jarvis) {
            object.customName = t("Jarvis", "Jarvis");
            AvatarDrawable avatar = new AvatarDrawable();
            avatar.setInfo(botFromId(), "Jarvis", null);
            object.forceAvatar = true;
            object.customAvatarDrawable = avatar;
        }
        return object;
    }

    private long outgoingFromId() {
        return userId;
    }

    private long botFromId() {
        return JarvisLocalCredential.BOT_USER_ID;
    }

    private TLRPC.Peer chatPeer() {
        TLRPC.TL_peerChannel peer = new TLRPC.TL_peerChannel();
        peer.channel_id = peerId;
        return peer;
    }

    private static TLRPC.Peer userPeer(long id) {
        TLRPC.TL_peerUser peer = new TLRPC.TL_peerUser();
        peer.user_id = id;
        return peer;
    }

    private void openSource(long messageId) {
        if (messageId > 0 && messageId <= Integer.MAX_VALUE) {
            presentFragment(ChatActivity.of(dialogId, (int) messageId));
        }
    }

    private void scrollToLatestQuestion() {
        final View target = latestQuestionView;
        if (scrollView == null || target == null) {
            return;
        }
        scrollView.post(() -> {
            if (scrollView == null || target.getParent() == null) {
                return;
            }
            int offset = target.getTop() - dp(2);
            scrollView.smoothScrollTo(0, Math.max(0, offset));
        });
    }

    private static class TappableChatMessageCell extends ChatMessageCell {

        private float downX;
        private float downY;
        private long downTime;
        private boolean moved;
        private Runnable onTap;

        TappableChatMessageCell(Context context, int currentAccount) {
            super(context, currentAccount);
        }

        void setOnTap(Runnable onTap) {
            this.onTap = onTap;
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            super.onTouchEvent(event);
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getX();
                    downY = event.getY();
                    downTime = event.getEventTime();
                    moved = false;
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (Math.abs(event.getX() - downX) > dp(8) || Math.abs(event.getY() - downY) > dp(8)) {
                        moved = true;
                    }
                    break;
                case MotionEvent.ACTION_UP:
                    if (!moved && onTap != null && event.getEventTime() - downTime < 400) {
                        onTap.run();
                    }
                    break;
                default:
                    break;
            }
            // A preview cell usually swallows no touch of its own, which would
            // leave the gesture with the ScrollView and never deliver ACTION_UP
            // here. Keeping the stream lets a plain tap reach this detector;
            // the ScrollView can still take over a drag through interception.
            return true;
        }
    }

    // MARK: - Markdown

    private static final Pattern MARKDOWN_HEADING = Pattern.compile("(?m)^[ \\t]*#{1,6}[ \\t]+");
    private static final Pattern MARKDOWN_TOKEN = Pattern.compile("\\*\\*(.+?)\\*\\*|`([^`\\n]+)`|(?<!\\*)\\*([^*\\n]+)\\*(?!\\*)", Pattern.DOTALL);

    /**
     * ChatMessageCell draws its text from message entities, not from a
     * Spannable, so the three markers that appear in answers (bold, inline
     * code, italic) are turned into entities here. Heading hashes only add
     * noise once the line is plain text, so they are dropped.
     */
    private static MarkdownText markdown(String source) {
        MarkdownText result = new MarkdownText();
        String text = source == null ? "" : MARKDOWN_HEADING.matcher(source).replaceAll("");
        Matcher matcher = MARKDOWN_TOKEN.matcher(text);
        StringBuilder builder = new StringBuilder(text.length());
        ArrayList<TLRPC.MessageEntity> entities = new ArrayList<>();
        int cursor = 0;
        while (matcher.find()) {
            builder.append(text, cursor, matcher.start());
            int group;
            TLRPC.MessageEntity entity;
            if (matcher.group(1) != null) {
                group = 1;
                entity = new TLRPC.TL_messageEntityBold();
            } else if (matcher.group(2) != null) {
                group = 2;
                entity = new TLRPC.TL_messageEntityCode();
            } else {
                group = 3;
                entity = new TLRPC.TL_messageEntityItalic();
            }
            String inner = matcher.group(group);
            if (inner == null || inner.isEmpty()) {
                builder.append(text, matcher.start(), matcher.end());
            } else {
                entity.offset = builder.length();
                entity.length = inner.length();
                entities.add(entity);
                builder.append(inner);
            }
            cursor = matcher.end();
        }
        builder.append(text, cursor, text.length());
        result.text = builder.toString();
        result.entities = entities;
        return result;
    }

    // MARK: - Errors

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
                return t("This server returned an unsupported result.", "服务器返回了不兼容的结果。");
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

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        destroyed = true;
        activeRequestId++;
        if (enterView != null) {
            enterView.onDestroy();
            enterView = null;
        }
        scrollView = null;
        content = null;
        containerView = null;
    }
}
