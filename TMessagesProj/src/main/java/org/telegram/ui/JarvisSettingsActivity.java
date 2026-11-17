package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.jarvis.JarvisLocalCredential;
import org.telegram.messenger.jarvis.JarvisLocalKeyStore;
import org.telegram.messenger.jarvis.JarvisManageClient;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.LayoutHelper;

import java.util.Locale;

/**
 * Jarvis settings, reached from the @jarvis profile row and from /setkey.
 *
 * Account mode is server state; the local key is device state. This screen
 * never uploads a local key and never silently changes the account mode when
 * a key is added or removed.
 */
public class JarvisSettingsActivity extends BaseFragment {

    private LinearLayout content;
    private ScrollView scrollView;
    private View deviceKeyRow;
    private final boolean focusKeys;
    private boolean didFocusKeys;
    private long userId;
    private String origin;
    private JarvisLocalKeyStore keyStore;

    private boolean loading = true;
    private String errorText;
    private JSONObject configuration;
    private JSONObject capabilities;
    private JSONObject overview;
    private JarvisLocalCredential deviceKey;
    private boolean deviceKeyUnavailable;
    private boolean pendingLocalModeOffer;

    public JarvisSettingsActivity() {
        this(false);
    }

    public JarvisSettingsActivity(boolean focusKeys) {
        this.focusKeys = focusKeys;
    }

    @Override
    public View createView(Context context) {
        setHasOwnBackground(true);

        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle(LocaleController.getString(R.string.JarvisSettings));
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
        loadState();
        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        // Refresh after the local-model editor (or any child screen) returns.
        if (!loading && content != null) {
            loadState();
        }
    }

    private String t(String en, String zh) {
        Locale locale = LocaleController.getInstance().getCurrentLocale();
        return locale != null && locale.getLanguage().startsWith("zh") ? zh : en;
    }

    /** The account's model source, or null until the configuration arrives. */
    private String mode() {
        return configuration != null ? configuration.optString("mode", "platform") : null;
    }

    private boolean hostedKeyPresent() {
        return configuration != null && configuration.optBoolean("hosted_key_present", false);
    }

    private void loadState() {
        loading = true;
        errorText = null;
        rebuild();
        if (TextUtils.isEmpty(origin)) {
            loading = false;
            errorText = t("Jarvis management is not configured on this server.", "此服务器没有配置 Jarvis 管理地址。");
            rebuild();
            return;
        }
        try {
            new JarvisManageClient(origin);
        } catch (Exception ignored) {
            loading = false;
            errorText = t("Jarvis settings are unavailable.", "Jarvis 设置暂时不可用。");
            rebuild();
            return;
        }
        getConnectionsManager().requestJarvisSessionTicket(ticket -> {
            if (ticket == null || ticket.isEmpty()) {
                runOnUIThreadIfCurrent(() -> {
                    loading = false;
                    errorText = t("Your session expired. Sign in again.", "登录状态已失效，请重新登录。");
                    rebuild();
                });
                return;
            }
            Utilities.globalQueue.postRunnable(() -> {
                JSONObject loadedConfiguration = null;
                JSONObject loadedCapabilities = null;
                JSONObject loadedOverview = null;
                String failure = null;
                try {
                    JarvisManageClient client = new JarvisManageClient(origin);
                    loadedConfiguration = client.configuration(ticket);
                    try {
                        loadedCapabilities = client.capabilities(ticket);
                    } catch (Exception ignored) {
                    }
                    try {
                        loadedOverview = client.overview(ticket);
                    } catch (Exception error) {
                        // The rest of the screen still works without it; the
                        // log is what tells us why usage/chats are missing.
                        FileLog.e("jarvis: overview failed", error);
                    }
                } catch (JarvisManageClient.ManageException error) {
                    failure = message(error.failure);
                } catch (Exception ignored) {
                    failure = t("Jarvis is unavailable right now.", "Jarvis 暂时不可用。");
                }
                JarvisLocalCredential loadedDeviceKey = null;
                boolean unavailable = false;
                try {
                    loadedDeviceKey = keyStore != null ? keyStore.load() : null;
                } catch (Exception ignored) {
                    unavailable = true;
                }
                final JSONObject finalConfiguration = loadedConfiguration;
                final JSONObject finalCapabilities = loadedCapabilities;
                final JSONObject finalOverview = loadedOverview;
                final String finalFailure = failure;
                final JarvisLocalCredential finalDeviceKey = loadedDeviceKey;
                final boolean finalUnavailable = unavailable;
                runOnUIThreadIfCurrent(() -> {
                    loading = false;
                    configuration = finalConfiguration;
                    capabilities = finalCapabilities;
                    overview = finalOverview;
                    errorText = finalFailure;
                    deviceKey = finalDeviceKey;
                    deviceKeyUnavailable = finalUnavailable;
                    rebuild();
                    if (pendingLocalModeOffer && finalDeviceKey != null) {
                        pendingLocalModeOffer = false;
                        offerLocalMode();
                    }
                });
            });
        });
    }

    private String message(JarvisManageClient.Failure failure) {
        switch (failure) {
            case AUTHENTICATION_REQUIRED:
                return t("Your session expired. Sign in again.", "登录状态已失效，请重新登录。");
            case FORBIDDEN:
                return t("This account cannot manage Jarvis settings.", "当前账号不能管理 Jarvis 设置。");
            case HOSTED_KEY_REQUIRED:
                return t("Add a hosted key before selecting hosted mode.", "请先添加托管 Key，再选择托管模式。");
            case OPERATION_FAILED:
                return t("The provider rejected that key. Check the key and model.", "服务商拒绝了这个 Key，请核对 Key 与模型名。");
            case INCOMPATIBLE_RESPONSE:
                return t("This server returned an unsupported response.", "服务器返回了不兼容的结果。");
            case INVALID_CONFIGURATION:
                return t("Enter a model and an API key.", "请填写模型名和 API Key。");
            case UNAVAILABLE:
            default:
                return t("Jarvis is unavailable right now.", "Jarvis 暂时不可用。");
        }
    }

    private void showError(JarvisManageClient.Failure failure) {
        showAlert(LocaleController.getString(R.string.JarvisSettings), message(failure));
    }

    private void showAlert(String title, String text) {
        if (getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(title);
        builder.setMessage(text);
        builder.setPositiveButton(t("OK", "好"), null);
        showDialog(builder.create());
    }

    // MARK: - Rows

    private void rebuild() {
        if (content == null || getParentActivity() == null) {
            return;
        }
        content.removeAllViews();
        deviceKeyRow = null;
        if (loading) {
            addInfo(t("Loading Jarvis settings…", "正在加载 Jarvis 设置…"));
            return;
        }
        if (errorText != null) {
            addInfo(errorText);
        }

        addHeader(t("Model source", "模型来源"));
        addValue(t("Current source", "当前来源"), modeLabel(), true, v -> presentModePicker());
        addInfo(t("The model source is account-wide. A local key belongs to this device only; other devices need their own.", "模型来源是账号级设置。本机 Key 只属于这台设备，其他设备需要各自配置。"));

        addHeader(t("This device", "本设备"));
        String deviceLabel;
        if (deviceKey != null) {
            // Keep the row short: TextSettingsCell gives a long value most of the
            // width and would ellipsize "Local key" down to a few characters.
            deviceLabel = providerName(deviceKey.provider) + " · " + deviceKey.model;
        } else if (deviceKeyUnavailable) {
            deviceLabel = t("Unavailable", "不可用");
        } else {
            deviceLabel = t("Not configured", "未配置");
        }
        deviceKeyRow = addValue(t("Local key", "本机 Key"), deviceLabel, true, v -> presentDeviceKeyFlow());
        if ("local_byok".equals(mode()) && deviceKey == null) {
            addInfo(t("This account is in local mode, but this device has no key. Local inference cannot run here until you add one.", "当前账号是本机模式，但这台设备没有 Key。添加 Key 之前，本机推理无法运行。"));
        } else {
            addInfo(t("Stored in this device's secure storage. It is never sent to Talky.", "保存在这台设备的安全存储中，不会发往 Talky。"));
        }

        addHeader(t("Hosted key", "托管 Key"));
        addValue(t("Hosted key", "托管 Key"), hostedKeyPresent() ? t("Configured", "已配置") : t("Not configured", "未配置"), true, v -> presentHostedKeyFlow());
        if (hostedKeyPresent()) {
            addAction(t("Delete hosted key", "删除托管 Key"), true, v -> confirmDeleteHostedKey());
        }
        addInfo(t("Encrypted on Talky servers and used for server-side calls. Deleting it does not change the account's model source.", "加密保存在 Talky 服务器，用于服务端调用。删除它不会改变账号的模型来源。"));

        if (overview != null) {
            addHeader(t("Usage today", "今日用量"));
            boolean premium = overview.optBoolean("premium", false);
            addValue(t("Chat turns", "对话轮次"), usageValue(premium, "chat_turns", "chat_limit"), true, null);
            addValue(t("Summaries", "摘要"), usageValue(premium, "summaries", "summary_limit"), false, null);
            if (premium) {
                addInfo(t("Premium has no daily limit. Counters reset at 00:00 UTC.", "Premium 没有每日限额。计数在 UTC 0 点重置。"));
            } else {
                addInfo(t("Free accounts get these turns and summaries each day; counters reset at 00:00 UTC. Premium and your own key do not use them.", "免费账号每天获得这些对话轮次与摘要次数，计数在 UTC 0 点重置。Premium 和自带 Key 不占用这些额度。"));
            }
        }

        addHeader(t("Authorized chats", "已授权会话"));
        JSONArray chats = overview != null ? overview.optJSONArray("chats") : null;
        if (chats != null) {
            for (int i = 0; i < chats.length(); i++) {
                JSONObject chat = chats.optJSONObject(i);
                if (chat == null) {
                    continue;
                }
                long peerId = chat.optLong("peer_id", 0);
                String title = chat.optString("title", "#" + peerId);
                boolean left = chat.optBoolean("left", false);
                addValue(title, left ? t("Left", "已离开") : "", i != chats.length() - 1, v -> confirmRevoke(peerId, title));
            }
        }
        addAction(t("Authorize a chat", "授权一个会话"), false, v -> presentAvailableChats());
        if (overview == null) {
            addInfo(t("Could not load your authorized chats. Reopen this screen to try again.", "无法加载已授权会话，请重新进入此页面重试。"));
        } else if (chats == null || chats.length() == 0) {
            addInfo(t("No chats are authorized yet. Jarvis can only read chats you authorize here or with /grant.", "还没有授权任何会话。Jarvis 只能读取你在这里或用 /grant 授权的会话。"));
        } else {
            addInfo(t("Jarvis reads through your membership. Leaving a group or revoking the grant removes access immediately.", "Jarvis 通过你的成员身份读取。退群或撤销授权会立即失去访问。"));
        }

        addHeader(t("What this source can do", "该来源支持的能力"));
        String currentMode = mode();
        if (currentMode == null) {
            // Never describe platform capabilities before the source is known.
            addInfo(t("Capabilities appear once the model source is loaded.", "模型来源加载完成后会显示支持的能力。"));
        } else {
            switch (currentMode) {
                case "hosted_byok":
                    addInfo(t("Text and summaries use your hosted key on Talky servers. Voice is unavailable with a personal key.", "文本和摘要使用你的托管 Key，在 Talky 服务器调用。个人 Key 下语音不可用。"));
                    break;
                case "local_byok":
                    addInfo(t("Text answers run on this device. Reminders, voice and server-side tools are not available in local mode.", "文本回答在这台设备上运行。本机模式下提醒、语音和服务端工具不可用。"));
                    break;
                default:
                    addInfo(t("Text, summaries, reminders and voice use the platform service. Answers are generated on Talky servers.", "文本、摘要、提醒和语音使用平台服务，回答在 Talky 服务器生成。"));
                    break;
            }
        }

        if (focusKeys && !didFocusKeys && deviceKeyRow != null && scrollView != null) {
            didFocusKeys = true;
            scrollView.post(() -> scrollView.smoothScrollTo(0, Math.max(0, deviceKeyRow.getTop() - dp(56))));
        }
    }

    /** "used / limit" for a free account, or the unlimited marker for Premium. */
    private String usageValue(boolean premium, String usedKey, String limitKey) {
        if (premium) {
            return t("Unlimited", "不限");
        }
        int used = overview != null ? overview.optInt(usedKey, 0) : 0;
        int limit = overview != null ? overview.optInt(limitKey, 0) : 0;
        return used + " / " + limit;
    }

    private String modeLabel() {
        String mode = mode();
        if (mode == null) {
            return t("Loading…", "加载中…");
        }
        switch (mode) {
            case "hosted_byok":
                return t("Hosted key", "托管 Key");
            case "local_byok":
                return t("This device", "本机");
            default:
                return t("Platform model", "平台模型");
        }
    }

    private HeaderCell addHeader(String text) {
        HeaderCell header = new HeaderCell(getParentActivity(), getResourceProvider());
        header.setText(text);
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

    // MARK: - Mode

    private void presentModePicker() {
        if (getParentActivity() == null) {
            return;
        }
        final String current = mode();
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(t("Model source", "模型来源"));
        builder.setItems(new CharSequence[]{
                modeItem(t("Platform model", "平台模型"), "platform".equals(current)),
                modeItem(t("Hosted key", "托管 Key"), "hosted_byok".equals(current)),
                modeItem(t("This device", "本机"), "local_byok".equals(current))
        }, (dialog, which) -> {
            if (which == 0) {
                if (!"platform".equals(current)) {
                    selectMode(JarvisManageClient.Mode.PLATFORM);
                }
            } else if (which == 1) {
                if (!"hosted_byok".equals(current)) {
                    selectMode(JarvisManageClient.Mode.HOSTED);
                }
            } else if (!"local_byok".equals(current)) {
                selectMode(JarvisManageClient.Mode.LOCAL);
            }
        });
        showDialog(builder.create());
    }

    private CharSequence modeItem(String label, boolean selected) {
        return selected ? label + " ✓" : label;
    }

    private void selectMode(JarvisManageClient.Mode target) {
        if (target == JarvisManageClient.Mode.HOSTED && !hostedKeyPresent()) {
            showAlert(t("Hosted key required", "需要托管 Key"), t("Add a hosted key first, then choose hosted mode.", "请先添加托管 Key，再选择托管模式。"));
            return;
        }
        withTicket(ticket -> Utilities.globalQueue.postRunnable(() -> {
            try {
                new JarvisManageClient(origin).select(ticket, target);
                runOnUIThreadIfCurrent(this::loadState);
            } catch (JarvisManageClient.ManageException error) {
                runOnUIThreadIfCurrent(() -> showError(error.failure));
            } catch (Exception ignored) {
                runOnUIThreadIfCurrent(() -> showError(JarvisManageClient.Failure.UNAVAILABLE));
            }
        }));
    }

    // MARK: - Local key

    private void presentDeviceKeyFlow() {
        if (deviceKey != null) {
            AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
            builder.setItems(new CharSequence[]{
                    t("Edit local model", "编辑本机模型"),
                    t("Delete local key", "删除本机 Key")
            }, (dialog, which) -> {
                if (which == 0) {
                    presentFragment(new JarvisLocalKeyActivity(deviceKey));
                } else {
                    confirmDeleteLocalKey();
                }
            });
            showDialog(builder.create());
        } else {
            pendingLocalModeOffer = true;
            presentFragment(new JarvisLocalKeyActivity(null));
        }
    }

    private String providerName(JarvisLocalCredential.Provider provider) {
        switch (provider) {
            case OPENAI:
                return "OpenAI";
            case DEEPSEEK:
                return "DeepSeek";
            case ANTHROPIC:
                return "Anthropic";
            case GEMINI:
                return "Gemini";
            case CUSTOM:
            default:
                return t("Custom", "自定义");
        }
    }

    private void presentProviderPicker() {
        if (getParentActivity() == null) {
            return;
        }
        final String[] providers = {"deepseek", "openai", "gemini", "anthropic"};
        final String[] names = {"DeepSeek", "OpenAI", "Gemini", "Anthropic"};
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(t("Choose a hosted provider", "选择托管供应商"));
        builder.setItems(names, (dialog, which) -> presentKeyDialog(providers[which], names[which]));
        showDialog(builder.create());
    }

    private String defaultModel(String provider) {
        switch (provider) {
            case "openai":
                return "gpt-4o-mini";
            case "gemini":
                return "gemini-2.0-flash";
            case "anthropic":
                return "claude-3-5-haiku-latest";
            case "deepseek":
            default:
                return "deepseek-chat";
        }
    }

    private void presentKeyDialog(String provider, String providerName) {
        if (getParentActivity() == null) {
            return;
        }
        Context context = getParentActivity();
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(24), dp(8), dp(24), 0);

        EditText modelField = new EditText(context);
        modelField.setHint(t("Model", "模型"));
        modelField.setText(defaultModel(provider));
        modelField.setSingleLine(true);
        modelField.setTextSize(16);
        layout.addView(modelField, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        EditText keyField = new EditText(context);
        keyField.setHint(t("API key", "API Key"));
        keyField.setSingleLine(true);
        keyField.setTextSize(16);
        keyField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        layout.addView(keyField, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(t("Hosted key", "托管 Key"));
        builder.setMessage(t("The key is encrypted on Talky servers and used for server-side calls.", "Key 加密保存在 Talky 服务器，用于服务端调用。"));
        builder.setView(layout);
        builder.setPositiveButton(t("Save", "保存"), (dialog, which) -> {
            String model = modelField.getText().toString();
            String key = keyField.getText().toString();
            saveHostedKey(provider, model, key, providerName);
        });
        builder.setNegativeButton(t("Cancel", "取消"), null);
        showDialog(builder.create());
    }

    private void offerLocalMode() {
        if ("local_byok".equals(mode()) || getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(t("Local key saved", "本机 Key 已保存"));
        builder.setMessage(t("This account is still using another model source. Switch this account to local mode?", "当前账号仍在使用其他模型来源。要把这个账号切换到本机模式吗？"));
        builder.setPositiveButton(t("Use local mode", "使用本机模式"), (dialog, which) -> selectMode(JarvisManageClient.Mode.LOCAL));
        builder.setNegativeButton(t("Not now", "暂不"), null);
        showDialog(builder.create());
    }

    private void confirmDeleteLocalKey() {
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(t("Delete local key?", "删除本机 Key？"));
        builder.setMessage(t("The key is removed from this device. The account's model source does not change.", "Key 会从这台设备删除；账号的模型来源不会改变。"));
        builder.setPositiveButton(t("Delete", "删除"), (dialog, which) -> Utilities.globalQueue.postRunnable(() -> {
            try {
                if (keyStore != null) {
                    keyStore.delete();
                }
                runOnUIThreadIfCurrent(() -> {
                    deviceKey = null;
                    deviceKeyUnavailable = false;
                    rebuild();
                });
            } catch (Exception ignored) {
                runOnUIThreadIfCurrent(() -> showAlert(LocaleController.getString(R.string.JarvisSettings), t("The key could not be deleted.", "无法删除这个 Key。")));
            }
        }));
        builder.setNegativeButton(t("Cancel", "取消"), null);
        showDialog(builder.create());
    }

    // MARK: - Hosted key

    private void presentHostedKeyFlow() {
        if (hostedKeyPresent()) {
            AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
            builder.setItems(new CharSequence[]{
                    t("Replace hosted key", "替换托管 Key"),
                    t("Delete hosted key", "删除托管 Key")
            }, (dialog, which) -> {
                if (which == 0) {
                    presentProviderPicker();
                } else {
                    confirmDeleteHostedKey();
                }
            });
            showDialog(builder.create());
        } else {
            presentProviderPicker();
        }
    }

    private void saveHostedKey(String provider, String model, String key, String providerName) {
        withTicket(ticket -> Utilities.globalQueue.postRunnable(() -> {
            try {
                new JarvisManageClient(origin).hostedKey(ticket, provider, model, key);
                runOnUIThreadIfCurrent(() -> {
                    loadState();
                    showAlert(t("Key saved", "Key 已保存"), t(providerName + " is now the hosted provider for this account.", providerName + " 现在是这个账号的托管供应商。"));
                });
            } catch (JarvisManageClient.ManageException error) {
                runOnUIThreadIfCurrent(() -> showError(error.failure));
            } catch (Exception ignored) {
                runOnUIThreadIfCurrent(() -> showError(JarvisManageClient.Failure.UNAVAILABLE));
            }
        }));
    }

    private void confirmDeleteHostedKey() {
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(t("Delete hosted key?", "删除托管 Key？"));
        builder.setMessage(t("The encrypted key is removed from Talky servers. The account's model source does not change.", "加密 Key 会从 Talky 服务器删除；账号的模型来源不会改变。"));
        builder.setPositiveButton(t("Delete", "删除"), (dialog, which) -> withTicket(ticket -> Utilities.globalQueue.postRunnable(() -> {
            try {
                new JarvisManageClient(origin).deleteHostedKey(ticket);
                runOnUIThreadIfCurrent(this::loadState);
            } catch (JarvisManageClient.ManageException error) {
                runOnUIThreadIfCurrent(() -> showError(error.failure));
            } catch (Exception ignored) {
                runOnUIThreadIfCurrent(() -> showError(JarvisManageClient.Failure.UNAVAILABLE));
            }
        })));
        builder.setNegativeButton(t("Cancel", "取消"), null);
        showDialog(builder.create());
    }

    // MARK: - Authorized chats

    private void presentAvailableChats() {
        JSONArray available = overview != null ? overview.optJSONArray("available") : null;
        if (available == null || available.length() == 0) {
            showAlert(LocaleController.getString(R.string.JarvisSettings), t("There are no chats left to authorize. Join a group first.", "没有可授权的会话了，请先加入一个群组。"));
            return;
        }
        final long[] peerIds = new long[available.length()];
        CharSequence[] titles = new CharSequence[available.length()];
        for (int i = 0; i < available.length(); i++) {
            JSONObject chat = available.optJSONObject(i);
            peerIds[i] = chat != null ? chat.optLong("peer_id", 0) : 0;
            titles[i] = chat != null ? chat.optString("title", "#" + peerIds[i]) : "";
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(t("Authorize a chat", "授权一个会话"));
        builder.setItems(titles, (dialog, which) -> grant(peerIds[which]));
        showDialog(builder.create());
    }

    private void grant(long peerId) {
        withTicket(ticket -> Utilities.globalQueue.postRunnable(() -> {
            try {
                new JarvisManageClient(origin).grant(ticket, peerId);
                runOnUIThreadIfCurrent(this::loadState);
            } catch (JarvisManageClient.ManageException error) {
                runOnUIThreadIfCurrent(() -> showError(error.failure));
            } catch (Exception ignored) {
                runOnUIThreadIfCurrent(() -> showError(JarvisManageClient.Failure.UNAVAILABLE));
            }
        }));
    }

    private void confirmRevoke(long peerId, String title) {
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(t("Revoke access?", "撤销授权？"));
        builder.setMessage(t("Jarvis will no longer read \"" + title + "\". The content already in this chat is not deleted.", "Jarvis 将不再读取「" + title + "」。聊天中已有的内容不会被删除。"));
        builder.setPositiveButton(t("Revoke", "撤销"), (dialog, which) -> withTicket(ticket -> Utilities.globalQueue.postRunnable(() -> {
            try {
                new JarvisManageClient(origin).revoke(ticket, peerId);
                runOnUIThreadIfCurrent(this::loadState);
            } catch (JarvisManageClient.ManageException error) {
                runOnUIThreadIfCurrent(() -> showError(error.failure));
            } catch (Exception ignored) {
                runOnUIThreadIfCurrent(() -> showError(JarvisManageClient.Failure.UNAVAILABLE));
            }
        })));
        builder.setNegativeButton(t("Cancel", "取消"), null);
        showDialog(builder.create());
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

    private void withTicket(Utilities.Callback<String> callback) {
        getConnectionsManager().requestJarvisSessionTicket(ticket -> {
            if (ticket == null || ticket.isEmpty()) {
                runOnUIThreadIfCurrent(() -> showError(JarvisManageClient.Failure.AUTHENTICATION_REQUIRED));
                return;
            }
            callback.run(ticket);
        });
    }
}
